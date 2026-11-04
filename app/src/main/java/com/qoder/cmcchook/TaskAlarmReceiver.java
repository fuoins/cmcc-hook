package com.qoder.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 定时自检的落点（模块进程）。
 *
 * 只做两件事：解冻目标 App、发广播让它自检一轮。不开界面、不发通知。
 * 开机广播也走这里，重启后把闹钟重新排上。
 */
public final class TaskAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            TaskAlarm.sync(context);
            return;
        }
        if (TaskAlarm.ACTION_TICK.equals(action)) {
            TaskAlarm.tick(context);
        }
    }
}
