package com.qoder.cmcchook;

import android.content.Context;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * 进活动页自动执行。
 *
 * 这是 v1.9 的核心变化：不再要求用户回模块点按钮，而是盯着 WebView 的页面加载，
 * 一旦发现进的是三个活动页之一，就按对应开关自动把那个活动的任务跑一遍。
 *
 * 几个约束：
 *   · 同一个活动 10 分钟内只自动触发一次（活动页内部路由会反复触发 onPageFinished）；
 *   · 当天已经成功跑过的活动不再自动跑（手动按钮不受限制）；
 *   · 延迟 2~3 秒再跑，等页面把 Cookie、token、活动配置都加载完。
 */
final class PageAuto {

    private static final long WINDOW_MS = 10 * 60_000L;
    private static final long WEBVIEW_TTL = 5 * 60_000L;

    private static final android.os.Handler MAIN =
            new android.os.Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, Long> LAST_RUN = new ConcurrentHashMap<>();

    private static volatile WeakReference<Object> lastWebView = new WeakReference<>(null);
    private static volatile String lastUrl = "";
    private static volatile long lastUrlAt;

    private PageAuto() {
    }

    /** WebView 钩子在 onPageFinished / doUpdateVisitedHistory 里调用。 */
    static void onPage(Object webView, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        lastUrl = url;
        lastUrlAt = System.currentTimeMillis();
        if (webView != null) {
            lastWebView = new WeakReference<>(webView);
        }

        final String cmd = match(url);
        if (cmd == null) {
            return;
        }
        if (!enabled(cmd)) {
            HookLog.logcat("page auto disabled for " + cmd);
            return;
        }
        Context ctx = HookLog.context();
        if (ctx != null && TaskRunner.isDoneToday(ctx, cmd)) {
            HookLog.logcat("page auto skipped, done today: " + cmd);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_RUN.get(cmd);
        if (last != null && now - last < WINDOW_MS) {
            return;
        }
        LAST_RUN.put(cmd, now);

        long delay = TaskRunner.CMD_GOOSE.equals(cmd) ? 1_500L : 2_500L;
        HookLog.log("AUTO", "进入活动页 · " + TaskRunner.label(cmd),
                "URL: " + url + "\n" + (int) (delay / 1000) + " 秒后自动执行（可在模块里关掉）");
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                TaskRunner.executeAuto(cmd, "进页面");
            }
        }, delay);
    }

    static String match(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase();
        if (u.contains("qwhdmark/1021122301") || u.contains("qwhdmark%2f1021122301")) {
            return TaskRunner.CMD_SIGN;
        }
        if (u.contains("goldeneggs/1026071510") || u.contains("goldeneggs%2f1026071510")) {
            return TaskRunner.CMD_EGG;
        }
        if (u.contains("lyh.lncmcc.com") && (u.contains("lyhvue") || u.contains("raisegoose"))) {
            return TaskRunner.CMD_GOOSE;
        }
        return null;
    }

    private static boolean enabled(String cmd) {
        if (TaskRunner.CMD_SIGN.equals(cmd)) {
            return HookPrefs.pageSign();
        }
        if (TaskRunner.CMD_EGG.equals(cmd)) {
            return HookPrefs.pageEgg();
        }
        if (TaskRunner.CMD_GOOSE.equals(cmd)) {
            return HookPrefs.pageGoose();
        }
        return false;
    }

    // ------------------------------------------------------- 给浏览任务用

    /** 当前活动页的 WebView 还在不在。 */
    static Object webView() {
        Object wv = lastWebView.get();
        if (wv == null || System.currentTimeMillis() - lastUrlAt > WEBVIEW_TTL) {
            return null;
        }
        return wv;
    }

    static String currentUrl() {
        return lastUrl;
    }

    /**
     * 在活动页 WebView 里打开一个任务页，停留 ms 毫秒，再打开 backUrl 回活动页。
     * 只用于「浏览 5 秒」这类任务：页面自己会上报埋点，模块不伪造请求。
     *
     * WebView 的方法只能在主线程调，这里统一切过去。
     */
    static boolean visit(final String url, final String backUrl, final long stayMs) {
        final Object wv = webView();
        if (wv == null || url == null || url.isEmpty()) {
            return false;
        }
        // 只有当前还停在签到活动页时才跳，避免把用户正在看的别的页面顶掉
        if (!TaskRunner.CMD_SIGN.equals(match(lastUrl))) {
            return false;
        }
        final boolean[] ok = {false};
        final java.util.concurrent.CountDownLatch wait =
                new java.util.concurrent.CountDownLatch(1);
        Runnable load = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(wv, "loadUrl", url);
                    ok[0] = true;
                } catch (Throwable e) {
                    HookLog.logcat("visit loadUrl failed: " + e);
                } finally {
                    wait.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            load.run();
        } else {
            MAIN.post(load);
            try {
                wait.await(4, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (!ok[0]) {
            return false;
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(wv, "loadUrl", backUrl);
                } catch (Throwable e) {
                    HookLog.logcat("visit back failed: " + e);
                }
            }
        }, stayMs);
        return true;
    }
}
