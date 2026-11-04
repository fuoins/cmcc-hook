package com.qoder.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 模块 → 目标进程的任务指令通道。
 *
 * v1.8 之前只有「写 settings + 拉起 App + onResume 读指令」一条路，问题是：
 *   · App 已经在前台时，startActivity 走的是 onNewIntent，onResume 不再触发；
 *   · 用户不想为了跑个任务被切到中国移动界面。
 *
 * 所以补一条广播：模块点按钮 → 发一个显式广播 → 目标进程里这个常驻 receiver
 * 收到就直接在后台跑，界面完全不动。进程不在时才退回「拉起 App」。
 *
 * 广播是显式指定包名的（setPackage），Android 8 的隐式广播限制不影响它。
 */
final class TaskReceiver {

    static final String ACTION_RUN = "com.qoder.cmcchook.action.RUN_TASK";
    static final String ACTION_ACK = "com.qoder.cmcchook.action.TASK_ACK";

    private static volatile boolean registered;

    private TaskReceiver() {
    }

    static void install(final Context context) {
        if (context == null || registered) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || !ACTION_RUN.equals(intent.getAction())) {
                        return;
                    }
                    final String cmd = intent.getStringExtra("cmd");
                    if (cmd == null || cmd.isEmpty()) {
                        return;
                    }
                    HookLog.logcat("task command from module: " + cmd);
                    ack(context, cmd);
                    TaskRunner.executeAuto(cmd, "模块指令");
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_RUN);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;
            HookLog.logcat("task receiver installed");
        } catch (Throwable e) {
            HookLog.logcat("task receiver install failed: " + e);
        }
    }

    /** 回执：模块收到后就不用再拉起 App 了。 */
    private static void ack(Context context, String cmd) {
        try {
            Intent intent = new Intent(ACTION_ACK);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra("cmd", cmd);
            intent.putExtra("ts", System.currentTimeMillis());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
