package com.cmcchook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * r17：一键任务期间的前台服务。
 *
 * <p>问题：模块只有一个 Activity。点「执行任务」后会把中国移动拉到前台，模块自己
 * 退到后台；HyperOS 把没有前台服务的进程当缓存进程，几分钟内就回收，于是任务跑到
 * 一半界面没了、当日状态也丢了（用户实测一轮掉两次）。广播的 goAsync 只能把进程
 * 多留几十秒，撑不住三分钟的一键。
 *
 * <p>做法：任务开始时起一个 IMPORTANCE_MIN 的常驻通知，把进程提到前台服务优先级；
 * 任务跑完立刻停掉，不留长期通知。只解决「跑任务期间不被杀」，不做常驻保活，
 * 也不申请电池白名单。
 */
public final class KeepAliveService extends Service {
    private static final String CHANNEL = "cmcchook_task";
    private static final int NID = 0x434D; // 'CM'

    /** 任务开始前调用。失败只记 logcat，不影响任务本身。 */
    static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable e) {
            HookLog.logcat("keepalive start failed: " + e);
        }
    }

    /** 任务结束后调用。 */
    static void stop(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable e) {
            HookLog.logcat("keepalive stop failed: " + e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(NID, build());
        } catch (Throwable e) {
            HookLog.logcat("keepalive foreground failed: " + e);
        }
        HookLog.logcat("keepalive on");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 重复 start 时再抬一次，防止某些 ROM 把通知吞掉后进程又降级。
        try {
            startForeground(NID, build());
        } catch (Throwable ignored) {
        }
        return START_STICKY;
    }

    /** 用户从最近任务里划掉模块界面时，服务仍活着；记一笔方便和日志时间点对照。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        HookLog.log("APP", "模块界面被划掉 · 保活服务仍在",
                "任务未结束时进程会继续跑完；如果任务已经结束，说明该停服务了。");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        HookLog.logcat("keepalive stopped");
        super.onDestroy();
    }

    private Notification build() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "任务运行",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setDescription("一键任务运行期间的保活通知，任务结束自动消失");
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("移动签到助手")
                .setContentText("正在执行任务，请勿关闭")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
