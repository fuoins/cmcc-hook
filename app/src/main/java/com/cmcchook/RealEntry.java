package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 活动页的「真实入口」学习器。
 *
 * <p>11.9.1 抓包实证：活动页 URL 必须带渠道参数（touch_id / yx / channelId /
 * A_C_CODE / lc_traceid / redCode 之一或组合），服务端才认这个入口渠道。
 * 用内置裸地址（如 .../qwhdmark/1021122301）打开，换票返回的 data.url 里
 * 也只剩 token，页面会被 302 到 channelNonsupport（访问渠道未开放），
 * 之后所有接口返回的都是那张错误 HTML，任务必然失败。
 *
 * <p>这些渠道参数随省份、入口、期次变化，不能写死。App 自己点进活动时，
 * WebView 加载的就是一份带全参数的现成地址 —— 模块在每个页面回调里
 * 把它学下来：剥掉一次性的 token 和 hash 路由，按活动号存好。
 * 执行任务时 {@link TaskApi#findEntry} 优先用学到的这一份。
 *
 * <p>只学「带渠道参数」的地址：模块自己用裸地址开的页面不会被采信，
 * 也就不会把学到的真实入口覆盖掉。学到新的覆盖旧的，入口变了自动跟上。
 */
final class RealEntry {

    private static final String PREFS = "cmcc_hook_real_entry";
    private static final String KEY = "entries";
    private static final long MAX_AGE = 30L * 24 * 3600 * 1000;

    /** 渠道参数名：命中任意一个就认为这份地址是「带渠道的真实入口」。 */
    private static final String[] CHANNEL_KEYS = {
            "touch_id", "yx", "channelId", "channelid", "channelCode", "channelcode",
            "A_C_CODE", "a_c_code", "lc_traceid", "redCode", "redcode", "pageId",
            // v3.63：wisdom-h5 的登录凭证就写在地址上（?h5token=… 或 #/route?h5token=…）。
            // 不把它算渠道参数的话，findEntry 第 3 步会拿「不带 token 的采集缓存」
            // 把内置的带 token 地址顶掉 —— 11-07 23:20 日志里那个裸
            // #/signInLottery 就是这么来的，ssoId 自然为空。
            "h5token"
    };

    private static final Pattern ACT = Pattern.compile("/qwhdhub/[a-zA-Z-]+/(\\d{6,})");
    /** 片段可能没有前导斜杠（如 findEntry 传的 "qwhdmark/1021122301"），放宽匹配。 */
    private static final Pattern ACT_LOOSE = Pattern.compile(
            "/?(?:qwhdmark|goldeneggs|diy-client|turntable|newbuildgroup)/(\\d{6,})");
    /** coc3 权益市场画布：最后一段是 pageKey（zzcj2 / 26xysrqtyld …）。 */
    private static final Pattern COC3 = Pattern.compile(
            "/coc3/canvas/rightsmarket-h5-canvas/online/([A-Za-z0-9_-]+)");
    /** coc web6 的 7 日打卡页。 */
    private static final Pattern DAILY7 = Pattern.compile("/coc/web6/dailyLoginEvent");
    /**
     * v3.62：米粒商城（wisdom-h5）的两个路由。
     * 这个站点不认渠道参数，只认 hash 上的 h5token —— 页面拿它调
     * /wisdom-front/login/jtH5Login 换 ssoId，没有它 sessionStorage.ssoId 就是空，
     * 之后每个接口都回 403「未登录或过期」。
     * 23:21 日志实证：模块用裸 #/answerQuestions 打开，login/user 直接 403。
     */
    private static final Pattern WISDOM = Pattern.compile(
            "wisdom-h5/index\\.html.*#/(signInLottery|answerQuestions)",
            Pattern.CASE_INSENSITIVE);

    private RealEntry() {
    }

    /** 从现场看到的一个 URL 里学习真实入口。带渠道参数才采信。 */
    static void learn(String url) {
        if (url == null || !url.startsWith("http")) {
            return;
        }
        String actId = actIdOf(url);
        if (actId.isEmpty()) {
            return;
        }
        String clean;
        if (wisdomId(actId)) {
            // 这类入口唯一的「渠道参数」就是 h5token，别的参数一律不要。
            clean = keepWisdom(url);
            if (clean.isEmpty()) {
                return;
            }
        } else {
            if (!hasChannel(url)) {
                return;
            }
            clean = stripTokenAndHash(url);
            if (clean.isEmpty() || !hasChannel(clean)) {
                return;
            }
        }
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences prefs = AccountScope.prefs(ctx, PREFS);
            JSONObject all = new JSONObject(prefs.getString(KEY, "{}"));
            JSONObject old = all.optJSONObject(actId);
            if (old != null && clean.equals(old.optString("u", ""))) {
                return;
            }
            JSONObject one = new JSONObject();
            one.put("u", clean);
            one.put("at", System.currentTimeMillis());
            all.put(actId, one);
            prefs.edit().putString(KEY, all.toString()).apply();
            HookLog.logcat("real entry learned " + actId + ": " + clean);
            // v3.80：wisdom 的票是会过期的一次性凭据，13:36 日志实证 App 从
            // 福利专区点开签到页会下发新票。以前只在 logcat 里记一行，导出的
            // 完整日志看不到「这次到底学没学到新票」，排障全靠猜。现在写进日志。
            if (wisdomId(actId)) {
                String tk = tokenIn(clean);
                String head = tk.length() > 10 ? tk.substring(0, 10) : tk;
                HookLog.log("TASK", "签到页票据已刷新 · " + actId,
                        "新票: " + head + "…\n"
                                + "来源: App 现场打开活动页（福利专区下发）\n"
                                + "入口: " + clean + "\n"
                                + "下次跑「签到抽奖赢好礼 / 乡友问答」会用这张票，不再用内置旧票。\n");
            }
        } catch (Throwable e) {
            HookLog.logcat("real entry learn failed: " + e);
        }
    }

    /** 按片段（活动号或路径片段）取学到的真实入口；过期或没有返回空串。 */
    static String get(String frag) {
        Context ctx = HookLog.context();
        if (ctx == null || frag == null || frag.isEmpty()) {
            return "";
        }
        String actId = actIdOf(frag);
        if (actId.isEmpty()) {
            actId = frag;
        }
        try {
            JSONObject all = new JSONObject(AccountScope.prefs(ctx, PREFS)
                    .getString(KEY, "{}"));
            // 先按数字活动号精确取，再退化为遍历匹配片段。
            JSONObject one = all.optJSONObject(actId);
            if (one == null) {
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    JSONObject cand = all.optJSONObject(it.next());
                    String u = cand == null ? "" : cand.optString("u", "");
                    if (u.contains(frag)) {
                        one = cand;
                        break;
                    }
                }
            }
            if (one == null) {
                return "";
            }
            long at = one.optLong("at", 0);
            if (at > 0 && System.currentTimeMillis() - at > MAX_AGE) {
                return "";
            }
            String u = one.optString("u", "");
            return (wisdomId(actId) || hasChannel(u)) ? u : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** URL 里是否带渠道参数（排除 token，token 不算渠道）。 */
    static boolean hasChannel(String url) {
        if (url == null || url.indexOf('?') < 0) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String key : CHANNEL_KEYS) {
            // 参数名要出现在 ? 或 & 之后，避免误匹配路径里的字样。
            if (lower.contains("?" + key.toLowerCase(Locale.US) + "=")
                    || lower.contains("&" + key.toLowerCase(Locale.US) + "=")) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉一次性的 token 参数和 # 后面的 hash 路由，保留渠道参数。 */
    private static String stripTokenAndHash(String url) {
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        String base = url.substring(0, q);
        String[] params = url.substring(q + 1).split("&");
        StringBuilder kept = new StringBuilder();
        for (String p : params) {
            if (p.isEmpty()) {
                continue;
            }
            String name = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
            if ("token".equalsIgnoreCase(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(p);
        }
        return kept.length() == 0 ? base : base + "?" + kept;
    }

    private static boolean wisdomId(String actId) {
        return "signinlottery".equals(actId) || "answerquestions".equals(actId);
    }

    /**
     * wisdom-h5 的入口只留「页面 + 路由 + h5token」。
     * 外层 ?ut23VwQPael2=1794062947957 是页面自己加的时间戳，剥掉；
     * h5token 必须留着，它就是这站的登录凭证。
     *
     * <p>v3.63：token 可能在两个位置 ——
     * {@code index.html#/signInLottery?h5token=x}（hash 路由的 query）或
     * {@code index.html?h5token=x#/signInLottery}（外层 query）。
     * 23:20 日志里 SPA 换完登录态把外层 query 改写成 {@code ut23VwQPael2=时间戳}，
     * 说明真实入口的 token 原本就在外层。以前只从 hash 里找，所以永远学不到。
     * 现在两处都找，并「按原位置」还原，不搬家。
     */
    private static String keepWisdom(String url) {
        int hash = url.indexOf('#');
        if (hash < 0) {
            return "";
        }
        String head = url.substring(0, hash);
        String frag = url.substring(hash);
        int q = frag.indexOf('?');
        String route = q < 0 ? frag : frag.substring(0, q);

        boolean inHash = !paramOf(frag, "h5token").isEmpty();
        String token = inHash ? paramOf(frag, "h5token") : tokenInQuery(head);
        if (token.isEmpty()) {
            return "";
        }
        if (inHash) {
            return head + route + "?h5token=" + token;
        }
        String plain = head;
        int hq = head.indexOf('?');
        if (hq >= 0) {
            plain = head.substring(0, hq);
        }
        return plain + "?h5token=" + token + route;
    }

    /** 先从 hash 段找 token，再从外层 query 找；都没有返回空串。 */
    static String tokenIn(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int hash = url.indexOf('#');
        if (hash >= 0) {
            String t = paramOf(url.substring(hash), "h5token");
            if (!t.isEmpty()) {
                return t;
            }
        }
        return tokenInQuery(url);
    }

    private static String tokenInQuery(String url) {
        int q = url == null ? -1 : url.indexOf('?');
        if (q < 0) {
            return "";
        }
        String s = url.substring(q);
        int h = s.indexOf('#');
        if (h > 0) {
            s = s.substring(0, h);
        }
        return paramOf(s, "h5token");
    }

    /**
     * 从任何一条 wisdom 记录里取 h5token —— 同站同 SPA，两个路由共用一份登录凭证。
     * 签到抽奖那条地址是用户点一次就能白拿到的（不消耗答题机会），
     * 答题页没有独立 token 时直接借它。
     */
    static String wisdomToken() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return "";
        }
        // v3.80：按学到时间取最新的一张票，别再「遍历到第一条就返回」。
        // 13:36 日志实证：App 从福利专区点开签到页时下发了一张全新的
        // h5token（YZsidssolgc467…），页面用它登录成功、接口回 code=0000；
        // 而 13:35 任务用的还是内置表写死的旧票（YZsidssolgfd8d770…），
        // jtH5Login 直接回 500。两个路由共用一份票，旧的那条不能顶掉新的。
        String best = "";
        long bestAt = -1;
        try {
            JSONObject all = new JSONObject(AccountScope.prefs(ctx, PREFS)
                    .getString(KEY, "{}"));
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                if (!wisdomId(id)) {
                    continue;
                }
                JSONObject one = all.optJSONObject(id);
                String u = one == null ? "" : one.optString("u", "");
                String t = tokenIn(u);
                if (t.isEmpty()) {
                    continue;
                }
                long at = one == null ? 0 : one.optLong("at", 0);
                if (at > bestAt) {
                    best = t;
                    bestAt = at;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!best.isEmpty()) {
            return best;
        }
        // v3.68：缓存里一条带 token 的都没有时，退回内置表那条签到抽奖地址。
        // 两站同域、token 通用，总比返回空、让上层发裸地址强。
        // v3.80：这张是备用旧票，可能已经过期，调用方要在日志里如实标出来。
        try {
            String built = WelfareCatalog.builtUrl("signlottery");
            String t = tokenIn(built);
            if (!t.isEmpty()) {
                return t;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * v3.80：按学到时间取最新的 wisdom 入口，**保留 App 现场下发的原始形态**。
     *
     * <p>13:36 日志实证：App 从福利专区点开签到页时下发的是
     * {@code index.html#/signInLottery?h5token=YZsidssolgc467…}（票只在 hash 里，
     * 外层 query 没有），页面用这张票登录成功。以前 {@link #get} 靠
     * {@code u.contains(frag)} 遍历匹配，可能命中另一条路由的旧记录把新票顶掉。
     * 这里只认带票的记录、按 {@code at} 取最新，不重新拼 URL。
     */
    static String wisdomLatest() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return "";
        }
        String best = "";
        long bestAt = -1;
        try {
            JSONObject all = new JSONObject(AccountScope.prefs(ctx, PREFS)
                    .getString(KEY, "{}"));
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                if (!wisdomId(id)) {
                    continue;
                }
                JSONObject one = all.optJSONObject(id);
                String u = one == null ? "" : one.optString("u", "");
                if (u.isEmpty() || !hasChannel(u) || tokenIn(u).isEmpty()) {
                    continue;
                }
                long at = one == null ? 0 : one.optLong("at", 0);
                if (at > bestAt) {
                    best = u;
                    bestAt = at;
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** v3.80：给任务日志用，说明这次入口用的是哪张票、什么时候学到的。 */
    static String wisdomTicketInfo(String entry) {
        String t = tokenIn(entry == null ? "" : entry);
        if (t.isEmpty()) {
            return "入口票据: 无（裸地址，页面换不到 ssoId）";
        }
        String head = t.length() > 10 ? t.substring(0, 10) : t;
        Context ctx = HookLog.context();
        long learnedAt = 0;
        try {
            JSONObject all = new JSONObject(AccountScope.prefs(ctx, PREFS)
                    .getString(KEY, "{}"));
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                JSONObject one = all.optJSONObject(it.next());
                if (one == null) {
                    continue;
                }
                String u = one.optString("u", "");
                if (t.equals(tokenIn(u)) && one.optLong("at", 0) > learnedAt) {
                    learnedAt = one.optLong("at", 0);
                }
            }
        } catch (Throwable ignored) {
        }
        if (learnedAt > 0) {
            long ageMin = (System.currentTimeMillis() - learnedAt) / 60000L;
            // v3.80：顺带比对「最新现场学到的一张票」，入口没跟上就直说。
            String recent = tokenIn(wisdomLatest());
            String warn = (!recent.isEmpty() && !recent.equals(t))
                    ? " · 注意：现场已学到更新的票 " + recent.substring(0, Math.min(10, recent.length()))
                      + "…，本次入口没用上"
                    : "";
            return "入口票据: " + head + "… · App 现场学到的 · "
                    + (ageMin < 1 ? "不到 1 分钟前" : ageMin + " 分钟前") + warn;
        }
        return "入口票据: " + head + "… · 内置备用票（未经现场刷新，可能已过期）";
    }

    /**
     * 用学到的 token 拼目标路由的入口（只用于 wisdom-h5）。
     *
     * <p>v3.81：票只放 hash 一处 —— 这是 App 现场下发的原始形态，也是唯一验证过能换到
     * ssoId 的形态。14:19 日志实证（同一张票 {@code YZsidssolg441f60f…}）：
     * <pre>
     *   index.html#/signInLottery?h5token=X            → jtH5Login code=0000
     *   index.html?h5token=X#/signInLottery?h5token=X  → jtH5Login code=500 系统登录异常
     * </pre>
     * 双写会让页面取错票、拼出服务端不认的加密参数，直接 500。拿不到 token 时返回空串，
     * 绝不拼裸地址。
     */
    static String wisdomEntry(String route) {
        String token = wisdomToken();
        if (token.isEmpty() || route == null || route.isEmpty()) {
            return "";
        }
        String base = "";
        Context ctx = HookLog.context();
        try {
            JSONObject all = new JSONObject(AccountScope.prefs(ctx, PREFS)
                    .getString(KEY, "{}"));
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext() && base.isEmpty()) {
                String id = it.next();
                if (!wisdomId(id)) {
                    continue;
                }
                JSONObject one = all.optJSONObject(id);
                String u = one == null ? "" : one.optString("u", "");
                int i = u.indexOf("index.html");
                if (i > 0) {
                    base = u.substring(0, i + 10);
                }
            }
        } catch (Throwable ignored) {
        }
        if (base.isEmpty()) {
            base = "https://digismart.lncmcc.com/wisdom-h5/index.html";
        }
        // v3.81：只写 hash 一处，复刻 App 现场下发的形态；双写会 500（见方法注释）。
        return base + "#/" + route + "?h5token=" + token;
    }

    private static String paramOf(String s, String name) {
        int q = s.indexOf('?');
        if (q < 0) {
            return "";
        }
        String[] parts = s.substring(q + 1).split("&");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0 && name.equalsIgnoreCase(p.substring(0, eq))) {
                return p.substring(eq + 1);
            }
        }
        return "";
    }

    /** 活动号（供入口候选、真页登记表回溯共用）。 */
    static String actOf(String url) {
        return actIdOf(url);
    }

    private static String actIdOf(String url) {
        if (url == null) {
            return "";
        }
        Matcher m = ACT.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = ACT_LOOSE.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = WISDOM.matcher(url);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.US);
        }
        m = COC3.matcher(url);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.US);
        }
        if (DAILY7.matcher(url).find()) {
            return "dailyloginevent";
        }
        return "";
    }
}
