package com.cmcchook;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

    /**
     * 开屏窗口内扫视图树的几个时间点（毫秒，相对 Activity 起来的那一刻）。
     *
     * <p>素材解码和图片下载有快有慢，单点扫一次容易扑空；几个点各扫一次，命中就停。
     */
    private static final long[] SWEEP_AT_MS = {350L, 700L, 1_200L, 2_000L, 3_000L};

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

    /** Activity 起来时调：开屏窗口内记录类名，命中广告特征就关掉，并按「跳过」按钮清浮层。 */
    static void observeActivity(Activity activity) {
        if (activity == null || !enabled()) {
            return;
        }
        if (inWindow()) {
            String name = activity.getClass().getName();
            boolean ad = adLike(name);
            noteWindow("Activity", name, ad);
            if (ad) {
                closeActivity(activity, name);
                return;
            }
        }
        // v4.2.1：开屏广告是贴在 Activity 上的一层 View（右上角「3 跳过」倒计时），
        // 既不是 Dialog 也不是 PopupWindow，接口层也拦不住（素材是上一次启动缓存的，
        // 等我们的 OkHttp 钩子装好，它早就画完了）。所以按「跳过」这个锚点扫视图树。
        // 不按「进程启动 12 秒」判断，热启动（进程还活着再点图标）同样会弹广告。
        for (long delay : SWEEP_AT_MS) {
            scheduleSweep(activity, delay);
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

    /** 带尺寸信息的窗口记录（取证用，只记全屏的，别刷屏）。 */
    private static void noteWindowSize(String name, int w, int h) {
        String key = "Window|" + name + "|" + w + "x" + h;
        if (SEEN.put(key, Boolean.TRUE) != null) {
            return;
        }
        HookLog.log("AD", "开屏窗口 · " + simple(name),
                "类型: WindowManager.addView\n"
                        + "类名: " + name + "\n"
                        + "尺寸: " + w + " x " + h + "\n"
                        + "说明: 开屏期间新加了一层窗口，这里只记录不处理，用于下一版按类名精准拦截。\n"
                        + "开关: 「功能」页「自动关闭广告」");
    }

    // ------------------------------------------------------------- 视图层

    private static void scheduleSweep(final Activity activity, long delayMs) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        sweep(activity);
                    } catch (Throwable e) {
                        HookLog.logcat("ad sweep failed: " + e);
                    }
                }
            }, delayMs);
        } catch (Throwable e) {
            HookLog.logcat("schedule ad sweep failed: " + e);
        }
    }

    /**
     * 扫一遍当前界面的视图树，按「N 跳过」这个按钮找到整块开屏广告并摘掉。
     *
     * <p>为什么用「跳过」当锚点：广告素材每次都换（11-11 是「百GB流量大放送」的转盘，
     * 前一天是别的），按图片/类名认都不稳；但「跳过」这个倒计时按钮是这套开屏广告
     * 固定的骨架。找到它，再往上爬到「挂在 content 下的那一整层」，就是广告容器。
     *
     * <p>两道保险，避免误伤主界面：
     * <ul>
     *   <li>容器必须是全屏尺寸（宽 ≥ 80%、高 ≥ 60% 的窗口）；</li>
     *   <li>容器里不能有 WebView —— App 真正的内容区一定是 WebView，广告层只是图和文字。</li>
     * </ul>
     */
    private static void sweep(Activity activity) {
        if (!enabled() || activity == null || activity.isFinishing()) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed()) {
            return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        View skip = findSkip((ViewGroup) decor, 0);
        if (skip == null) {
            return;
        }
        ViewGroup root = (ViewGroup) decor;
        int dw = root.getWidth() > 0 ? root.getWidth() : root.getMeasuredWidth();
        int dh = root.getHeight() > 0 ? root.getHeight() : root.getMeasuredHeight();
        // 从「跳过」往上走，记住「最高的一层：全屏、且不含 WebView」的祖先 —— 那才是广告层。
        // 不含 WebView 这条是硬保险：App 的主界面一定是 WebView 宿主，广告层只有图和字。
        View band = null;
        View node = skip;
        ViewGroup parent = node.getParent() instanceof ViewGroup
                ? (ViewGroup) node.getParent() : null;
        while (parent != null) {
            boolean stop = parent == root || parent.getId() == android.R.id.content;
            int nw = node.getWidth() > 0 ? node.getWidth() : node.getMeasuredWidth();
            int nh = node.getHeight() > 0 ? node.getHeight() : node.getMeasuredHeight();
            boolean full = dw > 0 && dh > 0 && nw >= dw * 0.8f && nh >= dh * 0.6f;
            if (full && !hasWebView(node, 0)) {
                band = node;
            }
            if (stop) {
                break;
            }
            node = parent;
            parent = parent.getParent() instanceof ViewGroup
                    ? (ViewGroup) parent.getParent() : null;
        }
        if (band == null) {
            HookLog.logcat("ad sweep: skip found, but no fullscreen WebView-free band");
            return;
        }
        int bw = band.getWidth() > 0 ? band.getWidth() : band.getMeasuredWidth();
        int bh = band.getHeight() > 0 ? band.getHeight() : band.getMeasuredHeight();
        String name = band.getClass().getName();
        ViewGroup host = band.getParent() instanceof ViewGroup
                ? (ViewGroup) band.getParent() : null;
        try {
            if (host != null) {
                host.removeView(band);
            } else {
                band.setVisibility(View.GONE);
            }
        } catch (Throwable e) {
            HookLog.logcat("remove ad band failed: " + e);
            try {
                band.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        }
        HookLog.log("AD", "已关闭开屏广告 · " + simple(name),
                "类型: 视图层浮层\n"
                        + "容器类名: " + name + "\n"
                        + "尺寸: " + bw + " x " + bh + "\n"
                        + "做法: 按右上角「N 跳过」按钮定位到整块广告层，直接移除（不动主界面）。\n"
                        + "开关: 「功能」页「自动关闭广告」");
    }

    /** 找「N 跳过」这个倒计时按钮。 */
    private static View findSkip(ViewGroup group, int depth) {
        if (group == null || depth > 24) {
            return null;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            // 有的版本把倒计时做成自定义 View，文字在 contentDescription 里。
            if (child.getContentDescription() != null
                    && child.getContentDescription().toString().contains("跳过")) {
                return child;
            }
            if (child instanceof TextView && skipText(((TextView) child).getText())) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View hit = findSkip((ViewGroup) child, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 是不是「跳过」倒计时按钮的文字。 */
    private static boolean skipText(CharSequence text) {
        if (text == null) {
            return false;
        }
        String s = text.toString().replace(" ", "").trim();
        if (s.isEmpty() || s.length() > 10 || !s.contains("跳过")) {
            return false;
        }
        // 「跳过」「3 跳过」「3s 跳过」「跳过广告 3」都认，但「跳过登录」「跳过验证」这类业务按钮不认。
        return s.matches("^\\d+[sS秒]?跳过$")
                || s.matches("^跳过(广告)?\\d*[sS秒]?$");
    }

    private static boolean hasWebView(View view, int depth) {
        if (view == null || depth > 24) {
            return false;
        }
        String cls = view.getClass().getName();
        if (cls.contains("WebView")) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasWebView(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
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
        // v4.2.1 取证：开屏广告如果不用 Dialog/PopupWindow，而是自己往 WindowManager
        // 上加一层窗口，Dialog/PopupWindow 的钩子一条都不会响。这里补一个只读探针，
        // 把开屏期间新加的窗口类名和尺寸记下来，下一版就能直接按类名在源头拦掉。
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            XposedBridge.hookAllMethods(wmg, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled() || !inWindow()) {
                        return;
                    }
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    Object v = param.args[0];
                    if (!(v instanceof View)) {
                        return;
                    }
                    View view = (View) v;
                    try {
                        ViewGroup.LayoutParams lp = view.getLayoutParams();
                        if (lp == null) {
                            return;
                        }
                        boolean fullscreen = lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                                && lp.height == ViewGroup.LayoutParams.MATCH_PARENT;
                        if (!fullscreen && !adLike(view.getClass().getName())) {
                            return;
                        }
                        noteWindowSize(view.getClass().getName(), lp.width, lp.height);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            HookLog.logcat("hook WindowManagerGlobal.addView failed: " + e);
        }
    }
}
