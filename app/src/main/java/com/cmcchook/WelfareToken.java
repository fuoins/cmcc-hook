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
        log.append("票据: 没有可用的 h5token（旧票 30 分钟就作废），自动去福利专区领一张\n");
        String minted = mint(log);
        if (!minted.isEmpty()) {
            log.append("票据: 领到新票 ").append(head(minted)).append('\n');
        } else {
            log.append("票据: 福利专区没领到票\n");
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
            // 等页面把卡片渲染出来，再点。最多 10 秒。
            boolean ready = false;
            long until = System.currentTimeMillis() + 10_000L;
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
                log.append("  福利专区里没找到「签到抽奖」卡片（可能还没渲染完）\n");
                return "";
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
            log.append("  点了入口但没等到带票的页面\n");
            return "";
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
