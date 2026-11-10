package com.cmcchook;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.util.List;

/**
 * App 官方真页 = 任务现场。
 *
 * <p>v4.0.9 起，签到/砸蛋/鹅蛋/福利/卡券/流量这些「执行」动作不再用模块自建的
 * 离屏 WebView，而是直接在 App 自己的 H5 容器（{@code CommonHtml5Activity}）里跑，
 * 脚本、时序、结果回填全部保持原样 —— 只是把「页面」换了。
 *
 * <p>这么改的原因在 11-10 的三份真机日志里：离屏页没有 App 容器的初始化流程
 * （Vue 不 mount、券接口一个不发、余量模块停在 loadInit），补桥、补 Cookie、
 * 复用带参地址都没能补齐；同一时刻真页一打开，接口全 200、Vuex 全量树直接可读。
 *
 * <p>这个类负责三件事：
 * <ol>
 *   <li>登记 App 真页的 WebView（{@link WebViewHooks} 在 loadUrl 回调里喂进来）；</li>
 *   <li>App 不在前台时自动把它拉起来；</li>
 *   <li>用 App 自己的容器打开目标地址，等页面可用后返回一个 {@link WebPage} 句柄。</li>
 * </ol>
 *
 * <p>一次任务串行执行期间会锁定第一个可用的真页（{@link #beginSession()}），
 * 后续活动在这个 WebView 里换地址，不叠出十几个 Activity。
 */
final class RealPage {

    /** 真页登记表；只保留最近若干条，按时间倒序找。 */
    private static final int SLOTS = 12;
    private static final Object[] VIEWS = new Object[SLOTS];
    private static final String[] URLS = new String[SLOTS];
    private static final long[] AT = new long[SLOTS];
    private static int cursor;

    /** 登记超过这么久就不再用（页面可能已经销毁或被切走）。 */
    static final long TTL_MS = 10L * 60_000L;

    /** 任务会话：串行任务共用同一个真页 WebView。 */
    private static volatile Object sessionView;
    private static volatile int sessionDepth;

    private RealPage() {
    }

    // ------------------------------------------------------------------ 登记

    /** WebViewHooks 在 loadUrl 回调里调用。离屏页一律不登记。 */
    static void note(Object view, String url) {
        if (view == null || url == null || url.isEmpty()) {
            return;
        }
        if (url.startsWith("javascript:") || url.startsWith("about:")
                || url.startsWith("data:") || url.startsWith("blob:")) {
            return;
        }
        if (isOffscreen(view)) {
            return;
        }
        synchronized (VIEWS) {
            int i = cursor % SLOTS;
            VIEWS[i] = view;
            URLS[i] = url;
            AT[i] = System.currentTimeMillis();
            cursor++;
        }
    }

    private static boolean isOffscreen(Object view) {
        try {
            Object cur = OffscreenWeb.current();
            return cur != null && cur == view;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 无参地址（scheme://host/path），用来做「同一个页面」的比较键。 */
    static String pathKey(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** 已登记地址与目标页是否同一个路径（参数忽略）。 */
    private static boolean samePath(String registered, String key) {
        if (registered == null || key == null || key.isEmpty()) {
            return false;
        }
        String bare = pathKey(registered);
        return bare.equals(key) || bare.startsWith(key) || key.startsWith(bare);
    }

    /**
     * 找还活着的真页。
     *
     * @param key     路径特征（如 {@code marginQuery}、{@code mycoupons} 或完整无参地址）
     * @param maxAgeMs 最大年龄
     */
    static Object find(String key, long maxAgeMs) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Object best = null;
        long bestAt = 0;
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                Object v = VIEWS[i];
                if (v == null || now - AT[i] > maxAgeMs) {
                    continue;
                }
                if (!URLS[i].contains(key) && !samePath(URLS[i], key)) {
                    continue;
                }
                if (AT[i] > bestAt) {
                    bestAt = AT[i];
                    best = v;
                }
            }
        }
        return best;
    }

    static String describe() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        synchronized (VIEWS) {
            for (int i = 0; i < SLOTS; i++) {
                if (VIEWS[i] == null || now - AT[i] > TTL_MS) {
                    continue;
                }
                sb.append("  · ").append((now - AT[i]) / 1000).append("s 前 ")
                        .append(shortUrl(URLS[i])).append('\n');
            }
        }
        return sb.length() == 0 ? "  · 无\n" : sb.toString();
    }

    // ------------------------------------------------------------------ 会话

    /** 一次任务开始：串行任务共用第一个真页，避免叠 Activity。 */
    static void beginSession() {
        sessionDepth++;
    }

    static void endSession() {
        int left = sessionDepth - 1;
        sessionDepth = left < 0 ? 0 : left;
        if (sessionDepth == 0) {
            sessionView = null;
        }
    }

    // ------------------------------------------------------------------ 前台

    /** App 自己的进程重要性；<=100 表示前台。 */
    private static int importance() {
        try {
            Context ctx = HookLog.context();
            if (ctx == null) {
                return -1;
            }
            ActivityManager am = (ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return -1;
            }
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) {
                return -1;
            }
            int pid = Process.myPid();
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info.pid == pid) {
                    return info.importance;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static boolean foreground() {
        int imp = importance();
        if (imp >= 0) {
            return imp <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        }
        Activity a = ActivityHooks.current();
        return a != null && !a.isFinishing();
    }

    /**
     * 不在前台就把 App 拉起来。
     *
     * @return 最终判定为前台返回 true
     */
    static boolean ensureForeground() {
        if (foreground()) {
            return true;
        }
        HookLog.log("PAGE", "App 不在前台 · 自动拉起", "");
        boolean launched = false;
        try {
            Context ctx = HookLog.context();
            if (ctx != null) {
                Intent i = ctx.getPackageManager()
                        .getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                    ctx.startActivity(i);
                    launched = true;
                }
            }
        } catch (Throwable e) {
            HookLog.logcat("bring app to front failed: " + e);
        }
        if (!launched) {
            try {
                Activity a = ActivityHooks.current();
                if (a != null) {
                    Context ctx = HookLog.context();
                    Intent i = ctx == null ? null : ctx.getPackageManager()
                            .getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(i);
                        launched = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (int i = 0; i < 16; i++) {
            if (foreground()) {
                return true;
            }
            sleep(500L);
        }
        return foreground();
    }

    // ------------------------------------------------------------------ 打开

    /**
     * 拿一个可用的真页。
     *
     * @param url       目标地址
     * @param key       路径特征，用来判断「是不是同一个页面」
     * @param timeoutMs 打开并等待可用状态的上限
     * @return null 表示真页拿不到（调用方退回离屏兜底）
     */
    static WebPage open(String url, String key, long timeoutMs) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        long deadline = System.currentTimeMillis() + Math.max(8_000L, timeoutMs);
        String match = key == null || key.isEmpty() ? pathKey(url) : key;

        Object view = find(match, TTL_MS);
        boolean reused = view != null;

        if (view == null) {
            Object sv = sessionView;
            if (sv != null) {
                view = sv;
                reused = true;
            }
        }

        if (view == null) {
            if (!ensureForeground()) {
                HookLog.log("PAGE", "真页打开失败 · App 拉不起来",
                        "目标: " + shortUrl(url) + "\n已登记的真页:\n" + describe());
                return null;
            }
            if (!openH5(url)) {
                HookLog.log("PAGE", "真页打开失败 · 容器没起来",
                        "目标: " + shortUrl(url));
                return null;
            }
            view = await(match, deadline);
            if (view == null) {
                HookLog.log("PAGE", "真页打开超时 · 容器里没等到目标页面",
                        "目标: " + shortUrl(url) + "\n已登记的真页:\n" + describe());
                return null;
            }
            reused = false;
        }

        WebPage page = WebPage.attachReal(view, url);
        if (page == null) {
            return null;
        }
        if (!reused) {
            page.awaitDoc(Math.min(12_000L, Math.max(4_000L, deadline - System.currentTimeMillis())));
        } else {
            String href = page.href();
            if (href == null || !samePath(href, pathKey(url))) {
                // 同一个真页换地址：这就是「执行下一个活动」，不新建 Activity。
                page.navigateFast(url, Math.max(6_000L, deadline - System.currentTimeMillis()));
            }
        }
        sessionView = view;
        HookLog.log("PAGE", (reused ? "复用真页" : "打开真页") + " · " + shortUrl(url),
                "页面: " + shortUrl(page.href()) + "\n内核: " + page.kernel());
        return page;
    }

    /** 用 App 自己的 H5 容器打开地址。 */
    private static boolean openH5(String url) {
        try {
            Activity a = ActivityHooks.current();
            if (a != null && !a.isFinishing() && Html5.openFrom(a, url)) {
                return true;
            }
        } catch (Throwable e) {
            HookLog.logcat("html5 openFrom failed: " + e);
        }
        try {
            Context ctx = HookLog.context();
            if (ctx != null && Html5.open(ctx, url)) {
                return true;
            }
        } catch (Throwable e) {
            HookLog.logcat("html5 open failed: " + e);
        }
        return false;
    }

    private static Object await(String key, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            Object v = find(key, TTL_MS);
            if (v != null) {
                return v;
            }
            sleep(300L);
        }
        return find(key, TTL_MS);
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "无";
        }
        return url.length() > 140 ? url.substring(0, 140) + "…" : url;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
