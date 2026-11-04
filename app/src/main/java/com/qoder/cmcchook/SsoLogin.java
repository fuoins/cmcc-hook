package com.qoder.cmcchook;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 和 App 的 SSO 换票据。
 *
 * v2.0 之前这里走的是「GET /qwhdsso/login，指望服务端 302 下发 Cookie」，
 * 但只要 WebView 里没有登录态，服务端就一定 302 回登录页 —— 纯 HTTP 拿不到任何东西。
 * 日志里 markstatus 返回 GOTO_LOGIN 就是这么来的。
 *
 * 真正的登录链路（从 qwhdsso/login 页面的 JS 还原）是：
 *
 *   leadeon.getUserInfo()            ← App 原生 JSBridge，返回手机号 + SSO token
 *        ↓
 *   POST /qwhdsso/appTokenLogin?sid=xxx
 *        body: {token, phoneNumber的hex, provinceCode, cityCode, ...}
 *        ↓
 *   Set-Cookie: QWHD_SESSION_TOKEN=...   +  data.url（带 token 的活动页地址）
 *
 * 关键点：getUserInfo 是 addJavascriptInterface 注入的 newSdkInterface 对象上的方法，
 * 模块跑在 App 进程里，可以直接在 App 的 WebView 里执行一段 JS 把它调出来，
 * 全程不需要打开任何页面 —— 这就是「后台过任务」缺的那一环。
 *
 * 拿到 QWHD_SESSION_TOKEN 后，签到和砸金蛋的所有接口都能直连，
 * 和浏览器里点出来的会话完全等价。
 */
final class SsoLogin {

    private static final String LOGIN_BASE = "https://wx.10086.cn/qwhdsso";
    private static final String CONSOLE_PREFIX = "CMCCUI::";
    private static final String APP_VERSION = "11.9.1";

    /** JS 回调最多等这么久，超时说明页面没起 JSBridge。 */
    private static final long CALL_TIMEOUT = 8_000L;

    /** getUserInfo 结果缓存 15 分钟（App 自己的登录态比活动会话长）。 */
    private static final long INFO_TTL = 15 * 60_000L;

    /** 活动会话缓存 18 分钟，服务端一般 30 分钟过期，留点余量。 */
    private static final long SESSION_TTL = 18 * 60_000L;

    private static final Pattern SID = Pattern.compile("appTokenLogin\\?sid=([A-Za-z0-9]+)");

    private static volatile WeakReference<Object> webView = new WeakReference<>(null);
    private static volatile long webViewAt;

    private static volatile String token = "";
    private static volatile String phone = "";
    private static volatile String province = "240";
    private static volatile String city = "0411";
    private static volatile String carrier = "002";
    private static volatile String version = APP_VERSION;
    private static volatile long infoAt;

    private static volatile CountDownLatch waiting;
    private static volatile String rawResult = "";
    private static volatile String lastError = "";

    private static volatile String sessionUrl = "";
    private static volatile String sessionToken = "";
    private static volatile long sessionAt;

    private SsoLogin() {
    }

    // ------------------------------------------------------------ 钩子入口

    /** WebViewHooks 在 addJavascriptInterface 里回调：记下带 SDK 的 WebView。 */
    static void onBridge(Object wv, String name) {
        if (wv == null || name == null) {
            return;
        }
        if (!name.contains("newSdkInterface") && !name.contains("SdkInterface")) {
            return;
        }
        webView = new WeakReference<>(wv);
        webViewAt = System.currentTimeMillis();
        HookLog.logcat("sso: bridge webview captured " + wv.getClass().getName());
    }

    /** WebViewHooks 在 onConsoleMessage 里回调；返回 true 表示这条是内部的，不用记日志。 */
    static boolean onConsole(String text) {
        if (text == null || !text.contains(CONSOLE_PREFIX)) {
            return false;
        }
        int at = text.indexOf(CONSOLE_PREFIX);
        String payload = text.substring(at + CONSOLE_PREFIX.length()).trim();
        CountDownLatch latch = waiting;
        if (latch == null) {
            return true;
        }
        if (payload.contains("\"err\"") && payload.length() < 200) {
            lastError = payload;
        } else {
            rawResult = payload;
        }
        latch.countDown();
        return true;
    }

    // ------------------------------------------------------------ 查询

    static boolean hasSession() {
        if (sessionAt == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - sessionAt > SESSION_TTL) {
            return false;
        }
        String cookie = CookieStore.get("QWHD_SESSION_TOKEN");
        return cookie != null && cookie.length() > 8;
    }

    static String sessionUrl() {
        return sessionUrl;
    }

    static void invalidate() {
        sessionUrl = "";
        sessionAt = 0L;
        infoAt = 0L;
    }

    static String summary() {
        if (!hasSession()) {
            return "活动会话: 无";
        }
        long age = (System.currentTimeMillis() - sessionAt) / 1000L;
        return "活动会话: 有效（" + age + " 秒前换取）";
    }

    // ------------------------------------------------------------ 主流程

    /**
     * 换一份可用的活动会话。
     *
     * @param actUrl 活动页地址（不带 token 的原始入口）
     * @return 带 token 的活动页地址；null 表示换不到
     */
    static String ensure(String actUrl, StringBuilder detail) {
        if (hasSession()) {
            String url = withToken(actUrl, sessionToken);
            detail.append("会话: 复用缓存 ").append(mask(url)).append('\n');
            return url;
        }

        // 第一步：拿登录页，服务端如果认当前 Cookie 会直接 302 到带 token 的活动页。
        TaskNet.Resp page = TaskNet.get(LOGIN_BASE + "/login?dlwmh=true&actUrl=" + enc(actUrl),
                actUrl);
        if (page.redirect() && page.location.contains("token=")) {
            detail.append("登录页: 302 到活动页（Cookie 已可用）\n");
            remember(page.location);
            return withToken(actUrl, sessionToken);
        }
        if (page.status < 0 || page.body.isEmpty()) {
            detail.append("登录页请求失败: ").append(page.brief()).append('\n');
            return null;
        }

        String sid = "";
        Matcher matcher = SID.matcher(page.body);
        if (matcher.find()) {
            sid = matcher.group(1);
            detail.append("sid: ").append(sid).append('\n');
        } else {
            String direct = extractActUrl(page.body);
            if (direct != null) {
                detail.append("登录页: 直接给出活动地址\n");
                remember(direct);
                return withToken(actUrl, sessionToken);
            }
            detail.append("登录页: 没找到 sid\n");
            return null;
        }

        // 第二步：从 App 的 JSBridge 取登录态。
        if (!fetchUserInfo(detail)) {
            return null;
        }

        // 第三步：换票据，服务端回 Set-Cookie + 带 token 的地址。
        String body = buildLoginBody();
        if (body == null) {
            detail.append("用户信息不完整，无法换票据\n");
            return null;
        }
        TaskNet.Resp resp = TaskNet.post(LOGIN_BASE + "/appTokenLogin?sid=" + sid, body, actUrl);
        if (resp.status < 0 || !resp.looksJson()) {
            detail.append("appTokenLogin 失败: ").append(resp.brief()).append('\n');
            return null;
        }
        try {
            JSONObject root = new JSONObject(resp.body);
            String code = root.optString("code");
            if (!"SUCCESS".equals(code)) {
                detail.append("appTokenLogin: ").append(code).append(' ')
                        .append(root.optString("msg")).append('\n');
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            String url = data == null ? "" : data.optString("url", "");
            String cookie = CookieStore.get("QWHD_SESSION_TOKEN");
            detail.append("appTokenLogin: OK, cookie=")
                    .append(cookie == null ? "空" : mask(cookie)).append('\n');
            if (url.isEmpty() || cookie == null || cookie.length() < 8) {
                return null;
            }
            remember(url);
            return withToken(actUrl, sessionToken);
        } catch (Throwable e) {
            detail.append("appTokenLogin 解析失败: ").append(e).append('\n');
            return null;
        }
    }

    /** 记住服务端下发的 token，之后每个活动各自拼自己的地址。 */
    private static void remember(String urlWithToken) {
        sessionUrl = urlWithToken;
        sessionAt = System.currentTimeMillis();
        int idx = urlWithToken.indexOf("token=");
        if (idx >= 0) {
            String tk = urlWithToken.substring(idx + 6);
            int amp = tk.indexOf('&');
            if (amp > 0) {
                tk = tk.substring(0, amp);
            }
            sessionToken = tk;
        }
    }

    /** 把 token 拼到任意活动页地址上。 */
    private static String withToken(String actUrl, String tk) {
        if (actUrl == null || actUrl.isEmpty()) {
            return sessionUrl;
        }
        if (tk == null || tk.isEmpty() || actUrl.contains("token=")) {
            return actUrl;
        }
        return actUrl + (actUrl.indexOf('?') >= 0 ? "&" : "?") + "token=" + tk;
    }

    private static String extractActUrl(String html) {
        Matcher matcher = Pattern.compile("https://wx\\.10086\\.cn/qwhdhub/[^\"'\\\\s]+token=[A-Za-z0-9]+")
                .matcher(html);
        return matcher.find() ? matcher.group() : null;
    }

    private static String buildLoginBody() {
        if (token.isEmpty() || phone.isEmpty()) {
            return null;
        }
        String checkId;
        try {
            checkId = Long.toHexString(Long.parseLong(phone));
        } catch (Throwable e) {
            checkId = phone;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("jwtToken", JSONObject.NULL);
            body.put("token", token);
            body.put("provinceCode", province);
            body.put("cityCode", city);
            body.put("userCheckId", checkId);
            body.put("carrierOperator", carrier);
            body.put("appVersionCode", version);
            body.put("took", 600);
            return body.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ getUserInfo

    /** 在 App 的 WebView 里执行 JS 调 JSBridge，等 console 回传。 */
    static boolean fetchUserInfo(StringBuilder detail) {
        if (!token.isEmpty() && System.currentTimeMillis() - infoAt < INFO_TTL) {
            detail.append("用户信息: 复用缓存\n");
            return true;
        }
        Object wv = webView.get();
        if (wv == null || System.currentTimeMillis() - webViewAt > 30 * 60_000L) {
            // 兜底：App 当前正在显示的那个 WebView 一样有注入对象。
            wv = PageAuto.webView();
        }
        if (wv == null) {
            lastError = "没有可用的 WebView（App 还没加载过 H5）";
            detail.append("用户信息: ").append(lastError).append('\n');
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            rawResult = "";
            lastError = "";
            CountDownLatch latch = new CountDownLatch(1);
            waiting = latch;
            boolean sent = JsInjector.evaluateRaw(wv, buildScript());
            if (!sent) {
                detail.append("用户信息: JS 注入失败，重试\n");
                clearDeadWebView();
                wv = webView.get();
                if (wv == null) {
                    break;
                }
                continue;
            }
            try {
                latch.await(CALL_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            waiting = null;
            String raw = rawResult;
            if (!raw.isEmpty()) {
                if (parseUserInfo(raw, detail)) {
                    return true;
                }
                return false;
            }
            detail.append("用户信息: 回调超时").append(attempt == 0 ? "，重试" : "").append('\n');
        }
        if (!lastError.isEmpty()) {
            detail.append("用户信息: ").append(lastError).append('\n');
        }
        return false;
    }

    private static boolean parseUserInfo(String raw, StringBuilder detail) {
        try {
            JSONObject json = new JSONObject(raw);
            String tk = json.optString("token", "");
            String phoneNumber = json.optString("phoneNumber", "");
            if (tk.isEmpty() || phoneNumber.isEmpty()) {
                lastError = "getUserInfo 缺少 token/phoneNumber";
                detail.append("用户信息: ").append(lastError).append('\n');
                return false;
            }
            token = tk;
            phone = phoneNumber;
            province = pick(json.optString("province", ""), province);
            city = pick(json.optString("city", ""), city);
            carrier = pick(json.optString("carrierOperator", ""), carrier);
            version = pick(json.optString("version", ""), APP_VERSION);
            infoAt = System.currentTimeMillis();
            detail.append("用户信息: ").append(maskPhone(phone))
                    .append(" 省 ").append(province)
                    .append(" 市 ").append(city).append('\n');
            return true;
        } catch (Throwable e) {
            lastError = "getUserInfo 解析失败: " + e;
            detail.append("用户信息: ").append(lastError).append('\n');
            return false;
        }
    }

    private static String pick(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static void clearDeadWebView() {
        webView = new WeakReference<>(null);
        webViewAt = 0L;
    }

    private static String buildScript() {
        return "(function(){"
                + "var T='" + CONSOLE_PREFIX + "';"
                + "function out(d){try{console.log(T+(typeof d==='string'?d:JSON.stringify(d)))}catch(e){}}"
                + "try{"
                + "  var b=window.newSdkInterface;"
                + "  if(!b){out('{\"err\":\"no_bridge\"}');return;}"
                + "  var sdk=window.leadeon||window.cmcc||(window.leadeon=window.cmcc={});"
                + "  sdk.callbackSuccess_getUserInfo=function(d){out(d)};"
                + "  sdk.callbackError_getUserInfo=function(d){out('{\"err\":\"cb\"}')};"
                + "  b.getUserInfo(JSON.stringify({businessName:'getUserInfo',"
                + "callbackSuccess:'callbackSuccess_getUserInfo',"
                + "callbackError:'callbackError_getUserInfo',debug:false}));"
                + "}catch(e){out('{\"err\":\"'+e+'\"}')}"
                + "})();";
    }

    // ------------------------------------------------------------ 工具

    private static String enc(String text) {
        return TaskApi.encode(text);
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "空";
        }
        int idx = value.indexOf("token=");
        if (idx >= 0) {
            String tk = value.substring(idx + 6);
            return "…token=" + mask(tk);
        }
        return value.length() <= 10 ? value : value.substring(0, 10) + "…";
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    static void reset() {
        clearDeadWebView();
        sessionUrl = "";
        sessionToken = "";
        sessionAt = 0L;
        infoAt = 0L;
        token = "";
        phone = "";
        waiting = null;
    }
}
