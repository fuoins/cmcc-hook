package com.qoder.cmcchook;

import de.robv.android.xposed.XSharedPreferences;

final class HookPrefs {
    private static final String FILE = "settings";
    private static volatile XSharedPreferences prefs;
    private static volatile long lastReload;
    private static volatile boolean h5 = true;
    private static volatile boolean nativeHttp = true;
    private static volatile boolean ui = true;
    private static volatile boolean storage = true;
    private static volatile boolean only10086 = true;
    private static volatile boolean secondary = false;
    private static volatile boolean h5Noise = true;
    private static volatile boolean verboseRaw = false;
    private static volatile boolean autoSign = false;
    private static volatile boolean pageSign = true;
    private static volatile boolean pageEgg = true;
    private static volatile boolean pageGoose = true;

    private HookPrefs() {
    }

    static synchronized void init() {
        try {
            prefs = new XSharedPreferences(HookLog.MODULE_PACKAGE, FILE);
            prefs.reload();
        } catch (Throwable e) {
            prefs = null;
            HookLog.logcat("XSharedPreferences unavailable: " + e);
        }
        reload(true);
    }

    static boolean h5() {
        reload(false);
        return h5;
    }

    static boolean nativeHttp() {
        reload(false);
        return nativeHttp;
    }

    static boolean ui() {
        reload(false);
        return ui;
    }

    static boolean storage() {
        reload(false);
        return storage;
    }

    static boolean only10086() {
        reload(false);
        return only10086;
    }

    static boolean secondaryProcesses() {
        reload(false);
        return secondary;
    }

    static boolean h5NoiseFilter() {
        reload(false);
        return h5Noise;
    }

    static boolean verboseRaw() {
        reload(false);
        return verboseRaw;
    }

    static boolean autoSign() {
        reload(false);
        return autoSign;
    }

    /** 打开「签到有礼」页面后自动跑签到与领奖。 */
    static boolean pageSign() {
        reload(false);
        return pageSign;
    }

    /** 打开「天天砸金蛋」页面后自动做浏览任务并抽奖。 */
    static boolean pageEgg() {
        reload(false);
        return pageEgg;
    }

    /** 打开「收鹅蛋换好礼」页面后自动打卡、投喂、收蛋、领任务。 */
    static boolean pageGoose() {
        reload(false);
        return pageGoose;
    }

    private static void reload(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReload < 1500L) {
            return;
        }
        lastReload = now;
        XSharedPreferences p = prefs;
        if (p == null) {
            return;
        }
        try {
            p.reload();
            h5 = p.getBoolean("capture_h5", true);
            nativeHttp = p.getBoolean("capture_native", true);
            ui = p.getBoolean("capture_ui", true);
            storage = p.getBoolean("capture_storage", true);
            only10086 = p.getBoolean("filter_10086", true);
            secondary = p.getBoolean("hook_secondary", false);
            h5Noise = p.getBoolean("filter_h5_noise", true);
            verboseRaw = p.getBoolean("verbose_raw", false);
            autoSign = p.getBoolean("auto_sign", false);
            pageSign = p.getBoolean("auto_page_sign", true);
            pageEgg = p.getBoolean("auto_page_egg", true);
            pageGoose = p.getBoolean("auto_page_goose", true);
        } catch (Throwable e) {
            HookLog.logcat("reload prefs failed: " + e);
        }
    }
}
