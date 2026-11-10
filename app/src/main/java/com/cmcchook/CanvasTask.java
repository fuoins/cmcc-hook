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
                    || text0.contains("已打卡") || text0.contains("明天再来")) {
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
                // 页面是好的，只是没挂载 Vue 组件 / 没抓到可提交的打卡按钮。
                // 如实报「没找到入口」：不猜坐标、不伪造提交、也不冒充完成。
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
            if (metricFlat || (submitN <= 0 && !metricUp)) {
                String why = metricFlat
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
            String summary = name + " · 账号触发服务端风控（SCORE_REFUSE），本次未完成";
            return TaskRunner.Result.fail(summary,
                    detail.append("判定: 服务端风控拦截（v3.92），已停止重试\n").toString(),
                    "这次是账号/设备被服务端风控拦了，不是模块坏了，也不是没抽奖次数。"
                            + "别在短时间内反复点这个活动（会加重风控），过一阵或换个号再试。");
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
                var tags=document.querySelectorAll('button,a,div,span,img');
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
                if(!best) return '';
                var txt=ownText(best)||String(best.innerText||'').replace(/\\s+/g,'').slice(0,8);
                return tapEl(best)?txt:'';
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
                  line('=== 画布活动 v3.37 ===');
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
                    var already=/已打卡|今日已签|今天已签|明天再来/.test(t0);
                    if(NEED_SIGN && !already){
                      var mark0=NET.length;
                      var got=hitAny(['立即打卡','今日打卡','打卡','签到'],1);
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
                        if(nr>=0){ rem=nr; line('奖池 '+id+' 剩余(页面): '+nr) }
                        else line('奖池 '+id+' 剩余: '+rem);
                      }
                      if(rem<=0){
                        // 次数为 0：多半是「浏览任务」没做。先拿任务清单，
                        // 用页面自己的任务组件提交一遍，再复查次数。
                        var tids=readTasks();
                        draw.tasks=tids.length;
                        if(tids.length){
                          line('  待做任务: '+tids.length+' 个');
                          var tn=[];
                          for(var c2=0;c2<comps.length;c2++){
                            if(/task/i.test(cname(comps[c2]))) tn.push(comps[c2]);
                          }
                          for(var q3=0;q3<tids.length && q3<4;q3++){
                            var tid=tids[q3], hit=false;
                            // 页面自己的浏览任务上报接口。先直发一次：
                            // 服务端只认 taskId 的话，这一发就把任务记上了。
                            try{
                              var tv=await post('/coc3/coc3-market-activity/cmpp-manage/task/visit',{taskId:tid});
                              var cv=codeOf(tv.data);
                              line('  任务上报: '+tid+' → '+cv);
                              if(cv!=='0'){
                                var tv2=await post('/coc3/coc3-market-activity/cmpp-manage/task/visit',{activityId:id,taskId:tid});
                                line('  任务上报(带活动): '+tid+' → '+codeOf(tv2.data));
                              }
                              // 任务上报挨着发会触发「访问过于频繁」，间隔拉到 1.4~2 秒。
                              await wait(1400+Math.floor(Math.random()*600));
                            }catch(e){ line('  任务上报失败: '+e) }
                            for(var c3=0;c3<tn.length && !hit;c3++){
                              var ms3=taskNames(mnames(tn[c3]));
                              for(var m3=0;m3<ms3.length && !hit;m3++){
                                var mark2=NET.length;
                                try{
                                  var rt=invoke(tn[c3],ms3[m3],tid);
                                  if(rt&&isFn(rt.then)) await rt;
                                  draw.taskTry=(draw.taskTry||0)+1;
                                  await wait(900);
                                  if(NET.length===mark2){
                                    // 有的组件要对象参数，再补一次。
                                    try{ invoke(tn[c3],ms3[m3],{taskId:tid}) }catch(e2){}
                                    await wait(700);
                                  }
                                  line('  任务提交: '+cname(tn[c3])+'.'+ms3[m3]+'('+tid+')');
                                  hit = NET.length>mark2;
                                }catch(e){ line('  任务提交失败: '+cname(tn[c3])+'.'+ms3[m3]+' '+e) }
                              }
                            }
                          }
                          await wait(1500);
                          var nr2=await remainOf(id);
                          if(nr2<0) nr2=netRemain();
                          if(nr2>=0){ rem=nr2; line('  任务后剩余: '+nr2) }
                        }
                      }
                      if(rem>=0){ draw.seen++; draw.left+=rem; draw.rems.push(id+':'+rem) }
                      if(rem<=0) continue;
                      var times=Math.min(rem,5);
                      // 任务上报刚打完，服务端还在限频窗口里，先缓一下再抽。
                      await wait(2600);
                      for(var k=0;k<times;k++){
                        draw.try++;
                        var r=await lotteryOnce(id);
                        // v3.91：命中服务端风控立即收手，绝不再补刀重试。
                        if(r.risk){
                          draw.risk=(draw.risk||0)+1;
                          line('  抽奖 '+id+' 命中服务端风控(SCORE_REFUSE/risk:true)，停止重试');
                          break;
                        }
                        if(!r.ok && /频繁|-1/.test(String(r.code)+' '+String(r.msg))){
                          line('  抽奖 '+id+' 触发限频，等 3 秒重试');
                          await wait(3000);
                          r=await lotteryOnce(id);
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
