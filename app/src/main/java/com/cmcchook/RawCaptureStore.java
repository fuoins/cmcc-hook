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
import java.util.Date;
import java.util.HashMap;
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
        Map<String, Integer> sessions = new HashMap<>();
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
                    collectParams(resp, seq, url, firstSeen);
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

        JSONObject report = new JSONObject();
        try {
            report.put("generated", System.currentTimeMillis());
            report.put("schema", "cmcc-raw-capture/1");
            report.put("count", records.length());
            report.put("note", "原始流全量保留；deps[] 标注参数取值来源；"
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
            for (int i = probes.length() - 1; i >= 0; i--) {
                JSONObject p = probes.optJSONObject(i);
                if (p != null) {
                    sw = p.optBoolean("enabled", false);
                    break;
                }
            }
            if (sw == null) {
                sc.put("switchInTarget", JSONObject.NULL);
                sc.put("hint", "目标进程没有自检记录：采集开关可能没被读到，"
                        + "或中国移动进程启动后没有任何请求。");
            } else {
                sc.put("switchInTarget", sw.booleanValue());
                sc.put("hint", sw ? "目标进程读到开关=开" : "目标进程读到开关=关");
            }
            report.put("selfcheck", sc);
            report.put("records", records);
        } catch (Throwable e) {
            HookLog.logcat("raw capture report failed: " + e);
        }
        return report.toString();
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
                                      Map<String, JSONObject> firstSeen) {
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
            Object child = obj.opt(key);
            if (child instanceof JSONObject) {
                collectParams((JSONObject) child, seq, url, firstSeen);
            } else if (child instanceof JSONArray) {
                JSONArray arr = (JSONArray) child;
                for (int i = 0; i < Math.min(arr.length(), 20); i++) {
                    Object v = arr.opt(i);
                    if (v instanceof JSONObject) {
                        collectParams((JSONObject) v, seq, url, firstSeen);
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
