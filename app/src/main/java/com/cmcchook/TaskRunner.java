package com.cmcchook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    /** 球赛狂欢季：辽宁 lyh 加密域，页面内调方法/接口封装。 */
    static final String CMD_SOCCER = "soccer";
    /** 拆一百元礼盒：qwhdhub 明文接口。 */
    static final String CMD_TEARBOX = "tearbox";
    /** 周六游戏日（diy-client 1126086939）：qwhdhub 明文接口。 */
    static final String CMD_SATURDAY = "saturday";
    /** 追剧领福利（视频日活动 1126082530）：diy 体系 + 视频日签到。 */
    static final String CMD_VIDEO = "video";
    /** 7日打卡分百万（coc web6）：进页面点打卡。 */
    static final String CMD_DAILY7 = "daily7";
    /** AI豆苗养成记（coc3 画布 2026dmycj）：签到领阳光值 + 浇水施肥。 */
    static final String CMD_MIAO = "miao";
    /** 充值先领券（qwhdhub 砸蛋模板 1025093012）：浏览任务换次数 + 抽奖。 */
    static final String CMD_RECHARGE = "recharge";
    /** 周三充值日（hlwyxhdhub act-wedrecharge 1024101716）：七天打卡 + 8 折券 + 抽奖。 */
    static final String CMD_WED = "wed";
    /** 签到有礼里的中午 12 点秒杀抢券。 */
    static final String CMD_SECKILL = "seckill";
    /** 幸运三日签（coc3 画布）：累计签到 + 每三天抽奖。 */
    static final String CMD_LUCKY3 = "lucky3";
    /** 周周有好礼（coc3 画布）：每周/月度翻牌。 */
    static final String CMD_WEEKLY = "weekly";
    /** 签到抽奖赢好礼（wisdom-h5 #/signInLottery）：签到 + 任务 + 转盘。 */
    static final String CMD_SIGNLOTTERY = "signlottery";
    /** 乡友农业知识问答（wisdom-h5 #/answerQuestions）：答案随题目下发。 */
    static final String CMD_QUIZ = "quiz";
    /** 天天领权益(辽宁)：每日 10 点「立即领」，周周抢/月月享不在自动范围。 */
    static final String CMD_PERKS = "perks";
    /** 福利中心盘点：只拉清单，不动账号资产。 */
    static final String CMD_WELFARE = "welfare";
    /** 探路：自己进福利中心和各活动页，把证据带回来。不写今日完成状态。 */
    static final String CMD_PROBE = "probe";
    /** 单活动采集：probe:<活动号>|<地址> —— 只进这一页，采完按活动号单独存。 */
    static final String CMD_PROBE_ONE = "probe:";
    /** 通用 DIY 活动前缀：diy:1126082530 → /qwhdhub/diy-client/1126082530。 */
    static final String CMD_DIY = "diy:";
    /** 打开一个页面：open:https://... —— 只开页面，不算任务。 */
    static final String CMD_OPEN = "open:";
    /** v4.0 流量余量：离屏只读官方余量页，不进任务队列、不记今日状态。 */
    static final String CMD_FLOW = "flow";

    /** v4.33.0：模块 UI「强行停止中国移动」的兜底命令（拿不到 root 时让目标进程自我收尾）。 */
    static final String CMD_FORCESTOP = "forcestop";
    /** r19：全活动接入清单（只读诊断，不进任务队列、不发请求）。 */
    static final String CMD_CATALOG = "catalog";
    /** r19：离线自验（纯函数，不依赖真机、不发请求）。 */
    static final String CMD_SELFTEST = "selftest";
    /** r20：列出外部下发的活动描述（只读诊断）。 */
    static final String CMD_DESCRIPTORS = "descriptors";
    /** r20：一键录制（模块进程拦截编排，目标进程只负责跑活动）。 */
    static final String CMD_RECORD = "record";
    static final String CMD_RECORD_ONE = "record:";

    /**
     * r20：由 {@code TaskReceiver} 从广播 extra 透传进来的描述 JSON。
     * 模块私有文件在 Android 15 上跨进程读不到，所以下发走广播直传。
     */
    private static volatile String sDescriptorJson = "";

    static void setDescriptorJson(String json) {
        sDescriptorJson = json == null ? "" : json;
    }

    private static final String STATE_FILE = "cmcc_hook_tasks";
    private static final String KEY_HANDLED_SEQ = "handled_seq";
    private static final String KEY_LAST_RESULT = "last_result";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_LAST_AUTO = "last_auto";
    private static final String KEY_PREFIX_DONE = "done_";
    private static final String KEY_PREFIX_ATTEMPT = "attempt_";
    private static final String KEY_PREFIX_RESULT = "result_";
    private static final String KEY_PREFIX_RESULT_AT = "result_at_";

    /** 正在跑别的任务时点下的单活动采集，等这一轮结束补跑。 */
    private static final AtomicReference<String> PENDING_PROBE = new AtomicReference<>();

    /**
     * 当天失败次数上限。
     *
     * <p>现在只用于显示（首页 ✗N）和导出诊断，不再用来拉黑活动。
     * 见 {@code addPending} 的注释。
     */
    static final int MAX_ATTEMPT = 3;
    /** 两次自动跑之间的最小间隔，防止多个触发源叠加。 */
    private static final long AUTO_GAP_MS = 8 * 60_000L;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** r17：给界面生命周期埋点用 —— 退后台时判断任务是否还在跑。 */
    static boolean isRunning() {
        return RUNNING.get();
    }
    private static volatile long lastForegroundCheck;
    /** 「今天没什么可跑」「还在等下一轮」这类检查日志按时间戳去重，避免刷屏。 */
    private static volatile long lastIdleLog;
    private static volatile boolean skipLogged;
    private static volatile boolean autoOffLogged;
    private static volatile long lastBusyLog;
    /**
     * 「登录态失效按天封杀」已停用（v3.14.1）。
     *
     * <p>原机制只要页面 URL 出现 qwhdsso/login 就打标、当天停掉所有任务。
     * 但 11.9.1 抓包实证：qwhdsso/login 是正常换票流程的必经中转页
     * （活动页 → qwhdsso/login → appTokenLogin 返回 SUCCESS → 跳带 token 活动页），
     * 每次正常进活动都会命中，等于把「登录成功」误判成「登录态失效」。
     * 且打标后任务被拦、页面不再开，而解锁只能靠「模块自己开页成功」，
     * 形成死锁：标记直到跨天才失效，用户手动登录也解不开。
     *
     * <p>当初它是为防「自动任务反复开页触发扫脸核验、烧操作次数」而加；
     * 现在 auto_trigger 默认关闭、任务全部手动逐个执行，换票走 appTokenLogin
     * 直发、不加载登录页 DOM，这个前提已不存在。故整体停用：判定恒 false，
     * 页面落登录页只让本次执行失败、下次点「执行任务」可立即重试，不再封杀当天。
     */
    static boolean loginLostNow() {
        return false;
    }

    /** 停用：保留签名供旧调用点编译，不再打标、不再落盘。 */
    static void markLoginLost(Context ctx) {
    }

    /** 停用：保留签名供旧调用点编译。 */
    static void markLoginOk(Context ctx) {
    }

    private TaskRunner() {
    }

    static final class Result {
        final boolean ok;
        /** 今天确实没活儿可做（已签过 / 没抽奖次数 / 页面没按钮）。算完成，但不算失败。 */
        final boolean idle;
        /**
         * 没跑起来，但不是活动本身的问题（比如还没采到入口）。
         * 不记完成、不记失败次数，采到入口后当天还能正常跑。
         */
        final boolean skip;
        final String summary;
        final String detail;
        final String userText;

        private Result(boolean ok, boolean idle, boolean skip, String summary,
                       String detail, String userText) {
            this.ok = ok;
            this.idle = idle;
            this.skip = skip;
            this.summary = summary;
            this.detail = detail;
            this.userText = userText;
        }

        static Result ok(String summary, String detail, String userText) {
            return new Result(true, false, false, summary, detail, userText);
        }

        /** 无需执行：状态正常，只是今天没有可做的。按完成记账，避免每轮都重跑。 */
        static Result idle(String summary, String detail, String userText) {
            return new Result(true, true, false, summary, detail, userText);
        }

        static Result fail(String summary, String detail, String hint) {
            return new Result(false, false, false, summary, detail, hint);
        }

        /** 环境没准备好（入口没采到），这一轮不算失败。 */
        static Result skip(String summary, String detail, String hint) {
            return new Result(false, false, true, summary, detail, hint);
        }
    }

    // ------------------------------------------------------------ 触发

    /**
     * v3.65 一次性迁移：3.64 把「进过答题路由」当成「答完了」。11-08 08:15 那次
     * 题目数据没取到、一个字没提交，服务端还留着机会，本地却把这一天锁死了。
     * 升级后清一次当天的问答锁，把机会还回去。
     */
    private static void migrateQuizUnlock(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            if (prefs(ctx).getBoolean("mig_quiz_unlock_365", false)) {
                return;
            }
            clearDone(ctx, CMD_QUIZ);
            prefs(ctx).edit().putBoolean("mig_quiz_unlock_365", true).apply();
            HookLog.logcat("migrate v3.65: quiz lock cleared");
        } catch (Throwable ignored) {
        }
    }

    static void onForeground(Activity activity) {
        if (activity == null) {
            return;
        }
        migrateQuizUnlock(activity);
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
        // 三个开关各自管一个活动：开着且今天没成功过就补跑。
        // 不需要用户进活动页，App 被拉到前台（哪怕在后台）就够。
        if (!HookPrefs.autoSign()) {
            HookLog.logcat("auto check skipped: 自动总开关关着");
            if (!autoOffLogged) {
                autoOffLogged = true;
                HookLog.log("TASK", "自动检查 · 总开关关着",
                        "自动执行总开关是关的，所有活动都不会自动跑。\n"
                                + "在模块设置里打开总开关，或点活动弹窗里的「执行任务」单独跑。");
            }
            return;
        }
        // 手动模式：自动触发默认关。任务只在用户点「执行任务」/「过任务」时跑，
        // 逐个击破阶段不让回前台自动跑打乱节奏。想恢复自动在模块设置里开。
        if (!HookPrefs.autoTrigger()) {
            HookLog.logcat("auto check skipped: 自动触发关着（手动模式）");
            return;
        }
        final String pending = pendingAuto(activity);
        if (pending.isEmpty()) {
            // 冷启动时把「为什么什么都没发生」写进日志。
            // 以前这里一声不响，日志里只剩一个 APP-START，根本分不清是
            // 触发没生效、还是今天确实都做完了。
            explainIdle(activity);
            return;
        }
        if (tooSoon(activity)) {
            HookLog.logcat("auto task skipped: last run was too recent");
            if (!skipLogged) {
                skipLogged = true;
                HookLog.log("TASK", "自动检查 · 等待下一轮",
                        "距上次自动执行不到 " + (AUTO_GAP_MS / 60_000L) + " 分钟，本次不重复跑。\n"
                                + "待跑: " + targetLine(pending) + "\n"
                                + "今日: " + todayLine(activity));
            }
            return;
        }
        skipLogged = false;
        markAutoRun(activity);
        // 不再「固定等 4 秒」：冷启动那 4 秒连首页都没渲染完，跑起来必然白屏。
        // 真正的入场时机交给 execute() 里的 AppReady 门禁。
        execute(activity, pending, "自动");
    }

    /** 冷启动时解释一句「为什么这次没有自动跑」。 */
    private static void explainIdle(Context ctx) {
        // logcat 每次都留痕；页面日志 10 分钟一条，既不刷屏又能定位「为什么没跑」。
        HookLog.logcat("auto check: nothing pending. " + switchLine() + " / " + todayLine(ctx));
        long now = System.currentTimeMillis();
        if (now - lastIdleLog < 10 * 60_000L) {
            return;
        }
        lastIdleLog = now;
        HookLog.log("TASK", "自动检查 · 今天没有待跑的活动",
                "开关: " + switchLine() + "\n"
                        + "今日: " + todayLine(ctx) + "\n"
                        + "没有活动待跑：要么都成功过了，要么开关全关。\n"
                        + "要强制再跑一遍，点活动弹窗里的「执行任务」。");
    }

    /** 按三个开关算出今天还没跑的活动，逗号分隔。 */
    /**
     * 自动执行时今天还没跑的活动。
     *
     * <p>按福利页的开关算，不再只认签到/砸蛋/鹅蛋三个 —— 内置表里的
     * diy 活动（追剧、游戏中心等）打开开关后同样该自动跑。
     */
    static String pendingAuto(Context ctx) {
        if (!HookPrefs.autoSign()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (WelfareCatalog.Item item : WelfareCatalog.items(ctx)) {
                if (WelfareCatalog.runnable(item) && WelfareCatalog.enabled(ctx, item)) {
                    addPending(sb, ctx, item.cmd, true);
                }
            }
        } catch (Throwable ignored) {
        }
        if (sb.length() == 0) {
            // 内置表读不到时退回三个基础活动，别让自动执行整个哑掉。
            addPending(sb, ctx, CMD_SIGN, HookPrefs.pageSign());
            addPending(sb, ctx, CMD_EGG, HookPrefs.pageEgg());
            addPending(sb, ctx, CMD_GOOSE, HookPrefs.pageGoose());
        }
        return sb.toString();
    }

    /**
     * 单个活动是否还该跑：开关开着、今天没成功过。
     *
     * <p>以前这里还有一条「当天失败满 3 次就拉黑」。那条规则是抄小黄鱼的，
     * 但小黄鱼的任务失败大多是业务真的做完了；这边失败几乎全是登录态、页面没起来、
     * 接口偶发超时 —— 这些都会自己好。拉黑的结果就是用户看到的
     * 「今天只做了一个活动」，另一个活动一声不响地被跳过。
     *
     * <p>现在不再按次数拉黑，只靠 {@link #tooSoon} 控制频率：
     * 每轮自动间隔 8 分钟，失败了下一轮还会再试，成功了当天就不再跑。
     */
    private static void addPending(StringBuilder sb, Context ctx, String cmd, boolean on) {
        if (!on || isDoneToday(ctx, cmd)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(cmd);
    }

    /** 这个活动今天为什么没进自动队列（写日志用，省得用户以为漏做了）。 */
    static String skipReason(Context ctx, String cmd) {
        if (!HookPrefs.autoSign()) {
            return "自动总开关关着";
        }
        if (isDoneToday(ctx, cmd)) {
            // v4.20.0-r34：这里以前写「今天已完成」，可当天成功过、后来手动重跑失败时，
            // 抬头显示 ✗、跳过理由写「已完成」，同一份日志两句话打架。
            // 真实语义是「今天已经成功过，不再重跑」，按这个写。
            return "今天已成功过";
        }
        return "";
    }

    static int attemptsToday(Context ctx, String cmd) {
        if (ctx == null) {
            return 0;
        }
        return getInt(ctx, KEY_PREFIX_ATTEMPT + cmd + "_" + dayKey(), 0);
    }

    static void markAttempt(Context ctx, String cmd) {
        if (ctx == null || cmd == null || cmd.isEmpty()) {
            return;
        }
        for (String one : cmd.split(",")) {
            String key = one.trim();
            if (key.isEmpty()) {
                continue;
            }
            String name = KEY_PREFIX_ATTEMPT + key + "_" + dayKey();
            putInt(ctx, name, getInt(ctx, name, 0) + 1);
        }
    }

    /** 距上次自动跑不足这个间隔就跳过，避免多个触发源叠加把任务连跑好几遍。 */
    static boolean tooSoon(Context ctx) {
        if (ctx == null) {
            return false;
        }
        long last = getLong(ctx, KEY_LAST_AUTO, 0L);
        return System.currentTimeMillis() - last < AUTO_GAP_MS;
    }

    static void markAutoRun(Context ctx) {
        if (ctx != null) {
            putLong(ctx, KEY_LAST_AUTO, System.currentTimeMillis());
        }
    }

    /**
     * 目标 App 是否在前台（含小窗 / 可见）。
     *
     * 抄小黄鱼的 importance 判断：<= 250 算可见。
     * 后台不硬跑——Android 12+ 会限制 cached 进程的网络，跑了也是全部失败，
     * 还会把本来能做的任务白白记成失败。
     */
    static boolean hostVisible(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return true;
            }
            int pid = android.os.Process.myPid();
            List<android.app.ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) {
                return true;
            }
            for (android.app.ActivityManager.RunningAppProcessInfo info : list) {
                if (info.pid == pid) {
                    return info.importance <= 250;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
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
        executeAuto(cmd, from, null);
    }

    /** 带结束回调的版本：广播触发的路径要等任务跑完才能 finish 掉 goAsync。 */
    static void executeAuto(final String cmd, final String from, final Runnable done) {
        execute(HookLog.context(), cmd, from, done);
    }

    private static void execute(final Context activity, final String cmd, final String from) {
        execute(activity, cmd, from, null);
    }

    private static void execute(final Context activity, final String cmd, final String from,
                                final Runnable done) {
        if (!RUNNING.compareAndSet(false, true)) {
            HookLog.logcat("task already running, skip " + cmd);
            if (cmd != null && cmd.startsWith(CMD_PROBE_ONE)) {
                // 采集是用户当场点的，不能像定时任务那样静默丢掉：
                // 记下来，等当前这一轮跑完自动开始。
                PENDING_PROBE.set(cmd);
                HookLog.log("TASK", "采集排队 · 正在跑别的任务",
                        "同时只跑一个任务，免得两套页面互相顶掉。这一轮结束会自动开始采集，不用再点。");
            } else {
                long now = System.currentTimeMillis();
                if (now - lastBusyLog > 60_000L) {
                    lastBusyLog = now;
                    HookLog.log("TASK", "任务忙 · 这次没排上",
                            "正在跑别的任务，请求「" + label(cmd) + "」（" + from + "触发）没执行。\n"
                                    + "等当前这轮跑完再点一次「执行任务」就行。");
                }
            }
            if (done != null) {
                done.run();
            }
            return;
        }
        HookLog.log("TASK", "任务开始 · " + label(cmd) + "（" + from + "触发）",
                "本次: " + targetLine(cmd) + "\n"
                        + "今日: " + todayLine(HookLog.context()) + "\n"
                        + "开关: " + switchLine() + "\n"
                        + skipLine(HookLog.context(), cmd) + "\n"
                        + "入口: " + entry(resolveEntry(cmd))
                        + "\nCookie: " + CookieStore.summary());
        // r18：这里不再启停保活服务。本方法跑在中国移动进程里，KeepAliveService 在模块包，
        // 跨 UID 且目标包无此组件，调用只会抛异常（r17 就是这么静默失败的）。
        // 启动在 MainActivity.runTask（模块进程）；结束时由下面 finally 发一条广播回去。
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Result result;
                try {
                    // 入场门禁：等 App 真正起来再动手。
                    // 之前这里固定等 4 秒，冷启动时页面还是白的，鹅蛋必然判「未登录」。
                    boolean ui = hostVisible(activity);
                    // v4.0.9：所有执行都改在 App 官方真页里跑，所以先把 App 拉到前台。
                    // 在前台这个判断就是一次进程重要性查询，不产生任何额外动作。
                    if (!RealPage.foreground()) {
                        HookLog.log("TASK", "App 不在前台 · 自动拉起",
                                "任务: " + label(cmd) + "（" + from + "触发）\n"
                                        + "所有功能都在 App 官方真页里执行，会先把中国移动拉到前台。");
                        RealPage.ensureForeground();
                        ui = hostVisible(activity);
                    }
                    boolean ready = AppReady.await(ui, ui ? 45_000L : 20_000L);
                    HookLog.logcat("app ready=" + ready + " (" + AppReady.brief() + ")");
                    RealPage.beginSession();
                    try {
                        result = dispatch(cmd);
                } finally {
                    // 任务结束把页面关掉，不留一堆窗口在 App 里。
                    // r8：快路径命中时不经过 runOne 的 WebSession.end()，这里只清引用，
                    // 页面统一交给 closeOwned 关，避免重复关闭同一个真页。
                    WebSession.detach();
                    RealPage.closeOwned();
                    RealPage.endSession();
                }
                } catch (Throwable e) {
                    result = Result.fail("任务异常：" + e, android.util.Log.getStackTraceString(e),
                            "把日志发我。");
                }
                try {
                    finish(activity, cmd, result);
                } catch (Throwable e) {
                    HookLog.logcat("task finish failed: " + e);
                } finally {
                    // 不管 finish 出什么问题，锁都要放掉，否则后面所有任务都被跳过。
                    // r18：保活服务在模块进程，这里只能发广播让它停（见 notifyTaskEnd）。
                    notifyTaskEnd();
                    RUNNING.set(false);
                    String next = PENDING_PROBE.getAndSet(null);
                    if (next != null) {
                        // 排队的采集接着跑；广播的 pending 交给它收尾，别在这里提前放掉。
                        executeAuto(next, "排队补采", done);
                        return;
                    }
                    if (done != null) {
                        try {
                            done.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    /**
     * r18：告诉模块进程「这一轮结束了，可以把前台服务降下来」。
     *
     * <p>本方法在目标进程中执行，发不了模块包的组件，只能发一条显式广播；
     * 模块侧的 LogReceiver 收到后 stopService。广播丢了也不会常驻——
     * KeepAliveService 自己有 15 分钟兜底超时。
     */
    private static void notifyTaskEnd() {
        try {
            Context ctx = HookLog.context();
            if (ctx == null) {
                return;
            }
            Intent i = new Intent(HookLog.ACTION_TASK_END);
            i.setPackage(HookLog.MODULE_PACKAGE);
            ctx.sendBroadcast(i);
        } catch (Throwable e) {
            HookLog.logcat("notifyTaskEnd failed: " + e);
        }
    }

    /**
     * v4.10.0：一键末尾「统一收尾轮」。
     *
     * <p>抽奖 / 投喂 / 收蛋 / 抽拼图这类「消耗型」动作，各活动内部已经改到
     * 「任务全部做完之后」再执行，并循环到余额为 0。但同一活动后做的任务、
     * 或别的活动补发的次数，仍可能攒下新的余额。一键过全部在所有活动跑完后，
     * 再对下面这几个活动补跑一轮：此时任务都已完成，各活动会自然跳过任务阶段、
     * 只把服务端口径下的最新余额抽 / 喂 / 收到 0。
     *
     * <p>为什么不新造「只收尾」入口：这几个活动的开页 / 换票 / 登录链路各不
     * 相同，复用 runOne 最稳。收尾结果只写进明细，不改主流程的完成计数与成败。
     */
    private static final String[] SWEEP_CMDS = {
            CMD_GOOSE, CMD_SATURDAY, CMD_WEEKLY, CMD_SIGNLOTTERY
    };

    private static boolean inCmds(String[] cmds, String cmd) {
        for (String c : cmds) {
            if (cmd.equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static Result dispatch(String cmd) {
        if (CMD_ALL.equals(cmd)) {
            return runAll();
        }
        // r19：两个只读诊断命令。不写今日状态、不进 SWEEP、不触发保活服务。
        if (CMD_CATALOG.equals(cmd)) {
            return Result.ok("全活动接入清单 · " + ActivityCatalog.summary(),
                    ActivityCatalog.report(), ActivityCatalog.summary());
        }
        if (CMD_SELFTEST.equals(cmd)) {
            // r20：把录制导出 / 描述 schema / 通用执行器 / 会话编排的自验一起跑，
            // 纯函数、不发请求、不依赖真机，一轮就能验证整套闭环的逻辑正确性。
            StringBuilder all = new StringBuilder();
            all.append("== 离线自验（r20 全链路）==\n\n");
            String[] checks = {
                    ActivityCatalog.selfCheck(),
                    ActivityDescriptor.selfCheck(),
                    DescriptorRunner.selfCheck(),
                    CaptureExport.selfCheck(),
                    CaptureSession.selfCheck()
            };
            int fail = 0;
            for (String c : checks) {
                all.append(c).append("\n\n");
                if (!c.startsWith("PASS")) {
                    fail++;
                }
            }
            String text = all.toString();
            String head = fail == 0
                    ? "PASS " + checks.length + "/" + checks.length + " 组全通过"
                    : "FAIL " + fail + "/" + checks.length + " 组未通过";
            if (fail == 0) {
                return Result.ok("离线自验 · " + head, text, head);
            }
            return Result.fail("离线自验 · " + head, text, "把完整日志发我，按失败项修。");
        }
        if (CMD_DESCRIPTORS.equals(cmd)) {
            String rep = ActivityDescriptor.report(HookLog.context());
            String v = ActivityDescriptor.validateAll(ActivityDescriptor.load(HookLog.context()));
            return Result.ok("活动描述清单 · " + v.split("\n")[0], rep, v.split("\n")[0]);
        }
        if (cmd.startsWith(ActivityDescriptor.CMD_DESC)) {
            return runDescriptor(cmd.substring(ActivityDescriptor.CMD_DESC.length()));
        }
        if (CMD_RECORD.equals(cmd) || cmd.startsWith(CMD_RECORD_ONE)) {
            // 录制编排在模块进程（开关 / 清流 / 导出都在那边），这里只负责把活动真跑一遍。
            // 若命令是外部广播直接发来的，也照常跑活动，不因缺编排而空转。
            String inner = cmd.startsWith(CMD_RECORD_ONE)
                    ? cmd.substring(CMD_RECORD_ONE.length()) : CMD_ALL;
            if (inner.isEmpty()) {
                inner = CMD_ALL;
            }
            return runOne(inner);
        }
        // r20：描述里显式 enabled 且 cmd 命中新命令的，走通用执行器；
        // 模块原有 20 个命令一个都不在这里，默认零回归。
        JSONObject wired = ActivityDescriptor.enabledFor(HookLog.context(), cmd);
        if (wired != null) {
            StringBuilder detail = new StringBuilder();
            Result r = DescriptorRunner.run(HookLog.context(), wired, detail);
            HookLog.log(r.ok ? "DESC" : "DESC-FAIL", wired.optString("name", cmd) + " · " + r.summary,
                    detail.toString());
            return r;
        }
        if (CMD_WELFARE.equals(cmd)) {
            return WelfareFeed.discover();
        }
        if (CMD_PROBE.equals(cmd)) {
            // 先就地采集（App 当前页面），拿不到再离屏开一次。
            // 福利专区的入口只在页面自己的 localStorage / Vue 数据里，
            // 靠外部拼接口永远对不上。
            return WelfareCollect.run();
        }
        if (cmd.startsWith(CMD_PROBE_ONE)) {
            return probeOne(cmd.substring(CMD_PROBE_ONE.length()));
        }
        if (cmd.startsWith(CMD_PROBE_LIVE)) {
            return probeLive(cmd.substring(CMD_PROBE_LIVE.length()));
        }
        if (cmd.indexOf(',') > 0) {
            return runList(cmd.split(","));
        }
        return runOne(cmd);
    }

    /** 引导式采集：前台页面 + 用户手动点按钮。命令形如 live:1025093012|url */
    static final String CMD_PROBE_LIVE = "live:";

    private static Result probeLive(String raw) {
        String frag = raw == null ? "" : raw;
        String url = "";
        int bar = frag.indexOf('|');
        if (bar > 0) {
            url = frag.substring(bar + 1).trim();
            frag = frag.substring(0, bar).trim();
        }
        if (frag.isEmpty()) {
            return Result.fail("引导采集失败：命令里没有活动号", "", "");
        }
        WelfareCatalog.Item item = WelfareCatalog.byFrag(frag);
        if (url.isEmpty()) {
            url = item != null ? item.url : WelfareCatalog.builtUrl(frag);
        }
        if (url == null || url.isEmpty()) {
            return Result.fail("引导采集失败：这个活动还没有地址", "",
                    "先在清单里确认它能打开，或复制链接检查。");
        }
        String name = item != null ? item.name : "新入口 · " + frag;
        return LiveProbe.runLive(frag, name, url, new StringBuilder());
    }

    /**
     * r20：执行一条外部下发的活动描述。
     *
     * <p>描述 JSON 由模块进程在发命令时通过广播 extra 直传（私有文件在 Android 15
     * 上跨进程读不到）。也兼容从模块进程私有 descriptors.json 里按 id 取。
     * 只有 {@code enabled=true} 的描述才会真跑，草稿一律拒绝执行，避免假 ✓。
     */
    private static Result runDescriptor(String id) {
        JSONObject d = null;
        // 1) 优先用广播直传的 JSON（单条或 descriptors 数组）
        String carried = sDescriptorJson;
        if (!carried.isEmpty()) {
            try {
                JSONObject root = new JSONObject(carried);
                JSONArray arr = root.optJSONArray("descriptors");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject one = arr.optJSONObject(i);
                        if (one != null && id.equals(one.optString("id", ""))) {
                            d = one;
                            break;
                        }
                    }
                } else {
                    d = root;
                }
            } catch (Throwable ignored) {
            }
        }
        // 2) 兜底：从描述文件按 id 找（模块进程内执行时有效）
        if (d == null) {
            JSONArray arr = ActivityDescriptor.load(HookLog.context());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject one = arr.optJSONObject(i);
                if (one != null && (id.equals(one.optString("id", ""))
                        || id.equals(one.optString("cmd", "")))) {
                    d = one;
                    break;
                }
            }
        }
        if (d == null) {
            return Result.fail("描述执行失败：找不到 desc:" + id,
                    "把完整描述 JSON 通过「导入描述」放进 descriptors.json，或用模块界面下发。",
                    "描述不从网络拉取，也不编译进 APK。");
        }
        String v = ActivityDescriptor.validate(d);
        String pretty;
        try {
            pretty = d.toString(2);
        } catch (Throwable e) {
            pretty = d.toString();
        }
        if (!v.startsWith("PASS")) {
            return Result.fail("描述非法 · " + v, pretty, "按 schema 修好再下发。");
        }
        if (!d.optBoolean("enabled", false)) {
            return Result.skip("描述草稿 · " + d.optString("name", id) + " 未启用",
                    pretty, "核对 jsonl 原文与成功判据后，把 enabled 改成 true 再下发。");
        }
        StringBuilder detail = new StringBuilder();
        Result r = DescriptorRunner.run(HookLog.context(), d, detail);
        HookLog.log(r.ok ? "DESC" : "DESC-FAIL",
                d.optString("name", id) + " · " + r.summary, detail.toString());
        return r;
    }

    /**
     * 单活动采集。
     *
     * <p>命令形如 {@code probe:1025093012|https://wx.10086.cn/...}：
     * 地址由模块界面按当前清单算好带下来，目标进程不用再去猜 ——
     * 采集结果存在模块进程，目标进程读不到那份清单。
     */
    private static Result probeOne(String raw) {
        String frag = raw == null ? "" : raw;
        String url = "";
        int bar = frag.indexOf('|');
        if (bar > 0) {
            url = frag.substring(bar + 1).trim();
            frag = frag.substring(0, bar).trim();
        }
        if (frag.isEmpty()) {
            return Result.fail("采集失败：命令里没有活动号", "", "");
        }
        WelfareCatalog.Item item = WelfareCatalog.byFrag(frag);
        if (url.isEmpty()) {
            url = item != null ? item.url : WelfareCatalog.builtUrl(frag);
        }
        if (url == null || url.isEmpty()) {
            return Result.fail("采集失败：这个活动还没有地址", "",
                    "先在清单里确认它能打开，或复制链接检查。");
        }
        String name = item != null ? item.name : "新入口 · " + frag;
        return WelfareProbe.runOne(frag, name, url);
    }

    /**
     * 跑单个活动。
     *
     * <p>先把这个活动的页面在离屏 WebView 里打开，再让任务借页面的上下文发请求。
     * 之前是「先直连、失败了再想办法」，而服务端认的就是页面本身，
     * 直连怎么拼都是 GOTO_LOGIN，所以现在反过来：页面先开，接口后发。
     */
    private static String entry(String url) {
        return url == null || url.isEmpty() ? "无入口（内置表里没有这个活动）" : url;
    }

    /** 按命令重新找一次入口，只在静默取数之后用。 */
    /** 地址是不是 wisdom-h5 那个 SPA（签到抽奖 / 乡友问答都在它里面）。 */
    private static boolean wisdomish(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return url.contains("wisdom-h5") || url.contains("digismart.lncmcc.com");
    }

    private static String resolveEntry(String cmd) {
        if (CMD_SIGN.equals(cmd)) {
            return TaskApi.signEntry();
        }
        if (CMD_EGG.equals(cmd)) {
            return TaskApi.eggEntry();
        }
        if (CMD_GOOSE.equals(cmd)) {
            return GooseTask.entryUrl();
        }
        if (CMD_SOCCER.equals(cmd)) {
            return SoccerTask.entryUrl();
        }
        if (CMD_TEARBOX.equals(cmd)) {
            return TaskApi.findEntry("tearbox/1026102314");
        }
        if (CMD_SATURDAY.equals(cmd)) {
            return TaskApi.findEntry("diy-client/1126086939");
        }
        if (CMD_VIDEO.equals(cmd)) {
            return TaskApi.findEntry("diy-client/1126082530");
        }
        if (CMD_DAILY7.equals(cmd)) {
            return TaskApi.findEntry("dailyLoginEvent");
        }
        if (CMD_MIAO.equals(cmd)) {
            String miao = TaskApi.findEntry("2026dmycj");
            return miao.isEmpty() ? MiaoTask.ACT_MIAO : miao;
        }
        if (CMD_RECHARGE.equals(cmd)) {
            return TaskApi.findEntry("1025093012");
        }
        if (CMD_WED.equals(cmd)) {
            return TaskApi.findEntry("1024101716");
        }
        if (CMD_SECKILL.equals(cmd)) {
            return TaskApi.signEntry();
        }
        if (CMD_LUCKY3.equals(cmd)) {
            return TaskApi.findEntry("26xysrqtyld");
        }
        if (CMD_WEEKLY.equals(cmd)) {
            return TaskApi.findEntry("zzcj2");
        }
        // v4.2.0：11-11 08:22 日志实证 —— 模块「从软件进入」签到抽奖时打开的是
        // wx.10086.cn/website/businessPlatform/shop?pageid=f381…（福利专区首页），
        // 而用户手点进的是 digismart.lncmcc.com/wisdom-h5/index.html（页面自己 SSO 换票
        // 再跳 #/signInLottery）。原因是采集把「签到抽奖」按钮所在的商城页当成了活动入口。
        // 现在只要地址不在 wisdom-h5 这个 SPA 上，就一律用内置根地址 + 路由。
        if (CMD_SIGNLOTTERY.equals(cmd)) {
            String e = TaskApi.findEntry("signInLottery");
            return wisdomish(e) ? e : WisdomTask.ACT_SIGN;
        }
        if (CMD_QUIZ.equals(cmd)) {
            String e = TaskApi.findEntry("answerQuestions");
            return wisdomish(e) ? e : WisdomTask.ACT_QUIZ;
        }
        if (cmd.startsWith(CMD_DIY)) {
            return TaskApi.findEntry("diy-client/" + cmd.substring(CMD_DIY.length()));
        }
        return "";
    }

    /**
     * r14：给这一项任务打上 trace，采集记录会带上它。
     *
     * <p>活动归属是按 URL 猜的，猜不中只能落「未归属」。trace 是确定的：
     * 这一秒模块在跑哪个活动，模块自己知道。两条线一对，报告里就能按任务窗口
     * 切出真实请求序列，也能看出「某个活动到底发了几个请求」。
     */
    private static Result runOne(String cmd) {
        RawCapture.beginTrace(cmd);
        try {
            return runOneInner(cmd);
        } finally {
            RawCapture.endTrace();
        }
    }

    private static Result runOneInner(String cmd) {
        // r8 快路径：先试「页面上下文纯请求编排」，命中就直接收工；
        // 未注册 / 失败 / 超时 / 异常一律返回 null，下面原样走现有真页链路。
        // 旧代码一行不删，某个活动的快路径在你机器上不被服务端认也不会漏做。
        Result fast = FastPath.tryRun(cmd);
        if (fast != null) {
            return fast;
        }
        String key = null;
        String url = null;
        boolean needToken = false;
        if (CMD_SIGN.equals(cmd)) {
            key = "sign";
            url = TaskApi.signEntry();
            needToken = true;
        } else if (CMD_EGG.equals(cmd)) {
            key = "egg";
            url = TaskApi.eggEntry();
            // 11-06 实证（A砸蛋1/3）：砸金蛋页 URL 全程只有 ?A_C_CODE=…，
            // 不带 token；页面自带 Cookie + Hlwyxhd-Device-Code 就能跑通
            // activity/info、user/info、task/list、lottery/remain、lotterySafely。
            // 模块前置换票（SsoLogin.ensure）反而会卡在 leadeon.getUserInfo
            // 回调超时，白等 80 秒，还把本来有效的现场判成「拿不到会话」。
            // 所以砸金蛋不做前置换票，会话有效性交给 EggTask 用真实接口探活。
            needToken = false;
        } else if (CMD_GOOSE.equals(cmd)) {
            key = "goose";
            url = GooseTask.entryUrl();
        } else if (CMD_SOCCER.equals(cmd)) {
            key = "soccer";
            url = SoccerTask.entryUrl();
            // 辽宁 lyh 域和鹅蛋同一套：会话靠 URL 上的 pc/pk，模块不换票。
            needToken = false;
        } else if (CMD_TEARBOX.equals(cmd)) {
            key = "tearbox";
            url = TaskApi.findEntry("tearbox/1026102314");
            // qwhdhub 明文接口，和砸金蛋一样只认 Cookie + Hlwyxhd-Device-Code。
            needToken = false;
        } else if (CMD_SATURDAY.equals(cmd)) {
            key = "saturday";
            url = TaskApi.findEntry("diy-client/1126086939");
            // 11-07 实证：没有 qwhd 会话的新号直接开页会被 302 到 qwhdsso/login，
            // 页面回来是空壳。先换票，换不到再让页面自己走登录。
            needToken = true;
        } else if (CMD_VIDEO.equals(cmd)) {
            key = "video";
            url = TaskApi.findEntry("diy-client/1126082530");
            // 11-07 实证（A/B 两个号）：不带票一律 GOTO_LOGIN，页面里 diyTask
            // 返回「活动信息为空」。这和砸蛋不同，它不吃 Cookie，只认 URL 上的 token。
            needToken = true;
        } else if (CMD_DAILY7.equals(cmd)) {
            key = "daily7";
            url = TaskApi.findEntry("dailyLoginEvent");
            // coc web6 用页面自己的登录态，URL 只需要 pageId + channelId。
            needToken = false;
        } else if (CMD_MIAO.equals(cmd)) {
            key = "miao";
            String miaoEntry = TaskApi.findEntry("2026dmycj");
            url = miaoEntry.isEmpty() ? MiaoTask.ACT_MIAO : miaoEntry;
            // coc3 画布，和三日签/周周同一套：页面自己的登录桥。
            needToken = false;
        } else if (CMD_RECHARGE.equals(cmd)) {
            key = "recharge";
            url = TaskApi.findEntry("1025093012");
            // qwhdhub 明文接口，和砸金蛋同一套会话：Cookie + Hlwyxhd-Device-Code。
            needToken = false;
        } else if (CMD_WED.equals(cmd)) {
            key = "wed";
            url = TaskApi.findEntry("1024101716");
            // hlwyxhdhub 的会话靠 URL 上的 token（页面地址里 QWHDSSOD…）。
            needToken = true;
        } else if (CMD_SECKILL.equals(cmd)) {
            key = "seckill";
            url = TaskApi.signEntry();
            // 秒杀接口和签到同一个域、同一份会话。
            needToken = true;
        } else if (CMD_LUCKY3.equals(cmd)) {
            key = "lucky3";
            url = TaskApi.findEntry("26xysrqtyld");
            needToken = false;
        } else if (CMD_WEEKLY.equals(cmd)) {
            key = "weekly";
            url = TaskApi.findEntry("zzcj2");
            needToken = false;
        } else if (CMD_SIGNLOTTERY.equals(cmd)) {
            key = "signlottery";
            String e = TaskApi.findEntry("signInLottery");
            url = e.isEmpty() ? WisdomTask.ACT_SIGN : e;
            // wisdom-h5 用 Cookie + 页面自己换的 ssoId，不需要 URL 上带票。
            needToken = false;
        } else if (CMD_QUIZ.equals(cmd)) {
            key = "quiz";
            String e = TaskApi.findEntry("answerQuestions");
            url = e.isEmpty() ? WisdomTask.ACT_QUIZ : e;
            needToken = false;
        } else if (CMD_PERKS.equals(cmd)) {
            key = "perks";
            url = PerksTask.entryUrl();
            // rr/actH5 的会话靠 SSO 落地时种下的 Cookie，不需要 URL 上带票。
            needToken = false;
        } else if (cmd.startsWith(CMD_DIY)) {
            String actId = cmd.substring(CMD_DIY.length());
            if (actId.isEmpty()) {
                return Result.fail("diy 命令缺少活动 ID", "", "");
            }
            key = "diy" + actId;
            url = TaskApi.findEntry("diy-client/" + actId);
            needToken = true;
        }
        if (key == null) {
            return Result.fail("未知任务：" + cmd, "", "");
        }
        if (url == null || url.isEmpty()) {
            // 还没采过就静默取一次地址：只读楼层里的跳转，不滚动、不出清单、
            // 不写「上次采集」，清单仍然只由手动「立即采集」刷新。
            WelfareCollect.ensureEntries();
            url = resolveEntry(cmd);
        }
        if (url == null || url.isEmpty()) {
            return Result.skip(label(cmd) + "：没有可用入口",
                    "内置表里没有这个活动，采集缓存里也没有。",
                    "这个活动可能已经下线，等下个版本更新内置表。");
        }
        // 11-10 22:57 实证：wisdom-h5 的 h5token 是一次性短时效凭据，旧票一律
        // 回 500。这里先确认手上有没有 30 分钟内的票，没有就自动去福利专区领一张
        // （打开福利专区真页 → 点「签到抽奖」→ 等 App 下发），全程不需要手动点。
        boolean wisdomTask = CMD_SIGNLOTTERY.equals(cmd) || CMD_QUIZ.equals(cmd);
        if (wisdomTask && RealEntry.tokenIn(url).isEmpty()) {
            StringBuilder mint = new StringBuilder();
            String tk = WelfareToken.ensure(mint);
            if (!tk.isEmpty()) {
                url = WisdomTask.entryWithToken(cmd, tk);
            }
            HookLog.log("TASK", "领取入口票据 · " + label(cmd), mint.toString());
        }
        if (wisdomTask && RealEntry.tokenIn(url).isEmpty()) {
            // 没有票就绝不进页面：这站裸地址必回 403/500，还可能白占问答机会。
            return Result.skip(label(cmd) + "：这次没领到入口票据，没有进页面",
                    "入口: " + briefUrl(url) + "\n"
                            + "领票顺序：福利专区点卡片 → SSO 短链 rr/dwz/0EL7vw → 裸入口，"
                            + "三级都没拿到 h5token。\n",
                    "稍后再点一次「执行任务」即可；模块下次会重新领票，不需要手动点福利专区。"
                            + "若连续两次都不行，把这份日志发我，短链可能换活动了。");
        }
        // v3.63：乡友问答每天只有一次机会，而且「进去就算用过」。
        // 所以进之前必须确认两件事：今天还没用过、地址上确实带着能换 ssoId 的
        // h5token。缺一样就不进 —— 白进一次比不进糟得多。
        if (CMD_QUIZ.equals(cmd)) {
            if (isDoneToday(HookLog.context(), cmd)) {
                return Result.idle(label(cmd) + "：今天已经答过",
                        "本地记着今天答过，不再重复进页面。\n",
                        "问答每天一次，进去就算用掉。明天再跑。");
            }
            String token = RealEntry.tokenIn(url);
            if (token.isEmpty()) {
                return Result.skip(label(cmd) + "：这次没领到入口票据，今天没进",
                        "入口: " + briefUrl(url) + "\n"
                                + "地址上没有 h5token。这站只认地址上的 token 换 ssoId，"
                                + "裸地址进去只会停在未登录，取不到题，还白占当天一次机会。\n",
                        "模块已经自动去福利专区试过一次领票（点「签到抽奖」由 App 现场下发），"
                                + "这次没拿到。等 App 页面加载慢的时候再点一次「执行任务」即可，"
                                + "不需要手动进福利专区。");
            }
            // 先停在签到页把 ssoId 换出来，再切答题路由 —— 这一步才是「进入」。
            // token 失效时停在签到页就能发现，答题机会一次都不浪费。
            url = WisdomTask.probeUrl(url, token);
        }
        StringBuilder login = new StringBuilder();
        boolean tokenOk = false;
        if (isCanvas(cmd)) {
            // 画布页挂载后 5 秒内拿不到 getUserInfo 就会跳 denglu 登录页。
            // 必须在开页前把 JSID 准备好：页面来要用户信息时，由模块直接回包，
            // 不再依赖 App 那个可能已经销毁的 WebView。
            SsoLogin.ensureUserInfo(login);
        }
        if (needToken) {
            // 这两个活动的页面地址必须带 SSO token（形如 ?token=QWHDSSOD...）。
            // 不带 token 直接开页面，页面会自己跳一遍登录；而离屏 WebView 没有
            // App 的登录态，跳完还是 GOTO_LOGIN —— v2.6 日志里那两个活动一声不响
            // 没跑，就是因为页面还没登录就被当成「页面就绪」了。
            String withToken = SsoLogin.ensure(url, login);
            if (withToken != null && withToken.contains("token=")) {
                url = withToken;
                tokenOk = true;
            } else {
                // App 刚被冷拉起时还没有任何带 JS 桥的 WebView，换票必然失败。
                // 先把页面打开（页面注册桥），后面再换一次。
                login.append("换票: 第一次没成，开页后再试\n");
            }
        }
        // v3.82：wisdom 两个活动优先复用用户手动打开的 App 前台真页。
        // 14:43 日志实证：另开的离屏 WebView 缺来源/瑞数环境/布局，jtH5Login 裸 URL
        // 必回 500；而同一张票在 App 前台页上 jtH5Login?LJRkIeoW=… 回 0000。
        // 有前台真页就别再开离屏页，省掉一次必然失败的登录请求和一堆噪音日志。
        boolean frontWisdom = WisdomTask.frontReady(cmd);
        if (frontWisdom) {
            HookLog.log("TASK", "复用 App 前台真页 · 跳过离屏开页 · " + label(cmd),
                    WisdomPage.status() + "\n"
                            + "说明: 用户手动打开的签到页已登录、已布局，本次直接在它上面跑脚本。\n");
            login.append("现场: 复用 App 前台真页（跳过离屏开页）\n");
        }
        // r40：把「这次用什么入口开的页」写清楚，好对照「没提前打开也能跑」。
        // 短链入口由页面自己走完 SSO 现场换票，落地地址带 pc/pk 或 h5token；
        // 复用真页则是用上一次学到的带票落地页。两者都不依赖用户先手动进活动页。
        if (!frontWisdom) {
            String mode = SsoShortLink.isShortLink(url)
                    ? "短链自开真页（不依赖提前打开，门户现场换票）"
                    : (url.contains("pc=") || url.contains("h5token=") || url.contains("token=")
                        ? "复用已学到的带票落地页" : "内置 / 采集地址开真页");
            HookLog.log("TASK", "入口方式 · " + label(cmd),
                    "方式: " + mode + "\n"
                            + "入口: " + briefUrl(url) + "\n"
                            + SsoShortLink.describe(url) + "\n"
                            + "说明: 短链由页面自己走完 uniTokenValidate → rr/dwz → 落地页，"
                            + "模块不解密、不拼加密参数。");
        }
        if (!frontWisdom && !beginSessionFast(key, url)) {
            HookLog.log("TASK", "活动页没起来 · " + label(cmd),
                    "URL: " + briefUrl(url) + "\n" + login);
            // 页面没起来是环境问题，不是活动本身拒绝。不计失败次数，
            // 下一轮或用户手动打开一次 App 之后还能正常跑。
            return Result.skip(label(cmd) + "：活动页没有打开",
                    "URL: " + briefUrl(url) + "\n" + login,
                    "App 的 WebView 没能加载活动页。重开一次中国移动 App 再试。");
        }
        if (needToken && !tokenOk) {
            // 页面开着，App 的 JS 桥已经被捕获，再换一次票；
            // 换成后 Cookie 进 CookieManager，现场请求自然带新会话。
            String withToken = SsoLogin.ensure(url, login);
            if (withToken != null && withToken.contains("token=")) {
                login.append("换票: 开页后换到票\n");
                // 换完票把会话 Cookie 灌进 WebView CookieManager，快速模式下
                // 不用重开页面，fetch 直接带新会话。
                CookieStore.flushToWebView();
                // 带票导航一次让 Referer 落在真实活动页上（不等渲染）。
                if (WebSession.beginFast(key, withToken, 20_000L)) {
                    login.append("带票导航页面: OK\n");
                } else {
                    login.append("带票导航页面: 失败，继续用当前现场\n");
                }
            } else {
                login.append("换票: 开页后仍失败（多半是 App 登录态过期，"
                        + "去 App 里重新登录一次再试）\n");
            }
        } else if (needToken && tokenOk) {
            // 开场前就换到票：直接把会话 Cookie 灌进 WebView，页面 fetch 带上。
            CookieStore.flushToWebView();
        }
        HookLog.log("TASK", "换票 · " + label(cmd), login.toString());
        try {
            if (CMD_SIGN.equals(cmd)) {
                return SignTask.run();
            }
            if (CMD_EGG.equals(cmd)) {
                return EggTask.run(TaskApi.eggEntry(), "砸金蛋");
            }
            if (CMD_RECHARGE.equals(cmd)) {
                return EggTask.run(TaskApi.rechargeEntry(), "充值先领券");
            }
            if (CMD_SOCCER.equals(cmd)) {
                return SoccerTask.run();
            }
            if (CMD_TEARBOX.equals(cmd)) {
                return TearBoxTask.run();
            }
            if (CMD_SATURDAY.equals(cmd)) {
                return DiyTask.run("1126086939", url);
            }
            if (CMD_VIDEO.equals(cmd)) {
                return DiyTask.run("1126082530", url);
            }
            if (CMD_DAILY7.equals(cmd)) {
                return CanvasTask.run(CanvasTask.KIND_DAILY7, url);
            }
            if (CMD_MIAO.equals(cmd)) {
                return MiaoTask.run(url);
            }
            if (CMD_WED.equals(cmd)) {
                return WedTask.run();
            }
            if (CMD_SECKILL.equals(cmd)) {
                return SignTask.runSeckill();
            }
            if (CMD_LUCKY3.equals(cmd)) {
                return CanvasTask.run(CanvasTask.KIND_LUCKY3, url);
            }
            if (CMD_WEEKLY.equals(cmd)) {
                return CanvasTask.run(CanvasTask.KIND_WEEKLY, url);
            }
            if (CMD_SIGNLOTTERY.equals(cmd)) {
                return WisdomTask.runSign(url);
            }
            if (CMD_QUIZ.equals(cmd)) {
                return WisdomTask.runQuiz(url);
            }
            if (CMD_PERKS.equals(cmd)) {
                return PerksTask.run(url);
            }
            if (cmd.startsWith(CMD_DIY)) {
                return DiyTask.run(cmd.substring(CMD_DIY.length()), url);
            }
            return GooseTask.run();
        } finally {
            WebSession.end();
        }
    }

    /**
     * r8：所有活动的开页都先试「轻量上下文」（只等文档建立，不等渲染），
     * 判不过或开关关掉就原样回退到原来的完整 beginFast。
     * 这样明文域不用重建 WebView 等 Vue，密文/画布域照旧走原来的现场判定。
     */
    private static boolean beginSessionFast(String key, String url) {
        if (HookPrefs.flag("fast_path", true) && WebSession.beginFastLite(key, url, 12_000L)) {
            return true;
        }
        return WebSession.beginFast(key, url, 20_000L);
    }

    /**
     * r20：阶段排序 —— 把「消耗型」活动（抽奖 / 投喂 / 收蛋）稳定挪到最后。
     *
     * <p>学上游 {@code cmcc_extra.run_games}「视频任务 +1 次放抽奖之前」和
     * {@code cmcc_wed.run}「finish_tasks → draw_all」：靠<b>顺序</b>保证「先挣次数、
     * 后消耗」，而不是所有活动跑完再回头把消耗型活动整轮重跑一遍。
     * 各活动内部本来就已把抽奖改到「任务全做完之后、抽到余额 0」（r10 起），
     * 所以只要消耗型排最后，跨活动补发的次数也能在最后一轮用掉。
     *
     * <p>稳定分区：两组内部都保留原顺序，只是把消耗型整体后移；用户 comma 列表
     * 里手动排的顺序不会被打乱。
     */
    private static String[] sortByPhase(String[] cmds) {
        java.util.List<String> earn = new ArrayList<>();
        java.util.List<String> spend = new ArrayList<>();
        for (String c : cmds) {
            // 描述活动多为未验证的新链路，同样归到最后一轮：不抢主力活动的次数。
            if (inCmds(SWEEP_CMDS, c) || c.startsWith(ActivityDescriptor.CMD_DESC)) {
                spend.add(c);
            } else {
                earn.add(c);
            }
        }
        if (spend.isEmpty()) {
            return cmds;
        }
        java.util.List<String> sorted = new ArrayList<>(earn.size() + spend.size());
        sorted.addAll(earn);
        sorted.addAll(spend);
        return sorted.toArray(new String[0]);
    }

    /** 按顺序跑指定的几个活动，每个都执行、每个都单独记账。 */
    private static Result runList(String[] cmds) {
        cmds = sortByPhase(cmds);
        StringBuilder detail = new StringBuilder();
        StringBuilder text = new StringBuilder();
        StringBuilder brief = new StringBuilder();
        // r14：一键汇总表。以前只有一行 brief + 一堆「活动结束」散日志，
        // 要看「哪几个白跑了」得自己数。这里逐项记一行四态（完成/无需操作/失败/跳过），
        // 跑完一次性写进日志和结果详情，耗时也带上 —— 一眼看出时间花在哪。
        java.util.List<String> rows = new ArrayList<>();
        // r15 P1：收尾复查只针对「主流程真跑成功」的消耗型活动。
        // 19:15 日志里鹅蛋在主流程已 skip、周周误报失败，收尾复查又各跑一遍，
        // 白花约 2 分钟且结果不进汇总表；周六补抽（主流程成功）才是有用的。
        Set<String> mainOk = new HashSet<>();
        int okCount = 0;
        int idleCount = 0;
        // v3.79：12:48 日志实证 —— 换号后 App 登录态全掉，一键过全部还是把
        // 15 项挨个跑完，qwhdsso/login 被撞了 77 次、每项都回 GOTO_LOGIN。
        // 风控期这么跑只会把 198 也送进去。连续 3 项登录态失败就熔断。
        int authFailStreak = 0;
        boolean aborted = false;
        int abortedAt = -1;
        for (int idx = 0; idx < cmds.length; idx++) {
            String cmd = cmds[idx];
            String name = label(cmd);
            long t0 = System.currentTimeMillis();
            if (aborted) {
                brief.append(name).append("– ");
                text.append(name).append("：未执行（登录态熔断后停止）\n");
                rows.add("– " + name + " · 0s · 未执行（登录态熔断）");
                continue;
            }
            brief.append(name).append(' ');
            // 上一项跑完/跳过的页面在这里收掉：一键过 15 个活动如果不关，
            // App 里会攒一摞 CommonHtml5Activity。只关模块自己打开的页面。
            RealPage.closeOwned();
            HookLog.log("TASK", "活动开始 · " + name,
                    "进度: " + (idx + 1) + "/" + cmds.length
                            + "　今日: " + todayLine(HookLog.context()));
            try {
                // r21：desc:<id> 走描述执行器，其余走原链路。这样描述活动也能复用
                // 四态汇总、登录态熔断、逐项记账，和内置活动一视同仁。
                Result one = cmd.startsWith(ActivityDescriptor.CMD_DESC)
                        ? runDescriptor(cmd.substring(ActivityDescriptor.CMD_DESC.length()))
                        : runOne(cmd);
                detail.append("=== ").append(name).append(" ===\n")
                        .append(one.detail == null ? "" : one.detail).append('\n');
                if (one.skip) {
                    // 环境没准备好，不算这一天跑过，也不计失败次数。
                    text.append(name).append("：跳过（").append(one.summary).append("）\n");
                    brief.append("– ");
                    rows.add("– " + name + " · " + secs(t0) + " · " + one.summary);
                    HookLog.log("TASK", "活动跳过 · " + name, one.summary);
                    continue;
                }
                text.append(name).append("：")
                        .append(one.ok ? one.summary : "失败（" + one.summary + "）").append('\n');
                // r16：统一四态展示口径。之前只要 Result.ok 就标 ✓，于是
                // 「完成 0 个任务 · 还剩 1 个要真实操作」「做了 1 个任务但次数没到账」
                // 也报 ✓；而「今日已签到，无需重复」又报 ✓、同样不用做的砸蛋报 ○。
                // 这里只改展示标记，不动 markDone / markResult 的状态写入。
                String mark = displayMark(one);
                brief.append(mark).append(' ');
                rows.add(mark + " " + name
                        + " · " + secs(t0) + " · " + one.summary);
                if (one.ok) {
                    okCount++;
                    // idle 表示「今天本来就没机会/已做过」，没有余额可补，
                    // 不再进收尾复查；只有真跑了动作且成功的才值得复查一次。
                    if (!one.idle) {
                        mainOk.add(cmd);
                    }
                    if (one.idle) {
                        idleCount++;
                    }
                    // 每个活动单独记完成，pendingAuto 才不会整组重跑。
                    markDone(HookLog.context(), cmd);
                } else {
                    // 只有「业务层拒绝」才计入当天失败次数。
                    // 会话过期 / 网络不通属于环境问题，下一轮还有机会自己好，
                    // 记进失败次数只会让活动当天被永久跳过 —— 之前签到整天不跑就是这么来的。
                    if (countableFailure(one.summary)) {
                        markAttempt(HookLog.context(), cmd);
                        authFailStreak = 0;
                    } else {
                        detail.append("（本次失败属于会话/网络问题，不计入当天失败次数）\n");
                        authFailStreak++;
                        if (authFailStreak >= 3 && idx + 1 < cmds.length) {
                            aborted = true;
                            abortedAt = idx + 1;
                        }
                    }
                }
                markResult(HookLog.context(), cmd, one.ok, one.idle, one.summary);
                // 失败时把明细一起写进来。以前只打 summary，「页面未登录」这种
                // 一句话看不出是没登录、还是被广告页顶掉了，还得回头翻全量日志。
                HookLog.log("TASK", "活动结束 · " + name + " " + (one.ok ? "完成" : "失败"),
                        one.ok ? one.summary : (one.summary + "\n" + tail(one.detail, 1_600)));
            } catch (Throwable e) {
                markAttempt(HookLog.context(), cmd);
                brief.append("✗ ");
                rows.add("✗ " + name + " · " + secs(t0) + " · 异常 " + e);
                detail.append("=== ").append(name).append(" ===\n异常: ").append(e).append('\n');
                text.append(name).append("：异常 ").append(e).append('\n');
            }
        }
        // r14：一键汇总表 —— 一行一个活动，四态 + 耗时 + 一句话。
        if (!rows.isEmpty()) {
            StringBuilder table = new StringBuilder("=== 一键汇总 ===\n");
            table.append("✓ 完成　○ 无需操作　✗ 失败　– 跳过\n");
            for (String r : rows) {
                table.append(r).append('\n');
            }
            // r19：一键汇总末尾带一句接入全景，别让「没打通的活动」继续隐身。
            table.append("\n接入清单: ").append(ActivityCatalog.summary()).append('\n')
                    .append("  完整缺口跑 catalog 查看；离线自验跑 selftest。\n");
            detail.insert(0, table);
            HookLog.log("TASK", "一键汇总", table.toString());
        }
        // 最后一项的页面也关掉，跑完不留窗口。
        RealPage.closeOwned();
        if (aborted) {
            HookLog.log("TASK", "一键过全部 · 登录态熔断",
                    "连续 " + authFailStreak + " 项因登录/会话失效失败，剩下的 "
                            + (cmds.length - abortedAt) + " 项已停止，没有再发请求。\n"
                            + "服务端连续回 GOTO_LOGIN / 未登录，说明 App 主登录态已经掉了"
                            + "（cmccssotoken 为空、getUserInfo 回调超时）。\n"
                            + "风控期继续撞 qwhdsso/login 只会把号送进去：请退出中国移动 App "
                            + "重新登录一次，静置几分钟，再单独点要跑的活动。");
        }
        // r16：「过任务 10/12（其中 5 项今天无需操作）」容易读成「做了 10 个」，
        // 实际是 5 个完成 + 5 个无需操作。直接按汇总表四态数出来写清楚。
        int doneN = 0, idleN = 0, skipN = 0, failN = 0;
        for (String r : rows) {
            if (r.startsWith("✓")) {
                doneN++;
            } else if (r.startsWith("○")) {
                idleN++;
            } else if (r.startsWith("–")) {
                skipN++;
            } else {
                failN++;
            }
        }
        String summary = "完成 " + doneN + " 项 · 无需操作 " + idleN + " 项"
                + " · 跳过/待办 " + skipN + " 项"
                + (failN > 0 ? " · 失败 " + failN + " 项" : "")
                + (aborted ? " · 登录态熔断，后续 " + (cmds.length - abortedAt) + " 项未跑" : "")
                + " · " + brief.toString().trim();

        // r20：删掉「一键末尾整轮重跑」。以前所有活动跑完后又对消耗型活动
        // 逐个 runOne 一遍（重新开页 / 换票 / 走 SSO 登录），只为「按最新余额补抽」。
        // 但各活动内部从 r10 起就把抽奖改到「任务全做完之后、抽到余额 0」，
        // 再加上本版 sortByPhase 把消耗型排到最后，任务补发的次数在最后一轮
        // 就已经用掉了 —— 全局重跑纯属多此一举，还平白多 4 次登录链路，
        // 风控期尤其危险（旧注释自己都写了「避免撞 qwhdsso/login」）。
        // 现在改成一句只读说明：不重开页、不换票、不发请求、不改完成计数。
        if (!aborted && !mainOk.isEmpty()) {
            int spendN = 0;
            for (String sc : SWEEP_CMDS) {
                if (mainOk.contains(sc)) {
                    spendN++;
                }
            }
            if (spendN > 0) {
                String note = "\n=== 收尾说明（r20）===\n"
                        + "消耗型活动已按阶段排在最后执行，抽奖 / 投喂 / 收蛋均在各自"
                        + "任务完成后抽到余额 0；本版不再全局重跑一轮。\n"
                        + "本次主流程成功的消耗型活动 " + spendN + " 项，无重复开页 / 换票。\n";
                detail.append(note);
                HookLog.log("TASK", "一键过全部 · 收尾（r20 顺序保证，无需重跑）", note);
            }
        }

        if (okCount == 0) {
            return Result.fail(summary, detail.toString(), text.toString().trim());
        }
        return Result.ok(summary, detail.toString(), text.toString().trim());
    }

    /**
     * 一键：三个主力活动 + 已经打开的 DIY 活动。
     *
     * <p>DIY 那几个（追剧、游戏中心、周一话费、短剧金蛋）是同一套引擎，
     * 开关在「福利」页，默认开。关掉哪个就不会进这个列表。
     */
    /**
     * 一键过全部：按福利页的开关逐个跑。
     *
     * <p>以前这里把签到/砸蛋/鹅蛋写死塞进去，等于绕过了开关 ——
     * 用户在福利页关掉一个活动，一键执行照样会跑。现在只收集
     * 「已接入且开关开着」的，关掉的就是不跑。
     */
    private static Result runAll() {
        List<String> cmds = new ArrayList<>();
        try {
            for (WelfareCatalog.Item item : WelfareCatalog.items(HookLog.context())) {
                // v4.2.0：每日 12 点话费券不进「一键全做」——一键是随手跑一轮，
                // 赶不上开抢时间，跑了也是空手；它在券页手动「进入等待」，到点由 GrabTask 抢。
                // r47：周三充值日从这条排除里拿出来。它主体是七天打卡 + 8 折券 + 抽奖，
                // 什么时候跑都能做；只有 08:00 的 88 元券要准点，抢不到不影响打卡。
                // 用户反馈「周三那个开关打开了一键却没执行」，就是被这行挡住的。
                if (CMD_SECKILL.equals(item.cmd)) {
                    continue;
                }
                if (WelfareCatalog.runnable(item) && WelfareCatalog.enabled(HookLog.context(), item)
                        && !cmds.contains(item.cmd)) {
                    cmds.add(item.cmd);
                }
            }
        } catch (Throwable ignored) {
        }
        // r21：把「已启用的活动描述」追加到一键队列。描述存在模块私有文件里，
        // 下发一条 JSON 就等于接通一个活动 —— 不用改 Java、不用重新发版。
        // 排在福利页开关之后，且 sortByPhase 会把 desc: 归到消耗阶段最后一轮，
        // 未验证的新链路不会抢主力活动的次数。
        try {
            for (String c : ActivityDescriptor.enabledCmds(HookLog.context())) {
                if (!cmds.contains(c)) {
                    cmds.add(c);
                }
            }
        } catch (Throwable ignored) {
        }
        if (cmds.isEmpty()) {
            return Result.idle("没有打开的活动", "福利页开关都关着，也没有启用的活动描述。",
                    "到「福利」页打开活动，或用「描述清单 / 导入」下发 enabled=true 的描述。");
        }
        return runList(cmds.toArray(new String[0]));
    }

    private static void finish(Context activity, String cmd, Result result) {
        Context ctx = HookLog.context();
        // 盘点不是「活动」：不写今日完成、不记失败次数，免得首页计数被它带偏。
        if (CMD_WELFARE.equals(cmd)) {
            HookLog.log(result.ok ? "TASK" : "TASK-FAIL",
                    (result.ok ? "盘点完成 · " : "盘点失败 · ") + result.summary,
                    result.detail == null ? "" : result.detail);
            return;
        }
        // r19：接入清单 / 离线自验是只读诊断，不是活动：不写今日完成、不记失败
        // 次数、不进四态汇总，免得首页计数被带偏（同「盘点/探路」的处理）。
        if (CMD_CATALOG.equals(cmd) || CMD_SELFTEST.equals(cmd)) {
            HookLog.log(result.ok ? "TASK" : "TASK-FAIL",
                    (CMD_CATALOG.equals(cmd) ? "接入清单 · " : "离线自验 · ") + result.summary,
                    result.detail == null ? "" : result.detail);
            return;
        }
        if (CMD_PROBE.equals(cmd)) {
            HookLog.log(result.ok ? "PROBE" : "TASK-FAIL",
                    (result.ok ? "探路完成 · " : "探路失败 · ") + result.summary,
                    result.detail == null ? "" : result.detail);
            return;
        }
        if (cmd.startsWith(CMD_PROBE_ONE)) {
            HookLog.log(result.ok ? "PROBE" : "TASK-FAIL",
                    (result.ok ? "采集完成 · " : "采集失败 · ") + result.summary,
                    result.detail == null ? "" : result.detail);
            return;
        }
        if (cmd.startsWith(CMD_PROBE_LIVE)) {
            HookLog.log(result.ok ? "PROBE" : "TASK-FAIL",
                    (result.ok ? "引导采集完成 · " : "引导采集失败 · ") + result.summary,
                    result.detail == null ? "" : result.detail);
            return;
        }
        if (result.skip) {
            // 没入口属于「还没准备好」，不写最近结果、不记完成、不记失败。
            HookLog.log("TASK", "任务跳过 · " + result.summary,
                    (result.detail == null ? "" : result.detail)
                            + (result.userText == null || result.userText.isEmpty()
                            ? "" : "\n" + result.userText));
            if (activity != null) {
                final String text = result.userText == null || result.userText.isEmpty()
                        ? result.summary : result.userText;
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
            return;
        }
        if (ctx != null) {
            putString(ctx, KEY_LAST_RESULT, (result.ok ? "OK " : "FAIL ") + result.summary);
            putLong(ctx, KEY_LAST_TIME, System.currentTimeMillis());
        }
        if (result.ok && cmd.indexOf(',') < 0 && !CMD_ALL.equals(cmd)) {
            // 只有单个活动才在这里记完成。
            // 逗号列表的成功数是 runList 逐个记的——以前这里再 markDone 一次，
            // 会把「1/3 成功」当成「三个都成功」，失败的活动当天再也不会重试。
            markDone(ctx, cmd);
        }
        if (cmd.indexOf(',') < 0 && !CMD_ALL.equals(cmd)) {
            // 列表的结果在 runList 里逐个记过了，这里不能覆盖成整组的摘要。
            markResult(ctx, cmd, result.ok, result.idle, result.summary);
        }
        StringBuilder detail = new StringBuilder(result.detail == null ? "" : result.detail);
        if (result.userText != null && !result.userText.isEmpty()) {
            detail.append("\n--- 摘要 ---\n").append(result.userText);
        }
        // 每次结束都带上全部活动的状态：只跑了一个时，能一眼看出其余是完成了
        // 还是失败次数到顶被跳过，不用再猜「是不是漏做了」。
        detail.append("\n--- 今日 ---\n").append(todayLine(ctx));
        String marks = todayDetail(ctx);
        if (!marks.isEmpty()) {
            detail.append('\n').append(marks);
        }
        String note = skipNote(ctx);
        if (!note.isEmpty()) {
            detail.append('\n').append(note);
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

    /** 日志里别把 token 整串打出来。 */
    static String briefUrl(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.indexOf("token=");
        String head = cut > 0 ? url.substring(0, cut) + "token=…" : url;
        cut = head.indexOf("pc=");
        if (cut > 0) {
            head = head.substring(0, cut) + "pc=…";
        }
        return head.length() <= 100 ? head : head.substring(0, 100) + "…";
    }

    /** 这次要跑哪几个活动，逗号列表拆成中文名。 */
    static String targetLine(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return "无";
        }
        String[] parts = cmd.split(",");
        StringBuilder sb = new StringBuilder();
        for (String one : parts) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(label(one.trim()));
        }
        return sb.toString();
    }

    /** 自动流程这次没带上谁，把原因写清楚，避免「怎么又只跑了一个」。 */
    static String skipLine(Context ctx, String cmd) {
        if (ctx == null || cmd == null) {
            return "跳过: -";
        }
        StringBuilder sb = new StringBuilder("跳过: ");
        boolean any = false;
        for (String one : ALL_CMDS) {
            if (cmd.contains(one)) {
                continue;
            }
            String reason = skipReason(ctx, one);
            if (reason.isEmpty()) {
                reason = "本次不在队列";
            }
            if (any) {
                sb.append("　");
            }
            sb.append(label(one)).append("（").append(reason).append("）");
            any = true;
        }
        if (!any) {
            return "跳过: 无，全部活动本次都跑";
        }
        return sb.toString();
    }

    /** 这次失败该不该算进「今天第 N 次失败」。会话/网络类不算。 */
    static boolean countableFailure(String summary) {
        if (summary == null || summary.isEmpty()) {
            return true;
        }
        String text = summary.toUpperCase(Locale.US);
        String[] exempt = {"会话", "登录", "GOTO_LOGIN", "NETWORK", "网络", "超时", "TIMEOUT",
                "拿不到"};
        for (String word : exempt) {
            if (text.contains(word.toUpperCase(Locale.US))) {
                return false;
            }
        }
        return true;
    }

    /** 明细截断：日志里只留够看清原因的一段。 */
    private static String tail(String detail, int max) {
        if (detail == null || detail.isEmpty()) {
            return "";
        }
        String text = detail.trim();
        return text.length() <= max ? text : text.substring(0, max) + "\n…（明细已截断）";
    }

    /** 画布活动：登录要先过 getUserInfo → userLoginV2。 */
    private static boolean isCanvas(String cmd) {
        return CMD_DAILY7.equals(cmd) || CMD_MIAO.equals(cmd)
                || CMD_LUCKY3.equals(cmd) || CMD_WEEKLY.equals(cmd);
    }

    /** 一键汇总表用的耗时：1 秒内给毫秒，其余给一位小数秒。 */
    private static String secs(long t0) {
        long d = System.currentTimeMillis() - t0;
        if (d < 1000L) {
            return d + "ms";
        }
        return String.format(Locale.US, "%.1fs", d / 1000.0);
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
        if (CMD_SOCCER.equals(cmd)) {
            return "球赛狂欢季";
        }
        if (CMD_TEARBOX.equals(cmd)) {
            return "拆一百元礼盒";
        }
        if (CMD_SATURDAY.equals(cmd)) {
            return "周六游戏日";
        }
        if (CMD_VIDEO.equals(cmd)) {
            return "追剧领福利";
        }
        if (CMD_DAILY7.equals(cmd)) {
            return "7日打卡分百万";
        }
        if (CMD_MIAO.equals(cmd)) {
            return "AI豆苗养成记";
        }
        if (CMD_RECHARGE.equals(cmd)) {
            return "充值先领券";
        }
        if (CMD_WED.equals(cmd)) {
            return "周三充值日";
        }
        if (CMD_SECKILL.equals(cmd)) {
            return "12点抢券";
        }
        if (CMD_LUCKY3.equals(cmd)) {
            return "幸运三日签";
        }
        if (CMD_WEEKLY.equals(cmd)) {
            return "周周有好礼";
        }
        if (CMD_SIGNLOTTERY.equals(cmd)) {
            return "签到抽奖赢好礼";
        }
        if (CMD_QUIZ.equals(cmd)) {
            return "乡友农业知识问答";
        }
        if (CMD_PERKS.equals(cmd)) {
            return "天天领权益";
        }
        if (CMD_WELFARE.equals(cmd)) {
            return "福利中心盘点";
        }
        if (CMD_PROBE.equals(cmd)) {
            return "探路";
        }
        if (cmd.startsWith(CMD_PROBE_ONE)) {
            String body = cmd.substring(CMD_PROBE_ONE.length());
            int bar = body.indexOf('|');
            return "采集 · " + (bar > 0 ? body.substring(0, bar) : body);
        }
        if (cmd.startsWith(CMD_PROBE_LIVE)) {
            String body = cmd.substring(CMD_PROBE_LIVE.length());
            int bar = body.indexOf('|');
            return "引导采集 · " + (bar > 0 ? body.substring(0, bar) : body);
        }
        if (cmd.startsWith(CMD_DIY)) {
            String name = WelfareCatalog.nameOf(cmd);
            return name == null ? "活动 " + cmd.substring(CMD_DIY.length()) : name;
        }
        if (cmd.startsWith(ActivityDescriptor.CMD_DESC)) {
            // 描述活动没有 WelfareCatalog 条目，名字从描述文件里取。
            return ActivityDescriptor.nameOfCmd(HookLog.context(), cmd);
        }
        return cmd;
    }

    // ------------------------------------------------------------ 状态

    static boolean isDoneToday(Context ctx, String cmd) {
        return getInt(ctx, KEY_PREFIX_DONE + cmd + "_" + dayKey(), 0) == 1;
    }

    /** 标记某个活动今天已完成；cmd 可能是逗号列表，拆开逐个记。 */
    /** 撤掉当天的完成标记：只给「真没提交成功」的补救用，别处不要调。 */
    static void clearDone(Context ctx, String cmd) {
        if (ctx == null || cmd == null || cmd.isEmpty()) {
            return;
        }
        try {
            prefs(ctx).edit().remove(KEY_PREFIX_DONE + cmd + "_" + dayKey()).apply();
        } catch (Throwable ignored) {
        }
    }

    static void markDone(Context ctx, String cmd) {
        if (ctx == null || cmd == null || cmd.isEmpty()) {
            return;
        }
        for (String one : cmd.split(",")) {
            String key = one.trim();
            if (key.isEmpty()) {
                continue;
            }
            putInt(ctx, KEY_PREFIX_DONE + key + "_" + dayKey(), 1);
        }
    }

    /**
     * 记录单个活动的最近一次结果，首页「今日任务」直接读它。
     * 值形如 {@code OK 签到成功 +8} / {@code FAIL 登录失败}。
     */
    static void markResult(Context ctx, String cmd, boolean ok, String summary) {
        markResult(ctx, cmd, ok, false, summary);
    }

    /**
     * r15 P2：idle（无需操作）随广播单独带一个标记。
     * 状态文本仍以 {@code OK } 开头 —— 多处 {@code startsWith("OK")} 用它判
     * 「今天已跑过、不用再跑」，改前缀会让「无需操作」的活动被反复重跑。
     * 文件头诊断摘要读那个标记显示成「○ 无需操作」，与一键汇总表口径一致。
     */
    static void markResult(Context ctx, String cmd, boolean ok, boolean idle, String summary) {
        if (ctx == null || cmd == null || cmd.isEmpty()) {
            return;
        }
        for (String one : cmd.split(",")) {
            String key = one.trim();
            if (key.isEmpty()) {
                continue;
            }
            // v4.20.0-r34：当天已经成功过、之后手动又跑了一次并失败时，
            // 以前直接覆盖成 FAIL —— 抬头「今日」画 ✗、跳过理由写「今天已完成」，
            // 同一份日志两句话打架（鹅蛋 14:50 成功 +37，15:29 重跑失败就是这样）。
            // 当天目标已经达成、策略上也不再重跑，所以按 OK 记，
            // 把这次失败的原因跟在括号里，信息不丢。
            boolean passedToday = ok || isDoneToday(ctx, key);
            String text;
            if (ok) {
                text = "OK " + shortText(summary);
            } else if (passedToday) {
                text = "OK 今天已成功过（最后一次重跑：" + shortText(summary) + "）";
            } else {
                text = "FAIL " + shortText(summary);
            }
            putString(ctx, KEY_PREFIX_RESULT + key + "_" + dayKey(), text);
            putLong(ctx, KEY_PREFIX_RESULT_AT + key, System.currentTimeMillis());
            // 结果同时回传模块进程，首页「今日」才能显示出来。
            // v4.30.0：广播只发「本次是否成功 + 当天是否成功过 + 原始 summary」，
            // 前缀由接收端按 ok 拼一次。旧版这里发的是已经带 "OK "/"FAIL " 的 text，
            // 接收端又拼一次，摘要里就出现了「OK OK 快路径命中」「FAIL FAIL …」。
            HookLog.state(key, ok, idle, passedToday, summary,
                    attemptsToday(ctx, key), dayKey());
        }
    }

    /** 某个活动今天的最近结果；null 表示今天没跑过。 */
    static String resultToday(Context ctx, String cmd) {
        if (ctx == null) {
            return null;
        }
        String value = getString(ctx, KEY_PREFIX_RESULT + cmd + "_" + dayKey(), "");
        return value.isEmpty() ? null : value;
    }

    /**
     * 今日全部活动的一行状态：
     * {@code 签到 ✓　砸蛋 ✗2　鹅蛋 ○　球赛 ○　礼盒 ○　周六 ○}
     * （✓ 成功 / ✗ 失败，数字是当天次数 / ○ 还没跑）。
     */
    static String todayLine(Context ctx) {
        if (ctx == null) {
            return "签到 ?　砸蛋 ?　鹅蛋 ?　球赛 ?　礼盒 ?　周六 ?　追剧 ?　日签 ?"
                    + "　豆苗 ?　充值 ?　周三 ?　抢券 ?　三日 ?　周礼 ?　签抽 ?　问答 ?";
        }
        StringBuilder sb = new StringBuilder();
        for (String cmd : ALL_CMDS) {
            if (sb.length() > 0) {
                sb.append("　");
            }
            sb.append(shortLabel(cmd)).append(' ').append(dayMark(ctx, cmd));
        }
        return sb.toString();
    }

    /** 已接入的全部活动，顺序即日志与首页的显示顺序。 */
    private static final String[] ALL_CMDS = {
            CMD_SIGN, CMD_EGG, CMD_GOOSE, CMD_SOCCER, CMD_TEARBOX, CMD_SATURDAY,
            CMD_VIDEO, CMD_DAILY7, CMD_MIAO, CMD_RECHARGE, CMD_WED, CMD_SECKILL,
            CMD_LUCKY3, CMD_WEEKLY, CMD_SIGNLOTTERY, CMD_QUIZ
    };

    /** 日志抬头用的两字简称，全名太长会把一行撑爆。 */
    private static String shortLabel(String cmd) {
        if (CMD_SIGN.equals(cmd)) {
            return "签到";
        }
        if (CMD_EGG.equals(cmd)) {
            return "砸蛋";
        }
        if (CMD_GOOSE.equals(cmd)) {
            return "鹅蛋";
        }
        if (CMD_SOCCER.equals(cmd)) {
            return "球赛";
        }
        if (CMD_TEARBOX.equals(cmd)) {
            return "礼盒";
        }
        if (CMD_SATURDAY.equals(cmd)) {
            return "周六";
        }
        if (CMD_VIDEO.equals(cmd)) {
            return "追剧";
        }
        if (CMD_DAILY7.equals(cmd)) {
            return "日签";
        }
        if (CMD_MIAO.equals(cmd)) {
            return "豆苗";
        }
        if (CMD_RECHARGE.equals(cmd)) {
            return "充值";
        }
        if (CMD_WED.equals(cmd)) {
            return "周三";
        }
        if (CMD_SECKILL.equals(cmd)) {
            return "抢券";
        }
        if (CMD_LUCKY3.equals(cmd)) {
            return "三日";
        }
        if (CMD_WEEKLY.equals(cmd)) {
            return "周礼";
        }
        if (CMD_SIGNLOTTERY.equals(cmd)) {
            return "签抽";
        }
        if (CMD_QUIZ.equals(cmd)) {
            return "问答";
        }
        return label(cmd);
    }

    /**
     * r16：一键汇总表用的展示标记。
     *
     * <p>只影响日志/广播里那个符号，不改 markDone / markResult 的状态写入，
     * 也不影响收尾复查名单，所以不会动到「今天已跑过」这类判据。
     * 结果文案自己承认「还剩 / 没到账 / 未确认」的一律不标 ✓，
     * 「无需重复 / 已领完 / 没有次数」统一标 ○。
     */
    private static String displayMark(Result one) {
        if (one.skip) {
            return StatusMark.NONE;
        }
        String state = (one.ok ? "OK " : "FAIL ")
                + (one.summary == null ? "" : one.summary);
        String mark = StatusMark.of(state, 0, false);
        // 文案没写明「无需操作」、但结果对象自己标了 idle 的，仍然画 ○。
        if (StatusMark.DONE.equals(mark) && one.idle) {
            return StatusMark.IDLE;
        }
        return mark;
    }

    /**
     * 文末「今日」行的标记。
     *
     * <p>v4.30.0：与文件头诊断摘要、一键汇总表共用 {@link StatusMark}。
     * 旧版这里读 {@code task_idle_}，可那个标记只写在模块进程，目标进程读不到，
     * 于是「今天已做完」的活动全被画成 ✓；而没跑过的活动又走
     * {@code result != null ? "✗" : "○"} 的兜底，被画成 ○（无需操作）。
     */
    private static String dayMark(Context ctx, String cmd) {
        return StatusMark.of(resultToday(ctx, cmd), attemptsToday(ctx, cmd),
                isDoneToday(ctx, cmd));
    }

    /** 逐活动写出今天最近一次结果，配合 {@link #todayLine} 看，不用再翻几百行日志。 */
    static String todayDetail(Context ctx) {
        if (ctx == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String cmd : ALL_CMDS) {
            String result = resultToday(ctx, cmd);
            if (result == null) {
                continue;
            }
            // v4.20.0-r34：当天成功过、后来手动重跑失败时，这里原来只印最后那条
            // FAIL，抬头却是 ✓，看着像两份日志拼在一起。按「已成功过」记，
            // 把最后一次失败的原因跟在括号里，账仍然对得上。
            if (result.startsWith("FAIL") && isDoneToday(ctx, cmd)) {
                sb.append(label(cmd)).append(": OK（今天已成功过；最后一次重跑：")
                        .append(result.substring(4).trim()).append('）');
            } else {
                sb.append(label(cmd)).append(": ").append(result);
            }
            int times = attemptsToday(ctx, cmd);
            if (times > 0) {
                sb.append("（今天失败 ").append(times).append(" 次）");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 全部活动的开关状态，写进任务日志抬头，省得怀疑「是不是关掉了」。 */
    static String switchLine() {
        // 直接显示 auto_trigger 总闸，不再用含糊的「自动开」：
        // 逐个击破阶段这个开关默认关，任务只在手动点执行时跑。
        StringBuilder sb = new StringBuilder("自动触发")
                .append(onOff(HookPrefs.autoTrigger()));
        for (WelfareCatalog.Item item : WelfareCatalog.built()) {
            if (item.state != WelfareCatalog.STATE_IMPL || item.cmd == null) {
                continue;
            }
            sb.append(" · ").append(shortLabel(item.cmd)).append('页')
                    .append(onOff(WelfareCatalog.enabled(HookLog.context(), item)));
        }
        return sb.toString();
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    /** 被自动流程跳过、但用户可能会疑惑的活动，逐条说明原因。 */
    static String skipNote(Context ctx) {
        if (ctx == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String cmd : ALL_CMDS) {
            String result = resultToday(ctx, cmd);
            if (result != null && result.startsWith("OK")) {
                continue;
            }
            int times = attemptsToday(ctx, cmd);
            if (result == null && times == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("提示: ").append(label(cmd));
            if (times > 0) {
                sb.append(" 今天试过 ").append(times).append(" 次未成功");
            }
            sb.append("；下一轮自动任务会再试（不再按次数拉黑）。");
        }
        return sb.toString();
    }

    static long resultAt(Context ctx, String cmd) {
        return ctx == null ? 0L : getLong(ctx, KEY_PREFIX_RESULT_AT + cmd, 0L);
    }

    private static String shortText(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    static String lastResult(Context ctx) {
        return getString(ctx, KEY_LAST_RESULT, "");
    }

    private static String dayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static SharedPreferences prefs(Context ctx) {
        // v3.57：今日完成/尝试次数按账号分开，换号不会把上一个号的「已完成」带过来。
        return AccountScope.prefs(ctx, STATE_FILE);
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
