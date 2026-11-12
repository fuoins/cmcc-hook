package com.cmcchook;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

public final class JsBridge {
    private static final int MAX_BODY = 200_000;
    /** r13：WS 摘要一分钟最多一条，避免聊天页心跳把日志刷满。 */
    private static volatile long lastWsLog;

    @JavascriptInterface
    public void log(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String type = json.optString("type", "");
            String page = json.optString("page", "");
            if ("xhr".equals(type) || "fetch".equals(type) || "beacon".equals(type)) {
                // r8 常驻采集旁路：放在所有开关 / UrlFilter / apiOnly 过滤之前，
                // 原样留一份完整请求（含全量头与请求体），纯旁路不改任何任务逻辑。
                RawCapture.record("h5", json);
                // v3.80：页面级 XHR 钩子比任务脚本注入得早。wisdom 的登录回执
                // 在这里留一份摘要，供任务日志自证（不受「详细原始返回」开关影响）。
                WisdomTask.noteLoginReply(json.optString("url", ""),
                        json.optString("respBody", ""));
                logNetwork(json, page, type);
            } else if ("page".equals(type)) {
                logPage(json, page);
            } else if ("click".equals(type)) {
                logClick(json, page);
            } else if ("route".equals(type)) {
                logRoute(json, page);
            } else if ("error".equals(type)) {
                logError(json, page);
            } else if ("ws".equals(type)) {
                logWs(json, page);
            } else if ("post".equals(type)) {
                logPost(json, page);
            } else if ("cmd".equals(type)) {
                handleCmd(json, page);
            }
        } catch (Throwable e) {
            HookLog.logcat("JsBridge payload ignored: " + e);
        }
    }

    /** 收鹅蛋自动化脚本的回调。 */
    @JavascriptInterface
    public void goose(String payload) {
        GooseTask.onJsResult(payload);
    }

    private void logNetwork(JSONObject json, String page, String type) {
        // r13：点中口令按钮后的 6 秒窗口里，XHR/fetch 的请求体/响应体也可能带着
        // 口令（有的活动是先查口令再发消息）。这条不受 apiOnly / H5 开关影响，
        // 否则「只抓业务接口」又会把最关键的这一条挡掉。
        if (json.optBoolean("cmd", false)) {
            try {
                JSONObject one = new JSONObject();
                one.put("click", json.optString("click", ""));
                JSONArray arr = new JSONArray();
                if (!json.optString("reqBody", "").isEmpty()) {
                    arr.put(json.optString("reqBody", ""));
                }
                if (!json.optString("respBody", "").isEmpty()) {
                    arr.put(json.optString("respBody", ""));
                }
                one.put("frames", arr);
                one.put("url", json.optString("url", page));
                handleCmd(one, page);
            } catch (Throwable ignored) {
            }
        }
        if (!HookPrefs.h5()) {
            return;
        }
        String url = json.optString("url", "");
        if (url.isEmpty()) {
            url = page;
        }
        if (!UrlFilter.interesting(url)) {
            return;
        }
        String method = json.optString("method", "GET");
        int status = json.optInt("status", 0);
        String statusText = json.optString("statusText", "");
        long duration = json.optLong("duration", 0L);
        // 活动接口单独打 tag，日志页「接口」一筛就只剩业务请求，埋点名都不显示。
        String tag = ApiSpy.tag(url, "H5");
        if (HookPrefs.apiOnly() && "H5".equals(tag)) {
            // 只抓业务接口：极光埋点、广告位、静态资源一律不写。
            return;
        }
        String summary = ApiSpy.prefix(tag) + "H5 " + type.toUpperCase() + " " + method + " "
                + UrlFilter.shortForTitle(url) + " → " + (status == 0 ? "ERR" : status)
                + (duration > 0 ? " (" + duration + "ms)" : "");

        StringBuilder detail = new StringBuilder();
        detail.append("页面: ").append(page).append('\n');
        detail.append("URL: ").append(url).append('\n');
        detail.append("方法: ").append(method).append('\n');
        detail.append("状态: ").append(status).append(' ').append(statusText).append('\n');
        detail.append("耗时: ").append(duration).append(" ms\n");
        appendBlock(detail, "请求头", json.optJSONObject("reqHeaders"));
        appendBody(detail, "请求体", json.optString("reqBody", ""));
        appendBlock(detail, "响应头", json.optJSONObject("respHeaders"));
        appendBody(detail, "响应体", json.optString("respBody", ""));

        HookLog.log(tag, summary, detail.toString());
    }

    private void logPage(JSONObject json, String page) {
        if (!HookPrefs.ui() && !HookPrefs.storage()) {
            return;
        }
        if (!UrlFilter.interesting(page)) {
            return;
        }
        String title = json.optString("title", "");
        String summary = "H5 页面 · " + (title.isEmpty() ? UrlFilter.shortForTitle(page) : title);

        StringBuilder detail = new StringBuilder();
        detail.append("URL: ").append(page).append('\n');
        detail.append("标题: ").append(title).append('\n');
        detail.append("来源: ").append(json.optString("referrer", "")).append('\n');
        detail.append("UA: ").append(json.optString("ua", "")).append('\n');
        if (HookPrefs.storage()) {
            appendBody(detail, "Cookie", json.optString("cookie", ""));
            appendBlock(detail, "localStorage", json.optJSONObject("local"));
            appendBlock(detail, "sessionStorage", json.optJSONObject("session"));
        }
        appendBlock(detail, "页面控件", json.optJSONArray("controls"));
        HookLog.log("H5-PAGE", summary, detail.toString());
    }

    private void logClick(JSONObject json, String page) {
        if (!HookPrefs.ui() || !UrlFilter.interesting(page)) {
            return;
        }
        String text = json.optString("text", "").trim();
        String tag = json.optString("tag", "");
        String summary = "H5 点击 · " + (text.isEmpty() ? tag : text);
        StringBuilder detail = new StringBuilder();
        detail.append("页面: ").append(page).append('\n');
        detail.append("控件: ").append(tag).append('\n');
        detail.append("文本: ").append(text).append('\n');
        detail.append("id: ").append(json.optString("id", "")).append('\n');
        detail.append("class: ").append(json.optString("cls", "")).append('\n');
        detail.append("href: ").append(json.optString("href", "")).append('\n');
        JSONObject attrs = json.optJSONObject("attrs");
        if (attrs != null && attrs.length() > 0) {
            appendBlock(detail, "属性", attrs);
        }
        JSONArray chain = json.optJSONArray("chain");
        if (chain != null && chain.length() > 0) {
            StringBuilder one = new StringBuilder();
            for (int i = 0; i < chain.length(); i++) {
                if (i > 0) {
                    one.append(" < ");
                }
                one.append(chain.optString(i, ""));
            }
            detail.append("元素链: ").append(one).append('\n');
        }
        if (json.optBoolean("cmd", false)) {
            detail.append("口令窗口: 是（点击后 6 秒内的收发帧会单独记 CMD）\n");
        }
        HookLog.log("H5-CLICK", summary, detail.toString());
    }

    private void logRoute(JSONObject json, String page) {
        if (!HookPrefs.ui()) {
            return;
        }
        String url = json.optString("url", page);
        if (!UrlFilter.interesting(url)) {
            return;
        }
        HookLog.log("H5-ROUTE", "H5 路由 · " + UrlFilter.shortForTitle(url),
                "页面: " + page + "\n新地址: " + url + "\n标题: " + json.optString("title", ""));
    }

    private void logError(JSONObject json, String page) {
        if (!HookPrefs.h5() && !HookPrefs.ui()) {
            return;
        }
        if (!UrlFilter.interesting(page)) {
            return;
        }
        String message = json.optString("message", "unknown");
        StringBuilder detail = new StringBuilder();
        detail.append("页面: ").append(page).append('\n');
        detail.append("消息: ").append(message).append('\n');
        detail.append("来源: ").append(json.optString("source", "")).append(':')
                .append(json.optInt("line", 0)).append(':')
                .append(json.optInt("col", 0)).append('\n');
        detail.append("堆栈:\n").append(json.optString("stack", ""));
        HookLog.log("H5-ERROR", "H5 脚本错误 · " + message, detail.toString());
    }

    /**
     * r13：WebSocket 帧。
     *
     * <p>灵犀的按钮不是一条 HTTP 接口，而是 IM 长连接里的一条文本消息。模块以前
     * 只包了 XHR/fetch，这一层完全看不见，所以「日志里没有网络请求」。这里把
     * 出入帧都留一份到采集，命中口令点击窗口的帧再单独提一条 CMD。
     */
    private void logWs(JSONObject json, String page) {
        if (!UrlFilter.interesting(page)) {
            return;
        }
        RawCapture.record("h5-ws", json);
        // 一分钟留一条摘要：证明 WS 这一层确实被抓到了，又不会像逐帧日志那样刷屏。
        long now = System.currentTimeMillis();
        if (now - lastWsLog > 60_000L) {
            lastWsLog = now;
            HookLog.log("WS", "WS 帧 · " + json.optString("dir", "?") + " · "
                            + UrlFilter.shortForTitle(json.optString("url", page)),
                    "URL: " + json.optString("url", page)
                            + "\n方向: " + json.optString("dir", "")
                            + "\n长度: " + json.optInt("len", 0)
                            + "\n内容: " + json.optString("data", ""));
        }
        if (!json.optBoolean("cmd", false)) {
            return;
        }
        try {
            JSONObject one = new JSONObject();
            one.put("click", json.optString("click", ""));
            JSONArray arr = new JSONArray();
            arr.put(json.optString("data", ""));
            one.put("frames", arr);
            one.put("url", json.optString("url", page));
            one.put("dir", json.optString("dir", ""));
            handleCmd(one, page);
        } catch (Throwable ignored) {
        }
    }

    /** 页面内 iframe / SDK 的 postMessage：只进采集，不刷日志。 */
    private void logPost(JSONObject json, String page) {
        if (!UrlFilter.interesting(page)) {
            return;
        }
        RawCapture.record("h5-post", json);
    }

    /**
     * 口令候选：按「出站帧 &gt; 输入框 &gt; 新气泡」挑一个，写成 CMD 日志。
     * 模块进程收到后落进 {@link LingxiCmd}，换号时直接复用。
     */
    private void handleCmd(JSONObject json, String page) {
        try {
            String click = json.optString("click", "");
            String candidate = LingxiCmd.clean(json.optString("candidate", ""), click);
            String src = "ws";
            if (candidate.isEmpty()) {
                JSONArray frames = json.optJSONArray("frames");
                for (int i = 0; frames != null && i < frames.length(); i++) {
                    // 帧是真正发出去的内容，即使和 chip 文案一模一样也是有效口令，
                    // 这里不再按 click 去重；输入框/气泡才需要防「把按钮名当口令」。
                    String one = LingxiCmd.fromFrame(frames.optString(i, ""));
                    if (!one.isEmpty()) {
                        candidate = one;
                        break;
                    }
                }
            }
            if (candidate.isEmpty()) {
                JSONArray inputs = json.optJSONArray("inputs");
                for (int i = 0; inputs != null && i < inputs.length(); i++) {
                    JSONObject one = inputs.optJSONObject(i);
                    if (one == null) {
                        continue;
                    }
                    String v = LingxiCmd.clean(one.optString("value", ""), click);
                    if (!v.isEmpty()) {
                        candidate = v;
                        src = "input";
                        break;
                    }
                }
            }
            if (candidate.isEmpty()) {
                JSONArray fresh = json.optJSONArray("fresh");
                for (int i = 0; fresh != null && i < fresh.length(); i++) {
                    String v = LingxiCmd.clean(fresh.optString(i, ""), click);
                    if (!v.isEmpty()) {
                        candidate = v;
                        src = "bubble";
                        break;
                    }
                }
            }
            JSONArray fr = json.optJSONArray("frames");
            JSONArray in = json.optJSONArray("inputs");
            JSONArray fresh = json.optJSONArray("fresh");
            boolean hasData = (fr != null && fr.length() > 0)
                    || (in != null && in.length() > 0)
                    || (fresh != null && fresh.length() > 0);
            if (candidate.isEmpty() && !hasData) {
                // 只点了一下、既没有帧也没有输入/新气泡：点击本身 H5-CLICK 已经记了，
                // 这里不再多写一条「未识别」。
                return;
            }
            if (!candidate.isEmpty()) {
                json.put("candidate", candidate);
                json.put("src", src);
            }
            HookLog.log("CMD", candidate.isEmpty()
                            ? "口令线索（未识别）"
                            : "捕获口令 · " + candidate,
                    json.toString());
        } catch (Throwable ignored) {
        }
    }

    private void appendBlock(StringBuilder sb, String name, JSONObject object) {
        if (object == null || object.length() == 0) {
            return;
        }
        sb.append("--- ").append(name).append(" ---\n");
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext() && count < 100) {
            String key = keys.next();
            String value = object.optString(key, "");
            if (value.length() > 4000) {
                value = value.substring(0, 4000) + "…";
            }
            sb.append(key).append(": ").append(value).append('\n');
            count++;
        }
    }

    private void appendBody(StringBuilder sb, String name, String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY) + "\n…[truncated]";
        }
        sb.append("--- ").append(name).append(" ---\n").append(body).append('\n');
    }

    private void appendBlock(StringBuilder sb, String name, JSONArray array) {
        if (array == null || array.length() == 0) {
            return;
        }
        sb.append("--- ").append(name).append(" ---\n");
        int count = Math.min(array.length(), 100);
        for (int i = 0; i < count; i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                sb.append(array.optString(i, "")).append('\n');
                continue;
            }
            String text = o.optString("text", "");
            if (text.length() > 200) {
                text = text.substring(0, 200) + "…";
            }
            sb.append("- ").append(text)
                    .append(" [").append(o.optString("tag", "")).append(']');
            String id = o.optString("id", "");
            if (!id.isEmpty()) {
                sb.append(" id=").append(id);
            }
            String href = o.optString("href", "");
            if (!href.isEmpty()) {
                sb.append(" href=").append(href);
            }
            sb.append('\n');
        }
    }
}
