package com.cmcchook;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

public final class JsBridge {
    private static final int MAX_BODY = 200_000;

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
