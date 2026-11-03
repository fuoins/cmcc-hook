package com.qoder.cmcchook;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 中国移动 App 使用了加固壳，OkHttp / X5 WebView 等业务类通常在启动之后
 * 才由自定义 ClassLoader 动态加载。安装时直接 findClass 往往找不到这些类，
 * 因此这里监听类加载，在目标类第一次出现时补装对应的 Hook。
 */
final class ClassLoaderHooks {
    private static final int KIND_OKHTTP = 1;
    private static final int KIND_WEBVIEW = 2;

    private static final Map<String, Integer> WATCHED;

    static {
        Map<String, Integer> map = new HashMap<>();
        map.put("okhttp3.internal.http.RealInterceptorChain", KIND_OKHTTP);
        map.put("com.squareup.okhttp.internal.http.RealInterceptorChain", KIND_OKHTTP);
        map.put("com.android.okhttp.internal.http.RealInterceptorChain", KIND_OKHTTP);
        map.put("com.tencent.smtt.sdk.WebView", KIND_WEBVIEW);
        map.put("com.tencent.smtt.sdk.WebViewClient", KIND_WEBVIEW);
        map.put("com.tencent.smtt.sdk.WebChromeClient", KIND_WEBVIEW);
        map.put("com.tencent.smtt.sdk.CookieManager", KIND_WEBVIEW);
        WATCHED = Collections.unmodifiableMap(map);
    }

    /** 防止补装 Hook 时 findClass 再次触发 loadClass 造成递归。 */
    private static final ThreadLocal<Boolean> GUARD = new ThreadLocal<>();
    /** 已处理过的 类名@ClassLoader，避免同一个类重复补装。 */
    private static final Set<String> HANDLED = ConcurrentHashMap.newKeySet();

    private ClassLoaderHooks() {
    }

    static void install() {
        hookLoadClass();
        hookBaseDexFindClass();
    }

    private static void hookLoadClass() {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handle(param);
                }
            });
            HookLog.logcat("ClassLoader.loadClass watcher installed");
        } catch (Throwable e) {
            HookLog.logcat("ClassLoader.loadClass watcher failed: " + e);
        }
    }

    /**
     * 部分加固壳在自定义 ClassLoader 中直接走 BaseDexClassLoader.findClass，
     * 这里再补一层，保证动态加载的类也能被看到。
     */
    private static void hookBaseDexFindClass() {
        Class<?> baseDex = XposedHelpers.findClassIfExists(
                "dalvik.system.BaseDexClassLoader", null);
        if (baseDex == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(baseDex, "findClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handle(param);
                }
            });
            HookLog.logcat("BaseDexClassLoader.findClass watcher installed");
        } catch (Throwable e) {
            HookLog.logcat("BaseDexClassLoader.findClass watcher failed: " + e);
        }
    }

    private static void handle(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) {
            return;
        }
        Object nameArg = param.args[0];
        if (!(nameArg instanceof String)) {
            return;
        }
        String name = (String) nameArg;
        Integer kind = WATCHED.get(name);
        if (kind == null) {
            return;
        }
        Object result = param.getResult();
        if (!(result instanceof Class)) {
            return;
        }
        Class<?> clazz = (Class<?>) result;
        if (!name.equals(clazz.getName())) {
            return;
        }
        if (Boolean.TRUE.equals(GUARD.get())) {
            return;
        }
        ClassLoader loader = clazz.getClassLoader();
        if (loader == null) {
            return;
        }
        String key = name + "@" + System.identityHashCode(loader);
        if (!HANDLED.add(key)) {
            return;
        }
        GUARD.set(Boolean.TRUE);
        try {
            HookLog.logcat("late class detected: " + name);
            if (kind == KIND_OKHTTP) {
                OkHttpHooks.install(loader);
            } else {
                WebViewHooks.install(loader);
            }
        } catch (Throwable e) {
            HookLog.logcat("late install failed for " + name + ": " + e);
        } finally {
            GUARD.remove();
        }
    }
}
