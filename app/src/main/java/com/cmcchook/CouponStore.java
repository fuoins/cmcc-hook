package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 卡券扫描结果 / 批量进度在模块进程里的落盘。
 *
 * <p>扫描跑在中国移动进程，结果靠广播回到模块；界面关掉再进来时广播早就没了，
 * 所以这里存一份。
 *
 * <p>v3.52：按用户要求，App 冷启动时必须清空上一次的券清单，「关闭软件重进后
 * 重新扫描」。旧清单是上一次页面状态，券可能已被用掉/过期/失效，直接展示会让
 * 人按旧勾选去批量使用，点上去必然失败（甚至点错券）。
 */
final class CouponStore {

    private static final String PREFS = "coupon";
    private static final String KEY_LIST = "list";
    private static final String KEY_AT = "at";
    private static final String KEY_COST = "cost";
    private static final String KEY_SCAN_START = "scan_start";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_PROGRESS_AT = "progress_at";

    private CouponStore() {
    }

    /**
     * v3.57：券清单按账号分开存。B 账号扫出来的券不能出现在 A 账号的界面上，
     * 否则换号后按旧清单点「使用」，点的是别人账号里的券。
     */
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AccountScope.prefsName(context, PREFS),
                Context.MODE_PRIVATE);
    }

    /** 清空券清单 + 扫描耗时 + 计时起点。冷启动时调用。 */
    static void clearList(Context context) {
        prefs(context).edit()
                .remove(KEY_LIST).remove(KEY_AT).remove(KEY_COST).remove(KEY_SCAN_START)
                .apply();
    }

    /** 清掉指定账号的券清单（切号时顺手把上一个账号的也清了，避免切回来看到过期数据）。 */
    static void clearListForUid(Context context, String uid) {
        if (uid == null || uid.isEmpty()) {
            return;
        }
        // 这里直接拼名字，不走 prefsNameFor 的「旧数据迁移」——要清的东西不需要搬。
        context.getSharedPreferences(PREFS + "__" + AccountScope.shortOf(uid),
                        Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LIST).remove(KEY_AT).remove(KEY_COST).remove(KEY_SCAN_START)
                .apply();
    }

    /** 点「扫描」时记下起点，扫描结果回来时据此算耗时。 */
    static void markScanStart(Context context) {
        prefs(context).edit().putLong(KEY_SCAN_START, System.currentTimeMillis()).apply();
    }

    static void saveList(Context context, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long start = prefs(context).getLong(KEY_SCAN_START, 0L);
        long cost = start > 0 && now > start ? now - start : 0L;
        prefs(context).edit()
                .putString(KEY_LIST, json)
                .putLong(KEY_AT, now)
                .putLong(KEY_COST, cost)
                .apply();
    }

    static String list(Context context) {
        return prefs(context).getString(KEY_LIST, "");
    }

    static long at(Context context) {
        return prefs(context).getLong(KEY_AT, 0L);
    }

    /** 上次扫描耗时（毫秒），没记录时为 0。 */
    static long cost(Context context) {
        return prefs(context).getLong(KEY_COST, 0L);
    }

    static void saveProgress(Context context, String json) {
        prefs(context).edit()
                .putString(KEY_PROGRESS, json == null ? "" : json)
                .putLong(KEY_PROGRESS_AT, System.currentTimeMillis())
                .apply();
    }

    static String progress(Context context) {
        return prefs(context).getString(KEY_PROGRESS, "");
    }

    static long progressAt(Context context) {
        return prefs(context).getLong(KEY_PROGRESS_AT, 0L);
    }

    static void clearProgress(Context context) {
        prefs(context).edit().remove(KEY_PROGRESS).remove(KEY_PROGRESS_AT).apply();
    }
}
