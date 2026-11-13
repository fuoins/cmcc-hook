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
    /**
     * 报告里单条响应体保留上限。
     *
     * <p>r22：8192 → 65536。8KB 这一刀正好砍在 coc3 的 getGrowthTaskList 上
     * （完整 23744 字符），截断后的字符串不是合法 JSON，整个响应体解析失败，
     * 前面那些任务在最需要分析的接口上反而全丢；上游任务列表、活动奖品列表
     * 普遍在 10–40KB，64KB 能覆盖绝大多数业务接口。
     */
    private static final int RESP_KEEP = 65536;
    /** 超过上限时保留的可读前缀长度。 */
    private static final int RESP_PREVIEW = 2000;

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
        /** r14：认不出活动的请求按域名归堆，不再混进 byAct。 */
        JSONObject byHost = new JSONObject();
        /** r14：按任务窗口（模块自己写的 trace）归堆，活动归属的兜底证据。 */
        JSONObject byTrace = new JSONObject();
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
                // r14：旧记录里的 act 可能是接口名（collect/websocket/getappkey）。
                // 只有内置表里真有的活动才允许进 byAct，其余一律按未归属处理。
                if (!WelfareCatalog.isKnownAct(act)) {
                    act = "";
                } else {
                    try {
                        o.put("act", act);
                    } catch (Throwable ignored) {
                    }
                }
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
                } else {
                    // r14：认不出活动的请求不再丢，按域名单独归一堆。
                    // 以前 idOf 会把接口名（collect/websocket/getappkey）当活动，
                    // byAct 里全是假活动；现在真活动只进 byAct，其余在这里按域看。
                    bucketHost(byHost, hostOf(url), o, seq);
                }
                // r14：按任务窗口归档 —— 比 URL 猜活动准得多。
                String tr = o.optString("trace", "");
                if (!tr.isEmpty()) {
                    traceBucket(byTrace, tr, o, seq, act);
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
                // r14：响应体分层 —— 报告里只留可读的那截 + 摘要，全量仍在 jsonl 原始流里。
                JSONObject briefResp = respBrief(o);
                if (briefResp != null) {
                    try {
                        o.put("resp", briefResp);
                    } catch (Throwable ignored) {
                    }
                }
                slimResp(o);
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
                // r14：脚本草稿封口 —— 截到 40 步（再长也不是人工能核的），
                // 末尾补一条 result，粘进 FastScripts 就是一份完整脚本。
                JSONObject script = b.optJSONObject("script");
                if (script != null) {
                    JSONArray st = script.optJSONArray("steps");
                    if (st != null && st.length() > 40) {
                        JSONArray cut = new JSONArray();
                        for (int i = 0; i < 40; i++) {
                            cut.put(st.opt(i));
                        }
                        script.put("steps", cut);
                        script.put("truncated", st.length() - 40);
                    }
                    JSONArray now = script.optJSONArray("steps");
                    if (now != null) {
                        JSONObject end = new JSONObject();
                        JSONObject r = new JSONObject();
                        r.put("kind", "ok");
                        r.put("text", "采集回放完成：" + k);
                        end.put("result", r);
                        now.put(end);
                    }
                    b.put("scriptJson", script.toString());
                }
            } catch (Throwable ignored) {
            }
        }

        JSONObject report = new JSONObject();
        try {
            report.put("generated", System.currentTimeMillis());
            report.put("schema", "cmcc-raw-capture/3");
            report.put("count", records.length());
            report.put("note", "records[] 是分层后的原始流（响应体超过 64KB 换成合法 JSON 占位，"
                    + "含 __truncated/bytes/preview，全量在 jsonl 文件里）；byAct 只收内置表里真实存在的活动，"
                    + "认不出的按域名归 byHost，按任务窗口归 byTrace（trace 由模块跑任务时写入，"
                    + "比 URL 猜活动准）；每个活动附 scriptJson：GET 带 query、body 用现场活值、"
                    + "末尾有 result，可直接粘进 FastScripts 跑一次；"
                    + "bodyTpl 是参数化参考（把活值换成 {参数名}，需人工补 set）。"
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
            sum.put("hosts", byHost.length());
            sum.put("traces", byTrace.length());
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
            report.put("byHost", byHost);
            report.put("byTrace", byTrace);
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

    /**
     * r14：认不出活动的请求按域名归堆。
     *
     * <p>这些多半是登录、探活、埋点、第三方 SDK 的请求，本来就是噪音；但直接丢掉
     * 又看不出「这个域到底在干什么」。按 host 留一份接口清单，既不影响 byAct，
     * 也能在接新活动时快速看出该域值不值得接。
     */
    private static void bucketHost(JSONObject byHost, String host, JSONObject o, int seq) {
        if (host == null || host.isEmpty()) {
            return;
        }
        try {
            JSONObject b = byHost.optJSONObject(host);
            if (b == null) {
                b = new JSONObject();
                b.put("count", 0);
                b.put("paths", new JSONArray());
                b.put("steps", new JSONArray());
                byHost.put(host, b);
            }
            b.put("count", b.optInt("count") + 1);
            String p = pathOf(o.optString("url", ""));
            if (!p.isEmpty()) {
                addOnce(b.optJSONArray("paths"), p, 40);
            }
            JSONObject s = new JSONObject();
            s.put("seq", seq);
            s.put("method", o.optString("method", "GET"));
            s.put("path", p);
            s.put("status", o.optInt("status", 0));
            b.optJSONArray("steps").put(s);
        } catch (Throwable ignored) {
        }
    }

    /**
     * r14：按任务窗口归档。
     *
     * <p>trace 是模块跑某一项任务时自己写的（{@code goose@16:31:07}），不靠 URL 猜。
     * 活动归属猜错时，这里仍是准的 —— 这段窗口内目标进程发的请求，就是这个活动
     * 真正需要的请求。
     */
    private static void traceBucket(JSONObject byTrace, String trace, JSONObject o,
                                    int seq, String act) {
        try {
            JSONObject b = byTrace.optJSONObject(trace);
            if (b == null) {
                b = new JSONObject();
                b.put("count", 0);
                b.put("acts", new JSONArray());
                b.put("hosts", new JSONArray());
                b.put("steps", new JSONArray());
                b.put("firstAt", o.optLong("ts", 0));
                byTrace.put(trace, b);
            }
            b.put("count", b.optInt("count") + 1);
            b.put("lastAt", o.optLong("ts", 0));
            if (!act.isEmpty()) {
                addOnce(b.optJSONArray("acts"), act, 12);
            }
            addOnce(b.optJSONArray("hosts"), hostOf(o.optString("url", "")), 12);
            JSONObject s = new JSONObject();
            s.put("seq", seq);
            s.put("method", o.optString("method", "GET"));
            s.put("path", pathOf(o.optString("url", "")));
            s.put("act", act);
            s.put("enc", cipherDomain(o.optString("url", ""), o));
            b.optJSONArray("steps").put(s);
        } catch (Throwable ignored) {
        }
    }

    private static void addOnce(JSONArray arr, String v, int max) {
        if (arr == null || v == null || v.isEmpty() || arr.length() >= max) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            if (v.equals(arr.optString(i, ""))) {
                return;
            }
        }
        arr.put(v);
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            int s = url.indexOf("://");
            if (s < 0) {
                return "";
            }
            int e = url.indexOf('/', s + 3);
            String h = e < 0 ? url.substring(s + 3) : url.substring(s + 3, e);
            int q = h.indexOf('?');
            if (q > 0) {
                h = h.substring(0, q);
            }
            return h.toLowerCase(Locale.US);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 响应体分层：只留 code/msg/顶层键 + 字节数，报告一眼能看出成败。 */
    private static JSONObject respBrief(JSONObject o) {
        try {
            String body = o.optString("respBody", "");
            if (body.isEmpty()) {
                return null;
            }
            JSONObject b = new JSONObject();
            b.put("bytes", body.length());
            JSONObject j = tryJson(body);
            if (j != null) {
                JSONArray keys = new JSONArray();
                java.util.Iterator<String> it = j.keys();
                int n = 0;
                while (it.hasNext() && n++ < 20) {
                    keys.put(it.next());
                }
                b.put("keys", keys);
                String code = firstOf(j, "code", "retCode", "rtnCode", "resultCode", "status");
                String msg = firstOf(j, "msg", "message", "desc", "retMsg", "resultDesc");
                if (!code.isEmpty()) {
                    b.put("code", code);
                }
                if (!msg.isEmpty()) {
                    b.put("msg", msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
                }
            } else {
                b.put("text", body.length() > 160 ? body.substring(0, 160) + "…" : body);
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String firstOf(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 报告里的响应体超限时，换成一个**合法 JSON** 的占位对象。
     *
     * <p>r22：旧实现是 {@code body.substring(0, 8192) + "…[+N 字符]"}，把 JSON
     * 硬切成半截字符串 —— 解析器直接抛异常，等于这条响应在报告里完全不可用
     * （getGrowthTaskList 就是这么丢的）。现在超限就替换为
     * {@code {"__truncated":true,"bytes":…,"preview":…,"note":…}}，仍然是字符串、
     * 仍然能被 JSON.parse 成功，字段名和字节数都在，预览给前 2000 字符；
     * 完整原文一条不少地留在 jsonl 原始流里。
     */
    private static void slimResp(JSONObject o) {
        try {
            String body = o.optString("respBody", "");
            if (body.length() <= RESP_KEEP) {
                return;
            }
            JSONObject stub = new JSONObject();
            stub.put("__truncated", true);
            stub.put("bytes", body.length());
            stub.put("preview", body.substring(0, RESP_PREVIEW));
            stub.put("note", "报告只留 " + RESP_PREVIEW + " 字符预览，完整响应体在 jsonl 原始流");
            o.put("respCut", body.length() - RESP_PREVIEW);
            o.put("respBody", stub.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 一步请求的「结构」：只留方法/路径/状态/签名头名/参数名，不留值。 */
    private static JSONObject step(JSONObject o, int seq, boolean cipher, JSONObject req) {        JSONObject s = new JSONObject();
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

    /**
     * 可直接粘进 {@code FastScripts} 的一步。
     *
     * <p>r14 起按「能跑」而不是「能看」来生成：
     * <ul>
     *   <li>GET 必须带 query —— 以前统一砍掉 query，GET 类接口（lottery/remain、
     *       user/info、task/list）粘进脚本必然缺参，跑不通；</li>
     *   <li>body 保留**现场活值**（不换成 {@code {占位}}）。占位要人工补 set 才能跑，
     *       活值虽然会过期，但粘上去就能立刻验证一次链路，这比「语法正确但一跑就空参」
     *       有用；另附 {@code bodyTpl} 作为参数化参考。</li>
     *   <li>密文域不生成 req（离线重放必失败），改生成一条 log，避免脚本一跑就
     *       拿密文白撞一次接口。</li>
     * </ul>
     */
    private static JSONObject scriptStep(JSONObject o, int seq, boolean cipher,
                                         JSONObject req, Map<String, String> idx) {
        String method = o.optString("method", "GET").toUpperCase(Locale.US);
        String full = o.optString("url", "");
        JSONObject step = new JSONObject();
        if (cipher) {
            try {
                step.put("log", "密文域（seq " + seq + "）：" + pathOf(full)
                        + " 需页面上下文/点击，不生成 req");
            } catch (Throwable ignored) {
            }
            return step;
        }
        JSONObject reqNode = new JSONObject();
        try {
            reqNode.put("id", "s" + seq);
            reqNode.put("method", method);
            // GET/带 query 的接口：完整 path 含 query，ScriptEngine 会 BASE+path。
            reqNode.put("path", pathWithQuery(full));
            String body = o.optString("reqBody", "");
            if (req != null && !body.isEmpty()) {
                reqNode.put("body", body);
                String tpl = substitute(body, idx);
                if (!tpl.equals(body)) {
                    reqNode.put("bodyTpl", tpl);
                }
            } else if (!body.isEmpty()) {
                reqNode.put("body", body);
            }
        } catch (Throwable ignored) {
        }
        try {
            step.put("req", reqNode);
        } catch (Throwable ignored) {
        }
        return step;
    }

    /** 脚本用的 path：BASE 内用相对路径，BASE 外用完整 URL；query 一律保留。 */
    private static String pathWithQuery(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith(TaskNet.BASE)) {
            String p = url.substring(TaskNet.BASE.length());
            return p.isEmpty() ? "/" : p;
        }
        return url;
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
                    .append("（").append(sum == null ? 0 : sum.optInt("hosts")).append(" 个域）")
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
            // r14：按任务窗口。活动归属是按 URL 猜的，这一段不猜 —— 模块知道
            // 这一分钟在跑哪个活动，窗口内的请求就是这个活动真正发的。
            JSONObject byTrace = r.optJSONObject("byTrace");
            if (byTrace != null && byTrace.length() > 0) {
                sb.append("按任务窗口:\n");
                java.util.Iterator<String> it = byTrace.keys();
                int n = 0;
                while (it.hasNext() && n < 20) {
                    String k = it.next();
                    JSONObject b = byTrace.optJSONObject(k);
                    if (b == null) {
                        continue;
                    }
                    JSONArray hosts = b.optJSONArray("hosts");
                    sb.append("  ").append(k).append(": ")
                            .append(b.optInt("count")).append(" 条");
                    if (hosts != null && hosts.length() > 0) {
                        sb.append(" · 域 ");
                        for (int i = 0; i < hosts.length() && i < 4; i++) {
                            if (i > 0) {
                                sb.append("/");
                            }
                            sb.append(hosts.optString(i, ""));
                        }
                    }
                    sb.append('\n');
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
