package com.qoder.cmcchook;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !HookLog.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        HookLog.init(lpparam);
        HookPrefs.init();
        HookLog.logcat("initializing hooks in " + lpparam.processName);

        install("Activity", () -> ActivityHooks.install(lpparam.classLoader));
        install("WebView", () -> WebViewHooks.install(lpparam.classLoader));
        install("OkHttp", () -> OkHttpHooks.install(lpparam.classLoader));
        install("ClassLoader", ClassLoaderHooks::install);

        scheduleHeartbeat();
        HookLog.logcat("hooks installed");
    }

    private static void scheduleHeartbeat() {
        try {
            android.os.Looper main = Looper.getMainLooper();
            if (main == null) {
                return;
            }
            Handler handler = new Handler(main);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    HookLog.heartbeat();
                    handler.postDelayed(this, 60_000L);
                }
            }, 60_000L);
        } catch (Throwable e) {
            HookLog.logcat("heartbeat schedule failed: " + e);
        }
    }

    private static void install(String name, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            HookLog.logcat(name + " hooks failed: " + e);
            try {
                XposedBridge.log("CmccHook " + name + " hook failure");
                XposedBridge.log(e);
            } catch (Throwable ignored) {
            }
        }
    }
}
