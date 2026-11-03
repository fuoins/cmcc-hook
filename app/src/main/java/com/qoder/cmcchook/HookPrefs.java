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
        } catch (Throwable e) {
            HookLog.logcat("reload prefs failed: " + e);
        }
    }
}
