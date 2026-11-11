package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 收鹅蛋换好礼（大鹅养成记）。
 *
 * 这个活动和另外两个不是一个体系：接口挂在 lyh.lncmcc.com/huodong-portal，
 * 请求体是国密加密后的 sinoEnParams、响应是 dataEn，URL 上还有瑞数（$_ts）动态签名，
 * 每次请求的 LJRkIeoW 都不一样。本地重放这条路走不通 —— 除非把整套瑞数 VM 也搬过来，
 * 成本远大于收益。
 *
 * 所以这里换一种做法：不自己发请求，而是等活动页打开后往那个 WebView 里注入脚本，
 * 让页面自己去点「打卡 / 领饲料 / 投喂 / 收蛋」。加密、签名全交给页面自己的 JS。
 *
 * 关键在地址。App 打开这个活动时用的是
 *   https://lyh.lncmcc.com/lyhVue/index.html#/bgIndex?actNo=goose2025&pc=...&pk=...
 * 其中 pc/pk 是门户后端按登录态生成的密文参数，缺了它们页面就是一片空白
 * （v2.3 的「页面文本: 空」就是这么来的，裸地址进去 DOM 根本没渲染）。
 *
 * 所以模块做两件事：
 *   1. 平时盯着 WebView，把带 pc/pk 的真实地址缓存下来，有效期 3 天；
 *   2. 跑任务时按「当前页 → 缓存地址 → 裸地址」的顺序试，谁渲染出内容就用谁。
 * 页面白屏不再直接判失败，而是换个地址重来一次，并把诊断信息写进日志。
 */
final class GooseTask {

    /**
     * 体检通道开关，默认 false（走正常动作脚本）。
     *
     * true 时 run() 交给 runDiag：只读枚举页面组件的方法名/字段名/源码并写进日志，
     * 不调用任何组件方法、不点任何 DOM、不领任何东西。需要再次核对页面真实方法时
     * 临时改成 true 即可；v3.19 用过一次，据此拿到了组件方法清单。
     */
    private static final boolean DIAG_ONLY = false;

    /**
     * v3.21 只读探测通道（B 方案第一步），已停用。
     *
     * true 时 run() 交给 runProbe：只往页面 webpack 里塞一个空探针模块拿到
     * require，再只读 r.m 模块表源码 + r.c 已加载模块缓存，把真实接口路径、
     * 明文参数、请求封装长什么样写进日志。已据其结论推进到自检阶段。
     */
    private static final boolean PROBE_ONLY = false;

    /**
     * v3.22 直发自检通道，已停用（本版走 VUE_SCRIPT 动作链）。
     *
     * <p>停用原因：v3.22 那版注入脚本里有一行 {@code new RegExp('["\']…')}，
     * Java text block 会把 {@code \'} 吃成 {@code '}，编进 DEX 后 JS 引号提前闭合，
     * 整个脚本解析期 SyntaxError —— 真机表现是 {@code Script error.(:0:0)}、
     * 零分片、轮询 45 秒超时。该行已改成不含引号字符类的写法，
     * 需要再次自检时把这里改成 true 即可。
     */
    private static final boolean SELFTEST_ONLY = false;

    /** 单轮脚本最长等多久（页面等待渲染 + 逐个调组件方法）。 */
    private static final long SCRIPT_TIMEOUT_MS = 40_000L;
    /** 发起 loadUrl 之后留一点时间给容器切页。 */
    private static final long LOAD_WAIT_MS = 2_500L;
    /** 缓存的入口地址能用多久。pc/pk 由服务端签发，放太久会被判失效。 */
    private static final long ENTRY_TTL = 24 * 3600_000L;

    private static final String PREFS = "cmcc_hook_goose";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_ENTRY_AT = "entry_at";

    private static volatile WeakReference<Object> webViewRef = new WeakReference<>(null);
    private static volatile String attachedUrl = "";
    private static volatile long attachedAt;

    private static volatile CountDownLatch latch;
    private static volatile String resultText = "";
    private static volatile boolean resultOk;
    private static volatile boolean resultBlank;
    private static volatile boolean resultStale;
    private static volatile String resultDetail = "";

    private GooseTask() {
    }

    /** 由 WebView 钩子在页面加载/路由变化时调用。 */
    static void attach(Object webView, String url) {
        if (webView == null || url == null) {
            return;
        }
        if (!isGoosePage(url)) {
            return;
        }
        webViewRef = new WeakReference<>(webView);
        attachedUrl = url;
        attachedAt = System.currentTimeMillis();
        rememberEntry(url);
        HookLog.logcat("goose page attached");
    }

    static boolean isGoosePage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("actno=goose") || lower.contains("raisegoose");
    }

    // ------------------------------------------------------- 入口地址缓存

    /**
     * 记住带 pc/pk 的真实地址。
     *
     * App 每次进活动都会重新生成一套 pc/pk，所以缓存只用来「下次不用再手动进页面」，
     * 一旦服务端判失效（页面提示活动结束/重新进入），调用 {@link #forgetEntry()} 清掉。
     */
    static void rememberEntry(String url) {
        if (url == null || !isGoosePage(url)) {
            return;
        }
        if (!url.contains("pc=") || !url.contains("pk=")) {
            return;
        }
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            if (url.equals(p.getString(KEY_ENTRY, ""))) {
                // 同一套参数反复刷新时间没意义，一小时刷一次就够，省得频繁写盘。
                if (System.currentTimeMillis() - p.getLong(KEY_ENTRY_AT, 0L) > 3600_000L) {
                    p.edit().putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
                }
                return;
            }
            p.edit().putString(KEY_ENTRY, url)
                    .putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
            HookLog.logcat("goose: entry cached, " + url.length() + " chars");
        } catch (Throwable e) {
            HookLog.logcat("goose: cache entry failed " + e);
        }
    }

    static String entryUrl() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return TaskApi.ACT_GOOSE;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (url.isEmpty() || System.currentTimeMillis() - at > ENTRY_TTL) {
                return TaskApi.ACT_GOOSE;
            }
            return url;
        } catch (Throwable e) {
            return TaskApi.ACT_GOOSE;
        }
    }

    private static void forgetEntry() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            AccountScope.prefs(ctx, PREFS)
                    .edit().remove(KEY_ENTRY).remove(KEY_ENTRY_AT).apply();
            HookLog.logcat("goose: cached entry dropped");
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- 执行

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();
        // 宿主进程里真正跑的 dex 版本：日志出现这条才说明 LSPosed 已重载，
        // 日志头「模块版本」只是 App 侧 BuildConfig，证明不了注入的 dex 是新的。
        HookLog.log("GOOSE", "收鹅蛋脚本 v3.98 已装载 · 出游归来领取 + 投喂/收蛋收敛循环",
                "判定尺子: 只认 doTask/signIn/feedGoose/harvestEgg/claimTravelReward 的真实提交回执；\n"
                        + "零提交一律不算完成、不记失败次数；业务数据未就绪时不空跑动作链。\n"
                        + "v3.97 补三处: ①点页面自己的出游奖励弹窗领取按钮(不拼加密请求) "
                        + "②投喂/收蛋改收敛循环(饲料涨了继续喂) ③新产鹅蛋继续收，回执逐条验收。");
        WebPage page = WebSession.current();
        if (page == null || page.raw() == null) {
            // 这里以前会去 PageAuto.load() 操作用户当前打开的页面，等于把任务变回半自动：
            // 用户不回到 App、不进活动页就跑不了。现在页面现场起不来就直接说清楚，
            // 不再动用户的界面。
            HookLog.logcat("goose: no page session");
            HookLog.log("GOOSE", "收鹅蛋 · 活动页没起来",
                    "出口地址: " + brief(entryUrl()) + "\n"
                            + "这次是 App 真页没加载成功，不是活动本身的问题。");
            return TaskRunner.Result.fail("活动页没起来",
                    "App 真页没能加载收鹅蛋页面。\n"
                            + "出口地址: " + brief(entryUrl()) + "\n",
                    "把日志发我。这条说明真页没加载成功，不是活动本身的问题。"
                            + "先手动点一次「收鹅蛋换好礼」，模块会把带 pc/pk 的入口记下来。");
        }
        if (!ensureLogin(page, detail)) {
            String entry = entryUrl();
            // v3.44：把现场写进完整日志，下一轮能直接定位是哪一步没走通
            //（SSO 中转？pc/pk 没生成？页面文本是什么），不用再靠猜。
            String scene = "";
            try {
                scene = page.eval("(function(){try{return ["
                        + "'url='+location.href,"
                        + "'title='+(document.title||'-'),"
                        + "'text='+(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,160)"
                        + "].join(' · ')}catch(e){return 'eval失败:'+e}})()", 5_000L);
            } catch (Throwable e) {
                scene = "现场读取异常: " + e;
            }
            HookLog.log("GOOSE", "收鹅蛋登录诊断 · " + (entry != null && entry.contains("pc=")
                            ? "有 pc/pk 但未登录" : "无 pc/pk"),
                    "缓存入口: " + brief(entry) + "\n"
                            + "页面现场: " + scene + "\n"
                            + "过程:\n" + detail);
            if (entry == null || !entry.contains("pc=")) {
                // 没有 pc/pk 就没有登录态，这一轮不算失败，也不该记进当天次数。
                // v3.44：裸地址现在会自己在离屏页走门户 SSO 拿到 pc/pk（见 waitLogged），
                // 首轮没拿到的多半是 SSO 还没走完/被外部跳转顶走，再执行一次即可。
                return TaskRunner.Result.skip("收鹅蛋：还没有登录参数（pc/pk）",
                        detail.toString(),
                        "真页会自己走一遍门户登录拿 pc/pk，再点一次「执行任务」重试；"
                                + "若连续几次都不行，在 App 里手动点一次「收鹅蛋换好礼」，"
                                + "模块会把带 pc/pk 的入口记下来，之后就能自动跑。");
            }
            // v3.48：pc/pk 过期时主动作废会话缓存，下一轮离屏页才会重走门户 SSO
            // 拿新参数；否则一直复用旧 pc/pk，连续几轮都报「页面未登录」。
            SsoLogin.invalidate();
            return TaskRunner.Result.fail("收鹅蛋页面未登录",
                    detail.toString(),
                    "页面打开了但没登录（已清会话缓存，下一轮会重走登录）。这通常意味着 pc/pk 过期，"
                            + "再执行一次；仍不行时，在 App 里手动点一次「收鹅蛋」活动页即可恢复。");
        }
        if (SELFTEST_ONLY) {
            // v3.22 自检版：动态定位业务接口层 + 只发一次只读接口验证直发。
            // 不调用任何会改数据的接口，不领任何东西，VUE_SCRIPT 整个不注入。
            return runSelfTest(page, detail);
        }
        if (PROBE_ONLY) {
            // v3.21 探测版：只读 webpack 模块源码与已加载缓存，列出真实接口
            // 路径和明文参数。VUE_SCRIPT / 任何动作方法整个不注入、不执行，
            // 不领任何东西。
            return runProbe(page, detail);
        }
        if (DIAG_ONLY) {
            // v3.19 诊断版：只枚举页面组件真实存在的方法名/字段/接口路径，
            // VUE_SCRIPT 整个不注入、不执行，不领任何东西。
            return runDiag(page, detail);
        }
        TaskRunner.Result viaVue = runVue(page, detail);
        if (viaVue != null) {
            return viaVue;
        }
        return TaskRunner.Result.fail("页面组件没拿到",
                detail.toString(),
                "页面能打开但没找到活动组件，可能改版了。把这段日志发我。");
    }

    /**
     * v3.19 只读诊断：把页面 Vue 组件树里真实存在的方法名、字段名、
     * 关键词方法的源码、以及源码里出现的真实接口路径全部写进日志。
     *
     * 这里刻意不调用任何组件方法、不点任何按钮、不发任何请求——
     * 用户要的是「页面里到底有哪些方法」，之后才能按真实方法名补动作，
     * 而不是靠接口名去猜一个方法名。分片写日志是为了绕开
     * HookLog 的 8KB detail 上限（没开详细原始返回时也不丢内容）。
     */
    private static TaskRunner.Result runDiag(WebPage page, StringBuilder detail) {
        page.eval(DIAG_SCRIPT, 8_000L);
        String countRaw = page.eval(
                "(function(){try{var a=window.__cmcc_goose_diag;"
                        + "return a?String(a.length):'0'}catch(e){return '0'}})()", 6_000L);
        int parts;
        try {
            parts = Integer.parseInt(countRaw == null ? "0" : countRaw.trim());
        } catch (Throwable e) {
            parts = 0;
        }
        int written = 0;
        long deadline = System.currentTimeMillis() + 30_000L;
        for (int i = 0; i < parts && System.currentTimeMillis() < deadline; i++) {
            String chunk = page.eval(
                    "(function(){try{var a=window.__cmcc_goose_diag;"
                            + "return a[" + i + "]||''}catch(e){return ''}})()", 6_000L);
            if (chunk == null) {
                chunk = "";
            }
            // 每片单独一条日志，<=4500 字符，不依赖「详细原始返回」开关也不会被截。
            HookLog.log("GOOSE-DIAG",
                    "收鹅蛋组件诊断 " + (i + 1) + "/" + parts + "（只读，未执行任何动作）",
                    chunk);
            written++;
        }
        if (parts == 0) {
            detail.append("诊断: 页面没有回报任何组件信息\n");
            return TaskRunner.Result.fail("收鹅蛋诊断：没拿到组件信息",
                    detail.toString(),
                    "页面组件脚本没有回报。确认已在 App 里手动打开过收鹅蛋活动页，再导出完整日志发我。");
        }
        detail.append("诊断: 组件信息分 ").append(written).append('/').append(parts)
                .append(" 段写入 GOOSE-DIAG 日志\n");
        detail.append("本版只做只读枚举，未调用任何组件方法、未领取任何东西\n");
        return TaskRunner.Result.skip(
                "收鹅蛋：v3.19 只读诊断完成（未执行任何动作）",
                detail.toString(),
                "已把页面组件方法清单写进日志（" + written + " 段）。\n"
                        + "本版一个动作都没做，号 1 的可领内容原样保留。\n"
                        + "请导出「完整」日志发我，我按真实方法名补齐出游/信箱后再出下一版。");
    }

    /**
     * v3.21 只读探测：往页面 webpack 里塞一个空探针模块拿到 require，
     * 然后只读 r.m 模块表源码 + r.c 已加载缓存，把真实接口路径、明文参数、
     * 请求封装的构造方式全部写进日志，用于判断能否按纯接口编排。
     *
     * 严格只读：探针模块函数体只有一句把 require 赋给全局变量；
     * 之后全是 toString / Object.keys / 读属性，不执行任何业务模块、
     * 不调用任何组件方法、不点 DOM、不发任何网络请求、不领任何东西。
     * 分片写日志复用诊断通道那套，绕开 HookLog 的 8KB detail 上限。
     */
    private static TaskRunner.Result runProbe(WebPage page, StringBuilder detail) {
        page.eval(PROBE_SCRIPT, 8_000L);
        String countRaw = page.eval(
                "(function(){try{var a=window.__cmcc_goose_probe;"
                        + "return a?String(a.length):'0'}catch(e){return '0'}})()", 6_000L);
        int parts;
        try {
            parts = Integer.parseInt(countRaw == null ? "0" : countRaw.trim());
        } catch (Throwable e) {
            parts = 0;
        }
        int written = 0;
        long deadline = System.currentTimeMillis() + 30_000L;
        for (int i = 0; i < parts && System.currentTimeMillis() < deadline; i++) {
            String chunk = page.eval(
                    "(function(){try{var a=window.__cmcc_goose_probe;"
                            + "return a[" + i + "]||''}catch(e){return ''}})()", 6_000L);
            if (chunk == null) {
                chunk = "";
            }
            HookLog.log("GOOSE-PROBE",
                    "收鹅蛋 webpack 只读探测 " + (i + 1) + "/" + parts + "（未执行任何动作）",
                    chunk);
            written++;
        }
        if (parts == 0) {
            detail.append("探测: 页面没有回报任何 webpack 信息\n");
            return TaskRunner.Result.fail("收鹅蛋探测：没拿到 webpack 信息",
                    detail.toString(),
                    "探测脚本没有回报。确认已在 App 里手动打开过收鹅蛋活动页，再导出完整日志发我。");
        }
        detail.append("探测: webpack 信息分 ").append(written).append('/').append(parts)
                .append(" 段写入 GOOSE-PROBE 日志\n");
        detail.append("本版只做只读探测，未执行业务模块、未调用组件方法、未领取任何东西\n");
        return TaskRunner.Result.skip(
                "收鹅蛋：v3.21 webpack 只读探测完成（未执行任何动作）",
                detail.toString(),
                "已把页面 webpack 模块里的真实接口路径和明文参数写进日志（" + written + " 段）。\n"
                        + "本版一个动作都没做，号 1 的可领内容原样保留。\n"
                        + "请导出「完整」日志发我，我据此判断能否按纯接口编排重写。");
    }

    /**
     * v3.22 直发自检：动态定位页面的「业务接口层」exports，dump 出完整的
     * 「路径→函数」映射，然后只调用一次只读接口 index，验证：
     *   - 页面自己的请求函数能不能脱离组件方法被独立调用（发得出请求）；
     *   - 拿回来的是解密后的明文业务对象，还是仍是 dataEn 密文；
     *   - 瑞数 WAF 放不放行这次直发；
     *   - 响应对象的真实结构（供 v3.23 编排时判 code/data）。
     *
     * 只读接口 index 不改动任何数据，不领取任何东西。VUE_SCRIPT 与所有动作
     * 接口整个不注入、不执行。脚本内部有 await 网络请求，所以注入同步脚本后
     * 由页面事件循环继续跑，Java 侧轮询 window 上的完成标记再逐片读回。
     * 分片写日志绕开 HookLog 的 8KB detail 上限，不依赖「详细原始返回」开关。
     */
    private static TaskRunner.Result runSelfTest(WebPage page, StringBuilder detail) {
        page.eval("(function(){try{window.__cmcc_goose_selftest_done='';"
                + "window.__cmcc_goose_selftest=null}catch(e){}})()", 6_000L);
        page.eval(SELFTEST_SCRIPT, 8_000L);
        String done = null;
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            done = page.eval(
                    "(function(){try{return window.__cmcc_goose_selftest_done||''}catch(e){return ''}})()",
                    6_000L);
            if (done != null && !done.isEmpty()) {
                break;
            }
            sleep(500L);
        }
        if (done == null || done.isEmpty()) {
            detail.append("自检: 脚本 45 秒没回报（可能请求被挂住），只读映射可能没写全\n");
            // 即便没等到完成标记，也把已经写出的分片读回来，能拿多少拿多少。
        }
        String countRaw = page.eval(
                "(function(){try{var a=window.__cmcc_goose_selftest;"
                        + "return a?String(a.length):'0'}catch(e){return '0'}})()", 6_000L);
        int parts;
        try {
            parts = Integer.parseInt(countRaw == null ? "0" : countRaw.trim());
        } catch (Throwable e) {
            parts = 0;
        }
        int written = 0;
        long readDeadline = System.currentTimeMillis() + 30_000L;
        for (int i = 0; i < parts && System.currentTimeMillis() < readDeadline; i++) {
            String chunk = page.eval(
                    "(function(){try{var a=window.__cmcc_goose_selftest;"
                            + "return a[" + i + "]||''}catch(e){return ''}})()", 6_000L);
            if (chunk == null) {
                chunk = "";
            }
            HookLog.log("GOOSE-SELFTEST",
                    "收鹅蛋 直发自检 " + (i + 1) + "/" + parts + "（只发 1 次只读请求，未领取任何东西）",
                    chunk);
            written++;
        }
        if (parts == 0) {
            detail.append("自检: 页面没有回报任何信息\n");
            return TaskRunner.Result.fail("收鹅蛋自检：没拿到信息",
                    detail.toString(),
                    "自检脚本没有回报。确认已在 App 里手动打开过收鹅蛋活动页，再导出完整日志发我。");
        }
        detail.append("自检: 直发结果分 ").append(written).append('/').append(parts)
                .append(" 段写入 GOOSE-SELFTEST 日志\n");
        detail.append("自检结论标记: ").append(done == null ? "(超时)" : done).append('\n');
        detail.append("本版只调用了 1 次只读接口 index，未执行任何动作、未领取任何东西\n");
        return TaskRunner.Result.skip(
                "收鹅蛋：v3.22 直发自检完成（未执行任何动作）",
                detail.toString(),
                "已把「路径→函数」映射和一次只读直发的真实返回写进日志（" + written + " 段）。\n"
                        + "本版一个动作都没做，号 1 的可领内容原样保留。\n"
                        + "请导出「完整」日志发我，我据此判断能否按纯接口编排重写动作链。");
    }

    /**
     * 确认页面真的登录了。
     *
     * <p>鹅蛋页的登录态不在 Cookie 里：URL 上的 {@code pc/pk} 是门户后端按登录态生成的密文，
     * 页面拿它换 accesstoken，再写进 localStorage。裸地址进页面不会报错，
     * 只是安静地显示「用户未登录」，所有数据字段空着 ——
     * v2.6 日志里「已执行 4 个动作（数据无变化）」就是这么来的。
     *
     * <p>所以这里分三步：先看当前页；没登录就换成缓存里带 pc/pk 的真实入口；
     * 还不行就拿页面自己存下的 accesstoken 拼 {@code wechatParams} 再试一次。
     */
    private static boolean ensureLogin(WebPage page, StringBuilder detail) {
        // 页面刚加载完时 indexFun 还没回来，pageInitData 是空的。
        // 裸地址进页面时还要走一遍门户 SSO（先跳 sso_token，再跳回带 pc/pk 的主页），
        // 这一段实测要 10 秒上下，只等 8 秒会把「正在登录」误判成「未登录」。
        if (waitLogged(page, 16_000L)) {
            detail.append("登录: 已登录\n");
            // v3.44：页面自己走完门户 SSO 后 URL 会带 pc/pk，把这份真实入口记下来。
            // 下次执行直接用缓存地址，不必再让用户「手动点进一次」拿信息。
            String landed = page.url();
            if (landed != null && landed.contains("pc=")) {
                rememberEntry(landed);
                detail.append("登录: 已记住带 pc/pk 的入口，之后可自动跑\n");
            }
            return true;
        }
        String now = page.url();
        boolean onGoose = isGoosePage(now);
        detail.append("登录: 首屏未登录（当前页 ").append(briefUrl(now)).append("）\n");

        String entry = entryUrl();
        if (entry != null && entry.contains("pc=")) {
            if (entry.equals(now) && onGoose) {
                // 页面刚起来时 indexFun 还没回来，上面的判定有可能是误判。
                // 以前这里直接清缓存，一次误判就把唯一能用的入口删了，
                // 下一轮只剩裸地址，只能报「还没有登录参数」。
                // 现在保留缓存，继续用下面的 accesstoken 兜底，实在不行下次还有地址可试。
                detail.append("登录: 同一入口仍未登录（保留缓存，继续用本地票兜底）\n");
            } else {
                if (!onGoose) {
                    // 活动页被广告位/下载引导页顶掉了。这种时候 pc/pk 是好的，
                    // 以前会因为「当前地址 != 缓存地址」误判成过期，把能用的入口删掉。
                    detail.append("登录: 页面被外部跳转顶掉，拉回活动页重试\n");
                } else {
                    detail.append("登录: 换真实入口重试（带 pc/pk）\n");
                }
                if (page.navigate(entry, 18_000L) && waitLogged(page, 12_000L)) {
                    detail.append("登录: 拉回活动页后已登录\n");
                    return true;
                }
                detail.append("登录: 换地址后仍未登录\n");
                if (isGoosePage(page.url())) {
                    forgetEntry();
                }
            }
        } else {
            detail.append("登录: 还没有带 pc/pk 的入口缓存\n");
        }

        // 最后一招：页面自己存的 accesstoken。用户之前在 App 里打开过活动，
        // localStorage 里就有；把它拼回 URL 能让页面跳过 pc/pk 直接认票。
        String token = page.eval("(function(){try{"
                + "var t=localStorage.getItem('accesstoken')||localStorage.getItem('acToken')||'';"
                + "var u=localStorage.getItem('uid')||'';"
                + "return JSON.stringify({t:t,u:u});"
                + "}catch(e){return ''}})()", 4_000L);
        if (token != null && token.contains("\"t\":\"") && token.length() > 12) {
            try {
                JSONObject json = new JSONObject(token);
                String tk = json.optString("t", "");
                String uid = json.optString("u", "");
                if (tk.length() > 8) {
                    detail.append("登录: 发现本地 accesstoken，拼 wechatParams 重试\n");
                    String base = TaskApi.ACT_GOOSE + "&wechatParams=" + enc(tk);
                    if (!uid.isEmpty()) {
                        base = base + "&uid=" + enc(uid);
                    }
                    if (page.navigate(base, 15_000L) && waitLogged(page, 10_000L)) {
                        detail.append("登录: 用本地 accesstoken 登录成功\n");
                        return true;
                    }
                }
            } catch (Throwable e) {
                detail.append("登录: accesstoken 解析失败 ").append(e).append('\n');
            }
        }

        String text = page.eval("(function(){try{return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,120)}catch(e){return ''}})()", 4_000L);
        detail.append("登录: 三次都失败 · 页面 ").append(briefUrl(page.url()))
                .append(" · ").append(text == null ? "" : text).append('\n');
        return false;
    }

    /** 轮询等页面把登录数据跑出来。 */
    private static boolean waitLogged(WebPage page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pageLogged(page)) {
                return true;
            }
            String now = page.url();
            if (now != null && !now.isEmpty()
                    && !now.startsWith("about:") && !isGoosePage(now)) {
                // v3.44：裸地址进鹅蛋页会先跳门户 SSO（sso_token → 跳回带 pc/pk 的
                // 主页），本文件注释里就写着「这一段实测要 10 秒上下」。但旧代码
                // 遇到中转页 URL（不含 actNo=goose）立刻 return false，把 16 秒等待
                // 直接打断 → 稳定误报「收鹅蛋页面未登录」。登录中转是正常链路，继续等；
                // 只有真正的外部跳转（下载引导 / 广告位）才提前放弃。
                if (isLoginHop(now)) {
                    sleep(700L);
                    continue;
                }
                return false;
            }
            sleep(700L);
        }
        return false;
    }

    /** 门户 SSO 登录中转页：URL 不含 actNo=goose，但确实是登录链路的一环。 */
    private static boolean isLoginHop(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        // 只认中国移动自己的域，别把外部广告域名误当登录页。
        if (!u.contains("10086.cn") && !u.contains("lncmcc.com")) {
            return false;
        }
        return u.contains("sso") || u.contains("login") || u.contains("token")
                || u.contains("passport") || u.contains("oauth") || u.contains("auth")
                || u.contains("accesstoken");
    }

    private static String briefUrl(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.indexOf("pc=");
        String head = cut > 0 ? url.substring(0, cut) + "pc=…" : url;
        return head.length() <= 70 ? head : head.substring(0, 70) + "…";
    }

    /** 页面拿到 pageInitData（含 reqTimestamp / eggCount）才算真登录。 */
    private static boolean pageLogged(WebPage page) {
        // 11-07 日志：页面已经渲染成「大鹅养成记」、桥也可用，但这里一直判未登录，
        // 于是被当成 pc/pk 过期、清掉唯一入口。根因是判据太窄 ——
        // 既要 #app.__vue__，又要求 pageInitData 带 reqTimestamp，页面一改版就全不成立。
        // 现在三条任一成立就算登录：Vue 上有 pageInitData / 本地有 accesstoken / 文案像已登录。
        String value = page.eval("(function(){"
                + "try{"
                + "var el=document.querySelector('#app')||document.querySelector('#root');"
                + "var v=el&&el.__vue__;"
                + "if(!v&&el&&el.__vue_app__&&el.__vue_app__._instance) v=el.__vue_app__._instance;"
                + "function f(o,d){if(!o||d>12)return null;"
                + "if(o.pageInitData)return o;"
                + "var cs=o.$children||[];for(var i=0;i<cs.length;i++){var x=f(cs[i],d+1);if(x)return x}"
                + "return null}"
                + "if(v&&f(v,0))return '1';"
                + "try{var tk=localStorage.getItem('accesstoken')||localStorage.getItem('acToken');"
                + "if(tk&&tk.length>8)return '1'}catch(e){}"
                + "var t=document.body?document.body.innerText:'';"
                + "if(t&&/攒饲料|我的鹅蛋|投喂|已赠送/.test(t)"
                + "&&!/用户未登录|请先登录|登录失效/.test(t))return '1';"
                + "return '0'"
                + "}catch(e){return '0'}})()", 5_000L);
        return "1".equals(value);
    }

    private static String enc(String text) {
        try {
            return java.net.URLEncoder.encode(text, "UTF-8");
        } catch (Throwable e) {
            return text;
        }
    }

    /** 单轮：注入脚本、等回报。 */
    /**
     * 调页面组件的方法把动作做完，全程零 DOM 点击。
     *
     * <p>这些动作在页面里就是几个函数：signIndexFun 打卡、feedGooseFun 投喂、
     * harvestEggFun 收蛋、toDoTask 做任务。它们内部走页面自己的请求封装，
     * 会发出带瑞数 WAF 令牌 + AES 密文的真实 XHR，参数和加密都由页面完成，
     * 模块不拼请求、不点按钮。号 2 已实测这套调用能发出
     * index/signIndex/taskIndex/doTask/signIn/feedGoose/harvestEgg 请求。
     *
     * @return null 表示页面里没找到组件（run() 会据此判失败）
     */
    /** v3.96：把本轮真实网络回执写进明细，判定尺子的证据来源。 */
    private static void appendReceipt(StringBuilder detail, JSONObject out) {
        int netN = out.optInt("netN", 0);
        int submitN = out.optInt("submitN", 0);
        detail.append("回执复核(v3.98): 鹅蛋接口 ").append(netN)
                .append(" 条 · 提交类 ").append(submitN).append(" 条")
                .append(" · 业务数据 ").append(out.optBoolean("dataReady", false) ? "就绪" : "未就绪")
                .append('\n');
        String subs = joinPaths(out.optJSONArray("submitPaths"));
        String querys = joinPaths(out.optJSONArray("queryPaths"));
        detail.append("  提交类: ").append(subs.isEmpty() ? "无" : subs).append('\n');
        detail.append("  查询类: ").append(querys.isEmpty() ? "无" : querys).append('\n');
        // v3.97：出游归来奖励单独验收。10:40 日志 travelRewardItems 有 1 项，
        // 但 claimTravelReward 一次都没发 —— 旧版只刷新结算、没点弹窗领取按钮。
        // 只有这里看到 claimTravelReward 的真实提交回执，才敢说「领取了」。
        int travelItems = out.optInt("travelItems", 0);
        if (travelItems > 0) {
            boolean claimed = subs.contains("claimTravelReward");
            detail.append("  出游归来领取: ").append(claimed
                            ? "已确认（页面自己发出 claimTravelReward 回执）"
                            : "未确认（本轮没有 claimTravelReward 回执，弹窗按钮没点到或已无待领）")
                    .append(" · 点击 ").append(out.optInt("travelTap", 0)).append(" 次\n");
        }
    }

    /** v3.96：JSONArray 拼成短串，供日志与摘要使用。 */
    private static String joinPaths(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length() && sb.length() < 160; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    /** 业务数据没就绪时由脚本报出来，主线程据此决定要不要 reload 再来一轮。 */
    private static volatile String lastStage = "";

    /**
     * 跑鹅蛋动作链；页面业务数据没就绪时，重载页面再跑一轮。
     *
     * <p>11-11 10:36 日志：模块打开的页面只初始化了一半（数组字段全 0、首屏
     * 「--个 / 加载中」），而同一时刻用户手动打开的页面 2 秒内就把 10 个接口发齐。
     * 半初始化是「重开一次 / 被下载引导页顶走」留下的，reload 能恢复；所以这里
     * 不再一判 notready 就整轮放弃，而是最多再来一次。
     */
    private static TaskRunner.Result runVue(WebPage page, StringBuilder detail) {
        TaskRunner.Result first = runVueOnce(page, detail);
        if (first == null || !"notready".equals(lastStage)) {
            return first;
        }
        String url = page.url();
        detail.append("数据没就绪，重载活动页再来一轮 · ").append(brief(url)).append('\n');
        if (!page.navigate(url, 30_000L)) {
            detail.append("重载没完成，保持本轮结果\n");
            return first;
        }
        sleep(2_500L);
        if (!waitLogged(page, 15_000L)) {
            detail.append("重载后页面没恢复登录，保持本轮结果\n");
            return first;
        }
        TaskRunner.Result second = runVueOnce(page, detail);
        return second != null ? second : first;
    }

    private static TaskRunner.Result runVueOnce(WebPage page, StringBuilder detail) {
        lastStage = "";
        // 上一轮的结果还挂在 window 上时，注入新脚本后可能先读到它。先清空再注入。
        page.eval("(function(){try{window.__cmcc_goose=''}catch(e){}})()", 3_000L);
        page.eval(VUE_SCRIPT, 8_000L);
        String raw = null;
        // 这套动作是串行等的：刷新+出游领取 → 日常任务（每个 1.7 秒）→ 打卡 →
        // 投喂/收蛋收敛循环（v3.97 最多 6 轮，每轮约 6 秒）→ 奖励箱 → 收尾刷新。
        // 循环跑满时整条链可达 90 秒以上，卡太紧会把「正在做」误判成「超时」。
        long deadline = System.currentTimeMillis() + 150_000L;
        while (System.currentTimeMillis() < deadline) {
            String value = page.eval(
                    "(function(){var v=window.__cmcc_goose;return v?String(v):'';})()", 6_000L);
            if (value != null && !value.isEmpty()) {
                raw = value;
                break;
            }
            sleep(600L);
        }
        if (raw == null) {
            detail.append("页面组件脚本超时（150 秒没有回报）\n");
            return null;
        }
        try {
            JSONObject out = new JSONObject(raw);
            String stage = out.optString("stage", "");
            if (!out.optBoolean("ok", false) && "init".equals(stage)) {
                detail.append("组件: ").append(out.optString("msg")).append('\n');
                return null;
            }
            if (!out.optBoolean("ok", false) && "login".equals(stage)) {
                // v3.92：21:44 日志实证 —— ensureLogin 已判已登录、URL 带 pc/pk、
                // 前台真页 Cookie 有 uid、localStorage 有 QWHDSSOJWT，票是好的。
                // 旧代码在这里无条件 SsoLogin.invalidate()，把刚记住的有效入口清掉，
                // 下一轮只能从裸地址重走 SSO，正是「每次都要手动点一次」的根因。
                // 现在保留票，只报数据未就绪，让下一轮直接用已缓存的带票入口。
                detail.append("组件: 页面组件已找到，但业务数据还没加载完（保留已缓存的 pc/pk）\n");
                detail.append("组件现场: ").append(out.optString("text", "")).append('\n');
                return TaskRunner.Result.fail("收鹅蛋页面数据未就绪",
                        detail.toString(),
                        "页面已登录、票据有效，只是这次数据没加载完（已保留带 pc/pk 的入口，没清缓存）。"
                                + "直接再执行一次即可；仍不行再在 App 里手动点一次「收鹅蛋」。");
            }
            if (!out.optBoolean("ok", false) && "notready".equals(stage)) {
                // v3.96：07:45 日志实证 —— 首屏「已有鹅蛋：--个 加载中...」，
                // 页面业务数据根本没回来。旧版照跑动作链，结果全是「跳过」，
                // 零提交却打 ✓。这里直接如实报没做成，且不锁机会。
                // v4.0.5：19:24 日志实证 —— raiseGoose/index 四次返回都是
                // {"code":"200","dataEn":"<密文>","success":true}，离屏页只拿到加密响应，
                // 首屏一直「加载中 / --个」，脚本空等 16 秒后零提交。
                // 模块不解密、不拼加密请求、不发业务请求；这种「读不到」不是任务失败，
                // 按尺子「零提交一律不算完成、不记失败次数」改记为跳过，不占用 ✗ 次数。
                detail.append("接口: [API-GOOSE] raiseGoose/index 若返回 dataEn 密文，"
                        + "说明只拿到加密响应，模块不解密、不拼签名\n");
                detail.append("业务数据: 未就绪（首屏 ").append(brief(out.optString("firstText", ""))).append("）\n");
                detail.append("组件数组字段: ").append(out.optString("arrayFields", "")).append('\n');
                appendReceipt(detail, out);
                String summary = "收鹅蛋 · 本轮零提交（页面业务数据未就绪）";
                lastStage = "notready";
                return TaskRunner.Result.skip(summary, detail.toString(),
                        "这一轮鹅蛋页面数据没加载出来（首屏还停在「加载中 / --个」，"
                                + "任务清单和蛋箱都是空的）。模块不解密、不拼加密请求、没有冒充完成，"
                                + "也不记失败次数，机会还在。\n"
                                + "这次已经自动重载页面重跑过一轮；若还是这样，在 App 里手动打开一次"
                                + "「收鹅蛋换好礼」，等页面数据出来，再回来执行。\n"
                                + "导出完整日志看 [API-GOOSE] 与 [GOOSE] 段落可逐条核对。");
            }
            JSONObject before = out.optJSONObject("before");
            JSONObject after = out.optJSONObject("after");
            JSONArray actions = out.optJSONArray("actions");
            StringBuilder lines = new StringBuilder();
            StringBuilder text = new StringBuilder("收鹅蛋 · ");
            if (actions != null) {
                for (int i = 0; i < actions.length(); i++) {
                    lines.append("· ").append(actions.optString(i)).append('\n');
                }
            }
            int total = out.optInt("taskTotal", 0);
            int todo = out.optInt("taskTodo", 0);
            int done = out.optInt("taskDone", 0);
            int travelItems = out.optInt("travelItems", 0);
            int travelTap = out.optInt("travelTap", 0);
            int travelBoxes = out.optInt("travelBoxes", 0);
            String travelLabels = joinPaths(out.optJSONArray("travelLabels"));
            if (travelItems > 0) {
                lines.append("出游奖励: ").append(travelItems)
                        .append(" 项 · 弹窗容器 ").append(travelBoxes)
                        .append(" 个 · 页面领取按钮点了 ").append(travelTap).append(" 次");
                if (!travelLabels.isEmpty()) {
                    lines.append(" [").append(travelLabels).append("]");
                }
                lines.append('\n');
            }
            int rounds = out.optInt("rounds", 0);
            if (rounds > 0) {
                lines.append("投喂/收蛋循环: ").append(rounds).append(" 轮（收敛或达上限）\n");
            }
            if (total > 0) {
                lines.append("日常任务: 共 ").append(total).append(" 个，")
                        .append("待做 ").append(todo).append("，")
                        .append("已提交 ").append(done).append('\n');
            }
            boolean changed = false;
            if (before != null && after != null) {
                changed = !before.optString("egg").equals(after.optString("egg"))
                        || !before.optString("my").equals(after.optString("my"))
                        || !before.optString("feed").equals(after.optString("feed"))
                        || !before.optString("sign").equals(after.optString("sign"));
                lines.append("鹅蛋: ").append(before.optString("egg"))
                        .append(" → ").append(after.optString("egg"))
                        .append("（累计 ").append(before.optString("my"))
                        .append(" → ").append(after.optString("my")).append("）\n");
                lines.append("饲料: ").append(before.optString("feed"))
                        .append(" → ").append(after.optString("feed")).append('\n');
                lines.append("打卡: ").append(before.optString("sign"))
                        .append(" → ").append(after.optString("sign")).append('\n');
            }
            String body = out.optString("text", "");
            if (!body.isEmpty()) {
                lines.append("页面: ").append(body).append('\n');
            }
            detail.append("=== 页面组件 ===\n").append(lines);
            // v3.96 回执复核：查询接口 != 提交接口。日志里 07:45 那轮
            // index/taskIndex/signIndex/boxQuery 发了 16 条，提交类 0 条，
            // 却被判成 ✓。现在把真实提交数摆到判定第一优先级。
            appendReceipt(detail, out);
            int submitN = out.optInt("submitN", 0);
            int netN = out.optInt("netN", 0);
            boolean dataReady = out.optBoolean("dataReady", false);
            String submitPaths = joinPaths(out.optJSONArray("submitPaths"));
            String queryPaths = joinPaths(out.optJSONArray("queryPaths"));

            if (submitN == 0 && !changed) {
                // 零提交 + 数据没变 = 一个任务都没做。绝不能记完成。
                String why = !dataReady ? "页面业务数据未就绪"
                        : (netN == 0 ? "本轮没抓到任何鹅蛋接口" : "只发了查询接口，没有提交");
                detail.append("判定(v3.98): 零提交且数据无变化 → 不算完成（").append(why).append("）\n");
                return TaskRunner.Result.fail(
                        "收鹅蛋 · 一个任务都没提交（提交类 0 条 / 查询 " + netN + " 条）",
                        detail.toString(),
                        "这一轮鹅蛋确实没做成：日志里只有查询接口（" + queryPaths + "），"
                                + "没有任何提交类接口（doTask/signIn/feedGoose/harvestEgg…）。\n"
                                + "模块不再把它记成完成，也没记失败次数，机会还在。\n"
                                + "原因：" + why + "。直接再执行一次；"
                                + "若连续几轮都这样，先在 App 里手动打开一次活动页让数据加载出来。");
            }

            StringBuilder summary = new StringBuilder("收鹅蛋 · ");
            if (done > 0) {
                summary.append("日常任务 ").append(done).append(" 个 · ");
            }
            summary.append("提交类回执 ").append(submitN).append(" 条");
            if (!submitPaths.isEmpty()) {
                summary.append("（").append(submitPaths).append("）");
            }
            if (changed) {
                summary.append(" · 数据已变化");
            }
            String resultText = summary.toString();
            if (changed || submitN > 0) {
                return TaskRunner.Result.ok(resultText, detail.toString(), resultText);
            }
            return TaskRunner.Result.idle(resultText, detail.toString(), resultText);
        } catch (Throwable e) {
            detail.append("组件结果解析失败: ").append(e).append('\n').append(raw).append('\n');
            return null;
        }
    }

    private static final String VUE_SCRIPT = """
            (function(){
              var ID='__cmcc_goose';
              window[ID]='';
              function done(o){ try{ window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"ok":false,"stage":"crash"}' } }
              function bodyText(){ try{ return (document.body?document.body.innerText:'').replace(/\\s+/g,' ') }catch(e){ return '' } }
              function findComp(v,d){
                if(!v||d>10) return null;
                if(typeof v.feedGooseFun==='function'||typeof v.harvestEggFun==='function') return v;
                var cs=v.$children||[];
                for(var i=0;i<cs.length;i++){ var r=findComp(cs[i],d+1); if(r) return r }
                return null;
              }
              function snap(c){
                var p=c.pageInitData||{};
                return {egg:String(p.eggCount==null?'':p.eggCount),
                        my:String(p.myEggCount==null?'':p.myEggCount),
                        feed:String(p.feedCount==null?'':p.feedCount),
                        sign:String(p.signFlag==null?'':p.signFlag),
                        travel:String(p.travelStatus==null?'':p.travelStatus)};
              }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function pick(c,names){
                for(var i=0;i<names.length;i++){ if(typeof c[names[i]]==='function') return names[i] }
                return '';
              }
              function leftOf(list){
                var out=[];
                for(var i=0;i<list.length;i++){
                  var it=list[i];
                  if(!it) continue;
                  if(String(it.isComplete)==='2') continue;
                  out.push(it);
                }
                return out;
              }
              // ---- v3.96 判定尺子：查询接口 != 提交接口 ----
              // 07:45 日志实证：一轮鹅蛋只发 index/taskIndex/signIndex/boxQuery 共 16 条查询，
              // doTask/signIn/feedGoose/harvestEgg 一条都没有，却被打成 ✓「已跑完一轮」。
              // 这里挂网络钩子把本轮 raiseGoose 请求全记下来，只认真实提交类接口的 2xx/success 回执。
              var NET=[];
              // 提交类白名单：历史日志（11-06 v3.18 / 11-08 v3.93）实证这些接口确实发出过。
              // 其余一律不算提交（index/taskIndex/signIndex/boxQuery/prizeList/userState/... 全是查询）。
              var SUBMIT={doTask:1,signIn:1,feedGoose:1,harvestEgg:1,
                          claimTravelReward:1,claimMailboxPrize:1,claimPrize:1,boxClaim:1};
              function pathOf(u){
                var s=String(u||''), i=s.indexOf('/raiseGoose/');
                if(i<0) return '';
                var t=s.slice(i+'/raiseGoose/'.length).split('?')[0].split('#')[0].split('&')[0];
                return t.split('/')[0];
              }
              function isSubmit(e){
                if(!e) return false;
                var st=Number(e.s)||0;
                if(st<200||st>=400) return false;
                if(!SUBMIT[pathOf(e.u)]) return false;
                var b=String(e.b||'');
                if(/"success"\\s*:\\s*false/i.test(b)) return false;
                if(/"code"\\s*:\\s*"(?!200)/i.test(b)) return false;
                return true;
              }
              function hookGooseNet(){
                try{
                  var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open=function(m,u){ this.__qu=String(u||''); return op.apply(this,arguments) };
                  XMLHttpRequest.prototype.send=function(b){
                    var x=this;
                    try{ x.addEventListener('load',function(){
                      try{ if(String(x.__qu).indexOf('/raiseGoose/')>=0)
                        NET.push({u:String(x.__qu).slice(0,220),s:x.status,b:String(x.responseText||'').slice(0,600)}) }catch(e){}
                    }) }catch(e){}
                    return se.apply(this,arguments);
                  };
                }catch(e){}
                try{
                  var of=window.fetch;
                  if(typeof of==='function'){
                    window.fetch=function(input,init){
                      var u=(typeof input==='string')?input:((input&&input.url)||'');
                      var r=of.apply(this,arguments);
                      try{
                        if(String(u).indexOf('/raiseGoose/')>=0 && r && typeof r.then==='function'){
                          r.then(function(rr){
                            try{ var cp=rr.clone(); cp.text().then(function(t){
                              NET.push({u:String(u).slice(0,220),s:rr.status,b:String(t).slice(0,600)});
                            }) }catch(e){}
                          });
                        }
                      }catch(e){}
                      return r;
                    };
                  }
                }catch(e){}
              }
              function tallyNet(){
                var q=[],s=[];
                for(var i=0;i<NET.length;i++){
                  var p=pathOf(NET[i].u)||'';
                  if(isSubmit(NET[i])){ if(s.indexOf(p)<0) s.push(p) }
                  else if(p && q.indexOf(p)<0) q.push(p);
                }
                var n=0;
                for(var j=0;j<NET.length;j++){ if(isSubmit(NET[j])) n++ }
                return {netN:NET.length,submitN:n,submitPaths:s,queryPaths:q};
              }
              // v3.96：数值判据。「已有鹅蛋：--个」里的 '--' 不是数据，
              // parseInt 出 NaN 就必须算未就绪，不能因为 eggCount!=null 就放行。
              function numOf(v){
                if(v===null||v===undefined) return -1;
                if(typeof v==='number') return isFinite(v)?v:-1;
                var n=parseInt(String(v).replace(/[^0-9-]/g,''),10);
                return isNaN(n)?-1:n;
              }
              function readyNow(c,bt){
                var p=c.pageInitData||{};
                var egg=numOf(p.eggCount), feed=numOf(p.feedCount), my=numOf(p.myEggCount);
                if(!(egg>=0||feed>=0||my>=0)) return false;
                if(/加载中|--个|undefined|NaN/.test(bt)) return false;
                return true;
              }
              // 组件上所有数组字段的名字和长度，数据没回来时用来定位字段名是否变了。
              function arrayFields(c){
                var a=[];
                try{
                  for(var k in c){
                    if(!Object.prototype.hasOwnProperty.call(c,k)) continue;
                    var v=c[k];
                    if(Object.prototype.toString.call(v)==='[object Array]'){
                      a.push(k+'='+v.length);
                    }
                  }
                }catch(e){}
                return a.slice(0,24).join(',');
              }
              // ---- v3.97：出游归来奖励领取 ----
              // 10:40 日志实证：travelRewardItems 有 1 项，claimTravelReward 却 0 次 ——
              // 旧版只刷 index 做「页面刷新结算」，没点弹窗领取按钮，大鹅游玩带回来的饲料没到手。
              // 11-06 实证：页面自己点弹窗会发 claimTravelReward，明文回「饲料已领取成功 rewardNumber:4」。
              // 该请求带 sinoEnParams 加密体 + 瑞数动态头，模块绝不手拼；
              // 这里只点页面自己的 DOM 按钮，加密与签名全交给页面 JS，再用回执复核验收。
              function fireEl(e){
                try{
                  var r=e.getBoundingClientRect();
                  var cx=r.left+r.width/2, cy=r.top+r.height/2;
                  var mk=function(){return {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}};
                  var ts=['pointerdown','touchstart','mousedown','pointerup','touchend','mouseup'];
                  for(var i=0;i<ts.length;i++){
                    try{
                      var ev;
                      if(ts[i].indexOf('pointer')===0 && typeof PointerEvent==='function') ev=new PointerEvent(ts[i],mk());
                      else if(ts[i].indexOf('touch')===0) ev=new Event(ts[i],{bubbles:true,cancelable:true});
                      else ev=new MouseEvent(ts[i],mk());
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  if(typeof e.click==='function') e.click();
                  return true;
                }catch(x){return false}
              }
              function visEl(e){
                try{
                  if(!e) return false;
                  var r=e.getBoundingClientRect();
                  if(r.width<2||r.height<2) return false;
                  var s=getComputedStyle(e);
                  return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity||1)>0.05;
                }catch(x){return false}
              }
              // 只扫出游/奖励弹窗容器内的领取按钮，不乱点任务列表里的「去领取」。
              function tapTravel(){
                var r={clicked:0,labels:[],boxes:0};
                var WORD=/开心收下|立即领取|一键领取|全部领取|领取饲料|收下|领取/;
                var SKIP=/已领取|已收下|已获得|去领取|去逛逛|去完成|去查看|规则|说明/;
                // 先扫出游/奖励专属弹窗，再退到通用 popup，避免误点任务弹窗里的「去领取」。
                var groups=[
                  '[class*=travel],[class*=reward],[class*=Travel],[class*=Reward]',
                  '[class*=popup],[class*=Popup],.van-popup'
                ];
                var seen=[];
                for(var g=0;g<groups.length;g++){
                  var boxes=document.querySelectorAll(groups[g]);
                  for(var b=0;b<boxes.length;b++){
                    var box=boxes[b];
                    if(!visEl(box)) continue;
                    if(g===0) r.boxes++;
                    var inner=box.querySelectorAll('button,a,div,span,img,i');
                    for(var i=0;i<inner.length;i++){
                      var e=inner[i];
                      if(!visEl(e)||seen.indexOf(e)>=0) continue;
                      seen.push(e);
                      var t=String(e.innerText||e.alt||'').replace(/ /g,'').slice(0,16);
                      var cls=String(e.className||'');
                      if(t&&SKIP.test(t)) continue;
                      var hitw=t?WORD.test(t):false;
                      var imgBtn=(e.tagName==='IMG')&&/travel|reward|btn|get|ling|claim|shou/i.test(cls+' '+String(e.src||''));
                      if(!hitw&&!imgBtn) continue;
                      if(fireEl(e)){
                        r.clicked++;
                        r.labels.push(t||('IMG:'+cls.slice(0,24)));
                        if(r.clicked>=2) return r;
                      }
                    }
                  }
                  if(r.clicked>0) return r;  // 出游弹窗里点到了就不再扫通用 popup
                }
                return r;
              }
              (async function(){
                var root=document.querySelector('#app');
                var vm=root&&root.__vue__;
                var c=vm?findComp(vm,0):null;
                if(!c){ done({ok:false,stage:'init',msg:'页面没有渲染出组件',url:location.href,text:bodyText().slice(0,300)}); return }
                // v3.96：先挂网络钩子，本轮动作链发出的每个 raiseGoose 请求才能被记账。
                hookGooseNet();
                // v3.92：21:44 日志 —— ensureLogin 已判「已登录」并记住带 pc/pk 的入口，
                // 首屏也渲染出「攒饲料 / 已有鹅蛋」，但数据还在「加载中... / --个」。
                // v3.96：07:45 日志实证 —— 旧判据只认 eggCount!=null，'--个' 也算就绪，
                // 于是动作链全是「跳过」，零提交却报「已跑完一轮」。
                // 现在要求首屏出现真实数字，且不含「加载中 / --个」；期间主动刷 index 帮页面取数。
                // v4.2.2：11-11 10:36 日志实证 —— 模块打开的这一页只发出了 index 一条，
                // 首屏一直「已有鹅蛋：--个 / 加载中…」，脚本等 6 秒就判 notready 跳过；
                // 同一时段用户手动进页面，一次连发 getNoticeList/index/userState/taskIndex
                // 等 10 个接口，2 秒内就齐了。说明不是「接口不给数据」，是这一页只初始化了
                // 一半（重开/被下载页顶走后 Vue 没跑完整）。等 6 秒太短，延长到 30 秒，
                // 每 5 秒主动催一次 index，仍不齐就交给主线程 reload 重来一轮。
                var ready=false, bt=bodyText();
                for(var wl=0;wl<60;wl++){
                  bt=bodyText();
                  if(readyNow(c,bt)){ ready=true; break }
                  if(wl>0 && wl%10===0 && typeof c.indexFun==='function'){
                    try{ c.indexFun('1') }catch(e){}
                  }
                  await wait(500);
                }
                var ls='';
                try{ ls=localStorage.getItem('accesstoken')||localStorage.getItem('acToken')||'' }catch(e){}
                var logged=!!(c.pageInitData)||ls.length>8
                  ||(/攒饲料|我的鹅蛋|投喂|已赠送/.test(bt)&&!/用户未登录|请先登录|登录失效/.test(bt));
                var out={ok:true,stage:'run',actions:[],before:snap(c),after:null,url:location.href,
                         taskTotal:0,taskTodo:0,taskDone:0,travelItems:0,login:logged,
                         dataReady:ready,netN:0,submitN:0,submitPaths:[],queryPaths:[],
                         arrayFields:'',firstText:bt.slice(0,160)};
                if(!out.login){ done({ok:false,stage:'login',msg:'页面未登录',url:location.href,text:bt.slice(0,200)}); return }
                if(!ready){
                  // 数据没回来就不跑动作链：跑了也全是「跳过」，只会制造假 OK。
                  out.ok=false; out.stage='notready';
                  out.arrayFields=arrayFields(c);
                  var tn=tallyNet();
                  out.netN=tn.netN; out.submitN=tn.submitN;
                  out.submitPaths=tn.submitPaths; out.queryPaths=tn.queryPaths;
                  done(out); return;
                }

                // ---- 1. 刷新主页 + 领「大鹅游玩回来的」出游奖励 (v3.97) ----
                // indexFun("1") 内部发 index 请求把出游奖励/任务奖励结算出来，
                // travelRewardItems 有值就说明「游玩回来」有东西可领。
                // 10:40 日志实证：旧版只刷新结算、不点弹窗领取按钮，
                // 于是 claimTravelReward 一次都没发，饲料一直没到手。
                // 11-06 实证：页面自己点弹窗会发 claimTravelReward，明文回
                // 「饲料已领取成功 rewardNumber:4」。该请求带 sinoEnParams 加密体 +
                // 瑞数动态头，模块绝不手拼；这里只调页面自己的队列方法让弹窗显示，
                // 再点页面自己的领取按钮，发出与否由回执复核验收。
                try{
                  if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(2200) }
                  var ti=c.travelRewardItems;
                  out.travelItems=(ti&&ti.length)?ti.length:0;
                  if(out.travelItems>0){
                    // 只调页面自己的弹窗队列方法把出游弹窗显示出来，不构造任何领取参数。
                    // 用户可能已手动关过弹窗，此时 travelRewardPop=false，需要重新入队。
                    try{
                      if(c.travelRewardPop!==true && typeof c.enqueuePopup==='function') c.enqueuePopup('travelRewardPop');
                      else if(typeof c.processPopupQueue==='function') c.processPopupQueue();
                    }catch(x){}
                    await wait(900);
                    var tp=tapTravel();
                    out.travelTap=tp.clicked; out.travelLabels=tp.labels; out.travelBoxes=tp.boxes;
                    out.actions.push('出游奖励: '+out.travelItems+' 项 · 弹窗容器 '+tp.boxes
                                     +' 个 · 领取按钮点了 '+tp.clicked+' 次'
                                     +(tp.labels.length?' ['+tp.labels.join('/')+']':''));
                    if(tp.clicked>0) await wait(1800);  // 留时间给页面自己发 claimTravelReward
                  }
                }catch(e){ out.actions.push('刷新/出游领取异常:'+e.message) }

                // ---- 2. 日常任务：浏览亲友圈、逛专区这些 ----
                //      toDoTask 内部先调接口登记，再 toJump 打开广告页。
                //      把 toJump 临时摘掉，只留登记那一步：服务端该记的记了，
                //      页面也不会被导航走，后面的投喂收蛋还能接着做。
                var taskName=pick(c,['taskIndexFun','taskFun']);
                if(taskName){
                  try{
                    await c[taskName](); await wait(1600);
                    var list=c.taskListArr||[];
                    out.taskTotal=list.length;
                    var todo=leftOf(list);
                    out.taskTodo=todo.length;
                    if(todo.length>0 && typeof c.toDoTask==='function'){
                      var oldJump=c.toJump;
                      c.toJump=function(){};
                      for(var j=0;j<todo.length && j<12;j++){
                        var t=todo[j];
                        try{
                          await c.toDoTask(t,j);
                          await wait(1700);
                          out.taskDone++;
                          out.actions.push('任务「'+String(t.jobTitle||t.taskKey||j).slice(0,14)+'」+'
                                           +String(t.jobReward||t.reward||''));
                        }catch(ee){ out.actions.push('任务异常:'+ee.message) }
                      }
                      c.toJump=oldJump;
                      await wait(1000);
                    }
                    // 再拉一次：toDoTask 登记成功后服务端即把饲料发下来，
                    // 这一步把最新数据回灌到组件字段里，不需要再点任何「领取」按钮。
                    await c[taskName](); await wait(1600);
                  }catch(e){ out.actions.push('任务阶段异常:'+e.message) }
                }

                // ---- 3. 打卡领饲料 ----
                try{
                  if(typeof c.signIndexFun==='function'){
                    await c.signIndexFun('handle'); await wait(1500);
                    var sign=c.signObj||{};
                    var st=sign.taskList||[];
                    var need=leftOf(st).length>0;
                    out.actions.push('打卡: '+(need?'待打卡':'今日已完成'));
                    if(need && typeof c.dakaFeedA==='function'){
                      await c.dakaFeedA(); await wait(1800);
                      out.actions.push('打卡领饲料');
                    }
                  }
                }catch(e){ out.actions.push('打卡异常:'+e.message) }

                // ---- 4. 投喂/收蛋 收敛循环 (v3.97) ----
                // 10:40 日志实证：任务做完饲料 10→64，投喂却只发 1 次 feedGoose，
                // 收蛋只发 1 次 harvestEgg，末尾还写着「可投喂饲料64袋」。
                // 根因是旧版每样只做一次就收工：投喂一次后饲料还够、蛋也新产出来，
                // 却不再喂也不再收。现在按「刷新→有饲料就喂→有蛋就收→再刷新」
                // 循环，直到饲料/蛋连续两轮不再变化，或达到 6 轮上限。
                // 全程只调页面自己的 c.feedGooseFun / c.harvestEggFun / c.indexFun，
                // 加密参数、签名、票据全由页面 JS 生成，模块不碰请求、不碰票据。
                var lastKey='', stall=0, rounds=0;
                for(var rd=0; rd<6; rd++){
                  try{ if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(1500) } }catch(e){}
                  var pp=c.pageInitData||{};
                  var feed=numOf(pp.feedCount), eggs=numOf(pp.eggCount);
                  var key=feed+'/'+eggs;
                  var did=false;
                  if(feed>0 && typeof c.feedGooseFun==='function'){
                    try{ c.feedGooseFun(); await wait(2300); did=true;
                         out.actions.push('第'+(rd+1)+'轮投喂（饲料 '+feed+'）') }
                    catch(e){ out.actions.push('第'+(rd+1)+'轮投喂异常:'+e.message) }
                  }
                  if(eggs>0 && typeof c.harvestEggFun==='function'){
                    try{ c.harvestEggFun(); await wait(2300); did=true;
                         out.actions.push('第'+(rd+1)+'轮收蛋（'+eggs+' 枚）') }
                    catch(e){ out.actions.push('第'+(rd+1)+'轮收蛋异常:'+e.message) }
                  }
                  rounds=rd+1;
                  if(!did){ out.actions.push('第'+(rd+1)+'轮: 饲料/待收蛋都为空，收敛'); break }
                  if(key===lastKey){
                    stall++;
                    if(stall>=2){ out.actions.push('第'+(rd+1)+'轮: 连续两轮无变化，收敛'); break }
                  }else{ stall=0 }
                  lastKey=key;
                }
                out.rounds=rounds;

                // ---- 6. 奖金箱 ----
                try{
                  if(typeof c.getBoxListFun==='function'){
                    await c.getBoxListFun(); await wait(1200);
                    var box=c.boxList||[];
                    if(box.length>0){ out.actions.push('奖励箱 '+box.length+' 个') }
                  }
                }catch(e){}

                // ---- 7. 收尾刷新：再发一轮 index/signIndex/taskIndex，让服务端把
                //         这一轮攒出来的奖励结算完，数据回灌到快照里。全程不点 DOM。 ----
                try{
                  if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(2000) }
                  if(typeof c.signIndexFun==='function'){ await c.signIndexFun(); await wait(700) }
                  if(typeof c.taskIndexFun==='function'){ await c.taskIndexFun(); await wait(1000) }
                }catch(e){}
                out.after=snap(c); out.text=bodyText().slice(0,300);
                // v3.96：动作链跑完再统一记账，把本轮真实提交类回执数回报给 Java 侧。
                var t2=tallyNet();
                out.netN=t2.netN; out.submitN=t2.submitN;
                out.submitPaths=t2.submitPaths; out.queryPaths=t2.queryPaths;
                out.arrayFields=arrayFields(c);
                done(out);
              })().catch(function(e){ done({ok:false,stage:'crash',msg:String(e)}) });
              return '1';
            })()
            """;

    /**
     * 只读体检脚本（体检通道打开时才注入）。
     *
     * 只做三件事：遍历 Vue 组件树、列出每个组件真实存在的方法名和 data 字段名、
     * 把关键词方法的函数源码（含真实接口路径）打印出来。
     * 全程不调用任何组件方法、不点任何 DOM、不发任何请求——号 1 老号的可领内容
     * 一个都不会被动到。
     *
     * 结果按 4500 字符切成数组放到 window.__cmcc_goose_diag，Java 侧逐片读回写日志，
     * 这样即使没开「详细原始返回」也不会被 HookLog 的 8KB 上限截断。
     */
    private static final String DIAG_SCRIPT = """
            (function(){
              var L=[];
              var GOAL=/travel|mail|box|prize|reward|claim|read|gift|award|diary|souvenir|feed|harvest|sign|daka|task|todo|egg|draw|pop|receive|collect/i;
              var FIELD=/travel|mail|box|prize|reward|pop|unread|feed|egg|sign|task|list|gift|award|count|status|flag|show|visible|num/i;
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function brief(v){
                try{
                  if(v===null||v===undefined) return 'null';
                  var t=typeof v;
                  if(t==='string') return cut(v,80);
                  if(t==='number'||t==='boolean') return String(v);
                  if(t==='function') return 'function';
                  var seenV=[];
                  var s=JSON.stringify(v,function(k,val){
                    if(k==='__ob__') return undefined;
                    if(typeof val==='object'&&val){
                      if(seenV.indexOf(val)>=0) return '[循环]';
                      seenV.push(val);
                    }
                    return val;
                  });
                  if(!s) return t+(Array.isArray(v)?'('+v.length+')':'');
                  return cut(s,260);
                }catch(e){ return '<err '+e.message+'>' }
              }
              function ownKeys(o){
                var r=[]; if(!o) return r;
                try{ r=Object.keys(o) }catch(e){}
                return r;
              }
              function compName(c,i){
                var o=c.$options||{};
                var n=o.name||o.__name||o._componentTag||o.__file||'';
                if(!n){ try{ n=(c.$vnode&&c.$vnode.tag)||'' }catch(e){} }
                return (n?String(n):'(组件'+i+')');
              }
              try{
              var root=document.querySelector('#app');
              var vm=root&&root.__vue__;
              var comps=[];
              var seen=[];
              function walk(c,depth){
                if(!c||depth>12||comps.length>=60) return;
                if(seen.indexOf(c)>=0) return;
                seen.push(c); comps.push(c);
                var cs=[]; try{ cs=c.$children||[] }catch(e){}
                for(var j=0;j<cs.length;j++) walk(cs[j],depth+1);
              }
              walk(vm,0);
              line('=== 收鹅蛋组件只读诊断 v3.19 ===');
              line('说明: 本版只列方法名/字段名/源码, 不调用任何方法、不点击、不发请求、不领取任何东西');
              line('URL: '+cut(location.href,180));
              line('Vue 根实例: '+(vm?'yes':'no')+'   遍历到的组件数: '+comps.length);
              var goalHits=[];
              var allSrc=[];
              var srcCount=0;
              for(var i=0;i<comps.length;i++){
                var c=comps[i];
                var name=compName(c,i);
                line('');
                line('---- ['+i+'] '+name+' ----');
                var opt=c.$options||{};
                var mo=opt.methods||{};
                var mnames=ownKeys(mo);
                var selfFns=[];
                try{
                  var own=Object.getOwnPropertyNames(c);
                  for(var q=0;q<own.length&&selfFns.length<80;q++){
                    var k=own[q];
                    if(k.charAt(0)==='$'||k.charAt(0)==='_') continue;
                    if(typeof c[k]==='function'&&mnames.indexOf(k)<0) selfFns.push(k);
                  }
                }catch(e){}
                line('methods('+mnames.length+'): '+cut(mnames.join(', '),1600));
                if(selfFns.length) line('实例自有函数('+selfFns.length+'): '+cut(selfFns.join(', '),800));
                var co=ownKeys(opt.computed||{});
                if(co.length) line('computed('+co.length+'): '+cut(co.join(', '),600));
                var d=null;
                try{ d=c._data||c.$data||{} }catch(e){ d={} }
                var dk=ownKeys(d);
                line('data 字段('+dk.length+'): '+cut(dk.join(', '),1600));
                var fl=[];
                for(var a=0;a<dk.length&&fl.length<26;a++){
                  var fk=dk[a];
                  if(!FIELD.test(fk)) continue;
                  var fv; try{ fv=c[fk] }catch(e){ fv=null }
                  fl.push('    '+fk+' = '+brief(fv));
                }
                if(fl.length){ line('  关键词字段当前值:'); for(var b=0;b<fl.length;b++) line(fl[b]) }
                var src=[];
                for(var m=0;m<mnames.length;m++){
                  var mn=mnames[m];
                  if(!GOAL.test(mn)) continue;
                  goalHits.push('['+i+']'+name+'.'+mn);
                  var fn=null; try{ fn=mo[mn]||c[mn] }catch(e){}
                  if(typeof fn==='function'&&srcCount<40){
                    var s=''; try{ s=String(fn) }catch(e){ s='<toString失败>' }
                    allSrc.push(s);
                    src.push('    · '+mn+' : '+cut(s.replace(/\\s+/g,' '),700));
                    srcCount++;
                  }
                }
                for(var s2=0;s2<selfFns.length&&srcCount<40;s2++){
                  var sn=selfFns[s2];
                  if(!GOAL.test(sn)) continue;
                  goalHits.push('['+i+']'+name+'.'+sn+'(自)');
                  var f2=null; try{ f2=c[sn] }catch(e){}
                  if(typeof f2==='function'){
                    var s3=''; try{ s3=String(f2) }catch(e){ s3='<toString失败>' }
                    allSrc.push(s3);
                    src.push('    · '+sn+'(自) : '+cut(s3.replace(/\\s+/g,' '),700));
                    srcCount++;
                  }
                }
                if(src.length){ line('  关键词方法源码('+src.length+'):'); for(var z=0;z<src.length;z++) line(src[z]) }
              }
              // 弹窗/信箱/出游这类组件只有打开时才实例化，光遍历 $children 拿不到。
              // 但组件定义在 $options.components（局部注册）和 Vue.options.components（全局注册）
              // 里是静态存在的，读定义里的 methods 就能拿到真实方法名和接口路径。
              var defs=[]; var defSeen=[];
              function addDef(nm,def){
                if(!def||defSeen.indexOf(def)>=0) return;
                var o=def.options||def;
                if(!o||typeof o!=='object') return;
                defSeen.push(def); defs.push([nm,o]);
              }
              for(var ci=0;ci<comps.length;ci++){
                var cc=comps[ci]; var cco=(cc.$options||{}).components||{};
                var rk=ownKeys(cco);
                for(var ri=0;ri<rk.length;ri++) addDef('局部:'+rk[ri],cco[rk[ri]]);
              }
              try{
                var V=vm&&vm.constructor;
                var gregs=(V&&V.options&&V.options.components)||{};
                var gk=ownKeys(gregs);
                for(var gi=0;gi<gk.length;gi++) addDef('全局:'+gk[gi],gregs[gk[gi]]);
              }catch(e){}
              line('');
              line('==== 已注册组件定义（'+defs.length+' 个，含未挂载的弹窗/信箱/出游）====');
              for(var di=0;di<defs.length&&di<70;di++){
                var dn=defs[di][0], dop=defs[di][1];
                var dm=dop.methods||{}; var dmk=ownKeys(dm);
                line('  ['+dn+'] name='+(dop.name||dop.__name||'?')+' methods('+dmk.length+'): '+cut(dmk.join(', '),900));
                for(var dj=0;dj<dmk.length&&srcCount<90;dj++){
                  var dmn=dmk[dj];
                  if(!GOAL.test(dmn)) continue;
                  goalHits.push('[定义]'+dn+'.'+dmn);
                  var dfn=dm[dmn];
                  if(typeof dfn==='function'){
                    var ds=''; try{ ds=String(dfn) }catch(e){ ds='<toString失败>' }
                    allSrc.push(ds);
                    line('      · '+dmn+' : '+cut(ds.replace(/\\s+/g,' '),500));
                    srcCount++;
                  }
                }
              }
              line('');
              line('==== GOAL 命中方法速查（'+goalHits.length+' 个）====');
              for(var g=0;g<goalHits.length;g++) line('  '+goalHits[g]);
              var joined=allSrc.join('\\n');
              var paths=[]; var pmap={};
              var re=new RegExp('[A-Za-z][A-Za-z0-9_]*[/][A-Za-z0-9_/-]{2,}','g'); var mm;
              while((mm=re.exec(joined))!==null){
                var p=mm[0];
                if(pmap[p]) continue; pmap[p]=1; paths.push(p);
                if(paths.length>=120) break;
              }
              line('');
              line('==== 源码里出现的接口路径（'+paths.length+' 个，去重）====');
              for(var p2=0;p2<paths.length;p2++) line('  '+paths[p2]);
              var wk=[];
              try{
                var wo=Object.getOwnPropertyNames(window);
                for(var w=0;w<wo.length;w++){
                  var wn=wo[w];
                  if(!/api|http|request|goose|box|task|vm|app/i.test(wn)) continue;
                  var wv=null; try{ wv=window[wn] }catch(e){}
                  var wt=typeof wv;
                  if(wt==='object'&&wv){
                    var wks=ownKeys(wv);
                    wk.push('  window.'+wn+' ('+wt+') keys: '+cut(wks.join(', '),300));
                  }else{
                    wk.push('  window.'+wn+' ('+wt+')');
                  }
                  if(wk.length>=25) break;
                }
              }catch(e){}
              if(wk.length){ line(''); line('==== window 上可疑挂载 ===='); for(var w2=0;w2<wk.length;w2++) line(wk[w2]) }
              line('');
              line('==== 诊断结束 ====');
              }catch(err){
                line('');
                line('!!!! 诊断中途异常（上方已收集的内容全部保留）: '+((err&&err.stack)||err));
              }
              var text=L.join('\\n');
              var parts=[]; var CH=4500;
              for(var off=0;off<text.length;off+=CH) parts.push(text.slice(off,off+CH));
              if(!parts.length) parts.push('(空)');
              window.__cmcc_goose_diag=parts;
              return '1';
            })()
            """;

    /**
     * v3.21 只读探测脚本（B 方案第一步）。
     *
     * 目标是回答「能不能像签到/砸蛋那样按纯接口编排直发请求」：把页面 webpack
     * 里真实的接口路径、明文参数、请求封装实例全部读出来，作为下一版重写的依据。
     *
     * 严格只读，分三层，全部不领任何东西：
     *  1. 纯静态遍历 window.webpackJsonp 数组里已存在的 chunk，读每个模块工厂的
     *     toString() 源码（零执行）；
     *  2. 用 webpackJsonp.push 塞一个「自写空探针模块」拿到 webpack 的 require，
     *     它的函数体只有一句把 require 赋给全局变量，不是业务模块。随后只读 r.m
     *     模块表源码、r.c 已执行模块缓存里的 exports（读已执行结果，不新执行模块）；
     *  3. 全程不调用任何组件方法、不点任何 DOM、不发任何网络请求。
     *
     * 诊断 dump 里已看到业务请求走 _utils_bigChoose__WEBPACK_IMPORTED_MODULE_11__
     * 的字母导出函数（["b"]/["f"]/["l"]...），参数明文形如
     * {actNo:..., reqTimestamp:..., taskKey:...}，本脚本据此筛关键字、抽路径与参数。
     * 结果按 4500 字符切成多条 GOOSE-PROBE 日志，绕开 8KB detail 上限。
     */
    private static final String PROBE_SCRIPT = """
            (function(){
              var L=[];
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function keys(o){ try{ return Object.keys(o) }catch(e){ return [] } }
              function srcOf(f){ try{ return String(f) }catch(e){ return '' } }
              line('=== 收鹅蛋 webpack 只读探测 v3.21 ===');
              line('说明: 只读 webpack chunk/模块表源码, 不执行任何业务模块、不调组件方法、不点 DOM、不发请求、不领任何东西');
              line('URL: '+cut(location.href,180));
              try{
                // ---- A. 找 webpack 全局 ----
                var wnames=[];
                try{
                  var wn=Object.getOwnPropertyNames(window);
                  for(var i=0;i<wn.length;i++){
                    if(/webpack|chunk/i.test(wn[i])) wnames.push(wn[i]);
                  }
                }catch(e){}
                line('');
                line('==== A. window 上 webpack/chunk 全局 ('+wnames.length+') ====');
                for(var i=0;i<wnames.length;i++){
                  var nm=wnames[i], v=null; try{ v=window[nm] }catch(e){}
                  var t=typeof v, extra='';
                  if(v&&typeof v==='object'&&typeof v.length==='number') extra=' len='+v.length;
                  line('  '+nm+' : '+t+extra);
                }
                var gj=null, gjName='';
                if(window.webpackJsonp){ gj=window.webpackJsonp; gjName='window.webpackJsonp' }
                if(!gj){
                  for(var i=0;i<wnames.length;i++){
                    var nm=wnames[i];
                    if(/^webpackChunk/.test(nm)){
                      var v=null; try{ v=window[nm] }catch(e){}
                      if(v&&typeof v.push==='function'){ gj=v; gjName='window.'+nm; break }
                    }
                  }
                }
                line('  选定全局: '+(gjName||'(无)'));

                // ---- B. 纯静态遍历已存在 chunk 的模块工厂源码（零执行）----
                var modSources=[];
                var MAXMOD=4000;
                if(gj&&typeof gj.length==='number'){
                  for(var ci=0;ci<gj.length;ci++){
                    var chunk=null; try{ chunk=gj[ci] }catch(e){}
                    if(!chunk||typeof chunk.length!=='number') continue;
                    for(var part=0;part<chunk.length;part++){
                      var partv=null; try{ partv=chunk[part] }catch(e){}
                      if(partv&&typeof partv==='object'&&!Array.isArray(partv)){
                        var ids=keys(partv);
                        for(var k=0;k<ids.length&&modSources.length<MAXMOD;k++){
                          var f=partv[ids[k]];
                          if(typeof f==='function'){
                            modSources.push({name:gjName+'['+ci+']['+part+']', id:ids[k], src:srcOf(f)});
                          }
                        }
                      }
                    }
                  }
                }
                line('');
                line('==== B. 静态读到的模块工厂数: '+modSources.length+'（零执行）====');

                // ---- C. 空探针拿 require（注入的是自写空模块，非业务模块）----
                var R=null, probeNote='';
                if(gj&&typeof gj.push==='function'){
                  try{
                    gj.push([['__cmcc_probe__'],{'__cmcc_probe__':function(m,e,r){ window.__cmccProbeReq=r }},[['__cmcc_probe__']]]);
                    R=window.__cmccProbeReq||null;
                    probeNote=R?'拿到 require':'push 完成但 require 未取到';
                  }catch(e){ probeNote='push 异常: '+((e&&e.message)||e) }
                } else probeNote='无可用 push';
                line('');
                line('==== C. require 探针（自写空模块，非业务模块）: '+probeNote+' ====');
                var rmCount=0, rcCount=0;
                if(R){
                  try{ rmCount=keys(R.m).length }catch(e){}
                  try{ rcCount=keys(R.c).length }catch(e){}
                  line('  r.m 模块表: '+rmCount+'   r.c 已执行缓存: '+rcCount);
                  if(modSources.length===0 && R.m){
                    var mids=keys(R.m);
                    for(var i=0;i<mids.length&&modSources.length<MAXMOD;i++){
                      modSources.push({name:'r.m', id:mids[i], src:srcOf(R.m[mids[i]])});
                    }
                    line('  改从 r.m 读到模块源码: '+modSources.length);
                  }
                }

                // ---- D. 关键字模块筛选 ----
                var KEY=/raiseGoose|sinoEnParams|dataEn|interceptors|axios|baseURL|huodong-portal|lnlyh|bigChoose|encrypt|AES|sinoSafety|LJRkIeoW/i;
                var hits=[];
                for(var i=0;i<modSources.length;i++){
                  if(KEY.test(modSources[i].src)) hits.push(modSources[i]);
                }
                line('');
                line('==== D. 命中关键字的模块 ('+hits.length+') ====');
                var allSrc=[];
                for(var i=0;i<hits.length && i<40;i++){
                  line('');
                  line('---- '+hits[i].name+' #'+hits[i].id+' (len '+hits[i].src.length+') ----');
                  line(cut(hits[i].src.replace(/\\s+/g,' '),1400));
                  allSrc.push(hits[i].src);
                }

                // D2 接口路径
                var paths=[], pmap={};
                var joined=allSrc.join('\\n');
                var re1=new RegExp('raiseGoose[/][A-Za-z0-9_]+','g'); var m1;
                while((m1=re1.exec(joined))!==null){ if(!pmap[m1[0]]){pmap[m1[0]]=1;paths.push(m1[0])} }
                var re2=new RegExp('(?:huodong-portal|zt-portal)[/][A-Za-z0-9_/-]{3,}','g'); var m2;
                while((m2=re2.exec(joined))!==null){ if(!pmap[m2[0]]){pmap[m2[0]]=1;paths.push(m2[0])} }
                line('');
                line('==== D2. 源码里出现的接口路径 ('+paths.length+'，去重) ====');
                for(var i=0;i<paths.length&&i<160;i++) line('  '+paths[i]);

                // D3 明文参数名
                var params=[], pamap={};
                var re3=new RegExp('[{,][ ]{0,3}(actNo|reqTimestamp|taskKey|noticeId|showToastType|taskId|prizeId|boxId|type|pageNum|channel|signType|awardId|recordId|rewardNumber|eggNum|feedNum|id)[ ]{0,3}:','g'); var m3;
                while((m3=re3.exec(joined))!==null){ if(!pamap[m3[1]]){pamap[m3[1]]=1;params.push(m3[1])} }
                line('');
                line('==== D3. 源码里出现的明文参数名 ('+params.length+') ====');
                line('  '+params.join(', '));

                // D4 含明文参数的对象片段
                var objs=[], ocount=0;
                var re4=new RegExp('[{][^{}]{0,220}(?:actNo|reqTimestamp|taskKey)[^{}]{0,220}[}]','g'); var m4;
                while((m4=re4.exec(joined))!==null && ocount<40){ objs.push(cut(m4[0].replace(/\\s+/g,' '),260)); ocount++ }
                line('');
                line('==== D4. 含明文参数的对象片段 ('+objs.length+') ====');
                for(var i=0;i<objs.length;i++) line('  · '+objs[i]);

                // ---- E. r.c 已执行缓存里的 axios 实例（零新执行）----
                line('');
                line('==== E. r.c 已执行模块里的请求实例（零新执行）====');
                var axCount=0;
                if(R&&R.c){
                  var cids=keys(R.c);
                  for(var i=0;i<cids.length;i++){
                    var ex=null; try{ ex=R.c[cids[i]]&&R.c[cids[i]].exports }catch(e){}
                    if(!ex||typeof ex!=='object') continue;
                    var cands=[ex, ex.default, ex.a, ex.Axios, ex.axios];
                    for(var q=0;q<cands.length;q++){
                      var cd=cands[q];
                      if(cd&&typeof cd==='object'&&typeof cd.request==='function'&&cd.interceptors&&cd.interceptors.request&&cd.interceptors.response){
                        var base=''; try{ base=(cd.defaults&&cd.defaults.baseURL)||'' }catch(e){}
                        line('  r.c['+cids[i]+'] -> axios 实例  baseURL='+cut(base,120));
                        axCount++;
                        break;
                      }
                    }
                    if(axCount>=12) break;
                  }
                }
                line('  找到 axios 实例: '+axCount+' 个');

                // ---- F. window 上可疑挂载 ----
                line('');
                line('==== F. window 上可疑挂载 ====');
                var wk=[];
                try{
                  var wo=Object.getOwnPropertyNames(window);
                  for(var w=0;w<wo.length&&wk.length<30;w++){
                    var kn=wo[w];
                    if(!/api|http|request|axios|goose|box|task|vm|app|encrypt/i.test(kn)) continue;
                    var kv=null; try{ kv=window[kn] }catch(e){}
                    var kt=typeof kv;
                    if(kt==='object'&&kv){ wk.push('  window.'+kn+' ('+kt+') keys: '+cut(keys(kv).join(', '),200)) }
                    else wk.push('  window.'+kn+' ('+kt+')');
                  }
                }catch(e){}
                for(var i=0;i<wk.length;i++) line(wk[i]);

                // ---- G. 判读要点 ----
                line('');
                line('==== G. 判读要点 ====');
                line('  webpack 全局: '+(gjName||'(无)'));
                line('  require 探针: '+probeNote);
                line('  静态模块数: '+modSources.length+'   命中关键字: '+hits.length+'   接口路径: '+paths.length+'   axios 实例: '+axCount);
                line('  (require + axios 实例 + 接口路径三者齐, 即可按纯接口编排直发)');
                line('');
                line('==== 探测结束 ====');
              }catch(err){
                line('');
                line('!!!! 探测中途异常（上方已收集内容全部保留）: '+((err&&err.stack)||err));
              }
              var text=L.join('\\n');
              var parts=[]; var CH=4500;
              for(var off=0;off<text.length;off+=CH) parts.push(text.slice(off,off+CH));
              if(!parts.length) parts.push('(空)');
              window.__cmcc_goose_probe=parts;
              return '1';
            })()
            """;

    /**
     * v3.22 直发自检脚本（自检通道打开时才注入）。
     *
     * 做三件事：
     *   A. 空探针拿 webpack 的 require（v3.21 已验证这条可行）；
     *   B. 在 r.c 已执行缓存里，找出所有源码含 raiseGoose/ 路径的导出函数——
     *      这些就是页面「业务接口层」e=>Object(o["c"])(路径, 明文参数)，
     *      全部读已执行结果，零新执行、零副作用；
     *   C. dump 完整「路径→函数」映射，然后从只读优先表里挑一个接口，
     *      用页面运行时真实参数（actNo / pageInitData.reqTimestamp）只调用一次，
     *      把返回结构、是否 dataEn 密文、是否有 code、WAF 放不放行如实写进日志。
     *
     * 只读接口不改任何数据，不领任何东西；调的是接口封装函数而不是组件方法，
     * 不带任何结算/领奖逻辑。脚本内部 await 一次网络请求，所以外层是同步 IIFE
     * 立刻返回 '1'，async 主体继续在页面事件循环里跑，结束时把结果切片写到
     * window.__cmcc_goose_selftest 并置完成标记。发请求前先写一次不带完成标记
     * 的部分结果，保证即使请求被 WAF 挂住也能拿到定位与映射信息。
     */
    private static final String SELFTEST_SCRIPT = """
            (function(){
              var L=[];
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function keys(o){
                try{
                  // webpack 的 i.d 可能用 defineProperty 定义不可枚举 getter，
                  // Object.keys 取不到，补 getOwnPropertyNames 才能覆盖导出键。
                  var a=Object.getOwnPropertyNames(o), b=Object.keys(o), seen={}, r=[];
                  for(var i=0;i<a.length;i++){ seen[a[i]]=1; r.push(a[i]) }
                  for(var i=0;i<b.length;i++){ if(!seen[b[i]]) r.push(b[i]) }
                  return r;
                }catch(e){ return [] }
              }
              function jstr(v){ try{ return JSON.stringify(v) }catch(e){ return '<不可序列化:'+e+'>' } }
              function extractPath(s){
                var k=s.indexOf('raiseGoose/');
                if(k<0) return '';
                var st=k;
                while(st>0 && s.charAt(st-1)!=='"' && s.charAt(st-1)!=="'") st--;
                if(st<=0) return '';
                var q=s.charAt(st-1);
                var en=s.indexOf(q,k);
                if(en<0) return '';
                return s.slice(st,en);
              }
              function withTimeout(p,ms){
                return new Promise(function(res,rej){
                  var fin=false;
                  var tm=setTimeout(function(){ if(fin)return; fin=true; rej(new Error('timeout '+ms+'ms')) },ms);
                  Promise.resolve(p).then(function(v){ if(fin)return; fin=true; clearTimeout(tm); res(v) },
                                          function(e){ if(fin)return; fin=true; clearTimeout(tm); rej(e) });
                });
              }
              function findTs(){
                try{
                  var root=document.querySelector('#app'); var vm=root&&root.__vue__;
                  var stack=[vm], seen=0;
                  while(stack.length && seen<300){
                    var c=stack.pop(); seen++;
                    if(!c) continue;
                    var pd=c.pageInitData;
                    if(pd && pd.reqTimestamp!==undefined && pd.reqTimestamp!==null) return String(pd.reqTimestamp);
                    var cs=c.$children||[]; for(var i=0;i<cs.length;i++) stack.push(cs[i]);
                  }
                }catch(e){}
                return '';
              }
              function writeParts(mark){
                var text=L.join('\\n');
                var parts=[]; var CH=4000;
                for(var off=0;off<text.length;off+=CH) parts.push(text.slice(off,off+CH));
                if(!parts.length) parts.push('(空)');
                window.__cmcc_goose_selftest=parts;
                if(mark) window.__cmcc_goose_selftest_done=mark;
              }
              try{ window.__cmcc_goose_selftest_done=''; window.__cmcc_goose_selftest=null }catch(e){}

              line('=== 收鹅蛋 直发自检 v3.22 ===');
              line('说明: 动态定位业务接口层 + dump 路径→函数映射 + 只调一次只读接口验证直发');
              line('约束: 调的是接口封装函数 e=>Object(o["c"])(path,e), 不是组件方法; 不改数据、不领任何东西; VUE_SCRIPT 未注入');
              line('URL: '+cut(location.href,200));

              (async function(){
                var done='ERR';
                try{
                  // ---- A. 空探针拿 require ----
                  var gj=window.webpackJsonp;
                  var R=null;
                  if(gj && typeof gj.push==='function'){
                    try{
                      gj.push([['__cmcc_selftest__'],{'__cmcc_selftest__':function(m,e,r){window.__cmccSelftestReq=r}},[['__cmcc_selftest__']]]);
                      R=window.__cmccSelftestReq||null;
                    }catch(e){ line('探针异常: '+((e&&e.message)||e)) }
                  }
                  line('');
                  line('==== A. require 探针: '+(R?('拿到  r.m='+keys(R.m||{}).length+'  r.c='+keys(R.c||{}).length):'未拿到')+' ====');
                  if(!R){ done='NO_REQUIRE'; return }

                  // ---- B. 在 r.c 已执行缓存里找业务接口层（零新执行）----
                  var rc=R.c||{}, cids=keys(rc), apiFns=[];
                  for(var ci=0;ci<cids.length;ci++){
                    var ex=null; try{ ex=rc[cids[ci]]&&rc[cids[ci]].exports }catch(e){}
                    if(!ex||typeof ex!=='object') continue;
                    var eks=keys(ex);
                    for(var ei=0;ei<eks.length;ei++){
                      var f=null; try{ f=ex[eks[ei]] }catch(e){}
                      if(typeof f!=='function') continue;
                      var s=''; try{ s=String(f) }catch(e){}
                      if(s.indexOf('raiseGoose/')<0) continue;
                      var p=extractPath(s);
                      if(!p) continue;
                      apiFns.push({mod:cids[ci],key:eks[ei],path:p,fn:f,src:cut(s,150)});
                    }
                  }
                  var mods={}; for(var i=0;i<apiFns.length;i++) mods[apiFns[i].mod]=1;
                  var byPath={};
                  for(var i=0;i<apiFns.length;i++){ if(!byPath[apiFns[i].path]) byPath[apiFns[i].path]=apiFns[i] }
                  var uniqPaths=keys(byPath); uniqPaths.sort();
                  line('');
                  line('==== B. 业务接口层定位（r.c 已执行缓存，零新执行）====');
                  line('  含 raiseGoose/ 路径的导出函数: '+apiFns.length+' 个，分布在 '+keys(mods).length+' 个模块');
                  line('  去重后的接口路径: '+uniqPaths.length+' 条');
                  if(apiFns.length===0){
                    // r.c 里没定位到，回退到 r.m 静态模块源码，把候选模块与导出键列出来。
                    // 只读源码、不执行任何模块，仅作线索，避免真机零信息。
                    line('  !!!! r.c 未定位到业务接口层，改从 r.m 静态源码找线索（零执行）:');
                    var rm=R.m||{}, rmids=keys(rm), cand=0;
                    for(var mi=0;mi<rmids.length;mi++){
                      var ms=''; try{ ms=String(rm[rmids[mi]]) }catch(e){}
                      if(ms.indexOf('raiseGoose/index')<0 || ms.indexOf('Object(')<0) continue;
                      var cpaths=[], mm; var pre=new RegExp('[/][A-Za-z0-9_/-]*raiseGoose[/][A-Za-z0-9_]+','g');
                      while((mm=pre.exec(ms))!==null){ if(cpaths.indexOf(mm[0])<0) cpaths.push(mm[0]) }
                      line('    r.m['+rmids[mi]+'] 含 '+cpaths.length+' 条 goose 路径, 源码长 '+ms.length);
                      for(var cj=0;cj<cpaths.length && cj<40;cj++) line('      '+cpaths[cj]);
                      cand++;
                      if(cand>=3) break;
                    }
                    if(cand===0) line('    r.m 里也没找到 raiseGoose/index 明文，可能路径被拆成变量拼接');
                  }

                  // ---- C. 完整 路径→函数 映射 ----
                  line('');
                  line('==== C. 路径 → 函数 完整映射 ====');
                  for(var i=0;i<uniqPaths.length;i++){
                    var a=byPath[uniqPaths[i]];
                    line('  '+uniqPaths[i]+'   <- r.c['+a.mod+'].'+a.key);
                    line('       fn: '+a.src);
                  }

                  // ---- D. 挑一个只读接口（精确按路径尾段匹配）----
                  var prefer=['index','getUnreadCount','userState','prizeList','boxQuery','getNoticeList','prizeLog','feedLog','eggLog','popupCheck','travelDiary'];
                  var chosen=null, chosenWhy='';
                  for(var pi=0;pi<prefer.length && !chosen;pi++){
                    for(var i=0;i<uniqPaths.length;i++){
                      var tail=uniqPaths[i].slice(uniqPaths[i].lastIndexOf('/')+1);
                      if(tail===prefer[pi]){ chosen=byPath[uniqPaths[i]]; chosenWhy=prefer[pi]; break }
                    }
                  }
                  line('');
                  line('==== D. 只读自检目标 ====');
                  if(!chosen){
                    line('  未能在只读优先表里定位到任何接口，不发请求');
                    line('  只读优先表: '+prefer.join(' / '));
                    done='NO_READONLY_FN';
                    return;
                  }
                  line('  选定: '+chosen.path+'   <- r.c['+chosen.mod+'].'+chosen.key+'  （尾段 '+chosenWhy+'）');

                  // ---- E. 页面运行时真实参数（不凭猜）----
                  var actNo='';
                  try{ var am=location.href.match(/[?&#]actNo=([^&#]+)/); if(am) actNo=decodeURIComponent(am[1]) }catch(e){}
                  var reqTs=findTs();
                  var params={actNo:actNo, reqTimestamp:reqTs, SHOW:false};
                  line('');
                  line('==== E. 实参（全部取自页面运行时）====');
                  line('  actNo        : '+actNo+'   （来源: URL query）');
                  line('  reqTimestamp : '+cut(reqTs,60)+'   （来源: Vue 组件 pageInitData.reqTimestamp）');
                  line('  SHOW         : false   （只关 loading 弹窗，不影响请求内容）');
                  line('  实参对象     : '+jstr(params));

                  // ---- F. 只发一次只读请求 ----
                  line('');
                  line('==== F. 直发结果（唯一一次真实请求）====');
                  writeParts('');   // 先把定位/映射落盘，不置完成标记
                  var t0=Date.now();
                  var verdict='OK';
                  try{
                    var res=await withTimeout(Promise.resolve(chosen.fn(params)),12000);
                    line('  耗时       : '+(Date.now()-t0)+'ms');
                    line('  返回 typeof: '+(res===null?'null':typeof res));
                    if(res && typeof res==='object'){
                      line('  返回顶层 keys: '+cut(keys(res).join(','),300));
                      var d=(res.data!==undefined)?res.data:res;
                      line('  业务层 typeof: '+(d===null?'null':typeof d));
                      if(d && typeof d==='object') line('  业务层 keys  : '+cut(keys(d).join(','),300));
                    }
                    var raw=jstr(res);
                    line('  返回原文(截断 1800): '+cut(raw,1800));
                    var cipher=/dataEn|sinoEnParams/i.test(raw);
                    var code='';
                    try{
                      if(res && res.code!==undefined) code=String(res.code);
                      else if(res && res.data && res.data.code!==undefined) code=String(res.data.code);
                    }catch(e){}
                    line('  识别到 code : '+(code||'(无)'));
                    line('  是否含密文字段(dataEn/sinoEnParams): '+cipher);
                    line('  WAF 判定    : 请求已返回（未抛异常/未超时）= 瑞数令牌 + 加密由页面封装自动带上了');
                    if(code) verdict='OK|明文 code='+code;
                    else if(cipher) verdict='OK|密文(解密仍在页面其它环节)';
                    else verdict='OK|结构待判读';
                  }catch(e){
                    line('  !!!! 直发异常: '+((e&&e.stack)||e));
                    line('  WAF 判定    : 抛异常或超时，可能被瑞数拦截/挂起，见上方');
                    verdict='EXC';
                  }

                  // ---- G. 判读要点 ----
                  line('');
                  line('==== G. 判读要点 ====');
                  line('  业务接口层函数: '+apiFns.length+' 个 / 去重路径 '+uniqPaths.length+' 条');
                  line('  直发目标      : '+chosen.path);
                  line('  结论标记      : '+verdict);
                  line('  判据          : 拿到明文业务对象(code/data) => 可按纯接口编排重写动作链');
                  line('                  仍是 dataEn 密文 => 解密还绑在页面其它环节，需要再定位');
                  line('  (明文参数、加密、瑞数令牌均由页面自己的请求封装完成，模块不拼不伪造)');
                  done=verdict;
                }catch(err){
                  line('');
                  line('!!!! 自检中途异常（上方已收集内容全部保留）: '+((err&&err.stack)||err));
                  done='EXC';
                }finally{
                  line('');
                  line('==== 自检结束 ====');
                  writeParts(done);
                }
              })();
              return '1';
            })()
            """;

    private static Attempt attempt(Object webView, String url) {
        Attempt attempt = new Attempt();
        latch = new CountDownLatch(1);
        resultText = "";
        resultDetail = "";
        resultOk = false;
        resultBlank = false;
        resultStale = false;

        String runId = String.valueOf(System.currentTimeMillis());
        if (!JsInjector.evaluateRaw(webView, buildScript(runId))) {
            attempt.result = TaskRunner.Result.fail("注入失败",
                    "evaluateJavascript 调用失败，可能是 WebView 已被回收。",
                    "重新打开一次「收鹅蛋换好礼」页面再试。");
            attempt.detail = "· " + brief(url) + " 脚本注入失败\n";
            return attempt;
        }

        boolean done;
        try {
            done = latch.await(SCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        attempt.blank = resultBlank;
        attempt.stale = resultStale;
        attempt.detail = "· " + brief(url) + "\n"
                + indent(resultDetail.isEmpty() ? "（无回报）" : resultDetail);
        if (!done) {
            attempt.result = TaskRunner.Result.fail("收鹅蛋超时",
                    "脚本已注入，但 40 秒内没有收到页面回报。\n页面: " + url,
                    "页面可能被切到后台了。保持活动页在前台再点一次。");
            return attempt;
        }
        if (resultOk) {
            attempt.result = TaskRunner.Result.ok(resultText, resultDetail, resultText);
        } else if (!resultBlank) {
            // 页面正常渲染、只是没找到可点的按钮：多半是今天已经做完了。
            // 这属于「无需操作」，不该记成失败 —— 记成失败会触发当天重试上限，
            // 把本来正常的活动锁死一整天。
            attempt.result = TaskRunner.Result.idle(
                    resultText == null || resultText.isEmpty() ? "收鹅蛋 · 今天没有待做的动作" : resultText,
                    resultDetail, "· 页面已打开，没有可执行的动作（今天可能已完成）");
        } else {
            attempt.result = TaskRunner.Result.fail(resultText, resultDetail,
                    "页面白屏，多半是登录参数失效。在 App 里重新打开一次活动页即可。");
        }
        return attempt;
    }

    /** JsBridge 回调。 */
    static void onJsResult(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            resultOk = json.optBoolean("ok", false);
            resultBlank = json.optBoolean("blank", false);
            resultStale = json.optBoolean("stale", false);
            resultText = json.optString("text", "");
            StringBuilder detail = new StringBuilder();
            detail.append("页面: ").append(json.optString("page", attachedUrl)).append('\n');
            JSONArray log = json.optJSONArray("log");
            if (log != null) {
                detail.append("--- 步骤 ---\n");
                for (int i = 0; i < log.length(); i++) {
                    detail.append("· ").append(log.optString(i)).append('\n');
                }
            }
            resultDetail = detail.toString();
        } catch (Throwable e) {
            resultOk = false;
            resultBlank = false;
            resultText = "结果解析失败：" + e;
            resultDetail = payload;
        }
        CountDownLatch waiting = latch;
        if (waiting != null) {
            waiting.countDown();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 等页面真正切过去再注入。
     *
     * 固定 sleep 有个坑：WebView 还在加载旧文档时注入，脚本跑在旧页面上，
     * 白等 15 秒才发现「页面白屏」。这里盯 attachedAt，页面一换过来就继续。
     */
    private static void waitAttached(long mark) {
        long end = mark + LOAD_WAIT_MS * 3;
        while (System.currentTimeMillis() < end && attachedAt < mark) {
            sleep(300L);
        }
    }

    private static String brief(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.indexOf("pc=");
        String head = cut > 0 ? url.substring(0, cut) + "pc=…" : url;
        return head.length() <= 90 ? head : head.substring(0, 90) + "…";
    }

    private static String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return "  " + text.replace("\n", "\n  ") + "\n";
    }

    private static final class Attempt {
        TaskRunner.Result result;
        String detail = "";
        boolean blank;
        boolean stale;
    }

    /**
     * 注入脚本：先等页面真正渲染出来，再按语义找按钮串行点。
     *
     * 只碰四类动作，都是「点了只会多不会少」的：打卡 / 领饲料 / 投喂 / 收蛋。
     * 兑换、分享、跳转一律不点。
     *
     * 页面 15 秒还没文字就回报 blank，宿主换个地址重来 —— 白屏基本等于 pc/pk 失效，
     * 死等下去没有意义。
     */
    private static String buildScript(String runId) {
        return "(function(){"
                + "var RUN='" + runId + "';"
                + "if(window['__cmcc_goose_'+RUN]){return;}"
                + "window['__cmcc_goose_'+RUN]=true;"
                + "var B='" + JsInjector.BRIDGE_NAME + "';"
                + "var log=[];var hitCount=0;var sent=false;"
                + "function send(ok,text,blank,stale){"
                + "  if(sent){return;}sent=true;"
                + "  try{var br=window[B];"
                + "    if(br&&typeof br.goose==='function'){"
                + "      br.goose(JSON.stringify({ok:ok,text:text,blank:!!blank,stale:!!stale,"
                + "log:log,page:location.href}));"
                + "    }}catch(e){}"
                + "}"
                + "function txt(el){return (el.innerText||el.textContent||'').replace(/\\s+/g,'').trim();}"
                + "function shown(el){"
                + "  try{var s=getComputedStyle(el);"
                + "    if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0'){return false;}"
                + "    var r=el.getBoundingClientRect();"
                + "    return r.width>2&&r.height>2;}catch(e){return false;}"
                + "}"
                + "function visibleText(el){"
                + "  var t=txt(el);"
                + "  if(!t){return '';}"
                + "  var kids=el.children||[];"
                + "  for(var i=0;i<kids.length;i++){"
                + "    if(shown(kids[i])){"
                + "      var kt=txt(kids[i]);"
                + "      if(kt===t){return '';}"
                + "    }"
                + "  }"
                + "  return t;"
                + "}"
                + "var BAD=['兑换','分享','邀请','充值','购买','开通','订购','退订','支付','确认','取消','关闭','规则','明细','记录','去完成','查看','更多','立即兑换','去逛逛'];"
                + "function bad(t){for(var i=0;i<BAD.length;i++){if(t.indexOf(BAD[i])>=0){return true;}}return false;}"
                // 页面可能把内容放在同源 iframe 里，两个文档都查一遍。
                + "function docs(){"
                + "  var out=[document];"
                + "  var fs=document.querySelectorAll('iframe');"
                + "  for(var i=0;i<fs.length&&i<5;i++){"
                + "    try{var d=fs[i].contentDocument;if(d&&d.body){out.push(d);}}catch(e){}"
                + "  }"
                + "  return out;"
                + "}"
                + "function findAll(sel){"
                + "  var ds=docs(),out=[];"
                + "  for(var i=0;i<ds.length;i++){"
                + "    try{var n=ds[i].querySelectorAll(sel);"
                + "      for(var j=0;j<n.length;j++){out.push(n[j]);}}catch(e){}"
                + "  }"
                + "  return out;"
                + "}"
                + "function find(words,maxLen){"
                + "  var all=findAll('button,a,div,span,p,li,i,view,text,"
                + "[class*=btn],[class*=button],[role=button]');"
                + "  var best=null,bestLen=999;"
                + "  for(var i=0;i<all.length;i++){"
                + "    var el=all[i];"
                + "    if(!shown(el)||el.__cmccHit){continue;}"
                + "    var t=visibleText(el);"
                + "    if(!t||t.length>(maxLen||8)||bad(t)){continue;}"
                + "    for(var j=0;j<words.length;j++){"
                + "      if(t===words[j]&&t.length<bestLen){best=el;bestLen=t.length;}"
                + "    }"
                + "  }"
                + "  if(best){return best;}"
                + "  for(var k=0;k<all.length;k++){"
                + "    var e2=all[k];"
                + "    if(!shown(e2)||e2.__cmccHit){continue;}"
                + "    var t2=visibleText(e2);"
                + "    if(!t2||t2.length>(maxLen||8)||bad(t2)){continue;}"
                + "    for(var m=0;m<words.length;m++){"
                + "      if(t2.indexOf(words[m])>=0&&t2.length<bestLen){best=e2;bestLen=t2.length;}"
                + "    }"
                + "  }"
                + "  return best;"
                + "}"
                + "function hit(el){"
                + "  if(!el){return false;}"
                + "  el.__cmccHit=1;"
                + "  try{el.scrollIntoView({block:'center'});}catch(e){}"
                + "  try{var r=el.getBoundingClientRect();"
                + "    var x=r.left+r.width/2,y=r.top+r.height/2;"
                + "    ['touchstart','touchend','mousedown','mouseup','click'].forEach(function(t){"
                + "      var ev;"
                + "      try{"
                + "        if(t.indexOf('touch')===0){ev=new TouchEvent(t,{bubbles:true,cancelable:true});}"
                + "        else{ev=new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y});}"
                + "        el.dispatchEvent(ev);"
                + "      }catch(e){}"
                + "    });"
                + "    if(typeof el.click==='function'){el.click();}"
                + "    return true;"
                + "  }catch(e){return false;}"
                + "}"
                + "function dump(){"
                + "  var all=findAll('button,a,div,span,p,li,i,view,text,img,[class*=btn],[class*=button]');"
                + "  var out=[];"
                + "  for(var i=0;i<all.length&&out.length<60;i++){"
                + "    var el=all[i];"
                + "    if(!shown(el)){continue;}"
                + "    var t=txt(el);"
                + "    if(!t||t.length>20){continue;}"
                + "    if(out.indexOf(t)>=0){continue;}"
                + "    out.push(t);"
                + "  }"
                + "  return out.join('/');"
                + "}"
                + "function pageText(){"
                + "  var ds=docs(),out='';"
                + "  for(var i=0;i<ds.length;i++){"
                + "    try{out+=(ds[i].body&&ds[i].body.innerText||'');}catch(e){}"
                + "  }"
                + "  return out.replace(/\\s+/g,'');"
                + "}"
                + "function diag(){"
                + "  var t=pageText();"
                + "  log.push('链接: '+location.href.slice(0,120));"
                + "  log.push('标题: '+(document.title||'（空）'));"
                + "  log.push('文本: '+t.length+' 字');"
                + "  log.push('节点: '+document.querySelectorAll('*').length);"
                + "  log.push('iframe: '+document.querySelectorAll('iframe').length);"
                + "  log.push('脚本: '+document.scripts.length);"
                + "  if(t){log.push('开头: '+t.slice(0,60));}"
                + "}"
                + "function finish(){"
                + "  var ok=hitCount>0;"
                + "  var text=ok?('页面内已执行 '+hitCount+' 个动作'):'页面上没有可执行的按钮';"
                + "  log.push('结束: 共点击 '+hitCount+' 次');"
                + "  try{log.push('页面文本: '+dump());}catch(e){}"
                + "  var all=pageText();"
                // 以前这里带了「已过期」「已失效」，本意是判断活动过期，
                // 结果命中的是奖品列表里的「已赠送 已过期 已使用」标签 ——
                // 页面明明正常，却被判成「活动失效」，白换了一次地址。
                // 现在只认明确指向活动本身的说法。
                + "  var stale=/活动已结束|活动结束|活动已下线|活动已下架|活动不存在|活动已失效|请重新进入活动/.test(all);"
                + "  if(stale&&!ok){log.push('判定: 页面提示活动失效，需要换地址');}"
                + "  send(ok,text,false,stale);"
                + "}"
                + "var steps=["
                + "  {name:'打卡',words:['打卡','立即打卡','签到','立即签到'],max:10},"
                + "  {name:'领饲料',words:['领取','领饲料','收下','立即领取'],max:10},"
                + "  {name:'投喂',words:['投喂','喂养','立即投喂'],max:10},"
                + "  {name:'收蛋',words:['收蛋','收取','收鹅蛋','一键收取','收获'],max:10}"
                + "];"
                + "var i=0;"
                + "function phase1(){"
                + "  if(i>=steps.length){phase2();return;}"
                + "  var s=steps[i++];"
                + "  var el=find(s.words,s.max);"
                + "  if(el&&hit(el)){hitCount++;log.push(s.name+': 已点击 ('+visibleText(el).slice(0,10)+')');}"
                + "  else{log.push(s.name+': 未找到按钮');}"
                + "  setTimeout(phase1,1200);"
                + "}"
                + "var extra=0;"
                + "var CLAIM=['领取','领奖励','领取奖励','去领取','领饲料','收下','一键领取','全部领取','收蛋','收取','收获'];"
                + "function phase2(){"
                + "  if(extra>=8){finish();return;}"
                + "  var el=find(CLAIM,8);"
                + "  if(!el){finish();return;}"
                + "  var label=visibleText(el).slice(0,10);"
                + "  if(hit(el)){hitCount++;extra++;log.push('补充领取: '+label);}"
                + "  setTimeout(phase2,1300);"
                + "}"
                + "var waited=0;"
                + "function waitReady(){"
                + "  var t='';"
                + "  try{t=pageText();}catch(e){}"
                + "  if(t.length>=8){"
                + "    log.push('页面已渲染: '+t.length+' 字');"
                + "    try{window.scrollTo(0,document.body.scrollHeight);}catch(e){}"
                + "    setTimeout(function(){"
                + "      try{window.scrollTo(0,0);}catch(e){}"
                + "      phase1();"
                + "    },900);"
                + "    return;"
                + "  }"
                + "  waited+=500;"
                + "  if(waited>=15000){"
                + "    log.push('页面 15 秒内没有渲染出任何文字');"
                + "    try{diag();}catch(e){}"
                + "    send(false,'页面白屏（缺少登录参数 pc/pk）',true,false);"
                + "    return;"
                + "  }"
                + "  setTimeout(waitReady,500);"
                + "}"
                + "setTimeout(waitReady,300);"
                + "})();";
    }
}
