package com.cmcchook;

import android.app.Activity;
import android.app.Dialog;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自动关闭中国移动 App 的启动 / 首页广告。
 *
 * <p>11-11 09:01 的开屏日志抓到了根因：广告不是独立的开屏 Activity（两次冷启动都只有
 * {@code StartPageActivity} 活 200~270ms 就进 {@code AppTabFragment}），而是首页渲染完
 * 之后由原生接口拉下来的弹窗：
 *
 * <pre>
 * 09:01:02.626 POST .../biz-orange/DN/homepagePopup/getSortInfo → 200
 * </pre>
 *
 * <p>所以分三层做，命中哪层都能关掉：
 * <ul>
 *   <li><b>接口层</b>：{@code /homepagePopup/} 这类弹窗接口直接在 OkHttp 拦截链里打断，
 *       调用方走失败分支 → 没有弹窗数据 → 不渲染。</li>
 *   <li><b>窗口层</b>：启动后 12 秒内出现的 Dialog / PopupWindow / Activity，
 *       类名命中广告特征就关掉（{@code StartPageActivity} 是 App 正常启动页，不碰）。</li>
 *   <li><b>取证层</b>：这 12 秒内出现的每个窗口类名都写进日志（{@code [AD] 弹层: xxx}），
 *       下一版就能按真实类名收紧，不再靠猜。</li>
 * </ul>
 *
 * <p>开关在「功能」页，默认开；万一拦住首页数据，关掉即可恢复。
 */
final class AdGuard {
    /** 启动后多久内算「开屏窗口」。App 首页 6 秒内就渲染完了，12 秒足够覆盖首屏弹窗。 */
    private static final long WINDOW_MS = 12_000L;

    /** 已记录过的窗口类名，避免同一个类刷满日志。 */
    private static final Map<String, Boolean> SEEN = new ConcurrentHashMap<>();

    /** 已拦过的接口，日志里只报一次，不然首页每次刷新都刷一条。 */
    private static final Map<String, Boolean> BLOCKED = new ConcurrentHashMap<>();

    private static volatile long startAt;
    private static volatile boolean installed;

    private AdGuard() {
    }

    static boolean enabled() {
        return HookPrefs.flag("ad_block", true);
    }

    /** 目标进程 Application.onCreate 时调一次，作为开屏窗口的起点。 */
    static void markStart() {
        if (startAt == 0L) {
            startAt = System.currentTimeMillis();
        }
    }

    private static boolean inWindow() {
        long since = startAt;
        return since > 0L && System.currentTimeMillis() - since < WINDOW_MS;
    }

    // ------------------------------------------------------------- 接口层

    /**
     * 这个地址是不是首页弹窗接口。
     *
     * <p>只认 {@code /homepagePopup/} 这一族（{@code getSortInfo} 只是其中一支，
     * 同族还有 {@code getPopInfo} 之类），不按 {@code popup} 泛匹配 —— 券列表、
     * 活动页也带 popup 字样，泛匹配会误伤。
     */
    static boolean isAdUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String u = url.toLowerCase(Locale.US);
        return u.contains("/homepagepopup/");
    }

    /** OkHttp 拦截链里问一句：这条请求要不要直接打断。 */
    static boolean blockable(String url) {
        return enabled() && isAdUrl(url);
    }

    /** 拦下一条广告接口时记一笔（每个地址只记一次）。 */
    static void reportBlocked(String url) {
        String key = UrlFilter.shortForTitle(url);
        if (BLOCKED.put(key, Boolean.TRUE) != null) {
            return;
        }
        HookLog.log("AD", "已拦广告弹窗接口 · " + key,
                "URL: " + url + "\n"
                        + "做法: 在 OkHttp 拦截链里让这次请求直接失败，调用方拿不到弹窗数据就不会弹。\n"
                        + "开关: 「功能」页「自动关闭广告」（默认开），关掉即可放行。");
    }

    // ------------------------------------------------------------- 窗口层

    /** Activity 起来时调：开屏窗口内记录类名，命中广告特征就关掉。 */
    static void observeActivity(Activity activity) {
        if (activity == null || !inWindow()) {
            return;
        }
        String name = activity.getClass().getName();
        boolean ad = adLike(name);
        noteWindow("Activity", name, ad);
        if (ad) {
            closeActivity(activity, name);
        }
    }

    /** Dialog 弹出来时调：开屏窗口内记录类名，命中广告特征就 dismiss。 */
    static void observeDialog(Dialog dialog) {
        if (dialog == null || !inWindow()) {
            return;
        }
        String name = dialog.getClass().getName();
        boolean ad = adLike(name);
        noteWindow("Dialog", name, ad);
        if (ad) {
            dismiss(dialog, name);
        }
    }

    /** PopupWindow 弹出来时调。 */
    static void observePopup(Object popup) {
        if (popup == null || !inWindow()) {
            return;
        }
        String name = popup.getClass().getName();
        boolean ad = adLike(name);
        noteWindow("PopupWindow", name, ad);
        if (ad) {
            closePopup(popup, name);
        }
    }

    /**
     * 类名像不像广告弹层。
     *
     * <p>刻意保守：只认明确的广告词，{@code launch}、{@code welcome} 这类容易误伤
     * 正常启动页/引导页的词不用，改成 {@code launchad}。
     */
    private static boolean adLike(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(Locale.US);
        if (n.contains("startpage")) {
            // App 自己的启动页（StartPageActivity），正常流程，不碰。
            return false;
        }
        return n.contains("splash")
                || n.contains("openscreen")
                || n.contains("open_screen")
                || n.contains("openad")
                || n.contains("launchad")
                || n.contains("advert")
                || n.contains("interstitial")
                || n.contains("fullscreenad")
                || n.contains("screenad");
    }

    private static void noteWindow(String kind, String name, boolean ad) {
        String key = kind + "|" + name;
        if (SEEN.put(key, Boolean.TRUE) != null) {
            return;
        }
        // 复用「页面」抓取开关记录的 Activity 已经写过日志，这里只补 Dialog/PopupWindow，
        // 以及 Activity 里像广告的那些 —— 免得开屏窗口内多出一堆重复的页面记录。
        boolean worth = ad || !"Activity".equals(kind);
        if (!worth) {
            return;
        }
        HookLog.log("AD", (ad ? "已关闭广告弹层 · " : "开屏期间弹层 · ") + simple(name),
                "类型: " + kind + "\n"
                        + "类名: " + name + "\n"
                        + (ad
                        ? "已自动关闭。\n"
                        : "不是广告特征类名，只记录不处理；下一版按这份清单收紧。\n")
                        + "开关: 「功能」页「自动关闭广告」");
    }

    private static String simple(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 || name == null ? String.valueOf(name) : name.substring(dot + 1);
    }

    private static void closeActivity(final Activity activity, String name) {
        try {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        activity.finish();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            HookLog.logcat("close ad activity failed: " + name + " " + e);
        }
    }

    private static void dismiss(final Dialog dialog, String name) {
        try {
            dialog.dismiss();
        } catch (Throwable e) {
            HookLog.logcat("dismiss ad dialog failed: " + name + " " + e);
        }
    }

    private static void closePopup(Object popup, String name) {
        try {
            XposedHelpers.callMethod(popup, "dismiss");
        } catch (Throwable e) {
            HookLog.logcat("dismiss ad popup failed: " + name + " " + e);
        }
    }

    // --------------------------------------------------------------- hook

    /**
     * 挂 Dialog / PopupWindow 的 show 系列。只观察，不做别的 ——
     * 真正拦截靠 {@link #blockable(String)}，这里是为了拿到类名清单。
     */
    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // Dialog.show / show 的两个重载
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !inWindow()) {
                        return;
                    }
                    if (param.thisObject instanceof Dialog) {
                        observeDialog((Dialog) param.thisObject);
                    }
                }
            });
        } catch (Throwable e) {
            HookLog.logcat("hook Dialog.show failed: " + e);
        }
        // PopupWindow 的 show 系列（框架类，直接按类 hook，不用反射找类加载器）
        String[] methods = {"showAsDropDown", "showAtLocation", "showAsPopupMenu"};
        for (String m : methods) {
            try {
                XposedBridge.hookAllMethods(android.widget.PopupWindow.class, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!enabled() || !inWindow()) {
                            return;
                        }
                        observePopup(param.thisObject);
                    }
                });
            } catch (Throwable e) {
                HookLog.logcat("hook PopupWindow." + m + " failed: " + e);
            }
        }
    }
}
