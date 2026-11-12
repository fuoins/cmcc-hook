package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * r13：灵犀「活动口令」的存取。
 *
 * <p>这个口令每次活动都不一样，写死没有意义。带按钮的号点一次按钮，页面会把口令
 * 直接发出去（聊天里能看到原文），注入脚本在点击后的 3 秒窗口里从三处取候选：
 * 出站 WebSocket 帧、输入框当前值、聊天里新冒出来的气泡。取到后写进模块的
 * settings，换到没有按钮的号时直接复用。
 *
 * <p>只在模块进程读写。目标进程要通过广播把候选送回来（{@link HookLog} 的 CMD
 * 条目），因为两个进程的 data 目录不互通。
 */
final class LingxiCmd {

    private static final String K_TEXT = "lingxi_cmd_text";
    private static final String K_AT = "lingxi_cmd_at";
    private static final String K_SRC = "lingxi_cmd_src";
    private static final String K_CLICK = "lingxi_cmd_click";

    /** 口令一般很短；超过这个长度基本是把整段聊天记录当候选了。 */
    private static final int MAX_LEN = 40;

    private LingxiCmd() {
    }

    static void save(Context context, String text, String source, String click) {
        String value = clean(text, click);
        if (value.isEmpty()) {
            return;
        }
        try {
            SharedPreferences.Editor e = AppPrefs.settings(context).edit();
            e.putString(K_TEXT, value);
            e.putLong(K_AT, System.currentTimeMillis());
            e.putString(K_SRC, source == null ? "" : source);
            e.putString(K_CLICK, click == null ? "" : click);
            e.commit();
        } catch (Throwable ignored) {
        }
    }

    static String text(Context context) {
        try {
            String v = AppPrefs.settings(context).getString(K_TEXT, "");
            return v == null ? "" : v.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    static long at(Context context) {
        try {
            return AppPrefs.settings(context).getLong(K_AT, 0L);
        } catch (Throwable e) {
            return 0L;
        }
    }

    static String source(Context context) {
        try {
            String v = AppPrefs.settings(context).getString(K_SRC, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    static void clear(Context context) {
        try {
            AppPrefs.settings(context).edit()
                    .remove(K_TEXT).remove(K_AT).remove(K_SRC).remove(K_CLICK).commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 收到一条 CMD 日志（JSON）后，从里面挑一个最像口令的值存下来。
     *
     * <p>优先级：脚本直接给的 candidate &gt; 点击后的出站帧 &gt; 输入框当前值 &gt;
     * 新出现的气泡。越靠前越接近「按钮真正发出去的那句话」。
     */
    static void accept(Context context, String detail) {
        if (detail == null || detail.isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(detail);
            String click = o.optString("click", "");
            String source = o.optString("src", "bubble");
            String value = clean(o.optString("candidate", ""), click);
            if (value.isEmpty()) {
                value = fromArray(o.optJSONArray("frames"), click);
                source = "ws";
            }
            if (value.isEmpty()) {
                value = fromInputs(o.optJSONArray("inputs"), click);
                source = "input";
            }
            if (value.isEmpty()) {
                value = fromArray(o.optJSONArray("fresh"), click);
                source = "bubble";
            }
            if (value.isEmpty()) {
                return;
            }
            save(context, value, source, click);
            HookLog.logcat("lingxi cmd captured: " + value + " (" + source + ")");
        } catch (Throwable ignored) {
        }
    }

    private static String fromInputs(JSONArray inputs, String click) {
        if (inputs == null) {
            return "";
        }
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject one = inputs.optJSONObject(i);
            if (one == null) {
                continue;
            }
            String v = clean(one.optString("value", ""), click);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String fromArray(JSONArray arr, String click) {
        if (arr == null) {
            return "";
        }
        for (int i = 0; i < arr.length(); i++) {
            String v = clean(arr.optString(i, ""), click);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 过滤掉界面文案：按钮名、输入框提示、导航词都不能当口令。
     *
     * <p>之前把「发口令 抢红包」这类 chip 文案也收进来过，换号发送时等于把按钮
     * 名字发出去，服务端当然不认。这里要求候选不能等于点击文案，也不能是纯
     * 数字时间或这些固定提示。
     */
    static String clean(String raw, String click) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\u0000', ' ').trim();
        s = s.replaceAll("\\s+", " ");
        if (s.length() < 2 || s.length() > MAX_LEN) {
            return "";
        }
        String[] bad = {"发送", "取消", "确定", "请输入", "点击", "按住", "说话", "表情",
                "更多", "返回", "关闭", "领网龄", "查一下", "热门流量", "充值缴费",
                "欢迎", "咨询", "客服", "消息", "菜单", "展开", "收起", "加载",
                "领取成功", "已领取", "抢券成功", "恭喜获得", "已到账",
                "已抢光", "已领完", "活动结束", "未开始", "稍后再试"};
        for (String b : bad) {
            if (s.equals(b) || (s.length() <= 6 && s.contains(b))) {
                return "";
            }
        }
        if (s.matches("^[\\d\\s:：.\\-/]+$")) {
            return "";
        }
        return s;
    }

    /** 从一帧 JSON 里找口令字段；找不到返回空。 */
    static String fromFrame(String data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        String s = data.trim();
        int brace = s.indexOf('{');
        if (brace < 0) {
            // 表单体：msg=抢红包&to=10086。口令在 msg/text 里，解出来再用。
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?:^|[&?])(msg|text|content|command)=([^&]+)")
                        .matcher(s);
                if (m.find()) {
                    String v = java.net.URLDecoder.decode(m.group(2), "UTF-8");
                    return clean(v, "");
                }
            } catch (Throwable ignored) {
            }
            return "";
        }
        try {
            String body = s.substring(brace);
            if (body.startsWith("[")) {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject one = arr.optJSONObject(i);
                    String v = deep(one, 0);
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
                return "";
            }
            return deep(new JSONObject(body), 0);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String deep(JSONObject o, int depth) {
        if (o == null || depth > 3) {
            return "";
        }
        String[] keys = {"msg", "text", "content", "command", "cmd", "keyword", "word", "message"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.isEmpty()) {
                continue;
            }
            if (v.startsWith("{")) {
                try {
                    String nested = deep(new JSONObject(v), depth + 1);
                    if (!nested.isEmpty()) {
                        return nested;
                    }
                } catch (Throwable ignored) {
                }
            }
            String c = clean(v, "");
            if (!c.isEmpty()) {
                return c;
            }
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                String nested = deep((JSONObject) v, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            } else if (v instanceof String) {
                // IM 常把消息体再套一层字符串：{"body":"{\"msg\":\"抢红包\"}"}。
                String sv = ((String) v).trim();
                if (sv.startsWith("{")) {
                    try {
                        String nested = deep(new JSONObject(sv), depth + 1);
                        if (!nested.isEmpty()) {
                            return nested;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return "";
    }
}
