package com.cmcchook;

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
    static final String MODULE_PACKAGE = "com.cmcchook";
    static final String TARGET_PACKAGE = "com.greenpoint.android.mc10086.activity";
    /**
     * 模块版本。
     *
     * <p>以前这里是写死的字符串，改版时忘了同步，导出的日志永远显示旧版本
     * （用户装了 v2.5，日志却写 2.4，排查时白绕一圈）。改成直接读构建产物。
     */
    /**
     * 编译期内联，不走反射。
     *
     * <p>原先用 Class.forName 读 BuildConfig，模块类由宿主 ClassLoader 加载，
     * 这个类经常找不到，一路回退到硬编码的 "2.7" —— 于是装了 3.0 的日志里
     * 版本还是 2.7，排查时一直误判用户装的是哪一版。
     */
    static final String MODULE_VERSION = BuildConfig.VERSION_NAME;
    static final String ACTION_LOG = "com.cmcchook.action.LOG";
    static final String ACTION_HEARTBEAT = "com.cmcchook.action.HEARTBEAT";
    static final String ACTION_STATE = "com.cmcchook.action.STATE";
    /** v4.2.0：刷新 / 抢券的实时步骤，界面上滚动显示。 */
    static final String ACTION_PROGRESS = "com.cmcchook.action.PROGRESS";
    private static final String RECEIVER = "com.cmcchook.LogReceiver";
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
            intent.putExtra("uid", AccountScope.uid());
            intent.putExtra("phone", SsoLogin.maskedPhone());
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

    /**
     * 把单个活动的当日结果告诉模块进程。
     *
     * 任务跑在目标 App 进程，结果写在它的 SharedPreferences 里，模块界面根本读不到 ——
     * v2.3 首页的「今日」一直显示 ○ 就是这个原因。这里用广播同步一份回去。
     */
    static void state(String cmd, boolean ok, String summary, int attempts, String day) {
        Context ctx = context();
        if (ctx == null || cmd == null || cmd.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_STATE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER));
            intent.setPackage(MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("cmd", cmd);
            intent.putExtra("ok", ok);
            intent.putExtra("summary", cut(summary, 160));
            intent.putExtra("attempts", attempts);
            intent.putExtra("day", day);
            // v3.57：任务结果按账号记，界面收到就切到对应账号的命名空间。
            intent.putExtra("uid", AccountScope.uid());
            intent.putExtra("phone", SsoLogin.maskedPhone());
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            logcat("state broadcast failed: " + e);
        }
    }

    static String processName() {
        return processName;
    }

    /**
     * 把「现在做到哪一步」实时推到模块界面。
     *
     * <p>v4.2.0：用户点「刷新」后要等真页拉起 + 页面自己请求解密，十几秒里界面只有一句
     * 静态文案，看起来像卡死。这里每一步都发一条，模块界面把状态行换成最新一条，
     * 用户能看见「正在等页面解密（已等 6s）」而不是干等。
     *
     * @param scope {@code flow} / {@code coupon} / {@code grab}，界面按这个决定写到哪一行
     */
    static void progress(String scope, String text) {
        Context ctx = context();
        if (ctx == null) {
            logcat("[PROGRESS/" + scope + "] " + text);
            return;
        }
        try {
            Intent intent = new Intent(ACTION_PROGRESS);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER));
            intent.setPackage(MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("scope", scope == null ? "" : scope);
            intent.putExtra("text", cut(text, 200));
            intent.putExtra("ts", System.currentTimeMillis());
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            logcat("progress broadcast failed: " + e);
        }
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
        // v3.57：心跳是目标进程最稳定的节拍，顺带做账号切换检测（切号时清券进度）。
        AccountScope.check(ctx);
        try {
            Intent intent = new Intent(ACTION_HEARTBEAT);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, RECEIVER));
            intent.setPackage(MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra("process", processName);
            intent.putExtra("module_version", MODULE_VERSION);
            intent.putExtra("target_version", targetVersion);
            intent.putExtra("uid", AccountScope.uid());
            intent.putExtra("phone", SsoLogin.maskedPhone());
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
