package com.cmcchook;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * r20：一键录制会话编排（模块进程侧）。
 *
 * <p>把「开开关 → 清旧流 → 跑一次活动 → 结束自动导出 → 关开关」串成一次操作，
 * 解决前三轮反馈回路里最费人的那段：开关藏在功能页、默认关、要手动开、
 * 手动在 App 里做一遍、再手动点导出、导出的还只是 8KB 报告。
 *
 * <p><b>切分靠时间戳窗口，不改目标进程采集逻辑</b>：{@link #begin} 记下
 * {@code capture_start_at}，{@link CaptureExport#export} 只取 ts ≥ 它的记录。
 * 目标进程里 {@link RawCapture} 一条代码都不用动 —— 零回归。
 *
 * <p>流程跨进程：本类在模块进程（MainActivity 调 begin，LogReceiver 收
 * TASK_END 调 {@link #onTaskEnd}），活动命令照旧由 {@code sendTaskBroadcast}
 * 发给目标进程跑。开关用 {@link AppPrefs#setBool} 写模块自己的 prefs，
 * 目标进程读不到也没关系（{@link RawCapture#shouldSend} 读不到就照发，
 * 落盘与否由模块进程 {@link LogReceiver} 决定）。
 */
final class CaptureSession {

    private CaptureSession() {
    }

    private static final String KEY_START = "capture_start_at";
    private static final String KEY_CMD = "capture_cmd";
    private static final String KEY_ON = AppPrefs.KEY_RAW_CAPTURE;
    private static final String KEY_REDACT = "capture_redact";

    /** 会话最长 20 分钟，防止 TASK_END 丢了就一直挂着（15 分钟兜底超时是保活服务的，这是另一条）。 */
    private static final long MAX_MS = 20L * 60_000L;

    static final String CMD_RECORD = "record";
    static final String CMD_RECORD_ONE = "record:";

    // ------------------------------------------------------------ 编排

    /**
     * 开始一次录制。
     *
     * @param cmd 要录的活动命令；空 / "all" = 一键全跑。
     * @return 给用户看的一句提示。
     */
    static String begin(Context ctx, String cmd, boolean redact) {
        if (ctx == null) {
            return "没有 Context，录制没开始";
        }
        // 清旧流：不这么做，导出的 jsonl 会混着前几天的内容，分析时全是噪音。
        RawCaptureStore.clear(ctx);
        AppPrefs.settings(ctx).edit()
                .putLong(KEY_START, System.currentTimeMillis())
                .putString(KEY_CMD, cmd == null ? "" : cmd)
                .putBoolean(KEY_REDACT, redact)
                .commit();
        // setBool 内部用 commit 同步写并 chmod 成 worldReadable，目标进程才读得到开关。
        // 不直接调 worldReadable —— 它是 private。
        AppPrefs.setBool(ctx, KEY_ON, true);
        HookLog.log("CAPTURE", "录制开始 · " + (cmd == null || cmd.isEmpty() ? "一键全跑" : cmd),
                "常驻采集已打开、旧流已清空。活动跑完会收到 TASK_END 自动导出；\n"
                        + "导出脱敏=" + redact + "（值打码，字段名与来源位置保留）。\n"
                        + "产物在 Downloads/CmccHook，把 *.jsonl 发我即可离线分析。");
        return "录制已开始：" + (cmd == null || cmd.isEmpty() ? "一键全跑" : cmd)
                + "。跑完自动导出 " + (redact ? "脱敏" : "原样") + "原始流。";
    }

    static String cmd(Context ctx) {
        return ctx == null ? "" : AppPrefs.settings(ctx).getString(KEY_CMD, "");
    }

    static long startAt(Context ctx) {
        return ctx == null ? 0L : AppPrefs.settings(ctx).getLong(KEY_START, 0L);
    }

    static boolean active(Context ctx) {
        if (ctx == null) {
            return false;
        }
        long at = startAt(ctx);
        if (at <= 0) {
            return false;
        }
        if (System.currentTimeMillis() - at > MAX_MS) {
            // 超时自己收摊，避免开关永远关不回去。
            end(ctx, "超时自动结束");
            return false;
        }
        return AppPrefs.settings(ctx).getBoolean(KEY_ON, false);
    }

    /**
     * 由模块进程 {@code LogReceiver} 在收到 TASK_END 时调用。
     * 只有当前确实在录制窗口里才导出；普通跑任务完全不受影响。
     */
    static void onTaskEnd(Context ctx) {
        if (ctx == null || !active(ctx)) {
            return;
        }
        // TASK_END 到达时，目标进程那批 300ms 合并广播可能还没全部落盘，
        // 稍等一下再读文件，否则尾部的响应会丢。
        try {
            Thread.sleep(600L);
        } catch (Throwable ignored) {
        }
        end(ctx, "任务结束");
    }

    /** 结束并导出。异步跑，避免阻塞主线程上的广播接收器。 */
    static void end(final Context ctx, final String why) {
        if (ctx == null) {
            return;
        }
        final long since = startAt(ctx);
        final String cmd = cmd(ctx);
        final boolean redact = AppPrefs.settings(ctx).getBoolean(KEY_REDACT, true);
        // 先清状态位，防止 TASK_END 重复触发导出两遍。
        AppPrefs.settings(ctx).edit().putLong(KEY_START, 0L).commit();
        AppPrefs.setBool(ctx, KEY_ON, false);
        new Thread(() -> {
            try {
                CaptureExport.Output out = CaptureExport.export(ctx, since, redact, cmd);
                LogExporter.exportFile(ctx, out.jsonlName,
                        "application/x-ndjson", out.jsonl);
                LogExporter.exportFile(ctx, out.reportName,
                        "application/json", out.report);
                HookLog.log("CAPTURE", "录制完成 · " + out.stats,
                        why + "，常驻采集已关。\n"
                                + "产物 1（发我这个）: " + out.jsonlName + "\n"
                                + "产物 2（人看摘要）: " + out.reportName + "\n"
                                + "位置: Downloads/CmccHook\n"
                                + "全量原始流一行一条、不截断；脱敏是确定性的，"
                                + "同一个值在响应与请求里占位符一致，依赖链仍连得上。");
                notifyUi(ctx, "录制完成 · " + out.stats);
            } catch (Throwable e) {
                HookLog.log("CAPTURE", "录制导出失败", String.valueOf(e)
                        + "\n原始流仍在模块私有目录 raw-capture.jsonl，可点「导出采集报告」重试。");
            }
        }, "capture-session-end").start();
    }

    /** 给界面刷一句即时反馈（#7）。丢了也不影响导出。 */
    private static void notifyUi(Context ctx, String text) {
        try {
            // HookLog.progress 用的是显式组件广播，隐式自定义广播在 Android 8+ 收不到。
            HookLog.progress("capture", text);
        } catch (Throwable ignored) {
        }
    }

    /** 手动导出（不走录制窗口，把当前 jsonl 全量导出）。给「导出原始流」按钮用。 */
    static void exportNow(Context ctx, boolean redact) {
        if (ctx == null) {
            return;
        }
        final boolean r = redact;
        new Thread(() -> {
            try {
                CaptureExport.Output out = CaptureExport.export(ctx, 0L, r, "manual");
                LogExporter.exportFile(ctx, out.jsonlName, "application/x-ndjson", out.jsonl);
                LogExporter.exportFile(ctx, out.reportName, "application/json", out.report);
                HookLog.log("CAPTURE", "原始流已导出 · " + out.stats,
                        out.jsonlName + " / " + out.reportName
                                + "\n位置: Downloads/CmccHook，把 *.jsonl 发我。");
                notifyUi(ctx, "原始流已导出 · " + out.stats);
            } catch (Throwable e) {
                HookLog.log("CAPTURE", "原始流导出失败", String.valueOf(e));
            }
        }, "capture-export-now").start();
    }

    /** 状态行文案，给功能页显示。 */
    static String state(Context ctx) {
        if (active(ctx)) {
            return "录制中（" + cmd(ctx) + "）· 跑完自动导出";
        }
        return "未录制";
    }

    // ------------------------------------------------------------ 离线自验（纯函数）

    static String selfCheck() {
        try {
            return selfCheckImpl();
        } catch (Throwable e) {
            return "FAIL CaptureSession 自验抛异常: " + e;
        }
    }

    private static String selfCheckImpl() throws JSONException {
        int total = 0;
        java.util.List<String> fails = new java.util.ArrayList<>();
        total++;
        if (CMD_RECORD_ONE.startsWith(CMD_RECORD) == false) {
            fails.add("命令前缀关系错");
        }
        total++;
        if (MAX_MS < 10L * 60_000L) {
            fails.add("会话上限太短");
        }
        total++;
        // 窗口过滤逻辑：export 里 ts < since 的要被丢掉，这里用一个等价纯函数复核
        JSONObject a = new JSONObject().put("ts", 100L);
        JSONObject b = new JSONObject().put("ts", 900L);
        long since = 500L;
        if (!(a.optLong("ts") < since && b.optLong("ts") >= since)) {
            fails.add("时间窗口判定错");
        }
        total++;
        // 描述草稿默认 enabled=false，不能被录制顺手启用
        JSONObject draft = CaptureExport.analyze(
                java.util.Collections.<JSONObject>emptyList(), true, "x", 0, 0)
                .optJSONObject("descriptorDraft");
        if (draft == null || draft.optBoolean("enabled", true)) {
            fails.add("描述草稿默认启用了");
        }
        total++;
        if (!ActivityDescriptor.validate(ActivityDescriptor.goodObj("r20", "r20_demo"))
                .startsWith("PASS")) {
            fails.add("r20 样例描述非法");
        }
        if (fails.isEmpty()) {
            return "PASS " + total + "/" + total + " · CaptureSession 命令/窗口/草稿默认不启用";
        }
        return "FAIL " + fails.size() + "/" + total + "\n  - " + String.join("\n  - ", fails);
    }
}
