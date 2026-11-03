package com.qoder.cmcchook;

import android.os.Build;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 捕获目标 App 的未处理异常，把线程、进程和堆栈写进日志。
 * 只做记录，随后仍交给原来的 handler，不改变 App 的崩溃行为。
 */
final class CrashHooks {
    private static final Set<Integer> SEEN = Collections.newSetFromMap(
            new ConcurrentHashMap<Integer, Boolean>());
    private static volatile boolean installed;

    private CrashHooks() {
    }

    static void install() {
        if (installed) {
            return;
        }
        installed = true;

        try {
            XposedBridge.hookAllMethods(Thread.class,
                    "setDefaultUncaughtExceptionHandler", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0) {
                                return;
                            }
                            Object handler = param.args[0];
                            if (handler instanceof Thread.UncaughtExceptionHandler
                                    && !(handler instanceof SafeHandler)) {
                                param.args[0] = new SafeHandler(
                                        (Thread.UncaughtExceptionHandler) handler);
                            }
                        }
                    });
            HookLog.logcat("crash handler watcher installed");
        } catch (Throwable e) {
            HookLog.logcat("setDefaultUncaughtExceptionHandler hook failed: " + e);
        }

        try {
            XposedBridge.hookAllMethods(ThreadGroup.class, "uncaughtException",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2) {
                                return;
                            }
                            if (!(param.args[1] instanceof Throwable)) {
                                return;
                            }
                            Thread thread = param.args[0] instanceof Thread
                                    ? (Thread) param.args[0] : Thread.currentThread();
                            logCrash(thread, (Throwable) param.args[1], "thread-group");
                        }
                    });
            HookLog.logcat("ThreadGroup.uncaughtException watcher installed");
        } catch (Throwable e) {
            HookLog.logcat("ThreadGroup hook failed: " + e);
        }

        ensureDefaultHandler();
    }

    /** App 可能自己设置默认 handler，这里再包一层，确保启动早期也能捕获。 */
    static void ensureDefaultHandler() {
        try {
            Thread.UncaughtExceptionHandler current =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (current instanceof SafeHandler) {
                return;
            }
            Thread.setDefaultUncaughtExceptionHandler(new SafeHandler(current));
        } catch (Throwable e) {
            HookLog.logcat("install default crash handler failed: " + e);
        }
    }

    private static void logCrash(Thread thread, Throwable throwable, String source) {
        if (throwable == null) {
            return;
        }
        int key = System.identityHashCode(throwable);
        if (!SEEN.add(key)) {
            return;
        }
        if (SEEN.size() > 30) {
            SEEN.clear();
        }
        try {
            StringBuilder detail = new StringBuilder();
            detail.append("来源: ").append(source).append('\n');
            detail.append("进程: ").append(HookLog.processName()).append('\n');
            if (thread != null) {
                detail.append("线程: ").append(thread.getName())
                        .append(" (id=").append(thread.getId()).append(")\n");
            }
            detail.append("设备: ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append('\n');
            detail.append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            detail.append("模块: ").append(HookLog.MODULE_VERSION).append('\n');
            detail.append("--- 堆栈 ---\n");
            detail.append(Log.getStackTraceString(throwable));

            Throwable cause = throwable.getCause();
            int depth = 0;
            while (cause != null && depth < 5) {
                depth++;
                detail.append("--- cause ").append(depth).append(": ")
                        .append(cause.getClass().getName()).append(" ---\n");
                detail.append(Log.getStackTraceString(cause));
                cause = cause.getCause();
            }

            String message = throwable.getMessage();
            String summary = "未捕获异常 · " + (message == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getClass().getSimpleName() + ": " + message);
            HookLog.log("CRASH", summary, detail.toString());
            try {
                XposedBridge.log(throwable);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // 崩溃记录本身绝不能再抛异常
        }
    }

    private static final class SafeHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler delegate;

        SafeHandler(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            logCrash(thread, throwable, "default");
            if (delegate != null && delegate != this) {
                try {
                    delegate.uncaughtException(thread, throwable);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
