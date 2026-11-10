package com.cmcchook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.0.7+probe：卡券真页的「当次现场原料」内存快照。
 *
 * <p>探针版一次性测试多组方案，其中「带渠道参数 URL」「重放真页 headers」
 * 需要 App 自己打开卡券页时的现场数据。这里只在内存里记最近一次前台真页的
 * loadUrl —— <b>不落盘、不当缓存复用</b>，并且带采集时间戳。
 *
 * <p>为什么不落盘：渠道参数随省份 / 入口 / 期次变化（见 {@link RealEntry} 注释），
 * 持久化缓存无法保证此刻仍被服务端接受。探针每次运行都读「本进程内最近一次
 * 现场」，日志里写明距今多久、是不是本次会话采到的；没有现场就如实标注，
 * 由对应方案退化为裸地址，绝不拿一份旧数据冒充最新入口。
 *
 * <p>只在 App 前台真页 loadUrl 时记录；模块自己开的离屏页不算（那正是要对照的
 * 对象，把它记进来等于自证循环）。
 */
final class CouponScene {

    private static volatile String url = "";
    private static volatile Map<String, String> headers = new LinkedHashMap<>();
    private static volatile String cookie = "";
    private static volatile String jwt = "";
    private static volatile long at = 0L;
    private static volatile int notes = 0;

    private CouponScene() {
    }

    /** WebViewHooks.loadUrl：前台真页加载 mycoupons 时调用。 */
    static synchronized void note(String realUrl, Map<?, ?> hdr) {
        if (realUrl == null || realUrl.indexOf("mycoupons") < 0) {
            return;
        }
        url = realUrl;
        Map<String, String> m = new LinkedHashMap<>();
        if (hdr != null) {
            for (Map.Entry<?, ?> e : hdr.entrySet()) {
                if (e.getKey() != null) {
                    m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        headers = m;
        try {
            cookie = CookieStore.header(realUrl);
        } catch (Throwable t) {
            cookie = "";
        }
        try {
            jwt = SsoLogin.ssoJwt();
        } catch (Throwable t) {
            jwt = "";
        }
        at = System.currentTimeMillis();
        notes++;
    }

    /** 有没有「当次会话」采到的现场。 */
    static boolean fresh() {
        return at > 0L && !url.isEmpty();
    }

    static long ageMs() {
        return at <= 0L ? -1L : System.currentTimeMillis() - at;
    }

    static String url() {
        return url;
    }

    /** 带渠道参数的真实入口；没有现场时返回空串。 */
    static String entryUrl() {
        return fresh() ? url : "";
    }

    static Map<String, String> headers() {
        return new LinkedHashMap<>(headers);
    }

    static String cookie() {
        return cookie;
    }

    static String jwt() {
        return jwt;
    }

    static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("现场采集: ").append(fresh() ? "有" : "无（本次会话没打开过前台卡券真页）").append('\n');
        if (fresh()) {
            sb.append("距今: ").append(ageMs() / 1000L).append("s · 累计记录 ").append(notes).append(" 次\n");
            sb.append("真页 URL: ").append(url).append('\n');
            sb.append("带渠道参数: ").append(url.indexOf('?') >= 0 ? "是" : "否").append('\n');
            sb.append("loadUrl headers: ").append(headers.isEmpty() ? "无" : headers.size() + " 项").append('\n');
            if (!headers.isEmpty()) {
                int n = 0;
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (n++ >= 12) {
                        sb.append("  ...\n");
                        break;
                    }
                    String k = e.getKey();
                    String v = e.getValue();
                    // Cookie / token 类只报长度，别把会话原文写进导出日志。
                    if (k != null && (k.toLowerCase().contains("cookie")
                            || k.toLowerCase().contains("token")
                            || k.toLowerCase().contains("auth"))) {
                        sb.append("  ").append(k).append(": <").append(v == null ? 0 : v.length())
                                .append(" 字符, 已脱敏>\n");
                    } else {
                        sb.append("  ").append(k).append(": ")
                                .append(v == null ? "" : (v.length() > 80 ? v.substring(0, 80) + "…" : v))
                                .append('\n');
                    }
                }
            }
            sb.append("Cookie 快照: ").append(cookie.isEmpty() ? "空" : cookie.length() + " 字符").append('\n');
            sb.append("SSO jwt: ").append(jwt.isEmpty() ? "无" : jwt.length() + " 字符").append('\n');
        }
        sb.append("已缓存的 App 私有 JS 桥:\n").append(AppBridges.describe());
        return sb.toString();
    }
}
