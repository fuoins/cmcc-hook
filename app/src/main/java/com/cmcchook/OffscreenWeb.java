package com.cmcchook;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

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
    /** v3.81：离屏页挂载用的容器，销毁时整块摘掉。 */
    private static volatile FrameLayout holderRef;

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
                            // 落地页会按 UA 分流：没有 leadeon 标记就当成外部浏览器，
                            // 给一个「请在客户端打开」的引导页。浏览任务要算完成，
                            // 必须让服务端看到的是 App 自己的 UA。
                            String ua = WebPage.appUserAgent();
                            if (ua != null && ua.length() > 20) {
                                XposedHelpers.callMethod(settings, "setUserAgentString", ua);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (bridge == null) {
                            Object appBridge = SsoLogin.bridgeObject();
                            String appName = SsoLogin.bridgeNameOf();
                            if (appBridge != null && appName != null && !appName.isEmpty()) {
                                XposedHelpers.callMethod(webView, "addJavascriptInterface",
                                        appBridge, appName);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object client;
                        ClassLoader cl = ctx.getClassLoader();
                        Class<?> clientCls = null;
                        if (webView.getClass().getName().startsWith("com.tencent.smtt.")) {
                            clientCls = XposedHelpers.findClassIfExists(
                                    "com.tencent.smtt.sdk.WebViewClient", cl);
                        }
                        if (clientCls != null) {
                            // X5 的 setWebViewClient 只认自己的类型，塞系统 client 会抛异常，
                            // 之前被 catch 吞掉，离屏页其实一个 client 都没有 ——
                            // onPageStarted 不回调，垫片也就没机会在页面脚本前注入。
                            try {
                                client = clientCls.getConstructor().newInstance();
                            } catch (Throwable ignored) {
                                client = XposedHelpers.newInstance(clientCls);
                            }
                        } else {
                            client = new WebViewClient();
                        }
                        XposedHelpers.callMethod(webView, "setWebViewClient", client);
                    } catch (Throwable e) {
                        HookLog.logcat("offscreen client failed: " + e);
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
                    // current 必须在 loadUrl 之前就位：onPageStarted 可能在 loadUrl
                    // 返回前就回调，晚一步垫片就赶不上页面脚本了。
                    current = webView;
                    createdAt = System.currentTimeMillis();
                    XposedHelpers.callMethod(webView, "loadUrl", url);
                    holder[0] = webView;
                    HookLog.logcat("offscreen webview opened: " + url
                            + " · " + webView.getClass().getName());
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
    static Object createWebView(Context ctx) {
        ClassLoader cl = ctx.getClassLoader();
        String[] candidates = {
                "com.tencent.smtt.sdk.WebView",
                "com.uc.webview.export.WebView",
        };
        Object created = null;
        for (String name : candidates) {
            try {
                Class<?> clazz = XposedHelpers.findClass(name, cl);
                created = clazz.getConstructor(Context.class).newInstance(ctx);
                break;
            } catch (Throwable e) {
                HookLog.logcat("offscreen " + name + " unavailable: " + e);
            }
        }
        if (created == null) {
            try {
                created = new WebView(ctx);
            } catch (Throwable e) {
                HookLog.logcat("offscreen system webview failed: " + e);
                return null;
            }
        }
        // 离屏 WebView 不挂窗口，默认尺寸 0×0：viewport 塌缩时页面的响应式布局
        // 不会展开，body 高度是 0，滚动和控件采集全是空的。手动给它一个真实
        // 屏幕尺寸再加载，页面布局和行为才和用户屏幕上看到的一样。
        try {
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            int wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY);
            int hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
            XposedHelpers.callMethod(created, "measure", wSpec, hSpec);
            XposedHelpers.callMethod(created, "layout", 0, 0, w, h);
            HookLog.logcat("offscreen viewport set: " + w + "x" + h);
        } catch (Throwable e) {
            HookLog.logcat("offscreen viewport failed: " + e);
        }
        // v3.81：光有尺寸还不够，必须真正 attach 到窗口，Chromium 才会跑布局。
        attachToWindow(ctx, created);
        return created;
    }

    /**
     * v3.81：把离屏页真正挂到窗口上。
     *
     * <p>只 measure/layout 不够：WebView 没 attach 到窗口时，Chromium 不启动合成器，
     * Blink 也不跑布局 —— {@code innerText} 为空、{@code getBoundingClientRect()} 全 0、
     * Vue 的 v-show/display 判不出真实状态。14:19 日志里「探针全 0x0 / 模板堆 9 个」就是这个。
     *
     * <p>做法：往当前 Activity 的 decorView 里加一个与屏幕同尺寸的 FrameLayout，
     * 整体平移到屏幕左外侧（用户看不见、也拦不到触摸），WebView 在里面是真实尺寸。
     * 任何一步失败都只记一行 logcat，保留上面的 measure/layout 兜底。
     */
    static void attachToWindow(final Context ctx, final Object webView) {
        if (ctx == null || !(webView instanceof View)) {
            return;
        }
        final View view = (View) webView;
        CountDownLatch latch = new CountDownLatch(1);
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity act = ActivityHooks.current();
                    if (act == null || act.isFinishing()) {
                        HookLog.logcat("offscreen attach skipped: no activity");
                        return;
                    }
                    try {
                        if (act.isDestroyed()) {
                            HookLog.logcat("offscreen attach skipped: activity destroyed");
                            return;
                        }
                    } catch (Throwable ignored) {
                        // isDestroyed 是 API 17+，低版本没有这个方法，继续即可。
                    }
                    ViewGroup root = (ViewGroup) act.getWindow().getDecorView();
                    if (root == null) {
                        return;
                    }
                    int w = act.getResources().getDisplayMetrics().widthPixels;
                    int h = act.getResources().getDisplayMetrics().heightPixels;
                    if (w <= 0 || h <= 0) {
                        return;
                    }
                    FrameLayout holder = new FrameLayout(act);
                    holder.setClipChildren(false);
                    holder.setClipToPadding(false);
                    holder.addView(view, new FrameLayout.LayoutParams(w, h));
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    // 整块移到屏幕左外侧：仍参与 measure/layout，但用户看不到、碰不到。
                    lp.leftMargin = -w - 40;
                    root.addView(holder, lp);
                    holderRef = holder;
                    HookLog.logcat("offscreen attached: " + w + "x" + h
                            + " · " + act.getClass().getName());
                } catch (Throwable e) {
                    HookLog.logcat("offscreen attach failed: " + e);
                } finally {
                    latch.countDown();
                }
            }
        }, latch, 1500L);
    }

    /** v3.81：把离屏页从窗口上摘下来，避免 Activity 销毁后留下悬空的 WebView。 */
    static void detach(final Object webView) {
        final FrameLayout holder = holderRef;
        if (holder == null) {
            return;
        }
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null && holder.getChildCount() > 0
                            && holder.getChildAt(0) != webView) {
                        return;
                    }
                    ViewGroup parent = holder.getParent() instanceof ViewGroup
                            ? (ViewGroup) holder.getParent() : null;
                    if (parent != null) {
                        parent.removeView(holder);
                    }
                    holder.removeAllViews();
                    if (holderRef == holder) {
                        holderRef = null;
                    }
                    HookLog.logcat("offscreen detached");
                } catch (Throwable ignored) {
                }
            }
        }, null, 0L);
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

    /**
     * 登记当前任务页。
     *
     * <p>{@link #open} 会登记，但任务页走的是 {@link WebPage#open} 里的
     * {@code createWebView}，不经过 open —— 于是 current 一直是 null，
     * 垫片注入和回调转发都在第一行就 return 了，表现成「桥明明有，回调就是不回来」。
     * 建好 WebView 后必须在这里登记一次。
     */
    static void track(Object webView) {
        if (webView != null) {
            current = webView;
            createdAt = System.currentTimeMillis();
        }
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
