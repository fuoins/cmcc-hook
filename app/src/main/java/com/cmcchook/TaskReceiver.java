package com.cmcchook;

import android.app.Activity;
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

    static final String ACTION_RUN = "com.cmcchook.action.RUN_TASK";
    static final String ACTION_ACK = "com.cmcchook.action.TASK_ACK";

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
                        // 手动模式：定时自检只解冻（在 TaskAlarm.tick 里做了），
                        // 不自动跑任务。任务只在用户手动点时执行。
                        if (!HookPrefs.autoTrigger()) {
                            HookLog.logcat("ping skipped: auto trigger off");
                            return;
                        }
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
                    if (cmd.startsWith(TaskRunner.CMD_OPEN)) {
                        // 只是打开页面，不排队、不等入场门禁、不记今日状态。
                        //
                        // 打开前先换票：裸地址进容器后页面自己跳 SSO 登录页，
                        // 登录态一过期登录页就卡住转圈。带 token 的地址服务端直接
                        // 认，页面不用走那套自动登录。
                        //
                        // 启动方式对齐手动路径：用 App 当前 Activity 拉起、不加
                        // NEW_TASK，页面进 App 现有任务栈。之前从 receiver 的 app
                        // context 开 NEW_TASK 新栈，路由框架在新栈里拿不到路由
                        // 上下文，容器 WebView 建了却不 loadUrl —— 一直转圈。
                        final String url = cmd.substring(TaskRunner.CMD_OPEN.length());
                        ack(context, cmd);
                        final BroadcastReceiver.PendingResult openPending = goAsync();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    StringBuilder login = new StringBuilder();
                                    String open = url;
                                    // v3.42：我的卡券（mycoupons.html）是普通 H5，靠 WebView
                                    // Cookie 登录，不需要 qwhd SSO 换票。之前按钮带一串活动
                                    // 入口参数还强行走换票，页面会卡在登录中转圈；用户从 App
                                    // 里手动进卡券中心用的是裸地址，能正常渲染券列表。
                                    if (!url.contains("mycoupons")) {
                                        String withToken = SsoLogin.ensure(url, login);
                                        if (withToken == null) {
                                            // v3.43：日志实证 appTokenLogin 会回「APP登录状态已过期」，
                                            // 而用户信息是「复用缓存」的旧票据。清掉会话/信息缓存
                                            // 强制重取一次再换，别拿过期票据直接开裸地址卡在 SSO。
                                            login.append("换票失败 → 清缓存重取登录态后再试一次\n");
                                            SsoLogin.invalidate();
                                            withToken = SsoLogin.ensure(url, login);
                                        }
                                        if (withToken != null && withToken.contains("token=")) {
                                            open = withToken;
                                        } else {
                                            login.append("仍未换到票：页面会自行走 SSO 登录；"
                                                    + "若一直转圈，请先在中国移动 App 里重新登录再打开。\n");
                                        }
                                    } else {
                                        login.append("卡券页：跳过换票，直接用裸地址开\n");
                                    }
                                    boolean ok = Html5.openFrom(ActivityHooks.current(), open);
                                    String way = ok ? "同任务栈" : "";
                                    if (!ok) {
                                        ok = Html5.open(context, open);
                                        way = ok ? "新任务栈兜底" : "打开失败";
                                    }
                                    HookLog.log("ACTIVITY",
                                            "打开活动 · " + (open.equals(url)
                                                    ? "没换到票" : "已换票") + " · " + way,
                                            "URL: " + TaskRunner.briefUrl(open) + "\n" + login);
                                    // v3.43：容器可能起来了却不 loadUrl（路由参数不被识别），
                                    // 2.6 秒内没有任何页面加载就由模块主动补一次，杜绝空白转圈。
                                    if (ok) {
                                        OpenGuard.arm(open);
                                    }
                                } catch (Throwable e) {
                                    HookLog.log("ACTIVITY", "打开活动异常",
                                            "URL: " + TaskRunner.briefUrl(url) + "\n" + e);
                                } finally {
                                    try {
                                        openPending.finish();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }, "cmcc-open").start();
                        return;
                    }
                    // 优惠券：验证码是「补数据」不是「跑任务」，不能进任务队列，
                    // 否则会把正在等码的那条流程堵在队列后面。
                    if (cmd.startsWith(CouponTask.CMD_CODE)) {
                        String code = cmd.substring(CouponTask.CMD_CODE.length());
                        // v3.56：带上短信时间，让 CouponTask 能判断这条码是不是本张券的。
                        long at = intent.getLongExtra("at", 0L);
                        CouponTask.submitCode(code, at);
                        HookLog.log("SMSCODE", "收到验证码 · " + code,
                                "已交给券核销流程，页面会自动填入并点确认。");
                        return;
                    }
                    if (CouponTask.CMD_SCAN.equals(cmd) || cmd.startsWith(CouponTask.CMD_USE)) {
                        ack(context, cmd);
                        final BroadcastReceiver.PendingResult couponPending = goAsync();
                        final String couponCmd = cmd;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (CouponTask.CMD_SCAN.equals(couponCmd)) {
                                        CouponTask.scan();
                                    } else {
                                        CouponTask.use(couponCmd.substring(
                                                CouponTask.CMD_USE.length()));
                                    }
                                } finally {
                                    try {
                                        couponPending.finish();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }, "cmcc-coupon").start();
                        return;
                    }
                    // v4.0 流量余量：离屏只读官方余量页，结果用广播回模块 UI。
                    // 和券扫描一样不进任务队列（只读、不记今日状态、不占用「过任务」串行池）。
                    if (TaskRunner.CMD_FLOW.equals(cmd)) {
                        ack(context, cmd);
                        final BroadcastReceiver.PendingResult flowPending = goAsync();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    FlowTask.fetch();
                                } finally {
                                    try {
                                        flowPending.finish();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }, "cmcc-flow").start();
                        return;
                    }
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
