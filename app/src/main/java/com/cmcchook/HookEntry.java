package com.cmcchook;

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
        // 启动计时的起点。放在这里而不是拦到 onResume 时才算：
        // 冷启动那几秒正是要等的部分。
        AppReady.markProcessStart();

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
     * 进程内兜底定时：每 30 分钟看一次，有没有今天还没跑的活动。
     *
     * 这条只在目标进程还活着、没被系统冻结时有效。Android 11+ 的 cgroup freezer
     * 会把后台进程的 Handler 定时器整个停掉，所以真正的主路径是模块进程里的
     * {@link TaskAlarm}（AlarmManager 唤醒 + 解冻 + PING 广播）。两条一起用：
     * 进程活着时这里最快，被冻住时那边兜底。
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
                            if (pending.isEmpty()) {
                                HookLog.logcat("scheduled check: nothing pending today");
                            } else if (!HookPrefs.autoTrigger()) {
                                // 手动模式：定时检查只做判断，绝不自动跑。
                                // 任务只在用户点「执行任务」时执行。
                                HookLog.logcat("scheduled check: auto trigger off (manual), skip " + pending);
                            } else if (hour < 7 || hour >= 23) {
                                HookLog.logcat("scheduled check: outside 7-23, skip " + pending);
                            } else if (TaskRunner.tooSoon(ctx)) {
                                HookLog.logcat("scheduled check: too soon, skip " + pending);
                            } else {
                                HookLog.logcat("scheduled auto task: " + pending);
                                TaskRunner.markAutoRun(ctx);
                                TaskRunner.executeAuto(pending, "进程内定时");
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
