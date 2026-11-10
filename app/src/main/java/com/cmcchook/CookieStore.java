package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 10086 系 Cookie 汇总（按域隔离）。
 *
 * v2.4 之前这里是「一张扁平大表」：不管来自哪个域名，同名一律后者覆盖。
 * 由此产生两个致命问题，日志里表现为 markstatus / task/list 一直 GOTO_LOGIN：
 *
 *   1. 鹅蛋页（lyh.lncmcc.com）会下发 JSESSIONID，把签到用的 JSESSIONID 顶掉。
 *      服务端按 JSESSIONID 找会话，找到的是鹅蛋那条 → 判定未登录。
 *   2. {@link #header(String)} 每次请求前都从 WebView 回灌一次快照，
 *      而 WebView 里存的是上一轮的旧 QWHD_SESSION_TOKEN，会把刚换到的新票覆盖回去。
 *
 * 现在改成：
 *   - 按注册域分组（10086.cn / lncmcc.com / …），请求哪个域只带哪个域的 Cookie；
 *   - Set-Cookie 解析 Domain 属性，归属到对应域；
 *   - 换票成功后把 QWHD_SESSION_TOKEN 钉住（{@link #pinSession()}），
 *     钉住期间 WebView 快照不得覆盖，直到过期。
 */
final class CookieStore {
    private static final String FILE = "cmcc_hook_net";
    private static final String KEY = "cookies_v2";
    private static final String KEY_LEGACY = "cookies";
    private static final long PERSIST_INTERVAL = 5_000L;

    /** 换票成功后锁定的 Cookie：这段时间内只认服务端新下发的值。 */
    private static final String[] PINNED = {"QWHD_SESSION_TOKEN", "HLWHD_SESSION_TOKEN"};
    private static final long PIN_TTL = 25 * 60_000L;

    /** 域 → (名 → 值)。用 LinkedHashMap 保证同名写入顺序稳定。 */
    private static final Map<String, Map<String, String>> JAR = new LinkedHashMap<>();
    private static final Map<String, Long> PIN_AT = new LinkedHashMap<>();
    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile long lastPersist;

    private CookieStore() {
    }

    // ------------------------------------------------------------ 写入

    /** 从 OkHttp / WebView 的请求头里截到 Cookie。 */
    static void capture(String url, String cookie) {
        capture(url, cookie, false);
    }

    /**
     * @param fromWebView true 表示来自 WebView 快照。快照可能是上一轮的旧值，
     *                    所以被钉住的会话 Cookie 不接受它。
     */
    static void capture(String url, String cookie, boolean fromWebView) {
        if (url == null || cookie == null || cookie.isEmpty()) {
            return;
        }
        String domain = domainOf(url);
        if (domain == null) {
            return;
        }
        boolean changed = false;
        synchronized (LOCK) {
            ensureLoaded();
            Map<String, String> bucket = bucket(domain);
            long now = System.currentTimeMillis();
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
                if (fromWebView && pinned(name, now)) {
                    continue;
                }
                if (!value.equals(bucket.get(name))) {
                    bucket.put(name, value);
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
        captureSetCookie(header, null);
    }

    /**
     * @param requestUrl 该 Set-Cookie 所属的请求地址。header 里没有 Domain 时用它兜底，
     *                   避免把 wx.10086.cn 的会话写进别的域。
     */
    static void captureSetCookie(String header, String requestUrl) {
        if (header == null || header.isEmpty()) {
            return;
        }
        String value = header;
        String domain = null;
        int semi = header.indexOf(';');
        if (semi > 0) {
            value = header.substring(0, semi);
            for (String attr : header.substring(semi + 1).split(";")) {
                String a = attr.trim();
                int eq = a.indexOf('=');
                if (eq > 0 && "domain".equalsIgnoreCase(a.substring(0, eq).trim())) {
                    domain = normalizeDomain(a.substring(eq + 1).trim());
                }
            }
        }
        int eq = value.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = value.substring(0, eq).trim();
        String cookieValue = value.substring(eq + 1).trim();
        if (name.isEmpty() || cookieValue.isEmpty()) {
            return;
        }
        if (domain == null) {
            domain = domainOf(requestUrl);
        } else {
            // 标准的 Cookie 域校验：响应头里的 Domain 必须是请求 host 的后缀。
            // 鹅蛋页（lyh.lncmcc.com）会塞一个 Domain=10086.cn 的 JSESSIONID，
            // 不加这一层校验就会被收进来，把签到那条正牌 JSESSIONID 顶掉 ——
            // 这正是 markstatus 一直 GOTO_LOGIN 的第二个来源。
            String host = hostOf(requestUrl);
            if (host != null && !host.equals(domain) && !host.endsWith("." + domain)) {
                domain = domainOf(requestUrl);
            }
        }
        if (domain == null) {
            return;
        }
        boolean changed;
        synchronized (LOCK) {
            ensureLoaded();
            Map<String, String> bucket = bucket(domain);
            changed = !cookieValue.equals(bucket.get(name));
            if (changed) {
                bucket.put(name, cookieValue);
            }
        }
        if (changed) {
            persist(false);
        }
    }

    /** 换票成功后调用：把会话 Cookie 钉住，防止 WebView 旧快照把它盖回去。 */
    static void pinSession() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            for (String name : PINNED) {
                PIN_AT.put(name, now);
            }
        }
    }

    // ------------------------------------------------------------ 读取

    /**
     * 汇总后的 Cookie 请求头。
     *
     * 只回灌该 url 自己那份 WebView 快照，并且只带与它同域（含父域）的 Cookie。
     * 以前这里会把 5 个探测地址全读一遍，等于把各域 Cookie 混成一锅。
     */
    static String header(String url) {
        if (url != null && url.indexOf("10086.cn") >= 0) {
            readOne(url);
        }
        String host = hostOf(url);
        if (host == null) {
            return all();
        }
        synchronized (LOCK) {
            ensureLoaded();
            List<String> domains = new ArrayList<>();
            for (String domain : JAR.keySet()) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    domains.add(domain);
                }
            }
            // 父域在前，子域在后；同名时子域覆盖父域。
            Collections.sort(domains, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return a.length() - b.length();
                }
            });
            LinkedHashMap<String, String> merged = new LinkedHashMap<>();
            for (String domain : domains) {
                Map<String, String> bucket = JAR.get(domain);
                if (bucket != null) {
                    merged.putAll(bucket);
                }
            }
            if (merged.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : merged.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 不区分域的兜底拼接（仅调试 / 兼容旧调用）。 */
    private static String all() {
        synchronized (LOCK) {
            ensureLoaded();
            LinkedHashMap<String, String> merged = new LinkedHashMap<>();
            for (Map<String, String> bucket : JAR.values()) {
                merged.putAll(bucket);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : merged.entrySet()) {
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
            for (Map<String, String> bucket : JAR.values()) {
                String value = bucket.get(name);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return null;
        }
    }

    /** 指定域下的值；domain 传 null 表示任意域。 */
    static String get(String domain, String name) {
        if (domain == null) {
            return get(name);
        }
        synchronized (LOCK) {
            ensureLoaded();
            Map<String, String> bucket = JAR.get(normalizeDomain(domain));
            return bucket == null ? null : bucket.get(name);
        }
    }

    /**
     * v4.0.7+probe-v2：App 容器把会话串拼进 XHR 参数用的四个键。
     *
     * <p>日志实证：App 请求形如
     * {@code gsmState?JSESSIONID=...;UID=...;Comment=...;Path=/;HTTPOnly;ticketID=POD9;Secure}，
     * 会话不是靠 touch.10086.cn 的 Cookie 传的（那边的 Path=/cmcc-service 根本不匹配），
     * 而是拼进请求参数。这里只读本进程已捕获的当前会话值，<b>不换票、不发起登录</b>，
     * 给离屏页做「会话串作为 URL 参数」一路对照。
     */
    static String sessionString() {
        String[] keys = {"JSESSIONID", "UID", "Comment", "ticketID"};
        synchronized (LOCK) {
            ensureLoaded();
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                String v = get("10086.cn", k);
                if (v == null || v.isEmpty()) {
                    v = firstOf(k);
                }
                if (v == null || v.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(k).append('=').append(v);
            }
            return sb.toString();
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
                String value = firstOf(key);
                if (value == null || value.isEmpty()) {
                    sb.append(key).append("=空  ");
                } else {
                    sb.append(key).append('=').append(shorten(value)).append("  ");
                }
            }
        }
        return sb.toString().trim();
    }

    /** 会话类 Cookie 的归属域，排障时用。 */
    static String scopeOf(String name) {
        synchronized (LOCK) {
            ensureLoaded();
            for (Map.Entry<String, Map<String, String>> e : JAR.entrySet()) {
                String value = e.getValue().get(name);
                if (value != null && !value.isEmpty()) {
                    return e.getKey() + "=" + shorten(value);
                }
            }
            return name + "=空";
        }
    }

    private static String firstOf(String name) {
        for (Map<String, String> bucket : JAR.values()) {
            String value = bucket.get(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String shorten(String value) {
        return value.length() <= 10 ? value : value.substring(0, 10) + "…";
    }

    // ------------------------------------------------------------ WebView

    /**
     * 把 MAP 里的 Cookie 灌进 WebView 的 CookieManager。
     *
     * <p>换票（appTokenLogin）走的是模块直连，Set-Cookie 只进了 MAP。
     * 而页面 fetch 时带的是 WebView CookieManager 里的值，两者不打通，
     * 以前只能靠「带票重开页面」让页面自己把 Cookie 种回去 —— 每个活动
     * 都要重新加载一次页面，这是慢的根因之一。现在换完票直接把会话
     * Cookie 写进系统内核和 X5 两套 CookieManager，页面 fetch 立刻带上新会话，
     * 不用再等页面加载。
     */
    static void flushToWebView() {
        String snapshot = snapshotForFlush();
        if (snapshot.isEmpty()) {
            return;
        }
        // 系统内核
        try {
            CookieManager sys = CookieManager.getInstance();
            for (String one : snapshot.split("\n")) {
                int bar = one.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String url = one.substring(0, bar);
                String cookie = one.substring(bar + 1);
                sys.setCookie(url, cookie);
            }
            sys.flush();
        } catch (Throwable ignored) {
        }
        // X5 内核：离屏 WebView 走的是它，不写的话页面 fetch 还是缺会话。
        try {
            Class<?> clazz = Class.forName("com.tencent.smtt.sdk.CookieManager");
            Object manager = clazz.getMethod("getInstance").invoke(null);
            for (String one : snapshot.split("\n")) {
                int bar = one.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                clazz.getMethod("setCookie", String.class, String.class)
                        .invoke(manager, one.substring(0, bar), one.substring(bar + 1));
            }
            try {
                clazz.getMethod("flush").invoke(manager);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 生成「url|cookie」快照，每域两行（域级 + 活动 host 级）。 */
    private static String snapshotForFlush() {
        synchronized (LOCK) {
            ensureLoaded();
            if (JAR.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Map<String, String>> entry : JAR.entrySet()) {
                StringBuilder cookie = new StringBuilder();
                for (Map.Entry<String, String> e : entry.getValue().entrySet()) {
                    if (cookie.length() > 0) {
                        cookie.append("; ");
                    }
                    cookie.append(e.getKey()).append('=').append(e.getValue());
                }
                if (cookie.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                // setCookie 不带 Domain 属性会存成 host-only Cookie，
                // 只写域级 url 的话 wx.10086.cn 的请求带不上，所以活动 host 也写一遍。
                sb.append(hostUrlFor(entry.getKey())).append('|').append(cookie);
                sb.append('\n').append("https://").append(entry.getKey()).append('/')
                        .append('|').append(cookie);
            }
            return sb.toString();
        }
    }

    /** 注册域 → 该域活动页 host。 */
    private static String hostUrlFor(String domain) {
        if ("10086.cn".equals(domain)) {
            return "https://wx.10086.cn/";
        }
        if ("lncmcc.com".equals(domain)) {
            return "https://lyh.lncmcc.com/";
        }
        return "https://" + domain + "/";
    }

    private static void readOne(String url) {
        try {
            String value = CookieManager.getInstance().getCookie(url);
            if (value != null && !value.isEmpty()) {
                capture(url, value, true);
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> clazz = Class.forName("com.tencent.smtt.sdk.CookieManager");
            Object manager = clazz.getMethod("getInstance").invoke(null);
            String value = (String) clazz.getMethod("getCookie", String.class)
                    .invoke(manager, url);
            if (value != null && !value.isEmpty()) {
                capture(url, value, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean pinned(String name, long now) {
        for (String key : PINNED) {
            if (!key.equals(name)) {
                continue;
            }
            Long at = PIN_AT.get(key);
            return at != null && now - at < PIN_TTL;
        }
        return false;
    }

    // ------------------------------------------------------------ 域工具

    /** https://wx.10086.cn/xxx → 10086.cn；lyh.lncmcc.com → lncmcc.com。 */
    private static String domainOf(String url) {
        String host = hostOf(url);
        return host == null ? null : baseDomain(host);
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            String host = new URL(url).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 取注册域：com.cn / net.cn 这类后缀向上多取一级。 */
    private static String baseDomain(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length <= 2) {
            return host;
        }
        String last = parts[parts.length - 1];
        String second = parts[parts.length - 2];
        boolean twoLevel = ("cn".equals(last) || "hk".equals(last) || "tw".equals(last))
                && ("com".equals(second) || "net".equals(second)
                || "org".equals(second) || "gov".equals(second));
        int take = twoLevel ? 3 : 2;
        if (parts.length <= take) {
            return host;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - take; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) {
            return null;
        }
        String value = domain.trim().toLowerCase();
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        return value.isEmpty() ? null : value;
    }

    private static Map<String, String> bucket(String domain) {
        Map<String, String> map = JAR.get(domain);
        if (map == null) {
            map = new LinkedHashMap<>();
            JAR.put(domain, map);
        }
        return map;
    }

    // ------------------------------------------------------------ 落盘

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
                migrateLegacy(p);
                return;
            }
            String domain = null;
            for (String line : raw.split("\n")) {
                if (line.startsWith("#")) {
                    domain = normalizeDomain(line.substring(1));
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && domain != null) {
                    bucket(domain).put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("cookie load failed: " + e);
        }
    }

    /** 旧版扁平表没有域信息，全部归到 10086.cn —— 任务接口都在这个域下。 */
    private static void migrateLegacy(SharedPreferences p) {
        try {
            String raw = p.getString(KEY_LEGACY, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            Map<String, String> bucket = bucket("10086.cn");
            for (String line : raw.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    bucket.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            persist(true);
        } catch (Throwable ignored) {
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
                for (Map.Entry<String, Map<String, String>> e : JAR.entrySet()) {
                    sb.append('#').append(e.getKey()).append('\n');
                    for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                        sb.append(c.getKey()).append('=').append(c.getValue()).append('\n');
                    }
                }
            }
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY, sb.toString()).apply();
        } catch (Throwable e) {
            HookLog.logcat("cookie persist failed: " + e);
        }
    }
}
