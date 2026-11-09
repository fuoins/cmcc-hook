package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 拆一百元礼盒（活动 id 1026102314，页面 /qwhdhub/tearbox/1026102314）。
 *
 * <p>11-06 A/B 两份人工全量日志实证的链路，全部是 qwhdhub 明文接口，
 * 和砸金蛋同一套会话机制（Cookie + Hlwyxhd-Device-Code，URL 只带 A_C_CODE）：
 * <ol>
 *   <li>GET /qwhdhub/activity/info → 规则：每日 2 次拆礼盒机会，
 *       做任务最多再加 3 次（当日 24 时清零）</li>
 *   <li>GET /qwhdhub/task/list?backUrl=… → 3 个任务，
 *       type=browse 的 url 是 /qwhdhub/task/startBrowse/{taskId}?…完整相对地址；
 *       邀请好友那条是 type=share_view，要真实好友，模块不代做</li>
 *   <li>GET startBrowse → 服务端登记任务、拆礼盒机会 +1</li>
 *   <li>GET /qwhdhub/lottery/lotterySafely → SUCCESS(data.prizeName) 或 NOT_WON</li>
 * </ol>
 *
 * <p>次数上限按活动规则取（2 次免费 + 3 次任务），不凭空放大；
 * 服务端一旦拒绝就停手，不硬试、不把「没抽」记成成功。
 */
final class TearBoxTask {

    private static final String PATH_REMAIN = "/qwhdhub/lottery/remain";
    private static final String PATH_LOTTERY = "/qwhdhub/lottery/lotterySafely";
    private static final String PATH_TASKS = "/qwhdhub/task/list";

    /** 规则写的：每日 2 次免费 + 任务最多 3 次。 */
    private static final int RULE_MAX_DRAW = 5;
    private static final int MAX_TASKS = 4;

    private TearBoxTask() {
    }

    static TaskRunner.Result run() {
        String referer = TaskApi.findEntry("tearbox/1026102314");
        StringBuilder detail = new StringBuilder();
        List<String> lines = new ArrayList<>();

        detail.append("▶ 入口\n");
        detail.append("地址: ").append(referer).append('\n');
        if (referer.isEmpty()) {
            return TaskRunner.Result.fail("还没有拿到拆礼盒入口",
                    "福利页内置表里没有拆一百元礼盒的地址。",
                    "先在 App 里手动打开一次「拆一百元礼盒」页面，再回来点执行任务。");
        }

        CookieStore.flushToWebView();
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');

        // 探活：activity/info 是页面进站第一个接口，能 SUCCESS 就说明登录态在。
        if (!probe(referer, detail)) {
            return TaskRunner.Result.fail("拆礼盒页面现场未登录",
                    detail.toString(),
                    "先在 App 里手动打开一次「拆一百元礼盒」页面（让它换好票），再回来点执行任务。");
        }
        int base = readRemain(referer, detail);
        int remain = base;

        // ---- 做浏览任务换次数 ----
        detail.append("▶ 浏览任务\n");
        int triggered = 0;
        int taskCount = 0;
        int skippedShare = 0;
        try {
            JSONArray tasks = TaskApi.arrayOf(
                    TaskApi.eggGet(PATH_TASKS + "?backUrl="
                            + TaskApi.encode(referer + "#/"), referer), PATH_TASKS);
            taskCount = tasks.length();
            for (int i = 0; i < tasks.length() && triggered < MAX_TASKS; i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String type = task.optString("type", "");
                String stage = task.optString("taskStage", "");
                int todayRemain = task.optInt("todayRemain", 0);
                String url = task.optString("url", "");
                String name = task.optString("name", "任务" + i);
                if (!"UNDO".equals(stage) || todayRemain <= 0 || url.isEmpty()) {
                    continue;
                }
                if (!"browse".equals(type)) {
                    // 邀请好友要真实好友点开，模块不代发。
                    skippedShare++;
                    detail.append("任务 ").append(name).append(": type=").append(type)
                            .append("，要真实操作，跳过\n");
                    continue;
                }
                try {
                    // startBrowse 在 302 之前就登记完成，HTTP 状态不代表结果，
                    // 统一在下面用真实次数复查。
                    TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + url, referer,
                            TaskApi.eggHeaders());
                    detail.append("任务 ").append(name).append(": 已触发 startBrowse（HTTP ")
                            .append(resp.status).append("）\n");
                    triggered++;
                } catch (Throwable e) {
                    detail.append("任务 ").append(name).append(" 异常: ").append(e).append('\n');
                }
                sleep(300);
            }
        } catch (TaskApi.TaskException e) {
            detail.append("任务列表失败: ").append(e.getMessage()).append('\n');
        }

        // ---- 以真实次数增长判定任务是否到账 ----
        detail.append("▶ 次数复查\n");
        for (int i = 0; i < 3; i++) {
            remain = readRemain(referer, detail);
            if (remain > base || triggered == 0) {
                break;
            }
            sleep(400);
        }
        int gained = Math.max(remain - base, 0);
        detail.append("任务: 共 ").append(taskCount).append(" 个，触发 ").append(triggered)
                .append(" 个");
        if (skippedShare > 0) {
            detail.append("，跳过 ").append(skippedShare).append(" 个要真实操作的");
        }
        detail.append("，次数 ").append(base).append(" → ").append(remain)
                .append("（到账 +").append(gained).append("）\n");
        if (triggered > 0) {
            lines.add("触发 " + triggered + " 个浏览任务，次数到账 +" + gained);
        }

        if (remain == 0) {
            String text = triggered > 0 ? "做了任务但次数没到账" : "今天没有拆礼盒次数";
            detail.append("结果: ").append(text).append('\n');
            return TaskRunner.Result.idle("拆礼盒 · " + text, detail.toString(), "· " + text);
        }

        // ---- 抽奖 ----
        detail.append("▶ 拆礼盒\n");
        int draws = 0;
        int wins = 0;
        int miss = 0;
        List<String> prizes = new ArrayList<>();
        int limit = RULE_MAX_DRAW;
        if (remain > 0) {
            limit = Math.min(remain, RULE_MAX_DRAW);
        } else if (remain == 0) {
            // 接口说 0 次：试一次确认真没有，被拒就停。
            limit = 1;
        }
        for (int i = 0; i < limit; i++) {
            try {
                JSONObject resp = TaskApi.eggGet(PATH_LOTTERY, referer);
                String code = resp.optString("code", "");
                String status = resp.optString("status", "");
                String msg = resp.optString("msg", "");
                if ("SUCCESS".equals(code)) {
                    String prize = prizeName(resp);
                    wins++;
                    draws++;
                    prizes.add(prize.isEmpty() ? "奖品" : prize);
                    lines.add("第 " + (i + 1) + " 次：" + (prize.isEmpty() ? "中奖" : prize));
                    detail.append("拆第 ").append(i + 1).append(" 次: SUCCESS ")
                            .append(prize).append('\n');
                } else if ("NOT_WON".equals(code) || "NOT_WON".equals(status)) {
                    miss++;
                    draws++;
                    detail.append("拆第 ").append(i + 1).append(" 次: 未中奖\n");
                } else {
                    detail.append("拆第 ").append(i + 1).append(" 次: ").append(code)
                            .append(' ').append(msg).append("（停止）\n");
                    break;
                }
            } catch (TaskApi.TaskException e) {
                detail.append("抽奖异常: ").append(e.getMessage()).append('\n');
                lines.add("抽奖异常：" + e.getMessage());
                break;
            }
            sleep(320);
        }

        // ---- 复查：次数真的被扣掉 ----
        detail.append("▶ 复查\n");
        int after = readRemain(referer, detail);
        detail.append("复查: 抽奖前 ").append(remain).append(" 次，抽 ").append(draws)
                .append(" 次，剩余 ").append(after < 0 ? "查询失败" : String.valueOf(after))
                .append('\n');

        StringBuilder summary = new StringBuilder("拆礼盒 · ");
        if (wins > 0) {
            summary.append("中 ").append(wins).append(" 次：").append(join(prizes));
        } else if (miss > 0) {
            summary.append("拆 ").append(miss).append(" 次，暂未中奖");
        } else {
            summary.append("没有拆成");
        }
        if (after > 0) {
            summary.append("，还剩 ").append(after).append(" 次");
        }

        detail.append("--- 结果 ---\n");
        for (String line : lines) {
            detail.append("· ").append(line).append('\n');
        }
        String text = summary.toString();
        if (draws <= 0) {
            return TaskRunner.Result.fail(text, detail.toString(), text);
        }
        return TaskRunner.Result.ok(text, detail.toString(), text);
    }

    /** 探活：activity/info 能返回 SUCCESS 就算页面现场登录着。 */
    private static boolean probe(String referer, StringBuilder detail) {
        try {
            JSONObject info = TaskApi.eggGet("/qwhdhub/activity/info", referer);
            String code = info.optString("code", "");
            detail.append("活动信息: ").append(code).append(' ')
                    .append(info.optString("msg")).append('\n');
            return "SUCCESS".equals(code);
        } catch (TaskApi.TaskException e) {
            detail.append("活动信息查询失败: ").append(e.getMessage()).append('\n');
            return false;
        }
    }

    /** @return 剩余次数；-1 表示这个活动不认 remain 接口（不当作未登录）。 */
    private static int readRemain(String referer, StringBuilder detail) {
        try {
            JSONObject resp = TaskApi.eggPost(PATH_REMAIN, "{}", referer);
            String code = resp.optString("code", "");
            if (!code.isEmpty() && !"SUCCESS".equals(code)) {
                detail.append("剩余次数查询失败: ").append(code).append(' ')
                        .append(resp.optString("msg")).append('\n');
                return -1;
            }
            int value = resp.optInt("data", 0);
            detail.append("剩余次数: ").append(value).append('\n');
            return Math.max(value, 0);
        } catch (TaskApi.TaskException e) {
            detail.append("剩余次数查询失败: ").append(e.getMessage()).append('\n');
            return -1;
        }
    }

    /** 拆礼盒 data 是单个对象；兼容数字/字符串形态。 */
    private static String prizeName(JSONObject resp) {
        Object data = resp.opt("data");
        if (data instanceof JSONObject) {
            JSONObject obj = (JSONObject) data;
            String name = obj.optString("prizeName", "");
            if (!name.isEmpty()) {
                return name;
            }
            name = obj.optString("name", "");
            if (!name.isEmpty()) {
                return name;
            }
            JSONObject prize = obj.optJSONObject("prize");
            if (prize != null) {
                return prize.optString("name", "");
            }
        } else if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            JSONObject one = array.optJSONObject(0);
            if (one != null) {
                return one.optString("prizeName", one.optString("name", ""));
            }
        } else if (data instanceof String) {
            String text = (String) data;
            if (text.length() <= 24) {
                return text;
            }
        }
        return "";
    }

    private static String join(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < 5; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(items.get(i));
        }
        if (items.size() > 5) {
            sb.append(" 等").append(items.size()).append("个");
        }
        return sb.toString();
    }

    private static void sleep(long base) {
        try {
            Thread.sleep(base + new Random().nextInt(250));
        } catch (InterruptedException ignored) {
        }
    }
}
