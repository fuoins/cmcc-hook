package com.qoder.cmcchook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 任务调度。
 *
 * 触发方式三种：
 *   1. 进活动页自动跑（v1.9 主路径）：WebView 发现进的是签到 / 砸蛋 / 鹅蛋页，
 *      按该活动的开关自动执行一次，见 {@link PageAuto}；
 *   2. 模块界面点按钮 → 写 task_cmd / task_cmd_seq → 唤起中国移动 →
 *      目标进程 onResume 时发现序号变化就执行；
 *   3. 「打开 App 就跑全部」开关打开 → 冷启动补跑一次，当天成功过就不再跑。
 *
 * 执行放单线程池，串行、带随机间隔，避免像脚本那样一把并发把自己送进风控。
 */
final class TaskRunner {

    /** 一键过全部：签到有礼 + 天天砸金蛋 + 收鹅蛋换好礼。 */
    static final String CMD_ALL = "all";
    static final String CMD_SIGN = "sign";
    static final String CMD_EGG = "egg";
    static final String CMD_GOOSE = "goose";

    private static final String STATE_FILE = "cmcc_hook_tasks";
    private static final String KEY_HANDLED_SEQ = "handled_seq";
    private static final String KEY_LAST_RESULT = "last_result";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_PREFIX_DONE = "done_";
    private static final String KEY_PREFIX_ATTEMPT = "attempt_";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile long lastForegroundCheck;

    private TaskRunner() {
    }

    static final class Result {
        final boolean ok;
        final String summary;
        final String detail;
        final String userText;

        private Result(boolean ok, String summary, String detail, String userText) {
            this.ok = ok;
            this.summary = summary;
            this.detail = detail;
            this.userText = userText;
        }

        static Result ok(String summary, String detail, String userText) {
            return new Result(true, summary, detail, userText);
        }

        static Result fail(String summary, String detail, String hint) {
            return new Result(false, summary, detail, hint);
        }
    }

    // ------------------------------------------------------------ 触发

    static void onForeground(Activity activity) {
        if (activity == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastForegroundCheck < 1_500L) {
            return;
        }
        lastForegroundCheck = now;

        String cmd = pendingCommand();
        if (cmd != null && !cmd.isEmpty()) {
            execute(activity, cmd, "手动");
            return;
        }
        if (!HookPrefs.autoSign() || isDoneToday(activity, CMD_ALL)) {
            return;
        }
        int attempt = getInt(activity, KEY_PREFIX_ATTEMPT + dayKey(), 0);
        if (attempt >= 3) {
            return;
        }
        putInt(activity, KEY_PREFIX_ATTEMPT + dayKey(), attempt + 1);
        long delay = attempt == 0 ? 4_000L : 20_000L;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                execute(activity, CMD_ALL, "自动");
            }
        }, delay);
    }

    private static String pendingCommand() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(HookLog.MODULE_PACKAGE, "settings");
            prefs.reload();
            String cmd = prefs.getString("task_cmd", "");
            long seq = prefs.getLong("task_cmd_seq", 0L);
            if (cmd == null || cmd.isEmpty() || seq <= 0) {
                return null;
            }
            Context ctx = HookLog.context();
            if (ctx == null) {
                return null;
            }
            long handled = getLong(ctx, KEY_HANDLED_SEQ, 0L);
            if (seq <= handled) {
                return null;
            }
            putLong(ctx, KEY_HANDLED_SEQ, seq);
            return cmd;
        } catch (Throwable e) {
            HookLog.logcat("pending command read failed: " + e);
            return null;
        }
    }

    /** 进活动页触发：不依赖 Activity，用模块持有的 Context。 */
    static void executeAuto(final String cmd, final String from) {
        execute(HookLog.context(), cmd, from);
    }

    private static void execute(final Context activity, final String cmd, final String from) {
        if (!RUNNING.compareAndSet(false, true)) {
            HookLog.logcat("task already running, skip " + cmd);
            return;
        }
        HookLog.log("TASK", "任务开始 · " + label(cmd) + "（" + from + "触发）",
                "入口: " + TaskApi.ACT_SIGN + "\n砸金蛋: " + TaskApi.ACT_EGG
                        + "\nCookie: " + CookieStore.summary());
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Result result;
                try {
                    result = dispatch(cmd);
                } catch (Throwable e) {
                    result = Result.fail("任务异常：" + e, android.util.Log.getStackTraceString(e),
                            "把日志发我。");
                }
                finish(activity, cmd, result);
                RUNNING.set(false);
            }
        });
    }

    private static Result dispatch(String cmd) {
        if (CMD_ALL.equals(cmd)) {
            return runAll();
        }
        if (CMD_SIGN.equals(cmd)) {
            return SignTask.run();
        }
        if (CMD_EGG.equals(cmd)) {
            return EggTask.run();
        }
        if (CMD_GOOSE.equals(cmd)) {
            return GooseTask.run();
        }
        return Result.fail("未知任务：" + cmd, "", "");
    }

    /** 三个活动串行跑：单个失败不影响后面，最后合成一条结果。 */
    private static Result runAll() {
        StringBuilder detail = new StringBuilder();
        StringBuilder text = new StringBuilder();
        int okCount = 0;
        int total = 0;

        total++;
        try {
            Result sign = SignTask.run();
            detail.append("=== 签到有礼 ===\n")
                    .append(sign.detail == null ? "" : sign.detail).append('\n');
            text.append("签到：").append(sign.ok ? sign.summary : "失败（" + sign.summary + "）")
                    .append('\n');
            if (sign.ok) {
                okCount++;
            }
        } catch (Throwable e) {
            detail.append("=== 签到有礼 ===\n异常: ").append(e).append('\n');
            text.append("签到：异常 ").append(e).append('\n');
        }

        total++;
        try {
            Result egg = EggTask.run();
            detail.append("=== 天天砸金蛋 ===\n")
                    .append(egg.detail == null ? "" : egg.detail).append('\n');
            text.append("砸金蛋：").append(egg.ok ? egg.summary : "失败（" + egg.summary + "）")
                    .append('\n');
            if (egg.ok) {
                okCount++;
            }
        } catch (Throwable e) {
            detail.append("=== 天天砸金蛋 ===\n异常: ").append(e).append('\n');
            text.append("砸金蛋：异常 ").append(e).append('\n');
        }

        total++;
        try {
            Result goose = GooseTask.run();
            detail.append("=== 收鹅蛋换好礼 ===\n")
                    .append(goose.detail == null ? "" : goose.detail).append('\n');
            text.append("收鹅蛋：").append(goose.ok ? goose.summary : "未完成（" + goose.summary + "）")
                    .append('\n');
            if (goose.ok) {
                okCount++;
            }
        } catch (Throwable e) {
            detail.append("=== 收鹅蛋换好礼 ===\n异常: ").append(e).append('\n');
            text.append("收鹅蛋：异常 ").append(e).append('\n');
        }

        String summary = "过任务 " + okCount + "/" + total + " 项成功";
        String userText = text.toString().trim();
        if (okCount == 0) {
            return Result.fail(summary, detail.toString(), userText);
        }
        return Result.ok(summary, detail.toString(), userText);
    }

    private static void finish(Context activity, String cmd, Result result) {
        Context ctx = HookLog.context();
        if (ctx != null) {
            putString(ctx, KEY_LAST_RESULT, (result.ok ? "OK " : "FAIL ") + result.summary);
            putLong(ctx, KEY_LAST_TIME, System.currentTimeMillis());
            if (result.ok) {
                putInt(ctx, KEY_PREFIX_DONE + cmd + "_" + dayKey(), 1);
            }
        }
        StringBuilder detail = new StringBuilder(result.detail == null ? "" : result.detail);
        if (result.userText != null && !result.userText.isEmpty()) {
            detail.append("\n--- 摘要 ---\n").append(result.userText);
        }
        HookLog.log(result.ok ? "TASK" : "TASK-FAIL",
                (result.ok ? "任务完成 · " : "任务失败 · ") + result.summary,
                detail.toString());
        HookLog.logcat("task " + cmd + " finished ok=" + result.ok + " " + result.summary);
        if (activity != null) {
            final String text = result.ok ? result.summary
                    : (result.userText == null || result.userText.isEmpty()
                    ? result.summary : result.userText);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(HookLog.context(), text, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    static String label(String cmd) {
        if (CMD_ALL.equals(cmd)) {
            return "一键过全部";
        }
        if (CMD_SIGN.equals(cmd)) {
            return "签到有礼";
        }
        if (CMD_EGG.equals(cmd)) {
            return "天天砸金蛋";
        }
        if (CMD_GOOSE.equals(cmd)) {
            return "收鹅蛋换好礼";
        }
        return cmd;
    }

    // ------------------------------------------------------------ 状态

    static boolean isDoneToday(Context ctx, String cmd) {
        return getInt(ctx, KEY_PREFIX_DONE + cmd + "_" + dayKey(), 0) == 1;
    }

    static String lastResult(Context ctx) {
        return getString(ctx, KEY_LAST_RESULT, "");
    }

    private static String dayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
    }

    private static int getInt(Context ctx, String key, int def) {
        try {
            return prefs(ctx).getInt(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private static void putInt(Context ctx, String key, int value) {
        try {
            prefs(ctx).edit().putInt(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    private static long getLong(Context ctx, String key, long def) {
        try {
            return prefs(ctx).getLong(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private static void putLong(Context ctx, String key, long value) {
        try {
            prefs(ctx).edit().putLong(key, value).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String getString(Context ctx, String key, String def) {
        try {
            return prefs(ctx).getString(key, def);
        } catch (Throwable e) {
            return def;
        }
    }

    private static void putString(Context ctx, String key, String value) {
        try {
            prefs(ctx).edit().putString(key, value).apply();
        } catch (Throwable ignored) {
        }
    }
}
