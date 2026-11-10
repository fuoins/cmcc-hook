package com.cmcchook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 用中国移动 App 自己的 H5 容器打开一个活动地址。
 *
 * <p>参数是它自己打开福利专区时传的那一套：{@code businessName=newWebview}、
 * {@code operationType=24}、两个回调名。走这条路，页面运行在 App 自己的进程里，
 * 登录态、UA、页面 JS 全对，跟用户从首页点进去没有区别。
 *
 * <p>在模块进程里调用时，目标 Activity 必须 exported；所以正常路径是
 * 模块发广播让目标进程自己开（那边是同 UID，不受导出限制）。
 */
final class Html5 {

    private static final String ACTIVITY = "com.cmccit.webview.ac.CommonHtml5Activity";

    private Html5() {
    }

    /**
     * 容器 Intent，参数与 App 手动打开活动时一致。
     *
     * <p>v3.43 关键修复：之前只把 url 平铺在 extras 顶层（{@code putExtra("url", url)}），
     * 而 App 自己打开卡券页/活动页走的是路由框架，真实 Intent 长这样：
     * <pre>
     *   flags: 0x0
     *   bundle = Bundle[{url=https://...}]      ← url 在这个嵌套 Bundle 里
     *   _componentQueryBundle = Bundle[EMPTY]
     *   _componentSyncUri = 785546254
     *   _componentRouterUri = router://webview/50000
     * </pre>
     * 容器从 {@code bundle} 里取 url 才会调 loadUrl。模块拼的平铺 Intent 让
     * Activity 起来了、WebView 也 new 了、JS 桥也注册了，却始终没人 loadUrl，
     * 于是页面一直停在 loading 转圈（15:51 日志实证：模块打开的两次后面
     * 一条 WebView 加载都没有；手动打开的后面紧跟 WebView 加载/页面完成）。
     *
     * <p>这里保留平铺字段做兼容，同时补齐 bundle / _componentQueryBundle，
     * 让容器无论从哪儿取都能拿到地址。真正兜底在 {@link TaskReceiver} 的
     * CMD_OPEN：若容器仍不自行加载，模块会主动补一次 loadUrl。
     */
    static Intent buildIntent(String url) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(AppPrefs.TARGET_PACKAGE, ACTIVITY));

        // 1) 路由框架的真实入参形态（决定容器会不会 loadUrl）。
        Bundle page = new Bundle();
        page.putString("url", url);
        intent.putExtra("bundle", page);
        intent.putExtra("_componentQueryBundle", new Bundle());
        intent.putExtra("_componentRouterUri", "router://webview/50000");
        intent.putExtra("_componentSyncUri", 785546254);

        // 2) 兼容旧容器：仍把 url 平铺一份，并保留它认识的业务字段。
        intent.putExtra("url", url);
        intent.putExtra("businessName", "newWebview");
        intent.putExtra("isHiddenNavigationBar", false);
        intent.putExtra("operationType", 24);
        intent.putExtra("debug", false);
        intent.putExtra("isShare", 0);
        intent.putExtra("markID", "");
        intent.putExtra("funCode", "");
        intent.putExtra("bizCode", "");
        intent.putExtra("type", "");
        intent.putExtra("callbackError", "callbackError_newWebview");
        intent.putExtra("callbackSuccess", "callbackSuccess_newWebview");
        return intent;
    }

    /**
     * 从当前 Activity 打开：不加 NEW_TASK，页面进 App 现有任务栈，
     * 与用户手动点活动完全一致（路由框架要的就是这个栈上下文）。
     *
     * @return 成功拉起返回 true。
     */
    static boolean openFrom(Activity activity, String url) {
        if (activity == null || activity.isFinishing() || url == null || url.isEmpty()) {
            return false;
        }
        try {
            // 这是模块自己开的页，登记一下，别让它被当成「App 现场下发的新票」。
            RealEntry.markSelfOpen(url);
            activity.startActivity(buildIntent(url));
            HookLog.logcat("open html5 (same task): " + url);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("open html5 same-task failed: " + e + " · " + url);
            return false;
        }
    }

    /** @return 成功把页面拉起来返回 true。 */
    static boolean open(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) {
            return false;
        }
        Intent intent = buildIntent(url);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            RealEntry.markSelfOpen(url);
            ctx.startActivity(intent);
            HookLog.logcat("open html5 ok (new task): " + url);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("open html5 failed: " + e + " · " + url);
            return false;
        }
    }

    /** 系统层兜底：让有能力处理这个地址的 App（含中国移动）自己接。 */
    static boolean viaSystem(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) {
            return false;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        view.setPackage(AppPrefs.TARGET_PACKAGE);
        try {
            ctx.startActivity(view);
            HookLog.logcat("open by ACTION_VIEW: " + url);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("ACTION_VIEW failed: " + e + " · " + url);
            return false;
        }
    }
}
