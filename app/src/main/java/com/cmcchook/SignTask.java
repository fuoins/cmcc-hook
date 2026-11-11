package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 福利中心签到 + 领奖（活动 id 1021122301）。
 *
 * <p>纯接口编排：所有请求在活动页上下文里发（{@link TaskApi} → {@link WebFetch}），
 * 不打开落地页、不点 DOM、不逐个停留。会话（token + jwt + 带 token Referer）由
 * {@link TaskApi#warmUp} 一次换好，全程复用同一个 referer。
 *
 * <p>v3.17 起不再「借页面现场点按钮」：v3.16 实测离屏页停在「加载中」
 * （cards=0），uni-app 没渲染，点不到任何按钮；而用户手工点一遍要 22 分钟。
 * 参考 Python 脚本 cmcc_extra.run_mark_tasks 的实测，任务绝大多数可直接发接口完成。
 * 签名是否必需交给服务端当场回答：完成上报只发 {taskId,taskType}，
 * SUCCESS 就领奖，返回错误就如实记下并跳过（{@link MarkTasks}），不伪造 sign。
 *
 * <p>脚本里只放请求序列与判定，接口变了只改脚本；判定型逻辑放 Java 回调。
 */
final class SignTask {

    static {
        registerCallbacks();
    }

    private SignTask() {
    }

    // ------------------------------------------------------------ 脚本

    /**
     * 五段：会话诊断 / 每日签到 / 累计奖励 / 组团共签 / 连续月签 / 任务编排。
     *
     * <p>任务编排（补领、完成上报、握手、领奖、第二重礼）整体交给
     * {@code sign:markTasks}，因为要按 status 分类、按 taskInfo 取枚举、按响应
     * 决定是否握手，这些判定用 JSON 表达不了。
     */
    static final String SCRIPT = String.join("\n", new String[]{
            "{\"steps\":[",
            "{\"log\":\"▶ 会话诊断\"},",
            "{\"call\":{\"fn\":\"sign:pageScan\",\"args\":[],\"out\":\"scene\"}},",
            "{\"log\":\"现场: {scene}\"},",
            "{\"log\":\"▶ 每日签到\"},",
            "{\"req\":{\"id\":\"status\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/mark31/markstatus\",\"body\":\"{}\"}},",
            "{\"if\":{\"cond\":{\"ne\":[\"{status.code}\",\"SUCCESS\"]},\"then\":[",
            "{\"call\":{\"fn\":\"sign:refreshSession\",\"args\":[],\"out\":\"newref\"}},",
            "{\"if\":{\"cond\":{\"ne\":[\"{newref}\",\"false\"]},\"then\":[",
            "{\"set\":{\"referer\":\"{newref}\"}},",
            "{\"req\":{\"id\":\"status\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/mark31/markstatus\",\"body\":\"{}\"}}",
            "]}}",
            "]}},",
            "{\"if\":{\"cond\":{\"ne\":[\"{status.code}\",\"SUCCESS\"]},\"then\":[",
            "{\"result\":{\"kind\":\"fail\",\"text\":\"查询签到状态失败：{status.code} {status.msg}\"}}",
            "]}},",
            "{\"call\":{\"fn\":\"sign:isSignedToday\",\"args\":[\"{status.data.markstatus}\",\"{today}\"],\"out\":\"signed\"}},",
            "{\"if\":{\"cond\":{\"true\":\"{signed}\"},\"then\":[",
            "{\"log\":\"今日已签，无需重复\"}",
            "],\"else\":[",
            "{\"req\":{\"id\":\"mark\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/mark31/domark\",\"body\":\"{\\\"date\\\":\\\"{today}\\\"}\"}},",
            "{\"if\":{\"cond\":{\"ne\":[\"{mark.code}\",\"SUCCESS\"]},\"then\":[",
            "{\"result\":{\"kind\":\"fail\",\"text\":\"签到失败：{mark.code} {mark.msg}\"}}",
            "]}},",
            "{\"log\":\"domark: {mark.code} {mark.msg}\"},",
            "{\"req\":{\"id\":\"check\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/mark31/markstatus\",\"body\":\"{}\"}},",
            "{\"call\":{\"fn\":\"sign:isSignedToday\",\"args\":[\"{check.data.markstatus}\",\"{today}\"],\"out\":\"verified\"}},",
            "{\"if\":{\"cond\":{\"true\":\"{verified}\"},\"then\":[",
            "{\"log\":\"复查: 今日已签，确认完成\"},",
            "{\"set\":{\"status\":\"@check\"}}",
            "],\"else\":[",
            "{\"result\":{\"kind\":\"fail\",\"text\":\"签到接口返回成功，但复查未生效\"}}",
            "]}}",
            "]}},",
            // 累计奖励：可领清单是 markstatus.data.taskAwardChance（服务端算好的），
            // 不是从 accumulateTaskInfo 自己猜条件（旧版猜出来的 272887 全报「任务状态异常」）。
            "{\"log\":\"▶ 累计奖励\"},",
            // v3.77：把 domark 的响应也带上。今天 12:04 实测，签到时服务端在
            // domark 响应的 data.taskAwardChance 里直接给了「7 天转盘」档位
            // （272899，lottery=true），而 markstatus 里不一定有 —— 只读 status
            // 会漏掉转盘，用户看到的就是「签了但转盘还在」。
            "{\"call\":{\"fn\":\"sign:claimable\",\"args\":[\"{status.data}\",\"{mark.data}\"],\"out\":\"chances\"}},",
            "{\"for\":{\"list\":\"{chances}\",\"as\":\"c\",\"do\":[",
            "{\"req\":{\"id\":\"award\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/mark31/taskAward/{c.id}\",\"body\":\"{}\"}},",
            "{\"log\":\"累计档位 {c.id} {c.text}: {award.code} {award.msg}\"}",
            "]}},",
            "{\"log\":\"▶ 组团共签\"},",
            "{\"req\":{\"id\":\"group\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/groupmark/status\",\"body\":\"{}\"}},",
            "{\"if\":{\"cond\":{\"eq\":[\"{group.data.status}\",\"3\"]},\"then\":[",
            "{\"req\":{\"id\":\"gaward\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/groupmark/award\",\"body\":\"{}\"}},",
            "{\"log\":\"组队: {gaward.code}\"}",
            "],\"else\":[",
            "{\"log\":\"组队: 未达标或未开团\"}",
            "]}},",
            // 月签：monthInfo.data 可能是 null（账号没有月签数据），判空交给 Java，
            // 不在 for 里报「不是数组」。
            "{\"log\":\"▶ 连续月签\"},",
            "{\"call\":{\"fn\":\"sign:monthPrizes\",\"args\":[\"{status.data.userinfo.phone}\",\"{referer}\"],\"out\":\"mp\"}},",
            "{\"log\":\"月签: {mp}\"},",
            "{\"log\":\"▶ 任务编排 · 补领与统计\"},",
            "{\"call\":{\"fn\":\"sign:markTasks\",\"args\":[\"{referer}\"],\"out\":\"mt\"}},",
            "{\"result\":{\"kind\":\"ok\",\"text\":\"签到任务完成\"}}",
            "]}"
    });

    // ------------------------------------------------------------ 入口

    static TaskRunner.Result run() {
        String referer = TaskApi.signEntry();
        StringBuilder detail = new StringBuilder();

        detail.append("▶ 每日签到\n");
        detail.append("入口: ").append(referer).append('\n');
        if (referer.isEmpty()) {
            return TaskRunner.Result.fail("还没有拿到签到入口",
                    "福利专区里没有签到页的地址。",
                    "打开一次中国移动 App 的「福利专区」，让模块把入口采下来再试。");
        }
        String warmed = TaskApi.warmUp(referer, detail);
        if (warmed == null) {
            detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
            return TaskRunner.Result.fail("拿不到签到会话", detail.toString(),
                    "App 的登录态没取到。确认中国移动 App 是登录状态、"
                            + "并且冷启动后加载过首页，再试一次。");
        }
        referer = warmed;
        detail.append("会话地址: ").append(referer).append('\n');
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
        CookieStore.flushToWebView();
        TaskRunner.Result base = ScriptEngine.run(SCRIPT, "签到有礼", referer, detail);

        // v4.10.0-r2：签到、补领都走完之后，在同一个签到真页里把「功能体验区」
        // 的浏览任务逐个点掉 —— 点「去完成」→ 跳落地页 → 停留 5 秒 → 回来复查。
        // 不做接口直发、不开离屏：用户实证这些任务服务端按真实落地页停留记账。
        int liveDone = MarkTasks.runLiveTasks(WebSession.current(), referer, detail);
        if (liveDone <= 0) {
            return base;
        }
        String summary = base.summary + " · 功能体验区 " + liveDone + " 个";
        String body = detail.toString();
        if (base.ok && !base.idle) {
            return TaskRunner.Result.ok(summary, body, summary);
        }
        if (base.ok) {
            return TaskRunner.Result.idle(summary, body, summary);
        }
        if (base.skip) {
            return TaskRunner.Result.skip(summary, body, base.userText);
        }
        return TaskRunner.Result.fail(summary, body, base.userText);
    }

    // ------------------------------------------------------------ 12 点秒杀

    /** 距开场超过这么久就不蹲了，避免白占任务线程；用户 11:58 再点即可。 */
    private static final long SECKILL_WAIT_LIMIT = 150_000L;

    /**
     * 签到有礼里的「每日秒杀抢话费券」（中午 12:00 场）。
     *
     * <p>页面自己的判断链是：今天签过到 → 开场时间 &lt; 当前时间 &lt; 结束时间
     * → 按钮变「立即抢」→ POST {@code markSeckill/redeem {secId, prizeId}}。
     * 所以这里先补签到，再按时间蹲到开场，提前 150ms 发第一枪；没抢到就在
     * 场次窗口内重试（库存只有 1 张，全靠重试撞退单）。
     *
     * <p>距开场超过 2.5 分钟直接返回「几点开抢」，不阻塞任务线程。
     */
    static TaskRunner.Result runSeckill() {
        StringBuilder detail = new StringBuilder();
        String referer = TaskApi.signEntry();
        if (referer.isEmpty()) {
            return TaskRunner.Result.skip("12点抢券 · 还没有签到入口",
                    "福利专区里没有签到页的地址。",
                    "先打开一次「签到有礼」，让模块记下入口再执行。");
        }
        detail.append("入口: ").append(referer).append('\n');
        String warmed = TaskApi.warmUp(referer, detail);
        if (warmed == null) {
            detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
            return TaskRunner.Result.fail("12点抢券 · 拿不到签到会话", detail.toString(),
                    "App 的登录态没取到。确认中国移动 App 是登录状态、冷启动后加载过首页。");
        }
        referer = warmed;
        detail.append("会话地址: ").append(referer).append('\n');

        // ---- 1. 秒杀要求当天已签到，先补齐 ----
        try {
            JSONObject st = TaskApi.post("/qwhdhub/api/mark/mark31/markstatus", "{}", referer);
            JSONObject data = "SUCCESS".equals(st.optString("code"))
                    ? st.optJSONObject("data") : null;
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            boolean signed = isSignedToday(data == null ? null : data.optJSONArray("markstatus"), today);
            detail.append("今日签到: ").append(signed ? "已签" : "未签").append('\n');
            if (!signed) {
                JSONObject mark = TaskApi.post("/qwhdhub/api/mark/mark31/domark",
                        "{\"date\":\"" + today + "\"}", referer);
                detail.append("补签到: ").append(mark.optString("code"))
                        .append(' ').append(mark.optString("msg")).append('\n');
                if (!"SUCCESS".equals(mark.optString("code"))) {
                    return TaskRunner.Result.fail("12点抢券 · 补签到失败", detail.toString(),
                            "秒杀要求当天已签到。签到接口被拒，先把「签到有礼」跑通再抢。");
                }
            }
        } catch (TaskApi.TaskException e) {
            detail.append("签到检查: ").append(e.getMessage()).append('\n');
            return TaskRunner.Result.fail("12点抢券 · 签到检查失败", detail.toString(),
                    e.sessionLost() ? "Cookie 失效了，冷启动 App 后重试。"
                            : "请求没发出去，把完整日志发我。");
        }

        // ---- 2. 拿场次 ----
        JSONObject zone;
        try {
            JSONObject cfg = TaskApi.post("/qwhdhub/api/mark/markSeckill/secConfig", "{}", referer);
            JSONObject data = "SUCCESS".equals(cfg.optString("code"))
                    ? cfg.optJSONObject("data") : null;
            JSONObject sk = data == null ? null : data.optJSONObject("secKillData");
            JSONArray zones = sk == null ? null : sk.optJSONArray("secKillZones");
            if (zones == null || zones.length() == 0) {
                String text = "12点抢券 · 活动没有配置场次";
                return TaskRunner.Result.idle(text, detail.toString(), text);
            }
            zone = pickZone(zones);
            if (zone == null) {
                String text = "12点抢券 · 今天的场次已经结束";
                return TaskRunner.Result.idle(text, detail.toString(), text);
            }
            detail.append("场次: ").append(zone.optString("id")).append(" · ")
                    .append(fmt(zone.optLong("startTime"))).append(" ~ ")
                    .append(fmt(zone.optLong("endTime"))).append('\n');
        } catch (TaskApi.TaskException e) {
            detail.append("场次: ").append(e.getMessage()).append('\n');
            return TaskRunner.Result.fail("12点抢券 · 查场次失败", detail.toString(),
                    "把完整日志发我。");
        }

        JSONObject prize = zone.optJSONObject("prize");
        String secId = zone.optString("id", "");
        String prizeId = prize == null ? "" : prize.optString("id", "");
        String prizeName = prize == null ? "话费券" : prize.optString("name", "话费券");
        detail.append("奖品: ").append(prizeName).append(" · 库存 ")
                .append(prize == null ? "?" : prize.optString("leaveStock", "?")).append('\n');
        if (secId.isEmpty() || prizeId.isEmpty()) {
            return TaskRunner.Result.fail("12点抢券 · 场次配置缺参数", detail.toString(),
                    "场次里没有 secId/prizeId，把完整日志发我。");
        }

        long start = zone.optLong("startTime", 0);
        long end = zone.optLong("endTime", 0);
        long now = correctedNow(referer);
        long wait = start - now;
        if (wait > SECKILL_WAIT_LIMIT) {
            String text = "12点抢券 · " + fmt(start) + " 开抢，提前 2 分钟再点";
            detail.append("距开场 ").append(wait / 1000).append(" 秒，先不蹲。\n");
            return TaskRunner.Result.idle(text, detail.toString(), text);
        }
        if (end > 0 && now > end) {
            String text = "12点抢券 · 今天这一场已经结束";
            return TaskRunner.Result.idle(text, detail.toString(), text);
        }

        // ---- 3. 蹲点：提前 150ms 抢跑，补偿一次请求往返 ----
        if (wait > 150) {
            detail.append("蹲点: 等 ").append(wait - 150).append(" ms\n");
            sleep(wait - 150);
        }

        // ---- 4. 抢 ----
        // 关键教训（11-07 12:00 实测）：网关限流返回 GWT_LIMITED「当前活动太火爆了」，
        // 旧代码把它当成「条件不满足」立刻 break，只发了一次就收工；
        // 而用户手动连点几十次就抢到了 —— 限流是短时窗口，撞得够久就能穿过去。
        // 现在：只要还是限流/没库存，就一直撞到开场后 60 秒。
        String body = "{\"secId\":\"" + secId + "\",\"prizeId\":\"" + prizeId + "\"}";
        String last = "";
        int attempt = 0;
        // v3.48：用户反馈「模块没抢到，手动一直点就抢到了」——GWT_LIMITED 是网关
        // 令牌桶限流，不是没资格；旧窗口 60s / 200 次不够穿透，延长撞窗。
        long hardEnd = System.currentTimeMillis() + 100_000L;
        while (System.currentTimeMillis() < hardEnd && attempt < 320) {
            attempt++;
            try {
                JSONObject r = TaskApi.post("/qwhdhub/api/mark/markSeckill/redeem", body, referer);
                String code = r.optString("code", "");
                String status = r.optString("status", "");
                String msg = r.optString("msg", "");
                last = code + " " + (status.isEmpty() ? "" : status + " ") + msg;
                if ("SUCCESS".equals(code)) {
                    detail.append("第 ").append(attempt).append(" 次: SUCCESS\n");
                    String text = "12点抢券 · 抢到 " + prizeName;
                    return TaskRunner.Result.ok(text, detail.toString(), text);
                }
                // 每次都写会把日志撑爆，前 3 次和每 20 次留一条就够定位。
                if (attempt <= 3 || attempt % 20 == 0) {
                    detail.append("第 ").append(attempt).append(" 次: ").append(last).append('\n');
                }
                if (!retryableSeckill(code, status, msg)) {
                    break;
                }
            } catch (TaskApi.TaskException e) {
                last = e.getMessage();
                if (attempt <= 3) {
                    detail.append("第 ").append(attempt).append(" 次: ").append(last).append('\n');
                }
                if (e.sessionLost()) {
                    break;
                }
            }
            // 固定 300ms 容易被网关的令牌桶整段吃掉，加一点抖动更容易穿过去。
            sleep(220L + (long) (Math.random() * 130));
        }
        detail.append("共试 ").append(attempt).append(" 次\n");
        detail.append("最后返回: ").append(last).append('\n');
        String text = "12点抢券 · 没抢到（" + (last.isEmpty() ? "窗口内无成功响应" : last) + "）";
        return TaskRunner.Result.idle(text, detail.toString(), text);
    }

    /**
     * 这些返回说明「这一发没排上」，不是「没资格」，值得继续撞。
     *
     * <p>反过来，未签到、已领过、活动未开始这类，重试多少次都一样，必须立刻停。
     */
    private static boolean retryableSeckill(String code, String status, String msg) {
        String all = (code + " " + status + " " + msg).toUpperCase(Locale.US);
        if (all.contains("GWT_LIMITED") || all.contains("太火爆") || all.contains("稍后重试")
                || all.contains("繁忙") || all.contains("限流") || all.contains("系统异常")
                || all.contains("PRIZE_NO_STOCK") || all.contains("抢光") || all.contains("库存")
                || all.contains("NO_STOCK") || all.contains("SOLD_OUT")) {
            return true;
        }
        return false;
    }

    /** 优先挑正在进行的场次，其次挑最近一场没开始的。 */
    private static JSONObject pickZone(JSONArray zones) {
        long now = System.currentTimeMillis();
        JSONObject next = null;
        long nextStart = Long.MAX_VALUE;
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) {
                continue;
            }
            long s = z.optLong("startTime", 0);
            long e = z.optLong("endTime", 0);
            if (s > 0 && e > 0 && now >= s && now <= e) {
                return z;
            }
            if (s > now && s < nextStart) {
                nextStart = s;
                next = z;
            }
        }
        return next;
    }

    /**
     * 本机时间 + 服务器偏差。sysTime 只是校准项，拿不到就用本机时间；
     * 页面自己也是用 {@code new Date().getTime()} 判断开场的，不校准同样能用。
     */
    private static long correctedNow(String referer) {
        long local = System.currentTimeMillis();
        try {
            JSONObject r = TaskApi.post("/qwhdhub/api/mark/markSeckill/sysTime", "{}", referer);
            JSONObject data = r.optJSONObject("data");
            long server = 0;
            if (data != null) {
                server = data.optLong("currentTime", data.optLong("time", 0));
            } else {
                server = r.optLong("data", 0);
            }
            if (server > 1_000_000_000_000L) {
                long offset = server - local;
                // 偏差超过 2 秒才采信，避免接口返回秒级时间把窗口算飞。
                if (Math.abs(offset) > 2000 && Math.abs(offset) < 600_000) {
                    return local + offset;
                }
            }
        } catch (Throwable ignored) {
        }
        return local;
    }

    private static String fmt(long ms) {
        if (ms <= 0) {
            return "?";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 回调注册

    private static void registerCallbacks() {
        ScriptEngine.register("sign:refreshSession", args -> {
            SsoLogin.invalidate();
            String again = TaskApi.warmUp(TaskApi.signEntry(), new StringBuilder());
            return again == null ? "false" : again;
        });
        ScriptEngine.register("sign:isSignedToday", args -> {
            try {
                JSONArray marks = args.length > 0 && !args[0].isEmpty()
                        ? new JSONArray(args[0]) : null;
                String today = args.length > 1 ? args[1] : "";
                return isSignedToday(marks, today) ? "true" : "false";
            } catch (Throwable e) {
                return "false";
            }
        });
        ScriptEngine.register("sign:claimable", args -> {
            JSONObject status = args.length > 0 && !args[0].isEmpty()
                    ? safeObject(args[0]) : null;
            JSONObject mark = args.length > 1 && !args[1].isEmpty()
                    ? safeObject(args[1]) : null;
            return claimableFromChance(status, mark).toString();
        });
        ScriptEngine.register("sign:monthPrizes", args -> {
            String phone = args.length > 0 ? args[0] : "";
            String ref = args.length > 1 ? args[1] : "";
            return claimMonthPrizes(phone, ref);
        });
        // 会话诊断：只看页面上下文活没活，不点按钮（PageLive.trigger 已停用）。
        ScriptEngine.register("sign:pageScan", args -> PageLive.scan());
        ScriptEngine.register("sign:markTasks", args -> {
            String ref = args.length > 0 ? args[0] : "";
            return MarkTasks.run(ref, new StringBuilder());
        });
    }

    private static JSONObject safeObject(String text) {
        try {
            return new JSONObject(text);
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 辅助函数

    /**
     * 可领累计/连签档位：服务端把「当前可领」算好放在 {@code taskAwardChance}，
     * 逐个 POST taskAward/{id} 即可，领取后条目从清单消失，重复运行天然幂等。
     * 不再从 accumulateTaskInfo 自己拼 status/lackDay/lottery 条件 —— 那是猜，
     * 实测猜出来的 272887 领奖全返回「任务状态异常」。
     *
     * <p>v3.77：清单可能出现在两处，都要看 —— {@code markstatus.data} 和
     * {@code domark.data}。今天 12:04 的实证：第 7 天签到时，服务端只在
     * domark 响应里给了 272899（lottery=true 的转盘档），markstatus 没给，
     * 只读前者就会漏掉转盘，用户看到「签完了转盘还在」。
     *
     * <p>再加一层兜底：{@code accumulateTaskInfo} 里 {@code lottery=true} 且
     * status/lackDay 都是 null 的档位，就是「已达标、还没抽」的转盘，补进清单。
     * 转盘和普通档位走同一个 taskAward 接口，服务端返回的就是抽奖结果。
     */
    static JSONArray claimableFromChance(JSONObject status, JSONObject mark) {
        JSONArray out = new JSONArray();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        collectChance(out, seen, status);
        collectChance(out, seen, mark);
        collectLottery(out, seen, status);
        return out;
    }

    private static void collectChance(JSONArray out, java.util.Set<String> seen, JSONObject data) {
        JSONArray chance = data == null ? null : data.optJSONArray("taskAwardChance");
        if (chance == null) {
            return;
        }
        for (int i = 0; i < chance.length(); i++) {
            JSONObject one = chance.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String id = one.optString("id", "");
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("num", one.optString("num", ""));
                item.put("taskType", one.optString("taskType", ""));
                item.put("lottery", one.optBoolean("lottery", false));
                item.put("text", one.optString("lotteryText", ""));
                out.put(item);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 兜底：{@code accumulateTaskInfo} 里已达标但还没抽的 lottery 档位。
     *
     * <p>判据来自今天 139 号的真实数据：可抽的 272899 是
     * {@code status=null, lackDay=null, lottery=true}；未达标的 272901
     * 是 {@code status=0, lackDay=7}；已抽完的 272899 变成 {@code status=1}。
     * 只认 status 与 lackDay 双 null 的，避免把没到的档位也去撞一遍。
     */
    private static void collectLottery(JSONArray out, java.util.Set<String> seen, JSONObject data) {
        JSONArray acc = data == null ? null : data.optJSONArray("accumulateTaskInfo");
        if (acc == null) {
            return;
        }
        for (int i = 0; i < acc.length(); i++) {
            JSONObject one = acc.optJSONObject(i);
            if (one == null || !one.optBoolean("lottery", false)) {
                continue;
            }
            if (!one.isNull("status") || !one.isNull("lackDay")) {
                continue;
            }
            String id = one.optString("id", "");
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("num", one.optString("num", ""));
                item.put("taskType", one.optString("taskType", "accumulate"));
                item.put("lottery", true);
                item.put("text", one.optString("lotteryText", ""));
                out.put(item);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 连续月签：monthInfo.data.pools 里 monthStatus=UNDO / hasPrize /
     * issueType=REDEEM / monthMarked 的档位可领。data 为 null 是正常情况
     * （账号没有月签数据），安静返回，不报「不是数组」。
     */
    static String claimMonthPrizes(String phone, String referer) {
        JSONObject root;
        try {
            root = TaskApi.post("/qwhdhub/api/mark/month/monthInfo", "{}", referer);
        } catch (Throwable e) {
            return "查询失败 " + e;
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return "无月签数据（data=null）";
        }
        JSONArray pools = data.optJSONArray("pools");
        if (pools == null || pools.length() == 0) {
            return "无可领档位";
        }
        int got = 0, tried = 0;
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < pools.length(); i++) {
            JSONObject p = pools.optJSONObject(i);
            if (p == null) {
                continue;
            }
            boolean claimable = "UNDO".equals(p.optString("monthStatus"))
                    && p.optBoolean("hasPrize")
                    && "REDEEM".equals(p.optString("issueType"))
                    && p.optBoolean("monthMarked");
            if (!claimable) {
                continue;
            }
            String month = p.optString("month", "");
            if (month.isEmpty()) {
                continue;
            }
            tried++;
            String body = "{\"channel\":\"app\",\"phone\":\"" + phone
                    + "\",\"prov\":\"" + p.optString("prov", "")
                    + "\",\"month\":\"" + month + "\"}";
            try {
                JSONObject r = TaskApi.post("/qwhdhub/api/mark/month/getMonthPrize",
                        body, referer);
                if ("SUCCESS".equals(r.optString("code"))) {
                    got++;
                    lines.append(month).append(" OK; ");
                } else {
                    lines.append(month).append(' ').append(r.optString("code", "?"))
                            .append(' ').append(r.optString("msg", "")).append("; ");
                }
            } catch (Throwable e) {
                lines.append(month).append(" 失败 ").append(e).append("; ");
            }
            WebPage.sleep(300L);
        }
        return tried == 0 ? "无可领档位" : "可领" + tried + " 成功" + got + " · " + lines;
    }

    static boolean isSignedToday(JSONArray marks, String today) {
        if (marks == null) {
            return false;
        }
        for (int i = 0; i < marks.length(); i++) {
            JSONObject item = marks.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (today.equals(item.optString("date"))
                    && "1".equals(item.optString("status"))) {
                return true;
            }
        }
        return false;
    }
}
