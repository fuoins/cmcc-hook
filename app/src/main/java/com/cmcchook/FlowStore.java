package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 流量余量结果在模块进程按账号落盘。
 *
 * <p>结果由目标进程（中国移动 App 内）离屏读取后广播回来，界面关掉再进时广播早就
 * 没了，所以存一份。和 {@link CouponStore} 一样按账号命名空间隔离，避免换号后
 * 看到上一个账号的余量。
 */
final class FlowStore {

    private static final String PREFS = "flow";
    private static final String KEY_JSON = "json";
    private static final String KEY_AT = "at";

    private FlowStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(AccountScope.prefsName(c, PREFS), Context.MODE_PRIVATE);
    }

    static void save(Context c, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        prefs(c).edit()
                .putString(KEY_JSON, json)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
    }

    static String json(Context c) {
        return prefs(c).getString(KEY_JSON, "");
    }

    static long at(Context c) {
        return prefs(c).getLong(KEY_AT, 0L);
    }
}
