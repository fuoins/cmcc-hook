package com.cmcchook;

import android.os.SystemClock;

/**
 * App 启动就绪判定。
 *
 * <p>为什么需要它：模块原来在 {@code Activity.onResume} 之后固定等 4 秒就跑。
 * 冷启动时那 4 秒连首页都还没渲染完 —— WebView 内核没热、App 自己的登录态还没落盘，
 * 于是离屏页面白屏、活动页被判「未登录」，任务白跑一轮。
 *
 * <p>现在的做法是等三个信号同时成立，再额外静置一会儿：
 * <ol>
 *   <li>进程已经起来至少 {@link #MIN_AGE_MS}；</li>
 *   <li>界面已经有过一次 onResume（后台唤醒时不要求）；</li>
 *   <li>App 自己已经发过业务请求（没等到就按 {@link #NET_FALLBACK_MS} 兜底）。</li>
 * </ol>
 * 最后还要求「最近一次界面/网络活动已经过去 {@link #SETTLE_MS}」，避免抢在首屏
 * 那一串并发请求中间进场。真等不到静置（某些页面会一直轮询）就在 15 秒后放行。
 *
 * <p>进程已经热了（超过 {@link #WARM_AGE_MS}）直接返回，不增加任何等待。
 */
final class AppReady {

    /** 进程起来后至少等这么久，让加固壳、Application、首页初始化跑完。 */
    private static final long MIN_AGE_MS = 9_000L;
    /** 一直没看到业务请求时，进程起来这么久也放行。 */
    private static final long NET_FALLBACK_MS = 20_000L;
    /** 最后一次活动之后静置多久。 */
    private static final long SETTLE_MS = 1_800L;
    /** 超过这个进程年龄就算「热进程」，直接放行。 */
    private static final long WARM_AGE_MS = 180_000L;
    /** 等这么久还没静置下来就不再等静置（页面持续轮询的情况）。 */
    private static final long SETTLE_GIVE_UP_MS = 15_000L;

    private static volatile long startedAt;
    private static volatile int resumeCount;
    private static volatile long firstResumeAt;
    private static volatile long lastResumeAt;
    private static volatile long firstNetAt;
    private static volatile long lastNetAt;

    private AppReady() {
    }

    /** 注入时调用一次，记下进程起点。 */
    static void markProcessStart() {
        if (startedAt == 0) {
            startedAt = SystemClock.elapsedRealtime();
        }
    }

    static void onResume() {
        long now = SystemClock.elapsedRealtime();
        resumeCount++;
        lastResumeAt = now;
        if (firstResumeAt == 0) {
            firstResumeAt = now;
        }
    }

    /** 目标 App 自己发出的请求（抓包钩子里顺手记一笔，不额外增加开销）。 */
    static void onNetwork(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        lastNetAt = now;
        if (firstNetAt == 0) {
            firstNetAt = now;
        }
    }

    static long ageMs() {
        if (startedAt == 0) {
            markProcessStart();
            return 0;
        }
        return SystemClock.elapsedRealtime() - startedAt;
    }

    /** 硬条件：年龄、界面、首个请求。 */
    private static boolean hardReady(boolean requireUi) {
        long age = ageMs();
        if (age < MIN_AGE_MS) {
            return false;
        }
        if (requireUi && resumeCount == 0) {
            return false;
        }
        return firstNetAt > 0 || age >= NET_FALLBACK_MS;
    }

    /** 硬条件 + 静置。 */
    private static boolean settled(boolean requireUi) {
        if (!hardReady(requireUi)) {
            return false;
        }
        long last = Math.max(Math.max(lastNetAt, lastResumeAt), firstResumeAt);
        return SystemClock.elapsedRealtime() - last >= SETTLE_MS;
    }

    /**
     * 等 App 就绪。可被任意后台线程调用（内部自己 sleep），不要在主线程调。
     *
     * @param requireUi 前台运行时要求界面已经起来；后台唤醒（解冻后无界面）传 false
     * @return true 就绪；false 超时（调用方照常执行，只是日志里会写明）
     */
    static boolean await(boolean requireUi, long timeoutMs) {
        if (ageMs() >= WARM_AGE_MS) {
            return true;
        }
        long begin = SystemClock.elapsedRealtime();
        long nextLog = 2_500L;
        while (true) {
            long waited = SystemClock.elapsedRealtime() - begin;
            if (settled(requireUi)
                    || (waited >= SETTLE_GIVE_UP_MS && hardReady(requireUi))) {
                if (waited >= 1_200L) {
                    HookLog.log("TASK", "等 App 就绪 · " + seconds(waited),
                            describe(requireUi) + "\n结论: 可以开始，App 启动期间不抢跑。");
                }
                return true;
            }
            if (waited >= timeoutMs) {
                HookLog.log("TASK", "等 App 就绪超时 · " + seconds(waited),
                        describe(requireUi) + "\n结论: 照常执行，失败的话多半是 App 还没起来。");
                return false;
            }
            if (waited >= nextLog) {
                HookLog.logcat("app not ready after " + waited + "ms: " + describe(requireUi));
                nextLog += 5_000L;
            }
            sleep(400L);
        }
    }

    /** 一行描述，写日志用。 */
    static String describe(boolean requireUi) {
        long age = ageMs();
        StringBuilder sb = new StringBuilder();
        sb.append("进程: ").append(seconds(age)).append('\n');
        sb.append("界面: ").append(resumeCount > 0 ? "已起来（" + resumeCount + " 次）" : "还没起")
                .append('\n');
        if (firstNetAt > 0) {
            sb.append("首个请求: 已发出（进程起来 ").append(seconds(firstNetAt - startedAt))
                    .append(" 时）\n");
        } else {
            sb.append("首个请求: 还没看到（").append(seconds(age))
                    .append(" 后按兜底放行）\n");
        }
        long last = Math.max(Math.max(lastNetAt, lastResumeAt), firstResumeAt);
        if (last > 0) {
            sb.append("静置: ").append(seconds(SystemClock.elapsedRealtime() - last));
        } else {
            sb.append("静置: 0.0s");
        }
        if (!requireUi) {
            sb.append("\n要求: 后台运行，不要求界面");
        }
        return sb.toString();
    }

    /** 给首页/日志用的短摘要。 */
    static String brief() {
        long age = ageMs();
        if (age >= WARM_AGE_MS) {
            return "热进程 " + seconds(age);
        }
        return "进程 " + seconds(age) + (resumeCount > 0 ? " · 界面✓" : " · 界面✗")
                + (firstNetAt > 0 ? " · 请求✓" : " · 请求✗");
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
