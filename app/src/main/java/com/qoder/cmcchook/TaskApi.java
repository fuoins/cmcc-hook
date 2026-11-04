package com.qoder.cmcchook;

import org.json.JSONObject;

/**
 * qwhdhub 活动接口封装。
 *
 * 这一层对应 Python 里的 exchange_session + api_headers + api_post。
 * 接口本身是明文 JSON，不需要签名，只要 Cookie 和 Referer 对得上就能通，
 * 所以 Xposed 侧不用重写任何加密逻辑。
 */
final class TaskApi {

    /** 签到主活动（1021122301）；channelId 沿用脚本默认值。 */
    static final String ACT_SIGN =
            TaskNet.BASE + "/qwhdhub/qwhdmark/1021122301?channelId=P00000109876";

    static final String API_MARK = TaskNet.BASE + "/qwhdhub/api/mark";

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
                    || all.contains("SESSION");
        }
    }

    /**
     * 预热：用当前 Cookie 打开一次活动页。
     *
     * 服务端会按页面会话重建上下文，并 Set-Cookie 下发 QWHD_SESSION_TOKEN；
     * 少了这一步 markstatus 会 302 到登录页，和 Python 里说的问题一致。
     *
     * @return null 表示会话可用；否则返回失败原因
     */
    static String warmUp(String activityUrl) {
        TaskNet.Resp resp = TaskNet.get(activityUrl, activityUrl);
        if (resp.status < 0) {
            return "网络不可用：" + resp.body;
        }
        if (resp.loginRedirect()) {
            return "登录已失效（活动页被重定向到登录页）";
        }
        if (resp.redirect() && resp.location.contains("qwhdsso")) {
            return "登录已失效（跳转 " + resp.location + "）";
        }
        String token = CookieStore.get("QWHD_SESSION_TOKEN");
        if (token == null || token.isEmpty()) {
            // 有的省份/活动不下发这个 Token，交给后面接口自己判断，不直接判失败
            HookLog.logcat("warmUp: no QWHD_SESSION_TOKEN, continue anyway");
        }
        return null;
    }

    static JSONObject post(String path, String body, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.post(TaskNet.BASE + path, body, referer);
        return parse(path, resp);
    }

    static JSONObject get(String path, String referer) throws TaskException {
        TaskNet.Resp resp = TaskNet.get(TaskNet.BASE + path, referer);
        return parse(path, resp);
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
}
