package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 签到页「去完成」类任务的接口编排（活动 1021122301 的 mark/task 体系）。
 *
 * <p>为什么不是「借页面现场点按钮」：v3.16 实测离屏页停在「加载中」
 * （html=5302 / cards=0），uni-app 根本没渲染，点不到任何按钮；而用户手工
 * 点一遍要 22 分钟（相邻完成上报中位间隔 21.6 秒，全耗在跳落地页 + 停留 +
 * 回来找按钮）。参考 Python 脚本 cmcc_extra.run_mark_tasks 的实测：这些任务
 * 绝大多数可以直接发接口完成，不必打开页面、不必点 DOM、不必逐个停留。
 *
 * <p>但「一切以真实软件为准」，不照搬 Python 的投机假设：
 * <ul>
 *   <li>请求一律在活动页上下文里发（{@link TaskApi#post} → {@link WebFetch}），
 *       Referer 用带 token 的活动页地址，和真实页面 JS 发 finishTask 时完全一致；</li>
 *   <li>完成上报只发 {@code {"taskId","taskType"}}，不伪造 sign/random。
 *       taskType 取自 {@code taskInfo}（taskList 里是另一套枚举，实测 3459
 *       在 taskList 是 3、taskInfo/finishTask 是 2）；</li>
 *   <li>签名到底要不要，交给服务端当场回答：SUCCESS 就领奖并记「接口完成」；
 *       返回错误就如实记 code/msg、标「需页面现场」并跳过，不重试、不硬试、不猜参数。</li>
 * </ul>
 *
 * <p>任务分类同样来自实证：
 * <ul>
 *   <li>status=1 已完成待领 → 直接 {@code getTaskAward} 补领；</li>
 *   <li>status=2 首礼已领、awardType=2 且 secondStatus=0 → {@code getTaskSecondAward}；</li>
 *   <li>status=0 待办 → 外部合作区整区跳过（合作方记账，需真开第三方 App），
 *       真消费/邀好友/助力/公众号跳过，其余逐个发完成上报；</li>
 *   <li>taskInfo 带 taskToken 且服务端明确提示「特殊处理/openFinish」时，才走
 *       开放平台握手 {@code getOneTaskInfo → openFinish}（16:26:54 真实链路）。
 *       openFinish 在停留不足时会返回 code=SUCCESS 但 data=false，必须校验 data。</li>
 * </ul>
 *
 * <p>会话跨任务复用同一个 referer / 页面上下文，相邻请求只隔 200~500ms；
 * 停留只在握手回退里按服务端下发的 scanTime 付，主路径不盲等。
 */
final class MarkTasks {

    private static final String P_LIST = "/qwhdhub/api/mark/task/taskList";
    private static final String P_INFO = "/qwhdhub/api/mark/task/taskInfo";
    private static final String P_FINISH = "/qwhdhub/api/mark/task/finishTask";
    private static final String P_AWARD = "/qwhdhub/api/mark/task/getTaskAward";
    private static final String P_SECOND = "/qwhdhub/api/mark/task/getTaskSecondAward";
    private static final String P_OPEN_INFO = "/hlwyxhdhub/api/open/_pub/task/getOneTaskInfo";
    private static final String P_OPEN_FINISH = "/hlwyxhdhub/api/open/_pub/task/openFinish";

    private MarkTasks() {
    }

    /**
     * 跑一轮任务编排，返回多行判定型文本（由 ScriptEngine 的 call 写进 detail）。
     */
    static String run(String referer, StringBuilder ignored) {
        StringBuilder d = new StringBuilder();
        int total = 0, todo = 0, ext = 0, blocked = 0, done = 0, refused = 0;
        int awarded = 0, second = 0, handshake = 0;

        JSONObject root = post(P_LIST, "{}", referer, d, "任务清单");
        if (root == null || !"SUCCESS".equals(root.optString("code"))) {
            return "任务编排: 清单获取失败，本次不做任何完成上报\n" + d;
        }
        JSONArray tasks = data(root).optJSONArray("tasks");
        if (tasks == null) {
            return "任务编排: 清单里没有 tasks，本次不做任何完成上报\n" + d;
        }
        total = tasks.length();

        // 1) 上次遗留：已完成但没领奖的，先补领首礼。
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optInt("status", -1) != 1) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            String r = award(id, P_AWARD, referer);
            d.append("[补领] ").append(name(t)).append('(').append(id).append(") → ").append(r).append('\n');
            if ("OK".equals(r)) {
                awarded++;
            }
            pause();
        }

        // 2) 待办逐个发完成上报，响应驱动。
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optInt("status", -1) != 0) {
                continue;
            }
            String cls = t.optString("taskClasifiName", "");
            String name = name(t);
            if (cls.contains("外部合作")) {
                ext++;
                continue;
            }
            if (hardBlocked(name)) {
                blocked++;
                d.append("[跳过] ").append(name).append(" · 真消费/邀好友/助力/公众号，不代做\n");
                continue;
            }
            todo++;
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }

            JSONObject info = post(P_INFO, "{\"taskId\":\"" + id + "\"}", referer, d,
                    "taskInfo " + id);
            JSONObject infoData = info == null ? new JSONObject() : data(info);
            // taskType 必须来自 taskInfo；scanTime / taskToken 同样只认这里的。
            String taskType = infoData.optString("taskType", "");
            int scan = infoData.optInt("scanTime", 0);
            String jt = infoData.optString("taskToken", "");
            if (taskType.isEmpty()) {
                d.append("[跳过] ").append(name).append('(').append(id)
                        .append(") · taskInfo 没给 taskType，不猜枚举\n");
                continue;
            }

            JSONObject fin = post(P_FINISH,
                    "{\"taskId\":\"" + id + "\",\"taskType\":\"" + taskType + "\"}",
                    referer, d, "finishTask " + id);
            boolean ok = fin != null && "SUCCESS".equals(fin.optString("code"));
            String why = "接口完成";
            String msg = fin == null ? "" : fin.optString("msg", "");

            if (!ok && (msg.contains("特殊处理") || msg.contains("openFinish"))
                    && !jt.isEmpty()) {
                // 服务端明确要求握手才走，不主动试探。
                if (handshake(jt, scan, referer, d)) {
                    ok = true;
                    handshake++;
                    why = "开放平台握手完成";
                }
            }
            if (!ok && msg.contains("公众号")) {
                d.append("[跳过] ").append(name).append('(').append(id)
                        .append(") · 需真实关注公众号，不代做\n");
                blocked++;
                pause();
                continue;
            }

            if (ok) {
                String r = award(id, P_AWARD, referer);
                done++;
                d.append("[完成] ").append(name).append('(').append(id)
                        .append(") taskType=").append(taskType).append(" · ").append(why)
                        .append(" · 领奖 ").append(r).append('\n');
                if ("OK".equals(r)) {
                    awarded++;
                }
            } else {
                refused++;
                // 签名是否必需由这里如实回答，不伪造、不重试、不硬试。
                d.append("[未做] ").append(name).append('(').append(id)
                        .append(") taskType=").append(taskType)
                        .append(" · 服务端拒绝 ").append(fin == null ? "无响应"
                                : fin.optString("code", "?") + " " + msg)
                        .append(" · 标记需页面现场，本版不拼签名\n");
            }
            pause();
        }

        // 3) 重拉清单，领第二重礼（首礼领完后才解锁）。
        JSONObject root2 = post(P_LIST, "{}", referer, d, "任务清单复查");
        JSONArray tasks2 = root2 == null ? null : data(root2).optJSONArray("tasks");
        for (int i = 0; tasks2 != null && i < tasks2.length(); i++) {
            JSONObject t = tasks2.optJSONObject(i);
            if (t == null || !t.has("secondStatus")) {
                continue;
            }
            if (t.optInt("awardType", 0) != 2 || t.optInt("status", -1) != 2
                    || t.optInt("secondStatus", -1) != 0) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            String r = award(id, P_SECOND, referer);
            d.append("[二礼] ").append(name(t)).append('(').append(id).append(") → ")
                    .append(r).append('\n');
            if ("OK".equals(r)) {
                second++;
            }
            pause();
        }

        String head = "任务编排 · 共" + total + " / 待办" + todo + " / 外部合作区跳过" + ext
                + " / 不代做" + blocked + " / 完成" + done + "（握手" + handshake + "）"
                + " / 服务端拒绝" + refused + " / 领奖" + awarded + " / 二礼" + second;
        HookLog.log("TASK", "任务编排 · 接口直发", head + "\n" + d);
        return head + "\n" + d;
    }

    /** 完成上报之外，只有领奖接口由这里发；返回 OK 或失败原因。 */
    private static String award(String taskId, String path, String referer) {
        JSONObject r = post(path, "{\"taskId\":\"" + taskId + "\"}", referer,
                new StringBuilder(), "领奖 " + taskId);
        if (r == null) {
            return "无响应";
        }
        if ("SUCCESS".equals(r.optString("code"))) {
            return "OK";
        }
        return r.optString("code", "?") + " " + r.optString("msg", "");
    }

    /**
     * 开放平台握手：jtToken → cToken → openFinish。
     * openFinish 停留不足时 code=SUCCESS 但 data=false，必须换 cToken 等 scanTime+1 再上报。
     */
    private static boolean handshake(String jt, int scan, String referer, StringBuilder d) {
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject gi = post(P_OPEN_INFO, "{\"jtToken\":\"" + jt + "\"}",
                    referer, d, "getOneTaskInfo");
            String ct = gi == null ? "" : data(gi).optString("cToken", "");
            if (ct.isEmpty()) {
                return false;
            }
            JSONObject of = post(P_OPEN_FINISH, "{\"cToken\":\"" + ct + "\"}",
                    referer, d, "openFinish");
            if (of != null && "SUCCESS".equals(of.optString("code"))
                    && !Boolean.FALSE.equals(of.opt("data"))) {
                return true;
            }
            if (attempt == 0 && scan > 0) {
                long wait = Math.min(scan, 15) + 1;
                d.append("[握手] 停留不足，等 ").append(wait).append("s 后换 cToken 重试\n");
                WebPage.sleep(wait * 1000L);
            }
        }
        return false;
    }

    /** 真消费、邀好友、助力、公众号：这些不是接口能代做的，不猜、不试。 */
    private static boolean hardBlocked(String name) {
        String[] words = {"给自己充", "充10元", "充话费", "邀好友", "邀当日", "助力",
                "公众号", "分享给", "补签卡", "受邀"};
        for (String w : words) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String name(JSONObject t) {
        String n = t.optString("taskName", "");
        return n.isEmpty() ? t.optString("taskId", "?") : n;
    }

    private static JSONObject data(JSONObject root) {
        JSONObject d = root == null ? null : root.optJSONObject("data");
        return d == null ? new JSONObject() : d;
    }

    /** 统一发请求：失败/非 JSON 都如实记一行，返回 null 交给调用方判定。 */
    private static JSONObject post(String path, String body, String referer,
                                   StringBuilder d, String tag) {
        try {
            return TaskApi.post(path, body, referer);
        } catch (Throwable e) {
            d.append("[").append(tag).append("] 请求失败: ").append(e).append('\n');
            return null;
        }
    }

    private static void pause() {
        WebPage.sleep(200L + new java.util.Random().nextInt(300));
    }
}
