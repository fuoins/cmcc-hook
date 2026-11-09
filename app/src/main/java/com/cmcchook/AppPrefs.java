package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String TARGET_PACKAGE = "com.greenpoint.android.mc10086.activity";
    static final String MODULE_PACKAGE = "com.cmcchook";
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
                .putString(taskKey("task_state_", cmd, day, context),
                        (ok ? "OK " : "FAIL ") + (summary == null ? "" : summary))
                .putInt(taskKey("task_attempt_", cmd, day, context), attempts)
                .putString("last_task_line", labelOf(cmd) + " " + (ok ? "完成" : "失败")
                        + " · " + (summary == null ? "" : summary))
                .putLong("last_task_at", System.currentTimeMillis())
                .apply();
    }

    /**
     * v3.57：今日任务状态的 key 带上账号后缀。
     *
     * <p>B 账号跑完一轮，首页全是 ✓；换回 A 账号打开还显示 ✓，用户会以为今天
     * 已经做过了。加上后缀之后，每个账号看自己的。
     *
     * <p>没识别到账号时保持旧 key，兼容升级前已经写下的记录。
     */
    private static String taskKey(String prefix, String cmd, String day, Context context) {
        String uid = getCurrentUid(context);
        String tail = uid.isEmpty() ? "" : "@" + AccountScope.shortOf(uid);
        return prefix + cmd + "_" + day + tail;
    }

    /** 最近一次跑完的活动，导出日志时放在诊断摘要里。 */
    static String getLastTaskLine(Context context) {
        return status(context).getString("last_task_line", "");
    }

    /** 日志导出范围（0 精简 / 1 只看任务 / 2 完整），记住上次选择。 */
    static int getExportMode(Context context) {
        return status(context).getInt("export_mode", 0);
    }

    static void setExportMode(Context context, int mode) {
        status(context).edit().putInt("export_mode", mode).apply();
    }

    private static String labelOf(String cmd) {
        if ("sign".equals(cmd)) {
            return "签到有礼";
        }
        if ("egg".equals(cmd)) {
            return "天天砸金蛋";
        }
        if ("goose".equals(cmd)) {
            return "收鹅蛋换好礼";
        }
        return cmd;
    }

    /** 某个活动今天的最近结果；空串表示今天没跑过。 */
    static String getTaskState(Context context, String cmd, String day) {
        return status(context).getString(taskKey("task_state_", cmd, day, context), "");
    }

    static int getTaskAttempts(Context context, String cmd, String day) {
        return status(context).getInt(taskKey("task_attempt_", cmd, day, context), 0);
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

    // ------------------------------------------------------------ 多账号
    // 目标进程从 Cookie 里认出 UID，随心跳/日志广播带过来；模块界面据此
    // 显示当前账号、切分任务状态、切号时清券清单。

    static final String KEY_CURRENT_UID = "current_uid";
    static final String KEY_CURRENT_PHONE = "current_phone";
    static final String KEY_ACCOUNT_FROM = "account_switch_from";

    /** 当前账号 UID；还没同步到时为空串。 */
    static String getCurrentUid(Context context) {
        try {
            String v = status(context).getString(KEY_CURRENT_UID, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    static String getCurrentPhone(Context context) {
        try {
            String v = status(context).getString(KEY_CURRENT_PHONE, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 目标进程报上来的账号。UID 变化时视为切换账号：
     * 清空券清单（两个账号的都清，切回来也不会看到过期券），并记一条日志。
     */
    static void setAccount(Context context, String uid, String phone) {
        String u = uid == null ? "" : uid.trim();
        if (u.isEmpty()) {
            return;
        }
        SharedPreferences p = status(context);
        String old = p.getString(KEY_CURRENT_UID, "");
        String oldPhone = p.getString(KEY_CURRENT_PHONE, "");
        String ph = phone == null ? "" : phone.trim();
        // 日志广播很密（每秒好几条），账号没变就别反复写盘。
        if (u.equals(old) && (ph.isEmpty() || ph.equals(oldPhone))) {
            return;
        }
        SharedPreferences.Editor e = p.edit()
                .putString(KEY_CURRENT_UID, u)
                .putLong("current_uid_at", System.currentTimeMillis());
        if (!ph.isEmpty()) {
            e.putString(KEY_CURRENT_PHONE, ph);
        }
        e.apply();
        if (old.isEmpty() || old.equals(u)) {
            return;
        }
        // v3.74：手机号没变就不是换号，只是服务端 UID 漂移。
        // v3.94：23:35 日志里一键跑到一半又报「198****6742 → mhrz68b1d081」，
        // 后半截是裸 UID —— 说明这一刻号码没上报(ph 为空)，旧保护要求 ph 非空，
        // 直接失效 → 清券清单 + 写一条假的「检测到账号切换」，把排查完全带偏。
        // 现在：号码读不到(ph 或 oldPhone 为空)、或号码相同，都不算换号；
        // 只有两边号码都识别到且不同，才认作真切换。
        // 取舍：真换号瞬间若号码尚未上报，本帧不清；等号码回灌且 UID 再漂移时
        // 仍会判到（prefsName 按 UID 分文件，数据隔离不依赖这条日志）。
        if (ph.isEmpty() || oldPhone.isEmpty() || ph.equals(oldPhone)) {
            return;
        }
        try {
            CouponStore.clearList(context);
            CouponStore.clearListForUid(context, old);
        } catch (Throwable ignored) {
        }
        p.edit().putString(KEY_ACCOUNT_FROM, old).apply();
        String fromLabel = oldPhone.isEmpty() ? AccountScope.shortOf(old) : oldPhone;
        String toLabel = ph.isEmpty() ? AccountScope.shortOf(u) : ph;
        LogStore.append(context, new LogEntry(System.currentTimeMillis(), "ACCOUNT",
                "检测到账号切换 · " + fromLabel + " → " + toLabel,
                "UID: " + AccountScope.shortOf(old) + " → " + AccountScope.shortOf(u)
                        + "\n两个账号的数据分开存：今日任务状态、券清单、验证码缓存、活动进度。"
                        + "\n券清单已清空，请重新扫描。"));
    }

    /** 界面显示的账号文案。 */
    static String accountLabel(Context context) {
        String phone = getCurrentPhone(context);
        String uid = getCurrentUid(context);
        if (uid.isEmpty()) {
            return "";
        }
        String id = AccountScope.shortOf(uid);
        // v3.70：手机号还没上报时别只显示一串 UID —— 用户会以为界面还挂着旧号。
        // 明确写「号码识别中」，配合日志里的 UID 变化就能判断切号到底成没成。
        return phone.isEmpty() ? "号码识别中 · " + id : phone + " · " + id;
    }

    /** 取一次「刚发生的账号切换」，取完即清；没有切换返回空串。 */
    static String consumeAccountSwitch(Context context) {
        SharedPreferences p = status(context);
        String from = p.getString(KEY_ACCOUNT_FROM, "");
        if (!from.isEmpty()) {
            p.edit().remove(KEY_ACCOUNT_FROM).apply();
        }
        return from;
    }
}
