package com.cmcchook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * r19：全活动接入注册表 + 离线自验。
 *
 * <p>这一版不做「配置驱动执行器」的改造（那要动 TaskRunner 的分派骨架，风险大），
 * 先把最要紧的一步做掉：把「所有活动还没打通」这件事变成一份**看得见、能自测**
 * 的清单。清单合并了三处来源：
 * <ul>
 *   <li>本模块 {@link TaskRunner} 的 20 个 {@code CMD_*} 命令；</li>
 *   <li>参考面板 {@code rdtscx/cmcc-panel} 的 35 张活动卡（{@code config/tasks.json}）；</li>
 *   <li>上游 {@code yupaiLy/cmcc-auto-checkin} 整合版里内嵌的 4 个模块
 *       （{@code cmcc_extra / cmcc_wed / cmcc_seed / cmcc_gold}）暴露出的端点与
 *       component ID。</li>
 * </ul>
 *
 * <p>状态口径（与一键四态 {@code OK/FAIL/idle/skip} 无关，这里描述的是「接入程度」）：
 * <ul>
 *   <li>{@code WIRED}：模块里有对应命令与实现；</li>
 *   <li>{@code PARTIAL}：有实现但依赖真页 DOM / 判定仍需真机日志验证；</li>
 *   <li>{@code DEFERRED}：用户明确说过「这个活动先不做」（灵犀口令一组）；</li>
 *   <li>{@code MISSING}：清单里有、模块里没有，就是还没打通的那些。</li>
 * </ul>
 *
 * <p>注册表只读、编译进 APK，不下发、不联网；{@link #selfCheck()} 全部是纯函数，
 * 不依赖真机、不发请求，可在模块里直接跑（对应参考面板 {@code tests/selftest.py}
 * 的思路：未接入必须被明确报出来，而不是悄悄显示成功）。
 */
final class ActivityCatalog {

    private ActivityCatalog() {
    }

    private static final String WIRED = "WIRED";
    private static final String PARTIAL = "PARTIAL";
    private static final String DEFERRED = "DEFERRED";
    private static final String MISSING = "MISSING";

    /** TaskRunner 里真实存在的命令值，selfCheck 会拿它校验 WIRED 项不写错 cmd。 */
    private static final Set<String> CMDS = new HashSet<>();

    static {
        String[] all = {
                "all", "sign", "egg", "goose", "soccer", "tearbox", "saturday",
                "video", "daily7", "miao", "recharge", "wed", "seckill", "lucky3",
                "weekly", "signlottery", "quiz", "welfare", "flow", "catalog", "selftest"
        };
        for (String c : all) {
            CMDS.add(c);
        }
    }

    private static final class Item {
        final String id;
        final String name;
        final String group;
        final String state;
        final String cmd;
        final String via;

        Item(String id, String name, String group, String state, String cmd, String via) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.state = state;
            this.cmd = cmd;
            this.via = via;
        }
    }

    private static final List<Item> ITEMS = new ArrayList<>();
    private static final String[][] ROWS = {
            // ---- 任务中心 / 签到 ----
            {"sign_daily", "每日签到·任务中心", "任务中心", WIRED, "sign", "TaskApi + MarkTasks(mark31/task)"},
            {"sign_gift", "签到有礼·功能体验区", "任务中心", WIRED, "sign", "FastScripts 快路径 taskList→finishTask→复查"},
            {"full_run", "全量活动（一键全跑）", "任务中心", WIRED, "all", "TaskRunner.runAll + 四态汇总"},
            {"bean_exchange", "签到 AI 豆 → 话费券兑换", "任务中心", MISSING, "", "上游 cmcc_gold: getExchangeList/checkStock/exchange"},
            {"rank_lexiaoxiao", "冲榜乐消消", "任务中心", MISSING, "", "面板卡片，模块无入口"},
            {"lucky_three", "幸运三日签·签到+抽奖", "任务中心", WIRED, "lucky3", "CanvasTask(coc3 cmpp)"},
            {"ai_trial", "AI尝鲜专区·改版", "任务中心", MISSING, "", "面板卡片，模块无入口"},
            {"guandan", "全民掼蛋挑战赛", "任务中心", MISSING, "", "面板卡片，模块无入口"},
            {"video_day", "视频日活动", "任务中心", WIRED, "video", "DiyTask component 4EC96l0-9btuobajC9Yu"},
            {"game_center", "移动游戏中心", "任务中心", MISSING, "", "面板卡片，模块无入口"},
            {"game_day", "游戏日", "任务中心", MISSING, "", "面板卡片，模块无入口"},
            {"game666", "玩游戏抽话费（周任务）", "任务中心", PARTIAL, "diy", "DiyTask component 7M6vIq0-TFMj5T-ZGjks（走 diy: 入口）"},
            // ---- DIV / 网页 / 浏览 ----
            {"saturday", "周六游戏中心", "DIV", WIRED, "saturday", "DiyTask component 50HQAg0-9YaSW4St7MDT + clockIn"},
            {"tearbox", "撕盒/拆盲盒", "DIV", WIRED, "tearbox", "TearBoxTask(diyLottery)"},
            {"traffic_more", "流量越用越多", "DIV", MISSING, "", "面板卡片，模块无入口"},
            {"login_landing", "登录承接页（SSO 保活）", "DIV", WIRED, "sign", "SsoLogin + RealPage/WebSession（基础设施）"},
            {"browse_tasks", "浏览型任务自动完成", "网页", PARTIAL, "sign", "MarkTasks + DiyTask + r19 SeedTasks 分域覆盖，未统一"},
            // ---- 打卡 ----
            {"week7_checkin", "7 日打卡分百万", "打卡", PARTIAL, "daily7", "r18：canvas 页无按钮，无回执改判 skip"},
            {"weekly", "周周抽奖/周周领", "打卡", PARTIAL, "weekly", "r18：DOM 卡片 + 180 秒预算 + 逐轮日志"},
            // ---- 灵犀（用户明确先不做）----
            {"lingxi_welfare", "灵犀福利官", "灵犀", DEFERRED, "", "用户：这个活动先不做"},
            {"newbie_gift", "新人礼", "灵犀", DEFERRED, "", "用户：这个活动先不做"},
            {"kouling_redpack", "发口令 抢红包", "灵犀", DEFERRED, "", "用户：这个活动先不做"},
            {"lingxi_guandan", "中国移动 APP 掼蛋比赛", "灵犀", DEFERRED, "", "用户：这个活动先不做"},
            {"kouling_claim", "口令领取（识别→自动领）", "灵犀", DEFERRED, "", "用户：这个活动先不做；r13 LingxiGrab 已停用"},
            {"lingxi_words", "灵犀口令·取词+领取", "灵犀", DEFERRED, "", "用户：这个活动先不做；encryptData 明文拿不到"},
            {"lucky_wheel", "好礼大转盘", "灵犀", MISSING, "", "面板卡片，模块无入口"},
            // ---- 商城 / 周三充值日 ----
            {"ebean_shop", "E 豆小店", "商城", MISSING, "", "面板卡片，模块无入口"},
            {"mall_aibean", "商城 AI 豆 + 活动页", "商城", MISSING, "", "面板卡片，模块无入口"},
            {"wed_recharge", "周三充值日（拼图+秒杀）", "周三充值日", WIRED, "wed", "WedTask(hlwyxhdhub wedrecharge)"},
            {"recharge", "充值次数任务", "周三充值日", PARTIAL, "recharge", "r18：gained<=0 改判 skip"},
            // ---- 豆苗 / 爱购 ----
            {"seed_tasks", "AI豆苗·赚阳光值任务（直播/榜单/爆品）", "数据", WIRED, "miao", "r19 SeedTasks: getGrowthTaskList→visit/share→复查 isFinish"},
            {"seed_exchange", "AI豆苗养成记·限时兑", "数据", MISSING, "", "只做了任务，兑换未接入"},
            {"aigou_seckill", "移动AI你·爱购日秒杀", "数据", MISSING, "", "面板卡片，模块无入口"},
            {"aigou_free", "爱购请你喝·免费抢购", "数据", MISSING, "", "面板卡片，模块无入口"},
            {"holiday_seckill", "假期秒杀·抢话费券", "数据", WIRED, "seckill", "TaskRunner + 对时"},
            // ---- 数据 / 云盘 ----
            {"data_query", "数据查询（5 项，只读）", "数据", MISSING, "", "只读，不需要自动化；如需可加诊断命令"},
            {"rate_gift", "评价有礼", "数据", MISSING, "", "面板卡片，模块无入口"},
            {"yunpan_sign", "云盘签到", "云盘", MISSING, "", "脚本 scripts/caiyun/sign.py，模块无入口"},
            {"yunbei_tasks", "云朵任务·AI 豆领取", "云盘", MISSING, "", "脚本 scripts/caiyun/cloud_tasks.py，模块无入口"},
            // ---- 其它模块自有活动 ----
            {"goose", "鹅蛋任务（短链 SSO）", "网页", PARTIAL, "goose", "r16 认 ln139.cn/ssojump/dwzcode；doTask 参数页面加密生成，不能直接重放"},
            {"soccer", "球赛（邀请任务）", "网页", PARTIAL, "soccer", "r18 排除 build/handle 前缀，按 fetch/load 逐个试 + DOM 交叉验证"},
            {"egg", "收鹅蛋/投喂", "网页", WIRED, "egg", "EggTask"},
            {"signlottery", "签到抽奖", "打卡", WIRED, "signlottery", "WisdomTask"},
            {"quiz", "答题", "打卡", WIRED, "quiz", "WisdomTask"},
            {"flow", "流量任务", "DIV", WIRED, "flow", "FlowTask"},
            {"welfare_catalog", "福利专区清单/采集", "自检", WIRED, "welfare", "WelfareFeed/WelfareCollect/LiveProbe（诊断非活动）"},
            // ---- 自检 ----
            {"selfcheck", "环境自检", "自检", WIRED, "selftest", "r19 ActivityCatalog.selfCheck（离线，不发请求）"},
            {"catalog", "全活动接入清单", "自检", WIRED, "catalog", "r19 ActivityCatalog.report"},
    };

    static {
        for (String[] r : ROWS) {
            ITEMS.add(new Item(r[0], r[1], r[2], r[3], r[4], r[5]));
        }
    }

    private static int count(String state) {
        int n = 0;
        for (Item it : ITEMS) {
            if (state.equals(it.state)) {
                n++;
            }
        }
        return n;
    }

    /** 一行摘要，给一键汇总末尾追加用。 */
    static String summary() {
        return "已打通 " + count(WIRED) + " / 部分 " + count(PARTIAL)
                + " / 暂缓 " + count(DEFERRED) + " / 未接入 " + count(MISSING)
                + "（共 " + ITEMS.size() + " 项）";
    }

    /**
     * 全量报告。重点把「未接入」和「部分打通」展开，已打通的压缩成一行，
     * 免得日志被刷满 —— 用户要看的是缺口，不是成绩单。
     */
    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("中国移动 · 全活动接入清单（r19）\n");
        sb.append("来源: 模块 CMD 表 + cmcc-panel 35 卡 + cmcc-auto-checkin 内嵌 4 模块\n");
        sb.append("统计: ").append(summary()).append('\n');
        sb.append("口径: WIRED=模块有命令与实现 / PARTIAL=依赖真页DOM或待真机验证"
                + " / DEFERRED=用户明确先不做 / MISSING=清单里有但模块没接\n\n");

        appendGroup(sb, "【未接入】" + count(MISSING) + " 项 —— 这就是还没打通的", MISSING);
        appendGroup(sb, "【部分打通】" + count(PARTIAL) + " 项 —— 待真机日志验证", PARTIAL);
        appendGroup(sb, "【暂缓】" + count(DEFERRED) + " 项 —— 按你的要求不做", DEFERRED);

        sb.append("【已打通】").append(count(WIRED)).append(" 项: ");
        boolean first = true;
        for (Item it : ITEMS) {
            if (!WIRED.equals(it.state)) {
                continue;
            }
            if (!first) {
                sb.append("、");
            }
            first = false;
            sb.append(it.id).append('(').append(it.cmd).append(')');
        }
        sb.append("\n\n下一步建议: 先按「未接入」里收益高、且上游已给出服务端端点的"
                + "活动（bean_exchange / seed_exchange / yunpan_sign）补通道，"
                + "再把 WIRED 项抽成描述驱动的表，新活动下发一条 JSON 即可。\n");
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String title, String state) {
        sb.append(title).append('\n');
        for (Item it : ITEMS) {
            if (!state.equals(it.state)) {
                continue;
            }
            sb.append("  · ").append(it.name).append("  [").append(it.group).append("]\n");
            sb.append("      ").append(it.via).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 离线自验：不发请求、不依赖真机，全部是纯函数与注册表一致性检查。
     * 对应参考面板 {@code tests/selftest.py} 的「未接入必须报错而不是假成功」。
     */
    static String selfCheck() {
        List<String> fails = new ArrayList<>();
        int total = 0;

        total++;
        if (ITEMS.isEmpty()) {
            fails.add("注册表为空");
        }

        Set<String> ids = new HashSet<>();
        for (Item it : ITEMS) {
            total++;
            boolean ok = it.id != null && !it.id.isEmpty()
                    && it.name != null && !it.name.isEmpty()
                    && it.group != null && !it.group.isEmpty()
                    && (WIRED.equals(it.state) || PARTIAL.equals(it.state)
                    || DEFERRED.equals(it.state) || MISSING.equals(it.state));
            if (!ok) {
                fails.add("条目字段不完整/状态非法: " + it.id);
            }
            if (!ids.add(it.id)) {
                fails.add("活动 ID 重复: " + it.id);
            }
        }

        for (Item it : ITEMS) {
            total++;
            boolean wired = WIRED.equals(it.state) || PARTIAL.equals(it.state);
            if (wired && (it.cmd == null || it.cmd.isEmpty())) {
                fails.add("已接入但没写 cmd: " + it.id);
            }
            // diy: 是前缀命令，不在 CMDS 白名单里，单独放行
            if (wired && !it.cmd.isEmpty() && !"diy".equals(it.cmd) && !CMDS.contains(it.cmd)) {
                fails.add("cmd 不在 TaskRunner 命令表: " + it.id + " -> " + it.cmd);
            }
            if (MISSING.equals(it.state) && !it.via.isEmpty() && it.via.startsWith("已")) {
                fails.add("未接入却写了成功口径: " + it.id);
            }
        }

        total++;
        if (count(WIRED) + count(PARTIAL) + count(DEFERRED) + count(MISSING) != ITEMS.size()) {
            fails.add("状态计数与总数不一致");
        }

        // SeedTasks 纯函数：visitUrl 提取
        String[][] urls = {
                {"https://dev.coc.10086.cn/coc3/canvas/x/online/yidongaini?a=1", "yidongaini"},
                {"/online/remenbangdan", "remenbangdan"},
                {"https://x/online/jingxuanbaopin#foo", "jingxuanbaopin"},
                {"https://x/online/a/b?c=1", "a"},
                {"https://x/no-online-here", ""},
                {null, ""},
        };
        for (String[] u : urls) {
            total++;
            String got = SeedTasks.targetKey(u[0]);
            if (!u[1].equals(got)) {
                fails.add("targetKey(" + u[0] + ") = " + got + "，期望 " + u[1]);
            }
        }

        // SeedTasks 纯函数：任务类型分流（黑名单制）
        String[][] types = {
                {"1", "true", "visit"}, {"1", "false", "no-target"},
                {"2", "false", "share"}, {"4", "true", "visit"}, {"5", "true", "visit"},
                {"3", "true", "skip"}, {"6", "true", "skip"}, {"9", "true", "unknown"},
        };
        for (String[] t : types) {
            total++;
            String got = SeedTasks.actionFor(Integer.parseInt(t[0]), Boolean.parseBoolean(t[1]));
            if (!t[2].equals(got)) {
                fails.add("actionFor(" + t[0] + "," + t[1] + ") = " + got + "，期望 " + t[2]);
            }
        }

        StringBuilder sb = new StringBuilder();
        int pass = total - fails.size();
        sb.append(fails.isEmpty() ? "PASS " : "FAIL ").append(pass).append('/').append(total)
                .append("  ").append(summary()).append('\n');
        sb.append("构建: ").append(FlowTask.BUILD).append('\n');
        if (fails.isEmpty()) {
            sb.append("检查项: 注册表字段/ID唯一/状态枚举/cmd有效性/状态计数"
                    + "/visitUrl提取(6例)/任务类型分流(8例) 全部通过\n");
            sb.append("说明: 本自验不发任何网络请求，只验证「未接入会被如实列出」"
                    + "与服务端直报通道的纯逻辑；真页执行结果仍需真机日志确认。\n");
        } else {
            sb.append("失败项:\n");
            for (String f : fails) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        return sb.toString();
    }
}
