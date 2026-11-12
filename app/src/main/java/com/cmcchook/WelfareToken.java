package com.cmcchook;

/**
 * 现场领一张 wisdom-h5 的 h5token。
 *
 * <p>11-10 22:57 实证：这站的 h5token 是一次性短时效凭据，模块以前用内置表里
 * 三天前那张死票硬闯，{@code jtH5Login} 一律回 500「系统登录异常」，
 * 签到抽奖和乡友问答都跑不起来。真正能用的票只在「从福利专区点进签到抽奖」
 * 这一条路上由 App 现场下发 —— 23:01 用户手动点开时拿到的票是好的。
 *
 * <p>所以这里把那条路自动走一遍：打开福利专区真页 → 用真实点击事件点
 * 「签到抽奖」入口 → 等 App 自己拉起下发新票的页面 → 从登记表里把票取出来。
 * 全程不碰用户的页面，失败就返回空串，由调用方给明确提示。
 */
final class WelfareToken {

    /** 福利专区（11-10 22:27 日志里 App 自己打开的那一条，带页面与渠道参数）。 */
    static final String WELFARE =
            "https://wx.10086.cn/website/businessPlatform/shop"
                    + "?pageid=f38143861a4a4a9fac920fdeecd119bb&channelId=P00000054178&yx=1597107001";

    /**
     * 签到抽奖的 SSO 入口。
     *
     * <p>11-11 11:31 抓包实证：用户手动在福利专区点「签到抽奖」，App 走的是
     * <b>ssoJump.html?dwzCode=0EL7vw</b> 这一层，不是 {@code /rr/dwz/0EL7vw}：
     *
     * <pre>
     * 福利专区卡片
     *  → rr.ln139.cn/rr/lnwd/ssoJump.html?dwzCode=0EL7vw      ← 关键：必须先走 ssoJump
     *  → rr.ln139.cn/rr/dwz/0EL7vw?channelId=&amp;yx=&amp;_sid_=…
     *  → rr.ln139.cn/rr/lm/tWlmChinaNetAuth/jumpOtherChinaNetUrl.wap?…dwzCode=0EKihC
     *  → wisdom-h5/index.html#/signInLottery?h5token=YZsidssolg…
     * </pre>
     *
     * <p>旧值直接开 {@code /rr/dwz/0EL7vw}，少了 ssoJump 这一层，落地永远没有
     * {@code h5token}；而 ssoJump 必须在 App 真页里开（要带 App 的 SSO 会话与来源）。
     */
    static final String SHORT_LINK = "https://rr.ln139.cn/rr/lnwd/ssoJump.html?dwzCode=0EL7vw";

    /** 签到抽奖裸入口：短链也不通时最后试一次，页面自己可能补 SSO。 */
    static final String SIGN_ENTRY =
            "https://digismart.lncmcc.com/wisdom-h5/index.html#/signInLottery";

    private WelfareToken() {
    }

    /**
     * 拿一张可用的票：30 分钟内学到过就直接用，否则去福利专区现场领。
     *
     * @param log 追加过程说明，写进任务日志
     * @return 票；拿不到返回空串
     */
    static String ensure(StringBuilder log) {
        String exist = RealEntry.wisdomLatest();
        String tk = RealEntry.tokenIn(exist);
        if (!tk.isEmpty()) {
            log.append("票据: 复用 ").append(head(tk)).append("（30 分钟内学到）\n");
            return tk;
        }
        log.append("票据: 没有可用的 h5token（旧票 30 分钟就作废），自动去领一张\n");
        // v4.3.0：先走 ssoJump 短链 —— 11-11 11:31 抓包确认这条路 2 秒就能出票，
        // 而福利专区是 uni-app 壳，卡片文本读不到，点 20 秒也是空手。
        String minted = viaShortLink(log);
        if (minted.isEmpty()) {
            // v4.10.0-r5：短链这一跳落地时，页面自己就把带票地址登记进缓存了
            // （11-12 08:18 日志：08:18:31 连学 5 条「签到页票据已刷新」，
            // 08:18:51 这里却还说没票，于是又开了一页去裸入口领 —— 用户看到
            // 「签到页打开又退回首页」就是这么来的）。先复查一次缓存，有票就直接用。
            String again = RealEntry.wisdomLatest();
            String t2 = RealEntry.tokenIn(again);
            if (!t2.isEmpty()) {
                log.append("  短链落地后从现场学到票 ").append(head(t2)).append('\n');
                minted = t2;
            }
        }
        if (minted.isEmpty()) {
            minted = mint(log);
        }
        if (!minted.isEmpty()) {
            log.append("票据: 领到新票 ").append(head(minted)).append('\n');
        } else {
            log.append("票据: 短链和福利专区都没领到票\n");
        }
        return minted;
    }

    /** 打开福利专区 → 点「签到抽奖」→ 等新票。 */
    private static String mint(StringBuilder log) {
        WebPage page = null;
        try {
            // 直接用 App 自己的 H5 容器打开福利专区 —— 走私聊会话会让 settle 的
            // 登录判定把它踢到离屏页，离屏页没有 App 的 JS 桥，点了也不会下发票。
            page = RealPage.open(WELFARE, "businessPlatform/shop", 20_000L);
            if (page == null) {
                log.append("  福利专区没打开（App 容器没起来）\n");
                return "";
            }
            // 等页面把卡片渲染出来，再点。最多 20 秒（uni-app 首屏慢，10 秒常常不够）。
            boolean ready = false;
            long until = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < until) {
                String txt = page.eval("(function(){try{return (document.body?document.body.innerText:'')"
                        + ".replace(/\\s+/g,'')}catch(e){return ''}})()", 3_000L);
                if (txt != null && txt.contains("签到抽奖")) {
                    ready = true;
                    break;
                }
                sleep(700L);
            }
            if (!ready) {
                log.append("  福利专区里没找到「签到抽奖」卡片（uni-app 壳，文本读不到）\n");
                // 11-11 10:40 实证：这一页的卡片在 uni-app 里，innerText 只有页脚，
                // 所以这里不再直接放弃，改走 App 自己那条 SSO 短链。
                // 先把福利页收掉，免得两个页面抢前台；置空避免 finally 二次关闭。
                closeQuiet(page);
                page = null;
                return viaShortLink(log);
            }
            HookLog.log("TASK", "领票 · 自动点「签到抽奖」", "入口页: " + WELFARE);
            String clicked = "";
            for (int depth = 0; depth < 4 && clicked.isEmpty(); depth++) {
                clicked = String.valueOf(page.eval(clickJs(depth), 4_000L));
                if (clicked == null || "null".equals(clicked)) {
                    clicked = "";
                }
                if (clicked.isEmpty()) {
                    continue;
                }
                // 点完等 App 走完 SSO 并把带票的页面拉起来。
                long wait = System.currentTimeMillis() + 6_000L;
                while (System.currentTimeMillis() < wait) {
                    String t = tokenOfRegistered();
                    if (!t.isEmpty()) {
                        log.append("  点击: ").append(clicked).append("（第 ").append(depth + 1).append(" 层）\n");
                        return t;
                    }
                    sleep(500L);
                }
                // 这一层点不出页面，往上一层再试。
                clicked = "";
            }
            log.append("  点了入口但没等到带票的页面，改走短链\n");
            return viaShortLink(log);
        } catch (Throwable e) {
            log.append("  领票异常: ").append(e).append('\n');
            return "";
        } finally {
            // 只关我们自己拉起来的福利专区，用户自己开着的页面不动。
            if (page != null && RealPage.isOwned(page.raw())) {
                RealPage.closePage(page.raw());
            }
        }
    }

    /** 直接打开 SSO 短链，让 App 自己把 h5token 换出来。 */
    private static String viaShortLink(StringBuilder log) {
        log.append("  改走 SSO 短链: ").append(SHORT_LINK).append('\n');
        WebPage page = null;
        // v4.10.0-r5：这一页落地到签到页后不能关 —— 它就是任务要跑的那一页。
        // 以前 finally 无条件 closeQuiet，把刚跳到签到页的真页收掉，用户看到
        // 「真页打开 → 退回首页」，任务随后只能拿着已销毁的 WebView 空跑。
        boolean keep = false;
        try {
            page = RealPage.open(SHORT_LINK, "rr/lnwd/ssoJump", 25_000L);
            if (page == null) {
                log.append("  短链没打开（App 容器没起来）\n");
                return viaBareEntry(log);
            }
            // 边等票边看落地：一旦落到签到/答题路由，票已经在地址上被登记进缓存了，
            // 不用再等满 20 秒，也不用再开第二页去裸入口。
            long until = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < until) {
                String t = tokenOfRegistered();
                if (!t.isEmpty()) {
                    log.append("  短链落地后拿到票 ").append(head(t)).append('\n');
                    keep = true;
                    return t;
                }
                String landed = RealPage.lastUrlOf(page.raw());
                if (onTaskPage(landed)) {
                    log.append("  短链已落地签到页，票由页面自己登记: ").append(brief(landed)).append('\n');
                    keep = true;
                    return "";
                }
                sleep(500L);
            }
            log.append("  短链打开后没等到带票页面（落地 ").append(brief(page.url())).append("）\n");
            return viaBareEntry(log);
        } catch (Throwable e) {
            log.append("  短链异常: ").append(e).append('\n');
            return "";
        } finally {
            if (!keep) {
                closeQuiet(page);
            }
        }
    }

    /** 最后兜底：裸入口打开，页面自己可能补一遍 SSO。 */
    private static String viaBareEntry(StringBuilder log) {
        log.append("  再试裸入口: ").append(SIGN_ENTRY).append('\n');
        WebPage page = null;
        // 拿到票的这一页就是任务页，留给任务复用，别在领票阶段把它关掉。
        boolean keep = false;
        try {
            page = RealPage.open(SIGN_ENTRY, "signInLottery", 20_000L);
            if (page == null) {
                log.append("  裸入口也没打开\n");
                return "";
            }
            String t = waitToken(15_000L);
            if (!t.isEmpty()) {
                log.append("  裸入口拿到票 ").append(head(t)).append('\n');
                keep = onTaskPage(RealPage.lastUrlOf(page.raw()));
                return t;
            }
            log.append("  裸入口没拿到票（落地 ").append(brief(page.url())).append("）\n");
            return "";
        } catch (Throwable e) {
            log.append("  裸入口异常: ").append(e).append('\n');
            return "";
        } finally {
            if (!keep) {
                closeQuiet(page);
            }
        }
    }

    /** 这一页是不是已经在签到 / 答题路由上（可以被任务直接接手）。 */
    private static boolean onTaskPage(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(java.util.Locale.US);
        return u.contains("signinlottery") || u.contains("answerquestions");
    }

    private static String waitToken(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            String t = tokenOfRegistered();
            if (!t.isEmpty()) {
                return t;
            }
            sleep(500L);
        }
        return "";
    }

    private static void closeQuiet(WebPage page) {
        try {
            if (page != null && RealPage.isOwned(page.raw())) {
                RealPage.closePage(page.raw());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String brief(String u) {
        if (u == null) {
            return "";
        }
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }

    /** 真页登记表里最新一条带 h5token 的 wisdom 地址的票。 */
    private static String tokenOfRegistered() {
        try {
            Object view = RealPage.find("signInLottery", 120_000L);
            if (view == null) {
                return "";
            }
            // 这一页是「我们点出来的」，任务结束一起回收。
            RealPage.markOwned(view);
            String url = RealPage.lastUrlOf(view);
            String t = RealEntry.tokenIn(url);
            // 只认「不是模块自己打开的那一条」：fresh 的票是 App 现场下发的。
            if (t.isEmpty()) {
                return "";
            }
            return t;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 点「签到抽奖」卡片的脚本。
     *
     * @param up 向上找几层父节点再点：卡片一般把 click 绑在外层容器上
     */
    private static String clickJs(int up) {
        StringBuilder target = new StringBuilder("e");
        for (int i = 0; i < up; i++) {
            target.append(".parentElement||e");
        }
        return "(function(){try{"
                + "var all=document.querySelectorAll('div,span,p,a,li');"
                + "for(var i=0;i<all.length;i++){var e=all[i];"
                + "var t=(e.innerText||'').replace(/\\s+/g,'');"
                + "if(!t||t.length>12||t.indexOf('签到抽奖')<0)continue;"
                + "var r=e.getBoundingClientRect();"
                + "if(r.width<4||r.height<4)continue;"
                + "var n=" + target + ";"
                + "try{n.scrollIntoView({block:'center'})}catch(x){}"
                + "try{n.click()}catch(x){try{e.click()}catch(y){}}"
                + "return t;}"
                + "return '';}catch(e){return ''}})()";
    }

    private static String head(String t) {
        return t.length() > 10 ? t.substring(0, 10) + "…" : t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
