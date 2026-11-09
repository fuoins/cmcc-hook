package com.cmcchook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

/**
 * 系统短信广播接收器，只负责把正文交给 {@link SmsCode} 判断。
 *
 * <p>静态注册 {@code SMS_RECEIVED}：这条广播在 Android 8+ 的隐式广播限制白名单里，
 * 模块进程没起来时系统也会拉起来收，不依赖模块界面是否开着。
 * 参考 XposedSmsCode 的 {@code SmsMsg.fromIntent} 做法，用 PDU 拼出完整正文
 * （长短信会被拆成多条 PDU，必须全部拼起来再提码）。
 *
 * <p>这里不做拦截、不 abortBroadcast、不写库——短信照常进收件箱，用户随时能自己看。
 */
public final class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !SmsCode.SMS_RECEIVED.equals(intent.getAction())) {
            return;
        }
        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }
        Object[] pdus;
        try {
            Object raw = bundle.get("pdus");
            if (!(raw instanceof Object[])) {
                return;
            }
            pdus = (Object[]) raw;
        } catch (Throwable e) {
            return;
        }
        String format = bundle.getString("format");
        StringBuilder body = new StringBuilder();
        long timestamp = 0L;
        for (Object pdu : pdus) {
            if (!(pdu instanceof byte[])) {
                continue;
            }
            try {
                SmsMessage msg;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    msg = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    msg = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (msg == null) {
                    continue;
                }
                String part = msg.getMessageBody();
                if (part != null) {
                    body.append(part);
                }
                if (timestamp == 0L) {
                    timestamp = msg.getTimestampMillis();
                }
            } catch (Throwable ignored) {
            }
        }
        if (body.length() > 0) {
            SmsCode.onSms(context, body.toString(), timestamp);
        }
    }
}
