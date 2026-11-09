package com.cmcchook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 我的卡券（touch.10086.cn/i/mobile/mycoupons.html）扫描与批量使用。
 *
 * <p>卡券列表和核销接口的请求体都是密文（{@code {"body":"Z7+Bl7..."}}），
 * 还带 x-token / x-sign / x-time / x-nonce 四个签名头，Java 层拼不出来，
 * 所以只能借页面自己做：离屏 WebView 打开卡券中心，脚本读卡片、点「去使用」。
 *
 * <p>两条指令：
 * <ul>
 *   <li>{@link #CMD_SCAN}：把卡片解析成 JSON 发回模块，界面用原生列表勾选；</li>
 *   <li>{@link #CMD_USE}：按勾选的名单逐张点「去使用」，遇到短信验证码就
 *       把「要验证码」发回模块，用户在模块里填，再把码灌回页面。</li>
 * </ul>
 *
 * <p>判定不写漂亮话：只有页面出现成功提示，或兑换弹窗真的关掉了，才算这张券用掉；
 * 否则记失败并把页面当时的文案带回去。
 */
final class CouponTask {

    static final String CMD_SCAN = "couponscan";
    static final String CMD_USE = "couponuse:";
    static final String CMD_CODE = "couponcode:";

    static final String ACT_LIST = "com.cmcchook.action.COUPON_LIST";
    static final String ACT_PROGRESS = "com.cmcchook.action.COUPON_PROGRESS";
    static final String ACT_NEED_CODE = "com.cmcchook.action.COUPON_NEED_CODE";
    static final String ACT_DONE = "com.cmcchook.action.COUPON_DONE";

    /**
     * 我的卡券（裸地址）。v3.42：之前按钮带 {@code ?c=11&e=99&channelId&yx}
     * 一串活动入口参数，用容器打开会卡在登录中转圈；用户从 App 里手动进
     * 卡券中心用的就是这个裸地址，页面能正常渲染券列表。统一对齐手动路径。
     */
    static final String URL = "https://touch.10086.cn/i/mobile/mycoupons.html";

    private static final String[] HOSTS = {"10086.cn"};
    private static final long SCAN_WAIT_MS = 45_000L;
    private static final long CODE_WAIT_MS = 125_000L;
    /** v3.59：单张券的总等待上限（含等码）。超时只废这一张，不影响后面的。 */
    private static final long ITEM_WAIT_MS = 150_000L;
    /** 重进卡券页的等待上限：等新文档接管 + 券列表渲染出来。 */
    private static final long RELOAD_WAIT_MS = 12_000L;

    private static volatile String pendingCode;
    /** 码到达目标进程的时间，用来丢弃上一张券残留的旧码。 */
    private static volatile long pendingCodeAt;
    /** v3.61：本张是否已经把码灌进页面。用来区分「真没收到码」和「码已交给页面」。 */
    private static volatile boolean codeFedThisItem;
    private static volatile boolean busy;

    private CouponTask() {
    }

    static boolean busy() {
        return busy;
    }

    /** 模块把用户输入的验证码送进来。 */
    static void submitCode(String code) {
        submitCode(code, 0L);
    }

    /**
     * v3.56：at 是短信真实时间（毫秒）。0 表示来源拿不到时间（手动输入/旧通道），
     * 退回当前时间。
     *
     * <p>220046 日志实证：21:59:45 点「获取验证码」，模块把 21:57:08 的缓存码
     * 720354 灌了进去。旧版这里一律写 System.currentTimeMillis()，于是
     * 「这条码比本张开始等码更早」的校验永远成立不了 —— 旧码被当成新码。
     */
    static void submitCode(String code, long at) {
        pendingCode = code == null ? "" : code.trim();
        pendingCodeAt = at > 0 ? at : System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ 页面现场

    /**
     * v3.40：扫描/使用统一走这个抽象。
     * 优先复用用户在中国移动里打开的卡券页 —— 它带 App 完整 JS 桥，
     * Vue 券列表会真正渲染；只有用户没打开时才退回离屏 WebView
     * （离屏页实测只发两个横幅接口、body 文本为空，v3.39 扫描恒为 0 张）。
     */
    private interface Host {
        boolean ok();

        String eval(String js, long timeoutMs);

        /**
         * v3.58：重进卡券页，返回 true 表示页面已重新就绪。
         *
         * <p>「兑换成功」弹窗是页面里的一整块结果页，没有关闭按钮、点遮罩也不关，
         * 残留下来会让下一张点「去使用」弹不出兑换框。与其在 DOM 里跟它搏斗，
         * 不如刷一次页面 —— 弹窗、Vue 状态、脏按钮一起归零。
         */
        boolean reload();

        void close();

        String name();
    }

    /**
     * v3.59：页面「代际」指纹。performance.timeOrigin 每次新文档加载都会变，
     * 比 location.href 可靠 —— 同一地址重载时 href 不变，旧版正是靠 href 判定，
     * 结果 location.reload() 刚发出、旧文档还活着就被当成「已就绪」，
     * 接着在新旧页面之间来回 eval，最后整批静默结束（223737 日志实证）。
     */
    private static final String GEN_JS =
            "(function(){try{"
                    + "return String(performance.timeOrigin||0)+'|'+String(location.href||'')"
                    + "}catch(e){return ''}})()";

    /** 新页面上的券列表真的画出来了没有（不是「文档加载完」就算）。 */
    private static final String LIST_JS =
            "(function(){try{"
                    + "if(document.readyState!=='complete') return '0';"
                    + "var n=document.querySelectorAll('ul.cp-list button.cp-btn,ul.cp-list .btn-style').length;"
                    + "if(n>0) return '1';"
                    + "var t=(document.body&&document.body.innerText)||'';"
                    + "return t.indexOf('去使用')>=0?'1':'0';"
                    + "}catch(e){return '0'}})()";

    /**
     * v3.59：等「页面真的换了一代」。必须同时满足：
     * ① 代际指纹变了（新文档已接管）；② 新文档加载完；③ 券列表已经渲染。
     * 少任何一条都不算就绪 —— 这正是旧版刷新后没下文的原因。
     */
    private static boolean waitFresh(Host host, String gen0, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        // timeOrigin 拿不到的极端情况：退化成「等 1.5 秒再按页面就绪判定」，
        // 避免每张白等 40 秒。
        boolean weak = gen0 == null || gen0.isEmpty() || gen0.startsWith("0|");
        long weakStart = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            if (weak) {
                if (System.currentTimeMillis() - weakStart > 1_500L && "1".equals(host.eval(LIST_JS, 3_000L))) {
                    return true;
                }
                sleep(250L);
                continue;
            }
            String gen = host.eval(GEN_JS, 3_000L);
            if (gen != null && !gen.isEmpty() && !gen.equals(gen0)) {
                if ("1".equals(host.eval(LIST_JS, 3_000L))) {
                    return true;
                }
            }
            sleep(250L);
        }
        return false;
    }

    private static final class LiveHost implements Host {
        private final Object view;

        LiveHost(Object v) {
            view = v;
        }

        @Override
        public boolean ok() {
            return view != null;
        }

        @Override
        public String eval(String js, long timeoutMs) {
            return CouponPage.eval(view, js);
        }

        @Override
        public boolean reload() {
            String gen0 = eval(GEN_JS, 3_000L);
            try {
                CouponPage.eval(view, "(function(){try{location.href='" + URL
                        + "';return 1}catch(e){return 0}})()");
            } catch (Throwable e) {
                return false;
            }
            if (waitFresh(this, gen0, RELOAD_WAIT_MS)) {
                return true;
            }
            // 第二次：显式 replace，绕开「同地址 href 不触发导航」的浏览器优化。
            try {
                CouponPage.eval(view, "(function(){try{location.replace('" + URL
                        + "');return 1}catch(e){return 0}})()");
            } catch (Throwable e) {
                return false;
            }
            return waitFresh(this, gen0, 8_000L);
        }

        @Override
        public void close() {
            // 用户正在看的页面，绝不 destroy。
        }

        @Override
        public String name() {
            return "前台卡券页（App 自己开的，带完整登录桥）";
        }
    }

    private static final class OffHost implements Host {
        private final WebPage page;

        OffHost(WebPage p) {
            page = p;
        }

        @Override
        public boolean ok() {
            return page != null && page.raw() != null && page.ok();
        }

        @Override
        public String eval(String js, long timeoutMs) {
            return page.eval(js, timeoutMs);
        }

        @Override
        public boolean reload() {
            if (page == null) {
                return false;
            }
            String gen0 = eval(GEN_JS, 3_000L);
            try {
                page.load(URL);
            } catch (Throwable e) {
                return false;
            }
            return waitFresh(this, gen0, RELOAD_WAIT_MS);
        }

        @Override
        public void close() {
            page.close();
        }

        @Override
        public String name() {
            return "离屏卡券页（用户没打开卡券中心时兜底）";
        }
    }

    private static Host openHost() {
        Object live = CouponPage.get();
        HookLog.log("COUPON", "卡券页现场", CouponPage.status());
        if (live != null) {
            // 用页面自己的 location.href 复核，比 X5 的 getUrl() 可靠。
            String href = CouponPage.eval(live, "(function(){try{return location.href}catch(e){return ''}})()");
            if (href != null && href.contains("mycoupons")) {
                HookLog.log("COUPON", "卡券页复用 · 前台真页", "地址: " + href);
                return new LiveHost(live);
            }
            HookLog.log("COUPON", "卡券页复用 · 地址不符，弃用",
                    "location.href: " + String.valueOf(href));
            CouponPage.detach(live);
        }
        WebPage page = WebPage.open(URL, 30_000L, HOSTS);
        return page == null ? null : new OffHost(page);
    }

    // ------------------------------------------------------------------ 扫描

    static void scan() {
        if (busy) {
            sendList("{\"stage\":\"busy\",\"count\":0,\"items\":[]}");
            return;
        }
        busy = true;
        Host host = null;
        try {
            host = openHost();
            if (host == null || !host.ok()) {
                sendList("{\"stage\":\"no-page\",\"count\":0,\"items\":[]}");
                HookLog.log("COUPON", "卡券扫描 · 页面没打开",
                        "入口: " + URL + "\n前台卡券页和离屏页都不可用");
                return;
            }
            host.eval("window.__cmcc_coupon_front=" + (host instanceof LiveHost) + ";", 4_000L);
            host.eval(SCAN_SCRIPT, 8_000L);
            String raw = null;
            long deadline = System.currentTimeMillis() + SCAN_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                String value = host.eval(POLL_SCAN, 6_000L);
                if (value != null && !value.isEmpty()) {
                    raw = value;
                    break;
                }
                sleep(500L);
            }
            if (raw == null) {
                sendList("{\"stage\":\"timeout\",\"count\":0,\"items\":[]}");
                HookLog.log("COUPON", "卡券扫描 · 超时",
                        "45 秒没有回报\n现场: " + host.name() + "\n入口: " + URL);
                return;
            }
            sendList(raw);
            HookLog.log("COUPON", "卡券扫描 · " + countOf(raw) + " 张",
                    "现场: " + host.name() + "\n" + describe(raw));
        } catch (Throwable e) {
            sendList("{\"stage\":\"error\",\"count\":0,\"items\":[]}");
            HookLog.log("COUPON", "卡券扫描 · 异常", String.valueOf(e));
        } finally {
            if (host != null) {
                host.close();
            }
            busy = false;
        }
    }

    // ------------------------------------------------------------------ 批量使用

    static void use(String payload) {
        if (busy) {
            sendDone(0, 0, 0, "上一批还在跑，先等它结束");
            return;
        }
        JSONArray items;
        try {
            items = new JSONArray(payload == null ? "[]" : payload);
        } catch (Throwable e) {
            sendDone(0, 0, 0, "名单解析失败");
            return;
        }
        if (items.length() == 0) {
            sendDone(0, 0, 0, "没有勾选任何券");
            return;
        }
        busy = true;
        pendingCode = null;
        pendingCodeAt = 0L;
        Host host = null;
        int ok = 0;
        int fail = 0;
        final int total = items.length();
        StringBuilder notes = new StringBuilder();
        // v3.59：同名券去重。key = 券名+有效期+面额，value = 本批已核销成功的张数。
        // 刷新后如果那张券还留在列表里，findCard 会按 skip 跳过它，避免重复点同一张。
        Map<String, Integer> usedKeys = new HashMap<>();
        try {
            host = openHost();
            if (host == null || !host.ok()) {
                sendDone(0, 0, 0, "卡券页面没打开");
                HookLog.log("COUPON", "批量使用 · 页面不可用", "前台卡券页和离屏页都没起来");
                return;
            }
            HookLog.log("COUPON", "批量使用 · 开始 " + total + " 张",
                    "现场: " + host.name()
                            + "\n运行方式: 一张一页 —— 每张开始前重进卡券页，做完立刻重进下一张"
                            + "\n结果弹窗不关、不点遮罩、不改 DOM，直接靠刷新清掉"
                            + "\n进度由 Java 侧掌握，页面刷新不会丢队列");

            // v3.61：待复核队列。页面没回明确成败时先挂在这里，下一张重进后
            // 按「这张券还在不在可用列表」做最终裁决 —— 券没了就是真用掉了。
            JSONObject pendingItem = null;
            int pendingK = -1;
            String pendingName = "";
            int uncertain = 0;

            for (int k = 0; k < total; k++) {
                JSONObject item = items.optJSONObject(k);
                if (item == null) {
                    item = new JSONObject();
                }
                final String name = item.optString("name", "第" + (k + 1) + "张");
                String key = name + "|" + item.optString("valid", "") + "|"
                        + item.optString("amount", "");
                Integer done = usedKeys.get(key);
                if (done != null && done > 0) {
                    try {
                        item.put("skip", done);
                    } catch (Throwable ignored) {
                    }
                }
                sendProgress("item", k, name, ok, fail, total, "");
                codeFedThisItem = false;
                boolean fresh = host.reload();
                if (fresh) {
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 已重进卡券页",
                            name);
                    // 页面刚换成新的，正好用来给上一张做对账。
                    if (pendingItem != null) {
                        String pv = probe(host, pendingItem);
                        if ("gone".equals(pv)) {
                            ok++;
                            uncertain--;
                            HookLog.log("COUPON", "第 " + (pendingK + 1) + " 张 · 复核成功 · "
                                            + pendingName,
                                    "重进卡券页后这张券已经不在可用列表里 —— 上一轮其实核销成功了。\n"
                                            + "旧版只认「兑换成功」结果框，框没弹出来就记失败，"
                                            + "才会出现「日志全失败、券全用了」。");
                            sendProgress("ok", pendingK, pendingName, ok, fail, total, "复核：券已核销");
                            notes.append("⟳ ").append(pendingName).append(" · 复核成功（券已不在列表）\n");
                            pendingItem = null;
                            pendingK = -1;
                            pendingName = "";
                        } else if ("there".equals(pv)) {
                            fail++;
                            uncertain--;
                            HookLog.log("COUPON", "第 " + (pendingK + 1) + " 张 · 复核确认失败 · "
                                            + pendingName,
                                    "重进卡券页后这张券还躺在可用列表里，确实没核销掉。");
                            notes.append("✗ ").append(pendingName).append(" · 复核确认仍在列表\n");
                            pendingItem = null;
                            pendingK = -1;
                            pendingName = "";
                        } else {
                            HookLog.log("COUPON", "第 " + (pendingK + 1) + " 张 · 复核跳过",
                                    "券列表还没渲染出来，这一张留到后面再核");
                        }
                    }
                } else {
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 重进失败",
                            "12 秒内没换成新页面，原地硬着头皮做这一张"
                                    + "（可能被上一张的结果弹窗影响，做完照样刷新）");
                }
                int[] seq = {0};
                String started = injectUse(host)
                        ? host.eval("window.__cmcc_coupon_one(" + item + ");", 8_000L) : null;
                if (!"ok".equals(started)) {
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 脚本没装载",
                            "页面还在跳转或不是卡券页，本张跳过（不影响后面的）");
                    sendProgress("fail", k, name, ok, fail + 1, total, "脚本没装载");
                    fail++;
                    notes.append("✗ ").append(name).append(" · 脚本没装载\n");
                    continue;
                }
                JSONObject st = waitOne(host, k, total, name, seq);
                boolean good = st != null && st.optBoolean("itemOk", false);
                boolean unknown = st != null && st.optBoolean("itemUnknown", false);
                boolean noCard = st != null && st.optBoolean("noCard", false);
                String msg = st == null ? "页面没有回报结果" : st.optString("msg", "");

                if (noCard) {
                    // v3.61：列表里一张可用的白名单券都没有 = 前面的都核销完了，
                    // 这是「没券可做」，不是失败。别再往后刷 10 次空页。
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 没券可做",
                            "卡券列表里已经没有可用的白名单券\n"
                                    + "说明前面的券确实都核销掉了，剩下的不再执行");
                    notes.append("· 第 ").append(k + 1).append(" 张起列表已空，提前收尾\n");
                    break;
                }

                if (good) {
                    ok++;
                    Integer prev = usedKeys.get(key);
                    usedKeys.put(key, prev == null ? 1 : prev + 1);
                    notes.append("✓ ").append(name);
                    if (!msg.isEmpty()) {
                        notes.append(" · ").append(msg);
                    }
                    notes.append('\n');
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 成功 · " + name,
                            msg.isEmpty() ? "（页面没给文案）" : msg);
                    sendProgress("ok", k, name, ok, fail, total, msg);
                } else if (unknown) {
                    // 不写「失败」——没有明确失败证据，先挂待复核。
                    uncertain++;
                    pendingItem = item;
                    pendingK = k;
                    pendingName = name;
                    notes.append("⟳ ").append(name).append(" · 待复核");
                    if (!msg.isEmpty()) {
                        notes.append(" · ").append(msg);
                    }
                    notes.append('\n');
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 待复核 · " + name,
                            (msg.isEmpty() ? "页面没回明确文案" : msg)
                                    + "\n没有失败证据，先不记失败；下一张重进后按「券还在不在列表」定论");
                    sendProgress("pending", k, name, ok, fail, total, "待复核");
                } else {
                    fail++;
                    notes.append("✗ ").append(name);
                    if (!msg.isEmpty()) {
                        notes.append(" · ").append(msg);
                    }
                    notes.append('\n');
                    HookLog.log("COUPON", "第 " + (k + 1) + "/" + total + " 张 · 失败 · " + name,
                            msg.isEmpty() ? "（页面没给文案）" : msg);
                    sendProgress("fail", k, name, ok, fail, total, msg);
                }

                if (st != null && st.optBoolean("aborted", false)) {
                    HookLog.log("COUPON", "批量使用 · 中止",
                            "跳到非卡券页（充值/支付），剩下 " + (total - k - 1) + " 张不再执行");
                    break;
                }
            }

            // 最后一张留下的待复核：没有下一张了，重新进一次页面做完对账，
            // 只多花 2~3 秒，但能避免「最后一张明明用了却记失败」。
            if (pendingItem != null) {
                HookLog.log("COUPON", "收尾复核 · " + pendingName,
                        "最后一张没有下一张可比对，单独重进一次卡券页核对");
                if (host.reload()) {
                    String pv = probe(host, pendingItem);
                    if ("gone".equals(pv)) {
                        ok++;
                        uncertain--;
                        HookLog.log("COUPON", "第 " + (pendingK + 1) + " 张 · 复核成功 · " + pendingName,
                                "收尾重进后这张券已不在可用列表 —— 确实核销成功");
                        sendProgress("ok", pendingK, pendingName, ok, fail, total, "复核：券已核销");
                        notes.append("⟳ ").append(pendingName).append(" · 复核成功（券已不在列表）\n");
                    } else if ("there".equals(pv)) {
                        fail++;
                        uncertain--;
                        HookLog.log("COUPON", "第 " + (pendingK + 1) + " 张 · 复核确认失败 · " + pendingName,
                                "收尾重进后这张券还在列表里，确实没核销掉");
                        notes.append("✗ ").append(pendingName).append(" · 复核确认仍在列表\n");
                    } else {
                        HookLog.log("COUPON", "收尾复核 · 券列表没渲染",
                                "这一张维持「未确认」，按已提交计，不记失败");
                    }
                }
            }

            String tail = uncertain > 0
                    ? ("其中 " + uncertain + " 张没拿到明确回执，按已提交计，不计失败")
                    : "";
            sendDone(ok + Math.max(0, uncertain), fail, total, tail);
            HookLog.log("COUPON", "批量使用 · 成功 " + (ok + Math.max(0, uncertain))
                            + (fail > 0 ? " / 失败 " + fail : "")
                            + (uncertain > 0 ? " / 未确认 " + uncertain : "")
                            + " / 共 " + total + " 张",
                    notes.toString());
        } catch (Throwable e) {
            sendDone(0, 0, total, String.valueOf(e));
            HookLog.log("COUPON", "批量使用 · 异常", String.valueOf(e));
        } finally {
            if (host != null) {
                host.close();
            }
            busy = false;
        }
    }

    /**
     * v3.59：注入单张脚本并确认真的注入进去了。
     * 页面刚导航完时 eval 可能落在旧文档/空文档上，光看 eval 返回值不可靠，
     * 直接问一句 typeof —— 确认拿到了才往下走，避免白等一轮超时。
     */
    private static boolean injectUse(Host host) {
        for (int i = 0; i < 6; i++) {
            host.eval(USE_DEF, 8_000L);
            String t = host.eval("(function(){return typeof window.__cmcc_coupon_one})()", 3_000L);
            if (t != null && t.contains("function")) {
                return true;
            }
            sleep(400L);
        }
        return false;
    }

    /**
     * v3.61：复核一张「没拿到明确结果」的券 —— 只看它还在不在可用列表里。
     * 返回 gone（已核销）/ there（还在）/ unknown（页面没渲染出来）。
     *
     * 这是整套判定里最可靠的一条证据：结果弹窗的文案千变万化，还可能被
     * 上一张的残留盖住，但「券还在不在可用列表」是服务端数据，不会骗人。
     */
    private static String probe(Host host, JSONObject item) {
        try {
            String arg = JSONObject.quote(item == null ? "{}" : item.toString());
            String raw = host.eval("(function(){try{"
                    + "return window.__cmcc_coupon_probe(JSON.parse(" + arg + "))"
                    + "}catch(e){return ''}})()", 6_000L);
            if (raw == null || raw.isEmpty()) {
                return "unknown";
            }
            JSONObject o = new JSONObject(raw);
            if (o.optInt("ready", 0) != 1) {
                return "unknown";
            }
            return o.optInt("there", 1) == 0 ? "gone" : "there";
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /**
     * v3.59：一张券的等待循环。页面脚本只负责这一张，Java 负责送码、判超时、记账。
     * 页面刷新由 Java 在下一轮开头做，所以这里看到 item-done 就立刻返回 —— 不等关弹窗。
     */
    private static JSONObject waitOne(Host host, int k, int total, String name, int[] seqBox) {
        long deadline = System.currentTimeMillis() + ITEM_WAIT_MS;
        JSONObject last = null;
        boolean asked = false;
        int misses = 0;
        while (System.currentTimeMillis() < deadline) {
            String value = host.eval(POLL_USE, 6_000L);
            if (value == null || value.isEmpty() || "null".equals(value)) {
                if (++misses > 12) {
                    break;
                }
                sleep(300L);
                continue;
            }
            misses = 0;
            JSONObject st;
            try {
                st = new JSONObject(value);
            } catch (Throwable e) {
                sleep(300L);
                continue;
            }
            last = st;
            bridgeLines(st, seqBox);
            boolean need = st.optBoolean("needCode", false) && st.optBoolean("codeSent", false);
            if (need) {
                if (!asked) {
                    asked = true;
                    sendNeedCode(k, name, total);
                    HookLog.log("COUPON", "第 " + (k + 1) + " 张要验证码 · " + name,
                            "页面已确认发出短信验证码，等模块把码送回来。");
                }
                JSONObject fed = feedCode(host, k, name, st);
                if (fed != null) {
                    last = fed;
                }
                continue;
            }
            asked = false;
            String stage = st.optString("stage", "");
            if ("item-done".equals(stage) || "done".equals(stage)) {
                break;
            }
            sleep(300L);
        }
        return last;
    }

    /** 等本张券的短信码并灌进页面。返回期间读到的最新页面状态。 */
    private static JSONObject feedCode(Host host, int k, String name, JSONObject st) {
        long needStart = System.currentTimeMillis();
        long waitUntil = needStart + CODE_WAIT_MS;
        String code = null;
        int ticks = 0;
        JSONObject last = st;
        while (System.currentTimeMillis() < waitUntil) {
            String candidate = pendingCode;
            if (candidate != null) {
                pendingCode = null;
                long arrivedAt = pendingCodeAt;
                // v3.56：容差 15 秒，挡旧码又不误杀新码（短信时间来自短信中心）。
                if (!candidate.isEmpty() && arrivedAt >= needStart - 15_000L) {
                    code = candidate;
                    break;
                }
                long early = (needStart - arrivedAt) / 1000L;
                HookLog.log("COUPON", "丢弃过期验证码",
                        "第 " + (k + 1) + " 张 · 这条码的短信时间比本张开始等码早 "
                                + early + " 秒（容差 15 秒），不是本张券的");
            }
            if (++ticks % 5 == 0) {
                try {
                    String live = host.eval(POLL_USE, 4_000L);
                    if (live != null && !live.isEmpty() && !"null".equals(live)) {
                        JSONObject ls = new JSONObject(live);
                        last = ls;
                        if (!ls.optBoolean("needCode", false)) {
                            // v3.61：needCode 变 false 有两种原因 ——
                            // 一是本张压根没拿到码、脚本自己超时收尾；
                            // 二是码已经灌进页面、页面正在提交。
                            // 旧版一律打「页面已结束等码 + 没等到验证码」，
                            // 每张成功券都带两条假失败日志，纯属误导。
                            HookLog.log("COUPON", "第 " + (k + 1) + " 张 · "
                                            + (codeFedThisItem ? "码已交给页面，进入提交" : "页面已结束等码"),
                                    codeFedThisItem
                                            ? "页面已消费验证码，不再等第二遍"
                                            : "脚本超时或已自行收尾，Java 侧停止等待，避免串券。");
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            sleep(400L);
        }
        if (code != null && !code.isEmpty()) {
            host.eval("window.__cmcc_coupon_code=" + JSONObject.quote(code), 4_000L);
            codeFedThisItem = true;
            HookLog.log("COUPON", "第 " + (k + 1) + " 张 · 验证码已灌入页面",
                    "已写入 window.__cmcc_coupon_code\n页面随后会自动点「认证」(btn-style) 提交兑换");
        } else if (codeFedThisItem) {
            // 第二遍进来（页面已经拿到码、正在提交），这不是失败，别打「没等到验证码」。
            HookLog.log("COUPON", "第 " + (k + 1) + " 张 · 码已灌入，等页面提交",
                    "本张的码先前已经交给页面，这里只是页面还没消费完");
        } else {
            HookLog.log("COUPON", "第 " + (k + 1) + " 张没等到验证码",
                    "窗口内没有收到合格的新码，页面会判超时");
        }
        return last;
    }

    /** 把券脚本内部 line() 增量桥接到导出日志（每张新页面从 1 重新计数）。 */
    private static void bridgeLines(JSONObject st, int[] seqBox) {
        try {
            JSONArray jl = st.optJSONArray("lines");
            if (jl == null || jl.length() == 0) {
                return;
            }
            StringBuilder fresh = new StringBuilder();
            for (int li = 0; li < jl.length(); li++) {
                String one = jl.optString(li, "");
                int rb = one.indexOf(']');
                if (one.startsWith("[") && rb > 1) {
                    int seq;
                    try {
                        seq = Integer.parseInt(one.substring(1, rb));
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (seq <= seqBox[0]) {
                        continue;
                    }
                    seqBox[0] = seq;
                }
                if (fresh.length() > 0) {
                    fresh.append('\n');
                }
                fresh.append(one);
            }
            if (fresh.length() > 0) {
                HookLog.log("COUPON", "券脚本明细 · 逐张过程", fresh.toString());
            }
        } catch (Throwable ignored) {
        }
    }
    // ------------------------------------------------------------------ 广播

    private static void sendList(String json) {
        Intent intent = base(ACT_LIST);
        if (intent == null) {
            return;
        }
        intent.putExtra("json", json);
        send(intent);
    }

    private static void sendProgress(String stage, int idx, String name,
                                     int ok, int fail, int total, String msg) {
        Intent intent = base(ACT_PROGRESS);
        if (intent == null) {
            return;
        }
        intent.putExtra("stage", stage);
        intent.putExtra("idx", idx);
        intent.putExtra("name", name == null ? "" : name);
        intent.putExtra("ok", ok);
        intent.putExtra("fail", fail);
        intent.putExtra("total", total);
        intent.putExtra("msg", msg == null ? "" : msg);
        send(intent);
    }

    private static void sendNeedCode(int idx, String name, int total) {
        Intent intent = base(ACT_NEED_CODE);
        if (intent == null) {
            return;
        }
        intent.putExtra("idx", idx);
        intent.putExtra("name", name == null ? "" : name);
        intent.putExtra("total", total);
        send(intent);
    }

    private static void sendDone(int ok, int fail, int total, String msg) {
        Intent intent = base(ACT_DONE);
        if (intent == null) {
            return;
        }
        intent.putExtra("ok", ok);
        intent.putExtra("fail", fail);
        intent.putExtra("total", total);
        intent.putExtra("msg", msg == null ? "" : msg);
        send(intent);
    }

    private static Intent base(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(
                    HookLog.MODULE_PACKAGE, "com.cmcchook.LogReceiver"));
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            // v3.57：每条券广播都带上当前账号，模块侧按它落盘 —— 不依赖心跳先到，
            // 避免「刚扫完就切号」把券清单写进上一个账号的命名空间。
            intent.putExtra("uid", AccountScope.uid());
            intent.putExtra("phone", SsoLogin.maskedPhone());
            return intent;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void send(Intent intent) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            HookLog.logcat("coupon broadcast failed: " + e);
        }
    }

    // ------------------------------------------------------------------ 日志

    private static int countOf(String raw) {
        try {
            return new JSONObject(raw).optInt("count", 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String describe(String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("入口: ").append(URL).append('\n');
        try {
            JSONObject o = new JSONObject(raw);
            sb.append("阶段: ").append(o.optString("stage")).append('\n');
            sb.append("页面: ").append(o.optString("title")).append(" · ")
                    .append(o.optString("head")).append('\n');
            sb.append("识别: ").append(o.optInt("count")).append(" 张\n");
            JSONArray lines = o.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    sb.append(lines.optString(i)).append('\n');
                }
            }
            if (!"ok".equals(o.optString("stage"))) {
                sb.append("页面地址: ").append(o.optString("url")).append('\n');
            }
        } catch (Throwable e) {
            sb.append(brief(raw));
        }
        return sb.toString();
    }

    private static String describeUse(String raw) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray rs = o.optJSONArray("results");
            if (rs != null) {
                for (int i = 0; i < rs.length(); i++) {
                    JSONObject one = rs.optJSONObject(i);
                    if (one == null) {
                        continue;
                    }
                    sb.append(one.optBoolean("ok") ? "✓ " : "✗ ")
                            .append(one.optString("name"));
                    String msg = one.optString("msg", "");
                    if (!msg.isEmpty()) {
                        sb.append(" · ").append(msg);
                    }
                    sb.append('\n');
                }
            }
            JSONArray lines = o.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    sb.append(lines.optString(i)).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append(brief(raw));
        }
        return sb.toString();
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final String POLL_SCAN =
            "(function(){var v=window.__cmcc_coupon_scan;return v?String(v):'';})()";

    private static final String POLL_USE =
            "(function(){try{return JSON.stringify(window.__cmcc_coupon_use)}"
                    + "catch(e){return ''}})()";

    // ------------------------------------------------------------------ 页面脚本

    private static final String SCAN_SCRIPT = """
            (function(){
              var ID='__cmcc_coupon_scan';
              window[ID]='';
              var L=[];
              function line(s){ L.push(String(s)); if(L.length>160) L.shift() }
              function T(e){
                try{
                  var t=(e.textContent||'').replace(/\\s+/g,' ').trim();
                  if(!t) t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  return t;
                }catch(x){ return '' }
              }
              function V(e){
                try{
                  if(!e) return false;
                  var s=getComputedStyle(e);
                  if(s&&(s.display==='none'||s.visibility==='hidden')) return false;
                  return true;
                }catch(x){ return true }
              }
              function BT(){
                try{ return (document.body?document.body.textContent:'').replace(/\\s+/g,' ').trim() }
                catch(e){ return '' }
              }
              function DOCS(){
                var out=[document];
                try{
                  var fs=document.querySelectorAll('iframe');
                  for(var i=0;i<fs.length;i++){
                    try{ var d=fs[i].contentDocument; if(d&&d.body) out.push(d) }catch(x){}
                  }
                }catch(x){}
                return out;
              }
              // v3.41：只认「去使用」类按钮。上一版把领券中心的「哦 去领券」
              // 当成一张券扫了出来，其实那只是引导，券列表根本没渲染。
              var WORDS=/^(去使用|立即使用|马上使用|去兑换|立即兑换|去充值)$/;
              function inLead(e){
                var p=e;
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/vouchercent|cp_list_vouchercent|cp-oper-button|ka-tabs|type-tabs|cp-tabs/.test(c)) return true;
                }
                return false;
              }
              function anchors(d){
                var out=[],bs=d.querySelectorAll('button.cp-btn');
                for(var i=0;i<bs.length;i++){
                  var b=bs[i];
                  if(!V(b)) continue;
                  if(/cp-grays/.test(String(b.className||''))) continue;
                  if(inLead(b)) continue;
                  if(!WORDS.test(T(b))) continue;
                  out.push(b);
                }
                return out;
              }
              // 找不到真正的券卡片容器就丢掉，绝不拿按钮的父节点凑数。
              function cardOf(btn){
                var p=btn;
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/cp_lsit_tp_content|cp_lsit_tpbag_content|couponNoHasWire/.test(c)) return p;
                  if(/cp-list/.test(c)) return p;
                }
                return null;
              }
              function parse(t,btnText){
                var mt=t.match(/(流量券|话费券|权益券|语音券|短信券|会员券|商城券|观影券|代金券|话费加赠券|加赠券|专享券|优惠券)/);
                // 绝对有效期：有效期至2026.11.12 23:59
                var mv=t.match(/有效期至?\\s*([0-9]{4}[.\\-\\/][0-9]{1,2}[.\\-\\/][0-9]{1,2}(?:\\s*[0-9]{1,2}:[0-9]{2})?)/);
                var valid=mv?mv[1]:'';
                var expire=0;
                if(mv){
                  var dm=mv[1].match(/([0-9]{4})[.\\-\\/]([0-9]{1,2})[.\\-\\/]([0-9]{1,2})(?:\\s*([0-9]{1,2}):([0-9]{2}))?/);
                  if(dm){ try{ expire=new Date(+dm[1],+dm[2]-1,+dm[3],dm[4]?+dm[4]:23,dm[5]?+dm[5]:59,0).getTime() }catch(x){} }
                }
                // 相对有效期：剩 2天 9小时 / 剩 9小时 22分 / 剩 1天 9小时。
                // 页面上临近失效的券会显示倒计时而不是日期，转成「现在 + 剩余毫秒」
                // 的到期时间戳，就能和绝对日期放在同一把尺子上排序。
                var si=t.indexOf('剩');
                if(si>=0){
                  var seg=t.substring(si,si+24);
                  var dd=seg.match(/([0-9]+)\\s*天/),hh=seg.match(/([0-9]+)\\s*小时/),mm=seg.match(/([0-9]+)\\s*分/);
                  if(dd||hh||mm){
                    var leftMs=((dd?+dd[1]:0)*1440+(hh?+hh[1]:0)*60+(mm?+mm[1]:0))*60000;
                    if(!expire) expire=Date.now()+leftMs;
                    if(!mv){ var ps=[]; if(dd)ps.push(dd[1]+'天'); if(hh)ps.push(hh[1]+'小时'); if(mm)ps.push(mm[1]+'分'); valid='剩'+ps.join(''); }
                  }
                }
                var name=t;
                // 「剩 N天」是有效期不是券名，切掉，别混进名称里。
                var cut=name.search(/有效期|待生效|去使用|立即使用|马上使用|已使用|已失效|已过期|剩\\s*[0-9]/);
                if(cut>0) name=name.substring(0,cut);
                else if(mt&&name.indexOf(mt[1])>=0) name=name.substring(name.indexOf(mt[1])+mt[1].length);
                name=name.replace(/^[\\s:：]+/,'').replace(/[\\s:：]+$/,'').substring(0,64);
                var am='',ma=t.match(/￥\\s*([0-9]+(?:\\.[0-9]+)?)/);
                if(ma) am='￥'+ma[1];
                if(!am){
                  var mb=t.match(/([0-9]+(?:\\.[0-9]+)?\\s*(?:MB|GB|分钟|元|折|天))/);
                  if(mb) am=mb[1].replace(/\\s+/g,'');
                }
                var state=/待生效|未生效|尚未生效|未激活/.test(t)?'待生效':'可使用';
                return {type:mt?mt[1]:'',name:name,valid:valid,expire:expire,amount:am,
                        state:state,tag:'',raw:t.substring(0,240),btn:btnText};
              }
              function collect(){
                var ds=DOCS(),out=[],seen=[];
                for(var d=0;d<ds.length;d++){
                  var as=anchors(ds[d]);
                  for(var i=0;i<as.length;i++){
                    var btn=as[i],el=cardOf(btn);
                    if(!el||seen.indexOf(el)>=0) continue;
                    seen.push(el);
                    var t=T(el),bt=T(btn);
                    if(!t) continue;
                    var one=parse(t,bt);
                    if(!one.name&&!one.type) continue;
                    one.i=out.length; out.push(one);
                    try{
                      el.setAttribute('data-qc-i',String(one.i));
                      btn.setAttribute('data-qc-btn',String(one.i));
                    }catch(x){}
                  }
                }
                return out;
              }
              // 券列表由页面 Vue 的 couponQry()/refresh() 拉，日志里这条接口
              // 一次都没发，DOM 就只剩「领券中心」的引导。这里主动唤醒一次。
              function vueRoot(){
                var els=document.querySelectorAll('*');
                for(var i=0;i<els.length;i++){
                  var v=els[i].__vue__;
                  if(v&&v.$root) return v.$root;
                  if(v&&typeof v.couponQry==='function') return v;
                }
                return null;
              }
              function wake(){
                var acts=[];
                var r=vueRoot();
                if(r){
                  try{
                    if(typeof r.refresh==='function'){ r.refresh(); acts.push('refresh') }
                    else if(typeof r.couponQry==='function'){ r.couponQry(); acts.push('couponQry') }
                  }catch(e){ acts.push('Vue调用异常:'+e) }
                  line('唤醒: Vue '+(acts.join(',')||'没有 refresh/couponQry'));
                }else{
                  line('唤醒: 页面没找到 Vue 实例');
                }
                var tabs=document.querySelectorAll('.type-tabs li,#typeUl li,.ka-tabs li');
                for(var i=0;i<tabs.length;i++){
                  if(T(tabs[i]).indexOf('可使用')>=0){
                    try{ tabs[i].click(); line('唤醒: 点「可使用」tab') }catch(e){}
                    break;
                  }
                }
                return acts.join(',')||'none';
              }
              function diagnose(){
                line('诊断: 现场='+(window.__cmcc_coupon_front?'前台真页':'离屏页'));
                try{ line('诊断: href='+location.href.substring(0,140)) }catch(x){}
                var bt=BT();
                line('诊断: 正文长度='+bt.length);
                line('诊断: 正文前200='+bt.substring(0,200));
                line('诊断: ul.cp-list='+document.querySelectorAll('ul.cp-list').length
                     +' / button.cp-btn='+document.querySelectorAll('button.cp-btn').length
                     +' / cp_lsit_tp_content='+document.querySelectorAll('.cp_lsit_tp_content').length);
                var bs=document.querySelectorAll('button,.cp-btn'),words=[];
                for(var i=0;i<bs.length&&words.length<25;i++){
                  var t=T(bs[i]); if(t&&t.length<14) words.push(t);
                }
                line('诊断: 按钮文案='+words.join('|').substring(0,300));
                try{ line('诊断: bodyHTML前300='+document.body.innerHTML.substring(0,300)) }catch(x){}
              }
              var tries=0,woke=false;
              function loop(){
                tries++;
                if(!woke&&tries>=2){ woke=true; wake() }
                var cards=[];
                try{ cards=collect() }catch(e){ line('扫描异常: '+e) }
                var ready=document.querySelectorAll('ul.cp-list').length>0
                          ||document.querySelectorAll('button.cp-btn').length>0;
                if((cards.length>0&&ready)||tries>40){
                  diagnose();
                  for(var i=0;i<cards.length;i++){
                    var c=cards[i];
                    line('券'+c.i+': ['+(c.type||'券')+'] '+c.name
                         +' | '+c.valid+' | '+c.amount+' | '+c.state+' | 按钮='+c.btn);
                  }
                  var bt=BT(),hm=bt.match(/可使用\\s*\\(([0-9]+)\\)/);
                  window[ID]=JSON.stringify({
                    stage:cards.length>0?'ok':'empty',
                    head:hm?('可使用('+hm[1]+')'):'',
                    count:cards.length,items:cards,lines:L,
                    title:document.title||'',url:location.href
                  });
                  return;
                }
                setTimeout(loop,600);
              }
              setTimeout(loop,700);
            })();
            """;

    private static final String USE_DEF_A = """
            (function(){
              var ID='__cmcc_coupon_use';
              var S={stage:'idle',idx:-1,name:'',total:0,ok:0,fail:0,needCode:false,codeSent:false,msg:'',results:[],lines:[],lineSeq:0,prevBtn:null,itemOk:false,itemUnknown:false,noCard:false,aborted:false};
              window[ID]=S;
              window.__cmcc_coupon_code='';

              // v3.50：每条 line 带自增序号，Java 侧轮询时增量桥接到导出日志。
              // 旧版 S.lines 只在收尾 describeUse() 里打印，流程一卡住/中途导出就全黑盒，
              // 「点认证/关弹窗/为什么跳过」全靠猜。序号让 S.lines 滚动 shift 也不重不漏。
              function line(s){
                S.lineSeq++;
                S.lines.push('['+S.lineSeq+'] '+String(s));
                if(S.lines.length>150){ S.lines.shift() }
              }
              // 宿主进程里真正跑的脚本版本：日志出现这条 = LSPosed 已重载新 dex。
              // 日志头「模块版本」只是 App 侧 BuildConfig，不能证明注入的 dex 是新的。
              line('券脚本 v3.98 已装载 · 公开版(全新包名/全新UI/默认关仅记录10086·开详细原始/QQ群+免责声明)');
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              // 同 SCAN_SCRIPT：离屏页 innerText 为空、rect 全 0，只能靠 textContent + CSS 判定。
              function T(e){
                try{
                  var t=(e.textContent||'').replace(/\\s+/g,' ').trim();
                  if(!t) t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  return t;
                }catch(x){ return '' }
              }
              function V(e){
                try{
                  if(!e) return false;
                  var s=getComputedStyle(e);
                  if(s&&(s.display==='none'||s.visibility==='hidden')) return false;
                  return true;
                }catch(x){ return true }
              }
              // v3.47：前台真页里 Vue 用 v-show 隐藏的结果弹窗 DOM 会留在树上，
              // 元素自身 style 看不出隐藏，getBoundingClientRect 却是 0×0。
              // v3.46 只查 display/visibility，resultInfo() 就一直命中这段残留
              // 「兑换成功」文本，settle() 每张都「弹窗还在，再关一次」→
              // 「结果弹窗没确认关掉」（日志里 19 次）。只在弹窗判定里加尺寸过滤，
              // 不动全局 V()（券按钮扫描还要靠它，避免离屏/虚拟列表被误杀）。
              function frontPage(){
                try{
                  var r=document.documentElement.getBoundingClientRect();
                  return !!(r&&r.width>0&&r.height>0);
                }catch(x){ return false }
              }
              function V2(e){
                if(!V(e)) return false;
                if(frontPage()){
                  try{
                    var r=e.getBoundingClientRect();
                    if(r&&r.width<=0&&r.height<=0) return false;
                  }catch(x){}
                }
                return true;
              }
              function BT(d){
                d=d||document;
                try{ return (d.body?d.body.textContent:'').replace(/\\s+/g,' ').trim() }catch(e){ return '' }
              }
              function DOCS(){
                var out=[document];
                try{
                  var fs=document.querySelectorAll('iframe');
                  for(var i=0;i<fs.length;i++){
                    try{ var d=fs[i].contentDocument; if(d&&d.body) out.push(d) }catch(x){}
                  }
                }catch(x){}
                return out;
              }
              // v3.41：只认「去使用」类按钮，领券中心 / tab 区引导一律不算券。
              var WORDS=/^(去使用|立即使用|马上使用|去兑换|立即兑换|去充值)$/;
              function inLead(e){
                var p=e;
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/vouchercent|cp_list_vouchercent|cp-oper-button|ka-tabs|type-tabs|cp-tabs/.test(c)) return true;
                }
                return false;
              }
              function anchors(d){
                var out=[],bs=d.querySelectorAll('button.cp-btn');
                for(var i=0;i<bs.length;i++){
                  var b=bs[i];
                  if(!V(b)) continue;
                  if(/cp-grays/.test(String(b.className||''))) continue;
                  if(inLead(b)) continue;
                  if(!WORDS.test(T(b))) continue;
                  out.push(b);
                }
                return out;
              }
              function cardOf(btn){
                var p=btn;
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/cp_lsit_tp_content|cp_lsit_tpbag_content|couponNoHasWire/.test(c)) return p;
                  if(/cp-list/.test(c)) return p;
                }
                return null;
              }
              function parseCard(el,btn){
                var t=T(el);
                var mt=t.match(/(流量券|话费券|权益券|语音券|短信券|会员券|商城券|观影券|代金券|话费加赠券|加赠券|专享券|优惠券)/);
                var mv=t.match(/有效期至?\\s*([0-9]{4}[.\\-\\/][0-9]{1,2}[.\\-\\/][0-9]{1,2}(?:\\s*[0-9]{1,2}:[0-9]{2})?)/);
                var valid=mv?mv[1]:'';
                var expire=0;
                if(mv){
                  var dm=mv[1].match(/([0-9]{4})[.\\-\\/]([0-9]{1,2})[.\\-\\/]([0-9]{1,2})(?:\\s*([0-9]{1,2}):([0-9]{2}))?/);
                  if(dm){ try{ expire=new Date(+dm[1],+dm[2]-1,+dm[3],dm[4]?+dm[4]:23,dm[5]?+dm[5]:59,0).getTime() }catch(x){} }
                }
                var si=t.indexOf('剩');
                if(si>=0){
                  var seg=t.substring(si,si+24);
                  var dd=seg.match(/([0-9]+)\\s*天/),hh=seg.match(/([0-9]+)\\s*小时/),mm=seg.match(/([0-9]+)\\s*分/);
                  if(dd||hh||mm){
                    if(!expire) expire=Date.now()+((dd?+dd[1]:0)*1440+(hh?+hh[1]:0)*60+(mm?+mm[1]:0))*60000;
                    if(!mv){ var ps=[]; if(dd)ps.push(dd[1]+'天'); if(hh)ps.push(hh[1]+'小时'); if(mm)ps.push(mm[1]+'分'); valid='剩'+ps.join(''); }
                  }
                }
                var name=t;
                var cut=name.search(/有效期|待生效|去查看|去使用|立即使用|已使用|已失效|已过期|剩\\s*[0-9]/);
                if(cut>0) name=name.substring(0,cut);
                else if(mt&&name.indexOf(mt[1])>=0) name=name.substring(name.indexOf(mt[1])+mt[1].length);
                name=name.replace(/^[\\s:：]+/,'').replace(/[\\s:：]+$/,'').substring(0,64);
                return {el:el,btn:btn,type:mt?mt[1]:'',name:name,valid:valid,expire:expire,text:t};
              }
              function collectCards(){
                var ds=DOCS(),out=[],els=[];
                for(var d=0;d<ds.length;d++){
                  var doc=ds[d],as=anchors(doc);
                  for(var i=0;i<as.length;i++){
                    var btn=as[i],el=cardOf(btn);
                    if(!el||els.indexOf(el)>=0) continue;
                    els.push(el);
                    var one=parseCard(el,btn);
                    out.push(one);
                  }
                }
                return out;
              }
              // v3.47：券列表每成功一张就 refresh()，扫描时打的 data-qc-btn
              // 序号会漂到别的券上。v3.46 的序号分支「找到就直接 return」，
              // 日志里出现「拒绝点击: 会员/权益券 美团出行省钱卡兑换券 /
              // 名单=流量券 幸运三日签-500MB流量日包券」，白名单一拦，
              // 那张合法流量券就被判失败用不掉（两轮各 4 张 / 2 张）。
              // 现在序号必须「名字相符 + 过白名单」才认，绝不再盲信序号。
              function normName(s){
                return String(s||'').replace(/[\\s（）()【】、，,。.:：;；\\-]/g,'');
              }
              function nameMatch(a,b){
                var x=normName(a),y=normName(b);
                if(!x||!y) return false;
                if(x===y) return true;
                var s=x.length<y.length?x:y,l=x.length<y.length?y:x;
                // 短的一方至少 5 个字，避免「券」「流量」这种泛匹配把不相干的券凑上。
                return s.length>=5&&l.indexOf(s)>=0;
              }
              function findCard(it){
                var cards=collectCards();
                var want=String(it&&it.name||'');
                if(it&&typeof it.i==='number'){
                  try{
                    var marked=document.querySelector('[data-qc-btn="'+it.i+'"]');
                    if(marked){
                      for(var k=0;k<cards.length;k++){
                        var c=cards[k];
                        if(c.btn!==marked) continue;
                        if(!nameMatch(c.name,want)){
                          line('序号 '+it.i+' 已漂移，实指「'+c.name+'」，与名单「'+want+'」不符');
                          break;
                        }
                        if(!cardAllowed(it,c)) break;
                        return c;
                      }
                    }
                  }catch(x){}
                }
                // 名字匹配：只在白名单券里找，绝不 loose 到不相干的券。
                // v3.59：it.skip = 这个「券名+有效期+面额」在本批里已经核销成功的张数。
                // 同名券（比如两张「流量券 100MB日流量券」）刷新后如果还留在列表里，
                // 不跳过就会把已经兑换掉的那张再点一次，第 2 张必然失败。
                // 两种页面都兼容：
                //   列表把用掉的删了 → 剩 1 张、skip=1 → 不满足 >skip，仍点这张（对）
                //   列表还留着只是变灰/没变 → 剩 2 张、skip=1 → 点第 2 张（对）
                var loose=null,cand=[];
                for(var i=0;i<cards.length;i++){
                  var c=cards[i];
                  if(!cardAllowed(it,c)) continue;
                  if(!nameMatch(c.name,want)) continue;
                  if(it.valid&&c.valid&&String(it.valid)!==String(c.valid)){ if(!loose) loose=c; continue }
                  cand.push(c);
                }
                var skip=(it&&typeof it.skip==='number')?it.skip:0;
                if(cand.length){
                  if(skip>0&&cand.length>skip){
                    line('同名券已核销 '+skip+' 张，跳过前 '+skip+' 张，点第 '+(skip+1)+' 张');
                    return cand[skip];
                  }
                  return cand[0];
                }
                return loose;
              }
              // 名单这张在当前列表找不到（已用掉 / 列表刷新 / 名字变体）时，
              // 退而取页面上第一张还没点过的白名单券，保证「一键全用」能跑完，
              // 不会因为一张券找不到就整批停摆。绝不碰满减/加赠/权益券。
              function pickCard(it){
                var c=findCard(it);
                if(c&&c.btn!==S.prevBtn) return c;
                var cards=collectCards();
                for(var i=0;i<cards.length;i++){
                  var x=cards[i];
                  if(x.btn===S.prevBtn) continue;
                  if(!cardAllowed(it,x)) continue;
                  line('名单这张没找到，改点当前列表第一张可用券: '+x.name);
                  return x;
                }
                return null;
              }

              // v3.46：Java 侧名单已经是白名单，但券列表每成功一张就刷新，
              // 扫描时打的 data-qc-btn 序号会失效，findCard 的 loose 名字兜底
              // 会点到「满减/加赠券」——这类券的「去使用」是跳充值页
              // (recharge.html / ￥48立即支付)，继续循环就会误点支付。
              // 点之前再硬校验一次：只允许流量券、话费兑换券，且必须与名单同名。
              var DENY=/满减|加赠|折扣|权益券|会员|天猫|美团|一嗨|出行|省钱卡|观影|代金|专享|满[0-9]+减|充值[0-9]+元及以上/;
              function cardAllowed(it,card){
                if(!card) return false;
                // 只认券名+类型：券卡片正文常带「充值1元及以上使用」等通用说明，
                // 把整段 text 纳入 DENY 会误杀合法流量券。
                var nm=String(card.name||''),tp=String(card.type||'');
                var all=nm+' '+tp;
                if(DENY.test(all)) return false;
                var flow=/流量/.test(all);
                var exch=/兑换券/.test(all)&&/话费/.test(all);
                if(!(flow||exch)) return false;
                // 名单本来就是「全部白名单券」，券列表刷新后序号会漂移；
                // 只要求实点的是白名单券即可，名字不强行一一对应（否则会误杀整批）。
                return true;
              }
              function here(){ try{ return String(location.href||'') }catch(x){ return '' } }
              function offPage(){
                var h=here();
                return h!==''&&h.indexOf('mycoupons')<0;
              }
              function abort(reason){
                line(reason);
                line('已中止整批，页面停在: '+here().slice(0,120));
                S.stage='done'; S.idx=-1; S.msg=reason; S.aborted=true;
                try{ history.back() }catch(x){}
              }

              function clickEl(e){
                if(!e) return false;
                // v3.48：日志里出现过两次「H5 点击 · 控件 HTML · 文本=整张券列表」，
                // 那是把 documentElement / 包裹整页的大 div 当成弹窗按钮点了，
                // 页面状态被点乱，紧接着就「验证码没发就显示兑换中」。
                try{
                  var tag=String(e.tagName||'').toUpperCase();
                  if(tag==='HTML'||tag==='BODY'||e===document.documentElement||e===document.body){
                    line('拒绝点击整页容器（避免点乱券页面）');
                    return false;
                  }
                }catch(x){}
                var b=e;
                try{ b=e.closest('button,a')||e }catch(x){}
                if(!b) return false;
                try{
                  var tt=T(b);
                  // v3.52：返回 false 让调用方立刻换候选。旧版点了被拒还继续
                  // await wait(500) 再判一次，203712 日志里一张券要空转 6 秒。
                  if(tt&&tt.length>60){ line('拒绝点击大容器（文本过长）'); return false }
                }catch(x){}
                try{ b.click(); return true }catch(x){}
                try{ e.click(); return true }catch(y){}
                return false;
              }
              function findAction(words,root){
                root=root||document;
                var all=root.querySelectorAll('button,a,div,span');
                var hit=null;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!V(e)) continue;
                  var t=T(e);
                  if(!t||t.length>10) continue;
                  for(var j=0;j<words.length;j++){
                    if(t===words[j]){ hit=e; break }
                  }
                }
                return hit;
              }
              function findModal(){
                var all=document.querySelectorAll('div,section,form');
                var best=null,bestLen=1e9;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!V2(e)) continue;
                  var t=T(e);
                  if(!t||t.length>420) continue;
                  // v3.48：券列表/筛选层文本里也会带「认证」「短信验证码」字样，
                  // 但它不是兑换弹窗。排除掉，否则 modalText() 会把整页当弹窗，
                  // 于是「验证码没发」就被误判成「已经要验证码/已经兑换中」。
                  if(/可使用\\s*\\(|综合排序|最近领取|最早领取|即将失效|卡券历史/.test(t)) continue;
                  if(!/获取验证码|输入验证码|短信验证码|确认兑换|立即兑换|确认使用|认证/.test(t)) continue;
                  if(t.length<bestLen){ best=e; bestLen=t.length }
                }
                return best;
              }
              // v3.48：判断「要不要验证码」只看当前兑换弹窗，不看整页文本。
              // 整页文本混着 24 张券的名字、隐藏残留弹窗、筛选层文字，用它会把
              // 没发码的券误判成已提交 —— 就是「验证码没发就显示兑换中」的根因。
              function modalText(){
                var mm=findModal();
                return mm?T(mm):'';
              }
              function findInput(root){
                root=root||document;
                var sel=['input[placeholder*=验证码]','input[type=tel]','input[type=number]','input[maxlength="6"]','input[maxlength="4"]','input[type=text]'];
                for(var i=0;i<sel.length;i++){
                  var list=root.querySelectorAll(sel[i]);
                  for(var j=0;j<list.length;j++){
                    if(V(list[j])) return list[j];
                  }
                }
                return null;
              }
              // 有些 H5 把 6 位验证码拆成 6 个 input(maxlength=1)。
              function sixCells(root){
                root=root||document;
                var all=root.querySelectorAll('input');
                var cells=[];
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!V(e)) continue;
                  var ml=e.getAttribute('maxlength');
                  if(ml==='1'||ml===1) cells.push(e);
                }
                return cells.length>=4?cells:[];
              }
              function setVal(el,v){
                try{
                  var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                  var d=Object.getOwnPropertyDescriptor(proto,'value');
                  d.set.call(el,v);
                  ['input','change','keyup','blur'].forEach(function(t){
                    try{ el.dispatchEvent(new Event(t,{bubbles:true})) }catch(x){}
                  });
                }catch(e){
                  try{
                    el.value=v;
                    el.dispatchEvent(new Event('input',{bubbles:true}));
                    el.dispatchEvent(new Event('change',{bubbles:true}));
                  }catch(x){}
                }
              }
              function fillCode(c,root){
                var cells=sixCells(root)||[];
                if(cells.length>=4){
                  for(var i=0;i<cells.length&&i<c.length;i++) setVal(cells[i],c.charAt(i));
                  return '格子 '+cells.length+' 个';
                }
                var inp=findInput(root)|| (root!==document?findInput(document):null);
                if(!inp) return '';
                setVal(inp,c);
                return '单框';
              }
              async function waitCode(ms){
                var end=Date.now()+ms;
                while(Date.now()<end){
                  var c=window.__cmcc_coupon_code;
                  if(c){ window.__cmcc_coupon_code=''; return String(c) }
                  await wait(400);
                }
                return '';
              }

              // v3.42：兑换成功后页面会弹「兑换成功」结果框，不点掉会一直盖住券列表，
              // 下一张券的按钮点不到、findCard 也会被遮挡 —— 这是上一版「成功 0」的根因。
              // 结果弹窗可能成功也可能失败，统一检测、点掉确认按钮后再进下一张。
              var WIN=/兑换成功|使用成功|领取成功|充值成功|办理成功|已到账|已兑换|兑换完成|成功兑换|恭喜/;
              // v3.53：券卡片自己的状态字，永远不是「结果弹窗文案」。
              var CARDSTATE=/^(已兑换|已使用|已领取|已失效|已过期|去使用|去查看|可使用|待生效|使用中)$/;
              // v3.54：「卡券兑换中」提示框的说明文案里写着「若兑换失败，系统会自动退还」，
              // 旧版 LOSE 正则命中这四个字，把「受理提示」当成了「失败结果弹窗」，
              // 于是每张券都白跑两轮 closeResultDialog + 两次 cleanExchangeUI。
              // 213554 日志 [13] 实证：结果弹窗 = 「卡券兑换中，预计24小时内到账…」。
              // v3.55：214516 日志 [13] 实证，这段受理文案每个字都可能带空格/换行，
              // 旧写法「预计[0-9]+小时」匹配不到「预计 24 小时内」。
              // 另外「卡券兑换中 / 兑换中....」本身就是过程态，绝不是结果，
              // 顺手一起排掉，避免任何变体再被当成结果弹窗。
              var NOTRESULT=/预计\s*[0-9]+\s*小时\s*(内)?\s*到账|请留意短信提醒|系统会自动退还|客服热线|正在为您兑换|请稍候|正在处理中|兑换中|提交中|处理中/;
              // v3.55：结果弹窗必须带「明确的成败词」。旧版 WIN 里塞了「已兑换」，
              // 券卡片、过程提示都能命中；这里改成只认真正的成败结论。
              var RESULT_STRICT=/兑换成功|使用成功|领取成功|充值成功|办理成功|已到账|兑换完成|成功兑换|恭喜|兑换失败|使用失败|领取失败|核销失败|处理失败|验证码错误|验证码不正确|验证码已失效|验证码失效|验证码位数不足|已抢光|库存不足|活动太火爆|很遗憾/;
              var LOSE=/兑换失败|使用失败|领取失败|核销失败|处理失败|验证码错误|验证码不正确|验证码输入错误|验证码已失效|验证码失效|验证码位数不足|非法篡改|活动太火爆|稍后再试|稍后重试|系统繁忙|库存不足|已抢光|GWT_LIMITED/;
              // v3.48：兑换中间态。btntext 会经历 认证 → 认证中.... → 兑换中.... → 结果弹窗。
              // 在这个态里点下一张券，就会「验证码还没发，页面已经显示兑换中」，整批错乱。
              // 中间态既不算成功也不算失败，必须等它走完，绝不在这时判定或点下一张。
              var PENDING=/认证中|兑换中|提交中|处理中|正在兑换|正在提交/;
              // v3.53：只认「按钮自己」的中间态，绝不把弹窗标题当按钮。
              // 210834 日志实证：点完「确定」后页面弹出「卡券兑换中」提示框，
              // 框标题被 pendingInfo 命中 → waitPending 死等 15s、settle 死等 20s，
              // 一共 41 秒才轮到第二张（用户感受就是「第二个卡住、中间特别慢」），
              // 而 resetPendingButton 改的是按钮 btntext，对这个标题毫无作用。
              function btnLike(e){
                var tg=''; try{ tg=String(e.tagName||'').toUpperCase() }catch(x){}
                if(tg==='BUTTON'||tg==='A'||tg==='INPUT') return true;
                var c=''; try{ c=String(e.className||'') }catch(x){}
                return /btn|button|submit/i.test(c);
              }
              function pendingInfo(){
                var ds=DOCS(),best=null,bl=1e9;
                for(var k=0;k<ds.length;k++){
                  var all=ds[k].querySelectorAll('div,span,p,button,a');
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!V2(e)) continue;
                    if(!btnLike(e)) continue;
                    var t=T(e);
                    if(!t||t.length>40) continue;
                    if(!PENDING.test(t)) continue;
                    if(t.length<bl){ best={el:e,text:t}; bl=t.length }
                  }
                }
                return best;
              }
              async function waitPending(tag,maxMs){
                var lim=maxMs||15000,end=Date.now()+lim,seen=false;
                while(Date.now()<end){
                  // v3.53：一旦弹出「卡券兑换中」提示框，就说明请求已经回来了。
                  // 此时按钮文案往往还停在「兑换中....」，再等只是白等到超时
                  // （210834 日志里「提交后: 等了 15s 仍停在兑换中间态」就是它）。
                  if(tipDialog()){ line(tag+': 出现提示弹窗，不再等按钮复位'); return false }
                  var p=pendingInfo();
                  if(!p) return false;
                  if(!seen){ line(tag+': 页面还在「'+p.text+'」，等它结束再继续'); seen=true }
                  await wait(500);
                }
                line(tag+': 等了 '+(lim/1000)+'s 仍停在兑换中间态，本张按未完成处理');
                if(!tipDialog()) line(tag+': 页面里没有可点的提示框按钮（弹窗可能在原生层）');
                return true;
              }
              // v3.61：成败口径。
              // 11-07 22:57 日志实证：第 8 张兑换后弹出
              // 「请关注到账情况，若兑换失败，系统将自动退还卡券」——
              // 这是「已受理、等系统到账」的成功提示，旧版因为句子里含「兑换失败」
              // 三个字直接判失败，可券其实已经核销掉了。用户看到的就是
              // 「日志失败，实际全用了」。
              // 口径拆成三层：
              //   1) 先把「若/如果…失败」「失败…自动退还」这类条件从句整段剪掉，
              //      剩下的才是页面对本张券的结论；
              //   2) 命中成功/受理词 → 成功；
              //   3) 命中真正的否定词 → 失败；
              //   4) 都不是（「兑换中···」「正在处理」）→ 没有结论，继续等。
              var ACCEPT_NOW=/请关注到账情况|到账情况|预计\s*[0-9]+\s*小时|已受理|受理成功|提交成功|申请已提交|申请成功|已提交/;
              var REAL_FAIL=/兑换失败|使用失败|领取失败|核销失败|处理失败|验证码(错误|不正确|输入错误|已?失效|位数不足)|非法篡改|活动太火爆|稍后再试|稍后重试|系统繁忙|库存不足|已抢光|GWT_LIMITED|很遗憾|不支持兑换|不能兑换|无法兑换|已过期/;
              function verdict(t){
                var s0=String(t||'');
                if(!s0) return null;
                // 条件从句整段剪掉：「若兑换失败，系统将自动退还卡券」只剩前六个字。
                var s=s0.replace(/(若|如果|如|万一|一旦)[^，。；,;]{0,8}失败[^，。；,;]{0,20}/g,'')
                          .replace(/[^，。；,;]{0,6}失败[^，。；,;]{0,20}(自动退还|退回|返还)/g,'')
                          .replace(/失败[^。；;]{0,30}(退还|退回|返还)/g,'');
                // v3.61：失败词优先于泛化的「成功」，避免「兑换失败，请重新尝试」里
                // 的「兑换」二字被人误当成功源。
                if(REAL_FAIL.test(s)) return 'fail';
                if(/兑换成功|使用成功|领取成功|充值成功|办理成功|核销成功|兑换完成|成功兑换|已到账|恭喜/.test(s)) return 'ok';
                if(ACCEPT_NOW.test(s)) return 'ok';
                return null;
              }

              function headCount(){
                var m=BT().match(/可使用\\s*\\(([0-9]+)\\)/);
                return m?parseInt(m[1],10):-1;
              }
              function resultInfo(){
                var ds=DOCS(),best=null,bl=1e9,fb=null,fl=1e9,H=vh();
                for(var k=0;k<ds.length;k++){
                  var d=ds[k];
                  var all=d.querySelectorAll('div,section,p,span,h1,h2,h3');
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    // v3.47：用 V2 排除 v-show 隐藏后 0×0 的残留节点。
                    // v3.46 只查 display，隐藏的「兑换成功」文本会一直命中，
                    // settle 就反复点容器、日志刷「弹窗还在，再关一次」。
                    if(!V2(e)) continue;
                    var t=T(e);
                    if(!t||t.length>120) continue;
                    if(!WIN.test(t)&&!LOSE.test(t)) continue;
                    // v3.55：必须含「明确的成败词」，光有「兑换」两个字的过程提示
                    // （例如标题「兑换中」）不算结果。
                    if(!RESULT_STRICT.test(t)) continue;
                    // v3.54：「卡券兑换中，预计24小时内到账…若兑换失败会自动退还」
                    // 是受理提示，不是结果。这类文案一律不参与结果判定。
                    if(NOTRESULT.test(t)) continue;
                    // v3.52：券卡片/列表页也会命中（「已兑换」状态、「兑换完成」说明），
                    // 203712 日志实证 resultInfo 命中的是整个 363x2951 的卡券页。
                    // 有列表特征、或自身高度超过屏高 90% 的一律不是结果弹窗。
                    // v3.54：90% → 75%。213554 日志里那个整页容器正好 363x712，
                    // 屏高 800 的 89%，刚好从 90% 的缝里漏了过去。
                    if(listLike(t)) continue;
                    var rr=null; try{ rr=e.getBoundingClientRect() }catch(x){}
                    if(rr&&rr.height>H*0.75) continue;
                    // v3.52：优先认「在浮层里」的命中。券卡片上的「已兑换」小标签
                    // 只有 3 个字，比真弹窗的「兑换成功」还短，旧版按「取最短文本」
                    // 会选它 → dialogBox 落到券卡片上 → 关弹窗全错。浮层命中优先，
                    // 没有浮层命中时才退回最短文本。
                    var inOverlay=false,p=e;
                    for(var q=0;q<8&&p;q++,p=p.parentElement){
                      if(p===document.body||p===document.documentElement) break;
                      if(isOverlay(p)){ inOverlay=true; break }
                    }
                    if(inOverlay){
                      if(t.length<bl){ best={doc:d,el:e,text:t}; bl=t.length }
                    }else if(!CARDSTATE.test(t)&&!inCouponList(e)&&t.length>=4&&t.length<fl){
                      // v3.53：券卡片上的「已兑换」只有 3 个字且必然在 LI/UL 里，
                      // 210834 日志 [8]「认证后已出现结果弹窗」就是它 —— 结果把整批
                      // 判定带偏，settle 之后 20 秒全耗在这个假弹窗上。
                      fb={doc:d,el:e,text:t}; fl=t.length;
                    }
                  }
                }
                return best||fb;
              }
              // v3.47：v3.46 只在 dialogRoot(info.el) 这个「小容器」里找按钮，
              // 真实页面弹窗容器 class 不叫 dialog/popup，结果返回文案元素的父节点，
              // 里面一个按钮都没有 —— 日志里每张券都「弹窗内没找到按钮，点了容器」
              // →「弹窗还在，再关一次」→「结果弹窗没确认关掉」（共 19 次），
              // 弹窗残留又遮挡下一张券的按钮，造成点错券 / 有时用不掉。
              // 改回「整页按词找按钮」，但用两道闸避免旧版误点筛选层「确定」：
              //   1) 词优先级：完成/我知道了/关闭 在前，确定/确认 排最后；
              //   2) 确定/确认 必须不在筛选浮层里、且不是整页大容器。
              // v3.52：删掉「查看」——203712 日志实证：券列表里每张券都有「去查看」
              // 链接，包含匹配时它们全部命中「查看」，被当成结果弹窗的关闭按钮，
              // 点又点不动（大容器被 clickEl 拒绝），于是一张券要空转 6 秒 × 2。
              var DISMISS=['完成','完成兑换','我知道了','知道了','关闭','返回','好的',
                           '继续','再兑一张','确认','确定'];
              // 券列表/筛选层的特征文本。真实结果弹窗里不会有「去使用」「有效期至」这些。
              function listLike(t){
                return /可使用\\s*\\(|卡券历史|综合排序|最近领取|最早领取|即将失效|筛选|有效期至|去使用|去查看|充值[0-9]+元/.test(String(t||''));
              }
              function vh(){
                try{ return window.innerHeight||document.documentElement.clientHeight||800 }catch(x){ return 800 }
              }
              function vw(){
                try{ return window.innerWidth||document.documentElement.clientWidth||375 }catch(x){ return 375 }
              }
              function isOverlay(e){
                var c=''; try{ c=String(e.className||'') }catch(x){}
                if(/dialog|popup|modal|mask|layer|toast|alert|confirm|pop-box|kq-pop|coupon-pop/i.test(c)) return true;
                var st=null; try{ st=getComputedStyle(e) }catch(x){}
                return !!(st&&(st.position==='fixed'||st.position==='absolute'));
              }
              // v3.52：从 resultInfo() 命中的文本节点往上找「浮层根」。
              // 203712 日志实证：dumpDialog 抓到的根是 363x2951 的整个卡券页容器，
              // 说明旧代码根本没有定位到弹窗，而是在整页里找关闭按钮 —— 这是
              // 「第二张卡住 + 两张之间特别慢」的真正原因。
              function dialogBox(el){
                if(!el) return null;
                var H=vh(),p=el,first=null;
                for(var i=0;i<8&&p;i++,p=p.parentElement){
                  if(p===document.body||p===document.documentElement) break;
                  var r=null; try{ r=p.getBoundingClientRect() }catch(x){}
                  if(r&&r.height>H*1.1) break;      // 超过屏高=整页容器，停止上溯
                  if(!first) first=p;
                  if(isOverlay(p)) return p;
                }
                return first||el;
              }
              function inFilterLayer(e){
                var p=e;
                for(var i=0;i<7&&p;i++,p=p.parentElement){
                  // v3.53：上溯到 body 就停。旧版会读 body 的整页文本，只要页面
                  // 里出现「筛选」两个字（券列表页必然有），任何按钮都被判成
                  // 「在筛选层里」—— 提示弹窗的「我知道了」因此永远点不到。
                  var tg=''; try{ tg=String(p.tagName||'').toUpperCase() }catch(x){}
                  if(tg==='BODY'||tg==='HTML') break;
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/cp-list|cp-btn|filter|screen|sort|type-tabs|ka-tabs/i.test(c)) return true;
                  var t=T(p);
                  if(t&&t.length<300&&/综合排序|最近领取|最早领取|即将失效|筛选/.test(t)) return true;
                }
                return false;
              }
              // v3.51：券列表里的元素（券名/「去使用」容器）不能被当成兑换弹窗的
              // 「确定」提交按钮。真实提交按钮 .btn-style 在兑换弹窗内，父链不会
              // 出现 cp-list / cp_lsit / coupon 列表容器。
              function inCouponList(e){
                var p=e;
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  var c=''; try{ c=String(p.className||'') }catch(x){}
                  if(/cp-list|cp_lsit|cp_lsit_tp|cp-item|cp-oper|coupon-list/i.test(c)) return true;
                  var tg=''; try{ tg=String(p.tagName||'').toUpperCase() }catch(x){}
                  if(tg==='UL'||tg==='LI') return true;
                }
                return false;
              }
              // 从结果文案元素往上收集若干个祖先，连同各文档一起作为搜索根。
              function candidateRoots(el){
                var out=[],p=el;
                for(var i=0;i<8&&p;i++,p=p.parentElement){
                  if(p===document.body||p===document.documentElement) break;
                  out.push(p);
                }
                var ds=DOCS();
                for(var k=0;k<ds.length;k++) out.push(ds[k]);
                return out;
              }
              // v3.51：结果弹窗刚弹出时可能在动画中（rect 还是 0×0），且「完成」按钮
              // 常常是 img/i/li 图标、或文案带后缀（「完成 >」「我知道了(3)」）。
              // 旧版 V2() + 文案严格相等 + 窄 selector 三条一起把候选全过滤光了，
              // 195417 日志 [12] 实证「一个候选都没有」→ 只能 display:none 硬隐藏。
              // 现在：宽松可见（只查自身 display/visibility，element.click 不看尺寸）、
              // 文案包含匹配、选择器放宽到图标元素。
              function dismissCandidates(info){
                var box=dialogBox(info.el);
                if(!box) return [];
                var out=[],seen=[],roots=[box];
                // 弹窗根可能只是「兑换成功」文本容器，把父级也纳入（最多 2 层），
                // 但绝不上溯到 body/document —— 那正是把券列表全当候选的原因。
                var up=box.parentElement;
                for(var u=0;u<2&&up;u++,up=up.parentElement){
                  if(up===document.body||up===document.documentElement) break;
                  var ur=null; try{ ur=up.getBoundingClientRect() }catch(x){}
                  if(ur&&ur.height>vh()*1.1) break;
                  roots.push(up);
                }
                for(var r=0;r<roots.length;r++){
                  var root=roots[r];
                  if(!root||!root.querySelectorAll) continue;
                  // v3.52：selector 要把 div 加回来 —— 真实按钮是 DIV.btn-style
                  // （日志 [8]「点认证: 确定 [btn-style]」）。风险由下面两条硬约束
                  // 兜住：文案 ≤12 字、尺寸 ≤260x90，券列表的整行容器都会被挡掉。
                  var all=root.querySelectorAll('button,a,div,span,li,i,img,p,input[type=button],input[type=submit]');
                  for(var i=0;i<all.length;i++){
                    var e=all[i]; if(!V(e)) continue;
                    var tg=''; try{ tg=String(e.tagName||'').toUpperCase() }catch(x){}
                    if(tg==='HTML'||tg==='BODY') continue;
                    var t=T(e);
                    // v3.52：真实按钮文案一定短。「套券 共1张 … 去查看 节节送好礼」这类
                    // 整行容器文本上千字，直接从源头排除（203712 日志里 12 次误点）。
                    if(!t||t.length>12) continue;
                    if(/去使用|立即使用|马上使用|去兑换|立即兑换|去充值|去查看|获取验证码|发送验证码|领券中心|券兑换|查看|充值|使用/.test(t)) continue;
                    var c=''; try{ c=String(e.className||'') }catch(x){}
                    var rank=-1;
                    for(var j=0;j<DISMISS.length;j++){
                      if(t.indexOf(DISMISS[j])>=0){ rank=j; break }
                    }
                    // 图标型关闭按钮：class 带 close，或文案就是 ×
                    if(rank<0&&(/close|guanbi|dismiss|btn-close/i.test(c)||/^[×✕xX]$/.test(t))) rank=DISMISS.length+1;
                    if(rank<0) continue;
                    if(rank<DISMISS.length&&(DISMISS[rank]==='确定'||DISMISS[rank]==='确认')&&inFilterLayer(e)) continue;
                    var rc=null; try{ rc=e.getBoundingClientRect() }catch(x){}
                    // v3.55：214516 日志 [15] 铁证 —— 真实「我知道了」是 302x59（外层）
                    // 和 273x44（内层 DIV.btnDigColorNew），旧上限 260x90 把这两个
                    // 全排掉了，日志才会写「结果弹窗关闭候选:（无按钮）」，
                    // 于是每张券都关不掉、白跑两轮。白名单文案放宽到屏宽 95%。
                    var wideWord=/^(完成|完成兑换|我知道了|知道了|关闭|关闭弹窗|返回|好的|继续|再兑一张)$/.test(t);
                    var maxW=wideWord?Math.round(vw()*0.95):260;
                    var maxH=wideWord?140:90;
                    if(rc&&(rc.width>maxW||rc.height>maxH)) continue;
                    if(seen.indexOf(e)>=0) continue;
                    seen.push(e); out.push({el:e,word:t,rank:rank});
                  }
                }
                out.sort(function(a,b){ return a.rank-b.rank });
                return out;
              }
              // v3.51：把结果弹窗祖先容器里的真实 DOM 打进白盒日志。
              // 前几版全靠猜「完成」按钮长什么样，猜错了就 display:none 硬隐藏，
              // 越修越坏。现在先看清再动手，这份日志下次能一次定位。
              function dumpDialog(info){
                // v3.52：根节点改用 dialogBox()。旧版找不到 class 含 dialog 的祖先就
                // 退回 document，203712 日志打印出来的是整页第一个 div（363x2951），
                // 完全看不到真实弹窗内部，诊断信息等于没用。
                var root=dialogBox(info.el),out=[];
                if(!root||!root.querySelectorAll) root=info.el;
                var all=root.querySelectorAll('button,a,div,span,li,i,img,p,h1,h2,h3');
                for(var i=0;i<all.length&&out.length<40;i++){
                  var e=all[i];
                  var t=T(e),cc='',tg='';
                  try{ cc=String(e.className||'') }catch(x){}
                  try{ tg=String(e.tagName||'') }catch(x){}
                  if(!t&&!cc) continue;
                  if(t.length>24) continue;
                  var wh='?'; try{ var rc=e.getBoundingClientRect(); if(rc) wh=Math.round(rc.width)+'x'+Math.round(rc.height) }catch(x){}
                  out.push(tg+(cc?('.'+cc.slice(0,24)):'')+'{"'+t.slice(0,20)+'"}['+wh+']'+(V(e)?'':'(hide)'));
                }
                var bc=''; try{ bc=String(root.className||'') }catch(x){}
                var bw='?'; try{ var br=root.getBoundingClientRect(); bw=Math.round(br.width)+'x'+Math.round(br.height) }catch(x){}
                line('结果弹窗DOM: 根='+String(root.tagName||'')+(bc?('.'+bc.slice(0,30)):'')+'['+bw+'] 内容: '+(out.join(' ')||'（空）'));
              }
              // v3.51 核心纠正：彻底删除 display:none 硬隐藏。
              // clickEl() 用的是 element.click()（JS 直接派发事件），不受任何遮罩遮挡，
              // 「结果弹窗挡住下一张券、必须隐藏」这个从 v3.42 沿用至今的前提是错的。
              // 隐藏反而把 Vue 兑换组件的 .btn-style 一起藏了，btntext 停在「兑换中···」
              // 不复位、V2() 又把它判 0×0 排除 → 195417 日志 [12]→[20]→[21] 整批卡死。
              // 现在只做：dump 真实 DOM + 点页面自己的关闭按钮（走它的 refresh 复位）；
              // 点不掉也绝不动 DOM，让页面自己完成复位。
            """;
    // v3.54：Java 单个字符串常量上限 65535 字节（UTF-8），券脚本已到 83KB，
    // 必须拆成两段在运行时拼。用 concat 而不是 +，避免 javac 常量折叠又合成一个大串。
    private static final String USE_DEF_B = """
              // v3.55：一个弹窗是不是真没了，必须同时满足「没有结果文案」+「没有提示框」。
              // v3.54 把受理提示从 resultInfo 里排掉后，光看 resultInfo 会一直返回 null，
              // closeResultDialog 第一行就误判「已关闭」，按钮一次都没点。
              function dialogGone(){ return !resultInfo()&&!tipDialog() }
              // v3.56：兑换成功后弹的「兑换成功」框只有「查询流量剩余 / 查询话费剩余」，
              // 没有任何关闭按钮。它不关，后面每张券点「去使用」都弹不出兑换框
              //（220046 日志 [59][63][67][71] 连续 4 张「点去使用后没出现兑换弹窗」）。
              // 这里按「点遮罩 → 复位 Vue 状态」两步关，绝不点引导按钮（会跳查询/充值页）。
              function tapRaw(e){
                if(!e) return false;
                try{
                  var tg=String(e.tagName||'').toUpperCase();
                  if(tg==='HTML'||tg==='BODY') return false;
                  e.click();
                  return true;
                }catch(x){ return false }
              }
              async function forceCloseResult(box){
                if(!box) return false;
                line('结果弹窗没有关闭按钮，试点遮罩 '+tagInfo(box));
                if(tapRaw(box)){
                  await wait(420);
                  if(dialogGone()){ line('点遮罩后结果弹窗已关闭'); return true }
                }
                // 遮罩没绑事件：直接复位弹窗组件的显示状态。只改布尔字段、只调关闭方法，
                // 不碰 DOM 文案，避免又出现「造假按钮」那类污染。
                var keys=['show','visible','isShow','showResult','resultShow','showSuccess',
                          'successShow','showDialog','dialogShow','showPop','popShow','showTip','isVisible'];
                var ms=['close','hide','onClose','handleClose','closeDialog','hideDialog','closeResult'];
                var p=box;
                for(var i=0;i<5&&p;i++,p=p.parentElement){
                  var vm=null;
                  try{ vm=vmOf(p) }catch(x){}
                  if(!vm) continue;
                  var hit=false;
                  var paths=[vm,vm.$data,vm._data,vm.setupState];
                  for(var a=0;a<paths.length;a++){
                    var o=paths[a]; if(!o) continue;
                    for(var k=0;k<keys.length;k++){
                      try{ if(o[keys[k]]===true){ o[keys[k]]=false; hit=true } }catch(x){}
                    }
                  }
                  for(var m=0;m<ms.length;m++){
                    try{ if(typeof vm[ms[m]]==='function'){ vm[ms[m]](); hit=true; break } }catch(x){}
                  }
                  try{ if(hit&&vm.$forceUpdate) vm.$forceUpdate() }catch(x){}
                  if(hit){
                    await wait(400);
                    if(dialogGone()){ line('复位 Vue 弹窗状态后已关闭'); return true }
                  }
                }
                line('结果弹窗仍然关不掉（页面没给关闭按钮，也不再强行改 DOM）');
                return false;
              }
              async function closeResultDialog(info){
                dumpDialog(info);
                var box=dialogBox(info.el);
                var cands=dismissCandidates(info);
                var words=[];
                for(var w=0;w<cands.length&&w<4;w++) words.push(cands[w].word);
                var ic=closeIcon(box);
                line('结果弹窗关闭候选: '+(words.join('|')||'（无按钮）')+(ic?' +关闭图标':''));
                for(var r=0;r<cands.length&&r<4;r++){
                  if(dialogGone()){ line('结果弹窗已关闭'); return true }
                  line('点关闭按钮: '+cands[r].word);
                  if(!clickEl(cands[r].el)){ line('该候选不可点，换下一个'); continue }
                  await wait(420);
                  if(dialogGone()){ line('结果弹窗已由页面按钮关闭'); return true }
                }
                if(ic){
                  line('点关闭图标');
                  if(clickEl(ic)){ await wait(420); if(dialogGone()){ line('结果弹窗已由关闭图标关闭'); return true } }
                }
                // v3.55：候选为空时别再直接放弃 —— 「结果弹窗」和「提示框」其实是
                // 同一个 Vue 弹窗的两层，dialogBox 拿到的外层里按钮可能被尺寸规则漏掉。
                // 这里直接用 tipDialog（它会优先返回 class 含 btn 的内层）再兜一次。
                if(!cands.length){
                  var tp=tipDialog();
                  if(tp){
                    line('候选为空，改用提示框按钮「'+T(tp.btn)+'」 '+tagInfo(tp.btn));
                    if(clickEl(tp.btn)){
                      await wait(380);
                      if(dialogGone()){ line('结果弹窗已由提示框按钮关闭'); return true }
                    }
                    var in2=innerBtn(tp.box,T(tp.btn));
                    if(in2&&in2!==tp.btn){
                      line('改点内层按钮「'+T(in2)+'」 '+tagInfo(in2));
                      if(clickEl(in2)){
                        await wait(380);
                        if(dialogGone()){ line('结果弹窗已由内层按钮关闭'); return true }
                      }
                    }
                  }
                }
                // v3.56：真正的「兑换成功」框（220046 日志）里只有一个
                // DIV.btnColorNew{"查询流量剩余"}[273x44]，没有任何关闭词。
                // 旧版到这里就放弃了，框一直挂着，后面 4 张券全弹不出兑换框。
                if(!cands.length&&!ic){
                  if(await forceCloseResult(box)) return true;
                }
                // v3.52：删掉「点弹窗容器」兜底 —— 点容器不会关弹窗，只会让
                // resultInfo() 继续命中，把 6 秒超时填满。element.click() 也不受
                // 遮挡影响，所以残留弹窗不阻塞下一张券。
                line('结果弹窗没有可点的关闭按钮（不隐藏 DOM，直接继续）');
                return false;
              }
              function closeIcon(root){
                // v3.52：root 为空就直接返回 —— 旧版 root||document 会在定位不到
                // 浮层时退回整页搜索，把券列表里的图标当关闭图标点。
                if(!root) return null;
                var all=root.querySelectorAll('div,span,i,a,img,button');
                for(var i=0;i<all.length;i++){
                  var e=all[i]; if(!V(e)) continue;
                  var c=''; try{ c=String(e.className||'') }catch(x){}
                  var t=T(e);
                  if(/close|Close|newClose|关闭|×|✕/.test(c)||/^[×✕xX]$/.test(t)) return e;
                }
                return null;
              }
              // ===== v3.53：专门收拾「卡券兑换中」提示框 =====
              // 用户实证：验证码确认后页面会弹一个「卡券兑换中」的提示框，框里只有
              // 「我知道了」和一个 ×，这个框不会自己消失，必须点掉才会显示真实结果
              // （或直接回列表、券状态变「已兑换」）。210834 日志里它既不是 WIN 也
              // 不是 LOSE，resultInfo() 找不到；倒被 pendingInfo() 当成「中间态」
              // 死等 15s+20s，第 1 张白白耗掉 41 秒，第 2 张接手时 Vue 还是脏的。
              // 这里换个方向：不去猜弹窗文案，而是从「我知道了/关闭/×」这些短按钮
              // 反查它所在的浮层 —— 只要浮层里带兑换类字样，就是提示框，直接点掉。
              var TIPBTN=/^(我知道了|知道了|我知道了！|关闭|关闭弹窗|好的|完成|确定|确认)$/;
              function tipBoxOf(btn){
                var p=btn,H=vh();
                for(var i=0;i<9&&p;i++,p=p.parentElement){
                  if(p===document.body||p===document.documentElement) break;
                  var r=null; try{ r=p.getBoundingClientRect() }catch(x){}
                  if(r&&r.height>H*1.05) break;      // 已经是整页容器，再往上没意义
                  if(r&&(r.width<60||r.height<30)) continue;
                  var t=T(p);
                  if(!t||t.length>300) continue;
                  if(listLike(t)) break;              // 进到券列表/筛选层，不是弹窗
                  if(!/兑换|领取|提交|处理中|认证|成功|失败|提示|确认/.test(t)) continue;
                  return p;
                }
                return null;
              }
              function tipDialog(){
                var ds=DOCS(),fb=null;
                for(var k=0;k<ds.length;k++){
                  var d=ds[k],all;
                  try{ all=d.querySelectorAll('a,button,div,span,i,em') }catch(x){ continue }
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!V2(e)) continue;
                    var t=T(e);
                    if(!t||t.length>6) continue;
                    var c=''; try{ c=String(e.className||'') }catch(x){}
                    var icon=/close|guanbi|dismiss|icon-close/i.test(c)||/^[×✕]$/.test(t);
                    if(!icon&&!TIPBTN.test(t)) continue;
                    if(inCouponList(e)||inFilterLayer(e)) continue;
                    var box=tipBoxOf(e);
                    if(!box) continue;
                    var bt=T(box);
                    // 关键护栏：带验证码输入框的是「兑换弹窗」，不是提示框。
                    // 它的提交按钮文案正好是「确定」，不排掉就会在用户还没填码时
                    // 被当成提示框按钮点掉，等于替用户提交了一次空验证码。
                    if(/获取验证码|输入验证码|短信验证码|请输入验证码|验证码/.test(bt)) continue;
                    try{ if(box.querySelector('input')) continue }catch(x){}
                    var hit={box:box,btn:e,text:bt};
                    // v3.54：必须点「真正绑了 @click 的那一层」。
                    // 213554 日志的 DOM 是：
                    //   DIV{"我知道了"}[302x59]                ← 外层容器，点了没反应
                    //     IMG.headIm
                    //     DIV.btnDigColorNew{"我知道了"}[273x44] ← 真按钮
                    // 旧版按 DOM 顺序先拿到外层容器，连点 6 次都没关掉，日志一直刷
                    // 「提示弹窗点掉了但还在（可能又弹了一次）」，第 2 张就卡在这。
                    if(icon||btnLike(e)) return hit;
                    if(!fb) fb=hit;
                  }
                }
                return fb;
              }
              var lastTipSig='';
              // v3.55：把「到底点到了哪一层」写进日志。214516 之前的所有日志
              // 都只写「点提示弹窗「我知道了」」，看不出点的是 302x59 的容器
              // 还是 273x44 的真按钮，白白多修两版。
              function tagInfo(e){
                if(!e) return '(null)';
                var tg='',cc='',wh='?';
                try{ tg=String(e.tagName||'') }catch(x){}
                try{ cc=String(e.className||'') }catch(x){}
                try{ var r=e.getBoundingClientRect(); wh=Math.round(r.width)+'x'+Math.round(r.height) }catch(x){}
                return tg+(cc?('.'+cc.slice(0,24)):'')+'['+wh+']';
              }
              function dumpTip(tip){
                // v3.54：同一个框只打一次 DOM。旧版每次 closeTipDialog 都全量打，
                // 一张券刷 3~4 遍同样的「提示弹窗DOM」，日志被这点内容淹没。
                var sig='';
                try{ sig=String(tip.box.className||'')+'|'+T(tip.box).slice(0,40) }catch(x){}
                if(sig&&sig===lastTipSig) return;
                lastTipSig=sig;
                var out=[],all;
                try{ all=tip.box.querySelectorAll('a,button,div,span,i,em,p,img') }catch(x){ all=[] }
                for(var i=0;i<all.length&&out.length<14;i++){
                  var e=all[i],t=T(e),cc='',tg='';
                  try{ cc=String(e.className||'') }catch(x){}
                  try{ tg=String(e.tagName||'').toUpperCase() }catch(x){}
                  if(!t&&!cc) continue;
                  if(t.length>24) continue;
                  var wh='?'; try{ var rc=e.getBoundingClientRect(); wh=Math.round(rc.width)+'x'+Math.round(rc.height) }catch(x){}
                  out.push(tg+(cc?('.'+cc.slice(0,22)):'')+'{"'+t.slice(0,18)+'"}['+wh+']');
                }
                var bc=''; try{ bc=String(tip.box.className||'') }catch(x){}
                var bw='?'; try{ var br=tip.box.getBoundingClientRect(); bw=Math.round(br.width)+'x'+Math.round(br.height) }catch(x){}
                line('提示弹窗DOM: 根='+String(tip.box.tagName||'')+(bc?('.'+bc.slice(0,28)):'')+'['+bw+'] 内容: '+(out.join(' ')||'（空）'));
              }
              // v3.54：同一个「我知道了」在 DOM 里有两层，外层是容器、内层才绑事件。
              // 找不到内层就等于点了个寂寞。
              function innerBtn(root,text){
                if(!root) return null;
                var all;
                try{ all=root.querySelectorAll('div,span,a,button,i,em') }catch(x){ return null }
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!V(e)) continue;
                  if(T(e)!==text) continue;
                  if(btnLike(e)) return e;
                }
                return null;
              }
              // v3.55：把框里所有同文案元素列出来（外层容器 + 内层真按钮 + 可能的多份副本）。
              function sameTextNodes(root,text){
                var out=[];
                if(!root) return out;
                var all; try{ all=root.querySelectorAll('div,span,a,button,i,em,p') }catch(x){ return out }
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!V(e)) continue;
                  if(T(e)!==text) continue;
                  out.push(e);
                }
                return out;
              }
              // 点掉提示框。返回 true = 框确实消失了。
              async function closeTipDialog(tag){
                var tip=tipDialog();
                if(!tip) return false;
                dumpTip(tip);
                var bt=T(tip.btn);
                // v3.55：日志必须写清点的是哪一层（tag/class/尺寸），
                // 否则「点了没关掉」永远只能靠猜。
                line((tag?tag+': ':'')+'点提示弹窗「'+bt+'」 '+tagInfo(tip.btn)+' · 框内: '+tip.text.slice(0,50));
                var ok=clickEl(tip.btn);
                if(!ok){
                  var ic=closeIcon(tip.box);
                  if(ic){ line((tag?tag+': ':'')+'主按钮不可点，改点关闭图标'); ok=clickEl(ic) }
                }
                if(!ok){ line((tag?tag+': ':'')+'提示弹窗没有可点的按钮'); return false }
                for(var i=0;i<4;i++){
                  await wait(260);
                  if(!tipDialog()){ line((tag?tag+': ':'')+'提示弹窗已关闭'); return true }
                }
                // v3.55：第一次没关掉就把框里「所有」同文案元素逐个点一遍。
                // 214516 的 DOM 是 外层DIV(302x59) > 内层DIV.btnDigColorNew(273x44)，
                // @click 到底绑在哪一层只能靠试。旧版只试内层一次，试错就放弃。
                var allSame=sameTextNodes(tip.box,bt);
                for(var k=0;k<allSame.length;k++){
                  var e2=allSame[k];
                  if(e2===tip.btn) continue;
                  line((tag?tag+': ':'')+'补点第 '+(k+1)+' 个「'+bt+'」 '+tagInfo(e2));
                  if(!clickEl(e2)) continue;
                  for(var j=0;j<3;j++){
                    await wait(220);
                    if(!tipDialog()){ line((tag?tag+': ':'')+'提示弹窗已关闭'); return true }
                  }
                }
                var ic2=closeIcon(tip.box);
                if(ic2&&ic2!==tip.btn){
                  line((tag?tag+': ':'')+'改点关闭图标 '+tagInfo(ic2));
                  if(clickEl(ic2)){
                    for(var m=0;m<3;m++){
                      await wait(220);
                      if(!tipDialog()){ line((tag?tag+': ':'')+'提示弹窗已关闭'); return true }
                    }
                  }
                }
                line((tag?tag+': ':'')+'提示弹窗点不掉（按钮可能绑在别的层）');
                return false;
              }
              // 提交（点「认证」/ 点确定）之后统一走这里：等结果弹窗 → 判定 → 点掉 → 回列表。
              // before 是点券前「可使用(N)」的数字；N 下降本身就是最硬的核销回执。
              async function settle(before){
                // v3.61：这段只做一件事 —— 读结果，别的一律不碰。
                // 11-07 22:56 日志实证：点「确定」后 5 秒，脚本去点了页面 BODY
                // （closeTipDialog 的「框内逐个补点」补到了 body），触发一次
                // mycoupons.html 跳转重载 —— 页面一重载，脚本状态全丢，
                // Java 只能判「页面没给文案」。所以提交之后：
                //   不点提示框、不点遮罩、不点按钮、不复位状态机、不改 DOM。
                // 结果迟迟不出来的，把 unknown 交给 Java 在下一张重进后复核。
                var lastTxt='';
                var limit=30;                       // 30 × 500ms = 15 秒
                for(var i=0;i<limit;i++){
                  // 1) 券数量下降是硬回执，够判成功。
                  if(before>0){
                    var n=headCount();
                    if(n>0&&n<before){
                      line('券数量 '+before+' → '+n+'（核销回执）');
                      return {ok:true,text:'券数量减少'};
                    }
                  }
                  // 2) 严格结果框（模板里带的那个）。
                  var info=resultInfo();
                  if(info){
                    var t=info.text.substring(0,90);
                    lastTxt=t;
                    var v=verdict(info.text);
                    if(v==='ok'){ line('结果弹窗: '+t); return {ok:true,text:t} }
                    if(v==='fail'){ line('结果弹窗: '+t); return {ok:false,text:t} }
                    // v===null：这是「兑换中···」之类过程态，继续等，别下结论。
                  }
                  // 3) 提示框：只读文案，绝不点它。
                  var tip=tipDialog();
                  if(tip&&tip.text){
                    var tt=tip.text.substring(0,90);
                    var vt=verdict(tip.text);
                    if(vt==='ok'){
                      line('提示「'+tt+'」· 已受理，按成功计');
                      return {ok:true,text:tt};
                    }
                    if(vt==='fail'){
                      line('提示「'+tt+'」· 明确失败');
                      return {ok:false,text:tt};
                    }
                    if(tt!==lastTxt){ line('提示框（过程态，继续等）: '+tt); lastTxt=tt }
                  }
                  await wait(500);
                }
                // 15 秒还没结论：不判死，标 unknown，由下一张重进后按「券还在不在」复核。
                line('结果: 15 秒内没有明确成败文案，标为待复核（下一张刷新后核对券是否还在）');
                return {ok:false,unknown:true,text:lastTxt};
              }

              function finish(ok,msg,unknown){
                S.results.push({name:S.name,ok:ok,msg:msg||''});
                if(ok){ S.ok++ }else{ S.fail++ }
                S.itemOk=ok; S.itemUnknown=!!unknown; S.msg=msg||''; S.stage='item-done';
                line((ok?'完成: ':unknown?'待复核: ':'失败: ')+S.name+(msg?(' / '+msg):''));
              }

              // v3.44：验证码按钮可能在 iframe 里、文案有变体、弹窗异步渲染，
              // 旧版 findAction 只认主文档里精确等于「获取验证码」且 <=10 字的元素，
              // 找不到也照样 needCode=true 空等 150 秒 —— 用户看到「没点上却进等待」。
              var CODE_WORDS=/获取验证码|发送验证码|免费获取|获取动态密码|获取短信验证码|点击获取|立即获取|获取校验码/;
              function findCodeButton(){
                var ds=DOCS();
                for(var k=0;k<ds.length;k++){
                  var all=ds[k].querySelectorAll('button,a,div,span,input[type=button],input[type=submit],li');
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!V(e)) continue;
                    var t=T(e);
                    if(!t||t.length>12) continue;
                    if(/[0-9]{1,3}s|[0-9]{1,3}秒|重新获取|已发送/.test(t)) continue;
                    if(CODE_WORDS.test(t)) return e;
                  }
                }
                return null;
              }
              // 短信真发出去了没有：按钮变倒计时 / 出现「已发送」提示。
              // 验证码输入框在点之前就存在，不能当发码证据。
              function codeSentEvidence(){
                // v3.48：优先看当前兑换弹窗，且用 V2 排除隐藏的残留「已发送」文本。
                // 旧版整页用 V()，上一张留下的隐藏倒计时会被当成这一张的发码回执，
                // 于是「验证码没真发出去就继续往下走」。
                var roots=[];
                var mm=findModal(); if(mm) roots.push(mm);
                var ds=DOCS(); for(var z=0;z<ds.length;z++) roots.push(ds[z]);
                for(var k=0;k<roots.length;k++){
                  var all=roots[k].querySelectorAll('button,a,div,span,p,li');
                  for(var i=0;i<all.length;i++){
                    var e=all[i]; if(!V2(e)) continue;
                    var t=T(e); if(!t||t.length>40) continue;
                    if(/重新获取|已发送|已下发|发送成功|验证码已发|短信已发|[0-9]{1,3} ?s后|[0-9]{1,3} ?秒后/.test(t)) return t;
                  }
                }
                return '';
              }
              function fillCodeAny(c){
                var ds=DOCS();
                for(var k=0;k<ds.length;k++){ var h=fillCode(c,ds[k]); if(h) return h }
                return '';
              }
              function findActionAny(words){
                var ds=DOCS();
                for(var k=0;k<ds.length;k++){ var h=findAction(words,ds[k]); if(h) return h }
                return null;
              }
              // 关掉验证码弹窗回到券列表，别让它挡住下一张券。
              function closeCodeDialog(){
                var ds=DOCS(),w=['取消','关闭','返回','×','✕','X'];
                for(var k=0;k<ds.length;k++){
                  var all=ds[k].querySelectorAll('button,a,div,span,i');
                  for(var i=0;i<all.length;i++){
                    var e=all[i]; if(!V(e)) continue;
                    var t=T(e); if(!t||t.length>4) continue;
                    for(var j=0;j<w.length;j++){ if(t===w[j]){ clickEl(e); return t } }
                  }
                }
                return '';
              }
              // v3.45：券兑换弹窗（stc-coupon.js）的确认按钮是
              //   <div class="btn-style" @click="validate(orderInfo)">{{btntext}}</div>
              // btntext 初值「认证」，点击后变「认证中....」，再走 valid()→saveOrder()。
              // 旧词表只有「确定/确认/立即兑换/提交」，永远匹配不到「认证」，
              // 于是验证码灌进去了也不点提交，券用不掉（用户反馈的原话）。
              // v3.49：中间态文案（认证中..../兑换中....）绝不能当可点提交按钮，
              // 那是上一张没关干净的脏状态，点它不会触发 validate()。
              var AUTH_WORDS=['认证',
                              '立即认证','去认证','确认验证','提交验证','立即验证','验证',
                              '认证并兑换','确定','确认','确认兑换','立即兑换','提交','确认使用'];
              function authTextOk(t){
                if(!t||t.length>10) return false;
                for(var i=0;i<AUTH_WORDS.length;i++){ if(t===AUTH_WORDS[i]) return true }
                return t.indexOf('认证')===0;
              }
              function isSendOrCard(e){
                var c=''; try{ c=String(e.className||'') }catch(x){}
                return /sendsmsBtn|cp-btn/.test(c);
              }
              function findAuthButton(){
                var ds=DOCS(),fb=null,byText=null,cands=[],pend=null;
                for(var k=0;k<ds.length;k++){
                  var all=ds[k].querySelectorAll('button,a,div,span,input[type=button],input[type=submit]');
                  for(var i=0;i<all.length;i++){
                    var e=all[i];
                    if(!V2(e)) continue;
                    var tg=''; try{ tg=String(e.tagName||'').toUpperCase() }catch(x){}
                    if(tg==='HTML'||tg==='BODY') continue;
                    var c=''; try{ c=String(e.className||'') }catch(x){}
                    var t=T(e);
                    if(t&&t.length<=10&&cands.length<20) cands.push(t);
                    if(isSendOrCard(e)) continue;
                    // v3.49：btntext 停在「兑换中..../认证中....」= 上一张弹窗没关干净，
                    // 这个按钮是禁用的，点它无效还假装提交成功，必须排除并上报。
                    if(PENDING.test(t)){ if(!pend) pend={el:e,text:t}; continue }
                    // 最硬的证据：Vue 模板写死的 .btn-style，不受 btntext 状态变化影响。
                    if(/btn-style/.test(c)&&!fb) fb=e;
                    if(/^(取消|关闭|返回|获取验证码|发送验证码)$/.test(t)) continue;
                    // v3.51：按文案兜底最容易误命中筛选层/券列表里的「确定」，
                    // 195417 日志 [20]「点认证: 确定 [按文案]」→ [21] 点了无反应就是它。
                    // 必须在兑换弹窗内（有验证码框/弹窗特征）、且不在筛选层/券列表里。
                    if(authTextOk(t)&&!byText&&!inFilterLayer(e)&&!inCouponList(e)){
                      byText=e;
                    }
                  }
                }
                return {el:fb||byText,cands:cands,byClass:!!fb,pending:pend};
              }
              // v3.50：把停在「兑换中..../认证中....」的提交按钮复位成可点。
              // 这是上一张结果弹窗没走完关闭闭环遗留的 Vue 脏状态，页面不会自己复位
              // （v3.49 干等 6 秒只会跳过这张券，194248 日志实证「还是点不了兑换」）。
              // 关键：validate(orderInfo) 会检查 btntext 防重复提交，光改 DOM 文本没用，
              // 必须把 Vue 实例里那个字符串 data 改回可点值；DOM 只作兜底。
              function vmOf(e){
                var p=e;
                for(var i=0;i<7&&p;i++,p=p.parentElement){
                  try{ if(p.__vue__) return p.__vue__ }catch(x){}
                  try{ if(p.__vueParentComponent) return p.__vueParentComponent.proxy||p.__vueParentComponent }catch(x){}
                }
                return null;
              }
              function setBtnText(vm,val){
                if(!vm) return false;
                var set=false;
                var paths=[vm,vm.$data,vm._data,vm.setupState,vm.proxyState,vm.ctx,vm.data];
                var keys=['btntext','btnText','buttonText','submitText','btnTxt','authText','btn'];
                for(var i=0;i<paths.length;i++){
                  var o=paths[i]; if(!o) continue;
                  for(var j=0;j<keys.length;j++){
                    try{
                      if(typeof o[keys[j]]==='string'&&PENDING.test(o[keys[j]])){ o[keys[j]]=val; set=true }
                    }catch(x){}
                  }
                  // 兜底：遍历自有属性，凡是短的 PENDING 文案都改回来
                  try{
                    for(var k in o){
                      try{ if(typeof o[k]==='string'&&PENDING.test(o[k])&&o[k].length<16){ o[k]=val; set=true } }catch(x){}
                    }
                  }catch(x){}
                }
                try{ if(vm.$forceUpdate) vm.$forceUpdate() }catch(x){}
                return set;
              }
              // v3.51：绝不改 DOM 文案造假按钮。195417 日志 [19][20][21] 实证：
              // 旧版把 btntext 停在「兑换中···」的脏按钮 textContent 改成「确定」，
              // 于是 findAuthButton 按文案误命中它 → 点下去 validate() 读的是 Vue
              // 响应式 btntext（仍是兑换中，防重复提交）→「点了提交按钮但页面无反应」。
              // 现在只复位 Vue 实例里的响应式 data，DOM 交给 Vue 自己重渲染；
              // 拿不到 Vue 实例就返回 null（宁可跳过这张券，也不点假按钮）。
              function resetPendingButton(pend){
                var e=pend&&pend.el; if(!e) return null;
                var vm=vmOf(e);
                var ok=setBtnText(vm,'确定');
                try{ e.removeAttribute('disabled') }catch(x){}
                try{ e.disabled=false }catch(x){}
                try{
                  var c=String(e.className||'');
                  e.className=c.replace(/gray|grays|grey|is-disabled|btn-disabled|no-click/gi,'').trim();
                }catch(x){}
                line('脏按钮「'+pend.text+'」'+(ok?'已复位 Vue btntext（DOM 由 Vue 重渲染）':'拿不到 Vue 实例，不造假按钮，本张跳过'));
                return ok?e:null;
              }
              // 只有「确实点上按钮 + 页面确认已发码」才置 needCode；
              // 找不到按钮或没确认发码，一律明确跳过这张券并关弹窗，绝不空等。
              async function askCode(){
                var g=null,sent='';
                for(var r=0;r<8;r++){
                  g=findCodeButton();
                  if(g){
                    line('点获取验证码'+(r?(' 第'+(r+1)+'次'):'')+': '+T(g));
                    clickEl(g);
                    for(var q=0;q<6;q++){
                      await wait(500);
                      sent=codeSentEvidence();
                      if(sent){ line('页面确认已发码: '+sent); break }
                    }
                    if(sent) break;
                    line('点了按钮但页面没出现发码回执，重试');
                  } else {
                    await wait(700);
                  }
                }
                if(!g){
                  line('始终没找到「获取验证码」按钮，跳过这张券（不进等码）');
                  var mm=findModal();
                  if(mm) line('弹窗文本: '+T(mm).slice(0,120));
                  closeCodeDialog();
                  return false;
                }
                if(!sent){
                  line('「获取验证码」点了但页面没确认发码，跳过这张券（不进等码）');
                  closeCodeDialog();
                  return false;
                }
                S.codeSent=true;
                S.stage='needcode'; S.needCode=true;
                var c=await waitCode(120000);
                if(!c){ S.needCode=false; S.codeSent=false; line('验证码等待超时'); closeCodeDialog(); return false }
                S.needCode=false; S.codeSent=false; S.stage='running';
                var how=fillCodeAny(c);
                if(!how){ line('没找到验证码输入框，跳过这张券'); closeCodeDialog(); return false }
                line('已填验证码 ('+how+')');
                await wait(450);
                var au=findAuthButton();
                // v3.51：这一张刚灌完码、还没点过提交，若 btntext 已是「兑换中....」
                // 就一定是上一张遗留的 Vue 脏状态。只复位 Vue 响应式 btntext，
                // DOM 由 Vue 自己重渲染成「确定」，再找干净的 .btn-style 来点。
                // 绝不改 DOM textContent 造假按钮（195417 日志 [19][20][21] 实证
                // 假按钮点了 validate() 会因 btntext 仍是兑换中而拒绝）。
                if(!au.el&&au.pending){
                  var fixed=resetPendingButton(au.pending);
                  if(fixed){
                    for(var q=0;q<8;q++){
                      await wait(400);
                      au=findAuthButton();
                      if(au.el&&!au.pending) break;
                    }
                    line('复位 Vue 后重新找提交按钮: '+(au.el?('找到 '+T(au.el)+(au.byClass?' [btn-style]':' [按文案]')):'仍未复位'));
                  } else {
                    // 拿不到 Vue 实例：不点假按钮，改清残留弹窗让页面 refresh 自己复位。
                    var stale=resultInfo();
                    if(stale){ await closeResultDialog(stale) }
                    for(var q2=0;q2<8;q2++){
                      await wait(500);
                      au=findAuthButton();
                      if(au.el&&!au.pending) break;
                    }
                    line('页面自行复位后重新找提交按钮: '+(au.el?('找到 '+T(au.el)):'仍未复位'));
                  }
                }
                // v3.48：Vue 灌码后按钮会异步重渲染，第一次没找到再等一拍重试。
                if(!au.el){ await wait(700); au=findAuthButton(); }
                // v3.51：仍有 pending 且没有可点按钮 = 组件脏着且救不回来，诚实跳过，
                // 绝不再「复位成假的确定然后强点」（那是点了也没反应的假成功）。
                if(!au.el){
                  line('没找到干净的「认证」按钮，跳过这张券（券未使用，不点假按钮）');
                  line('弹窗候选按钮: '+(au.cands.join(' | ')||'（无）'));
                  if(au.pending) line('提交按钮仍停在中间态: '+au.pending.text);
                  var mm=findModal(); if(mm) line('弹窗文本: '+T(mm).slice(0,140));
                  closeCodeDialog(); return false;
                }
                line('点认证: '+T(au.el)+(au.byClass?' [btn-style]':' [按文案]'));
                clickEl(au.el);
                // 等 Vue 真的接住这次点击：按钮变「认证中..../兑换中....」或结果弹窗出现。
                var taken=false;
                for(var z=0;z<10;z++){
                  await wait(500);
                  if(resultInfo()){ line('认证后已出现结果弹窗'); taken=true; break }
                  var tt=''; try{ tt=T(au.el) }catch(x){}
                  if(PENDING.test(tt)){ line('页面已进入提交中间态: '+tt); taken=true; break }
                }
                // v3.50：点了但页面毫无反应也要写清楚，下一份日志能一眼定位。
                if(!taken){
                  var tt2=''; try{ tt2=T(au.el) }catch(x){}
                  line('点了提交按钮但页面无反应（按钮现在文案: '+tt2+'），可能复位没生效');
                }
                return true;
              }

              // v3.49：检测兑换弹窗是否「脏」—— .btn-style 的 btntext 还停在
              // 「兑换中..../认证中....」。这是上一张没走完关闭闭环留下的，
              // 下一张券复用同一个 Vue 组件就会一打开显示「兑换中」且点不动。
              function dirtyExchange(){
                var ds=DOCS();
                for(var k=0;k<ds.length;k++){
                  var all=ds[k].querySelectorAll('.btn-style');
                  for(var i=0;i<all.length;i++){
                    var e=all[i]; if(!V2(e)) continue;
                    var t=T(e);
                    // v3.56：220046 日志 [43] 把整张券列表（几百字）当成了「脏按钮」，
                    // resetPendingButton 拿到的是页面根组件，把根组件的 btntext
                    // 改成「确定」—— 页面状态被点乱，后面 4 张全弹不出兑换框。
                    // 真按钮文案只有「兑换中···」5 个字，超过 16 字的一律不是。
                    if(!t||t.length>16) continue;
                    if(PENDING.test(t)) return {el:e,text:t};
                  }
                }
                return null;
              }
              // v3.51：把页面恢复到「干净券列表」。只点页面自己的按钮（完成/我知道了/
              // 取消/关闭/图标/遮罩）让 Vue 走关闭方法复位 btntext 并 refresh 券列表。
              // 绝不 display:none 隐藏 —— 195417 日志实证隐藏会把 .btn-style 一起藏掉、
              // btntext 永远停在「兑换中....」，下一张一打开就点不了兑换。
              async function cleanExchangeUI(tag,maxMs){
                // v3.52：默认超时 6s → 2.2s，最多 3 轮，永远返回 true。
                // 203712 日志实证：结果弹窗关不掉时这里空转 6 秒，紧接着
                // clearStale() 又空转 6 秒，两张券之间白等 12 秒以上 ——
                // 用户感受就是「第二张卡住、中间特别慢」。而 element.click()
                // 不受遮挡影响，残留弹窗根本不需要阻塞下一张券。
                var lim=maxMs||2200,end=Date.now()+lim,tries=0;
                // v3.53：「卡券兑换中」提示框先点掉 —— 它不会自己消失，留着就会
                // 让下一张券的「去使用」被盖住，用户手动点「我知道了」才能继续。
                if(tipDialog()) await closeTipDialog(tag);
                var d0=dirtyExchange();
                if(d0){ resetPendingButton(d0); await wait(250) }
                while(Date.now()<end&&tries<3){
                  tries++;
                  if(tipDialog()){
                    // v3.54：关不掉就别循环重试了，直接往下走。element.click()
                    // 不受遮挡影响，残留一个提示框不会阻塞下一张券。
                    var okTip=await closeTipDialog(tag);
                    await wait(250);
                    if(!okTip){ line(tag+': 提示框关不掉，直接继续（不阻塞下一张）'); return true }
                    continue;
                  }
                  var info=resultInfo();
                  if(info){
                    var cands=dismissCandidates(info);
                    if(cands.length){
                      line(tag+': 点掉结果弹窗「'+cands[0].word+'」');
                      if(!clickEl(cands[0].el)){ line(tag+': 该候选不可点，结束清理'); return true }
                      await wait(400);
                      if(dialogGone()){ line(tag+': 结果弹窗已关闭'); return true }
                      continue;
                    }
                    var ic=closeIcon(dialogBox(info.el));
                    if(ic){
                      line(tag+': 点关闭图标');
                      if(!clickEl(ic)) return true;
                      await wait(400);
                      if(dialogGone()){ line(tag+': 结果弹窗已关闭'); return true }
                      continue;
                    }
                    line(tag+': 结果弹窗没有可点的关闭按钮，跳过清理（不阻塞下一张）');
                    return true;
                  }
                  var d=dirtyExchange(),mm=findModal();
                  if(d||mm){
                    var cc=(mm?findAction(['取消','关闭','返回','×','✕'],mm):null)||findActionAny(['取消','关闭','返回']);
                    if(cc){
                      line(tag+': '+(d?('提交按钮停在「'+d.text+'」，'):'')+'点「'+T(cc)+'」关掉残留兑换弹窗');
                      clickEl(cc); await wait(450); continue;
                    }
                    if(d) resetPendingButton(d);
                    line(tag+': 没找到关闭按钮，复位 Vue btntext 后继续');
                    return true;
                  }
                  return true;
                }
                line(tag+': 清理结束（残留弹窗不阻塞下一张）');
                return true;
              }
              // v3.51：每张开券前把上一张遗留清干净。同样只点页面按钮，绝不隐藏 DOM。
              async function clearStale(){
                // v3.53：上一张留下的「卡券兑换中」提示框必须先点掉。
                // 210834 日志里它一直挂着，用户手动点「我知道了」才继续 —— 这就是
                // 「第二个做不了」。现在每张开券前先收拾干净这层。
                if(tipDialog()) await closeTipDialog('开券前');
                var d0=dirtyExchange();
                if(d0){ resetPendingButton(d0); await wait(250) }
                // v3.48：上一张还在真实提交中间态时绝不关弹窗、绝不点下一张。
                // v3.52：护栏从 15s 收紧到 4s —— resetPendingButton 已经把 Vue 的
                // btntext 复位，真中间态正常 1~3 秒内结束；15s 只会放大「卡住」感。
                await waitPending('开券前',4000);
                // v3.52：cleanExchangeUI 已包含「点按钮/图标 + 快速放弃」的完整清理，
                // 旧版后面还跟了一段 v3.47 遗留的 3 轮循环，用 candidateRoots 从
                // document 全局搜按钮 —— 那是第二段 6 秒空转，203712 日志里
                // 「开券前: 6s 内没能把兑换弹窗恢复到干净状态」就是它。整段删掉。
                await cleanExchangeUI('开券前');
              }
              // ---------- v3.59：一张一页，Java 持队列 ----------
              // 「兑换成功」是页面里的一整块结果页：没有关闭按钮，点遮罩也不关，
              // 残留下来下一张点「去使用」就弹不出兑换框。既然只有一个办法能清掉它
              // ——刷新，那就把刷新写进流程本身：
              //   每张开始前重进卡券页 → 注入脚本 → 只做这一张 → 立刻返回结果 → 下一张。
              // 队列、进度、成功失败全在 Java 侧，页面刷新不会丢状态；
              // 页面这边不存任何跨张数据，也就不存在 sessionStorage/恢复时序的坑。
              // 处理一张券。返回 false = 应该中止整批（跳去了充值/支付页）。
              async function one(it){
                it=it||{};
                if(offPage()){ abort('本张开始前已不在卡券页，自动中止'); return false }
                await clearStale();
                S.name=it.name||'这张券';
                S.stage='click'; S.needCode=false; S.codeSent=false; S.msg=''; S.itemOk=false;
                var card=pickCard(it);
                if(!card){
                  // v3.61：列表里一张可用的白名单券都没有 = 没券可做了，
                  // 不是失败。第 10 张「列表里没找到」其实是前 9 张全用完了。
                  S.noCard=true;
                  finish(false,'列表里没有可用的白名单券了');
                  return true;
                }
                // 资金安全硬校验：点之前绝不碰满减/加赠/折扣/权益券。
                if(!cardAllowed(it,card)){
                  line('拒绝点击（不在白名单）: '+card.name+' / 名单='+S.name);
                  finish(false,'命中非白名单券，已跳过，避免跳充值页');
                  return true;
                }
                var btn=card.btn;
                if(!btn){ finish(false,'没有操作按钮'); return true }
                S.prevBtn=btn;
                var before=headCount();
                line('点按钮: '+T(btn)+' / '+card.name+' / 名单='+S.name+' / 可使用='+before);
                clickEl(btn);
                // v3.54：兑换弹窗有时 1.5s 还没渲染出来，旧版只看一眼就判
                // 「点「去使用」后没出现兑换弹窗」。改成轮询，出现就立刻往下走。
                var mm=null;
                for(var q=0;q<4;q++){
                  await wait(600);
                  if(offPage()) break;
                  mm=findModal();
                  if(mm) break;
                }
                // v3.46：满减/加赠券的「去使用」会跳充值页，立刻停，绝不在支付页继续点。
                if(offPage()){ abort('点「去使用」后跳到了非卡券页（充值/支付页），自动中止整批'); return false }
                // v3.48：判定只看当前兑换弹窗，不用整页文本。整页文本混着 24 张券
                // 的名字和隐藏弹窗残留，是「验证码没发就显示兑换中」的根因。
                var t=mm?T(mm):'';
                var submitted=false;
                if(/获取验证码|输入验证码|短信验证码|认证/.test(t)){
                  submitted=await askCode();
                } else if(t){
                  var conf=findAction(['确认兑换','立即兑换','确认使用','立即充值','确认领取','认证'],mm)
                      ||findAction(['确定','确认','认证'],mm);
                  if(conf){
                    line('点确认: '+T(conf)); clickEl(conf); await wait(800);
                    if(/获取验证码|输入验证码|短信验证码|认证/.test(modalText())) submitted=await askCode();
                    else submitted=true;
                  } else {
                    // v3.48：弹窗在但没有确认按钮 = 没有提交证据，绝不假判「直充成功」。
                    line('弹窗里没找到确认按钮，不按直充处理（避免验证码没发就判定成功）');
                    submitted=false;
                  }
                } else {
                  // v3.56：上一张的结果框残留会让「去使用」弹不出兑换框。
                  // 先关掉它再重点一次，不直接判失败。
                  var staleRes=resultInfo();
                  if(staleRes){
                    line('没弹兑换框，但页面上还挂着结果弹窗，先关掉再重点一次');
                    await closeResultDialog(staleRes);
                    await wait(400);
                    if(offPage()){ abort('关结果弹窗后跳到了非卡券页，自动中止整批'); return false }
                    clickEl(btn);
                    for(var q3=0;q3<4;q3++){
                      await wait(600);
                      if(offPage()) break;
                      mm=findModal();
                      if(mm) break;
                    }
                    if(offPage()){ abort('重点「去使用」后跳到了非卡券页，自动中止整批'); return false }
                    t=mm?T(mm):'';
                    if(/获取验证码|输入验证码|短信验证码|认证/.test(t)){
                      submitted=await askCode();
                    }
                  }
                  if(!submitted){
                    // v3.48：连兑换弹窗都没打开（点空 / 被遮挡 / 直充券）。
                    // 只有「可使用(N)」当场下降才算直充成功，否则一律不提交。
                    await wait(1200);
                    var direct=headCount();
                    if(before>0&&direct>0&&direct<before){
                      line('没弹窗但券数量 '+before+' → '+direct+'，按直充成功处理');
                      finish(true,'直充成功（券数量减少）');
                      return true;
                    }
                    line('点「去使用」后没出现兑换弹窗，本张不提交');
                    submitted=false;
                  }
                }
                if(!submitted){
                  if(!pendingInfo()) closeCodeDialog();
                  finish(false,'没能提交兑换（验证码未确认发出 / 认证按钮没点上 / 弹窗没出现）');
                  return true;
                }
                // v3.61：提交之后绝不点任何弹窗。
                // 22:56 日志实证：closeTipDialog 的「框内逐个补点」会补到 BODY 上，
                // 直接触发 mycoupons.html 重载，页面一刷新脚本状态全丢。
                // 「卡券兑换中」这种提示框留着不管，下一张重进时随页面一起消失。
                var stuck=await waitPending('提交后',6000);
                if(stuck){
                  // v3.61：只记一笔，绝不去复位按钮 / 改 Vue 状态。
                  // 卡在「兑换中」说明请求还在路上，这时候动 DOM 只会打断它；
                  // 页面下一张会整体刷新，脏状态自己就没了。
                  line('提交后仍停在中间态，保持原样等结果（不动 DOM）');
                }
                var res=await settle(before);
                var good=res.ok;
                // 券按钮变灰 / 变「已使用」也是页面给的真实回执。
                try{
                  if(/cp-grays/.test(String(btn.className||''))||/已使用|已失效/.test(T(btn))) good=true;
                }catch(x){}
                if(good){
                  finish(true, res.text?res.text:'页面回执已确认');
                } else if(res.unknown){
                  // 没有明确失败，只是没等到文案 —— 交给 Java 下一张复核，
                  // 别在这时候写「失败」，那正是「日志失败、实际全用了」的来源。
                  finish(false, '页面没回明确结果，待下一张刷新后复核', true);
                } else {
                  finish(false, res.text?res.text:'页面明确报错');
                }
                return true;
              }

              // Java 调一次 = 只做这一张。做完把结果写进 S（itemOk / msg / stage）。
              // 不循环、不刷新、不存进度 —— 下一张由 Java 重新加载页面后再调。
              window.__cmcc_coupon_one=function(it){
                S.stage='start'; S.idx=0; S.needCode=false; S.codeSent=false;
                S.msg=''; S.itemOk=false; S.itemUnknown=false; S.noCard=false;
                S.aborted=false; S.results=[];
                (async function(){
                  try{
                    var r=await one(it||{});
                    if(r===false) return;   // abort() 已经写好 stage/msg/aborted
                    if(S.stage!=='item-done') finish(false,'脚本结束但没有结果');
                  }catch(e){
                    finish(false,'脚本异常: '+(e&&e.message?e.message:e));
                  }
                })();
                return 'ok';
              };

              // v3.61：复核探针。上一张没拿到明确结果时，Java 在下一张重进卡券页后
              // 调它一次 —— 只看「这张券还在不在可用列表里」。
              //   还在  → 上一张确实没核销掉
              //   不在了 → 上一张已经用掉了（不管结果框有没有弹出来）
              // ready=0 表示页面还没渲染出券列表，这种时候不下任何结论。
              window.__cmcc_coupon_probe=function(it){
                it=it||{};
                try{
                  var cards=collectCards();
                  if(!cards||!cards.length) return JSON.stringify({ready:0});
                  var want=String(it.name||''),there=false;
                  for(var i=0;i<cards.length;i++){
                    var c=cards[i];
                    if(!cardAllowed(it,c)) continue;
                    if(!nameMatch(c.name,want)) continue;
                    if(it.valid&&c.valid&&String(it.valid)!==String(c.valid)) continue;
                    there=true; break;
                  }
                  return JSON.stringify({ready:1,there:there?1:0});
                }catch(e){ return JSON.stringify({ready:0}) }
              };

            })();
            """;
    private static final String USE_DEF = USE_DEF_A.concat(USE_DEF_B);
}
