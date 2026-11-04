package com.qoder.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class LogReceiver extends BroadcastReceiver {
    static final String ACTION_LOG = "com.qoder.cmcchook.action.LOG";
    static final String ACTION_HEARTBEAT = "com.qoder.cmcchook.action.HEARTBEAT";
    static final String ACTION_STATE = "com.qoder.cmcchook.action.STATE";

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
        }
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
