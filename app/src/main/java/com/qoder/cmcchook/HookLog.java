package com.qoder.cmcchook;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

final class HookLog {
    static final String MODULE_PACKAGE = "com.qoder.cmcchook";
    static final String TARGET_PACKAGE = "com.greenpoint.android.mc10086.activity";
    static final String MODULE_VERSION = "2.3";
    static final String ACTION_LOG = "com.qoder.cmcchook.action.LOG";
    static final String ACTION_HEARTBEAT = "com.qoder.cmcchook.action.HEARTBEAT";
    private static final String RECEIVER = "com.qoder.cmcchook.LogReceiver";
    private static final String TAG = "CmccHook";
    private static final int MAX_DETAIL_SHORT = 8_192;
    private static final int MAX_DETAIL_FULL = 160_000;
    private static final int MAX_SUMMARY = 600;
    private static final long HEARTBEAT_INTERVAL_MS = 45_000L;

    private static volatile Context application;
    private static volatile String processName = "";
    private static volatile String targetVersion = "";
    private static volatile long lastHeartbeat;
    private static volatile boolean logcatAvailable = true;

    private HookLog() {
    }

    static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        processName = lpparam.processName == null ? "" : lpparam.processName;
        logcat("module loaded process=" + processName);
    }

    static void setApplication(Application app) {
        if (app != null) {
            application = app;
            if (processName.isEmpty()) {
                processName = currentProcessName(app);
            }
            targetVersion = readTargetVersion(app);
            heartbeat();
        }
    }

    static Context context() {
        Context ctx = application;
        if (ctx != null) {
            return ctx;
        }
        try {
            ctx = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method current = activityThread.getDeclaredMethod("currentApplication");
                current.setAccessible(true);
                ctx = (Context) current.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            application = ctx;
        }
        return ctx;
    }

    static void log(String tag, String summary, String detail) {
        Context ctx = context();
        if (ctx == null) {
            logcat("[" + tag + "] " + summary);
            return;
        }
        if (targetVersion.isEmpty()) {
            targetVersion = readTargetVersion(ctx);
        }
        try {
            Intent intent = new Intent(ACTION_LOG);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER));
            intent.setPackage(MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("ts", System.currentTimeMillis());
            intent.putExtra("tag", cut(tag, 40));
            intent.putExtra("summary", cut(summary, MAX_SUMMARY));
            int maxDetail = HookPrefs.verboseRaw() ? MAX_DETAIL_FULL : MAX_DETAIL_SHORT;
            intent.putExtra("detail", cut(detail, maxDetail));
            intent.putExtra("process", processName);
            intent.putExtra("module_version", MODULE_VERSION);
            intent.putExtra("target_version", targetVersion);
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            logcat("broadcast failed: " + e);
            XposedBridge.log(e);
        }
        heartbeatIfDue();
    }

    static void error(String summary, Throwable throwable) {
        String detail = throwable == null ? "" : Log.getStackTraceString(throwable);
        log("ERROR", summary, detail);
    }

    static String processName() {
        return processName;
    }

    static void heartbeatIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeat = now;
            heartbeat();
        }
    }

    static void heartbeat() {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        lastHeartbeat = System.currentTimeMillis();
        try {
            Intent intent = new Intent(ACTION_HEARTBEAT);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER));
            intent.setPackage(MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("process", processName);
            intent.putExtra("module_version", MODULE_VERSION);
            intent.putExtra("target_version", targetVersion);
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            logcat("heartbeat failed: " + e);
        }
    }

    static void logcat(String message) {
        if (!logcatAvailable) {
            return;
        }
        try {
            Log.i(TAG, message == null ? "" : message);
        } catch (Throwable e) {
            logcatAvailable = false;
        }
    }

    private static String readTargetVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(TARGET_PACKAGE, 0);
            String code = Build.VERSION.SDK_INT >= 28
                    ? String.valueOf(info.getLongVersionCode())
                    : String.valueOf(info.versionCode);
            return info.versionName + " (" + code + ")";
        } catch (Throwable e) {
            return "";
        }
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = Application.getProcessName();
                if (name != null) {
                    return name;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentProcessName");
            method.setAccessible(true);
            Object name = method.invoke(null);
            if (name != null) {
                return String.valueOf(name);
            }
        } catch (Throwable ignored) {
        }
        return context.getPackageName();
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "\n…[truncated]";
    }
}
