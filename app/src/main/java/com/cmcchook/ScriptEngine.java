package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 活动任务脚本引擎。
 *
 * <p>每个活动一份 JSON「脚本」：请求序列 + 判定规则。引擎按步骤执行，
 * 请求走页面上下文 fetch（{@link TaskNet}），响应存进变量，模板 {var} 引用。
 * 接口变了只改脚本，不改 Java —— 采集报告里的 XHR 时间线就是脚本的原材料。
 *
 * <p>脚本步骤：
 * <pre>
 * {"req": {"id": "s1", "method": "POST", "path": "/qwhdhub/...", "body": "{}", "egg": false}}
 *     发请求。响应 JSON 根对象存为 vars[s1]（非 JSON 包成 {"__status":n,"__text":".."}）。
 * {"if": {"cond": {"eq": ["{s1.code}", "SUCCESS"]}, "then": [..], "else": [..]}}
 *     条件：eq / ne / contains / true / exists
 * {"for": {"list": "{s1.data.tasks}", "as": "t", "do": [..]}}
 * {"sleep": 1500}
 * {"log": "文本 {var}"}                 —— 追加到结果详情
 * {"set": {"name": "{s1.data.x}"}}      —— 存变量
 * {"call": {"fn": "claimTask", "args": ["{t.taskId}"], "out": "r"}}
 *     回调 Java 辅助函数（见 callbacks），返回字符串存 vars[out]
 * {"browse": {"url": "{t.jumpUrl}", "stay": 10000}}
 *     离屏打开落地页停留 stay 毫秒（浏览类任务的真实停留）
 * {"result": {"kind": "ok|fail|idle", "text": "...", "detail": "..."}}
 *     结束脚本；省略则默认 ok
 * </pre>
 */
final class ScriptEngine {

    private ScriptEngine() {
    }

    // ------------------------------------------------------------ 入口

    static TaskRunner.Result run(String script, String name, String referer,
                                 StringBuilder detail) {
        JSONObject root;
        try {
            root = new JSONObject(script);
        } catch (Throwable e) {
            return TaskRunner.Result.fail(name + "：脚本解析失败 " + e, "", "把日志发我。");
        }
        Vars vars = new Vars();
        vars.put("today", new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        vars.put("referer", referer == null ? "" : referer);
        JSONArray steps = root.optJSONArray("steps");
        if (steps == null) {
            return TaskRunner.Result.fail(name + "：脚本没有步骤", "", "");
        }
        try {
            String text = runSteps(steps, vars, detail);
            return TaskRunner.Result.ok(text, detail.toString(), text);
        } catch (ScriptResult e) {
            String d = e.detail == null || e.detail.isEmpty() ? detail.toString() : e.detail;
            if ("fail".equals(e.kind)) {
                return TaskRunner.Result.fail(e.text, d, "把日志发我。");
            }
            if ("idle".equals(e.kind)) {
                return TaskRunner.Result.idle(e.text, d, e.text);
            }
            return TaskRunner.Result.ok(e.text, d, e.text);
        } catch (Throwable e) {
            return TaskRunner.Result.fail(name + "：脚本执行异常 " + e,
                    detail.toString(), "把日志发我。");
        }
    }

    /** result 步骤抛出来提前结束脚本。 */
    private static final class ScriptResult extends RuntimeException {
        final String kind;
        final String text;
        final String detail;

        ScriptResult(String kind, String text, String detail) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
        }
    }

    /** 执行一组步骤，返回最终文本。 */
    private static String runSteps(JSONArray steps, Vars vars, StringBuilder detail) {
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) {
                continue;
            }
            if (step.has("req")) {
                doReq(step.optJSONObject("req"), vars, detail);
            } else if (step.has("if")) {
                doIf(step.optJSONObject("if"), vars, detail);
            } else if (step.has("for")) {
                doFor(step.optJSONObject("for"), vars, detail);
            } else if (step.has("sleep")) {
                WebPage.sleep(step.optLong("sleep", 1000));
            } else if (step.has("log")) {
                detail.append(vars.render(step.optString("log", ""))).append('\n');
            } else if (step.has("set")) {
                doSet(step.optJSONObject("set"), vars);
            } else if (step.has("call")) {
                doCall(step.optJSONObject("call"), vars, detail);
            } else if (step.has("browse")) {
                doBrowse(step.optJSONObject("browse"), vars, detail);
            } else if (step.has("result")) {
                JSONObject r = step.optJSONObject("result");
                String kind = r.optString("kind", "ok");
                throw new ScriptResult(kind,
                        vars.render(r.optString("text", "完成")),
                        r.has("detail") ? vars.render(r.optString("detail")) : null);
            }
        }
        return "完成";
    }

    // ------------------------------------------------------------ req

    private static void doReq(JSONObject req, Vars vars, StringBuilder detail) {
        String id = req.optString("id", "");
        String method = req.optString("method", "GET").toUpperCase();
        String path = vars.render(req.optString("path", ""));
        String body = vars.render(req.optString("body", ""));
        boolean egg = req.optBoolean("egg", false);
        long timeout = req.optLong("timeout", 20_000L);
        if (id.isEmpty()) {
            detail.append("req: 缺少 id，跳过 ").append(path).append('\n');
            return;
        }
        String url = path.startsWith("http") ? path : TaskNet.BASE + path;
        String ref = vars.get("referer");
        JSONObject saved = new JSONObject();
        try {
            TaskNet.Resp resp;
            if ("POST".equals(method)) {
                resp = egg ? TaskNet.post(url, body, ref, TaskApi.eggHeaders())
                        : TaskNet.post(url, body, ref);
            } else {
                resp = egg ? TaskNet.get(url, ref, TaskApi.eggHeaders())
                        : TaskNet.get(url, ref);
            }
            if (resp.status < 0) {
                saved.put("__status", -1);
                saved.put("__text", resp.body);
                vars.put(id, saved);
                detail.append("req ").append(id).append(": 网络失败 ").append(resp.body)
                        .append('\n');
                return;
            }
            saved.put("__status", resp.status);
            saved.put("__text", resp.body);
            vars.put(id, saved);
            boolean json = false;
            try {
                JSONObject parsed = new JSONObject(resp.body);
                vars.put(id, parsed);
                // 成功解析也要留住 __status/__text，失败判定能引用。
                parsed.put("__status", resp.status);
                parsed.put("__text", resp.body);
                json = true;
            } catch (Throwable notJson) {
                // 非 JSON：保留 __status/__text 包装
            }
            String brief = resp.body.replace('\n', ' ').trim();
            if (brief.length() > 120) {
                brief = brief.substring(0, 120) + "…";
            }
            // 判定型日志：接口异常/非 JSON 时把状态与响应片段写进 detail，
            // 别让脚本只报一句「失败：」后面空白。
            if (resp.status >= 400 || !json) {
                detail.append("req ").append(id).append(": HTTP ").append(resp.status)
                        .append(json ? " " : " 非JSON ")
                        .append(brief).append('\n');
            }
            HookLog.logcat("script req " + id + " " + method + " " + path
                    + " -> " + resp.status + " " + brief);
        } catch (Throwable e) {
            try {
                saved.put("__status", -2);
                saved.put("__text", String.valueOf(e));
            } catch (Throwable ignored) {
            }
            vars.put(id, saved);
            detail.append("req ").append(id).append(": 异常 ").append(e).append('\n');
        }
    }

    // ------------------------------------------------------------ 控制流

    private static void doIf(JSONObject node, Vars vars, StringBuilder detail) {
        JSONObject cond = node.optJSONObject("cond");
        boolean hit = cond == null || evalCond(cond, vars);
        JSONArray branch = hit ? node.optJSONArray("then") : node.optJSONArray("else");
        if (branch != null) {
            runSteps(branch, vars, detail);
        }
    }

    private static boolean evalCond(JSONObject cond, Vars vars) {
        if (cond.has("eq")) {
            JSONArray pair = cond.optJSONArray("eq");
            return pair != null && pair.length() >= 2
                    && vars.render(pair.optString(0)).equals(vars.render(pair.optString(1)));
        }
        if (cond.has("ne")) {
            JSONArray pair = cond.optJSONArray("ne");
            return pair != null && pair.length() >= 2
                    && !vars.render(pair.optString(0)).equals(vars.render(pair.optString(1)));
        }
        if (cond.has("contains")) {
            JSONArray pair = cond.optJSONArray("contains");
            return pair != null && pair.length() >= 2
                    && vars.render(pair.optString(0)).contains(vars.render(pair.optString(1)));
        }
        if (cond.has("true")) {
            String v = vars.render(cond.optString("true"));
            return "true".equals(v) || "1".equals(v);
        }
        if (cond.has("exists")) {
            return !vars.render(cond.optString("exists")).isEmpty();
        }
        return false;
    }

    /**
     * 解析 for 的列表表达式，返回结构化 JSONArray。
     *
     * <p>旧写法 vars.path(vars.render(listPath)) 是错的：{tasks.data.tasks}
     * 会先被 render 成整段数组 JSON 文本，再把这段文本当「变量路径」按 . 拆去
     * map 里查，必然查不到，所有 for 都判「不是数组」跳过。正确的是直接按
     * 路径取结构化对象；call 回传的字符串数组再解析回来。
     */
    private static Object resolveList(String listPath, Vars vars) {
        String expr = listPath == null ? "" : listPath.trim();
        Object value = null;
        if (expr.startsWith("{") && expr.endsWith("}") && expr.indexOf('{', 1) < 0) {
            // 纯 {path}：直接按变量路径取，保留 JSONObject/JSONArray 结构。
            value = vars.path(expr.substring(1, expr.length() - 1));
        }
        if (value == null) {
            // 含内嵌变量（如 {a.{b}}）：render 成文本后尝试当数组解析。
            String rendered = vars.render(listPath);
            if (rendered != null && rendered.trim().startsWith("[")) {
                try {
                    value = new JSONArray(rendered);
                } catch (Throwable ignored) {
                }
            }
        }
        // call 回传的 JSON 数组是字符串形式，这里转回来。
        if (value instanceof String) {
            try {
                value = new JSONArray((String) value);
            } catch (Throwable notArray) {
                value = null;
            }
        }
        return value;
    }

    private static void doFor(JSONObject node, Vars vars, StringBuilder detail) {
        String listPath = node.optString("list", "");
        String as = node.optString("as", "it");
        JSONArray steps = node.optJSONArray("do");
        if (steps == null) {
            return;
        }
        Object value = resolveList(listPath, vars);
        if (!(value instanceof JSONArray)) {
            detail.append("for: ").append(listPath).append(" 不是数组")
                    .append(value == null ? "" : "（取到 " + value.getClass().getSimpleName() + "）")
                    .append('\n');
            return;
        }
        JSONArray list = (JSONArray) value;
        int max = node.optInt("max", Integer.MAX_VALUE);
        for (int i = 0; i < list.length() && i < max; i++) {
            vars.put(as, list.opt(i));
            vars.put("idx", i);
            runSteps(steps, vars, detail);
        }
        vars.remove(as);
        vars.remove("idx");
    }

    private static void doSet(JSONObject node, Vars vars) {
        JSONObject names = node;
        java.util.Iterator<String> keys = names.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String raw = names.optString(key);
            if (raw.startsWith("@")) {
                // @path 表示存对象引用（不字符串化），后面还能继续取子字段。
                vars.put(key, vars.path(raw.substring(1)));
            } else {
                vars.put(key, vars.render(raw));
            }
        }
    }

    // ------------------------------------------------------------ call / browse

    private static void doCall(JSONObject node, Vars vars, StringBuilder detail) {
        String fn = node.optString("fn", "");
        JSONArray args = node.optJSONArray("args");
        String out = node.optString("out", "");
        String[] rendered = args == null ? new String[0] : new String[args.length()];
        for (int i = 0; i < rendered.length; i++) {
            rendered[i] = vars.render(args.optString(i));
        }
        String result = callbacks.call(fn, rendered);
        if (!out.isEmpty()) {
            vars.put(out, result == null ? "" : result);
        }
        detail.append("call ").append(fn).append(": ")
                .append(result == null ? "空" : result).append('\n');
    }

    private static void doBrowse(JSONObject node, Vars vars, StringBuilder detail) {
        String url = vars.render(node.optString("url", ""));
        long stay = node.optLong("stay", 10_000L);
        if (url.isEmpty() || !url.startsWith("http")) {
            detail.append("browse: 没有落地页地址\n");
            return;
        }
        OffscreenWeb.close();
        Object wv = OffscreenWeb.open(url, 6_000L);
        if (wv == null) {
            detail.append("browse: 离屏 WebView 不可用，跳过 ").append(ProbeSession.shortOf(url))
                    .append('\n');
            return;
        }
        detail.append("browse: 离屏停留 ").append(stay / 1000).append(" 秒 ")
                .append(ProbeSession.shortOf(url)).append('\n');
        WebPage.sleep(stay);
        OffscreenWeb.close();
    }

    // ------------------------------------------------------------ 回调表

    /** 脚本 call 可用的 Java 辅助函数（各活动任务类注册）。 */
    private static final Callbacks callbacks = new Callbacks();

    static final class Callbacks {
        private final Map<String, Func> table = new HashMap<>();

        interface Func {
            String call(String[] args);
        }

        void register(String name, Func fn) {
            table.put(name, fn);
        }

        String call(String name, String[] args) {
            Func fn = table.get(name);
            if (fn == null) {
                return "未知函数 " + name;
            }
            try {
                return fn.call(args);
            } catch (Throwable e) {
                return "异常 " + e;
            }
        }
    }

    static void register(String name, Callbacks.Func fn) {
        callbacks.register(name, fn);
    }

    // ------------------------------------------------------------ 变量

    /** 脚本变量：JSON 对象/数组/标量，模板 {a.b[0].c} 取路径。 */
    static final class Vars {
        private final Map<String, Object> map = new HashMap<>();

        void put(String name, Object value) {
            if (name == null || name.isEmpty()) {
                return;
            }
            map.put(name, value);
        }

        void remove(String name) {
            map.remove(name);
        }

        String get(String name) {
            Object value = path(name);
            if (value == null) {
                return "";
            }
            if (value instanceof JSONObject || value instanceof JSONArray) {
                return value.toString();
            }
            return String.valueOf(value);
        }

        Object path(String expr) {
            if (expr == null || expr.isEmpty()) {
                return null;
            }
            String[] parts = expr.split("\\.");
            Object cur = map.get(parts[0]);
            if (cur == null) {
                return null;
            }
            for (int i = 1; i < parts.length; i++) {
                cur = step(cur, parts[i]);
                if (cur == null) {
                    return null;
                }
            }
            return cur;
        }

        private static Object step(Object cur, String part) {
            try {
                int bracket = part.indexOf('[');
                if (bracket < 0) {
                    return cur instanceof JSONObject
                            ? ((JSONObject) cur).opt(part) : null;
                }
                String field = part.substring(0, bracket);
                if (!field.isEmpty()) {
                    if (!(cur instanceof JSONObject)) {
                        return null;
                    }
                    cur = ((JSONObject) cur).opt(field);
                }
                if (cur == null) {
                    return null;
                }
                while (bracket >= 0 && cur != null) {
                    int close = part.indexOf(']', bracket);
                    if (close <= bracket + 1) {
                        return null;
                    }
                    int idx = Integer.parseInt(part.substring(bracket + 1, close).trim());
                    if (!(cur instanceof JSONArray) || idx < 0
                            || idx >= ((JSONArray) cur).length()) {
                        return null;
                    }
                    cur = ((JSONArray) cur).opt(idx);
                    bracket = part.indexOf('[', close);
                }
                return cur;
            } catch (Throwable e) {
                return null;
            }
        }

        /**
         * 把文本里的 {path} 替换成变量值；JSON 对象字面量的花括号原样保留。
         *
         * <p>请求体常写成 {@code {"date":"{today}"}} —— 外层 {...} 是 JSON
         * 对象，内层 {today} 才是变量。旧实现见 { 就吃到下一个 }，会把
         * {@code "date":"{today} } 整段当成变量路径查不到而吞成空，body 坏成
         * {@code "}}，服务端直接 400。这里只认「合法变量路径」的占位符，
         * 其余的 { 当普通字符吐出，继续往后扫，内嵌变量照样能替换。
         */
        String render(String text) {
            if (text == null || text.isEmpty()) {
                return text == null ? "" : text;
            }
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c != '{') {
                    out.append(c);
                    i++;
                    continue;
                }
                int close = text.indexOf('}', i + 1);
                if (close < 0) {
                    out.append(text.substring(i));
                    break;
                }
                String expr = text.substring(i + 1, close);
                if (!isVarPath(expr)) {
                    // JSON 对象的左括号（或含引号/冒号/嵌套 { 的片段）：
                    // 原样吐出这个 {，只前进一格，让内嵌的 {var} 继续被识别。
                    out.append('{');
                    i++;
                    continue;
                }
                out.append(get(expr));
                i = close + 1;
            }
            return out.toString();
        }

        /** 变量路径：字母/下划线开头，其余为字母、数字、_ . [ ]。 */
        private static boolean isVarPath(String expr) {
            if (expr.isEmpty()) {
                return false;
            }
            char first = expr.charAt(0);
            if (!Character.isLetter(first) && first != '_') {
                return false;
            }
            for (int k = 1; k < expr.length(); k++) {
                char ch = expr.charAt(k);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.'
                        && ch != '[' && ch != ']') {
                    return false;
                }
            }
            return true;
        }
    }
}
