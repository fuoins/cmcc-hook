package com.cmcchook;

/**
 * 用户在中国移动 App 里亲手打开的「我的卡券」页面（前台真实 WebView）。
 *
 * <p>v3.39 的扫描一律自己开离屏 WebView，实测两次都是 0 张：离屏页的
 * body.innerText 是空的，Vue 券列表根本没渲染出来，日志里页面只发了
 * getBannerList / getEmotionMarketNew/second 两个横幅接口。App 自己的
 * 前台页带完整 JS 桥（newSdkInterface / cardInterface），页面能正常登录、
 * 正常渲染卡片，点击也能落到真实的 Vue 事件上。
 *
 * <p>所以扫描和批量使用优先复用这个前台页，只有用户没打开卡券中心时
 * 才退回离屏。前台页绝不 destroy —— 那是用户正在看的界面。
 *
 * <p>v3.41：get() 不再因 X5 上 getUrl() 取不到值就直接判死。X5 内核的
 * getUrl 在页面还没走完首帧时会返回 null，上一版 5 次扫描全被这一句挡回
 * 离屏兜底。真正的地址校验放到 {@link CouponTask#openHost()} 里用 JS 的
 * location.href 做，那个才准。
 */
final class CouponPage {

    /** 前台页可能被用户切走，15 分钟后不再复用。 */
    private static final long TTL_MS = 15L * 60_000L;

    private static volatile Object view;
    private static volatile long at;
    private static volatile String viewClass = "无";
    /** v3.43：登记时把地址一并记住，status() 不再反射调 getUrl。 */
    private static volatile String viewUrl = "无";

    private CouponPage() {
    }

    /** WebViewHooks 在页面加载时登记；离屏任务页不算现场。 */
    static void attach(Object wv) {
        attach(wv, null);
    }

    /** 带地址的登记：WebView 方法只能在主线程调，这里直接存 url 字符串。 */
    static void attach(Object wv, String url) {
        if (wv == null) {
            return;
        }
        try {
            Object offscreen = OffscreenWeb.current();
            if (offscreen != null && offscreen == wv) {
                return;
            }
            // v4.0.7：X5 的 loadUrl / onPageStarted 回调传进来的可能是
            // com.tencent.smtt.sdk.WebView$a 内部类实例，和 OffscreenWeb.track()
            // 登记的 WebView 不是同一个对象 —— 上面的 == 判定会漏。21:32 日志实证
            // 离屏页被登记成「前台」，把用户真页的登记冲掉，扫描回退随之失效。
            // 只要当前存在离屏会话、且已经有一个有效的真页登记，就不让离屏页覆盖。
            if (offscreen != null && view != null
                    && System.currentTimeMillis() - at <= TTL_MS) {
                return;
            }
        } catch (Throwable ignored) {
        }
        view = wv;
        at = System.currentTimeMillis();
        if (url != null && !url.isEmpty()) {
            viewUrl = url;
        }
        try {
            viewClass = wv.getClass().getName();
        } catch (Throwable ignored) {
            viewClass = "未知";
        }
    }

    static void detach(Object wv) {
        if (wv != null && view == wv) {
            view = null;
        }
    }

    /** 还停在卡券页、并且在 TTL 内的前台 WebView；否则 null。 */
    static Object get() {
        Object v = view;
        if (v == null || System.currentTimeMillis() - at > TTL_MS) {
            return null;
        }
        // v3.43：不再反射调 getUrl（子线程会抛 WebView 线程异常）。
        // 只按 TTL 软判断，真正的地址校验交给 openHost 里的 location.href。
        return v;
    }

    /** 排障用：把登记状态原样带进日志。 */
    static String status() {
        Object v = view;
        if (v == null) {
            return "登记: 无（App 没打开卡券页，或页面已被切走）";
        }
        long age = System.currentTimeMillis() - at;
        String ago = age < 1000 ? (age + "ms") : ((age / 1000) + "s");
        // v3.43：以前在这里 XposedHelpers.callMethod(v,"getUrl")，但扫描跑在
        // cmcc-coupon 子线程，WebView 方法只能主线程调，日志里全是
        // "A WebView method was called on thread ..." 异常。改成登记时存的字符串。
        return "登记: 有（" + ago + " 前）\n"
                + "类: " + viewClass + "\n地址: " + viewUrl;
    }

    /** 同步求值；页面已销毁或脚本报错返回 null。 */
    static String eval(Object v, String js) {
        if (v == null || js == null) {
            return null;
        }
        try {
            return JsInjector.evalValue(v, js);
        } catch (Throwable e) {
            return null;
        }
    }
}
