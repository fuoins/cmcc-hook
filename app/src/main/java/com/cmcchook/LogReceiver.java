package com.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class LogReceiver extends BroadcastReceiver {
    static final String ACTION_LOG = "com.cmcchook.action.LOG";
    static final String ACTION_HEARTBEAT = "com.cmcchook.action.HEARTBEAT";
    static final String ACTION_STATE = "com.cmcchook.action.STATE";
    /** 目标进程盘点完福利中心，把新入口列表发回来。 */
    static final String ACTION_WELFARE = "com.cmcchook.action.WELFARE";
    /** 目标进程探路完，把整份报告发回来。 */
    static final String ACTION_PROBE = "com.cmcchook.action.PROBE";
    /** 目标进程采完单个活动，把这一份报告发回来。 */
    static final String ACTION_PROBE_ACT = "com.cmcchook.action.PROBE_ACT";
    /** 目标进程检测到页面落在 SSO 登录页（登录态失效），模块进程记下日期。 */
    static final String ACTION_LOGIN_LOST = "com.cmcchook.action.LOGIN_LOST";
    /** 卡券扫描结果（整份 JSON）。 */
    static final String ACTION_COUPON_LIST = "com.cmcchook.action.COUPON_LIST";
    /** 批量使用的逐张进度。 */
    static final String ACTION_COUPON_PROGRESS = "com.cmcchook.action.COUPON_PROGRESS";
    /** 某张券要短信验证码，模块弹输入框。 */
    static final String ACTION_COUPON_NEED_CODE = "com.cmcchook.action.COUPON_NEED_CODE";
    /** 一批跑完。 */
    static final String ACTION_COUPON_DONE = "com.cmcchook.action.COUPON_DONE";
    /** v4.0 目标进程离屏读完官方余量页，把整份结果 JSON 发回来。 */
    static final String ACTION_FLOW_DATA = "com.cmcchook.action.FLOW_DATA";
    /** v4.0 电话进程框架层短信通道的安装诊断（回答「还得加东西吗」）。 */
    static final String ACTION_SMS_DIAG = "com.cmcchook.action.SMS_DIAG";
    /** v4.2.0 刷新 / 抢券的实时步骤（界面上滚动显示，不用干等）。 */
    static final String ACTION_PROGRESS = HookLog.ACTION_PROGRESS;

    /** 优惠券页开着时，收到广播立刻刷新；关着就只落盘。 */
    interface CouponWatcher {
        void onCoupon(String action, Intent intent);
    }

    private static volatile CouponWatcher couponWatcher;

    static void setCouponWatcher(CouponWatcher watcher) {
        couponWatcher = watcher;
    }

    /** 流量页开着时，收到数据立刻重画；关着只落盘。 */
    interface FlowWatcher {
        void onFlow(String action, Intent intent);
    }

    private static volatile FlowWatcher flowWatcher;

    static void setFlowWatcher(FlowWatcher watcher) {
        flowWatcher = watcher;
    }

    /** 刷新进度：界面开着就实时换状态行，关着就丢掉（不需要落盘）。 */
    interface ProgressWatcher {
        void onProgress(String scope, String text);
    }

    private static volatile ProgressWatcher progressWatcher;

    static void setProgressWatcher(ProgressWatcher watcher) {
        progressWatcher = watcher;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_HEARTBEAT.equals(action)) {
            handleHeartbeat(context, intent);
        } else if (ACTION_LOG.equals(action)) {
            handleLog(context, intent);
        } else if (ACTION_STATE.equals(action)) {
            handleState(context, intent);
        } else if (ACTION_WELFARE.equals(action)) {
            handleWelfare(context, intent);
        } else if (ACTION_PROBE.equals(action)) {
            handleProbe(context, intent);
        } else if (ACTION_PROBE_ACT.equals(action)) {
            handleProbeAct(context, intent);
        } else if (ACTION_LOGIN_LOST.equals(action)) {
            handleLoginLost(context, intent);
        } else if (ACTION_COUPON_LIST.equals(action)) {
            handleCouponList(context, intent);
        } else if (ACTION_COUPON_PROGRESS.equals(action)) {
            handleCouponProgress(context, intent);
        } else if (ACTION_COUPON_NEED_CODE.equals(action)) {
            // 券任务开始等码：开短信库兜底轮询，并先把可能早到的缓存码投出去。
            AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                    intent.getStringExtra("phone"));
            SmsCode.onNeedCode(context);
            notifyCoupon(action, intent);
        } else if (ACTION_COUPON_DONE.equals(action)) {
            SmsCode.onDone(context);
            notifyCoupon(action, intent);
        } else if (ACTION_FLOW_DATA.equals(action)) {
            handleFlowData(context, intent);
        } else if (ACTION_SMS_DIAG.equals(action)) {
            handleSmsDiag(context, intent);
        } else if (ACTION_PROGRESS.equals(action)) {
            handleProgress(intent);
        }
    }

    private void handleCouponList(Context context, Intent intent) {
        // v3.57：先对齐账号，再落盘 —— 券清单必须写进「产生它的那个账号」的命名空间。
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));
        try {
            CouponStore.saveList(context, intent.getStringExtra("json"));
        } catch (Throwable ignored) {
        }
        notifyCoupon(ACTION_COUPON_LIST, intent);
    }

    private void handleCouponProgress(Context context, Intent intent) {
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));
        try {
            org.json.JSONObject one = new org.json.JSONObject();
            one.put("stage", intent.getStringExtra("stage"));
            one.put("idx", intent.getIntExtra("idx", -1));
            one.put("name", intent.getStringExtra("name"));
            one.put("ok", intent.getIntExtra("ok", 0));
            one.put("fail", intent.getIntExtra("fail", 0));
            one.put("total", intent.getIntExtra("total", 0));
            one.put("msg", intent.getStringExtra("msg"));
            CouponStore.saveProgress(context, one.toString());
        } catch (Throwable ignored) {
        }
        notifyCoupon(ACTION_COUPON_PROGRESS, intent);
    }

    private void notifyCoupon(String action, Intent intent) {
        CouponWatcher watcher = couponWatcher;
        if (watcher != null) {
            try {
                watcher.onCoupon(action, intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /** v4.0 流量结果：按账号落盘 + 通知流量页重画。 */
    private void handleFlowData(Context context, Intent intent) {
        // 数据必须写进「产生它的那个账号」的命名空间，换号后不串数据。
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));
        try {
            FlowStore.save(context, intent.getStringExtra("json"));
        } catch (Throwable ignored) {
        }
        FlowWatcher watcher = flowWatcher;
        if (watcher != null) {
            try {
                watcher.onFlow(ACTION_FLOW_DATA, intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v4.0 电话进程框架层短信通道安装诊断。
     *
     * <p>之前这些状态只在 LSPosed 的 XposedBridge.log 里，模块自己的完整日志一行
     * 都没有 —— 用户说「不行」时根本无法判断模块到底有没有被注入电话进程、
     * InboundSmsHandler 找没找到、hook 到几个方法。这条广播把它们写进日志页，
     * 下次导日志就能一眼定位，不用再猜「是不是还得加东西」。
     */
    private void handleProgress(Intent intent) {
        ProgressWatcher watcher = progressWatcher;
        if (watcher == null) {
            return;
        }
        try {
            watcher.onProgress(intent.getStringExtra("scope"),
                    intent.getStringExtra("text"));
        } catch (Throwable ignored) {
        }
    }

    private void handleSmsDiag(Context context, Intent intent) {
        String summary = intent.getStringExtra("summary");
        String detail = intent.getStringExtra("detail");
        try {
            LogStore.append(context, new LogEntry(System.currentTimeMillis(),
                    "SMSDIAG",
                    summary == null || summary.isEmpty()
                            ? "框架层短信通道安装诊断" : summary,
                    detail == null ? "" : detail));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 登录态失效标记：目标进程发现页面落在 SSO 登录页时发过来。
     * day 为空表示登录态恢复（页面又正常落在活动页了），清除标记。
     *
     * <p>写模块自己的 settings：目标进程用 XSharedPreferences 读同一份文件。
     */
    private void handleLoginLost(Context context, Intent intent) {
        try {
            String day = intent.getStringExtra("day");
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit()
                    .putString("login_lost_day", day == null ? "" : day)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单活动报告：按活动号单独存一份，不覆盖全局盘点报告。
     */
    private void handleProbeAct(Context context, Intent intent) {
        try {
            String frag = intent.getStringExtra("frag");
            String json = intent.getStringExtra("json");
            if (frag == null || frag.isEmpty() || json == null || json.isEmpty()) {
                return;
            }
            ProbeStore.saveAct(context, frag, json);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 探路报告：存一份到私有目录，界面上能看摘要、能导出。
     *
     * <p>报告可能有几百 KB，放 SharedPreferences 会拖慢启动，所以走文件。
     */
    private void handleProbe(Context context, Intent intent) {
        try {
            String json = intent.getStringExtra("json");
            if (json == null || json.isEmpty()) {
                return;
            }
            ProbeStore.save(context, json);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 福利盘点结果。
     *
     * <p>清单现在是内置表 + 采集缓存（{@link ProbeStore}）算出来的，
     * 这条广播只表示「盘点跑完了」，不再往本地另存一份清单，
     * 否则两份数据会各说各话。
     */
    private void handleWelfare(Context context, Intent intent) {
    }

    /** 目标进程跑完一个活动就报一次，模块首页据此显示 ✓ / ✗ / ○。 */
    private void handleState(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null || cmd.isEmpty()) {
            return;
        }
        String day = intent.getStringExtra("day");
        if (day == null || day.isEmpty()) {
            day = AppPrefs.today();
        }
        // v3.57：先对齐账号，任务状态才有正确的 key 后缀。
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));
        AppPrefs.setTaskState(context, cmd,
                day,
                intent.getBooleanExtra("ok", false),
                intent.getStringExtra("summary"),
                intent.getIntExtra("attempts", 0));
    }

    private void handleHeartbeat(Context context, Intent intent) {
        long now = System.currentTimeMillis();
        long previous = AppPrefs.getLastHeartbeat(context);
        AppPrefs.setHeartbeat(
                context,
                now,
                intent.getStringExtra("process"),
                intent.getStringExtra("module_version"),
                intent.getStringExtra("target_version"));
        // v3.57：目标进程把当前登录账号一并带上来，模块界面显示、并按账号切分数据。
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));

        if (previous == 0L || now - previous > 30L * 60L * 1000L) {
            String process = intent.getStringExtra("process");
            String version = intent.getStringExtra("target_version");
            String detail = "进程: " + safe(process)
                    + "\n模块: " + safe(intent.getStringExtra("module_version"))
                    + "\n目标版本: " + safe(version)
                    + "\n如果这行出现，说明模块已经注入中国移动 App。";
            LogStore.append(context, new LogEntry(now, "SYSTEM", "模块心跳 / 注入成功", detail));
        }
    }

    private void handleLog(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long ts = extras.getLong("ts", now);
        if (ts < now - 24L * 60L * 60L * 1000L || ts > now + 60_000L) {
            ts = now;
        }
        String tag = safe(intent.getStringExtra("tag"));
        String summary = safe(intent.getStringExtra("summary"));
        String detail = safe(intent.getStringExtra("detail"));
        String process = safe(intent.getStringExtra("process"));
        if (tag.isEmpty()) {
            tag = "LOG";
        }
        if (summary.isEmpty()) {
            summary = "(no summary)";
        }
        if (!process.isEmpty() && !AppPrefs.TARGET_PACKAGE.equals(process)) {
            summary = "[" + shortProcess(process) + "] " + summary;
        }

        AppPrefs.setHeartbeat(
                context,
                now,
                intent.getStringExtra("process"),
                intent.getStringExtra("module_version"),
                intent.getStringExtra("target_version"));
        AppPrefs.setAccount(context, intent.getStringExtra("uid"),
                intent.getStringExtra("phone"));
        LogStore.append(context, new LogEntry(ts, tag, summary, detail));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String shortProcess(String process) {
        int colon = process.indexOf(':');
        return colon >= 0 ? process.substring(colon) : process;
    }
}
