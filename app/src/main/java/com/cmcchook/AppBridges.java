package com.cmcchook;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * App 私有 JS 桥的缓存与复用（v4.0.8 · 离屏一键扫描修复）。
 *
 * <p>22:05 / 22:06 真机日志实证：用户手动点进卡券中心时，App 给那个 WebView
 * 注册了十来个 JS 桥 —— {@code cardInterface}（mPaaS CubeCard）、
 * {@code androidJsInterface}、{@code RequestInspection}、{@code newWindowInterface}、
 * {@code androidIMScene}、{@code nfcManager}、埋点桥等等，页面靠它们拿登录态、
 * 取券数据，所以能看到券列表。而模块自己开的离屏 WebView 历来只注入
 * {@code newSdkInterface} 一个（见 {@code WebPage.build()}），卡券页初始化时
 * 拿不到 {@code cardInterface}，JS 直接走降级 / 抛异常，Vue 没 mount ——
 * 日志里 body 还是 {@code {{item.title}}} / {@code {{totalNum||0}}} 模板原文，
 * 券列表接口一次都没发。这就是「一键扫描恒 0 张」的根因。
 *
 * <p>做法：{@link WebViewHooks} 在 {@code addJavascriptInterface} 钩子里把 App
 * 真页注册过的桥对象按名字缓存；离屏 WebView 建好、{@code loadUrl} 之前，
 * 由 {@link WebPage} 调 {@link #injectInto(Object, String)} 把这一整套桥原样
 * 注入离屏页，让它具备真页同等的启动条件。桥对象内部的回调若仍发回原真页，
 * {@link SdkBridge#mirror(String, Object)} 会把 {@code callbackSuccess_*} 转发到
 * 离屏页，链路已经存在。
 *
 * <p>只缓存 App 自己的页面：模块离屏页注入桥时也会经过这个钩子，
 * 用 {@link OffscreenWeb#current()} 排除，避免把离屏页的桥当真页桥学回去。
 */
final class AppBridges {

    /** 桥对象缓存有效期。用户切号后不应再复用上一个账号的桥。 */
    private static final long TTL_MS = 10L * 60_000L;
    /** 最多缓存多少个桥，防止长期驻留把 Activity 攥住。 */
    private static final int MAX = 24;

    private static final class Entry {
        final Object object;
        final String type;
        final long at;

        Entry(Object object) {
            this.object = object;
            this.type = object == null ? "null" : object.getClass().getName();
            this.at = System.currentTimeMillis();
        }
    }

    private static final Map<String, Entry> CACHE = new LinkedHashMap<>();

    private AppBridges() {
    }

    /** {@code addJavascriptInterface(wv, object, name)} 之后调用，记下 App 真页的桥。 */
    static void record(Object webView, Object object, String name) {
        if (object == null || name == null || name.isEmpty()) {
            return;
        }
        try {
            if (JsInjector.BRIDGE_NAME.equals(name)) {
                return;
            }
            // 模块自己往离屏页注入桥时同样会命中钩子，不能当成 App 的桥学回去。
            if (webView != null && webView == OffscreenWeb.current()) {
                return;
            }
        } catch (Throwable ignored) {
        }
        synchronized (CACHE) {
            pruneLocked();
            CACHE.put(name, new Entry(object));
            while (CACHE.size() > MAX) {
                String oldest = CACHE.keySet().iterator().next();
                CACHE.remove(oldest);
            }
        }
    }

    /**
     * 把缓存的 App 私有桥全部注入离屏 WebView。
     *
     * @param skipName {@code newSdkInterface} 由 {@code WebPage} 单独改绑注入，
     *                 这里跳过，避免重复 addJavascriptInterface 顶掉改绑结果。
     * @return 注入结果清单，直接写进诊断日志。
     */
    static String injectInto(Object webView, String skipName) {
        if (webView == null) {
            return "webView 为空，未注入";
        }
        Map<String, Entry> snapshot;
        synchronized (CACHE) {
            pruneLocked();
            snapshot = new LinkedHashMap<>(CACHE);
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        int fail = 0;
        for (Map.Entry<String, Entry> e : snapshot.entrySet()) {
            String name = e.getKey();
            if (name.equals(skipName)) {
                continue;
            }
            try {
                XposedHelpers.callMethod(webView, "addJavascriptInterface",
                        e.getValue().object, name);
                ok++;
                sb.append("  + ").append(name).append(" (").append(e.getValue().type)
                        .append(")\n");
            } catch (Throwable t) {
                fail++;
                sb.append("  ! ").append(name).append(" 注入失败: ").append(t).append('\n');
            }
        }
        String head = "缓存 " + snapshot.size() + " 个桥 · 注入成功 " + ok + " · 失败 " + fail
                + (skipName == null || skipName.isEmpty() ? "" : " · 跳过 " + skipName + "(由 WebPage 单独改绑)")
                + "\n";
        HookLog.log("TASK-DIAG", "App 私有 JS 桥 · 全量注入离屏页", head + sb);
        return head + sb;
    }

    /** 诊断用：缓存了哪些桥。 */
    static String describe() {
        synchronized (CACHE) {
            pruneLocked();
            if (CACHE.isEmpty()) {
                return "无（App 还没在任何页面注册过 JS 桥；先打开一次中国移动 App 首页或卡券中心）";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
                sb.append("  · ").append(e.getKey()).append(" (").append(e.getValue().type)
                        .append(", ").append((System.currentTimeMillis() - e.getValue().at) / 1000)
                        .append("s 前)\n");
            }
            return sb.toString();
        }
    }

    private static void pruneLocked() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().at > TTL_MS) {
                it.remove();
            }
        }
    }
}
