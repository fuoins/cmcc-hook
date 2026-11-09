package com.cmcchook;

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
 * <p>活动地址不在这里写死：一律取 App 福利专区里实际下发的那一条。
 * 用户手动点「立即采集」时，{@link WelfareCollect} 会把页面里的楼层数据读回来，
 * 里面带着每个活动当下的入口和渠道参数；这里只负责按活动号去找。
 *
 * <p>找不到就返回空串，调用方直接报「还没有采集到活动入口」，
 * 不拿任何写死的地址去顶。
 */
final class TaskApi {

    /** 收鹅蛋换好礼（页面入口，实参由 App 下发）。 */
    static final String ACT_GOOSE = "https://lyh.lncmcc.com/lyhVue/index.html#/bgIndex?actNo=goose2025";

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
            String low = all.toLowerCase();
            return all.contains("login")
                    || low.contains("goto_login")
                    || all.contains("302")
                    || all.contains("未登录")
                    || all.contains("去登录")
                    || all.contains("登录已失效")
                    || all.contains("SESSION")
                    || all.contains("NOT_LOGIN");
        }
    }

    /**
     * 换一份可用的活动会话。
     *
     * v2.0 这里是纯 HTTP 走 SSO 跳转，但服务端只认 WebView 里的登录态，
     * 结果每次都 302 回登录页 → markstatus 直接 GOTO_LOGIN。
     * v2.1 交给 {@link SsoLogin}：先从 App 的 JSBridge 取 SSO token，
     * 再走 appTokenLogin 换 QWHD_SESSION_TOKEN，全程不打开页面。
     *
     * @return 带 token 的活动页 Referer；null 表示换不到
     */
    static String warmUp(String activityUrl, StringBuilder detail) {
        if (detail == null) {
            detail = new StringBuilder();
        }
        // 页面现场已经打开时，接口直接从这个页面里发，不需要再换票。
        // 但「现场开着」不等于「会话有效」：进程刚被拉起时开的页面可能
        // 停在登录页。那种情况必须走换票（页面已把 App 的 JS 桥注册好）。
        // 命中会话时返回缓存里带 token 的活动页地址做 Referer —— 签到接口
        // 正是靠 Referer 里的 token 认会话，返回不带 token 的原始地址会前功尽弃。
        if (WebSession.current() != null && SsoLogin.hasSession(activityUrl)) {
            String session = SsoLogin.sessionUrl(activityUrl);
            detail.append("会话: 活动页现场（同源请求，免换票）· 带 token Referer\n");
            return session.contains("token=") ? session : activityUrl;
        }
        if (WebSession.current() != null) {
            detail.append("会话: 现场开着但没有有效会话，尝试换票\n");
        }
        String url = SsoLogin.ensure(activityUrl, detail);
        HookLog.logcat("warmUp: " + (url == null ? "failed" : "ok") + " " + trim(url));
        return url;
    }

    static String warmUp(String activityUrl) {
        return warmUp(activityUrl, new StringBuilder());
    }

    /** 签到有礼的当前入口。 */
    static String signEntry() {
        return findEntry("qwhdmark/1021122301");
    }

    /** 天天砸金蛋的当前入口。 */
    static String eggEntry() {
        return findEntry("goldeneggs/1026071510");
    }

    /** 充值先领券：和砸金蛋同一套 qwhdhub 接口，只是活动号不同。 */
    static String rechargeEntry() {
        return findEntry("1025093012");
    }

    /**
     * 找一个活动的入口。
     *
     * <p>11.9.1 实证：活动页 URL 必须带渠道参数（touch_id / yx / channelId …），
     * 否则服务端把页面 302 到「访问渠道未开放」，接口全返回错误 HTML。
     * 所以优先级是「谁带了渠道参数谁优先」：
     *
     * <ol>
     *   <li>{@link RealEntry}：App 现场点进活动时学到的真实完整地址（最贴真实软件）</li>
     *   <li>采集缓存里带渠道参数的那条</li>
     *   <li>内置表里带渠道参数的地址（冷启动兜底）</li>
     *   <li>以上都没有，退回采集缓存 / 内置裸地址（可能仍被渠道拦截，至少不编参数）</li>
     * </ol>
     */
    static String findEntry(String frag) {
        Context ctx = HookLog.context();
        if (frag == null || frag.isEmpty()) {
            return "";
        }
        // 0. wisdom-h5 单独走一条：这站的「渠道参数」就是 h5token，两个路由共用
        //    一份登录凭证。答题页自己没有 token 时，借签到抽奖学到的那份。
        //    以前走通用流程，采集缓存里那条裸 #/answerQuestions 会把内置地址顶掉，
        //    结果 ssoId 永远是空，进去只能看到未登录。
        String wid = WelfareCatalog.idOf(frag);
        if ("signinlottery".equals(wid) || "answerquestions".equals(wid)) {
            // v3.80：13:36 日志实证 —— App 从福利专区点开签到页会现场下发一张
            // 全新的 h5token（YZsidssolgc467…），页面用它登录成功、接口回
            // code=0000；而 13:35 任务用的还是内置表写死的旧票，jtH5Login
            // 直接回 500。以前 RealEntry.get() 靠 contains(frag) 遍历匹配，
            // 可能命中另一条路由的旧记录把新票顶掉。
            // 现在统一走 wisdomEntry：票按学到时间取最新、路由按本次任务重建
            // （? 和 hash 两处都带票，和内置表同形态，13:35 已实证能正常加载），
            // 这样最新学到的是哪条路由都不会进错页。
            String route = "answerquestions".equals(wid) ? "answerQuestions" : "signInLottery";
            String built = RealEntry.wisdomEntry(route);
            if (!built.isEmpty()) {
                return built;
            }
            return WelfareCatalog.builtUrl(frag);
        }
        // 1. 现场学到的真实入口。
        String learned = RealEntry.get(frag);
        if (!learned.isEmpty()) {
            return learned;
        }
        // 2. 采集缓存，优先挑带渠道参数的那条。
        String hit = cachedEntry(ctx, frag);
        if (RealEntry.hasChannel(hit)) {
            return hit;
        }
        // 3. 内置表（签到入口已补真实抓到的渠道参数）。
        String built = WelfareCatalog.builtUrl(frag);
        if (RealEntry.hasChannel(built)) {
            return built;
        }
        // 4. 都没带参数：能用哪个用哪个，不编参数。
        if (!hit.isEmpty()) {
            return hit;
        }
        return built;
    }

    /** 采集缓存里按片段匹配；没有返回空串。 */
    private static String cachedEntry(Context ctx, String frag) {
        if (ctx == null) {
            return "";
        }
        try {
            String json = AccountScope.prefs(ctx, WELFARE_PREFS)
                    .getString(KEY_ENTRIES, "");
            if (json.isEmpty()) {
                return "";
            }
            JSONArray array = new JSONArray(json);
            String fallback = "";
            for (int i = 0; i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                String url = one == null ? "" : one.optString("u", "");
                if (!url.startsWith("http") || !url.contains(frag)) {
                    continue;
                }
                // 带渠道参数的优先：裸地址会被服务端判「访问渠道未开放」。
                if (RealEntry.hasChannel(url)) {
                    return url;
                }
                if (fallback.isEmpty()) {
                    fallback = url;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 采集结果在 App 进程里的落地位置，供 {@link WelfareCollect} 写入。 */
    static final String WELFARE_PREFS = "cmcc_hook_welfare";
    static final String KEY_ENTRIES = "entries";
    static final String KEY_ENTRIES_AT = "entries_at";

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
