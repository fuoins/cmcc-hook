package com.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收「系统电话进程 / 中国移动 App 主进程」转来的短信正文，交给已有的
 * {@link SmsCode} 判定链。
 *
 * <p>本类故意不引用任何 Xposed API：它跑在模块自己的 App 进程里（那里没有
 * Xposed Bridge 的类），只有 {@link PhoneSmsHook} / {@link AppSmsHook} 跑在被注入的
 * 目标进程里。常量都是编译期常量，跨类引用不会触发对方类加载。
 *
 * <p>v4.0.4：对齐 XposedSmsCode 后，框架层通道从电话进程发
 * {@code SMS_DELIVER_ACTION} 或 {@code SMS_RECEIVED} 的正文；App 主进程通道
 * （{@link AppSmsHook}）从中国移动 App 发 {@code SMS_RECEIVED} 的正文。
 * 两条通道与模块 App 自己的静态广播共用 {@link SmsCode} 的去重表，一码只投一次。
 *
 * <p>小米 MIUI / HyperOS 上「验证码安全保护」会在系统层屏蔽第三方 App 的短信广播
 * 与短信库，光给 RECEIVE_SMS / READ_SMS 权限不够，所以才需要框架层通道。
 * 但系统开关本身需用户在短信 App 设置里关闭，模块不代改。
 */
public final class PhoneSmsReceiver extends BroadcastReceiver {

    /** 被注入进程 → 模块 App 的显式广播 action。 */
    static final String ACTION_SMS_BODY = "com.cmcchook.action.PHONE_SMS_BODY";
    static final String EXTRA_BODY = "body";
    static final String EXTRA_AT = "at";
    /** v4.0.4：短信来自哪个进程（"电话进程框架层" / "App进程"；空 = 旧版框架层通道）。 */
    static final String EXTRA_SRC = "src";
    /** v4.0.4：拦到的 Intent action（SMS_DELIVER / SMS_RECEIVED），用于验收时判读。 */
    static final String EXTRA_ACTION = "action";
    /** 显式指定包名，避免被系统的隐式广播管控拦掉。 */
    static final String MODULE_PACKAGE = "com.cmcchook";
    /**
     * 电话进程包名：原生与小米 / HyperOS 都是 {@code com.android.phone}。
     * v4.0.4 删除了臆造的 {@code com.xiaomi.phone}（设备上不存在，见 HyperCeiler
     * 官方作用域清单：电话服务 com.android.phone、短信 com.android.mms）。
     */
    static final String[] PHONE_PACKAGES = {"com.android.phone"};

    static boolean isPhonePackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : PHONE_PACKAGES) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SMS_BODY.equals(intent.getAction())) {
            return;
        }
        String body = intent.getStringExtra(EXTRA_BODY);
        if (body == null || body.isEmpty()) {
            return;
        }
        long at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis());
        String src = intent.getStringExtra(EXTRA_SRC);
        String action = intent.getStringExtra(EXTRA_ACTION);
        boolean fromFramework = "电话进程框架层".equals(src) || src == null || src.isEmpty();
        boolean fromApp = "App进程".equals(src);
        String channel = fromApp ? "中国移动 App 主进程"
                : (fromFramework ? "系统电话进程（框架层）" : src);
        // 留一条来源日志，验收时一眼能看出走的是哪条通道、拦的是哪个 action。
        try {
            LogStore.append(context, new LogEntry(System.currentTimeMillis(),
                    "SMSCODE", "短信到达 · " + channel,
                    "正文 " + body.length() + " 字 · 短信时间 "
                            + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(new java.util.Date(at))
                            + "\n拦到的 action: " + (action == null ? "（旧版未带）" : action)
                            + (fromApp
                            ? "\n由中国移动 App 主进程内的 SMS_RECEIVED 接收器读到，用 App 自己的短信权限。"
                            : "\n由电话进程 hook InboundSmsHandler.dispatchIntent 从框架层取到，"
                                    + "不依赖模块短信权限与自启动。")));
        } catch (Throwable ignored) {
        }
        try {
            SmsCode.onSms(context, body, at);
        } catch (Throwable e) {
            try {
                LogStore.append(context, new LogEntry(System.currentTimeMillis(),
                        "SMSCODE", "短信处理失败", String.valueOf(e)));
            } catch (Throwable ignored) {
            }
        }
    }
}
