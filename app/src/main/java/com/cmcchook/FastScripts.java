package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * r8 快路径脚本注册表。
 *
 * <p>这里只放「声明式请求序列」，不点 DOM、不判断按钮是图片还是文字、不等渲染。
 * 请求全部由 {@link ScriptEngine} → {@link TaskNet} → {@link WebFetch} 在活动真页
 * 的 JS 上下文里发出：加密、签名、动态头、token 都由页面运行时负责。
 *
 * <p>脚本的阶梯照参考项目 {@code cmcc_extra.run_mark_tasks}：
 * {@code taskList} 取待办 → {@code taskInfo} 取 taskType/scanTime/jumpUrl
 * → {@code finishTask} 直发 → 失败则带目标页 Referer 的 GET 冒充到访并重试
 * → {@code getTaskAward} 领奖 → 复查 {@code taskList} 验收。
 * 验收不通过就由 {@link FastPath} 回退原来的真页点击链路，功能不回退。
 *
 * <p>密文域（biz-orange / wisdom-h5 / lyh）录下来是密文 + 每次现算的签名头，
 * 离线重放做不到，本版不注册脚本；画布域接口层没 dump 出来前也没有脚本可写。
 * 它们全部保留原链路，并靠 {@link RawCapture} 旁路录下真实请求序列供下一版收敛。
 */
final class FastScripts {

    private FastScripts() {
    }

    static final String SIGN_BROWSE = String.join("\n", new String[]{
            "{\"steps\":[",
            "{\"log\":\"▶ 快路径 · 功能体验区纯请求阶梯\"},",
            "{\"req\":{\"id\":\"list\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/taskList\",\"body\":\"{}\"}},",
            "{\"if\":{\"cond\":{\"ne\":[\"{list.code}\",\"SUCCESS\"]},\"then\":[{\"result\":{\"kind\":\"fail\",\"text\":\"任务清单拿不到：{list.code}，回退真页链路\"}}]}},",
            "{\"for\":{\"list\":\"{list.data.tasks}\",\"as\":\"t\",\"do\":[{\"if\":{\"cond\":{\"eq\":[\"{t.status}\",\"0\"]},\"then\":[{\"if\":{\"cond\":{\"contains\":[\"{t.taskClasifiName}\",\"外部合作\"]},\"then\":[{\"log\":\"跳过外部合作区 {t.taskName}\"}],\"else\":[{\"if\":{\"cond\":{\"contains\":[\"{t.taskName}\",\"充10元\"]},\"then\":[{\"log\":\"跳过真实消费 {t.taskName}\"}],\"else\":[{\"req\":{\"id\":\"info\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/taskInfo\",\"body\":\"{\\\"taskId\\\":\\\"{t.taskId}\\\"}\"}},{\"req\":{\"id\":\"fin\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/finishTask\",\"body\":\"{\\\"taskId\\\":\\\"{t.taskId}\\\",\\\"taskType\\\":\\\"{info.data.taskType}\\\"}\"}},{\"if\":{\"cond\":{\"ne\":[\"{fin.code}\",\"SUCCESS\"]},\"then\":[{\"if\":{\"cond\":{\"contains\":[\"{t.jumpUrl}\",\"https://\"]},\"then\":[{\"req\":{\"id\":\"visit\",\"method\":\"GET\",\"path\":\"{t.jumpUrl}\",\"ref\":\"{t.jumpUrl}\"}},{\"req\":{\"id\":\"fin\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/finishTask\",\"body\":\"{\\\"taskId\\\":\\\"{t.taskId}\\\",\\\"taskType\\\":\\\"{info.data.taskType}\\\"}\",\"ref\":\"{t.jumpUrl}\"}}]}}]}},{\"if\":{\"cond\":{\"eq\":[\"{fin.code}\",\"SUCCESS\"]},\"then\":[{\"req\":{\"id\":\"award\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/getTaskAward\",\"body\":\"{\\\"taskId\\\":\\\"{t.taskId}\\\"}\"}},{\"log\":\"完成 {t.taskName}: {fin.code} / 领奖 {award.code}\"}],\"else\":[{\"log\":\"未通过 {t.taskName}: {fin.code} {fin.msg}（留待真页链路复查）\"}]}}]}}]}}]}}]}},",
            "{\"req\":{\"id\":\"verify\",\"method\":\"POST\",\"path\":\"/qwhdhub/api/mark/task/taskList\",\"body\":\"{}\"}},",
            "{\"call\":{\"fn\":\"fast:markTodo\",\"args\":[\"{verify.__text}\"],\"out\":\"todo\"}},",
            "{\"log\":\"复查: 功能体验区待办 {todo} 个\"},",
            "{\"if\":{\"cond\":{\"ne\":[\"{todo}\",\"0\"]},\"then\":[{\"result\":{\"kind\":\"fail\",\"text\":\"快路径仍有 {todo} 个待办，回退真页点击\"}}]}},",
            "{\"result\":{\"kind\":\"ok\",\"text\":\"功能体验区快路径完成\"}}",
            "]}"
    });

    /**
     * 某个命令有没有注册快路径脚本。
     *
     * <p>目前只有签到有礼：它的「功能体验区 9 个浏览任务」是唯一还在真页点
     * 「去完成」+ 停留 5 秒的地方，改成纯请求收益最大。其它明文活动本来就是
     * 纯请求编排（不点击、不停留），已经由 {@code TaskRunner.beginSessionFast}
     * 的轻量页面上下文统一提速，不需要再换一套脚本去冒风险。
     */
    static String scriptFor(String cmd) {
        if (TaskRunner.CMD_SIGN.equals(cmd)) {
            return SIGN_BROWSE;
        }
        return null;
    }

    static {
        // 验收回调：按 MarkTasks 的同一套规则数「功能体验区待办」，
        // 返回 0 才算快路径真把任务做完了，否则回退真页点击。
        ScriptEngine.register("fast:markTodo", args -> {
            try {
                JSONObject root = new JSONObject(args.length > 0 ? args[0] : "");
                JSONObject data = root.optJSONObject("data");
                JSONArray tasks = data == null ? null : data.optJSONArray("tasks");
                if (tasks == null) {
                    return "-1";
                }
                int todo = 0;
                for (int i = 0; i < tasks.length(); i++) {
                    // v4.20.0-r30：口径统一到 MarkTasks.isTodo。
                    // 这里原来只判「外部合作区 / 充10元」，把「充值8折」「咪咕视频」
                    // 这类做不了的也算进待办，导致复查永远不为 0、每轮都白回退真页。
                    if (MarkTasks.isTodo(tasks.optJSONObject(i))) {
                        todo++;
                    }
                }
                return String.valueOf(todo);
            } catch (Throwable e) {
                return "-1";
            }
        });
    }
}
