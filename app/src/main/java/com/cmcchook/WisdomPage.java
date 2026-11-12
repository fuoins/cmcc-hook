package com.cmcchook;

/**
 * 用户在中国移动 App 里亲手打开的 wisdom-h5 签到 / 问答页（前台真实 WebView）。
 *
 * <p>14:43 日志实证：同一个地址、同一张票（{@code index.html#/signInLottery?h5token=…}），
 * 两条路的结果完全不同：
 * <ul>
 *   <li>App 前台真页：从 {@code https://rr.ln139.cn/} 跳入，有来源、瑞数动态防护完整，
 *       {@code jtH5Login?LJRkIeoW=…} 回 {@code 0000}，{@code login/user} 带 ssoId 回 {@code 0000}，
 *       页面真实渲染、按钮有几何尺寸。</li>
 *   <li>模块另开的离屏页：来源为空、{@code jtH5Login} 是裸 URL、{@code ssoId} 空 → {@code 500}，
 *       {@code login/user} → {@code 403}，按钮全 {@code 0x0}、{@code innerText} 为空、只读到 Vue 弹窗模板。</li>
 * </ul>
 *
 * <p>病根不在 URL 形态（v3.81 已改单 hash，入口和手动页一模一样仍失败），也不在票据
 * （14:43 那张是不到 1 分钟前 App 现场下发的），而是离屏 WebView 缺来源 / 瑞数环境 / 布局。
 * 卡券模块早就用「复用前台真页」解决过同一类问题，这里照搬：
 * 优先在用户自己打开的那个页面上跑脚本，只有用户没打开时才退回离屏兜底。
 *
 * <p><b>前台真页绝不 destroy</b> —— 那是用户正在看的界面。
 */
final class WisdomPage {

    /** 前台页可能被用户切走，15 分钟后不再复用。 */
    private static final long TTL_MS = 15L * 60_000L;

    private static volatile Object view;
    private static volatile long at;
    private static volatile String viewUrl = "无";
    private static volatile String viewClass = "无";

    private WisdomPage() {
    }

    /** WebViewHooks 在页面加载时登记；离屏任务页不算现场。 */
    static void attach(Object wv, String url) {
        if (wv == null) {
            return;
        }
        try {
            Object offscreen = OffscreenWeb.current();
            if (offscreen != null && offscreen == wv) {
                return;
            }
        } catch (Throwable ignored) {
        }
        view = wv;
        at = System.currentTimeMillis();
        if (url != null && !url.isEmpty()) {
            viewUrl = url;
        }
        try {
            viewClass = wv.getClass().getName();
        } catch (Throwable ignored) {
            viewClass = "未知";
        }
    }

    static void detach(Object wv) {
        if (wv != null && view == wv) {
            view = null;
        }
    }

    /** TTL 内登记过的前台 WebView；否则 null。真正的可用性校验交给 {@link #ready}。 */
    static Object get() {
        Object v = view;
        if (v == null || System.currentTimeMillis() - at > TTL_MS) {
            return null;
        }
        return v;
    }

    /**
     * 现场复核：这张前台真页能不能直接复用。
     *
     * <p>v3.94：23:35 日志实证 —— 23:30:56.735 已登记「前台真页」，URL 是
     * {@code .../wisdom-h5/index.html#/signInLottery?h5token=…}；同一毫秒 App 又
     * loadUrl 裸 {@code .../index.html}，瑞数再重写成
     * {@code .../index.html?ut23VwQPael2=…} —— hash 路由 {@code #/signInLottery} 被吃掉。
     * 23:30:57.562「页面就绪」现场 {@code ready=loading / html=0 / vue=no}；
     * 23:30:57.572 老判据拿运行时 {@code location.href} 找 signinlottery 找不到，
     * 加上 ssoId/正文都为空 → 判「没找到可用的前台真页」掉回离屏兜底 →
     * 离屏没登录 → 403 → 签到抽奖/问答被跳过。用户点名的「进入有问题」就是这里。
     *
     * <p>用户亲手在软件内打开的那张页，<b>登记 URL 已经证明它就是目标活动页</b>
     * （{@code attachWisdom} 只登记 signinlottery / answerquestions）；SPA 同页可切
     * hash 路由，签到与问答共用一张 WebView 也合理。所以 TTL 内登记过 wisdom-h5
     * 前台真页就直接复用，不再被运行时 URL 改写否掉。
     *
     * @param route 小写路由关键字，保留参数以兼容 frontReady / pickPage 调用
     */
    /**
     * v3.95：07:45 日志实证 —— v3.94 把这里放宽成「登记 URL 含 wisdom-h5 就复用」，
     * 丢掉了路由区分，结果一键跑到最后的「乡友问答」时，把用户手动打开的
     * {@code #/signInLottery} 签抽页也复用给了问答任务。QUIZ_JS 切
     * {@code location.hash='#/answerQuestions'} 触发 SPA 重载，把注入脚本冲掉
     * （全文连 answerQuestions 都没出现，脚本零回报）→ await 死等 240 秒、
     * 超时误锁今天机会，还把用户正在手动抽奖的页面切走。
     *
     * <p>判据用<b>登记 URL</b>（{@link #viewUrl}，用户打开那一刻的原始地址，带完整
     * hash 路由）而非运行时 {@code location.href}——运行时 hash 会被瑞数重写吃掉，
     * 那正是 v3.94 要修的 23:35 问题；登记 URL 不受影响，两者可兼得。
     */
    static boolean ready(String route) {
        Object v = get();
        if (v == null) {
            return false;
        }
        // v4.10.0-r5：登记过 ≠ 还活着。11-12 08:18 日志实证 —— 领票流程把这个
        // WebView 的 Activity 关掉了，这里仍判「有前台真页」，任务于是拿着一个
        // Activity 已 finish 的 WebView 注入脚本：第一秒还能跑，之后 JS 引擎不再调度，
        // evaluateJavascript 永不回调，表现就是「心跳丢失 → 重注入 3 次 → 全无进度」。
        // Activity 一旦 finish 就当场摘掉，让上层去开一页新的真页。
        if (!RealPage.alive(v)) {
            detach(v);
            return false;
        }
        String logged = String.valueOf(viewUrl).toLowerCase(java.util.Locale.US);
        if (!logged.contains("wisdom-h5")) {
            // 没登记 URL（老数据 / 异常路径）：退回运行时判据，只要求还在 wisdom-h5 域，
            // 不再要求 body>20 字或 ssoId>=16（登录态由换票保证）。
            String js = "(function(){try{"
                    + "var h=String(location.href||'').toLowerCase();"
                    + "return h.indexOf('wisdom-h5')>=0?'1':'0';"
                    + "}catch(e){return '0'}})()";
            return "1".equals(eval(v, js));
        }
        String r = route == null ? "" : route.toLowerCase(java.util.Locale.US);
        String front = loggedRoute();
        // 问答只复用明确的问答页；签抽页/路由未识别页一律不复用给问答
        // （问答脚本要切 hash，必然冲掉自己、还会切走用户的前台页）。
        if (r.equals("answerquestions")) {
            return front.equals("answerquestions");
        }
        // 签抽复用签抽页，或 hash 被瑞数重写后识别不出路由的 wisdom-h5 页
        // （v3.94 的 23:35 兜底场景，保持不回归）。
        if (r.equals("signinlottery")) {
            return front.equals("signinlottery") || front.isEmpty();
        }
        return true;
    }

    /** v3.95：从登记 URL 识别这张前台真页是哪条路由。 */
    static String loggedRoute() {
        String u = String.valueOf(viewUrl).toLowerCase(java.util.Locale.US);
        if (u.contains("answerquestions")) {
            return "answerquestions";
        }
        if (u.contains("signinlottery")) {
            return "signinlottery";
        }
        return "";
    }

    /** 同步求值；页面已销毁或脚本报错返回 null。 */
    static String eval(Object v, String js) {
        if (v == null || js == null) {
            return null;
        }
        try {
            return JsInjector.evalValue(v, js);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 排障用：把登记状态原样带进日志。 */
    static String status() {
        Object v = view;
        if (v == null) {
            return "登记: 无（App 没打开签到页，或页面已被切走）";
        }
        long age = System.currentTimeMillis() - at;
        String ago = age < 1000 ? (age + "ms") : ((age / 1000) + "s");
        String front = loggedRoute();
        return "登记: 有（" + ago + " 前）\n类: " + viewClass + "\n地址: " + viewUrl
                + "\n存活: " + (RealPage.alive(v) ? "是（Activity 未 finish）"
                : "否（承载它的 Activity 已关闭，脚本不会再有回报）")
                + "\n进入判定(v3.95): 登记路由=" + (front.isEmpty() ? "未识别" : front)
                + "；问答只复用问答页、签抽只复用签抽页/未识别页（避免串页切走前台页）";
    }
}
