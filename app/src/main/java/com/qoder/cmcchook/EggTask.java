package com.qoder.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 天天砸金蛋（活动 id 1026071510）。
 *
 * 抓包还原出来的完整链路：
 *   1. POST /qwhdhub/lottery/remain        → data 就是剩余次数（整数）
 *   2. GET  /qwhdhub/task/list?backUrl=…   → 每个任务带一条 startBrowse 相对地址
 *   3. GET  /qwhdhub/task/startBrowse/…    → 服务端登记 + 302 到落地页，任务变 DONE，次数 +1
 *   4. GET  /qwhdhub/lottery/lotterySafely → 抽一次，扣一次机会
 *
 * 这套接口不带 login-check 语义，只认 Hlwyxhd-Device-Code，所以走 eggGet/eggPost。
 * 请求体全部是空对象，参数都在 URL 和 Referer 里。
 */
final class EggTask {

    private static final String PATH_REMAIN = "/qwhdhub/lottery/remain";
    private static final String PATH_LOTTERY = "/qwhdhub/lottery/lotterySafely";
    private static final String PATH_TASKS = "/qwhdhub/task/list";

    private static final int MAX_LOTTERY = 15;
    private static final int MAX_TASKS = 12;

    private EggTask() {
    }

    static TaskRunner.Result run() {
        String referer = TaskApi.ACT_EGG;
        StringBuilder detail = new StringBuilder();
        List<String> lines = new ArrayList<>();

        detail.append("入口: ").append(referer).append('\n');
        String warn = TaskApi.warmUp(referer, detail);
        if (warn == null) {
            detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
            return TaskRunner.Result.fail("拿不到砸金蛋会话", detail.toString(),
                    "App 的登录态没取到。确认中国移动 App 是登录状态、"
                            + "并且冷启动后加载过首页，再试一次。");
        }
        referer = warn;
        detail.append("会话地址: ").append(referer).append('\n');
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');

        int remain = readRemain(referer, detail);

        // ---- 做浏览任务换次数 ----
        int browsed = 0;
        int taskCount = 0;
        try {
            JSONArray tasks = TaskApi.arrayOf(
                    TaskApi.eggGet(PATH_TASKS + "?backUrl="
                            + TaskApi.encode(referer + "#/"), referer), PATH_TASKS);
            taskCount = tasks.length();
            for (int i = 0; i < tasks.length() && browsed < MAX_TASKS; i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String stage = task.optString("taskStage", "");
                int todayRemain = task.optInt("todayRemain", 0);
                String url = task.optString("url", "");
                String name = task.optString("name", "任务" + i);
                if (!"UNDO".equals(stage) || todayRemain <= 0 || url.isEmpty()) {
                    continue;
                }
                try {
                    TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + url, referer,
                            TaskApi.eggHeaders());
                    boolean ok = resp.status == 302 || resp.status == 200;
                    detail.append("任务 ").append(name).append(": HTTP ")
                            .append(resp.status).append('\n');
                    if (ok) {
                        browsed++;
                        lines.add("完成「" + name + "」+1 次");
                    }
                } catch (Throwable e) {
                    detail.append("任务 ").append(name).append(" 异常: ").append(e).append('\n');
                }
                sleep(1_500);
            }
            detail.append("任务: 共 ").append(taskCount).append(" 个，完成 ").append(browsed).append('\n');
        } catch (TaskApi.TaskException e) {
            detail.append("任务列表失败: ").append(e.getMessage()).append('\n');
        }

        // ---- 重新读次数 ----
        remain = readRemain(referer, detail);
        if (remain <= 0) {
            String text = browsed > 0
                    ? "做完 " + browsed + " 个任务但次数没到账，稍后再试"
                    : "当前没有抽奖次数";
            detail.append("结果: ").append(text).append('\n');
            return TaskRunner.Result.ok("砸金蛋 · " + text, detail.toString(),
                    "· " + text);
        }

        // ---- 抽奖 ----
        int wins = 0;
        int miss = 0;
        List<String> prizes = new ArrayList<>();
        int rounds = Math.min(remain, MAX_LOTTERY);
        for (int i = 0; i < rounds; i++) {
            try {
                JSONObject resp = TaskApi.eggGet(PATH_LOTTERY, referer);
                String code = resp.optString("code", "");
                String status = resp.optString("status", "");
                String msg = resp.optString("msg", "");
                if ("SUCCESS".equals(code)) {
                    String prize = prizeName(resp);
                    wins++;
                    prizes.add(prize.isEmpty() ? "奖品" : prize);
                    lines.add("第 " + (i + 1) + " 次：" + (prize.isEmpty() ? "中奖" : prize));
                    detail.append("抽奖 ").append(i + 1).append(": SUCCESS ").append(prize).append('\n');
                } else if ("NOT_WON".equals(code) || "NOT_WON".equals(status)) {
                    miss++;
                    detail.append("抽奖 ").append(i + 1).append(": 未中奖\n");
                } else {
                    detail.append("抽奖 ").append(i + 1).append(": ").append(code)
                            .append(' ').append(msg).append('\n');
                    lines.add("抽奖中止：" + code + " " + msg);
                    break;
                }
            } catch (TaskApi.TaskException e) {
                detail.append("抽奖异常: ").append(e.getMessage()).append('\n');
                lines.add("抽奖异常：" + e.getMessage());
                break;
            }
            sleep(1_800);
        }

        StringBuilder summary = new StringBuilder("砸金蛋 · ");
        if (wins > 0) {
            summary.append("中 ").append(wins).append(" 次：")
                    .append(join(prizes));
        } else {
            summary.append("抽 ").append(wins + miss).append(" 次，暂未中奖");
        }
        if (miss > 0 && wins > 0) {
            summary.append("（另有 ").append(miss).append(" 次未中）");
        }

        detail.append("--- 结果 ---\n");
        for (String line : lines) {
            detail.append("· ").append(line).append('\n');
        }
        String text = summary.toString();
        return TaskRunner.Result.ok(text, detail.toString(), text);
    }

    private static int readRemain(String referer, StringBuilder detail) {
        try {
            JSONObject resp = TaskApi.eggPost(PATH_REMAIN, "{}", referer);
            int value = resp.optInt("data", 0);
            detail.append("剩余次数: ").append(value).append('\n');
            return Math.max(value, 0);
        } catch (TaskApi.TaskException e) {
            detail.append("剩余次数查询失败: ").append(e.getMessage()).append('\n');
            return 0;
        }
    }

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
        } else if (data instanceof String) {
            String text = (String) data;
            // 中奖时 data 是奖品文案；未中奖是活动配置的提示语，这里只取短的
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
            Thread.sleep(base + new Random().nextInt(700));
        } catch (InterruptedException ignored) {
        }
    }
}
