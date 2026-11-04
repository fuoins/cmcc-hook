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
 * v2.2 起多接一路 {@link TaskAlarm#ACTION_PING}：模块进程的 AlarmManager 到点后
 * 先解冻再发这条广播，目标进程即使被系统冻在 cached 组里也能被拉起来跑一轮。
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
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (TaskAlarm.ACTION_PING.equals(action)) {
                        String pending = pendingForPing(context);
                        if (pending == null) {
                            return;
                        }
                        // goAsync 让广播在任务跑完前保持 pending，
                        // 否则 onReceive 一返回，进程又被丢回 cached 组冻住。
                        final BroadcastReceiver.PendingResult result = goAsync();
                        TaskRunner.executeAuto(pending, "定时自检", new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    result.finish();
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        return;
                    }
                    if (!ACTION_RUN.equals(action)) {
                        return;
                    }
                    final String cmd = intent.getStringExtra("cmd");
                    if (cmd == null || cmd.isEmpty()) {
                        return;
                    }
                    HookLog.logcat("task command from module: " + cmd);
                    ack(context, cmd);
                    // goAsync 让广播在任务跑完前保持 pending，避免 onReceive 一返回
                    // 进程又被丢回 cached 组、跑到一半被冻住。
                    final BroadcastReceiver.PendingResult pending = goAsync();
                    TaskRunner.executeAuto(cmd, "模块指令", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                pending.finish();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_RUN);
            filter.addAction(TaskAlarm.ACTION_PING);
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

    /**
     * 定时自检：算出这次该跑什么，返回 null 表示不用跑。
     *
     * 只有「有活动待跑」才动，跑过就静默返回 —— 日志里也能看出来是哪种情况，
     * 免得用户以为定时根本没生效。
     */
    private static String pendingForPing(Context context) {
        try {
            String pending = TaskRunner.pendingAuto(context);
            if (pending.isEmpty()) {
                HookLog.logcat("ping: 今天没有待跑的活动");
                return null;
            }
            if (TaskRunner.tooSoon(context)) {
                HookLog.logcat("ping: 距上次自动跑太近，跳过");
                return null;
            }
            boolean visible = TaskRunner.hostVisible(context);
            HookLog.logcat("ping: 待跑 " + pending + (visible ? "（前台）" : "（后台）"));
            TaskRunner.markAutoRun(context);
            return pending;
        } catch (Throwable e) {
            HookLog.logcat("ping failed: " + e);
            return null;
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
