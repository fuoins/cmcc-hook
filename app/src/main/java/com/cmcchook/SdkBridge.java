package com.cmcchook;

/**
 * H5 登录桥的回调转发。
 *
 * <p>App 的 newSdkInterface（JSSDKImpl）是「往自己构造时那个 WebView 里执行回调 JS」的。
 * 我们把同一个对象注入到离屏页后，页面的 checkUID 能发出去、客户端也回包了，
 * 但回调 JS 落在 App 那个 WebView 上，离屏页的 Promise 永远挂着 ——
 * 现象就是 7 日打卡卡在「加载中 92% 登录中」，画布页组件一直不渲染。
 *
 * <p>改绑 JSSDKImpl 内部的 WebView 字段是第一种解法（SsoLogin.bindBridgeTo），
 * 但那是猜它的内部结构。这里再加一条与结构无关的路：只要看到任何 WebView 在执行
 * {@code callbackSuccess_xxx / callbackError_xxx} 这类回调 JS，就把它原样转发一份到
 * 当前离屏页。改绑成功时两边都收到，改绑失败时靠这一条也能把登录走完。
 *
 * <p>只转发带 SDK 回调名的脚本，普通业务 JS 一律不碰；转发目标必须是离屏页，
 * 避免把离屏页自己的脚本又弹回去造成死循环。
 */
final class SdkBridge {

    /** SDK 回调脚本的特征串：cmcc.js 里 callbackSuccess_/callbackError_ 是固定前缀。 */
    private static final String[] MARKS = {
            "callbackSuccess_", "callbackError_",
            "callbackChangeSuccess_", "callbackChangeError_"
    };

    /** 防重入：转发过程中目标页自己也会触发 evaluateJavascript 钩子。 */
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    /**
     * 页面侧垫片，必须在页面脚本之前注入。
     *
     * <p>coc 系页面判断登录的第一行是 {@code /leadeon/.test(navigator.userAgent)}，
     * 然后走 {@code window.cmcc}；拿不到就动态加载 cmcc.js，10 秒超时后再判未登录。
     * 离屏页里这一步经常等不到（脚本被拦或时序太晚），页面就永远停在「加载中 92%」。
     *
     * <p>这段按 cmcc.js 的原协议（businessName / callbackSuccess_ / callbackError_ /
     * newSdkInterface[方法名]）先把对象立起来，页面不用等外部脚本就能直接调桥。
     * 回调同时挂在 cmcc 和 leadeon 上：native 端用哪个名字回都收得到。
     */
    private static final String SHIM_JS = "(function(){try{"
            + "if(window.__cmccSdkShim)return;window.__cmccSdkShim=1;"
            // 上一轮登录失败会留下这个标记，画布页看到它就直接跳过 getUserInfo
            // 去拉起登录页（appLoginTimeout=true）。任务页每次都要重新走登录，先清掉。
            + "try{localStorage.removeItem('appLoginTimeout')}catch(x){}"
            + "function isFn(f){return typeof f==='function'}"
            + "var NAMES=['checkUID','getUserInfo','getLoginInfo','userStatus',"
            + "'checkSessionIsvalid','showLogin','getCatchInfo','getLocation',"
            + "'permissionIsEnable','showMsgSubscribeDialog','shareMessage','newWebview',"
            + "'goNativePage','setTitle','closeWindow','showNavCustomBtn','hiddenShareButton',"
            + "'showTitleBarRightIcons','openOtherAPP','copyToClipboard','getIDMPLoginToken',"
            + "'getDeviceMultiSimInfo','getNetInfo','getRiskLevel','getStatusBarHeight'];"
            + "var box=window.cmcc||{};var ld=window.leadeon||{};"
            + "function reg(t,st,s,e){if(!t)return;"
            + "var ok=function(d){var j=d;"
            + "if(typeof j==='string'){try{j=JSON.parse(j)}catch(x){}}try{s(j)}catch(x){}};"
            + "var no=function(d){var j=d;"
            + "if(typeof j==='string'){try{j=JSON.parse(j)}catch(x){}}try{e(j)}catch(x){}};"
            + "t['callbackChangeSuccess_'+st]=s;t['callbackChangeError_'+st]=e;"
            + "t['callbackSuccess_'+st]=ok;t['callbackError_'+st]=no;"
            + "window['callbackSuccess_'+st]=ok;window['callbackError_'+st]=no;}"
            + "function callNative(os,st){os=os||{};os.businessName=st;"
            + "os.callbackSuccess='callbackSuccess_'+st;os.callbackError='callbackError_'+st;"
            // 只给 getUserInfo 打标：native 侧看到 __cmcc 才由模块直接回包，
            // App 自己页面调过来的不带标记，一律走原逻辑，不会被截胡。
            + "if(st==='getUserInfo'){os.__cmcc=1;}"
            + "if(typeof os.debug==='undefined')os.debug=false;"
            + "if(!isFn(os.success))os.success=function(){};"
            + "if(!isFn(os.error))os.error=function(){};"
            + "reg(box,st,os.success,os.error);reg(ld,st,os.success,os.error);"
            + "delete os.success;delete os.error;"
            + "var nat=window.newSdkInterface;"
            + "if(nat&&isFn(nat[st])){try{nat[st](JSON.stringify(os));return true}catch(x){}}"
            + "return false}"
            + "for(var i=0;i<NAMES.length;i++){(function(nm){"
            + "if(!isFn(box[nm]))box[nm]=function(o){return callNative(o,nm)};"
            + "if(!isFn(ld[nm]))ld[nm]=function(o){return callNative(o,nm)};"
            + "})(NAMES[i])}"
            + "window.cmcc=box;window.leadeon=ld;"
            // 画布接口要带登录后加密的 phone 头，模块自己发的请求没有就被 500。
            // 垫片在页面脚本之前跑，这里顺手把页面用过的 phone 记下来给任务脚本用。
            + "try{var _sr=XMLHttpRequest.prototype.setRequestHeader;"
            + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
            + "try{if(String(k).toLowerCase()==='phone')window.__cmccPhone=String(v)}catch(x){}"
            + "return _sr.apply(this,arguments)};}catch(x){}"
            // 页面自己的 cmcc.js 会在垫片之后加载并覆盖 getUserInfo。它拿的是
            // App 那个 JSSDKImpl，回调落在 App 的 WebView 上；离屏页等不到，
            // 画布页 5 秒后就会跳 denglu?appLoginTimeout=true。
            // 页面加载完成后再把 getUserInfo 换回垫片（带 __cmcc 标记），
            // 由模块用缓存的 JSID 直接回包。其它方法不动。
            + "window.__cmccPatchUserInfo=function(){try{"
            + "var f=function(o){return callNative(o||{},'getUserInfo')};"
            + "if(window.cmcc)window.cmcc.getUserInfo=f;"
            + "if(window.leadeon)window.leadeon.getUserInfo=f;"
            + "}catch(e){}};"
            + "}catch(e){}})();";

    private SdkBridge() {
    }

    /** 页面加载完成后把 getUserInfo 换回垫片版本，只改这一个方法。 */
    private static final String PATCH_JS =
            "(function(){try{if(typeof window.__cmccPatchUserInfo==='function'){"
                    + "window.__cmccPatchUserInfo();return 'ok'}return 'no'}catch(e){return 'err'}})()";

    /** 已经补过的页面，避免 onPageFinished + postDelayed 重复刷日志。 */
    private static volatile java.lang.ref.WeakReference<Object> patchedPage =
            new java.lang.ref.WeakReference<>(null);

    static void patchUserInfo(Object webView) {
        if (webView == null || webView != OffscreenWeb.current()) {
            return;
        }
        if (patchedPage.get() == webView) {
            return;
        }
        // 这里可能在主线程（onPageFinished）上调，不能用会等回调的 evalValue：
        // 回调也在主线程，主线程一等就是 5 秒，页面直接卡死。
        boolean ok = JsInjector.evaluateRaw(webView, PATCH_JS);
        if (ok) {
            patchedPage = new java.lang.ref.WeakReference<>(webView);
        }
        HookLog.log("TASK-DIAG", "用户信息补丁 · " + (ok ? "getUserInfo 已接管" : "下发失败"),
                "页面自己的 cmcc.js 会覆盖垫片，这里只把 getUserInfo 换回来");
    }

    /**
     * 在页面脚本跑起来之前把垫片塞进去。
     *
     * <p>只对离屏页做：App 自己的页面有真的 cmcc.js，不能被垫片顶掉。
     */
    static void injectShim(Object webView) {
        if (webView == null || webView != OffscreenWeb.current()) {
            return;
        }
        boolean ok = JsInjector.evaluateRaw(webView, SHIM_JS);
        HookLog.log("TASK-DIAG", "登录桥垫片 · " + (ok ? "已提前注入" : "注入失败"),
                "页面脚本执行前立起 window.cmcc");
    }

    /**
     * 判断一段 JS 是不是 SDK 回调，是就转发给离屏页。
     *
     * @param js   即将在 from 这个 WebView 里执行的脚本，可能是 javascript: 伪协议
     * @param from 执行它的 WebView
     */
    static void mirror(String js, Object from) {
        if (js == null || from == null) {
            return;
        }
        if (Boolean.TRUE.equals(BUSY.get())) {
            return;
        }
        String body = js.startsWith("javascript:") ? js.substring(11) : js;
        if (!looksLikeCallback(body)) {
            return;
        }
        // App 自己的 getUserInfo 回包里有 JSID token，先抄一份。
        // 后面画布页再要用户信息时，就算 App 的 WebView 已经回收，
        // 模块也能用这份缓存直接回包，不用再看 native 脸色。
        SsoLogin.onBridgeCallback(body);
        Object target = OffscreenWeb.current();
        if (target == null || target == from) {
            return;
        }
        BUSY.set(Boolean.TRUE);
        try {
            boolean ok = JsInjector.evaluateRaw(target, body);
            HookLog.log("TASK-DIAG", "登录回调转发 · " + (ok ? "已转投离屏页" : "转投失败"),
                    "脚本: " + brief(body));
        } catch (Throwable e) {
            HookLog.logcat("sdk callback mirror failed: " + e);
        } finally {
            BUSY.remove();
        }
    }

    private static boolean looksLikeCallback(String js) {
        if (js == null || js.isEmpty() || js.length() > 20_000) {
            return false;
        }
        for (String mark : MARKS) {
            if (js.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    private static String brief(String value) {
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
