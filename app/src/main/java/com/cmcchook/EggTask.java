package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * qwhdhub 砸蛋模板（天天砸金蛋 1026071510 / 充值先领券 1025093012）。
 *
 * <p>抓包还原出来的完整链路（11-06 A砸蛋3 人工全量实证）：
 * <ol>
 *   <li>POST /qwhdhub/lottery/remain {} → data 是剩余次数（整数）</li>
 *   <li>GET  /qwhdhub/task/list?backUrl=… → 8 个任务，url 是完整
 *       /qwhdhub/task/startBrowse/{taskId}?pageId…&amp;touch_id…&amp;targetUrl… 相对地址，
 *       taskStage=UNDO / todayRemain=1 表示可做</li>
 *   <li>GET  startBrowse → 服务端登记任务（次数 +1），302 到外域落地页</li>
 *   <li>GET  /qwhdhub/lottery/lotterySafely → SUCCESS(data.prizeName) 或 NOT_WON</li>
 * </ol>
 *
 * <p>会话机制（11-06 实证，和签到不是一套）：页面 URL 全程只有
 * {@code ?A_C_CODE=…}，<b>不带 token</b>；页面靠 WebView 里的 Cookie +
 * localStorage.QWHDSSOJWT + 请求头 Hlwyxhd-Device-Code 认会话。
 * 用户先在 App 里打开一次活动页（前台 WebView 自己走 qwhdsso/login 换票），
 * 之后的离屏页同域共享这份登录态，接口直接 200。
 *
 * <p>所以这里<b>不再做模块自己的换票</b>：旧版先跑 SsoLogin.ensure /
 * TaskApi.warmUp，用「模块内存里有没有会话记录」判定登录态，结果
 * leadeon.getUserInfo 回调超时就把明明 200 的现场判成「拿不到会话」，
 * 一个业务接口都没发、白等 80 秒（A砸蛋2）。现在一律以真实接口响应为准。
 */
final class EggTask {

    private static final String PATH_REMAIN = "/qwhdhub/lottery/remain";
    private static final String PATH_LOTTERY = "/qwhdhub/lottery/lotterySafely";
    private static final String PATH_TASKS = "/qwhdhub/task/list";

    private static final int MAX_LOTTERY = 15;
    private static final int MAX_TASKS = 12;

    private EggTask() {
    }

    static TaskRunner.Result run(String referer, String title) {
        StringBuilder detail = new StringBuilder();
        List<String> lines = new ArrayList<>();

        detail.append("▶ 次数查询\n");
        detail.append("入口: ").append(referer).append('\n');
        if (referer.isEmpty()) {
            return TaskRunner.Result.fail("还没有拿到" + title + "入口",
                    "福利专区里没有这个活动页的地址。",
                    "打开一次中国移动 App 的「福利专区」，让模块把入口采下来再试。");
        }

        // 会话是否有效，只认真实接口响应：把 App 的登录 Cookie 灌进 WebView，
        // 再查一次剩余次数。200 + SUCCESS 就是有效，直接往下做。
        CookieStore.flushToWebView();
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
        int remain = readRemain(referer, detail);
        if (remain < 0) {
            // 探活没过不猜、不兜底：真实软件这一步就是靠 App 前台页面先登录。
            return TaskRunner.Result.fail(title + "页面现场未登录",
                    detail.toString(),
                    "先在 App 里手动打开一次「" + title + "」页面（让它换好票），再回来点执行任务。");
        }
        int base = remain;

        // ---- 做浏览任务换次数 ----
        detail.append("▶ 浏览任务\n");
        int triggered = 0;
        int taskCount = 0;
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
                String stage = task.optString("taskStage", "");
                int todayRemain = task.optInt("todayRemain", 0);
                String url = task.optString("url", "");
                String name = task.optString("name", "任务" + i);
                if (!"UNDO".equals(stage) || todayRemain <= 0 || url.isEmpty()) {
                    continue;
                }
                try {
                    // startBrowse 会 302 到外域落地页，服务端在 302 之前就已经
                    // 登记任务并 +1 次。fetch 跟随重定向可能被跨域拦，HTTP 状态
                    // 不代表登记结果，所以不靠单次状态判定，统一在下面复查次数。
                    // v4.20.0-r33：改成不跟跳转（redirect:manual），拿到的状态是 0，
                    // 不再出现「HTTP -1」这种看着像失败、其实已经登记成功的日志。
                    TaskNet.Resp resp = TaskNet.getNoFollow(TaskNet.BASE + url, referer,
                            TaskApi.eggHeaders());
                    detail.append("任务 ").append(name).append(": 已发出 startBrowse（")
                            .append(resp.status == 0 ? "302 登记，不跟落地页"
                                    : "HTTP " + resp.status).append("）\n");
                    triggered++;
                } catch (Throwable e) {
                    detail.append("任务 ").append(name).append(" 异常: ").append(e).append('\n');
                }
                sleep(300);
            }
        } catch (TaskApi.TaskException e) {
            detail.append("任务列表失败: ").append(e.getMessage()).append('\n');
        }

        // ---- 以真实次数增长判定任务是否到账（不靠 startBrowse 的 HTTP 状态）----
        detail.append("▶ 次数复查\n");
        for (int i = 0; i < 3; i++) {
            remain = readRemain(referer, detail);
            if (remain > base || triggered == 0) {
                break;
            }
            // r18：400ms 太短，服务端登记浏览任务通常要 1~2 秒。三轮回 1.5 秒。
            sleep(1_500);
        }
        int gained = Math.max(remain - base, 0);
        detail.append("任务: 共 ").append(taskCount).append(" 个，触发 ").append(triggered)
                .append(" 个，次数 ").append(base).append(" → ").append(remain)
                .append("（到账 +").append(gained).append("）\n");
        if (triggered > 0) {
            lines.add("触发 " + triggered + " 个浏览任务，次数到账 +" + gained);
        }

        if (remain <= 0) {
            // r18：触发了任务但次数一分没到账 —— 说明只发出了 startBrowse，
            // 服务端没认这笔浏览（这类任务通常要求真打开落地页并停留几秒）。
            // 以前这里走 idle，idle 的 ok 是 true，汇总表会打成 ✓，看着像做成了。
            if (triggered > 0 && gained <= 0) {
                String text = "触发 " + triggered + " 个浏览任务，次数 " + base + "→" + remain
                        + " 没到账（需人工进落地页停留）";
                detail.append("结果: ").append(text).append('\n');
                return TaskRunner.Result.skip(title + " · " + text, detail.toString(),
                        "这些浏览任务要真打开落地页并停留几秒才算。模块只发出了 startBrowse 请求。\n"
                                + "请在 App 里手动点开一次任务链接，再回来执行。");
            }
            String text = triggered > 0
                    ? "做了 " + triggered + " 个任务，次数仍为 0"
                    : "今天没有抽奖次数";
            detail.append("结果: ").append(text).append('\n');
            return TaskRunner.Result.idle(title + " · " + text, detail.toString(), "· " + text);
        }

        // ---- 抽奖 ----
        detail.append("▶ 抽奖\n");
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
            sleep(300);
        }

        // ---- 做完检查：次数真的被扣掉了才算抽中过 ----
        detail.append("▶ 抽奖复查\n");
        int after = readRemain(referer, detail);
        detail.append("复查: 抽奖前 ").append(remain).append(" 次，抽 ")
                .append(wins + miss).append(" 次，剩余 ")
                .append(after < 0 ? "查询失败" : String.valueOf(after)).append('\n');
        if (after >= 0 && after >= remain && (wins + miss) > 0) {
            detail.append("复查: 次数没有减少，抽奖可能没生效\n");
        }

        StringBuilder summary = new StringBuilder(title).append(" · ");
        if (wins > 0) {
            summary.append("中 ").append(wins).append(" 次：").append(join(prizes));
        } else if (miss > 0) {
            summary.append("抽 ").append(miss).append(" 次，暂未中奖");
        } else {
            summary.append("抽奖未成功");
        }
        if (after > 0) {
            summary.append("，还剩 ").append(after).append(" 次");
        }

        detail.append("--- 结果 ---\n");
        for (String line : lines) {
            detail.append("· ").append(line).append('\n');
        }
        String text = summary.toString();
        boolean effective = (wins + miss) > 0;
        if (!effective) {
            return TaskRunner.Result.fail(text, detail.toString(), text);
        }
        return TaskRunner.Result.ok(text, detail.toString(), text);
    }

    /** @return 剩余次数；-1 表示查询失败（多半是页面现场没登录）。 */
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

    /** 防风控的短间隔，不再是旧版每任务 1.5s / 每抽 1.8s 的固定等待。 */
    private static void sleep(long base) {
        try {
            Thread.sleep(base + new Random().nextInt(250));
        } catch (InterruptedException ignored) {
        }
    }
}
