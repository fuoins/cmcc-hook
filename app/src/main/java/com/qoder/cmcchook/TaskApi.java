package com.qoder.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

/**
 * qwhdhub 活动接口封装。
 *
 * 这一层对应 Python 里的 exchange_session + api_headers + api_post。
 * 接口本身是明文 JSON，不需要签名，只要 Cookie 和 Referer 对得上就能通，
 * 所以 Xposed 侧不用重写任何加密逻辑。
 *
 * 现在管三套活动：
 *   签到有礼      /qwhdhub/qwhdmark/1021122301        （api/mark/*，要 login-check）
 *   天天砸金蛋    /qwhdhub/goldeneggs/1026071510       （lottery/* + task/*，要 Hlwyxhd-Device-Code）
 *   收鹅蛋换好礼  /huodong-portal/lnlyh/raiseGoose/*   （国密加密 + 瑞数签名，只能走 WebView）
 */
final class TaskApi {

    /** 签到有礼。 */
    static final String ACT_SIGN =
            TaskNet.BASE + "/qwhdhub/qwhdmark/1021122301?touch_id=JTST_P00000010910&yx=JHQD9999999999";

    /** 天天砸金蛋。 */
    static final String ACT_EGG =
            TaskNet.BASE + "/qwhdhub/goldeneggs/1026071510?A_C_CODE=HraVsPIWok";

    /** 收鹅蛋换好礼（页面入口，实参由 App 下发）。 */
    static final String ACT_GOOSE = "https://lyh.lncmcc.com/lyhVue/index.html#/bgIndex?actNo=goose2025";

    static final String API_MARK = TaskNet.BASE + "/qwhdhub/api/mark";

    private static final String PREFS = "cmcc_hook_net";
    private static final String KEY_DEVICE_CODE = "hlwyxhd_device_code";

    private TaskApi() {
    }

    static final class TaskException extends Exception {
        final String code;

        TaskException(String code, String message) {
            super(message);
            this.code = code == null ? "" : code;
        }

        boolean sessionLost() {
            String all = code + " " + getMessage();
            return all.contains("login")
                    || all.contains("302")
                    || all.contains("未登录")
                    || all.contains("登录已失效")
                    || all.contains("SESSION")
                    || all.contains("NOT_LOGIN");
        }
    }

    /**
     * 预热：走一遍 SSO 登录跳转，拿到带 token 的活动页地址。
     *
     * App 里打开签到页的实际链路是
     *   /qwhdsso/login?dlwmh=true&actUrl=<活动页>  →  302  →  /qwhdhub/qwhdmark/1021122301?token=QWHDSSOD...
     * 服务端在这一步按 cmccssotoken 重建 qwhd 会话并下发 Cookie。
     * 少了这一步 markstatus 会 302 到登录页，和 Python 里踩过的坑一致。
     *
     * @return 带 token 的 Referer；null 表示会话不可用
     */
    static String warmUp(String activityUrl) {
        String encoded = encode(activityUrl);
        String login = TaskNet.BASE + "/qwhdsso/login?dlwmh=true&actUrl=" + encoded;
        TaskNet.Resp resp = TaskNet.getRaw(login, activityUrl);
        if (resp.status < 0) {
            HookLog.logcat("warmUp: network " + resp.body);
            return null;
        }
        if (resp.redirect()) {
            String loc = resp.location;
            if (loc.contains("qwhdsso") && loc.contains("login")) {
                return null;
            }
            if (loc.startsWith("http")) {
                HookLog.logcat("warmUp: sso -> " + trim(loc));
                // 跟随一次，让服务端把 Set-Cookie 写完
                TaskNet.get(loc, activityUrl);
                return loc;
            }
        }
        // 没有跳转：说明当前 Cookie 已经够用，直接用原地址
        String token = CookieStore.get("QWHD_SESSION_TOKEN");
        HookLog.logcat("warmUp: no redirect (token=" + (token == null ? "null" : "set") + ")");
        return activityUrl;
    }

    static JSONObject post(String path, String body, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.post(TaskNet.BASE + path, body, referer);
        return parse(path, resp);
    }

    static JSONObject get(String path, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + path, referer);
        return parse(path, resp);
    }

    /** 砸金蛋那套接口：多带一个设备码。 */
    static JSONObject eggPost(String path, String body, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.post(TaskNet.BASE + path, body, referer, eggHeaders());
        return parse(path, resp);
    }

    static JSONObject eggGet(String path, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + path, referer, eggHeaders());
        return parse(path, resp);
    }

    static Map<String, String> eggHeaders() {
        return TaskNet.headers("Hlwyxhd-Device-Code", deviceCode());
    }

    /**
     * H5 自己生成的一个本地设备串（8 位前缀 + 32 位随机）。
     * 服务端只拿它做设备维度限频，不参与会话校验，所以本地生成一份固定值即可。
     */
    private static String deviceCode() {
        Context ctx = HookLog.context();
        if (ctx != null) {
            try {
                SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String v = p.getString(KEY_DEVICE_CODE, "");
                if (v != null && v.length() == 40) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        StringBuilder sb = new StringBuilder("6aea835d");
        String pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            sb.append(pool.charAt(random.nextInt(pool.length())));
        }
        String code = sb.toString();
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_DEVICE_CODE, code).apply();
            } catch (Throwable ignored) {
            }
        }
        return code;
    }

    private static JSONObject parse(String path, TaskNet.Resp resp) throws TaskException {
        if (resp.status < 0) {
            throw new TaskException("NETWORK", path + " 请求失败：" + resp.body);
        }
        if (resp.loginRedirect()) {
            throw new TaskException("LOGIN", path + " 被重定向到登录页，Cookie 已失效");
        }
        if (!resp.looksJson()) {
            throw new TaskException("NOT_JSON", path + " 返回非 JSON：" + resp.brief());
        }
        try {
            return new JSONObject(resp.body);
        } catch (Throwable e) {
            throw new TaskException("BAD_JSON", path + " 解析失败：" + resp.brief());
        }
    }

    /** 统一的 code 校验：qwhdhub 成功码是字符串 SUCCESS。 */
    static JSONObject dataOf(JSONObject root, String path) throws TaskException {
        String code = root.optString("code", "");
        if (!"SUCCESS".equals(code)) {
            String msg = root.optString("msg", root.optString("message", ""));
            throw new TaskException(code.isEmpty() ? "UNKNOWN" : code,
                    path + " 返回 " + code + " " + msg);
        }
        JSONObject data = root.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    /** data 是数组的接口（task/list）。 */
    static JSONArray arrayOf(JSONObject root, String path) throws TaskException {
        String code = root.optString("code", "");
        if (!"SUCCESS".equals(code)) {
            String msg = root.optString("msg", root.optString("message", ""));
            throw new TaskException(code.isEmpty() ? "UNKNOWN" : code,
                    path + " 返回 " + code + " " + msg);
        }
        JSONArray data = root.optJSONArray("data");
        return data == null ? new JSONArray() : data;
    }

    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return text;
        }
    }

    private static String trim(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 90 ? url : url.substring(0, 90) + "…";
    }
}
