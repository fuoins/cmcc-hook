package com.cmcchook;

/**
 * v4.30.0：四态展示标记的唯一实现。
 *
 * <p>同一份日志里原来有三套口径，同一时刻能同时出现「收鹅蛋 ✗」和「收鹅蛋 OK」：
 * <ul>
 *   <li>文件头诊断摘要（{@link LogExporter}）读模块进程的广播状态，按 OK/FAIL + idle 画；</li>
 *   <li>一键汇总表（{@link TaskRunner#displayMark}）看结果文案；</li>
 *   <li>文件尾「今日」行（{@link TaskRunner#dayMark}）读目标进程的状态，且
 *       {@code task_idle_} 只写在模块进程、跨进程读不到，于是 idle 全被画成 ✓；
 *       没跑过的活动又因为兜底分支被画成 ○（无需操作）。</li>
 * </ul>
 *
 * <p>现在三处都调这里，只认「状态文本 + 当天是否成功过 + 失败次数」，
 * 不再依赖跨进程读不到的标记。文本里已经写明「还剩 / 没到账」的，一律不画 ✓。
 */
final class StatusMark {

    static final String DONE = "✓";
    static final String IDLE = "○";
    static final String NONE = "–";

    private StatusMark() {
    }

    /**
     * @param state     形如 {@code OK 签到成功} / {@code FAIL 登录失败}；null 或空表示今天没跑过
     * @param attempts  当天失败次数
     * @param doneToday 当天是否成功过（重跑失败时仍算达成）
     */
    static String of(String state, int attempts, boolean doneToday) {
        if (state == null || state.isEmpty()) {
            return doneToday ? DONE : NONE;
        }
        boolean ok = state.startsWith("OK");
        String text = ok ? state.substring(2).trim() : state;
        if (!ok) {
            if (doneToday) {
                return DONE;
            }
            return attempts >= 1 ? "✗" + attempts : "✗";
        }
        // 签到真的到账了，就别因为「任务 0 个」把它压成「无需操作」。
        if (text.contains("签到到账")) {
            return DONE;
        }
        if (isIdle(text)) {
            return IDLE;
        }
        if (isPartial(text)) {
            return NONE;
        }
        return DONE;
    }

    /** 今天本来就没得做：已做完、无次数、外域不代做。 */
    static boolean isIdle(String text) {
        return text.contains("今天已做完")
                || text.contains("今天没有可做的")
                || text.contains("无需重复")
                || text.contains("今天已经做过")
                || text.contains("已领完")
                || text.contains("没有抽奖次数")
                || text.contains("没有拆礼盒次数")
                || text.contains("外域落地页不代做");
    }

    /** 做了但没做全：还有剩、没到账、没确认。 */
    static boolean isPartial(String text) {
        boolean hasDraw = text.contains("抽 ") && !text.contains("抽 0 次");
        return text.contains("还剩")
                || text.contains("没到账")
                || text.contains("未确认")
                || text.contains("仍待做")
                || text.contains("没找到可做的按钮")
                || (text.contains("任务 0 个") && !hasDraw && !text.contains("外域"))
                || (text.contains("完成 0 个任务") && !hasDraw);
    }
}
