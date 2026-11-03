package com.qoder.cmcchook;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * 也就是弹窗一旦创建就必崩。这里不去修它，而是在它上面四层全部设卡，
 * 让代码根本走不到那一行。
 *
 * 为什么不写死方法签名：没有 11.9.1 的反编译结果，参数类型可能被混淆，
 * 用 hookAllMethods 按方法名把同名重载一次性全挂上，改版也不容易失效。
 *
 * 为什么不在 HookEntry 里直接装：App 有加固壳，业务类要等壳初始化后才加载，
 * 启动瞬间 Class.forName 一定是 ClassNotFound。这里做成可重复调用的
 * install()，由 ClassLoaderHooks 的后台探测线程反复尝试，类一出现就补装。
 */
final class UpdateHooks {

    /** 已经处理过的类，避免探测线程重复挂钩 */
    private static final Set<String> HOOKED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean READY_LOGGED = new AtomicBoolean();

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

    /**
     * 可重复调用：还没加载的类下一轮再试，已处理过的类不会重复挂钩。
     *
     * @return 四条弹窗链路入口是否都已完成挂钩
     */
    static boolean install(ClassLoader cl) {
        for (String[] entry : DIALOG_ENTRIES) {
            hookOnce(cl, entry);
        }
        for (String[] entry : CHECK_ENTRIES) {
            hookOnce(cl, entry);
        }
        for (String[] entry : FLAG_ENTRIES) {
            hookOnce(cl, entry);
        }

        boolean ready = true;
        for (String[] entry : DIALOG_ENTRIES) {
            if (!HOOKED.contains(entry[0])) {
                ready = false;
                break;
            }
        }
        if (ready && READY_LOGGED.compareAndSet(false, true)) {
            HookLog.log("HOOK", "升级弹窗拦截已安装",
                    "已拦截 UpgradeDialogChecker / CheckVersionHelper / "
                            + "AppUpgradeManager / AppUpgradeDialogManager，"
                            + "并关闭版本检查，不会再弹出「发现新版本」。");
            HookLog.logcat("update hooks ready, classes=" + HOOKED.size());
        }
        return ready;
    }

    private static void hookOnce(ClassLoader cl, String[] entry) {
        if (HOOKED.contains(entry[0])) {
            return;
        }
        Class<?> clazz = findClass(cl, entry[0]);
        if (clazz == null) {
            // 加固壳还没把类放出来，交给下一轮探测
            return;
        }

        int count = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 1; i < entry.length; i++) {
            String name = entry[i];
            try {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, name, SKIP);
                if (unhooks == null || unhooks.isEmpty()) {
                    detail.append(' ').append(name).append("=miss");
                } else {
                    count += unhooks.size();
                    detail.append(' ').append(name).append('=').append(unhooks.size());
                }
            } catch (Throwable e) {
                detail.append(' ').append(name).append("=err(").append(e).append(')');
            }
        }
        HOOKED.add(entry[0]);
        HookLog.logcat("update hook " + entry[0] + " x" + count + detail);
    }

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable e) {
            return null;
        }
    }
}
