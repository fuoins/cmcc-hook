package com.cmcchook;

import android.content.Context;

import org.json.JSONObject;

/**
 * wisdom-h5（米粒商城）里的两个子活动。
 *
 * <p>福利专区点进去的那个「签到抽奖赢好礼」和它里面的「乡友农业知识问答」，
 * 其实是同一个 SPA 的两个路由：
 * <ul>
 *   <li>{@code #/signInLottery} —— 每日签到 + 任务 + 转盘抽奖；</li>
 *   <li>{@code #/answerQuestions} —— 每天 3 道判断题，全对才有奖励。</li>
 * </ul>
 *
 * <p>接口是 AES 加密的（请求体/响应体都套 {@code {"data":"..."}}，密钥在页面
 * {@code localStorage.aesKey}，IV 走 {@code iv} 响应头），Java 层拼不出来，
 * 所以整套都放在页面里跑：借页面自己的 axios（拦截器负责加密和带 ssoId），
 * 或者解开页面已经拿到的响应。
 *
 * <p>答题的关键发现（11-07 抓包实证）：{@code knowTalentShow/getQuestions}
 * 返回的每个选项都带 {@code isRight} 字段，0/1 直接标明对错。也就是说
 * 根本不用猜、不用题库，题目发下来的同时答案就到手了 —— 拿它比一比点就行。
 */
final class WisdomTask {

    /** 签到抽奖赢好礼。h5token 由福利专区下发，采集到带 token 的地址会覆盖这里。 */
    static final String ACT_SIGN =
            "https://digismart.lncmcc.com/wisdom-h5/index.html#/signInLottery";
    /** 乡友农业知识问答。同域同 SPA，直接进这个路由即可。 */
    static final String ACT_QUIZ =
            "https://digismart.lncmcc.com/wisdom-h5/index.html#/answerQuestions";

    private WisdomTask() {
    }

    // v3.80：登录回执的 Java 层观测。13:36 日志实证 —— jtH5Login 500、
    // login/user 403 在页面加载后 500ms 内就发完了，而 SIGN_JS/QUIZ_JS 要等
    // 「页面就绪 865ms」之后才注入，脚本里的 XHR 钩子永远赶不上，所以 v3.79
    // 加的「登录回执: 失效」一条都没出现。模块自己在 JsBridge 已经拿到完整
    // 响应体，这里留一份带时间戳的摘要，只用于让日志自证，不改判定流程。
    private static volatile long loginDeadAt = 0L;
    private static volatile String loginDeadMsg = "";

    /** 由 JsBridge 在页面级 XHR 钩子里调用，比任务脚本注入得早。 */
    static void noteLoginReply(String url, String body) {
        if (url == null) {
            return;
        }
        String u = url.toLowerCase(java.util.Locale.US);
        boolean login = u.contains("/wisdom-front/login/jth5login")
                || u.contains("/wisdom-front/login/user");
        if (!login) {
            return;
        }
        String b = body == null ? "" : body;
        boolean dead = b.contains("\"code\":\"500\"") || b.contains("\"code\":\"403\"")
                || b.contains("\"code\":\"401\"") || b.contains("未登录")
                || b.contains("系统登录异常") || b.contains("重新登录");
        if (dead) {
            loginDeadAt = System.currentTimeMillis();
            loginDeadMsg = (u.contains("jth5login") ? "jtH5Login" : "login/user")
                    + " → " + b.substring(0, Math.min(96, b.length()));
        } else if (b.contains("\"code\":\"0000\"") || b.contains("\"code\":\"0\"")) {
            // 服务端恢复正常就撤掉，别让一次旧失败一直挂在日志里。
            loginDeadAt = 0L;
            loginDeadMsg = "";
        }
    }

    /** 最近两分钟内观测到的登录被拒摘要；没有就返回空串。 */
    static String recentLoginDead() {
        if (loginDeadAt > 0L && System.currentTimeMillis() - loginDeadAt < 120_000L) {
            return loginDeadMsg;
        }
        return "";
    }

    /** 任务日志开头固定写的一段：入口票据来源 + Java 层看到的登录回执。 */
    private static String entryDigest(String entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(RealEntry.wisdomTicketInfo(entry)).append('\n');
        String dead = recentLoginDead();
        if (!dead.isEmpty()) {
            sb.append("服务端登录回执(页面加载期): ").append(dead).append('\n');
        }
        return sb.toString();
    }

    /**
     * 问答的登录探针。
     *
     * <p>不直接进 {@code #/answerQuestions}：这一步一旦被服务端记成「已参与」，
     * 当天唯一一次机会就没了。先停在同一 SPA 的签到路由上，等页面用 h5token
     * 换出 ssoId 再切路由 —— token 失效时停在这里就能发现，答题页一次都不进。
     */
    static String probeUrl(String entry, String token) {
        String base = "https://digismart.lncmcc.com/wisdom-h5/index.html";
        int i = entry == null ? -1 : entry.indexOf("index.html");
        if (i > 0) {
            base = entry.substring(0, i + 10);
        }
        // token 在 ? 和 hash 里各放一份：这站读 search 还是读 hash query 没有实证，
        // 放两处任取其一都能换到 ssoId。
        return base + "?h5token=" + token + "#/signInLottery?h5token=" + token;
    }

    /**
     * v3.70：把当前账号标识塞进页面，供脚本给 ssoId 缓存分桶。
     *
     * <p>拿不到 UID 时留空，脚本退回按 QWHDSSOJWT 分桶的旧行为 —— 宁可慢一点，
     * 也不要用一个假标识把两个号的数据混在一起。
     */
    private static void tagAccount(WebPage page) {
        if (page == null) {
            return;
        }
        try {
            String acc = AccountScope.uid().isEmpty() ? "" : AccountScope.tag();
            String ph = SsoLogin.maskedPhone();
            // v3.71：同时落到 localStorage。页面跳转一次 window 上的变量就没了，
            // 只靠内存变量会让脚本退回「无账号」分支，又读回旧格式缓存。
            page.eval("(function(){var a=" + JSONObject.quote(acc)
                    + ",p=" + JSONObject.quote(ph)
                    + ";window.__cmcc_acc=a;window.__cmcc_phone=p;"
                    + "try{localStorage.setItem('__cmcc_acc',a);"
                    + "localStorage.setItem('__cmcc_phone',p)}catch(e){}})()", 3_000L);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 现场页选择
    //
    // v3.82：14:43 日志把前面几版的判断全推翻了。入口改成单 hash 后，模块用的地址
    // 和「用户手动打开、登录成功」的那一页一模一样（同一张不到 1 分钟前 App 现场
    // 下发的票），可结果还是 500/403。逐条对比两条路的请求：
    //   App 前台真页：jtH5Login?LJRkIeoW=… 回 0000，login/user 带 ssoId 回 0000，
    //                 来源 https://rr.ln139.cn/，页面真实渲染；
    //   模块离屏页：  jtH5Login 是裸 URL、ssoId 空 → 500，login/user 空 → 403，
    //                 探针全 0x0、innerText 为空、模板堆 9 个。
    // 病根不是 URL 形态、不是票据新旧，而是「凭空 new 出来的离屏 WebView」缺来源、
    // 缺瑞数动态防护初始化、缺真实布局。卡券模块（CouponPage）早就用「复用 App
    // 前台真页」解决过同一类问题，这里照搬：优先在用户自己打开的那个页面上跑脚本。
    // 前台真页绝不 destroy —— 那是用户正在看的界面。

    /** 该命令对应的 wisdom 路由关键字（小写），供现场页复核用。 */
    private static String routeOf(String cmd) {
        if (TaskRunner.CMD_SIGNLOTTERY.equals(cmd)) {
            return "signinlottery";
        }
        if (TaskRunner.CMD_QUIZ.equals(cmd)) {
            return "answerquestions";
        }
        return "";
    }

    /** TaskRunner 用：这次能不能直接复用 App 前台真页（能就别再开离屏页）。 */
    static boolean frontReady(String cmd) {
        String route = routeOf(cmd);
        return !route.isEmpty() && WisdomPage.ready(route);
    }

    /** 复用前台真页时，别把上一轮离屏页的 403 残留在日志里误导判定。 */
    private static void clearLoginDead() {
        loginDeadAt = 0L;
        loginDeadMsg = "";
    }

    /**
     * 选现场页：优先 App 前台真页，否则退回离屏 WebView。
     * 返回的 WebPage 只用于 eval / await，调用方不要 close 前台真页。
     */
    private static WebPage pickPage(String cmd, StringBuilder detail) {
        String route = routeOf(cmd);
        Object live = WisdomPage.get();
        if (!route.isEmpty() && live != null && WisdomPage.ready(route)) {
            WebPage front = WebPage.attach(live);
            if (front != null && front.raw() != null) {
                clearLoginDead();
                // v3.82：告诉页面脚本「这次跑在 App 前台真页上」。前台页是用户亲手
                // 打开、App 自己登录完的页面，jtH5Login / login/user 早已回过 0000；
                // 脚本不该再复判登录回执，更不能因为页面运行时某条轮询回 403 就把
                // App 现场这张有效 ssoId 清掉（14:43 日志实证的循环就是这样来的）。
                front.eval("(function(){try{window.__cmcc_front='1'}catch(e){}})()", 3_000L);
                detail.append("现场: 复用 App 前台真页（用户手动打开，已登录、已布局）\n");
                HookLog.log("TASK", "签到页现场 · 复用前台真页",
                        WisdomPage.status() + "\n"
                                + "说明: 这一页是 App 自己打开的，jtH5Login / login/user 已回过 0000，"
                                + "瑞数动态防护和布局都完整；本次直接在它上面跑脚本，"
                                + "不再另开离屏 WebView。\n");
                return front;
            }
        }
        detail.append("现场: 离屏兜底（没登记到可用的 App 前台真页）\n");
        HookLog.log("TASK", "签到页现场 · 离屏兜底",
                WisdomPage.status() + "\n"
                        + "说明: 没找到可用的前台真页，退回离屏 WebView；该环境缺来源/布局，"
                        + "登录可能被服务端拒绝。去福利专区点开一次签到页并保持打开即可。\n");
        WebPage off = WebSession.current();
        // 离屏页保留 v3.79 的登录回执复判：它可能拿着过期票反复空点，早停是对的。
        if (off != null) {
            off.eval("(function(){try{delete window.__cmcc_front}catch(e){}})()", 3_000L);
        }
        return off;
    }

    static TaskRunner.Result runQuiz(String url) {
        // v3.73：任务全程占住活动页。心跳的切号检测会调 WebSession.end()，
        // 而 UID 漂移会被误判成切号 —— 中途拆页面等于让脚本对着空 DOM 空转。
        WebSession.hold(true);
        try {
            return runQuizGuarded(url);
        } finally {
            WebSession.hold(false);
        }
    }

    private static TaskRunner.Result runQuizGuarded(String url) {
        String entry = (url == null || url.isEmpty()) ? ACT_QUIZ : url;
        StringBuilder detail = new StringBuilder("入口: ").append(brief(entry)).append('\n');
        // v3.95：07:45 日志实证 —— 一键跑到 15/15「乡友问答」时，v3.94 丢掉路由区分，
        // 把用户手动打开的 #/signInLottery 签抽页复用给了问答任务；QUIZ_JS 切
        // #/answerQuestions 路由触发 SPA 重载，冲掉注入脚本 → await 死等 240 秒、
        // 超时误锁机会，还把用户正在手动抽奖的页面切走（用户反馈「软件内打开有问题」）。
        // 现在：前台登记的不是问答页，就直接快速跳过 —— 不注入、不切路由、不等待、不锁机会。
        String front = WisdomPage.loggedRoute();
        if (WisdomPage.get() != null && !"answerquestions".equals(front)) {
            detail.append("现场: 前台登记的是「").append(front.isEmpty() ? "未知路由" : front)
                    .append("」页，不是问答页\n");
            return TaskRunner.Result.skip("乡友问答 · 请在福利专区单独打开问答页",
                    detail.append("本次不复用前台页、不切路由、不注入脚本；"
                            + "没有进答题页，今天机会未消耗。\n").toString(),
                    "问答每天只有一次机会、进入答题页即算用掉。这次检测到你打开的是"
                            + "「签到抽奖赢好礼」页，模块不会去切它（切了会把你的抽奖页弄没）。\n"
                            + "请手动答题；或在福利专区单独点开「乡友农业知识问答」页、停在上面，"
                            + "再回模块单独执行本任务（别用一键过全部）。");
        }
        // v3.82：先选现场页（复用前台真页时会清掉离屏页残留的 403 记录），
        // 再写票据/登录回执摘要，日志开头就不会再挂上一轮离屏页的旧账。
        WebPage page = pickPage(TaskRunner.CMD_QUIZ, detail);
        // v3.80：13:36 日志实证 —— 失败时日志只有「页面拿不到 ssoId」一句，
        // 看不出用的是哪张票、服务端到底回过什么。固定把票据来源和页面加载期
        // 的登录回执写进来，下次日志能直接自证，不用再靠猜。
        detail.append(entryDigest(entry));
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("乡友问答 · 活动页没起来", detail.toString(),
                    "前台真页和离屏 WebView 都没能加载活动页。去福利专区点开一次签到页再试。");
        }
        tagAccount(page);
        // 复用前台真页时它会一直开着，清掉上一轮脚本可能留下的回报，避免 await 读到旧结果。
        page.eval("(function(){try{delete window.__cmcc_quiz}catch(e){}"
                + "try{window.__cmcc_quiz=''}catch(e){}})()", 3_000L);
        page.eval(QUIZ_JS, 8_000L);
        // v3.95：240 秒是 07:45 日志「等半天不结束」的元凶。脚本正常路径最长约 30 秒
        // （25×0.4s 等 ssoId + 24×0.5s 等题 + 30×0.6s 等回执），60 秒足够；
        // 真被 SPA 重载冲掉时 60 秒就结束，不再让用户干等 4 分钟。
        JSONObject out = await(page, "__cmcc_quiz", 60_000L, detail);
        if (out == null) {
            // v3.95：超时 ≠ 进了答题页。07:45 日志铁证 —— 脚本被切路由重载冲掉，
            // 全文连 answerQuestions 都没出现，根本没进答题页；旧版无条件 markDone
            // 会把今天唯一一次机会误锁掉。现在纯超时不锁机会、不记完成，明确引导手动做。
            // 只有脚本真回报 entered=true（下面那段 if (entered)）才按规则锁机会。
            return TaskRunner.Result.fail("乡友问答 · 页面脚本没回报(v3.95)",
                    detail.append("脚本 60 秒没回报：多为页面重载冲掉注入脚本，"
                            + "没确认进答题页；本次不锁今天机会、不记完成。\n").toString(),
                    "没确认进答题页，今天机会大概率还在。请手动答题；"
                            + "或在福利专区单独打开问答页、停在上面，再单独执行本任务。");
        }
        boolean entered = out.optBoolean("entered", false);
        TaskRunner.Result r = judge("乡友问答", out, detail);
        Context ctx = HookLog.context();
        // v3.69：按用户实测的规则改回来 —— 进了答题页，就算中途退出，
        // 这次机会也算用掉了（页面会弹「因您中途退出答题页面，当日答题机会已用完」）。
        // 所以只要 entered，当天就锁死，绝不给第二次「再进一次」的机会。
        // 3.65 那版只在提交成功时才锁，等于默认「没提交还能再来」，这个前提是错的。
        if (entered) {
            TaskRunner.markDone(ctx, TaskRunner.CMD_QUIZ);
            HookLog.log("TASK", "乡友问答 · 今天的机会已用掉",
                    "已进入答题页，这次机会按规则算用掉；本地记下，今天不再重复进入。\n");
        }
        return r;
    }

    static TaskRunner.Result runSign(String url) {
        WebSession.hold(true);
        try {
            return runSignGuarded(url);
        } finally {
            WebSession.hold(false);
        }
    }

    private static TaskRunner.Result runSignGuarded(String url) {
        String entry = (url == null || url.isEmpty()) ? ACT_SIGN : url;
        StringBuilder detail = new StringBuilder("入口: ").append(brief(entry)).append('\n');
        // v3.82：先选现场页（复用前台真页时会清掉离屏页残留的 403 记录），
        // 再写票据/登录回执摘要，日志开头就不会再挂上一轮离屏页的旧账。
        WebPage page = pickPage(TaskRunner.CMD_SIGNLOTTERY, detail);
        // v3.80：13:36 日志实证 —— 失败时日志只有「页面拿不到 ssoId」一句，
        // 看不出用的是哪张票、服务端到底回过什么。固定把票据来源和页面加载期
        // 的登录回执写进来，下次日志能直接自证，不用再靠猜。
        detail.append(entryDigest(entry));
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("签到抽奖 · 活动页没起来", detail.toString(),
                    "前台真页和离屏 WebView 都没能加载活动页。去福利专区点开一次签到页再试。");
        }
        tagAccount(page);
        // 复用前台真页时它会一直开着，清掉上一轮脚本可能留下的回报，避免 await 读到旧结果。
        page.eval("(function(){try{delete window.__cmcc_sign}catch(e){}"
                + "try{window.__cmcc_sign=''}catch(e){}})()", 3_000L);
        page.eval(SIGN_JS, 8_000L);
        JSONObject out = await(page, "__cmcc_sign", 120_000L, detail);
        if (out == null) {
            return TaskRunner.Result.fail("签到抽奖 · 页面脚本超时",
                    detail.append("脚本 120 秒没有回报\n").toString(),
                    "页面能打开但脚本没回报。把完整日志发我。");
        }
        return judge("签到抽奖", out, detail);
    }

    // ------------------------------------------------------------------ Java 侧

    private static JSONObject await(WebPage page, String key, long maxMs, StringBuilder detail) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            String v = page.eval("(function(){var v=window." + key + ";return v?String(v):'';})()",
                    6_000L);
            if (v != null && !v.isEmpty()) {
                try {
                    return new JSONObject(v);
                } catch (Throwable e) {
                    detail.append("回报解析失败: ").append(brief(v)).append('\n');
                    return null;
                }
            }
            sleep(600L);
        }
        return null;
    }

    private static TaskRunner.Result judge(String tag, JSONObject out, StringBuilder detail) {
        String stage = out.optString("stage", "");
        String msg = out.optString("msg", "");
        org.json.JSONArray lines = out.optJSONArray("lines");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                detail.append("  ").append(lines.optString(i)).append('\n');
            }
        }
        if ("no-page".equals(stage)) {
            return TaskRunner.Result.skip(tag + " · 页面没进对",
                    detail.append("URL 不是 wisdom-h5 的活动页\n").toString(),
                    "入口地址可能过期了，去福利专区点一次这个活动，让模块重新采集地址。");
        }
        if ("need-login".equals(stage) || "need-token".equals(stage)) {
            // v3.99：12:44 日志实证 —— 票是「App 现场打开活动页（福利专区下发）」
            // 不到 1 秒前刚刷新的，前台真页也登记并复用了（签到页现场·复用前台真页），
            // 可服务端 jtH5Login 仍回 500「系统登录异常」、login/user 连回 403
            // 「未登录或过期」，ssoId 一直为空。也就是「手动进福利专区点开页面」
            // 这一招用户已经做了、也不管用，病根在服务端拒票，不是没进福利专区。
            // 旧提示一律甩「去福利专区点开一次并保持打开」是误导，这里按现场分流。
            String dead = recentLoginDead();
            if (!dead.isEmpty()) {
                detail.append("服务端登录回执(页面加载期): ").append(dead).append('\n');
            }
            boolean usedFront = detail.indexOf("复用 App 前台真页") >= 0;
            String scene = usedFront
                    ? "本次已经复用你在 App 里打开的签到页（前台真页），入口票据也是刚才"
                            + "福利专区现场下发的，不是离屏页的问题"
                    : "本次没有可用的前台真页，跑在离屏兜底页上";
            String advice;
            if (usedFront) {
                advice = "这次是服务端把这张刚下发的票据判成失效（jtH5Login 500 / login/user 403），"
                        + "跟「有没有手动进福利专区」无关，再点一次活动页、把页面一直开着也不会有 ssoId。"
                        + "别反复重试：今天什么都没做、答题机会没消耗。等几分钟，或切一下网络、"
                        + "在中国移动 App 里退出重登一次，再单独重跑「" + tag + "」；"
                        + "仍不行就明天再跑，别用一键过全部连撞。";
            } else {
                advice = "这一站（wisdom-h5）的登录态只认 App 从福利专区现场跳进去的那张页面："
                        + "离屏 WebView 缺来源、缺瑞数动态防护、缺真实布局，服务端会直接拒票。"
                        + "去「领福利 / 福利专区」点开一次「签到抽奖赢好礼」，等页面真的显示出"
                        + "签到天数或转盘，让这个页面一直开着，回到模块单独重跑一次（别用一键过全部）。";
            }
            detail.append(msg).append('\n')
                    .append("现场判定: ").append(scene).append('\n')
                    .append("页面拿不到 ssoId：h5token 换不回来，缓存里也没有可用登录态。\n");
            if ("need-login".equals(stage)) {
                return TaskRunner.Result.fail(tag + " · 页面登录被拒绝", detail.toString(), advice);
            }
            return TaskRunner.Result.skip(tag + " · 登录凭证失效", detail.toString(), advice);
        }
        if ("used".equals(stage)) {
            // v3.69：用户在真机上确认 —— 进过答题页、中途退出，这次机会就作废，
            // 页面会明确写「因您中途退出答题页面，当日答题机会已用完，明天再来吧~」。
            // 这不是失败，是今天的额度没了，反复点只会一直吃这个提示。
            return TaskRunner.Result.idle(tag + " · 今天机会已用完",
                    detail.append("页面提示：因中途退出答题页面，当日答题机会已用完。\n").toString(),
                    "服务端已经把今天这次机会判掉了（进过答题页就算，不看有没有提交）。"
                            + "今天不用再点，明天再跑。");
        }
        if ("need-topic".equals(stage)) {
            return TaskRunner.Result.fail(tag + " · 没取到题目",
                    detail.append("已进答题页，但没拿到题目数据，没提交。\n").toString(),
                    "「" + tag + "」这次进去了却没取到题，机会大概率已经被算掉（这站进了页面就算开始）。"
                            + "今天别再连点，明天再跑；要查原因就把这份日志发我。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail(tag + " · 脚本异常",
                    detail.append(msg).append('\n').toString(), "把这份日志发我。");
        }
        if ("idle".equals(stage)) {
            return TaskRunner.Result.idle(tag + " · " + msg, detail.toString(),
                    tag + "：" + msg);
        }
        if ("ok".equals(stage)) {
            return TaskRunner.Result.ok(tag + " · " + msg, detail.toString(), tag + "：" + msg);
        }
        return TaskRunner.Result.fail(tag + " · " + (msg.isEmpty() ? "没做成" : msg),
                detail.toString(), "把这份日志发我，能看出卡在哪一步。");
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ 页面脚本

    /**
     * 答题。
     *
     * <p>顺序是「拿题 → 对答案 → 点选项 → 提交 → 读结果」。
     * 拿题三条路，从稳到偏：页面 axios 重取一份（最干净，走页面自己的加密和 ssoId）、
     * 解密已经抓到的 getQuestions 响应、翻 Vue 组件树。
     */
    private static final String QUIZ_JS = """
            (function(){
              var ID='__cmcc_quiz';
              window[ID]='';
              window.__cmcc_qs=null;
              var L=[], NET=[];
              // v3.63：一旦切到答题路由就算「今天用过」，所有出口都要带上这个标记，
              // Java 侧据此落当日锁，避免再进一次。
              var ENTERED=false;
              function line(s){L.push(String(s))}
              function tx(e){
                if(!e) return '';
                // v3.73：离屏 WebView 没做布局时 innerText 一直返回空串。
                // 11:02 那次脚本 20 秒读到的 body 全是空，最后误报「页面没进对」。
                // textContent 不依赖布局，必须兜底。
                var s=(e.innerText!==undefined?e.innerText:'');
                if(!s) s=e.textContent||'';
                return String(s).replace(/ +/g,' ').replace(/\\n+/g,' ').trim();
              }
              function vis(e){
                try{
                  if(!e||!e.getBoundingClientRect) return false;
                  var r=e.getBoundingClientRect();
                  if(r.width===0&&r.height===0){
                    // 整页没做布局时所有 rect 都是 0x0，按几何判断会一个都点不到。
                    // 退回样式链判断：自身和祖先都没隐藏就算可见。
                    for(var p=e;p&&p.nodeType===1;p=p.parentElement){
                      var ps=getComputedStyle(p);
                      if(ps.display==='none'||ps.visibility==='hidden') return false;
                      if(parseFloat(ps.opacity||'1')<0.05) return false;
                    }
                    return true;
                  }
                  if(r.width<14||r.height<10) return false;
                  var s=getComputedStyle(e);
                  if(s.display==='none'||s.visibility==='hidden') return false;
                  if(parseFloat(s.opacity||'1')<0.05) return false;
                  return true;
                }catch(x){return false}
              }
              // v3.76：离屏 WebView 的宽度是 0，元素的 rect 常见的不是 0x0 而是 0x40，
              // 上一条只处理了宽高都为 0 的情况，于是所有按钮继续被判成不可见 ——
              // 11:55 那次就是：btnList 能列出「确定」，closeTip 却一个都点不到。
              // 这里干脆不看几何，只看自身和祖先有没有被样式隐藏。
              function shown(e){
                try{
                  if(!e) return false;
                  for(var p=e;p&&p.nodeType===1;p=p.parentElement){
                    var cs=getComputedStyle(p);
                    if(cs.display==='none'||cs.visibility==='hidden') return false;
                    if(parseFloat(cs.opacity||'1')<0.05) return false;
                  }
                  return true;
                }catch(x){return false}
              }
              // v3.74：正文不能直接取 textContent —— 页面里的 <script> 内联脚本
              // 也带「签到」字样，DOM 还没渲染时照样能命中，脚本以为页面好了，
              // 11:35 那次 2.5 秒就收工、一个按钮没点。先试 innerText；
              // 真没布局时克隆一份，剔掉 script/style 再取 textContent。
              function bodyText(){
                var b=document.body;
                if(!b) return '';
                var it='';
                try{ it=String(b.innerText||'').trim() }catch(e){}
                if(it) return it.replace(/\\s+/g,'');
                try{
                  var c=b.cloneNode(true);
                  var junk=c.querySelectorAll('script,style,noscript,template');
                  for(var i=0;i<junk.length;i++){
                    if(junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
                  }
                  return String(c.textContent||'').replace(/\\s+/g,'');
                }catch(e){
                  return String(b.textContent||'').replace(/\\s+/g,'');
                }
              }
              function wait(ms){return new Promise(function(r){setTimeout(r,ms)})}
              function done(stage,msg){
                window[ID]=JSON.stringify({stage:stage,msg:msg,lines:L,entered:ENTERED});
              }
              function clickEl(e,px,py){
                try{
                  try{e.scrollIntoView({block:'center'})}catch(x){}
                  var r=e.getBoundingClientRect();
                  // v3.84：签到按钮是一张图片（用户长按会弹「保存图片」），
                  // 它外面常盖着透明容器，元素自己的中心点未必是能点中的位置。
                  // 允许调用方传入 elementFromPoint 命中的真实坐标。
                  var cx=(typeof px==='number')?px:(r.left+r.width/2);
                  var cy=(typeof py==='number')?py:(r.top+r.height/2);
                  var opt={bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy};
                  // v3.83：17:02 日志实证 —— 复用前台真页后登录（jtH5Login /
                  // login/user 回 0000）和布局（探针 111x33）都好了，可点了 9 次
                  // 页面一条新 XHR 都没发。这页是 uni-app（UNI-PAGE-BODY、uni-view），
                  // 按钮全是 div/span 上的 @tap，只发 MouseEvent / click 不触发
                  // Vue 的 handler。补一套完整 touch 序列，覆盖 tap 与 click 两种实现。
                  function tev(type,end){
                    try{
                      var t=new Touch({identifier:1,target:e,clientX:cx,clientY:cy,
                        pageX:cx+window.scrollX,pageY:cy+window.scrollY,
                        screenX:cx,screenY:cy,radiusX:1,radiusY:1,rotationAngle:0,force:1});
                      var ev=new TouchEvent(type,{bubbles:true,cancelable:true,view:window,
                        touches:end?[]:[t],targetTouches:end?[]:[t],changedTouches:[t]});
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  tev('touchstart',false);
                  var ts=['pointerdown','mousedown','pointerup','mouseup'];
                  for(var i=0;i<ts.length;i++){
                    try{
                      var ev=ts[i].indexOf('pointer')===0
                        ? new PointerEvent(ts[i],opt) : new MouseEvent(ts[i],opt);
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  tev('touchend',true);
                  // click 仍然只发一次：之前 click 事件和 e.click() 各发一遍，
                  // 等于点两下 —— 选项会被点成取消。
                  try{if(typeof e.click==='function')e.click()}catch(x){}
                  return true;
                }catch(x){return false}
              }

              // v3.65：页面自己会解密 getQuestions，再对明文做一次 JSON.parse。
              // 直接包住 JSON.parse 把题目连答案捞出来 —— 不依赖 window.CryptoJS，
              // 也不依赖页面把 axios 挂到全局。11-08 08:15 那两次就是这两条都没命中。
              try{
                if(!window.__cmcc_parse_hook){
                  window.__cmcc_parse_hook=true;
                  var oParse=JSON.parse;
                  JSON.parse=function(s){
                    var r=oParse.apply(this,arguments);
                    try{
                      var q=(r&&r.questionList)||(r&&r.data&&r.data.questionList)||null;
                      if(q&&q.length&&!window.__cmcc_qs) window.__cmcc_qs=q;
                    }catch(e){}
                    return r;
                  };
                }
              }catch(e){}

              // 抓 XHR：页面自己发的 getQuestions / submit 都留一份，兜底解密用。
              try{
                var oOpen=XMLHttpRequest.prototype.open, oSend=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){
                  this.__qu=String(u||''); return oOpen.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send=function(b){
                  var x=this;
                  x.addEventListener('load',function(){
                    try{
                      var u=String(x.__qu||'');
                      if(u.indexOf('knowTalentShow')<0) return;
                      NET.push({u:u,code:x.status,iv:x.getResponseHeader('iv')||'',
                                t:String(x.responseText||'')});
                    }catch(e){}
                  });
                  return oSend.apply(this,arguments);
                };
              }catch(e){}

              function key(){try{return localStorage.getItem('aesKey')||''}catch(e){return ''}}
              function b64words(b64){
                try{return CryptoJS.enc.Base64.parse(b64)}catch(e){return null}
              }
              function decData(b64,ivb64){
                try{
                  var k=key(); if(!k||!window.CryptoJS) return '';
                  var wa=b64words(ivb64); if(!wa) return '';
                  var iv=CryptoJS.lib.WordArray.create(wa.words.slice(0,4),16);
                  var d=CryptoJS.AES.decrypt({ciphertext:b64words(b64)},
                        CryptoJS.enc.Utf8.parse(k),
                        {iv:iv,mode:CryptoJS.mode.CBC,padding:CryptoJS.pad.Pkcs7});
                  return d.toString(CryptoJS.enc.Utf8)||'';
                }catch(e){return ''}
              }
              function pickQuestions(obj,depth,seen){
                if(!obj||depth>6||seen.indexOf(obj)>=0) return null;
                seen.push(obj);
                try{
                  if(obj.questionList&&obj.questionList.length) return obj.questionList;
                  if(obj.data&&obj.data.questionList&&obj.data.questionList.length)
                    return obj.data.questionList;
                }catch(e){}
                try{
                  for(var k in obj){
                    if(k==='$parent'||k==='$root'||k==='_router') continue;
                    var v=obj[k];
                    if(!v||typeof v!=='object') continue;
                    var r=pickQuestions(v,depth+1,seen);
                    if(r) return r;
                  }
                }catch(e){}
                return null;
              }
              function fromNet(){
                for(var i=NET.length-1;i>=0;i--){
                  var n=NET[i];
                  if(n.u.indexOf('getQuestions')<0) continue;
                  try{
                    var j=JSON.parse(n.t);
                    if(j.data&&typeof j.data==='string'){
                      var plain=decData(j.data,n.iv);
                      if(plain&&plain.indexOf('questionList')>=0){
                        return JSON.parse(plain).questionList;
                      }
                    }
                  }catch(e){}
                }
                return null;
              }
              function fromVue(){
                try{
                  var el=document.querySelector('#app')||document.querySelector('#root')||document.body;
                  var roots=[];
                  if(el.__vue__) roots.push(el.__vue__);
                  if(el.__vueParentComponent) roots.push(el.__vueParentComponent);
                  if(el.__vue_app__) roots.push(el.__vue_app__);
                  for(var i=0;i<roots.length;i++){
                    var q=pickQuestions(roots[i],0,[]);
                    if(q) return q;
                  }
                }catch(e){}
                return null;
              }
              function http(){
                var c=[window.axios,window.$axios,window.http,window.$http];
                try{
                  if(window.Vue&&Vue.prototype){c.push(Vue.prototype.$axios,Vue.prototype.$http)}
                }catch(e){}
                try{
                  var el=document.querySelector('#app')||document.body;
                  var vm=el.__vue__||(el.__vueParentComponent&&el.__vueParentComponent.proxy);
                  if(vm){c.push(vm.$axios,vm.$http,vm.axios)}
                }catch(e){}
                for(var i=0;i<c.length;i++){
                  if(c[i]&&typeof c[i].post==='function') return c[i];
                }
                return null;
              }
              function fromHttp(){
                return new Promise(function(resolve){
                  var h=http();
                  if(!h){resolve(null);return}
                  var base='';
                  try{base=(h.defaults&&h.defaults.baseURL)||''}catch(e){}
                  var url=(base?String(base).replace(/\\/+$/,''):'')+'/knowTalentShow/getQuestions';
                  line('取题: 调页面 axios ' + url);
                  var p;
                  try{p=h.post(url,{})}catch(e){resolve(null);return}
                  if(!p||typeof p.then!=='function'){resolve(null);return}
                  p.then(function(r){
                    var q=pickQuestions(r,0,[]);
                    resolve(q||null);
                  },function(e){
                    line('取题: axios 失败 ' + (e&&e.message?e.message:e));
                    resolve(null);
                  });
                  setTimeout(function(){resolve(null)},12000);
                });
              }

              // v3.69：进答题页之前做一次登录体检。
              // 只读页面自己刚发的 getUserInfo 回执，不额外发请求、不碰答题接口。
              // 目的是别在「登录其实已失效」的状态下白白进一次答题页 —— 那一次就是一天。
              function loginProbe(){
                for(var i=NET.length-1;i>=0;i--){
                  var n=NET[i];
                  if(String(n.u||'').indexOf('getUserInfo')<0) continue;
                  var t=String(n.t||'');
                  if(/未登录|登录.{0,4}过期|token.{0,6}(失效|过期)/.test(t)) return 'dead';
                  try{
                    var j=JSON.parse(t);
                    var c=String(j.code||'');
                    if(c==='403'||c==='401') return 'dead';
                    if(c==='0000'||c==='0'||c==='200') return 'ok';
                  }catch(e){}
                  return 'unknown';
                }
                return 'unknown';
              }
              function blockOf(title){
                var all=document.querySelectorAll('*'), best=null, bestLen=1e9;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vis(e)) continue;
                  var t=tx(e);
                  if(t.indexOf(title)<0) continue;
                  if(t.length<=bestLen){bestLen=t.length;best=e}
                }
                if(!best) return null;
                var node=best;
                for(var d=0;d<7&&node;d++){
                  var yes=0,no=0;
                  var kids=node.querySelectorAll('*');
                  for(var j=0;j<kids.length;j++){
                    var k=tx(kids[j]);
                    if(!vis(kids[j])) continue;
                    if(k==='对') yes++;
                    else if(k==='错') no++;
                  }
                  if(yes>0&&no>0) return node;
                  node=node.parentElement;
                }
                return best.parentElement||best;
              }
              function pickOption(block,label){
                if(!block) return false;
                var all=block.querySelectorAll('*'), hit=null, hitLen=1e9;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vis(e)) continue;
                  var t=tx(e);
                  if(t!==label) continue;
                  if(t.length<=hitLen){hitLen=t.length;hit=e}
                }
                if(!hit){
                  // 有些皮肤把「对/错」做成图片或 ::before，退到按序号取。
                  var idx=label==='对'?0:1;
                  var pairs=[];
                  var els=block.querySelectorAll('*');
                  for(var j=0;j<els.length;j++){
                    var s=tx(els[j]);
                    if(s.length<=2&&vis(els[j])) pairs.push(els[j]);
                  }
                  if(pairs.length>idx) hit=pairs[idx];
                }
                if(!hit) return false;
                var node=hit;
                for(var d=0;d<4&&node&&node!==block;d++){
                  var cls=String(node.className||'');
                  var tag=String(node.tagName||'').toUpperCase();
                  if(tag==='BUTTON'||tag==='LABEL'||cls.indexOf('opt')>=0
                     ||cls.indexOf('item')>=0||cls.indexOf('btn')>=0
                     ||cls.indexOf('radio')>=0||cls.indexOf('answer')>=0){
                    return clickEl(node);
                  }
                  node=node.parentElement;
                }
                return clickEl(hit);
              }
              function clickSubmit(){
                var all=document.querySelectorAll('button,a,div,span');
                var best=null,bestLen=1e9;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vis(e)) continue;
                  var t=tx(e);
                  if(t!=='提交'&&t!=='交卷'&&t!=='提交答案') continue;
                  if(t.length<=bestLen){bestLen=t.length;best=e}
                }
                if(!best){
                  for(var j=0;j<all.length;j++){
                    var s=tx(all[j]);
                    if(!vis(all[j])) continue;
                    if(s.length<=4&&s.indexOf('提交')>=0){best=all[j];break}
                  }
                }
                return best?clickEl(best):false;
              }
              function readResult(){
                var t=bodyText();
                var i=t.indexOf('共答对');
                if(i>=0) return t.slice(i,i+40);
                i=t.indexOf('全部答对');
                if(i>=0) return t.slice(Math.max(0,i-20),i+40);
                var m=t.match(/答对\\s*\\d+\\s*道/);
                if(m) return m[0];
                return '';
              }
              // v3.69：用户在真机上确认过 —— 进了答题页就算开始，没提交就退出，
              // 页面会弹「因您中途退出答题页面，当日答题机会已用完，明天再来吧~」。
              // 这个提示以前谁都不认，最后被记成「提交后 12 秒没看到结果弹窗」，
              // 看起来像模块超时，实际是服务端把这次机会判掉了。必须单独识别。
              function usedUp(){
                var t=bodyText();
                return /中途退出答题页面|答题机会已用完|当天答题机会已用完/.test(t);
              }
              function usedMsg(){
                var t=bodyText();
                var i=t.indexOf('因您');
                if(i<0) i=t.indexOf('提示');
                return i>=0?t.slice(i,i+52):'页面提示当日答题机会已用完';
              }

              (async function(){
                try{
                  var href=String(location.href||'');
                  if(href.indexOf('wisdom-h5')<0){done('no-page',href);return}
                  // v3.63：进来时先停在签到路由上，只换登录态，不碰答题页。
                  // 这站登录态靠 sessionStorage.ssoId，而 ssoId 只能用地址上的
                  // h5token 调 login/jtH5Login 换来。token 失效时停在这一步就能发现 ——
                  // 答题页一次都不进，当天唯一一次机会保住。
                  // v3.64：先看上一次真实页面登录留下的 ssoId（localStorage 同域共享）。
                  // 有就直接用 —— 这条路完全不需要 h5token，也不怕 token 一次性。
                  var sso='', ownCache=false;
                  try{
                    var jw=String(localStorage.getItem('QWHDSSOJWT')||'');
                    var acc='';
                    try{ acc=String(window.__cmcc_acc||localStorage.getItem('__cmcc_acc')||'') }catch(e){}
                    var ph0=String(window.__cmcc_phone||'');
                    if(!ph0){ try{ ph0=String(localStorage.getItem('__cmcc_phone')||'') }catch(e){} }
                    // v3.72：UID 会漂移（10:34 是 mhrz183097fb，10:41 登录后变成
                    // mhrze7e12968），按 UID 分桶等于每次冷启动都读不到。改成扫全表：
                    // 带 _ph 且与本号掩码一致的最优先；没有 _ph 的旧键只认 2 小时内。
                    // v3.79：只认「带 _ph 且等于本号」的桶。12:48 日志实证：
                    // 139 桶 / 198 桶 / 无 _ph 旧键三个 key 存的是同一个 ssoId，
                    // 切到 198 后拿 139 的登录态去请求 → login/user 403。
                    // 无归属的旧键和别的号的桶一律就地清掉，宁可让页面用
                    // h5token 重新登录，也不要跨号沿用一张身份不明的票。
                    var cands=[], purge=[];
                    for(var li=0;li<localStorage.length;li++){
                      var lk=String(localStorage.key(li)||'');
                      if(lk.indexOf('__cmcc_sso_')!==0) continue;
                      if(lk.slice(-3)==='_at'||lk.slice(-3)==='_ph') continue;
                      var lv=String(localStorage.getItem(lk)||'');
                      var la=Number(localStorage.getItem(lk+'_at')||0);
                      var lp=String(localStorage.getItem(lk+'_ph')||'');
                      if(!lp){
                        // v3.80：13:36 日志实证 —— 用户在 App 里手动点开签到页，
                        // 页面登录成功后 moveSso 把那张有效 ssoId 写成无 _ph 的桶；
                        // v3.79 一律清掉，等于把刚拿到的登录态删了，重跑又回到
                        // 「没票」死循环。现在：无归属但近期写入的桶认领到本号，
                        // 只有明确属于别的号、或陈年旧票才清掉。下面 cands 会按
                        // _at 排序取最新，旧的同值桶排后面、且归一阶段会被合并。
                        var fresh=la>0&&Date.now()-la<43200000;
                        if(ph0&&fresh&&lv&&lv.length>=16){
                          try{ localStorage.setItem(lk+'_ph',ph0); lp=ph0;
                               line('认领无归属登录态桶（补写本号 _ph）') }catch(e){}
                        }
                      }
                      if(!lp||(ph0&&lp!==ph0)){ purge.push(lk); continue }
                      if(!lv||lv.length<16) continue;
                      if(la&&Date.now()-la>=43200000) continue;
                      cands.push({k:lk,v:lv,a:la,own:1});
                    }
                    for(var pi=0;pi<purge.length;pi++){
                      try{
                        localStorage.removeItem(purge[pi]);
                        localStorage.removeItem(purge[pi]+'_at');
                        localStorage.removeItem(purge[pi]+'_ph');
                      }catch(e){}
                    }
                    if(purge.length) line('已清掉 '+purge.length+' 个无归属/非本号登录态桶');
                    cands.sort(function(x,y){return y.a-x.a});
                    var pick=cands.length?cands[0]:null;
                    if(pick){
                      sessionStorage.setItem('ssoId',pick.v); sso=pick.v; ownCache=true;
                      line('ssoId 取自本号缓存');
                      // 归一到「手机号桶」：UID 再漂也不会丢。
                      var nk='__cmcc_sso_'+(ph0||acc||'x')+'_'+jw.slice(0,12);
                      try{
                        localStorage.setItem(nk,pick.v);
                        localStorage.setItem(nk+'_at',String(pick.a||Date.now()));
                        if(ph0) localStorage.setItem(nk+'_ph',ph0);
                        if(ph0){
                          for(var ci=0;ci<cands.length;ci++){
                            var ok0=cands[ci].k;
                            if(ok0===nk) continue;
                            localStorage.removeItem(ok0);
                            localStorage.removeItem(ok0+'_at');
                            localStorage.removeItem(ok0+'_ph');
                          }
                        }
                        if(pick.k!==nk) line('已把登录态归到本号桶');
                      }catch(e){}
                    }
                  }catch(e){}
                  if(!ownCache){
                    // 没有本账号的缓存。先看页面现在登录的是不是这个号：
                    // 是 → 直接接管它的 ssoId 并存进本账号桶，不用重新登录；
                    // 不是 → 清掉旧值，等页面自己用 h5token 重新登录。
                    var ss='', pm='';
                    var ph=String(window.__cmcc_phone||'');
                    if(!ph){ try{ ph=String(localStorage.getItem('__cmcc_phone')||'') }catch(e){} }
                    try{ ss=String(sessionStorage.getItem('ssoId')||'') }catch(e){}
                    try{ pm=String(sessionStorage.getItem('loginMobile')||'') }catch(e){}
                    if(ph&&pm&&pm===ph&&ss&&ss.length>=16){
                      sso=ss; ownCache=true;
                      try{
                        var jw2=String(localStorage.getItem('QWHDSSOJWT')||'');
                        var ck2='__cmcc_sso_'+(ph||acc||'x')+'_'+jw2.slice(0,12);
                        localStorage.setItem(ck2,ss);
                        localStorage.setItem(ck2+'_at',String(Date.now()));
                        if(ph) localStorage.setItem(ck2+'_ph',ph);
                      }catch(e){}
                      line('页面已是本账号（'+pm+'），直接接管登录态');
                    }else if(ph&&pm&&pm!==ph){
                      // 明确知道页面登录的是另一个号 —— 这种必须清掉，
                      // 否则就会拿别人的身份去领奖。
                      try{ sessionStorage.removeItem('ssoId') }catch(e){}
                      line('页面登录的是 '+pm+'，不是本账号，等它重新登录');
                    }else{
                      // 手机号没取到、或页面还没写 loginMobile：判断不了，
                      // 沿用页面现值（退回 v3.69 行为），别一刀切把能用的也废了。
                      if(ss&&ss.length>=16){
                        sso=ss; ownCache=true;
                        line('账号校验信息不全，沿用页面登录态');
                      }
                    }
                  }
                  for(var sw=0;sw<25;sw++){
                    try{ sso=String(sessionStorage.getItem('ssoId')||'') }catch(e){}
                    if(sso&&sso.length>=16) break;
                    await wait(400);
                  }
                  if(!sso||sso.length<16){
                    done('need-token','h5token 换不到 ssoId（多半已过期）；没进答题页，今天的机会还在');
                    return;
                  }
                  line('ssoId 已就绪: '+sso.slice(0,8)+'…');
                  // 再给页面 2 秒把自己那次 getUserInfo 走完，然后看结果。
                  await wait(2000);
                  var lp=loginProbe();
                  if(lp==='dead'){
                    done('need-token','getUserInfo 回未登录；没进答题页，今天的机会没动');
                    return;
                  }
                  line('登录体检: '+lp);
                  // 登录确认了，再切路由 —— 从这一刻起才算「进入答题」。
                  if(String(location.hash).indexOf('answerQuestions')<0){
                    var tk=(function(){
                      var m=String(location.href).match(/[?&]h5token=([^&#]+)/);
                      return m?decodeURIComponent(m[1]):'';
                    })();
                    ENTERED=true;
                    location.hash='#/answerQuestions'+(tk?('?h5token='+encodeURIComponent(tk)):'');
                    line('已切到答题路由');
                    await wait(1500);
                  } else {
                    ENTERED=true;
                  }
                  // 等页面把题目数据解出来：JSON.parse 命中就走，最多等 10 秒。
                  // 老版本只看「判断题」这三个字就往下走，字出来了数据还没到，
                  // 三条取题路全空，白白把一次机会判成失败。
                  for(var w=0;w<24;w++){
                    var body=bodyText();
                    if(window.__cmcc_qs&&window.__cmcc_qs.length) break;
                    if(usedUp()) break;
                    if(/今日已答|今天已经答|明天再来|已参与/.test(body)) break;
                    await wait(500);
                  }
                  if(usedUp()){
                    done('used', usedMsg());
                    return;
                  }
                  var bodyNow=bodyText();
                  var qs=(window.__cmcc_qs&&window.__cmcc_qs.length)?window.__cmcc_qs:null;
                  if(qs) line('取题: 解析钩子拿到 ' + qs.length + ' 道');
                  // v3.69：顺序改了 —— 先用页面已经拿到的数据，最后才自己去请求。
                  // getQuestions 每多调一次，就多一次被服务端记账的风险，能省就省。
                  if(!qs||!qs.length){ qs=fromNet(); if(qs&&qs.length) line('取题: 用抓到的响应解密 ' + qs.length + ' 道') }
                  if(!qs||!qs.length){ qs=fromVue(); if(qs&&qs.length) line('取题: 用页面组件数据 ' + qs.length + ' 道') }
                  if(!qs||!qs.length){ qs=await fromHttp(); if(qs&&qs.length) line('取题: 自己调接口 ' + qs.length + ' 道') }
                  if(!qs||!qs.length){
                    // 取不到题时先看看是不是「今天已经答过」。正常页面即使有
                    // 「已完成」字样也会在上一步拿到题，所以这里不会误判。
                    if(/今日已答|今天已经答|明天再来|已参与|已完成/.test(bodyNow)){
                      done('idle','页面显示今天已经答过');
                      return;
                    }
                    // v3.69：进了答题页就算开始，取不到题这次机会多半也保不住，
                    // 不能再写「机会还在」误导用户反复重跑。
                    done('need-topic','进了答题页但没取到题目数据，这次机会大概率已算掉');
                    return;
                  }
                  line('题目 ' + qs.length + ' 道，答案随题目一起下发');

                  // v3.69：这一批题是【多选题】，isRight=1 可能不止一个。
                  // 老代码在第一个命中项上 break，多选只点一个 —— 09:17 那三题
                  // 各只点了 A，就算机会还在也只能对三分之一。改成全选。
                  var right=0;
                  for(var i=0;i<qs.length;i++){
                    var q=qs[i]||{};
                    var title=String(q.questionTitle||'');
                    var opts=q.optionList||[];
                    var wants=[];
                    for(var k=0;k<opts.length;k++){
                      if(String(opts[k].isRight)==='1') wants.push(String(opts[k].optionInfo||''));
                    }
                    if(!wants.length){ line('第 ' + (i+1) + ' 题没有 isRight，跳过'); continue }
                    var block=blockOf(title);
                    if(!block){ line('第 ' + (i+1) + ' 题在页面上没找到: ' + title.slice(0,24)); continue }
                    var anyHit=false;
                    for(var wi=0;wi<wants.length;wi++){
                      var okOne=await pickOption(block, wants[wi]);
                      line('第 ' + (i+1) + ' 题选「' + wants[wi] + '」' + (okOne?'':'（没命中）'));
                      if(okOne) anyHit=true;
                      await wait(240);
                    }
                    if(anyHit) right++;
                    line('第 ' + (i+1) + ' 题共 ' + wants.length + ' 个正确项');
                    await wait(180);
                  }
                  if(right===0){ done('fail','答案拿到了但一道都没点上，页面结构可能要改'); return }
                  await wait(400);
                  if(usedUp()){ done('used', usedMsg()); return }
                  if(!clickSubmit()){ done('fail','没找到「提交」按钮'); return }
                  line('已提交，等回执');
                  for(var t=0;t<30;t++){
                    await wait(600);
                    if(usedUp()){ done('used', usedMsg()); return }
                    var r=readResult();
                    if(r){
                      var m=r.match(/答对(\\d+)道/);
                      var n=m?parseInt(m[1],10):-1;
                      if(n===qs.length) done('ok','全部答对 ' + n + '/' + qs.length);
                      else if(n>=0) done('fail','答对 ' + n + '/' + qs.length + '，不是全对');
                      else done('ok', r.slice(0,30));
                      return;
                    }
                  }
                  // 18 秒还没结果，把页面提示原文带回去，别再笼统报「没看到弹窗」。
                  var tail=bodyText();
                  var pi=tail.indexOf('提示');
                  done('fail','提交了但没读到结果' + (pi>=0?('；页面提示：'+tail.slice(pi,pi+64)):''));
                }catch(e){
                  done('error', String(e&&e.stack?e.stack:e));
                }
              })();
            })();
            """;

    /**
     * 签到 + 任务 + 抽奖。
     *
     * <p>只点页面上真实存在的按钮，判定看 XHR 回执：{@code signInActivity/signIn}
     * 或 {@code lottery} 返回 200 才算真做了；只有「已签到」文案就记「今天无需操作」。
     */
    private static final String SIGN_JS = """
            (function(){
              var ID='__cmcc_sign';
              window[ID]='';
              var L=[], NET=[];
              function line(s){L.push(String(s))}
              function tx(e){
                if(!e) return '';
                // v3.73：离屏 WebView 没做布局时 innerText 一直返回空串。
                // 11:02 那次脚本 20 秒读到的 body 全是空，最后误报「页面没进对」。
                // textContent 不依赖布局，必须兜底。
                var s=(e.innerText!==undefined?e.innerText:'');
                if(!s) s=e.textContent||'';
                return String(s).replace(/ +/g,' ').replace(/\\n+/g,' ').trim();
              }
              function vis(e){
                try{
                  if(!e||!e.getBoundingClientRect) return false;
                  var r=e.getBoundingClientRect();
                  if(r.width===0&&r.height===0){
                    // 整页没做布局时所有 rect 都是 0x0，按几何判断会一个都点不到。
                    // 退回样式链判断：自身和祖先都没隐藏就算可见。
                    for(var p=e;p&&p.nodeType===1;p=p.parentElement){
                      var ps=getComputedStyle(p);
                      if(ps.display==='none'||ps.visibility==='hidden') return false;
                      if(parseFloat(ps.opacity||'1')<0.05) return false;
                    }
                    return true;
                  }
                  if(r.width<14||r.height<10) return false;
                  var s=getComputedStyle(e);
                  if(s.display==='none'||s.visibility==='hidden') return false;
                  if(parseFloat(s.opacity||'1')<0.05) return false;
                  return true;
                }catch(x){return false}
              }
              // v3.76：离屏 WebView 的宽度是 0，元素的 rect 常见的不是 0x0 而是 0x40，
              // 上一条只处理了宽高都为 0 的情况，于是所有按钮继续被判成不可见 ——
              // 11:55 那次就是：btnList 能列出「确定」，closeTip 却一个都点不到。
              // 这里干脆不看几何，只看自身和祖先有没有被样式隐藏。
              function shown(e){
                try{
                  if(!e) return false;
                  for(var p=e;p&&p.nodeType===1;p=p.parentElement){
                    var cs=getComputedStyle(p);
                    if(cs.display==='none'||cs.visibility==='hidden') return false;
                    if(parseFloat(cs.opacity||'1')<0.05) return false;
                  }
                  return true;
                }catch(x){return false}
              }
              // v3.74：正文不能直接取 textContent —— 页面里的 <script> 内联脚本
              // 也带「签到」字样，DOM 还没渲染时照样能命中，脚本以为页面好了，
              // 11:35 那次 2.5 秒就收工、一个按钮没点。先试 innerText；
              // 真没布局时克隆一份，剔掉 script/style 再取 textContent。
              function bodyText(){
                var b=document.body;
                if(!b) return '';
                var it='';
                try{ it=String(b.innerText||'').trim() }catch(e){}
                if(it) return it.replace(/\\s+/g,'');
                try{
                  var c=b.cloneNode(true);
                  var junk=c.querySelectorAll('script,style,noscript,template');
                  for(var i=0;i<junk.length;i++){
                    if(junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
                  }
                  return String(c.textContent||'').replace(/\\s+/g,'');
                }catch(e){
                  return String(b.textContent||'').replace(/\\s+/g,'');
                }
              }
              function wait(ms){return new Promise(function(r){setTimeout(r,ms)})}
              function done(stage,msg){window[ID]=JSON.stringify({stage:stage,msg:msg,lines:L});}
              function clickEl(e,px,py){
                try{
                  try{e.scrollIntoView({block:'center'})}catch(x){}
                  var r=e.getBoundingClientRect();
                  // v3.84：签到按钮是一张图片（用户长按会弹「保存图片」），
                  // 它外面常盖着透明容器，元素自己的中心点未必是能点中的位置。
                  // 允许调用方传入 elementFromPoint 命中的真实坐标。
                  var cx=(typeof px==='number')?px:(r.left+r.width/2);
                  var cy=(typeof py==='number')?py:(r.top+r.height/2);
                  var opt={bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy};
                  // v3.83：17:02 日志实证 —— 复用前台真页后登录（jtH5Login /
                  // login/user 回 0000）和布局（探针 111x33）都好了，可点了 9 次
                  // 页面一条新 XHR 都没发。这页是 uni-app（UNI-PAGE-BODY、uni-view），
                  // 按钮全是 div/span 上的 @tap，只发 MouseEvent / click 不触发
                  // Vue 的 handler。补一套完整 touch 序列，覆盖 tap 与 click 两种实现。
                  function tev(type,end){
                    try{
                      var t=new Touch({identifier:1,target:e,clientX:cx,clientY:cy,
                        pageX:cx+window.scrollX,pageY:cy+window.scrollY,
                        screenX:cx,screenY:cy,radiusX:1,radiusY:1,rotationAngle:0,force:1});
                      var ev=new TouchEvent(type,{bubbles:true,cancelable:true,view:window,
                        touches:end?[]:[t],targetTouches:end?[]:[t],changedTouches:[t]});
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  tev('touchstart',false);
                  var ts=['pointerdown','mousedown','pointerup','mouseup'];
                  for(var i=0;i<ts.length;i++){
                    try{
                      var ev=ts[i].indexOf('pointer')===0
                        ? new PointerEvent(ts[i],opt) : new MouseEvent(ts[i],opt);
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  tev('touchend',true);
                  // click 仍然只发一次：之前 click 事件和 e.click() 各发一遍，
                  // 等于点两下 —— 选项会被点成取消。
                  try{if(typeof e.click==='function')e.click()}catch(x){}
                  return true;
                }catch(x){return false}
              }
              // v3.79：登录回执必须先验。12:48 日志实证 —— jtH5Login 回 500
              // 「系统登录异常」、login/user 回 403「未登录或过期」，脚本却拿着
              // 缓存里的旧 ssoId 宣布「已就绪」，对着一堆没渲染真实数据的弹窗
              // 模板点了 6 次，一个 XHR 都没有。登录没过就别碰 DOM，更别把
              // 这张不明归属的票留到下次切号继续用。
              var AUTH_DEAD=false, AUTH_MSG='';
              // v3.82：复用 App 前台真页时，pickPage 会先注入 window.__cmcc_front='1'。
              // 前台页是用户亲手打开、App 自己登录完的页面，登录 XHR 早已回过 0000，
              // 脚本注入后不会再重放；但页面运行时某次轮询若回 403，也不该据此复判
              // 登录态、更不该把现场这张有效 ssoId 清掉（14:43 日志实证的死循环）。
              var FRONT=(function(){try{return window.__cmcc_front==='1'}catch(e){return false}})();
              function killSsoCache(){
                if(FRONT){ line('前台真页：保留 App 现场登录态，不清缓存'); return }
                try{
                  var kill=[];
                  for(var ki=0;ki<localStorage.length;ki++){
                    var kk=String(localStorage.key(ki)||'');
                    if(kk.indexOf('__cmcc_sso_')===0) kill.push(kk);
                  }
                  for(var kj=0;kj<kill.length;kj++) localStorage.removeItem(kill[kj]);
                  try{ sessionStorage.removeItem('ssoId') }catch(e){}
                }catch(e){}
              }
              try{
                var oOpen=XMLHttpRequest.prototype.open, oSend=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){
                  this.__qu=String(u||''); return oOpen.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send=function(b){
                  var x=this;
                  x.addEventListener('load',function(){
                    try{
                      var u=String(x.__qu||'');
                      var raw=String(x.responseText||'');
                      if(u.indexOf('login/user')>=0||u.indexOf('jtH5Login')>=0){
                        if(/"code"\s*:\s*"?403"?|"code"\s*:\s*"?401"?|"code"\s*:\s*"?500"?|未登录|登录.{0,4}过期|系统登录异常|重新登录/.test(raw)){
                          AUTH_DEAD=true; AUTH_MSG=raw.slice(0,88);
                          line('登录回执: 失效 ' + AUTH_MSG);
                          killSsoCache();
                        }else{
                          line('登录回执: 正常');
                        }
                      }
                      // v3.83：17:02 日志里点了 9 次却看不到任何新请求 —— 老钩子
                      // 只记录 signInActivity 路径，抽奖 / 任务 / 其它路径全被吞掉，
                      // 排障时看不出点击到底有没有触发页面动作。放宽到整个
                      // wisdom-front，日志里能直接看到点击后页面发了什么。
                      if(u.indexOf('/wisdom-front/')<0) return;
                      var body='';
                      try{
                        var j=JSON.parse(raw);
                        body=(j.data&&typeof j.data==='object')?JSON.stringify(j.data):'';
                      }catch(e){}
                      NET.push({u:u,code:x.status,body:body});
                      line('回执: ' + u.split('?')[0].split('/').pop() + ' ' + x.status);
                    }catch(e){}
                  });
                  return oSend.apply(this,arguments);
                };
              }catch(e){}

              var SEEN={};
              // v3.71：页面把按钮写成「立 即 抽 奖」（字之间带空格），老逻辑按
              // 「立即抽奖」包含匹配永远找不到，退到「抽奖」就点成了「转盘抽奖」tab。
              // 统一去掉空白再比；文本一样长时取层级更深的那个，别点到大容器。
              function norm(s){return String(s||'').replace(/\\s+/g,'')}
              function deepOf(e){var d=0;while(e&&e.parentElement){d++;e=e.parentElement}return d}
              function pick(words,excludes,ok){
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                for(var w=0;w<words.length;w++){
                  var word=norm(words[w]);
                  var best=null,bestLen=1e9,bestDeep=-1;
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!ok(e)) continue;
                    var raw=tx(e), t=norm(raw);
                    if(t.length>word.length+8) continue;
                    if(t.indexOf(word)<0) continue;
                    var bad=false;
                    for(var x=0;x<excludes.length;x++){
                      if(t.indexOf(norm(excludes[x]))>=0){bad=true;break}
                    }
                    if(bad) continue;
                    // 同一个按钮一轮里只点一次：点完 DOM 不一定立刻变，
                    // 不挡的话「立即抽奖」会被连点三次。
                    var k=raw+'|'+String(e.className||'')+'|'+String(e.tagName||'');
                    if(SEEN[k]) continue;
                    var dp=deepOf(e);
                    if(t.length<bestLen||(t.length===bestLen&&dp>bestDeep)){
                      bestLen=t.length;bestDeep=dp;best=e;
                    }
                  }
                  if(best){
                    SEEN[tx(best)+'|'+String(best.className||'')+'|'+String(best.tagName||'')]=1;
                    return best;
                  }
                }
                return null;
              }
              // v3.78：第三级，只按文本精确相等找，完全不看样式。
              // 12:24 的日志里 btnList 退化成了「纯文本采样」，说明 shown() 对所有
              // 元素都返回 false（离屏首帧或入场动画期间），而「确定」「我知道了」
              // 「跳过下一步」明明就在 DOM 里。前两级找不到时允许按文本强点一次。
              function pickExact(words,excludes){
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                for(var w=0;w<words.length;w++){
                  var word=norm(words[w]);
                  var best=null,bestDeep=-1;
                  for(var i=0;i<all.length;i++){
                    var e=all[i], t=norm(tx(e));
                    if(t!==word) continue;
                    var bad=false;
                    for(var x=0;excludes&&x<excludes.length;x++){
                      if(t.indexOf(norm(excludes[x]))>=0){bad=true;break}
                    }
                    if(bad) continue;
                    var dp=deepOf(e);
                    if(dp>bestDeep){bestDeep=dp;best=e}
                  }
                  if(best){
                    SEEN[tx(best)+'|'+String(best.className||'')+'|'+String(best.tagName||'')]=1;
                    return best;
                  }
                }
                return null;
              }
              // 先按真实几何找；离屏布局缺失时退回样式判断；还找不到就按文本强点。
              function hit(words,excludes){
                return pick(words,excludes,vis)||pick(words,excludes,shown)
                  ||pickExact(words,excludes);
              }
              // v3.78 诊断：把候选按钮的尺寸和计算样式一起打出来，
              // 下一份日志就能看出 shown() 为什么全灭，不用再猜。
              function probe(){
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                var out=[];
                for(var i=0;i<all.length&&out.length<12;i++){
                  var e=all[i], t=tx(e);
                  if(!t||t.length>12) continue;
                  if(!/签到|抽奖|确定|知道|跳过|领取|恭喜|奖励|提示/.test(t)) continue;
                  var r={width:0,height:0}; try{ r=e.getBoundingClientRect() }catch(x){}
                  var cs={display:'?',visibility:'?',opacity:'?'}; try{ cs=getComputedStyle(e) }catch(x){}
                  out.push(t.slice(0,8)+'@'+String(e.tagName||'')
                    +'|'+Math.round(r.width)+'x'+Math.round(r.height)
                    +'|'+cs.display+'|'+cs.visibility+'|'+cs.opacity);
                }
                return out.join(' ; ');
              }
              // v3.84：签到按钮是图片 —— 17:35 用户长按「立即签到」弹出了系统的
              // 「保存图片」，说明它就是个 <img>，DOM 里根本没有「立即签到」这四个字
              // 的文本节点。而 hit / probe / btnList / domSnapshot 全都有一道
              // `if(!t) continue`，图片在第一道筛就被丢了，每轮只能点到引流的
              // 「去签到 >」，点 13 次也不会有回执。
              function imgTail(e){
                var s='';
                try{ s=String(e.currentSrc||e.src||e.getAttribute('src')||'') }catch(x){}
                if(!s){
                  try{
                    var bg=String(getComputedStyle(e).backgroundImage||'');
                    var m=bg.match(/url\\(["']?([^"')]+)/);
                    if(m) s=m[1];
                  }catch(x){}
                }
                return String((s.split('?')[0].split('/').pop()||s||'?')).slice(-30);
              }
              // 胶囊按钮的几何特征：宽 80~420、高 24~96、宽高比 >=1.4、可见。
              // 再按「离签到状态区多远」和 src 关键词打分，最像的排最前。
              function imgCands(){
                var out=[], imgs=document.images||[];
                var anchor=null;
                try{
                  var ae=document.querySelectorAll('div,span,uni-view,uni-text');
                  for(var i=0;i<ae.length;i++){
                    var t=norm(tx(ae[i])||'');
                    if(t.indexOf('已连续签到')===0||t.indexOf('连续签到')===0
                       ||t.indexOf('累计签到')===0){
                      var ar=ae[i].getBoundingClientRect();
                      if(ar.width>30&&ar.height>10){ anchor=ar; break }
                    }
                  }
                }catch(x){}
                for(var j=0;j<imgs.length;j++){
                  var e=imgs[j], r={width:0,height:0,left:0,top:0};
                  try{ r=e.getBoundingClientRect() }catch(x){ continue }
                  var w=r.width, h=r.height;
                  if(w<80||h<24||h>96) continue;
                  var ratio=w/h;
                  if(ratio<1.4||ratio>9) continue;
                  var cs={display:'',visibility:'',opacity:''};
                  try{ cs=getComputedStyle(e) }catch(x){}
                  if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0') continue;
                  var s=String(imgTail(e)).toLowerCase();
                  var sc=0;
                  // v3.85：17:42 日志实证 —— 活动页图片文件名就是拼音首字母：
                  //   ljqd.*.png  = 立即签到（171x55，真正触发 signIn 的那张）
                  //   zpchl.*.png = 广告/转盘入口图（275x52，点了只发 getAdvertPointList）
                  //   qddj.*.png  = 签到得奖展示图
                  // v3.84 只按位置打分，把 zpchl 排到了第一，白点一次；
                  // 点广告图还有跳走的风险，跳走整个任务就废了。按名字直接排序。
                  if(/ljqd/.test(s)) sc+=14;
                  if(/sign|qiandao|check|signin|qian/.test(s)) sc+=8;
                  if(/qddj/.test(s)) sc+=5;
                  if(/btn|button|anniu/.test(s)) sc+=4;
                  if(/liji|now|go/.test(s)) sc+=3;
                  if(/zpchl|zpcj|choujiang|zhuanpan/.test(s)) sc-=8;
                  if(/jkyhf|advert|banner|guanggao/.test(s)) sc-=6;
                  if(anchor){
                    var d=Math.abs((r.top+h/2)-(anchor.top+anchor.height+120));
                    sc+=Math.max(0,6-Math.round(d/60));
                  }
                  out.push({e:e,sc:sc,w:w,h:h,x:r.left+w/2,y:r.top+h/2,
                            src:imgTail(e),tag:String(e.tagName||'IMG')});
                }
                out.sort(function(a,b){ return b.sc-a.sc || (b.w*b.h)-(a.w*a.h) });
                return out;
              }
              // 按坐标点：先取这一点的最上层元素，再在这一点派发完整事件序列。
              // 图片按钮外面常盖一层透明容器，点容器才能触发它自己的 @tap。
              function clickAt(x,y){
                var e=null;
                try{ e=document.elementFromPoint(x,y) }catch(ex){}
                if(!e) return null;
                clickEl(e,x,y);
                return e;
              }
              // v3.83：前台真页已布局，旧探针只看 12 个短文本元素，看不到真按钮
              // 的 class / 自定义标签 / Vue 绑定。失败时把全页有尺寸的候选元素
              // 原样导出到日志，下一份日志就能照着真实 DOM 精确改点击逻辑。
              function domSnapshot(){
                var out=[];
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                for(var i=0;i<all.length&&out.length<36;i++){
                  var e=all[i], t=norm(tx(e)||'');
                  if(!t||t.length>22) continue;
                  var r={width:0,height:0}; try{ r=e.getBoundingClientRect() }catch(x){}
                  if(r.width<8||r.height<8) continue;
                  var cs; try{ cs=getComputedStyle(e) }catch(x){}
                  if(cs&&(cs.display==='none'||cs.visibility==='hidden')) continue;
                  var cls=String(e.className||'').replace(/\\s+/g,'.').slice(0,50);
                  var v='';
                  try{ if(e.__vue__||e.__vueParentComponent||e.__vue_component__) v='V' }catch(x){}
                  out.push(t.slice(0,14)+'@'+String(e.tagName||'')+'.'+cls
                    +'|'+Math.round(r.width)+'x'+Math.round(r.height)+'|'+v);
                }
                // v3.84：上面只收有文本的元素，图片按钮永远进不来。
                // 单独把「胶囊图片按钮」候选列出来，下次失败日志能直接看到
                // 签到按钮的 src、尺寸和排序分。
                var ic=imgCands();
                if(ic.length){
                  var q=[];
                  for(var k=0;k<ic.length&&k<8;k++){
                    q.push(ic[k].src+'|'+Math.round(ic[k].w)+'x'+Math.round(ic[k].h)
                      +'|s'+ic[k].sc);
                  }
                  out.push('IMG按钮:'+q.join(' , '));
                }
                return out.join(' ; ');
              }
              // v3.72：失败时把页面上的短按钮一起带回来。签到页的按钮文案
              // 每次活动皮肤都可能不一样，光看「点了几次」猜不出来。
              function btnList(){
                var out=[], all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                for(var i=0;i<all.length&&out.length<16;i++){
                  var e=all[i];
                  if(!shown(e)) continue;
                  var t=tx(e);
                  if(!t||t.length>10) continue;
                  if(!/签到|抽奖|领取|立即|去|已/.test(t)) continue;
                  if(out.indexOf(t)<0) out.push(t);
                }
                // v3.74：可见性判断全灭时（离屏真没布局）上面一条都收不到，
                // 退化成纯文本采样，至少能看出页面渲染到了哪一步。
                if(!out.length){
                  for(var j=0;j<all.length&&out.length<16;j++){
                    var t2=tx(all[j]);
                    if(!t2||t2.length>12) continue;
                    if(out.indexOf(t2)<0) out.push(t2);
                  }
                }
                // v3.84：签到按钮是图片，没有文字，上面永远收不到。
                // 补一段「图片按钮」摘要，失败日志里能直接看到它叫什么、多大。
                var ic=imgCands();
                for(var k=0;k<ic.length&&k<4;k++){
                  var q='IMG:'+String(ic[k].src).slice(0,16)+'|'
                    +Math.round(ic[k].w)+'x'+Math.round(ic[k].h);
                  if(out.indexOf(q)<0) out.push(q);
                }
                return out.join('|');
              }
              // 只探测、不占用 SEEN：等按钮出现时会反复调用，
              // 用 hit() 探一次就把按钮标成「点过了」，真去点的时候反而点不到。
              function peek(words,excludes){
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                for(var w=0;w<words.length;w++){
                  var word=norm(words[w]);
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!shown(e)) continue;
                    var t=norm(tx(e));
                    if(!t||t.length>word.length+8) continue;
                    if(t.indexOf(word)<0) continue;
                    var bad=false;
                    for(var x=0;x<excludes.length;x++){
                      if(t.indexOf(norm(excludes[x]))>=0){bad=true;break}
                    }
                    if(!bad) return true;
                  }
                }
                return false;
              }
              // v3.75：签到页打开会连着弹两三个框，全挡在「立即签到」上面。
              // 11:49 那次抓到的按钮是：
              //   规则说明 / 确定 / 提示 / 连续签到第7天 / 恭喜您 /
              //   连签7天获得「惊喜奖励四选一」/ 请选择连签7天奖励 / 确认领取 / 跳过下一步
              // 脚本一个都没点，直接报「没有可点的按钮」。
              function closeTip(){
                var all=document.querySelectorAll('button,a,div,span,p,li,uni-view,uni-button,uni-text,uni-image,view,text,image,[role=button],[class*=btn],[class*=sign]');
                var words=['确定','我知道了','知道了','好的','关闭'];
                var best=null,bestDeep=-1,bestTx='';
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!shown(e)) continue;
                  var t=norm(tx(e));
                  if(!t||t.length>8) continue;
                  var match=false;
                  for(var k=0;k<words.length;k++){ if(t===words[k]){match=true;break} }
                  if(!match) continue;
                  var d=deepOf(e);
                  if(d>bestDeep){bestDeep=d;best=e;bestTx=t}
                }
                // 奖励选择弹窗没有普通关闭按钮，只有「跳过下一步」。
                // 先跳过：选奖品要判断哪个划算，脚本替用户选容易选亏，签到才是主线。
                if(!best){
                  for(var j=0;j<all.length;j++){
                    var e2=all[j];
                    if(!shown(e2)) continue;
                    var t2=norm(tx(e2));
                    if(t2==='跳过下一步'||t2==='跳过'){
                      var d2=deepOf(e2);
                      if(d2>bestDeep){bestDeep=d2;best=e2;bestTx=t2}
                    }
                  }
                }
                if(!best){
                  // 普通关闭词优先；没有才点「跳过下一步」，顺序不能反 ——
                  // 反了会在有「确定」时把奖励弹窗跳过。
                  best=pickExact(words.concat(['跳过下一步','跳过']),null);
                  if(best){
                    // v3.84：17:35 日志实证 —— 这个兜底在一轮里强点了 20 多次
                    // DIV|0x0|display:none 的隐藏「确定」，把页面状态反复扰动，
                    // 而真正要点的签到图一次都没点到。没有实际尺寸就不点。
                    var br={width:0,height:0};
                    try{ br=best.getBoundingClientRect() }catch(x){}
                    if(br.width<8||br.height<8){ best=null }
                    else{ bestTx=norm(tx(best)); line('弹窗按钮被样式判隐藏，按文本强点') }
                  }
                }
                if(!best) return false;
                clickEl(best);
                line('关掉弹窗: '+bestTx);
                return true;
              }

              (async function(){
                try{
                  var href=String(location.href||'');
                  if(href.indexOf('wisdom-h5')<0){done('no-page',href);return}
                  // v3.64：先看上一次真实页面登录留下的 ssoId（localStorage 同域共享）。
                  // 有就直接用 —— 这条路完全不需要 h5token，也不怕 token 一次性。
                  var sso='', ownCache=false;
                  try{
                    var jw=String(localStorage.getItem('QWHDSSOJWT')||'');
                    var acc='';
                    try{ acc=String(window.__cmcc_acc||localStorage.getItem('__cmcc_acc')||'') }catch(e){}
                    var ph0=String(window.__cmcc_phone||'');
                    if(!ph0){ try{ ph0=String(localStorage.getItem('__cmcc_phone')||'') }catch(e){} }
                    // v3.72：UID 会漂移（10:34 是 mhrz183097fb，10:41 登录后变成
                    // mhrze7e12968），按 UID 分桶等于每次冷启动都读不到。改成扫全表：
                    // 带 _ph 且与本号掩码一致的最优先；没有 _ph 的旧键只认 2 小时内。
                    // v3.79：只认「带 _ph 且等于本号」的桶。12:48 日志实证：
                    // 139 桶 / 198 桶 / 无 _ph 旧键三个 key 存的是同一个 ssoId，
                    // 切到 198 后拿 139 的登录态去请求 → login/user 403。
                    // 无归属的旧键和别的号的桶一律就地清掉，宁可让页面用
                    // h5token 重新登录，也不要跨号沿用一张身份不明的票。
                    var cands=[], purge=[];
                    for(var li=0;li<localStorage.length;li++){
                      var lk=String(localStorage.key(li)||'');
                      if(lk.indexOf('__cmcc_sso_')!==0) continue;
                      if(lk.slice(-3)==='_at'||lk.slice(-3)==='_ph') continue;
                      var lv=String(localStorage.getItem(lk)||'');
                      var la=Number(localStorage.getItem(lk+'_at')||0);
                      var lp=String(localStorage.getItem(lk+'_ph')||'');
                      if(!lp){
                        // v3.80：13:36 日志实证 —— 用户在 App 里手动点开签到页，
                        // 页面登录成功后 moveSso 把那张有效 ssoId 写成无 _ph 的桶；
                        // v3.79 一律清掉，等于把刚拿到的登录态删了，重跑又回到
                        // 「没票」死循环。现在：无归属但近期写入的桶认领到本号，
                        // 只有明确属于别的号、或陈年旧票才清掉。下面 cands 会按
                        // _at 排序取最新，旧的同值桶排后面、且归一阶段会被合并。
                        var fresh=la>0&&Date.now()-la<43200000;
                        if(ph0&&fresh&&lv&&lv.length>=16){
                          try{ localStorage.setItem(lk+'_ph',ph0); lp=ph0;
                               line('认领无归属登录态桶（补写本号 _ph）') }catch(e){}
                        }
                      }
                      if(!lp||(ph0&&lp!==ph0)){ purge.push(lk); continue }
                      if(!lv||lv.length<16) continue;
                      if(la&&Date.now()-la>=43200000) continue;
                      cands.push({k:lk,v:lv,a:la,own:1});
                    }
                    for(var pi=0;pi<purge.length;pi++){
                      try{
                        localStorage.removeItem(purge[pi]);
                        localStorage.removeItem(purge[pi]+'_at');
                        localStorage.removeItem(purge[pi]+'_ph');
                      }catch(e){}
                    }
                    if(purge.length) line('已清掉 '+purge.length+' 个无归属/非本号登录态桶');
                    cands.sort(function(x,y){return y.a-x.a});
                    var pick=cands.length?cands[0]:null;
                    if(pick){
                      sessionStorage.setItem('ssoId',pick.v); sso=pick.v; ownCache=true;
                      line('ssoId 取自本号缓存');
                      // 归一到「手机号桶」：UID 再漂也不会丢。
                      var nk='__cmcc_sso_'+(ph0||acc||'x')+'_'+jw.slice(0,12);
                      try{
                        localStorage.setItem(nk,pick.v);
                        localStorage.setItem(nk+'_at',String(pick.a||Date.now()));
                        if(ph0) localStorage.setItem(nk+'_ph',ph0);
                        if(ph0){
                          for(var ci=0;ci<cands.length;ci++){
                            var ok0=cands[ci].k;
                            if(ok0===nk) continue;
                            localStorage.removeItem(ok0);
                            localStorage.removeItem(ok0+'_at');
                            localStorage.removeItem(ok0+'_ph');
                          }
                        }
                        if(pick.k!==nk) line('已把登录态归到本号桶');
                      }catch(e){}
                    }
                  }catch(e){}
                  if(!ownCache){
                    // 没有本账号的缓存。先看页面现在登录的是不是这个号：
                    // 是 → 直接接管它的 ssoId 并存进本账号桶，不用重新登录；
                    // 不是 → 清掉旧值，等页面自己用 h5token 重新登录。
                    var ss='', pm='';
                    var ph=String(window.__cmcc_phone||'');
                    if(!ph){ try{ ph=String(localStorage.getItem('__cmcc_phone')||'') }catch(e){} }
                    try{ ss=String(sessionStorage.getItem('ssoId')||'') }catch(e){}
                    try{ pm=String(sessionStorage.getItem('loginMobile')||'') }catch(e){}
                    if(ph&&pm&&pm===ph&&ss&&ss.length>=16){
                      sso=ss; ownCache=true;
                      try{
                        var jw2=String(localStorage.getItem('QWHDSSOJWT')||'');
                        var ck2='__cmcc_sso_'+(ph||acc||'x')+'_'+jw2.slice(0,12);
                        localStorage.setItem(ck2,ss);
                        localStorage.setItem(ck2+'_at',String(Date.now()));
                        if(ph) localStorage.setItem(ck2+'_ph',ph);
                      }catch(e){}
                      line('页面已是本账号（'+pm+'），直接接管登录态');
                    }else if(ph&&pm&&pm!==ph){
                      // 明确知道页面登录的是另一个号 —— 这种必须清掉，
                      // 否则就会拿别人的身份去领奖。
                      try{ sessionStorage.removeItem('ssoId') }catch(e){}
                      line('页面登录的是 '+pm+'，不是本账号，等它重新登录');
                    }else{
                      // 手机号没取到、或页面还没写 loginMobile：判断不了，
                      // 沿用页面现值（退回 v3.69 行为），别一刀切把能用的也废了。
                      if(ss&&ss.length>=16){
                        sso=ss; ownCache=true;
                        line('账号校验信息不全，沿用页面登录态');
                      }
                    }
                  }
                  for(var sw=0;sw<25;sw++){
                    try{ sso=String(sessionStorage.getItem('ssoId')||'') }catch(e){}
                    if(sso&&sso.length>=16) break;
                    await wait(400);
                  }
                  if(!sso||sso.length<16){
                    done('need-token','入口没带 h5token，页面换不到 ssoId（login/user 会回 403）');
                    return;
                  }
                  line('ssoId 已就绪: '+sso.slice(0,8)+'…');
                  // v3.79：给自己 1.5 秒收页面那几条登录回执，再决定要不要动手。
                  await wait(1500);
                  // v3.82：前台真页已登录，页面自己的轮询回执不参与本次判定，
                  // 更不能触发早停把这张有效票清掉；离屏页仍按 v3.79 早停。
                  if(AUTH_DEAD&&!FRONT){
                    done('need-login','页面登录被服务端拒绝（' + AUTH_MSG + '）；已清掉本地 ssoId 缓存，本次没点任何按钮');
                    return;
                  }
                  if(AUTH_DEAD) line('前台真页：忽略页面轮询回执，继续用已登录现场');
                  // v3.74：等「真能点的按钮」出现，不再等「正文里有签到两个字」。
                  // 11:35 那次页面 871ms 就报就绪，DOM 里只有内联脚本，正文一命中就收工，
                  // 实际按钮一秒后才渲染出来，脚本已经判完「没有可点的」了。
                  var SIGN_WORDS=['立即签到','去签到','点击签到','签到'];
                  var SIGN_SKIP=['已签到','签到集卡','签到记录','签到补签','签到奖励',
                                 '签到日历','每日签到','签到3次','连续签到','规则说明',
                                 '确认领取','跳过下一步','惊喜奖励'];
                  var ready=false, tips=0;
                  for(var w=0;w<60;w++){
                    var b0=bodyText();
                    if(!b0){ await wait(500); continue; }
                    // 弹窗盖在上面的时候底下按钮点不到，先关掉再说。
                    if(tips<4&&closeTip()){ tips++; await wait(700); continue; }
                    // v3.78：不再拿「签到成功」四个字当就绪 —— 规则文案里也可能有，
                    // 12:24 那次就是在弹窗还没关、按钮还没出时被它提前判了就绪。
                    if(/今日已签到|今天已签到/.test(b0)){ ready=true; break; }
                    if(/请选择连签|惊喜奖励四选一|连签\\d+天获得/.test(b0)){ ready=true; break; }
                    if(peek(SIGN_WORDS,SIGN_SKIP)){ ready=true; break; }
                    if(peek(['立即抽奖','抽奖','转盘'],
                            ['转盘抽奖','每签到3次','去兑换','抽奖记录'])){ ready=true; break; }
                    await wait(500);
                  }
                  line('页面就绪: ' + (ready?'按钮已出现':'30 秒内没等到按钮') + ' / ' + btnList().slice(0,140));
                  line('探针: ' + probe());
                  var body=bodyText();
                  // v3.73：body 空要单独说清楚。11:02 那次脚本读到的正文一直是空串，
                  // 却打印「今天还没签」——看起来像页面正常但没签，实际是页面被拆了。
                  if(!body) line('页面正文为空（离屏未布局，或页面已被销毁）');
                  // v3.71：只要页面还有「去签到」，今天就一定没签。连续天数是历史值，
                  // 昨天签过会显示 3 天，拿它当「今天已签」会直接漏签。
                  // v3.79：12:48 探针实证 —— 页面把「规则说明/确定/连续签到第7天/
                  // 惊喜奖励四选一/我知道了/恭喜您/请选择连签7天奖励/确认领取/
                  // 跳过下一步」这些互斥弹窗的模板文案同时塞在 DOM 里（全 0x0），
                  // 靠 CSS 控制显隐。textContent 一把全读进来，于是「请选择连签」
                  // 「签到成功」永远都在，拿它判已签就是假成功。先看是不是模板堆：
                  var MARKS=['规则说明','请选择连签','惊喜奖励四选一','我知道了',
                             '恭喜您','确认领取','跳过下一步','立即体验','集卡完成'];
                  function pileCount(){
                    var b=bodyText(), n=0;
                    for(var mi=0;mi<MARKS.length;mi++) if(b.indexOf(MARKS[mi])>=0) n++;
                    return n;
                  }
                  function signedNow(){
                    var b=bodyText();
                    // 模板堆里这些词全是假状态，一律不许当「已签」证据。
                    if(pileCount()>=3) return false;
                    // v3.86：签到按钮是图片，签到前后会换图 ——
                    //   未签：ljqd（立即签到）171x55
                    //   已签：yqd （已签到）  171x55
                    // 17:55 日志实证：服务端那边今天早已签过，页面上 20 次点击全打空，
                    // 而正文里常驻一个「去签到 >」引导按钮，把下面那条 indexOf 判断
                    // 钉死成「未签」，于是每轮都去点一遍。图片名字是硬状态，优先级最高。
                    var hasYqd=false, hasLjqd=false;
                    try{
                      var ims=document.images||[];
                      for(var i=0;i<ims.length;i++){
                        var fn=String(imgTail(ims[i])).toLowerCase();
                        if(fn.indexOf('ljqd')===0){ hasLjqd=true }
                        if(fn.indexOf('yqd')===0){ hasYqd=true }
                      }
                    }catch(x){}
                    // 两张同时出现是不可能的；真出现就按未签处理，宁可多点一次也不漏签。
                    if(hasLjqd) return false;
                    if(hasYqd){ line('已签到图片: yqd.*.png（按钮已变成「已签到」）'); return true }
                    if(/今日已签到|今天已签到|签到成功/.test(b)) return true;
                    if(/请选择连签|惊喜奖励四选一|连签\\d+天获得|连签\\d+天奖励/.test(b)) return true;
                    if(b.indexOf('去签到')>=0) return false;
                    return false;
                  }
                  // 12:48 探针：候选按钮全是 0x0、display 却 block/flex 混合，
                  // 说明离屏 WebView 没有真正布局 —— innerText 恒为空，脚本只能
                  // 退回 textContent，于是把 9 个互斥弹窗模板一次全读进正文。
                  // 只有「正文走了 textContent 兜底」+「模板堆」同时成立，才是
                  // 页面没渲染；正常可见页 innerText 非空，不会误伤。
                  var laidOut=(function(){ try{ return String(document.body.innerText||'').trim().length>0 }catch(e){ return false } })();
                  if(!laidOut && pileCount()>=3){
                    line('页面未布局: innerText 为空 / 模板堆 '+pileCount()+' 个 / 探针见下行');
                    line('探针: ' + probe());
                    done('need-login','离屏页没有布局（按钮全 0x0、innerText 为空），只读到 Vue 弹窗模板；'
                         + '登录态也未被服务端确认，没有点任何按钮');
                    return;
                  }
                  var signedAlready=signedNow();
                  if(signedAlready) line('页面文案: 今天已经签到过了');
                  else if(!body) line('页面文案: 读不到正文，先按未签到尝试');
                  else line('页面文案: 今天还没签，准备点签到');

                  var detail=[];
                  var did=0;

                  // 动手前把残留弹窗再清一遍：签到成功、领奖都会再弹一层。
                  for(var tp=0;tp<2;tp++){
                    if(closeTip()){ await wait(600); } else break;
                  }

                  // v3.83：17:02 日志实证 —— 复用前台真页后页面默认停在「转盘抽奖」
                  // 区（探针里有「立 即 抽 奖」），旧脚本却把「每日签到」列进 SKIP
                  // 永远点不到，只能点到引导按钮「去签到 >」，9 次点击一条 signIn
                  // 请求都没发。改成：先切 tab → 每点一次重新扫 DOM → 以接口回执
                  // 为闭环，收到 signIn 就收手，不再盲目连点。
                  function netSign(){
                    for(var q=0;q<NET.length;q++){
                      var n=NET[q];
                      if(n.code!==200) continue;
                      var sg=n.u.split('?')[0].split('/').pop();
                      if(sg==='signIn'||sg==='doSign'||sg==='sign'||sg==='signInDo') return true;
                    }
                    return false;
                  }
                  // 1) 先切到「每日签到」tab（新版活动页三个 tab：每日签到 /
                  //    转盘抽奖 / 签到集卡，签到动作在第一个 tab 里）
                  var tab=hit(['每日签到'],['签到集卡','签到记录','签到补签','已签到']);
                  if(tab){
                    clickEl(tab); did++;
                    line('切到「每日签到」');
                    await wait(1200);
                    SEEN={};
                  }

                  // 2) 签到：多轮重新扫描，每轮都查回执
                  var SIGN_WORDS2=['立即签到','点击签到','签到领奖','马上签到','去签到','签到'];
                  var SIGN_SKIP2=['已签到','签到集卡','签到记录','签到补签','签到奖励',
                                  '签到日历','每日签到','签到3次','签到补签卡','连续签到',
                                  '累计签到','每签到','确认领取','跳过下一步','惊喜奖励',
                                  '规则说明','去兑换','米粒'];
                  if(!signedAlready){
                    // v3.84：签到按钮是图片，没有文本。先把页面上符合「胶囊图片」
                    // 特征的候选全列出来，日志里必须先能看见它，否则下一轮还是猜。
                    var ic0=imgCands();
                    if(ic0.length){
                      var q0=[];
                      for(var z=0;z<ic0.length&&z<6;z++){
                        q0.push(ic0[z].src+'|'+Math.round(ic0[z].w)+'x'+Math.round(ic0[z].h)
                          +'|s'+ic0[z].sc);
                      }
                      line('页面图片按钮: '+q0.join(' , '));
                    }else{
                      line('页面图片按钮: 没找到符合胶囊特征的 img');
                    }
                    var triedImg={};
                    for(var sw=0;sw<6;sw++){
                      if(netSign()) break;
                      for(var ct=0;ct<2;ct++){ if(closeTip()){ await wait(500) } else break }
                      var moved=false;
                      // ① 图片按钮：每轮换一个没试过的，点完就查回执，收到立刻收手
                      var ic=imgCands(), im=null;
                      for(var ci=0;ci<ic.length;ci++){
                        var ikey=ic[ci].src+'|'+Math.round(ic[ci].w)+'x'+Math.round(ic[ci].h);
                        if(!triedImg[ikey]){ im=ic[ci]; triedImg[ikey]=1; break }
                      }
                      if(im){
                        detail.push('点图片:'+String(im.src).slice(0,12));
                        line('点图片按钮「'+String(im.src).slice(0,16)+'」'
                          +Math.round(im.w)+'x'+Math.round(im.h)+' s'+im.sc);
                        var hitEl=clickAt(im.x,im.y);
                        if(hitEl){
                          try{
                            line('点击落点: '+String(hitEl.tagName||'')+'.'
                              +String(hitEl.className||'').slice(0,26));
                          }catch(x){}
                        }
                        did++; moved=true;
                        await wait(2000); SEEN={};
                        if(netSign()) break;
                      }
                      // ② 文本兜底（「去签到 >」这类引流按钮）
                      var s=hit(SIGN_WORDS2,SIGN_SKIP2);
                      if(s){
                        clickEl(s); did++; moved=true;
                        detail.push('点签到:' + tx(s).slice(0,10));
                        line('点签到「' + tx(s).slice(0,12) + '」');
                        await wait(1600);
                        SEEN={};
                      }
                      if(!moved) break;
                    }
                  }

                  // 3) 任务：去完成 / 去浏览 / 领取
                  for(var r=0;r<4;r++){
                    if(netSign()) break;
                    var t=hit(['去完成','去浏览','去领取','立即领取','领取'],
                              ['已领取','已完成','已结束']);
                    if(!t) break;
                    clickEl(t); did++;
                    detail.push('点任务:' + tx(t).slice(0,10));
                    line('点任务「' + tx(t).slice(0,12) + '」');
                    await wait(2000);
                    SEEN={};
                  }

                  // 4) 抽奖
                  if(!netSign()){
                    for(var tp2=0;tp2<2;tp2++){
                      if(closeTip()){ await wait(600); } else break;
                    }
                    for(var d=0;d<3;d++){
                      var l=hit(['立即抽奖','抽奖','转盘'],
                                ['抽奖记录','抽奖机会已用完','暂无抽奖','转盘抽奖','每日签到','签到集卡','去兑换','每签到3次']);
                      if(!l) break;
                      clickEl(l); did++;
                      detail.push('点抽奖');
                      line('点「' + tx(l).slice(0,10) + '」');
                      await wait(3200);
                      SEEN={};
                    }
                  }

                  await wait(1500);
                  var sign=0, drawn=0;
                  for(var i=0;i<NET.length;i++){
                    var n=NET[i];
                    if(n.code!==200) continue;
                    // 取路径最后一段精确比对：signInTaskList / signInDoTask 也带
                    // "signIn" 前缀，用包含判断会把「列任务」当成「签到成功」。
                    var seg=n.u.split('?')[0].split('/').pop();
                    if(seg==='signIn') sign++;
                    if(seg==='lottery'||seg==='lotteryDo') drawn++;
                  }
                  var okSign=(sign>0)||signedNow();
                  if(okSign) done('ok',(sign>0?'签到有回执'
                    :(signedAlready?'今天已经签过（按钮已是「已签到」）':'页面已显示签到成功'))
                    +'，动作 ' + did + ' 次');
                  else if(drawn>0) done('ok','抽奖有回执，动作 ' + did + ' 次');
                  else if(signedAlready) done('idle','今天已签到（动作 ' + did + ' 次）');
                  else if(did>0){
                    // v3.83：点击没生效时把真实 DOM 带回来，别再靠猜按钮文案。
                    line('DOM 快照: ' + domSnapshot());
                    done('fail','点了 ' + did + ' 次但页面没回执: ' + detail.join(' / ') + '；按钮: ' + btnList());
                  }
                  else if(!body) done('no-page','页面正文为空：DOM 没渲染出来，或页面在任务途中被销毁');
                  else if(body.indexOf('签到')<0) done('no-page','页面上没有签到字样，可能没进对页面');
                  // v3.74：一个字都没点到不能记「完成」。11:35 那次页面还没渲染，
                  // 脚本 2.5 秒收工，摘要却打勾，用户以为签过了。
                  else done('fail','页面上找不到可点的按钮，什么都没做；按钮: ' + btnList());
                }catch(e){
                  done('error', String(e&&e.stack?e.stack:e));
                }
              })();
            })();
            """;
}
