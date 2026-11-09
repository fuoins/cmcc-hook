package com.cmcchook;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 和 App 的 SSO 换票据。
 *
 * 真实登录链路（11.9.1 抓 H5 页面首次进入还原，非猜测）：
 *
 *   福利页点活动 → qwhdsso/login?dlwmh=true&actUrl=<活动页>
 *        ↓ 登录页 JS 调 leadeon.getUserInfo() 拿 App 登录态
 *   POST /qwhdsso/appTokenLogin?sid=QWHDSSOD...   （sid 就是要下发的 token）
 *        ↓ 响应体（注意：没有任何 Set-Cookie）
 *   {"code":"SUCCESS","data":{"jwt":"...","url":"<活动页>?...&token=QWHDSSOD..."}}
 *        ↓ 页面把 data.jwt 存进 localStorage.QWHDSSOJWT，跳到 data.url
 *   活动页所有接口 /qwhdhub/api/... 只带 login-check:1 等通用头，
 *   服务端靠 Referer（= 带 token 的活动页 URL）里的 token 识别会话。
 *
 * 所以「会话」不是 Cookie，而是「活动页 URL 上的 token= + localStorage.QWHDSSOJWT」。
 * 早期版本死等一个根本不存在的 QWHD_SESSION_TOKEN Cookie，导致 code=SUCCESS
 * 换到票也被判成「没有会话」——这就是「拿不到签到会话」的真正原因。
 *
 * 模块的做法：直发 appTokenLogin 拿到 data.url 的 token（兜底用 sid，两者同值），
 * 用带 token 的活动页地址开页面，fetch 时 Referer 天然带 token，接口即认会话。
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

    /**
     * App 自己的 JSBridge 对象（newSdkInterface）。
     *
     * App 用 addJavascriptInterface 把它挂到自己的 WebView 上，所以只有它创建的 WebView
     * 才有 window.newSdkInterface。把这个对象留一份，就能重新注入到我们自己的离屏 WebView，
     * 让页面在后台也能走完 leadeon.getUserInfo → appTokenLogin 这套登录。
     */
    private static volatile WeakReference<Object> bridge = new WeakReference<>(null);
    private static volatile String bridgeName = "";
    private static volatile long bridgeAt;

    private static volatile String token = "";
    private static volatile String phone = "";
    private static volatile String province = "240";
    private static volatile String city = "0411";
    private static volatile String carrier = "002";
    private static volatile String version = APP_VERSION;
    private static volatile long infoAt;

    /**
     * App 原生 getUserInfo 的完整回包（含 JSID token）。
     *
     * <p>画布页的登录顺序是 cmcc.getUserInfo → userLoginV2(ticket=token)。
     * 这个回调只有 App 自己的 WebView 收得到；App 退到后台或被系统回收后，
     * JSSDKImpl 里那个 WebView 已经死了，回调就再也不回来 —— 页面等 5 秒，
     * 跳到 denglu?appLoginTimeout=true。把第一次拿到的回包留一份，
     * 后面直接由模块回给离屏页，登录就能继续往下走。
     */
    private static volatile String userJson = "";
    private static volatile long userJsonAt;

    private static volatile CountDownLatch waiting;
    private static volatile String rawResult = "";
    private static volatile String lastError = "";

    /**
     * 活动会话按活动隔离。
     *
     * 服务端下发的 token 里带着活动 id —— 日志里 {@code QWHDSSOD…DU1021122301H54wcsR…}
     * 中间的 1021122301 就是签到活动号。把签到换到的 token 拼到砸金蛋地址上，
     * 服务端一定判 GOTO_LOGIN。v2.4 之前只有一个全局 sessionUrl，
     * 所以三个活动里只有第一个能拿到有效会话，后两个全靠运气。
     */
    private static final java.util.Map<String, Session> SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * appTokenLogin 下发的 jwt，页面 JS 会存进 localStorage 的 QWHDSSOJWT。
     * 真实机制里服务端不下发任何 Cookie，会话就是「URL 上的 token + 这个 jwt」，
     * 离屏 WebView 必须自己把它灌进 localStorage，页面才能正常初始化。
     */
    private static volatile String ssoJwt = "";

    static String ssoJwt() {
        return ssoJwt;
    }

    private static final class Session {
        final String url;
        final String token;
        final String cookie;
        final long at;

        Session(String url, String token, String cookie, long at) {
            this.url = url;
            this.token = token;
            this.cookie = cookie == null ? "" : cookie;
            this.at = at;
        }
    }

    private SsoLogin() {
    }

    // ------------------------------------------------------------ 钩子入口

    /** WebViewHooks 在 addJavascriptInterface 里回调：记下带 SDK 的 WebView。 */
    static void onBridge(Object wv, String name) {
        onBridge(wv, null, name);
    }

    /** 同上，同时留下注入对象本身，供离屏 WebView 复用。 */
    static void onBridge(Object wv, Object bridgeObject, String name) {
        if (name == null) {
            return;
        }
        if (wv != null && (name.contains("newSdkInterface") || name.contains("SdkInterface"))) {
            // 我们自己往离屏页注入桥时也会走到这里。不能把「任务页」当成
            // App 的页面记下来：任务结束页一销毁，后面 fetchUserInfo 就对着
            // 一个死 WebView 注入，永远超时。
            if (wv != OffscreenWeb.current()) {
                webView = new WeakReference<>(wv);
                webViewAt = System.currentTimeMillis();
                HookLog.logcat("sso: bridge webview captured " + wv.getClass().getName());
            }
        }
        if (bridgeObject != null && hasGetUserInfo(bridgeObject)) {
            bridge = new WeakReference<>(bridgeObject);
            bridgeName = name;
            bridgeAt = System.currentTimeMillis();
            hookUserInfo(bridgeObject.getClass());
            HookLog.logcat("sso: js interface captured " + name
                    + " (" + bridgeObject.getClass().getName() + ")");
        }
    }

    private static boolean hasGetUserInfo(Object object) {
        try {
            for (java.lang.reflect.Method m : object.getClass().getMethods()) {
                if ("getUserInfo".equals(m.getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    static boolean hasSession(String actUrl) {
        Session session = SESSIONS.get(actKey(actUrl));
        if (session == null || session.token.isEmpty()) {
            return false;
        }
        // 真实机制（11.9.1 抓包实证）：appTokenLogin 不下发任何 Cookie，
        // 会话 = 活动页 URL 的 token= 参数 + localStorage.QWHDSSOJWT。
        // 早期版本在这里查 QWHD_SESSION_TOKEN Cookie，那是个根本不存在的东西，
        // 导致 code=SUCCESS 换到票也被判成「没有会话」。有效票据 = 有 token 且没过期。
        return System.currentTimeMillis() - session.at <= SESSION_TTL;
    }

    static String sessionUrl(String actUrl) {
        Session session = SESSIONS.get(actKey(actUrl));
        return session == null ? "" : session.url;
    }

    static void invalidate() {
        SESSIONS.clear();
        infoAt = 0L;
    }

    static String summary() {
        if (SESSIONS.isEmpty()) {
            return "活动会话: 无";
        }
        StringBuilder sb = new StringBuilder("活动会话: ");
        long now = System.currentTimeMillis();
        boolean first = true;
        for (java.util.Map.Entry<String, Session> e : SESSIONS.entrySet()) {
            if (!first) {
                sb.append("　");
            }
            first = false;
            sb.append(e.getKey()).append(' ')
                    .append((now - e.getValue().at) / 1000L).append("秒前");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 主流程

    /** 省份编码（App 登录信息里下发的，辽宁是 240）。 */
    static String provinceCode() {
        return province;
    }

    /** 城市编码，形如 0411。 */
    static String cityCode() {
        return city;
    }

    /** App 版本号，请求体里的 version 字段用它。 */
    static String appVersion() {
        return version;
    }

    /**
     * 换一份可用的活动会话。
     *
     * @param actUrl 活动页地址（不带 token 的原始入口）
     * @return 带 token 的活动页地址；null 表示换不到
     */
    static String ensure(String actUrl, StringBuilder detail) {
        String key = actKey(actUrl);
        Session cached = SESSIONS.get(key);
        if (cached != null && hasSession(actUrl)) {
            String url = withToken(actUrl, cached.token);
            detail.append("会话: 复用缓存 ").append(key).append(' ')
                    .append(mask(url)).append('\n');
            return url;
        }

        // 第一步：拿登录页，服务端如果认当前 Cookie 会直接 302 到带 token 的活动页。
        TaskNet.Resp page = TaskNet.getRaw(LOGIN_BASE + "/login?dlwmh=true&actUrl=" + enc(actUrl),
                actUrl);
        if (page.redirect() && page.location.contains("token=")) {
            detail.append("登录页: 302 到活动页（Cookie 已可用）\n");
            CookieStore.pinSession();
            CookieStore.flushToWebView();
            remember(page.location);
            return withToken(actUrl, tokenOf(page.location));
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
                return withToken(actUrl, tokenOf(direct));
            }
            detail.append("登录页: 没找到 sid\n");
            return null;
        }

        // 第二步：从 App 的 JSBridge 取登录态。
        if (!fetchUserInfo(detail)) {
            // 手上没有可用的 WebView（冷启动、App 在后台被回收）：
            // 用离屏 WebView 复用 App 的 JS 桥，让页面自己把登录走完。
            if (loginViaOffscreen(actUrl, detail)) {
                return actUrl;
            }
            return null;
        }

        // 第三步：换票据，服务端回 Set-Cookie + 带 token 的地址。
        // Referer 必须和真实页面一致（登录页地址），否则服务端可能不下发会话。
        String body = buildLoginBody();
        if (body == null) {
            detail.append("用户信息不完整，无法换票据\n");
            return null;
        }
        String loginRef = LOGIN_BASE + "/login?dlwmh=true&actUrl=" + enc(actUrl);
        TaskNet.Resp resp = TaskNet.postRaw(LOGIN_BASE + "/appTokenLogin?sid=" + sid, body,
                loginRef);
        if (resp.status < 0 || !resp.looksJson()) {
            detail.append("appTokenLogin 失败: ").append(resp.brief()).append('\n');
            return null;
        }
        try {
            JSONObject root = new JSONObject(resp.body);
            String code = root.optString("code");
            JSONObject data = root.optJSONObject("data");
            String url = data == null ? "" : data.optString("url", "");
            String jwt = data == null ? "" : data.optString("jwt", "");
            detail.append("appTokenLogin: ").append(code)
                    .append(" data.url=").append(url.isEmpty() ? "空" : "有")
                    .append(" jwt=").append(jwt.isEmpty() ? "空" : jwt.length() + "字符")
                    .append(" 活动=").append(key).append('\n');
            if (!resp.headers.isEmpty()) {
                detail.append("响应头:\n").append(resp.headers);
            }
            if (!"SUCCESS".equals(code)) {
                detail.append("失败: ").append(root.optString("msg")).append('\n');
                return null;
            }
            // 真实机制（11.9.1 抓 H5 页面首次进入实证）：服务端不下发任何
            // Set-Cookie，会话 = 活动页 URL 上的 token= + data.jwt（页面存进
            // localStorage.QWHDSSOJWT）。签到接口只带 login-check 等通用头，
            // 服务端靠 Referer（带 token 的活动页 URL）识别会话。
            // 早期版本死等一个根本不存在的 QWHD_SESSION_TOKEN Cookie，
            // 于是 code=SUCCESS 换到票也被判成「没会话」。
            // data.url 里的 token 与登录页的 sid 是同一个值，可兜底。
            String tk = tokenOf(url);
            String landed = url;
            if (!url.startsWith("http") || tk.isEmpty()) {
                tk = sid;
                landed = withToken(actUrl, tk);
            }
            if (tk.isEmpty()) {
                detail.append("SUCCESS 但既没 url 也没 sid，无法拼会话\n");
                return null;
            }
            if (!jwt.isEmpty()) {
                ssoJwt = jwt;
            }
            remember(landed);
            CookieStore.pinSession();
            CookieStore.flushToWebView();
            detail.append("换票成功 · 带 token 打开活动页 · ")
                    .append(mask(landed)).append('\n');
            return landed;
        } catch (Throwable e) {
            detail.append("appTokenLogin 解析失败: ").append(e).append('\n');
            return null;
        }
    }

    /** 记住某个活动服务端下发的 token。 */
    private static void remember(String urlWithToken) {
        String token = tokenOf(urlWithToken);
        if (token.isEmpty()) {
            return;
        }
        SESSIONS.put(actKey(urlWithToken), new Session(urlWithToken, token,
                CookieStore.get("10086.cn", "QWHD_SESSION_TOKEN"),
                System.currentTimeMillis()));
    }

    private static String tokenOf(String urlWithToken) {
        if (urlWithToken == null) {
            return "";
        }
        int idx = urlWithToken.indexOf("token=");
        if (idx < 0) {
            return "";
        }
        String tk = urlWithToken.substring(idx + 6);
        int amp = tk.indexOf('&');
        if (amp > 0) {
            tk = tk.substring(0, amp);
        }
        return tk;
    }

    /**
     * 活动标识：签到/砸蛋用路径里的活动号，鹅蛋用 actNo。
     * token 是按活动签发的，必须按活动分开缓存。
     */
    static String actKey(String url) {
        if (url == null || url.isEmpty()) {
            return "default";
        }
        Matcher matcher = Pattern.compile("/qwhdhub/[a-zA-Z]+/(\\d{6,})").matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher goose = Pattern.compile("actNo=([A-Za-z0-9]+)").matcher(url);
        if (goose.find()) {
            return "goose-" + goose.group(1);
        }
        return "default";
    }

    /** 把 token 拼到任意活动页地址上。 */
    private static String withToken(String actUrl, String tk) {
        if (actUrl == null || actUrl.isEmpty()) {
            return "";
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

    /**
     * 从桥回调脚本里抄下 getUserInfo 的完整 JSON。
     *
     * <p>脚本形如
     * {@code (window.cmcc||window.leadeon).callbackSuccess_getUserInfo({...})}，
     * 大括号里还有嵌套对象和字符串，按括号配对截，不能按最后一个右括号切。
     */
    static void onBridgeCallback(String js) {
        if (js == null || userJsonAt > 0 && System.currentTimeMillis() - userJsonAt < INFO_TTL) {
            return;
        }
        int at = js.indexOf("callbackSuccess_getUserInfo(");
        if (at < 0) {
            return;
        }
        int start = js.indexOf('{', at);
        if (start < 0) {
            return;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < js.length(); i++) {
            char c = js.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    rememberUserJson(js.substring(start, i + 1));
                    return;
                }
            }
        }
    }

    private static void rememberUserJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            String tk = obj.optString("token", "");
            String phoneNumber = obj.optString("phoneNumber", "");
            if (tk.isEmpty() || phoneNumber.isEmpty()) {
                return;
            }
            userJson = json;
            userJsonAt = System.currentTimeMillis();
            token = tk;
            phone = phoneNumber;
            province = pick(obj.optString("province", ""), province);
            city = pick(obj.optString("city", ""), city);
            carrier = pick(obj.optString("carrierOperator", ""), carrier);
            version = pick(obj.optString("version", ""), APP_VERSION);
            infoAt = System.currentTimeMillis();
            HookLog.logcat("sso: user info cached from bridge callback");
        } catch (Throwable ignored) {
        }
    }

    static boolean hasUserInfo() {
        return !userJson.isEmpty() && !token.isEmpty()
                && System.currentTimeMillis() - userJsonAt < INFO_TTL;
    }

    /** 拿给离屏页直接回包用；没有就返回空串。 */
    static String userInfoJson() {
        return hasUserInfo() ? userJson : "";
    }

    /**
     * 画布任务开始前先保证手上有一份用户信息。
     *
     * <p>优先用桥回调缓存；没有才去 App 的 WebView 里调一次 getUserInfo。
     * 这一步必须在开任务页之前做完：页面挂载后 5 秒内等不到回包就会跳登录页。
     */
    static boolean ensureUserInfo(StringBuilder detail) {
        if (detail == null) {
            detail = new StringBuilder();
        }
        if (hasUserInfo()) {
            detail.append("用户信息: 复用缓存\n");
            return true;
        }
        if (fetchUserInfo(detail)) {
            return true;
        }
        // 上一次记下的 WebView 可能是已经被销毁的任务页，清掉再试一次，
        // 这次会退到 App 当前正在显示的页面。
        clearDeadWebView();
        detail.append("用户信息: 换一个 WebView 再试\n");
        return fetchUserInfo(detail);
    }

    /**
     * 接管带 {@code __cmcc} 标记的 getUserInfo。
     *
     * <p>只拦我们垫片发出来的调用；App 自己页面发的 JSON 没有这个字段，
     * 原样走 native，不受影响。
     */
    static void hookUserInfo(Class<?> bridgeClass) {
        if (bridgeClass == null || !INFO_HOOKED.add(bridgeClass.getName())) {
            return;
        }
        try {
            for (java.lang.reflect.Method m : bridgeClass.getMethods()) {
                if (!"getUserInfo".equals(m.getName())
                        || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object arg = param.args[0];
                            if (!(arg instanceof String)
                                    || !((String) arg).contains("\"__cmcc\"")) {
                                return;
                            }
                            String json = userInfoJson();
                            Object page = OffscreenWeb.current();
                            if (json.isEmpty() || page == null) {
                                return;
                            }
                            String callback = "callbackSuccess_getUserInfo";
                            try {
                                callback = new JSONObject((String) arg)
                                        .optString("callbackSuccess", callback);
                            } catch (Throwable ignored) {
                            }
                            if (callback.isEmpty()) {
                                callback = "callbackSuccess_getUserInfo";
                            }
                            String js = "(function(){try{var d=" + json
                                    + ";var cb=" + JSONObject.quote(callback)
                                    + ";var o=window.cmcc||window.leadeon;"
                                    + "if(o&&typeof o[cb]==='function'){o[cb](d);return}"
                                    + "if(typeof window[cb]==='function'){window[cb](d)}"
                                    + "}catch(e){}})()";
                            // 不能在桥方法里等 evaluateJavascript 的结果：
                            // JS 线程正卡在这个同步桥上，脚本要等桥返回才执行。
                            JsInjector.postRaw(page, js);
                            HookLog.log("TASK-DIAG", "用户信息直答 · 已排队回包",
                                    "回调: " + callback
                                            + "\n说明: 不走 App 的 WebView，直接用缓存的 JSID");
                            param.setResult(null);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable e) {
            HookLog.logcat("hook getUserInfo failed: " + e);
        }
    }

    private static final java.util.Set<String> INFO_HOOKED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        // App 刚冷启动时 WebView 可能还停在 about:blank，等它挂上真实页面再调。
        for (int i = 0; i < 5; i++) {
            String current = urlOf(wv);
            if (current.startsWith("http")) {
                break;
            }
            sleep(1_000L);
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
                    // 记下的那个已经销毁，退到 App 当前正在显示的页面。
                    wv = PageAuto.webView();
                }
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

    private static volatile long offscreenFailAt;

    /**
     * 离屏登录：把 App 的 JS 接口重新注入到一个不挂窗口的 WebView，
     * 打开 SSO 登录页，让页面自己跑 leadeon.getUserInfo → appTokenLogin。
     *
     * 这条只为「换出 QWHD_SESSION_TOKEN」服务：Cookie 一到手，后面所有接口
     * 都能直连，不需要页面活着。冷启动后第一次自检走的就是这条路。
     */
    private static boolean loginViaOffscreen(String actUrl, StringBuilder detail) {
        if (System.currentTimeMillis() - offscreenFailAt < 5 * 60_000L) {
            detail.append("离屏登录: 5 分钟内刚失败过，跳过\n");
            return false;
        }
        Object api = bridge.get();
        if (api == null || System.currentTimeMillis() - bridgeAt > 30 * 60_000L) {
            detail.append("离屏登录: 没有捕获到 JS 接口\n");
            return false;
        }
        String url = LOGIN_BASE + "/login?dlwmh=true&actUrl=" + enc(actUrl);
        detail.append("离屏登录: 复用 ").append(bridgeName).append(" 打开登录页\n");
        Object wv = OffscreenWeb.open(url, api, bridgeName, 10_000L);
        if (wv == null) {
            detail.append("离屏登录: WebView 创建失败\n");
            return false;
        }
        // 页面加载完会自动调 getUserInfo，再走 appTokenLogin，最后跳到带 token
        // 的活动页；给足 15 秒。服务端不下发 Cookie，成功标志是落地 URL 带 token。
        for (int i = 0; i < 15; i++) {
            sleep(1_000L);
            String landed = urlOf(wv);
            if (landed != null && landed.contains("token=")) {
                String tk = tokenOf(landed);
                remember(landed);
                // 顺手把页面上算出来的 jwt 从 localStorage 读回来（若有）。
                readJwtFromWebView(wv);
                detail.append("离屏登录: OK, 落地页带 token=").append(mask(tk)).append('\n');
                return true;
            }
        }
        detail.append("离屏登录: 15 秒内没跳转到带 token 的活动页\n");
        offscreenFailAt = System.currentTimeMillis();
        return false;
    }

    /** 从离屏页面 localStorage 回读 QWHDSSOJWT（页面 JS 换票后存进去的）。 */
    private static void readJwtFromWebView(Object wv) {
        try {
            String v = JsInjector.evalValue(wv,
                    "(function(){try{return localStorage.getItem('QWHDSSOJWT')||''}catch(e){return ''}})()");
            if (v != null && v.length() > 16) {
                ssoJwt = v;
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean parseUserInfo(String raw, StringBuilder detail) {        try {
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
            userJson = raw;
            userJsonAt = System.currentTimeMillis();
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

    private static String urlOf(Object wv) {
        try {
            Object value = de.robv.android.xposed.XposedHelpers.callMethod(wv, "getUrl");
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String buildScript() {
        return "(function(){"
                + "var T='" + CONSOLE_PREFIX + "';"
                + "function out(d){try{console.log(T+(typeof d==='string'?d:JSON.stringify(d)))}catch(e){}}"
                + "try{"
                + "  var b=window." + bridgeVar() + ";"
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

    /** 注入时用的变量名：App 那边叫什么，JS 里就用什么。 */
    private static String bridgeVar() {
        String name = bridgeName;
        return name == null || name.isEmpty() ? "newSdkInterface" : name;
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

    /** 已登录手机号的掩码形式（139****1234），给界面显示账号用；没取到返回空串。 */
    static String maskedPhone() {
        String p = phone;
        if (p == null || p.isEmpty()) {
            return "";
        }
        return maskPhone(p);
    }

    /**
     * 任务页 → 被改绑的字段旧值，用于用完恢复。
     *
     * 必须恢复：改绑期间 App 自己那个 WebView 是收不到回调的，任务结束不还原，
     * 用户之后手动进活动页就会卡在登录。
     */
    private static final java.util.Map<Object, java.util.List<Object[]>> REBOUND =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 把 App JSBridge 里的 WebView 引用临时指向我们的离屏页。
     *
     * <p>只把同一个 JSSDKImpl 对象 addJavascriptInterface 到新 WebView 是不够的：
     * 它的 success/error 回调是「往构造时那个 WebView 里 evaluateJavascript」的，
     * 离屏页的 Promise 会一直挂着。现象很具体 ——
     * 页面停在「加载中 92% 登录中」，而 checkUID 请求其实已经发出去了（日志里有 200）。
     *
     * <p>所以这里用反射把它内部的 WebView 字段临时改指离屏页，任务结束再还原。
     * 找不到字段也不报错，只是回到原来的行为。
     */
    static boolean bindBridgeTo(Object target) {
        Object api = bridge.get();
        if (api == null || target == null) {
            return false;
        }
        java.util.List<Object[]> olds = new java.util.ArrayList<>();
        int n = 0;
        Class<?> c = api.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    // 静态字段多半是全局单例，改它会影响别的 WebView，不碰。
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Object old;
                    try {
                        old = XposedHelpers.getObjectField(api, f.getName());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (old == null) {
                        continue;
                    }
                    // 先挂回调钩子：WebViewEx 这类包装类走不到内核 WebView 的
                    // loadUrl，只能按它自己的方法名抓。只挑字段名/类型像 WebView 的，
                    // 免得给 activity、callback 这些对象白挂一遍。
                    String fn = f.getName().toLowerCase(java.util.Locale.US);
                    String tn = old.getClass().getName().toLowerCase(java.util.Locale.US);
                    if (fn.contains("webview") || tn.contains("webview")) {
                        hookMirror(old);
                    }
                    // 有的实现把 WebView 放在 WeakReference 里，只按字段类型找会漏。
                    Object inner = old;
                    boolean ref = false;
                    if (old instanceof java.lang.ref.Reference) {
                        inner = ((java.lang.ref.Reference<?>) old).get();
                        ref = true;
                    }
                    // 不能按类名 endsWith(".WebView") 判：App 实际用的是
                    // com.tencent.smtt.sdk.WebView$a（X5 的匿名子类），类名对不上，
                    // 于是每次都是「没有可改的 WebView 字段」。改成看能力：
                    // 有 loadUrl(String) 的就是 WebView，匿名子类也算。
                    if (!isWebView(inner)) {
                        continue;
                    }
                    if (inner == target) {
                        continue;
                    }
                    XposedHelpers.setObjectField(api, f.getName(),
                            ref ? new java.lang.ref.WeakReference<>(target) : target);
                    olds.add(new Object[]{f.getName(), old});
                    n++;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        if (n > 0) {
            REBOUND.put(target, olds);
            HookLog.log("TASK-DIAG", "登录桥改绑 · " + n + " 个 WebView 字段指向离屏页",
                    "桥: " + api.getClass().getSimpleName());
            return true;
        }
        HookLog.log("TASK-DIAG", "登录桥改绑 · 没有可改的 WebView 字段",
                bridgeShape(api));
        return false;
    }

    /**
     * 是不是一个 WebView。
     *
     * <p>按能力判：自带 loadUrl(String) 的才算。App 真实用的是
     * com.tencent.smtt.sdk.WebView$a 这种 X5 匿名子类，按类名匹配会全部漏掉。
     */
    /** 已经挂过回调钩子的类，避免每次绑定都重复 hook 同一个方法。 */
    private static final java.util.Set<String> MIRRORED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 把回调 WebView 的 loadUrl / evaluateJavascript 挂上转发钩子。
     *
     * <p>JSSDKImpl 里存的是 {@code mWebView: WebViewEx}，不是内核 WebView，
     * 按 WebView 判不出来，改绑也换不掉。但它执行回调总得走这两个方法，
     * 所以不问类型，直接按方法名挂：只要它把 {@code callbackSuccess_xxx}
     * 交出去，就复制一份到离屏页。
     */
    private static void hookMirror(Object holder) {
        if (holder == null) {
            return;
        }
        Class<?> c = holder.getClass();
        if (!MIRRORED.add(c.getName())) {
            return;
        }
        int n = 0;
        try {
            for (java.lang.reflect.Method m : c.getMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (types.length < 1 || types[0] != String.class) {
                    continue;
                }
                String name = m.getName();
                if (!"loadUrl".equals(name) && !"evaluateJavascript".equals(name)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object v = param.args[0];
                                if (v instanceof String) {
                                    SdkBridge.mirror((String) v, param.thisObject);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (n > 0) {
            HookLog.log("TASK-DIAG", "回调钩子 · " + c.getName() + " 挂了 " + n + " 个方法", "");
        }
    }

    private static boolean isWebView(Object object) {
        if (object == null) {
            return false;
        }
        try {
            for (java.lang.reflect.Method m : object.getClass().getMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if ("loadUrl".equals(m.getName()) && types.length == 1
                        && types[0] == String.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 改绑失败时把桥的内部结构写进日志。
     *
     * <p>只列字段名/类型和跟登录相关的方法名，不含任何用户数据；下次看日志就能
     * 直接知道回调到底存在哪个字段里，不用再猜一轮。
     */
    private static String bridgeShape(Object api) {
        StringBuilder sb = new StringBuilder("桥: ").append(api.getClass().getName());
        sb.append("\n--- 字段 ---");
        int n = 0;
        Class<?> c = api.getClass();
        while (c != null && c != Object.class && n < 24) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (n >= 24) {
                    break;
                }
                String value = "null";
                try {
                    Object v = XposedHelpers.getObjectField(api, f.getName());
                    value = v == null ? "null" : v.getClass().getSimpleName();
                } catch (Throwable ignored) {
                }
                sb.append("\n  ").append(f.getName()).append(": ")
                        .append(f.getType().getSimpleName()).append(" = ").append(value);
                n++;
            }
            c = c.getSuperclass();
        }
        sb.append("\n--- 方法 ---");
        int m = 0;
        for (java.lang.reflect.Method method : api.getClass().getMethods()) {
            String name = method.getName();
            if (m >= 20) {
                break;
            }
            if (!(name.toLowerCase(java.util.Locale.US).contains("uid")
                    || name.toLowerCase(java.util.Locale.US).contains("user")
                    || name.toLowerCase(java.util.Locale.US).contains("login")
                    || name.toLowerCase(java.util.Locale.US).contains("token")
                    || name.toLowerCase(java.util.Locale.US).contains("webview"))) {
                continue;
            }
            sb.append("\n  ").append(name).append('(');
            Class<?>[] ps = method.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ps[i].getSimpleName());
            }
            sb.append("): ").append(method.getReturnType().getSimpleName());
            m++;
        }
        return sb.toString();
    }

    /** 任务结束恢复 JSBridge 原来的 WebView，别影响用户自己进页面。 */    static void unbindBridgeFrom(Object target) {
        java.util.List<Object[]> olds = REBOUND.remove(target);
        if (olds == null || olds.isEmpty()) {
            return;
        }
        Object api = bridge.get();
        if (api == null) {
            return;
        }
        int n = 0;
        for (Object[] pair : olds) {
            try {
                XposedHelpers.setObjectField(api, (String) pair[0], pair[1]);
                n++;
            } catch (Throwable ignored) {
            }
        }
        HookLog.logcat("sso: bridge rebound back " + n);
    }

    /** 给离屏活动页用的：App 注入的那个 JSBridge 对象和它的名字。 */
    static Object bridgeObject() {
        Object api = bridge.get();
        if (api == null || System.currentTimeMillis() - bridgeAt > 30 * 60_000L) {
            return null;
        }
        return api;
    }

    static String bridgeNameOf() {
        return bridgeName == null ? "" : bridgeName;
    }

    static void reset() {
        clearDeadWebView();
        SESSIONS.clear();
        infoAt = 0L;
        token = "";
        phone = "";
        waiting = null;
    }
}
