package com.qoder.cmcchook;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中国移动 App 使用了加固壳，OkHttp / X5 WebView 等业务类通常要等壳初始化后
 * 才真正加载。这里不再 hook ClassLoader.loadClass / findClass（这类全局 hook
 * 很容易和加固壳冲突导致闪退），改为后台低频探测：类一旦出现再补装 Hook。
 */
final class ClassLoaderHooks {
    private static final long TOTAL_MS = 5L * 60L * 1000L;
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
        long start = System.currentTimeMillis();
        boolean classicDone = false;
        boolean okhttpDone = false;
        boolean x5Done = false;
        boolean fallbackTried = false;
        int round = 0;

        while (System.currentTimeMillis() - start < TOTAL_MS) {
            round++;
            if (!classicDone) {
                try {
                    WebViewHooks.installClassic(appLoader);
                    classicDone = true;
                    HookLog.logcat("classic WebView hooks installed");
                } catch (Throwable e) {
                    HookLog.logcat("classic WebView probe failed: " + e);
                }
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

            if (!x5Done) {
                try {
                    x5Done = WebViewHooks.installX5(appLoader);
                } catch (Throwable e) {
                    HookLog.logcat("X5 probe failed: " + e);
                }
            }

            if (classicDone && okhttpDone && x5Done) {
                break;
            }

            long elapsed = System.currentTimeMillis() - start;
            try {
                Thread.sleep(elapsed < FAST_PHASE_MS ? 800L : 3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        String detail = "探测轮次: " + round
                + "\nClassic WebView: " + done(classicDone)
                + "\nOkHttp: " + done(okhttpDone)
                + "\nX5 WebView: " + done(x5Done);
        HookLog.log("HOOK", "晚加载类探测结束", detail);
        HookLog.logcat("late probe finished: okhttp=" + okhttpDone + " x5=" + x5Done);
    }

    private static String done(boolean value) {
        return value ? "已安装" : "未找到（可能该版本不使用）";
    }
}
