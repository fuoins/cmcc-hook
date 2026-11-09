package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 周三充值日（hlwyxhdhub · act-wedrecharge · 1024101716）。
 *
 * <p>纯接口编排，不点 DOM：
 * <ol>
 *   <li>{@code querySignActInfo} 拿今天是否已签、连续天数、七天任务清单；</li>
 *   <li>{@code signFinishTask} 完成清单里可完成的签到任务（v3.92 起含 {@code llym}）；</li>
 *   <li>{@code receiveSign} 领当天的签到奖励（8 折充值券）；</li>
 *   <li>{@code queryDrawPrizeInfo} / {@code drawPrize} 用攒下的次数抽奖。</li>
 * </ol>
 *
 * <p>v3.92：21:46 日志实证，活动 11 月改版（页面公告「拼图活动暂时下线，
 * 全新 7 日打卡已于 11 月 6 日上线」）后，旧「ljdk + 第几天」编号的服务端已不收，
 * signFinishTask 一律回 160030。真正能签到的是 taskList 里的 {@code llym}
 * 「浏览签到活动页面 / 去完成」：页面自己用 {"taskId":"llym"} 调 signFinishTask
 * 回 code:0 success，随后 isTodaySign 由 0 变 1。
 * 所以候选直接从清单按标题/按钮挑，不再写死编号前缀；不按 {@code signTimes}
 * 自己推算天数 —— 断签、补签会让本地推算和真实进度对不上。
 *
 * <p>成功码是字符串 {@code "0"}，不是 qwhdhub 的 {@code SUCCESS}，所以这里不能用
 * {@link TaskApi#dataOf}。
 */
final class WedTask {

    private static final String API = "/hlwyxhdhub/api/wedrecharge/";

    /**
     * 直发全失败时，退回页面点一次「去打卡」。
     * 页面自己发请求会带上它需要的全部参数（可能含时间戳/签名），
     * 模块照着猜参数猜不出来，借它这一下最省事。
     */
    private static final String CLICK_JS = """
              (function(){
                try{
                  if(String(location.href||'').indexOf('act-wedrecharge')<0) return 'wrong-page';
                  var all=document.querySelectorAll('*'), hits=[];
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    var t=(e.textContent||'').replace(/\\s+/g,'');
                    if(t.length>12||t.indexOf('去打卡')<0) continue;
                    var deep=true;
                    for(var j=0;j<e.children.length;j++){
                      var ct=(e.children[j].textContent||'').replace(/\\s+/g,'');
                      if(ct.indexOf('去打卡')>=0){ deep=false; break }
                    }
                    if(deep) hits.push(e);
                  }
                  if(!hits.length) return 'no-btn';
                  var b=hits[0], r=b.getBoundingClientRect();
                  var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2};
                  function fire(n,C){ try{ b.dispatchEvent(new C(n,o)) }catch(x){} }
                  fire('pointerdown', window.PointerEvent||MouseEvent);
                  fire('mousedown', MouseEvent);
                  fire('pointerup', window.PointerEvent||MouseEvent);
                  fire('mouseup', MouseEvent);
                  fire('click', MouseEvent);
                  return 'clicked:'+hits.length;
                }catch(e){ return 'err:'+e }
              })()
            """;

    private WedTask() {
    }

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();

        String referer = TaskApi.findEntry("1024101716");
        if (referer.isEmpty()) {
            referer = TaskApi.findEntry("act-wedrecharge");
        }
        if (referer.isEmpty()) {
            return TaskRunner.Result.skip("周三充值日 · 还没有活动入口",
                    "福利专区里没采到这个活动的地址。",
                    "在 App 的福利专区里点开一次「周三充值日」，让模块记下入口再执行。");
        }
        detail.append("入口: ").append(referer).append('\n');

        String warmed = TaskApi.warmUp(referer, detail);
        if (warmed == null) {
            detail.append("Cookie: ").append(CookieStore.summary()).append('\n');
            return TaskRunner.Result.fail("周三充值日 · 拿不到活动会话", detail.toString(),
                    "App 的登录态没取到。确认中国移动 App 是登录状态、"
                            + "冷启动后加载过首页，再试一次。");
        }
        referer = warmed;
        detail.append("会话地址: ").append(referer).append('\n');
        detail.append("Cookie: ").append(CookieStore.summary()).append('\n');

        JSONObject data;
        try {
            JSONObject root = call("querySignActInfo", "{}", referer);
            if (!ok(root)) {
                detail.append("querySignActInfo: ").append(brief(root)).append('\n');
                return TaskRunner.Result.fail("周三充值日 · 查签到状态失败",
                        detail.toString(), "接口没返回成功码。把完整日志发我。");
            }
            data = root.optJSONObject("data");
            if (data == null) {
                data = new JSONObject();
            }
        } catch (TaskApi.TaskException e) {
            detail.append("querySignActInfo: ").append(e.getMessage()).append('\n');
            return TaskRunner.Result.fail("周三充值日 · 查签到状态失败",
                    detail.toString(), e.sessionLost()
                    ? "Cookie 失效了。冷启动中国移动 App 后重试。"
                    : "请求没发出去。把完整日志发我。");
        }

        boolean signed = "1".equals(data.optString("isTodaySign", "0"));
        boolean wasSigned = signed;
        int signTimes = data.optInt("signTimes", 0);
        detail.append("今日签到: ").append(signed ? "已签" : "未签")
                .append(" · 连续 ").append(signTimes).append(" 天\n");

        // ---- 1. 打卡：ljdkN 逐个试 ----
        int punched = 0;
        int tried = 0;
        JSONArray tasks = data.optJSONArray("taskList");
        // v3.92：服务端已回 isTodaySign=1 时不发打卡请求（21:46 日志里
        // 第二次 llym 也回 160030，属重复提交）。已签到就只走复查和抽奖，
        // 不对服务端补刀。
        String[] ids = signed ? new String[0] : punchIds(tasks);
        if (signed) {
            detail.append("今日已签到（服务端回执），跳过打卡提交\n");
        }
        detail.append("待试打卡: ").append(ids.length).append(" 个");
        if (ids.length == 0) {
            detail.append("（任务清单里没有可完成项）");
        } else {
            detail.append(" · ");
            for (int k = 0; k < ids.length; k++) {
                if (k > 0) {
                    detail.append(',');
                }
                detail.append(ids[k]);
            }
        }
        detail.append('\n');
        for (String id : ids) {
            tried++;
            try {
                // v3.92：21:46 日志 —— 160030 不是频控，是「这个 taskId 服务端不收」
                // （11 月改版后 ljdkN 已下线）。旧版对每个 ID 等 4 秒再补一发，
                // 日志里 4 发全回 160030，白等 16 秒还多占请求额度。
                // 现在 160030 立刻换下一个候选，只有其它错误才隔 2 秒重试一次。
                JSONObject r = null;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    r = call("signFinishTask", "{\"taskId\":\"" + id + "\"}", referer);
                    if (ok(r) || "160030".equals(r.optString("code", ""))) {
                        break;
                    }
                    if (attempt == 1) {
                        detail.append("打卡 ").append(id).append(": ")
                                .append(r.optString("code", "")).append(' ')
                                .append(r.optString("msg", ""))
                                .append(" · 等 2 秒重试\n");
                        sleep(2000);
                    }
                }
                String code = r.optString("code", "");
                String msg = r.optString("msg", "");
                JSONObject d = r.optJSONObject("data");
                int times = d == null ? -1 : d.optInt("signTimes", -1);
                detail.append("打卡 ").append(id).append(": ").append(code);
                if (!msg.isEmpty()) {
                    detail.append(' ').append(msg);
                }
                if (times >= 0) {
                    detail.append(" · 连续 ").append(times).append(" 天");
                    signTimes = times;
                }
                detail.append('\n');
                if (ok(r)) {
                    punched++;
                    signed = true;
                    // 一天只打一次：成功就收手，后面的 ljdkN 是别的天数，打了也是白打。
                    break;
                }
            } catch (TaskApi.TaskException e) {
                detail.append("打卡 ").append(id).append(": ").append(e.getMessage()).append('\n');
            }
            // v3.92：成功已 break，160030 立即换候选，不再按旧「频控」假设空等 2.5 秒。
        }

        // ---- 1.5 直发全失败：回页面点一次「去打卡」 ----
        // 11-08 08:33 和 08:50 两轮，signFinishTask 直发全是 160030，
        // 重试、拉间隔都没用 —— 说明不是频控，是请求本身和页面不一致。
        if (punched == 0) {
            WebPage page = WebSession.current();
            if (page != null && page.raw() != null) {
                String hit = page.eval(CLICK_JS, 8_000L);
                detail.append("页面点击: ").append(hit == null ? "无回执" : hit).append('\n');
                if (hit != null && hit.startsWith("clicked")) {
                    sleep(3_500);
                    try {
                        JSONObject again = call("querySignActInfo", "{}", referer);
                        JSONObject d = again.optJSONObject("data");
                        if (d != null && "1".equals(d.optString("isTodaySign", "0"))) {
                            punched = 1;
                            signed = true;
                            detail.append("页面点击后复查: 已签\n");
                        } else {
                            detail.append("页面点击后复查: 仍未签\n");
                        }
                    } catch (TaskApi.TaskException e) {
                        detail.append("页面点击后复查: ").append(e.getMessage()).append('\n');
                    }
                }
            } else {
                detail.append("页面点击: 当前没有活动页，跳过\n");
            }
        }

        // ---- 2. 领当天签到奖励 ----
        if (punched > 0) {
            try {
                JSONObject r = call("receiveSign", "{}", referer);
                detail.append("领签到奖励: ").append(brief(r)).append('\n');
                if (ok(r)) {
                    signed = true;
                }
            } catch (TaskApi.TaskException e) {
                detail.append("领签到奖励: ").append(e.getMessage()).append('\n');
            }
            sleep(600);
        }

        // ---- 3. 复查 ----
        try {
            JSONObject again = call("querySignActInfo", "{}", referer);
            JSONObject d = again.optJSONObject("data");
            if (d != null && ok(again)) {
                boolean now = "1".equals(d.optString("isTodaySign", "0"));
                int times = d.optInt("signTimes", signTimes);
                detail.append("复查: ").append(now ? "已签" : "仍未签")
                        .append(" · 连续 ").append(times).append(" 天\n");
                signed = now;
                signTimes = times;
            }
        } catch (TaskApi.TaskException e) {
            detail.append("复查: ").append(e.getMessage()).append('\n');
        }

        // ---- 4. 抽奖 ----
        String prize = "";
        int drawn = 0;
        String drawCode = "";
        String drawMsg = "";
        try {
            JSONObject info = call("queryDrawPrizeInfo", "{}", referer);
            int rd = 0;
            JSONObject d = info.optJSONObject("data");
            if (d != null) {
                rd = d.optInt("rdStatus", 0);
            }
            detail.append("抽奖状态 rdStatus: ").append(rd).append('\n');
            // rdStatus 的含义各期不同（见过 1 和 4），不按它决定能不能抽，
            // 直接发一次，让服务端回答；被拒就是没次数。
            for (int i = 0; i < 3; i++) {
                JSONObject r = call("drawPrize", "{\"constId\":\"\"}", referer);
                String code = r.optString("code", "");
                String msg = r.optString("msg", "");
                drawCode = code;
                drawMsg = msg;
                detail.append("抽奖 ").append(i + 1).append(": ").append(code);
                if ("160001".equals(code) || msg.contains("活动还未开始")) {
                    detail.append("（抽奖只在周三开，今天不开属正常）");
                }
                if (!msg.isEmpty()) {
                    detail.append(' ').append(msg);
                }
                JSONObject dd = r.optJSONObject("data");
                if (dd != null) {
                    String name = dd.optString("prizeName", dd.optString("shortName", ""));
                    if (!name.isEmpty()) {
                        prize = name;
                        detail.append(" · ").append(name);
                    }
                }
                detail.append('\n');
                if (!ok(r)) {
                    break;
                }
                drawn++;
                sleep(900);
            }
        } catch (TaskApi.TaskException e) {
            detail.append("抽奖: ").append(e.getMessage()).append('\n');
        }

        // ---- 结果 ----
        StringBuilder summary = new StringBuilder("周三充值日 · ");
        if (punched > 0 || drawn > 0 || (!wasSigned && signed)) {
            if (punched > 0) {
                summary.append("打卡 ").append(punched).append(" 个");
            } else {
                summary.append("签到成功");
            }
            if (!prize.isEmpty()) {
                summary.append(" · 中 ").append(prize);
            } else if (drawn > 0) {
                summary.append(" · 抽奖 ").append(drawn).append(" 次");
            }
            return TaskRunner.Result.ok(summary.toString(), detail.toString(), summary.toString());
        }
        if (signed) {
            // v3.92：isTodaySign=1 是服务端权威回执（21:46 日志实证），
            // 说明今天确实已签到 —— 不是假 OK，但也不是本次提交，文案说清楚。
            String text = "周三充值日 · 今日已签到（服务端回执，非本次提交）"
                    + (signTimes > 0 ? "（连续 " + signTimes + " 天）" : "");
            return TaskRunner.Result.idle(text, detail.toString(), text);
        }
        String text = "周三充值日 · 打卡和领奖都没成功";
        return TaskRunner.Result.fail(text, detail.toString(),
                "打卡接口回 160030 不是频控：11 月起拼图打卡已下线、改成 7 日打卡，"
                        + "旧的 ljdkN 服务端不收了。v3.92 已改用页面实证的签到任务（llym），"
                        + "再执行一次；仍不行把完整日志发我。");
    }

    /**
     * v3.92：候选不再写死 {@code ljdk} 前缀。21:46 日志里真正签到成功的是
     * {@code llym}「浏览签到活动页面 / 去完成」，而 ljdk2/ljdk4 全回 160030
     * （11 月改版后旧编号已下线）。现在按「可完成」从 taskList 里挑：
     * 浏览/签到/完成类排最前，去打卡的 ljdkN 兜底，其余未完成的最后。
     *
     * <p>明确排除「去邀请 / 去充值 / 助力」—— 拉人和花钱的事模块绝不代做。
     */
    private static String[] punchIds(JSONArray tasks) {
        if (tasks == null) {
            return new String[0];
        }
        java.util.List<String> first = new java.util.ArrayList<>();
        java.util.TreeMap<Integer, String> daka = new java.util.TreeMap<>();
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            // status: 0 未完成 / 1、2 已完成，已完成的不再重复打。
            int status = t.optInt("status", 0);
            if (status == 1 || status == 2) {
                continue;
            }
            String btn = t.optString("buttontxt", "");
            String title = t.optString("title", "");
            // 不代邀请、不代充值、不代助力。
            if (btn.contains("邀请") || btn.contains("充值") || title.contains("助力")) {
                continue;
            }
            boolean signLike = title.contains("浏览") || title.contains("签到")
                    || btn.contains("完成") || btn.contains("签到");
            boolean dakaLike = btn.contains("打卡");
            if (signLike && !dakaLike) {
                first.add(id);
            } else if (dakaLike) {
                int n = numOf(id);
                daka.put(n >= 0 ? n : 1000 + rest.size(), id);
            } else {
                rest.add(id);
            }
        }
        java.util.List<String> out = new java.util.ArrayList<>(first);
        out.addAll(daka.values());
        out.addAll(rest);
        return out.toArray(new String[0]);
    }

    /** 取 ljdkN 里的天数；不是这种编号返回 -1。 */
    private static int numOf(String id) {
        if (!id.startsWith("ljdk")) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(4));
        } catch (Throwable e) {
            return -1;
        }
    }

    private static JSONObject call(String name, String body, String referer)
            throws TaskApi.TaskException {
        return TaskApi.post(API + name, body, referer);
    }

    /** wedrecharge 的成功码是字符串 "0"。 */
    private static boolean ok(JSONObject root) {
        return root != null && "0".equals(root.optString("code", ""));
    }

    private static String brief(JSONObject root) {
        if (root == null) {
            return "无返回";
        }
        return root.optString("code", "?") + " " + root.optString("msg", "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
