package com.cmcchook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * r8 常驻采集旁路（模块进程侧）：原始流落盘 + 导出报告。
 *
 * <p>落盘用 JSONL 追加写，一条不丢、不去重、不截断（上限已在目标进程侧按
 * Binder 事务控过），按 {@code session} 归档。导出时再离线生成带
 * {@code deps[]} 的结构化报告：标出哪些参数来自哪条响应、哪些域是密文不可参数化。
 *
 * <p>这份报告是「采集 → 自动生成 ScriptEngine 脚本」的地基产出，不是脚本本身。
 */
final class RawCaptureStore {

    private static final String FILE = "raw-capture.jsonl";
    private static final long MAX_FILE = 24L * 1024 * 1024;

    /** 服务端下发的参数：加活动时要从这里取值，不能硬编码。 */
    private static final String[] PARAMS = {
            "taskId", "taskType", "scanTime", "jumpUrl", "cToken", "taskToken",
            "ssoId", "h5token", "awardNum", "actId", "pageId", "channelId",
            "prizeId", "secId", "token", "openId", "taskToken"
    };

    private RawCaptureStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    static synchronized boolean append(Context ctx, String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        try {
            File f = file(ctx);
            if (f.exists() && f.length() > MAX_FILE) {
                // 到上限就滚动，别把手机存储写满；旧文件改名留一份。
                File bak = new File(ctx.getFilesDir(), FILE + ".bak");
                if (bak.exists() && !bak.delete()) {
                    HookLog.logcat("raw capture bak delete failed");
                }
                if (!f.renameTo(bak)) {
                    HookLog.logcat("raw capture rotate failed");
                }
            }
            FileOutputStream out = new FileOutputStream(file(ctx), true);
            out.write((line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
            out.close();
            return true;
        } catch (Throwable e) {
            HookLog.logcat("raw capture append failed: " + e);
            return false;
        }
    }

    /** 最近一次落盘时间，给界面显示「采集是不是还活着」。 */
    static long lastAt(Context ctx) {
        try {
            File f = file(ctx);
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable e) {
            return 0L;
        }
    }

    static synchronized String load(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.exists()) {
                return "";
            }
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            in.close();
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    static synchronized void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists() && !f.delete()) {
                HookLog.logcat("raw capture clear failed");
            }
            File bak = new File(ctx.getFilesDir(), FILE + ".bak");
            if (bak.exists()) {
                bak.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    static synchronized int count(Context ctx) {
        // 流式数行：文件上限 24MB，别为了一个数字把整份读进内存（设置页每次进来都调）。
        try {
            File f = file(ctx);
            if (!f.exists()) {
                return 0;
            }
            FileInputStream in = new FileInputStream(f);
            byte[] chunk = new byte[8192];
            int n;
            int lines = 0;
            while ((n = in.read(chunk)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (chunk[i] == '\n') {
                        lines++;
                    }
                }
            }
            in.close();
            return lines;
        } catch (Throwable e) {
            return 0;
        }
    }

    static String exportName(Context ctx) {
        return "cmcc-raw-capture-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".json";
    }

    /**
     * 把 JSONL 原始流整理成一份可看的报告：记录全量保留，额外补
     * {@code deps[]}（参数来自哪条响应）与 {@code flags[]}（密文域 / 未归属活动）。
     */
    static synchronized String buildReport(Context ctx) {
        JSONArray records = new JSONArray();
        String raw = load(ctx);
        Map<String, JSONObject> firstSeen = new HashMap<>();
        /** 服务端下发的「参数值 → 参数名」，用来把请求体里的活值换成占位符。 */
        Map<String, String> valueIndex = new HashMap<>();
        Map<String, Integer> sessions = new HashMap<>();
        JSONObject byAct = new JSONObject();
        int encrypted = 0;
        int unassigned = 0;
        JSONArray probes = new JSONArray();
        long firstAt = 0;
        long lastAt = 0;
        if (!raw.isEmpty()) {
            String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (Throwable bad) {
                    continue;
                }
                String url = o.optString("url", "");
                String act = o.optString("act", "");
                int seq = o.optInt("seq", records.length() + 1);
                boolean cipher = cipherDomain(url, o);

                // 请求体里用到的参数，若此前在某条响应里出现过，就标出取值来源。
                JSONArray deps = new JSONArray();
                JSONObject req = tryJson(o.optString("reqBody", ""));
                if (req != null) {
                    java.util.Iterator<String> it = req.keys();
                    while (it.hasNext()) {
                        String key = it.next();
                        if (!known(key)) {
                            continue;
                        }
                        JSONObject src = firstSeen.get(key);
                        if (src == null) {
                            continue;
                        }
                        JSONObject d = new JSONObject();
                        try {
                            d.put("name", key);
                            d.put("fromSeq", src.optInt("seq"));
                            d.put("fromUrl", src.optString("url"));
                            deps.put(d);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    o.put("deps", deps);
                } catch (Throwable ignored) {
                }

                JSONArray flags = new JSONArray();
                if (cipher) {
                    flags.put("encrypted,不可参数化");
                    encrypted++;
                }
                if (act.isEmpty()) {
                    flags.put("未归属活动");
                    unassigned++;
                }
                try {
                    o.put("flags", flags);
                } catch (Throwable ignored) {
                }

                // 响应体里的参数记为「服务端下发」，供后续步骤引用。
                JSONObject resp = tryJson(o.optString("respBody", ""));
                if (resp != null) {
                    collectParams(resp, seq, url, firstSeen, valueIndex);
                }
                // r12：按活动归类，并把每一步压成「结构」而不是原文，报告才看得懂、
                // 也才能据此生成脚本草稿（原文仍在 records 里，一个字没少）。
                if (!act.isEmpty()) {
                    bucket(byAct, act).optJSONArray("steps").put(step(o, seq, cipher, req));
                    bucket(byAct, act).optJSONObject("script").optJSONArray("steps")
                            .put(scriptStep(o, seq, cipher, req, valueIndex));
                }
                String sid = o.optString("session", "");
                if (!sid.isEmpty()) {
                    Integer v = sessions.get(sid);
                    sessions.put(sid, (v == null ? 0 : v) + 1);
                }
                long ts = o.optLong("ts", 0);
                if (ts > 0) {
                    if (firstAt == 0 || ts < firstAt) {
                        firstAt = ts;
                    }
                    if (ts > lastAt) {
                        lastAt = ts;
                    }
                }
                if ("selfcheck".equals(o.optString("source", ""))) {
                    probes.put(o);
                }
                records.put(o);
            }
        }
        // 每个活动的接口数 / 密文数，一眼看出「这个活动要走请求还是要留点击」。
        java.util.Iterator<String> keys = byAct.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject b = byAct.optJSONObject(k);
            if (b == null) {
                continue;
            }
            JSONArray steps = b.optJSONArray("steps");
            java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
            int enc = 0;
            for (int i = 0; steps != null && i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String p = s.optString("path", "");
                if (!p.isEmpty()) {
                    paths.add(p);
                }
                if (s.optBoolean("enc", false)) {
                    enc++;
                }
            }
            try {
                b.put("steps", steps == null ? 0 : steps.length());
                b.put("interfaces", paths.size());
                b.put("encrypted", enc);
                // 接口清单（带顺序、最多 24 条）：日志摘要里直接用，做脚本时不用翻 records。
                JSONArray pl = new JSONArray();
                int n = 0;
                for (String p : paths) {
                    if (n++ >= 24) {
                        break;
                    }
                    pl.put(p);
                }
                b.put("paths", pl);
            } catch (Throwable ignored) {
            }
        }

        JSONObject report = new JSONObject();
        try {
            report.put("generated", System.currentTimeMillis());
            report.put("schema", "cmcc-raw-capture/2");
            report.put("count", records.length());
            report.put("note", "原始流全量保留在 records[]；byAct 按活动压成请求序列"
                    + "（method/path/签名头名/参数名），并附可直接粘进 FastScripts 的"
                    + " scriptDraft（服务端活值已换成 {参数名} 占位）；"
                    + "encrypted 域为密文+每次现算签名头，离线重放不可行，"
                    + "只能用页面上下文直发或保留点击兜底。");
            JSONArray ss = new JSONArray();
            for (Map.Entry<String, Integer> e : sessions.entrySet()) {
                JSONObject one = new JSONObject();
                one.put("session", e.getKey());
                one.put("count", e.getValue());
                ss.put(one);
            }
            report.put("sessions", ss);
            JSONObject sum = new JSONObject();
            sum.put("records", records.length());
            sum.put("encrypted", encrypted);
            sum.put("unassigned", unassigned);
            sum.put("acts", byAct.length());
            JSONArray params = new JSONArray();
            for (String p : PARAMS) {
                params.put(p);
            }
            sum.put("params", params);
            report.put("summary", sum);

            // 自检段：一眼看出「目标进程读到开关没有」「记录的时间跨度」「广播通没通」。
            JSONObject sc = new JSONObject();
            sc.put("records", records.length());
            sc.put("firstAt", firstAt);
            sc.put("lastAt", lastAt);
            sc.put("probes", probes);
            Boolean sw = null;
            boolean readable = false;
            for (int i = probes.length() - 1; i >= 0; i--) {
                JSONObject p = probes.optJSONObject(i);
                if (p != null) {
                    readable = p.optBoolean("switchReadable", false);
                    Object v = p.opt("enabled");
                    sw = v instanceof Boolean ? (Boolean) v : null;
                    break;
                }
            }
            sc.put("switchReadable", readable);
            if (sw == null) {
                sc.put("switchInTarget", JSONObject.NULL);
                sc.put("hint", readable
                        ? "目标进程读到了 prefs，但开关值不是布尔（异常），采集按模块侧开关落盘。"
                        : "目标进程读不到模块 prefs（Android 15 跨进程文件权限常见），"
                        + "所以它一律照发，落盘与否由模块侧开关决定 —— 这是正常路径，不是故障。");
            } else {
                sc.put("switchInTarget", sw.booleanValue());
                sc.put("hint", sw ? "目标进程读到开关=开" : "目标进程读到开关=关");
            }
            report.put("selfcheck", sc);
            report.put("byAct", byAct);
            report.put("records", records);
        } catch (Throwable e) {
            HookLog.logcat("raw capture report failed: " + e);
        }
        return report.toString();
    }

    /** 按活动取（或建）一个桶：steps 是结构序列，script 是可直接粘的脚本骨架。 */
    private static JSONObject bucket(JSONObject byAct, String act) {
        JSONObject b = byAct.optJSONObject(act);
        if (b == null) {
            b = new JSONObject();
            try {
                b.put("steps", new JSONArray());
                JSONObject script = new JSONObject();
                script.put("steps", new JSONArray());
                b.put("script", script);
                byAct.put(act, b);
            } catch (Throwable ignored) {
            }
        }
        return b;
    }

    /** 一步请求的「结构」：只留方法/路径/状态/签名头名/参数名，不留值。 */
    private static JSONObject step(JSONObject o, int seq, boolean cipher, JSONObject req) {
        JSONObject s = new JSONObject();
        try {
            s.put("seq", seq);
            s.put("source", o.optString("source", ""));
            s.put("method", o.optString("method", "GET"));
            s.put("path", pathOf(o.optString("url", "")));
            s.put("url", o.optString("url", ""));
            s.put("status", o.optInt("status", 0));
            s.put("enc", cipher);
            s.put("heads", headNames(o.optString("reqHeaders", "")));
            JSONArray ps = new JSONArray();
            if (req != null) {
                java.util.Iterator<String> it = req.keys();
                while (it.hasNext()) {
                    ps.put(it.next());
                }
            }
            s.put("params", ps);
            s.put("reqBytes", o.optString("reqBody", "").length());
            s.put("respBytes", o.optString("respBody", "").length());
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** 可直接粘进 {@code FastScripts} 的一步：{@code {"req":{id,method,path,body}}}。 */
    private static JSONObject scriptStep(JSONObject o, int seq, boolean cipher,
                                         JSONObject req, Map<String, String> idx) {
        JSONObject reqNode = new JSONObject();
        try {
            reqNode.put("id", "s" + seq);
            reqNode.put("method", o.optString("method", "GET"));
            reqNode.put("path", pathOf(o.optString("url", "")));
            if (cipher) {
                reqNode.put("body", "<encrypted>");
                reqNode.put("note", "密文域：离线重放不可行，需页面上下文或保留点击");
            } else if (req != null) {
                reqNode.put("body", substitute(o.optString("reqBody", ""), idx));
            }
        } catch (Throwable ignored) {
        }
        JSONObject step = new JSONObject();
        try {
            step.put("req", reqNode);
        } catch (Throwable ignored) {
        }
        return step;
    }

    /** 去掉 BASE 前缀，脚本里用相对 path 更不容易写错。 */
    private static String pathOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        String noQuery = q > 0 ? url.substring(0, q) : url;
        if (noQuery.startsWith(TaskNet.BASE)) {
            String p = noQuery.substring(TaskNet.BASE.length());
            return p.isEmpty() ? "/" : p;
        }
        return noQuery;
    }

    /** 只记签名/鉴权头的名字，不落值 —— 值在 records 里，报告里重复没有意义。 */
    private static JSONArray headNames(String headers) {
        JSONArray out = new JSONArray();
        if (headers == null || headers.isEmpty()) {
            return out;
        }
        String low = headers.toLowerCase(Locale.US);
        String[] names = {"x-token", "x-sign", "x-time", "x-nonce", "$_ts",
                "authorization", "cookie", "x-app-sdk-session-jwt", "appsession",
                "x-audiovideo-sdk-jwt", "referer"};
        for (String n : names) {
            if (low.contains(n)) {
                out.put(n);
            }
        }
        return out;
    }

    /** 把服务端下发的活值换成 {参数名}，草稿才能直接跑。长值先替换，避免子串踩踏。 */
    private static String substitute(String body, Map<String, String> idx) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        if (idx.isEmpty()) {
            return body;
        }
        List<Map.Entry<String, String>> list = new ArrayList<>(idx.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return b.getValue().length() - a.getValue().length();
            }
        });
        String out = body;
        for (Map.Entry<String, String> e : list) {
            String v = e.getValue();
            // 太短的值（"0"、"1"、"2"）替换会把 JSON 结构踩坏，宁可留着原值。
            if (v == null || v.length() < 6) {
                continue;
            }
            if (out.contains(v)) {
                out = out.replace(v, "{" + e.getKey() + "}");
            }
        }
        return out;
    }

    /**
     * 导出后写进模块日志的一行摘要 —— 这样「日志里」也能看到采集全貌：
     * 多少条、几个活动、每个活动几步几接口几密文。全量原文在报告里。
     */
    static String brief(String reportJson) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject r = new JSONObject(reportJson);
            JSONObject sum = r.optJSONObject("summary");
            JSONObject sc = r.optJSONObject("selfcheck");
            sb.append("记录 ").append(sum == null ? 0 : sum.optInt("records"))
                    .append(" 条 · 活动 ").append(sum == null ? 0 : sum.optInt("acts"))
                    .append(" 个 · 密文 ").append(sum == null ? 0 : sum.optInt("encrypted"))
                    .append(" 条 · 未归属 ").append(sum == null ? 0 : sum.optInt("unassigned"))
                    .append('\n');
            if (sc != null) {
                long f = sc.optLong("firstAt", 0);
                long l = sc.optLong("lastAt", 0);
                if (f > 0) {
                    sb.append("时间: ").append(fmt(f)).append(" → ").append(fmt(l)).append('\n');
                }
                sb.append("目标进程读到开关: ")
                        .append(sc.optBoolean("switchReadable", false)
                                ? String.valueOf(sc.opt("switchInTarget"))
                                : "读不到（按模块侧开关判定，正常）")
                        .append('\n');
            }
            JSONObject byAct = r.optJSONObject("byAct");
            if (byAct != null && byAct.length() > 0) {
                sb.append("按活动:\n");
                java.util.Iterator<String> it = byAct.keys();
                int n = 0;
                while (it.hasNext() && n < 30) {
                    String k = it.next();
                    JSONObject b = byAct.optJSONObject(k);
                    if (b == null) {
                        continue;
                    }
                    sb.append("  ").append(k).append(": ")
                            .append(b.optInt("steps")).append(" 步 · ")
                            .append(b.optInt("interfaces")).append(" 接口 · 密文 ")
                            .append(b.optInt("encrypted")).append('\n');
                    JSONArray pl = b.optJSONArray("paths");
                    if (pl != null && pl.length() > 0) {
                        sb.append("    ");
                        for (int i = 0; i < pl.length() && i < 6; i++) {
                            if (i > 0) {
                                sb.append(" · ");
                            }
                            sb.append(pl.optString(i, ""));
                        }
                        if (pl.length() > 6) {
                            sb.append(" 等 ").append(pl.length()).append(" 个");
                        }
                        sb.append('\n');
                    }
                    n++;
                }
            }
        } catch (Throwable e) {
            return "采集报告解析失败: " + e;
        }
        return sb.toString().trim();
    }

    private static String fmt(long ts) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
    }

    private static boolean known(String key) {
        for (String p : PARAMS) {
            if (p.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** 递归找响应体里的服务端下发参数（只往下两层，够用且不会爆栈）。 */
    private static void collectParams(JSONObject obj, int seq, String url,
                                      Map<String, JSONObject> firstSeen,
                                      Map<String, String> valueIndex) {
        if (obj == null) {
            return;
        }
        java.util.Iterator<String> it = obj.keys();
        int scanned = 0;
        while (it.hasNext() && scanned < 120) {
            String key = it.next();
            scanned++;
            if (known(key) && !firstSeen.containsKey(key)) {
                try {
                    JSONObject src = new JSONObject();
                    src.put("seq", seq);
                    src.put("url", url);
                    firstSeen.put(key, src);
                } catch (Throwable ignored) {
                }
            }
            if (known(key) && !valueIndex.containsKey(key)) {
                // 记下真实值（截到 200），导出时把请求体里的活值换成 {参数名}。
                String v = obj.optString(key, "");
                if (!v.isEmpty() && v.length() <= 200) {
                    valueIndex.put(key, v);
                }
            }
            Object child = obj.opt(key);
            if (child instanceof JSONObject) {
                collectParams((JSONObject) child, seq, url, firstSeen, valueIndex);
            } else if (child instanceof JSONArray) {
                JSONArray arr = (JSONArray) child;
                for (int i = 0; i < Math.min(arr.length(), 20); i++) {
                    Object v = arr.opt(i);
                    if (v instanceof JSONObject) {
                        collectParams((JSONObject) v, seq, url, firstSeen, valueIndex);
                    }
                }
            }
        }
    }

    private static JSONObject tryJson(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean cipherDomain(String url, JSONObject o) {
        String u = url.toLowerCase(Locale.US);
        if (u.contains("biz-orange") || u.contains("wisdom-h5") || u.contains("lyh")
                || u.contains("lncmcc") || u.contains("raisegoose")) {
            return true;
        }
        String headers = o.optString("reqHeaders", "");
        if (headers.contains("x-sign") || headers.contains("x-token")
                || headers.contains("$_ts") || headers.contains("X-Sign")) {
            return true;
        }
        String body = o.optString("reqBody", "").trim();
        // 整段是密文（非 JSON、非空、且较长）也按密文域标注。
        return body.length() > 24 && !body.startsWith("{") && !body.startsWith("[")
                && !body.startsWith("<") && !body.startsWith("---")
                && !body.contains("=");
    }
}
