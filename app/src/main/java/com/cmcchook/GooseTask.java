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
    /**
     * 缓存的入口地址能用多久。pc/pk 由服务端按登录态签发，11-11 实测当天上午的
     * 地址下午就已失效（页面停在「已有鹅蛋：--个」），24 小时的 TTL 太乐观，改成 3 小时。
     */
    private static final long ENTRY_TTL = 3 * 3600_000L;

    /**
     * 卡片短链：福利专区 →「收鹅蛋换好礼」跳出来的真实链路。
     * 11-10 22:28、11-11 08:24 / 10:37 / 11:51 / 12:02 五次抓包里都是这一条，
     * 真页打开后自己会跳 {@code lyhVue/index.html#/bgIndex?actNo=goose2025&pc=…&pk=…}，
     * 落地即活动页。pc/pk 必须由它现场换，模块拼不出来。
     */
    // r40：短链常量统一收进 SsoShortLink 登记表，鹅蛋 / 球赛 / wisdom 同源管理。
    private static final String SHORT_LINK = SsoShortLink.GOOSE;

    /** 一轮最多点几个浏览任务。每点一个要等跳转 + 回跳 + 页面重载，约 20 秒。 */
    private static final int TASK_TRIES = 8;

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

    /**
     * 优先用缓存里带 pc/pk 的真实地址；缓存空了或过期就走卡片短链现场换票。
     *
     * <p>11-11 11:49 日志：模块直接用裸地址 {@code #/bgIndex?actNo=goose2025} 打开，
     * 页面只发了 enctyption/u、nav3004、rrlogin 三个请求就停住，首屏一直是
     * 「已有鹅蛋：--个 加载中...」；两分钟后用户手动从福利卡片进同一次活动，
     * 地址带 {@code pc=eJmGqg…&pk=0E9DB5…}，页面立刻把 getNoticeList / index /
     * taskIndex 等十个接口发齐。差别只在 pc/pk，所以这里必须走短链换票。
     */
    static String entryUrl() {
        // 不再优先用缓存：11-11 12:02 手动进页面拿到的 pc/pk 地址，12:27 再用时
        // 页面已经把用户识别成游客（nav3004/0/…、rrlogin/0/…，Cookie 里
        // cmccssotoken 为空），raiseGoose/index 连发 12 次全是密文。
        // pc/pk 是短时效的一次性票据，缓存复用只会把每一轮都浪费在失效地址上。
        // 现在统一走卡片短链，让门户每次重新签发。
        return SHORT_LINK;
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

    /** 缓存里 3 小时内、带 pc/pk 的入口；没有就返回空串。只作备用入口。 */
    private static String cachedEntry() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return "";
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (url.isEmpty() || !url.contains("pc=") || !url.contains("pk=")) {
                return "";
            }
            if (System.currentTimeMillis() - at > 3 * 3600_000L) {
                return "";
            }
            return url;
        } catch (Throwable e) {
            return "";
        }
    }

    // --------------------------------------------------------------- 执行

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();
        // 宿主进程里真正跑的 dex 版本：日志出现这条才说明 LSPosed 已重载，
        // 日志头「模块版本」只是 App 侧 BuildConfig，证明不了注入的 dex 是新的。
        HookLog.log("GOOSE", "收鹅蛋脚本 " + FlowTask.BUILD + " 已装载 · 短链中转页放行 + 逐任务点(按提交回执验收) + 任务后投喂收蛋",
                "判定尺子: 只认 doTask/signIn/feedGoose/harvestEgg/claimTravelReward 的真实提交回执；\n"
                        + "零提交一律不算完成、不记失败次数；业务数据未就绪时不空跑动作链。\n"
                        + "r40 修出游奖励领取: ①只点 .travelRewardDiv .claimBtn「立即领取」，"
                        + "不再误点 cardSub2「别忘了领取哦~」/rewardBox「x2」/IMG:rewardFeed；"
                        + "②fireEl 补 touches/changedTouches，页面 Vue 不再抛 reading '0'；"
                        + "③去掉点满 2 个就 return；④摘要只认 claimTravelReward 回执，不再用点击次数冒充结果。");
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
        long overall = System.currentTimeMillis() + 25_000L;
        if (!pageAlive(page, detail, "GOOSE")) {
            // r15 P1：坏页不在原地空转。11-12 19:12 那轮页面落在空白 WebView，
            // 一条 lyh 业务请求都没有，脚本却把 74 秒登录重试链全跑了一遍。
            // 关掉会话、按入口重开一次真页（只是重开活动页，不是自动登录）。
            HookLog.log("GOOSE", "真页空白 · 重建一次",
                    "存活检查没过，关掉当前 WebView 会话，按入口重开一次真页。\n"
                            + "总预算 25 秒，重建与登录重试共用，不再叠加。");
            String key = WebSession.key();
            WebSession.end();
            WebSession.begin(key == null || key.isEmpty() ? "goose" : key,
                    entryUrl(), budget(overall, 12_000L));
            WebPage fresh = WebSession.current();
            // r16：重建后 eval 仍可能暂时不通（空白文档 / JS 还没注入），
            // 但页面其实正在从短链跳到活动页。再给一次「只看 URL 落地」的机会，
            // 不等 JS 现场，避免又误杀一轮。
            if (fresh == null || (!pageAlive(fresh, detail, "GOOSE")
                    && !waitLanding(fresh, budget(overall, 8_000L)))) {
                return TaskRunner.Result.skip("收鹅蛋：真页没起来（空白 / 被跳转顶掉）",
                        detail.toString(),
                        "证据链见「页面存活检查」日志。请在 App 里手动点一次「收鹅蛋换好礼」，"
                                + "让官方容器把活动页加载出来；模块会记住带 pc/pk 的入口并复用真页。");
            }
            page = fresh;
        }
        if (!ensureLogin(page, detail, overall)) {
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
                            ? "有 pc/pk 但页面没就绪" : "没拿到带 pc/pk 的落地页"),
                    "缓存入口: " + brief(entry) + "\n"
                            + "页面现场: " + scene + "\n"
                            + "过程:\n" + detail);
            // r15 P1：不再把「页面没起来」说成「还没有登录参数」。19:12 日志里
            // URL 明明带 pc，页面却是空白 WebView、零业务请求，旧文案误导排查。
            String why = (entry == null || !entry.contains("pc="))
                    ? "真页没起来（空白页，或没走到带 pc/pk 的落地页）"
                    : "页面已打开但没发出业务请求（可能被外部跳转顶掉）";
            if (entry != null && entry.contains("pc=")) {
                // pc/pk 过期时作废会话缓存，下一轮真页才会重走门户 SSO。
                SsoLogin.invalidate();
            }
            return TaskRunner.Result.skip("收鹅蛋：" + why,
                    detail.toString(),
                    "证据链见「页面存活检查」和「收鹅蛋登录诊断」两条日志。"
                            + "在 App 里手动点一次「收鹅蛋换好礼」，让官方容器把活动页加载出来；"
                            + "模块会记住带 pc/pk 的入口并复用真页，之后就能自动跑。");
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
    /**
     * r15 P1：页面存活硬检查 + 证据链日志。
     *
     * <p>11-12 19:12 一键那轮，页面落在 {@code com.tencent.smtt.sdk.WebView$a}，
     * readyState/title/正文/业务请求全空；19:29 手动点过界面后落在
     * {@code WebViewEx}，同一条短链 190 条 raiseGoose 业务请求、一次跑通。
     * 说明失败不是 pc/pk、也不是脚本，是 WebView 实例不对。旧代码在坏页里
     * 把整套登录重试链跑满 74 秒，这里先把它挡掉。
     */
    static boolean pageAlive(WebPage page, StringBuilder detail, String tag) {
        // r16：短链 / SSO 中转页天生没有正文、没有 Vue、没有业务请求，
        // 旧版拿「活动页存活」的判据去卡它，必然判空白 → 重建一次 → 还是空白
        // → 直接 skip。20:12 那轮就是在这一步被挡掉的，压根没进登录重试链。
        // 中转页属于正常跳转链，放行给 ensureLogin / waitLogged 去等它落地。
        String hop = page.url();
        if (isLoginHop(hop)) {
            HookLog.log(tag, "页面存活检查",
                    "当前在登录/短链中转页，按跳转链放行（不按活动页判据杀）\n"
                            + "URL: " + briefUrl(hop));
            if (detail != null) {
                detail.append("页面存活: 中转页放行 · URL=").append(briefUrl(hop)).append('\n');
            }
            return true;
        }
        String cls = "";
        try {
            Object raw = page.raw();
            if (raw != null) {
                cls = raw.getClass().getName();
            }
        } catch (Throwable ignored) {
        }
        String v = null;
        try {
            v = page.eval("(function(){try{"
                    + "var t=document.body?document.body.innerText:'';"
                    + "var biz=0;try{var r=performance.getEntriesByType('resource')||[];"
                    + "for(var i=0;i<r.length;i++){if(/lyh\\.lncmcc\\.com|huodong-portal|\\/lnlyh\\//i.test(r[i].name||''))biz++}}catch(e){}"
                    + "var app=document.querySelector('#app')||document.querySelector('#root');"
                    + "var vue=!!(app&&(app.__vue__||app.__vue_app__));"
                    + "return JSON.stringify({ready:document.readyState,title:document.title||'',"
                    + "len:t.length,html:(document.documentElement?document.documentElement.outerHTML.length:0),"
                    + "vue:vue,biz:biz,url:location.href.slice(0,180)})"
                    + "}catch(e){return ''}})()", 3_000L);
        } catch (Throwable e) {
            v = "eval异常:" + e;
        }
        HookLog.log(tag, "页面存活检查",
                "WebView: " + cls + "\n现场: " + (v == null ? "eval 返回 null" : v)
                        + "\n判据: readyState=complete 且 (正文>0 或 html>2000)"
                        + " 且 (标题非空 或 Vue 或 业务请求>0)");
        if (detail != null) {
            detail.append("页面存活: WebView=").append(cls).append(" · 现场=")
                    .append(v == null ? "null" : v).append('\n');
        }
        if (v == null || v.isEmpty()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(v);
            boolean ready = "complete".equals(o.optString("ready"));
            int len = o.optInt("len", 0);
            int html = o.optInt("html", 0);
            int biz = o.optInt("biz", 0);
            boolean hasTitle = !o.optString("title").trim().isEmpty();
            boolean vue = o.optBoolean("vue", false);
            return ready && (len > 0 || html > 2000) && (hasTitle || vue || biz > 0);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 25 秒总预算里还剩多少，取 min(单步上限, 剩余)，用完返回 0 立即收手。 */
    static long budget(long deadline, long cap) {
        long left = deadline - System.currentTimeMillis();
        return Math.max(0L, Math.min(cap, left));
    }

    /**
     * r16：只看 URL 判断短链有没有落到活动页，不依赖 eval。
     *
     * <p>短链中转页的 JS 上下文常常还没建立，{@code pageAlive()} 的 eval 会返回
     * null；但 WebView 的 getUrl()（{@code WebPage.href()} 已在 r16 补了这条兜底）
     * 能照常反映跳转。这里轮询地址，看到 lyhVue / bgIndex / actNo=goose 就认为
     * 链路活着，交给 ensureLogin 继续，不再重建、不再 skip。
     */
    private static boolean waitLanding(WebPage page, long timeoutMs) {
        if (page == null) {
            return false;
        }
        long end = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < end) {
            String u = page.url();
            if (u != null && (u.contains("actNo=goose") || u.contains("bgIndex")
                    || u.contains("lyhVue/index.html#/"))) {
                HookLog.log("GOOSE", "中转页已落地", "URL: " + briefUrl(u)
                        + "\n按 URL 判定链路存活，继续登录重试。");
                return true;
            }
            sleep(500L);
        }
        return false;
    }

    private static boolean ensureLogin(WebPage page, StringBuilder detail, long deadline) {
        // 页面刚加载完时 indexFun 还没回来，pageInitData 是空的。
        // 裸地址进页面时还要走一遍门户 SSO（先跳 sso_token，再跳回带 pc/pk 的主页），
        // 这一段实测要 10 秒上下，只等 8 秒会把「正在登录」误判成「未登录」。
        if (waitLogged(page, budget(deadline, 16_000L))) {
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
                if (page.navigate(entry, budget(deadline, 18_000L)) && waitLogged(page, budget(deadline, 12_000L))) {
                    detail.append("登录: 拉回活动页后已登录\n");
                    return true;
                }
                detail.append("登录: 换地址后仍未登录\n");
                if (isGoosePage(page.url())) {
                    forgetEntry();
                }
            }
        } else {
            detail.append("登录: 没有可用的带 pc/pk 缓存，直接走短链换票\n");
        }

        // 第二级：卡片短链现场换票。pc/pk 是门户后端按登录态签发的密文，模块拼不出来，
        // 只能让 ssoJump 走一遍 uniTokenValidate 再跳到 lyhVue。落地地址就是带票的活动页。
        String cur = page.url();
        if (cur == null || !SsoShortLink.isShortLink(cur)) {
            detail.append("登录: 用卡片短链换票（" + SsoShortLink.describe(SHORT_LINK) + "）\n");
            if (page.navigate(SHORT_LINK, budget(deadline, 25_000L)) && waitLogged(page, budget(deadline, 20_000L))) {
                String landed = page.url();
                if (landed != null && landed.contains("pc=")) {
                    rememberEntry(landed);
                    detail.append("登录: 短链换票成功，已记住带 pc/pk 的地址\n");
                } else {
                    detail.append("登录: 短链换票成功\n");
                }
                return true;
            }
            detail.append("登录: 短链换票后仍未登录\n");
        }

        // r14 备用入口 1：缓存里带 pc/pk 的地址（只当备胎，不当首选）。
        // 短链换票是首选，因为 pc/pk 是门户按登录态现签的；但短链偶尔会被
        // dwz 限流/换码，这时 3 小时内的缓存地址往往还能用一次，比直接判失败强。
        String cached = cachedEntry();
        String here = page.url();
        if (!cached.isEmpty() && !cached.equals(here)) {
            detail.append("登录: 备用入口 · 缓存 pc/pk\n");
            if (page.navigate(cached, budget(deadline, 15_000L)) && waitLogged(page, budget(deadline, 12_000L))) {
                detail.append("登录: 备用入口登录成功\n");
                return true;
            }
            detail.append("登录: 备用入口仍未登录\n");
        }

        // r14 备用入口 2：裸地址。页面自己会走一遍门户 SSO（sso_token → 带 pc/pk 回来），
        // 短链和缓存都不行时这条路仍有一半机会，且不消耗活动次数。
        if (!isGoosePage(page.url())) {
            detail.append("登录: 备用入口 · 裸地址走门户 SSO\n");
            if (page.navigate(TaskApi.ACT_GOOSE, budget(deadline, 18_000L)) && waitLogged(page, budget(deadline, 14_000L))) {
                String landed = page.url();
                if (landed != null && landed.contains("pc=")) {
                    rememberEntry(landed);
                }
                detail.append("登录: 裸地址登录成功\n");
                return true;
            }
            detail.append("登录: 裸地址仍未登录\n");
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
                    if (page.navigate(base, budget(deadline, 15_000L)) && waitLogged(page, budget(deadline, 10_000L))) {
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
        // r14：8 秒无进展就判失败，不再干等到 16~20 秒。
        // 11-12 实测：页面被判未登录时，多数是短链换票没成，页面停在骨架/中转页
        // 一动不动；这时候多等 10 秒只是把一键总时长拉长。只要 URL、文档状态、
        // 正文长度这三样在 8 秒内一次都没变，就认为这条入口废了，直接交给
        // 备用入口（缓存 pc/pk → 裸地址 → accesstoken）去试。
        String sig = "";
        long lastMove = System.currentTimeMillis();
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
                    lastMove = System.currentTimeMillis();
                    sleep(700L);
                    continue;
                }
                return false;
            }
            String s = progressSig(page);
            long at = System.currentTimeMillis();
            if (!s.equals(sig)) {
                sig = s;
                lastMove = at;
            } else if (at - lastMove > 8_000L) {
                HookLog.logcat("goose: 8s 无进展，判未登录 · " + briefUrl(now));
                return false;
            }
            sleep(700L);
        }
        return false;
    }

    /** 轻量的「页面有没有在动」信号：路由 + 文档状态 + 正文长度。 */
    private static String progressSig(WebPage page) {
        try {
            String v = page.eval("(function(){try{"
                    + "return location.href.slice(0,120)+'|'+document.readyState+'|'"
                    + "+(document.body?document.body.innerText.length:0)"
                    + "}catch(e){return ''}})()", 1_500L);
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 门户 SSO 登录中转页：URL 不含 actNo=goose，但确实是登录链路的一环。 */
    private static boolean isLoginHop(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        // r16：短链换票的中转页在 rr.ln139.cn（辽宁移动门户），既不是 10086.cn
        // 也不是 lncmcc.com。旧版把它挡在门外，waitLogged 遇到 ssoJump 立刻
        // return false，短链换票永远走不完；20:37 手动日志实证同一个
        // dwzCode=0AnebE 约 1 秒就跳到 lyhVue，说明短链本身没过期。
        // 只认中国移动自己的域，别把外部广告域名误当登录页。
        if (!u.contains("10086.cn") && !u.contains("lncmcc.com")
                && !u.contains("ln139.cn")) {
            return false;
        }
        return u.contains("sso") || u.contains("login") || u.contains("token")
                || u.contains("passport") || u.contains("oauth") || u.contains("auth")
                || u.contains("accesstoken") || u.contains("dwzcode")
                || u.contains("ssojump") || u.contains("unitokenvalidate");
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
                + "if(/用户未登录|请先登录|登录失效/.test(t))return '0';"
                // 11-11 11:49 实测：页面骨架渲染完后文案是「已有鹅蛋：--个 加载中...」，
                // 旧判据拿「攒饲料 / 投喂 / 已赠送」当已登录，骨架也全中，于是 ensureLogin
                // 直接放行、动作链空跑。现在只认「已有鹅蛋：数字」这种真数据。
                + "if(/已有鹅蛋[:：]?\\s*\\d+/.test(t))return '1';"
                + "var biz=0;try{var rs=performance.getEntriesByType('resource')||[];"
                + "for(var j=0;j<rs.length;j++){if(/lyh\\.lncmcc\\.com|huodong-portal|\\/lnlyh\\//i.test(rs[j].name||''))biz++}}catch(e){}"
                + "if(biz>0&&t.length>100&&!/加载中|loading/i.test(t))return '1';"
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
    /**
     * r27：把本轮真实网络回执写进明细，判定尺子的证据来源。
     *
     * <p>13:42 号B日志实证四套数字打架：摘要 8 条 / 复核 1 条 / 报 5 个成功 /
     * 真实 doTask 3 条。根因是复核读的是 VUE_SCRIPT 里的 NET 数组（页面重载即
     * 清空），摘要读的是 localStorage 总数，两者不同源。现在统一读 __cmccGS.by
     * 分组（跨重载持久），并把每类条数直接列出来，谁都能对账。
     */
    private static void appendReceipt(StringBuilder detail, JSONObject out, WebPage page) {
        int netN = out.optInt("netN", 0);
        JSONObject by = submitGroups(page);
        int total = 0;
        java.util.Iterator<String> keys = by.keys();
        while (keys.hasNext()) {
            total += by.optInt(keys.next(), 0);
        }
        String breakdown = groupText(by);
        detail.append("回执复核(r27): 鹅蛋接口 ").append(netN)
                .append(" 条 · 提交类 ").append(total).append(" 条")
                .append(" · 业务数据 ").append(out.optBoolean("dataReady", false) ? "就绪" : "未就绪")
                .append('\n');
        detail.append("  提交类分组: ").append(breakdown.isEmpty() ? "无" : breakdown).append('\n');
        String querys = joinPaths(out.optJSONArray("queryPaths"));
        detail.append("  查询类: ").append(querys.isEmpty() ? "无" : querys).append('\n');
        // v3.97：出游归来奖励单独验收。r27 改认 localStorage 分组 ——
        // 13:42 号B实际发出 2 条 claimTravelReward 200，旧版却因 NET 数组
        // 重载清空判「未确认」，是假否定。
        int travelItems = out.optInt("travelItems", 0);
        int travelClaims = by.optInt("claimTravelReward", 0);
        if (travelItems > 0 || travelClaims > 0) {
            detail.append("  出游归来领取: ").append(travelClaims > 0
                            ? "已确认（claimTravelReward " + travelClaims + " 条）"
                            : "未确认（本轮没有 claimTravelReward 回执，领取按钮没点到或已无待领）")
                    .append(" · 待领 ").append(travelItems)
                    .append(" 项 · 找到领取按钮 ").append(out.optInt("travelMatched", 0))
                    .append(" 个\n");
        } else {
            // r41：084621 真机日志整段静默，看起来像「压根没做这块」。
            // 其实页面没弹出「出游归来」卡片时脚本不会调 tapTravel()，
            // r40 的 .claimBtn 精确点击本轮没有触发机会 —— 如实写明，别留白。
            detail.append("  出游归来领取: 本轮无待领项（页面没弹出「出游归来」卡片）\n")
                    .append("    说明: r40 精确点 .travelRewardDiv .claimBtn 的逻辑本轮未触发，")
                    .append("不是通过也不是失败，等下次真有待领时验证\n");
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
        // 本轮「提交回执」计数清零。计数器存在页面 localStorage 里，页面重载不丢，
        // 所以只能在这里重置 —— 放在脚本里每次注入都重置的话，回跳重载后计数又会归零。
        page.eval("try{localStorage.setItem('__cmccGS',JSON.stringify({t:Date.now(),n:0,by:{}}))}catch(e){}", 3_000L);
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
                appendReceipt(detail, out, page);
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
            // 浏览任务不能在 VUE_SCRIPT 里连着点：一点就跳走，脚本上下文随即销毁。
            // 这里交给 runTaskLoop 一个个来 —— 点一个、等页面自己跳出去又回来、
            // 再复查剩余数。11-11 12:02 手动日志就是这条路径，全程不做人为停留。
            // v4.10.0：任务必须先全部做完，投喂 / 收蛋放到之后的收尾阶段 ——
            // 任务会发下新的饲料 / 蛋，先喂先收就会「做了任务却没喂、喂后没领蛋」。
            int taskDoneReal = todo > 0 ? runTaskLoop(page, detail, page.url(), todo) : 0;
            JSONObject fin = runFinish(page, detail);
            if (fin != null && fin.optBoolean("ok", false)) {
                JSONArray finActs = fin.optJSONArray("actions");
                if (finActs != null) {
                    for (int i = 0; i < finActs.length(); i++) {
                        lines.append("· ").append(finActs.optString(i)).append('\n');
                    }
                }
                JSONObject finAfter = fin.optJSONObject("after");
                if (finAfter != null) {
                    after = finAfter;   // 让下面的 changed 判定用投喂 / 收蛋之后的真实快照
                }
            }
            int done = out.optInt("taskDone", 0) + taskDoneReal;
            int travelItems = out.optInt("travelItems", 0);
            int travelMatched = out.optInt("travelMatched", 0);
            String travelLabels = joinPaths(out.optJSONArray("travelLabels"));
            // r40：验收只认 claimTravelReward 的真实回执，不再拿「点了几次」当结果。
            int travelClaims = submitGroups(page).optInt("claimTravelReward", 0);
            if (travelItems > 0 || travelClaims > 0) {
                lines.append("出游归来领取: ").append(travelClaims > 0
                                ? "已确认（claimTravelReward " + travelClaims + " 条）"
                                : "未确认（本轮没有 claimTravelReward 回执）")
                        .append(" · 待领 ").append(travelItems)
                        .append(" 项 · 找到领取按钮 ").append(travelMatched).append(" 个");
                if (!travelLabels.isEmpty()) {
                    lines.append(" [").append(travelLabels).append("]");
                }
                lines.append('\n');
            } else {
                // r41：不再静默。页面没弹出「出游归来」卡片时不点领取按钮，
                // 明确写一行，避免日志看起来像漏做了这块。
                lines.append("出游归来领取: 本轮无待领项（页面没弹出「出游归来」卡片，")
                        .append("r40 精确领取逻辑未触发）\n");
            }
            int rounds = fin == null ? 0 : fin.optInt("rounds", 0);
            if (rounds > 0) {
                lines.append("收尾投喂/收蛋循环: ").append(rounds).append(" 轮（饲料/待收蛋收敛到 0）\n");
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
                // r27：打卡状态不再打「→」空箭头。pageInitData.signFlag 在 13:42 日志里
                // 前后都是空串，箭头只能打出「打卡:  → 」，等于没信息。改打本次实测状态。
                String signTxt = before.optString("sign");
                if (signTxt.isEmpty()) {
                    signTxt = out.optString("signAfter",
                            out.optString("signState", ""));
                }
                lines.append("打卡: ").append(signTxt.isEmpty() ? "未读到" : signTxt).append('\n');
            }
            String body = out.optString("text", "");
            if (!body.isEmpty()) {
                lines.append("页面: ").append(body).append('\n');
            }
            detail.append("=== 页面组件 ===\n").append(lines);
            // v3.96 回执复核：查询接口 != 提交接口。日志里 07:45 那轮
            // index/taskIndex/signIndex/boxQuery 发了 16 条，提交类 0 条，
            // 却被判成 ✓。现在把真实提交数摆到判定第一优先级。
            appendReceipt(detail, out, page);
            // r27：摘要与复核统一读 __cmccGS.by 分组。旧版 submitN 取
            // max(out.submitN, submitCount) 两个不同来源的大值，submitPaths 又在
            // 空时硬猜成 "doTask" —— 13:42 号B日志里 8 条回执被写成「（signIn）」，
            // 复核却只有 1 条。现在总数 = 分组之和，括号里直接列分组明细。
            JSONObject by = submitGroups(page);
            int submitN = 0;
            java.util.Iterator<String> bk = by.keys();
            while (bk.hasNext()) {
                submitN += by.optInt(bk.next(), 0);
            }
            int netN = out.optInt("netN", 0);
            boolean dataReady = out.optBoolean("dataReady", false);
            String submitPaths = groupText(by);
            String queryPaths = joinPaths(out.optJSONArray("queryPaths"));

            // r29：今天本来就做完了，不是失败。15:29 那轮页面返回 6 个任务、
            // isComplete 全是 2、「待做任务 0 个」，因为 todo==0 根本没进 runTaskLoop
            //（上面只在 todo>0 时调用），于是 submitN / changed / taskDoneReal 全是 0，
            // 被下面的零提交判据记成 FAIL，用户看到 ✗ 以为点击坏了。
            // 只有「数据确实就绪」才敢这么记：页面没加载完时 total 也可能是 0。
            if (total > 0 && todo == 0 && dataReady) {
                String idle = "收鹅蛋 · 今天 " + total + " 个任务已全部完成";
                detail.append("判定(r29): 共 ").append(total).append(" 个任务，待做 0，")
                        .append("业务数据就绪 → 今日任务已完成，不空转、不记失败\n");
                return TaskRunner.Result.idle(idle, detail.toString(),
                        "今天没有待做任务：页面返回 " + total + " 个任务，全部已完成。\n"
                                + "这不是点击失败，模块不会重复点，也不会记失败次数。\n"
                                + "明天任务刷新后正常执行即可。");
            }

            if (submitN == 0 && !changed && taskDoneReal == 0) {
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

            // r27：完成判据以「真实数据变化 / doTask 回执 / 打卡状态翻转」为主。
            // 旧版只要 submitN>0 就判 OK —— 号A 13:38 那轮只发了 1 条 signIn
            // （页面本来就已打卡），蛋、饲料、任务全没动，却也写成 OK。
            int doTaskN = by.optInt("doTask", 0);
            boolean signFlipped = "待打卡".equals(out.optString("signState", ""))
                    && "已打卡".equals(out.optString("signAfter", ""));
            boolean realWork = changed || doTaskN > 0 || taskDoneReal > 0 || signFlipped;

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
            if (signFlipped) {
                summary.append(" · 打卡成功");
            }
            if (!realWork) {
                summary.append(" · 没有可做的事");
                String idleText = summary.toString();
                detail.append("判定(r27): 有回执但数据/任务都没动（").append(submitPaths)
                        .append("），按「没有可做的事」记账，不算完成\n");
                return TaskRunner.Result.idle(idleText, detail.toString(), idleText);
            }
            String resultText = summary.toString();
            return TaskRunner.Result.ok(resultText, detail.toString(), resultText);
        } catch (Throwable e) {
            detail.append("组件结果解析失败: ").append(e).append('\n').append(raw).append('\n');
            return null;
        }
    }

    /**
     * 点一个未完成的浏览任务，点完立刻返回（不等跳转）。
     *
     * <p>为什么不调 {@code c.toDoTask(t, j)}：11-11 12:02 手动日志里，点任务卡片后
     * 页面自己发 doTask、自己跳落地页、自己回跳，服务端才认。模块直接调 toDoTask
     * 会拿到「登记成功」的假回执，但人没去，进度永远是 0/1。
     * 点卡片走的是页面自己的 @click，doTask 与 toJump 都由它完成，参数不会错位。
     */
    private static final String TASK_ONE_SCRIPT = """
            (function(){
              try{
                var FORCE=__FORCE__;
                var SKIP=__SKIP__;
                // 「已完成」的判据只有 isComplete==='2'（11-11 13:32 日志实证：
                // 6 个任务里 3 个是 '2'，页面显示「待做 3 个」）。
                // 旧实现认 '1'/'true'，把已完成的也当成未完成，永远取同一个任务。
                function undone(list){var r=[];for(var i=0;i<list.length;i++){var t=list[i];if(!t)continue;
                  var s=String(t.isComplete);if(s==='2'||s==='true')continue;r.push(t)}return r}
                function nameOf(x){return String((x&&(x.jobTitle||x.taskKey))||'')}
                function skipped(x){
                  var s=nameOf(x);
                  for(var i=0;i<SKIP.length;i++){ if(SKIP[i]===s) return true }
                  return false;
                }
                var el=document.querySelector('#app')||document.querySelector('#root');
                var v=el&&el.__vue__;
                if(!v&&el&&el.__vue_app__&&el.__vue_app__._instance)v=el.__vue_app__._instance;
                function find(o,d){if(!o||d>12)return null;if(o.taskListArr)return o;
                  var cs=o.$children||[];for(var i=0;i<cs.length;i++){var x=find(cs[i],d+1);if(x)return x}return null}
                var c=v&&find(v,0);
                if(!c)return JSON.stringify({err:'组件没找到'});
                var list=c.taskListArr||[];
                var todo=undone(list);
                if(!todo.length)return JSON.stringify({none:1});
                // 本轮已经点过的任务直接跳过：13:32 那次 doTask 明明回了 200，
                // 因为剩余数没降（计数判据写错），脚本又把同一个任务点了第二遍。
                var t=null;
                for(var q=0;q<todo.length;q++){ if(!skipped(todo[q])){ t=todo[q]; break } }
                if(!t)return JSON.stringify({none:1});
                var title=String(t.jobTitle||t.taskKey||'');
                var idx=list.indexOf(t); if(idx<0)idx=0;
                function norm(s){return String(s||'').replace(/[「」『』【】"\\s]/g,'')}
                // toDoTask 内部就是「发 doTask → toJump 打开落地页」，和手点同一条链路。
                // 绝不覆盖 c.toJump —— 12:02 那次「登记成功但人没去」就是把它摘掉了。
                function byComp(tag){
                  try{
                    if(typeof c.toDoTask==='function'){
                      c.toDoTask(t,idx);
                      return JSON.stringify({name:title,kind:'toDoTask',tag:tag||''});
                    }
                  }catch(e){}
                  return '';
                }
                // r27：FORCE 从「布尔」改成「任务名」——只强制重试上一个失败的那个任务。
                // 旧版 failStreak>0 就让下一个任务走组件入口，结果是 9块9 失败一次后
                // 被记进 tried 永久跳过，后面的任务却替它走了兜底路径。
                if(FORCE && nameOf(t)===FORCE)return byComp('force');
                var key=norm(title).slice(0,6);
                var qm=/[「『【]([^」』】]+)[」』】]/.exec(title);
                var alt=qm&&qm[1]?norm(qm[1]):'';
                // 先按任务名找到标题节点，再往上扩到「含图片/按钮」的整块卡片。
                // 12:55 日志里 jobTitle 是「浏览我的亲友圈」，页面渲染成
                // 「浏览「我的亲友圈」 +10」，去掉引号再比才命中。
                // 搜索范围用「所有卡片的共同父级」，不是第一张卡。旧版取
                // document.querySelector('.doTaskDiv') 只覆盖第一张，idx>0 的任务
                // 永远找不到标题，只能按列表顺序硬取。
                var cardsAll=document.querySelectorAll('.doTaskDiv');
                var scope=(cardsAll.length&&cardsAll[0].parentElement)||document.body;
                var nodes=scope.querySelectorAll('*');
                var anchor=null,anchorLen=99999;
                for(var i=0;i<nodes.length;i++){
                  var e=nodes[i],tx=norm(e.innerText);
                  if(!tx||tx.length>200)continue;
                  var hitC=(key&&tx.indexOf(key)>=0)||(alt&&tx.indexOf(alt)>=0);
                  if(!hitC)continue;
                  if(tx.length<anchorLen){anchor=e;anchorLen=tx.length}
                }
                if(!anchor){
                  // 连标题都是图时按列表顺序取：taskListArr 与 .doTaskDiv 顺序一致。
                  if(cardsAll.length>idx)anchor=cardsAll[idx];
                }
                if(!anchor)return byComp('nocard')||JSON.stringify({err:'任务卡片没找到',name:title});
                // r28：只认标题所在的这一张卡，绝不向上扩。
                // r27 是 for(up<6) 一路往父节点爬，直到找到「含 img 的块」——任务列表
                // 本身就在一个可上下滑的容器里，爬到列表容器就满足条件停住了，于是
                // card.querySelectorAll('img') 把**别的任务**的按钮图也收进来，再按
                // r.left/100 加分排序，最靠左那张永远第一，三次点同一个 @280,959 就是这么来的。
                var card=null;
                try{ card=anchor.closest('.doTaskDiv') }catch(x){}
                if(!card)card=anchor;
                // r28 三级降级，前两级都不碰坐标。
                // ① 卡内有文字按钮：直接 DOM click。事件派发不受滚动位置、遮挡、
                //    pointer-events 影响，也不需要先滚到可视区。
                var KW={'去完成':1,'去逛逛':1,'去参与':1,'立即完成':1,'去看看':1,
                        '去领取':1,'去做任务':1,'立即领取':1,'去签到':1};
                var txts=card.querySelectorAll('button,a,span,div,p,i,em');
                for(var j=0;j<txts.length;j++){
                  var s=norm(txts[j].innerText);
                  if(!s||s.length>10)continue;
                  if(KW[s]){
                    try{ txts[j].click() }catch(x){}
                    return JSON.stringify({name:title,kind:'text',btn:s,url:String(location.href)});
                  }
                }
                // ② 纯图片按钮：不猜坐标，直接调组件方法。toDoTask 内部就是
                //    「发 doTask → toJump 打开落地页」，与手点同一条链路（12:02 已实证）。
                //    14:58 这次三次点 @280,959 全无反应，就是候选排序选中了别的任务
                //    的图；与其修打分，不如根本不用坐标。
                var comp=byComp('imgcard');
                if(comp)return comp;
                // ③ 组件方法也没有（页面改版）才退回坐标。此时必须先在滑动容器里
                //    把按钮滚进可视区——getBoundingClientRect 不受容器裁剪影响，
                //    露半截的卡片算出来的中心点会落在容器外，点下去打在底栏/遮罩上。
                var cands=[];
                function push(node,score,kind){
                  if(!node)return;
                  var r=null;
                  try{ r=node.getBoundingClientRect() }catch(x){ return }
                  if(!r||r.width<8||r.height<8)return;
                  for(var q=0;q<cands.length;q++){ if(cands[q].el===node)return }
                  cands.push({el:node,score:score,kind:kind});
                }
                var imgs=card.querySelectorAll('img');
                for(var m=0;m<imgs.length;m++){
                  var im=imgs[m],ir=null;
                  try{ ir=im.getBoundingClientRect() }catch(x){ continue }
                  if(!ir||ir.width<20||ir.height<12)continue;
                  var ratio=ir.width/Math.max(ir.height,1);
                  var tag=(im.src||'')+' '+(im.className||'')+' '+(im.alt||'')
                          +' '+(im.getAttribute('data-src')||'');
                  var named=/task|btn|button|complete|finish|go|receive|lingqu|done|goto/i.test(tag);
                  var shape=(ir.height>=16&&ir.height<=80&&ratio>=1.4&&ratio<=8);
                  if(named||shape)push(im,(named?60:30)+(shape?25:0),'img');
                }
                if(!cands.length){
                  var bs=card.querySelectorAll('[class*=btn],[class*=Btn],[class*=button],[onclick]');
                  for(var n=0;n<bs.length;n++)push(bs[n],25,'box');
                }
                if(!cands.length)return JSON.stringify({err:'任务卡片里没有可点的按钮',name:title});
                cands.sort(function(a,b){return b.score-a.score});
                var pick=cands[0];
                try{ pick.el.scrollIntoView({block:'center',inline:'nearest'}) }catch(x){}
                var r2=null;
                try{ r2=pick.el.getBoundingClientRect() }catch(x){}
                if(!r2)return JSON.stringify({err:'拿不到按钮位置',name:title});
                var x=r2.left+r2.width/2,y=r2.top+r2.height/2;
                var vw=window.innerWidth||360, vh=window.innerHeight||640;
                if(x<4||y<4||x>vw-4||y>vh-4){
                  return JSON.stringify({err:'按钮在可视区外 '+Math.round(x)+','+Math.round(y),name:title});
                }
                var top=null;
                try{ top=document.elementFromPoint(x,y) }catch(x){}
                if(!top||!(top===pick.el||pick.el.contains(top)||top.contains(pick.el))){
                  return JSON.stringify({err:'按钮被遮住 '+Math.round(x)+','+Math.round(y),name:title});
                }
                // 事件序列：r27 只有 touch/mouse，没有 pointer；而且 touchstart 是用
                // MouseEvents 造的假货，e.touches 为 undefined，页面上的 touch 逻辑
                // 一读就 return。这里补 pointerdown/pointerup，touch 用真 TouchEvent。
                var tg=pick.el;
                var pt={bubbles:true,cancelable:true,clientX:x,clientY:y,view:window};
                try{ tg.dispatchEvent(new PointerEvent('pointerdown',pt)) }catch(x){}
                try{ tg.dispatchEvent(new PointerEvent('pointerup',pt)) }catch(x){}
                var touchOk=false;
                try{
                  var tt=new Touch({identifier:1,target:tg,clientX:x,clientY:y,
                                    pageX:x+(window.scrollX||0),pageY:y+(window.scrollY||0)});
                  tg.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,
                                    touches:[tt],targetTouches:[tt],changedTouches:[tt]}));
                  tg.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,
                                    touches:[],targetTouches:[],changedTouches:[tt]}));
                  touchOk=true;
                }catch(x){}
                var seq=touchOk?['mousedown','mouseup','click']
                               :['touchstart','touchend','mousedown','mouseup','click'];
                for(var k=0;k<seq.length;k++){
                  try{
                    var ev=document.createEvent('MouseEvents');
                    ev.initMouseEvent(seq[k],true,true,window,1,x,y,x,y,false,false,false,false,0,null);
                    tg.dispatchEvent(ev);
                  }catch(x){}
                }
                try{ if(typeof tg.click==='function')tg.click() }catch(x){}
                return JSON.stringify({name:title,kind:pick.kind,x:Math.round(x),y:Math.round(y),
                                       url:String(location.href)});
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;


    /**
     * 只做一件事：把「本轮发出去的提交类回执」累加到页面 localStorage。
     *
     * <p>点完任务页面常常整页重载（跳落地页再被拉回活动页），页面里注入的钩子
     * 和 NET 数组一起消失；13:32 那次 {@code doTask} 明明回了 200，复核却写成
     * 「提交类 0 条」，就是被重载清掉的。localStorage 同源持久，重载不丢。
     *
     * <p>与 VUE_SCRIPT 里的 {@code hookGooseNet} 共用 {@code window.__cmccGSNet}
     * 标记，谁先装谁生效，不会重复计数。
     */
    private static final String NET_HOOK_SCRIPT = """
            (function(){
              try{
                if(window.__cmccGSNet) return 'already';
                window.__cmccGSNet=1;
                // r27：n 仍是「本轮所有提交类总数」，另按接口名分一组 by[path]。
                // 13:42 号B日志实证：只数总数时，签到/投喂/收蛋/出游奖励都会让
                // runTaskLoop 误判「这个任务拿到 doTask 回执」——6 个任务报 5 个成功，
                // 真实 doTask 只有 3 条。按 path 分组后，验收只认 doTask 增量。
                function bump(path){
                  try{
                    var o={t:0,n:0,by:{}};
                    try{ o=JSON.parse(localStorage.getItem('__cmccGS')||'{}')||{t:0,n:0,by:{}} }catch(x){}
                    o.n=(Number(o.n)||0)+1;
                    if(!o.by||typeof o.by!=='object')o.by={};
                    var k=String(path||'unknown');
                    o.by[k]=(Number(o.by[k])||0)+1;
                    localStorage.setItem('__cmccGS',JSON.stringify(o));
                  }catch(x){}
                }
                // 不是提交类返回空串；是提交类返回接口名（供 bump 分组）。
                function subPath(u,s,b){
                  var str=String(u||''), i=str.indexOf('/raiseGoose/');
                  if(i<0) return '';
                  var t=str.slice(i+12).split('?')[0].split('#')[0].split('&')[0].split('/')[0];
                  if(t!=='doTask'&&t!=='signIn'&&t!=='feedGoose'&&t!=='harvestEgg'
                     &&t!=='claimTravelReward'&&t!=='claimMailboxPrize'&&t!=='claimPrize'
                     &&t!=='boxClaim') return '';
                  var st=Number(s)||0;
                  if(st<200||st>=400) return '';
                  var body=String(b||'');
                  if(/"success"\\s*:\\s*false/i.test(body)) return '';
                  if(/"code"\\s*:\\s*"(?!200)/i.test(body)) return '';
                  return t;
                }
                var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){ this.__qu=String(u||''); return op.apply(this,arguments) };
                XMLHttpRequest.prototype.send=function(b){
                  var x=this;
                  try{ x.addEventListener('load',function(){
                    try{ var p=subPath(x.__qu,x.status,x.responseText); if(p) bump(p) }catch(e){}
                  }) }catch(e){}
                  return se.apply(this,arguments);
                };
                var of=window.fetch;
                if(typeof of==='function'){
                  window.fetch=function(input,init){
                    var u=(typeof input==='string')?input:((input&&input.url)||'');
                    var r=of.apply(this,arguments);
                    try{
                      if(String(u).indexOf('/raiseGoose/')>=0 && r && typeof r.then==='function'){
                        r.then(function(rr){
                          try{ var cp=rr.clone(); cp.text().then(function(t){
                            try{ var p=subPath(u,rr.status,t); if(p) bump(p) }catch(e){}
                          }) }catch(e){}
                        });
                      }
                    }catch(e){}
                    return r;
                  };
                }
                return 'ok';
              }catch(e){ return 'err' }
            })()
            """;
    /** 数一下还剩几个未完成的任务。-1 = 读不到。 */
    private static final String TASK_COUNT_SCRIPT = """
            (function(){
              try{
                var el=document.querySelector('#app')||document.querySelector('#root');
                var v=el&&el.__vue__;
                if(!v&&el&&el.__vue_app__&&el.__vue_app__._instance)v=el.__vue_app__._instance;
                function find(o,d){if(!o||d>12)return null;if(o.taskListArr)return o;
                  var cs=o.$children||[];for(var i=0;i<cs.length;i++){var x=find(cs[i],d+1);if(x)return x}return null}
                var c=v&&find(v,0);
                if(!c)return '-1';
                var list=c.taskListArr||[],n=0;
                for(var i=0;i<list.length;i++){var s=String(list[i]&&list[i].isComplete);
                  if(s!=='2'&&s!=='true')n++}
                return String(n);
              }catch(e){return '-1'}
            })()
            """;

    /**
     * 逐个点浏览任务。
     *
     * <p>节奏完全照手动：点一个 → 页面自己发 doTask 并跳落地页 → 等它回跳 →
     * 复查剩余任务数。不做停留计时 —— 11-11 12:02 手动日志里落地页只待了
     * 4.3 秒，那就是页面往返本身的耗时，用户没有刻意等。
     *
     * @return 本轮真正记上的任务数（以剩余数下降为准，不看接口回执）
     */
    private static int runTaskLoop(WebPage page, StringBuilder detail, String entry, int todo0) {
        detail.append("浏览任务: 待做 ").append(todo0).append(" 个，逐个点（点完让它自己跳，不停留）\n");
        List<String> tried = new ArrayList<>();
        int done = 0;
        int failStreak = 0;
        int left = todo0;
        // r27：上一个失败的任务名。非空时下一轮只强制重试它（走组件入口 toDoTask），
        // 而不是让「下一个任务」替它兜底 —— 13:42 号B日志里 9块9 图片按钮点空一次后
        // 就被永久跳过，兜底反被用在了 领视频会员 上。
        String retryName = "";
        for (int i = 0; i < TASK_TRIES; i++) {
            // 上一次点击可能把页面整页重载了（跳落地页再被拉回），注入的钩子跟着丢，
            // 这里补装一次；已装过就直接返回，不会重复计数。
            page.eval(NET_HOOK_SCRIPT, 5_000L);
            // r27：只数 doTask。旧版数「所有提交类总数」，13:42 号B日志里
            // signIn/claimTravelReward/feedGoose/harvestEgg 都会被算成
            // 「这个任务拿到 doTask 回执」，6 个任务报 5 个成功、真实只有 3 条。
            int before = submitCountOf(page, "doTask");
            // 点过的任务会进 tried，脚本按名字跳过，不再原地重复点同一个。
            String raw = page.eval(TASK_ONE_SCRIPT
                    .replace("__FORCE__", jsStr(retryName))
                    .replace("__SKIP__", skipJson(tried)), 10_000L);
            if (raw == null || raw.isEmpty()) {
                detail.append("浏览任务: 点击脚本没回报，停止\n");
                break;
            }
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Throwable e) {
                detail.append("浏览任务: 回报解析失败 ").append(brief(raw)).append('\n');
                break;
            }
            if (o.has("none")) {
                detail.append("浏览任务: 没有新的待做了\n");
                break;
            }
            if (o.has("err")) {
                detail.append("浏览任务: ").append(o.optString("err")).append('\n');
                break;
            }
            String name = o.optString("name", "");
            String kind = o.optString("kind", "");
            String kd = "text".equals(kind) ? "文字按钮"
                    : "img".equals(kind) ? "图片按钮"
                    : "box".equals(kind) ? "按钮容器"
                    : "toDoTask".equals(kind) ? "组件入口"
                    : "卡片";
            if (!name.isEmpty()) {
                tried.add(name);
            }
            detail.append("浏览任务 ").append(i + 1).append(": 点「").append(name).append("」（").append(kd).append("）")
                    .append(o.has("x") ? " @" + o.optInt("x") + "," + o.optInt("y") : "")
                    .append('\n');
            sleep(2_000L);
            String nowUrl = page.url();
            if (nowUrl != null && !isGoosePage(nowUrl)) {
                detail.append("浏览任务: 已跳到 ").append(briefUrl(nowUrl)).append("，等回跳\n");
                long end = System.currentTimeMillis() + 6_000L;
                while (System.currentTimeMillis() < end && !isGoosePage(page.url())) {
                    sleep(600L);
                }
            }
            if (page.url() == null || !isGoosePage(page.url())) {
                detail.append("浏览任务: 没自己回来，拉回活动页\n");
                if (!page.navigate(entry, 25_000L)) {
                    detail.append("浏览任务: 拉回失败，停止\n");
                    break;
                }
                sleep(1_500L);
            }
            waitLogged(page, 12_000L);
            // 完成判据以「本轮 doTask 回执有没有增加」为准。剩余数只作参考：
            // 13:32 那次 doTask 明明回了 200，但剩余数读的是另一套字段，
            // 恒为 6 不下降，于是脚本判「没记上」并停下，后面两个任务全没做。
            int after = submitCountOf(page, "doTask");
            boolean submitted = before >= 0 && after > before;
            int now = taskTodo(page);
            if (submitted) {
                done++;
                failStreak = 0;
                retryName = "";
                detail.append("浏览任务: 「").append(name).append("」已拿到 doTask 回执（doTask ")
                        .append(before).append(" → ").append(after).append("）\n");
                if (now >= 0) {
                    left = now;
                }
            } else if (now >= 0 && now < left) {
                done++;
                failStreak = 0;
                retryName = "";
                detail.append("浏览任务: 剩余 ").append(left).append(" → ").append(now).append("（记上了）\n");
                left = now;
            } else {
                failStreak++;
                boolean retried = !name.isEmpty() && name.equals(retryName);
                detail.append("浏览任务: 「").append(name).append("」没拿到提交回执");
                if (now >= 0) {
                    detail.append("，剩余仍 ").append(now);
                }
                if (!retried && !name.isEmpty()) {
                    // 图片按钮点空（9块9 是绿底图，文本节点为空）→ 立刻让同一个任务
                    // 改走组件入口 toDoTask 再来一次。tried 里先摘掉它，下一轮才选得到。
                    retryName = name;
                    tried.remove(name);
                    detail.append("，改用组件入口重试");
                } else {
                    retryName = "";
                    detail.append("（组件入口也没成功）");
                }
                detail.append('\n');
                if (failStreak >= 3) {
                    detail.append("浏览任务: 连续 3 次没回执，停止本轮\n");
                    break;
                }
            }
            if (left == 0) {
                detail.append("浏览任务: 全部做完\n");
                break;
            }
        }
        detail.append("浏览任务: 本轮记上 ").append(done).append(" 个\n");
        return done;
    }

    /**
     * v4.10.0 收尾阶段：等所有浏览任务做完后，注入 FINISH_SCRIPT 把饲料喂完、
     * 蛋收完，收敛到 0。返回脚本回报（含 actions / rounds / 收尾后快照）。
     *
     * <p>投喂 / 收蛋也是提交类接口（feedGoose / harvestEgg），NET_HOOK_SCRIPT
     * 会把它们记进 localStorage 的 __cmccGS 计数；runVueOnce 里随后用
     * submitCount 取到的总数自然包含这一段，不用这里单独记账。
     */
    private static JSONObject runFinish(WebPage page, StringBuilder detail) {
        page.eval("(function(){try{window.__cmcc_goose_finish=''}catch(e){}})()", 3_000L);
        // 收尾阶段发出的 feedGoose / harvestEgg 也要计入提交回执：NET_HOOK_SCRIPT
        // 是幂等的，页面若在浏览任务里重载过，这里正好补装一次。
        page.eval(NET_HOOK_SCRIPT, 5_000L);
        page.eval(FINISH_SCRIPT, 8_000L);
        long deadline = System.currentTimeMillis() + 100_000L;
        String raw = null;
        while (System.currentTimeMillis() < deadline) {
            String value = page.eval(
                    "(function(){var v=window.__cmcc_goose_finish;return v?String(v):'';})()", 6_000L);
            if (value != null && !value.isEmpty()) {
                raw = value;
                break;
            }
            sleep(600L);
        }
        if (raw == null) {
            detail.append("收尾阶段: 投喂/收蛋脚本 100 秒没回报（保持任务阶段结果）\n");
            return null;
        }
        try {
            JSONObject fin = new JSONObject(raw);
            if (!fin.optBoolean("ok", false)) {
                detail.append("收尾阶段: 脚本未成功（").append(fin.optString("msg", "")).append("）\n");
            }
            return fin;
        } catch (Throwable e) {
            detail.append("收尾阶段: 回报解析失败 ").append(brief(raw)).append('\n');
            return null;
        }
    }

    // r27：旧的 submitCount(WebPage) 只读 __cmccGS.n（所有提交类混算），已由
    // submitCountOf(page, "doTask") 取代 —— 保留会让「8 条回执」和「1 条 doTask」
    // 两个口径继续并存，正是这轮要消灭的问题。

    /**
     * r27：读 __cmccGS.by —— 本轮各提交类接口各自的条数。页面整页重载时 NET
     * 数组会清空，但 localStorage 里的分组不丢，所以复核/摘要/任务验收统一用它。
     */
    private static JSONObject submitGroups(WebPage page) {
        String v = page.eval("(function(){try{var o=JSON.parse(localStorage.getItem('__cmccGS')||'{}');"
                + "return JSON.stringify(o.by||{})}catch(e){return '{}'}})()", 4_000L);
        try {
            return new JSONObject(v == null || v.isEmpty() ? "{}" : v);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** r27：只数某一种提交类（如 doTask），不再把签到/投喂/出游奖励混进来。 */
    private static int submitCountOf(WebPage page, String path) {
        return submitGroups(page).optInt(path, 0);
    }

    /** r27：把分组明细拼成 "doTask×3/signIn×1/feedGoose×1" 这类可读串。 */
    private static String groupText(JSONObject by) {
        if (by == null || by.length() == 0) {
            return "";
        }
        String[] order = {"doTask", "signIn", "claimTravelReward", "feedGoose", "harvestEgg",
                "claimMailboxPrize", "claimPrize", "boxClaim"};
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String k : order) {
            int n = by.optInt(k, 0);
            if (n > 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(k).append('×').append(n);
            }
            seen.add(k);
        }
        java.util.Iterator<String> it = by.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (seen.contains(k)) {
                continue;
            }
            int n = by.optInt(k, 0);
            if (n > 0) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(k).append('×').append(n);
            }
        }
        return sb.toString();
    }

    /** r27：把字符串安全地塞进注入脚本当 JS 字面量（任务名里可能带「」、引号）。 */
    private static String jsStr(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /** 已点过的任务名拼成 JS 数组字面量，供脚本跳过。 */
    private static String skipJson(List<String> keys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JSONObject.quote(keys.get(i)));
        }
        return sb.append(']').toString();
    }

    private static int taskTodo(WebPage page) {
        String v = page.eval(TASK_COUNT_SCRIPT, 6_000L);
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return -1;
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
              // r27：打卡状态三路判定。13:42 号B日志页面明写「今日已打卡，明日再来吧！」，
              // 组件侧却判「待打卡」并白调一次 dakaFeedA —— 旧逻辑只看
              // signObj.taskList 的 isComplete==='2'，而签到格子用的不是这个字段。
              // 顺序：显式标志位 → 页面文案 → 列表兜底，任一路成立即算已打卡。
              function signStateOf(c,st){
                try{
                  var p=c.pageInitData||{}, so=c.signObj||{};
                  var f=[so.signFlag,so.isSign,so.signStatus,so.dakaFlag,
                         p.signFlag,p.isSign,p.signStatus];
                  for(var i=0;i<f.length;i++){
                    var v=f[i];
                    if(v===null||v===undefined||v==='') continue;
                    var t=String(v);
                    if(t==='1'||t==='2'||t==='true') return '已打卡';
                  }
                }catch(e){}
                try{ if(/今日已打卡|明日再来/.test(bodyText())) return '已打卡' }catch(e){}
                try{ if(leftOf(st).length===0) return '已打卡' }catch(e){}
                return '待打卡';
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
              // 提交回执要跨页面重载累加：点完任务页面常整页重载（回跳或被拉回），
              // 页面里的 NET 数组跟着清空，13:32 那次 doTask 明明 200，复核却写成
              // 「提交类 0 条」。localStorage 是同一 origin 持久存储，重载不丢。
              // r27：与 NET_HOOK_SCRIPT 的 bump 共用同一份 __cmccGS 分组结构。
              function bumpSubmit(path){
                try{
                  var o={t:0,n:0,by:{}};
                  try{ o=JSON.parse(localStorage.getItem('__cmccGS')||'{}')||{t:0,n:0,by:{}} }catch(x){}
                  o.n=(Number(o.n)||0)+1;
                  if(!o.by||typeof o.by!=='object')o.by={};
                  var k=String(path||'unknown');
                  o.by[k]=(Number(o.by[k])||0)+1;
                  localStorage.setItem('__cmccGS',JSON.stringify(o));
                }catch(x){}
              }
              function hookGooseNet(){
                // 幂等：runTaskLoop 在页面重载后会重新注入 NET_HOOK_SCRIPT，
                // 两边共用同一个标记，避免同一个 doTask 被计两次。
                if(window.__cmccGSNet) return;
                window.__cmccGSNet=1;
                try{
                  var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open=function(m,u){ this.__qu=String(u||''); return op.apply(this,arguments) };
                  XMLHttpRequest.prototype.send=function(b){
                    var x=this;
                    try{ x.addEventListener('load',function(){
                      try{
                        if(String(x.__qu).indexOf('/raiseGoose/')>=0){
                          var e={u:String(x.__qu).slice(0,220),s:x.status,b:String(x.responseText||'').slice(0,600)};
                          NET.push(e);
                          if(isSubmit(e)) bumpSubmit(pathOf(e.u));
                        }
                      }catch(e){}
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
                              var e={u:String(u).slice(0,220),s:rr.status,b:String(t).slice(0,600)};
                              NET.push(e);
                              if(isSubmit(e)) bumpSubmit(pathOf(e.u));
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
                // 重载过的页面 NET 是空的，但本轮提交计数还在 localStorage 里。
                var kept=0;
                try{ var po=JSON.parse(localStorage.getItem('__cmccGS')||'{}'); kept=Number(po.n)||0 }catch(x){}
                if(kept>n) n=kept;
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
                  // r40：页面 Vue 的 touch 指令会读 e.touches[0] / e.changedTouches[0]。
                  // 旧版派的是空 Event('touchstart')，一点就抛
                  // "Cannot read properties of undefined (reading '0')"，事件链当场断掉，
                  // claimTravelReward 根本发不出去。这里补齐触摸点，页面指令才拿得到坐标。
                  var touch=null;
                  try{
                    if(typeof Touch==='function'){
                      touch=new Touch({identifier:1,target:e,clientX:cx,clientY:cy,
                                       pageX:cx,pageY:cy,screenX:cx,screenY:cy});
                    }
                  }catch(x){}
                  // 没有 Touch 构造器时也给出带坐标的模拟触点对象：页面读
                  // touches[0].clientX / touches.length 都不会再是 undefined。
                  var fake={identifier:1,target:e,clientX:cx,clientY:cy,
                            pageX:cx,pageY:cy,screenX:cx,screenY:cy};
                  var tl=function(){return [touch?touch:fake]};
                  var touchInit=function(type){
                    var down=type!=='touchend';
                    var o=mk();
                    o.touches=down?tl():[];
                    o.targetTouches=down?tl():[];
                    o.changedTouches=tl();
                    return o;
                  };
                  var ts=['pointerdown','touchstart','mousedown','pointerup','touchend','mouseup'];
                  for(var i=0;i<ts.length;i++){
                    try{
                      var ev;
                      if(ts[i].indexOf('pointer')===0 && typeof PointerEvent==='function'){
                        ev=new PointerEvent(ts[i],mk());
                      }else if(ts[i].indexOf('touch')===0){
                        if(typeof TouchEvent==='function'){
                          ev=new TouchEvent(ts[i],touchInit(ts[i]));
                        }else{
                          ev=new Event(ts[i],{bubbles:true,cancelable:true});
                          var ti=touchInit(ts[i]);
                          try{
                            Object.defineProperty(ev,'touches',{value:ti.touches});
                            Object.defineProperty(ev,'targetTouches',{value:ti.targetTouches});
                            Object.defineProperty(ev,'changedTouches',{value:ti.changedTouches});
                            Object.defineProperty(ev,'clientX',{value:cx});
                            Object.defineProperty(ev,'clientY',{value:cy});
                          }catch(x){}
                        }
                      }else{
                        ev=new MouseEvent(ts[i],mk());
                      }
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
              // r40：11-13 用户抓包实证，真正会发 claimTravelReward 的按钮是
              //   DIV.claimBtn「立即领取」 < DIV.rewardCard < DIV.travelRewardDiv
              // 点它 0.2 秒后就 POST claimTravelReward → 200 →「饲料已领取成功」。
              // 旧版用宽松文本 WORD 把 cardSub2「别忘了领取哦~」、rewardBox「x2」、
              // IMG:rewardFeed 都当按钮，点满 2 个就 return，claimBtn 根本没轮到；
              // 再叠加 fireEl 的空 touchstart 让页面 JS 抛异常，整轮零 claimTravelReward。
              // 现在只点领取按钮本身，且按可见性逐个点完，不提前收工。
              function tapTravel(){
                var r={clicked:0,labels:[],boxes:0,matched:0};
                var seen=[];
                // 只在出游奖励弹窗内找，避免点到任务列表里的「去领取 / 立即领取」。
                var scopes=document.querySelectorAll(
                    '.travelRewardDiv,[class*=travelReward],[class*=TravelReward]');
                // 精确优先：页面自己的领取按钮类名；类名再兜一层「立即领取」文本。
                var SEL='[class*=claimBtn],[class*=ClaimBtn],[class*=claimbtn],'
                      + 'button,[class*=btnGet],[class*=btnReceive]';
                function isClaimBtn(e){
                  if(!visEl(e)||seen.indexOf(e)>=0) return false;
                  if(e.tagName==='IMG') return false;              // 图片不是按钮
                  var cls=String(e.className||'');
                  var t=String(e.innerText||'').replace(/ /g,'').slice(0,16);
                  // 提示文案 / 数量角标 / 卡片容器一律排除。
                  if(/cardSub|cardsub|rewardBox|rewardbox|rewardCard|rewardcard|cardTip|cardText/i.test(cls)) return false;
                  if(/别忘|已领取|已收下|已获得|去领取|去逛逛|去完成|去查看|规则|说明/.test(t)) return false;
                  if(/claim|btnget|btnreceive/i.test(cls)) return true;
                  return /^(立即领取|一键领取|全部领取|开心收下|领取饲料|收下)$/.test(t);
                }
                for(var s=0;s<scopes.length;s++){
                  var box=scopes[s];
                  if(!visEl(box)) continue;
                  r.boxes++;
                  var els=box.querySelectorAll(SEL);
                  for(var i=0;i<els.length;i++){
                    var e=els[i];
                    if(!isClaimBtn(e)) continue;
                    seen.push(e);
                    r.matched++;
                    if(fireEl(e)){
                      r.clicked++;
                      var t=String(e.innerText||'').replace(/ /g,'').slice(0,16);
                      r.labels.push(t||('BTN:'+String(e.className||'').slice(0,20)));
                    }
                  }
                }
                // 类名改版兜底：整个弹窗内文本严格等于领取口令、且自身是可点元素。
                if(r.matched===0){
                  var all=document.querySelectorAll('.travelRewardDiv *,[class*=travelReward] *');
                  for(var j=0;j<all.length;j++){
                    var b=all[j];
                    if(!isClaimBtn(b)) continue;
                    var bt=String(b.innerText||'').replace(/ /g,'').slice(0,16);
                    if(!/^(立即领取|一键领取|全部领取|开心收下|领取饲料|收下)$/.test(bt)) continue;
                    seen.push(b);
                    r.matched++;
                    if(fireEl(b)){ r.clicked++; r.labels.push(bt) }
                  }
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
                    out.travelMatched=tp.matched;
                    // r40：点了几下不是结果。这里只记「找到几个领取按钮」这个事实，
                    // 领没领到由下面 claimTravelReward 的真实回执说了算。
                    out.actions.push('出游奖励: '+out.travelItems+' 项 · 找到领取按钮 '+tp.matched
                                     +' 个（弹窗 '+tp.boxes+' 个）'
                                     +(tp.labels.length?' ['+tp.labels.join('/')+']':''));
                    if(tp.clicked>0) await wait(1800);  // 留时间给页面自己发 claimTravelReward
                  }else{
                    // r41：待领 0 项时明确记一行，别让「出游奖励」整段静默。
                    out.actions.push('出游奖励: 本轮无待领项（页面没弹出「出游归来」卡片，未触发领取按钮）');
                  }
                }catch(e){ out.actions.push('刷新/出游领取异常:'+e.message) }

                // ---- 2. 日常任务：这里只统计，点击交给 Java 侧逐个做 ----
                //      11-11 12:02 手动日志实证：点任务 → doTask 200 → 页面自己跳到
                //      落地页（shopDetail / ssoJump.html?dwzCode=…）→ 约 4 秒后回到
                //      活动页 → taskIndex 重新拉。完成这件事靠的是「真的跳出去又回来」。
                //      旧代码把 toJump 摘成空函数、只调 toDoTask 登记，等于登记完没去，
                //      服务端永远判 0/1 不动 —— 这就是「跑完了但一个都没记上」的根因。
                //      跳转会销毁当前脚本上下文，所以不能在这一次注入里连着点。
                var taskName=pick(c,['taskIndexFun','taskFun']);
                if(taskName){
                  try{
                    await c[taskName](); await wait(1600);
                    var list=c.taskListArr||[];
                    out.taskTotal=list.length;
                    var todo=leftOf(list);
                    out.taskTodo=todo.length;
                    var names=[];
                    for(var j=0;j<todo.length && j<10;j++){
                      names.push(String(todo[j].jobTitle||todo[j].taskKey||j).slice(0,18));
                    }
                    out.taskNames=names.join(' / ');
                    out.actions.push('待做任务 '+todo.length+' 个'
                                     +(out.taskNames?' ['+out.taskNames+']':''));
                  }catch(e){ out.actions.push('任务统计异常:'+e.message) }
                }

                // ---- 3. 打卡领饲料 ----
                try{
                  if(typeof c.signIndexFun==='function'){
                    await c.signIndexFun('handle'); await wait(1500);
                    var st=(c.signObj||{}).taskList||[];
                    var signBefore=signStateOf(c,st);
                    out.signState=signBefore;
                    out.actions.push('打卡: '+signBefore);
                    if(signBefore!=='已打卡' && typeof c.dakaFeedA==='function'){
                      try{ await c.dakaFeedA(); await wait(1800) }catch(e){}
                      // 打完再拉一次状态，用「变没变」当验收，而不是「调过就算成」。
                      try{ await c.signIndexFun('handle'); await wait(1000) }catch(e){}
                      var st2=(c.signObj||{}).taskList||[];
                      out.signAfter=signStateOf(c,st2);
                      out.actions.push('打卡后: '+out.signAfter);
                    }
                  }
                }catch(e){ out.actions.push('打卡异常:'+e.message) }

                // ---- 4. 投喂/收蛋：本轮不再在这里做 (v4.10.0) ----
                // 用户实证：先喂饲料/领蛋，再做任务，任务又发下新的饲料/蛋，
                // 结果「做了任务但没喂、喂后没领蛋」。VUE_SCRIPT 是一次性注入，
                // 浏览任务要由 Java 侧 runTaskLoop 逐个点、页面还会往返跳，
                // 所以在脚本里投喂永远早于任务。这里整个抽掉，改由独立的
                // FINISH_SCRIPT 在「全部任务做完之后」再注入，收敛到饲料/待收蛋为 0。

                // ---- 5. 奖金箱 ----
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
     * v4.10.0 收尾阶段脚本：等「全部任务做完」之后才注入。
     *
     * <p>用户实证：先投喂 / 领蛋，再做任务，任务又发下新的饲料 / 蛋，末尾
     * 还挂着「可投喂饲料 N 袋」，于是「做了任务但没喂、喂后没领蛋」。VUE_SCRIPT
     * 是一次性注入、浏览任务由 Java 侧 runTaskLoop 逐个点，天生先于任务，
     * 所以把投喂 / 收蛋整段抽到这里：先刷新读最新 feedCount / eggCount，
     * 有饲料就喂、有蛋就收，循环到两者都归 0（或连续无变化 / 达上限）。
     *
     * <p>投喂会产出新蛋，下一轮刷新就能看到 eggCount&gt;0，再收 —— 天然覆盖
     * 「喂之后领蛋」。全程只调页面自己的 feedGooseFun / harvestEggFun / indexFun，
     * 加密参数、签名、票据都由页面 JS 生成，模块不碰请求、不碰票据。
     * 提交回执由 NET_HOOK_SCRIPT 记进 localStorage，Java 侧收尾后统一读数。
     */
    private static final String FINISH_SCRIPT = """
            (function(){
              var ID='__cmcc_goose_finish';
              window[ID]='';
              function done(o){ try{ window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"ok":false,"stage":"crash"}' } }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function numOf(v){
                if(v===null||v===undefined) return -1;
                if(typeof v==='number') return isFinite(v)?v:-1;
                var n=parseInt(String(v).replace(/[^0-9-]/g,''),10);
                return isNaN(n)?-1:n;
              }
              function snap(c){
                var p=c.pageInitData||{};
                return {egg:String(p.eggCount==null?'':p.eggCount),
                        my:String(p.myEggCount==null?'':p.myEggCount),
                        feed:String(p.feedCount==null?'':p.feedCount),
                        sign:String(p.signFlag==null?'':p.signFlag),
                        travel:String(p.travelStatus==null?'':p.travelStatus)};
              }
              function findComp(v,d){
                if(!v||d>10) return null;
                if(typeof v.feedGooseFun==='function'||typeof v.harvestEggFun==='function') return v;
                var cs=v.$children||[];
                for(var i=0;i<cs.length;i++){ var r=findComp(cs[i],d+1); if(r) return r }
                return null;
              }
              (async function(){
                var root=document.querySelector('#app');
                var vm=root&&root.__vue__;
                var c=vm?findComp(vm,0):null;
                if(!c){ done({ok:false,stage:'init',msg:'收尾阶段没找到组件'}); return }
                var actions=[], rounds=0, lastKey='', stall=0, noFeed=0, noEgg=0;
                for(var rd=0; rd<16; rd++){
                  try{ if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(1200) } }catch(e){}
                  var pp=c.pageInitData||{};
                  var feed=numOf(pp.feedCount), eggs=numOf(pp.eggCount);
                  var key=feed+'/'+eggs, did=false;
                  // 先喂：饲料会转成蛋，下一轮刷新再收，覆盖「喂之后领蛋」。
                  if(feed>0 && typeof c.feedGooseFun==='function'){
                    try{
                      var beforeFeed=feed;
                      c.feedGooseFun(); await wait(1800); did=true;
                      actions.push('第'+(rd+1)+'轮投喂（饲料 '+beforeFeed+'）');
                    }catch(e){ actions.push('第'+(rd+1)+'轮投喂异常:'+e.message) }
                  } else { noFeed++ }
                  // 再收：把本轮 / 上轮投喂产出的蛋收干净。
                  try{ if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(600) } }catch(e){}
                  var pp2=c.pageInitData||{}, eggs2=numOf(pp2.eggCount);
                  if(eggs2>0 && typeof c.harvestEggFun==='function'){
                    try{ c.harvestEggFun(); await wait(1800); did=true;
                         actions.push('第'+(rd+1)+'轮收蛋（'+eggs2+' 枚）') }
                    catch(e){ actions.push('第'+(rd+1)+'轮收蛋异常:'+e.message) }
                  } else { noEgg++ }
                  rounds=rd+1;
                  if(!did){ actions.push('第'+(rd+1)+'轮: 饲料/待收蛋都为空，收敛'); break }
                  if(key===lastKey){
                    stall++;
                    if(stall>=3){ actions.push('第'+(rd+1)+'轮: 连续三轮无变化，收敛'); break }
                  }else{ stall=0 }
                  lastKey=key;
                }
                // 收尾刷新：让服务端把这一轮投喂 / 收蛋结算完，回灌到快照。
                try{
                  if(typeof c.indexFun==='function'){ await c.indexFun('1'); await wait(1600) }
                  if(typeof c.signIndexFun==='function'){ await c.signIndexFun(); await wait(600) }
                  if(typeof c.taskIndexFun==='function'){ await c.taskIndexFun(); await wait(800) }
                }catch(e){}
                done({ok:true,stage:'done',rounds:rounds,actions:actions,after:snap(c)});
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
