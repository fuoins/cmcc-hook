package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * qwhdhub「diy-client」通用活动引擎。
 *
 * <p>福利中心里一大半活动是同一个模子：页面地址 {@code /qwhdhub/diy-client/{actId}}，
 * 背后都是 diyActivity / diyTask / diyLottery 三套接口，只是活动 ID 和奖励不同。
 * 逐个硬编码既慢又容易漏，所以这里做成一个引擎：给活动 ID，它自己读活动信息、
 * 列任务、按任务类型做完、领代币、抽免费次数、复查。
 *
 * <p>已经确认能跑这套的：追剧领福利（1126082530）、移动游戏中心（1126086939）、
 * 玩游戏抽话费（1126090514）、0 元抢短剧金蛋礼（1126090575）。
 * 福利中心探路发现的新 diy 活动，只要带活动 ID 就能直接复用，不用改代码。
 *
 * <p>任务类型与端点的对应关系是抓包实测出来的（11-06 周六游戏日全量日志复核）：
 * <ul>
 *   <li>{@code browse} / {@code business} → GET 任务自带的
 *       {@code /qwhdhub/diyTask/startBrowse/{id}?…}，一次登记到位；</li>
 *   <li>{@code inapp_cdt} / {@code browse_app} → 取 cToken → 等满 scanTime →
 *       openFinish，服务端缺时长会回 SUCCESS 但 data=false，必须校验；</li>
 *   <li>{@code share} / {@code share_view} → POST diyTask/finish/{id}；</li>
 *   <li>其余类型要真实操作（下单、看直播），不硬做，如实记下来。</li>
 * </ul>
 *
 * <p>周六游戏日（1126086939，页面里叫「移动游戏中心」的周六场次）额外有一套
 * {@code /qwhdhub/api/diySaturdayGame/*} 每日签到：getClockInStatus 查状态，
 * clockIn 领当日奖励。只在活动号匹配时调用，不对别的 diy 活动乱发。
 */
final class DiyTask {

    /** 周六游戏日的活动号：只有它带 diySaturdayGame 每日签到。 */
    private static final String ACT_SATURDAY_GAME = "1126086939";
    /** 追剧领福利（视频日活动）：签到走 diyVideoDayRedesign 专属接口。 */
    private static final String ACT_VIDEO_DAY = "1126082530";

    /** 单个组件最多做几个任务，防止一次跑太久。 */
    private static final int MAX_TASKS = 12;
    /** 单个组件最多抽几次，防止异常情况下无限抽；正常按服务端剩余次数走。 */
    private static final int MAX_DRAW = 10;
    private static final int MAX_COMPONENTS = 3;

    private DiyTask() {
    }

    static TaskRunner.Result run(String actId, String pageUrl) {
        StringBuilder detail = new StringBuilder();
        StringBuilder text = new StringBuilder();
        String referer = pageUrl;
        try {
            // 页面已经由 TaskRunner 打开，接口直接借页面的上下文发。
            JSONObject info = TaskApi.get("/qwhdhub/diyActivity/info/" + actId, referer);
            JSONObject data = info.optJSONObject("data");
            if (data == null) {
                return TaskRunner.Result.fail("活动信息为空：" + info.optString("code")
                                + " " + info.optString("msg"),
                        detail.append("actId: ").append(actId).append('\n')
                                .append("返回: ").append(brief(info)).append('\n').toString(),
                        "活动可能已下线，或这个 ID 不属于 diy 体系。");
            }
            JSONObject tmpl = data.optJSONObject("templateActivity");
            String name = tmpl == null ? "" : tmpl.optString("activityName", "");
            String state = tmpl == null ? "" : tmpl.optString("state", "");
            if (name.isEmpty()) {
                name = "DIY 活动 " + actId;
            }
            detail.append("▶ 任务执行\n");
            detail.append("活动: ").append(name).append(" (").append(actId).append(')')
                    .append(" 状态: ").append(state).append('\n');

            // 周六游戏日：先领每日签到奖励，再做任务、抽奖。
            int clockWins = 0;
            if (ACT_SATURDAY_GAME.equals(actId)) {
                clockWins = clockIn(referer, detail, text, name);
            }
            // 追剧领福利：视频日自己的签到接口，和 diyTask 体系分开。
            int videoSign = 0;
            if (ACT_VIDEO_DAY.equals(actId)) {
                videoSign = signVideoDay(referer, detail, text, name);
            }

            JSONArray methods = data.optJSONArray("playMethodInfos");
            if (methods == null || methods.length() == 0) {
                return TaskRunner.Result.idle(name + " · 没有可玩的环节",
                        detail.append("playMethodInfos 为空\n").toString(), name + "：今天没有可做的");
            }

            int doneTasks = 0;
            int undoTasks = 0;
            int drawTimes = 0;
            int comps = 0;
            for (int i = 0; i < methods.length() && comps < MAX_COMPONENTS; i++) {
                JSONObject pm = methods.optJSONObject(i);
                JSONObject ap = pm == null ? null : pm.optJSONObject("actPlayMethod");
                String comp = ap == null ? "" : ap.optString("componentId", "");
                if (comp.isEmpty()) {
                    continue;
                }
                comps++;
                String method = ap.optString("name", "玩法" + comps);
                detail.append("-- 环节 ").append(comps).append(": ").append(method)
                        .append(" (").append(comp).append(")\n");

                JSONArray tasks = taskList(comp, referer);
                int undo = 0;
                int waitReceive = 0;
                for (int t = 0; t < tasks.length(); t++) {
                    JSONObject task = tasks.optJSONObject(t);
                    if (task == null) {
                        continue;
                    }
                    String stage = task.optString("taskStage", "");
                    if ("UNDO".equals(stage)) {
                        undo++;
                    } else if ("WAIT_RECEIVE".equals(stage)) {
                        waitReceive++;
                    }
                }
                undoTasks += undo;
                detail.append("   任务 ").append(tasks.length())
                        .append(" 个，待做 ").append(undo)
                        .append("，待领 ").append(waitReceive).append('\n');

                // 已完成待领的先补领：有些活动不自动发币，光做完没用
                for (int t = 0; t < tasks.length(); t++) {
                    JSONObject task = tasks.optJSONObject(t);
                    if (task == null || !"WAIT_RECEIVE".equals(task.optString("taskStage"))) {
                        continue;
                    }
                    String id = task.optString("taskId", "");
                    if (id.isEmpty()) {
                        continue;
                    }
                    try {
                        JSONObject resp = TaskApi.post("/qwhdhub/diyTask/receive",
                                "{\"taskId\":\"" + id + "\"}", referer);
                        if ("SUCCESS".equals(resp.optString("code"))) {
                            text.append(name).append("·补领 ").append(task.optString("name", id)).append('\n');
                            detail.append("   补领 ").append(id).append(": OK\n");
                        }
                    } catch (Throwable ignored) {
                    }
                    sleep(600);
                }

                int tried = 0;
                for (int t = 0; t < tasks.length() && tried < MAX_TASKS; t++) {
                    JSONObject task = tasks.optJSONObject(t);
                    if (task == null || !"UNDO".equals(task.optString("taskStage"))) {
                        continue;
                    }
                    tried++;
                    String taskName = task.optString("name", task.optString("taskName", "任务"));
                    String result = finishOne(task, referer, detail);
                    if ("OK".equals(result)) {
                        doneTasks++;
                        text.append(name).append("·").append(taskName).append('\n');
                    } else {
                        detail.append("   任务「").append(taskName).append("」跳过: ")
                                .append(result).append('\n');
                    }
                    sleep(1_100);
                }

                // 抽奖：只有「有剩余次数」且「有中奖名额」才抽，避免空抽
                JSONObject rem = remain(comp, referer);
                int remain = rem == null ? 0 : rem.optInt("remain", 0);
                int winRemain = rem == null ? 0 : rem.optInt("winRemain", 0);
                detail.append("   次数: 剩余 ").append(remain)
                        .append("，可中 ").append(winRemain).append('\n');
                if (remain > 0 && winRemain > 0) {
                    int times = Math.min(remain, MAX_DRAW);
                    for (int d = 0; d < times; d++) {
                        String prize = draw(comp, referer, detail);
                        if (prize == null) {
                            break;
                        }
                        drawTimes++;
                        text.append(name).append("·抽奖 ").append(prize).append('\n');
                        sleep(1_300);
                    }
                }
            }

            // ---- 复查：做完再拉一遍，确认真的变成 DONE ----
            int left = 0;
            for (int i = 0, c = 0; i < methods.length() && c < MAX_COMPONENTS; i++) {
                JSONObject pm = methods.optJSONObject(i);
                JSONObject ap = pm == null ? null : pm.optJSONObject("actPlayMethod");
                String comp = ap == null ? "" : ap.optString("componentId", "");
                if (comp.isEmpty()) {
                    continue;
                }
                c++;
                JSONArray after = taskList(comp, referer);
                for (int t = 0; t < after.length(); t++) {
                    JSONObject task = after.optJSONObject(t);
                    if (task != null && "UNDO".equals(task.optString("taskStage"))) {
                        left++;
                    }
                }
            }
            detail.append("复查: 仍待做 ").append(left).append(" 个\n");
            detail.append("结果: 完成 ").append(doneTasks)
                    .append(" 个任务，抽奖 ").append(drawTimes).append(" 次\n");

            String summary = name + " · 完成 " + doneTasks + " 个任务";
            if (clockWins > 0 || videoSign > 0) {
                summary += " · 签到到账";
            }
            if (drawTimes > 0) {
                summary += " · 抽 " + drawTimes + " 次";
            }
            if (left > 0) {
                summary += " · 还剩 " + left + " 个要真实操作";
            }
            boolean idle = doneTasks == 0 && drawTimes == 0 && undoTasks == 0
                    && clockWins == 0 && videoSign == 0;
            if (idle) {
                return TaskRunner.Result.idle(name + " · 今天已做完",
                        detail.toString(), summary);
            }
            return TaskRunner.Result.ok(summary, detail.toString(),
                    text.length() == 0 ? summary : text.toString().trim());
        } catch (TaskApi.TaskException e) {
            return TaskRunner.Result.fail("活动接口失败：" + e.getMessage(),
                    detail.append('\n').append(e.getMessage()).toString(),
                    e.sessionLost() ? "活动页登录态失效，先打开一次 App 首页再试。" : "");
        } catch (Throwable e) {
            return TaskRunner.Result.fail("活动执行异常：" + e,
                    detail.append('\n').append(android.util.Log.getStackTraceString(e)).toString(), "");
        }
    }

    private static JSONArray taskList(String comp, String referer) {
        try {
            JSONObject root = TaskApi.get("/qwhdhub/diyTask/list/" + comp, referer);
            JSONArray array = root.optJSONArray("data");
            return array == null ? new JSONArray() : array;
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    private static JSONObject remain(String comp, String referer) {
        try {
            return TaskApi.post("/qwhdhub/diyLottery/period/remain/" + comp, "{}", referer)
                    .optJSONObject("data");
        } catch (Throwable e) {
            return null;
        }
    }

    /** 抽一次，返回奖品描述；返回 null 表示该停手了。 */
    private static String draw(String comp, String referer, StringBuilder detail) {
        try {
            JSONObject resp = TaskApi.get("/qwhdhub/diyLottery/lotterySafely/" + comp, referer);
            String code = resp.optString("code");
            if ("NOT_WON".equals(code)) {
                detail.append("   抽奖: 未中奖\n");
                return "未中奖";
            }
            if (!"SUCCESS".equals(code)) {
                detail.append("   抽奖停止: ").append(code).append(' ')
                        .append(resp.optString("msg")).append('\n');
                return null;
            }
            JSONArray data = resp.optJSONArray("data");
            String prize = "";
            if (data != null && data.length() > 0) {
                JSONObject one = data.optJSONObject(0);
                if (one != null) {
                    prize = one.optString("prizeName", one.optString("name", ""));
                }
            }
            detail.append("   抽奖: ").append(prize.isEmpty() ? "已抽" : prize).append('\n');
            return prize.isEmpty() ? "已抽" : prize;
        } catch (Throwable e) {
            detail.append("   抽奖异常: ").append(e).append('\n');
            return null;
        }
    }

    /** 按任务类型做完一个，返回 OK 或「为什么没做」。 */
    private static String finishOne(JSONObject task, String referer, StringBuilder detail) {
        String id = task.optString("taskId", "");
        String type = task.optString("type", "");
        String url = task.optString("url", "");
        if (id.isEmpty()) {
            return "缺少 taskId";
        }
        try {
            // 实测：browse / business 的任务 url 都是 startBrowse/{id}?…，
            // 服务端在 302 之前就登记完成。按 url 走比按类型猜端点准。
            if (url.contains("/startBrowse/")) {
                TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + url, referer,
                        TaskApi.eggHeaders());
                detail.append("   startBrowse ").append(id).append(": HTTP ")
                        .append(resp.status).append('\n');
                return resp.status > 0 ? "OK" : "HTTP " + resp.status;
            }
            if ("browse".equals(type)) {
                JSONObject resp = TaskApi.get("/qwhdhub/diyTask/finishBrowse/" + id, referer);
                return "SUCCESS".equals(resp.optString("code")) ? "OK" : resp.optString("code");
            }
            if ("inapp_cdt".equals(type) || "browse_app".equals(type)) {
                return finishInApp(task, id, referer, detail);
            }
            if ("share".equals(type) || "share_view".equals(type)) {
                String path = url.startsWith("/qwhdhub/diyTask/finish/")
                        ? url : "/qwhdhub/diyTask/finish/" + id;
                JSONObject resp = TaskApi.post(path, "{}", referer);
                return "SUCCESS".equals(resp.optString("code")) ? "OK" : resp.optString("code");
            }
            return "类型 " + type + " 要真实操作";
        } catch (Throwable e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * 周六游戏日每日签到：先查状态，未签到才领。
     *
     * <p>接口实证：POST getClockInStatus {} → data.message「今日未签到」；
     * POST clockIn {} → data 是奖品数组。已签到就不重复调，避免白跑。
     *
     * @return 领到几个奖品（0 表示今天已签过或没领到）
     */
    private static int clockIn(String referer, StringBuilder detail, StringBuilder text,
                               String actName) {
        try {
            JSONObject status = TaskApi.post("/qwhdhub/api/diySaturdayGame/getClockInStatus",
                    "{}", referer);
            JSONObject data = status.optJSONObject("data");
            String message = data == null ? "" : data.optString("message", "");
            int days = data == null ? 0 : data.optInt("consecutiveDays", 0);
            detail.append("签到状态: ").append(status.optString("code")).append(' ')
                    .append(message.isEmpty() ? "-" : message)
                    .append("，连续 ").append(days).append(" 天\n");
            if (message.contains("已签到") || message.contains("已签")) {
                return 0;
            }
            JSONObject resp = TaskApi.post("/qwhdhub/api/diySaturdayGame/clockIn",
                    "{}", referer);
            if (!"SUCCESS".equals(resp.optString("code"))) {
                detail.append("签到: ").append(resp.optString("code")).append(' ')
                        .append(resp.optString("msg")).append('\n');
                return 0;
            }
            JSONArray prizes = resp.optJSONArray("data");
            int count = 0;
            StringBuilder names = new StringBuilder();
            if (prizes != null) {
                for (int i = 0; i < prizes.length() && i < 5; i++) {
                    JSONObject one = prizes.optJSONObject(i);
                    String prize = one == null ? ""
                            : one.optString("prizeName", one.optString("name", ""));
                    if (prize.isEmpty()) {
                        continue;
                    }
                    count++;
                    if (names.length() > 0) {
                        names.append("、");
                    }
                    names.append(prize);
                }
            }
            detail.append("签到: OK ").append(names).append('\n');
            if (count > 0) {
                text.append(actName).append("·签到 ").append(names).append('\n');
            }
            return count;
        } catch (Throwable e) {
            detail.append("签到异常: ").append(e).append('\n');
            return 0;
        }
    }

    /**
     * 追剧领福利（视频日活动）签到。
     *
     * <p>11-06 全量日志实证：
     * GET /qwhdhub/api/diyVideoDayRedesign/sign/querySignStatus
     * → data.todaySignFlag=0/1、signDays=N；
     * GET /qwhdhub/api/diyVideoDayRedesign/sign/doSign
     * → code=SUCCESS、msg=签到成功。
     * 已签就不重复调，避免白跑；接口失败按失败记，不写成成功。
     *
     * @return 1 表示这次真的签上了
     */
    private static int signVideoDay(String referer, StringBuilder detail, StringBuilder text,
                                    String actName) {
        try {
            JSONObject status = TaskApi.get(
                    "/qwhdhub/api/diyVideoDayRedesign/sign/querySignStatus", referer);
            JSONObject data = status.optJSONObject("data");
            String flag = data == null ? "" : data.optString("todaySignFlag", "0");
            int days = data == null ? 0 : data.optInt("signDays", 0);
            detail.append("视频日签到状态: ").append(status.optString("code"))
                    .append(" 今日已签=").append(flag.isEmpty() ? "-" : flag)
                    .append("，累计 ").append(days).append(" 天\n");
            if ("1".equals(flag)) {
                return 0;
            }
            JSONObject resp = TaskApi.get(
                    "/qwhdhub/api/diyVideoDayRedesign/sign/doSign", referer);
            if (!"SUCCESS".equals(resp.optString("code"))) {
                detail.append("视频日签到: ").append(resp.optString("code")).append(' ')
                        .append(resp.optString("msg")).append('\n');
                return 0;
            }
            detail.append("视频日签到: OK ").append(resp.optString("msg")).append('\n');
            text.append(actName).append("·签到\n");
            return 1;
        } catch (Throwable e) {
            detail.append("视频日签到异常: ").append(e).append('\n');
            return 0;
        }
    }

    /**
     * 应用内浏览：服务端按停留时长判，先要 cToken，等够 scanTime 再 openFinish。
     * 缺时长会回 code=SUCCESS 但 data=false，只看 code 会误报成功。
     */
    private static String finishInApp(JSONObject task, String id, String referer,
                                      StringBuilder detail) {
        String token = task.optString("param", task.optString("jtToken", ""));
        if (token.isEmpty()) {
            return "缺少 jtToken";
        }
        try {
            JSONObject one = TaskApi.post("/qwhdhub/api/open/_pub/task/getOneTaskInfo",
                    "{\"jtToken\":\"" + token + "\"}", referer);
            JSONObject data = one.optJSONObject("data");
            String cToken = data == null ? "" : data.optString("cToken", "");
            int scan = data == null ? 3 : data.optInt("scanTime", 3);
            if (cToken.isEmpty()) {
                return "cToken 缺失: " + one.optString("code") + one.optString("msg");
            }
            sleep(Math.min(Math.max(scan, 3), 20) * 1_000L + 1_500L);
            JSONObject fin = TaskApi.post("/qwhdhub/api/open/_pub/task/openFinish",
                    "{\"cToken\":\"" + cToken + "\"}", referer);
            Object value = fin.opt("data");
            boolean ok = "SUCCESS".equals(fin.optString("code"))
                    && !(value instanceof Boolean && !((Boolean) value));
            detail.append("   应用内浏览 ").append(id).append(": ")
                    .append(ok ? "OK" : fin.optString("msg")).append('\n');
            return ok ? "OK" : "时长未达标";
        } catch (Throwable e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String brief(JSONObject root) {
        String text = root == null ? "" : root.toString();
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
