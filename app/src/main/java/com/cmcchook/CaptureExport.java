package com.cmcchook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * r20：原始流导出 + 脱敏 + 参数依赖自动发现 + 结构化摘要。
 *
 * <p>解决 r8~r19 一直存在的「最后一公里」：{@link RawCaptureStore#buildReport}
 * 导出的报告把响应体截到 8KB、按活动加工过，用户发给我的一直是 {@code full.txt}
 * 摘要日志，看不到一条不丢的原始请求。本类直接读 {@link RawCaptureStore#load}
 * 的 JSONL 原始流（一条不丢、不截断），产出两份文件：
 * <ul>
 *   <li>{@code *.jsonl}：<b>全量原始流</b>，一行一条，字段原样（默认脱敏值），
 *       给离线分析用；</li>
 *   <li>{@code *.report.json}：可读摘要 —— byAct / byTrace / 接口清单 / 参数依赖图 /
 *       密文与不可参数化标记 / 成功判据候选 / 活动描述草稿。</li>
 * </ul>
 *
 * <p><b>参数依赖改为自动发现</b>：不再用 {@code RawCaptureStore.PARAMS} 那 16 个
 * 硬编码白名单，而是把每条响应里的标量值建索引，再扫后续请求的 body / query /
 * headers，值对上就记一条依赖（谁下发、谁引用）。新活动的 visitUrl / round /
 * dpN / unfinishRedirectUrl 之类都能自动认出来。
 *
 * <p><b>脱敏是确定性的</b>：敏感值换成 {@code <redacted:指纹 len=N>}，同一个值在
 * 响应下发处和请求引用处变成同一个占位符 —— 值不泄露，但依赖链仍然连得上，
 * 我拿到脱敏流照样能画出参数依赖图。用户明确说过不在乎隐私，因此另留
 * {@code redact=false} 原样导出的开关。
 *
 * <p>核心逻辑全是纯函数（不碰 Android），可在 JVM 上用 {@link #selfCheck()}
 * 离线自验；只有 {@link #export} 需要 Context 读文件。
 */
final class CaptureExport {

    private CaptureExport() {
    }

    static final String SCHEMA = "cmcc-capture-export/1";

    /** 敏感字段名：值打码、名字保留。覆盖凭证 / 身份 / 风控签名三大类。 */
    private static final String[] SECRET_KEYS = {
            "phone", "mobile", "mobileno", "phonenum", "tel", "userid", "uid",
            "openid", "unionid", "idcard", "certno", "realname",
            "token", "accesstoken", "refreshtoken", "apptoken", "app_token",
            "coc3_ticket", "coc3ticket", "ticket", "jwt", "session", "sessionid",
            "sessiontoken", "h5token", "ssoid", "tasktoken", "ctoken", "cticket",
            "authorization", "cookie", "set-cookie", "appsession",
            "x-app-sdk-session-jwt", "x-audiovideo-sdk-jwt", "x-token", "x-sign",
            "x-nonce", "sign", "signature", "encryptData", "encryptdata",
            "wasmParams", "wasparams", "password", "pwd", "secret", "appkey"
    };

    /** 明确是「页面加密 / 每次现算」的域，离线重放不可行。 */
    private static final String[] CIPHER_HINTS = {
            "/lyh/", "encrypt", "wasmparams", "getappkey", "websocket"
    };

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Set<String> SECRET_SET = new HashSet<>();

    static {
        for (String k : SECRET_KEYS) {
            SECRET_SET.add(norm(k));
        }
    }

    // ------------------------------------------------------------ 导出入口

    static final class Output {
        final String jsonlName;
        final byte[] jsonl;
        final String reportName;
        final byte[] report;
        final String stats;

        Output(String jsonlName, byte[] jsonl, String reportName, byte[] report, String stats) {
            this.jsonlName = jsonlName;
            this.jsonl = jsonl;
            this.reportName = reportName;
            this.report = report;
            this.stats = stats;
        }
    }

    /**
     * 导出一次录制窗口内的全量原始流。
     *
     * @param sinceTs 只取 ts >= 这个时间的记录（&lt;=0 表示全量）。一键录制开始时记下。
     * @param redact  true = 值打码（默认），false = 原样导出
     */
    static synchronized Output export(Context ctx, long sinceTs, boolean redact, String cmd) {
        String raw = RawCaptureStore.load(ctx);
        List<JSONObject> recs = new ArrayList<>();
        StringBuilder jsonl = new StringBuilder();
        long from = Long.MAX_VALUE;
        long to = 0L;

        String[] lines = raw.isEmpty() ? new String[0] : raw.split("\n");
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
            long ts = o.optLong("ts", 0L);
            if (sinceTs > 0 && ts > 0 && ts < sinceTs) {
                continue;
            }
            if (ts > 0) {
                if (ts < from) {
                    from = ts;
                }
                if (ts > to) {
                    to = ts;
                }
            }
            recs.add(o);
            jsonl.append(redact ? redactRecord(o).toString() : o.toString()).append('\n');
        }
        if (from == Long.MAX_VALUE) {
            from = 0L;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String base = "cmcc-capture-" + stamp;
        JSONObject report = analyze(recs, redact, cmd, from, to);
        String stats = report.optJSONObject("stats") == null
                ? "0 条记录" : briefStats(report.optJSONObject("stats"));
        String reportText;
        try {
            reportText = report.toString(2);
        } catch (Throwable e) {
            reportText = report.toString();
        }

        return new Output(
                base + ".jsonl",
                jsonl.toString().getBytes(StandardCharsets.UTF_8),
                base + ".report.json",
                reportText.getBytes(StandardCharsets.UTF_8),
                stats);
    }

    private static String briefStats(JSONObject s) {
        return "本次录制 " + s.optInt("records") + " 条请求 / "
                + s.optInt("acts") + " 个活动 / " + s.optInt("encrypted") + " 个密文域 / "
                + s.optInt("deps") + " 条参数依赖 / " + s.optInt("sessions") + " 个会话";
    }

    // ------------------------------------------------------------ 脱敏（纯函数）

    /** 一条记录脱敏：字段名、类型、长度、seq/url/ts/trace/session/act 全保留，只有值打码。 */
    static JSONObject redactRecord(JSONObject src) {
        try {
            return redactRecordImpl(src);
        } catch (Throwable e) {
            // org.json 的 put 抛受检异常；这一层兜住，绝不因为一条坏记录中断整份导出。
            return clone(src);
        }
    }

    private static JSONObject redactRecordImpl(JSONObject src) throws JSONException {
        JSONObject o = clone(src);
        for (String f : new String[]{"reqHeaders", "respHeaders"}) {
            Object v = o.opt(f);
            if (v instanceof String) {
                o.put(f, maskHeaders((String) v));
            } else if (v instanceof JSONObject) {
                maskJson((JSONObject) v, 0);
            }
        }
        for (String f : new String[]{"reqBody", "respBody", "body", "detail", "data"}) {
            Object v = o.opt(f);
            if (v instanceof String) {
                o.put(f, maskTextOrJson((String) v));
            } else if (v instanceof JSONObject) {
                maskJson((JSONObject) v, 0);
            } else if (v instanceof JSONArray) {
                maskArray((JSONArray) v, 0);
            }
        }
        String url = o.optString("url", "");
        if (!url.isEmpty()) {
            o.put("url", maskUrl(url));
        }
        o.put("__redacted", true);
        o.put("__schema", SCHEMA);
        return o;
    }

    private static JSONObject clone(JSONObject src) {
        try {
            return new JSONObject(src.toString());
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private static void maskJson(JSONObject o, int depth) throws JSONException {
        if (depth > 12) {
            return;
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (secretKey(k)) {
                o.put(k, placeholder(v));
            } else if (v instanceof JSONObject) {
                maskJson((JSONObject) v, depth + 1);
            } else if (v instanceof JSONArray) {
                maskArray((JSONArray) v, depth + 1);
            } else if (v instanceof String) {
                o.put(k, maskPhone((String) v));
            }
        }
    }

    private static void maskArray(JSONArray a, int depth) throws JSONException {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof JSONObject) {
                maskJson((JSONObject) v, depth + 1);
            } else if (v instanceof JSONArray) {
                maskArray((JSONArray) v, depth + 1);
            } else if (v instanceof String) {
                a.put(i, maskPhone((String) v));
            }
        }
    }

    /** body 可能是 JSON，也可能是密文串 / form 表单。JSON 递归打码，其余按文本打码。 */
    private static String maskTextOrJson(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String t = s.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                if (t.startsWith("{")) {
                    JSONObject o = new JSONObject(t);
                    maskJson(o, 0);
                    return o.toString();
                }
                JSONArray a = new JSONArray(t);
                maskArray(a, 0);
                return a.toString();
            } catch (Throwable ignored) {
                // 不是合法 JSON，按文本处理。
            }
        }
        return maskHeaders(s);
    }

    /** headers 可能是 "k: v\n..." 文本，也可能是密文。 */
    private static String maskHeaders(String s) {
        String out = maskPhone(s);
        if (out.indexOf(':') < 0 || out.trim().startsWith("{")) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        String[] rows = out.split("\n");
        for (String row : rows) {
            int p = row.indexOf(':');
            if (p > 0) {
                String k = row.substring(0, p).trim();
                String v = row.substring(p + 1);
                sb.append(k).append(": ")
                        .append(secretKey(k) ? placeholder(v.trim()) : v.trim());
            } else {
                sb.append(row);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String maskUrl(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return maskPhone(url);
        }
        String head = url.substring(0, q + 1);
        String[] pairs = url.substring(q + 1).split("&");
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            int eq = pairs[i].indexOf('=');
            if (eq < 0) {
                sb.append(pairs[i]);
                continue;
            }
            String k = pairs[i].substring(0, eq);
            String v = pairs[i].substring(eq + 1);
            sb.append(k).append('=').append(secretKey(k) ? placeholder(v) : maskPhone(v));
        }
        return sb.toString();
    }

    private static String maskPhone(String s) {
        Matcher m = PHONE.matcher(s);
        return m.replaceAll("1**********");
    }

    private static boolean secretKey(String k) {
        if (k == null) {
            return false;
        }
        String low = norm(k);
        for (String s : SECRET_SET) {
            if (low.equals(s) || low.endsWith(s) || low.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static String norm(String k) {
        return k.toLowerCase(Locale.US).replace("-", "").replace("_", "");
    }

    /**
     * 确定性占位符：同一个值在任何位置都变成同一个指纹，脱敏后依赖链仍连得上。
     * 用 FNV-1a 加固定常量做混淆，只保留 8 位十六进制指纹与原始长度。
     */
    private static String placeholder(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        long h = 0x811c9dc5L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193L;
        }
        h ^= 0x5a17c0deL;
        return "<redacted:" + String.format(Locale.US, "%08x", h & 0xffffffffL)
                + " len=" + s.length() + ">";
    }

    // ------------------------------------------------------------ 分析摘要（纯函数）

    static JSONObject analyze(List<JSONObject> recs, boolean redact, String cmd,
                              long from, long to) {
        try {
            return analyzeImpl(recs, redact, cmd, from, to);
        } catch (Throwable e) {
            JSONObject bad = new JSONObject();
            try {
                bad.put("schema", SCHEMA);
                bad.put("error", "analyze 失败: " + e);
                bad.put("stats", new JSONObject().put("records", recs.size()));
            } catch (Throwable ignored) {
            }
            return bad;
        }
    }

    private static JSONObject analyzeImpl(List<JSONObject> recs, boolean redact, String cmd,
                                          long from, long to) throws JSONException {
        JSONObject byAct = new JSONObject();
        JSONObject byTrace = new JSONObject();
        JSONObject byHost = new JSONObject();
        Set<String> sessions = new HashSet<>();
        Set<String> allPaths = new LinkedHashSet<>();
        int encrypted = 0;
        int unassigned = 0;

        // 响应标量值索引：值 -> 首次下发的 (field, seq, url, path)
        Map<String, JSONObject> valueSrc = new HashMap<>();

        for (JSONObject o : recs) {
            int seq = o.optInt("seq", 0);
            String url = o.optString("url", "");
            String act = o.optString("act", "");
            String tr = o.optString("trace", "");
            String sid = o.optString("session", "");
            String path = pathOf(url);
            if (!sid.isEmpty()) {
                sessions.add(sid);
            }
            if (!path.isEmpty()) {
                allPaths.add(path);
            }
            boolean cipher = isCipher(url, o);
            if (cipher) {
                encrypted++;
            }
            if (act.isEmpty()) {
                unassigned++;
            }

            JSONObject bucket = bucket(byAct, act.isEmpty() ? "_unassigned" : act);
            bucket.put("requests", bucket.optInt("requests") + 1);
            if (cipher) {
                bucket.put("encrypted", bucket.optInt("encrypted") + 1);
            }
            JSONArray ps = bucket.optJSONArray("paths");
            if (ps == null) {
                ps = new JSONArray();
                bucket.put("paths", ps);
            }
            if (!path.isEmpty() && !contains(ps, path)) {
                ps.put(path);
            }
            bucket.put("interfaces", ps.length());

            if (!tr.isEmpty()) {
                JSONObject tb = bucket(byTrace, tr);
                tb.put("requests", tb.optInt("requests") + 1);
                JSONArray tps = tb.optJSONArray("paths");
                if (tps == null) {
                    tps = new JSONArray();
                    tb.put("paths", tps);
                }
                if (!path.isEmpty() && !contains(tps, path)) {
                    tps.put(path);
                }
            }
            if (act.isEmpty()) {
                JSONObject hb = bucket(byHost, hostOf(url));
                hb.put("requests", hb.optInt("requests") + 1);
            }

            String resp = o.optString("respBody", "");
            if (!resp.isEmpty() && valueSrc.size() < 6000) {
                collectScalars(resp, seq, url, valueSrc);
            }
        }

        JSONArray deps = findDeps(recs, valueSrc);
        JSONArray flags = new JSONArray();
        for (JSONObject o : recs) {
            String url = o.optString("url", "");
            boolean cipher = isCipher(url, o);
            if (!cipher && !o.optString("act", "").isEmpty()) {
                continue;
            }
            JSONObject f = new JSONObject();
            f.put("seq", o.optInt("seq", 0));
            f.put("url", url);
            f.put("reason", cipher
                    ? "encrypted,需页面上下文/点击，不可离线参数化" : "未归属活动");
            flags.put(f);
        }

        JSONArray okCandidates = new JSONArray();
        for (JSONObject o : recs) {
            JSONObject c = okCandidate(o);
            if (c != null) {
                okCandidates.put(c);
            }
        }

        JSONObject report = new JSONObject();
        try {
            report.put("schema", SCHEMA);
            report.put("generated", System.currentTimeMillis());
            report.put("cmd", cmd == null ? "" : cmd);
            report.put("redacted", redact);
            report.put("window", new JSONObject().put("from", from).put("to", to));
            JSONObject stats = new JSONObject();
            stats.put("records", recs.size());
            stats.put("acts", byAct.length() - (byAct.has("_unassigned") ? 1 : 0));
            stats.put("encrypted", encrypted);
            stats.put("unassigned", unassigned);
            stats.put("deps", deps.length());
            stats.put("sessions", sessions.size());
            stats.put("interfaces", allPaths.size());
            report.put("stats", stats);
            report.put("note", "records 全量在同名 .jsonl 里，一行一条、不截断；"
                    + "本文件只放摘要。deps 是自动发现的参数依赖（谁下发、谁引用），"
                    + "不再依赖硬编码白名单。密文域离线重放不可行，只能页面内执行或点击兜底。");
            report.put("byAct", byAct);
            report.put("byTrace", byTrace);
            report.put("byHost", byHost);
            report.put("deps", deps);
            report.put("flags", flags);
            report.put("okWhenCandidates", okCandidates);
            report.put("descriptorDraft", draft(recs, deps, cmd));
        } catch (Throwable ignored) {
        }
        return report;
    }

    /** 自动发现：响应标量值 → 后续请求里出现的位置。 */
    private static JSONArray findDeps(List<JSONObject> recs, Map<String, JSONObject> valueSrc) throws JSONException {
        JSONArray out = new JSONArray();
        int cap = 0;
        for (JSONObject o : recs) {
            if (cap >= 400) {
                break;
            }
            String req = o.optString("reqBody", "") + " " + o.optString("reqHeaders", "")
                    + " " + o.optString("url", "");
            if (req.length() < 8) {
                continue;
            }
            int seq = o.optInt("seq", 0);
            for (Map.Entry<String, JSONObject> e : valueSrc.entrySet()) {
                String val = e.getKey();
                JSONObject src = e.getValue();
                if (src.optInt("seq", 0) >= seq) {
                    continue;   // 只认「先下发、后引用」
                }
                if (req.contains(val)) {
                    JSONObject d = new JSONObject();
                    d.put("name", src.optString("field"));
                    d.put("valueLen", val.length());
                    d.put("fromSeq", src.optInt("seq", 0));
                    d.put("fromUrl", src.optString("url", ""));
                    d.put("fromPath", src.optString("path", ""));
                    d.put("usedInSeq", seq);
                    d.put("usedInUrl", o.optString("url", ""));
                    out.put(d);
                    cap++;
                    break;
                }
            }
        }
        return out;
    }

    /** 递归收集响应里的标量值（最多三层、每层 120 key），记下 JSON path。 */
    private static void collectScalars(String body, int seq, String url,
                                       Map<String, JSONObject> valueSrc) {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Throwable e) {
            return;
        }
        walk(root, "", seq, url, valueSrc, 0);
    }

    private static void walk(JSONObject o, String prefix, int seq, String url,
                             Map<String, JSONObject> valueSrc, int depth) {
        if (depth > 3 || valueSrc.size() >= 6000) {
            return;
        }
        Iterator<String> it = o.keys();
        int n = 0;
        while (it.hasNext() && n++ < 120) {
            String k = it.next();
            Object v = o.opt(k);
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof JSONObject) {
                walk((JSONObject) v, path, seq, url, valueSrc, depth + 1);
            } else if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < Math.min(a.length(), 12) && valueSrc.size() < 6000; i++) {
                    Object one = a.opt(i);
                    if (one instanceof JSONObject) {
                        walk((JSONObject) one, path + "." + i, seq, url, valueSrc, depth + 1);
                    } else {
                        indexValue(one, path + "." + i, k, seq, url, valueSrc);
                    }
                }
            } else {
                indexValue(v, path, k, seq, url, valueSrc);
            }
        }
    }

    private static void indexValue(Object v, String path, String field, int seq, String url,
                                   Map<String, JSONObject> valueSrc) {
        if (v == null || v instanceof Boolean) {
            return;
        }
        String s = String.valueOf(v);
        if (s.length() < 4 || s.length() > 160) {
            return;
        }
        if (valueSrc.containsKey(s)) {
            return;
        }
        try {
            JSONObject rec = new JSONObject();
            rec.put("field", field);
            rec.put("path", path);
            rec.put("seq", seq);
            rec.put("url", url);
            valueSrc.put(s, rec);
        } catch (Throwable ignored) {
        }
    }

    /** 成功判据候选：从响应里的 code/msg/isFinish/status 推断，给描述 JSON 用。 */
    private static JSONObject okCandidate(JSONObject o) throws JSONException {
        String body = o.optString("respBody", "");
        if (body.isEmpty() || !body.trim().startsWith("{")) {
            return null;
        }
        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (Throwable e) {
            return null;
        }
        String code = firstOf(j, "code", "resultCode", "retCode", "rtnCode", "status");
        if (code.isEmpty()) {
            return null;
        }
        JSONObject c = new JSONObject();
        c.put("seq", o.optInt("seq", 0));
        c.put("url", o.optString("url", ""));
        c.put("code", code);
        c.put("msg", firstOf(j, "msg", "message", "resultDesc", "retMsg"));
        JSONObject data = j.optJSONObject("data");
        if (data != null) {
            for (String k : new String[]{"isFinish", "remain", "drawTimes", "finish"}) {
                if (data.has(k)) {
                    c.put("data." + k, String.valueOf(data.opt(k)));
                }
            }
        }
        return c;
    }

    /** 一条活动描述草稿：把接口序列 + 依赖 + 成功码拼成 cmcc-activity/1 骨架。 */
    private static JSONObject draft(List<JSONObject> recs, JSONArray deps, String cmd) throws JSONException {
        JSONObject d = new JSONObject();
        JSONArray steps = new JSONArray();
        String entry = "";
        int n = 0;
        for (JSONObject o : recs) {
            String url = o.optString("url", "");
            if (url.isEmpty() || isCipher(url, o)) {
                continue;
            }
            if (entry.isEmpty()) {
                entry = url;
            }
            if (n++ >= 24) {
                break;
            }
            JSONObject s = new JSONObject();
            s.put("kind", "http");
            s.put("id", "s" + o.optInt("seq", n));
            s.put("method", o.optString("method", "GET").toUpperCase(Locale.US));
            s.put("path", pathOf(url));
            if (!o.optString("reqBody", "").isEmpty()) {
                s.put("body", "<填参数，见 deps 与 jsonl>");
            }
            steps.put(s);
        }
        try {
            d.put("schema", ActivityDescriptor.SCHEMA);
            d.put("id", cmd == null || cmd.isEmpty() ? "unnamed" : cmd.replace(':', '_'));
            d.put("name", "<按活动名补>");
            d.put("enabled", false);      // 草稿默认不启用，人工核对后才下发
            d.put("entry", entry);
            d.put("budgetMs", 60000);
            d.put("steps", steps);
            d.put("okWhen", new JSONObject().put("hint", "见 okWhenCandidates，人工确认后填 path+eq"));
            d.put("deps", deps);
            d.put("note", "由导出报告自动生成的草稿，未验证；核对 jsonl 原文与成功判据后再启用。");
        } catch (Throwable ignored) {
        }
        return d;
    }

    // ------------------------------------------------------------ 工具

    private static JSONObject bucket(JSONObject parent, String key) throws JSONException {
        JSONObject b = parent.optJSONObject(key);
        if (b == null) {
            b = new JSONObject();
            parent.put(key, b);
        }
        return b;
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) {
            if (s.equals(a.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private static String pathOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int q = url.indexOf('?');
        String p = q > 0 ? url.substring(0, q) : url;
        int s = p.indexOf("://");
        if (s > 0) {
            int slash = p.indexOf('/', s + 3);
            return slash > 0 ? p.substring(slash) : "/";
        }
        return p;
    }

    private static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) {
            return "unknown";
        }
        String rest = url.substring(s + 3);
        int e = rest.indexOf('/');
        return e > 0 ? rest.substring(0, e) : rest;
    }

    private static String firstOf(JSONObject j, String... keys) {
        for (String k : keys) {
            String v = j.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static boolean isCipher(String url, JSONObject o) {
        String low = url.toLowerCase(Locale.US);
        for (String h : CIPHER_HINTS) {
            if (low.contains(h)) {
                return true;
            }
        }
        String head = (o.optString("reqHeaders", "") + o.optString("respHeaders", ""))
                .toLowerCase(Locale.US);
        return head.contains("x-sign") || head.contains("x-nonce")
                || head.contains("wasparams") || head.contains("encryptdata");
    }

    // ------------------------------------------------------------ 离线自验

    /** 纯 JVM 可跑：不发请求、不依赖真机。校验脱敏、依赖发现、判据、草稿、密文标记。 */
    static String selfCheck() {
        try {
            return selfCheckImpl();
        } catch (Throwable e) {
            return "FAIL CaptureExport 自验抛异常: " + e;
        }
    }

    private static String selfCheckImpl() throws JSONException {
        List<String> fails = new ArrayList<>();
        int total = 0;

        // 1) 脱敏：敏感值打码，字段名/结构保留，非敏感值保留
        total++;
        JSONObject rec = new JSONObject();
        rec.put("seq", 7);
        rec.put("url", "https://wx.10086.cn/api/x?app_token=ABCDEF123456&actId=55");
        rec.put("reqHeaders", "Cookie: SESSION=abcdefghijklmn\nX-Trace: tid-001");
        rec.put("reqBody", "{\"phone\":\"13800138000\",\"actId\":55,\"taskId\":\"T-7788\"}");
        rec.put("respBody", "{\"code\":\"SUCCESS\",\"data\":{\"isFinish\":1,\"token\":\"ZXCVBN098765\"}}");
        JSONObject masked = redactRecord(rec);
        String m = masked.toString();
        if (m.contains("ABCDEF123456") || m.contains("13800138000")
                || m.contains("abcdefghijklmn") || m.contains("ZXCVBN098765")) {
            fails.add("脱敏漏掉敏感值");
        }
        total++;
        if (!masked.has("reqBody") || !masked.optString("reqBody").contains("phone")
                || !masked.optString("reqBody").contains("55")) {
            fails.add("脱敏把字段名/非敏感值也弄丢了");
        }
        total++;
        if (!masked.optBoolean("__redacted", false) || masked.optInt("seq") != 7) {
            fails.add("脱敏标记或定位字段丢失");
        }
        total++;
        if (!masked.optString("url").contains("actId=55")
                || masked.optString("url").contains("ABCDEF123456")) {
            fails.add("URL 敏感参数打码失败");
        }
        total++;
        // 确定性：同一个值在「响应下发」与「请求引用」两处必须变成同一个占位符。
        // 用真实字段名（respBody/reqBody 才会进脱敏链），且字段名故意不同 —— 只比
        // 抽出来的 <redacted:...> 占位符，不能比整串（整串含不同字段名，本就不等）。
        JSONObject same = new JSONObject();
        same.put("respBody", "{\"token\":\"SECRET-VALUE-1234\"}");
        same.put("reqBody", "{\"taskToken\":\"SECRET-VALUE-1234\"}");
        JSONObject masked2 = redactRecord(same);
        java.util.regex.Pattern ph = java.util.regex.Pattern.compile("<redacted:[^>]*>");
        java.util.regex.Matcher ma = ph.matcher(masked2.optString("respBody"));
        java.util.regex.Matcher mb = ph.matcher(masked2.optString("reqBody"));
        String pa = ma.find() ? ma.group() : null;
        String pb = mb.find() ? mb.group() : null;
        if (pa == null || !pa.equals(pb)) {
            fails.add("确定性脱敏失败：同值占位符不一致，依赖链会断");
        }

        // 2) 参数依赖自动发现：响应下发的 taskId 被后续请求引用
        total++;
        List<JSONObject> recs = new ArrayList<>();
        JSONObject r1 = new JSONObject();
        r1.put("seq", 1);
        r1.put("ts", 1000L);
        r1.put("url", "https://wx.10086.cn/coc3/getGrowthTaskList");
        r1.put("method", "POST");
        r1.put("act", "miao");
        r1.put("respBody", "{\"resultCode\":0,\"data\":[{\"taskId\":\"T-9001\","
                + "\"unfinishRedirectUrl\":\"https://x/online/live88\"}]}");
        JSONObject r2 = new JSONObject();
        r2.put("seq", 2);
        r2.put("ts", 2000L);
        r2.put("url", "https://wx.10086.cn/coc3/visit");
        r2.put("method", "POST");
        r2.put("act", "miao");
        r2.put("reqBody", "{\"taskId\":\"T-9001\",\"visitUrl\":\"/online/live88\"}");
        r2.put("respBody", "{\"resultCode\":0}");
        recs.add(r1);
        recs.add(r2);
        JSONObject rep = analyze(recs, true, "miao", 1000L, 2000L);
        JSONArray deps = rep.optJSONArray("deps");
        if (deps == null || deps.length() == 0) {
            fails.add("参数依赖自动发现没认出 taskId 来源");
        }
        total++;
        if (rep.optJSONObject("stats") == null
                || rep.optJSONObject("stats").optInt("records") != 2) {
            fails.add("摘要统计条数不对");
        }
        total++;
        JSONArray ok = rep.optJSONArray("okWhenCandidates");
        if (ok == null || ok.length() == 0) {
            fails.add("成功判据候选没提取到 resultCode");
        }
        total++;
        JSONObject draft = rep.optJSONObject("descriptorDraft");
        if (draft == null || !ActivityDescriptor.SCHEMA.equals(draft.optString("schema"))
                || draft.optBoolean("enabled", true)) {
            fails.add("描述草稿 schema 错或默认启用了");
        }
        total++;
        if (rep.optJSONObject("byAct") == null
                || !rep.optJSONObject("byAct").has("miao")) {
            fails.add("按活动分组失败");
        }

        // 3) 密文域标记
        total++;
        JSONObject enc = new JSONObject();
        enc.put("seq", 3);
        enc.put("ts", 3000L);
        enc.put("url", "https://lyh.10086.cn/api/doTask");
        enc.put("reqHeaders", "X-Sign: abc\nX-Nonce: def");
        JSONObject rep2 = analyze(java.util.Collections.singletonList(enc), true, "egg", 0, 0);
        if (rep2.optJSONObject("stats").optInt("encrypted") != 1
                || rep2.optJSONArray("flags").length() == 0) {
            fails.add("密文域没被标记");
        }

        // 4) 描述符草稿能通过 schema 校验
        total++;
        String v = ActivityDescriptor.validate(draft);
        if (!v.startsWith("PASS")) {
            fails.add("草稿过不了描述 schema 校验: " + v);
        }

        if (fails.isEmpty()) {
            return "PASS " + total + "/" + total
                    + " · CaptureExport 脱敏/确定性/依赖发现/判据/草稿/密文标记/分组";
        }
        return "FAIL " + fails.size() + "/" + total + "\n  - " + String.join("\n  - ", fails);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
