package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多账号数据隔离。
 *
 * <p>背景：模块所有落盘数据之前都是「一份」，谁最后跑谁覆盖。B 账号（198 / 小米）
 * 跑完一轮，任务状态、券清单、验证码缓存、活动记录全留在本地；换回 A 账号（一加）
 * 打开模块，看到的是 B 的「今日已完成」，于是当天任务被误判成做过了。
 *
 * <p>这里给每个账号分配一个独立的存储命名空间：{@code 原文件名__UID前12位}。
 * 没有识别到账号时退回原文件名，兼容旧数据与「Cookie 还没抓到」的冷启动早期。
 *
 * <p>UID 来源是 Cookie 里服务端下发的 {@code UID}（10086.cn 域，32 位 hex），
 * 不是我们自己编的；换账号后服务端会下发新值，命名空间自动跟着换。
 */
final class AccountScope {

    private static final String GLOBAL = "cmcc_hook_account";
    private static final String KEY_UID = "last_uid";
    private static final String KEY_PHONE = "last_phone";
    private static final String KEY_CHANGED_AT = "changed_at";
    private static final String KEY_MIGRATED = "migrated_";
    /** r15：上一次用于分片的 UID；同账号漂移时据此接续当日状态。 */
    private static final String KEY_SCOPE_UID = "scope_uid";
    private static final String KEY_SCOPE_PHONE = "scope_phone_";
    private static volatile String scopedUid = "";

    /** Cookie 缓存有效期。太短会频繁读 JAR，太长会漏掉刚发生的账号切换。 */
    private static final long TTL_MS = 15_000L;

    private static volatile String cachedUid;
    private static volatile long cachedAt;
    /** 本进程内刚发现「账号变了」，等下一次心跳做清理。 */
    private static volatile String switchedFrom = "";
    private static final Set<String> MIGRATED = new HashSet<>();
    /**
     * 不参与「旧数据迁移」的文件。
     *
     * <p>券清单每次冷启动都必须重扫（v3.52 的硬要求），验证码缓存 90 秒就过期；
     * 把这两类旧数据搬给新账号，等于让用户看到上一个账号的券、或填进一个过期码。
     */
    private static final Set<String> NO_MIGRATE = new HashSet<>(
            java.util.Arrays.asList("coupon", "smscode"));

    private AccountScope() {
    }

    /** 当前登录账号的 UID；读不到返回空串。 */
    static String uid() {
        long now = System.currentTimeMillis();
        String hit = cachedUid;
        if (hit != null && now - cachedAt < TTL_MS) {
            return hit;
        }
        Context ctx = HookLog.context();
        String live = "";
        try {
            // 10086.cn 是任务接口的主域；UID 也可能挂在 wx.10086.cn 下，兜底再找一遍。
            live = norm(CookieStore.get("10086.cn", "UID"));
            if (live.isEmpty()) {
                live = norm(CookieStore.get("UID"));
            }
        } catch (Throwable ignored) {
        }
        if (!live.isEmpty()) {
            String old = persistedUid(ctx);
            String ph = SsoLogin.maskedPhone();
            if (!old.isEmpty() && !old.equals(live)) {
                // v3.73：UID 会漂移。11:01:52 登录成功时是 mhrze7e12968，
                // 11:01:54 心跳读到 mhrz0ad1feed，手机号前后都是 139****6891 ——
                // 同一个人，只是服务端在不同活动域下发了不同 UID。
                // 老逻辑一律当切号：清券 + WebSession.end() 把正在跑的任务页销毁，
                // 签到脚本随后 20 秒读到的 body 全是空串，最后报「页面没进对」。
                String lastPh = persistedPhone(ctx);
                // v3.94：23:35 日志实证 —— 一键跑到一半又报「检测到账号切换
                // mhrz68b1d081 → mhrz4edfe6c3」，用户确认切号在按一键之前就完成。
                // 那是服务端下发的匿名 UID 漂移，而 SSO 早期 maskedPhone() 返回空串；
                // 旧判据 samePhone 要求 ph 非空 → 保护直接失效 → 判成切号 →
                // check() 里 WebSession.end() 把正在跑的页面/票全清、券进度清空，
                // 后面 5 个活动全变「页面未登录 / 停在登录页 / 403 / 访问受限」。
                // 现在：只有新旧号码都识别到、且确实不同，才算真换号；
                // 号码读不到(任一为空)或相同，一律当同账号 UID 漂移，不判切号。
                boolean knownBoth = !ph.isEmpty() && !lastPh.isEmpty();
                boolean realSwitch = knownBoth && !ph.equals(lastPh);
                if (realSwitch) {
                    switchedFrom = old;
                    HookLog.logcat("account switched: " + lastPh + " -> " + ph
                            + " (" + shortOf(old) + " -> " + shortOf(live) + ")");
                } else {
                    HookLog.logcat("uid drift (same account, phone "
                            + (ph.isEmpty() ? "未识别" : ph) + "): "
                            + shortOf(old) + " -> " + shortOf(live));
                }
            }
            if (!live.equals(old)) {
                persistUid(ctx, live);
            }
            if (!ph.isEmpty()) {
                persistPhone(ctx, ph);
            }
            cachedUid = live;
        } else {
            // 冷启动早期 Cookie 还没回灌：沿用上次记住的，避免同一账号在
            // 「有 UID / 无 UID」两个命名空间之间来回切，把数据劈成两半。
            cachedUid = persistedUid(ctx);
        }
        cachedAt = now;
        return cachedUid == null ? "" : cachedUid;
    }

    /** 当前账号的短标识（UID 前 12 位），日志里用它区分账号。 */
    static String tag() {
        return shortOf(uid());
    }

    /**
     * 账号命名空间下的 SharedPreferences 名。
     *
     * <p>模块进程（界面）读不到目标 App 的 Cookie，它的「当前账号」由心跳里的
     * uid 字段同步，存在 {@link AppPrefs}；目标进程直接用 Cookie。两边各取各的。
     */
    static String prefsName(Context ctx, String base) {
        return prefsNameFor(ctx, base, accountUid(ctx));
    }

    static String prefsNameFor(Context ctx, String base, String uid) {
        String u = uid == null ? "" : uid.trim();
        if (u.isEmpty()) {
            return base;
        }
        String name = base + "__" + shortOf(u);
        migrate(ctx, base, name);
        if (ctx != null) {
            syncScope(ctx, u);
        }
        return name;
    }

    static SharedPreferences prefs(Context ctx, String base) {
        return ctx.getSharedPreferences(prefsName(ctx, base), Context.MODE_PRIVATE);
    }

    /** 目标进程用 Cookie，模块进程用界面同步过来的账号。 */
    static String accountUid(Context ctx) {
        if (ctx != null) {
            try {
                if (AppPrefs.MODULE_PACKAGE.equals(ctx.getPackageName())) {
                    return AppPrefs.getCurrentUid(ctx);
                }
            } catch (Throwable ignored) {
            }
        }
        return uid();
    }

    /**
     * 账号切换后的清理。返回 true 表示这次确实换了账号。
     *
     * <p>只清「短时任务状态」：券进度、验证码缓存。任务完成记录按账号分文件存，
     * 不需要清 —— 切回旧账号就该看到旧账号自己的记录。
     */
    static boolean check(Context ctx) {
        String from = switchedFrom;
        if (from.isEmpty()) {
            return false;
        }
        // v3.73：任务正在跑的时候不能拆页面，也不能现在就把标记清掉 ——
        // 心跳 15 秒一次，正好在签到中途把 WebView 销毁，脚本读到空 body、
        // 白跑一趟。留着标记，等任务收尾后的下一次心跳再收拾。
        if (WebSession.held()) {
            HookLog.logcat("account switched: deferred (task running)");
            return false;
        }
        switchedFrom = "";
        Context c = ctx != null ? ctx : HookLog.context();
        if (c != null) {
            try {
                CouponStore.clearProgress(c);
            } catch (Throwable ignored) {
            }
        }
        // v3.70：切号必须把活动页「现场」一起丢掉。
        // 复用旧 WebView 时，页面 localStorage 里还留着上一个号的 ssoId，
        // wisdom 任务会直接拿旧身份跑 —— 09:46 那次 139 的号跑出 198 的
        // 「今天已签到」就是这条路径。销毁后下次 begin 重建页面，
        // 页面会自己用 h5token 重新换一次 ssoId，身份才跟着换过来。
        try {
            WebSession.end();
            HookLog.logcat("account switched: web session dropped");
        } catch (Throwable ignored) {
        }
        String now = uid();
        HookLog.log("ACCOUNT", "检测到账号切换",
                "上一账号: " + shortOf(from)
                        + "\n当前账号: " + shortOf(now)
                        + "\n两套数据独立存放：任务完成记录、券清单、验证码缓存、活动进度都不复用。");
        return true;
    }

    /**
     * r15 P0：同账号 UID 漂移时，把旧分片今天的记录接到新分片。
     *
     * <p>11-12 19:11 一键跑完 9 项（UID {@code mhrz44b292…}），19:29 服务端在
     * 另一个活动域下发了 {@code mhrzd2e29b…}，手机号仍是 {@code 198****6742}。
     * 分片名按 UID 前 12 位生成，于是界面读到一份全新的空状态：9 项全变 ○，
     * 模块又会把已做过的活动重跑一遍，白花时间还重复发抽奖请求。
     * {@code migrate()} 只从「无后缀的旧格式」搬一次，管不了这种分片间漂移。
     *
     * <p>这里在所有 prefs 访问的必经入口 {@link #prefsNameFor} 检测分片变化，
     * 目标进程和模块界面进程都会各自执行一次；只接「当日」与「最近结果时间」
     * 类 key，目标分片已有的值一律不覆盖，券清单和验证码缓存不参与。
     */
    private static void syncScope(Context ctx, String uid) {
        if (ctx == null || uid == null || uid.isEmpty() || uid.equals(scopedUid)) {
            return;
        }
        // 先占位再干活：下面的 HookLog.log 内部也可能读 prefs，避免同一进程重入。
        scopedUid = uid;
        try {
            SharedPreferences g = ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE);
            String prev = g.getString(KEY_SCOPE_UID, "");
            String phone = currentPhone(ctx);
            if (phone != null && !phone.isEmpty()) {
                g.edit().putString(KEY_SCOPE_PHONE + shortOf(uid), phone).apply();
            }
            if (!prev.isEmpty() && !prev.equals(uid)) {
                String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
                String flag = "carry_" + shortOf(prev) + "_" + shortOf(uid) + "_" + day;
                if (!g.getBoolean(flag, false)) {
                    boolean same = sameAccountForScope(g, prev, phone);
                    int moved = same ? carryAll(ctx, prev, uid, day) : 0;
                    g.edit().putBoolean(flag, true).apply();
                    HookLog.log("ACCOUNT", "UID 漂移 · 当日状态接续",
                            "上一分片: " + shortOf(prev) + "\n当前分片: " + shortOf(uid)
                                    + "\n判据: " + (same ? "同手机号（或号码未识别，保守按同账号）" : "号码不同，按真切号处理")
                                    + "\n接续文件数: " + moved
                                    + "\n说明: 服务端在不同活动域下发不同 UID 时，当日完成记录不再清零。");
                }
            }
            g.edit().putString(KEY_SCOPE_UID, uid).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 两个分片是否属于同一个手机号。号码读不到时保守按同账号处理。 */
    private static boolean sameAccountForScope(SharedPreferences g, String prev, String phone) {
        String prevPhone = "";
        try {
            prevPhone = g.getString(KEY_SCOPE_PHONE + shortOf(prev), "");
        } catch (Throwable ignored) {
        }
        if (!prevPhone.isEmpty() && phone != null && !phone.isEmpty()) {
            return prevPhone.equals(phone);
        }
        // SSO 早期 maskedPhone() 常返回空串，恰好是匿名 UID 漂移高发期。
        // 只接当天状态、不接券/验证码，保守接续的风险可控。
        return true;
    }

    private static String currentPhone(Context ctx) {
        try {
            if (ctx != null && AppPrefs.MODULE_PACKAGE.equals(ctx.getPackageName())) {
                return AppPrefs.getCurrentPhone(ctx);
            }
        } catch (Throwable ignored) {
        }
        try {
            return SsoLogin.maskedPhone();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 把旧分片里「当日」相关的 key 复制到新分片；已存在的不覆盖。 */
    private static int carryAll(Context ctx, String fromUid, String toUid, String day) {
        int moved = 0;
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files == null) {
                return 0;
            }
            String suffix = "__" + shortOf(fromUid);
            String newSuffix = "__" + shortOf(toUid);
            for (File f : files) {
                String fn = f.getName();
                if (!fn.endsWith(".xml")) {
                    continue;
                }
                String oldName = fn.substring(0, fn.length() - 4);
                if (GLOBAL.equals(oldName) || !oldName.endsWith(suffix)) {
                    continue;
                }
                String base = oldName.substring(0, oldName.length() - suffix.length());
                if (NO_MIGRATE.contains(base)) {
                    continue;
                }
                String newName = base + newSuffix;
                if (newName.equals(oldName)) {
                    continue;
                }
                try {
                    SharedPreferences from = ctx.getSharedPreferences(oldName, Context.MODE_PRIVATE);
                    SharedPreferences to = ctx.getSharedPreferences(newName, Context.MODE_PRIVATE);
                    Map<String, ?> all = from.getAll();
                    if (all == null || all.isEmpty()) {
                        continue;
                    }
                    SharedPreferences.Editor e = to.edit();
                    boolean changed = false;
                    for (Map.Entry<String, ?> en : all.entrySet()) {
                        String k = en.getKey();
                        boolean daily = k.contains(day) || k.startsWith("result_at_")
                                || k.startsWith("task_at_") || k.startsWith("last_")
                                || k.contains("entry");
                        if (!daily || to.contains(k)) {
                            continue;
                        }
                        Object v = en.getValue();
                        if (v instanceof Boolean) {
                            e.putBoolean(k, (Boolean) v);
                        } else if (v instanceof Integer) {
                            e.putInt(k, (Integer) v);
                        } else if (v instanceof Long) {
                            e.putLong(k, (Long) v);
                        } else if (v instanceof Float) {
                            e.putFloat(k, (Float) v);
                        } else if (v instanceof String) {
                            e.putString(k, (String) v);
                        } else if (v instanceof Set) {
                            @SuppressWarnings("unchecked")
                            Set<String> set = (Set<String>) v;
                            e.putStringSet(k, set);
                        } else {
                            continue;
                        }
                        changed = true;
                    }
                    if (changed) {
                        e.apply();
                        moved++;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return moved;
    }

    /** 把旧的无后缀数据搬给「第一个识别到的账号」，每个文件只搬一次。 */
    private static void migrate(Context ctx, String base, String target) {
        if (ctx == null || NO_MIGRATE.contains(base)) {
            return;
        }
        synchronized (MIGRATED) {
            if (!MIGRATED.add(target)) {
                return;
            }
        }
        try {
            SharedPreferences g = ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE);
            String flag = KEY_MIGRATED + base;
            if (g.getBoolean(flag, false)) {
                return;
            }
            SharedPreferences from = ctx.getSharedPreferences(base, Context.MODE_PRIVATE);
            Map<String, ?> all = from.getAll();
            if (all == null || all.isEmpty()) {
                g.edit().putBoolean(flag, true).apply();
                return;
            }
            SharedPreferences to = ctx.getSharedPreferences(target, Context.MODE_PRIVATE);
            Map<String, ?> exists = to.getAll();
            if (exists != null && !exists.isEmpty()) {
                g.edit().putBoolean(flag, true).apply();
                return;
            }
            SharedPreferences.Editor e = to.edit();
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                Object v = en.getValue();
                if (v instanceof Boolean) {
                    e.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    e.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    e.putLong(k, (Long) v);
                } else if (v instanceof Float) {
                    e.putFloat(k, (Float) v);
                } else if (v instanceof String) {
                    e.putString(k, (String) v);
                } else if (v instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<String> set = (Set<String>) v;
                    e.putStringSet(k, set);
                }
            }
            e.apply();
            g.edit().putBoolean(flag, true).apply();
            HookLog.logcat("account data migrated: " + base + " -> " + target);
        } catch (Throwable ignored) {
        }
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                sb.append(ch);
            }
        }
        String v = sb.toString();
        // UID 是 32 位 hex；太短的不像账号标识（可能是空值或占位符），宁可不认。
        return v.length() < 8 || v.length() > 64 ? "" : v;
    }

    static String shortOf(String uid) {
        if (uid == null) {
            return "未知";
        }
        return uid.length() <= 12 ? uid : uid.substring(0, 12);
    }

    private static String persistedUid(Context ctx) {
        if (ctx == null) {
            return "";
        }
        try {
            return ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)
                    .getString(KEY_UID, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void persistUid(Context ctx, String uid) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE).edit()
                    .putString(KEY_UID, uid)
                    .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static String persistedPhone(Context ctx) {
        if (ctx == null) {
            return "";
        }
        try {
            return ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)
                    .getString(KEY_PHONE, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void persistPhone(Context ctx, String phone) {
        if (ctx == null || phone == null || phone.isEmpty()) {
            return;
        }
        try {
            ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PHONE, phone)
                    .apply();
        } catch (Throwable ignored) {
        }
    }
}
