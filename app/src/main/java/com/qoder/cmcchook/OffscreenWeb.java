package com.qoder.cmcchook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedHelpers;

/**
 * 离屏 WebView。
 *
 * 用途只有一个：让「必须在页面里完成」的任务不用真的把界面打开。
 *
 * 签到页的浏览任务要靠页面自己上报埋点，鹅蛋页整套国密 + 瑞数只能在页面里跑。
 * 之前这两种都得让用户先打开活动页，很蠢。这里在目标进程里临时建一个不挂到窗口的
 * WebView，用 App 自己的内核（X5）加载页面 —— Cookie、UA、内核全是 App 的那一套，
 * 服务端看起来和正常浏览没有区别，但用户界面一点没动。
 *
 * 用完就 destroy，最多同时存在一个。
 */
final class OffscreenWeb {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Object current;
    private static volatile long createdAt;

    private OffscreenWeb() {
    }

    /** 建一个离屏 WebView 并开始加载，返回实例；失败返回 null。 */
    static Object open(final String url, final long timeoutMs) {
        return open(url, null, null, timeoutMs);
    }

    /**
     * 建一个离屏 WebView，并在 loadUrl **之前**注入一个 JS 接口对象。
     *
     * App 的 newSdkInterface 是它自己 addJavascriptInterface 注入的，只存在于它创建的
     * WebView 上。把捕获到的同一个对象重新注入到我们这个离屏 WebView，
     * 页面里的 leadeon.getUserInfo() 就又能拿到登录态 —— 冷启动、App 在后台、
     * 手上没有任何可用页面时，靠这条把 SSO 会话换出来。
     */
    static Object open(final String url, final Object bridge, final String bridgeName,
                       final long timeoutMs) {
        final Context ctx = HookLog.context();
        if (ctx == null || url == null || url.isEmpty()) {
            return null;
        }
        final Object[] holder = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    Object webView = createWebView(ctx);
                    if (webView == null) {
                        return;
                    }
                    try {
                        Object settings = XposedHelpers.callMethod(webView, "getSettings");
                        if (settings != null) {
                            XposedHelpers.callMethod(settings, "setJavaScriptEnabled", true);
                            XposedHelpers.callMethod(settings, "setDomStorageEnabled", true);
                            XposedHelpers.callMethod(settings, "setDatabaseEnabled", true);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        XposedHelpers.callMethod(webView, "setWebViewClient", new WebViewClient());
                    } catch (Throwable ignored) {
                    }
                    if (bridge != null && bridgeName != null && !bridgeName.isEmpty()) {
                        try {
                            XposedHelpers.callMethod(webView, "addJavascriptInterface", bridge,
                                    bridgeName);
                            HookLog.logcat("offscreen bridge injected: " + bridgeName);
                        } catch (Throwable e) {
                            HookLog.logcat("offscreen bridge inject failed: " + e);
                        }
                    }
                    XposedHelpers.callMethod(webView, "loadUrl", url);
                    holder[0] = webView;
                    HookLog.logcat("offscreen webview opened: " + url);
                } catch (Throwable e) {
                    HookLog.logcat("offscreen webview failed: " + e);
                } finally {
                    latch.countDown();
                }
            }
        };
        runOnMain(task, latch, timeoutMs);
        current = holder[0];
        createdAt = System.currentTimeMillis();
        return current;
    }

    /**
     * 优先用 App 自己的 X5 内核，Cookie 和 UA 才是同一套；
     * 拿不到再退回系统 WebView。
     */
    private static Object createWebView(Context ctx) {
        ClassLoader cl = ctx.getClassLoader();
        String[] candidates = {
                "com.tencent.smtt.sdk.WebView",
                "com.uc.webview.export.WebView",
        };
        for (String name : candidates) {
            try {
                Class<?> clazz = XposedHelpers.findClass(name, cl);
                return clazz.getConstructor(Context.class).newInstance(ctx);
            } catch (Throwable e) {
                HookLog.logcat("offscreen " + name + " unavailable: " + e);
            }
        }
        try {
            return new WebView(ctx);
        } catch (Throwable e) {
            HookLog.logcat("offscreen system webview failed: " + e);
            return null;
        }
    }

    static void close() {
        final Object webView = current;
        current = null;
        if (webView == null) {
            return;
        }
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(webView, "stopLoading");
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.callMethod(webView, "destroy");
                } catch (Throwable ignored) {
                }
            }
        }, null, 0);
    }

    /** 当前离屏页是否还能用（20 分钟内）。 */
    static Object current() {
        if (current == null || System.currentTimeMillis() - createdAt > 20 * 60_000L) {
            return null;
        }
        return current;
    }

    static void runOnMain(Runnable task, CountDownLatch latch, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        MAIN.post(task);
        if (latch != null) {
            try {
                latch.await(timeoutMs <= 0 ? 6_000L : timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
