package com.cmcchook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * r8 常驻采集旁路（目标进程侧）。
 *
 * <p>在 {@link JsBridge} / {@link OkHttpHooks} 的**所有过滤之前**把每条请求原样
 * 留一份：不去重、不按 apiOnly 丢、不主动开页换票、不发任何业务请求、不进任务
 * 队列。开关默认关，打开后用户手动在 App 里做一遍活动，请求序列就自动落盘。
 *
 * <p>为什么走广播：模块进程和中国移动进程不是同一个 data 目录，目标进程写不进
 * 模块的私有目录。和 {@code WelfareProbe.send} 一样送 {@link LogReceiver}，
 * 由 {@link RawCaptureStore} 在模块进程落盘。
 *
 * <p>r10 修的三处（r8 实测「一条都没录到」）：
 * <ul>
 *   <li><b>显式组件</b>：r8 只 {@code setPackage}，属于隐式广播。Android 8+ 禁止
 *       静态接收器接收自定义隐式广播，Android 15 上直接丢，且不报错。现在和
 *       {@link HookLog} 完全一致：{@code setComponent} + INCLUDE_STOPPED + FOREGROUND。</li>
 *   <li><b>批量 + 限长</b>：r8 每请求一次广播、单条 32 万字符，容易撞 Binder
 *       事务上限和后台广播配额，失败还被 catch 吞掉。现在按 20 条 / 18 万字符 /
 *       300ms 合并成一条广播，单条上限收到 12 万字符。</li>
 *   <li><b>自检</b>：开关第一次被读取时发一条 {@code source=selfcheck}，带上
 *       目标进程真实读到的开关值、pid、单条上限。导出的报告里能一眼看出是
 *       「开关没读到」还是「广播没到」。</li>
 * </ul>
 *
 * <p>这是「采集报告 → 自动生成 ScriptEngine 脚本」地基的原材料层：密文域录下来
 * 仍是密文 + 每次现算的签名头，离线重放做不到，但能看出请求序列、参数来源，
 * 下一版据此把更多活动收敛成声明式脚本。
 */
final class RawCapture {

    static final String ACTION = "com.cmcchook.action.RAW_CAPTURE";
    static final String EXTRA_JSON = "json";
    static final String KEY = "raw_capture";

    private static final String RECEIVER = "com.cmcchook.LogReceiver";

    /** 单条记录的字符上限：Binder 事务有 ~1MB 限制，留足余量。 */
    private static final int MAX_CHARS = 120_000;
    private static final int MAX_FIELD = 12_000;
    /** 一批累计到这个字符数就先发，避免一条广播撑爆 Binder。 */
    private static final int BATCH_CHARS = 180_000;
    private static final int BATCH_MAX = 20;
    private static final long FLUSH_MS = 300L;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Object LOCK = new Object();
    private static final ArrayList<String> QUEUE = new ArrayList<>();
    private static long queuedChars;
    private static Handler handler;
    private static volatile boolean selfChecked;

    /** 本次进程启动算一个会话，导出时按会话归档。 */
    private static final String SESSION =
            Long.toString(System.currentTimeMillis(), 36);

    private static final Runnable FLUSH = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };

    private RawCapture() {
    }

    /**
     * 要不要发这一条。
     *
     * <p>r12 起开关判定搬到模块进程：这里只负责「明确读到关」才不发。
     * 读不到（{@link HookPrefs#rawCaptureOrNull()} 返回 null）一律照发，
     * 由 {@link LogReceiver} 按模块自己的开关决定落不落盘 —— 否则默认关的
     * 采集在跨进程读不到 prefs 时会永久静默失效，且查不出原因。
     *
     * <p>第一次被问到时顺手发一条自检（即使读到关也发），报告里能证明是
     * 「目标进程读到了 false」还是「读不到」。
     */
    static boolean shouldSend() {
        Boolean on = null;
        try {
            on = HookPrefs.rawCaptureOrNull();
        } catch (Throwable ignored) {
        }
        if (!selfChecked) {
            // 只有真的发出去才记「已自检」：极早期请求拿不到 Context 时下次重试，
            // 否则报告里会永远缺这条，又变成黑盒。
            selfChecked = selfCheck(on);
        }
        return on == null || on;
    }

    /**
     * 旁路记录一条。payload 里通常有 type/page/url/method/status/reqHeaders/
     * reqBody/respHeaders/respBody 等字段，全部原样带过去。
     */
    static void record(String source, JSONObject payload) {
        if (payload == null) {
            return;
        }
        // 先问开关：第一次问会发自检，不能因为第一条是静态资源就跳过。
        if (!shouldSend()) {
            return;
        }
        try {
            String url = payload.optString("url", "");
            if (url.isEmpty()) {
                url = payload.optString("page", "");
            }
            if (!wanted(url)) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("seq", SEQ.incrementAndGet());
            o.put("session", SESSION);
            o.put("source", source == null ? "" : source);
            o.put("url", url);
            String act = "";
            try {
                act = WelfareCatalog.idOf(url);
            } catch (Throwable ignored) {
                // 归属解析失败只影响分类，不能因此丢掉整条记录。
            }
            o.put("act", act == null ? "" : act);
            Iterator<String> keys = payload.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (o.has(k)) {
                    continue;
                }
                o.put(k, payload.opt(k));
            }
            String text = o.toString();
            if (text.length() > MAX_CHARS) {
                trim(o);
                text = o.toString();
            }
            if (text.length() > MAX_CHARS) {
                HookLog.logcat("raw capture drop oversize: " + text.length());
                return;
            }
            enqueue(text);
        } catch (Throwable e) {
            HookLog.logcat("raw capture failed: " + e);
        }
    }

    private static void trim(JSONObject o) {
        try {
            o.put("__truncated", true);
            String[] big = {"reqBody", "respBody", "reqHeaders", "respHeaders",
                    "body", "headers", "detail"};
            for (String k : big) {
                Object v = o.opt(k);
                if (v instanceof String && ((String) v).length() > MAX_FIELD) {
                    o.put(k, ((String) v).substring(0, MAX_FIELD) + "…[truncated]");
                } else if (v instanceof JSONObject) {
                    JSONObject jo = (JSONObject) v;
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String hk = it.next();
                        String hv = jo.optString(hk, "");
                        if (hv.length() > MAX_FIELD) {
                            jo.put(hk, hv.substring(0, MAX_FIELD) + "…[truncated]");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void enqueue(String text) {
        boolean now;
        synchronized (LOCK) {
            QUEUE.add(text);
            queuedChars += text.length();
            now = QUEUE.size() >= BATCH_MAX || queuedChars >= BATCH_CHARS;
            if (!now) {
                schedule();
            }
        }
        if (now) {
            flush();
        }
    }

    private static void schedule() {
        try {
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            handler.removeCallbacks(FLUSH);
            handler.postDelayed(FLUSH, FLUSH_MS);
        } catch (Throwable e) {
            // 拿不到主线程 Looper 就立刻发，宁可多条广播也别攒着不发。
            flush();
        }
    }

    private static void flush() {
        JSONArray arr = new JSONArray();
        int n = 0;
        synchronized (LOCK) {
            if (QUEUE.isEmpty()) {
                return;
            }
            for (String s : QUEUE) {
                try {
                    arr.put(new JSONObject(s));
                    n++;
                } catch (Throwable ignored) {
                }
            }
            QUEUE.clear();
            queuedChars = 0;
            if (handler != null) {
                handler.removeCallbacks(FLUSH);
            }
        }
        if (n == 0) {
            return;
        }
        if (!send(arr.toString())) {
            HookLog.logcat("raw capture batch lost: " + n);
        }
    }

    /** 发一包给模块进程。必须是显式组件，隐式广播在 Android 8+ 收不到。 */
    private static boolean send(String payload) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return false;
        }
        try {
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(HookLog.MODULE_PACKAGE, RECEIVER));
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtra(EXTRA_JSON, payload);
            ctx.sendBroadcast(intent);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("raw capture send failed: " + e);
            return false;
        }
    }

    private static boolean selfCheck(Boolean on) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("seq", 0);
            o.put("session", SESSION);
            o.put("source", "selfcheck");
            o.put("type", "selfcheck");
            // on == null 表示目标进程根本没读到模块 prefs，不是「读到关」。
            o.put("switchReadable", on != null);
            o.put("enabled", on == null ? JSONObject.NULL : on);
            o.put("pid", android.os.Process.myPid());
            o.put("process", HookLog.processName());
            o.put("module", HookLog.MODULE_VERSION);
            o.put("maxChars", MAX_CHARS);
            o.put("batchMax", BATCH_MAX);
            return send("[" + o.toString() + "]");
        } catch (Throwable e) {
            HookLog.logcat("raw capture selfcheck failed: " + e);
            return false;
        }
    }

    /**
     * 静态资源与埋点不录：采集目的是业务请求序列，图片/字体/埋点会把文件撑爆，
     * 还会撞后台广播配额。只按后缀与已知埋点域过滤，业务域一律放行。
     */
    static boolean wanted(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String u = url.toLowerCase(Locale.US);
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("file:")) {
            return false;
        }
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;
        String[] ext = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
                ".css", ".js", ".woff", ".woff2", ".ttf", ".map"};
        for (String e : ext) {
            if (path.endsWith(e)) {
                return false;
            }
        }
        String[] noise = {"sensorsdata", "giodata", "/gio/", "umeng",
                "talkingdata", "bugly", "/monitor/", "beacon."};
        for (String n : noise) {
            if (u.contains(n)) {
                return false;
            }
        }
        return true;
    }

    /** 给 OkHttp 原生侧拼一个和 H5 侧同形态的 payload。 */
    static JSONObject of(String url, String method, int status,
                         String reqHeaders, String reqBody,
                         String respHeaders, String respBody) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "native");
            o.put("url", url == null ? "" : url);
            o.put("method", method == null ? "" : method);
            o.put("status", status);
            o.put("reqHeaders", reqHeaders == null ? "" : reqHeaders);
            o.put("reqBody", reqBody == null ? "" : reqBody);
            o.put("respHeaders", respHeaders == null ? "" : respHeaders);
            o.put("respBody", respBody == null ? "" : respBody);
            o.put("encoding", "text");
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    static String session() {
        return SESSION;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
