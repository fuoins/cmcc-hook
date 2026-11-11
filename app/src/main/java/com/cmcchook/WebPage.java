package com.cmcchook;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedHelpers;

/**
 * 离屏活动页。
 *
 * <p>存在的理由：签到、砸金蛋、喂鹅这三套活动全是 H5，
 * 服务端认的是「页面自己发的请求」——同源的 Referer、App 内核的 Cookie、
 * 瑞数签名、国密参数，缺一样就回 GOTO_LOGIN 或者 412。
 * 之前用 HttpURLConnection 照着抓包拼，永远是半成品；
 * 让用户手动点进页面，又变成半自动。
 *
 * <p>这里在目标 App 进程里建一个不挂到窗口的 WebView，用 App 自己的内核加载活动页。
 * 页面照常跑它的 JS、种它的 Cookie、算它的签名，用户界面一点不动。
 * 我们只在页面上下文里取数据、点按钮。
 *
 * <p>生命周期：一次任务建一个，任务结束 destroy。最多同时存在一个。
 */
final class WebPage {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long LOAD_TIMEOUT = 25_000L;
    private static final long EVAL_TIMEOUT = 12_000L;

    /** 允许导航的域名后缀；null 表示不限制。 */
    private final NavState nav;

    private final Object webView;
    /** true 表示这是 App 自己的真页 WebView：不建新页、不销毁、不做导航拦截。 */
    private final boolean real;
    private volatile String title = "";
    private volatile boolean finished;
    private static volatile long lastBlockLog;

    /** 导航状态：入口地址、当前真实地址、允许的域。WebViewClient 和任务侧共用一份。 */
    private static final class NavState {
        final String entry;
        final String[] hosts;
        volatile String current;

        /** 页面自己去请求了哪些地址。图片楼层页的入口只藏在这里。 */
        private final java.util.LinkedHashSet<String> reqs = new java.util.LinkedHashSet<>();

        /** 文档一开始加载就注入的脚本。晚一步页面自己的 JS 就跑完了。 */
        final String earlyScript;
        final boolean withImages;
        /** v4.0.7+probe：主文档 loadUrl 附加的额外请求头；null 表示不带。 */
        final java.util.Map<String, String> extraHeaders;

        NavState(String url, String[] hosts) {
            this(url, hosts, null, false, null);
        }

        NavState(String url, String[] hosts, String earlyScript, boolean withImages) {
            this(url, hosts, earlyScript, withImages, null);
        }

        NavState(String url, String[] hosts, String earlyScript, boolean withImages,
                 java.util.Map<String, String> extraHeaders) {
            this.entry = url;
            this.current = url;
            this.hosts = hosts;
            this.earlyScript = earlyScript;
            this.withImages = withImages;
            this.extraHeaders = extraHeaders;
        }

        void remember(String url) {
            synchronized (reqs) {
                if (reqs.size() < 400) {
                    reqs.add(url);
                }
            }
        }

        java.util.List<String> dumped() {
            synchronized (reqs) {
                return new java.util.ArrayList<>(reqs);
            }
        }
    }

    private WebPage(Object webView, NavState nav) {
        this(webView, nav, false);
    }

    private WebPage(Object webView, NavState nav, boolean real) {
        this.webView = webView;
        this.nav = nav;
        this.real = real;
    }

    /**
     * 包住 App 自己的一个真页 WebView。
     *
     * <p>与 {@link #attach(Object)} 的区别：真页不做拦截、不注入任何东西、不销毁。
     * 页面已经在跑，模块只是在它上面执行同一套脚本。
     */
    static WebPage attachReal(Object webView, String url) {
        if (webView == null) {
            return null;
        }
        NavState nav = new NavState(url == null ? "" : url, null);
        WebPage page = new WebPage(webView, nav, true);
        page.finished = true;
        return page;
    }

    boolean isReal() {
        return real;
    }

    /** 页面当前真实地址（真页实时读；离屏用登记的当前地址）。 */
    String href() {
        if (!real) {
            return nav.current;
        }
        try {
            String h = evalRaw("(function(){try{return location.href}catch(e){return ''}})()",
                    1_800L);
            if (h != null && !h.isEmpty() && !"null".equals(h)) {
                nav.current = h;
                return h;
            }
        } catch (Throwable ignored) {
        }
        return nav.current;
    }

    /**
     * 打开一个页面并等它加载完。
     *
     * @return null 表示 WebView 建不起来（内核不可用）
     */
    static WebPage open(String url, long timeoutMs) {
        return open(url, timeoutMs, null);
    }

    /**
     * @param allowedHosts 只允许这些域名（及其子域）做主文档导航，null 不限制。
     *                     活动页里常挂着「下载客户端」之类的广告位，una 点击或
     *                     定时器都会把整页顶掉，顶掉之后页面里既没有组件也没有接口上下文，
     *                     表现出来就是「页面未登录」。
     */
    static WebPage open(String url, long timeoutMs, String[] allowedHosts) {
        // 任务页也要开图。这些活动页的楼层、按钮、皮肤几乎全是图片，
        // 禁图之后页面自己的懒加载和 onload 回调不触发，DOM 就停在骨架那一步，
        // innerHTML 有几百字符但 title 和正文都空——拿这种页面发请求，服务端只会回 HTML。
        return open(url, timeoutMs, allowedHosts, null, true);
    }

    /**
     * @param earlyScript 文档开始加载时就注入的脚本，null 表示不注入。
     *                    页面自己发的 XHR 全在首屏，等 onPageFinished 再注入就晚了。
     * @param withImages  允许加载图片。楼层页整屏都是图，禁图会让懒加载逻辑不触发，
     *                    采集到的是空 DOM。
     */
    static WebPage open(String url, long timeoutMs, String[] allowedHosts,
                        String earlyScript, boolean withImages) {
        return openInternal(url, timeoutMs, allowedHosts, earlyScript, withImages, false);
    }

    /**
     * v4.0.7+probe：探针专用开页 —— 可附加主文档请求头、可预注入脚本。
     *
     * <p>与 {@link #open(String, long, String[], String, boolean)} 走同一套 build，
     * 只是把 extraHeaders 透传到 NavState，让 loadUrl 带上真页的请求头做对照。
     * 刻意用 awaitDoc（不等 Vue 渲染）—— 探针自己会按 SITE_JS 轮询若干秒看券列表
     * 到底渲不渲染，等渲染反而会错过「被踢到登录页」这个关键中间态。
     */
    static WebPage openProbe(String url, long timeoutMs, String[] allowedHosts,
                             String earlyScript, java.util.Map<String, String> extraHeaders) {
        // v4.3.0：探针也走真页。离屏页带不进 App 的来源/UA/Cookie，拿它去对照
        // 「真页为什么能成」只会得到一份假结论；真页本身就是对照的那一边。
        return realOpen(url, timeoutMs, earlyScript);
    }

    /**
     * 快速开页：只等文档建立，不等渲染。
     *
     * <p>任务请求是页面上下文里 fetch 直发，不依赖 DOM 渲染结果；
     * 等渲染（awaitSettled）白白花掉 1~2 秒。这里只确认文档建立、
     * JS 环境可用，再留 800ms 给页面初始化（种 Cookie、注册桥），
     * 之后就能开始发请求。
     */
    static WebPage openFast(String url, long timeoutMs, String[] allowedHosts) {
        return openInternal(url, timeoutMs, allowedHosts, null, true, true);
    }

    /** 快速开页 + 文档一开始加载就注入脚本（灌 localStorage.QWHDSSOJWT 用）。 */
    static WebPage openFast(String url, long timeoutMs, String[] allowedHosts,
                            String earlyScript) {
        return openInternal(url, timeoutMs, allowedHosts, earlyScript, true, true);
    }

    private static WebPage openInternal(String url, long timeoutMs, String[] allowedHosts,
                                        String earlyScript, boolean withImages, boolean fast) {
        final Context ctx = HookLog.context();
        if (ctx == null || url == null || url.isEmpty()) {
            return null;
        }
        // v4.3.0：不再建离屏 WebView。离屏缺 App 容器的初始化（UA/来源/Cookie/私有 JS 桥）、
        // 缺 localStorage 的 SSO 会话、页面不布局就不发请求 —— 实测成功率远低于真页，
        // 采集、探测、券兜底、流量兜底全都一样。统一走 App 自己的 H5 容器。
        WebPage real = realOpen(url, timeoutMs, earlyScript);
        if (real != null) {
            return real;
        }
        HookLog.logcat("webpage open: real page unavailable, skip offscreen: " + url);
        return null;
    }

    /** 真页打开 + 按需注入早脚本（真页没有「文档开始前」的注入点，改成加载后补一次）。 */
    private static WebPage realOpen(String url, long timeoutMs, String earlyScript) {
        try {
            WebPage page = RealPage.open(url, RealPage.pathKey(url),
                    Math.min(Math.max(12_000L, timeoutMs), 25_000L));
            if (page == null) {
                return null;
            }
            if (earlyScript != null && !earlyScript.isEmpty()) {
                try {
                    page.eval(earlyScript, 2_000L);
                } catch (Throwable ignored) {
                }
            }
            return page;
        } catch (Throwable e) {
            HookLog.logcat("webpage real open failed: " + e);
            return null;
        }
    }

    /** 必须在主线程调用。 */
    private static Object build(Context ctx, final String url, final NavState nav) {
        // 必须优先 X5。App 自己就是 X5 内核，页面按 X5 的特性渲染；
        // 换成系统 WebView 会命中另一套 HTTP 缓存，拿到的是一份没有 JS 外壳的
        // 旧 HTML，0.2 秒就「加载完成」，DOM 里连一个控件都没有。
        Object webView = OffscreenWeb.createWebView(ctx);
        if (webView == null) {
            try {
                webView = new WebView(ctx);
            } catch (Throwable e) {
                HookLog.logcat("system webview unavailable: " + e);
            }
        }
        if (webView == null) {
            return null;
        }
        // 任务页走的是 createWebView，不经过 OffscreenWeb.open。不登记的话
        // OffscreenWeb.current() 永远是 null，登录桥垫片和回调转发全部失效。
        OffscreenWeb.track(webView);
        String ua = appUserAgent();
        try {
            Object settings = XposedHelpers.callMethod(webView, "getSettings");
            if (settings instanceof WebSettings) {
                WebSettings s = (WebSettings) settings;
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setJavaScriptCanOpenWindowsAutomatically(true);
                s.setSupportMultipleWindows(false);
                s.setLoadsImagesAutomatically(nav.withImages);
                s.setBlockNetworkImage(!nav.withImages);
                s.setCacheMode(WebSettings.LOAD_DEFAULT);
                if (!ua.isEmpty()) {
                    s.setUserAgentString(ua);
                }
                if (Build.VERSION.SDK_INT >= 17) {
                    s.setMediaPlaybackRequiresUserGesture(false);
                }
            } else if (settings != null) {
                // X5 的 WebSettings 不是系统类，只能按名字调。
                XposedHelpers.callMethod(settings, "setJavaScriptEnabled", true);
                XposedHelpers.callMethod(settings, "setDomStorageEnabled", true);
                XposedHelpers.callMethod(settings, "setDatabaseEnabled", true);
                XposedHelpers.callMethod(settings, "setLoadsImagesAutomatically",
                        nav.withImages);
                XposedHelpers.callMethod(settings, "setBlockNetworkImage", !nav.withImages);
                if (!ua.isEmpty()) {
                    XposedHelpers.callMethod(settings, "setUserAgentString", ua);
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("webpage settings failed: " + e);
        }
        try {
            // 系统内核才设得了系统 WebViewClient；X5 设了会 ClassCastException，
            // 它默认的 client 也能正常加载，就不碰了。
            if (webView instanceof WebView) {
                ((WebView) webView).setWebViewClient(new WebViewClient() {
                    @Override
                    public android.webkit.WebResourceResponse shouldInterceptRequest(
                            WebView view, android.webkit.WebResourceRequest request) {
                        // 不拦不替，只抄一份地址。楼层配置、活动入口、商品 id
                        // 全在这些请求的 URL 和参数里，比 DOM 靠谱得多。
                        try {
                            if (request != null && request.getUrl() != null) {
                                nav.remember(request.getUrl().toString());
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String u) {
                        return blockIfForeign(u, nav.hosts);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view,
                                                            android.webkit.WebResourceRequest request) {
                        if (request == null || request.getUrl() == null) {
                            return false;
                        }
                        return blockIfForeign(request.getUrl().toString(), nav.hosts);
                    }

                    @Override
                    public void onPageStarted(WebView view, String u, android.graphics.Bitmap icon) {
                        super.onPageStarted(view, u, icon);
                        // 记真实地址：任务侧靠 page.url() 判断「还停在活动页吗」，
                        // 如果一直返回我们自己 loadUrl 的那个串，跳走了也看不出来。
                        if (u != null && !u.isEmpty() && !u.startsWith("about:")) {
                            nav.current = u;
                        }
                        // 页面自己的 JS 一解析就会开始发 XHR，等 onPageFinished 再注入
                        // 抓到的永远是空。这里在文档刚建立、业务脚本还没跑的时候挂上钩子。
                        if (nav.earlyScript != null) {
                            try {
                                view.evaluateJavascript(nav.earlyScript, null);
                            } catch (Throwable e) {
                                HookLog.logcat("early script failed: " + e);
                            }
                        }
                        // 有些跳转不走 shouldOverrideUrlLoading（比如表单 POST、
                        // 或者内核自己做的重定向），这里再兜一层：一发现换了域就掐掉，
                        // 别让广告页把活动页顶掉之后还在那儿跑 JS。
                        if (isForeign(u, nav.hosts)) {
                            HookLog.log("TASK", "页面被外部跳转顶掉 · 已拦截",
                                    "目标: " + brief(u) + "\n活动页: " + brief(nav.entry));
                            try {
                                view.stopLoading();
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    @Override
                    public void onPageFinished(WebView view, String u) {
                        super.onPageFinished(view, u);
                        // 页面自己的 cmcc.js 已经执行完，把 getUserInfo 换回垫片。
                        // 画布页在 Vue 挂载时才调它，这里赶得上。
                        SdkBridge.patchUserInfo(view);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        if (!(webView instanceof WebView)) {
            // X5 内核：setWebViewClient 只认 com.tencent.smtt.sdk.WebViewClient，
            // 上面那个系统 client 根本设不进去，页面连 onPageStarted 都没有。
            // 这里反射建一个空 client，只为把 onPageStarted 这条链打通 ——
            // 垫片注入和 earlyScript 都挂在它上面。
            try {
                Class<?> clientCls = XposedHelpers.findClassIfExists(
                        "com.tencent.smtt.sdk.WebViewClient",
                        webView.getClass().getClassLoader());
                if (clientCls != null) {
                    Object client = XposedHelpers.newInstance(clientCls);
                    XposedHelpers.callMethod(webView, "setWebViewClient", client);
                }
            } catch (Throwable e) {
                HookLog.logcat("x5 client failed: " + e);
            }
        }
        // 活动页换票要靠 App 注入的 JSBridge（leadeon / newSdkInterface）。
        // 那个对象只存在于 App 自己创建的 WebView 上，新建的 WebView 必须重新注入，
        // 否则页面里的登录流程会直接卡住 —— 用户手动打开能成、我们离屏打开不能成，
        // 区别就在这里。
        Object bridge = SsoLogin.bridgeObject();
        String bridgeName = SsoLogin.bridgeNameOf();
        if (bridge != null && !bridgeName.isEmpty()) {
            // 只注入对象不够：JSSDKImpl 的 success/error 回调是发回它自己那个
            // WebView 的，不改绑的话离屏页永远等不到 checkUID 的回调，
            // 页面就卡在「登录中 92%」。改绑在 close() 里还原。
            boolean rebound = SsoLogin.bindBridgeTo(webView);
            try {
                XposedHelpers.callMethod(webView, "addJavascriptInterface", bridge, bridgeName);
                HookLog.log("TASK-DIAG", "登录桥注入 · " + bridgeName + " 已挂到离屏页",
                        "改绑: " + (rebound ? "成功" : "未改绑，靠回调转发"));
            } catch (Throwable e) {
                HookLog.log("TASK-DIAG", "登录桥注入失败", String.valueOf(e));
            }
        }
        // v4.0.8：把 App 真页注册过的全部私有 JS 桥装回离屏页。
        // 卡券页初始化依赖 cardInterface / androidJsInterface 等，只注入
        // newSdkInterface 时页面脚本会在 Vue mount 前抛异常，body 停在
        // {{item.title}} 模板原文、券列表接口一次都不发（22:05 真机日志实证）。
        // 必须在 loadUrl 之前 addJavascriptInterface，页面脚本才看得到这些桥。
        AppBridges.injectInto(webView, bridgeName);
        final Object target = webView;
        try {
            // v4.0.7+probe：探针方案要重放真页 loadUrl 的额外请求头。
            // 只有探针开的页带 extraHeaders；生产任务的 open() 一律 null，
            // 走的还是原来那句单参 loadUrl，行为完全不变。
            boolean loaded = false;
            if (nav.extraHeaders != null && !nav.extraHeaders.isEmpty()) {
                try {
                    XposedHelpers.callMethod(webView, "loadUrl", url, nav.extraHeaders);
                    loaded = true;
                } catch (Throwable he) {
                    HookLog.logcat("loadUrl(headers) unsupported, fallback: " + he);
                }
            }
            if (!loaded) {
                XposedHelpers.callMethod(webView, "loadUrl", url);
            }
            // v4.0.7+flowprobe：earlyScript 注入改为「不依赖内核类型」。
            // 原来只有系统内核分支的匿名 WebViewClient.onPageStarted 会注入；X5 分支
            // 只 newInstance 了一个空 WebViewClient，onPageStarted 链根本不通，导致
            // 4.0.7+probe-v2 全轮 DIAG:无(earlyScript 未生效) —— JS 报错是假阴性、
            // 缺桥垫片 / JWT / 唤醒脚本全部空转。这里在 loadUrl 之后用 postDelayed
            // 多点重复注入，覆盖「文档刚建立→脚本陆续加载」各阶段，对系统内核与 X5
            // 一视同仁；earlyScript 自带幂等守卫，重复执行无害。
            // 用 JsInjector.postRaw（反射 evaluateJavascript(js,null)，X5 兼容），
            // 与 SdkBridge 垫片同一条已验证生效的通道。
            if (nav.earlyScript != null && !nav.earlyScript.isEmpty()) {
                final String early = nav.earlyScript;
                for (long delay : new long[]{60L, 350L, 1_000L, 2_400L}) {
                    try {
                        XposedHelpers.callMethod(webView, "postDelayed", new Runnable() {
                            @Override
                            public void run() {
                                JsInjector.postRaw(target, early);
                            }
                        }, delay);
                    } catch (Throwable e) {
                        HookLog.logcat("early script post failed: " + e);
                    }
                }
            }
            // onPageStarted 万一没回调（不同 X5 版本行为不一样），文档建立后
            // 补一次垫片；幂等，页面已经加载了真 SDK 也不会被顶掉。
            XposedHelpers.callMethod(webView, "postDelayed", new Runnable() {
                @Override
                public void run() {
                    SdkBridge.injectShim(target);
                }
            }, 150L);
            // X5 分支没有自己的 onPageFinished，补两次：600ms 赶在 Vue 挂载前，
            // 2s 兜住脚本加载慢的情况。重复调用在 patchUserInfo 里会去重。
            for (long delay : new long[]{600L, 2_000L}) {
                XposedHelpers.callMethod(webView, "postDelayed", new Runnable() {
                    @Override
                    public void run() {
                        SdkBridge.patchUserInfo(target);
                    }
                }, delay);
            }
        } catch (Throwable e) {
            HookLog.logcat("webpage load failed: " + e);
        }
        return webView;
    }

    /**
     * 拦不拦这次导航。
     *
     * <p>活动页里塞了「下载中国移动客户端」的广告位，定时器一响就 location 跳到
     * dx.10086.cn 的短链，再落到 transferDownload.html —— 整页被换成下载引导页，
     * 之后不管怎么调组件都是空数据，日志里只留下「页面未登录」。
     * 这类跳转对任务没有任何用处，一律掐掉，让页面停在活动页上。
     */
    private static boolean blockIfForeign(String u, String[] allowedHosts) {
        if (!isForeign(u, allowedHosts)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastBlockLog > 3_000L) {
            lastBlockLog = now;
            HookLog.log("TASK", "拦下页面外跳", "目标: " + brief(u));
        }
        return true;
    }

    private static boolean isForeign(String u, String[] allowedHosts) {
        if (u == null || u.isEmpty()) {
            return false;
        }
        String lower = u.toLowerCase();
        if (lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("javascript:") || lower.startsWith("blob:")) {
            return false;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            // intent://、weixin://、alipays://、market://、tel: 这些一律不跳
            return true;
        }
        if (lower.contains("transferdownload") || lower.contains("/download")) {
            return true;
        }
        if (allowedHosts == null || allowedHosts.length == 0) {
            return false;
        }
        String host = hostOf(lower);
        if (host.isEmpty()) {
            return true;
        }
        if ("dx.10086.cn".equals(host)) {
            // 营销短链，落地基本都是下载页
            return true;
        }
        for (String allow : allowedHosts) {
            if (host.equals(allow) || host.endsWith("." + allow)) {
                return false;
            }
        }
        return true;
    }

    private static String hostOf(String url) {
        try {
            int start = url.indexOf("://");
            if (start < 0) {
                return "";
            }
            int from = start + 3;
            int end = url.length();
            for (int i = from; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String host = url.substring(from, end);
            int at = host.indexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
            return host;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String brief(String u) {
        if (u == null) {
            return "";
        }
        return u.length() <= 110 ? u : u.substring(0, 110) + "…";
    }

    /**
     * 用 App 自己 WebView 的 UA。
     *
     * <p>这不是讲究：`wx.10086.cn` 会看 UA 决定给活动页还是给「请下载客户端」的
     * 引导页。系统 WebView 的默认 UA 里没有 leadeon 标记，直接加载会被 302 走。
     */
    static String appUserAgent() {
        try {
            Object wv = PageAuto.webView();
            if (wv != null) {
                Object settings = XposedHelpers.callMethod(wv, "getSettings");
                Object value = XposedHelpers.callMethod(settings, "getUserAgentString");
                if (value instanceof String && ((String) value).length() > 20) {
                    return (String) value;
                }
            }
        } catch (Throwable ignored) {
        }
        return TaskNet.UA;
    }

    Object raw() {
        return webView;
    }

    /** v3.58：让这个页面重新打开指定地址（券批量「一张一重进」用）。 */
    void load(String url) {
        if (webView == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            XposedHelpers.callMethod(webView, "loadUrl", url);
        } catch (Throwable e) {
            HookLog.logcat("page load failed: " + e);
        }
    }

    /** 离屏页用的哪套内核：X5 和系统 WebView 的 Cookie/桥行为不一样，日志要能看出来。 */
    String kernel() {
        if (webView == null) {
            return "无";
        }
        return (real ? "真页 · " : "") + webView.getClass().getName();
    }

    String url() {
        return real ? href() : nav.current;
    }

    /** 页面这一轮自己去请求过的地址（主文档 + 子资源 + XHR）。 */
    java.util.List<String> requests() {
        return nav.dumped();
    }

    /**
     * 包住一个已经在跑的 WebView（App 自己那个），不要新建。
     *
     * <p>福利专区的楼层数据、登录态、Vue 实例全在 App 那个 WebView 里，
     * 换一个新页面去读，读到的只有缓存外壳。这里只借用它的 evaluateJavascript。
     */
    static WebPage attach(Object webView) {
        if (webView == null) {
            return null;
        }
        return new WebPage(webView, new NavState("", null));
    }

    /**
     * 换个地址重新加载。
     *
     * <p>鹅蛋页的登录态不在 Cookie 里，而是 URL 上的 {@code pc/pk} 密文参数。
     * 裸地址进去页面会显示「用户未登录」，怎么调组件都是空数据。
     * 所以发现未登录时得能在同一个 WebView 里换成带参数的地址重来 ——
     * 重建一个 WebView 反而会丢掉刚种下的同源 Cookie。
     */
    boolean navigate(String next, long timeoutMs) {
        if (webView == null || next == null || next.isEmpty()) {
            return false;
        }
        nav.current = next;
        finished = false;
        final CountDownLatch called = new CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(webView, "loadUrl", next);
                } catch (Throwable e) {
                    HookLog.logcat("webpage navigate failed: " + e);
                } finally {
                    called.countDown();
                }
            }
        });
        try {
            called.await(5_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        awaitSettled(timeoutMs <= 0 ? LOAD_TIMEOUT : timeoutMs);
        return finished;
    }

    /**
     * 快速导航：换地址但不等待渲染。
     *
     * <p>同域活动切换时，同一个 WebView 换个地址就好 —— Referer 跟着变，
     * 不用销毁重建、不用等渲染。页面上下文（fetch/Cookie）在文档建立后即可用。
     */
    boolean navigateFast(String next, long timeoutMs) {
        if (webView == null || next == null || next.isEmpty()) {
            return false;
        }
        nav.current = next;
        finished = false;
        final CountDownLatch called = new CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(webView, "loadUrl", next);
                } catch (Throwable e) {
                    HookLog.logcat("webpage navigateFast failed: " + e);
                } finally {
                    called.countDown();
                }
            }
        });
        try {
            called.await(5_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        awaitDoc(timeoutMs <= 0 ? LOAD_TIMEOUT : timeoutMs);
        return finished;
    }

    String title() {
        return title;
    }

    boolean ok() {
        return webView != null && finished;
    }

    /**
     * 等文档建立即可（快速模式）。
     *
     * <p>只要 readyState 进入 interactive/complete，window 上就有完整的
     * fetch、Cookie 环境；页面业务 JS 跑没跑完不影响我们直发请求。
     * 留 800ms 让页面初始化（种 Cookie、注册 JS 桥），之后放行。
     */
    void awaitDoc(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String state = "";
        while (System.currentTimeMillis() < deadline) {
            state = evalRaw("(function(){try{return document.readyState}catch(e){return ''}})()",
                    3_000L);
            if (state != null && (state.contains("interactive") || state.contains("complete"))) {
                sleep(800L);
                try {
                    String t = evalRaw("(function(){try{return document.title||''}catch(e){return ''}})()",
                            2_000L);
                    if (t != null && !t.isEmpty()) {
                        title = t;
                    }
                } catch (Throwable ignored) {
                }
                finished = true;
                return;
            }
            sleep(250L);
        }
        finished = false;
        HookLog.logcat("page doc timeout: " + nav.entry + " state=" + state);
    }

    /**
     * 等页面把首屏渲染出来。不是等 onPageFinished，而是等 DOM 里真有内容。
     *
     * <p>只判断 innerHTML 长度是不够的：SPA 的骨架页（一个 div#app 加几个 script）
     * 也超过 400 字符，会被当成「加载完成」。这种页面里的业务 JS 还没跑起来，
     * 拿它发请求，服务端只会回登录页或者路由兜底页的 HTML。
     * 所以还要看标题、正文、Vue 实例三样里有没有真的东西。
     */
    private void awaitSettled(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long start = System.currentTimeMillis();
        boolean reloaded = false;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            state = evalRaw("(function(){try{"
                    + "var app=document.querySelector('#app')||document.querySelector('#root');"
                    + "return JSON.stringify({"
                    + "r:document.readyState,"
                    + "n:(document.body?document.body.innerText:'').trim().length,"
                    + "h:(document.body?document.body.innerHTML:'').length,"
                    + "t:(document.title||'').slice(0,80),"
                    + "v:!!(app&&app.__vue__)});"
                    + "}catch(e){return ''}})()", 4_000L);
            if (state != null && state.contains("\"r\":\"complete\"")) {
                String pageTitle = str(state, "\"t\":\"");
                // SPA 骨架阶段 document.title 常常还是整条 URL，这不是真标题；
                // 拿它当「渲染完成」会在路由切换、DOM 挂载之前就放行采集。
                boolean titleOk = !pageTitle.isEmpty() && !pageTitle.startsWith("http");
                if (titleOk) {
                    title = pageTitle;
                }
                int html = num(state, "\"h\":");
                int text = num(state, "\"n\":");
                boolean vue = state.contains("\"v\":true");
                if (html > 400) {
                    // 标题、正文、Vue 实例，三样里有一样就算真的渲染出来了。
                    if (titleOk || text > 60 || vue) {
                        sleep(1_200L);
                        finished = true;
                        return;
                    }
                    // 骨架页：先无缓存重来一次，绕过内核里那份没有业务脚本的旧壳。
                    // SPA 从骨架到渲染可能要好几秒，阈值给足，别把正常加载当白屏打断。
                    if (!reloaded && System.currentTimeMillis() - start > 8_000L) {
                        reloaded = true;
                        HookLog.logcat("page skeleton, reload without cache: " + nav.entry);
                        try {
                            Object settings = XposedHelpers.callMethod(webView, "getSettings");
                            XposedHelpers.callMethod(settings, "setCacheMode", 2);
                        } catch (Throwable ignored) {
                        }
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    XposedHelpers.callMethod(webView, "reload");
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        sleep(600L);
                        continue;
                    }
                    // 重来一次还是骨架，判断为白屏。上层不该拿它发请求。
                    if (System.currentTimeMillis() - start > 24_000L) {
                        HookLog.logcat("page blank after reload: " + nav.entry);
                        finished = false;
                        return;
                    }
                }
            }
            sleep(400L);
        }
        finished = false;
        HookLog.logcat("page settle timeout: " + nav.entry + " state=" + state);
    }

    private static String str(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) {
            return "";
        }
        int j = i + key.length();
        int k = json.indexOf('"', j);
        return k > j ? json.substring(j, k) : "";
    }

    private static int num(String json, String key) {
        try {
            int i = json.indexOf(key);
            if (i < 0) {
                return 0;
            }
            int j = i + key.length();
            int k = j;
            while (k < json.length() && Character.isDigit(json.charAt(k))) {
                k++;
            }
            return k > j ? Integer.parseInt(json.substring(j, k)) : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 执行一段 JS，返回它 return 的值（字符串原样返回，对象请自己 JSON.stringify）。 */
    String eval(String js, long timeoutMs) {
        return evalRaw(js, timeoutMs <= 0 ? EVAL_TIMEOUT : timeoutMs);
    }

    String eval(String js) {
        return evalRaw(js, EVAL_TIMEOUT);
    }

    private String evalRaw(final String js, final long timeoutMs) {
        if (webView == null) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {null};
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    Method method = findEvaluate(webView.getClass());
                    if (method != null) {
                        Class<?> cbType = method.getParameterTypes()[1];
                        Object cb = Proxy.newProxyInstance(
                                cbType.getClassLoader(), new Class[]{cbType},
                                (proxy, m, args) -> {
                                    if ("onReceiveValue".equals(m.getName())) {
                                        out[0] = args != null && args.length > 0
                                                ? (String) args[0] : null;
                                        latch.countDown();
                                        return null;
                                    }
                                    return null;
                                });
                        method.invoke(webView, js, cb);
                    } else {
                        XposedHelpers.callMethod(webView, "loadUrl", "javascript:" + js);
                        latch.countDown();
                    }
                } catch (Throwable e) {
                    HookLog.logcat("webpage eval failed: " + e);
                    latch.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN.post(task);
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return unquote(out[0]);
    }

    /** evaluateJavascript 会把字符串结果包成 JSON 字面量，去掉外层引号并反转义。 */
    static String unquote(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if ("null".equals(value)) {
            return null;
        }
        if (value.length() < 2 || value.charAt(0) != '"') {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length() - 1) {
                sb.append(c);
                continue;
            }
            char n = value.charAt(++i);
            switch (n) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            sb.append((char) Integer.parseInt(
                                    value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignored) {
                        }
                    }
                    break;
                default:
                    sb.append(n);
            }
        }
        return sb.toString();
    }

    private static Method findEvaluate(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("evaluateJavascript")
                    && types.length == 2
                    && types[0] == String.class) {
                return method;
            }
        }
        return null;
    }

    void close() {
        final Object view = webView;
        if (view == null) {
            return;
        }
        if (real) {
            // v4.3.0：真页也分两种 —— 模块自己拉起来的那种（采集、探测、券兜底）
            // 用完必须收掉，否则一路采集会留下一叠 Activity；用户自己正看的绝不销毁。
            if (RealPage.isOwned(view)) {
                try {
                    RealPage.closePage(view);
                } catch (Throwable ignored) {
                }
            } else {
                HookLog.logcat("real page kept alive: " + nav.current);
            }
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                // 先把 JSBridge 的 WebView 引用还原，再销毁离屏页：
                // 不还原的话，App 自己之后打开活动页会因为回调发进已销毁的 WebView 而卡登录。
                SsoLogin.unbindBridgeFrom(view);
                try {
                    XposedHelpers.callMethod(view, "stopLoading");
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.callMethod(view, "loadUrl", "about:blank");
                } catch (Throwable ignored) {
                }
                // v3.81：先把离屏页从窗口上摘掉，再 destroy，避免留下悬空 View。
                OffscreenWeb.detach(view);
                try {
                    XposedHelpers.callMethod(view, "destroy");
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 系统 ValueCallback 只是为了让编译期有个明确类型。 */
    @SuppressWarnings("unused")
    private static final Class<?> CB = ValueCallback.class;
}
