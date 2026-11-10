package com.cmcchook;

/**
 * 活动页的「现场」。
 *
 * <p>同一个活动里，页面和接口必须同源：签到/砸金蛋的接口在 wx.10086.cn，
 * 鹅蛋的接口在 lyh.lncmcc.com，而且服务端会认 Referer 和页面自己种的 Cookie。
 * 所以每个活动开跑前先把这个域的页面在离屏 WebView 里打开，任务全程借它的上下文发请求。
 *
 * <p>任务串行执行，同一时刻只有一个「现场」，用完就销毁。
 */
final class WebSession {

    private static volatile WebPage current;
    private static volatile String currentKey = "";
    private static volatile boolean suppressAuto;
    /**
     * 正在跑任务的现场计数。
     *
     * <p>v3.73：心跳每 15 秒做一次「账号切换」检测，一旦判成切号就 {@link #end()}
     * 销毁页面。可 UID 会漂移（同一个号在 11:01:52 是 mhrze7e12968、11:01:54 变成
     * mhrz0ad1feed），心跳正好在任务跑到一半时把页面干掉 —— 11:02 那次签到就是这样，
     * 脚本之后 20 秒读到的 body 一直是空串，最后报「页面没进对」。
     * 任务期间只记账、不销毁，等任务收尾再说。
     */
    private static volatile int held;

    private WebSession() {
    }

    static void hold(boolean on) {
        int v = held + (on ? 1 : -1);
        held = v < 0 ? 0 : v;
    }

    static boolean held() {
        return held > 0;
    }

    /**
     * 打开活动页。
     *
     * @param key 活动标识，只用于日志
     * @param url 活动页地址
     * @return 页面可用返回 true；false 表示退回老的直连方式
     */
    static boolean begin(String key, String url) {
        return begin(key, url, 30_000L);
    }

    static boolean begin(String key, String url, long timeoutMs) {
        if (current != null && key.equals(currentKey)) {
            return current.ok();
        }
        long start = System.currentTimeMillis();
        // v4.0.9：先走 App 官方真页 —— 页面、会话、容器初始化全都是 App 自己的，
        // 模块只换了个「在哪执行」。真页拿不到才退回离屏，保证不比以前差。
        WebPage real = tryReal(key, url, timeoutMs);
        if (real != null) {
            boolean ok = settle(key, real, start);
            if (ok) {
                HookLog.logcat("session real ok: " + key + " · " + real.href());
                return true;
            }
            HookLog.logcat("real page rejected, fallback offscreen: " + key);
        }
        end();
        suppressAuto = true;
        WebPage page = WebPage.open(url, timeoutMs, hostsFor(key));
        suppressAuto = false;
        return settle(key, page, start);
    }

    /**
     * 用 App 自己的 H5 容器打开目标页。
     *
     * <p>真页优先是 v4.0.9 的核心改动：离屏页缺 App 容器的初始化流程，
     * 券 Vue 不 mount、余量模块停在 loadInit；同一个地址在真页里一切正常。
     */
    private static WebPage tryReal(String key, String url, long timeoutMs) {
        try {
            long budget = Math.min(Math.max(timeoutMs, 12_000L), 28_000L);
            WebPage page = RealPage.open(url, RealPage.pathKey(url), budget);
            if (page != null) {
                HookLog.logcat("session real page: " + key + " -> " + page.href());
            }
            return page;
        } catch (Throwable e) {
            HookLog.logcat("session real page failed: " + e);
            return null;
        }
    }

    /**
     * 快速模式：同域活动复用同一个页面，只导航不重开。
     *
     * <p>签到、砸金蛋、DIY 活动全在 wx.10086.cn。以前每个活动都要重建
     * WebView、重新加载、等渲染，串行几十秒；现在同域只开一次页，之后
     * 切换活动就是一次 navigateFast（文档建立即可），毫秒级换场。
     */
    static boolean beginFast(String key, String url, long timeoutMs) {
        long realStart = System.currentTimeMillis();
        // v4.0.9：真页优先。签到/砸蛋/鹅蛋这些原本靠离屏页换票、换场，
        // 现在统一放到 App 官方活动页里执行；同一个任务会话内共用同一个真页。
        WebPage live = null;
        suppressAuto = true;
        try {
            live = tryReal(key, url, timeoutMs);
        } finally {
            suppressAuto = false;
        }
        if (live != null) {
            boolean ok = settle(key, live, realStart);
            if (ok) {
                HookLog.logcat("session real fast ok: " + key + " · " + live.href());
                return true;
            }
            HookLog.logcat("real fast page rejected, fallback offscreen: " + key);
        }
        suppressAuto = true;
        long start = System.currentTimeMillis();
        WebPage page = null;
        try {
            // 服务端不下发 Cookie：页面 JS 靠 localStorage.QWHDSSOJWT 判断已登录。
            // 换票拿到的 jwt 必须在文档加载一开始就灌进去，否则页面又跳
            // qwhdsso/login，现场就废了。
            String jwt = SsoLogin.ssoJwt();
            String early = jwt.isEmpty() ? null :
                    "(function(){try{localStorage.setItem('QWHDSSOJWT',"
                            + org.json.JSONObject.quote(jwt) + ")}catch(e){}})()";
            WebPage cur = current();
            if (cur != null && sameHost(cur.url(), url)) {
                // 同域复用：同一个页面换地址（Referer 跟着变），不重建。
                if (key.equals(currentKey) && cur.ok()) {
                    injectJwt(cur, early);
                    suppressAuto = false;
                    return true;
                }
                if (cur.navigateFast(url, timeoutMs)) {
                    page = cur;
                    currentKey = key;
                    HookLog.logcat("session fast reuse: " + key + " -> " + brief(url));
                }
            }
            if (page == null) {
                end();
                page = WebPage.openFast(url, timeoutMs, hostsFor(key), early);
            } else {
                injectJwt(page, early);
            }
        } finally {
            suppressAuto = false;
        }
        boolean ok = settle(key, page, start);
        // 页面已 complete、document/localStorage 确定可用，再补一次注入，
        // 兜住 onPageStarted 阶段 localStorage 尚未 commit 的情况。
        if (ok) {
            String jwt = SsoLogin.ssoJwt();
            if (!jwt.isEmpty()) {
                injectJwt(current(), "(function(){try{localStorage.setItem('QWHDSSOJWT',"
                        + org.json.JSONObject.quote(jwt) + ")}catch(e){}})()");
            }
        }
        return ok;
    }

    /** 页面已经开着时补一次 localStorage 注入（换票可能在开页之后才完成）。 */
    private static void injectJwt(WebPage page, String script) {
        if (page == null || script == null) {
            return;
        }
        try {
            page.eval(script, 2_000L);
        } catch (Throwable ignored) {
        }
    }

    private static boolean sameHost(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        try {
            return new java.net.URL(a).getHost().equals(new java.net.URL(b).getHost());
        } catch (Throwable e) {
            return false;
        }
    }

    private static String brief(String url) {
        return url == null ? "" : (url.length() <= 80 ? url : url.substring(0, 80) + "…");
    }

    /** 页面建立后的统一收尾：登录态检测、场景快照、挂到 current。 */
    private static boolean settle(String key, WebPage page, long start) {
        return settle(key, page, start, 0);
    }

    private static boolean settle(String key, WebPage page, long start, int depth) {
        if (page == null || page.raw() == null) {
            HookLog.log("TASK", "页面打开失败 · " + key, "");
            return false;
        }
        long cost = System.currentTimeMillis() - start;
        String text = "";
        String scene = "";
        try {
            text = page.eval("(function(){try{return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,120)}catch(e){return ''}})()", 4_000L);
        } catch (Throwable ignored) {
        }
        try {
            // 页面到底是个什么状态，一行看全：域对不对、骨架还是真页面、
            // Vue 挂没挂上、App 的 JS 桥在不在。缺哪样，日志里直接能看出来。
            scene = page.eval("(function(){try{"
                    + "var a=document.querySelector('#app')||document.querySelector('#root');"
                    + "return ["
                    + "'origin='+location.origin,"
                    + "'ready='+document.readyState,"
                    + "'title='+(document.title||'-'),"
                    + "'vue='+(a&&a.__vue__?'yes':'no'),"
                    + "'html='+(document.body?document.body.innerHTML.length:0),"
                    + "'bridge='+(typeof leadeon)+'/'+(typeof window.newSdkInterface)"
                    + "].join(' · ');}catch(e){return ''}})()", 4_000L);
        } catch (Throwable ignored) {
        }
        if (!page.ok()) {
            // 白屏就别拿它发请求了，否则接口会拿到一个空文档的上下文，
            // 返回的 HTML 报错比直连还难懂。关掉，让上层明确失败。
            HookLog.log("TASK", "页面白屏 · " + key + " · " + cost + "ms",
                    "URL: " + page.url() + "\n现场: " + scene + "\n首屏: " + text);
            page.close();
            return false;
        }
        String pageUrl = page.url();
        if (pageUrl != null && pageUrl.contains("qwhdsso/login")) {
            // 页面停在 SSO 登录页：这一次的现场不能用（登录页发不了活动接口），
            // 判本次执行失败即可。不再按天封杀——qwhdsso/login 同时也是正常
            // 换票流程的中转页，不代表登录态失效（v3.14.1 停用该机制）。
            // 下次点「执行任务」会立即重试，用户手动登录同样立即生效。
            HookLog.log("TASK", "页面停在登录页 · " + key + " · 本次放弃",
                    "URL: " + pageUrl + "\n"
                            + "这次开出来的页面停在 SSO 登录页，没法用来发活动接口，已关闭。\n"
                            + "大概率是这次换票的 token 没被页面接住。直接再点一次「执行任务」重试即可，"
                            + "不会锁定当天任务。");
            page.close();
            return false;
        }
        String title = "";
        try {
            title = page.title();
        } catch (Throwable ignored) {
        }
        boolean channelBlocked = (title != null && title.contains("渠道未开放"))
                || (text != null && text.contains("渠道未开放"))
                || (scene != null && scene.contains("channelNonsupport"));
        // 11-10 22:27 实证：砸蛋入口缺 A_C_CODE 时，wx 把页面 302 到
        // /qwhdhub/notice/limited?A_C_CODE=null，标题「访问受限」、正文 548 字节。
        // 旧判定不认这种页，脚本在空壳上跑完，任务报「完成」但一步都没做。
        // 先看便宜的线索（标题/首屏/scene 里的 html 长度），可疑了才让页面 eval 复核。
        boolean suspect = (title != null && (title.contains("受限") || title.contains("无法访问")))
                || (text != null && (text.contains("受限") || text.contains("无法访问")))
                || (scene != null && scene.matches(".*html=\\d{1,3}( ·|\\z).*"));
        boolean limited = !channelBlocked && suspect && RealPage.blocked(page);
        if ((channelBlocked || limited) && depth == 0) {
            // 先别急着判死：换一个带渠道参数的入口（学到的 / 真页登记表 /
            // 内置表 / 鹅蛋球赛的缓存入口）重开一次，多数情况能救回来。
            WebPage alt = RealPage.reopenBetter(pageUrl, key);
            if (alt != null && alt.raw() != null) {
                return settle(key, alt, start, depth + 1);
            }
        }
        if (channelBlocked || limited) {
            // 服务端把页面 302 到「访问渠道未开放 / 访问受限」：入口 URL 缺渠道参数。
            // 这时候页面同源能发请求，但接口一律返回错误 HTML，继续跑只会白跑一轮。
            // 明确判失败，让上层直接报「活动页没有打开」，日志一眼能定位。
            HookLog.log("TASK", (limited ? "页面访问受限" : "访问渠道未开放")
                            + " · " + key + " · 入口缺渠道参数",
                    "URL: " + pageUrl + "\n标题: " + title + "\n现场: " + scene + "\n"
                            + "活动页地址缺 A_C_CODE / touch_id / yx / channelId 等渠道参数，"
                            + "服务端把页面重定向到了受限页，接口只会返回错误 HTML。\n"
                            + "已尝试换成学到的带参入口重开，仍被拦。\n"
                            + "在中国移动 App 里手动点进一次这个活动，模块会自动记住带渠道参数的真实入口，"
                            + "之后再点「执行任务」即可；不会锁定当天任务。");
            page.close();
            return false;
        }
        current = page;
        currentKey = key;
        HookLog.log("TASK", "页面就绪 · " + key + " · " + cost + "ms",
                "URL: " + page.url() + "\n内核: " + page.kernel()
                        + "\n现场: " + scene
                        + "\n标题: " + page.title() + "\n首屏: " + text);
        return true;
    }

    /**
     * 这个活动页允许停留在哪些域。
     *
     * <p>三个活动都在 10086.cn / lncmcc.com 下，正常的路由、SSO 跳转都在这个范围里；
     * 真正要拦的是「下载客户端」那条链（dx 短链 → transferDownload），
     * 它在 WebPage 里单独拉黑。拦域只是兜底，别把正常登录跳转也拦掉。
     */
    private static String[] hostsFor(String key) {
        return new String[]{"10086.cn", "lncmcc.com"};
    }

    /** 当前现场还能不能用来发请求。 */
    static WebPage current() {        WebPage page = current;
        return page != null && page.raw() != null ? page : null;
    }

    static String key() {
        return currentKey;
    }

    /** 离屏页面加载时不要触发「进页面自动跑」，否则自己套自己。 */
    static boolean suppressing() {
        return suppressAuto;
    }

    /** 只读页面（比如读标题）期间临时关掉自动执行。 */
    static void suppress(boolean on) {
        suppressAuto = on;
    }

    static void end() {
        WebPage page = current;
        current = null;
        currentKey = "";
        if (page != null) {
            page.close();
        }
    }
}
