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
        // 升级弹窗的拦截放在 ClassLoaderHooks 的晚加载探测里装：
        // 加固壳没初始化完时，升级 SDK 的类还不存在，这里直接找必然失败。
        install("Activity", () -> ActivityHooks.install(lpparam.classLoader));
        install("WebView", () -> WebViewHooks.install(lpparam.classLoader));
        // 加固壳晚加载的类由 ClassLoaderHooks 的后台探测补装。
        install("LateProbe", () -> ClassLoaderHooks.install(lpparam.classLoader));

        scheduleHeartbeat();
        scheduleAutoTask();
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

    /**
     * 目标进程里每 30 分钟看一次：开关开着、今天还没跑的活动，就补跑。
     * 这样不用打开中国移动、更不用进活动页，只要进程还活着就会自己跑。
     */
    private static void scheduleAutoTask() {
        try {
            android.os.Looper main = Looper.getMainLooper();
            if (main == null) {
                return;
            }
            Handler handler = new Handler(main);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Context ctx = HookLog.context();
                        if (ctx != null) {
                            int hour = java.util.Calendar.getInstance()
                                    .get(java.util.Calendar.HOUR_OF_DAY);
                            String pending = TaskRunner.pendingAuto(ctx);
                            if (!pending.isEmpty() && hour >= 7 && hour < 23) {
                                HookLog.logcat("scheduled auto task: " + pending);
                                TaskRunner.executeAuto(pending, "定时");
                            }
                        }
                    } catch (Throwable e) {
                        HookLog.logcat("scheduled auto task failed: " + e);
                    }
                    handler.postDelayed(this, 30 * 60_000L);
                }
            }, 90_000L);
        } catch (Throwable e) {
            HookLog.logcat("auto task schedule failed: " + e);
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
