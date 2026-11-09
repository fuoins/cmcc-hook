package com.cmcchook;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在活动页自己的上下文里发请求。
 *
 * <p>为什么不直接 HttpURLConnection：这些 H5 活动的鉴权不是「带个 Cookie」这么简单。
 * 服务端会看 Referer 是不是活动页、Cookie 是不是它这个域刚种下的、
 * 请求是不是页面 JS 发的（瑞数签名、国密参数都藏在页面里）。
 * 在页面里 fetch，这些条件天然全对。
 *
 * <p>evaluateJavascript 不认 Promise，所以这里用「发起 + 轮询」：
 * 先把 fetch 挂上去，结果写进 window 上的一个槽，再反复读这个槽。
 */
final class WebFetch {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final long POLL_MS = 250L;

    private WebFetch() {
    }

    static final class Resp {
        final int status;
        final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean httpOk() {
            return status >= 200 && status < 300;
        }

        boolean looksJson() {
            String t = body.trim();
            return t.startsWith("{") || t.startsWith("[");
        }

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Throwable e) {
                return new JSONObject();
            }
        }

        String brief() {
            String t = body.replace('\n', ' ').replace('\r', ' ').trim();
            if (t.length() > 160) {
                t = t.substring(0, 160) + "…";
            }
            return "HTTP " + status + " " + t;
        }
    }

    static Resp post(WebPage page, String url, String body) {
        return send(page, "POST", url, body, null, null, 20_000L);
    }

    static Resp post(WebPage page, String url, String body, String contentType) {
        return send(page, "POST", url, body, contentType, null, 20_000L);
    }

    static Resp get(WebPage page, String url) {
        return send(page, "GET", url, null, null, null, 20_000L);
    }

    static Resp send(WebPage page, String method, String url, String body,
                     String contentType, Map<String, String> extra, long timeoutMs) {
        return send(page, method, url, body, null, contentType, extra, timeoutMs);
    }

    /**
     * @param referer 带 token 的活动页 URL，作为 fetch 的 Referer。
     *                签到接口靠 Referer 里的 token 识别会话（真实页面实证），
     *                location.href 在 SPA 路由 / 同域复用时可能已经不带当前活动的 token。
     */
    static Resp send(WebPage page, String method, String url, String body,
                     String referer, String contentType, Map<String, String> extra,
                     long timeoutMs) {
        if (page == null || page.raw() == null) {
            return new Resp(-1, "页面不可用");
        }
        final String slot = "__cmcc_http_" + SEQ.getAndIncrement();
        String ct = contentType == null || "json".equals(contentType)
                ? "application/json;charset=UTF-8" : contentType;
        // 活动接口认这几样：
        //   login-check      活动页是否已登录的唯一标记，缺了它服务端直接回登录页 HTML；
        //   X-Requested-With AJAX 标记，缺了会走页面路由兜底，返回 SPA 的 index.html；
        //   Accept           只要 JSON，避免服务端按浏览器的 Accept 回整页。
        // 砸金蛋另外要 Hlwyxhd-Device-Code，由调用方从 extra 传进来。
        StringBuilder hs = new StringBuilder();
        hs.append("'Content-Type':").append(JSONObject.quote(ct)).append(',')
          .append("'X-Requested-With':'XMLHttpRequest',")
          .append("'login-check':'1',")
          .append("'Accept':'application/json, text/plain, */*',")
          .append("'Accept-Language':'zh-CN,zh;q=0.9'");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                hs.append(',').append(JSONObject.quote(e.getKey()))
                  .append(':').append(JSONObject.quote(e.getValue()));
            }
        }
        String js = "(function(){"
                + "var id=" + JSONObject.quote(slot) + ";"
                + "window[id]='';"
                + "try{"
                + "  fetch(" + JSONObject.quote(url) + ",{"
                + "    method:" + JSONObject.quote(method) + ","
                + "    credentials:'include',"
                + "    cache:'no-store',"
                + "    headers:{" + hs + "},"
                + (body == null ? "" : "    body:" + JSONObject.quote(body) + ",")
                // 只有带 token 的活动页 URL 才显式覆盖 Referer；否则沿用页面
                // 自身的 location.href（它是带 token 打开的），避免被不带 token
                // 的 referer 冲掉会话。
                + "    referrer:" + (referer != null && referer.contains("token=")
                        ? JSONObject.quote(referer) : "location.href")
                + "  }).then(function(r){return r.text().then(function(t){"
                + "    window[id]=JSON.stringify({s:r.status,t:t});"
                + "  })}).catch(function(e){"
                + "    window[id]=JSON.stringify({s:-1,t:'fetch: '+String(e)});"
                + "  });"
                + "}catch(e){window[id]=JSON.stringify({s:-2,t:String(e)});}"
                + "return '1';})()";
        page.eval(js, 4_000L);

        String readJs = "(function(){var v=window[" + JSONObject.quote(slot)
                + "];return v?String(v):'';})()";
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String value = page.eval(readJs, 5_000L);
            if (value != null && !value.isEmpty()) {
                page.eval("(function(){try{delete window[" + JSONObject.quote(slot)
                        + "]}catch(e){}})()", 2_000L);
                try {
                    JSONObject obj = new JSONObject(value);
                    Resp out = new Resp(obj.optInt("s", -1), obj.optString("t", ""));
                    if (out.status == 200 && out.body.trim().startsWith("<")) {
                        // 接口回了 HTML：要么是登录页，要么是前端路由兜底。
                        // 把现场一起记下来，下次看日志就知道是页面没起来还是会话失效。
                        HookLog.logcat("fetch got HTML " + url + " :: "
                                + out.body.replace('\n', ' ').trim().substring(
                                0, Math.min(120, out.body.replace('\n', ' ').trim().length())));
                    }
                    return out;
                } catch (Throwable e) {
                    return new Resp(-3, value);
                }
            }
            WebPage.sleep(POLL_MS);
        }
        return new Resp(-4, "超时（" + timeoutMs + "ms）");
    }
}
