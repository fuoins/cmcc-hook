package com.qoder.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String TARGET_PACKAGE = "com.greenpoint.android.mc10086.activity";
    static final String MODULE_PACKAGE = "com.qoder.cmcchook";
    static final String WELFARE_URL =
            "https://wx.10086.cn/website/businessPlatform/shop"
                    + "?pageid=f38143861a4a4a9fac920fdeecd119bb"
                    + "&channelId=P00000132578&yx=1586012001";

    private static final String SETTINGS = "settings";
    private static final String STATUS = "status";

    static final String KEY_CAPTURE_H5 = "capture_h5";
    static final String KEY_CAPTURE_NATIVE = "capture_native";
    static final String KEY_CAPTURE_UI = "capture_ui";
    static final String KEY_CAPTURE_STORAGE = "capture_storage";
    static final String KEY_FILTER_10086 = "filter_10086";
    static final String KEY_HOOK_SECONDARY = "hook_secondary";
    static final String KEY_FILTER_H5_NOISE = "filter_h5_noise";
    static final String KEY_VERBOSE_RAW = "verbose_raw";
    static final String KEY_AUTO_SIGN = "auto_sign";
    static final String KEY_PAGE_SIGN = "auto_page_sign";
    static final String KEY_PAGE_EGG = "auto_page_egg";
    static final String KEY_PAGE_GOOSE = "auto_page_goose";
    static final String KEY_API_ONLY = "api_only";
    static final String KEY_KEEPALIVE = "keep_alive";
    static final String KEY_KEEP_GAP = "keep_gap";

    private AppPrefs() {
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences status(Context context) {
        return context.getSharedPreferences(STATUS, Context.MODE_PRIVATE);
    }

    static boolean getBool(Context context, String key) {
        return settings(context).getBoolean(key, true);
    }

    static void setBool(Context context, String key, boolean value) {
        settings(context).edit().putBoolean(key, value).apply();
    }

    /**
     * 给目标进程下指令。模块和目标 App 不是同一个进程，Broadcast 在 App 被冻结时
     * 会丢，所以走 settings 文件 + 序号：目标进程 onResume 时比对序号，没执行过就跑。
     */
    static void triggerTask(Context context, String cmd) {
        settings(context).edit()
                .putString("task_cmd", cmd)
                .putLong("task_cmd_seq", System.currentTimeMillis())
                .apply();
    }

    static long getLastHeartbeat(Context context) {
        return status(context).getLong("last_heartbeat", 0L);
    }

    // ------------------------------------------------------- 今日任务状态
    // 任务跑在目标进程，结果在那边写 SharedPreferences，模块界面读不到。
    // 所以目标进程每跑完一个活动就发一条广播回来，这里按 (活动, 日期) 存一份。

    static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    static void setTaskState(Context context, String cmd, String day,
                             boolean ok, String summary, int attempts) {
        status(context).edit()
                .putString("task_state_" + cmd + "_" + day,
                        (ok ? "OK " : "FAIL ") + (summary == null ? "" : summary))
                .putInt("task_attempt_" + cmd + "_" + day, attempts)
                .apply();
    }

    /** 某个活动今天的最近结果；空串表示今天没跑过。 */
    static String getTaskState(Context context, String cmd, String day) {
        return status(context).getString("task_state_" + cmd + "_" + day, "");
    }

    static int getTaskAttempts(Context context, String cmd, String day) {
        return status(context).getInt("task_attempt_" + cmd + "_" + day, 0);
    }

    static long getLastTick(Context context) {
        return status(context).getLong("last_tick", 0L);
    }

    static void setLastTick(Context context, long value) {
        status(context).edit().putLong("last_tick", value).apply();
    }

    static String getLastTickResult(Context context) {
        return status(context).getString("last_tick_result", "");
    }

    static void setLastTickResult(Context context, String value) {
        status(context).edit().putString("last_tick_result", value == null ? "" : value).apply();
    }

    static String getLastProcess(Context context) {
        return status(context).getString("last_process", "");
    }

    static String getLastModuleVersion(Context context) {
        return status(context).getString("last_module_version", "");
    }

    static String getLastTargetVersion(Context context) {
        return status(context).getString("last_target_version", "");
    }

    static void setHeartbeat(Context context, long timestamp, String process,
                             String moduleVersion, String targetVersion) {
        status(context).edit()
                .putLong("last_heartbeat", timestamp)
                .putString("last_process", process == null ? "" : process)
                .putString("last_module_version", moduleVersion == null ? "" : moduleVersion)
                .putString("last_target_version", targetVersion == null ? "" : targetVersion)
                .apply();
    }
}
