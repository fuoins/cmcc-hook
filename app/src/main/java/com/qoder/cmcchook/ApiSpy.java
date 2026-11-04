package com.qoder.cmcchook;

import java.util.Locale;

/**
 * 接口归类：把抓到的请求按「属于哪个活动」打上标签，并滤掉埋点噪音。
 *
 * 为什么需要它：日志里一次首页加载就有 80 多条极光埋点、几十条广告位，
 * 真正的业务接口夹在中间根本翻不出来。开发者要的是「签到那套接口在哪、
 * 请求体长什么样」，不是「这一屏曝光上报了几次」。
 *
 * 归类只影响日志的 tag，不影响抓取本身 —— 「全部」视图里埋点照样在，
 * 只是「接口」视图会把它们挡掉。等所有活动都实现完，这个视图就可以不用了。
 */
final class ApiSpy {

    static final String SIGN = "SIGN";
    static final String EGG = "EGG";
    static final String GOOSE = "GOOSE";
    static final String SSO = "SSO";
    static final String ACT = "ACT";
    static final String WEB = "WEB";

    /** 埋点 / 监控 / 静态资源：不是业务接口。 */
    private static final String[] NOISE = {
            "jiguang", "growing", "sensors", "umeng", "bugly",
            "apm.app", "push.it", "res.coc", "/cache_server/",
            "ngpsie", "psieapp", "/favicon", "/collect",
            "/monitor/", "reportv2", "/beacon", "stat.10086",
    };

    /** 10086 系业务接口所在的域 / 路径前缀。 */
    private static final String[] BIZ = {
            "qwhdhub", "qwhdsso", "hlwyxhdhub", "lyh.lncmcc.com",
            "biz-orange", "wmhnewcenter", "wmhopenapi", "leadeon-",
    };

    private ApiSpy() {
    }

    /** 埋点、监控、静态资源。 */
    static boolean noise(String url) {
        if (url == null) {
            return true;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String word : NOISE) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".css")
                || lower.endsWith(".woff") || lower.endsWith(".woff2");
    }

    /** 业务接口（包含但不限于三个活动）。 */
    static boolean business(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String word : BIZ) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 归到哪个活动；null 表示不是已知活动接口。 */
    static String category(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains("qwhdsso") || lower.contains("apptokenlogin")) {
            return SSO;
        }
        if (lower.contains("qwhdmark") || lower.contains("/mark/")
                || lower.contains("mark31")) {
            return SIGN;
        }
        if (lower.contains("goldeneggs") || lower.contains("lottery")) {
            return EGG;
        }
        if (lower.contains("lncmcc.com") || lower.contains("raisegoose")) {
            return GOOSE;
        }
        if (lower.contains("qwhdhub") || lower.contains("hlwyxhdhub")) {
            return ACT;
        }
        return null;
    }

    /** 日志 tag：活动接口 API-SIGN，其它业务接口 API-WEB，噪音保持原 tag。 */
    static String tag(String url, String fallback) {
        if (url == null || url.isEmpty() || noise(url)) {
            return fallback;
        }
        String cat = category(url);
        if (cat != null) {
            return "API-" + cat;
        }
        return business(url) ? "API-WEB" : fallback;
    }

    /** 给 summary 用的中文前缀，没有归类就返回空串。 */
    static String prefix(String tag) {
        if (tag == null) {
            return "";
        }
        if (tag.equals("API-" + SIGN)) {
            return "签到 ";
        }
        if (tag.equals("API-" + EGG)) {
            return "砸蛋 ";
        }
        if (tag.equals("API-" + GOOSE)) {
            return "鹅蛋 ";
        }
        if (tag.equals("API-" + SSO)) {
            return "登录 ";
        }
        if (tag.equals("API-" + ACT)) {
            return "活动 ";
        }
        return "";
    }

    static String label(String tag) {
        String prefix = prefix(tag);
        return prefix.isEmpty() ? "接口" : prefix.trim();
    }
}
