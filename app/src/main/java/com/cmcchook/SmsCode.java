package com.cmcchook;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 券核销短信验证码：自动读取、自动转发到已有的 {@code couponcode:} 通道。
 *
 * <p>参考 XposedSmsCode 的做法，但只保留我们需要的这一段：它 hook 系统电话进程
 * {@code InboundSmsHandler} 拦所有短信，还带规则引擎、通知、剪贴板、无障碍自动输入、
 * 历史记录。本模块只 hook 中国移动 App，也不需要通用验证码框架——验证码最终要填进
 * 离屏 WebView 的 H5 输入框，而工程里已经有完整的
 * {@code submitCode → window.__cmcc_coupon_code → setVal()} 链路。
 *
 * <p>所以这里只做三件事：
 * <ol>
 *   <li>从短信正文按「验证密码：6 位数字」提取验证码，并限定为中国移动的兑换类短信；</li>
 *   <li>只在券任务正在等验证码时投递，避免把登录验证码或其它 App 的码灌进核销框；</li>
 *   <li>通过 {@link TaskReceiver#ACTION_RUN} 广播把码送到目标进程，一码只投一次。</li>
 * </ol>
 *
 * <p>两条读短信的通道：{@link SmsReceiver} 静态广播实时收，{@link #scanRecent(Context)}
 * 从 {@code content://sms/inbox} 兜底捞（覆盖广播被 ROM 拦、或短信比「要验证码」早到的
 * 情况）。两通道共用同一份去重表，不会把同一个码投两遍。
 *
 * <p>不拦截短信、不改短信库、不读无关短信——用户没要求的都不做。
 */
final class SmsCode {

    static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

    /** 短信里写的最长有效期（5 分钟），超时不投。 */
    private static final long VALID_MS = 5L * 60_000L;
    /**
     * v3.56：本次「要验证码」可接受的短信年龄。
     *
     * <p>220046 日志实证：21:59:45 点「获取验证码」，页面发的是 738256，模块却把
     * 21:57:08 的缓存码 720354 灌了进去（来源写着「缓存短信」）。旧版按 5 分钟
     * 有效期投递，上一轮批量残留的旧码正好在窗口里，于是第一张必错，用户只能手动改。
     *
     * <p>页面自己的倒计时是 60 秒，短信正常 1~5 秒到。90 秒足够覆盖「用户先点获取、
     * 脚本晚一点才标记 needCode」的情况，又不会碰到上一轮的码。
     */
    private static final long FRESH_MS = 90_000L;
    /** 「正在等验证码」标记的寿命，批量跑完没收到 done 也能自己过期。 */
    private static final long WAIT_MS = 3L * 60_000L;
    /** 去重表最多留这么多条，防止长跑累积。 */
    private static final int MAX_SEEN = 32;
    /** 等码状态落盘：静态短信广播可能重新拉起模块进程，内存状态会丢。 */
    private static final String PREFS = "smscode";
    private static final String KEY_WAIT_AT = "wait_at";
    private static final String KEY_CACHE_CODE = "cache_code";
    private static final String KEY_CACHE_AT = "cache_at";
    private static final String KEY_CACHE_KEY = "cache_key";
    private static final String KEY_LAST_DELIVERED = "last_delivered";

    // 用户给的两种真实格式：
    //   【中国移动线上营业厅】(验证密码)：749235。…
    //   【中国移动客户端】验证密码：780365。…
    // 关键词后允许 0~6 个非数字字符（「)：」「：」「为」「 」等），再取 6 位数字。
    // 用 (?![0-9]) 挡住 7 位以上数字串，避免截半段。
    private static final Pattern CODE6 = Pattern.compile(
            "(?:验证密码|验证码|动态密码|校验码)[^0-9]{0,6}([0-9]{6})(?![0-9])");
    private static final Pattern CODE_ANY = Pattern.compile(
            "(?:验证密码|验证码|动态密码|校验码)[^0-9]{0,6}([0-9]{4,8})(?![0-9])");

    private static final Set<String> seen = new LinkedHashSet<>();
    /** 兜底轮询线程，只在等码期间活着。 */
    private static volatile Thread pollThread;
    private static volatile long pollRound;

    private static SharedPreferences prefs(Context context) {
        // v3.57：验证码缓存按账号隔离 —— A 账号的码绝不能填进 B 账号的兑换框。
        return AccountScope.prefs(context.getApplicationContext(), PREFS);
    }

    private static long waitAt(Context context) {
        return prefs(context).getLong(KEY_WAIT_AT, 0L);
    }

    private static void setWait(Context context, long value) {
        prefs(context).edit().putLong(KEY_WAIT_AT, value).apply();
    }

    private static String cacheCode(Context context) {
        return prefs(context).getString(KEY_CACHE_CODE, "");
    }

    private static long cacheAt(Context context) {
        return prefs(context).getLong(KEY_CACHE_AT, 0L);
    }

    private static void setCache(Context context, String code, long at, String key) {
        prefs(context).edit().putString(KEY_CACHE_CODE, code)
                .putLong(KEY_CACHE_AT, at).putString(KEY_CACHE_KEY, key).apply();
    }

    private static String cacheKey(Context context) {
        return prefs(context).getString(KEY_CACHE_KEY, "");
    }

    private static void clearCache(Context context) {
        prefs(context).edit().remove(KEY_CACHE_CODE).remove(KEY_CACHE_AT)
                .remove(KEY_CACHE_KEY).apply();
    }

    private SmsCode() {
    }

    /**
     * 从短信正文提验证码。非中国移动兑换类短信一律返回空串。
     */
    static String extract(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replace('\u3000', ' ').trim();
        if (text.isEmpty()) {
            return "";
        }
        // 白名单：必须是中国移动发的、且是兑换/券类业务。
        // 登录验证码、营销短信、其它 App 的码都不能进核销框。
        boolean cmcc = text.contains("中国移动")
                || text.contains("10086")
                || text.contains("线上营业厅");
        boolean biz = text.contains("兑换") || text.contains("券")
                || text.contains("话费") || text.contains("流量");
        if (!cmcc || !biz) {
            return "";
        }
        Matcher m = CODE6.matcher(text);
        if (!m.find()) {
            m = CODE_ANY.matcher(text);
            if (!m.find()) {
                return "";
            }
        }
        return m.group(1);
    }

    /** 静态广播收到一条短信。 */
    static void onSms(Context context, String body, long timestamp) {
        String code = extract(body);
        if (code.isEmpty()) {
            return;
        }
        long at = timestamp > 0 ? timestamp : System.currentTimeMillis();
        String key = fingerprint(body, code, at);
        if (key.equals(prefs(context).getString(KEY_LAST_DELIVERED, ""))) {
            return;
        }
        synchronized (seen) {
            if (seen.contains(key)) {
                return;
            }
            remember(key);
        }
        if (System.currentTimeMillis() - at > VALID_MS) {
            return;
        }
        if (!isWaiting(context)) {
            // 短信比「要验证码」早到（例如用户手动点了获取验证码），先缓存。
            // 下一张券开始等码时立刻投出去，不让用户白等。
            setCache(context, code, at, key);
            log(context, "收到验证码（暂未开始核销）", "码已缓存，等券任务要码时自动填入");
            return;
        }
        deliver(context, code, at, "短信广播", key);
    }

    /** 目标进程报告「这张券要验证码」。 */
    static void onNeedCode(Context context) {
        setWait(context, System.currentTimeMillis());
        // 先消费缓存（短信可能早到了），再从短信库兜底捞一次。
        String code = cacheCode(context);
        long at = cacheAt(context);
        String cachedKey = cacheKey(context);
        clearCache(context);
        if (!code.isEmpty()) {
            long age = System.currentTimeMillis() - at;
            if (age <= FRESH_MS) {
                deliver(context, code, at, "缓存短信", cachedKey);
            } else {
                // v3.56：220046 日志实证 —— 21:59:45 点「获取验证码」，页面发的是 738256，
                // 模块却把 21:57:08 的缓存码 720354 灌了进去。旧版按 5 分钟有效期投递，
                // 上一轮残留的旧码正好落在窗口里，第一张必错，用户只能手动改。
                log(context, "丢弃过期缓存验证码",
                        "缓存码 " + code + " 的短信时间是 " + hhmmss(at)
                                + "，距本次要码已 " + (age / 1000) + " 秒（上限 "
                                + (FRESH_MS / 1000) + " 秒），不再使用");
            }
        }
        scanRecent(context);
        startPolling(context);
    }

    private static String hhmmss(long at) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(at));
    }

    /** 一批跑完，取消等码标记，轮询线程随之退出。 */
    static void onDone(Context context) {
        setWait(context, 0L);
        clearCache(context);
        pollRound++;
    }

    /**
     * 等码期间每 1.5 秒扫一次短信库。为什么需要：部分 ROM 会拦静态短信广播，
     * 或者用户点「获取验证码」后短信比广播注册晚到；短信库是最终一致的那一份。
     * 轮询只在 wait 窗口内存在，跑完/超时自动退出，不常驻、不耗电。
     */
    private static void startPolling(Context context) {
        Thread alive = pollThread;
        if (alive != null && alive.isAlive()) {
            return;
        }
        final long round = ++pollRound;
        Thread thread = new Thread(() -> {
            while (round == pollRound && isWaiting(context)) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    return;
                }
                if (round != pollRound || !isWaiting(context)) {
                    return;
                }
                scanRecent(context.getApplicationContext());
            }
        }, "cmcc-sms-poll");
        thread.setDaemon(true);
        pollThread = thread;
        thread.start();
    }

    static boolean isWaiting(Context context) {
        long at = waitAt(context);
        return at > 0 && System.currentTimeMillis() - at < WAIT_MS;
    }

    /**
     * 兜底：查最近几条收件箱短信。广播被拦、或用户切走模块又回来时，
     * 从短信库把还没投过的码补上。返回是否投出了一个码。
     */
    static boolean scanRecent(Context context) {
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            cursor = context.getContentResolver().query(uri,
                    new String[]{"address", "body", "date"},
                    null, null, "date DESC");
            if (cursor == null) {
                return false;
            }
            long floor = System.currentTimeMillis() - FRESH_MS;
            int checked = 0;
            while (cursor.moveToNext() && checked++ < 12) {
                long date = cursor.getLong(2);
                if (date < floor) {
                    break; // 按 date DESC 排，遇到更旧的就不用往后看了
                }
                String body = cursor.getString(1);
                String code = extract(body);
                if (code.isEmpty()) {
                    continue;
                }
                String key = fingerprint(body, code, date);
                if (key.equals(prefs(context).getString(KEY_LAST_DELIVERED, ""))) {
                    continue;
                }
                synchronized (seen) {
                    if (seen.contains(key)) {
                        continue;
                    }
                    remember(key);
                }
                if (!isWaiting(context)) {
                    setCache(context, code, date, key);
                    continue;
                }
                deliver(context, code, date, "短信库兜底", key);
                return true;
            }
        } catch (Throwable e) {
            // 没授权 / 厂商 ROM 不给读，都不影响广播主通道，静默跳过。
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static void deliver(Context context, String code, long at,
                                 String source, String key) {
        clearCache(context);
        try {
            prefs(context).edit().putString(KEY_LAST_DELIVERED, key).apply();
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(TaskReceiver.ACTION_RUN);
            intent.setPackage(AppPrefs.TARGET_PACKAGE);
            intent.putExtra("cmd", CouponTask.CMD_CODE + code);
            // v3.56：必须把「短信时间」一起带过去。旧版只传 code，目标进程
            // submitCode() 用 System.currentTimeMillis() 当到达时间，于是
            // 「这条码比本张开始等码更早」的校验永远成立不了 —— 220046 日志里
            // 21:57:08 的旧码被当成 21:59:45 的新码，第一张直接填错。
            intent.putExtra("at", at);
            context.sendBroadcast(intent);
            log(context, "自动读到验证码 · " + code,
                    "来源: " + source + "\n短信时间: " + new java.text.SimpleDateFormat(
                            "HH:mm:ss", java.util.Locale.US).format(new java.util.Date(at))
                            + "\n已发往中国移动进程，页面会自动填入并点确认。");
        } catch (Throwable e) {
            log(context, "验证码转发失败", String.valueOf(e));
        }
    }

    private static String fingerprint(String body, String code, long at) {
        String flat = body == null ? "" : body.replaceAll("\\s+", "");
        // v3.47：时间不进 key。广播的 timestamp 来自 SmsMessage，短信库的 date
        // 来自收件箱，同一条短信两者常差 1 秒；旧版用 at/1000L 生成了不同指纹，
        // 于是同一个码被投两次（日志 837163 / 213725 / 986225 / 710856），
        // 下一张券开始等码时会拿到上一张的旧码。码本身就足以区分不同券的短信。
        return code + "@" + flat.hashCode();
    }

    private static void remember(String key) {
        if (seen.size() >= MAX_SEEN) {
            java.util.Iterator<String> it = seen.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        seen.add(key);
    }

    private static void log(Context context, String summary, String detail) {
        try {
            LogStore.append(context, new LogEntry(System.currentTimeMillis(),
                    "SMSCODE", summary, detail));
        } catch (Throwable ignored) {
        }
    }
}
