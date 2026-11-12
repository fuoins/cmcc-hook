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
        // v4.3.0：全部走 App 官方真页。离屏页缺容器初始化、缺 localStorage SSO、
        // 缺真实布局，实测成功率远低于真页 —— 真页拿不到就直接失败，不再降级。
        WebPage real = tryReal(key, url, timeoutMs);
        if (real != null) {
            boolean ok = settle(key, real, start);
            if (ok) {
                HookLog.logcat("session real ok: " + key + " · " + real.href());
                return true;
            }
            HookLog.log("TASK", "真页没判过 · " + key + " · 不再退离屏",
                    "目标: " + url + "\n"
                            + "现在所有活动都只在 App 真页里执行；离屏页缺容器初始化与 SSO 会话，"
                            + "拿它兜底只会白花时间。\n本次先跳过，不记失败。");
            end();
            return false;
        }
        HookLog.log("TASK", "真页没打开 · " + key + " · 不再退离屏",
                "目标: " + url + "\n"
                        + "App 容器这一轮没起来（或被下载引导页顶走），没有离屏兜底。\n"
                        + "本次先跳过，不记失败。");
        end();
        return false;
    }

    /**
     * 这些活动只认 App 真页，真页判不过就直接跳过，不再退离屏。
     *
     * <p>鹅蛋和球赛都要 lyh 门户 SSO 下发的 {@code pc/pk}，离屏 WebView 里重走一遍
     * SSO 两轮都没拿到（11-11 09:53 日志）。真页是 App 自己的容器，页面自己会跳 SSO。
     */
    private static boolean realOnly(String key) {
        return "goose".equals(key) || "soccer".equals(key);
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
            HookLog.log("TASK", "真页没判过 · " + key + " · 不再退离屏",
                    "目标: " + url + "\n"
                            + "现在所有活动都只在 App 真页里执行；离屏页缺容器初始化，"
                            + "拿它兜底只会白花时间。\n本次先跳过，不记失败。");
            return false;
        }
        HookLog.log("TASK", "真页没打开 · " + key + " · 不再退离屏",
                "目标: " + url + "\n"
                        + "App 容器这一轮没起来，没有离屏兜底。\n本次先跳过，不记失败。");
        return false;
    }

    /**
     * r8 快路径用的轻量上下文：只等文档建立，不等 Vue 渲染、不等落地跳转链、
     * 不做场景快照。服务端认的是「请求由页面 JS 发出」，文档一起就够发 fetch。
     *
     * <p>任何一步判不过都直接返回 false，由调用方原样回退 {@link #beginFast}——
     * 旧链路一行不删，功能不回退，只是这次多花一次开页。
     */
    static boolean beginFastLite(String key, String url, long timeoutMs) {
        long start = System.currentTimeMillis();
        suppressAuto = true;
        WebPage live;
        try {
            live = tryReal(key, url, Math.min(Math.max(timeoutMs, 8_000L), 14_000L));
        } finally {
            suppressAuto = false;
        }
        if (live == null || live.raw() == null) {
            return false;
        }
        // 文档建立即可发请求：渲染、Vue mount、落地跳转链都不等。
        long okEnd = System.currentTimeMillis() + 6_000L;
        while (!live.ok() && live.raw() != null && System.currentTimeMillis() < okEnd) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        if (!live.ok()) {
            live.close();
            return false;
        }
        String landed = live.url();
        if (landed == null || landed.contains("qwhdsso/login") || RealPage.transit(landed)) {
            // 停在登录页 / 中转页：这个现场发不了活动接口，交给完整链路去换票。
            live.close();
            return false;
        }
        current = live;
        currentKey = key;
        HookLog.log("TASK", "轻量页面上下文 · " + key + " · "
                        + (System.currentTimeMillis() - start) + "ms",
                "URL: " + landed + "\n"
                        + "只等文档建立，不等渲染 / 落地跳转链 / 场景快照；"
                        + "接口全部在这个真页上下文里发。");
        return true;
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

    /**
     * 等页面自己把 SSO 跳转链走完，再判定它到底是什么页。
     *
     * <p>11-11 12:33 实证：鹅蛋入口 {@code ssoJump.html?dwzCode=0AnebE} 是一个
     * 407 字节的桩页，真页容器一匹配到它就返回了；模块立刻按「小 DOM + 空正文」
     * 判成访问受限并 close()。而 70 毫秒后同一 WebView 就跳到了
     * {@code lyhVue/index.html#/bgIndex?pc=…}（pc/pk 齐全），被自己关在门外。
     *
     * <p>判定条件：URL 不再是中转页，且连续 900ms 没变化。上限 {@code timeoutMs}，
     * 超时就返回原页，由调用方按「跳转链没走完」处理。
     */
    private static WebPage waitLanded(WebPage page, long timeoutMs) {
        if (page == null || page.raw() == null) {
            return page;
        }
        long startAt = System.currentTimeMillis();
        // 没经过中转页的普通活动，最多等 3.5 秒看地址稳不稳定；
        // 一旦发现自己在 SSO / 短链中转页上，就放宽到调用方给的预算（默认 16 秒）。
        long end = startAt + Math.min(timeoutMs, 3_500L);
        String last = null;
        long stableAt = 0L;
        while (System.currentTimeMillis() < end) {
            String now;
            try {
                now = page.href();
            } catch (Throwable e) {
                return page;
            }
            if (page.raw() == null) {
                return page;
            }
            if (now == null) {
                now = "";
            }
            if (now.contains("qwhdsso/login")) {
                return page;
            }
            if (RealPage.transit(now)) {
                long wider = startAt + timeoutMs;
                if (wider > end) {
                    end = wider;
                }
            }
            if (now.isEmpty()) {
                // WebView 还没拿到第一个地址：这不是「稳定」，是没开始。
                last = now;
                stableAt = 0L;
                try {
                    Thread.sleep(220L);
                } catch (InterruptedException e) {
                    return page;
                }
                continue;
            }
            if (now.equals(last) && !RealPage.transit(now)) {
                if (stableAt == 0L) {
                    stableAt = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - stableAt >= 900L) {
                    return page;
                }
            } else {
                stableAt = 0L;
                last = now;
            }
            try {
                Thread.sleep(220L);
            } catch (InterruptedException e) {
                return page;
            }
        }
        return page;
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
        // 先让跳转链跑完：中转页上的任何判定都是噪声。
        page = waitLanded(page, depth == 0 ? 16_000L : 8_000L);
        if (page == null || page.raw() == null) {
            HookLog.log("TASK", "页面在跳转途中被关掉 · " + key, "");
            return false;
        }
        // 落地这一跳的文档可能刚开始加载（finished 被 onPageStarted 复位），
        // 给它 8 秒把文档建起来，别把正在加载的活动页当白屏关掉。
        long okEnd = System.currentTimeMillis() + 8_000L;
        while (!page.ok() && page.raw() != null && System.currentTimeMillis() < okEnd) {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ignored) {
                break;
            }
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
        if (RealPage.transit(pageUrl)) {
            // 等到上限还停在中转页：短链失效、门户没下发 token，或者网络卡在中间。
            // 这跟「入口缺渠道参数」是两回事，日志别写错，不然排查方向会歪。
            HookLog.log("TASK", "跳转链没走完 · " + key + " · 仍停在短链",
                    "URL: " + pageUrl + "\n标题: " + title + "\n现场: " + scene + "\n"
                            + "等了 16 秒仍在中转页（ssoJump / rr/dwz / uniTokenValidate 这些），"
                            + "没有跳到活动页。\n"
                            + "常见原因是短链过期，或门户这次没把 token 接住。\n"
                            + "在中国移动 App 里手动点进一次这个活动，再点「执行任务」重试即可；"
                            + "不会锁定当天任务。");
            page.close();
            return false;
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
        boolean limited = false;
        if (!channelBlocked && suspect && !RealPage.transit(pageUrl)) {
            limited = RealPage.blocked(page);
            if (limited) {
                // 刚落地的一瞬间，Vue 还没 mount、DOM 只有几百字节，也像「壳页」。
                // 等 1.2 秒再复核一次，两次都判才认，避免把正在渲染的活动页关掉。
                try {
                    Thread.sleep(1_200L);
                } catch (InterruptedException ignored) {
                }
                limited = RealPage.blocked(page);
                if (limited) {
                    try {
                        title = page.title();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
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
            Object raw = page.raw();
            page.close();
            // r17：非 owned 的真页 close() 只是「保留不销毁」，登记表和 sessionView
            // 都还指着它；重建时 find/sessionView 又把同一个实例取回来，日志里
            // 「重建一次」后仍写「复用真页 · WebView$a」就是这么来的。这里显式摘掉，
            // 让 begin() 必须重新等一个可用的容器。
            RealPage.forget(raw);
            RealPage.dropSession(raw);
        }
    }

    /**
     * r8：只清引用、不关页。快路径命中时页面由 {@code RealPage.closeOwned()}
     * 统一收尾，避免和这里重复关闭同一个真页。
     */
    static void detach() {
        current = null;
        currentKey = "";
    }
}
