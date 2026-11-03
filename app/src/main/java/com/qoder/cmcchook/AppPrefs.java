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

    static long getLastHeartbeat(Context context) {
        return status(context).getLong("last_heartbeat", 0L);
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
