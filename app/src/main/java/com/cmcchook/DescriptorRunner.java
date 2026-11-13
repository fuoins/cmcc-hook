package com.cmcchook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * r20：描述驱动通用执行器。
 *
 * <p>吃一条 {@link ActivityDescriptor} 校验过的 JSON，按 steps 顺序执行：
 * <ul>
 *   <li>{@code http}：页面上下文同源请求（{@link TaskNet}），响应 JSON 存进变量，
 *       {@code save} 用 JSON path 把字段抽出来给后续步骤用；</li>
 *   <li>{@code dom}：<b>真页</b>内结构定位点击（{@code IMG.content-btn} 这类选择器，
 *       不靠 innerText），并按 stayMs 停留（校验已保证 ≥5 秒）—— 沿用 r16 豆苗的约定；</li>
 *   <li>{@code sleep / log / set}：节奏、日志、变量。</li>
 * </ul>
 *
 * <p>{@code okWhen} / {@code idleWhen} 是<b>成功判据</b>，只认服务端返回的状态量，
 * 不认「请求发出去了」「点了按钮」—— 延续 r18 修 7 日打卡 / 充值假 ✓ 的口径。
 * 总预算 budgetMs 到点就收手，四态映射 ok / idle / fail / skip。
 *
 * <p><b>零回归边界</b>：本执行器只在 {@link TaskRunner} 分派到
 * {@code desc:<id>} 或描述文件里显式 {@code enabled=true} 的新命令时才被调用。
 * 模块原有的 20 个活动命令一个都不经过这里，descriptors.json 不存在时完全不生效。
 */
final class DescriptorRunner {

    private DescriptorRunner() {
    }

    /** 变量表：步骤 id → 响应 JSON；set/save 抽出的标量也放这里。 */
    private static final class Vars {
        final Map<String, String> flat = new HashMap<>();
        final Map<String, JSONObject> objs = new HashMap<>();

        void putObj(String id, JSONObject o) {
            objs.put(id, o);
            if (o != null) {
                flatten("", o, 6);
            }
        }

        private void flatten(String prefix, JSONObject o, int depth) {
            if (o == null || depth <= 0) {
                return;
            }
            java.util.Iterator<String> it = o.keys();
            int n = 0;
            while (it.hasNext() && n++ < 60) {
                String k = it.next();
                String key = prefix.isEmpty() ? k : prefix + "." + k;
                Object v = o.opt(k);
                if (v instanceof JSONObject) {
                    flatten(key, (JSONObject) v, depth - 1);
                } else if (!(v instanceof JSONArray)) {
                    flat.put(key, String.valueOf(v));
                }
            }
        }

        /** 取 JSON path：{@code $.data.isFinish} / {@code s1.data[0].taskId} / 扁平 key。 */
        String path(String expr) {
            if (expr == null || expr.isEmpty()) {
                return "";
            }
            String e = expr;
            String root = "";
            if (e.startsWith("$.")) {
                e = e.substring(2);
            } else {
                int dot = e.indexOf('.');
                if (dot > 0) {
                    root = e.substring(0, dot);
                    e = e.substring(dot + 1);
                } else {
                    return flat.containsKey(e) ? flat.get(e) : "";
                }
            }
            JSONObject base = root.isEmpty() ? null : objs.get(root);
            if (!root.isEmpty() && base == null) {
                return flat.containsKey(expr) ? flat.get(expr) : "";
            }
            Object cur = base == null ? null : base;
            String[] segs = e.split("\\.");
            for (String seg : segs) {
                if (cur == null) {
                    break;
                }
                int bracket = seg.indexOf('[');
                String name = bracket > 0 ? seg.substring(0, bracket) : seg;
                if (!name.isEmpty()) {
                    if (cur instanceof JSONObject) {
                        cur = ((JSONObject) cur).opt(name);
                    } else {
                        cur = null;
                    }
                }
                if (bracket > 0) {
                    int close = seg.indexOf(']', bracket);
                    int idx = parseInt(seg.substring(bracket + 1,
                            close > 0 ? close : seg.length()));
                    if (cur instanceof JSONArray && idx >= 0) {
                        cur = ((JSONArray) cur).opt(idx);
                    } else {
                        cur = null;
                    }
                }
            }
            if (cur == null) {
                // 兜底：扁平表里按完整路径找
                String key = expr.startsWith("$.") ? expr.substring(2) : expr;
                return flat.containsKey(key) ? flat.get(key) : "";
            }
            return cur instanceof JSONObject || cur instanceof JSONArray
                    ? cur.toString() : String.valueOf(cur);
        }

        private static int parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Throwable e) {
                return -1;
            }
        }

        /**
         * 把 {@code {path}} 占位符换成变量值，其余原样保留。
         *
         * <p>只匹配<b>最内层</b>花括号对：http 步骤的 body 常是 JSON
         * （如 {@code {"taskId":"{tid}"}}），若用「找第一个 { 再找第一个 }」的
         * 手写扫描，会把 JSON 外层花括号误当占位符起点，把整段 body 截断。
         * 正则 {@code \{([^{}]+)\}} 天然跳过外层对象括号，只吃 {@code {tid}}。
         */
        private static final java.util.regex.Pattern PH =
                java.util.regex.Pattern.compile("\\{([^{}]+)\\}");

        String render(String text) {
            if (text == null || text.indexOf('{') < 0) {
                return text == null ? "" : text;
            }
            java.util.regex.Matcher m = PH.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String key = m.group(1).trim();
                String v = flat.containsKey(key) ? flat.get(key) : path(key);
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(v == null ? "" : v));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ 入口

    static TaskRunner.Result run(Context ctx, JSONObject d, StringBuilder detail) {
        String id = d.optString("id", "unnamed");
        String name = d.optString("name", id);
        String check = ActivityDescriptor.validate(d);
        if (!check.startsWith("PASS")) {
            return TaskRunner.Result.fail(name + "：描述非法 —— " + check, "", "修好描述再下发。");
        }
        long budget = d.optLong("budgetMs", 60_000L);
        long deadline = System.currentTimeMillis() + budget;
        Vars vars = new Vars();
        vars.flat.put("today", new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        vars.flat.put("referer", d.optString("entry", ""));

        try {
            // 幂等：day = 当天只跑一次；once = 装上去只跑一次；none = 每轮都跑。
            String idem = d.optString("idempotent", "day");
            String key = stateKey(d, idem);
            if (!key.isEmpty() && ctx != null) {
                // 显式读状态位，不用 getBool（它默认 true，会把「没记过」误判成「记过」）。
                String state = AppPrefs.getTaskState(ctx, key, dayOf(d, idem));
                if (state.startsWith("OK")) {
                    return TaskRunner.Result.idle(name + "：今天已经做过了",
                            detail.toString() + "\n幂等命中，跳过。",
                            "按描述 idempotent=" + idem + " 跳过，明天会自动再跑。");
                }
            }

            JSONArray steps = d.optJSONArray("steps");
            WebPage page = null;
            try {
                for (int i = 0; i < steps.length(); i++) {
                    if (System.currentTimeMillis() > deadline) {
                        return finish(ctx, d, idem,
                                TaskRunner.Result.idle(name + "：预算用完，收手",
                                        detail.toString(), "把 budgetMs 调大或精简 steps。"),
                                detail);
                    }
                    JSONObject s = steps.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    String kind = s.optString("kind", "");
                    if ("http".equals(kind)) {
                        doHttp(s, vars, detail, deadline);
                    } else if ("dom".equals(kind)) {
                        if (page == null) {
                            String entry = vars.render(d.optString("entry", ""));
                            if (entry.isEmpty()) {
                                detail.append("dom: 描述缺 entry，跳过点击\n");
                                continue;
                            }
                            page = RealPage.open(entry, RealPage.pathKey(entry),
                                    Math.min(20_000L, left(deadline)));
                            if (page == null || !page.ok()) {
                                detail.append("dom: 真页没起来，跳过点击\n");
                                page = null;
                                continue;
                            }
                        }
                        doDom(page, s, vars, detail);
                    } else if ("sleep".equals(kind)) {
                        long ms = Math.min(s.optLong("ms", 0L), left(deadline));
                        if (ms > 0) {
                            Thread.sleep(ms);
                        }
                    } else if ("log".equals(kind)) {
                        detail.append(vars.render(s.optString("text", ""))).append('\n');
                    } else if ("set".equals(kind)) {
                        vars.flat.put(s.optString("name", ""),
                                vars.render(s.optString("value", "")));
                    }
                }
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Throwable ignored) {
                    }
                }
                RealPage.closeOwned();
            }

            // 判据只认服务端状态量。
            String ok = matches(d.optJSONObject("okWhen"), vars);
            String idle = matches(d.optJSONObject("idleWhen"), vars);
            JSONObject r = d.optJSONObject("result");
            if (idle != null) {
                return finish(ctx, d, idem,
                        TaskRunner.Result.idle(text(r, "idle", name + "：无需操作 · " + idle),
                                detail.toString(), ""), detail);
            }
            if (ok == null) {
                if (d.optJSONObject("okWhen") != null
                        && d.optJSONObject("okWhen").has("hint")) {
                    // 草稿只有 hint：不能判成功，如实报「待人工确认」。
                    return TaskRunner.Result.skip(name + "：草稿缺成功判据，未判成功",
                            detail.toString(), "把 okWhen 填成 path+eq 再下发，避免假 ✓。");
                }
                return finish(ctx, d, idem,
                        TaskRunner.Result.fail(name + "：成功判据未命中",
                                detail.toString(), "看 detail 里的响应，按真实 code 调 okWhen。"),
                        detail);
            }
            return finish(ctx, d, idem,
                    TaskRunner.Result.ok(text(r, "ok", name + "：完成 · " + ok),
                            detail.toString(), text(r, "ok", name + " 完成")), detail);
        } catch (Throwable e) {
            return TaskRunner.Result.fail(name + "：执行异常 " + e, detail.toString(),
                    "把日志发我。");
        }
    }

    private static TaskRunner.Result finish(Context ctx, JSONObject d, String idem,
                                            TaskRunner.Result r, StringBuilder detail) {
        if (ctx != null && r.ok && !"none".equals(idem)) {
            String key = stateKey(d, idem);
            if (!key.isEmpty()) {
                try {
                    AppPrefs.setTaskState(ctx, key, dayOf(d, idem),
                            true, false, r.summary, 0);
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    private static String stateKey(JSONObject d, String idem) {
        // 借 AppPrefs 的当日状态表，但用独立前缀，绝不和 20 个活动命令的 key 撞车。
        return "none".equals(idem) ? "" : "desc_" + d.optString("id", "unnamed");
    }

    private static String dayOf(JSONObject d, String idem) {
        return "once".equals(idem) ? "once"
                : new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static long left(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    private static String text(JSONObject r, String key, String fallback) {
        return r == null ? fallback : r.optString(key, fallback);
    }

    // ------------------------------------------------------------ 步骤

    private static void doHttp(JSONObject s, Vars vars, StringBuilder detail, long deadline) {
        String id = s.optString("id", "");
        String method = s.optString("method", "GET").toUpperCase(Locale.US);
        String path = vars.render(s.optString("path", ""));
        String body = vars.render(s.optString("body", ""));
        String ref = vars.render(s.optString("ref", ""));
        if (ref.isEmpty()) {
            ref = vars.flat.get("referer");
        }
        if (id.isEmpty()) {
            detail.append("http: 缺 id，跳过 ").append(path).append('\n');
            return;
        }
        String url = path.startsWith("http") ? path : TaskNet.BASE + path;
        try {
            TaskNet.Resp resp = "POST".equals(method)
                    ? TaskNet.post(url, body, ref) : TaskNet.get(url, ref);
            JSONObject saved = new JSONObject();
            saved.put("__status", resp.status);
            saved.put("__text", resp.body);
            try {
                JSONObject parsed = new JSONObject(resp.body);
                // 把 __status/__text 也塞进去，okWhen 可以判 __status。
                parsed.put("__status", resp.status);
                parsed.put("__text", resp.body);
                vars.putObj(id, parsed);
            } catch (Throwable notJson) {
                vars.putObj(id, saved);
            }
            String brief = resp.body.replace('\n', ' ').trim();
            if (brief.length() > 160) {
                brief = brief.substring(0, 160) + "…";
            }
            detail.append("http ").append(id).append(' ').append(method).append(' ')
                    .append(path).append(" → ").append(resp.status).append(' ')
                    .append(brief).append('\n');
            // save：把响应里的字段抽成变量，供后续步骤 {var} 引用。
            JSONObject save = s.optJSONObject("save");
            if (save != null) {
                java.util.Iterator<String> it = save.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = vars.path(save.optString(k, ""));
                    vars.flat.put(k, v);
                }
            }
        } catch (Throwable e) {
            JSONObject bad = new JSONObject();
            try {
                bad.put("__status", -1);
                bad.put("__text", String.valueOf(e));
            } catch (JSONException ignored) {
            }
            vars.putObj(id, bad);
            detail.append("http ").append(id).append(" 异常 ").append(e).append('\n');
        }
    }

    /**
     * 真页内结构定位点击：selector 交给页面自己 querySelector，点 IMG.content-btn
     * 这类图片按钮，不看 innerText（r16 豆苗三个任务就是栽在 innerText 上）。
     */
    private static void doDom(WebPage page, JSONObject s, Vars vars, StringBuilder detail) {
        String selector = vars.render(s.optString("selector", ""));
        String action = s.optString("action", "click");
        int index = s.optInt("index", 0);
        long stay = s.optLong("stayMs", 5000L);
        String js = "(function(){"
                + "var n=document.querySelectorAll(" + quote(selector) + ");"
                + "var i=" + index + ";"
                + "if(!n||!n[i]) return 'NO_TARGET:'+n.length;"
                + "var e=n[i];"
                + "var t=e.closest('a,button,[onclick],[role=button]')||e;"
                + "t.click();"
                + "return 'CLICKED:'+(e.tagName||'')+'.'+(e.className||'');"
                + "})()";
        try {
            String out = WebPage.unquote(page.eval(js, Math.max(8000L, stay + 3000L)));
            detail.append("dom ").append(selector).append(" [").append(index)
                    .append("] → ").append(out).append('\n');
            if (!out.startsWith("CLICKED")) {
                return;   // 没点到就别白等 5 秒
            }
            // 浏览任务：真页停留 5 秒再回来，跟 r16/r18 的口径一致。
            if (stay > 0) {
                Thread.sleep(stay);
            }
        } catch (Throwable e) {
            detail.append("dom ").append(selector).append(" 异常 ").append(e).append('\n');
        }
    }

    private static String quote(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ------------------------------------------------------------ 判据

    /** 命中返回一句描述，没命中返回 null。支持 eq / ne / contains / exists / true / status。 */
    private static String matches(JSONObject when, Vars vars) {
        if (when == null) {
            return null;
        }
        String path = when.optString("path", "");
        String got = path.isEmpty() ? "" : vars.path(path);
        String code = when.optString("code", "");
        if (!code.isEmpty()) {
            String all = String.valueOf(vars.objs);
            return all.contains("\"code\":\"" + code + "\"")
                    || all.contains("\"resultCode\":\"" + code + "\"")
                    ? "code=" + code : null;
        }
        if (when.has("eq")) {
            String want = String.valueOf(when.opt("eq"));
            return want.equals(got) ? path + "=" + got : null;
        }
        if (when.has("ne")) {
            String want = String.valueOf(when.opt("ne"));
            return !want.equals(got) ? path + "≠" + got : null;
        }
        if (when.has("contains")) {
            String want = String.valueOf(when.opt("contains"));
            return got.contains(want) ? path + "含" + want : null;
        }
        if (when.optBoolean("exists", false)) {
            return got.isEmpty() ? null : path + "存在";
        }
        if (when.optBoolean("true", false)) {
            return "true".equals(got) || "1".equals(got) ? path + "为真" : null;
        }
        if (when.has("status")) {
            int want = when.optInt("status", 200);
            String st = vars.path("$.__status");
            return String.valueOf(want).equals(st) ? "HTTP " + want : null;
        }
        return null;
    }

    // ------------------------------------------------------------ 离线自验（纯函数）

    static String selfCheck() {
        try {
            return selfCheckImpl();
        } catch (Throwable e) {
            return "FAIL DescriptorRunner 自验抛异常: " + e;
        }
    }

    private static String selfCheckImpl() throws JSONException {
        int total = 0;
        java.util.List<String> fails = new java.util.ArrayList<>();
        Vars v = new Vars();
        JSONObject o = new JSONObject();
        o.put("code", "SUCCESS");
        o.put("__status", 200);
        JSONObject data = new JSONObject();
        data.put("isFinish", 1);
        data.put("remain", 0);
        o.put("data", data);
        JSONArray arr = new JSONArray();
        JSONObject one = new JSONObject();
        one.put("taskId", "T-7788");
        arr.put(one);
        o.put("list", arr);
        v.putObj("s1", o);

        total++;
        if (!"1".equals(v.path("s1.data.isFinish"))) {
            fails.add("path 取嵌套字段失败");
        }
        total++;
        if (!"T-7788".equals(v.path("s1.list[0].taskId"))) {
            fails.add("path 取数组下标失败: " + v.path("s1.list[0].taskId"));
        }
        total++;
        if (!"SUCCESS".equals(v.path("$.code"))) {
            fails.add("$. 根路径失败");
        }
        total++;
        v.flat.put("tid", "T-7788");
        if (!"{\"taskId\":\"T-7788\"}".equals(v.render("{\"taskId\":\"{tid}\"}"))) {
            fails.add("模板渲染失败: " + v.render("{\"taskId\":\"{tid}\"}"));
        }
        total++;
        if (matches(new JSONObject().put("path", "s1.data.isFinish").put("eq", 1), v) == null) {
            fails.add("okWhen eq 命中失败");
        }
        total++;
        if (matches(new JSONObject().put("path", "s1.data.isFinish").put("eq", 0), v) != null) {
            fails.add("okWhen eq 不该命中却命中了");
        }
        total++;
        if (matches(new JSONObject().put("path", "s1.nope").put("exists", true), v) != null) {
            fails.add("exists 对空值不该命中");
        }
        total++;
        if (matches(new JSONObject().put("code", "SUCCESS"), v) == null) {
            fails.add("code 判据失败");
        }
        total++;
        if (matches(new JSONObject().put("status", 200), v) == null) {
            fails.add("status 判据失败: " + v.path("$.__status"));
        }
        total++;
        if ("none".equals(ActivityDescriptor.goodObj("x", "y")
                .optString("idempotent", "day"))) {
            fails.add("幂等默认值异常");
        }

        if (fails.isEmpty()) {
            return "PASS " + total + "/" + total + " · DescriptorRunner path/模板/okWhen 判据";
        }
        return "FAIL " + fails.size() + "/" + total + "\n  - " + String.join("\n  - ", fails);
    }
}
