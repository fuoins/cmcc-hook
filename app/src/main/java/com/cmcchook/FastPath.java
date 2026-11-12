package com.cmcchook;

/**
 * r8 统一快路径调度：先试「页面上下文纯请求编排」，不过就原样回退旧链路。
 *
 * <p>设计红线（用户确认的「一次性全上 + 每条新路都带自动兜底」）：
 * <ul>
 *   <li>旧代码一行不删，快路径只在 {@code TaskRunner.runOne} 最前面抢跑一次；</li>
 *   <li>未注册 / 开关关 / 换票失败 / 页面没起来 / 脚本失败 / 验收不通过 / 超时 /
 *       任何异常 —— 一律返回 {@code null}，交给原来的真页链路照常执行；</li>
 *   <li>只有「脚本跑完且状态接口验收通过」才算命中，避免「报完成但服务端没记」。</li>
 * </ul>
 *
 * <p>所以某个活动的快路径在真机上不被服务端认，也不会漏做、不会失败，
 * 只是那一次慢一点。一次「全部跑一次」即可同时拿到「哪些走快路径成功、
 * 哪些回退了」以及密文域页面发出的真实请求序列（由 {@link RawCapture} 旁路录）。
 */
final class FastPath {

    private static final String KEY = "fast_path";
    /** 单个活动的快路径硬预算；到点收手，别拖住整轮。 */
    private static final long BUDGET_MS = 45_000L;

    private FastPath() {
    }

    /**
     * @return 命中返回结果；{@code null} 表示「交给旧链路」，调用方必须原样继续。
     */
    static TaskRunner.Result tryRun(String cmd) {
        if (cmd == null || !HookPrefs.flag(KEY, true)) {
            return null;
        }
        String script = FastScripts.scriptFor(cmd);
        if (script == null) {
            // 没注册脚本的活动：不抢跑，直接走原链路（开页仍由 beginSessionFast 提速）。
            return null;
        }
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        try {
            if (TaskRunner.CMD_SIGN.equals(cmd)) {
                return trySign(script, deadline);
            }
            return null;
        } catch (Throwable e) {
            HookLog.log("TASK", "快路径异常 · 回退真页链路 · " + TaskRunner.label(cmd),
                    "异常: " + e + "\n" + android.util.Log.getStackTraceString(e)
                            + "\n说明: 旧链路完整保留，这次按原来的方式执行。");
            return null;
        }
    }

    /**
     * 签到有礼：两步都过才算命中。
     *
     * <p>① 签到本体直接复用 {@link SignTask#SCRIPT}（已验证的声明式请求序列，
     * 含每日签到 / 累计奖励 / 组团 / 月签 / 补领），不重写、不漏项；
     * ② 功能体验区改用 {@link FastScripts#SIGN_BROWSE} 的纯请求阶梯，
     * 替掉原来「点去完成 → 跳落地页 → 停留 5 秒 × 9」的真页点击。
     */
    private static TaskRunner.Result trySign(String browseScript, long deadline) {
        StringBuilder detail = new StringBuilder();
        detail.append("▶ 快路径 · 页面上下文请求编排\n");

        String entry = TaskApi.signEntry();
        if (entry == null || entry.isEmpty()) {
            return null;    // 没入口交给旧链路去采
        }
        String warmed = TaskApi.warmUp(entry, detail);
        if (warmed == null || warmed.isEmpty()) {
            return null;    // 换不到票，旧链路会开页后再试一次
        }
        CookieStore.flushToWebView();
        if (System.currentTimeMillis() > deadline) {
            return null;
        }
        // 真页上下文，只等文档建立；不等 Vue 渲染、不做场景快照。
        if (!WebSession.beginFastLite("fast-sign", warmed, 12_000L)) {
            return null;    // 页面没判过，旧链路的 beginFast 原样兜底
        }

        TaskRunner.Result base = ScriptEngine.run(SignTask.SCRIPT, "签到有礼",
                warmed, detail, deadline);
        if (base == null || !base.ok || base.skip) {
            fallback("签到脚本", base, detail);
            return null;
        }
        TaskRunner.Result browse = ScriptEngine.run(browseScript, "功能体验区",
                warmed, detail, deadline);
        if (browse == null || !browse.ok || browse.skip) {
            fallback("功能体验区", browse, detail);
            return null;
        }

        String summary = "快路径命中 · 签到有礼（纯请求编排，未点页面、未逐个停留）";
        detail.append("验收: 状态接口复查通过，功能体验区待办归零\n");
        HookLog.log("TASK", summary, detail.toString());
        return TaskRunner.Result.ok(summary, detail.toString(), summary);
    }

    private static void fallback(String stage, TaskRunner.Result r, StringBuilder detail) {
        String why = r == null ? "没有结果" : r.summary;
        HookLog.log("TASK", "快路径未过 · 回退真页链路 · " + stage,
                "原因: " + why + "\n"
                        + "说明: 旧链路一行没删，这次按原来的真页方式完整执行，"
                        + "不会漏做、不记失败。\n\n" + detail);
    }
}
