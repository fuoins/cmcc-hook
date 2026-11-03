package com.qoder.cmcchook;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class OkHttpHooks {
    private static final int MAX_BODY = 200_000;
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();
    private static final Set<Class<?>> CHAIN_HOOKED = Collections.newSetFromMap(
            new ConcurrentHashMap<Class<?>, Boolean>());
    private static final Set<Class<?>> FALLBACK_HOOKED = Collections.newSetFromMap(
            new ConcurrentHashMap<Class<?>, Boolean>());
    private static final Set<ClassLoader> CHAIN_LOADERS = Collections.newSetFromMap(
            new ConcurrentHashMap<ClassLoader, Boolean>());

    private OkHttpHooks() {
    }

    static void install(ClassLoader classLoader) {
        boolean installed = hookChain(classLoader, "okhttp3.internal.http.RealInterceptorChain");
        installed |= hookChain(classLoader, "com.android.okhttp.internal.http.RealInterceptorChain");
        installed |= hookChain(classLoader, "com.squareup.okhttp.internal.http.RealInterceptorChain");
        if (!installed) {
            hookFallback(classLoader, "okhttp3.OkHttpClient");
            hookFallback(classLoader, "com.squareup.okhttp.OkHttpClient");
        }
    }

    private static boolean hookChain(ClassLoader classLoader, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            return false;
        }
        if (!CHAIN_HOOKED.add(clazz)) {
            return true;
        }
        boolean installed = false;
        try {
            XposedBridge.hookAllMethods(clazz, "proceed", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handleProceed(param);
                }
            });
            installed = true;
            CHAIN_LOADERS.add(classLoader);
            HookLog.logcat("OkHttp chain hooked: " + className);
        } catch (Throwable e) {
            reportOnce(className, "hook " + className + " failed: " + e);
        }
        if (!installed) {
            CHAIN_HOOKED.remove(clazz);
        }
        return installed;
    }

    private static void handleProceed(XC_MethodHook.MethodHookParam param) {
        if (!HookPrefs.nativeHttp()) {
            return;
        }
        Object request = findRequest(param.args);
        if (request == null) {
            return;
        }
        String url = requestUrl(request);
        if (!UrlFilter.interesting(url)) {
            return;
        }
        if (param.getThrowable() != null) {
            HookLog.log("HTTP-ERROR", "HTTP 请求异常 · " + requestMethod(request) + " "
                            + UrlFilter.shortForTitle(url),
                    requestDetail(request, url) + "\n--- 异常 ---\n"
                            + android.util.Log.getStackTraceString(param.getThrowable()));
            return;
        }
        Object response = param.getResult();
        if (response == null) {
            return;
        }
        try {
            int code = intValue(call(response, "code"), 0);
            String body = responseBody(response);
            String key = System.identityHashCode(request) + "|" + code + "|"
                    + (body == null ? 0 : body.hashCode());
            long now = System.currentTimeMillis();
            Long previous = RECENT.put(key, now);
            if (previous != null && now - previous < 500L) {
                return;
            }
            if (RECENT.size() > 300) {
                RECENT.clear();
            }

            String summary = "HTTP " + requestMethod(request) + " "
                    + UrlFilter.shortForTitle(url) + " → " + code;
            StringBuilder detail = new StringBuilder(requestDetail(request, url));
            detail.append("状态: ").append(code).append(' ')
                    .append(string(call(response, "message"))).append('\n');
            Object protocol = call(response, "protocol");
            if (protocol != null) {
                detail.append("协议: ").append(protocol).append('\n');
            }
            appendBlock(detail, "响应头", responseHeaders(response));
            appendBody(detail, "响应体", body);
            HookLog.log("HTTP", summary, detail.toString());
        } catch (Throwable e) {
            reportOnce("response", "format HTTP response failed: " + e);
        }
    }

    private static String requestDetail(Object request, String url) {
        StringBuilder detail = new StringBuilder();
        detail.append("URL: ").append(url).append('\n');
        detail.append("方法: ").append(requestMethod(request)).append('\n');
        appendBlock(detail, "请求头", requestHeaders(request));
        appendBody(detail, "请求体", requestBody(request));
        return detail.toString();
    }

    private static Object findRequest(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String name = arg.getClass().getName();
            if (name.equals("okhttp3.Request") || name.equals("com.android.okhttp.Request")
                    || name.equals("com.squareup.okhttp.Request")) {
                return arg;
            }
        }
        return null;
    }

    private static String requestUrl(Object request) {
        try {
            Object url = call(request, "url");
            return url == null ? "" : String.valueOf(url);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String requestMethod(Object request) {
        try {
            return string(call(request, "method"));
        } catch (Throwable e) {
            return "GET";
        }
    }

    private static String requestHeaders(Object request) {
        try {
            return headers(call(request, "headers"));
        } catch (Throwable e) {
            return "";
        }
    }

    private static String responseHeaders(Object response) {
        try {
            return headers(call(response, "headers"));
        } catch (Throwable e) {
            return "";
        }
    }

    private static String requestBody(Object request) {
        try {
            Object body = call(request, "body");
            return bodyString(body);
        } catch (Throwable e) {
            return "[unreadable request body]";
        }
    }

    private static String responseBody(Object response) {
        try {
            Object body = call(response, "body");
            if (body == null) {
                return "";
            }
            Object contentType = call(body, "contentType");
            String type = contentType == null ? "" : String.valueOf(contentType);
            if (type.startsWith("image/") || type.startsWith("video/")
                    || type.startsWith("audio/") || type.startsWith("font/")
                    || type.startsWith("application/zip") || type.startsWith("application/pdf")) {
                return "[" + type + " body omitted]";
            }
            Object contentLength = call(body, "contentLength");
            long length = contentLength instanceof Number ? ((Number) contentLength).longValue() : -1L;
            Object peek = callWith(response, "peekBody", new Class<?>[]{long.class},
                    Math.min(MAX_BODY, length > 0 ? length : MAX_BODY));
            if (peek != null) {
                String value = string(call(peek, "string"));
                return cut(value, MAX_BODY);
            }
            if (length > 0) {
                return "[响应体 " + length + " bytes，未展开]";
            }
            return "";
        } catch (Throwable e) {
            return "[unreadable response body]";
        }
    }

    private static String bodyString(Object body) {
        if (body == null) {
            return "";
        }
        try {
            Object oneShot = call(body, "isOneShot");
            if (Boolean.TRUE.equals(oneShot)) {
                return "[one-shot body omitted]";
            }
            Object duplex = call(body, "isDuplex");
            if (Boolean.TRUE.equals(duplex)) {
                return "[duplex body omitted]";
            }
            Object contentType = call(body, "contentType");
            String type = contentType == null ? "" : String.valueOf(contentType);
            if (type.startsWith("multipart/") || type.startsWith("image/")
                    || type.startsWith("application/octet-stream")
                    || type.startsWith("application/zip")) {
                return "[" + type + " body omitted]";
            }
            Object lengthValue = call(body, "contentLength");
            long length = lengthValue instanceof Number ? ((Number) lengthValue).longValue() : -1L;
            if (length > 512_000L) {
                return "[body " + length + " bytes omitted]";
            }
            ClassLoader cl = body.getClass().getClassLoader();
            Class<?> bufferClass = XposedHelpers.findClassIfExists("okio.Buffer", cl);
            if (bufferClass == null) {
                bufferClass = XposedHelpers.findClassIfExists("okio.Buffer",
                        OkHttpHooks.class.getClassLoader());
            }
            if (bufferClass != null) {
                Object buffer = XposedHelpers.newInstance(bufferClass);
                call(body, "writeTo", buffer);
                Object clone = call(buffer, "clone");
                String text = string(call(clone, "readUtf8"));
                return cut(text, MAX_BODY);
            }
        } catch (Throwable ignored) {
        }
        try {
            String fallback = String.valueOf(body);
            return cut(fallback, 20_000);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String headers(Object headers) {
        if (headers == null) {
            return "";
        }
        try {
            int size = intValue(call(headers, "size"), 0);
            StringBuilder sb = new StringBuilder();
            int count = Math.min(size, 100);
            for (int i = 0; i < count; i++) {
                Object name = callWith(headers, "name", new Class<?>[]{int.class}, i);
                Object value = callWith(headers, "value", new Class<?>[]{int.class}, i);
                sb.append(name).append(": ").append(value).append('\n');
            }
            if (size > count) {
                sb.append("... ").append(size - count).append(" more headers\n");
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static void appendBlock(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append("--- ").append(name).append(" ---\n").append(value).append('\n');
    }

    private static void appendBody(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append("--- ").append(name).append(" ---\n").append(value).append('\n');
    }

    private static Object call(Object target, String method) {
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object call(Object target, String method, Object... args) {
        try {
            return XposedHelpers.callMethod(target, method, args);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object callWith(Object target, String method, Class<?>[] types,
                                   Object... args) {
        try {
            return XposedHelpers.callMethod(target, method, types, args);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n…[truncated]";
    }

    private static void hookFallback(ClassLoader classLoader, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            return;
        }
        if (!FALLBACK_HOOKED.add(clazz)) {
            return;
        }
        boolean installed = false;
        try {
            XposedBridge.hookAllMethods(clazz, "newCall", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!HookPrefs.nativeHttp() || param.args.length == 0) {
                        return;
                    }
                    if (param.thisObject != null
                            && CHAIN_LOADERS.contains(param.thisObject.getClass().getClassLoader())) {
                        return;
                    }
                    Object request = param.args[0];
                    String url = requestUrl(request);
                    if (!UrlFilter.interesting(url)) {
                        return;
                    }
                    HookLog.log("HTTP-REQ", "HTTP " + requestMethod(request) + " "
                                    + UrlFilter.shortForTitle(url),
                            requestDetail(request, url));
                }
            });
            installed = true;
            HookLog.logcat("OkHttp newCall fallback hooked: " + className);
        } catch (Throwable e) {
            reportOnce(className + ":newCall", "fallback hook failed: " + e);
        }
        if (!installed) {
            FALLBACK_HOOKED.remove(clazz);
        }
    }

    private static void reportOnce(String key, String message) {
        if (REPORTED.put(key, Boolean.TRUE) == null) {
            HookLog.logcat(message);
        }
    }
}
