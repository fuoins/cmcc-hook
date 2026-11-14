package com.cmcchook;

import java.util.Locale;

/**
 * 辽宁 / 门户活动的 SSO 短链入口登记表（r40）。
 *
 * <p>用户 11-13 抓包实证：这些活动各自都有一条 {@code ssoJump.html?dwzCode=…}
 * 短链，打开后由门户自己走完
 * {@code uniTokenValidate → rr/dwz → addToProductStores / jumpOtherChinaNetUrl → 落地页}，
 * 落地页 URL 上带着 pc/pk 或 h5token，页面据此拿到登录态并发业务请求。
 * 也就是说：<b>不依赖用户提前手动打开活动页</b>，模块像收鹅蛋那样直接开短链即可。
 *
 * <p>逐条核对来源（302 链最终 200 落地页，均来自用户上传的压缩包）：
 * <ul>
 *   <li>{@code 0AnebE} 收鹅蛋换好礼 → {@code lyhVue/index.html#/bgIndex?actNo=goose2025&pc=…&pk=…}
 *       （鹅蛋缺少的游玩奖励领取.zip / 历史包）</li>
 *   <li>{@code 0ELdax} 球赛狂欢季 → {@code lyhVue/index.html#/footballGuess?actNo=AC2026DBCQUEZQ1G&pc=…}
 *       （球赛.zip）</li>
 *   <li>{@code 0EL7vw} 签到抽奖赢好礼 / 乡友农业知识问答 →
 *       {@code wisdom-h5/index.html#/signInLottery?h5token=…}（签到抽奖赢好礼.zip，
 *       经 {@code jumpOtherChinaNetUrl.wap?dwzCode=0EKihC} 中转）</li>
 *   <li>{@code 0FKMeG} 天天领权益(辽宁) →
 *       {@code rr/actH5/index.html#/workPerksMall?activityCode=36293370}
 *       （天天领权益(辽宁).zip，经 {@code dayWeekMoonReceive/entrance.do} 中转）</li>
 * </ul>
 *
 * <p>注意 dwzCode 会轮换：执行日志的搜索发现里「收鹅蛋兑好礼」是
 * {@code 0A97f4}，本次抓包是 {@code 0AnebE}，两者都指向鹅蛋。
 * 所以这里只登记「已核对能落地」的那一条，采集到新短链仍由
 * {@link RealEntry} / 各 Task 的现场学习覆盖。
 *
 * <p>本类只做登记与查询，不打开页面、不发请求，也不改任何活动的既有动作链。
 */
final class SsoShortLink {

    /** 一条登记：短链 code / 完整短链 / 落地页 pathKey / 落地路由特征 / 票据 / 活动说明。 */
    static final class Entry {
        final String code;
        final String link;
        /** 短链跨域跳转后的落地页 key，交给 {@link RealPage#pathKey} 让 await 能命中。 */
        final String landing;
        /** 落地页 URL 里应出现的特征，用于判断「已经落地、不是中转页」。 */
        final String route;
        /** 落地后 URL 上带什么票（pc/pk、h5token、activityCode）。 */
        final String ticket;
        final String act;

        Entry(String code, String link, String landing, String route, String ticket, String act) {
            this.code = code;
            this.link = link;
            this.landing = landing;
            this.route = route;
            this.ticket = ticket;
            this.act = act;
        }
    }

    private static final String BASE = "https://rr.ln139.cn/rr/lnwd/ssoJump.html?dwzCode=";
    private static final String LYH = "https://lyh.lncmcc.com/lyhVue/index.html";
    private static final String WISDOM = "https://digismart.lncmcc.com/wisdom-h5/index.html";
    private static final String ACT_H5 = "https://rr.ln139.cn/rr/actH5/index.html";

    /** 收鹅蛋 换好礼：与 v4.34 之前 {@link GooseTask} 里那条硬编码短链完全一致。 */
    static final String GOOSE = BASE + "0AnebE";
    /** 球赛狂欢季：以前只能靠缓存里带 pc/pk 的地址，没提前打开就跑不动。 */
    static final String SOCCER = BASE + "0ELdax";
    /** 签到抽奖 / 乡友问答：{@link WelfareToken} 领票用的就是这一条。 */
    static final String WISDOM_LINK = BASE + "0EL7vw";
    /** 天天领权益(辽宁)：{@link PerksTask} 每日「立即领」用的入口。 */
    static final String PERKS = BASE + "0FKMeG";

    private static final Entry[] ALL = {
            new Entry("0AnebE", GOOSE, LYH, "bgIndex", "pc/pk", "收鹅蛋 换好礼(辽宁)"),
            new Entry("0ELdax", SOCCER, LYH, "footballGuess", "pc", "球赛狂欢季(辽宁)"),
            new Entry("0EL7vw", WISDOM_LINK, WISDOM, "signInLottery", "h5token",
                    "签到抽奖赢好礼 / 乡友农业知识问答(辽宁)"),
            new Entry("0FKMeG", PERKS, ACT_H5, "workPerksMall", "activityCode",
                    "天天领权益(辽宁) · 每日领取"),
    };

    private SsoShortLink() {
    }

    /** 从 URL（或裸 code）里取 dwzCode，取不到返回空串。 */
    static String codeOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int i = url.indexOf("dwzCode=");
        if (i >= 0) {
            String c = url.substring(i + 8).split("[&#?]", 2)[0].trim();
            if (!c.isEmpty()) {
                return c;
            }
        }
        String t = url.trim();
        for (Entry e : ALL) {
            if (e.code.equalsIgnoreCase(t)) {
                return e.code;
            }
        }
        return "";
    }

    /** 按 URL 或活动 key（goose / soccer / signlottery / quiz / perks）查登记项。 */
    static Entry find(String urlOrKey) {
        if (urlOrKey == null || urlOrKey.isEmpty()) {
            return null;
        }
        String code = codeOf(urlOrKey);
        String low = urlOrKey.toLowerCase(Locale.US);
        for (Entry e : ALL) {
            if (!code.isEmpty() && e.code.equalsIgnoreCase(code)) {
                return e;
            }
            if (code.isEmpty()) {
                if (low.contains("goose") || low.contains("raisegoose")) {
                    if ("0AnebE".equals(e.code)) return e;
                } else if (low.contains("soccer") || low.contains("football")) {
                    if ("0ELdax".equals(e.code)) return e;
                } else if (low.contains("wisdom") || low.contains("signlottery")
                        || low.contains("quiz") || low.contains("answerquestion")) {
                    if ("0EL7vw".equals(e.code)) return e;
                } else if (low.contains("perks") || low.contains("workperks")) {
                    if ("0FKMeG".equals(e.code)) return e;
                }
            }
        }
        return null;
    }

    /** 这是不是登记过的短链。 */
    static boolean isShortLink(String url) {
        Entry e = find(url);
        return e != null && url != null && url.contains("ssoJump.html");
    }

    /**
     * 短链对应的落地页 pathKey；不是登记短链返回 {@code null}。
     *
     * <p>{@link RealPage#pathKey} 用它替换原来只对 {@code 0AnebE} 的硬编码特判：
     * 短链跨域跳转后地址与入口完全不同，按入口算 await 的 key 会一直等不到页面。
     * 这里统一按落地页算，任何登记短链都能像鹅蛋一样自己走完 SSO 落地。
     */
    static String landingKey(String url) {
        if (!isShortLink(url)) {
            return null;
        }
        Entry e = find(url);
        return e == null ? null : e.landing;
    }

    /** 日志用的一行说明：短链 code → 落地页与票据，便于逐条对账。 */
    static String describe(String url) {
        Entry e = find(url);
        if (e == null) {
            return "普通入口（非登记短链）";
        }
        return "短链自开真页 · " + e.act
                + " · dwzCode=" + e.code
                + " → " + e.landing + "#/" + e.route
                + "（落地带 " + e.ticket + "，门户现场签发，模块不拼）";
    }

    /** 按活动 key 返回可直接用于开页的短链；没登记返回空串。 */
    static String linkOf(String key) {
        Entry e = find(key);
        return e == null ? "" : e.link;
    }
}
