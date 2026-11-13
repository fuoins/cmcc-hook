package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

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
    /** 自动关闭启动 / 首页广告（拦截首页弹窗接口 + 关掉广告类弹层），默认开。 */
    static final String KEY_AD_BLOCK = "ad_block";
    static final String KEY_KEEPALIVE = "keep_alive";
    static final String KEY_KEEP_GAP = "keep_gap";
    /** r8：快路径（页面上下文纯请求编排）总开关，默认开；失败自动回退旧链路。 */
    static final String KEY_FAST_PATH = "fast_path";
    /** r8：常驻采集旁路开关，默认关；用户手动做一遍活动时才打开录全量请求。 */
    static final String KEY_RAW_CAPTURE = "raw_capture";

    private AppPrefs() {
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences status(Context context) {
        return context.getSharedPreferences(STATUS, Context.MODE_PRIVATE);
    }

    /**
     * v4.32.0：顶部「提取」按钮用的 5 个脚本凭证。目标进程通过
     * {@link HookLog#ACTION_CRED} 广播送过来，LogReceiver 在模块进程落盘。
     *
     * <p>存 {@code status} 而不是 {@code settings}：settings 会被
     * {@link #setBool} chmod 成全局可读，cookie / token 不能放进那里。
     */
    static void setCredentials(Context context, String appToken, String phone,
                               String provinceCode, String cityCode, String cookie) {
        status(context).edit()
                .putString("cred_app_token", nullToEmpty(appToken))
                .putString("cred_phone", nullToEmpty(phone))
                .putString("cred_province_code", nullToEmpty(provinceCode))
                .putString("cred_city_code", nullToEmpty(cityCode))
                .putString("cred_cookie", nullToEmpty(cookie))
                .putLong("cred_at", System.currentTimeMillis())
                .apply();
    }

    /** 取一个凭证值；没有同步过时返回空串。key 为 app_token/phone/... 之一。 */
    static String credential(Context context, String key) {
        return status(context).getString("cred_" + key, "");
    }

    /** 凭证最近一次同步时间；从没同步过返回 0。 */
    static long credentialsAt(Context context) {
        return status(context).getLong("cred_at", 0L);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static boolean getBool(Context context, String key) {
        return settings(context).getBoolean(key, true);
    }

    static void setBool(Context context, String key, boolean value) {
        // 用 commit 而不是 apply：下面要立刻 chmod 这个文件，apply 是异步写，
        // 可能文件还没落盘就去改权限，改成同步写最省心（开关切换频率极低）。
        settings(context).edit().putBoolean(key, value).commit();
        worldReadable(context);
    }

    /**
     * r12：把 settings.xml 放开成「别人可读」。
     *
     * <p>目标进程（中国移动）读模块开关走的是 {@code XSharedPreferences}，
     * 而 Android 7 起 {@code MODE_WORLD_READABLE} 被禁，文件默认 600。
     * 读不到时默认关的开关（如常驻采集）会永远 false 且不报错 —— 这正是
     * 「开了也一条都录不到」的根因之一。这里直接 chmod 664 兜一层。
     *
     * <p>不依赖 Xposed 的类：模块进程里 {@code XSharedPreferences} 未必加载得到，
     * 用纯文件 API 更稳。失败就算了，调用方还有三态兜底。
     */
    private static void worldReadable(Context context) {
        try {
            java.io.File dir = context.getFilesDir().getParentFile();
            if (dir == null) {
                return;
            }
            java.io.File f = new java.io.File(new java.io.File(dir, "shared_prefs"),
                    SETTINGS + ".xml");
            if (f.exists()) {
                // 第二个参数 false = 不只是 owner，其他用户也可读。
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
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

    /**
     * r18：最近一次下发任务的时间戳。
     *
     * <p>任务在目标进程执行，模块进程读不到 TaskRunner 的运行标志，只能靠这个时间戳
     * 判断「刚点过一键、可能还在跑」。模块界面退到后台时的埋点、以及保活提示都读它。
     */
    static long getTaskSeq(Context context) {
        return settings(context).getLong("task_cmd_seq", 0L);
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
        setTaskState(context, cmd, day, ok, false, summary, attempts);
    }

    /** r15 P2：idle 单独存一份，不改 task_state_ 的 OK/FAIL 前缀。 */
    static void setTaskState(Context context, String cmd, String day,
                             boolean ok, boolean idle, String summary, int attempts) {
        setTaskState(context, cmd, day, ok, idle, ok, summary, attempts);
    }

    /**
     * v4.30.0：done 表示「当天成功过」，供文件头诊断摘要判「重跑失败但仍算达成」。
     *
     * <p>旧版摘要只看最近一次是 OK 还是 FAIL，于是「14:50 成功、15:29 重跑失败」
     * 会在抬头显示 ✗，文件尾的「今日」行却显示 OK，同一份日志两句话打架。
     */
    static void setTaskState(Context context, String cmd, String day,
                             boolean ok, boolean idle, boolean done,
                             String summary, int attempts) {
        long now = System.currentTimeMillis();
        status(context).edit()
                .putString(taskKey("task_state_", cmd, day, context),
                        (ok ? "OK " : "FAIL ") + (summary == null ? "" : summary))
                .putBoolean(taskKey("task_idle_", cmd, day, context), idle)
                .putBoolean(taskKey("task_done_", cmd, day, context), done || ok)
                .putInt(taskKey("task_attempt_", cmd, day, context), attempts)
                // v4.10.0-r7：模块侧记一份「本次结果时间」，供福利页条目展示。
                // 目标进程里那份 result_at_<cmd> 模块读不到，这里在收到 STATE 广播时
                // 自己落一个，误差只有广播延迟，且完全不碰任务执行链路。
                .putLong(taskKey("task_at_", cmd, day, context), now)
                .putString("last_task_line", labelOf(cmd) + " "
                        + (idle ? "无需操作" : (ok ? "完成" : "失败"))
                        + " · " + (summary == null ? "" : summary))
                .putLong("last_task_at", now)
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

    /** v4.10.0-r7：最近一次任务结果的时间（模块侧），福利页顶部结果条用。 */
    static long getLastTaskAt(Context context) {
        return status(context).getLong("last_task_at", 0L);
    }

    /** v4.10.0-r7：某活动今天最近一次结果的时间，0 表示今天没结果。 */
    static long getTaskAt(Context context, String cmd, String day) {
        return status(context).getLong(taskKey("task_at_", cmd, day, context), 0L);
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
        if ("soccer".equals(cmd)) {
            return "球赛狂欢季";
        }
        if ("tearbox".equals(cmd)) {
            return "拆一百元礼盒";
        }
        if ("saturday".equals(cmd)) {
            return "周六游戏日";
        }
        if ("video".equals(cmd)) {
            return "追剧";
        }
        if ("daily7".equals(cmd)) {
            return "7 日打卡";
        }
        if ("miao".equals(cmd)) {
            return "豆苗";
        }
        if ("recharge".equals(cmd)) {
            return "充值领券";
        }
        if ("wed".equals(cmd)) {
            return "周三充值日";
        }
        if ("seckill".equals(cmd)) {
            return "12 点抢券";
        }
        if ("lucky3".equals(cmd)) {
            return "三日签";
        }
        if ("weekly".equals(cmd)) {
            return "周周有好礼";
        }
        if ("signlottery".equals(cmd)) {
            return "签到抽奖赢好礼";
        }
        if ("quiz".equals(cmd)) {
            return "乡友问答";
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

    /** r15 P2：这次结果是「无需操作」吗。头部诊断摘要据此显示 ○。 */
    static boolean getTaskIdle(Context context, String cmd, String day) {
        return status(context).getBoolean(taskKey("task_idle_", cmd, day, context), false);
    }

    /** v4.30.0：当天是否成功过。头部诊断摘要据此把「重跑失败」仍画成 ✓。 */
    static boolean getTaskDone(Context context, String cmd, String day) {
        return status(context).getBoolean(taskKey("task_done_", cmd, day, context), false);
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
            // r15 P0：同账号 UID 漂移（或号码还没上报）。界面把今日状态存在
            // status 文件里、key 后缀是 @UID前12位，UID 一变整套 key 就换掉，
            // 19:11 跑完的 9 项到 19:29 全显示 ○。这里把当日状态接到新后缀。
            carryTaskScope(context, old, u, today());
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

    /**
     * r15 P0：UID 漂移后，把 {@code status} 文件里旧 {@code @UID} 后缀的当日 key
     * 复制到新后缀。任务侧的分片文件由 {@link AccountScope} 接续，这里补的是
     * 模块界面「今日」列表读的那一份；目标已有的值一律不覆盖。
     */
    private static void carryTaskScope(Context context, String fromUid, String toUid, String day) {
        String from = "@" + AccountScope.shortOf(fromUid);
        String to = "@" + AccountScope.shortOf(toUid);
        if (from.equals(to)) {
            return;
        }
        try {
            SharedPreferences p = status(context);
            Map<String, ?> all = p.getAll();
            SharedPreferences.Editor e = p.edit();
            boolean changed = false;
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                if (!k.contains(day) || !k.endsWith(from)) {
                    continue;
                }
                String nk = k.substring(0, k.length() - from.length()) + to;
                if (p.contains(nk)) {
                    continue;
                }
                Object v = en.getValue();
                if (v instanceof Boolean) {
                    e.putBoolean(nk, (Boolean) v);
                } else if (v instanceof Integer) {
                    e.putInt(nk, (Integer) v);
                } else if (v instanceof Long) {
                    e.putLong(nk, (Long) v);
                } else if (v instanceof Float) {
                    e.putFloat(nk, (Float) v);
                } else if (v instanceof String) {
                    e.putString(nk, (String) v);
                } else {
                    continue;
                }
                changed = true;
            }
            if (changed) {
                e.apply();
                LogStore.append(context, new LogEntry(System.currentTimeMillis(), "ACCOUNT",
                        "UID 漂移 · 今日状态已接续",
                        "旧后缀: " + from + "\n新后缀: " + to
                                + "\n界面「今日」列表不再因 UID 漂移清零。"));
            }
        } catch (Throwable ignored) {
        }
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
