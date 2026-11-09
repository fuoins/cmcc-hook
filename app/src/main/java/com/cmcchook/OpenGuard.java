package com.cmcchook;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedHelpers;

/**
 * CMD_OPEN 兜底：容器 Activity 起来了、WebView 也 new 了，却一直不调 loadUrl 时，
 * 模块主动补一次，避免页面卡在空白 loading 转圈。
 *
 * <p>15:51 日志实证：模块按钮打开的两次（卡券页 / 活动页）后面只看到「注册 JS 桥」，
 * 一条 {@code WebView 加载} 都没有；而用户手动打开的页面后面紧跟 loadUrl → 页面完成。
 * 根因是容器走路由框架，从 {@code bundle} 嵌套 Bundle 里取 url，模块拼的平铺 Intent
 * 它不认（{@link Html5#buildIntent} 已补 bundle）。这里再加一层保险：对齐参数后
 * 万一容器仍不加载，直接在主线程对刚创建的 WebView 调 loadUrl。
 *
 * <p>判定很保守：arm 之后 2.6 秒内只要出现任何一次 http(s) loadUrl，就认为容器
 * 自己在走流程，绝不插手（避免把登录中转页 / 用户正在看的页面顶掉）。
 */
final class OpenGuard {

    private static final long WAIT_MS = 2_600L;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile WeakReference<Object> lastView = new WeakReference<>(null);
    private static volatile long viewAt;
    private static volatile long lastLoadAt;
    private static volatile String target = "";
    private static volatile long armAt;

    private OpenGuard() {
    }

    /** WebView 构造函数 hook 里登记：arm 之后新建的那个才是容器自己的 WebView。 */
    static void noteWebView(Object wv) {
        if (wv != null) {
            lastView = new WeakReference<>(wv);
            viewAt = System.currentTimeMillis();
        }
    }

    /** loadUrl hook 里登记；javascript: 回调不算页面加载。 */
    static void noteLoad(String url) {
        if (url != null && url.startsWith("http")) {
            lastLoadAt = System.currentTimeMillis();
        }
    }

    /** CMD_OPEN 成功拉起容器后调用。 */
    static void arm(final String url) {
        if (url == null || !url.startsWith("http")) {
            return;
        }
        target = url;
        armAt = System.currentTimeMillis();
        final long stamp = armAt;
        // 只认本次 arm 之后的新事件，避免上一次打开的残留造成误判。
        viewAt = 0L;
        lastLoadAt = 0L;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(WAIT_MS);
                } catch (InterruptedException ignored) {
                }
                // 期间又发起了新的打开，旧的这次作废。
                if (stamp != armAt || !url.equals(target)) {
                    return;
                }
                if (lastLoadAt >= stamp) {
                    HookLog.log("ACTIVITY", "打开页面 · 容器已自行加载",
                            "URL: " + TaskRunner.briefUrl(url) + "\n无需兜底");
                    return;
                }
                Object wv = viewAt >= stamp ? lastView.get() : null;
                if (wv == null) {
                    // 容器被复用（BROUGHT_TO_FRONT）时不会新建 WebView，
                    // 退到 App 最近加载过页面的前台 WebView。
                    wv = PageAuto.webView();
                }
                if (wv == null) {
                    HookLog.log("ACTIVITY", "打开页面 · 没补成（拿不到 WebView）",
                            "URL: " + TaskRunner.briefUrl(url));
                    return;
                }
                final Object view = wv;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            XposedHelpers.callMethod(view, "loadUrl", url);
                            HookLog.log("ACTIVITY", "打开页面 · 容器没加载，已补 loadUrl",
                                    "URL: " + TaskRunner.briefUrl(url)
                                            + "\n说明: 容器 Activity 起来了但没调 loadUrl，模块主动补发。");
                        } catch (Throwable e) {
                            HookLog.log("ACTIVITY", "打开页面 · 补 loadUrl 失败",
                                    "URL: " + TaskRunner.briefUrl(url) + "\n" + e);
                        }
                    }
                });
            }
        }, "cmcc-open-guard").start();
    }
}
