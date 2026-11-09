package com.cmcchook;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 定时自检调度（跑在模块自己的进程里，不在中国移动进程里）。
 *
 * 为什么不能只靠目标进程里的 Handler：
 * Android 11+ 会把 cached 进程丢进 cgroup freezer，Handler 的 postDelayed 一律不走；
 * Android 12+ 还会掐掉 cached 进程的网络。目标 App 一旦退到后台被冻结，
 * v2.1 那个「每 30 分钟补跑」就永远不会触发 —— 这就是「任务都没做」的另一半原因。
 *
 * 做法照着成熟模块抄：模块进程用 AlarmManager 周期唤醒自己（不需要前台服务、不弹通知），
 * 到点做两件事：
 *   1. 执行 {@code cmd activity unfreeze --sticky <目标包>} 把目标进程从 freezer 里捞出来；
 *      普通应用可执行，不需要 root，也不会把界面拉到前台。
 *   2. 给目标包发一条显式广播，让里面的模块自己判断该不该跑。
 * 全程不打开任何界面。
 */
final class TaskAlarm {

    /** 到点广播（发给模块自己的接收器） */
    static final String ACTION_TICK = "com.cmcchook.action.ALARM_TICK";
    /** 自检广播（发给目标进程里的模块） */
    static final String ACTION_PING = "com.cmcchook.action.PING";

    private static final int REQ = 4210;
    private static final long MIN_GAP_MS = 10 * 60_000L;

    private TaskAlarm() {
    }

    static int gapMinutes(Context context) {
        int gap = AppPrefs.settings(context).getInt(AppPrefs.KEY_KEEP_GAP, 30);
        if (gap < 10) {
            gap = 10;
        }
        if (gap > 240) {
            gap = 240;
        }
        return gap;
    }

    /** 按当前开关启停：总开关 + 定时自检都开才排闹钟。 */
    static void sync(Context context) {
        if (context == null) {
            return;
        }
        try {
            boolean on = AppPrefs.getBool(context, AppPrefs.KEY_AUTO_SIGN)
                    && AppPrefs.getBool(context, AppPrefs.KEY_KEEPALIVE);
            if (on) {
                schedule(context);
            } else {
                cancel(context);
            }
        } catch (Throwable e) {
            HookLog.logcat("alarm sync failed: " + e);
        }
    }

    private static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long next = System.currentTimeMillis() + gapMinutes(context) * 60_000L;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                // 单次闹钟 + 自己续期：Doze 下 setInexactRepeating 会被整段推迟，
                // setAndAllowWhileIdle 每次都能唤醒（系统限频约 9 分钟一次，够用）。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(context));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, next, pi(context));
            }
        } catch (Throwable e) {
            HookLog.logcat("alarm schedule failed: " + e);
        }
    }

    private static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi(context));
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static PendingIntent pi(Context context) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent intent = new Intent(context, TaskAlarmReceiver.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, REQ, intent, flags);
    }

    /** 到点动作：解冻目标 App，再让它自检一轮；跑完自己续下一次。 */
    static void tick(Context context) {
        try {
            tickOnce(context);
        } finally {
            // 单次闹钟的续期放在 finally，任何异常都不能让定时链断掉。
            sync(context);
        }
    }

    private static void tickOnce(Context context) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = AppPrefs.getLastTick(context);
        if (now - last < MIN_GAP_MS) {
            return;
        }
        AppPrefs.setLastTick(context, now);
        String why;
        try {
            unfreeze(AppPrefs.TARGET_PACKAGE);
            why = "已解冻";
        } catch (Throwable e) {
            why = "解冻失败(" + e.getClass().getSimpleName() + ")";
        }
        try {
            Intent ping = new Intent(ACTION_PING);
            ping.setPackage(AppPrefs.TARGET_PACKAGE);
            ping.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(ping);
            HookLog.logcat("tick: " + why + "，已发自检广播");
        } catch (Throwable e) {
            HookLog.logcat("tick: 自检广播失败 " + e);
        }
    }

    /**
     * 把目标进程从 cached 组里捞出来。
     * {@code cmd activity unfreeze --sticky <pkg>} 是 shell 命令，普通应用可执行，
     * 只作用于已存在的进程，不会把界面拉到前台。
     */
    static void unfreeze(String pkg) {
        String[] cmds = {
                "cmd activity unfreeze --sticky " + pkg,
                "/system/bin/cmd activity unfreeze --sticky " + pkg,
        };
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(cmd.split(" "));
                p.waitFor();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    static String describe(Context context) {
        if (!AppPrefs.getBool(context, AppPrefs.KEY_KEEPALIVE)) {
            return "未开启";
        }
        return "每 " + gapMinutes(context) + " 分钟唤醒一次";
    }
}
