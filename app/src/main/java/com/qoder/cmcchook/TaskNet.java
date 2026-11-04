package com.qoder.cmcchook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 任务接口用的极简 HTTP 客户端。
 *
 * 不引 OkHttp：模块的类会被塞进目标 App 的 ClassLoader，多带一个 okhttp 容易和
 * App 自带的版本打架。HttpURLConnection 走系统实现，够用且零依赖。
 *
 * 关键是请求头 —— Python 里必须原样还原才不会 403 的那几个：
 *   Origin / Referer / login-check / x-requested-with / Cookie / UA
 */
final class TaskNet {
    static final String BASE = "https://wx.10086.cn";

    private static final int TIMEOUT = 20_000;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
            + "Chrome/120.0.0.0 Mobile Safari/537.36 leadeon/11.9.1/CMCCIT";

    private TaskNet() {
    }

    static final class Resp {
        final int status;
        final String body;
        final String location;

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
            return redirect() && location.contains("login");
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
        return request("GET", url, null, referer);
    }

    static Resp post(String url, String json, String referer) {
        return request("POST", url, json == null ? "{}" : json, referer);
    }

    private static Resp request(String method, String url, String body, String referer) {
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
                OutputStream out = conn.getOutputStream();
                out.write(bytes);
                out.flush();
                out.close();
            }
            int status = conn.getResponseCode();
            readSetCookies(conn);
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            return new Resp(status, text, conn.getHeaderField("Location"));
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
    private static void readSetCookies(HttpURLConnection conn) {
        try {
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                String name = entry.getKey();
                if (name == null || !"set-cookie".equalsIgnoreCase(name)) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    CookieStore.captureSetCookie(value);
                }
            }
        } catch (Throwable ignored) {
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
}
