package com.cmcchook;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.lang.reflect.Field;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 短信相关系统进程里的短信入口（v4.10.0 对齐 XposedSmsCode 官方 scope.list）。
 *
 * <p>参考的是 XposedSmsCode 的 {@code SmsHandlerHook}：在 {@code com.android.phone}
 * / {@code com.xiaomi.phone} 进程里 hook 框架类
 * {@code com.android.internal.telephony.InboundSmsHandler}，拦
 * {@code dispatchIntent()} 里携带短信 PDU 的 Intent，从框架层拿正文，
 * 不依赖任何 App 层的短信权限、自启动或广播白名单。
 *
 * <p><b>为什么必须走框架层</b>：小米 MIUI / HyperOS 的「验证码安全保护」在系统层
 * 把验证码短信从第三方 App 的 {@link #SMS_RECEIVED} 广播和 {@code content://sms}
 * 里摘掉，光给 RECEIVE_SMS / READ_SMS 权限没用。而 {@code dispatchIntent()} 是
 * 短信刚进框架、还没分发给任何 App 那一刻，验证码保护还没生效 ——
 * 这正是 XposedSmsCode 能在定制 ROM 上拿到码的原理。
 * XposedSmsCode 只认 {@link #SMS_DELIVER_ACTION}（发给默认短信应用的那个），
 * 本实现同时兼容 {@link #SMS_RECEIVED}，覆盖部分 ROM 直接分发 SMS_RECEIVED 的情况。
 *
 * <p><b>v4.10.0 更正 v4.0.4 的错误结论</b>：v4.0.4 把 {@code com.xiaomi.phone}
 * 当成「臆造包、设备上不存在」删掉，唯一依据是「HyperCeiler 作用域清单里没有它」
 * —— 那份清单本来就不覆盖全部系统进程。对照 XposedSmsCode 官方
 * {@code META-INF/xposed/scope.list} 与 {@code SmsHandlerHook} 源码：它明确定义
 * {@code XIAOMI_PHONE_PACKAGE = "com.xiaomi.phone"}，且
 * {@code isSmsHandlerPackage()} 同时认 {@code com.android.phone} 与
 * {@code com.xiaomi.phone}。小米 / HyperOS 上 {@code InboundSmsHandler} 有可能
 * 跑在 {@code com.xiaomi.phone}，删掉它就正好漏掉小米 —— 这是「框架层通道装不上、
 * 小米一直读不到验证码」的根因之一。现已加回作用域，并把
 * {@code com.android.providers.telephony} / {@code com.android.mms} /
 * {@code com.google.android.apps.messaging} 一并纳入，让动态兜底接收器也能装在
 * 默认短信 App / Provider 进程里（它们是系统进程，不受验证码保护屏蔽）。
 *
 * <p>旧实现要点保留：构造函数与 {@code dispatchIntent} 都 hook，解析优先用
 * {@link Telephony.Sms.Intents#getMessagesFromIntent(Intent)}，手写
 * {@code createFromPdu} 只作兜底。</p>
 *
 * <p>必须如实说明的限制：XposedSmsCode 的 README 明写
 * 「此模块适用于偏原生的系统，其他第三方定制 Rom 可能不适用」。小米系统开关
 * （短信 App → 设置 → 验证码安全保护；以及给应用勾「通知类短信」）是系统策略，
 * 模块不会、也不应该去替用户改。所以本通道能装上 ≠ 一定能读到码，
 * 模块内另有「小米短信自检」引导把这几步写清楚。
 *
 * <p>安全边界（hook 的是电话进程，搞崩了会连累整机短信）：
 * <ul>
 *   <li><b>只读</b>：不 abortBroadcast、不 setResult、不改参数、不写短信库；</li>
 *   <li>所有逻辑包在 {@code try/catch(Throwable)}，异常绝不抛回框架；</li>
 *   <li>只把「短信正文 + 时间 + action」用显式广播送回模块 App，
 *       提取/白名单/去重/等待窗口/账号隔离全部在模块 App 的 {@link SmsCode} 里做；</li>
 *   <li>没找到 {@code InboundSmsHandler} 时还有动态广播兜底，两者都失败也不影响短信正常送达。</li>
 * </ul>
 *
 * <p>启用条件：LSPosed → 模块 → 作用域，除「中国移动」外还要勾「电话服务」
 * {@code com.android.phone} 和小米的 {@code com.xiaomi.phone}（系统应用需先打开
 * 「显示系统应用」），建议把短信 App / Provider 也勾上，然后<b>重启手机</b>。
 * 完整清单见 {@code arrays.xml} 的 {@code xposed_scope} 与
 * {@link PhoneSmsReceiver#SMS_PACKAGES}。
 */
public final class PhoneSmsHook {

    private static final String HANDLER_CLASS =
            "com.android.internal.telephony.InboundSmsHandler";
    private static final String SMS_RECEIVED =
            "android.provider.Telephony.SMS_RECEIVED";
    /** XposedSmsCode 只认这个：框架层把新短信交给默认短信应用的 Intent。 */
    private static final String SMS_DELIVER =
            "android.provider.Telephony.SMS_DELIVER";

    /** 同进程内防重复安装（LSPosed 对同一包可能回调多次 / 多个进程）。 */
    private static volatile boolean installed;

    /** v4.0.4：构造函数 hook 拿到的电话进程 Context，比反射找字段稳。 */
    private static volatile Context phoneContext;

    // v4.0 安装诊断：把这些状态回传模块日志页，回答「还得加东西吗」时不用再猜。
    private static volatile String diagPkg = "";
    private static volatile String diagProc = "";
    private static volatile String diagSdk = "";
    private static volatile String diagHandler = "未执行";
    private static volatile String diagConstructor = "未执行";
    private static volatile int diagHookCount = -1;
    private static volatile String diagFallback = "未执行";
    /** 实际拦到过的 action，验证到底走的是 SMS_DELIVER 还是 SMS_RECEIVED。 */
    private static volatile String diagLastAction = "（还没拦到短信）";
    private static volatile int diagRelayCount = 0;

    private PhoneSmsHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        installed = true;
        diagPkg = lpparam.packageName == null ? "" : lpparam.packageName;
        diagProc = lpparam.processName == null ? "" : lpparam.processName;
        diagSdk = String.valueOf(Build.VERSION.SDK_INT);
        XposedBridge.log("CMCC/PhoneSms installing in " + diagPkg + "/" + diagProc);
        try {
            hookSmsHandler(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("CMCC/PhoneSms hook SmsHandler failed: " + t);
        }
        try {
            registerFallbackReceiver();
        } catch (Throwable t) {
            XposedBridge.log("CMCC/PhoneSms fallback receiver failed: " + t);
        }
        // fallback 是异步线程，等 5 秒让它跑完再汇总回传模块日志页。
        Thread diag = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ignored) {
                }
                sendDiag();
            }
        }, "cmcc-phone-sms-diag");
        diag.setDaemon(true);
        diag.start();
    }

    /** 对齐 XposedSmsCode.SmsHandlerHook#hookSmsHandler：构造函数 + dispatchIntent 都 hook。 */
    private static void hookSmsHandler(ClassLoader classLoader) {
        Class<?> handler;
        try {
            handler = XposedHelpers.findClass(HANDLER_CLASS, classLoader);
            diagHandler = "已找到 " + HANDLER_CLASS;
        } catch (Throwable t) {
            diagHandler = "未找到（" + t + "）";
            XposedBridge.log("CMCC/PhoneSms handler class missing: " + t);
            return;
        }

        // ① 构造函数：XposedSmsCode 靠 param.args[1] 拿电话进程 Context。
        //    这里用 hookAllConstructors 遍历参数找 Context，不猜签名 ——
        //    InboundSmsHandler 的构造参数从 Android 7 到 15 换过好几轮。
        try {
            Set<XC_MethodHook.Unhook> cHooks = XposedBridge.hookAllConstructors(
                    handler, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                for (Object arg : param.args) {
                                    if (arg instanceof Context) {
                                        phoneContext = ((Context) arg).getApplicationContext();
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            diagConstructor = "已 hook " + (cHooks == null ? 0 : cHooks.size()) + " 个构造函数"
                    + (phoneContext != null ? " · Context 已拿到" : " · 等实例创建后拿 Context");
            XposedBridge.log("CMCC/PhoneSms constructors hooked: " + diagConstructor);
        } catch (Throwable t) {
            diagConstructor = "构造函数 hook 失败: " + t;
            XposedBridge.log("CMCC/PhoneSms constructor hook failed: " + t);
        }

        // ② dispatchIntent：hookAllMethods 覆盖各版本所有重载，遍历参数找带 pdus 的 Intent。
        //    比 XposedSmsCode 按 SDK 精确匹配签名更抗 ROM 改动（它 Android 10+ 也改成了
        //    getDeclaredMethods 模糊搜索，思路一致）。
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    handler, "dispatchIntent", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object[] args = param.args;
                                if (args == null) {
                                    return;
                                }
                                for (Object arg : args) {
                                    if (!(arg instanceof Intent)) {
                                        continue;
                                    }
                                    Intent in = (Intent) arg;
                                    String action = in.getAction();
                                    // 只认这两类短信 Intent，其它带 pdus 的（彩信/数据短信/
                                    // WAP PUSH）一律不碰，避免把无关内容灌进核销流程。
                                    if (!SMS_DELIVER.equals(action)
                                            && !SMS_RECEIVED.equals(action)) {
                                        continue;
                                    }
                                    Bundle b = in.getExtras();
                                    if (b == null || b.get("pdus") == null) {
                                        continue;
                                    }
                                    Parsed p = parse(in);
                                    if (p == null || p.body.isEmpty()) {
                                        continue;
                                    }
                                    diagLastAction = String.valueOf(action);
                                    Context ctx = contextOf(param.thisObject);
                                    if (ctx != null) {
                                        relay(ctx, p.body, p.at, action);
                                    }
                                }
                            } catch (Throwable t) {
                                // 绝不把异常抛回电话进程的框架调用栈。
                                XposedBridge.log("CMCC/PhoneSms read dispatch failed: " + t);
                            }
                        }
                    });
            diagHookCount = hooks == null ? 0 : hooks.size();
            XposedBridge.log("CMCC/PhoneSms dispatchIntent hooked: " + diagHookCount);
        } catch (Throwable t) {
            diagHookCount = 0;
            diagHandler = "dispatchIntent hook 失败: " + t;
            XposedBridge.log("CMCC/PhoneSms dispatchIntent hook failed: " + t);
        }
    }

    /**
     * 兜底：在电话进程里动态注册一个短信广播接收器。部分 ROM 会改
     * {@code InboundSmsHandler} 的类名/方法名导致上面的 hook 落空，
     * 但电话进程自己发的 SMS_RECEIVED，它自己一定能收到。
     * 只读、低优先级、不 abort。
     */
    private static void registerFallbackReceiver() {
        Thread t = new Thread(() -> {
            Context ctx = null;
            for (int i = 0; i < 40 && ctx == null; i++) {
                ctx = phoneContext != null ? phoneContext : currentApplication();
                if (ctx == null) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (ctx == null) {
                diagFallback = "电话进程无 Application Context，兜底跳过";
                XposedBridge.log("CMCC/PhoneSms no phone-process context, fallback skipped");
                return;
            }
            final Context app = ctx.getApplicationContext();
            try {
                android.content.BroadcastReceiver recv =
                        new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(Context c, Intent intent) {
                                try {
                                    String action = intent == null ? null : intent.getAction();
                                    if (!SMS_RECEIVED.equals(action)
                                            && !SMS_DELIVER.equals(action)) {
                                        return;
                                    }
                                    Parsed p = parse(intent);
                                    if (p != null && !p.body.isEmpty()) {
                                        diagLastAction = String.valueOf(action) + "(兜底)";
                                        relay(app, p.body, p.at, action);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        };
                IntentFilter filter = new IntentFilter(SMS_RECEIVED);
                filter.addAction(SMS_DELIVER);
                if (Build.VERSION.SDK_INT >= 33) {
                    // Android 13(API 33)+ 动态注册必须显式声明 exported 标志：
                    // targetSdk>=34 时不带 flag 会抛 SecurityException，兜底通道直接失效。
                    app.registerReceiver(recv, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(recv, filter);
                }
                diagFallback = "已注册 SMS_RECEIVED / SMS_DELIVER 兜底"
                        + (Build.VERSION.SDK_INT >= 33 ? "（RECEIVER_EXPORTED）" : "");
                XposedBridge.log("CMCC/PhoneSms fallback receiver registered");
            } catch (Throwable e) {
                diagFallback = "注册失败: " + e;
                XposedBridge.log("CMCC/PhoneSms fallback register failed: " + e);
            }
        }, "cmcc-phone-sms-fallback");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 从 intent 的 pdus 拼出完整正文。
     *
     * <p>v4.0.4：优先用 {@link Telephony.Sms.Intents#getMessagesFromIntent(Intent)}，
     * 与 XposedSmsCode 的 {@code SmsMsg.fromIntent} 一致 —— 系统 API 会自动处理
     * {@code format}（3GPP / 3GPP2）和多段长短信，比手写 createFromPdu 稳。
     * 拿不到再退回手写解析。
     */
    private static Parsed parse(Intent intent) {
        if (intent == null) {
            return null;
        }
        String body = null;
        long at = 0L;
        try {
            SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs != null && msgs.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (SmsMessage msg : msgs) {
                    if (msg == null) {
                        continue;
                    }
                    if (msg.getMessageBody() != null) {
                        sb.append(msg.getMessageBody());
                    }
                    if (at == 0L) {
                        at = msg.getTimestampMillis();
                    }
                }
                body = sb.toString();
            }
        } catch (Throwable ignored) {
        }
        if (body == null || body.isEmpty()) {
            // 兜底：手写 PDU 解析（部分 ROM 的 getMessagesFromIntent 会返回空）。
            Bundle b = intent.getExtras();
            Object raw = b == null ? null : b.get("pdus");
            if (!(raw instanceof Object[])) {
                return null;
            }
            String format = b.getString("format");
            StringBuilder sb = new StringBuilder();
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
                        sb.append(msg.getMessageBody());
                    }
                    if (at == 0L) {
                        at = msg.getTimestampMillis();
                    }
                } catch (Throwable ignored) {
                }
            }
            body = sb.toString();
        }
        if (body.isEmpty()) {
            return null;
        }
        return new Parsed(body, at > 0L ? at : System.currentTimeMillis());
    }

    /** 显式广播送回模块 App；FLAG_INCLUDE_STOPPED_PACKAGES 保证进程没起来也能拉起。 */
    private static void relay(Context ctx, String body, long at, String action) {
        try {
            Intent out = new Intent(PhoneSmsReceiver.ACTION_SMS_BODY);
            out.setPackage(PhoneSmsReceiver.MODULE_PACKAGE);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            out.putExtra(PhoneSmsReceiver.EXTRA_BODY, body);
            out.putExtra(PhoneSmsReceiver.EXTRA_AT, at);
            out.putExtra(PhoneSmsReceiver.EXTRA_ACTION, action);
            out.putExtra(PhoneSmsReceiver.EXTRA_SRC, "电话进程框架层");
            ctx.sendBroadcast(out);
            diagRelayCount++;
        } catch (Throwable t) {
            XposedBridge.log("CMCC/PhoneSms relay failed: " + t);
        }
    }

    /**
     * v4.0.4：把框架层通道的安装结果回传模块日志页。
     *
     * <p>之前这些状态只在 LSPosed 的 XposedBridge.log 里，用户导出的模块完整日志
     * 一个字都没有，「还是不行」时完全没法判断装没装上。这条显式广播把诊断写进
     * 日志页，并把小米必须做的系统设置写清楚 —— 系统开关模块不代改，只能引导。
     */
    private static void sendDiag() {
        try {
            Context ctx = phoneContext != null ? phoneContext : currentApplication();
            if (ctx == null) {
                XposedBridge.log("CMCC/PhoneSms diag: no context");
                return;
            }
            Intent out = new Intent("com.cmcchook.action.SMS_DIAG");
            out.setComponent(new android.content.ComponentName(
                    PhoneSmsReceiver.MODULE_PACKAGE, "com.cmcchook.LogReceiver"));
            out.setPackage(PhoneSmsReceiver.MODULE_PACKAGE);
            out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            out.putExtra("summary", "框架层短信通道安装诊断 · " + diagPkg);
            StringBuilder d = new StringBuilder();
            d.append("这条诊断由被注入的短信相关进程发出 —— 出现即代表模块已注入进程 ").append(diagPkg).append("。\n");
            d.append("进程: ").append(diagProc).append('\n');
            d.append("Android SDK: ").append(diagSdk).append('\n');
            d.append("InboundSmsHandler: ").append(diagHandler).append('\n');
            d.append("构造函数: ").append(diagConstructor).append('\n');
            d.append("dispatchIntent hook 到的方法数: ").append(diagHookCount).append('\n');
            d.append("SMS_DELIVER / SMS_RECEIVED 兜底: ").append(diagFallback).append('\n');
            d.append("最近拦到的 action: ").append(diagLastAction).append('\n');
            d.append("已回传短信条数: ").append(diagRelayCount).append('\n');
            d.append("────────────────────────\n");
            d.append("判读：\n");
            d.append("· 连这条诊断都没出现 → 模块没被注入任何短信进程：LSPosed → 模块 → 作用域，\n");
            d.append("  除「中国移动」外勾「电话服务」com.android.phone，小米 / HyperOS 还要勾\n");
            d.append("  com.xiaomi.phone，建议连 com.android.providers.telephony / com.android.mms 一起勾。\n");
            d.append("  都是系统应用，先在 LSPosed 打开「显示系统应用」，勾完重启手机。\n");
            d.append("  （v4.0.4 曾把 com.xiaomi.phone 当成「不存在的包」删掉，正好漏掉小米；\n");
            d.append("   v4.10.0 按 XposedSmsCode 官方 scope.list 已加回。）\n");
            d.append("· 方法数为 0 且兜底也没注册 → 该 ROM 改了框架类名，需按它的框架再适配。\n");
            d.append("· 出现这条诊断、且券核销时「最近拦到的 action」有值、「已回传」增加，\n");
            d.append("  但模块仍没自动填码 → 短信正文到了模块，问题在白名单/等码窗口，\n");
            d.append("  补一份完整日志按 [SMSCODE] 条目核对。\n");
            d.append("────────────────────────\n");
            d.append("小米 / HyperOS 仍读不到验证码时，还要做系统设置（模块不代改）：\n");
            d.append("1) 短信 App → 右上角设置 → 关闭「验证码安全保护」；\n");
            d.append("2) 设置 → 应用设置 → 应用管理 → 中国移动 → 权限：允许「短信」，\n");
            d.append("   并在「通知类短信」里放行（部分版本在 安全中心 → 授权管理 里）；\n");
            d.append("3) 设置 → 应用设置 → 授权管理 → 自启动：允许中国移动；\n");
            d.append("   电池与性能里别限制它的后台运行。\n");
            d.append("这是系统级策略，XposedSmsCode 的 README 也明确写「适用于偏原生系统，\n");
            d.append("其他第三方定制 Rom 可能不适用」，模块无法替用户绕过。\n");
            d.append("ColorOS 正常、小米不正常，差别就在这个开关。");
            out.putExtra("detail", d.toString());
            ctx.sendBroadcast(out);
            XposedBridge.log("CMCC/PhoneSms diag sent");
        } catch (Throwable t) {
            XposedBridge.log("CMCC/PhoneSms diag failed: " + t);
        }
    }

    /**
     * 优先用构造函数 hook 缓存的 Context，再从 {@code InboundSmsHandler} 实例里
     * 反射找 Context 字段（字段名各版本不同，按类型找），最后退回
     * {@code ActivityThread.currentApplication()}。
     */
    private static Context contextOf(Object obj) {
        Context cached = phoneContext;
        if (cached != null) {
            return cached;
        }
        if (obj != null) {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable ignored) {
                    continue;
                }
                for (Field f : fields) {
                    if (!Context.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof Context) {
                            return (Context) v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return currentApplication();
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class Parsed {
        final String body;
        final long at;

        Parsed(String body, long at) {
            this.body = body;
            this.at = at;
        }
    }
}
