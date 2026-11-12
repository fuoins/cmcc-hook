package com.cmcchook;

import android.webkit.CookieManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class WebViewHooks {
    private static final String CLASSIC_WEBVIEW = "android.webkit.WebView";
    private static final String CLASSIC_CLIENT = "android.webkit.WebViewClient";
    private static final String CLASSIC_CHROME = "android.webkit.WebChromeClient";
    private static final String X5_WEBVIEW = "com.tencent.smtt.sdk.WebView";
    private static final String X5_CLIENT = "com.tencent.smtt.sdk.WebViewClient";
    private static final String X5_CHROME = "com.tencent.smtt.sdk.WebChromeClient";
    private static final String X5_COOKIE = "com.tencent.smtt.sdk.CookieManager";
    private static final long DEDUPE_MS = 1500L;
    private static final long H5_DEDUPE_MS = 10_000L;
    private static final long NOISE_REPORT_MS = 60_000L;

    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final Map<String, long[]> H5_SEEN = new ConcurrentHashMap<>();
    private static final AtomicLong NOISE_DROPPED = new AtomicLong();
    private static final AtomicLong NOISE_REPORTED_AT = new AtomicLong();
    private static final Map<String, Long> COOKIE_RECENT = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> COOKIE_GUARD = new ThreadLocal<>();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();
    private static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();
    private static volatile boolean x5Done;

    private WebViewHooks() {
    }

    static void install(ClassLoader classLoader) {
        installClassic(classLoader);
        installX5(classLoader);
    }

    /** android.webkit 属于系统类，任何 ClassLoader 都能立刻找到并安装。 */
    static void installClassic(ClassLoader classLoader) {
        hookWebView(classLoader, CLASSIC_WEBVIEW);
        hookClient(classLoader, CLASSIC_CLIENT);
        hookChrome(classLoader, CLASSIC_CHROME);
        hookCookieManager(classLoader, "android.webkit.CookieManager");
    }

    /**
     * X5 SDK 可能随加固壳晚加载，必须等类真正出现后再 hook。
     *
     * @return true 表示已安装或已确认该版本没有 X5，无需继续探测。
     */
    static synchronized boolean installX5(ClassLoader classLoader) {
        if (x5Done) {
            return true;
        }
        if (XposedHelpers.findClassIfExists(X5_WEBVIEW, classLoader) == null) {
            return false;
        }
        hookWebView(classLoader, X5_WEBVIEW);
        hookClient(classLoader, X5_CLIENT);
        hookChrome(classLoader, X5_CHROME);
        hookCookieManager(classLoader, X5_COOKIE);
        x5Done = true;
        HookLog.logcat("X5 WebView hooks installed");
        return true;
    }

    private static void hookWebView(ClassLoader classLoader, String className) {
        if (!INSTALLED.add(className)) {
            return;
        }
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            INSTALLED.remove(className);
            return;
        }
        try {
            XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null) {
                        JsInjector.installBridge(param.thisObject, "constructor");
                        // v3.43：登记新建的 WebView，供 CMD_OPEN 兜底补 loadUrl。
                        OpenGuard.noteWebView(param.thisObject);
                    }
                }
            });
        } catch (Throwable e) {
            reportOnce(className + ":ctor", "WebView constructor hook failed: " + e);
        }

        hookAll(clazz, "loadUrl", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    // v3.43：容器有没有自行加载页面，CMD_OPEN 兜底靠这个判断。
                    OpenGuard.noteLoad(url);
                    // SDK 的 success/error 回调也是用 loadUrl("javascript:...") 发回来的。
                    // 这类脚本不打印、不注入，只做一件事：转发到离屏页。
                    if (url.startsWith("javascript:")) {
                        SdkBridge.mirror(url, param.thisObject);
                        return;
                    }
                    // loadUrl 的 thisObject 就是 WebView 本体，比 WebViewClient 回调里的
                    // args[0] 稳（X5 回调参数顺序会随版本变）。App 自己开的卡券页在这里登记。
                    // v4.0.9：同时登记到 RealPage —— 所有「执行」都改在 App 官方真页里跑，
                    // 这里就是「真页在哪」的唯一来源（离屏页在 note 内部会被排除）。
                    try {
                        RealPage.note(param.thisObject, url);
                    } catch (Throwable ignored) {
                    }
                    attachCoupon(url, param.thisObject);
                    attachWisdom(url, param.thisObject);
                    attachGoose(param, url);
                    // v4.0.7+probe：记下前台真页卡券入口的「当次现场原料」（带参 URL +
                    // loadUrl headers + Cookie 快照 + 时间戳），供全方案探针复用。
                    // 只记前台真页：离屏页正是被对照的对象，把它记进来等于自证循环。
                    if (url.contains("mycoupons")) {
                        boolean off = isOffscreenPage(param.thisObject);
                        if (!off && CouponTask.URL.equals(url) && OffscreenWeb.current() != null) {
                            off = true;
                        }
                        if (!off) {
                            CouponScene.note(url, firstMap(param.args));
                        }
                    }
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    Map<?, ?> headers = firstMap(param.args);
                    if (HookPrefs.ui()) {
                        String detail = "URL: " + url + "\n类: "
                                + param.thisObject.getClass().getName();
                        if (headers != null && !headers.isEmpty()) {
                            detail += "\n--- loadUrl headers ---\n" + headers(headers);
                        }
                        logUi("WEBVIEW", "WebView 加载 · " + UrlFilter.shortForTitle(url), detail);
                    }
                    dumpCookies(url, param.thisObject.getClass().getClassLoader(),
                            className.startsWith("com.tencent."));
                    JsInjector.inject(param.thisObject, "loadUrl");
                } catch (Throwable e) {
                    reportOnce(className + ":loadUrl", "loadUrl hook failed: " + e);
                }
            }
        });

        // 回调也可能走 evaluateJavascript（X5 新版就是这条路）。
        // 同样只做转发，不打印、不改写。
        hookAll(clazz, "evaluateJavascript", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    SdkBridge.mirror((String) param.args[0], param.thisObject);
                } catch (Throwable ignored) {
                }
            }
        });

        hookAll(clazz, "postUrl", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2) {
                        return;
                    }
                    String url = String.valueOf(param.args[0]);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    if (HookPrefs.ui()) {
                        String body = "";
                        if (param.args[1] instanceof byte[]) {
                            body = new String((byte[]) param.args[1], StandardCharsets.UTF_8);
                            if (body.length() > 20_000) {
                                body = body.substring(0, 20_000) + "…";
                            }
                        }
                        logUi("WEBVIEW", "WebView POST · " + UrlFilter.shortForTitle(url),
                                "URL: " + url + "\nBody:\n" + body);
                    }
                    dumpCookies(url, param.thisObject.getClass().getClassLoader(), false);
                    JsInjector.inject(param.thisObject, "postUrl");
                } catch (Throwable e) {
                    reportOnce(className + ":postUrl", "postUrl hook failed: " + e);
                }
            }
        });

        hookAll(clazz, "loadDataWithBaseURL", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2) {
                        return;
                    }
                    String base = String.valueOf(param.args[0]);
                    Object data = param.args[1];
                    int length = data instanceof String ? ((String) data).length() : 0;
                    if (HookPrefs.ui() && UrlFilter.interesting(base)) {
                        logUi("WEBVIEW", "WebView loadData · " + UrlFilter.shortForTitle(base),
                                "Base URL: " + base + "\nHTML 长度: " + length
                                        + "\n类: " + param.thisObject.getClass().getName());
                    }
                    JsInjector.inject(param.thisObject, "loadDataWithBaseURL");
                } catch (Throwable e) {
                    reportOnce(className + ":loadData", "loadData hook failed: " + e);
                }
            }
        });

        hookAll(clazz, "addJavascriptInterface", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2) {
                        return;
                    }
                    String name = String.valueOf(param.args[1]);
                    if (JsInjector.BRIDGE_NAME.equals(name)) {
                        return;
                    }
                    // 记下带 App H5 SDK 的 WebView：换 SSO 票据要靠它执行 JS。
                    SsoLogin.onBridge(param.thisObject, param.args[0], name);
                    // v4.0.8：缓存 App 真页注册过的全部私有 JS 桥，离屏 WebView
                    // 建好时会整套装回去。卡券页缺 cardInterface 会连 Vue 都不 mount。
                    AppBridges.record(param.thisObject, param.args[0], name);
                    if (!HookPrefs.ui()) {
                        return;
                    }
                    Object object = param.args[0];
                    logUi("WEBVIEW", "注册 JS 桥 · " + name,
                            "WebView: " + param.thisObject.getClass().getName()
                                    + "\n接口类: "
                                    + (object == null ? "null" : object.getClass().getName()));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void hookClient(ClassLoader classLoader, String className) {
        if (!INSTALLED.add(className)) {
            return;
        }
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            INSTALLED.remove(className);
            return;
        }

        hookAll(clazz, "onPageStarted", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    attachCoupon(url, param.args[0]);
                    attachWisdom(url, param.args[0]);
                    // 离屏页：赶在页面脚本之前把登录桥垫片立起来，
                    // 否则 coc 页面会卡在「加载中 92% 登录中」。
                    SdkBridge.injectShim(param.args[0]);
                    // App 自己的 WebView 落在 SSO 登录页 = 登录态失效。
                    // 这种页面带 App 的 JS 桥，会自动拉起扫脸核验烧次数，
                    // 检测到就暂停当天所有任务，别再火上浇油。
                    checkLoginLost(url);
                    // 引导式采集：前台页面加载就登记 WebView 并注入采集脚本。
                    if (LiveProbe.active() && LiveProbe.interested(url)) {
                        LiveProbe.attach(param.args[0]);
                        JsInjector.inject(param.args[0], "liveProbe");
                    }
                    attachGoose(param, url);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    if (HookPrefs.ui()) {
                        logUi("WEBVIEW", "页面开始 · " + UrlFilter.shortForTitle(url),
                                "URL: " + url + "\nWebViewClient: " + className);
                    }
                    dumpCookies(url, classLoader, className.startsWith("com.tencent."));
                    JsInjector.inject(param.args[0], "onPageStarted");
                } catch (Throwable e) {
                    reportOnce(className + ":start", "onPageStarted hook failed: " + e);
                }
            }
        });

        hookAll(clazz, "onPageFinished", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    attachCoupon(url, param.args[0]);
                    attachWisdom(url, param.args[0]);
                    // 页面加载完成再注一次采集脚本：started 时 body 可能还没建好。
                    if (LiveProbe.active() && LiveProbe.interested(url)) {
                        LiveProbe.attach(param.args[0]);
                        JsInjector.evaluateRaw(param.args[0], ProbeScript.COLLECT_LIVE);
                    }
                    attachGoose(param, url);
                    PageAuto.onPage(webViewOf(param), url);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    if (HookPrefs.ui()) {
                        logUi("WEBVIEW", "页面完成 · " + UrlFilter.shortForTitle(url),
                                "URL: " + url + "\n标题: " + titleOf(param.args[0]));
                    }
                    dumpCookies(url, classLoader, className.startsWith("com.tencent."));
                    JsInjector.inject(param.args[0], "onPageFinished");
                } catch (Throwable e) {
                    reportOnce(className + ":finish", "onPageFinished hook failed: " + e);
                }
            }
        });

        hookAll(clazz, "doUpdateVisitedHistory", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    attachCoupon(url, param.args[0]);
                    attachWisdom(url, param.args[0]);
                    attachGoose(param, url);
                    PageAuto.onPage(webViewOf(param), url);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    if (HookPrefs.ui()) {
                        logUi("WEBVIEW", "页面历史 · " + UrlFilter.shortForTitle(url),
                                "URL: " + url + "\nisReload: " + (param.args.length > 2
                                        ? String.valueOf(param.args[2]) : "?"));
                    }
                    JsInjector.inject(param.args[0], "doUpdateVisitedHistory");
                } catch (Throwable ignored) {
                }
            }
        });

        // 任务页最怕被「下载 App / 去 APP 打开」这类引导顶掉：一顶走，原页面的
        // JS 上下文当场销毁，任务脚本写回的变量再也读不到，表现就是「页面能打开、
        // 脚本 90 秒不回报」。球赛页 11-07 就是这么挂的，鹅蛋 11-10 22:07 / 22:27
        // 又挂了一次（dx.10086.cn/A/… → transferDownload）。
        //
        // 必须放在 before：after 时段原方法早就返回 false，导航已经开始，
        // 这时候 setResult(true) 什么也拦不住 —— 11-10 两份日志里 transferDownload
        // 照跳不误，就是这个原因。before 里 setResult(true) 才是「已处理，别导航」。
        hookAll(clazz, "shouldOverrideUrlLoading", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    Object view = webViewOf(param);
                    boolean taskPage = isOffscreenPage(view) || RealPage.isTaskPage(view);
                    if (!taskPage) {
                        return;
                    }
                    if (isDetourUrl(url)) {
                        param.setResult(true);
                        HookLog.log("TASK", "已拦下外部引导跳转",
                                "目标: " + UrlFilter.shortForTitle(url)
                                        + "\n任务页需要留在活动页，已阻止本次导航");
                        return;
                    }
                    // 鹅蛋 / 球赛（lyh.lncmcc.com）页面里有个「下载 App」广告位，
                    // 换票后 0.2 秒内自动跳 dx.10086.cn/A/…，再一跳就是下载引导页，
                    // 整个 WebView 被换掉，脚本上下文当场销毁 —— 22:27 鹅蛋卡死就是这么来的。
                    // 只拦「当前页在 lyh、目标是 dx 短链」这一种组合，其它 dx 短链
                    // （真正的 SSO 登录中转）照旧放行，避免把登录流程也堵了。
                    if (isAppJumpShort(url) && currentUrlOf(view).contains("lyh.lncmcc.com")) {
                        param.setResult(true);
                        HookLog.log("TASK", "已拦下活动页内的下载引导跳转",
                                "目标: " + UrlFilter.shortForTitle(url)
                                        + "\n当前页: " + UrlFilter.shortForTitle(currentUrlOf(view))
                                        + "\n该页面里的「下载 App」广告位会顶掉活动页，已阻止");
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        hookAll(clazz, "shouldOverrideUrlLoading", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null) {
                        return;
                    }
                    attachGoose(param, url);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    if (HookPrefs.ui()) {
                        logUi("WEBVIEW", "跳转请求 · " + UrlFilter.shortForTitle(url),
                                "URL: " + url + "\n返回值: " + param.getResult());
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        hookAll(clazz, "onReceivedError", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                logWebError("页面加载错误", param, className);
            }
        });

        hookAll(clazz, "onReceivedHttpError", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                logWebError("页面 HTTP 错误", param, className);
            }
        });
    }

    private static void logWebError(String name, XC_MethodHook.MethodHookParam param,
                                    String className) {
        try {
            String url = firstWebUrl(param.args);
            if (url == null || !UrlFilter.interesting(url)) {
                return;
            }
            StringBuilder detail = new StringBuilder("URL: ").append(url)
                    .append("\nWebViewClient: ").append(className);
            for (Object arg : param.args) {
                if (arg == null || arg instanceof String) {
                    continue;
                }
                String argClass = arg.getClass().getName();
                if (argClass.contains("WebResourceRequest") || argClass.contains("WebResourceError")
                        || argClass.contains("WebResourceResponse")) {
                    detail.append("\n").append(argClass).append(": ").append(safeObject(arg));
                }
            }
            HookLog.log("WEBVIEW-ERROR", name + " · " + UrlFilter.shortForTitle(url),
                    detail.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void hookChrome(ClassLoader classLoader, String className) {
        if (!INSTALLED.add(className)) {
            return;
        }
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            INSTALLED.remove(className);
            return;
        }
        hookAll(clazz, "onConsoleMessage", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length == 0) {
                        return;
                    }
                    ConsoleText console = readConsole(param.args);
                    // 换 SSO 票据时页面回传的结果，从这里截走，不进日志。
                    if (SsoLogin.onConsole(console.text)) {
                        swallowConsole(param);
                        return;
                    }
                    // 埋点 SDK 每次 DOM 变化都刷同一条 TypeError，先吞掉再谈记录。
                    if (HookPrefs.h5NoiseFilter() && isH5Noise(console.text, console.source)) {
                        long dropped = NOISE_DROPPED.incrementAndGet();
                        reportNoise(dropped, console.text, console.source);
                        swallowConsole(param);
                        return;
                    }
                    if (!HookPrefs.ui()) {
                        return;
                    }
                    recordConsole(console);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** onConsoleMessage 有两个重载：ConsoleMessage 对象版和已废弃的 (String, int, String) 版。 */
    private static ConsoleText readConsole(Object[] args) {
        String text = "";
        String source = "";
        String level = "";
        int line = 0;
        Object first = args[0];
        if (first instanceof String) {
            text = (String) first;
            if (args.length > 1 && args[1] instanceof Number) {
                line = ((Number) args[1]).intValue();
            }
            if (args.length > 2 && args[2] != null) {
                source = String.valueOf(args[2]);
            }
            return new ConsoleText(text, source, level, line);
        }
        if (first == null) {
            return new ConsoleText("", "", "", 0);
        }
        try {
            Object value = XposedHelpers.callMethod(first, "message");
            if (value != null) {
                text = String.valueOf(value);
            }
        } catch (Throwable ignored) {
        }
        try {
            Object value = XposedHelpers.callMethod(first, "messageLevel");
            if (value != null) {
                level = value.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object value = XposedHelpers.callMethod(first, "sourceId");
            if (value != null) {
                source = value.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object value = XposedHelpers.callMethod(first, "lineNumber");
            if (value instanceof Number) {
                line = ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return new ConsoleText(text, source, level, line);
    }

    private static void recordConsole(ConsoleText console) {
        String lower = (console.level + " " + console.text).toLowerCase(Locale.US);
        if (!lower.contains("error") && !lower.contains("warn")
                && !lower.contains("fail") && !lower.contains("token")
                && !lower.contains("auth")) {
            return;
        }
        String key = console.source + "|" + console.text;
        long now = System.currentTimeMillis();
        long[] seen = H5_SEEN.get(key);
        int repeat = 0;
        if (seen == null) {
            H5_SEEN.put(key, new long[]{now, 0L});
        } else {
            synchronized (seen) {
                if (now - seen[0] < H5_DEDUPE_MS) {
                    seen[1]++;
                    return;
                }
                repeat = (int) seen[1];
                seen[0] = now;
                seen[1] = 0L;
            }
        }
        if (H5_SEEN.size() > 300) {
            H5_SEEN.clear();
        }
        String detail = "消息: " + console.text + "\n来源: " + console.source + ":" + console.line
                + "\n级别: " + console.level;
        if (repeat > 0) {
            detail += "\n期间重复: " + repeat + " 次（已省略）";
        }
        HookLog.log("H5-CONSOLE", "H5 控制台 · " + console.level + " · "
                + UrlFilter.shortForTitle(console.text), detail);
    }

    /**
     * psie 曝光埋点 SDK 注册了 MutationObserver，DOM 一变就拿节点调 getAttribute，
     * 节点已被移除时抛 TypeError，SDK 自己 catch 后打一条 WARNING。属于 SDK 自身缺陷，
     * 和页面功能无关，只是把日志刷满。
     */
    private static boolean isH5Noise(String text, String source) {
        String src = source == null ? "" : source.toLowerCase(Locale.US);
        if (src.contains("psie")) {
            return true;
        }
        if (text == null || text.isEmpty()) {
            return false;
        }
        String msg = text.toLowerCase(Locale.US);
        if (msg.contains("初始化曝光失败")) {
            return true;
        }
        return msg.contains("cannot read") && msg.contains("undefined")
                && msg.contains("getattribute");
    }

    private static void reportNoise(long dropped, String text, String source) {
        long now = System.currentTimeMillis();
        long last = NOISE_REPORTED_AT.get();
        if (last != 0L && now - last < NOISE_REPORT_MS) {
            return;
        }
        if (!NOISE_REPORTED_AT.compareAndSet(last, now)) {
            return;
        }
        HookLog.log("H5-NOISE", "已忽略 H5 埋点噪音 · 累计 " + dropped + " 条",
                "最后一条: " + shortText(text) + "\n来源: " + source
                        + "\n说明: psie 曝光埋点 SDK 在 DOM 变化时读不到节点，属 SDK 自身缺陷"
                        + "\n关闭过滤: 设置 → 过滤 H5 埋点噪音");
    }

    private static void swallowConsole(XC_MethodHook.MethodHookParam param) {
        try {
            Class<?> type = param.method instanceof Method
                    ? ((Method) param.method).getReturnType() : boolean.class;
            if (type == void.class) {
                param.setResult(null);
            } else if (type == boolean.class || type == Boolean.class) {
                param.setResult(Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shortText(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static final class ConsoleText {
        final String text;
        final String source;
        final String level;
        final int line;

        ConsoleText(String text, String source, String level, int line) {
            this.text = text == null ? "" : text;
            this.source = source == null ? "" : source;
            this.level = level == null ? "" : level;
            this.line = line;
        }
    }

    private static void hookCookieManager(ClassLoader classLoader, String className) {
        if (!INSTALLED.add(className)) {
            return;
        }
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            INSTALLED.remove(className);
            return;
        }
        hookAll(clazz, "getCookie", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(COOKIE_GUARD.get())) {
                    return;
                }
                try {
                    if (!HookPrefs.storage() || param.args.length == 0) {
                        return;
                    }
                    String url = String.valueOf(param.args[0]);
                    String cookie = param.getResult() == null ? "" : String.valueOf(param.getResult());
                    logCookie("Cookie 读取", url, cookie);
                } catch (Throwable ignored) {
                }
            }
        });
        hookAll(clazz, "setCookie", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(COOKIE_GUARD.get())) {
                    return;
                }
                try {
                    if (!HookPrefs.storage() || param.args.length < 2) {
                        return;
                    }
                    String url = String.valueOf(param.args[0]);
                    String cookie = String.valueOf(param.args[1]);
                    logCookie("Cookie 写入", url, cookie);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void dumpCookies(String url, ClassLoader classLoader, boolean x5) {
        if (!HookPrefs.storage() || !UrlFilter.interesting(url)) {
            return;
        }
        if (Boolean.TRUE.equals(COOKIE_GUARD.get())) {
            return;
        }
        COOKIE_GUARD.set(Boolean.TRUE);
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            logCookie("WebView Cookie 快照", url, cookie);
        } catch (Throwable ignored) {
        } finally {
            COOKIE_GUARD.remove();
        }

        if (!x5) {
            return;
        }
        COOKIE_GUARD.set(Boolean.TRUE);
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(X5_COOKIE, classLoader);
            if (clazz != null) {
                Object manager = XposedHelpers.callStaticMethod(clazz, "getInstance");
                String cookie = (String) XposedHelpers.callMethod(manager, "getCookie", url);
                logCookie("X5 Cookie 快照", url, cookie);
            }
        } catch (Throwable ignored) {
        } finally {
            COOKIE_GUARD.remove();
        }
    }

    private static void logCookie(String action, String url, String cookie) {
        try {
            if (!HookPrefs.storage() || cookie == null || cookie.isEmpty()
                    || !UrlFilter.interesting(url)) {
                return;
            }
            String key = action + "|" + url + "|" + cookie.hashCode();
            long now = System.currentTimeMillis();
            Long previous = COOKIE_RECENT.put(key, now);
            if (previous != null && now - previous < 30_000L) {
                return;
            }
            if (COOKIE_RECENT.size() > 300) {
                COOKIE_RECENT.clear();
            }
            HookLog.log("COOKIE", action + " · " + UrlFilter.host(url),
                    "URL: " + url + "\nCookie:\n" + cookie);
        } catch (Throwable ignored) {
        }
    }

    private static void hookAll(Class<?> clazz, String name, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, name, hook);
        } catch (Throwable e) {
            reportOnce(clazz.getName() + ":" + name, "hook " + name + " failed: " + e);
        }
    }

    private static void logUi(String tag, String summary, String detail) {
        if (!HookPrefs.ui()) {
            return;
        }
        String key = tag + "|" + summary;
        long now = System.currentTimeMillis();
        Long previous = RECENT.put(key, now);
        if (previous != null && now - previous < DEDUPE_MS) {
            return;
        }
        if (RECENT.size() > 400) {
            RECENT.clear();
        }
        HookLog.log(tag, summary, detail);
    }

    /**
     * 页面经过 SSO 登录页：只记一条诊断日志，不再标记「登录态失效」。
     *
     * <p>v3.14.1 起停用按天封杀：qwhdsso/login 是正常换票流程的必经中转页
     * （11.9.1 抓包实证），App 每次正常进活动都会经过它。旧版据此打标并
     * 当天停掉全部任务，把「登录成功」误判成「登录态失效」，且打标后任务
     * 被拦、页面不再开，解锁又只能靠开页成功，形成无法自愈的死锁。
     * 现在任务全部手动逐个执行，不存在自动反复开页烧操作次数的前提，
     * 这里只保留观测，不再产生任何拦截。
     */
    private static void checkLoginLost(String url) {
        if (url != null && url.toLowerCase().contains("qwhdsso/login")) {
            HookLog.logcat("sso relay page (normal, no lock): " + url);
        }
    }

    /** 这个 WebView 是不是模块自己开的离屏任务页。 */
    private static boolean isOffscreenPage(Object view) {
        if (view == null) {
            return false;
        }
        Object cur = OffscreenWeb.current();
        return cur != null && cur == view;
    }

    /**
     * 用户打开「我的卡券」时把前台 WebView 登记给 CouponTask。
     * 离屏页不算：它没有 App 的完整 JS 桥，Vue 券列表渲染不出来（v3.39 实测 0 张）。
     */
    private static volatile long lastCouponAttachLog;

    private static void attachCoupon(String url, Object view) {
        // 顺手把用户亲手打开的「余量查询」真页登记给 FlowTask：真页有会话、有 App 现场参数，
        // 页面会自己请求并解密，读它的 Vuex 比离屏稳（11-10 18:12 日志实证）。
        try {
            FlowTask.noteReal(url, view);
        } catch (Throwable ignored) {
        }
        if (url == null || !url.contains("mycoupons")) {
            return;
        }
        boolean off = isOffscreenPage(view);
        // v4.0.7：X5 回调传进来的可能是 com.tencent.smtt.sdk.WebView$a 内部类，
        // 和 OffscreenWeb.track() 登记的 WebView 不是同一实例，== 判定会漏
        // （21:32 日志实证：离屏页被登记成「前台」，把用户真页的登记冲掉）。
        // 离屏页统一用不带参数的裸地址 CouponTask.URL，前台真页一定带
        // ?c=11&e=99&channelId=... 参数；离屏会话期间按地址再兜一层。
        if (!off && CouponTask.URL.equals(url) && OffscreenWeb.current() != null) {
            off = true;
        }
        // v3.41：把「有没有登记上」写进日志。上一版 5 次扫描全走离屏兜底，
        // 但日志里 App 明明两次开了卡券页，不落日志就只能一直猜。
        // 4 个 hook 点会连着触发同一个页面，1.5 秒内只记一条，避免刷屏。
        long now = System.currentTimeMillis();
        if (HookPrefs.ui() && now - lastCouponAttachLog > 1500L) {
            lastCouponAttachLog = now;
            HookLog.log("COUPON", "卡券页登记 · " + (off ? "离屏(跳过)" : "前台"),
                    "url: " + url + "\nWebView: "
                            + (view == null ? "null" : view.getClass().getName())
                            + "\n入口: loadUrl / 页面开始 / 页面完成 / 页面历史");
        }
        if (!off) {
            CouponPage.attach(view, url);
        }
    }

    /** v3.82：登记用户手动打开的 wisdom-h5 签到 / 问答前台真页。 */
    private static volatile long lastWisdomAttachLog;

    private static void attachWisdom(String url, Object view) {
        if (url == null || view == null || !url.toLowerCase(Locale.US).contains("wisdom-h5")) {
            return;
        }
        String u = url.toLowerCase(Locale.US);
        if (!u.contains("signinlottery") && !u.contains("answerquestions")) {
            return;
        }
        boolean off = isOffscreenPage(view);
        long now = System.currentTimeMillis();
        if (HookPrefs.ui() && now - lastWisdomAttachLog > 1500L) {
            lastWisdomAttachLog = now;
            HookLog.log("TASK", "签到页登记 · " + (off ? "离屏(跳过)" : "前台真页"),
                    "url: " + url + "\nWebView: " + view.getClass().getName()
                            + "\n说明: 前台真页已登录、已布局，后续任务会优先复用它，不再另开离屏页。");
        }
        if (!off) {
            WisdomPage.attach(view, url);
        }
    }

    /** dx.10086.cn/A/xxx 这类「打开 App」短链（不是 SSO 登录中转）。 */
    private static boolean isAppJumpShort(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.US);
        return (u.startsWith("https://dx.10086.cn/a/") || u.startsWith("http://dx.10086.cn/a/"))
                && !u.contains("login");
    }

    /** WebView 当前地址；反射拿不到就退回登记表里这个实例最近一条。 */
    private static String currentUrlOf(Object view) {
        if (view == null) {
            return "";
        }
        try {
            Object v = view.getClass().getMethod("getUrl").invoke(view);
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        try {
            return RealPage.lastUrlOf(view);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 「下载 App / 去 APP 打开」这类会把活动页顶掉的中转页。
     *
     * <p>只认明确的几种，不按域名一刀切：球赛活动自己也会跨到 lyh 的其它子页，
     * 拦宽了活动就跑不下去。
     */
    static boolean isDetourUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String u = url.toLowerCase(Locale.US);
        // v3.60：dx.10086.cn/A/xxx 不再一律当「下载 App 引导」拦掉。
        // 11-07 收鹅蛋日志实证：换票后页面跳的就是 https://dx.10086.cn/A/tccgDw，
        // 那是登录短链，被拦下之后页面永远停在未登录，任务「啥也没做」就结束了。
        // 短链没法预判落点，只能放行；真正的下载引导会再跳一次 transferdownload，
        // 那一跳仍在下面的拦截里。
        return u.contains("transferdownload")
                || u.contains("/transit/download")
                || u.contains("downloadapp");
    }

    private static void attachGoose(XC_MethodHook.MethodHookParam param, String url) {        if (!GooseTask.isGoosePage(url)) {
            return;
        }
        Object webView = null;
        if (param.args != null) {
            for (Object arg : param.args) {
                if (arg == null || arg instanceof String) {
                    continue;
                }
                if (arg.getClass().getName().contains("WebView")) {
                    webView = arg;
                    break;
                }
            }
        }
        if (webView == null) {
            webView = param.thisObject;
        }
        GooseTask.attach(webView, url);
    }

    private static Object webViewOf(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) {
            for (Object arg : param.args) {
                if (arg == null || arg instanceof String) {
                    continue;
                }
                if (arg.getClass().getName().contains("WebView")) {
                    return arg;
                }
            }
        }
        Object self = param.thisObject;
        if (self != null && self.getClass().getName().contains("WebView")) {
            return self;
        }
        return null;
    }

    /**
     * 现场看到 URL 时顺手喂给 {@link RealEntry} 学习真实入口。
     * 只对「带渠道参数的活动页地址」生效，其它 URL 原样返回、零开销。
     */
    private static String noteUrl(String url) {
        RealEntry.learn(url);
        return url;
    }

    private static String firstWebUrl(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String value = (String) arg;
                if (value.startsWith("http://") || value.startsWith("https://")
                        || value.startsWith("file://")) {
                    return noteUrl(value);
                }
            }
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String name = arg.getClass().getName();
            if (name.contains("WebResourceRequest")) {
                try {
                    Object uri = XposedHelpers.callMethod(arg, "getUrl");
                    if (uri != null) {
                        return noteUrl(String.valueOf(uri));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Map<?, ?> firstMap(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Map) {
                return (Map<?, ?>) arg;
            }
        }
        return null;
    }

    private static String headers(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ >= 60) {
                sb.append("...\n");
                break;
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String titleOf(Object webView) {
        try {
            Object title = XposedHelpers.callMethod(webView, "getTitle");
            return title == null ? "" : String.valueOf(title);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String safeObject(Object object) {
        try {
            String value = String.valueOf(object);
            // r12：2000 会把 localStorage 里整段活动配置（regionJson / floorRule）
            // 从中间切断，正是「加活动时要抄的服务端参数」。放到 8000，仍留上限
            // 防止某条超大对象撑爆单条日志的 160k 预算。
            return value.length() > 8000 ? value.substring(0, 8000) + "…" : value;
        } catch (Throwable e) {
            return object.getClass().getName();
        }
    }

    private static void reportOnce(String key, String message) {
        if (REPORTED.put(key, Boolean.TRUE) == null) {
            HookLog.logcat(message);
        }
    }
}
