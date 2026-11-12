package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * coc3 权益市场画布活动 / coc web6 打卡活动的执行引擎。
 *
 * <p>这几类活动不是 qwhdhub 的明文接口：页面由「画布」渲染器按服务端下发的
 * 页面配置动态拼出来，签到、翻牌、抽奖都是页面里的组件在发请求，
 * 请求头还带页面 WASM 生成的 {@code wasmParams} / {@code phone} 风控参数。
 * Java 层拼不出这些参数，所以统一走「离屏页 + 页面内调用」：
 *
 * <ol>
 *   <li>页面已经由 {@link TaskRunner} 用 App 自己的内核打开，登录态、Cookie、
 *       风控脚本都是现成的；</li>
 *   <li>注入脚本后先挂 XHR / fetch 钩子，页面自己发出的请求和响应原样留证；</li>
 *   <li>能找到签到 / 抽奖组件方法就直接调（方法内部走页面请求层，等价于发请求）；</li>
 *   <li>组件方法找不到时，用页面自己的 axios 实例发已知的 cmpp 接口；</li>
 *   <li>最后才退到点页面上的「签到 / 抽奖」按钮 —— 只在方法、接口都不可用时用，
 *       并在明细里写明是点击兜底，不把点击伪装成接口成功。</li>
 * </ol>
 *
 * <p>判定只看证据：接口响应里的 code、页面文案里从不含「已签」变成含「已签」、
 * 抽奖返回的中奖记录。没有证据就判失败或无需操作，绝不写「成功」。
 */
final class CanvasTask {

    static final int KIND_DAILY7 = 1;
    static final int KIND_LUCKY3 = 3;
    static final int KIND_WEEKLY = 4;

    /** 幸运三日签的三个奖池：累签第 2 天、第 3 天、每满 3 天。 */
    private static final int[] IDS_LUCKY3 = {17788, 17789, 17790};
    /** 周周有权益（周周有好礼）奖池。 */
    private static final int[] IDS_WEEKLY = {17765};

    private CanvasTask() {
    }

    static String nameOf(int kind) {
        if (kind == KIND_DAILY7) {
            return "7日打卡分百万";
        }
        if (kind == KIND_LUCKY3) {
            return "幸运三日签";
        }
        return "周周有好礼";
    }

    static TaskRunner.Result run(int kind, String url) {
        StringBuilder detail = new StringBuilder();
        String name = nameOf(kind);
        WebPage page = WebSession.current();
        detail.append("入口: ").append(brief(url)).append('\n');
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail(name + " · 活动页没起来",
                    detail.toString(),
                    "离屏 WebView 没能加载活动页。重开一次中国移动 App 再试。");
        }

        if (kind == KIND_WEEKLY) {
            // 周周有好礼的「去逛逛」是实打实的浏览任务：页面点一下会跳到专题页，
            // 5 秒门槛，服务端按落地页停留计时。11-11 日志实证点「逛19.9元特价专区」
            // 后 residueLotteryCount 从 2 变 3，所以先做任务再去抽。
            runWeeklyTasks(page, url, detail);
        }
        String script = script(kind);
        page.eval(script, 8_000L);
        String raw = null;
        long deadline = System.currentTimeMillis() + 100_000L;
        while (System.currentTimeMillis() < deadline) {
            String value = page.eval(
                    "(function(){var v=window.__cmcc_canvas;return v?String(v):'';})()", 6_000L);
            if (value != null && !value.isEmpty()) {
                raw = value;
                break;
            }
            sleep(500L);
        }
        if (raw == null) {
            return TaskRunner.Result.fail(name + " · 页面脚本超时",
                    detail.append("脚本 100 秒没有回报\n").toString(),
                    "页面能打开但没有回报。把完整日志发我。");
        }

        JSONObject out;
        try {
            out = new JSONObject(raw);
        } catch (Throwable e) {
            return TaskRunner.Result.fail(name + " · 结果解析失败",
                    detail.append("回报: ").append(brief(raw)).toString(), "");
        }

        JSONArray lines = out.optJSONArray("lines");
        writeLines(name, lines);
        // v3.87 P0：页面脚本按「提交类 + 业务成功」筛出的真回执；查询接口不算。
        JSONArray submits = out.optJSONArray("submits");
        int submitN = submits == null ? 0 : submits.length();
        // v3.90：服务端明确回「本日已签到」的原始串（空串=没有）。
        String signedAlready = out.optString("signedAlready", "");
        JSONObject metric = out.optJSONObject("metric");
        int metric0 = metric == null ? -1 : metric.optInt("c0", -1);
        int metric1 = metric == null ? -1 : metric.optInt("c1", -1);
        boolean metricFlat = metric0 >= 0 && metric1 >= 0 && metric1 <= metric0;

        String stage = out.optString("stage", "");
        String text0 = out.optString("text", "");
        String text1 = out.optString("text1", "");
        detail.append("页面: ").append(out.optString("title", "")).append('\n');
        detail.append("首屏: ").append(brief(text0)).append('\n');
        if (!text1.isEmpty() && !text1.equals(text0)) {
            detail.append("执行后: ").append(brief(text1)).append('\n');
        }
        detail.append("组件: ").append(out.optInt("comps", 0))
                .append(" 个，请求层: ").append(out.optInt("ax", 0)).append(" 个\n");
        detail.append("回执复核(v3.92): 提交类 ").append(submitN).append(" 条");
        if (metric0 >= 0 && metric1 >= 0) {
            detail.append(" · 累计签到 ").append(metric0).append(" → ").append(metric1)
                    .append(metric1 > metric0 ? "（+1，真签到了）" : "（没变，没签到）");
        }
        detail.append('\n');
        if (!signedAlready.isEmpty()) {
            detail.append("服务端回执: ").append(brief(signedAlready)).append('\n');
        }
        String sdk = out.optString("sdk", "");
        if (!sdk.isEmpty()) {
            detail.append("登录桥: ").append(sdk).append('\n');
        }
        int loginWait = out.optInt("loginWait", 0);
        if (loginWait > 0) {
            detail.append("等登录: ").append(loginWait * 500).append("ms\n");
        }

        if ("no-vue".equals(stage)) {
            // 没有 Vue 根组件：7 日打卡这类 coc web6 页面。脚本已经在页面里
            // 点过真实的「打卡」按钮，这里按回执判定，不再拿文案猜。
            JSONObject dom = out.optJSONObject("dom");
            int domTry = dom == null ? 0 : dom.optInt("try", 0);
            int domOk = dom == null ? 0 : dom.optInt("ok", 0);
            String domBefore = dom == null ? "" : dom.optString("before", "");
            String domAfter = dom == null ? "" : dom.optString("after", "");
            String domHit = dom == null ? "" : dom.optString("hits", "");
            if (!domBefore.isEmpty()) {
                detail.append("打卡前: ").append(brief(domBefore)).append('\n');
            }
            if (!domAfter.isEmpty()) {
                detail.append("打卡后: ").append(brief(domAfter)).append('\n');
            }
            if (domOk > 0) {
                if (submitN <= 0) {
                    String idleSummary = name + " · 未确认打卡（没看到提交类回执）";
                    return TaskRunner.Result.idle(idleSummary,
                            detail.append("判定: v3.87 按回执尺子拦下，不报成功\n").toString(),
                            idleSummary);
                }
                String summary = name + " · 打卡成功";
                return TaskRunner.Result.ok(summary,
                        detail.append("按钮: ").append(domHit).append('\n').toString(), summary);
            }
            if (domTry > 0) {
                return TaskRunner.Result.fail(name + " · 点了打卡按钮但页面没回执",
                        detail.append("按钮: ").append(domHit).append('\n').toString(),
                        "按钮点到了，但页面没发出请求也没变成已打卡。把完整日志发我。");
            }
            if (out.optInt("already", 0) == 1
                    || text0.contains("已打卡") || text0.contains("明天再来")
                    // v4.2.0：打卡成功的页面会把抽奖次数发下来，文案变成
                    // 「今日抽奖次数已用完」，这就是「今天打过了」的等价证据。
                    || text0.contains("抽奖次数已用完") || text0.contains("机会已用完")) {
                String summary = name + " · 今天已经打过卡";
                return TaskRunner.Result.idle(summary, detail.toString(), summary);
            }
            // 页面确实没有打卡按钮（纯访问型），且文案里能看到「今天 +N」，
            // 才认定这次进页已经记上。这条证据放在按钮之后，避免再出现假成功；
            // 摘要也写明是「按进页判定」，不和真点过按钮的「打卡成功」混在一起。
            if (kind == KIND_DAILY7 && daily7Paid(text0)) {
                // v3.48：以前这里报 ok，但「进页 + 文案」根本不是打卡回执，
                // 用户看到 ✓ 却不知道是不是自己手动打的卡。改成 idle：
                // 不报成功、不计失败次数，明确写清「没有打卡按钮，未确认打卡」。
                String summary = name + " · 页面没有打卡按钮，未确认打卡（不算完成）";
                return TaskRunner.Result.idle(summary, detail.toString(), summary);
            }
            // v4.0.5：19:24 日志实证 —— 7 日打卡首屏已经完整渲染出活动页
            // （「我的奖品 / 第1天…第7天 / 剩余1次机会 / 每天登录可抽奖1次」），
            // 只是这个 coc web6 页面不用 Vue（组件 0 个）。旧判据用
            // text0.contains("登录") 认登录页，被活动规则里的「每天登录可抽奖1次」
            // 命中，把好端端的活动页误报成「页面停在登录页」。
            // 先确认活动主体是否已渲染，再决定报什么。
            boolean rendered = text0.contains("打卡") || text0.contains("我的奖品")
                    || text0.contains("第1天") || text0.contains("抽奖")
                    || text0.contains("红包") || text0.contains("剩余");
            String title = out.optString("title", "");
            if (!rendered && (text0.contains("登录中") || text0.contains("校验中"))) {
                return TaskRunner.Result.fail(name + " · 页面登录没走完",
                        detail.append("等待期间文案一直停在「登录中」\n").toString(),
                        "checkUID 的桥回调没回到离屏页。把完整日志发我，"
                                + "看「登录桥改绑」和「登录回调转发」两行。");
            }
            if (!rendered && (text0.contains("登录") || title.contains("登录"))) {
                return TaskRunner.Result.fail(name + " · 页面停在登录页",
                        detail.toString(),
                        "在 App 里手动打开一次这个活动，再回来执行任务。");
            }
            if (rendered) {
                // r14：按文本找不到打卡按钮时，先试一次「第 N 天」卡片的坐标点击。
                // 有回执才判成功；没回执一律 idle —— 这个页面本来就是「进页即打卡」
                // 或画布热区，判失败只会把一键汇总染红，也不该计失败次数。
                if (kind == KIND_DAILY7 && domTry == 0) {
                    String tapBefore = text0;
                    String tap = page.eval(DAILY7_TAP, 6_000L);
                    detail.append("坐标点击: ").append(brief(tap)).append('\n');
                    if (tap != null && tap.contains("\"ok\":1")) {
                        sleep(2_800L);
                        String after = page.eval(BODY_TEXT, 4_000L);
                        if (after != null && !after.isEmpty() && !after.equals(tapBefore)) {
                            detail.append("点击后: ").append(brief(after)).append('\n');
                        }
                        if (after != null && (after.contains("已打卡") || after.contains("已领取")
                                || after.contains("明天再来") || after.contains("已签到")
                                || daily7Paid(after))) {
                            String summary = name + " · 坐标点击后已打卡";
                            return TaskRunner.Result.ok(summary, detail.toString(), summary);
                        }
                        detail.append("坐标点击: 点到了卡片，但文案没变（不伪造提交）\n");
                    } else if (tap != null && tap.contains("\"none\":1")) {
                        detail.append("坐标点击: 页面上没有「第 N 天」卡片，跳过\n");
                    }
                }
                // 页面是好的，只是没挂载 Vue 组件 / 没抓到可提交的打卡按钮。
                // 如实报「没找到入口」：不猜坐标、不伪造提交、也不冒充完成。
                if (kind == KIND_DAILY7) {
                    String summary = name + " · 未确认打卡（页面无按钮/热区，本轮零提交）";
                    return TaskRunner.Result.idle(summary,
                            detail.append("判定(v4.14): 活动页已渲染，但没有任何可确认的打卡入口。\n")
                                    .append("这类页面按「进页即打卡」处理，未确认就不报成功；\n")
                                    .append("下一轮进页会重新记一次，不影响当天次数。\n").toString(),
                            summary);
                }
                return TaskRunner.Result.fail(name + " · 页面已渲染但没找到打卡入口",
                        detail.append("判定(v4.0.5): 首屏已是活动页，不是登录页（")
                                .append(brief(text0)).append("）\n")
                                .append("组件 0 个、请求层 0 个、提交类回执 0 条；\n")
                                .append("打卡按钮可能是画布热区/图片，脚本只能按文本找按钮，\n")
                                .append("这一轮零提交，不算完成。\n").toString(),
                        "页面本身没问题（不是登录页）。这个活动用画布热区，模块抓不到可点的打卡按钮。\n"
                                + "请在 App 里手动点一次打卡，再导出完整日志发我，我按真实按钮补 DOM 目标。");
            }
            return TaskRunner.Result.fail(name + " · 页面组件没挂载",
                    detail.append("没找到 Vue 根组件，页面可能被重定向或还没渲染完\n").toString(),
                    "在 App 里手动点进一次这个活动，再回来执行任务。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail(name + " · 脚本异常",
                    detail.append(out.optString("msg", "")).toString(), "把日志发我。");
        }

        JSONObject sign = out.optJSONObject("sign");
        JSONObject draw = out.optJSONObject("draw");
        JSONArray net = out.optJSONArray("net");

        int signOk = sign == null ? 0 : sign.optInt("ok", 0);
        int signTry = sign == null ? 0 : sign.optInt("try", 0);
        int drawOk = draw == null ? 0 : draw.optInt("ok", 0);
        int drawTry = draw == null ? 0 : draw.optInt("try", 0);
        int drawSeen = draw == null ? 0 : draw.optInt("seen", 0);
        int drawLeft = draw == null ? 0 : draw.optInt("left", 0);
        int drawTasks = draw == null ? 0 : draw.optInt("tasks", 0);
        int drawNoChance = draw == null ? 0 : draw.optInt("noChance", 0);
        int drawRisk = draw == null ? 0 : draw.optInt("risk", 0);
        String drawPrize = draw == null ? "" : draw.optString("prize", "");
        // 「已签」在签到日历里会重复出现（前几天的记录），不能拿来判今天；
        // 只认明确的当日字样。
        // v3.48：周周/三日签页面做完任务后文案变体很多，以前只认 6 个词，
        // 明明做完了还报「动作发了但没成功」。补齐常见「已完成」写法。
        boolean already = text1.contains("今日已签") || text1.contains("今天已签")
                || text1.contains("今天已打卡") || text1.contains("今日已打卡")
                || daily7Paid(text1) || text1.contains("今日已领")
                || text1.contains("今日已完成") || text1.contains("任务已完成")
                || text1.contains("已全部完成") || text1.contains("今日已领完")
                || text1.contains("已领完") || text1.contains("明天继续")
                || text1.contains("今日已抽") || text1.contains("机会已用完");

        detail.append("签到: 调用 ").append(signTry).append(" 次，成功 ").append(signOk).append('\n');
        detail.append("抽奖: 尝试 ").append(drawTry).append(" 次，成功 ").append(drawOk).append('\n');
        String netLine = netBrief(net);
        if (!netLine.isEmpty()) {
            detail.append("网络证据:\n").append(netLine).append('\n');
        }

        // ---- 判定 ----
        // v3.87 P0：JS 报「成功」还不够，Java 再核一遍回执尺子：
        // 必须真有提交类回执，或状态量（累计签到次数）变大。只有查询接口
        // （taskSignRecord / residueLotteryCount / checkActivityMutex…）或次数没变，
        // 一律不许报 OK —— 17-55 幸运三日签就是这样假成功的。
        // v3.88 B-1：「累计签到次数变大」是比接口 URL 更硬的状态量证据。
        // 真签到接口未必叫 signIn（可能叫 accumulateSign / sign），所以次数
        // 0→1 可独立判成功；但次数没变（0→0）一律不许报 OK。
        boolean metricUp = metric0 >= 0 && metric1 >= 0 && metric1 > metric0;
        // v3.90：提交接口明确回「本日已签到」(-9014) 且页面累计签到次数 >= 1，
        // 说明今天已经签过了，不是「没签到」。双证据才放行，不放宽原有的假 OK
        // 拦截 —— 查询接口里的「已签到」文案仍然不算数。
        if (!signedAlready.isEmpty() && metric0 >= 1 && metricFlat) {
            String summary = name + " · 今日已签到（累计签到 " + metric0 + " 次，无需重复）";
            return TaskRunner.Result.ok(summary,
                    detail.append("判定: 服务端确认本日已签到（v3.90），今天无需重复执行\n")
                            .toString(), summary);
        }
        if (drawOk > 0 || signOk > 0 || metricUp) {
            // v4.2.2：周周这类「只翻牌、不签到」的活动（kind==KIND_WEEKLY）累计签到次数
            // 永远是 0→0（metricFlat）。旧判据拿 metricFlat 一票否决，会把页面真的
            // 翻牌中奖判成「未确认完成」。现在只有需要签到的活动才要求签到次数变化；
            // 抽奖成功本身就是有效回执。
            boolean needSignKind = kind != KIND_WEEKLY;
            if ((needSignKind && metricFlat) || (submitN <= 0 && !metricUp && drawOk <= 0)) {
                String why = (needSignKind && metricFlat)
                        ? "累计签到次数没变（" + metric0 + "→" + metric1 + "），没签到"
                        : "没有提交类回执，只有查询/失败响应";
                String idleSummary = name + " · 未确认完成（" + why + "）";
                return TaskRunner.Result.idle(idleSummary,
                        detail.append("判定: 按回执尺子判为未确认完成（v3.92）\n")
                                .toString(), idleSummary);
            }
            StringBuilder summary = new StringBuilder(name).append(" · ");
            if (signOk > 0 || metricUp) {
                summary.append("签到成功");
                if (metricUp) {
                    summary.append("（累计签到 ").append(metric0).append("→")
                            .append(metric1).append("）");
                }
            }
            if (drawOk > 0) {
                if (signOk > 0 || metricUp) {
                    summary.append("，");
                }
                summary.append("抽奖 ").append(drawOk).append(" 次");
                if (!drawPrize.isEmpty()) {
                    summary.append("：").append(drawPrize);
                }
            }
            return TaskRunner.Result.ok(summary.toString(), detail.toString(), summary.toString());
        }
        // v3.88：签到没成（次数 0→0）时别用「今天没有抽奖次数」把问题盖过去。
        if (metricFlat && signTry > 0) {
            String idleSummary = name + " · 未确认完成（累计签到次数没变 "
                    + metric0 + "→" + metric1 + "，没签到）";
            return TaskRunner.Result.idle(idleSummary,
                    detail.append("判定: 按回执尺子判为未确认完成（v3.92）\n").toString(),
                    idleSummary);
        }
        // 次数为 0 不是失败：活动今天/本周本来就没机会。
        // 周周有好礼这类要先做浏览任务，任务数也一并报出来。
        if ((drawSeen > 0 && drawLeft <= 0) || drawNoChance > 0) {
            String summary = name + (drawTasks > 0
                    ? " · 翻牌机会 0，还有 " + drawTasks + " 个浏览任务没做"
                    : " · 今天没有抽奖次数");
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        // v3.91：21:33 日志 —— 服务端账号风控，不是模块坏了、也不是没机会。
        // 别再笼统报「接口拒绝」，更不能引导用户反复重试（会加重风控）。
        if (drawRisk > 0) {
            // v4.2.0：用户明确要求「周周那个风控不算过任务」—— SCORE_REFUSE 是服务端
            // 当场拒绝，不是模块没做、也不是活动没机会。以前报 fail，界面记一个 ✗，
            // 还会进失败计数、下一轮又去撞一次，只会加重风控。改成 idle：
            // 记为「今天不做」，不重试。
            String summary = name + " · 服务端风控拦了，今天先不做（不算失败）";
            return TaskRunner.Result.idle(summary,
                    detail.append("判定: 服务端风控拦截（v4.2.0 改判 idle），已停止重试\n")
                            .toString(),
                    "这次是账号/设备被服务端风控拦了，不是模块坏了，也不是没机会。"
                            + "模块不会反复重试；过一阵再手动执行一次。");
        }
        if (signTry > 0 || drawTry > 0) {
            return TaskRunner.Result.fail(name + " · 动作发了但没成功",
                    detail.toString(),
                    "页面接口拒绝了这次请求。把完整日志发我。");
        }
        if (kind == KIND_DAILY7 && daily7Paid(text1)) {
            if (submitN > 0) {
                String summary = name + " · 今日打卡已记录";
                return TaskRunner.Result.ok(summary, detail.toString(), summary);
            }
            String summary = name + " · 页面显示今天已到账，但没看到提交回执（未确认）";
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        if (already) {
            String summary = name + " · 今天已经签过 / 没有待领";
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        if (drawSeen > 0 && drawLeft <= 0) {
            // 奖池查到了、剩余次数是 0：不是失败，是今天本来就没次数。
            String summary = name + " · 今天没有抽奖次数";
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        String summary = name + " · 没有拿到签到/抽奖结果";
        return TaskRunner.Result.fail(summary, detail.toString(),
                "页面结构和预期不一致。把完整日志发我，按页面里的真实组件改。");
    }

    /** 页面内的执行明细按 4000 字符分片落日志。 */
    private static void writeLines(String name, JSONArray lines) {
        if (lines == null || lines.length() == 0) {
            return;
        }
        StringBuilder chunk = new StringBuilder();
        int part = 1;
        for (int i = 0; i < lines.length(); i++) {
            String one = lines.optString(i, "");
            if (chunk.length() + one.length() + 1 > 4000) {
                HookLog.log("CANVAS", name + " 页面明细 " + part, chunk.toString());
                chunk.setLength(0);
                part++;
            }
            chunk.append(one).append('\n');
        }
        if (chunk.length() > 0) {
            HookLog.log("CANVAS", name + " 页面明细 " + part, chunk.toString());
        }
    }

    private static String netBrief(JSONArray net) {
        if (net == null || net.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < net.length() && i < 8; i++) {
            JSONObject one = net.optJSONObject(i);
            if (one == null) {
                continue;
            }
            sb.append("· ").append(one.optString("u", ""));
            sb.append(" → ").append(one.optString("s", ""));
            String body = one.optString("b", "");
            if (!body.isEmpty()) {
                sb.append(' ').append(brief(body));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 7 日打卡的「今天到账」判定。
     *
     * <p>未登录的骨架页也会写「当前累计金额 0%」，只用关键词会把空页面判成成功；
     * 这里只认「今天 +N」，且 N 必须大于 0。
     */
    private static boolean daily7Paid(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("今天\\s*\\+?\\s*([0-9]+(?:\\.[0-9]+)?)")
                .matcher(text);
        while (m.find()) {
            try {
                if (Double.parseDouble(m.group(1)) > 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() <= 260 ? value : value.substring(0, 260) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------- 周周浏览任务

    /** 周周的「去逛逛」停留门槛。用户实测 5 秒就够，多留 1 秒防服务端按整秒截断。 */
    private static final long WEEKLY_STAY_MS = 6_000L;
    /** 一轮最多做几个浏览任务。每个约 13 秒（跳 2 + 停 6 + 回 4）。 */
    private static final int WEEKLY_TASK_TRIES = 12;

    /**
     * 数页面上还剩几张待做任务卡片；-1 = 读不到。
     *
     * <p>v4.10.0-r4：按「卡片」数，不再按「文本恰好等于去逛逛的元素」数。
     * 旧写法把卡片外层和里面的按钮各数一次，一张卡算两个，循环里的
     * 「剩余数下降」「skip 到顶就停」全跟着错。现在先找「同时含 (0/1)
     * 和动作词」的容器，再把被别的候选包含的外层丢掉，只剩最内层卡片。
     */
    private static final String WEEKLY_TODO_COUNT = """
            (function(){
              try{
                function norm(s){return String(s||'').replace(/\\s+/g,'')}
                function tx(e){if(!e)return '';var s='';try{s=e.innerText||''}catch(x){}
                  if(!s){try{s=e.textContent||''}catch(x){}}return String(s)}
                var WORDS=['去逛逛','去完成','去看看','去浏览','去参与','立即参与','去体验','立即领取','去领取'];
                function isCard(t){
                  if(t.indexOf('(0/1)')<0&&t.indexOf('（0/1）')<0)return false;
                  if(t.length>220)return false;
                  for(var i=0;i<WORDS.length;i++){if(t.indexOf(WORDS[i])>=0)return true}
                  return false;
                }
                var all=document.querySelectorAll('div,li,section,article,uni-view,view,p'),c=[],i,j;
                for(i=0;i<all.length;i++){
                  var e=all[i],t=norm(tx(e));
                  if(!isCard(t))continue;
                  var r={width:0,height:0};try{r=e.getBoundingClientRect()}catch(x){continue}
                  if(r.width<40||r.height<20)continue;
                  c.push(e);
                }
                var kept=0;
                for(i=0;i<c.length;i++){
                  var nested=false;
                  for(j=0;j<c.length;j++){
                    if(i===j||c[i]===c[j])continue;
                    if(c[i].contains(c[j])){nested=true;break}
                  }
                  if(!nested)kept++;
                }
                return String(kept);
              }catch(e){return '-1'}
            })()
            """;

    /**
     * r14：先读页面组件里的任务清单，判断「还要不要点」。
     *
     * <p>以前直接数 DOM 卡片：卡片没渲染出来（骨架/懒加载）就判「读不到」，
     * 有一半时间白跑一轮点击脚本。这里从 Vue 实例里找 taskList/tasks 数组，
     * 空数组就是真的没待办，能省掉整轮点击；读不到（返回空串）才退回 DOM 计数。
     *
     * <p>只读，不调任何方法 —— 判空和点击分开，避免「判空顺便把任务点了」。
     */
    private static final String WEEKLY_TASKS = """
            (function(){
              try{
                var out=[],seen=0;
                function walk(v,d){
                  if(!v||d>12||seen>500)return; seen++;
                  var keys=['taskList','taskListArr','taskArr','tasks'];
                  for(var ki=0;ki<keys.length;ki++){
                    var a=v[keys[ki]];
                    if(!a||!a.length)continue;
                    for(var i=0;i<a.length&&i<40;i++){
                      var it=a[i];
                      if(!it||typeof it!=='object')continue;
                      var id=it.taskId||it.taskID||it.id||'';
                      if(!id)continue;
                      var nm=it.taskName||it.taskTitle||it.name||it.title||'';
                      var st=it.status!=null?it.status:(it.state!=null?it.state:(it.finish!=null?it.finish:''));
                      out.push({id:String(id),name:String(nm).slice(0,40),status:String(st)});
                    }
                  }
                  var cs=v.$children||[];
                  for(var q=0;q<cs.length;q++)walk(cs[q],d+1);
                }
                var root=document.querySelector('#app')||document.querySelector('#root')||document.body;
                var rv=root&&(root.__vue__||(root.__vue_app__&&root.__vue_app__._instance));
                walk(rv,0);
                if(!out.length)return '[]';
                return JSON.stringify(out.slice(0,40));
              }catch(e){return ''}
            })()
            """;

    /**
     * r14：7 日打卡的坐标点击兜底。
     *
     * <p>这个 coc web6 页面不用 Vue，打卡键有时是画布热区/图片，按文本找按钮
     * 一定落空（v4.0.5 起一直报「页面已渲染但没找到打卡入口」）。这里按
     * 「第 N 天」卡片找落点：优先当天卡片（class 带 today/active），点它的
     * 中心，再读文案变化。没变化就什么都不算 —— 不伪造提交。
     */
    private static final String DAILY7_TAP = """
            (function(){
              try{
                function ownText(e){
                  var s='';try{for(var k=0;k<e.childNodes.length;k++){
                    var n=e.childNodes[k];if(n.nodeType===3)s+=n.nodeValue}}catch(x){}
                  return s.replace(/\\s+/g,'');
                }
                var all=document.querySelectorAll('div,li,section,article,span,img,button,p');
                var best=null,bestScore=-1,i;
                for(i=0;i<all.length;i++){
                  var e=all[i],t=ownText(e);
                  if(!/第\\s*[1-7]\\s*天/.test(t))continue;
                  var r={width:0,height:0};try{r=e.getBoundingClientRect()}catch(x){continue}
                  if(r.width<40||r.height<30)continue;
                  if(r.top<0||r.top>window.innerHeight)continue;
                  var cls=String(e.className||'');
                  var score=(/today|current|active|now|cur|选中/i.test(cls)?10:0)
                    + r.width*r.height/10000;
                  if(score>bestScore){bestScore=score;best=e}
                }
                if(!best)return JSON.stringify({none:1});
                var rr=best.getBoundingClientRect();
                var x=rr.left+rr.width/2,y=rr.top+rr.height/2;
                var tgt=best;
                try{
                  var f=document.elementFromPoint(x,y);
                  if(f&&(f===best||best.contains(f)))tgt=f;
                }catch(x){}
                function fire(el){
                  try{
                    var tch=new Touch({identifier:1,target:el,clientX:x,clientY:y,
                      pageX:x+window.scrollX,pageY:y+window.scrollY,screenX:x,screenY:y,
                      radiusX:1,radiusY:1,rotationAngle:0,force:1});
                    el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,
                      view:window,touches:[tch],targetTouches:[tch],changedTouches:[tch]}));
                    el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,
                      view:window,touches:[],targetTouches:[],changedTouches:[tch]}));
                  }catch(e){}
                  var ps=['pointerdown','mousedown','pointerup','mouseup'];
                  for(var m=0;m<ps.length;m++){
                    try{
                      var ev=ps[m].indexOf('pointer')===0
                        ? new PointerEvent(ps[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y})
                        : new MouseEvent(ps[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y});
                      el.dispatchEvent(ev);
                    }catch(e){}
                  }
                  try{if(typeof el.click==='function')el.click()}catch(e){}
                }
                fire(tgt);
                return JSON.stringify({ok:1,x:Math.round(x),y:Math.round(y),
                  tag:String(tgt.tagName||''),cls:String(tgt.className||'').slice(0,30)});
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    private static final String BODY_TEXT = """
            (function(){try{return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().slice(0,240)}catch(e){return ''}})()
            """;

    /**
     * 点第 __SKIP__ 张待做卡片上的按钮。点完立刻返回，不等跳转 —— 跳转会销毁脚本上下文。
     *
     * <p>v4.10.0-r4 修两处：
     * <ol>
     *   <li>落点绝不取祖先容器。旧代码写的是 {@code f.contains(btn)} 也算命中，
     *       而周周页的「去逛逛」外面盖着一层透明层，{@code elementFromPoint} 返回的是
     *       整页容器 .van-pull-refresh__track —— 事件全打在那个 DIV 上，Vue 的
     *       {@code @click} 一次都没触发（21:36 日志两条点击的控件就是它）。</li>
     *   <li>1 秒后如果页面还在 zzcj，按卡片中心补一次；卡片自己的 click 处理器
     *       也能接住，不再出现「点了 8 次 (0/1) 一动不动」。</li>
     * </ol>
     */
    private static final String WEEKLY_TASK_ONE = """
            (function(){
              try{
                var SKIP=__SKIP__;
                function norm(s){return String(s||'').replace(/\\s+/g,'')}
                function tx(e){if(!e)return '';var s='';try{s=e.innerText||''}catch(x){}
                  if(!s){try{s=e.textContent||''}catch(x){}}return String(s)}
                var WORDS=['去逛逛','去完成','去看看','去浏览','去参与','立即参与','去体验','立即领取','去领取'];
                function isCard(t){
                  if(t.indexOf('(0/1)')<0&&t.indexOf('（0/1）')<0)return false;
                  if(t.length>220)return false;
                  for(var i=0;i<WORDS.length;i++){if(t.indexOf(WORDS[i])>=0)return true}
                  return false;
                }
                var all=document.querySelectorAll('div,li,section,article,uni-view,view,p'),c=[],i,j;
                for(i=0;i<all.length;i++){
                  var e=all[i],t=norm(tx(e));
                  if(!isCard(t))continue;
                  var r={width:0,height:0};try{r=e.getBoundingClientRect()}catch(x){continue}
                  if(r.width<40||r.height<20)continue;
                  c.push(e);
                }
                var cards=[];
                for(i=0;i<c.length;i++){
                  var nested=false;
                  for(j=0;j<c.length;j++){
                    if(i===j||c[i]===c[j])continue;
                    if(c[i].contains(c[j])){nested=true;break}
                  }
                  if(!nested)cards.push(c[i]);
                }
                if(!cards.length)return JSON.stringify({none:1});
                var at=Math.min(SKIP,cards.length-1),card=cards[at];
                var name=norm(tx(card)).slice(0,40);
                // 按钮 = 卡片里文本恰好等于动作词的最深元素；没有文本就退到卡片里的图片。
                var btn=null,bestLen=1e9,bestDeep=-1,img=null;
                var els=card.querySelectorAll('div,span,a,button,p,li,uni-view,uni-text,view,text,img,image');
                for(i=0;i<els.length;i++){
                  var el=els[i],et=norm(tx(el)),tag=String(el.tagName||'').toUpperCase();
                  var ok=false;
                  for(j=0;j<WORDS.length;j++){ if(et===WORDS[j]){ok=true;break} }
                  var rr={width:0,height:0};try{rr=el.getBoundingClientRect()}catch(x){continue}
                  if(rr.width<8||rr.height<8)continue;
                  if(ok){
                    var dp=0,p2=el;while(p2&&p2.parentElement){dp++;p2=p2.parentElement}
                    if(et.length<bestLen||(et.length===bestLen&&dp>bestDeep)){bestLen=et.length;bestDeep=dp;btn=el}
                  }else if((tag==='IMG'||tag==='IMAGE')&&!img){img=el}
                }
                if(!btn)btn=img||card;
                var r=btn.getBoundingClientRect();
                var x=r.left+r.width/2,y=r.top+r.height/2;
                // 只认三种落点：按钮自己、按钮的子节点、同一张卡片里盖在按钮上的兄弟层。
                // 祖先容器一律不认 —— 打在祖先上等于没点。
                var tgt=btn;
                try{
                  if(y>8&&y<window.innerHeight-8&&x>4&&x<window.innerWidth-4){
                    var f=document.elementFromPoint(x,y);
                    if(f){
                      if(f===btn||btn.contains(f))tgt=f;
                      else if(card.contains(f)&&!f.contains(btn))tgt=f;
                    }
                  }
                }catch(x){}
                function fire(el){
                  try{
                    var tch=new Touch({identifier:1,target:el,clientX:x,clientY:y,
                      pageX:x+window.scrollX,pageY:y+window.scrollY,screenX:x,screenY:y,
                      radiusX:1,radiusY:1,rotationAngle:0,force:1});
                    el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,view:window,
                      touches:[tch],targetTouches:[tch],changedTouches:[tch]}));
                    el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,view:window,
                      touches:[],targetTouches:[],changedTouches:[tch]}));
                  }catch(e){}
                  var ps=['pointerdown','mousedown','pointerup','mouseup'];
                  for(var m=0;m<ps.length;m++){
                    try{
                      var ev=ps[m].indexOf('pointer')===0
                        ? new PointerEvent(ps[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y})
                        : new MouseEvent(ps[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y});
                      el.dispatchEvent(ev);
                    }catch(e){}
                  }
                  try{if(typeof el.click==='function')el.click()}catch(e){}
                }
                // r14：点击前先调页面组件自己的 sendTaskVisit(taskId)。
                // 点击要跳走、等渲染、再回来复查，一轮 13 秒；方法调用是页面自己的
                // 上报入口，能成就不必跳。调不到 / 报错都只记一笔，后面照旧点击，
                // 功能不回退。taskNames 原来只声明不调用，这次真接上。
                var visit='';
                function findTaskId(v,want,d){
                  if(!v||d>8)return '';
                  var ks=['taskList','taskListArr','tasks','taskArr'];
                  for(var ki=0;ki<ks.length;ki++){
                    var arr=v[ks[ki]];
                    if(!arr||!arr.length)continue;
                    for(var ai=0;ai<arr.length&&ai<40;ai++){
                      var it=arr[ai];
                      if(!it||typeof it!=='object')continue;
                      var nm=String(it.taskName||it.taskTitle||it.name||it.title||'');
                      if(want&&nm&&nm.indexOf(want)<0&&want.indexOf(nm)<0)continue;
                      var id=it.taskId||it.taskID||it.id||'';
                      if(id)return id;
                    }
                  }
                  var cq=v.$children||[];
                  for(var qi=0;qi<cq.length;qi++){
                    var got=findTaskId(cq[qi],want,d+1);
                    if(got)return got;
                  }
                  return '';
                }
                function visitWalk(v,d){
                  if(!v||d>12||visit)return;
                  if(typeof v.sendTaskVisit==='function'){
                    var id=findTaskId(v,wantTaskId,0)||'';
                    if(id){
                      try{ v.sendTaskVisit(id); visit='sent:'+id }
                      catch(e){ visit='err:'+String(e&&e.message||e).slice(0,30) }
                    }
                  }
                  var cs=v.$children||[];
                  for(var q=0;q<cs.length;q++)visitWalk(cs[q],d+1);
                }
                // 卡片名去掉动作词和 (0/1)，只留标题前 10 字去对任务名 ——
                // 直接拿整段卡片文本去 indexOf 一定匹配不上（任务名里没有「去逛逛」）。
                var wantTaskId=(function(){
                  var t=norm(tx(card));
                  for(var wi=0;wi<WORDS.length;wi++){ t=t.split(WORDS[wi]).join('') }
                  return t.replace(/\\(0\\/1\\)/g,'').replace(/（0\\/1）/g,'').slice(0,10);
                })();
                try{
                  var rootEl=document.querySelector('#app')||document.querySelector('#root')||document.body;
                  var vueRoot=rootEl&&(rootEl.__vue__||(rootEl.__vue_app__&&rootEl.__vue_app__._instance));
                  visitWalk(vueRoot,0);
                }catch(e){ visit='scan:'+String(e&&e.message||e).slice(0,30) }
                fire(tgt);
                // 兜底：1 秒后还在活动页，说明第一下落点没命中，按卡片中心补一次。
                try{
                  setTimeout(function(){
                    try{
                      if(String(location.href).indexOf('zzcj')<0)return;
                      var cr=card.getBoundingClientRect();
                      var cx=cr.left+cr.width/2,cy=cr.top+cr.height/2;
                      if(cy<10||cy>window.innerHeight-10)return;
                      var f2=document.elementFromPoint(cx,cy);
                      if(!f2||!card.contains(f2)||f2.contains(btn))return;
                      var sx=x,sy=y; x=cx; y=cy; fire(f2); x=sx; y=sy;
                    }catch(e){}
                  },900);
                }catch(e){}
                var cls='';
                try{cls=String(tgt.className||'').slice(0,36)}catch(e){}
                return JSON.stringify({name:name,idx:at,total:cards.length,
                  tag:String(tgt.tagName||''),cls:cls,visit:visit});
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    private static String weeklyTaskOne(WebPage page, int skip) {
        return page.eval(WEEKLY_TASK_ONE.replace("__SKIP__", String.valueOf(skip)), 10_000L);
    }

    private static int weeklyTodo(WebPage page) {
        String v = page.eval(WEEKLY_TODO_COUNT, 6_000L);
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 页面组件里的任务清单；读不到返回 null（退回 DOM 计数）。 */
    private static JSONArray weeklyTasks(WebPage page) {
        String v = page.eval(WEEKLY_TASKS, 4_000L);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONArray(v.trim());
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 逐个点「去逛逛」：点 → 页面自己跳 → 停留 6 秒（门槛 5 秒）→ 回活动页 → 复查剩余数。
     *
     * <p>之前模块只统计待做任务、从不点击，所以「做了几个任务也抽了但没做完」。
     * 这里也不调组件方法：页面组件叫不出名字，点卡片走的是页面自己的 @click，
     * 跳转与上报都由它完成。
     */
    private static void runWeeklyTasks(WebPage page, String entry, StringBuilder detail) {
        sleep(1_500L);
        // r14：先看页面组件里的任务清单。清单明确为空就不再点 —— 以前 DOM 数到
        // 卡片就点，卡片虚影/懒加载时白跑一轮 13 秒。
        JSONArray list = weeklyTasks(page);
        if (list != null) {
            detail.append("任务清单: 组件读到 ").append(list.length()).append(" 条\n");
            if (list.length() == 0) {
                detail.append("浏览任务: 清单为空（接口层面无待办），不点\n");
                return;
            }
        } else {
            detail.append("任务清单: 组件里没找到 taskList，退回按卡片数\n");
        }
        int left = weeklyTodo(page);
        if (left == 0) {
            detail.append("浏览任务: 没有待做（都已完成）\n");
            return;
        }
        if (left < 0) {
            detail.append("浏览任务: 页面上读不到「去逛逛」卡片，跳过\n");
            return;
        }
        detail.append("浏览任务: 待做 ").append(left)
                .append(" 个，点「去逛逛」后停留 ").append(WEEKLY_STAY_MS / 1000).append(" 秒\n");
        int done = 0, skip = 0;
        for (int i = 0; i < WEEKLY_TASK_TRIES && left > 0; i++) {
            // v4.10.0：点了没记上时换下一张卡片，别一直点第一个（老逻辑 8 次全打在
            // 同一个按钮上，日志里「( 0/1)」一个没变）。
            String raw = weeklyTaskOne(page, skip);
            if (raw == null || raw.isEmpty()) {
                detail.append("浏览任务: 点击脚本没回报，停止\n");
                break;
            }
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Throwable e) {
                detail.append("浏览任务: 回报解析失败 ").append(brief(raw)).append('\n');
                break;
            }
            if (o.has("none")) {
                detail.append("浏览任务: 页面已无「去逛逛」\n");
                break;
            }
            if (o.has("err")) {
                detail.append("浏览任务: ").append(o.optString("err")).append('\n');
                break;
            }
            String name = o.optString("name", "");
            detail.append("浏览任务 ").append(i + 1).append(": 点第 ")
                    .append(o.optInt("idx", 0) + 1).append("/")
                    .append(o.optInt("total", 0)).append(" 张「")
                    .append(name.isEmpty() ? "去逛逛" : name).append("」")
                    .append(" 落点 ").append(o.optString("tag", "?"))
                    .append(o.optString("cls", "").isEmpty() ? "" : "." + o.optString("cls"))
                    .append('\n');
            String visit = o.optString("visit", "");
            if (!visit.isEmpty()) {
                detail.append("浏览任务: 点击前调 sendTaskVisit → ").append(visit).append('\n');
            } else {
                detail.append("浏览任务: 组件上没有可调的 sendTaskVisit，只走点击\n");
            }
            // 脚本里 0.9 秒还有一次「卡片中心」补点，这里多留 0.5 秒再判断跳没跳。
            sleep(2_000L);
            String landed = page.url();
            boolean jumped = landed == null || !landed.contains("zzcj");
            if (jumped) {
                detail.append("浏览任务: 已跳到 ").append(brief(landed)).append("，停留 ")
                        .append(WEEKLY_STAY_MS / 1000).append(" 秒\n");
            } else {
                detail.append("浏览任务: 页面内没跳走，仍然计 6 秒\n");
            }
            sleep(WEEKLY_STAY_MS);
            if (!page.navigate(entry, 25_000L)) {
                detail.append("浏览任务: 拉回活动页失败，停止\n");
                break;
            }
            sleep(3_500L);
            int now = weeklyTodo(page);
            if (now >= 0 && now < left) {
                done++;
                skip = 0;
                detail.append("浏览任务: 剩余 ").append(left).append(" → ").append(now).append("（记上了）\n");
                left = now;
            } else if (now >= 0) {
                skip++;
                detail.append("浏览任务: 剩余仍 ").append(now).append("（这张没记上，下一轮换卡片）\n");
                left = now;
                if (skip >= left) {
                    detail.append("浏览任务: 所有待做卡片都试过仍没记上，停止\n");
                    break;
                }
            } else {
                skip++;
            }
        }
        detail.append("浏览任务: 本轮记上 ").append(done).append(" 个，剩余 ").append(left).append(" 个\n");
    }

    /**
     * 生成页面内脚本。
     *
     * <p>kind 只影响「要不要找签到」「要不要抽奖」，脚本主体是同一套：
     * 挂网络钩子 → 等 Vue → 枚举组件 → 调方法 → 接口兜底 → 点击兜底 → 复查。
     */
    private static String script(int kind) {
        int[] ids;
        if (kind == KIND_LUCKY3) {
            ids = IDS_LUCKY3;
        } else if (kind == KIND_WEEKLY) {
            ids = IDS_WEEKLY;
        } else {
            ids = new int[0];
        }
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(ids[i]);
        }
        arr.append(']');
        // 7 日打卡原先按「进页面即打卡」处理，不点按钮。11-07 实测账号根本没签上，
        // 而日志写的是「今日打卡已记录」——假成功。现在改成和其他活动一样找打卡入口，
        // 页面没有按钮时才退到「今天 +N」文案证据。
        boolean needSign = kind != KIND_WEEKLY;
        boolean needDraw = ids.length > 0;
        return SCRIPT_TEMPLATE
                .replace("__NEED_SIGN__", needSign ? "true" : "false")
                .replace("__NEED_DRAW__", needDraw ? "true" : "false")
                .replace("__IDS__", arr.toString());
    }

    /**
     * 页面内脚本模板。
     *
     * <p>注意 Java text block 的转义：脚本里要出现 {@code \s} 时必须写成 {@code \\s}；
     * 不要写 {@code \'}、{@code \d} 这类 Java 不认识的转义。
     */
    private static final String SCRIPT_TEMPLATE = """
            (function(){
              var ID='__cmcc_canvas';
              window[ID]='';
              var L=[], NET=[], AX=[];
              // 顶层留一份组件树：readTasks 定义在外层，直接用 async 里的 comps
              // 会报 ReferenceError（11-07 三日签就是这么挂的）。
              var COMPS=[];
              var NEED_SIGN=__NEED_SIGN__, NEED_DRAW=__NEED_DRAW__, IDS=__IDS__;
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function isFn(x){ return typeof x==='function' }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function done(o){ try{ o.lines=L; window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"stage":"crash"}' } }
              function btext(){ try{ return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim() }catch(e){ return '' } }
              function ctext(c){ try{ var e=c.$el; return e?(e.innerText||'').replace(/\\s+/g,' ').trim():'' }catch(e){ return '' } }

              // coc 页面的登录要过 window.cmcc.checkUID。这个对象平时由页面自己加载
              // res.app.coc.10086.cn/appother/js/public/cmcc.js 生成，但离屏页里它经常
              // 还没加载完，页面就一直停在「登录中」。这里按 cmcc.js 的同一套协议
              // （businessName + callbackSuccess_/callbackError_，最后调
              // newSdkInterface[方法名](json)）自己补一个，让页面能立刻走登录。
              function ensureSdk(){
                var had=!!(window.cmcc&&isFn(window.cmcc.checkUID));
                if(had) return 'native';
                var NAMES=['checkUID','getUserInfo','getLoginInfo','userStatus',
                  'checkSessionIsvalid','showLogin','getCatchInfo','getLocation',
                  'permissionIsEnable','showMsgSubscribeDialog','shareMessage','newWebview',
                  'goNativePage','setTitle','closeWindow','showNavCustomBtn','hiddenShareButton',
                  'showTitleBarRightIcons','openOtherAPP','copyToClipboard','getIDMPLoginToken',
                  'getDeviceMultiSimInfo','getNetInfo','getRiskLevel','getStatusBarHeight'];
                function callNative(os,st){
                  os=os||{};
                  os.businessName=st;
                  os.callbackSuccess='callbackSuccess_'+st;
                  os.callbackError='callbackError_'+st;
                  if(!isFn(os.success)) os.success=function(){};
                  if(!isFn(os.error)) os.error=function(){};
                  var box=window.cmcc;
                  var ld=window.leadeon||box;
                  var regs=function(t,st,s,e){
                    if(!t) return;
                    var ok=function(d){
                      var j=d; if(typeof j==='string'){ try{ j=JSON.parse(j) }catch(x){} }
                      try{ s(j) }catch(x){}
                    };
                    var no=function(d){
                      var j=d; if(typeof j==='string'){ try{ j=JSON.parse(j) }catch(x){} }
                      try{ e(j) }catch(x){}
                    };
                    t['callbackChangeSuccess_'+st]=s;
                    t['callbackChangeError_'+st]=e;
                    t['callbackSuccess_'+st]=ok;
                    t['callbackError_'+st]=no;
                    window['callbackSuccess_'+st]=ok;
                    window['callbackError_'+st]=no;
                  };
                  regs(box,st,os.success,os.error);
                  regs(ld,st,os.success,os.error);
                  try{ delete os.success; delete os.error }catch(e){}
                  if(window.newSdkInterface&&isFn(window.newSdkInterface[st])){
                    window.newSdkInterface[st](JSON.stringify(os));
                    return true;
                  }
                  return false;
                }
                var face=window.cmcc||{};
                var n=0;
                for(var i=0;i<NAMES.length;i++){
                  (function(nm){
                    if(isFn(face[nm])) return;
                    face[nm]=function(o){ return callNative(o,nm) };
                    n++;
                  })(NAMES[i]);
                }
                window.cmcc=face;
                if(!window.leadeon) window.leadeon=face;
                return 'shim+'+n;
              }

              function hookNet(){
                try{
                  var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;
                  var sr=XMLHttpRequest.prototype.setRequestHeader;
                  XMLHttpRequest.prototype.setRequestHeader=function(k,v){
                    // 页面请求带 phone 头，模块自己发的请求没有就被服务端 500。
                    // 这里把页面用过的 phone 原样记下来，后面的直发请求带上。
                    try{ if(String(k).toLowerCase()==='phone') window.__cmccPhone=String(v) }catch(e){}
                    return sr.apply(this,arguments);
                  };
                  XMLHttpRequest.prototype.open=function(m,u){ this.__qm=m; this.__qu=String(u||''); return op.apply(this,arguments) };
                  XMLHttpRequest.prototype.send=function(b){
                    var x=this;
                    try{
                      x.addEventListener('load',function(){
                        try{
                          if(/lottery|sign|draw|clock|receive|task|activity/i.test(x.__qu)){
                            NET.push({u:String(x.__qu).slice(0,220),s:x.status,b:String(x.responseText||'').slice(0,2000)})
                          }
                        }catch(e){}
                      });
                    }catch(e){}
                    return se.apply(this,arguments);
                  };
                  line('网络钩子: XHR 已挂');
                }catch(e){ line('网络钩子 XHR 失败: '+e) }
                try{
                  var of=window.fetch;
                  if(isFn(of)){
                    window.fetch=async function(input,init){
                      var u=typeof input==='string'?input:((input&&input.url)||'');
                      var r=await of.apply(this,arguments);
                      try{
                        if(/lottery|sign|draw|clock|receive|task|activity/i.test(u)){
                          var cp=r.clone();
                          cp.text().then(function(t){ NET.push({u:String(u).slice(0,220),s:r.status,b:String(t).slice(0,2000)}) })
                        }
                      }catch(e){}
                      return r;
                    };
                    line('网络钩子: fetch 已挂');
                  }
                }catch(e){ line('网络钩子 fetch 失败: '+e) }
              }

              function walk(v,d,out){
                if(!v||d>7) return;
                out.push(v);
                var cs=[]; try{ cs=v.$children||[] }catch(e){}
                for(var i=0;i<cs.length;i++) walk(cs[i],d+1,out);
              }
              function mnames(c){
                var out=[];
                try{
                  var m=c.$options&&c.$options.methods;
                  if(m){ for(var k in m){ if(isFn(m[k]) && out.indexOf(k)<0) out.push(k) } }
                }catch(e){}
                try{
                  var own=Object.getOwnPropertyNames(c);
                  for(var i=0;i<own.length;i++){
                    var k=own[i];
                    if(k.charAt(0)==='$'||k.charAt(0)==='_') continue;
                    var ok=false; try{ ok=isFn(c[k]) }catch(e){}
                    if(ok && out.indexOf(k)<0) out.push(k);
                  }
                }catch(e){}
                return out;
              }
              function cname(c){ try{ return (c.$options&&(c.$options.name||c.$options.__name))||'?' }catch(e){ return '?' } }
              function invoke(c,m,arg){
                var fn=null;
                try{ fn=c[m] }catch(e){}
                if(!isFn(fn)){ try{ fn=c.$options&&c.$options.methods&&c.$options.methods[m] }catch(e){} }
                if(!isFn(fn)){ try{ fn=c.type&&c.type.methods&&c.type.methods[m] }catch(e){} }
                if(!isFn(fn)) throw new Error('method not found: '+m);
                return fn.call(c,arg);
              }
              function isAxios(o){ try{ return o && isFn(o.post) && isFn(o.get) && o.interceptors && o.interceptors.request }catch(e){ return false } }
              function findAxios(){
                function add(o,src){
                  if(!isAxios(o)) return;
                  for(var i=0;i<AX.length;i++){ if(AX[i].i===o) return }
                  AX.push({i:o,src:src});
                }
                try{ for(var k in window){ try{ add(window[k],'window.'+k) }catch(e){} } }catch(e){}
                var R=null;
                try{
                  var gj=window.webpackJsonp||window.webpackChunk;
                  if(gj&&isFn(gj.push)){
                    var tok='__cmcc_canvas_probe__';
                    var mods={}; mods[tok]=function(m,e,r){ window.__cmccCanvasReq=r };
                    gj.push([[tok],mods,[[tok]]]);
                    R=window.__cmccCanvasReq||null;
                  }
                }catch(e){ line('webpack 探针失败: '+e) }
                if(R){
                  try{
                    var ids=Object.keys(R.c||{});
                    for(var i=0;i<ids.length;i++){
                      var ex=R.c[ids[i]]&&R.c[ids[i]].exports;
                      if(!ex) continue;
                      add(ex,'exports['+ids[i]+']');
                      try{
                        var ks=Object.getOwnPropertyNames(ex);
                        for(var j=0;j<ks.length;j++) add(ex[ks[j]],'exports['+ids[i]+'].'+ks[j]);
                      }catch(e){}
                    }
                  }catch(e){}
                }
                return AX;
              }

              function cookie(n){
                try{
                  var m=new RegExp('(^|; )'+n+'=([^;]*)').exec(document.cookie||'');
                  return m?decodeURIComponent(m[2]):'';
                }catch(e){ return '' }
              }
              function qs(n){
                try{ var m=new RegExp('[?&]'+n+'=([^&]*)').exec(location.href); return m?decodeURIComponent(m[1]):'' }catch(e){ return '' }
              }
              function pick(o,keys){
                for(var i=0;i<keys.length;i++){
                  try{ if(o&&o[keys[i]]!=null && o[keys[i]]!=='') return o[keys[i]] }catch(e){}
                }
                return '';
              }
              function ctxBody(id){
                var ch=qs('channelCode')||qs('channelId')||'';
                var yx=qs('yx')||'';
                var wtAc='';
                try{
                  wtAc=localStorage.getItem('wtAc')||'';
                  if(!wtAc){
                    var m=/wtAc=([^&]+)/.exec(location.href);
                    if(m) wtAc=decodeURIComponent(m[1]);
                  }
                  if(!wtAc && yx) wtAc='P00000054178_'+yx;
                }catch(e){}
                var ref='';
                try{
                  var ph=JSON.parse(sessionStorage.getItem('pageHistoryPage')||'[]');
                  for(var i=0;i<ph.length;i++){
                    if(ph[i]&&ph[i].pageKey){ ref=ph[i].pageKey+'&'+qs('pageKey'); break }
                  }
                }catch(e){}
                var cur='';
                try{ cur=location.pathname.split('/').pop()||'' }catch(e){}
                var prev='';
                try{
                  var rf=document.referrer||'';
                  var mm=/online[/]([A-Za-z0-9_-]+)/.exec(rf);
                  if(mm) prev=mm[1];
                }catch(e){}
                if(!ref) ref=(prev||qs('pageKey')||'')+'&'+(cur||qs('pageKey')||'');
                return {
                  activityId:id,
                  channelNo:ch,
                  referer:ref,
                  wtAc:wtAc,
                  wtAcId:cookie('JSESSIONID'),
                  operDfp:null
                };
              }
              async function post(path,body){
                var err='';
                var ph=''; try{ ph=window.__cmccPhone||'' }catch(e){}
                for(var i=0;i<AX.length;i++){
                  try{
                    var inst=AX[i].i;
                    var cfg=ph?{headers:{phone:ph}}:undefined;
                    var ret=cfg?inst.post(path,body,cfg):inst.post(path,body);
                    if(ret&&isFn(ret.then)) ret=await ret;
                    return {ok:true,src:AX[i].src,data:(ret&&ret.data!==undefined)?ret.data:ret};
                  }catch(e){ err=String(e) }
                }
                try{
                  var h={'Content-Type':'application/json;charset=utf-8'};
                  if(ph) h.phone=ph;
                  var f=await fetch(path,{method:'POST',credentials:'include',headers:h,
                    body:JSON.stringify(body)});
                  var t=await f.text();
                  return {ok:true,src:'fetch',data:t};
                }catch(e){ return {ok:false,err:(err?err+' / ':'')+String(e)} }
              }
              function codeOf(data){
                try{
                  if(typeof data==='string') data=JSON.parse(data);
                  if(data&&data.code!=null) return String(data.code);
                  if(data&&data.resultCode!=null) return String(data.resultCode);
                }catch(e){}
                return '';
              }
              // v3.87 P0：判定尺子。只认「提交类接口 + 业务成功」的回执。
              // 17-55 日志实证：幸运三日签把 taskSignRecord（查询，counts:0 / rows:[]）
              // 当成签到成功，页面「已累计签到0次」纹丝不动却报 OK。
              var QUERY_RE=/(record|list|count|remain|residue|current|check|query|detail|info|status|config|visibility|mutex|rule|calendar|myaward|awardlist|signrule|getsign)/i;
              // v3.88：真签到接口可能叫 accumulateSign / sign / addSign …
              // QUERY_RE 会先排除 record/list/count/check/...，放宽不会误收查询。
              var SUBMIT_RE=/(signIn|doSign|accumulate|signAdd|addSign|signSave|signSubmit|userSign|signToday|clockIn|doClockIn|receiveAward|doReceive|goLottery|lottery[/]lottery|drawPrize|flipCard|turnCard|[/]sign([/]|$|[^A-Za-z])|打卡|签到提交)/i;
              var FAIL_RE=/(-61004|没有抽奖次数|访问过于频繁|未登录|登录异常|系统异常|请重新登录|"success":false|"code":"403"|"code":"500"|"code":403|"code":500)/i;
              function bizOk(b){
                if(!b) return false;
                if(FAIL_RE.test(b)) return false;
                var c='';
                try{ var d=JSON.parse(b); c=(d&&d.code!=null)?String(d.code):((d&&d.resultCode!=null)?String(d.resultCode):'') }catch(e){}
                if(c && !/^(0|0000|000000|200|60000|success)$/i.test(c)) return false;
                return true;
              }
              function isSubmit(e){
                if(!e) return false;
                var st=Number(e.s)||0;
                if(st<200||st>=400) return false;
                var u=String(e.u||'');
                if(QUERY_RE.test(u)) return false;
                if(!SUBMIT_RE.test(u)) return false;
                // v3.89：20:00 日志实证 —— accumulateSignNext 发出的
                // task/accumulateSign 回的是「签到奖励配置」数组
                // （awardName=签到即得2GB / skuName=幸运三日签首签奖励），
                // 它让 sign.ok=1，真正的 accumulateSign 再没机会跑。
                if(/task[/]accumulateSign/i.test(u)&&/"awardName"/.test(String(e.b||''))) return false;
                return bizOk(String(e.b||''));
              }
              function submitsAfter(from){
                var a=[];
                for(var i=from;i<NET.length;i++){
                  if(isSubmit(NET[i])) a.push({u:NET[i].u,s:NET[i].s,b:cut(NET[i].b,160)});
                }
                return a;
              }
              // v3.90：21:10 日志实证 —— 提交接口明确回 -9014「本日已签到」。
              // 这不是失败，是今天已经签过了。只认「提交类接口」里的这条回执，
              // 查询接口里的「已签到」文案一律不算，避免重演 17:55 的假 OK。
              function signedAlreadyReply(){
                for(var i=0;i<NET.length;i++){
                  var u=String(NET[i].u||''), b=String(NET[i].b||'');
                  if(QUERY_RE.test(u)) continue;
                  if(!SUBMIT_RE.test(u)) continue;
                  if(!/(-9014|本日已签到|今日已签到|次日再来签到)/.test(b)) continue;
                  return cut(b,140);
                }
                return '';
              }
              function signCount(t){
                var m=/已累计签到[^0-9]*([0-9]+)[^0-9]*次/.exec(String(t||''));
                return m?parseInt(m[1],10):-1;
              }
              function dataOf(data){
                try{
                  if(typeof data==='string') data=JSON.parse(data);
                  if(data&&data.data!==undefined) return data.data;
                }catch(e){}
                return null;
              }
              async function remainOf(id){
                var paths=['/coc3/coc3-market-activity/cmpp-manage/lottery/residueLotteryCount',
                           '/coc3-market-activity/cmpp-manage/lottery/residueLotteryCount'];
                for(var i=0;i<paths.length;i++){
                  var r=await post(paths[i],{activityId:id});
                  if(r.ok && codeOf(r.data)==='200'){
                    var d=dataOf(r.data);
                    if(d===null||d===undefined||d==='') return -2;
                    var n=parseInt(d,10);
                    return isNaN(n)?-2:n;
                  }
                }
                return -2;
              }
              // 页面自己的请求带 phone 头，模块直发的请求没有，常常 500。
              // 直发失败时，退回去读页面自己那条 residueLotteryCount 的结果。
              function netRemain(){
                for(var i=NET.length-1;i>=0;i--){
                  var e=NET[i];
                  if(!/residueLotteryCount/i.test(e.u)) continue;
                  if(!(e.s>=200&&e.s<400)) continue;
                  try{
                    var d=JSON.parse(e.b);
                    if(d&&d.data!==undefined&&d.data!==null&&!isNaN(Number(d.data))) return Number(d.data);
                  }catch(x){}
                }
                return -2;
              }
              // 次数读不到时的最后一级：直接看页面自己怎么写的。
              // 周周三期的首屏就是「您有 3 次翻牌机会」，比接口更可靠；
              // 组件里也常留一份 residueLotteryCount / residueNum。
              function remainFromPage(){
                try{
                  var t=(document.body?document.body.innerText:'').replace(/\\s+/g,' ');
                  var m=/([0-9]+)\\s*次(翻牌|抽奖)/.exec(t);
                  if(m) return parseInt(m[1],10);
                }catch(e){}
                for(var i=0;i<comps.length;i++){
                  var n='';
                  try{ n=cname(comps[i]) }catch(e){}
                  if(!/lottery|draw|choujiang/i.test(n)) continue;
                  var d=null;
                  try{ d=comps[i].$data }catch(e){}
                  if(!d) continue;
                  for(var k in d){
                    if(!/residue|remain|chance|left|num|count/i.test(k)) continue;
                    var v=Number(d[k]);
                    if(!isNaN(v) && v>=0 && v<100) return v;
                  }
                }
                return -2;
              }
              // 从请求钩子里捞这一次的抽奖回执，供「点卡片」和「调方法」共用。
              function lotteryResult(mark){
                var hit=null;
                for(var q=mark;q<NET.length;q++){
                  if(/\\/lottery\\/lottery/.test(NET[q].u)){ hit=NET[q]; break }
                }
                if(!hit) return null;
                var code='',msg='';
                try{
                  var d=JSON.parse(hit.b);
                  code=String(d.code==null?'':d.code);
                  msg=String(d.msg||d.message||'');
                }catch(e){}
                return {ok:(code==='60000'||code==='200'),code:code,msg:msg,
                        risk:riskOf(hit.b),data:hit.b,via:'page'};
              }
              // 点页面上的翻牌卡片。三期页面根本没有「翻牌」两个字，按钮就是三张
              // 背面卡片，只能按 class / 近正方形尺寸去找，点完只认 /lottery/lottery 回执。
              async function drawByClick(id){
                var mark=NET.length;
                var tgt=null,area=0;
                var tags=document.querySelectorAll('div,li,img,span');
                for(var i=0;i<tags.length;i++){
                  var e=tags[i];
                  var cls=String(e.className||'')+' '+(e.id||'');
                  if(!/card|flip|poker|fanpai|turn/i.test(cls)) continue;
                  var r=null;
                  try{ r=e.getBoundingClientRect() }catch(x){ continue }
                  if(!r||r.width<40||r.height<40) continue;
                  var ratio=r.width/r.height;
                  if(ratio<0.6||ratio>1.6) continue;
                  if(ownText(e).length>4) continue;
                  var a=r.width*r.height;
                  if(a>area){ area=a; tgt=e }
                }
                if(!tgt){
                  // 三张牌也可能是没有 card/flip class 的 img，按近正方形 + 尺寸兜底。
                  // 只在 class 完全没线索时才用，取面积最大那张（牌位比奖品图标大），
                  // 并排除屏幕上部 20% —— 那里是 banner 与奖品展示区。
                  var imgs2=document.querySelectorAll('img');
                  area=0;
                  for(var m2=0;m2<imgs2.length;m2++){
                    var im2=imgs2[m2],r3=null;
                    try{ r3=im2.getBoundingClientRect() }catch(x){ continue }
                    if(!r3||r3.width<60||r3.height<60) continue;
                    var ra=r3.width/r3.height;
                    if(ra<0.7||ra>1.4) continue;
                    if(r3.top<window.innerHeight*0.2) continue;
                    var a3=r3.width*r3.height;
                    if(a3>area){ area=a3; tgt=im2 }
                  }
                  if(tgt){
                    var rr0=tgt.getBoundingClientRect();
                    line('  翻牌卡片按方形图片命中: '+Math.round(rr0.width)+'x'+Math.round(rr0.height));
                  }
                }
                if(!tgt){
                  // 没有 class 线索时退回按文字点（有些活动卡片上写「立即开启」）。
                  var t=clickDrawExact();
                  if(!t) return null;
                  await wait(2500);
                  return lotteryResult(mark);
                }
                if(!tapEl(tgt)) return null;
                await wait(2500);
                return lotteryResult(mark);
              }
              // getTaskList 里拿了任务清单：次数为 0 时先把没做的任务做了。
              function readTasks(){
                var out=[];
                for(var i=NET.length-1;i>=0;i--){
                  if(!/getTaskList/i.test(NET[i].u)) continue;
                  try{
                    var d=JSON.parse(NET[i].b);
                    var arr=d&&d.data&&d.data.taskQueryTimeVOS;
                    if(!arr||!arr.length) continue;
                    for(var j=0;j<arr.length;j++){
                      var t=arr[j]||{};
                      var done=Number(t.completedNum||0), all=Number(t.totalNum||0);
                      if(t.taskId && done<all) out.push(Number(t.taskId));
                    }
                    break;
                  }catch(x){}
                }
                if(!out.length){
                  // 页面自己的 getTaskList 可能在脚本挂上钩子之前就发完了，
                  // 这时从任务组件的 data 里把 taskId 抠出来。
                  var seen={};
                  for(var ci=0;ci<COMPS.length;ci++){
                    if(!/task/i.test(cname(COMPS[ci]))) continue;
                    var js='';
                    try{ js=JSON.stringify(COMPS[ci].$data||{}) }catch(e){}
                    if(!js) continue;
                    var mm=js.match(/"taskId"\\s*:\\s*(\\d+)/g)||[];
                    for(var k=0;k<mm.length;k++){
                      var num=Number((mm[k].match(/(\\d+)/)||[])[1]);
                      if(num && !seen[num]){ seen[num]=1; out.push(num) }
                    }
                  }
                }
                return out;
              }
              // v3.91：21:33 日志 —— SCORE_REFUSE / risk:true / risk-verify 是
              // 服务端「账号风控」，不是普通限频。以前一律当「访问过于频繁」
              // 等 3 秒补一发，反而把账号风控等级越推越高。
              function riskOf(data){
                try{
                  var d=typeof data==='string'?JSON.parse(data):data;
                  var inner=d&&d.data;
                  var s=String((d&&(d.msg||d.message))||'')+' '
                        +String((inner&&inner.explain)||'')+' '
                        +String((inner&&inner.url)||'');
                  if(/SCORE_REFUSE|risk-verify|可能存在风险|暂无法继续/i.test(s)) return true;
                  if(inner&&inner.risk===true) return true;
                }catch(e){}
                return false;
              }
              // v4.2.2：11-11 10:39 日志实证 —— 模块直连 lottery 的请求缺
              // gr-risk-p3 头与 wasmParams 字段（请求体 referer 也是空的），必回
              // SCORE_REFUSE/risk:true；同一分钟用户手动点翻牌，页面自己发同一接口
              // 就中了奖（code 60000）。风控参数只有页面里的 SDK 能生成，模块不伪造，
              // 改成调页面组件自己的 goLottery，由页面组织请求体与风控头。
              // 只有组件里确实没有这个方法时，才退回直连。
              async function drawViaPage(id,list){
                var c=null;
                for(var i=0;i<list.length;i++){
                  var one=list[i];
                  try{
                    if(isFn(one.goLottery)){ c=one; break }
                    var ms=one.$options&&one.$options.methods;
                    if(ms&&isFn(ms.goLottery)){ c=one; break }
                  }catch(e){}
                }
                if(!c) return null;
                var mark=NET.length;
                // 页面按钮一般绑成 @click="goLottery"（无参），但有的版本要传奖品对象。
                // 按方法声明了几个参数决定先试哪种：11-11 12:39 无参调用直接抛
                // 「Cannot read properties of undefined (reading '__ob__')」，说明
                // 三期这版是要对象的，盲猜会白跑。
                var fn=null;
                try{ fn=c.goLottery }catch(e){}
                if(!isFn(fn)){ try{ fn=c.$options&&c.$options.methods&&c.$options.methods.goLottery }catch(e){} }
                var item=null;
                try{
                  var cand=c.picLotteryList||c.lotteryList||c.picList||c.list||[];
                  item=(cand&&cand.length)?cand[0]:null;
                }catch(e){}
                var arity=0;
                try{ arity=fn?fn.length:0 }catch(e){}
                var tries=arity===0
                    ? [undefined,id,{activityId:id}]
                    : [item,id,{activityId:id},undefined];
                for(var t=0;t<tries.length;t++){
                  try{
                    var rt=invoke(c,'goLottery',tries[t]);
                    if(rt&&isFn(rt.then)) await rt;
                  }catch(e){ line('  goLottery 调用异常('+t+'): '+e) }
                  await wait(2600);
                  if(NET.length>mark) break;
                }
                var res=lotteryResult(mark);
                if(res) return res;
                return {ok:false,code:'',msg:'页面没发抽奖请求',risk:false,via:'page'};
              }
              async function lotteryOnce(id){
                var paths=['/coc3/coc3-market-activity/cmpp-manage/lottery/lottery',
                           '/coc3-market-activity/cmpp-manage/lottery/lottery'];
                for(var i=0;i<paths.length;i++){
                  var r=await post(paths[i],ctxBody(id));
                  if(!r.ok) continue;
                  var c=codeOf(r.data);
                  if(c==='60000'||c==='200') return {ok:true,data:r.data,risk:false};
                  var msg='';
                  try{ var d=typeof r.data==='string'?JSON.parse(r.data):r.data; msg=String((d&&(d.msg||d.message))||'') }catch(e){}
                  return {ok:false,code:c,msg:msg,risk:riskOf(r.data)};
                }
                return {ok:false,code:'',msg:'没有可用的请求层',risk:false};
              }
              function prizeOf(data){
                try{
                  var d=typeof data==='string'?JSON.parse(data):data;
                  var list=d&&d.data&&d.data.multiPoolLotteryVOList;
                  if(list&&list.length){
                    var n=list[0].activitySales&&list[0].activitySales.salesUnitName;
                    if(n) return String(n);
                  }
                  var a=d&&d.data&&d.data.activitySales;
                  if(a&&a.salesUnitName) return String(a.salesUnitName);
                }catch(e){}
                return '';
              }

              function signNames(ms){
                var out=[], luck=[];
                // v3.88 B-1：19:08 日志实证 —— 幸运三日签的真签到方法叫
                // accumulateSign / accumulateSignNext，挂在匿名组件上。旧白名单
                // 只认 sign/doSign/clockIn，候选恒为 0，于是兜底点了「签到记录」，
                // 页面「已累计签到0次」纹丝不动却报签到成功。
                for(var i=0;i<ms.length;i++){
                  var m=ms[i];
                  if(/^(accumulateSign|accumulateSignNext)$/i.test(m)){ luck.push(m); continue }
                  if(/^(query|get|check|load|init|open|show|close|jump|go)/i.test(m)) continue;
                  if(/^(doSign|sign|signIn|doSignIn|signToday|signClick|onSign|onSignClick|handleSign|handleSignClick|toSign|clockIn|doClockIn|receive|doReceive|receiveAward|receivePrize|getPrize|lingqu|doLingqu|领取|签到)$/i.test(m)) out.push(m);
                }
                // 主动作 accumulateSign 排最前，Next 只作兜底；避免先弹「下一步」窗。
                luck.sort(function(a,b){
                  var pa=/^accumulateSign$/i.test(a)?0:1, pb=/^accumulateSign$/i.test(b)?0:1;
                  return pa-pb;
                });
                return luck.concat(out);
              }
              function taskNames(ms){
                var out=[];
                for(var i=0;i<ms.length;i++){
                  if(/^(sendTaskVisit|taskVisit|visitTask|completeTask|receiveTask|doTask|finishTask)$/i.test(ms[i])) out.push(ms[i]);
                }
                return out;
              }
              function drawNames(ms){
                var out=[];
                for(var i=0;i<ms.length;i++){
                  var m=ms[i];
                  // 只认明确的「抽一次」方法。以前这里先把 go/open 开头的一律排除，
                  // 结果 goLottery / openCard 这种真按钮被误杀了。
                  if(/^(doLottery|lottery|draw|doDraw|startDraw|flip|doFlip|flipCard|openCard|turnCard|turnOver|drawCard|doTurn|goLottery|goDraw|goFlip|抽奖|翻牌)$/i.test(m)) out.push(m);
                }
                return out;
              }
              // v3.88：19:08 日志里兜底点了「签到记录」——那是导航文本，
              // 点了只开记录弹窗，不是签到。这类词一律不点。
              var CLICK_BAD=/记录|累计|规则|奖品|说明|详情|日历|我的|已签|第[0-9]+次/i;
              function clickText(words){
                var tags=document.querySelectorAll('button,a,div,span,p');
                for(var i=0;i<tags.length;i++){
                  var e=tags[i];
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>14||CLICK_BAD.test(t)) continue;
                  for(var j=0;j<words.length;j++){
                    if(t.indexOf(words[j])<0) continue;
                    var cls=(e.className||'')+' '+(e.id||'');
                    if(!/btn|button|sign|draw|lottery|card|click/i.test(cls)) continue;
                    try{ e.click(); return t }catch(x){}
                  }
                }
                return '';
              }

              // v3.89：20:00 日志里兜底点到了整页根 DIV（文本是整页文案），
              // 等于什么都没点。这里只认「元素自己的直接文本」——子节点里
              // 的「签到」不算，必须是这个元素本身写着「签到」。
              function ownText(e){
                var s='';
                try{
                  for(var i=0;i<e.childNodes.length;i++){
                    var n=e.childNodes[i];
                    if(n.nodeType===3) s+=n.nodeValue;
                  }
                }catch(x){}
                return s.replace(/\\s+/g,'').trim();
              }
              // 完整 touch 序列 + elementFromPoint 校正落点，再补一次 click。
              function tapEl(e){
                try{
                  var r=e.getBoundingClientRect();
                  if(!r||r.width<2||r.height<2) return false;
                  var x=r.left+r.width/2, y=r.top+r.height/2;
                  var tgt=e;
                  try{
                    var f=document.elementFromPoint(x,y);
                    if(f&&(f===e||e.contains(f)||f.contains(e))) tgt=f;
                  }catch(x2){}
                  var seq=['touchstart','touchend','mousedown','mouseup','click'];
                  for(var i=0;i<seq.length;i++){
                    var tp=seq[i];
                    try{
                      var ev;
                      if(tp.indexOf('touch')===0 && typeof TouchEvent!=='undefined'){
                        ev=new TouchEvent(tp,{bubbles:true,cancelable:true,
                          touches:tp==='touchstart'?[{identifier:1,target:tgt,clientX:x,clientY:y}]:[],
                          changedTouches:[{identifier:1,target:tgt,clientX:x,clientY:y}]});
                      } else {
                        ev=new MouseEvent(tp,{bubbles:true,cancelable:true,clientX:x,clientY:y});
                      }
                      tgt.dispatchEvent(ev);
                    }catch(x3){}
                  }
                  try{ tgt.click() }catch(x4){}
                  return true;
                }catch(e2){ return false }
              }
              // 11-10 23:19：周周有好礼直发 /lottery 接口时体里是 operDfp:null，
              // 服务端判成「风险操作」(SCORE_REFUSE/risk:true)，一次都没抽成。
              // 改成点页面自己的翻牌按钮 —— 事件进到 Vue 的 click 处理器里，
              // 设备指纹、referer 这些由页面自己补齐，和用户手点完全一致。
              function clickDrawExact(){
                var want=/翻牌|抽奖|开启|立即抽|抽取/;
                var bad=/记录|规则|说明|详情|我的|奖品|次数|剩余|已抽/;
                var tags=document.querySelectorAll('button,a,div,span');
                var best=null,area=0;
                for(var i=0;i<tags.length;i++){
                  var e=tags[i];
                  var t=ownText(e);
                  if(!t||t.length>8) continue;
                  if(bad.test(t)||!want.test(t)) continue;
                  var r=e.getBoundingClientRect();
                  if(!r||r.width<18||r.height<18) continue;
                  var a=r.width*r.height;
                  if(a>area){ area=a; best=e }
                }
                if(best){
                  var txt=ownText(best)||String(best.innerText||'').replace(/\\s+/g,'').slice(0,8);
                  if(tapEl(best)) return txt;
                }
                // 「立即抽好礼」这块在页面上是图片（橙底白字长条），ownText 恒为空，
                // 上面那圈文字匹配永远漏掉它。这里单独按 img 收一遍：
                // 名称命中优先，其次看形状（横条按钮），顶部 8% 的 banner 排除。
                var imgs=document.querySelectorAll('img');
                var cands=[];
                for(var m=0;m<imgs.length;m++){
                  var im=imgs[m],ir=null;
                  try{ ir=im.getBoundingClientRect() }catch(x){ continue }
                  if(!ir||ir.width<80||ir.width>460) continue;
                  if(ir.height<28||ir.height>140) continue;
                  var ratio=ir.width/Math.max(ir.height,1);
                  if(ratio<1.2||ratio>8) continue;
                  var tag2=(im.src||'')+' '+(im.className||'')+' '+(im.alt||'')
                           +' '+(im.getAttribute('data-src')||'');
                  var named=/lottery|draw|flip|card|btn|button|chou|prize|gift/i.test(tag2);
                  var inBtn=false;
                  try{ inBtn=!!im.closest('button,a,[class*=btn],[class*=Btn]') }catch(x){}
                  var shape=(ratio>=1.5&&ratio<=4.5);
                  if(!named&&!inBtn&&!shape) continue;
                  if(!named&&!inBtn&&ir.top<window.innerHeight*0.25) continue;
                  var sc=(named?60:0)+(inBtn?30:0)+(shape?15:0)+(ir.width>=140?10:0);
                  cands.push({e:im,s:sc,w:Math.round(ir.width),h:Math.round(ir.height),n:named});
                }
                cands.sort(function(a,b){return b.s-a.s});
                for(var q=0;q<cands.length&&q<3;q++){
                  if(tapEl(cands[q].e)){
                    line('  图片按钮候选 '+cands.length+' 个，点 '+cands[q].w+'x'+cands[q].h
                         +(cands[q].n?'（名称命中）':'（形状）'));
                    return '图片按钮 '+cands[q].w+'x'+cands[q].h;
                  }
                }
                if(cands.length) line('  图片按钮候选 '+cands.length+' 个，但都没点下去');
                return '';
              }
              // v3.89：只点「自己就写着签到/打卡」的元素，文本必须精确等于。
              function clickSignExact(){
                var words=['立即签到','签到','立即打卡','打卡','立即领取','领取'];
                var all=document.querySelectorAll('button,a,div,span,p,i,em');
                var hits=[];
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vok(e)) continue;
                  var t=ownText(e);
                  if(!t||t.length>6) continue;
                  var hit='';
                  for(var j=0;j<words.length;j++){
                    if(t===words[j]){ hit=t; break }
                  }
                  if(!hit) continue;
                  var r=null;
                  try{ r=e.getBoundingClientRect() }catch(x){ continue }
                  if(r.width<16||r.height<8) continue;
                  hits.push({e:e,t:hit,w:Math.round(r.width),h:Math.round(r.height)});
                }
                line('  v3.89 精确签到候选: '+hits.length+' 个');
                for(var hi=0;hi<hits.length&&hi<10;hi++){
                  line('    #'+hi+' '+hits[hi].t+' '+hits[hi].w+'x'+hits[hi].h+' '+(hits[hi].e.tagName||'')+'.'+String(hits[hi].e.className||'').slice(0,60));
                }
                // 按词优先（「立即签到」>「签到」），同词按 DOM 顺序点第一个。
                for(var wi=0;wi<words.length;wi++){
                  for(var hk=0;hk<hits.length;hk++){
                    if(hits[hk].t!==words[wi]) continue;
                    if(tapEl(hits[hk].e)){
                      line('  v3.89 已点: '+hits[hk].t+' #'+hk);
                      return hits[hk].t;
                    }
                  }
                }
                return '';
              }
              // v3.89：accumulateSign 报 hasRemainCount 读不到，说明它要一个
              // 带该字段的对象做参数。从组件 data 里把现成的找出来，不硬造。
              function findHasRemain(obj, depth, seen){
                if(!obj||typeof obj!=='object'||depth>3) return null;
                for(var s=0;s<seen.length;s++){ if(seen[s]===obj) return null }
                seen.push(obj);
                try{ if(obj.hasRemainCount!==undefined) return obj }catch(x){}
                var keys=[];
                try{ keys=Object.keys(obj) }catch(x){}
                for(var i=0;i<keys.length && i<60;i++){
                  var v=null;
                  try{ v=obj[keys[i]] }catch(x){}
                  if(v&&typeof v==='object'){
                    var r=findHasRemain(v,depth+1,seen);
                    if(r) return r;
                  }
                }
                return null;
              }

              // ---- 非 Vue 页（coc web6）用的宽松点击 ----
              // 7 日打卡页是 web6 的普通 H5，没有 __vue__，但页面上确实有「打卡」按钮。
              // 这里不猜接口，只点真实按钮，再用网络回执/文案变化验证。
              function vok(e){
                try{
                  var r=e.getBoundingClientRect();
                  if(!r||r.width<2||r.height<2) return false;
                  var s=getComputedStyle(e);
                  return s.display!=='none' && s.visibility!=='hidden' && s.opacity!=='0';
                }catch(x){ return false }
              }
              function cok(e){
                try{
                  var tag=(e.tagName||'').toUpperCase();
                  if(tag==='BUTTON'||tag==='A') return true;
                  var cls=String(e.className||'')+' '+(e.id||'');
                  if(/btn|button|click|sign|clock|card|receive|submit/i.test(cls)) return true;
                  if(getComputedStyle(e).cursor==='pointer') return true;
                }catch(x){}
                return false;
              }
              function hitAny(words,max){
                var all=document.querySelectorAll('button,a,div,span,i');
                var n=0;
                for(var i=0;i<all.length && n<max;i++){
                  var e=all[i];
                  if(!vok(e)||!cok(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>10) continue;
                  if(/已打卡|已签到|已领取|已完成|明天再来/.test(t)) continue;
                  for(var j=0;j<words.length;j++){
                    if(t.indexOf(words[j])<0) continue;
                    try{ e.click(); n++; return t }catch(x){}
                  }
                }
                return '';
              }
              function closePopDom(){
                var words=['确定','确认','知道了','开心收下','收下','关闭'];
                var all=document.querySelectorAll('button,a,div,span');
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vok(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>6) continue;
                  for(var j=0;j<words.length;j++){
                    if(t!==words[j]) continue;
                    if(!cok(e)) continue;
                    try{ e.click(); return t }catch(x){}
                  }
                }
                return '';
              }

              (async function(){
                try{
                  line('=== 画布活动 v3.39 ===');
                  line('URL: '+cut(location.href,220));
                  hookNet();
                  // coc 页面进页先走 checkUID / getUserInfo，这段时间文案是
                  // 「加载中 92% 登录中」，组件树是空的 —— 找组件一定是 0 个。
                  // 先等它把登录走完，再找 Vue 和业务组件。
                  var sdk=ensureSdk();
                  line('登录桥: '+sdk+' · cmcc='+(window.cmcc?'有':'无')
                    +' · newSdkInterface='+(window.newSdkInterface?'有':'无'));
                  var t0='';
                  var loginWait=0;
                  for(var w=0; w<60; w++){
                    t0=btext();
                    if(t0.length>0 && !/登录中|加载中|校验中|请稍候/.test(t0)) break;
                    loginWait++;
                    await wait(500);
                  }
                  if(loginWait>0) line('等登录: '+(loginWait*500)+'ms');
                  var root=null;
                  for(var i=0;i<20;i++){
                    try{
                      var el=document.querySelector('#app')||document.querySelector('#root');
                      root=el&&el.__vue__;
                      // Vue 3 的挂在容器 __vue_app__ 上，没有 __vue__。
                      if(!root && el&&el.__vue_app__&&el.__vue_app__._instance) root=el.__vue_app__._instance;
                    }catch(e){}
                    if(root) break;
                    await wait(500);
                  }
                  t0=btext();
                  if(!root){
                    line('没有 Vue 根组件');
                    // coc web6 的 7 日打卡页就是这种：普通 H5，没有 __vue__。
                    // 先把「今天到底签没签」用页面自己的按钮问清楚，别拿文案猜。
                    var markV=NET.length;
                    var dom={try:0,ok:0,hits:[],before:cut(t0,160),after:''};
                    // v4.2.0：11-11 08:22 日志 —— 7 日打卡页文案是「今日抽奖次数已用完」，
                    // 说明今天已经打过卡（打卡才有抽奖次数）。旧正则不认这句，
                    // 脚本以为没打卡，接着找按钮找不到，最后报「没找到打卡入口」。
                    var already=/已打卡|今日已签|今天已签|明天再来|抽奖次数已用完|机会已用完|已连续/.test(t0);
                    if(NEED_SIGN && !already){
                      var mark0=NET.length;
                      // v4.2.1：7 日打卡页的按钮文案各版不一样（「立即打卡」「打卡」
                      // 之外还有「立即领取」「抽奖」这类入口），11-11 09:54 的首屏里
                      // 一个「打卡」字样都没有，旧词表直接空手而归。补齐常见入口文案。
                      var got=hitAny(['立即打卡','今日打卡','去打卡','打卡','签到',
                                      '立即领取','领取红包','立即抽奖','抽奖'],1);
                      if(!got){
                        // 一个都没点到：把可点元素原样打进日志，下一版按真实结构补目标，
                        // 不再靠猜（这条只在失败时出现，不影响正常流程）。
                        var cand=[];
                        try{
                          var all2=document.querySelectorAll(
                            'button,a,[onclick],[class*=btn],[class*=sign],[class*=clock],[class*=draw]');
                          for(var k2=0;k2<all2.length && cand.length<30;k2++){
                            var e2=all2[k2];
                            if(!vok(e2)) continue;
                            var t2=(e2.innerText||'').replace(/\\s+/g,' ').trim();
                            if(!t2) continue;
                            cand.push(e2.tagName.toLowerCase()
                              +'.'+String(e2.className||'').slice(0,20)+'='+cut(t2,16));
                          }
                        }catch(e2){}
                        line('  可点元素: '+cut(cand.join(' | '),900));
                      }
                      if(got){
                        dom.try++; dom.hits.push(got);
                        line('  非Vue页点击: '+got);
                        await wait(1800);
                        var cf=closePopDom();
                        if(cf) line('  弹层确认: '+cf);
                        await wait(900);
                        var tj=btext();
                        dom.after=cut(tj,160);
                        var netHit=false;
                        for(var q0=mark0;q0<NET.length;q0++){
                          if(isSubmit(NET[q0])){ netHit=true; break }
                        }
                        // v3.87：文案只认明确结果，「已打卡/明天再来」在日历里天天都有。
                        if(netHit || /打卡成功|已领取|今日已打卡|今天已打卡/.test(tj)){
                          dom.ok=1;
                          line('  非Vue页打卡回执: '+(netHit?'提交类回执':'文案明确结果'));
                        }
                      }
                    }
                    t0=btext();
                    done({stage:'no-vue',text:t0,text1:t0,dom:dom,
                          already:already?1:0,title:document.title||'',
                          sdk:sdk,loginWait:loginWait,submits:submitsAfter(markV)});
                    return;
                  }
                  var comps=[]; walk(root,0,comps);
                  COMPS=comps;
                  line('组件: '+comps.length+' 个');
                  // 组件名全量打出来：签到/抽奖失败时，这就是改下一版的依据。
                  var cn=[];
                  for(var ci=0;ci<comps.length && ci<40;ci++) cn.push(cname(comps[ci]));
                  line('组件名: '+cut(cn.join(','),700));
                  try{
                    var ifr=document.querySelectorAll('iframe');
                    if(ifr.length){
                      var iu=[];
                      for(var fi=0;fi<ifr.length && fi<5;fi++) iu.push(String(ifr[fi].src||''));
                      line('内嵌页: '+cut(iu.join(' | '),400));
                    }
                  }catch(e){}
                  // 任务组件的 data 里有 taskId / jumpUrl，次数为 0 时靠它找回机会。
                  for(var ti=0;ti<comps.length;ti++){
                    if(!/task/i.test(cname(comps[ti]))) continue;
                    var td='';
                    try{ td=JSON.stringify(comps[ti].$data||{}) }catch(e){}
                    if(td && td!=='{}') line('  任务组件 '+cname(comps[ti])+' 数据: '+cut(td,700));
                    try{
                      var pk=[];
                      for(var kk in (comps[ti].$props||{})) pk.push(kk);
                      if(pk.length) line('  任务组件 '+cname(comps[ti])+' props: '+cut(pk.join(','),300));
                    }catch(e){}
                  }

                  // 组件清单：方法名 + 文案，失败时这就是改下一版的依据。
                  for(var i=0;i<comps.length && i<40;i++){
                    var ms=mnames(comps[i]);
                    var keep=[];
                    for(var j=0;j<ms.length;j++){
                      if(/sign|clock|receive|draw|lottery|flip|turn|task|award|prize/i.test(ms[j])) keep.push(ms[j]);
                    }
                    if(keep.length) line('  ['+cname(comps[i])+'] '+cut(keep.join(','),300));
                  }

                  var sign={try:0,ok:0,names:[]};
                  var draw={try:0,ok:0,prize:'',names:[],seen:0,left:0,rems:[]};

                  // ---- 签到 ----
                  if(NEED_SIGN){
                    var cands=[];
                    for(var i=0;i<comps.length;i++){
                      var ms=signNames(mnames(comps[i]));
                      if(!ms.length) continue;
                      var t=ctext(comps[i]);
                      var w=0;
                      if(t.indexOf('签到')>=0||t.indexOf('领取')>=0||t.indexOf('打卡')>=0) w-=2;
                      cands.push({c:comps[i],ms:ms,w:w,n:cname(comps[i]),t:t});
                    }
                    cands.sort(function(a,b){ return a.w-b.w });
                    line('签到候选: '+cands.length+' 个');
                    // v3.89：把签到组件 data 的字段键打出来。20:00 日志里
                    // accumulateSign 报 hasRemainCount undefined，靠这些键名
                    // 下一段就能精确定位签到项对象（signList/currentSign…）。
                    for(var di=0;di<cands.length;di++){
                      try{
                        var dc=cands[di].c;
                        var dk=[];
                        for(var ky in (dc.$data||{})) dk.push(ky);
                        try{ for(var ky2 in (dc.setupState||{})) dk.push('setup:'+ky2) }catch(x){}
                        try{ for(var ky3 in (dc._data||{})) dk.push('data:'+ky3) }catch(x){}
                        line('  签到组件数据键 '+cname(dc)+': '+cut(dk.join(','),400));
                      }catch(e){}
                    }
                    for(var i=0;i<cands.length && sign.ok===0;i++){
                      var one=cands[i];
                      for(var j=0;j<one.ms.length && sign.ok===0;j++){
                        sign.try++;
                        var mark=NET.length;
                        try{
                          // v3.89：accumulateSign 需要「带 hasRemainCount 的签到项
                          // 对象」做参数，无参调用直接 TypeError。只找页面现成的，
                          // 不硬造；找不到就按原样调用，交给下面的精确点击兜底。
                          var sarg=null;
                          if(/accumulateSign/i.test(one.ms[j])){
                            try{ sarg=findHasRemain(one.c.$data,0,[]) }catch(x){}
                            if(!sarg){ try{ sarg=findHasRemain(one.c.$props,0,[]) }catch(x){} }
                            for(var sgi=0;sgi<comps.length&&!sarg;sgi++){
                              try{ sarg=findHasRemain(comps[sgi].$data,0,[]) }catch(x){}
                            }
                            line('  v3.89 签到参数: '+(sarg?cut(Object.keys(sarg).join(','),200):'未找到 hasRemainCount 对象，仍按原样调用'));
                          }
                          var ret=invoke(one.c,one.ms[j],sarg||undefined);
                          if(ret&&isFn(ret.then)) ret=await ret;
                          sign.names.push(one.n+'.'+one.ms[j]);
                          line('  签到调用: '+one.n+'.'+one.ms[j]+' 已发出，等接口回执');
                        }catch(e){ line('  签到调用失败: '+one.n+'.'+one.ms[j]+' '+e) }
                        await wait(1500);
                        // v3.88：把调用后页面发的每条请求都打出来。万一
                        // accumulateSign 只弹窗不发请求，下一份日志能直接看清。
                        for(var qd=mark;qd<NET.length;qd++){
                          line('  调用后请求: '+NET[qd].s+' '+cut(NET[qd].u,110)+' '+cut(NET[qd].b,140));
                        }
                        // 只有页面真的发出签到请求、且 HTTP 2xx，才算签到成功。
                        for(var q=mark;q<NET.length;q++){
                          if(isSubmit(NET[q])){
                            sign.ok++;
                            line('  签到提交回执: '+NET[q].s+' '+cut(NET[q].u,90)+' '+cut(NET[q].b,120));
                            break;
                          }
                        }
                      }
                    }
                    if(sign.ok===0){
                      var markC=NET.length;
                      // v3.89：v3.88 的 clickText 用 innerText 匹配，点到了整页
                      // 根 DIV；且只发 .click()，uni-app 不认。改用 ownText 精确
                      // 匹配 + 完整 touch 序列 + elementFromPoint 校正落点。
                      var hit=clickSignExact();
                      if(hit){
                        sign.try++; sign.names.push('click:'+hit);
                        line('  签到点击兜底: '+hit+'，只认点击后的提交回执');
                        await wait(1400);
                        for(var q2=markC;q2<NET.length;q2++){
                          if(isSubmit(NET[q2])){ sign.ok++; break }
                        }
                        if(sign.ok===0) line('  兜底没拿到提交回执: 点击后只有查询/失败响应');
                      } else {
                        line('  v3.89 兜底: 页面上没有「自己就写着签到」的可点元素');
                      }
                    }
                  }

                  // ---- 抽奖 ----
                  if(NEED_DRAW){
                    await wait(1200);
                    findAxios();
                    line('请求层: '+AX.length+' 个'+(AX.length?('，用 '+AX[0].src):''));
                    for(var i=0;i<IDS.length;i++){
                      if(draw.risk) break;   // v3.91：命中风控后不再向服务端发任何抽奖请求
                      var id=IDS[i];
                      var rem=await remainOf(id);
                      if(rem>=0){
                        line('奖池 '+id+' 剩余: '+rem);
                      } else {
                        var nr=netRemain();
                        if(nr>=0){ rem=nr; line('奖池 '+id+' 剩余(页面请求): '+nr) }
                      }
                      // v4.4.0：模块直发缺 phone 头时常回 500/404，请求钩子也会因为
                      // 页面重载被清空 —— 这时 rem 是负数（读不到），不是「0 次」。
                      // 11-11 12:39 就是这样：首屏明明写着「您有 3 次翻牌机会」，
                      // 模块却按 -2 判成没次数，一次都没抽，最后报「没有拿到结果」。
                      if(rem<0){
                        rem=remainFromPage();
                        if(rem>=0) line('奖池 '+id+' 剩余(页面文案): '+rem);
                      }
                      if(rem===0){
                        // 真的 0 次：说明「浏览任务」还没记上。任务现在统一在 Java 侧
                        // 点「去逛逛」完成，这里不再直发 task/visit —— 11-11 12:39 那 9 发
                        // 全部返回 923「手机号码非法」，纯噪音。
                        line('  次数为 0：浏览任务可能还没记上，本轮不抽');
                        continue;
                      }
                      if(rem>=0){ draw.seen++; draw.left+=rem; draw.rems.push(id+':'+rem) }
                      // v4.10.0：抽奖一律抽到「剩余次数 0」。
                      // 有余额就按余额抽（上限 40），读不到余额就按 12 次试探，
                      // 每轮开头重读一次余额，到 0 立即收手 —— 覆盖「做任务又补发次数」
                      // 和「次数一次给到 10 次以上」两种「抽了但有剩」的情况。
                      var times=rem>0?Math.min(rem,40):12;
                      if(rem<0) line('  次数读不到，按最多 12 次尝试，抽到余额 0 / 接口明确没次数为止');
                      // 任务上报刚打完，服务端还在限频窗口里，先缓一下再抽。
                      await wait(2600);
                      for(var k=0;k<times;k++){
                        // v4.10.0：每轮重读余额，服务端把次数抽到 0 就收手。
                        if(k>0){
                          var cur=await remainOf(id);
                          if(cur===0){ line('  奖池 '+id+' 余额已到 0，收手'); break }
                        }
                        draw.try++;
                        // 顺序对齐用户手点：先点页面上的翻牌卡片（页面自己组请求、
                        // 自己带设备指纹与风控头），点不到再调组件方法，最后才直连。
                        var r=await drawByClick(id);
                        if(r) line('  抽奖 '+id+' 点了页面翻牌卡片（页面自己发请求）');
                        if(!r) r=await drawViaPage(id,comps);
                        if(r&&r.via==='page'&&r.ok!==false){
                          line('  抽奖 '+id+' 走页面 goLottery（页面自带风控参数）');
                        }
                        // 「页面没发请求」不等于被拒：再退一次直连，别在这里就收手。
                        if(!r || (r.ok===false && r.msg==='页面没发抽奖请求')){
                          r=await lotteryOnce(id)||r;
                        }
                        if(!r) r={ok:false,code:'',msg:'没有可用的抽奖通道',risk:false};
                        // v3.91：命中服务端风控立即收手，绝不再补刀重试。
                        if(r.risk){
                          draw.risk=(draw.risk||0)+1;
                          line('  抽奖 '+id+' 命中服务端风控(SCORE_REFUSE/risk:true)，停止重试');
                          break;
                        }
                        if(!r.ok && /频繁|-1/.test(String(r.code)+' '+String(r.msg))){
                          line('  抽奖 '+id+' 触发限频，等 3 秒重试');
                          await wait(3000);
                          r=await drawViaPage(id,comps);
                          if(!r) r=await lotteryOnce(id);
                        }
                        // v3.91：重试那一发也可能带回风控，同样立即停。
                        if(r.risk){
                          draw.risk=(draw.risk||0)+1;
                          line('  抽奖 '+id+' 重试后仍命中服务端风控，停止');
                          break;
                        }
                        if(!r.ok){
                          line('  抽奖 '+id+' 被拒: '+r.code+' '+cut(r.msg,80));
                          if(/次数|机会|用完|不足|上限|已领/.test(String(r.msg))) draw.noChance=1;
                          break;
                        }
                        draw.apiOk=(draw.apiOk||0)+1;
                        var pz=prizeOf(r.data);
                        if(pz){ draw.prize=pz; line('  抽奖 '+id+' 中: '+pz) }
                        else line('  抽奖 '+id+' OK');
                        await wait(900);
                      }
                    }
                    if(draw.risk>0){
                      // 命中风险后不再直发接口：改点页面自己的翻牌按钮，由页面
                      // 自己组织请求体（operDfp 等设备指纹只有页面能补）。
                      var hd=clickDrawExact();
                      if(hd){ draw.try++; line('  风控后改用页面真实点击: '+hd+'（等网络证据）'); await wait(2200) }
                      else line('  风控后没找到可点的翻牌按钮');
                    } else if((draw.apiOk||0)===0){
                      // 接口抽不了时，才用页面自己的抽奖按钮/组件方法兜底。
                      var dc=[];
                      for(var i=0;i<comps.length;i++){
                        var ms=drawNames(mnames(comps[i]));
                        if(ms.length) dc.push({c:comps[i],ms:ms,n:cname(comps[i])});
                      }
                      if(dc.length){
                        // 组件名带 lottery 的排前面：dc[0] 以前可能是个无关组件，
                        // 11-11 12:39 就是拿它调了一次，直接抛 __ob__ 异常。
                        dc.sort(function(a,b){
                          return (/lottery|draw/i.test(b.n)?1:0)-(/lottery|draw/i.test(a.n)?1:0)
                        });
                        try{
                          var ret=invoke(dc[0].c,dc[0].ms[0]);
                          if(ret&&isFn(ret.then)) ret=await ret;
                          draw.try++;
                          line('  抽奖组件兜底: '+dc[0].n+'.'+dc[0].ms[0]+'（等网络证据）');
                        }catch(e){ line('  抽奖组件兜底失败: '+e) }
                      } else {
                        var hit=clickText(['抽奖','翻牌','立即领取']);
                        if(hit){ draw.try++; line('  抽奖点击兜底: '+hit+'（等网络证据）') }
                      }
                    }
                  }

                  await wait(1800);
                  // 最终以网络证据为准：NET 里 lottery/lottery 返回 60000 才算真抽中。
                  // 直接接口调用时 XHR 钩子也会记一份，所以取两者最大值，不重复计数。
                  var netOk=0;
                  for(var i=0;i<NET.length;i++){
                    try{
                      if(!/lottery[/]lottery/i.test(NET[i].u)) continue;
                      var d=JSON.parse(NET[i].b);
                      if(String(d.code)==='60000'){
                        netOk++;
                        var pz2=prizeOf(d);
                        if(pz2) draw.prize=pz2;
                      }
                    }catch(e){}
                  }
                  draw.ok=Math.max(draw.apiOk||0, netOk);
                  var t1=btext();
                  done({stage:'done',title:document.title||'',text:t0,text1:t1,
                        comps:comps.length,ax:AX.length,sign:sign,draw:draw,
                        sdk:sdk,loginWait:loginWait,net:NET.slice(0,12),
                        submits:submitsAfter(0),
                        signedAlready:signedAlreadyReply(),
                        metric:{c0:signCount(t0),c1:signCount(t1)}});
                }catch(err){
                  done({stage:'error',msg:String((err&&err.stack)||err)});
                }
              })();
            })()
            """;
}
