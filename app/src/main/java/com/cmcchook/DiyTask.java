package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * v4.10.0：抽奖一律抽到服务端「剩余次数 = 0」。
     * 这里只是防死循环的兜底上限（服务端不给递减时最多抽这么多次），
     * 正常由 {@code readRemain} 读到 0 就收手。
     */
    private static final int MAX_DRAW = 40;
    private static final int MAX_COMPONENTS = 3;

    /**
     * v4.20.0-r33：抽奖环节的「这个组件根本没抽奖接口」标记。
     *
     * <p>16:45 周六游戏日实证：3 个 actPlayMethod 里只有「签到区按钮玩法」
     * 是真抽奖（remain 2、lotterySafely 有响应）；「秒杀组件」remain 回
     * FAILED 查询失败、「个性化奖品」remain 回 98 且 lotterySafely 回
     * 「插件不存在」。旧版一律当作「抽奖停止 FAILED」，日志看着像抽挂了。
     */
    private static final String DRAW_SKIP = "__NO_LOTTERY__";

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
            // v4.10.0 两阶段：先把所有环节的「补领 + 做任务」跑完，收集环节 ID；
            // 全部任务做完之后，再统一遍历环节抽奖到剩余 0 次。
            // 用户实证：先抽奖再做任务，任务又发下新的抽奖次数，结果「抽了但有剩」。
            List<String> allComps = new ArrayList<>();
            List<java.util.Map<String, String>> stageBefore = new ArrayList<>();
            // taskId → 任务名，复查确认 UNDO→DONE 后补进摘要用。
            java.util.Map<String, String> taskNames = new java.util.HashMap<>();
            for (int i = 0; i < methods.length() && comps < MAX_COMPONENTS; i++) {
                JSONObject pm = methods.optJSONObject(i);
                JSONObject ap = pm == null ? null : pm.optJSONObject("actPlayMethod");
                String comp = ap == null ? "" : ap.optString("componentId", "");
                if (comp.isEmpty()) {
                    continue;
                }
                comps++;
                allComps.add(comp);
                String method = ap.optString("name", "玩法" + comps);
                detail.append("-- 环节 ").append(comps).append(": ").append(method)
                        .append(" (").append(comp).append(")\n");

                JSONArray tasks = taskList(comp, referer);
                int undo = 0;
                int waitReceive = 0;
                // v4.20.0-r33：记下做之前的 taskStage，复查时按 UNDO→DONE 的真实
                // 变化算完成数。startBrowse 的 HTTP 状态完全不能当判据 ——
                // 16:45 那轮 3 个 -1 里 2 个复查时是 DONE，1 个仍 UNDO。
                java.util.Map<String, String> beforeStage = new java.util.HashMap<>();
                for (int t = 0; t < tasks.length(); t++) {
                    JSONObject task = tasks.optJSONObject(t);
                    if (task == null) {
                        continue;
                    }
                    String stage = task.optString("taskStage", "");
                    String tid = task.optString("taskId", "");
                    if (!tid.isEmpty()) {
                        beforeStage.put(tid, stage);
                        taskNames.put(tid, task.optString("name", tid));
                    }
                    if ("UNDO".equals(stage)) {
                        undo++;
                    } else if ("WAIT_RECEIVE".equals(stage)) {
                        waitReceive++;
                    }
                }
                stageBefore.add(beforeStage);
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
                    } else if ("SENT".equals(result)) {
                        // 已发出、服务端登记，但这里不下结论：等复查看 taskStage。
                        detail.append("   任务「").append(taskName).append("」已发出，等复查\n");
                    } else {
                        detail.append("   任务「").append(taskName).append("」跳过: ")
                                .append(result).append('\n');
                    }
                    sleep(1_100);
                }

                // v4.10.0：本环节不再抽奖，抽奖统一放到「所有任务做完后」的收尾阶段。
            }

            // ---- v4.10.0 收尾阶段：所有环节的任务/补领都做完，再统一抽到 0 ----
            detail.append("▶ 抽奖收尾（任务全部完成后，一律抽到剩余次数 0）\n");
            for (String comp : allComps) {
                int drawn = drain(comp, referer, name, text, detail);
                drawTimes += drawn;
                detail.append("   环节 ").append(comp).append(": 抽 ").append(drawn).append(" 次\n");
            }

            // ---- 复查：做完再拉一遍，确认真的变成 DONE ----
            int left = 0;
            int verifiedDone = 0;
            for (int i = 0, c = 0; i < methods.length() && c < MAX_COMPONENTS; i++) {
                JSONObject pm = methods.optJSONObject(i);
                JSONObject ap = pm == null ? null : pm.optJSONObject("actPlayMethod");
                String comp = ap == null ? "" : ap.optString("componentId", "");
                if (comp.isEmpty()) {
                    continue;
                }
                java.util.Map<String, String> before =
                        c < stageBefore.size() ? stageBefore.get(c) : null;
                c++;
                JSONArray after = taskList(comp, referer);
                for (int t = 0; t < after.length(); t++) {
                    JSONObject task = after.optJSONObject(t);
                    if (task == null) {
                        continue;
                    }
                    String stage = task.optString("taskStage", "");
                    if ("UNDO".equals(stage)) {
                        left++;
                    } else if ("DONE".equals(stage) && before != null
                            && "UNDO".equals(before.get(task.optString("taskId", "")))) {
                        // 做之前是 UNDO、复查是 DONE —— 这才是真的做成了。
                        verifiedDone++;
                        String nm = taskNames.get(task.optString("taskId", ""));
                        if (nm != null) {
                            text.append(name).append("·").append(nm).append('\n');
                        }
                    }
                }
            }
            if (verifiedDone > doneTasks) {
                detail.append("复查校正: 接口回执算 ").append(doneTasks)
                        .append(" 个，实际 UNDO→DONE ").append(verifiedDone)
                        .append(" 个，按实际计\n");
                doneTasks = verifiedDone;
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
                summary += " · 还剩 " + left + " 个没做上";
            }
            boolean idle = doneTasks == 0 && drawTimes == 0 && undoTasks == 0
                    && clockWins == 0 && videoSign == 0;
            if (idle) {
                return TaskRunner.Result.idle(name + " · 今天已做完",
                        detail.toString(), summary);
            }
            // v4.20.0-r33：有活、但一个都没做上时不能再报 OK。
            // 16:45 那轮 5 个待做里 4 个其实成功了，旧版只算 2 个；
            // 反过来的情况（全挂）旧版会报「完成 0 个任务」还带 OK 标记。
            if (doneTasks == 0 && drawTimes == 0 && clockWins == 0 && videoSign == 0
                    && undoTasks > 0) {
                String why = "活动有 " + undoTasks + " 个待做，但一个都没做上";
                detail.append("判定: ").append(why).append("，不记完成\n");
                return TaskRunner.Result.skip(name + " · " + why, detail.toString(),
                        "任务接口是通的，但这轮全部没登记成功（startBrowse 回执异常）。\n"
                                + "建议在 App 里手动打开这个活动页，点一个任务看看能不能正常跳转，再回来重试。");
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

    /**
     * v4.10.0 收尾抽奖：把某个环节的抽奖次数一律抽到服务端「剩余 = 0」。
     *
     * <p>不再看 {@code winRemain}（可中名额）—— 旧版就卡在「还有次数但可中名额
     * 为 0」直接不抽，用户实证「抽奖了但有剩的没抽」。现在只要还有次数就抽，
     * 未中奖也算消耗一次，继续抽到 0。
     *
     * <p>三重收手条件，任一命中即停：
     * <ol>
     *   <li>{@code draw()} 返回 null：服务端明确说停手（次数用尽 / 风控 / 非 SUCCESS）；</li>
     *   <li>重读 {@code remain} 得到 0；</li>
     *   <li>服务端剩余次数连续几轮不再下降，或达到 {@link #MAX_DRAW} 硬上限
     *       （防接口异常时空转）。</li>
     * </ol>
     */
    private static int drain(String comp, String referer, String name,
                             StringBuilder text, StringBuilder detail) {
        int drawn = 0;
        JSONObject first = remain(comp, referer);
        if (first == null) {
            detail.append("   环节 ").append(comp).append(" 没有抽奖接口（剩余次数读不到），跳过\n");
            return 0;
        }
        int serverLeft = first.optInt("remain", -1);
        if (serverLeft < 0) {
            detail.append("   环节 ").append(comp)
                    .append(" 没有可用抽奖次数（remain 字段缺失），跳过\n");
            return 0;
        }
        if (serverLeft > MAX_DRAW) {
            // 「个性化奖品」这类组件 remain 会回库存数（实测 98），不是抽奖次数。
            // 按次数抽会把库存当次数打空，直接按脏值跳过。
            detail.append("   环节 ").append(comp).append(" 剩余次数异常（").append(serverLeft)
                    .append("），不是抽奖次数，跳过\n");
            return 0;
        }
        if (serverLeft == 0) {
            detail.append("   环节 ").append(comp).append(" 抽奖前剩余: 0，跳过\n");
            return 0;
        }
        // 服务端给了明确剩余次数就以它为上限（+2 余量）；读不到则靠 draw 停手 + 硬上限。
        int cap = Math.min(serverLeft + 2, MAX_DRAW);
        detail.append("   环节 ").append(comp).append(" 抽奖前剩余: ")
                .append(serverLeft).append('\n');
        int lastLeft = -2;
        int stall = 0;
        for (int i = 0; i < cap; i++) {
            String prize = draw(comp, referer, detail);
            if (DRAW_SKIP.equals(prize)) {
                detail.append("   环节 ").append(comp).append(" 该组件没有抽奖玩法\n");
                break;
            }
            if (prize == null) {
                detail.append("   环节 ").append(comp).append(" 抽奖接口停手\n");
                break;
            }
            drawn++;
            text.append(name).append("·抽奖 ").append(prize).append('\n');
            sleep(1_100);
            JSONObject now = remain(comp, referer);
            int left = now == null ? -1 : now.optInt("remain", -1);
            if (left == 0) {
                detail.append("   环节 ").append(comp).append(" 剩余次数已到 0\n");
                break;
            }
            if (left > 0) {
                if (left == lastLeft) {
                    if (++stall >= 3) {
                        detail.append("   环节 ").append(comp)
                                .append(" 剩余次数连续不降，收手\n");
                        break;
                    }
                } else {
                    stall = 0;
                }
                lastLeft = left;
            }
        }
        if (drawn == 0) {
            // 一次都没抽成时补一句，否则日志里只剩「抽 0 次」，看不出是没次数还是接口挂了。
            detail.append("   环节 ").append(comp).append(": 本次一次都没抽成\n");
        }
        return drawn;
    }

    /** 抽一次，返回奖品描述；返回 null 表示该停手了，{@link #DRAW_SKIP} 表示组件没抽奖玩法。 */
    private static String draw(String comp, String referer, StringBuilder detail) {
        try {
            JSONObject resp = TaskApi.get("/qwhdhub/diyLottery/lotterySafely/" + comp, referer);
            String code = resp.optString("code");
            String msg = resp.optString("msg", "");
            // v4.20.0-r33：未中奖的 code 有两种写法。
            //   砸金蛋：{"code":"NOT_WON"}  周六游戏日：{"code":"NO_WON"}
            // 旧版只认 NOT_WON，周六那轮第一次（NO_WON）就被当成「该停手」，
            // 抽了 1 次却记「抽 0 次」，剩 1 次也没再抽。
            if ("NOT_WON".equals(code) || "NO_WON".equals(code) || "NOTWON".equals(code)
                    || "NOT_WON".equals(resp.optString("status"))) {
                detail.append("   抽奖: 未中奖\n");
                return "未中奖";
            }
            if (!"SUCCESS".equals(code)) {
                if (msg.contains("插件不存在") || msg.contains("查询失败")) {
                    detail.append("   抽奖: 该组件没有抽奖玩法（")
                            .append(code).append(' ').append(msg).append("）\n");
                    return DRAW_SKIP;
                }
                detail.append("   抽奖停止: ").append(code).append(' ')
                        .append(msg).append('\n');
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
                // v4.20.0-r33：不跟随 302。startBrowse 一律跳到外域落地页，
                // fetch 跟过去会被 CORS 拦成 TypeError，模块只看到 HTTP -1；
                // 但服务端已经登记了。这里只负责「把请求发出去」，
                // 真实结果交给复查阶段看 taskStage 有没有 UNDO→DONE。
                String full = url.startsWith("http") ? url : TaskNet.BASE + url;
                TaskNet.Resp resp = TaskNet.getNoFollow(full, referer, TaskApi.eggHeaders());
                detail.append("   startBrowse ").append(id).append(": ")
                        .append(resp.status == 0 ? "已发出（302 登记，不跟落地页）"
                                : "HTTP " + resp.status).append('\n');
                return "SENT";
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
