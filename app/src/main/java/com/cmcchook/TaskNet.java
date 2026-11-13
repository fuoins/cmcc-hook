package com.cmcchook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动接口通道：一律从活动页自己的上下文里发。
 *
 * <p>服务端认的是「这条请求由活动页的 JS 发出」——Referer、Cookie、页面里算出来的
 * 签名参数，只有在页面里 fetch 才全部成立。模块自己拼 HTTP 头，拼得再像也不是
 * 页面发的，签到和砸金蛋都会回 GOTO_LOGIN。
 *
 * <p>唯一的例外是 SSO 换票：它靠 302 的 Location 下发 token，而浏览器的 fetch
 * 会自动跟随重定向、拿不到 Location，只能直连。
 */
final class TaskNet {
    static final String BASE = "https://wx.10086.cn";

    private static final int TIMEOUT = 20_000;
    static final String UA = "Mozilla/5.0 (Linux; Android 13; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
            + "Chrome/120.0.0.0 Mobile Safari/537.36 leadeon/11.9.1/CMCCIT";

    private TaskNet() {
    }

    static final class Resp {
        final int status;
        final String body;
        final String location;
        /** 原始响应头文本，只给 SSO 换票诊断用。 */
        String headers = "";

        Resp(int status, String body, String location) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        boolean redirect() {
            return status >= 300 && status < 400;
        }

        boolean looksJson() {
            String text = body.trim();
            return text.startsWith("{") || text.startsWith("[");
        }

        boolean loginRedirect() {
            if (!redirect()) {
                return false;
            }
            String loc = location.toLowerCase();
            return loc.contains("login") || loc.contains("qwhdsso");
        }

        String brief() {
            if (status < 0) {
                return body;
            }
            String text = body.replace('\n', ' ').trim();
            if (text.length() > 160) {
                text = text.substring(0, 160) + "…";
            }
            return "HTTP " + status + " " + text;
        }
    }

    static Resp get(String url, String referer) {
        return request("GET", url, null, referer, null);
    }

    static Resp post(String url, String json, String referer) {
        return request("POST", url, json == null ? "{}" : json, referer, null);
    }

    static Resp get(String url, String referer, Map<String, String> extra) {
        return request("GET", url, null, referer, extra);
    }

    static Resp post(String url, String json, String referer, Map<String, String> extra) {
        return request("POST", url, json == null ? "{}" : json, referer, extra);
    }

    /**
     * 在活动页里发请求。
     *
     * <p>活动页没开就没有通道，直接失败——不再自己造一个请求去顶。
     * 以前这里会退回 HttpURLConnection，结果页面明明没起来也能“跑成功”，
     * 服务端那边其实什么都没发生。
     *
     * <p>referer 会原样作为 fetch 的 Referer：签到接口靠它带的活动页 token
     * 识别会话（11.9.1 抓包实证），早期版本把它丢掉、改用 location.href，
     * 同域复用或 SPA 路由后 token 就丢了，接口一律 GOTO_LOGIN。
     */
    private static Resp request(String method, String url, String body, String referer,
                                Map<String, String> extra) {
        return request(method, url, body, referer, extra, false);
    }

    /**
     * v4.20.0-r33：给「只登记、不落地」的接口用（startBrowse 系列）。
     *
     * <p>这类接口 302 到外域落地页，fetch 跟随会被跨域拦成 TypeError，
     * 于是模块只看到 HTTP -1，而服务端其实已经登记成功。改成不跟随重定向后，
     * 拿到的是 opaqueredirect（status 0），不会再抛异常。
     * 状态 0 表示「请求已发出」，不是失败 —— 真实结果一律看复查。
     */
    static Resp getNoFollow(String url, String referer, Map<String, String> extra) {
        return request("GET", url, null, referer, extra, true);
    }

    private static Resp request(String method, String url, String body, String referer,
                                Map<String, String> extra, boolean noFollow) {
        WebPage page = WebSession.current();
        if (page == null) {
            return new Resp(-1, "活动页没有打开，请求没有发出", "");
        }
        WebFetch.Resp resp = WebFetch.send(page, method, url, body, referer,
                null, extra, 25_000L, noFollow);
        if (resp.status == -1 || resp.status == -2) {
            return new Resp(-1, resp.body, "");
        }
        return new Resp(resp.status, resp.body, "");
    }

    /** 只给 SSO 换票用：不跟随重定向，取 302 的 Location。 */
    static Resp getRaw(String url, String referer) {
        return direct("GET", url, null, referer);
    }

    /** 只给 SSO 换票用：换 token 那一步要自己收 Set-Cookie。 */
    static Resp postRaw(String url, String json, String referer) {
        return direct("POST", url, json == null ? "{}" : json, referer);
    }

    private static Resp direct(String method, String url, String body, String referer) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Origin", BASE);
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setRequestProperty("login-check", "1");
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            String cookie = CookieStore.header(url);
            if (!cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream out = conn.getOutputStream();
                out.write(bytes);
                out.flush();
                out.close();
            }
            int status = conn.getResponseCode();
            readSetCookies(conn, url);
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            Resp resp = new Resp(status, readAll(in), conn.getHeaderField("Location"));
            resp.headers = headerDump(conn);
            return resp;
        } catch (Throwable e) {
            return new Resp(-1, "NETWORK " + e, "");
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 服务端下发的 Session Token 全靠这里收。 */
    private static void readSetCookies(HttpURLConnection conn, String url) {
        try {
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                String name = entry.getKey();
                if (name == null || !"set-cookie".equalsIgnoreCase(name)) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    CookieStore.captureSetCookie(value, url);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 拼响应头全文（诊断用，限长）。 */
    private static String headerDump(HttpURLConnection conn) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                String name = entry.getKey();
                if (name == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    if (sb.length() > 600) {
                        return sb + "…";
                    }
                    sb.append(name).append(": ").append(value).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
