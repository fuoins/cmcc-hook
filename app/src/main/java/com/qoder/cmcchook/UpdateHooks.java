package com.qoder.cmcchook;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 升级弹窗拦截，适配中国移动 App 11.9.1（versionCode 119101）。
 *
 * 11.9.1 的调用链：
 *
 *   HomeFragment.finishAutoSlide
 *     └─ AutoSecondChecker.process
 *         └─ AbstractAppTabDialogChecker.check
 *             └─ UpgradeDialogChecker.process              ← ①
 *                 └─ CheckVersionHelper.showUpgradeDialog   ← ②
 *                     └─ AppUpgradeManager.showUpgradeDialog ← ③
 *                         └─ AppUpgradeDialogManager.showDialog ← ④
 *                             └─ android.media.QuourstGlaernong.<init>  ← 崩在这里
 *
 * QuourstGlaernong 是升级 SDK 里被改名的类，构造函数本身会抛异常，
 * 也就是说弹窗一旦创建就必崩。这里不去修它，而是在它上面四层全部设卡，
 * 让代码根本走不到那一行。
 *
 * 为什么不写死方法签名：没有 11.9.1 的反编译结果，参数类型可能被混淆，
 * 用 hookAllMethods 按方法名把同名重载一次性全挂上，改版也不容易失效。
 */
final class UpdateHooks {

    /** 弹窗链路入口，任意一个命中就能阻断整条链 */
    private static final String[][] DIALOG_ENTRIES = {
            {"com.mc10086.cmcc.presenter.apptable.checker.UpgradeDialogChecker", "process"},
            {"com.mc10086.cmcc.core.checkversion.CheckVersionHelper", "showUpgradeDialog"},
            {"com.cmccit.appupgrade.AppUpgradeManager", "showUpgradeDialog", "showDialog"},
            {"com.cmccit.appupgrade.utils.AppUpgradeDialogManager", "showDialog", "showUpgradeDialog"},
    };

    /** 版本检查入口，顺手掐掉，“发现新版本”的红点/角标也不会出现 */
    private static final String[][] CHECK_ENTRIES = {
            {"com.mc10086.cmcc.core.checkversion.CheckVersionHelper",
                    "checkVersion", "checkNewVersion", "doCheckVersion", "requestVersionInfo"},
            {"com.cmccit.appupgrade.AppUpgradeManager",
                    "checkVersion", "checkAppUpgrade", "startCheck", "checkUpgrade"},
    };

    /** 返回 boolean 的判定方法，统一给 false */
    private static final String[][] FLAG_ENTRIES = {
            {"com.cmccit.appupgrade.AppUpgradeManager",
                    "isNeedUpgrade", "isNeedUpdate", "isForceUpdate", "hasNewVersion"},
    };

    /**
     * 命中后直接给默认返回值，原方法体不执行。
     * 只处理 void 和基本类型；返回对象的方法一律放行，避免调用方 NPE。
     */
    private static final XC_MethodHook SKIP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Method method = (Method) param.method;
            Class<?> type = method.getReturnType();
            if (type == void.class) {
                param.setResult(null);
            } else if (type == boolean.class) {
                param.setResult(false);
            } else if (type == int.class) {
                param.setResult(0);
            } else if (type == long.class) {
                param.setResult(0L);
            } else if (type == short.class) {
                param.setResult((short) 0);
            } else if (type == byte.class) {
                param.setResult((byte) 0);
            } else if (type == char.class) {
                param.setResult((char) 0);
            } else if (type == float.class) {
                param.setResult(0f);
            } else if (type == double.class) {
                param.setResult(0d);
            }
        }
    };

    private UpdateHooks() {
    }

    static void install(ClassLoader cl) {
        int total = 0;
        for (String[] entry : DIALOG_ENTRIES) {
            total += hook(cl, entry);
        }
        for (String[] entry : CHECK_ENTRIES) {
            total += hook(cl, entry);
        }
        for (String[] entry : FLAG_ENTRIES) {
            total += hook(cl, entry);
        }
        HookLog.logcat("update hooks installed, methods=" + total);
    }

    private static int hook(ClassLoader cl, String[] entry) {
        Class<?> clazz = findClass(cl, entry[0]);
        if (clazz == null) {
            HookLog.logcat("update hook missing class: " + entry[0]);
            return 0;
        }
        int count = 0;
        for (int i = 1; i < entry.length; i++) {
            String name = entry[i];
            try {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, name, SKIP);
                if (unhooks == null || unhooks.isEmpty()) {
                    HookLog.logcat("update hook no match: " + entry[0] + "#" + name);
                } else {
                    count += unhooks.size();
                    HookLog.logcat("update hook ok: " + entry[0] + "#" + name + " x" + unhooks.size());
                }
            } catch (Throwable e) {
                HookLog.logcat("update hook failed: " + entry[0] + "#" + name + " " + e);
            }
        }
        return count;
    }

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable e) {
            return null;
        }
    }
}
