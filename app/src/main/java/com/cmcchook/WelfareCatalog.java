package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 福利页清单：内置活动表 + 采集到的新入口。
 *
 * <p>内置表就是「成品」：活动号、活动名、入口地址、执行命令都写死，
 * 装上就能看到、能点、能跑，不需要用户先采集一次。
 * 采集只负责一件事：发现内置表里还没有的新活动，以及在内置地址失效时
 * 提供这一期实际下发的地址。
 *
 * <p>地址会随活动换期变化（比如周三充值的 pageId、coc3 年度代号），
 * 所以执行时优先用采集到的那条，没有才用内置的。两者都没有才算不可用。
 */
final class WelfareCatalog {

    /** 已接入：模块现在就能跑，开关直接生效。 */
    static final int STATE_IMPL = 0;
    /** 待适配：内置了，但还没有对应引擎。 */
    static final int STATE_PENDING = 1;
    /** 只能手动：要好友助力、短信/滑块校验，模块不碰。 */
    static final int STATE_MANUAL = 2;
    /** 仅展示：商品、内容页，没有可做的任务。 */
    static final int STATE_INFO = 3;
    /** 采集发现、内置表里还没有的新活动。 */
    static final int STATE_NEW = 4;

    private static final String WX = "https://wx.10086.cn";
    private static final String COC3 =
            "https://dev.coc.10086.cn/coc3/canvas/rightsmarket-h5-canvas/online/";

    /** 一条清单项。 */
    static final class Item {
        final String key;
        final String name;
        final String desc;
        /** 内置地址；采集到同一活动时会被替换成实际下发的地址。 */
        final String url;
        /** 活动号或路径标识，用来和采集结果对齐。 */
        final String frag;
        final int state;
        /** 执行命令；null 表示没有引擎。 */
        final String cmd;
        /** 是否来自采集发现（不是内置）。 */
        final boolean live;
        /** 默认是否打开（仅对已接入有意义）。 */
        final boolean defaultOn;

        Item(String key, String name, String desc, String url, String frag,
             int state, String cmd, boolean live) {
            this(key, name, desc, url, frag, state, cmd, live, false);
        }

        Item(String key, String name, String desc, String url, String frag,
             int state, String cmd, boolean live, boolean defaultOn) {
            this.key = key;
            this.name = name;
            this.desc = desc;
            this.url = url;
            this.frag = frag;
            this.state = state;
            this.cmd = cmd;
            this.live = live;
            this.defaultOn = defaultOn;
        }

        Item withUrl(String next) {
            return new Item(key, name, desc, next, frag, state, cmd, live, defaultOn);
        }
    }

    /**
     * 内置活动表。
     *
     * <p>只放「模块真能跑完」的活动，做一个加一个。界面上「已接入」就是下面这些：
     * 签到有礼、天天砸金蛋、收鹅蛋换好礼、球赛狂欢季、拆一百元礼盒、周六游戏日。
     * 还没做引擎的活动不往里塞，等做完再加，避免列表里一堆点了没反应的项。
     */
    private static final Item[] BUILT_IN = {
            new Item("sign", "签到有礼", "签到 + 每日任务 + AI豆",
                    // 冷启动兜底：11.9.1 抓包实证 App 点进签到用的真实入口，
                    // 带渠道参数。服务端靠它判渠道开放，缺了会 302 到
                    // channelNonsupport（访问渠道未开放）。现场学到更新的会自动覆盖。
                    WX + "/qwhdhub/qwhdmark/1021122301"
                            + "?touch_id=JTST_P00000010910&yx=JHQD9999999999", "1021122301",
                    STATE_IMPL, TaskRunner.CMD_SIGN, false, true),

            new Item("egg", "天天砸金蛋", "8 个浏览任务 + 当日次数抽完",
                    WX + "/qwhdhub/goldeneggs/1026071510?A_C_CODE=HraVsPIWok", "1026071510",
                    STATE_IMPL, TaskRunner.CMD_EGG, false, true),

            new Item("goose", "收鹅蛋 换好礼", "打卡 / 投喂 / 收蛋 / 领任务",
                    "https://lyh.lncmcc.com/lyhVue/index.html#/bgIndex?actNo=goose2025",
                    "raisegoose", STATE_IMPL, TaskRunner.CMD_GOOSE, false, true),

            // 辽宁 lyh 域，加密接口，靠离屏页内调页面方法/接口封装完成。
            new Item("soccer", "球赛狂欢季", "竞猜任务，攒竞猜票（不代投注）",
                    "https://lyh.lncmcc.com/lyhVue/index.html#/footballGuess"
                            + "?actNo=AC2026DBCQUEZQ1G",
                    "footballguess", STATE_IMPL, TaskRunner.CMD_SOCCER, false, true),

            // qwhdhub 明文接口：task/list → startBrowse → lotterySafely。
            new Item("tearbox", "拆一百元礼盒", "做浏览任务加次数 + 拆礼盒",
                    WX + "/qwhdhub/tearbox/1026102314?A_C_CODE=jtzQwadx30", "1026102314",
                    STATE_IMPL, TaskRunner.CMD_TEARBOX, false, true),

            // diy-client 1126086939：每日签到 + 任务 + 抽奖，纯 qwhdhub 接口。
            new Item("sat_game", "周六游戏日", "每日签到 + 任务 + 抽奖",
                    WX + "/qwhdhub/diy-client/1126086939?A_C_CODE=pXMkQhEmZ7", "1126086939",
                    STATE_IMPL, TaskRunner.CMD_SATURDAY, false, true),

            // 视频日活动 1126082530：签到 + 视频任务 + 抽奖，仍是 diy 体系，
            // 只是签到走 diyVideoDayRedesign 专属接口。
            new Item("video", "追剧领福利", "签到 + 视频任务 + 抽奖",
                    WX + "/qwhdhub/diy-client/1126082530?A_C_CODE=10hep1ZhkL", "1126082530",
                    STATE_IMPL, TaskRunner.CMD_VIDEO, false, true),

            // coc web6：进页面即打卡，页面自己记当天进度。
            new Item("daily7", "7日打卡分百万", "每日进页打卡，连签 7 天",
                    "https://dev.coc.10086.cn/coc/web6/dailyLoginEvent/index"
                            + "?pageId=2090341662452445184&channelId=P00000005743", "dailyloginevent",
                    STATE_IMPL, TaskRunner.CMD_DAILY7, false, true),

            // coc3 画布：签到领阳光值 + 浇水施肥养豆苗，同一页。
            new Item("miao", "AI豆苗养成记", "签到领阳光值 + 浇水施肥",
                    "https://dev.coc.10086.cn/coc3/canvas/rightsmarket-h5-canvas/online/2026dmycj"
                            + "?channelCode=P00000153130", "2026dmycj",
                    STATE_IMPL, TaskRunner.CMD_MIAO, false, true),

            // 以下三个是 coc3 权益市场画布活动：签到/翻牌由页面组件发请求。
            // qwhdhub 砸蛋模板：浏览任务换次数，lotterySafely 抽奖。
            // 1025093012 是「充值先领券」，接口和天天砸金蛋同一套，只是活动号不同。
            new Item("recharge", "充值先领券", "浏览任务换次数 + 砸蛋抽奖",
                    WX + "/qwhdhub/goldeneggs/1025093012?A_C_CODE=RLnnD8f2p6", "1025093012",
                    STATE_IMPL, TaskRunner.CMD_RECHARGE, false, true),

            // hlwyxhdhub 周三充值日：连续七天打卡，每天一次 8 折充值券，
            // 打卡任务（ljdkN）每次加一次抽奖机会。会话靠 URL 上的 QWHDSSOD token。
            new Item("wed", "周三充值日", "七天打卡 + 8折券 + 抽奖",
                    WX + "/hlwyxhdhub/act-wedrecharge/1024101716"
                            + "?pageId=1849008675699650560&channelId=P00000003839",
                    "act-wedrecharge", STATE_IMPL, TaskRunner.CMD_WED, false, true),

            // 签到有礼的秒杀专区：每天 12:00 一场，库存通常只有 1 张。
            // 必须准点手动触发，所以默认不进「一键全部」，只在这里单独点。
            new Item("seckill", "12点抢券", "签到有礼 · 每日 12 点秒杀话费券",
                    WX + "/qwhdhub/qwhdmark/1021122301"
                            + "?touch_id=JTST_P00000010910&yx=JHQD9999999999", "seckill",
                    STATE_IMPL, TaskRunner.CMD_SECKILL, false, false),

            new Item("lucky3", "幸运三日签", "累计签到 + 每三天抽奖",
                    COC3 + "26xysrqtyld?channelCode=P00000153130", "26xysrqtyld",
                    STATE_IMPL, TaskRunner.CMD_LUCKY3, false, true),

            new Item("weekly", "周周有好礼", "每周翻牌 + 月度翻牌",
                    COC3 + "zzcj2?channelCode=P00000153130", "zzcj2",
                    STATE_IMPL, TaskRunner.CMD_WEEKLY, false, true),

            // wisdom-h5（米粒商城）：签到 + 任务 + 转盘。h5token 由福利专区下发，
            // 采到带 token 的真实地址会覆盖内置地址；采不到就用内置的裸路由。
            // v3.62：这站只认地址上的 h5token（页面拿它换 ssoId），裸路由必然 403。
            // v3.81：票只放 hash 一处。14:19 日志实证：同一张票双写（外层 + hash）
            // 会让 jtH5Login 回 500「系统登录异常」，只写 hash 才回 0000。
            // v4.10.0-r2：按用户要求改成默认关闭，名字后面标 (辽宁) —— 这两个
            // 活动是辽宁域的，别的省号进去也做不了，不该默认进「一键全部」。
            new Item("signlottery", "签到抽奖赢好礼(辽宁)", "每日签到 + 任务 + 转盘抽奖",
                    // 11-10 22:57 实证：这行以前写死的是一张早已过期的票
                    // （jtH5Login 回 500「系统登录异常」），模块一直拿它硬闯。
                    // 现在只留路由，票由「福利专区现场领取」补进来。
                    "https://digismart.lncmcc.com/wisdom-h5/index.html"
                            + "#/signInLottery",
                    "signinlottery", STATE_IMPL, TaskRunner.CMD_SIGNLOTTERY, false, false),

            // 同一个 SPA 的答题路由：getQuestions 直接下发 isRight，答对率 100%。
            // v3.68：内置地址必须带 h5token。08:50 那次学到的是裸路由，
            // 裸地址换不到 ssoId，进去只会看到未登录。token 与签到抽奖共用一份。
            new Item("quiz", "乡友农业知识问答(辽宁)", "每天 3 道判断题，答案随题目下发",
                    "https://digismart.lncmcc.com/wisdom-h5/index.html"
                            + "#/answerQuestions",
                    "answerquestions", STATE_IMPL, TaskRunner.CMD_QUIZ, false, false),
    };

    private WelfareCatalog() {
    }

    /** 内置表的只读副本，给采集报告当同类参考。 */
    static List<Item> built() {
        List<Item> list = new ArrayList<>();
        for (Item one : BUILT_IN) {
            list.add(one);
        }
        return list;
    }

    /** 按活动号/路径标识找内置条目，找不到返回 null。 */
    static Item byFrag(String frag) {
        if (frag == null || frag.isEmpty()) {
            return null;
        }
        String want = idOf(frag);
        if (want.isEmpty()) {
            want = frag.toLowerCase(Locale.US).trim();
        }
        for (Item one : BUILT_IN) {
            if (one.frag.toLowerCase(Locale.US).equals(want)) {
                return one;
            }
        }
        return null;
    }

    /**
     * 已从内置表下线、暂时不展示的旧活动号。
     *
     * <p>「做一个加一个」：这轮只保留六个做完的活动，采集缓存里如果还留着
     * 以前那些没做引擎的活动，不再混进清单里。以后做完一个从这里删一个。
     */
    private static final String[] RETIRED = {
            "1126090514", "1126090575",
            "264xysryqydh", "1025103186",
            "ydagrjh", "2026dmycj", "26hlyyltyld", "26xysrqtybld",
            "sxkhyczb", "szhhsxxzq", "bojinyhb", "mshytbsg2025mall",
            "1026082720", "1126037461"
    };

    /**
     * v4.2.0：用户自己删掉的「采集发现」条目。
     *
     * <p>内置活动不允许删（删了就再也找不回来，页面上会整块消失）；采集发现的新入口
     * 是模块扫出来的，用户不想要就该能删。删掉的 id 存在 settings 里，下次扫描即使
     * 又扫到同一条也不再冒出来。
     */
    private static final String KEY_HIDDEN = "welfare_hidden";

    /** v4.10.0-r2 一次性迁移标记：签到抽奖 / 乡友问答改默认关闭。 */
    private static final String KEY_OFF_MIGRATED = "welfare_off_migrated_v410";

    /**
     * 把「签到抽奖赢好礼(辽宁)」「乡友农业知识问答(辽宁)」显式关一次。
     *
     * <p>只改 {@code defaultOn} 对老用户无效 —— 他们本地早就存了 {@code true}。
     * 所以这里做一次性迁移：第一次跑时把两个开关写成关，之后不再动，
     * 用户自己再打开就保持打开（标记只写一次）。
     */
    private static void migrateDefaultOff(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = prefs(ctx);
            if (p.getBoolean(KEY_OFF_MIGRATED, false)) {
                return;
            }
            p.edit()
                    .putBoolean("welfare_signlottery", false)
                    .putBoolean("welfare_quiz", false)
                    .putBoolean(KEY_OFF_MIGRATED, true)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean hidden(Context ctx, String id) {
        if (ctx == null || id == null || id.isEmpty()) {
            return false;
        }
        try {
            java.util.Set<String> set = prefs(ctx).getStringSet(KEY_HIDDEN, null);
            return set != null && set.contains(id.toLowerCase(Locale.US));
        } catch (Throwable e) {
            return false;
        }
    }

    static void hide(Context ctx, String id) {
        if (ctx == null || id == null || id.isEmpty()) {
            return;
        }
        try {
            java.util.Set<String> old = prefs(ctx).getStringSet(KEY_HIDDEN, null);
            java.util.Set<String> next = new java.util.LinkedHashSet<>();
            if (old != null) {
                next.addAll(old);
            }
            next.add(id.toLowerCase(Locale.US));
            prefs(ctx).edit().putStringSet(KEY_HIDDEN, next).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 这个活动号是不是已经下线、不展示。 */
    private static boolean retired(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        String want = id.toLowerCase(Locale.US);
        for (String one : RETIRED) {
            if (one.equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清单 = 内置表 + 采集发现的新入口。
     *
     * <p>同一个活动在内置表里有时，用采集到的地址覆盖内置地址：
     * 采集到的是这一期实际下发的，带了当期参数。
     */
    static List<Item> items(Context ctx) {
        migrateDefaultOff(ctx);
        List<Item> built = new ArrayList<>();
        for (Item one : BUILT_IN) {
            built.add(one);
        }
        List<Item> fresh = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String[] one : ProbeStore.candidates(ctx)) {
            String url = one == null || one.length < 2 ? "" : one[1];
            if (url == null || url.isEmpty()) {
                continue;
            }
            String id = idOf(url);
            if (id.isEmpty() || retired(id)) {
                continue;
            }
            int hit = -1;
            for (int i = 0; i < built.size(); i++) {
                if (id.equals(built.get(i).frag)) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                // v4.2.0：wisdom-h5 那两条（签到抽奖 / 乡友问答）不能被商城的页面地址覆盖。
                // 采集时「签到抽奖」按钮跳的是福利专区首页，覆盖之后「在软件内打开」
                // 就进了商城，页面根本不认识那些路由。
                String frag = built.get(hit).frag;
                boolean wisdom = "signinlottery".equals(frag) || "answerquestions".equals(frag);
                if (!wisdom || url.contains("wisdom-h5") || url.contains("digismart.lncmcc.com")) {
                    built.set(hit, built.get(hit).withUrl(url));
                }
                continue;
            }
            if (!looksLikeActivity(url) || !seen.add(id)) {
                continue;
            }
            // 用户删掉过的，扫描再扫到也不再冒出来。
            if (hidden(ctx, id)) {
                continue;
            }
            String name = one[0] == null || one[0].isEmpty() ? "新入口 · " + id : one[0];
            fresh.add(new Item("new_" + id, name, "采集发现 · 内置表里还没有",
                    url, id, STATE_NEW, engineOf(url), true));
        }

        List<Item> impl = new ArrayList<>();
        List<Item> pending = new ArrayList<>();
        List<Item> manual = new ArrayList<>();
        for (Item item : built) {
            if (item.state == STATE_IMPL) {
                impl.add(item);
            } else if (item.state == STATE_MANUAL) {
                manual.add(item);
            } else {
                pending.add(item);
            }
        }
        List<Item> list = new ArrayList<>(impl);
        list.addAll(fresh);
        list.addAll(pending);
        list.addAll(manual);
        return list;
    }

    /**
     * 按地址判断这条入口归哪个引擎管。
     *
     * <p>判断依据是活动页的前端，不是活动号：diy 引擎的接口是
     * {@code run(actId, pageUrl)} 全参数化的，任务列表、补领、抽奖都在运行时读。
     */
    static String engineOf(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        // wisdom-h5 是一个 SPA，两个活动只差 hash 路由；不先按路由切开，
        // idOf 会把它们都归成「wisdom-h5」，采集缓存互相覆盖。
        if (lower.contains("signinlottery")) {
            return TaskRunner.CMD_SIGNLOTTERY;
        }
        if (lower.contains("answerquestions")) {
            return TaskRunner.CMD_QUIZ;
        }
        // lyhVue 有两个活动，必须先按路由区分，否则会被下面的 lyhvue 判成鹅蛋。
        if (lower.contains("footballguess") || lower.contains("greensoccer")) {
            return TaskRunner.CMD_SOCCER;
        }
        if (lower.contains("/tearbox/")) {
            return TaskRunner.CMD_TEARBOX;
        }
        // coc3 权益市场画布活动：地址最后一段就是 pageKey。
        if (lower.contains("dailyloginevent")) {
            return TaskRunner.CMD_DAILY7;
        }
        if (lower.contains("2026dmycj")) {
            return TaskRunner.CMD_MIAO;
        }
        if (lower.contains("26xysrqtyld")) {
            return TaskRunner.CMD_LUCKY3;
        }
        if (lower.contains("264xysryqydh") || lower.contains("/ydagrjh")) {
            // 已下线：爱购日没有任务，只在周五 10 点抢购。
            return null;
        }
        if (lower.contains("1025093012")) {
            return TaskRunner.CMD_RECHARGE;
        }
        if (lower.contains("act-wedrecharge") || lower.contains("1024101716")) {
            return TaskRunner.CMD_WED;
        }
        if (lower.contains("/zzcj2")) {
            return TaskRunner.CMD_WEEKLY;
        }
        String diy = idAfter(url, "/diy-client/");
        if (diy != null) {
            if ("1126086939".equals(diy)) {
                return TaskRunner.CMD_SATURDAY;
            }
            if ("1126082530".equals(diy)) {
                return TaskRunner.CMD_VIDEO;
            }
            return TaskRunner.CMD_DIY + diy;
        }
        if (lower.contains("/qwhdmark/")) {
            return TaskRunner.CMD_SIGN;
        }
        if (lower.contains("/goldeneggs/")) {
            return TaskRunner.CMD_EGG;
        }
        if (lower.contains("raisegoose") || lower.contains("lyhvue")) {
            return TaskRunner.CMD_GOOSE;
        }
        return null;
    }

    /** 取路径段后面的活动号（纯数字，至少 6 位）。 */
    private static String idAfter(String url, String path) {
        int at = url.indexOf(path);
        if (at < 0) {
            return null;
        }
        int start = at + path.length();
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        String id = url.substring(start, end);
        return id.length() >= 6 ? id : null;
    }

    /** 内置表里这个活动的地址；没有返回空串。frag 可以是活动号，也可以是路径片段。 */
    static String builtUrl(String frag) {
        if (frag == null || frag.isEmpty()) {
            return "";
        }
        String want = idOf(frag);
        if (want.isEmpty()) {
            want = frag.toLowerCase(Locale.US).trim();
        }
        for (Item one : BUILT_IN) {
            if (one.frag.toLowerCase(Locale.US).equals(want)) {
                return one.url;
            }
        }
        return "";
    }

    /** 把地址归一到活动号：coc3 取最后一段，act-wedrecharge 取固定标识。 */
    static String idOf(String url) {
        if (url == null) {
            return "";
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains("signinlottery")) {
            return "signinlottery";
        }
        if (lower.contains("answerquestions")) {
            return "answerquestions";
        }
        if (lower.contains("act-wedrecharge")) {
            return "act-wedrecharge";
        }
        // v3.65：周三充值日有两条身份 —— 路径片段 act-wedrecharge、活动号 1024101716。
        // 内置表按前者的 frag 存，TaskApi.findEntry("1024101716") 按后者查，
        // 归一不到一起就永远「没有可用入口」（11-08 08:00 日志实证）。
        if (lower.contains("1024101716")) {
            return "act-wedrecharge";
        }
        if (lower.contains("dailyloginevent")) {
            return "dailyloginevent";
        }
        if (lower.contains("footballguess") || lower.contains("greensoccer")) {
            return "footballguess";
        }
        if (lower.contains("raisegoose") || lower.contains("lyhvue")) {
            return "raisegoose";
        }
        String path = url;
        int cut = path.indexOf('?');
        if (cut > 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut > 0) {
            path = path.substring(0, cut);
        }
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String one = parts[i].trim();
            if (one.isEmpty() || one.contains(".")) {
                continue;
            }
            return one.toLowerCase(Locale.US);
        }
        return "";
    }

    /** 路径里带这些词的基本都是活动页，不是商品。 */
    static boolean looksLikeActivity(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        String[] hints = {"qwhdhub", "hlwyxhdhub", "rightsmarket-h5-canvas",
                "diy-client", "goldeneggs", "turntable", "newbuildgroup", "coc3",
                "coc/web6"};
        for (String hint : hints) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 实际可用的地址：清单里那条就是最终地址（内置或采集覆盖后的）。 */
    static String liveUrl(Context ctx, Item item) {
        return item == null || item.url == null ? "" : item.url;
    }

    /** 全部开关里打开了几项。 */
    static int enabledCount(Context ctx) {
        int count = 0;
        for (Item item : items(ctx)) {
            if (enabled(ctx, item)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 开关状态。
     *
     * <p>走 {@link HookPrefs#flag}：福利页画在模块进程，任务跑在中国移动进程，
     * 直接读本地 SharedPreferences 会永远拿到默认值。
     */
    static boolean enabled(Context ctx, Item item) {
        if (item == null) {
            return false;
        }
        if (!runnable(item)) {
            return false;
        }
        // 签到、砸蛋、鹅蛋跟「功能」页共用同一个开关，避免两处各存一份、互相打架。
        String shared = sharedKey(item);
        if (shared != null) {
            return readFlag(ctx, shared, true);
        }
        // 内置的已接入活动默认开；采集发现的新活动默认关，等用户自己确认。
        return readFlag(ctx, "welfare_" + item.key, item.defaultOn);
    }

    /**
     * 两个进程读同一份开关。
     *
     * <p>模块进程直接读自己的 SharedPreferences（写也是写这里）；
     * 中国移动进程读不到别人的 data 目录，只能走 XSharedPreferences。
     */
    private static boolean readFlag(Context ctx, String key, boolean def) {
        String pkg = ctx == null ? "" : ctx.getPackageName();
        if (HookLog.MODULE_PACKAGE.equals(pkg)) {
            return prefs(ctx).getBoolean(key, def);
        }
        return HookPrefs.flag(key, def);
    }

    static void setEnabled(Context ctx, Item item, boolean on) {
        if (ctx == null || item == null) {
            return;
        }
        String shared = sharedKey(item);
        prefs(ctx).edit()
                .putBoolean(shared != null ? shared : "welfare_" + item.key, on)
                .apply();
    }

    /** 已接入活动对应的原开关；其余返回 null。 */
    static String sharedKey(Item item) {
        if (item == null || item.cmd == null) {
            return null;
        }
        if (TaskRunner.CMD_SIGN.equals(item.cmd)) {
            return AppPrefs.KEY_PAGE_SIGN;
        }
        if (TaskRunner.CMD_EGG.equals(item.cmd)) {
            return AppPrefs.KEY_PAGE_EGG;
        }
        if (TaskRunner.CMD_GOOSE.equals(item.cmd)) {
            return AppPrefs.KEY_PAGE_GOOSE;
        }
        return null;
    }

    /** 按命令反查活动名。 */
    static String nameOf(String cmd) {
        if (cmd == null) {
            return null;
        }
        for (Item item : BUILT_IN) {
            if (cmd.equals(item.cmd)) {
                return item.name;
            }
        }
        if (cmd.startsWith(TaskRunner.CMD_DIY)) {
            return "活动 " + cmd.substring(TaskRunner.CMD_DIY.length());
        }
        return null;
    }

    /** 已接入的活动按开关决定今天跑不跑。 */
    static boolean commandEnabled(Context ctx, String cmd) {
        if (cmd == null) {
            return false;
        }
        for (Item item : items(ctx)) {
            if (cmd.equals(item.cmd)) {
                return enabled(ctx, item);
            }
        }
        return false;
    }

    static long liveAt(Context ctx) {
        long at = ProbeStore.at(ctx);
        return at > 0 ? at : 0L;
    }

    /** 采集到的新入口数量（内置表里还没有的）。 */
    static int liveCount(Context ctx) {
        int count = 0;
        for (Item item : items(ctx)) {
            if (item.live) {
                count++;
            }
        }
        return count;
    }

    /**
     * 这个入口有没有引擎能跑。
     *
     * <p>只看地址、不读 prefs：盘点跑在中国移动进程里，
     * 那边的 SharedPreferences 是 App 自己的，读不到模块的设置。
     */
    static boolean known(String url) {
        if (engineOf(url) != null) {
            return true;
        }
        String id = idOf(url);
        for (Item one : BUILT_IN) {
            if (one.frag.equals(id)) {
                return true;
            }
        }
        return false;
    }

    static String stateName(int state) {
        if (state == STATE_IMPL) {
            return "已接入";
        }
        if (state == STATE_PENDING) {
            return "待适配";
        }
        if (state == STATE_NEW) {
            return "新发现";
        }
        if (state == STATE_INFO) {
            return "仅展示";
        }
        return "仅手动";
    }

    /** 该条目能不能自动跑（只有已接入且有命令的可以）。 */
    static boolean runnable(Item item) {
        if (item == null || item.cmd == null) {
            return false;
        }
        // 采集发现的新活动如果有引擎认领，也能跑，但默认关着，等用户自己开。
        return item.state == STATE_IMPL || item.state == STATE_NEW;
    }

    private static SharedPreferences prefs(Context ctx) {
        return AppPrefs.settings(ctx);
    }
}
