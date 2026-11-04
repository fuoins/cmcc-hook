package com.qoder.cmcchook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ActivityHooks {
    private static volatile boolean appStartedLogged;

    private ActivityHooks() {
    }

    static void install(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(Application.class, "attachBaseContext",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Application) {
                                HookLog.setApplication((Application) param.thisObject);
                                CrashHooks.ensureDefaultHandler();
                            }
                        }
                    });
        } catch (Throwable e) {
            reportOnce("application:attach", "Application.attachBaseContext hook failed: " + e);
        }

        try {
            XposedBridge.hookAllMethods(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Application) {
                                Application app = (Application) param.thisObject;
                                HookLog.setApplication(app);
                                CrashHooks.ensureDefaultHandler();
                                logAppStart(app);
                                TaskReceiver.install(app);
                            }
                        }
                    });
        } catch (Throwable e) {
            reportOnce("application:create", "Application.onCreate hook failed: " + e);
        }

        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!HookPrefs.ui()) {
                        return;
                    }
                    try {
                        Activity activity = (Activity) param.thisObject;
                        logActivity("ACTIVITY", "页面打开 · " + activity.getClass().getSimpleName(),
                                activity, param.args.length > 0 ? param.args[0] : null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            reportOnce("activity:create", "Activity.onCreate hook failed: " + e);
        }

        try {
            XposedBridge.hookAllMethods(Activity.class, "onNewIntent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // App 已经在前台时，startActivity 走的是 onNewIntent，onResume 不会再触发，
                    // v1.8 的按钮就是卡在这里。补一次触发。
                    try {
                        TaskRunner.onForeground((Activity) param.thisObject);
                    } catch (Throwable e) {
                        HookLog.logcat("task trigger on new intent failed: " + e);
                    }
                    if (!HookPrefs.ui()) {
                        return;
                    }
                    try {
                        Activity activity = (Activity) param.thisObject;
                        logActivity("ACTIVITY", "新 Intent · " + activity.getClass().getSimpleName(),
                                activity, param.args.length > 0 ? param.args[0] : null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable e) {
            reportOnce("activity:newintent", "Activity.onNewIntent hook failed: " + e);
        }

        // 任务触发点：每次页面回到前台都看一眼有没有待执行的指令。
        // 模块界面点「一键签到」会先写 settings 序号再把 App 拉到前台，
        // 所以这里的 onResume 一定会被触发；自动签到也挂在这。
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        TaskRunner.onForeground((Activity) param.thisObject);
                    } catch (Throwable e) {
                        HookLog.logcat("task trigger failed: " + e);
                    }
                }
            });
        } catch (Throwable e) {
            reportOnce("activity:resume", "Activity.onResume hook failed: " + e);
        }
    }

    private static void logAppStart(Application app) {
        if (appStartedLogged) {
            return;
        }
        appStartedLogged = true;
        StringBuilder detail = new StringBuilder();
        detail.append("包名: ").append(app.getPackageName()).append('\n');
        detail.append("进程: ").append(HookLog.context() == null ? "" : processName()).append('\n');
        detail.append("版本: ").append(targetVersion(app)).append('\n');
        detail.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append('\n');
        detail.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        detail.append("模块: ").append(HookLog.MODULE_VERSION).append('\n');
        detail.append("注入进程: ").append(HookLog.processName()).append('\n');
        detail.append("抓取开关: H5=").append(HookPrefs.h5())
                .append(" 原生=").append(HookPrefs.nativeHttp())
                .append(" 页面=").append(HookPrefs.ui())
                .append(" 存储=").append(HookPrefs.storage())
                .append(" 仅10086=").append(HookPrefs.only10086())
                .append('\n');
        try {
            detail.append("Xposed: ").append(XposedBridge.getXposedVersion()).append('\n');
        } catch (Throwable ignored) {
        }
        detail.append("说明: 从这里开始记录，APP_START 表示模块已注入目标进程。");
        HookLog.log("APP-START", "中国移动 App 启动 / 模块已注入", detail.toString());
        // 读取上一次进程退出的系统记录：可区分 Java 崩溃、native 崩溃、被信号杀死等。
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                ExitInfoApi30.log(app);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return Application.getProcessName();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String targetVersion(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable e) {
            return "";
        }
    }

    private static void logActivity(String tag, String summary, Activity activity, Object bundle) {
        StringBuilder detail = new StringBuilder();
        detail.append("Activity: ").append(activity.getClass().getName()).append('\n');
        try {
            Intent intent = activity.getIntent();
            detail.append("Intent: ").append(intent == null ? "null" : intent.toString())
                    .append('\n');
            if (intent != null) {
                detail.append("action: ").append(intent.getAction()).append('\n');
                detail.append("data: ").append(intent.getDataString()).append('\n');
                detail.append("type: ").append(intent.getType()).append('\n');
                detail.append("component: ").append(intent.getComponent()).append('\n');
                detail.append("flags: 0x")
                        .append(Integer.toHexString(intent.getFlags())).append('\n');
                Set<String> categories = intent.getCategories();
                if (categories != null) {
                    detail.append("categories: ").append(categories).append('\n');
                }
                detail.append("extras:\n").append(bundleText(intent.getExtras()));
            }
        } catch (Throwable e) {
            detail.append("Intent 读取失败: ").append(e).append('\n');
        }
        if (bundle instanceof Bundle) {
            detail.append("savedState:\n").append(bundleText((Bundle) bundle));
        }
        HookLog.log(tag, summary, detail.toString());
    }

    private static String bundleText(Bundle bundle) {
        if (bundle == null) {
            return "  (null)\n";
        }
        StringBuilder sb = new StringBuilder();
        try {
            Set<String> keys = bundle.keySet();
            int count = 0;
            for (String key : keys) {
                if (count++ >= 120) {
                    sb.append("  ...\n");
                    break;
                }
                Object value;
                try {
                    value = bundle.get(key);
                } catch (Throwable e) {
                    value = "<" + e.getClass().getSimpleName() + ">";
                }
                String text = safeString(value);
                if (text.length() > 3000) {
                    text = text.substring(0, 3000) + "…";
                }
                sb.append("  ").append(key).append(" = ").append(text).append('\n');
            }
            if (count == 0) {
                sb.append("  (empty)\n");
            }
        } catch (Throwable e) {
            sb.append("  <").append(e.getClass().getSimpleName()).append(">\n");
        }
        return sb.toString();
    }

    private static String safeString(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            if (value instanceof String[]) {
                return Arrays.toString((String[]) value);
            }
            if (value instanceof int[]) {
                return Arrays.toString((int[]) value);
            }
            if (value instanceof long[]) {
                return Arrays.toString((long[]) value);
            }
            return String.valueOf(value);
        } catch (Throwable e) {
            return "<" + value.getClass().getName() + ">";
        }
    }

    private static void reportOnce(String key, String message) {
        if (REPORTED.put(key, Boolean.TRUE) == null) {
            HookLog.logcat(message);
        }
    }

    private static final java.util.Map<String, Boolean> REPORTED =
            new java.util.concurrent.ConcurrentHashMap<>();
}
