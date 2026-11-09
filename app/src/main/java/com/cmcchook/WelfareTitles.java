package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Locale;

/**
 * 活动页标题缓存。
 *
 * <p>清单里的名字一律取活动页自己的 {@code document.title}，不自己编。
 * 标题有两个来源：用户正常打开活动页时顺手记一条；手动采集时对还没名字的
 * 候选挨个打开读一次。读到的标题按「去掉参数后的地址」存七天。
 */
final class WelfareTitles {

    private static final String FILE = "cmcc_welfare_titles";
    private static final long TTL = 7 * 24 * 3600_000L;
    private static final int MAX_FILL = 16;
    private static final long PAGE_TIMEOUT = 3_000L;

    private static final String[] HOSTS = {"10086.cn", "lncmcc.com", "cmcc-cs.cn"};

    private static final String[] ACT_HINTS = {
            "qwhdhub", "hlwyxhdhub", "rightsmarket-h5-canvas", "diy-client",
            "goldeneggs", "turntable", "newbuildgroup", "coc3", "act-wedrecharge",
            "raisegoose", "act-", "rightsmarket",
    };

    private WelfareTitles() {
    }

    /** 用户打开页面时顺手记一条。 */
    static void put(Context ctx, String url, String title) {
        if (ctx == null) {
            return;
        }
        String key = keyOf(url);
        String clean = clean(title);
        if (key.isEmpty() || clean.isEmpty()) {
            return;
        }
        try {
            prefs(ctx).edit()
                    .putString(key, System.currentTimeMillis() + "\n" + clean)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 取缓存标题；过期或没有返回空串。 */
    static String get(Context ctx, String url) {
        if (ctx == null) {
            return "";
        }
        String key = keyOf(url);
        if (key.isEmpty()) {
            return "";
        }
        try {
            String raw = prefs(ctx).getString(key, "");
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            int cut = raw.indexOf('\n');
            if (cut <= 0) {
                return "";
            }
            long at = Long.parseLong(raw.substring(0, cut));
            if (System.currentTimeMillis() - at > TTL) {
                return "";
            }
            return raw.substring(cut + 1);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 采集后补齐候选标题。
     *
     * <p>只处理看起来像活动的地址，已有名字或已有缓存的直接跳过。
     * 打开期间关掉「进页面自动跑」，否则读标题会顺手把活动任务跑了。
     */
    static int fill(Context ctx, List<String[]> found, StringBuilder detail) {
        if (ctx == null || found == null) {
            return 0;
        }
        int filled = 0;
        int tried = 0;
        boolean wasSuppressed = WebSession.suppressing();
        for (String[] one : found) {
            if (one == null || one.length < 2) {
                continue;
            }
            String url = one[1];
            if (one[0] != null && !one[0].isEmpty()) {
                continue;
            }
            if (!looksLikeActivity(url)) {
                continue;
            }
            String cached = get(ctx, url);
            if (!cached.isEmpty()) {
                one[0] = cached;
                continue;
            }
            if (tried >= MAX_FILL) {
                break;
            }
            tried++;
            WebPage page = null;
            try {
                WebSession.suppress(true);
                page = WebPage.open(url, PAGE_TIMEOUT, HOSTS, null, false);
                if (page == null) {
                    continue;
                }
                String title = clean(page.title());
                if (title.isEmpty()) {
                    title = clean(page.eval("document.title", 1_500L));
                }
                if (!title.isEmpty()) {
                    one[0] = title;
                    put(ctx, url, title);
                    filled++;
                }
            } catch (Throwable e) {
                HookLog.logcat("title read failed: " + e);
            } finally {
                WebSession.suppress(wasSuppressed);
                if (page != null) {
                    page.close();
                }
            }
        }
        if (detail != null && tried > 0) {
            detail.append("活动页标题: 新读 ").append(filled)
                    .append(" 个 / 尝试 ").append(tried).append(" 个\n");
        }
        return filled;
    }

    private static boolean looksLikeActivity(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String hint : ACT_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        if (t.isEmpty() || "中国移动".equals(t) || "网页无法打开".equals(t)
                || "404".equals(t) || "null".equalsIgnoreCase(t)) {
            return "";
        }
        return t;
    }

    private static String keyOf(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int cut = u.indexOf('?');
        if (cut > 0) {
            u = u.substring(0, cut);
        }
        cut = u.indexOf('#');
        if (cut > 0) {
            u = u.substring(0, cut);
        }
        return u.toLowerCase(Locale.US);
    }

    private static SharedPreferences prefs(Context ctx) {
        // v3.57：活动入口清单按账号隔离（两个账号看到的福利不一样）。
        return AccountScope.prefs(ctx, FILE);
    }
}
