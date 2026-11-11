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
     * 短信相关进程（v4.10.0 对齐 XposedSmsCode 官方 {@code scope.list}）。
     *
     * <p>前两个是电话服务：AOSP 用 {@code com.android.phone}，小米 / HyperOS 还有
     * {@code com.xiaomi.phone}。XposedSmsCode 源码 {@code SmsHandlerHook} 明确定义了
     * {@code ANDROID_PHONE_PACKAGE} / {@code XIAOMI_PHONE_PACKAGE} 两个常量，
     * {@code isSmsHandlerPackage()} 两个都认 —— {@code InboundSmsHandler} 就跑在
     * 这两个进程里，是框架层取 PDU 的主通道。
     *
     * <p>后三个是短信库 / 短信 App：本项目不在这些进程 hook 框架类（找不到
     * {@code InboundSmsHandler} 会安全跳过），但会注册动态
     * {@code SMS_DELIVER / SMS_RECEIVED} 兜底接收器。它们是系统进程，不在小米
     * 「验证码安全保护」对第三方 App 的屏蔽范围内。
     *
     * <p><b>v4.0.4 的错误结论已纠正</b>：当时把 {@code com.xiaomi.phone} 当成
     * 「臆造包、设备上不存在」从作用域和判定里删掉，依据只是「HyperCeiler 清单里没有」
     * —— 那份清单本来就不覆盖全部系统进程。结果框架层通道只在
     * {@code com.android.phone} 安装，正好漏掉小米，这是小米一直读不到验证码的根因之一。
     * 对照 XposedSmsCode 源码后加回。
     */
    static final String[] SMS_PACKAGES = {
            "com.android.phone",
            "com.xiaomi.phone",
            "com.android.providers.telephony",
            "com.android.mms",
            "com.google.android.apps.messaging",
    };

    /** 是否短信相关进程：命中就在该进程装框架层/兜底通道，然后立刻 return。 */
    static boolean isPhonePackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : SMS_PACKAGES) {
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
