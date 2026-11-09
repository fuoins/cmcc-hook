package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 探路会话：临时把「这段时间内所有请求」都记下来。
 *
 * <p>平时日志只记 10086 系的业务接口，还要去重、还挑 activity，
 * 目的是给人看。探路要的正好相反 —— 一个页面从加载到关闭之间发了什么、
 * 请求体长什么样、响应里有没有任务字段，全都得留下，否则又得让用户
 * 重新抓包发日志。
 *
 * <p>两个来源同时记：
 * <ul>
 *   <li>{@code okhttp}：App 原生请求（福利中心那种原生渲染页只有这一路能抓到）；</li>
 *   <li>{@code page}：离屏 WebView 里页面自己发的 XHR/fetch。</li>
 * </ul>
 * 两边都带毫秒时间戳，导出后能直接对齐「点了哪个按钮 → 发了哪条请求」。
 */
final class ProbeSession {

    private static final int MAX_HITS = 300;
    private static final int MAX_BODY = 700;
    private static final int MAX_URL = 300;

    private static final Object LOCK = new Object();
    private static final List<Hit> HITS = new ArrayList<>();
    private static final Set<String> SEEN = new LinkedHashSet<>();
    private static volatile boolean active;
    private static volatile long startedAt;

    static final class Hit {
        final long at;
        final String via;
        final String method;
        final String url;
        final String req;
        final String resp;
        final int status;
        int count = 1;

        Hit(String via, String method, String url, String req, String resp, int status) {
            this.at = System.currentTimeMillis();
            this.via = via;
            this.method = method;
            this.url = url;
            this.req = req;
            this.resp = resp;
            this.status = status;
        }

        JSONObject toJson() {
            JSONObject one = new JSONObject();
            try {
                one.put("t", at - startedAt);
                one.put("via", via);
                one.put("m", method);
                one.put("u", url);
                one.put("s", status);
                one.put("n", count);
                if (req != null && !req.isEmpty()) {
                    one.put("req", req);
                }
                if (resp != null && !resp.isEmpty()) {
                    one.put("resp", resp);
                }
            } catch (Throwable ignored) {
            }
            return one;
        }
    }

    private ProbeSession() {
    }

    static void start() {
        synchronized (LOCK) {
            HITS.clear();
            SEEN.clear();
            startedAt = System.currentTimeMillis();
            active = true;
        }
    }

    static void stop() {
        active = false;
    }

    static boolean active() {
        return active;
    }

    static long startedAt() {
        return startedAt;
    }

    /** 记一条。同一个 URL + 方法 + 状态码只留第一次，后面只加次数。 */
    static void record(String via, String method, String url, String req, String resp, int status) {
        if (!active || url == null || url.isEmpty()) {
            return;
        }
        String clean = url.length() > MAX_URL ? url.substring(0, MAX_URL) : url;
        String key = method + " " + clean + "|" + status;
        synchronized (LOCK) {
            if (!SEEN.add(key)) {
                for (Hit hit : HITS) {
                    if (key.equals(hit.method + " " + hit.url + "|" + hit.status)) {
                        hit.count++;
                        break;
                    }
                }
                return;
            }
            if (HITS.size() >= MAX_HITS) {
                return;
            }
            HITS.add(new Hit(via, method, clean, cut(req), cut(resp), status));
        }
    }

    /** 把页面里抓到的请求批量灌进来（JS 侧一次 eval 回来一整串）。 */
    static void recordPage(String json) {
        if (!active || json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                record("page", one.optString("m", "GET"), one.optString("u", ""),
                        one.optString("req", ""), one.optString("resp", ""),
                        one.optInt("s", 0));
            }
        } catch (Throwable ignored) {
        }
    }

    static int size() {
        synchronized (LOCK) {
            return HITS.size();
        }
    }

    static String dump() {
        return dump(MAX_HITS);
    }

    /** 只取前 limit 条：探路报告要经广播回传，条数决定体积。 */
    static String dump(int limit) {
        JSONArray array = new JSONArray();
        synchronized (LOCK) {
            int n = 0;
            for (Hit hit : HITS) {
                if (n++ >= limit) {
                    break;
                }
                array.put(hit.toJson());
            }
        }
        return array.toString();
    }

    /** 给日志用的短摘要：最多 40 条，只留方法、路径、状态。 */
    static String briefLines(int limit) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        synchronized (LOCK) {
            for (Hit hit : HITS) {
                if (n++ >= limit) {
                    break;
                }
                sb.append("  +").append(hit.at - startedAt).append("ms ")
                        .append(hit.method).append(' ')
                        .append(shortOf(hit.url)).append(" → ").append(hit.status);
                if (hit.count > 1) {
                    sb.append(" ×").append(hit.count);
                }
                sb.append('\n');
            }
            if (HITS.size() > limit) {
                sb.append("  … 另有 ").append(HITS.size() - limit).append(" 条\n");
            }
        }
        return sb.toString();
    }

    private static String cut(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY) + "…";
    }

    static String shortOf(String url) {
        if (url == null) {
            return "";
        }
        String value = url;
        int scheme = value.indexOf("://");
        if (scheme > 0) {
            value = value.substring(scheme + 3);
        }
        int cut = value.indexOf('?');
        if (cut > 0) {
            value = value.substring(0, cut);
        }
        return value.length() <= 96 ? value : "…" + value.substring(value.length() - 95);
    }
}
