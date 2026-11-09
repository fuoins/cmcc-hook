package com.cmcchook;

import de.robv.android.xposed.XSharedPreferences;

final class HookPrefs {
    private static final String FILE = "settings";
    private static volatile XSharedPreferences prefs;
    private static volatile long lastReload;
    private static volatile boolean h5 = true;
    private static volatile boolean nativeHttp = true;
    private static volatile boolean ui = true;
    private static volatile boolean storage = true;
    private static volatile boolean only10086 = false;
    private static volatile boolean secondary = false;
    private static volatile boolean h5Noise = true;
    private static volatile boolean verboseRaw = true;
    private static volatile boolean apiOnly = true;
    private static volatile boolean autoSign = true;
    private static volatile boolean pageSign = true;
    private static volatile boolean pageEgg = true;
    private static volatile boolean pageGoose = true;
    private static volatile boolean autoTrigger;

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

    /** 只记录业务接口：埋点、广告、静态资源一律不写日志。 */
    static boolean apiOnly() {
        reload(false);
        return apiOnly;
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

    /**
     * 是否允许「自动触发」执行任务。
     *
     * <p>默认关：回前台自动跑、进活动页触发、定时自检跑任务三个自动源全部
     * 停用，任务只在用户手动点「执行任务」/「过任务」时跑。逐个击破阶段
     * 需要这个：每个活动单独验证，不让自动触发打乱节奏。
     */
    static boolean autoTrigger() {
        reload(false);
        return autoTrigger;
    }

    private static volatile long lastFlagAt;

    /**
     * 读一个任意布尔开关。
     *
     * <p>用 XSharedPreferences 而不是当前的 Context：福利页的开关写在模块进程，
     * 任务是跑在中国移动进程里的，两边不是同一个 data 目录，读本地 prefs 永远是默认值。
     */
    static boolean flag(String key, boolean def) {
        if (key == null || key.isEmpty()) {
            return def;
        }
        if (prefs == null) {
            reload(true);
        }
        XSharedPreferences p = prefs;
        if (p == null) {
            return def;
        }
        long now = System.currentTimeMillis();
        if (now - lastFlagAt > 500L) {
            lastFlagAt = now;
            try {
                p.reload();
            } catch (Throwable ignored) {
            }
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    /**
     * 登录态失效日（yyyy-MM-dd，空串表示没失效）。
     *
     * <p>目标进程检测到页面落在 SSO 登录页时发广播让模块进程写这个字段，
     * 这里用 XSharedPreferences 直接读文件——目标进程和模块进程不是同一个
     * data 目录，读本地 prefs 永远是默认值。
     */
    static String loginLostDay() {
        if (prefs == null) {
            reload(true);
        }
        XSharedPreferences p = prefs;
        if (p == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        if (now - lastFlagAt > 500L) {
            lastFlagAt = now;
            try {
                p.reload();
            } catch (Throwable ignored) {
            }
        }
        try {
            return p.getString("login_lost_day", "");
        } catch (Throwable e) {
            return "";
        }
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
            only10086 = p.getBoolean("filter_10086", false);
            secondary = p.getBoolean("hook_secondary", false);
            h5Noise = p.getBoolean("filter_h5_noise", true);
            verboseRaw = p.getBoolean("verbose_raw", true);
            apiOnly = p.getBoolean("api_only", true);
            autoSign = p.getBoolean("auto_sign", true);
            pageSign = p.getBoolean("auto_page_sign", true);
            pageEgg = p.getBoolean("auto_page_egg", true);
            pageGoose = p.getBoolean("auto_page_goose", true);
            autoTrigger = p.getBoolean("auto_trigger", false);
        } catch (Throwable e) {
            HookLog.logcat("reload prefs failed: " + e);
        }
    }
}
