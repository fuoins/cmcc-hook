package com.cmcchook;

import java.util.Calendar;

/**
 * 抢券：提前进页面等，到点自动抢。
 *
 * <p>用户的要求（11-11）：
 * <ul>
 *   <li>「周三那个你自动领了每周三的抢的那个」—— 周三充值日里 08:00 才开的 88 元话费券，
 *       一键全做在 8 点前跑只能领个空，所以这项从「一键全做」里剥离，改成手动触发。</li>
 *   <li>「提前不是模块提前啊，是用户提前进，会在页面等着；如果用户在活动开始后进，
 *       就直接试着领」—— 所以这里不设「提前 N 分钟」的参数：用户点了就进页面，
 *       到点前后由页面自己走流程；已经过点就直接开抢。</li>
 *   <li>「12 点抢券不作为过任务」—— 同样从一键全做里剥离，单独一个入口。</li>
 * </ul>
 *
 * <p>具体动作仍复用现有实现，不重写业务：
 * <ul>
 *   <li>{@code wed}：{@link WedTask#run()}（补签到 → receiveSign 领券 → 抽奖）</li>
 *   <li>{@code seckill}：{@link SignTask#runSeckill()}（查场次 → 蹲点 → 撞击重试）</li>
 *   <li>{@code lingxi}：{@link LingxiGrab#run()}（每周二、周四 10:00，灵犀页面上直接点）</li>
 * </ul>
 *
 * <p>本类只负责三件事：先把 App 拉到前台、把抢券页打开停住（用户能看见页面在等）、
 * 到点后把活交给上面两个实现，期间每 5 秒往界面推一条倒计时。
 */
final class GrabTask {

    /** 目标进程侧的命令前缀，后面跟 {@code wed} / {@code seckill}。 */
    static final String CMD = "grab:";

    /** 最多等这么久。再长说明用户点错了，不如直接告诉他几点开抢。 */
    private static final long WAIT_LIMIT_MS = 4L * 60L * 60_000L;

    /** 到点前多久把活交出去（留出建立会话和请求往返的时间）。 */
    private static final long HANDOVER_MS = 150_000L;

    private static volatile boolean running;
    private static volatile boolean stop;
    private static volatile String currentKind = "";

    private GrabTask() {
    }

    static boolean running() {
        return running;
    }

    static String kind() {
        return currentKind;
    }

    /** 用户再点一次 = 取消等待。 */
    static void cancel() {
        if (!running) {
            return;
        }
        stop = true;
        HookLog.progress("grab", "· 已取消等待");
    }

    static void run(String rawKind) {
        String kind = rawKind == null ? "" : rawKind.trim();
        if (kind.isEmpty()) {
            return;
        }
        if (running) {
            cancel();
            return;
        }
        running = true;
        stop = false;
        currentKind = kind;
        long start = System.currentTimeMillis();
        try {
            boolean perks = kind.startsWith("perks");
            boolean perks10 = "perks10".equals(kind);
            String label = perks ? ("perks10".equals(kind) ? "天天领权益 · 10 点档" : "天天领权益 · 12 点档")
                    : "wed".equals(kind) ? "周三 88 元话费券"
                    : "lingxi".equals(kind) ? "灵犀 · 周二/周四 10 点券" : "12 点话费券";
            long at = perks ? atHour(perks10 ? 10 : 12)
                    : "wed".equals(kind) ? nextWednesday8()
                    : "lingxi".equals(kind) ? nextTueThu10() : nextNoon();
            long wait = at - System.currentTimeMillis();
            HookLog.log("GRAB", "抢券 · " + label + " · " + fmt(at),
                    "现在: " + fmt(System.currentTimeMillis())
                            + "\n开抢: " + fmt(at)
                            + "\n距开抢: " + (wait > 0 ? (wait / 1000) + " 秒" : "已过点，直接抢")
                            + "\n方式: 先把 App 拉到前台、打开抢券页停住，到点自动点；"
                            + "提前进就是在页面上等，过点才进就直接抢。");

            // 1. 打开抢券页并停住（同时把 App 拉到前台）。
            if (perks) {
                // 天天领权益：页面必须停在活动页，到点才有按钮可点，所以先开真页。
                HookLog.progress("grab", "· 打开天天领权益…");
                boolean opened = WebSession.beginFast("perks", PerksTask.entryUrl(), 25_000L);
                HookLog.progress("grab", opened ? "✓ 活动页已打开，停在每日奖品"
                        : "· 页面没打开，到点直接重试");
            } else if ("lingxi".equals(kind)) {
                // 灵犀没有已知接口，抢的是「已经停住的那一页」，所以这里只找页面，
                // 找不到就让用户手动进一次，不硬开一个空壳页。
                HookLog.progress("grab", "· 找灵犀页面…");
                StringBuilder pd = new StringBuilder();
                WebPage lingxi = LingxiGrab.prepare(pd);
                HookLog.log("GRAB", "灵犀抢券 · 页面", pd.toString());
                HookLog.progress("grab", lingxi != null
                        ? "✓ 已停在灵犀页，等开抢" : "· 没找到灵犀页，请手动进到抢券页");
            } else {
                HookLog.progress("grab", "· 打开抢券页…");
                String url = entryOf(kind);
                if (url != null && !url.isEmpty()) {
                    boolean opened = WebSession.beginFast(
                            "wed".equals(kind) ? "wedrecharge" : "sign", url, 25_000L);
                    HookLog.progress("grab", opened ? "✓ 页面已打开，停在抢券页" : "· 页面没打开，直接走接口");
                } else {
                    HookLog.progress("grab", "· 没有活动入口，直接走接口");
                }
            }

            // 2. 等到点（每 5 秒报一次倒计时，用户能看见确实在等）。
            //    天天领权益要贴着点交接：页面已经在活动页，早交过去只会空等。
            long handover = perks ? 8_000L : HANDOVER_MS;
            while (!stop) {
                long left = at - System.currentTimeMillis();
                if (left <= handover) {
                    break;
                }
                if (System.currentTimeMillis() - start > WAIT_LIMIT_MS) {
                    HookLog.progress("grab", "· 距开抢还有 " + human(left) + "，先不等了");
                    return;
                }
                HookLog.progress("grab", "· 在页面等待 · 距开抢还有 " + human(left));
                sleep(Math.min(5_000L, Math.max(500L, left - handover)));
            }
            if (stop) {
                HookLog.progress("grab", "· 已取消");
                return;
            }

            // 3. 交给现成实现。WedTask 内含「打卡 + 领券 + 抽奖」，
            //    runSeckill 内含「查场次 + 蹲到开场 + 撞击重试」。
            HookLog.progress("grab", "· 到点了，开始抢…");
            TaskRunner.Result r = perks
                    ? PerksTask.run(PerksTask.entryUrl(), at, perks10 ? "1" : "2")
                    : "wed".equals(kind) ? WedTask.run()
                    : "lingxi".equals(kind) ? LingxiGrab.run() : SignTask.runSeckill();
            String text = r == null ? "没有返回" : r.summary;
            HookLog.progress("grab", (r != null && r.ok ? "✓ " : "· ") + text);
            HookLog.log("GRAB", "抢券结束 · " + label + " · " + text,
                    "结果: " + (r == null ? "无" : (r.ok ? "成功" : "未抢到"))
                            + "\n" + (r == null ? "" : r.detail));
        } catch (Throwable t) {
            HookLog.progress("grab", "· 抢券异常: " + t);
            HookLog.log("GRAB", "抢券异常", String.valueOf(t));
        } finally {
            running = false;
            currentKind = "";
            stop = false;
            try {
                WebSession.end();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 抢券页入口：周三用充值日活动页，12 点用签到页。 */
    private static String entryOf(String kind) {
        try {
            if ("wed".equals(kind)) {
                String url = TaskApi.findEntry("1024101716");
                if (url == null || url.isEmpty()) {
                    url = TaskApi.findEntry("act-wedrecharge");
                }
                return url;
            }
            return TaskApi.signEntry();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下一个周三 08:00；今天就是周三且已过 8 点，返回「现在」（直接抢）。 */
    private static long nextWednesday8() {
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        int delta = (Calendar.WEDNESDAY - now.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        t.add(Calendar.DAY_OF_MONTH, delta);
        t.set(Calendar.HOUR_OF_DAY, 8);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (delta == 0 && now.getTimeInMillis() >= t.getTimeInMillis()) {
            // 今天就是周三、已经过了 8 点：用户就是来补抢的，别再等一周。
            return now.getTimeInMillis();
        }
        if (t.getTimeInMillis() <= now.getTimeInMillis()) {
            t.add(Calendar.DAY_OF_MONTH, 7);
        }
        return t.getTimeInMillis();
    }

    /**
     * 下一个周二 / 周四 10:00。
     *
     * <p>今天就是周二或周四、且已经过了 10 点：返回「现在」直接试一次
     * （窗口没关还能捡漏），不再等下一场。
     */
    private static long nextTueThu10() {
        Calendar now = Calendar.getInstance();
        for (int i = 0; i < 8; i++) {
            Calendar t = (Calendar) now.clone();
            t.add(Calendar.DAY_OF_MONTH, i);
            int dow = t.get(Calendar.DAY_OF_WEEK);
            if (dow != Calendar.TUESDAY && dow != Calendar.THURSDAY) {
                continue;
            }
            t.set(Calendar.HOUR_OF_DAY, 10);
            t.set(Calendar.MINUTE, 0);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (i == 0 && now.getTimeInMillis() >= t.getTimeInMillis()) {
                return now.getTimeInMillis();
            }
            if (t.getTimeInMillis() > now.getTimeInMillis()) {
                return t.getTimeInMillis();
            }
        }
        return now.getTimeInMillis();
    }

    /** 今天某个整点；已经过点就返回「现在」（直接抢，窗口没关还能捡漏）。 */
    private static long atHour(int hour) {
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.HOUR_OF_DAY, hour);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return Math.max(now.getTimeInMillis(), t.getTimeInMillis());
    }

    /** 下一个 12:00；已过 12 点返回「现在」（直接试一次）。 */
    private static long nextNoon() {
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.HOUR_OF_DAY, 12);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (now.getTimeInMillis() >= t.getTimeInMillis()) {
            // 已过 12 点：直接试，场次没结束还能抢；结束了 runSeckill 会自己说清楚。
            return now.getTimeInMillis();
        }
        return t.getTimeInMillis();
    }

    private static String human(long ms) {
        if (ms <= 0) {
            return "0 秒";
        }
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return h + " 小时 " + m + " 分";
        }
        if (m > 0) {
            return m + " 分 " + s + " 秒";
        }
        return s + " 秒";
    }

    private static String fmt(long at) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(at));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(200L, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
