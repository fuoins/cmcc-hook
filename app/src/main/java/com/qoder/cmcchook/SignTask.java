package com.qoder.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 福利中心签到 + 领奖。
 *
 * 对应 Python 的 cmcc_sign.py（签到、累计/连签领奖、组团共签、连续月签），
 * 接口顺序、请求体、幂等判断全部照搬，只是把「抓包拿 Cookie」换成了进程内直取。
 */
final class SignTask {

    private static final String PATH_STATUS = "/qwhdhub/api/mark/mark31/markstatus";
    private static final String PATH_MARK = "/qwhdhub/api/mark/mark31/domark";
    private static final String PATH_AWARD = "/qwhdhub/api/mark/mark31/taskAward/";
    private static final String PATH_TASKS = "/qwhdhub/api/mark/task/taskList";
    private static final String PATH_TASK_AWARD = "/qwhdhub/api/mark/task/getTaskAward";

    private static final int MAX_BROWSE = 5;
    private static final long BROWSE_STAY_MS = 7_000L;

    private SignTask() {
    }

    static TaskRunner.Result run() {
        String referer = TaskApi.ACT_SIGN;
        StringBuilder detail = new StringBuilder();
        List<String> lines = new ArrayList<>();

        String warmed = TaskApi.warmUp(referer);
        if (warmed == null) {
            detail.append("预热: SSO 跳转失败\n");
            detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
            return TaskRunner.Result.fail("签到会话不可用", detail.toString(),
                    "在 App 里打开一次「我的 → 签到领流量」，等页面加载完再点一次；"
                            + "模块会自动把 Cookie 接过来，不用抓包。");
        }
        referer = warmed;
        detail.append("入口: ").append(referer).append('\n');
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');

        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());

        JSONObject data;
        try {
            data = TaskApi.dataOf(TaskApi.post(PATH_STATUS, "{}", referer), "markstatus");
        } catch (TaskApi.TaskException e) {
            detail.append("markstatus 失败: ").append(e.getMessage()).append('\n');
            return TaskRunner.Result.fail("查询签到状态失败：" + e.getMessage(),
                    detail.toString(), hintFor(e));
        }

        JSONObject userinfo = data.optJSONObject("userinfo");
        int accumulated = userinfo == null ? -1 : userinfo.optInt("accumulateTimes", -1);
        boolean signed = isSignedToday(data.optJSONArray("markstatus"), today);
        detail.append("累计签到: ").append(accumulated).append(" 天，今日")
                .append(signed ? "已签" : "未签").append('\n');

        if (signed) {
            lines.add("今日已签（累计 " + accumulated + " 天）");
        } else {
            try {
                JSONObject resp = TaskApi.post(PATH_MARK, "{\"date\":\"" + today + "\"}", referer);
                String code = resp.optString("code");
                String status = resp.optString("status");
                String msg = resp.optString("msg");
                detail.append("domark: code=").append(code).append(" status=")
                        .append(status).append(" msg=").append(msg).append('\n');
                boolean ok = "SUCCESS".equals(code) || status.contains("HAVE_MARKED")
                        || msg.contains("已签");
                if (!ok) {
                    lines.add("签到失败：" + code + " " + msg);
                    return TaskRunner.Result.fail("签到失败：" + code + " " + msg,
                            detail.toString(), "服务端拒绝，先确认账号状态；若一直失败把日志发我。");
                }
                String prize = prizeOf(resp.optJSONObject("data"));
                if (status.contains("HAVE_MARKED")) {
                    lines.add("今日已签到（服务端幂等）");
                } else {
                    lines.add("签到成功" + (prize.isEmpty() ? "" : "，得 " + prize));
                }
                JSONObject latest = TaskApi.dataOf(
                        TaskApi.post(PATH_STATUS, "{}", referer), "markstatus");
                JSONObject info2 = latest.optJSONObject("userinfo");
                if (info2 != null) {
                    accumulated = info2.optInt("accumulateTimes", accumulated);
                }
                data = latest;
            } catch (TaskApi.TaskException e) {
                detail.append("domark 异常: ").append(e.getMessage()).append('\n');
                lines.add("签到请求异常：" + e.getMessage());
            }
        }

        // ---- 累计 / 连签奖励 ----
        try {
            JSONArray chances = data.optJSONArray("taskAwardChance");
            if (chances == null || chances.length() == 0) {
                detail.append("领奖: 当前无可领\n");
            } else {
                for (int i = 0; i < chances.length(); i++) {
                    JSONObject task = chances.optJSONObject(i);
                    if (task == null) {
                        continue;
                    }
                    String id = task.optString("id");
                    if (id.isEmpty()) {
                        continue;
                    }
                    String label = labelOf(task);
                    try {
                        JSONObject resp = TaskApi.post(PATH_AWARD + id, "{}", referer);
                        JSONObject prize = resp.optJSONObject("data");
                        String name = prize == null ? "" : prize.optString("prizeName");
                        if (!name.isEmpty()) {
                            String value = amountOf(prize);
                            lines.add(label + " 领到 " + name + value);
                            detail.append("领奖 ").append(id).append(": ").append(name).append('\n');
                        } else {
                            String code = resp.optString("status", resp.optString("code", "?"));
                            lines.add(label + " 未领取：" + code + " " + resp.optString("msg"));
                            detail.append("领奖 ").append(id).append(": ").append(code)
                                    .append(' ').append(resp.optString("msg")).append('\n');
                        }
                    } catch (TaskApi.TaskException e) {
                        detail.append("领奖 ").append(id).append(" 异常: ")
                                .append(e.getMessage()).append('\n');
                    }
                    sleep(900);
                }
            }
        } catch (Throwable e) {
            detail.append("领奖异常: ").append(e).append('\n');
        }

        // ---- 组团共签 ----
        try {
            JSONObject group = TaskApi.post("/qwhdhub/api/mark/groupmark/status", "{}", referer);
            JSONObject gdata = group.optJSONObject("data");
            if ("SUCCESS".equals(group.optString("code")) && gdata != null
                    && gdata.optInt("status", 0) == 3) {
                JSONObject award = TaskApi.post("/qwhdhub/api/mark/groupmark/award", "{}", referer);
                JSONObject prize = award.optJSONObject("data") == null ? null
                        : award.optJSONObject("data").optJSONObject("myPrize");
                String name = prize == null ? "团队奖励" : prize.optString("name", "团队奖励");
                lines.add("组队奖励：" + name);
                detail.append("组队: ").append(award.optString("code")).append('\n');
            } else {
                detail.append("组队: 未达标或未开团\n");
            }
        } catch (Throwable e) {
            detail.append("组队异常: ").append(e).append('\n');
        }
        sleep(700);

        // ---- 连续月签 ----
        try {
            JSONObject month = TaskApi.post("/qwhdhub/api/mark/month/monthInfo", "{}", referer);
            JSONObject mdata = month.optJSONObject("data");
            JSONArray pools = mdata == null ? null : mdata.optJSONArray("pools");
            String phone = userinfo == null ? "" : userinfo.optString("phone", "");
            int claimed = 0;
            if (pools != null) {
                for (int i = 0; i < pools.length(); i++) {
                    JSONObject pool = pools.optJSONObject(i);
                    if (pool == null) {
                        continue;
                    }
                    boolean claimable = "UNDO".equals(pool.optString("monthStatus"))
                            && pool.optBoolean("hasPrize")
                            && "REDEEM".equals(pool.optString("issueType"))
                            && pool.optBoolean("monthMarked");
                    if (!claimable || phone.isEmpty()) {
                        continue;
                    }
                    String body = "{\"channel\":\"app\",\"phone\":\"" + phone
                            + "\",\"prov\":\"" + pool.optString("prov", "")
                            + "\",\"month\":\"" + pool.optString("month") + "\"}";
                    JSONObject resp = TaskApi.post(
                            "/qwhdhub/api/mark/month/getMonthPrize", body, referer);
                    if ("SUCCESS".equals(resp.optString("code"))) {
                        JSONObject d = resp.optJSONObject("data");
                        String name = d == null ? "月签奖励"
                                : d.optString("prizeName", d.optString("name", "月签奖励"));
                        lines.add("月签 " + pool.optString("month") + "：" + name);
                        claimed++;
                    }
                    sleep(800);
                }
            }
            detail.append("月签: 领取 ").append(claimed).append(" 个\n");
        } catch (Throwable e) {
            detail.append("月签异常: ").append(e).append('\n');
        }

        // ---- 签到页任务奖励（已完成、标记可领的）----
        try {
            JSONObject taskRoot = TaskApi.post("/qwhdhub/api/mark/task/taskList", "{}", referer);
            JSONObject tdata = TaskApi.dataOf(taskRoot, "taskList");
            JSONArray tasks = tdata.optJSONArray("tasks");
            int tried = 0;
            int got = 0;
            if (tasks != null) {
                for (int i = 0; i < tasks.length() && tried < 8; i++) {
                    JSONObject task = tasks.optJSONObject(i);
                    if (task == null) {
                        continue;
                    }
                    // status=2 已完成；awardType=1 表示奖励要手动领
                    if (task.optInt("status", 0) != 2
                            || !"1".equals(task.optString("awardType"))) {
                        continue;
                    }
                    String taskId = task.optString("taskId", "");
                    if (taskId.isEmpty()) {
                        continue;
                    }
                    tried++;
                    try {
                        JSONObject resp = TaskApi.post("/qwhdhub/api/mark/task/getTaskAward",
                                "{\"taskId\":\"" + taskId + "\"}", referer);
                        if ("SUCCESS".equals(resp.optString("code"))) {
                            got++;
                            lines.add("任务「" + task.optString("taskName") + "」奖励已领");
                            detail.append("任务奖励 ").append(taskId).append(": OK\n");
                        } else {
                            detail.append("任务奖励 ").append(taskId).append(": ")
                                    .append(resp.optString("code")).append(' ')
                                    .append(resp.optString("msg")).append('\n');
                        }
                    } catch (TaskApi.TaskException e) {
                        detail.append("任务奖励 ").append(taskId).append(" 异常: ")
                                .append(e.getMessage()).append('\n');
                    }
                    sleep(900);
                }
            }
            detail.append("任务奖励: 尝试 ").append(tried).append("，成功 ").append(got).append('\n');
        } catch (Throwable e) {
            detail.append("任务奖励异常: ").append(e).append('\n');
        }

        // ---- 浏览类任务：在签到页 WebView 里逐个打开，停留 7 秒再回来 ----
        int browsed = 0;
        try {
            JSONArray tasks = fetchTasks(referer);
            int triedBrowse = 0;
            for (int i = 0; i < tasks.length() && triedBrowse < MAX_BROWSE; i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (!isBrowseTask(task)) {
                    continue;
                }
                String name = task.optString("taskName", "浏览任务");
                String jump = task.optString("jumpUrl", "");
                triedBrowse++;
                if (!PageAuto.visit(jump, referer, BROWSE_STAY_MS)) {
                    detail.append("浏览 ").append(name).append(": 页面不可用，跳过\n");
                    break;
                }
                detail.append("浏览 ").append(name).append(": 打开 ")
                        .append(BROWSE_STAY_MS / 1000).append(" 秒\n");
                sleep(BROWSE_STAY_MS + 900);
                browsed++;
            }
            if (triedBrowse > 0) {
                // 浏览完再拉一次，把变成「已完成」的奖励领掉
                sleep(1_200);
                JSONArray latest = fetchTasks(referer);
                int got = claimFinished(latest, referer, detail, lines);
                detail.append("浏览任务: 尝试 ").append(browsed)
                        .append(" 个，新领 ").append(got).append(" 个奖励\n");
            } else {
                detail.append("浏览任务: 没有待做的\n");
            }
        } catch (Throwable e) {
            detail.append("浏览任务异常: ").append(e).append('\n');
        }

        String summary = "签到完成 · 累计 " + accumulated + " 天";
        if (lines.size() > 1) {
            summary += " · " + (lines.size() - 1) + " 项奖励/变化";
        }
        StringBuilder all = new StringBuilder();
        for (String line : lines) {
            all.append("· ").append(line).append('\n');
        }
        detail.append("--- 结果 ---\n").append(all);
        return TaskRunner.Result.ok(summary, detail.toString(), all.toString().trim());
    }

    private static JSONArray fetchTasks(String referer) throws TaskApi.TaskException {
        JSONObject root = TaskApi.post(PATH_TASKS, "{}", referer);
        JSONObject data = TaskApi.dataOf(root, "taskList");
        JSONArray tasks = data.optJSONArray("tasks");
        return tasks == null ? new JSONArray() : tasks;
    }

    /** 浏览类：status=0、分类 2、jumpUrl 是 http 页面。充值/办理/订阅一律跳过。 */
    private static boolean isBrowseTask(JSONObject task) {
        if (task == null) {
            return false;
        }
        if (task.optInt("status", -1) != 0) {
            return false;
        }
        if (!"2".equals(task.optString("taskCategory"))) {
            return false;
        }
        if (!"1".equals(task.optString("jumpUrlType"))) {
            return false;
        }
        String url = task.optString("jumpUrl", "");
        if (!url.startsWith("http")) {
            return false;
        }
        String name = task.optString("taskName", "");
        String[] blocked = {"充值", "装宽带", "办理", "订购", "订阅", "开通", "购买", "申请"};
        for (String word : blocked) {
            if (name.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static int claimFinished(JSONArray tasks, String referer,
                                     StringBuilder detail, List<String> lines) {
        int got = 0;
        int tried = 0;
        for (int i = 0; i < tasks.length() && tried < 12; i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || task.optInt("status", 0) != 2) {
                continue;
            }
            if (!"1".equals(task.optString("awardType"))) {
                continue;
            }
            String id = task.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            tried++;
            try {
                JSONObject resp = TaskApi.post(PATH_TASK_AWARD,
                        "{\"taskId\":\"" + id + "\"}", referer);
                if ("SUCCESS".equals(resp.optString("code"))) {
                    got++;
                    lines.add("任务「" + task.optString("taskName") + "」奖励已领");
                }
                detail.append("任务奖励 ").append(id).append(": ")
                        .append(resp.optString("code")).append(' ')
                        .append(resp.optString("msg")).append('\n');
            } catch (TaskApi.TaskException e) {
                detail.append("任务奖励 ").append(id).append(" 异常: ")
                        .append(e.getMessage()).append('\n');
            }
            sleep(800);
        }
        return got;
    }

    private static boolean isSignedToday(JSONArray marks, String today) {
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

    private static String labelOf(JSONObject task) {
        String id = task.optString("id");
        StringBuilder sb = new StringBuilder("任务").append(id);
        String type = task.optString("taskType");
        int num = task.optInt("num", 0);
        if ("accumulate".equals(type) && num > 0) {
            sb.append("（累计").append(num).append("签）");
        }
        return sb.toString();
    }

    private static String prizeOf(JSONObject data) {
        if (data == null) {
            return "";
        }
        JSONObject prize = data.optJSONObject("markPrize");
        if (prize == null) {
            return "";
        }
        return prize.optString("name") + amountOf(prize);
    }

    private static String amountOf(JSONObject prize) {
        if (prize == null) {
            return "";
        }
        String value = prize.optString("prizeValue");
        if (value.isEmpty()) {
            return "";
        }
        String unit = "FLOW".equals(prize.optString("prizeCategory")) ? "MB" : "元";
        return "（" + value + unit + "）";
    }

    private static String hintFor(TaskApi.TaskException e) {
        if (e.sessionLost()) {
            return "Cookie 里的活动会话过期了。在 App 里重新打开一次「签到领流量」，"
                    + "模块会自己接上新会话，不用重新抓包。";
        }
        return "把这段日志发我，我看下是接口变了还是账号状态问题。";
    }

    private static void sleep(long base) {
        try {
            Thread.sleep(base + new Random().nextInt(500));
        } catch (InterruptedException ignored) {
        }
    }
}
