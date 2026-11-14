package com.cmcchook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

    /** 兜底：即使收尾广播丢了（目标进程被直接杀掉），也不会留下常驻通知。 */
    private static final long MAX_MS = 15 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = new Runnable() {
        @Override
        public void run() {
            HookLog.log("ALIVE", "保活服务 · 兜底超时自动停止",
                    "等了 " + (MAX_MS / 60_000L) + " 分钟没收到任务收尾广播。\n"
                            + "通常是目标进程中途被杀，任务没跑完；看这份日志前面最后一条活动是哪一项。");
            stopSelf();
        }
    };

    /** 服务是否已经在本进程里起来（同一进程的静态标志够用）。 */
    private static volatile boolean alive;

    /**
     * r47：模块界面重新打开时把保活服务补回来。
     *
     * <p>用户实测「模块执行过程中一直自己关闭」：HyperOS 把模块进程回收后，
     * 前台服务也跟着没了；用户再点开模块时它只是普通缓存进程，下一次被切到后台
     * 又会被回收 —— 看起来就是「模块一直关」。这里在界面每次回到前台时检查：
     * 15 分钟内下发过任务、服务又不在，就重新抬一次前台优先级。
     */
    static void ensure(Context ctx) {
        if (ctx == null || alive) {
            return;
        }
        long seq = AppPrefs.getTaskSeq(ctx);
        if (seq <= 0 || System.currentTimeMillis() - seq > 15 * 60 * 1000L) {
            return;
        }
        start(ctx);
    }

    /**
     * 任务开始前调用。
     *
     * <p>r18：以前成败只打 logcat，导出的日志文件里一点痕迹都没有，出问题时无法判断
     * 「服务没起来」还是「起来了但被 ROM 杀了」。现在两条路都写进日志文件。
     */
    static void start(Context ctx) {
        if (ctx == null) {
            HookLog.log("ALIVE", "保活服务 · 没启动（拿不到 Context）",
                    "任务照常执行，但模块退到后台后可能被系统回收。");
            return;
        }
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            HookLog.log("ALIVE", "保活服务 · 已请求启动",
                    "API " + Build.VERSION.SDK_INT + " · 前台服务优先级\n"
                            + "作用: 一键要跑两三分钟，模块界面退到后台后不被当缓存进程回收。");
        } catch (Throwable e) {
            // Android 12+ 后台启动前台服务会被拒（ForegroundServiceStartNotAllowed）。
            HookLog.log("ALIVE", "保活服务 · 启动失败",
                    "异常: " + e + "\n"
                            + "任务继续跑，但模块退到后台后可能被系统回收，"
                            + "表现是日志中途断掉、没有一键汇总。");
            HookLog.logcat("keepalive start failed: " + e);
        }
    }

    /** 任务结束后调用。 */
    static void stop(Context ctx) {
        alive = false;
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
        alive = true;
        boolean ok;
        try {
            startForeground(NID, build());
            ok = true;
        } catch (Throwable e) {
            ok = false;
            HookLog.log("ALIVE", "保活服务 · startForeground 失败",
                    "异常: " + e + "\n"
                            + "服务进程起来了但没升到前台优先级，仍可能被回收。");
            HookLog.logcat("keepalive foreground failed: " + e);
        }
        if (ok) {
            HookLog.log("ALIVE", "保活服务 · 前台服务已生效",
                    "通知: IMPORTANCE_LOW · 任务结束自动消失\n"
                            + "看到这条说明进程已升到前台服务优先级。");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 重复 start 时再抬一次，防止某些 ROM 把通知吞掉后进程又降级。
        try {
            startForeground(NID, build());
        } catch (Throwable ignored) {
        }
        // 每次 start 重置兜底计时，避免连跑多项时被提前掐。
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, MAX_MS);
        return START_STICKY;
    }

    /** 用户从最近任务里划掉模块界面时，服务仍活着；记一笔方便和日志时间点对照。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // r47：划掉界面后 ROM 常把前台服务一起降级/收掉。这里再抬一次通知，
        // 只要任务还在 15 分钟窗口里就继续保活，任务跑完由 TASK_END 停。
        long seq = AppPrefs.getTaskSeq(this);
        boolean running = seq > 0 && System.currentTimeMillis() - seq < 15 * 60 * 1000L;
        if (running) {
            try {
                startForeground(NID, build());
            } catch (Throwable ignored) {
            }
            HookLog.log("APP", "模块界面被划掉 · 保活服务仍在",
                    "任务未结束时进程会继续跑完；如果任务已经结束，说明该停服务了。\n"
                            + "任务实际跑在「中国移动」进程里，模块界面关掉不影响执行结果，"
                            + "重新打开模块可在日志页看到进度。");
        } else {
            HookLog.log("APP", "模块界面被划掉 · 保活服务仍在",
                    "任务未结束时进程会继续跑完；如果任务已经结束，说明该停服务了。");
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        alive = false;
        handler.removeCallbacks(expire);
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
                // r18：MIN 在 HyperOS 上容易被折叠/清理，抬到 LOW 更稳，仍然不出声不弹窗。
                NotificationChannel ch = new NotificationChannel(CHANNEL, "任务运行",
                        NotificationManager.IMPORTANCE_LOW);
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
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
