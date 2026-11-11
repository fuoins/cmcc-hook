package com.cmcchook;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

/**
 * v4.0.1：在「中国移动 App 主进程」里读短信验证码。
 *
 * <p>为什么不再只走模块自己 / 电话进程：
 * <ul>
 *   <li>模块 App（{@code com.cmcchook}）是第三方：小米 MIUI / HyperOS 的「验证码安全
 *       保护」是<b>系统级开关</b>，会把验证码短信从第三方 App 的静态
 *       {@code SMS_RECEIVED} 广播和 {@code content://sms/inbox} 里摘掉 —— 光给
 *       RECEIVE_SMS / READ_SMS 权限不够。</li>
 *   <li>电话进程框架层通道（{@link PhoneSmsHook}）在短信分发前拦 PDU，是
 *       XposedSmsCode 的正解，但必须让 LSPosed 额外勾选「电话服务 /
 *       {@code com.android.phone} / {@code com.xiaomi.phone}」作用域并重启手机；
 *       16:44 日志里 {@code SMSDIAG} 一条都没有，证明这条通道压根没被注入。
 *       v4.0.4 曾把 {@code com.xiaomi.phone} 误删（正好漏掉小米），v4.10.0 按
 *       XposedSmsCode 官方 scope.list 加回。</li>
 * </ul>
 *
 * <p>本类换一条最短的路：模块本来就 hook 在<b>中国移动 App 主进程</b>里（日志
 * {@code [APP-START] / [HOOK]} 铁证），而这个 App 自己声明了
 * {@code RECEIVE_SMS / READ_SMS / WRITE_SMS / SEND_SMS} 全套权限，券页面
 * （{@code touch.10086.cn/i/mobile/mycoupons.html}）也跑在这个进程的 WebView 里。
 * 于是就在<b>这个进程</b>里动态注册 {@code SMS_RECEIVED}。
 *
 * <p><b>v4.0.4 更正</b>：v4.0.1 的注释曾断言「小米的验证码防护针对第三方偷读，
 * 不会拦 App 读自己触发的验证码」——这个论断<u>没有证据且已证伪</u>。小米的
 * 「验证码安全保护」对所有非默认短信应用生效，中国移动 App 也在其列；这正是
 * ColorOS 正常、小米不正常的差别。所以本通道<b>同样需要用户在系统设置里关闭
 * 验证码安全保护 / 给中国移动放行「通知类短信」</b>，模块不会替用户改系统开关。
 * 本通道的价值是：不需要勾电话服务作用域、不需要重启手机，只要系统开关一开，
 * 强停重开一次中国移动 App 就能收码。
 *
 * <p>好处：不依赖 LSPosed 勾选「电话服务」作用域、不依赖重启手机、不依赖模块自启动。
 * 券核销时这个进程一定在前台存活，动态接收器必然有效。
 *
 * <p>只读、不 abortBroadcast、不改短信库、不写入任何东西。拿到正文后用显式广播
 * {@link PhoneSmsReceiver#ACTION_SMS_BODY} 送回模块 App，交给 {@link SmsCode#onSms}
 * 走原有的白名单 / 去重 / 等码窗口 / 账号隔离 / 投递链 —— 不重新造判定轮子，
 * 与模块 App 静态广播、电话进程框架层通道共用一份去重表，一码只投一次。
 *
 * <p>每条到达的短信都写一条<b>脱敏</b>诊断日志（不打印正文、不打印验证码），
 * 让下一份完整日志能自证：广播到底进没进 App 进程、有没有命中兑换类白名单。
 */
final class AppSmsHook {

    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    /** 全局防重复注册：Application.onCreate 可能被 hook 多次回调。 */
    private static volatile boolean installed;

    private AppSmsHook() {
    }

    /** 在 Application.onCreate 之后拿到 App Context 时调用。 */
    static void install(final Context app) {
        if (app == null || installed) {
            return;
        }
        installed = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        handle(c, intent);
                    } catch (Throwable ignored) {
                    }
                }
            };
            IntentFilter filter = new IntentFilter(SMS_RECEIVED);
            filter.setPriority(999);
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13(API 33)+ 动态注册必须显式声明 exported；SMS_RECEIVED 是
                // 系统（电话框架）发来的广播，接收器必须 RECEIVER_EXPORTED 才收得到。
                // 不带 flag 会抛 SecurityException，通道直接失效。
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            boolean hasRecv = app.getPackageManager().checkPermission(
                    Manifest.permission.RECEIVE_SMS, app.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasRead = app.getPackageManager().checkPermission(
                    Manifest.permission.READ_SMS, app.getPackageName())
                    == PackageManager.PERMISSION_GRANTED;
            HookLog.log("SMSCODE", "App进程短信通道已安装(v4.0.1)",
                    "接收器注册在【中国移动 App 主进程】，不是模块自己、也不是系统电话进程。\n"
                            + "进程: " + HookLog.processName() + "\n"
                            + "Android SDK: " + Build.VERSION.SDK_INT
                            + " · 注册方式: " + (Build.VERSION.SDK_INT >= 33
                            ? "RECEIVER_EXPORTED" : "旧版两参数") + "\n"
                            + "中国移动 App 持有 RECEIVE_SMS 权限: " + hasRecv + "\n"
                            + "中国移动 App 持有 READ_SMS 权限: " + hasRead + "\n"
                            + "────────────────────────\n"
                            + "判读（下次券核销收码时看本日志页）：\n"
                            + "· 出现「App进程收到短信」→ 广播进了 App 进程，通道通了；\n"
                            + "  后面应紧跟「自动读到验证码」并自动填入页面。\n"
                            + "· 连「App进程收到短信」都没有，但券页面确实发了码 →\n"
                            + "  ① 看上面 RECEIVE_SMS 是否已授予；为 false 就去系统设置给【中国移动】\n"
                            + "     开短信权限（不是给模块开），再强停重开一次中国移动 App；\n"
                            + "  ② 小米 / HyperOS 还有系统级「验证码安全保护」，光有权限也会拦：\n"
                            + "     短信 App → 设置 → 关闭「验证码安全保护」；\n"
                            + "     设置 → 应用管理 → 中国移动 → 权限里放行「通知类短信」；\n"
                            + "     授权管理 → 自启动允许中国移动，电池别限制后台。\n"
                            + "     这是 ColorOS 正常、小米不正常的根因，模块不代改系统开关。\n"
                            + "· 有「收到短信」却标「非兑换类」→ 短信文案变了，把完整日志发回按真实正文调白名单。\n"
                            + "· 这条通道不依赖 LSPosed 勾选「电话服务」作用域，也不需要重启手机。");
        } catch (Throwable t) {
            HookLog.log("SMSCODE", "App进程短信通道安装失败", String.valueOf(t));
        }
    }

    /** 拼出完整正文（长短信拆成多条 PDU），脱敏记一条日志，再把正文送回模块 App。 */
    private static void handle(Context ctx, Intent intent) {
        if (intent == null || !SMS_RECEIVED.equals(intent.getAction())) {
            return;
        }
        Bundle b = intent.getExtras();
        Object raw = b == null ? null : b.get("pdus");
        if (!(raw instanceof Object[])) {
            return;
        }
        String format = b.getString("format");
        StringBuilder body = new StringBuilder();
        long at = 0L;
        for (Object pdu : (Object[]) raw) {
            if (!(pdu instanceof byte[])) {
                continue;
            }
            try {
                SmsMessage msg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? SmsMessage.createFromPdu((byte[]) pdu, format)
                        : SmsMessage.createFromPdu((byte[]) pdu);
                if (msg == null) {
                    continue;
                }
                if (msg.getMessageBody() != null) {
                    body.append(msg.getMessageBody());
                }
                if (at == 0L) {
                    at = msg.getTimestampMillis();
                }
            } catch (Throwable ignored) {
            }
        }
        String text = body.toString();
        if (text.isEmpty()) {
            return;
        }
        // 脱敏诊断：只记长度与是否命中白名单，绝不打印正文 / 验证码 / 手机号。
        boolean hit = !SmsCode.extract(text).isEmpty();
        HookLog.log("SMSCODE", "App进程收到短信 · " + (hit ? "命中兑换类白名单" : "非兑换类，忽略"),
                "在中国移动 App 主进程内收到 SMS_RECEIVED 广播。\n"
                        + "正文长度 " + text.length() + " 字（按隐私要求不打印正文/验证码）。\n"
                        + "命中中国移动兑换类白名单: " + hit + "\n"
                        + "短信时间: " + new java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date(at > 0 ? at : System.currentTimeMillis()))
                        + "\n正文已用显式广播送回模块 App，由 SmsCode 去重后自动填入券页面。");
        try {
            Intent out = new Intent(PhoneSmsReceiver.ACTION_SMS_BODY);
            out.setPackage(PhoneSmsReceiver.MODULE_PACKAGE);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(PhoneSmsReceiver.EXTRA_BODY, text);
            out.putExtra(PhoneSmsReceiver.EXTRA_AT,
                    at > 0L ? at : System.currentTimeMillis());
            out.putExtra(PhoneSmsReceiver.EXTRA_SRC, "App进程");
            out.putExtra(PhoneSmsReceiver.EXTRA_ACTION, SMS_RECEIVED);
            ctx.sendBroadcast(out);
        } catch (Throwable t) {
            HookLog.log("SMSCODE", "App进程短信回传失败", String.valueOf(t));
        }
    }
}
