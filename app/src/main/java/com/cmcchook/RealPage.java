package com.cmcchook;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.util.List;

/**
 * App 官方真页 = 任务现场。
 *
 * <p>v4.0.9 起，签到/砸蛋/鹅蛋/福利/卡券/流量这些「执行」动作不再用模块自建的
 * 离屏 WebView，而是直接在 App 自己的 H5 容器（{@code CommonHtml5Activity}）里跑，
 * 脚本、时序、结果回填全部保持原样 —— 只是把「页面」换了。
 *
 * <p>这么改的原因在 11-10 的三份真机日志里：离屏页没有 App 容器的初始化流程
 * （Vue 不 mount、券接口一个不发、余量模块停在 loadInit），补桥、补 Cookie、
 * 复用带参地址都没能补齐；同一时刻真页一打开，接口全 200、Vuex 全量树直接可读。
 *
 * <p>这个类负责三件事：
 * <ol>
 *   <li>登记 App 真页的 WebView（{@link WebViewHooks} 在 loadUrl 回调里喂进来）；</li>
 *   <li>App 不在前台时自动把它拉起来；</li>
 *   <li>用 App 自己的容器打开目标地址，等页面可用后返回一个 {@link WebPage} 句柄。</li>
 * </ol>
 *
 * <p>一次任务串行执行期间会锁定第一个可用的真页（{@link #beginSession()}），
 * 后续活动在这个 WebView 里换地址，不叠出十几个 Activity。
 */
final class RealPage {

    /** 真页登记表；只保留最近若干条，按时间倒序找。 */
    private static final int SLOTS = 12;
    private static final Object[] VIEWS = new Object[SLOTS];
    private static final String[] URLS = new String[SLOTS];
    private static final long[] AT = new long[SLOTS];
    private static int cursor;

    /** 登记超过这么久就不再用（页面可能已经销毁或被切走）。 */
    static final long TTL_MS = 10L * 60_000L;

    /** 任务会话：串行任务共用同一个真页 WebView（仅限同域）。 */
    private static volatile Object sessionView;
    private static volatile String sessionUrl = "";
    private static volatile int sessionDepth;

    private RealPage() {
    }

    // ------------------------------------------------------------------ 登记

    /** WebViewHooks 在 loadUrl 回调里调用。离屏页一律不登记。 */
    static void note(Object view, String url) {
        if (view == null || url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("javascript:") || url.startsWith("about:")
                || url.startsWith("data:") || url.startsWith("blob:")) {
            return;
        }
        if (isOffscreen(view)) {
            return;
        }
        synchronized (VIEWS) {
            int i = cursor % SLOTS;
            VIEWS[i] = view;
            URLS[i] = url;
            AT[i] = System.currentTimeMillis();
            cursor++;
        }
    }

    private static boolean isOffscreen(Object view) {
        try {
            Object cur = OffscreenWeb.current();
            return cur != null && cur == view;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 页面比较键：{@code scheme://host/path#/路由}，query 一律丢掉。
     *
     * <p>11-10 22:57 实证：wisdom-h5 是 hash 路由的 SPA，
     * {@code index.html#/signInLottery} 和 {@code index.html#/answerQuestions}
     * 只差一个 hash。旧实现把 hash 一起丢掉，问答任务就被判成「已经在这一页」，
     * 一次都没导航，直接跑在签抽页上，然后报「请在福利专区单独打开问答页」。
     */
    static String pathKey(String url) {
        if (url == null) {
            return "";
        }
        String base = url;
        String hash = "";
        int h = base.indexOf('#');
        if (h >= 0) {
            hash = base.substring(h);
            base = base.substring(0, h);
        }
        int q = base.indexOf('?');
        if (q >= 0) {
            base = base.substring(0, q);
        }
        int hq = hash.indexOf('?');
        if (hq >= 0) {
            hash = hash.substring(0, hq);
        }
        return base + hash;
    }

    /**
     * 页面是不是「被服务端重定向到受限页」，只看站点给的明确特征。
     *
     * <p>11-10 22:56 实证：周三充值日的首屏 {@code html=790}、Vue 还没 mount，
     * 旧实现按「DOM 小于 900 字节」判成受限页，把正常活动整条跳过。空壳判定只在
     * 「地址本身没有渠道参数」时才用 —— 带渠道参数还被重定向成空壳的情况，
     * 站点会把标题写成「访问受限」，走上一段的特征串就能认出来。
     */
    private static boolean shellPage(String url, String text, String len) {
        if (url != null && RealEntry.hasChannel(url)) {
            return false;
        }
        int n = 0;
        try {
            n = Integer.parseInt(len);
        } catch (Throwable ignored) {
        }
        return n > 0 && n < 900 && text.length() < 40;
    }

    /** 已登记地址与目标页是否同一个路径（参数忽略，大小写不敏感）。 */
    private static boolean samePath(String registered, String key) {
        if (registered == null || key == null || key.isEmpty()) {
            return false;
        }
        String bare = pathKey(registered).toLowerCase(java.util.Locale.US);
        String k = key.toLowerCase(java.util.Locale.US);
        return bare.equals(k) || bare.startsWith(k) || k.startsWith(bare);
    }

    /**
     * 找还活着的真页。
     *
     * @param key     路径特征（如 {@code marginQuery}、{@code mycoupons} 或完整无参地址）
     * @param maxAgeMs 最大年龄
     */
    static Object find(String key, long maxAgeMs) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Object best = null;
        long bestAt = 0;
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                Object v = VIEWS[i];
                if (v == null || now - AT[i] > maxAgeMs) {
                    continue;
                }
                if (URLS[i] == null) {
                    continue;
                }
                // 大小写不敏感：调用方传 "signlottery"，站点地址写的是 "signInLottery"。
                if (!URLS[i].toLowerCase(java.util.Locale.US).contains(key.toLowerCase(java.util.Locale.US))
                        && !samePath(URLS[i], key)) {
                    continue;
                }
                if (AT[i] > bestAt) {
                    bestAt = AT[i];
                    best = v;
                }
            }
        }
        return best;
    }

    static String describe() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                if (VIEWS[i] == null || now - AT[i] > TTL_MS) {
                    continue;
                }
                sb.append("  · ").append((now - AT[i]) / 1000).append("s 前 ")
                        .append(shortUrl(URLS[i])).append('\n');
            }
        }
        return sb.length() == 0 ? "  · 无\n" : sb.toString();
    }

    /** 登记表里这个 WebView 最近一次加载的地址。 */
    static String lastUrlOf(Object view) {
        if (view == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        String best = "";
        long bestAt = 0;
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                if (VIEWS[i] != view || now - AT[i] > TTL_MS) {
                    continue;
                }
                if (AT[i] > bestAt) {
                    bestAt = AT[i];
                    best = URLS[i];
                }
            }
        }
        return best;
    }

    /** 登记表里包含该活动号、且带渠道参数的最新地址（用户手动点活动时留下的）。 */
    static String recall(String actId) {
        if (actId == null || actId.isEmpty()) {
            return "";
        }
        long now = System.currentTimeMillis();
        String best = "";
        long bestAt = 0;
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                String u = URLS[i];
                if (u == null || VIEWS[i] == null || now - AT[i] > TTL_MS) {
                    continue;
                }
                if (!u.contains(actId) || !RealEntry.hasChannel(u)) {
                    continue;
                }
                if (AT[i] > bestAt) {
                    bestAt = AT[i];
                    best = u;
                }
            }
        }
        return best;
    }

    /**
     * 页面是不是「被服务端重定向到受限页」。
     *
     * <p>11-10 22:27 实证：砸蛋入口缺 {@code A_C_CODE} 时，wx 把 {@code /qwhdhub/goldeneggs/…}
     * 302 到 {@code /qwhdhub/notice/limited?A_C_CODE=null}，标题「访问受限」、
     * 正文 548 字节、没有 Vue。旧判定只认「渠道未开放」，于是这种页被当成正常页，
     * 脚本在空壳上跑完，任务报「完成」但实际什么都没做。
     */
    static boolean blocked(WebPage page) {
        if (page == null) {
            return true;
        }
        String probe;
        try {
            probe = page.eval("(function(){try{"
                    + "return (document.title||'')+String.fromCharCode(1)"
                    + "+(document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,160):'')"
                    + "+String.fromCharCode(1)"
                    + "+(document.body?document.body.innerHTML.length:0);"
                    + "}catch(e){return ''}})()", 4_000L);
        } catch (Throwable e) {
            return false;
        }
        if (probe == null || probe.isEmpty()) {
            return false;
        }
        String[] parts = probe.split(String.valueOf((char) 1), -1);
        String title = parts.length > 0 ? parts[0] : "";
        String text = parts.length > 1 ? parts[1] : "";
        String len = parts.length > 2 ? parts[2].trim() : "";
        if (title.contains("访问受限") || text.contains("访问受限")
                || title.contains("渠道未开放") || text.contains("渠道未开放")
                || title.contains("无法访问") || text.contains("渠道不支持")) {
            return true;
        }
        String href = "";
        try {
            href = page.href();
        } catch (Throwable ignored) {
        }
        if (href != null && (href.contains("notice/limited") || href.contains("A_C_CODE=null"))) {
            return true;
        }
        return shellPage(href, text, len);
    }

    /**
     * 当前入口被服务端重定向到受限页时，换一个「带渠道参数」的入口重开。
     *
     * <p>候选来源按可信度排：现场学到的（RealEntry 持久化）→ 真页登记表里
     * 用户手动点活动留下的地址 → 内置表带参地址 → 鹅蛋/球赛的缓存入口。
     * 一个都不沾边就返回 null，让上层报明确的失败原因，不再假装完成。
     */
    static WebPage reopenBetter(String badUrl, String key) {
        List<String> cands = betterEntries(badUrl, key);
        if (cands.isEmpty()) {
            return null;
        }
        HookLog.log("TASK", "入口被拦 · 换一个带渠道参数的地址重试",
                "原地址: " + shortUrl(badUrl) + "\n候选: " + cands.size() + " 条\n"
                        + "  · " + shortUrl(cands.get(0)));
        int tried = 0;
        for (String cand : cands) {
            if (tried >= 3) {
                break;
            }
            if (cand == null || cand.equals(badUrl)) {
                continue;
            }
            tried++;
            WebPage p = open(cand, pathKey(cand), 18_000L);
            if (p == null) {
                continue;
            }
            if (!blocked(p)) {
                HookLog.log("TASK", "换入口成功 · " + shortUrl(cand), "页面: " + shortUrl(p.href()));
                return p;
            }
            HookLog.logcat("entry retry still blocked: " + shortUrl(cand));
        }
        return null;
    }

    /**
     * 裸地址换成同类「带渠道参数」的地址（同一个活动、同一个路径才换）。
     *
     * <p>只在原地址确实没有渠道参数时才换，且要求路径一致 —— 换错页比裸地址更糟。
     * 换完仍被拦也没关系，{@link #reopenBetter} 会再兜一层。
     */
    private static String preferBetter(String url) {
        if (url == null || url.isEmpty() || RealEntry.hasChannel(url)) {
            return url;
        }
        String act = RealEntry.actOf(url);
        if (!act.isEmpty()) {
            String best = RealEntry.get(act);
            if (best.isEmpty()) {
                best = recall(act);
            }
            if (best.isEmpty()) {
                best = WelfareCatalog.builtUrl(act);
            }
            if (!best.isEmpty() && RealEntry.hasChannel(best)
                    && sameHost(best, url) && samePath(best, pathKey(url))) {
                HookLog.logcat("entry preferred: " + shortUrl(url) + " -> " + shortUrl(best));
                return best;
            }
        }
        // lyh 的活动入口票据（pc/pk）只存在于「上次 App 现场打开」的那条地址里。
        if (url.contains("lyh.lncmcc.com")) {
            String cached = url.contains("footballGuess")
                    ? SoccerTask.entryUrl() : GooseTask.entryUrl();
            if (RealEntry.hasChannel(cached) && samePath(cached, pathKey(url))) {
                HookLog.logcat("entry preferred(lyh): " + shortUrl(cached));
                return cached;
            }
        }
        return url;
    }

    private static List<String> betterEntries(String badUrl, String key) {        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String act = RealEntry.actOf(badUrl);
        if (!act.isEmpty()) {
            addChannel(set, RealEntry.get(act));
            addChannel(set, recall(act));
            addChannel(set, WelfareCatalog.builtUrl(act));
        }
        // lyh 的鹅蛋 / 球赛入口靠 URL 上的 pc/pk（一次性票据），只能靠现场学到。
        if (badUrl != null && badUrl.contains("lyh.lncmcc.com")) {
            if ("goose".equals(key)) {
                addChannel(set, GooseTask.entryUrl());
            } else if ("soccer".equals(key)) {
                addChannel(set, SoccerTask.entryUrl());
            }
        }
        set.remove(badUrl);
        return new java.util.ArrayList<>(set);
    }

    private static void addChannel(java.util.Set<String> set, String url) {
        if (url != null && !url.isEmpty() && RealEntry.hasChannel(url)) {
            set.add(url);
        }
    }

    // ------------------------------------------------------------------ 会话

    /** 一次任务开始：串行任务共用第一个真页，避免叠 Activity。 */
    static void beginSession() {
        sessionDepth++;
    }

    static void endSession() {
        int left = sessionDepth - 1;
        sessionDepth = left < 0 ? 0 : left;
        if (sessionDepth == 0) {
            sessionView = null;
            sessionUrl = "";
        }
    }

    // ------------------------------------------------------------------ 收页
    //
    // 11-10 23:19 用户要求：每个活动跑完就把页面关掉，否则一键过 15 个活动会在
    // App 里攒一摞 CommonHtml5Activity。只关「我们打开的」页面，用户自己点开的
    // 页面一律不动。

    private static final java.util.List<Object> OWNED = new java.util.ArrayList<>();

    static void markOwned(Object view) {
        if (view == null) {
            return;
        }
        synchronized (OWNED) {
            for (int i = 0; i < OWNED.size(); i++) {
                if (OWNED.get(i) == view) {
                    return;
                }
            }
            OWNED.add(view);
        }
    }

    /** 这一页是不是模块自己打开的（只有这种才能关）。 */
    static boolean isOwned(Object view) {
        if (view == null) {
            return false;
        }
        synchronized (OWNED) {
            for (int i = 0; i < OWNED.size(); i++) {
                if (OWNED.get(i) == view) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 关掉本次任务里由模块打开的真页，并清空会话。 */
    static void closeOwned() {
        java.util.List<Object> copy;
        synchronized (OWNED) {
            copy = new java.util.ArrayList<>(OWNED);
            OWNED.clear();
        }
        int closed = 0;
        for (int i = 0; i < copy.size(); i++) {
            if (closePage(copy.get(i))) {
                closed++;
            }
        }
        sessionView = null;
        sessionUrl = "";
        if (closed > 0) {
            HookLog.logcat("closed " + closed + " task page(s)");
        }
    }

    /**
     * 关掉承载这个 WebView 的 Activity。
     *
     * <p>真页是 App 自己的容器（{@code CommonHtml5Activity}），销毁 WebView 会和
     * App 的 SDK 回调打架；关 Activity 才等同于用户按返回键。
     */
    static boolean closePage(Object view) {
        if (view == null) {
            return false;
        }
        // 关掉的页面不能继续当会话现场，否则下一项活动会 attach 到一个死 WebView。
        if (sessionView == view) {
            sessionView = null;
            sessionUrl = "";
        }
        synchronized (OWNED) {
            for (int i = 0; i < OWNED.size(); i++) {
                if (OWNED.get(i) == view) {
                    OWNED.remove(i);
                    break;
                }
            }
        }
        try {
            Activity act = activityOf(view);
            if (act != null) {
                if (!act.isFinishing()) {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                act.finish();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                return true;
            }
            // 拿不到 Activity 时至少把页面清空，别把旧 DOM 留在后台。
            java.lang.reflect.Method m = view.getClass()
                    .getMethod("loadUrl", String.class);
            m.invoke(view, "about:blank");
            return true;
        } catch (Throwable e) {
            HookLog.logcat("close page failed: " + e);
            return false;
        }
    }

    /** 从 WebView 的 Context 往上找 Activity。 */
    private static Activity activityOf(Object view) {
        try {
            Object ctx = view.getClass().getMethod("getContext").invoke(view);
            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof Activity) {
                    return (Activity) ctx;
                }
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
            return ctx instanceof Activity ? (Activity) ctx : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 两个地址是不是同一个域（含子域）。 */
    private static boolean sameHost(String a, String b) {
        String ha = hostOf(a);
        String hb = hostOf(b);
        if (ha.isEmpty() || hb.isEmpty()) {
            return false;
        }
        return ha.equals(hb) || ha.endsWith("." + hb) || hb.endsWith("." + ha);
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String u = url;
            int s = u.indexOf("://");
            if (s >= 0) {
                u = u.substring(s + 3);
            }
            int e = u.length();
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    e = i;
                    break;
                }
            }
            String host = u.substring(0, e);
            int colon = host.indexOf(':');
            return colon >= 0 ? host.substring(0, colon) : host;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 这次跳转是不是发生在「任务用真页」上。
     *
     * <p>真页是 App 自己的容器，模块不能换它的 WebViewClient（换了 App 的 SDK 回调就断了），
     * 但可以在 {@code shouldOverrideUrlLoading} 这一层把「下载 App / 去 APP 打开」的中转页拦掉。
     * 11-10 22:07 日志实证：鹅蛋真页被 {@code dx.10086.cn/A/…} 带去 transferDownload，
     * 原页面 JS 上下文当场销毁，脚本再也读不到数据，任务就卡在那儿。
     */
    static boolean isTaskPage(Object view) {
        if (sessionDepth <= 0 || sessionView == null) {
            return false;
        }
        if (view == null || sessionView == view) {
            return true;
        }
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                if (VIEWS[i] == view) {
                    return true;
                }
            }
        }
        // X5 回调传进来的可能是 WebView 的内部类实例，和登记的实例不是同一个；
        // 任务期间只有一个真页在服役，按会话状态放行即可（只对下载类中转页生效）。
        return true;
    }

    // ------------------------------------------------------------------ 前台

    /** App 自己的进程重要性；<=100 表示前台。 */
    private static int importance() {
        try {
            Context ctx = HookLog.context();
            if (ctx == null) {
                return -1;
            }
            ActivityManager am = (ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return -1;
            }
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) {
                return -1;
            }
            int pid = Process.myPid();
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info.pid == pid) {
                    return info.importance;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static boolean foreground() {
        int imp = importance();
        if (imp >= 0) {
            return imp <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
        Activity a = ActivityHooks.current();
        return a != null && !a.isFinishing();
    }

    /**
     * 不在前台就把 App 拉起来。
     *
     * @return 最终判定为前台返回 true
     */
    static boolean ensureForeground() {
        if (foreground()) {
            return true;
        }
        HookLog.log("PAGE", "App 不在前台 · 自动拉起", "");
        boolean launched = false;
        try {
            Context ctx = HookLog.context();
            if (ctx != null) {
                Intent i = ctx.getPackageManager()
                        .getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                    ctx.startActivity(i);
                    launched = true;
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("bring app to front failed: " + e);
        }
        if (!launched) {
            try {
                Activity a = ActivityHooks.current();
                if (a != null) {
                    Context ctx = HookLog.context();
                    Intent i = ctx == null ? null : ctx.getPackageManager()
                            .getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(i);
                        launched = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < 16; i++) {
            if (foreground()) {
                return true;
            }
            sleep(500L);
        }
        return foreground();
    }

    // ------------------------------------------------------------------ 打开

    /**
     * 拿一个可用的真页。
     *
     * @param url       目标地址
     * @param key       路径特征，用来判断「是不是同一个页面」
     * @param timeoutMs 打开并等待可用状态的上限
     * @return null 表示真页拿不到（调用方退回离屏兜底）
     */
    static WebPage open(String url, String key, long timeoutMs) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // 先用「带渠道参数」的同类地址替换裸地址：11-10 22:27 的砸蛋就是裸地址
        // 被 wx 302 到 notice/limited?A_C_CODE=null，白跑一轮还报「完成」。
        url = preferBetter(url);
        // v4.2.0：不管复用还是新开，先确认 App 在前台 —— 用户原话「刷新你要看移动还在不在
        // 前台 没在就拉前台」。以前只在「需要新开页」时才拉，命中已开页但 App 在后台时
        // 页面是冻结的，eval 一直拿不到数据，最后被判成「真页读不到」。
        if (!foreground()) {
            HookLog.log("PAGE", "App 不在前台 · 打开前先拉起", "目标: " + shortUrl(url));
            ensureForeground();
        }
        long deadline = System.currentTimeMillis() + Math.max(8_000L, timeoutMs);
        String match = key == null || key.isEmpty() ? pathKey(url) : key;

        Object view = find(match, TTL_MS);
        boolean reused = view != null;

        if (view == null) {
            Object sv = sessionView;
            // 只在同一个域里复用。跨域（wx.10086.cn → lyh.lncmcc.com）必须新开一个真页：
            // 11-10 22:07 日志实证，同一个 WebView 跨域导航后，页面里的广告位把整页
            // 顶到了 transferDownload 下载引导页（标题「中国移动客户端」），鹅蛋脚本
            // 当场失去 JS 上下文，任务卡死。用户手动点开活动是「新开一个页面」，这里对齐。
            if (sv != null && sameHost(sessionUrl, url)) {
                // v4.2.0：先看这一页现在到底停在哪。用户「看完流量再去看券」时，前台那个
                // 真页已经被导航成卡券页了；此时若直接 navigateFast 回余量页，等于把用户
                // 正在看的券页抢走，下次刷新券又要重开。只有「当前确实还停在目标页」才复用，
                // 否则另开一页，谁都不打扰。
                String cur = lastUrlOf(sv);
                boolean samePage = cur == null || cur.isEmpty()
                        || samePath(cur, pathKey(url));
                if (samePage) {
                    view = sv;
                    reused = true;
                } else {
                    HookLog.log("PAGE", "前台真页已变成别的页面 · 另开一页",
                            "当前: " + shortUrl(cur) + "\n目标: " + shortUrl(url));
                }
            }
        }

        if (view == null) {
            if (!ensureForeground()) {
                HookLog.log("PAGE", "真页打开失败 · App 拉不起来",
                        "目标: " + shortUrl(url) + "\n已登记的真页:\n" + describe());
                return null;
            }
            if (!openH5(url)) {
                HookLog.log("PAGE", "真页打开失败 · 容器没起来",
                        "目标: " + shortUrl(url));
                return null;
            }
            view = await(match, deadline);
            if (view == null) {
                HookLog.log("PAGE", "真页打开超时 · 容器里没等到目标页面",
                        "目标: " + shortUrl(url) + "\n已登记的真页:\n" + describe());
                return null;
            }
            reused = false;
            // 这一页是我们拉起来的，任务结束要回收。
            markOwned(view);
        }

        WebPage page = WebPage.attachReal(view, url);
        if (page == null) {
            return null;
        }
        if (!reused) {
            page.awaitDoc(Math.min(12_000L, Math.max(4_000L, deadline - System.currentTimeMillis())));
        } else {
            String href = page.href();
            if (href == null || !samePath(href, pathKey(url))) {
                // 同一个真页换地址：这就是「执行下一个活动」，不新建 Activity。
                page.navigateFast(url, Math.max(6_000L, deadline - System.currentTimeMillis()));
            }
        }
        // v4.2.0：页面里的「下载 App」广告位会把整页顶到 transferDownload 引导页，
        // 看起来像打开了，其实活动页的 JS 上下文已经销毁 —— 11-11 08:22 日志里
        // 鹅蛋和球赛就是这样「现场: null」直接跳过的。这里复查最终地址，
        // 被顶走就丢掉这一页重开一次；再被顶走才认输并写清原因。
        String landed = page.href();
        if (WebViewHooks.isDetourUrl(landed) && !WebViewHooks.isDetourUrl(url)) {
            HookLog.log("PAGE", "真页被下载引导页顶走 · 重开一次",
                    "打开: " + shortUrl(url) + "\n实际: " + shortUrl(landed)
                            + "\n做法: 关掉这一页，重新用 App 容器打开目标地址。");
            try {
                if (isOwned(view)) {
                    closePage(view);
                }
            } catch (Throwable ignored) {
            }
            view = null;
            page = null;
            sessionView = null;
            sessionUrl = null;
            if (ensureForeground() && openH5(url)) {
                view = await(match, deadline);
                if (view != null) {
                    markOwned(view);
                    page = WebPage.attachReal(view, url);
                    if (page != null) {
                        page.awaitDoc(Math.min(12_000L,
                                Math.max(4_000L, deadline - System.currentTimeMillis())));
                    }
                }
            }
            if (page == null) {
                HookLog.log("PAGE", "重开失败 · 容器没起来", "目标: " + shortUrl(url));
                return null;
            }
            String again = page.href();
            if (WebViewHooks.isDetourUrl(again) && !WebViewHooks.isDetourUrl(url)) {
                HookLog.log("PAGE", "重开后仍是下载引导页 · 放弃这一页",
                        "目标: " + shortUrl(url) + "\n实际: " + shortUrl(again));
                try {
                    closePage(view);
                } catch (Throwable ignored) {
                }
                return null;
            }
            reused = false;
        }

        sessionView = view;
        sessionUrl = url;
        HookLog.log("PAGE", (reused ? "复用真页" : "打开真页") + " · " + shortUrl(url),
                "页面: " + shortUrl(page.href()) + "\n内核: " + page.kernel());
        return page;
    }

    /** 用 App 自己的 H5 容器打开地址。 */
    private static boolean openH5(String url) {
        try {
            Activity a = ActivityHooks.current();
            if (a != null && !a.isFinishing() && Html5.openFrom(a, url)) {
                return true;
            }
        } catch (Throwable e) {
            HookLog.logcat("html5 openFrom failed: " + e);
        }
        try {
            Context ctx = HookLog.context();
            if (ctx != null && Html5.open(ctx, url)) {
                return true;
            }
        } catch (Throwable e) {
            HookLog.logcat("html5 open failed: " + e);
        }
        return false;
    }

    private static Object await(String key, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            Object v = find(key, TTL_MS);
            if (v != null) {
                return v;
            }
            sleep(300L);
        }
        return find(key, TTL_MS);
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "无";
        }
        return url.length() > 140 ? url.substring(0, 140) + "…" : url;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
