package com.qoder.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 10086 系 Cookie 汇总。
 *
 * Python 版最麻烦的一步是「手动抓一份 Cookie 粘到 config.json」，
 * 而且 QWHD_SESSION_TOKEN 30 分钟就过期，过期后所有接口 302 到登录页。
 *
 * 模块跑在 App 进程里，这件事可以完全自动化：
 *   1. App 自己发出的每个请求都带 Cookie，OkHttp / WebView 两处都截下来；
 *   2. 各域名的 Cookie 按名字合并成一份，任务接口直接复用；
 *   3. QWHD_SESSION_TOKEN 不必等 App 去刷 —— 用 cmccssotoken 拉一次活动页，
 *      服务端会 Set-Cookie 下发新的，和 Python 的 cookie_session() 同一套逻辑。
 */
final class CookieStore {
    private static final String FILE = "cmcc_hook_net";
    private static final String KEY = "cookies";
    private static final long PERSIST_INTERVAL = 5_000L;

    /** 探测用的入口，取到哪份算哪份（同名以后写的为准）。 */
    private static final String[] PROBE_URLS = {
            "https://wx.10086.cn/qwhdhub/qwhdmark/1021122301",
            "https://wx.10086.cn/",
            "https://dev.coc.10086.cn/coc3/canvas/rightsmarket-h5-canvas/online/home",
            "https://client.app.coc.10086.cn/",
            "https://h.app.coc.10086.cn/",
    };

    private static final Map<String, String> MAP = new LinkedHashMap<>();
    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile long lastPersist;

    private CookieStore() {
    }

    /** 从 OkHttp / WebView 的请求头里截到 Cookie。 */
    static void capture(String url, String cookie) {
        if (url == null || cookie == null || cookie.isEmpty()) {
            return;
        }
        if (url.indexOf("10086.cn") < 0) {
            return;
        }
        boolean changed = false;
        synchronized (LOCK) {
            ensureLoaded();
            for (String part : cookie.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (name.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (!value.equals(MAP.get(name))) {
                    MAP.put(name, value);
                    changed = true;
                }
            }
        }
        if (changed) {
            persist(false);
        }
    }

    /** Set-Cookie 也要收：QWHD_SESSION_TOKEN 就是这么下发的。 */
    static void captureSetCookie(String header) {
        if (header == null || header.isEmpty()) {
            return;
        }
        int semi = header.indexOf(';');
        String pair = semi > 0 ? header.substring(0, semi) : header;
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty() || value.isEmpty()) {
            return;
        }
        boolean changed;
        synchronized (LOCK) {
            ensureLoaded();
            changed = !value.equals(MAP.get(name));
            if (changed) {
                MAP.put(name, value);
            }
        }
        if (changed) {
            persist(false);
        }
    }

    /** 汇总后的 Cookie 请求头；同时把 App 各域名当前值刷进来。 */
    static String header(String url) {
        refreshFromWebView(url);
        synchronized (LOCK) {
            ensureLoaded();
            if (MAP.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : MAP.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    static String get(String name) {
        synchronized (LOCK) {
            ensureLoaded();
            return MAP.get(name);
        }
    }

    static boolean hasSso() {
        String token = get("cmccssotoken");
        return token != null && token.length() > 8;
    }

    static String summary() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            ensureLoaded();
            for (String key : new String[]{"cmccssotoken", "QWHD_SESSION_TOKEN",
                    "HLWHD_SESSION_TOKEN", "JSESSIONID", "UID"}) {
                String value = MAP.get(key);
                if (value == null || value.isEmpty()) {
                    sb.append(key).append("=空  ");
                } else {
                    sb.append(key).append('=').append(shorten(value)).append("  ");
                }
            }
        }
        return sb.toString().trim();
    }

    private static String shorten(String value) {
        return value.length() <= 10 ? value : value.substring(0, 10) + "…";
    }

    /** 只读不写盘：把 WebView CookieManager 当前值补进来，App 刚刷过的以它为准。 */
    private static void refreshFromWebView(String url) {
        if (url != null && url.indexOf("10086.cn") >= 0) {
            readOne(url);
        }
        for (String probe : PROBE_URLS) {
            readOne(probe);
        }
    }

    private static void readOne(String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            if (value != null && !value.isEmpty()) {
                capture(url, value);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> clazz = Class.forName("com.tencent.smtt.sdk.CookieManager");
            Object manager = clazz.getMethod("getInstance").invoke(null);
            String value = (String) clazz.getMethod("getCookie", String.class)
                    .invoke(manager, url);
            if (value != null && !value.isEmpty()) {
                capture(url, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String raw = p.getString(KEY, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    MAP.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("cookie load failed: " + e);
        }
    }

    private static void persist(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPersist < PERSIST_INTERVAL) {
            return;
        }
        lastPersist = now;
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (LOCK) {
                for (Map.Entry<String, String> e : MAP.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY, sb.toString()).apply();
        } catch (Throwable e) {
            HookLog.logcat("cookie persist failed: " + e);
        }
    }
}
