package com.qoder.cmcchook;

import android.webkit.CookieManager;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
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
                    if (url == null || !UrlFilter.interesting(url)) {
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
                    if (!HookPrefs.ui() || param.args.length < 2) {
                        return;
                    }
                    String name = String.valueOf(param.args[1]);
                    if (JsInjector.BRIDGE_NAME.equals(name)) {
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
                    if (url == null || !UrlFilter.interesting(url)) {
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
                    if (url == null || !UrlFilter.interesting(url)) {
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
                    if (url == null || !UrlFilter.interesting(url)) {
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

        hookAll(clazz, "shouldOverrideUrlLoading", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String url = firstWebUrl(param.args);
                    if (url == null || !UrlFilter.interesting(url)) {
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
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!HookPrefs.ui() || param.args.length == 0) {
                        return;
                    }
                    Object message = param.args[0];
                    if (message == null) {
                        return;
                    }
                    String text = "";
                    String level = "";
                    String source = "";
                    int line = 0;
                    try {
                        text = String.valueOf(XposedHelpers.callMethod(message, "message"));
                        Object l = XposedHelpers.callMethod(message, "messageLevel");
                        level = l == null ? "" : l.toString();
                        Object sourceId = XposedHelpers.callMethod(message, "sourceId");
                        source = sourceId == null ? "" : sourceId.toString();
                        Object lineNumber = XposedHelpers.callMethod(message, "lineNumber");
                        if (lineNumber instanceof Number) {
                            line = ((Number) lineNumber).intValue();
                        }
                    } catch (Throwable ignored) {
                    }
                    String lower = (level + " " + text).toLowerCase(Locale.US);
                    if (!lower.contains("error") && !lower.contains("warn")
                            && !lower.contains("fail") && !lower.contains("token")
                            && !lower.contains("auth")) {
                        return;
                    }
                    HookLog.log("H5-CONSOLE", "H5 控制台 · " + level + " · "
                                    + UrlFilter.shortForTitle(text),
                            "消息: " + text + "\n来源: " + source + ":" + line
                                    + "\n级别: " + level);
                } catch (Throwable ignored) {
                }
            }
        });
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

    private static String firstWebUrl(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String value = (String) arg;
                if (value.startsWith("http://") || value.startsWith("https://")
                        || value.startsWith("file://")) {
                    return value;
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
                        return String.valueOf(uri);
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
            return value.length() > 2000 ? value.substring(0, 2000) + "…" : value;
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
