package com.cmcchook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * r8 常驻采集旁路（目标进程侧）。
 *
 * <p>在 {@link JsBridge} / {@link OkHttpHooks} 的**所有过滤之前**把每条请求原样
 * 留一份：不去重、不按 apiOnly 丢、不主动开页换票、不发任何业务请求、不进任务
 * 队列。开关默认关，打开后用户手动在 App 里做一遍活动，请求序列就自动落盘。
 *
 * <p>为什么走广播：模块进程和中国移动进程不是同一个 data 目录，目标进程写不进
 * 模块的私有目录。和 {@code WelfareProbe.send} 一样用显式广播送到
 * {@link LogReceiver}，由 {@link RawCaptureStore} 在模块进程落盘。
 *
 * <p>这是「采集报告 → 自动生成 ScriptEngine 脚本」地基的原材料层：密文域录下来
 * 仍是密文 + 每次现算的签名头，离线重放做不到，但能看出请求序列、参数来源，
 * 下一版据此把更多活动收敛成声明式脚本。
 */
final class RawCapture {

    static final String ACTION = "com.cmcchook.action.RAW_CAPTURE";
    static final String EXTRA_JSON = "json";
    static final String KEY = "raw_capture";

    /** 单条记录的字符上限：Binder 事务有 ~1MB 限制，留足余量。 */
    private static final int MAX_CHARS = 320_000;
    private static final int MAX_FIELD = 24_000;
    private static final AtomicLong SEQ = new AtomicLong();
    /** 本次进程启动算一个会话，导出时按会话归档。 */
    private static final String SESSION =
            Long.toString(System.currentTimeMillis(), 36);

    private RawCapture() {
    }

    static boolean enabled() {
        return HookPrefs.flag(KEY, false);
    }

    /**
     * 旁路记录一条。payload 里通常有 type/page/url/method/status/reqHeaders/
     * reqBody/respHeaders/respBody 等字段，全部原样带过去。
     */
    static void record(String source, JSONObject payload) {
        if (!enabled() || payload == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("seq", SEQ.incrementAndGet());
            o.put("session", SESSION);
            o.put("source", source == null ? "" : source);
            String url = payload.optString("url", "");
            if (url.isEmpty()) {
                url = payload.optString("page", "");
            }
            o.put("url", url);
            o.put("act", WelfareCatalog.idOf(url));
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
                o.put("__truncated", true);
                String[] big = {"reqBody", "respBody", "reqHeaders", "respHeaders",
                        "body", "headers", "detail"};
                for (String k : big) {
                    Object v = o.opt(k);
                    if (v instanceof String && ((String) v).length() > MAX_FIELD) {
                        o.put(k, ((String) v).substring(0, MAX_FIELD) + "…[truncated]");
                    } else if (v instanceof JSONObject) {
                        Iterator<String> it = ((JSONObject) v).keys();
                        while (it.hasNext()) {
                            String hk = it.next();
                            String hv = ((JSONObject) v).optString(hk, "");
                            if (hv.length() > MAX_FIELD) {
                                ((JSONObject) v).put(hk, hv.substring(0, MAX_FIELD)
                                        + "…[truncated]");
                            }
                        }
                    }
                }
                text = o.toString();
            }
            Context ctx = HookLog.context();
            if (ctx == null) {
                return;
            }
            Intent intent = new Intent(ACTION);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra(EXTRA_JSON, text);
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            HookLog.logcat("raw capture failed: " + e);
        }
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
