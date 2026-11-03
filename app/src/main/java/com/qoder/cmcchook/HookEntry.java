package com.qoder.cmcchook;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

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

        String process = lpparam.processName == null ? "" : lpparam.processName;
        boolean main = process.isEmpty() || lpparam.packageName.equals(process);
        // :tools 是加固壳的安全进程，注入它容易被判定为篡改，默认完全跳过。
        if (!main && isSecurityProcess(process)) {
            HookLog.logcat("security process skipped: " + process);
            return;
        }
        if (!main && !HookPrefs.secondaryProcesses()) {
            HookLog.logcat("secondary process skipped: " + process);
            return;
        }
        HookLog.logcat("initializing hooks in " + process);

        // 先装崩溃捕获，后续任何 hook 出问题都能留下堆栈。
        install("Crash", CrashHooks::install);
        install("Activity", () -> ActivityHooks.install(lpparam.classLoader));
        install("WebView", () -> WebViewHooks.install(lpparam.classLoader));
        // 加固壳晚加载的类由 ClassLoaderHooks 的后台探测补装。
        install("LateProbe", () -> ClassLoaderHooks.install(lpparam.classLoader));

        scheduleHeartbeat();
        HookLog.logcat("hooks installed in " + process);
    }

    private static boolean isSecurityProcess(String process) {
        String lower = process.toLowerCase(Locale.ROOT);
        return lower.endsWith(":tools") || lower.endsWith(":security") || lower.endsWith(":sec");
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
