package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引导式采集：用 App 自己的 WebView 把活动页开到前台，用户在页面上手动点按钮，
 * 模块在旁边记下每一次点击和点击之后的请求链。
 *
 * <p>为什么不是离屏自动采集：自动采集只开页不点按钮，「点按钮之后才发的请求」
 * （领券、秒杀、报名这类）永远抓不到。引导式把页面交给用户操作，页面里的
 * 浮层按钮就是「采集完成」的开关——纯页面 DOM，不需要任何悬浮窗权限。
 *
 * <p>会话模型：{@link #runLive} 换票 + 前台开页后，{@code WebViewHooks} 在
 * onPageStarted/onPageFinished 把 WebView 登记进来；轮询线程每 2 秒从页面
 * 拉一次增量（请求 + 点击），浮层点完或 10 分钟超时收尾，报告复用单活动
 * 采集的格式落盘。
 */
final class LiveProbe {

    /** 会话超时：10 分钟没点浮层也自动收尾。 */
    private static final long TIMEOUT_MS = 10 * 60_000L;
    private static final long POLL_MS = 2_000L;

    /** 会话状态（App 进程内，同一时刻只有一个引导采集）。 */
    private static volatile String frag = "";
    private static volatile String name = "";
    private static volatile long beginAt;
    private static volatile Object webView;

    private LiveProbe() {
    }

    static boolean active() {
        return beginAt > 0;
    }

    /** WebViewHooks 页面加载时登记前台 WebView。 */
    static void attach(Object wv) {
        if (beginAt > 0 && wv != null) {
            webView = wv;
        }
    }

    /** 目标域匹配就用当前会话（登记不区分具体活动页，同域即收）。 */
    static boolean interested(String url) {
        return beginAt > 0 && url != null
                && (url.contains("10086.cn") || url.contains("lncmcc.com"));
    }

    /**
     * 入口：换票 → App 同栈前台开活动页 → 挂起会话轮询 → 收尾落盘。
     *
     * <p>跑在任务线程里（串行），整个采集过程占用任务队列，期间别的任务
     * 会排队——这是有意的，两套页面不能同时抢 App 的 WebView。
     */
    static TaskRunner.Result runLive(String actFrag, String actName, String url,
                                     StringBuilder detail) {
        long begin = System.currentTimeMillis();
        ProbeSession.start();
        frag = actFrag;
        name = actName;
        beginAt = begin;
        webView = null;
        try {
            // 裸地址进去会跳 SSO 登录页。跟跑任务一样先换带 token 的票。
            String openUrl = url;
            String withToken = SsoLogin.ensure(url, detail);
            if (withToken != null && withToken.contains("token=")) {
                openUrl = withToken;
            }
            boolean opened = Html5.openFrom(ActivityHooks.current(), openUrl);
            if (!opened) {
                opened = Html5.open(HookLog.context(), openUrl);
            }
            if (!opened) {
                return TaskRunner.Result.fail("引导采集失败：活动页打不开", detail.toString(),
                        "确认中国移动 App 能正常打开这个活动再试。");
            }

            // 轮询：拉增量、看 done、等 WebView 登记。
            List<JSONObject> reqs = new ArrayList<>();
            List<JSONObject> clicks = new ArrayList<>();
            String finalUrl = openUrl;
            String title = "";
            int failStreak = 0;
            boolean done = false;
            long deadline = begin + TIMEOUT_MS;
            while (!done && System.currentTimeMillis() < deadline) {
                WebPage.sleep(POLL_MS);
                Object wv = webView;
                if (wv == null) {
                    // 页面还没加载到 WebView 的 hook 点，继续等。
                    if (System.currentTimeMillis() - begin > 30_000L) {
                        detail.append("等了 30 秒页面还没出现，检查 App 是否打开了活动页\n");
                        break;
                    }
                    continue;
                }
                String raw = JsInjector.evalValue(wv, ProbeScript.PULL_LIVE);
                if (raw == null || raw.isEmpty() || "null".equals(raw)) {
                    failStreak++;
                    if (failStreak >= 3) {
                        detail.append("页面已关闭或拿不到数据，收尾\n");
                        break;
                    }
                    continue;
                }
                failStreak = 0;
                try {
                    JSONObject inc = new JSONObject(raw);
                    done = inc.optBoolean("done", false);
                    String u = inc.optString("url", "");
                    if (!u.isEmpty()) {
                        finalUrl = u;
                    }
                    String t = inc.optString("title", "");
                    if (!t.isEmpty() && !t.startsWith("http")) {
                        title = t;
                    }
                    JSONArray r = inc.optJSONArray("reqs");
                    if (r != null) {
                        for (int i = 0; i < r.length() && reqs.size() < 260; i++) {
                            reqs.add(r.optJSONObject(i));
                        }
                    }
                    JSONArray c = inc.optJSONArray("clicks");
                    if (c != null) {
                        for (int i = 0; i < c.length() && clicks.size() < 200; i++) {
                            clicks.add(c.optJSONObject(i));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            return finish(begin, reqs, clicks, finalUrl, title, done, detail);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("引导采集失败：" + e, detail.toString(),
                    "把这次日志发出来即可。");
        } finally {
            ProbeSession.stop();
            frag = "";
            name = "";
            beginAt = 0;
            webView = null;
        }
    }

    /** 收尾：抓最后一帧快照，组装报告，广播回模块进程落盘。 */
    private static TaskRunner.Result finish(long begin, List<JSONObject> reqs,
                                            List<JSONObject> clicks, String finalUrl,
                                            String title, boolean done,
                                            StringBuilder detail) {
        JSONObject report = new JSONObject();
        try {
            report.put("v", 1);
            report.put("at", begin);
            report.put("live", true);

            JSONObject act = new JSONObject();
            act.put("frag", frag);
            act.put("name", name);
            WelfareCatalog.Item built = WelfareCatalog.byFrag(frag);
            act.put("state", built == null
                    ? WelfareCatalog.stateName(WelfareCatalog.STATE_NEW)
                    : WelfareCatalog.stateName(built.state));
            act.put("cmd", built == null || built.cmd == null ? "" : built.cmd);
            report.put("act", act);

            // 最后一帧页面快照：控件/正文/localStorage/Vue。
            JSONArray controls = new JSONArray();
            JSONArray links = new JSONArray();
            JSONArray perf = new JSONArray();
            Set<String> controlKeys = new LinkedHashSet<>();
            Set<String> linkKeys = new LinkedHashSet<>();
            Set<String> perfKeys = new LinkedHashSet<>();
            JSONObject storage = new JSONObject();
            String text = "";
            Object wv = webView;
            if (wv != null) {
                try {
                    JSONObject shot = new JSONObject(JsInjector.evalValue(wv,
                            ProbeScript.COLLECT_ACT));
                    WelfareProbe.collectInto(shot, controls, controlKeys,
                            links, linkKeys, perf, perfKeys);
                    String t = shot.optString("title", "");
                    if (!t.isEmpty() && !t.startsWith("http")) {
                        title = t;
                    }
                    String u = shot.optString("url", "");
                    if (!u.isEmpty()) {
                        finalUrl = u;
                    }
                    JSONObject st = shot.optJSONObject("storage");
                    if (st != null) {
                        storage = st;
                    }
                    text = shot.optString("text", "");
                } catch (Throwable ignored) {
                }
            }

            JSONObject pageInfo = new JSONObject();
            pageInfo.put("title", title);
            pageInfo.put("finalUrl", finalUrl);
            pageInfo.put("mode", done ? "浮层结束" : "超时/页面关闭");
            report.put("page", pageInfo);
            report.put("controls", controls);
            report.put("links", links);
            report.put("perf", perf);
            report.put("storage", storage);
            report.put("text", text);
            report.put("clicks", new JSONArray(clicks));
            report.put("siblings", WelfareProbe.siblingJson());

            JSONObject summary = new JSONObject();
            summary.put("controls", controls.length());
            summary.put("reqs", reqs.size());
            summary.put("clicks", clicks.size());
            summary.put("cost", System.currentTimeMillis() - begin);
            report.put("summary", summary);

            String json = WelfareProbe.attach(report, new JSONArray(reqs), detail);
            if (json == null) {
                return TaskRunner.Result.fail("采集失败：报告过大，发不回来",
                        detail.toString(), "这个活动请求太多，重试一次通常就好。");
            }
            WelfareProbe.sendAct(frag, json);

            StringBuilder body = new StringBuilder();
            body.append("采集方式: 引导式（前台页面 + 手动点按钮）\n");
            body.append("采集范围: ").append(title.isEmpty() ? name : title).append('\n');
            body.append("最终地址: ").append(finalUrl).append('\n');
            if (finalUrl.contains("qwhdsso/login") || title.contains("登录")) {
                body.append("注意: 落在了登录页，这次数据不可用。换票也没成，多半是 App 登录态过期。\n");
            }
            body.append("控件: ").append(controls.length())
                    .append(" 个 · 请求: ").append(reqs.size())
                    .append(" 条 · 点击: ").append(clicks.size())
                    .append(" 次 · ").append(done ? "浮层结束" : "超时收尾").append('\n');
            body.append("报告: ").append(json.length() / 1024)
                    .append(" KB，已存到这一行，可导出\n");
            String text2 = "引导采集完成 · " + controls.length() + " 控件 · "
                    + reqs.size() + " 请求 · " + clicks.size() + " 点击";
            return TaskRunner.Result.ok(text2, body.toString(), text2);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("采集收尾失败：" + e, detail.toString(),
                    "把这次日志发出来即可。");
        }
    }
}
