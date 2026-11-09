package com.cmcchook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 探路器：模块自己打开福利中心，把里面的入口一个个走一遍，把证据带回来。
 *
 * <p>为什么需要它：福利中心是「中国移动商城」那套页面（businessPlatform/shop），
 * 数据由 App 原生接口下发，DOM 里几乎是空的；靠人肉点、靠抓包发日志，
 * 一轮下来还是不知道某个入口到底是什么玩法、发了哪些接口。
 *
 * <p>这里做的事跟人手动操作一样：
 * <ol>
 *   <li>用 App 自己的 UA 和 Cookie 打开福利中心；</li>
 *   <li>注入探针，记下页面自己发的 XHR/fetch 和原生侧发出的请求；</li>
 *   <li>分屏滚动，把懒加载的楼层逼出来，每屏采一次控件和正文；</li>
 *   <li>从页面链接、请求 URL、接口响应体三处里找活动入口；</li>
 *   <li>对每个入口再开一页，同样采集一次，拿到该活动的按钮和接口；</li>
 *   <li>汇总成一份 JSON 报告，广播回模块进程落盘。</li>
 * </ol>
 *
 * <p>全过程离屏执行，不占用户的界面。报告里既有「能点哪些按钮」，
 * 也有「点了之后发了什么请求、返回了什么」，下次接新活动不用再让人抓包。
 */
final class WelfareProbe {

    /** 福利中心入口。辽宁这套 pageid 实测可用；换省时页面会自己重定向到本省配置。 */
    static final String ENTRY = "https://wx.10086.cn/website/businessPlatform/shop"
            + "?pageid=f38143861a4a4a9fac920fdeecd119bb&channelId=P00000054178&yx=1597107001";

    private static final String[] HOSTS = {"10086.cn", "lncmcc.com", "cmcc-cs.cn"};

    /** 值得再进去看的入口特征。 */
    private static final String[] ACT_HINTS = {
            "qwhdhub", "hlwyxhdhub", "rightsmarket-h5-canvas", "diy-client",
            "goldeneggs", "turntable", "newbuildgroup", "coc3", "coc/web6",
            "act-wedrecharge", "raiseGoose",
    };

    /** 明显不是活动的：图片、脚本、埋点、商品详情、下载引导。 */
    private static final String[] SKIP_HINTS = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".css", ".js", ".woff",
            "beian.miit", "transferDownload", "dx.10086.cn", "app.coc.10086.cn/download",
            "gdp_", "sensorsdata", "jiguang", "growing",
    };

    private static final int MAX_PAGES = 8;
    private static final int SCROLL_STEPS = 4;
    private static final int MAX_CANDIDATES = 40;

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{12,300}");

    private WelfareProbe() {
    }

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();
        long begin = System.currentTimeMillis();
        ProbeSession.start();
        try {
            JSONObject report = new JSONObject();
            report.put("v", 1);
            report.put("at", System.currentTimeMillis());
            report.put("entry", ENTRY);

            JSONArray pages = new JSONArray();
            List<String[]> found = new ArrayList<>();

            // ---- 第一页：福利中心首屏 + 滚动 ----
            Page main = visit(ENTRY, "福利中心", detail);
            if (main == null) {
                return TaskRunner.Result.fail("探路失败：福利中心页面打不开",
                        detail.toString(), "确认中国移动 App 能正常打开「领福利」再试。");
            }
            pages.put(main.json);
            found.addAll(main.candidates);
            if (main.url != null && main.url.contains("qwhdsso/login")
                    || main.title.contains("登录")) {
                detail.append("注意: 落在了登录页（").append(main.title).append("）\n")
                        .append("  福利中心要靠 App 的登录态，探路前先打开一次中国移动首页或「领福利」，")
                        .append("让 App 自己续一下 SSO，再点开始探路。\n");
            }
            detail.append("首屏: ").append(main.title).append(" · ")
                    .append(main.controls).append(" 个控件 · ")
                    .append(main.reqs).append(" 条请求\n");

            // ---- 后面几页：挑出来的活动入口逐个进去看 ----
            Set<String> seen = new LinkedHashSet<>();
            for (String[] one : found) {
                seen.add(one[1]);
            }
            int visited = 0;
            for (String[] one : found) {
                if (visited >= MAX_PAGES) {
                    detail.append("入口: 还有 ").append(found.size() - visited)
                            .append(" 个没进（单次上限 ").append(MAX_PAGES).append(" 个）\n");
                    break;
                }
                if (one[1].equals(ENTRY)) {
                    continue;
                }
                Page page = visit(one[1], one[0], detail);
                visited++;
                if (page == null) {
                    detail.append("跳过: ").append(one[0]).append(" · 打不开\n");
                    continue;
                }
                pages.put(page.json);
                detail.append("入口 ").append(visited).append(": ").append(page.title)
                        .append(" · ").append(page.controls).append(" 控件 · ")
                        .append(page.reqs).append(" 请求\n");
                for (String[] child : page.candidates) {
                    if (seen.add(child[1])) {
                        found.add(child);
                    }
                }
            }

            report.put("pages", pages);
            report.put("candidates", candidatesToJson(found));
            report.put("session", ProbeSession.dump(200));

            JSONObject summary = new JSONObject();
            summary.put("pages", pages.length());
            summary.put("reqs", ProbeSession.size());
            summary.put("candidates", found.size());
            summary.put("cost", System.currentTimeMillis() - begin);
            report.put("summary", summary);

            String json = report.toString();
            send(json);
            detail.append("会话请求: ").append(ProbeSession.size()).append(" 条\n");
            detail.append("候选入口: ").append(found.size()).append(" 个\n");
            for (int i = 0; i < found.size() && i < 20; i++) {
                detail.append("  ").append(found.get(i)[0]).append("  ")
                        .append(ProbeSession.shortOf(found.get(i)[1])).append('\n');
            }
            detail.append("报告: ").append(json.length() / 1024).append(" KB，")
                    .append("已存到「福利」页，可一键导出\n");
            String text = "探路完成 · " + pages.length() + " 个页面 · "
                    + found.size() + " 个入口";
            return TaskRunner.Result.ok(text, detail.toString(), text);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("探路失败：" + e, detail.toString(),
                    "把这次日志发出来即可。");
        } finally {
            ProbeSession.stop();
        }
    }

    /**
     * 单活动采集：进一个活动页，把「适配这个活动要用的东西」带回来。
     *
     * <p>跟全局探路的区别是目的：全局探路回答「福利专区里有哪些入口」，
     * 这里回答「这个入口点进去之后长什么样、发了哪些接口」。所以只开这一页、
     * 不追子页，报告里带控件 class、本地存储、完整请求链。
     *
     * <p>不点任何按钮 —— 点了可能真的领奖、抽奖、下单。
     *
     * @param frag 活动号或路径标识，用来归档报告
     * @param name 活动名，只用于展示
     * @param url  这一期实际下发的地址
     */
    static TaskRunner.Result runOne(String frag, String name, String url) {
        StringBuilder detail = new StringBuilder();
        long begin = System.currentTimeMillis();
        ProbeSession.start();
        try {
            JSONObject report = new JSONObject();
            report.put("v", 1);
            report.put("kind", "act");
            report.put("at", begin);

            JSONObject act = new JSONObject();
            act.put("frag", frag);
            act.put("name", name);
            act.put("url", url);
            // 顺带把「现在归哪个引擎管、什么状态」写进去：写新引擎时不用再对一遍表。
            WelfareCatalog.Item built = WelfareCatalog.byFrag(frag);
            act.put("state", built == null
                    ? WelfareCatalog.stateName(WelfareCatalog.STATE_NEW)
                    : WelfareCatalog.stateName(built.state));
            act.put("cmd", built == null || built.cmd == null ? "" : built.cmd);
            report.put("act", act);

            // 裸地址进去，页面会自己跳 SSO 登录页；离屏 WebView 跑不完那套自动登录。
            // 跟跑任务一样先换带 token 的票，换不到再拿裸地址开（结果会落登录页）。
            StringBuilder login = new StringBuilder();
            String openUrl = url;
            String withToken = SsoLogin.ensure(url, login);
            if (withToken != null && withToken.contains("token=")) {
                openUrl = withToken;
            }
            detail.append(login);
            WebPage page = WebPage.open(openUrl, 30_000L, HOSTS, ProbeScript.INSTALL, true);
            if (page == null) {
                return TaskRunner.Result.fail("采集失败：活动页打不开", detail.toString(),
                        "确认中国移动能正常打开这个活动再试。");
            }
            JSONObject pageInfo = new JSONObject();
            try {
                // 装过了就直接返回 ready，这里只是兜底：有些页面会整页重载，钩子跟着丢了。
                page.eval(ProbeScript.INSTALL, 5_000L);
                long ready = System.currentTimeMillis() - begin;
                // 判定「渲染完成」只是标题/正文/Vue 三选一，SPA 可能刚把 title 设上、
                // DOM 还没挂完，首屏 XHR 也还在路上。静置一下，等页面把该挂的挂完、
                // 该发的发完再动手，否则控件是 0、请求只有第一条。
                WebPage.sleep(1_500L);

                JSONObject first = parse(page.eval(ProbeScript.COLLECT_ACT, 8_000L));
                if (first == null) {
                    return TaskRunner.Result.fail("采集失败：页面没读到内容", detail.toString(),
                            "页面可能是空白页或加载超时，重试一次。");
                }

                String title = first.optString("title", "");
                String finalUrl = first.optString("url", url);
                pageInfo.put("title", title);
                pageInfo.put("finalUrl", finalUrl);
                pageInfo.put("readyMs", ready);
                pageInfo.put("globals", first.optJSONArray("globals"));
                pageInfo.put("vue", first.optJSONArray("vue"));
                pageInfo.put("storageKeys", first.optInt("storageKeys", 0));

                Set<String> controlKeys = new LinkedHashSet<>();
                JSONArray controls = new JSONArray();
                Set<String> linkKeys = new LinkedHashSet<>();
                JSONArray links = new JSONArray();
                Set<String> perfKeys = new LinkedHashSet<>();
                JSONArray perf = new JSONArray();
                JSONObject storage = first.optJSONObject("storage");
                String text = first.optString("text", "");
                int screens = 1;

                collectInto(first, controls, controlKeys, links, linkKeys, perf, perfKeys);

                int height = intOf(page.eval(ProbeScript.HEIGHT, 3_000L), 0);
                int steps = height > 0 ? 3 : 0;
                for (int i = 1; i <= steps; i++) {
                    page.eval(ProbeScript.scroll(height * i / (steps + 1)), 2_500L);
                    WebPage.sleep(700L);
                    JSONObject shot = parse(page.eval(ProbeScript.COLLECT_ACT, 8_000L));
                    if (shot == null) {
                        continue;
                    }
                    screens++;
                    collectInto(shot, controls, controlKeys, links, linkKeys, perf, perfKeys);
                    String more = shot.optString("text", "");
                    if (more.length() > text.length()) {
                        // 滚动后正文是整页重读的，取最长那份即可。
                        text = more;
                    }
                    JSONObject more2 = shot.optJSONObject("storage");
                    if (more2 != null && (storage == null || more2.length() > storage.length())) {
                        storage = more2;
                    }
                    finalUrl = shot.optString("url", finalUrl);
                }

                // 收走页面自己发的请求。最后一屏滚动会触发新的懒加载 XHR，
                // 等它们落定再收，不然首屏之后的请求全丢。
                WebPage.sleep(1_200L);
                ProbeSession.recordPage(page.eval(ProbeScript.DRAIN, 6_000L));
                JSONArray requests = new JSONArray(ProbeSession.dump(140));

                pageInfo.put("screens", screens);
                report.put("page", pageInfo);
                report.put("controls", controls);
                report.put("links", links);
                report.put("perf", perf);
                report.put("storage", storage == null ? new JSONObject() : storage);
                report.put("text", text);
                report.put("siblings", siblingJson());

                StringBuilder body = new StringBuilder();
                body.append("采集范围: ").append(title.isEmpty() ? name : title).append('\n');
                body.append("最终地址: ").append(finalUrl).append('\n');
                if (finalUrl.contains("qwhdsso/login") || title.contains("登录")) {
                    body.append("注意: 落在了登录页，这次数据不可用。换票也没成，多半是 App 登录态过期，先在中国移动里打开一次福利专区再重采。\n");
                }
                body.append("控件: ").append(controls.length()).append(" 个 · 请求: ")
                        .append(requests.length()).append(" 条 · 滚动 ").append(screens).append(" 屏\n");

                JSONObject summary = new JSONObject();
                summary.put("controls", controls.length());
                summary.put("reqs", requests.length());
                summary.put("cost", System.currentTimeMillis() - begin);
                report.put("summary", summary);

                // 请求体/响应体都带原文，几十条就能到几百 KB；超过广播能承受的再砍。
                String json = attach(report, requests, detail);
                if (json == null) {
                    return TaskRunner.Result.fail("采集失败：报告过大，发不回来", detail.toString(),
                            "这个活动接口太多，重试一次通常就好。");
                }
                sendAct(frag, json);
                body.append("报告: ").append(json.length() / 1024).append(" KB，已存到这一行，可导出\n");
                String text2 = "采集完成 · " + controls.length() + " 控件 · "
                        + requests.length() + " 请求";
                return TaskRunner.Result.ok(text2, body.toString(), text2);
            } finally {
                page.close();
            }
        } catch (Throwable e) {
            return TaskRunner.Result.fail("采集失败：" + e, detail.toString(),
                    "把这次日志发出来即可。");
        } finally {
            ProbeSession.stop();
        }
    }

    /** 把一屏的采集结果并进总表。 */
    static void collectInto(JSONObject shot, JSONArray controls, Set<String> controlKeys,
                                    JSONArray links, Set<String> linkKeys,
                                    JSONArray perf, Set<String> perfKeys) {
        try {
            JSONArray one = shot.optJSONArray("controls");
            if (one != null) {
                for (int i = 0; i < one.length(); i++) {
                    JSONObject c = one.optJSONObject(i);
                    if (c == null) {
                        continue;
                    }
                    if (controlKeys.add(c.optString("t") + "|" + c.optString("h"))) {
                        controls.put(c);
                    }
                }
            }
            JSONArray two = shot.optJSONArray("links");
            if (two != null) {
                for (int i = 0; i < two.length(); i++) {
                    JSONObject c = two.optJSONObject(i);
                    if (c != null && linkKeys.add(c.optString("h"))) {
                        links.put(c);
                    }
                }
            }
            JSONArray three = shot.optJSONArray("perf");
            if (three != null) {
                for (int i = 0; i < three.length(); i++) {
                    String u = three.optString(i, "");
                    if (!u.isEmpty() && perfKeys.add(u)) {
                        perf.put(u);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 报告里附上请求链；太大就逐级砍条数，宁可少两条也别整份发不回来。 */
    static String attach(JSONObject report, JSONArray requests, StringBuilder detail) {
        int[] limits = {0, 60, 30, 15};
        for (int limit : limits) {
            try {
                JSONArray use = requests;
                if (limit > 0 && requests.length() > limit) {
                    use = new JSONArray();
                    for (int i = 0; i < limit; i++) {
                        use.put(requests.opt(i));
                    }
                }
                report.put("requests", use);
                String json = report.toString();
                if (json.length() <= 380_000) {
                    return json;
                }
                detail.append("报告 ").append(json.length() / 1024).append(" KB，超过上限，减请求条数重试\n");
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 其余活动的名和地址，写引擎时当同类参考。 */
    static JSONArray siblingJson() {
        JSONArray array = new JSONArray();
        try {
            for (WelfareCatalog.Item one : WelfareCatalog.built()) {
                JSONObject item = new JSONObject();
                item.put("n", one.name);
                item.put("u", one.url);
                item.put("s", WelfareCatalog.stateName(one.state));
                array.put(item);
            }
        } catch (Throwable ignored) {
        }
        return array;
    }

    static void sendAct(String frag, String json) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(LogReceiver.ACTION_PROBE_ACT);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra("frag", frag);
            intent.putExtra("json", json);
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            HookLog.logcat("probe act broadcast failed: " + e);
        }
    }

    /** 一个页面的采集结果。 */
    private static final class Page {        final JSONObject json = new JSONObject();
        final List<String[]> candidates = new ArrayList<>();
        String title = "";
        String url = "";
        int controls;
        int reqs;
    }

    /** 打开一页 → 注入探针 → 分屏滚动采集 → 收走请求。 */
    private static Page visit(String url, String label, StringBuilder detail) {
        // earlyScript：页面一开就装钩子，首屏那批接口才抓得到。
        WebPage page = WebPage.open(url, 26_000L, HOSTS, ProbeScript.INSTALL, true);
        if (page == null) {
            return null;
        }
        Page out = new Page();
        try {
            long begin = System.currentTimeMillis();
            // 页面加载完才注入，首屏那批请求抓不到 body，
            // 但 performance 里还有 URL，原生侧也有兜底，够定位了。
            page.eval(ProbeScript.INSTALL, 5_000L);

            JSONObject first = parse(page.eval(ProbeScript.COLLECT, 6_000L));
            String title = first == null ? "" : first.optString("title", "");
            out.title = title.isEmpty() ? label : title;
            out.url = page.url();
            out.json.put("label", label);
            out.json.put("url", out.url);
            out.json.put("title", out.title);
            out.json.put("readyMs", System.currentTimeMillis() - begin);

            JSONArray screens = new JSONArray();
            Set<String> controlKeys = new LinkedHashSet<>();
            JSONArray allControls = new JSONArray();
            StringBuilder text = new StringBuilder();
            String lastUrl = page.url();

            int height = intOf(page.eval(ProbeScript.HEIGHT, 3_000L), 0);
            int steps = height > 0 ? SCROLL_STEPS : 1;
            for (int i = 0; i < steps; i++) {
                if (i > 0) {
                    page.eval(ProbeScript.scroll(height * i / steps), 2_500L);
                    WebPage.sleep(900L);
                }
                JSONObject shot = i == 0 ? first : parse(page.eval(ProbeScript.COLLECT, 6_000L));
                if (shot == null) {
                    continue;
                }
                // 只留轻量摘要：整份 shot 里有 text 和 controls，四屏乘八页
                // 会把广播撑到 Binder 上限，反而发不回来。
                JSONObject brief = new JSONObject();
                brief.put("u", shot.optString("url", ""));
                JSONArray shotControls = shot.optJSONArray("controls");
                brief.put("n", shotControls == null ? 0 : shotControls.length());
                screens.put(brief);
                if (i == 0) {
                    text.append(shot.optString("text", ""));
                } else {
                    String more = shot.optString("text", "");
                    // 滚动后正文是叠加的，只补后面新出现的部分
                    if (!text.toString().contains(more) && more.length() > 0) {
                        text.append(' ').append(more);
                    }
                }
                JSONArray controls = shot.optJSONArray("controls");
                if (controls != null) {
                    for (int c = 0; c < controls.length(); c++) {
                        JSONObject one = controls.optJSONObject(c);
                        if (one == null) {
                            continue;
                        }
                        String key = one.optString("t") + "|" + one.optString("h");
                        if (controlKeys.add(key)) {
                            allControls.put(one);
                        }
                        String href = one.optString("h", "");
                        if (href.startsWith("http")) {
                            addCandidate(out.candidates, one.optString("t", ""), href);
                        }
                    }
                }
                JSONArray links = shot.optJSONArray("links");
                if (links != null) {
                    for (int c = 0; c < links.length(); c++) {
                        JSONObject one = links.optJSONObject(c);
                        if (one != null && one.optString("h", "").startsWith("http")) {
                            addCandidate(out.candidates, one.optString("t", ""), one.optString("h", ""));
                        }
                    }
                }
                JSONArray perf = shot.optJSONArray("perf");
                if (perf != null) {
                    for (int c = 0; c < perf.length(); c++) {
                        addCandidate(out.candidates, "", perf.optString(c, ""));
                    }
                }
                lastUrl = shot.optString("url", lastUrl);
            }

            // 收走页面自己发的请求
            ProbeSession.recordPage(page.eval(ProbeScript.DRAIN, 5_000L));
            out.reqs = ProbeSession.size();
            out.controls = allControls.length();
            out.json.put("controls", allControls);
            out.json.put("text", cut(text.toString().replaceAll("\\s+", " ").trim(), 2200));
            out.json.put("finalUrl", lastUrl);
            out.json.put("screens", screens.length());
            if (first != null) {
                out.json.put("globals", first.optJSONArray("globals"));
                out.json.put("vue", first.optJSONArray("vue"));
            }
            // 从原生接口响应里捞 URL：原生渲染页的入口只在这里
            harvestFromSession(out.candidates);
        } catch (Throwable e) {
            HookLog.logcat("probe page failed: " + url + " · " + e);
        } finally {
            page.close();
        }
        return out;
    }

    /** 把这一页相关的请求、响应摘要带上；响应里的 URL 也当入口候选。 */
    private static void harvestFromSession(List<String[]> candidates) {
        try {
            JSONArray all = new JSONArray(ProbeSession.dump(160));
            for (int i = 0; i < all.length(); i++) {
                JSONObject one = all.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String url = one.optString("u", "");
                addCandidate(candidates, "", url);
                String resp = one.optString("resp", "");
                if (resp.length() > 24) {
                    Matcher m = URL_PATTERN.matcher(resp);
                    int guard = 0;
                    while (m.find() && guard++ < 30) {
                        addCandidate(candidates, "", m.group());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只留活动入口：命中 ACT_HINTS，且不命中 SKIP_HINTS。 */
    private static void addCandidate(List<String[]> list, String name, String url) {
        if (url == null || url.length() < 20 || list.size() >= MAX_CANDIDATES) {
            return;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String skip : SKIP_HINTS) {
            if (lower.contains(skip)) {
                return;
            }
        }
        boolean hit = false;
        for (String hint : ACT_HINTS) {
            if (lower.contains(hint.toLowerCase(Locale.US))) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return;
        }
        String clean = url;
        int cut = clean.indexOf('#');
        if (cut > 0) {
            clean = clean.substring(0, cut);
        }
        for (String[] one : list) {
            if (one[1].equals(clean)) {
                if (one[0].isEmpty() && name != null && !name.isEmpty()) {
                    one[0] = name.trim();
                }
                return;
            }
        }
        list.add(new String[]{
                name == null ? "" : name.trim().replaceAll("\\s+", " "),
                clean,
        });
    }

    private static JSONArray candidatesToJson(List<String[]> list) {
        JSONArray array = new JSONArray();
        for (String[] one : list) {
            JSONObject item = new JSONObject();
            try {
                item.put("n", one[0]);
                item.put("u", one[1]);
            } catch (Throwable ignored) {
            }
            array.put(item);
        }
        return array;
    }

    private static void send(String json) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(LogReceiver.ACTION_PROBE);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra("json", json);
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            HookLog.logcat("probe broadcast failed: " + e);
        }
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int intOf(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
