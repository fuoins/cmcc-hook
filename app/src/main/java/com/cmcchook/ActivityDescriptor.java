package com.cmcchook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * r20：活动描述 JSON 的 schema、校验与加载。
 *
 * <p>这是「录一次 → 离线分析 → 产出描述 → 下发执行」闭环里的<b>契约层</b>：
 * 新活动不再需要写一个 Java Task 类、改 5 处分派、编译发版，而是下发一条
 * 符合本 schema 的 JSON。描述文件从模块私有目录 {@code descriptors.json}
 * 读取（不联网、不编译进 APK），找不到就当没有 —— 默认零回归。
 *
 * <pre>
 * {
 *   "schema": "cmcc-activity/1",
 *   "id": "miao_browse",              // 唯一 ID
 *   "cmd": "miao",                   // 绑定的命令；缺省用 "desc:&lt;id&gt;"
 *   "name": "豆苗·浏览任务",
 *   "enabled": false,                // 默认不启用，人工核对后由你把 true 下发
 *   "entry": "https://...",          // dom 步骤需要的真页入口
 *   "budgetMs": 60000,               // 总预算，超时按 idle/skip 收手
 *   "requires": ["phone"],           // 凭证要求（只做声明，不负责采集）
 *   "idempotent": "day",             // none / day / once
 *   "steps": [
 *     {"kind":"http","id":"s1","method":"POST","path":"/coc3/...","body":"{}",
 *      "save":{"taskId":"$.data[0].taskId"}},
 *     {"kind":"dom","selector":"IMG.content-btn","action":"click","stayMs":5000},
 *     {"kind":"sleep","ms":1500},
 *     {"kind":"log","text":"已上报"}
 *   ],
 *   "okWhen":  {"path":"$.data.isFinish","eq":1},   // 成功判据
 *   "idleWhen": {"path":"$.code","eq":"HAVE_MARKED"},
 *   "result":  {"ok":"...","idle":"...","fail":"...","skip":"..."}
 * }
 * </pre>
 *
 * <p>安全边界：{@code cmd} 不允许覆盖模块已有的 20 个活动命令和诊断命令，
 * 描述执行器只接管「新命令」或显式 {@code desc:} 前缀，避免一条配置把
 * 跑了十几轮的老链路顶掉。{@link #validate} 是纯函数，可在 JVM 上离线自验。
 */
final class ActivityDescriptor {

    private ActivityDescriptor() {
    }

    static final String SCHEMA = "cmcc-activity/1";
    static final String FILE = "descriptors.json";
    static final String CMD_DESC = "desc:";

    /** 不允许描述覆盖的保留命令（老链路 + 诊断命令）。 */
    private static final Set<String> RESERVED = new HashSet<>();

    static {
        String[] all = {
                "all", "sign", "egg", "goose", "soccer", "tearbox", "saturday",
                "video", "daily7", "miao", "recharge", "wed", "seckill", "lucky3",
                "weekly", "signlottery", "quiz", "welfare", "flow", "catalog", "selftest",
                "record", "descriptors", "probe"
        };
        for (String c : all) {
            RESERVED.add(c);
        }
    }

    private static final Set<String> KINDS = new HashSet<>();

    static {
        String[] k = {"http", "dom", "sleep", "log", "set"};
        for (String s : k) {
            KINDS.add(s);
        }
    }

    private static final Set<String> IDEMPOTENT = new HashSet<>();

    static {
        String[] k = {"none", "day", "once"};
        for (String s : k) {
            IDEMPOTENT.add(s);
        }
    }

    // ------------------------------------------------------------ 校验（纯函数）

    /** 校验一条描述。返回 {@code PASS ...} 或 {@code FAIL ...原因}。 */
    static String validate(JSONObject d) {
        List<String> fails = new ArrayList<>();
        if (d == null) {
            return "FAIL 描述为空";
        }
        if (!SCHEMA.equals(d.optString("schema", ""))) {
            fails.add("schema 不是 " + SCHEMA);
        }
        String id = d.optString("id", "");
        if (id.isEmpty() || !id.matches("[a-zA-Z0-9_-]{1,48}")) {
            fails.add("id 为空或含非法字符");
        }
        if (d.optString("name", "").isEmpty()) {
            fails.add("name 为空");
        }
        String cmd = d.optString("cmd", "");
        if (!cmd.isEmpty() && RESERVED.contains(cmd)) {
            fails.add("cmd 覆盖了保留命令「" + cmd + "」，拒绝（防止顶掉老链路）");
        }
        long budget = d.optLong("budgetMs", 60000L);
        if (budget < 1000L || budget > 600_000L) {
            fails.add("budgetMs 越界（1000..600000）");
        }
        String idem = d.optString("idempotent", "day");
        if (!IDEMPOTENT.contains(idem)) {
            fails.add("idempotent 只能是 none/day/once");
        }
        JSONArray steps = d.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            fails.add("steps 为空");
        } else {
            if (steps.length() > 60) {
                fails.add("steps 超过 60 条");
            }
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s == null) {
                    fails.add("步骤 " + i + " 不是对象");
                    continue;
                }
                String kind = s.optString("kind", "");
                if (!KINDS.contains(kind)) {
                    fails.add("步骤 " + i + " kind 非法: " + kind);
                    continue;
                }
                if ("http".equals(kind)) {
                    String method = s.optString("method", "GET").toUpperCase();
                    if (!method.equals("GET") && !method.equals("POST")) {
                        fails.add("步骤 " + i + " method 只能是 GET/POST");
                    }
                    if (s.optString("path", "").isEmpty()) {
                        fails.add("步骤 " + i + " http 缺 path");
                    }
                } else if ("dom".equals(kind)) {
                    // 图片按钮靠结构定位，不靠 innerText —— 沿用 r16 的约定。
                    if (s.optString("selector", "").isEmpty()) {
                        fails.add("步骤 " + i + " dom 缺 selector");
                    }
                    long stay = s.optLong("stayMs", 5000L);
                    if (stay < 5000L) {
                        fails.add("步骤 " + i + " 浏览停留不足 5 秒");
                    }
                } else if ("sleep".equals(kind)) {
                    long ms = s.optLong("ms", 0L);
                    if (ms < 0L || ms > 60_000L) {
                        fails.add("步骤 " + i + " sleep.ms 越界");
                    }
                } else if ("set".equals(kind)) {
                    if (s.optString("name", "").isEmpty()) {
                        fails.add("步骤 " + i + " set 缺 name");
                    }
                }
            }
        }
        JSONObject ok = d.optJSONObject("okWhen");
        if (ok == null || (ok.optString("path", "").isEmpty()
                && ok.optString("code", "").isEmpty()
                && !ok.has("hint"))) {
            fails.add("okWhen 缺判据（草稿可只填 hint）");
        }
        if (fails.isEmpty()) {
            return "PASS " + id;
        }
        return "FAIL " + id + ": " + String.join("; ", fails);
    }

    static String validateAll(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return "PASS 0/0 · 没有外部描述（默认零回归）";
        }
        List<String> fails = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> cmds = new HashSet<>();
        int total = arr.length();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            String v = validate(d);
            if (v.startsWith("FAIL")) {
                fails.add("[" + i + "] " + v);
            }
            if (d != null) {
                if (!ids.add(d.optString("id", ""))) {
                    fails.add("[" + i + "] id 重复: " + d.optString("id", ""));
                }
                String c = d.optString("cmd", "");
                if (!c.isEmpty() && !cmds.add(c)) {
                    fails.add("[" + i + "] cmd 重复: " + c);
                }
            }
        }
        if (fails.isEmpty()) {
            return "PASS " + total + "/" + total + " · 描述文件全部合法";
        }
        return "FAIL " + fails.size() + "/" + total + "\n  - " + String.join("\n  - ", fails);
    }

    // ------------------------------------------------------------ 加载（模块进程）

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 读外部下发的描述文件。文件不存在 / 坏了都返回空数组，绝不影响主流程。 */
    static synchronized JSONArray load(Context ctx) {
        JSONArray out = new JSONArray();
        if (ctx == null) {
            return out;
        }
        try {
            File f = file(ctx);
            if (!f.exists()) {
                return out;
            }
            // minSdk 23：java.nio.file.Files 要 API 26，这里用流读写，跟 RawCaptureStore 一致。
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            in.close();
            JSONObject root = new JSONObject(
                    new String(buf.toByteArray(), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("descriptors");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d != null) {
                        out.put(d);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 写描述文件（供「导入描述」用）。先全量校验，非法的一律拒收。 */
    static synchronized String save(Context ctx, String json) {
        if (ctx == null) {
            return "FAIL 没有 Context";
        }
        JSONArray arr;
        try {
            JSONObject root = new JSONObject(json);
            arr = root.optJSONArray("descriptors");
            if (arr == null) {
                return "FAIL 缺 descriptors 数组";
            }
        } catch (Throwable e) {
            return "FAIL JSON 解析失败: " + e;
        }
        String v = validateAll(arr);
        if (!v.startsWith("PASS")) {
            return v;
        }
        try {
            FileOutputStream out = new FileOutputStream(file(ctx), false);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
            return "PASS 已保存 " + arr.length() + " 条描述（enabled 的才会接管命令）";
        } catch (Throwable e) {
            return "FAIL 写文件失败: " + e;
        }
    }

    /** 按命令找一条「已启用」的描述；找不到返回 null —— 老链路原样跑。 */
    static JSONObject enabledFor(Context ctx, String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return null;
        }
        JSONArray arr = load(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null || !d.optBoolean("enabled", false)) {
                continue;
            }
            String c = d.optString("cmd", "");
            String key = c.isEmpty() ? CMD_DESC + d.optString("id", "") : c;
            if (cmd.equals(key) || cmd.equals(CMD_DESC + d.optString("id", ""))) {
                if (validate(d).startsWith("PASS")) {
                    return d;
                }
            }
        }
        return null;
    }

    /** 清单：把外部描述列出来，标注启用状态与校验结果。 */
    static String report(Context ctx) {
        JSONArray arr = load(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("外部活动描述（r20）\n");
        sb.append("文件: ").append(FILE).append("（模块私有目录，不联网、不编译进 APK）\n");
        sb.append("校验: ").append(validateAll(arr)).append("\n\n");
        if (arr.length() == 0) {
            sb.append("当前没有描述文件 —— 所有活动走原 Java 链路，零回归。\n");
            sb.append("下发方式: 把 {\"descriptors\":[...]} 存成 descriptors.json 放进模块 files 目录，\n");
            sb.append("或用「导入描述」命令。enabled=true 且 cmd 命中才接管。\n");
            return sb.toString();
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) {
                continue;
            }
            sb.append("  · ").append(d.optString("id", ""))
                    .append("  ").append(d.optString("name", ""))
                    .append("  cmd=").append(d.optString("cmd", "(desc:前缀)"))
                    .append("  enabled=").append(d.optBoolean("enabled", false))
                    .append("  steps=").append(d.optJSONArray("steps") == null
                    ? 0 : d.optJSONArray("steps").length())
                    .append("  ").append(validate(d)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 离线自验

    static String selfCheck() {
        try {
            return selfCheckImpl();
        } catch (Throwable e) {
            return "FAIL ActivityDescriptor 自验抛异常: " + e;
        }
    }

    private static String selfCheckImpl() throws JSONException {
        List<String> fails = new ArrayList<>();
        int total = 0;

        total++;
        if (validate(null).startsWith("PASS")) {
            fails.add("空描述被误判合法");
        }
        total++;
        if (!validate(new JSONObject()).startsWith("FAIL")) {
            fails.add("空对象被误判合法");
        }
        total++;
        if (!good("cover_all", "all").startsWith("FAIL")) {
            fails.add("覆盖保留命令 all 没被拒");
        }
        total++;
        if (!good("d1", "miao_browse").startsWith("PASS")) {
            fails.add("合法描述被误判非法: " + good("d1", "miao_browse"));
        }
        total++;
        JSONObject badStep = goodObj("d2", "x2");
        badStep.optJSONArray("steps").put(new JSONObject().put("kind", "ftp"));
        if (!validate(badStep).startsWith("FAIL")) {
            fails.add("非法 kind 没被拒");
        }
        total++;
        JSONObject shortStay = goodObj("d3", "x3");
        shortStay.optJSONArray("steps").getJSONObject(1).put("stayMs", 1000);
        if (!validate(shortStay).startsWith("FAIL")) {
            fails.add("浏览停留不足 5 秒没被拒");
        }
        total++;
        JSONArray arr = new JSONArray();
        arr.put(goodObj("dup", "c1"));
        arr.put(goodObj("dup", "c2"));
        if (!validateAll(arr).startsWith("FAIL")) {
            fails.add("重复 id 没被拒");
        }
        total++;
        JSONArray arr2 = new JSONArray();
        arr2.put(goodObj("d4", "c4"));
        arr2.put(goodObj("d5", "c5"));
        if (!validateAll(arr2).startsWith("PASS")) {
            fails.add("合法描述集被误判: " + validateAll(arr2));
        }
        total++;
        if (validateAll(new JSONArray()).indexOf("0/0") < 0) {
            fails.add("空描述集没报「零回归」");
        }

        if (fails.isEmpty()) {
            return "PASS " + total + "/" + total + " · ActivityDescriptor schema/保留命令/步骤/停留/id唯一";
        }
        return "FAIL " + fails.size() + "/" + total + "\n  - " + String.join("\n  - ", fails);
    }

    private static String good(String id, String cmd) {
        return validate(goodObj(id, cmd));
    }

    /** 一条最小合法描述，供自验与文档示例。固定值 put 抛的受检异常在此吞掉，签名保持不变。 */
    static JSONObject goodObj(String id, String cmd) {
        JSONObject d = new JSONObject();
        try {
            d.put("schema", SCHEMA);
            d.put("id", id);
            d.put("cmd", cmd);
            d.put("name", "自验样例");
            d.put("enabled", false);
            d.put("entry", "https://wx.10086.cn/act/demo");
            d.put("budgetMs", 60000L);
            d.put("idempotent", "day");
            JSONArray steps = new JSONArray();
            JSONObject http = new JSONObject();
            http.put("kind", "http");
            http.put("id", "s1");
            http.put("method", "POST");
            http.put("path", "/demo/api/list");
            http.put("body", "{}");
            steps.put(http);
            JSONObject dom = new JSONObject();
            dom.put("kind", "dom");
            dom.put("selector", "IMG.content-btn");
            dom.put("action", "click");
            dom.put("stayMs", 5000L);
            steps.put(dom);
            d.put("steps", steps);
            d.put("okWhen", new JSONObject().put("path", "$.code").put("eq", "SUCCESS"));
        } catch (JSONException ignored) {
            // 固定键值的构造不可能触发；即便 Android 版 org.json 抛受检异常也不外溢。
        }
        return d;
    }
}
