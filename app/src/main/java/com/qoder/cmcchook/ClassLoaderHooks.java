package com.qoder.cmcchook;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中国移动 App 使用了加固壳，OkHttp / X5 WebView 等业务类通常要等壳初始化后
 * 才真正加载。这里不再 hook ClassLoader.loadClass / findClass（这类全局 hook
 * 很容易和加固壳冲突导致闪退），改为后台低频探测：类一旦出现再补装 Hook。
 */
final class ClassLoaderHooks {
    private static final long TOTAL_MS = 15L * 60L * 1000L;
    private static final long FAST_PHASE_MS = 60_000L;
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private ClassLoaderHooks() {
    }

    static void install(final ClassLoader appLoader) {
        if (appLoader == null || !STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                probe(appLoader);
            }
        }, "CmccHook-LateProbe");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void probe(ClassLoader appLoader) {
        // 给 Application.attachBaseContext 一点时间，保证日志能通过广播发出去。
        try {
            Thread.sleep(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long start = System.currentTimeMillis();
        boolean classicDone = false;
        boolean okhttpDone = false;
        boolean x5Done = false;
        boolean updateDone = false;
        boolean fallbackTried = false;
        boolean classicLogged = false;
        boolean okhttpLogged = false;
        boolean x5Logged = false;
        int round = 0;

        while (System.currentTimeMillis() - start < TOTAL_MS) {
            round++;
            // 升级弹窗优先：它一旦弹出来就会崩，而且是首页动画结束后立刻触发。
            if (!updateDone) {
                try {
                    updateDone = UpdateHooks.install(appLoader);
                } catch (Throwable e) {
                    HookLog.logcat("update hook probe failed: " + e);
                }
            }
            if (!classicDone) {
                try {
                    WebViewHooks.installClassic(appLoader);
                    classicDone = true;
                    HookLog.logcat("classic WebView hooks installed");
                } catch (Throwable e) {
                    HookLog.logcat("classic WebView probe failed: " + e);
                }
            }
            if (classicDone && !classicLogged) {
                classicLogged = true;
                HookLog.log("HOOK", "Classic WebView Hook 已安装",
                        "已接管 android.webkit.WebView 的构造、loadUrl、页面回调与 Cookie。");
            }

            if (!okhttpDone) {
                try {
                    okhttpDone = OkHttpHooks.install(appLoader, false);
                } catch (Throwable e) {
                    HookLog.logcat("OkHttp probe failed: " + e);
                }
                long elapsed = System.currentTimeMillis() - start;
                if (!okhttpDone && !fallbackTried && elapsed > 20_000L) {
                    fallbackTried = true;
                    try {
                        OkHttpHooks.installFallback(appLoader);
                    } catch (Throwable e) {
                        HookLog.logcat("OkHttp fallback probe failed: " + e);
                    }
                }
            }
            if (okhttpDone && !okhttpLogged) {
                okhttpLogged = true;
                HookLog.log("HOOK", "OkHttp Hook 已安装",
                        "已接管 okhttp3.internal.http.RealInterceptorChain.proceed，"
                                + "请求与响应会写入日志。");
            }

            if (!x5Done) {
                try {
                    x5Done = WebViewHooks.installX5(appLoader);
                } catch (Throwable e) {
                    HookLog.logcat("X5 probe failed: " + e);
                }
            }
            if (x5Done && !x5Logged) {
                x5Logged = true;
                HookLog.log("HOOK", "X5 WebView Hook 已安装",
                        "已接管 com.tencent.smtt.sdk.WebView，H5 请求与页面快照会写入日志。");
            }

            if (classicDone && okhttpDone && x5Done && updateDone) {
                break;
            }

            long elapsed = System.currentTimeMillis() - start;
            try {
                Thread.sleep(elapsed < FAST_PHASE_MS ? 800L : 5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        String detail = "探测轮次: " + round
                + "\nClassic WebView: " + done(classicDone)
                + "\nOkHttp: " + done(okhttpDone)
                + "\nX5 WebView: " + done(x5Done)
                + "\n升级弹窗拦截: " + done(updateDone);
        HookLog.log("HOOK", "晚加载类探测结束", detail);
        HookLog.logcat("late probe finished: okhttp=" + okhttpDone + " x5=" + x5Done + " update=" + updateDone);
    }

    private static String done(boolean value) {
        return value ? "已安装" : "未找到（可能该版本不使用）";
    }
}
