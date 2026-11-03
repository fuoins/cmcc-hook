package com.qoder.cmcchook;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Android 11+ 可以查询自己进程上一次的退出原因，并能拿到 native 崩溃的 tombstone。
 * 这比 Java 未捕获异常更底层：即使进程被 SIGSEGV / SIGKILL 杀掉也能看到原因。
 */
final class ExitInfoApi30 {
    private static final int MAX_TRACE = 160_000;

    private ExitInfoApi30() {
    }

    static void log(Context context) {
        if (Build.VERSION.SDK_INT < 30 || context == null) {
            return;
        }
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            List<ApplicationExitInfo> list = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) {
                HookLog.log("EXIT-INFO", "没有历史进程退出记录",
                        "系统未返回本应用的 ApplicationExitInfo。\n"
                                + "可能是厂商 ROM 限制，或本次是安装后第一次启动。");
                return;
            }
            SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            long now = System.currentTimeMillis();
            int count = 0;
            for (ApplicationExitInfo info : list) {
                if (info == null) {
                    continue;
                }
                if (now - info.getTimestamp() > 60L * 60L * 1000L) {
                    break;
                }
                String time = format.format(new Date(info.getTimestamp()));
                int reason = info.getReason();
                StringBuilder detail = new StringBuilder();
                detail.append("时间: ").append(time).append('\n');
                detail.append("进程: ").append(info.getProcessName())
                        .append(" (pid=").append(info.getPid()).append(")\n");
                detail.append("原因: ").append(reason)
                        .append(" (").append(reasonName(reason)).append(")\n");
                detail.append("状态: ").append(info.getStatus()).append('\n');
                detail.append("重要性: ").append(info.getImportance()).append('\n');
                String description = info.getDescription();
                if (description != null && !description.isEmpty()) {
                    detail.append("描述: ").append(description).append('\n');
                }
                if (reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || reason == ApplicationExitInfo.REASON_ANR
                        || reason == ApplicationExitInfo.REASON_SIGNALED) {
                    String trace = readTrace(info);
                    if (trace != null && !trace.isEmpty()) {
                        detail.append("--- 系统 trace / tombstone ---\n").append(trace);
                    }
                }
                HookLog.log("EXIT-INFO", "上次进程退出 · " + reasonName(reason)
                        + " · " + time, detail.toString());
                if (++count >= 5) {
                    break;
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("read exit info failed: " + e);
            HookLog.log("EXIT-INFO", "读取进程退出记录失败",
                    Log.getStackTraceString(e));
        }
    }

    private static String readTrace(ApplicationExitInfo info) {
        InputStream in = null;
        try {
            in = info.getTraceInputStream();
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_TRACE) {
                    out.write(buffer, 0, read - (total - MAX_TRACE));
                    out.write("\n…[trace truncated]".getBytes("UTF-8"));
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable e) {
            return "[读取 trace 失败: " + e + "]";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER:
                return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return "PACKAGE_UPDATED";
            default:
                return "UNKNOWN(" + reason + ")";
        }
    }
}
