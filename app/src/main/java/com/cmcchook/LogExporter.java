package com.cmcchook;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志导出。
 *
 * 全量日志一天能到几百 KB（请求体、响应体全是加密串），发出去既费流量也没人看得完。
 * 所以导出前先选范围：
 *
 *   精简（默认） 只留任务流程、状态变化、错误。请求头/请求体/Cookie/存储快照整段丢掉。
 *                一两百行，排障够用，也是给开发者看的那份。
 *   只看任务     再把 H5 交互、WebView 生命周期也去掉，只剩 TASK / STATE / 错误。
 *   完整         一条不删，需要复现接口细节时用。
 *
 * 三种模式都在文件头加一段「诊断摘要」：三个活动今天的结果、最近一次任务、关键环境。
 * 打开文件第一屏就能判断问题在哪，不用往下翻。
 */
final class LogExporter {

    static final int MODE_BRIEF = 0;
    static final int MODE_TASK = 1;
    static final int MODE_FULL = 2;

    /** 精简模式最多带多少条，超了从最早的开始丢。 */
    private static final int BRIEF_MAX_ENTRIES = 500;
    /** 单条 detail 上限，防止一条异常堆栈吃掉整个文件。 */
    private static final int BRIEF_DETAIL_LIMIT = 3_000;
    /** 单行上限，响应体那种一行几万字符的必须砍。 */
    private static final int LINE_LIMIT = 220;

    /** 这些段落整块丢弃：都是加密串 / 快照，排障用不上。 */
    private static final String[] DROP_SECTIONS = {
            "--- 请求体 ---", "--- 响应体 ---", "--- 请求头 ---", "--- 响应头 ---",
            "--- Cookie ---", "--- localStorage ---", "--- sessionStorage ---",
    };

    private LogExporter() {
    }

    static String modeName(int mode) {
        switch (mode) {
            case MODE_TASK:
                return "只看任务";
            case MODE_FULL:
                return "完整";
            case MODE_BRIEF:
            default:
                return "精简";
        }
    }

    /** 导出结果：路径用于提示，uri 用于直接分享（Android 10+ 才有）。 */
    static final class Output {
        final String path;
        final Uri uri;

        Output(String path, Uri uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    static String export(Context context, List<LogEntry> entries) throws Exception {
        return export(context, entries, MODE_BRIEF).path;
    }

    static Output export(Context context, List<LogEntry> entries, int mode) throws Exception {
        String fileName = "cmcc-hook-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "-" + suffix(mode) + ".txt";
        String content = buildContent(context, entries, mode);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= 29) {
            return exportModern(context, fileName, bytes);
        }
        return exportLegacy(fileName, bytes);
    }

    private static String suffix(int mode) {
        switch (mode) {
            case MODE_TASK:
                return "task";
            case MODE_FULL:
                return "full";
            case MODE_BRIEF:
            default:
                return "brief";
        }
    }

    /** 导出任意内容（探路报告之类），复用同一套 Downloads 落盘逻辑。 */
    static Output exportFile(Context context, String fileName, String mime, byte[] bytes)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            return exportModern(context, fileName, mime, bytes);
        }
        return exportLegacy(fileName, bytes);
    }

    private static Output exportModern(Context context, String fileName, byte[] bytes)
            throws Exception {
        return exportModern(context, fileName, "text/plain", bytes);
    }

    private static Output exportModern(Context context, String fileName, String mime, byte[] bytes)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/CmccHook");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("系统没有返回 Downloads 文件地址");
            }
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) {
                    throw new IllegalStateException("无法打开 Downloads 输出流");
                }
                out.write(bytes);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new Output("Download/CmccHook/" + fileName, uri);
        } catch (Exception e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            throw e;
        }
    }

    private static Output exportLegacy(String fileName, byte[] bytes) throws Exception {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "CmccHook");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 Download/CmccHook 目录");
        }
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.flush();
        }
        try {
            MediaScannerConnection.scanFile(
                    null, new String[]{file.getAbsolutePath()}, new String[]{"text/plain"}, null);
        } catch (Throwable ignored) {
        }
        // Android 9 及以下没有 MediaStore 权限模型，file:// 又不能直接分享，
        // 只给路径，让用户自己去 Download 目录取。
        return new Output(file.getAbsolutePath(), null);
    }

    private static String buildContent(Context context, List<LogEntry> entries, int mode) {
        List<LogEntry> picked = pick(entries, mode);

        StringBuilder sb = new StringBuilder(32_768);
        sb.append("移动签到助手 - LSPosed 日志（").append(modeName(mode)).append("）\n");
        sb.append("导出时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("目标应用: ").append(targetVersion(context)).append('\n');
        // 以前这里写死 1.0，导出的日志永远显示「模块版本: 1.0」，
        // 排查时分不清装的是哪一版。改成读目标进程心跳里带回的真实版本。
        String moduleVersion = AppPrefs.getLastModuleVersion(context);
        sb.append("模块版本: ")
                .append(moduleVersion == null || moduleVersion.isEmpty() ? "未知" : moduleVersion)
                .append('\n');
        sb.append("日志条数: ").append(picked.size());
        if (picked.size() != entries.size()) {
            sb.append("（原始 ").append(entries.size()).append(" 条，已按模式筛选）");
        }
        sb.append('\n');
        long heartbeat = AppPrefs.getLastHeartbeat(context);
        if (heartbeat > 0) {
            sb.append("最近心跳: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date(heartbeat)))
                    .append('\n');
        }
        if (mode != MODE_FULL) {
            sb.append("已省略: 请求头/请求体/响应体/Cookie/存储快照。需要细节请重新导出「完整」。\n");
        }
        sb.append("注意: 日志可能含有 Cookie / Token / Authorization / 手机号，请勿公开分享。\n");
        sb.append("====================================================================\n\n");

        appendDigest(sb, context);
        sb.append("====================================================================\n\n");

        for (LogEntry entry : picked) {
            sb.append('[').append(entry.formatFullTime()).append("] [").append(entry.tag)
                    .append("] ").append(entry.summary).append('\n');
            if (!entry.detail.isEmpty()) {
                // 任务日志是核心，放宽到 12000 字符，别把失败原因截掉；
                // 其余条目按 3000 字符收，一天几百条噪音也不会撑爆文件。
                int limit = entry.tag.startsWith("TASK") || entry.tag.startsWith("PROBE")
                        ? 12_000 : BRIEF_DETAIL_LIMIT;
                String detail = mode == MODE_FULL
                        ? entry.detail
                        : compact(entry.detail, limit);
                if (!detail.isEmpty()) {
                    sb.append(detail).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 文件头的诊断摘要。
     *
     * 日志主体再精简，也可能有几百行；这里把「每个已接入活动今天到底怎么样」先摆出来，
     * 一眼能看出是没跑、跑了没成、还是做完了。
     */
    private static void appendDigest(StringBuilder sb, Context context) {
        sb.append("=== 诊断摘要 ===\n");
        String day = AppPrefs.today();
        String[] cmds = {"sign", "egg", "goose", "soccer", "tearbox", "saturday",
                "video", "daily7", "miao", "recharge", "wed", "seckill", "lucky3", "weekly",
                "signlottery", "quiz"};
        String[] names = {"签到有礼", "天天砸金蛋", "收鹅蛋换好礼",
                "球赛狂欢季", "拆一百元礼盒", "周六游戏日",
                "追剧领福利", "7日打卡分百万", "AI豆苗养成记", "充值先领券", "周三充值日",
                "12点抢券", "幸运三日签", "周周有好礼", "签到抽奖赢好礼", "乡友农业知识问答"};
        StringBuilder line = new StringBuilder("今日: ");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < cmds.length; i++) {
            String state = AppPrefs.getTaskState(context, cmds[i], day);
            int attempts = AppPrefs.getTaskAttempts(context, cmds[i], day);
            String mark;
            if (state.startsWith("OK")) {
                mark = "✓";
            } else if (state.startsWith("FAIL")) {
                mark = attempts > 1 ? "✗" + attempts : "✗";
            } else {
                mark = "○";
            }
            line.append(names[i]).append(' ').append(mark);
            if (i < cmds.length - 1) {
                line.append(i % 4 == 3 ? "　\n　　　" : "　");
            }
            if (!state.isEmpty()) {
                rows.append(names[i]).append(": ").append(state);
                if (attempts > 0) {
                    rows.append("（失败 ").append(attempts).append(" 次）");
                }
                rows.append('\n');
            }
        }
        sb.append(line).append('\n');
        if (rows.length() == 0) {
            sb.append("今天还没有任何活动跑过。\n");
        } else {
            sb.append(rows);
        }
        String last = AppPrefs.getLastTaskLine(context);
        if (last != null && !last.isEmpty()) {
            sb.append("最近一次: ").append(last).append('\n');
        }
        sb.append("图例: ✓ 完成　✗ 失败（数字为当天次数）　○ 未执行\n");
    }

    // ------------------------------------------------------------ 筛选与压缩

    private static List<LogEntry> pick(List<LogEntry> entries, int mode) {
        if (mode == MODE_FULL) {
            return entries;
        }
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (keep(entry.tag, mode)) {
                result.add(entry);
            }
        }
        if (mode == MODE_BRIEF && result.size() > BRIEF_MAX_ENTRIES) {
            return new ArrayList<>(result.subList(result.size() - BRIEF_MAX_ENTRIES,
                    result.size()));
        }
        return result;
    }

    private static boolean keep(String tag, int mode) {
        if (tag == null) {
            return false;
        }
        boolean core = tag.startsWith("TASK") || tag.startsWith("PROBE")
                || tag.equals("STATE") || tag.equals("AUTO")
                || tag.equals("SYSTEM") || tag.equals("APP-START")
                || tag.equals("CRASH") || tag.equals("EXIT-INFO")
                // 券核销与自动读短信验证码：排查「码有没有读到/送到了没」全靠这两类。
                || tag.equals("SMSCODE") || tag.equals("COUPON")
                // v3.43：打开页面链路（含「容器没加载，已补 loadUrl」兜底诊断）。
                || tag.equals("ACTIVITY")
                // v3.44：收鹅蛋离屏登录现场诊断（SSO 中转 / pc/pk / 页面文本）。
                || tag.equals("GOOSE") || tag.startsWith("GOOSE-");
        if (core || tag.contains("ERROR") || tag.contains("FAIL")) {
            return true;
        }
        if (mode == MODE_TASK) {
            return false;
        }
        // 精简模式再多带一点：页面点击和 H5 报错，鹅蛋那种「找不到按钮」全靠它定位。
        // 普通的 WEBVIEW 生命周期一天能刷 96 条，一律不带。
        return tag.startsWith("H5-CLICK") || tag.equals("AUTO");
    }

    /**
     * 把 detail 压到可读范围：
     *   1. 请求体/响应体这类整段跳过；
     *   2. 单行截断（base64 一行几万字符）；
     *   3. 总长度截断，保留头尾。
     */
    private static String compact(String detail, int limit) {
        String[] lines = detail.split("\n", -1);
        StringBuilder sb = new StringBuilder(Math.min(detail.length(), limit));
        boolean dropping = false;
        int dropped = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (isSectionHeader(trimmed)) {
                dropping = isDropSection(trimmed);
                if (dropping) {
                    dropped++;
                    continue;
                }
            } else if (dropping) {
                if (trimmed.isEmpty()) {
                    dropping = false;
                }
                continue;
            }
            if (line.length() > LINE_LIMIT) {
                line = line.substring(0, LINE_LIMIT) + "…";
            }
            if (sb.length() + line.length() + 1 > limit) {
                sb.append("…（本条已截断）");
                break;
            }
            sb.append(line).append('\n');
        }
        if (dropped > 0) {
            sb.append("（已省略 ").append(dropped).append(" 段请求/响应细节）\n");
        }
        return sb.toString().trim();
    }

    private static boolean isSectionHeader(String line) {
        return line.startsWith("--- ") && line.endsWith(" ---");
    }

    private static boolean isDropSection(String line) {
        for (String section : DROP_SECTIONS) {
            if (section.equals(line)) {
                return true;
            }
        }
        return false;
    }

    private static String targetVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(AppPrefs.TARGET_PACKAGE, 0);
            String code;
            if (Build.VERSION.SDK_INT >= 28) {
                code = String.valueOf(info.getLongVersionCode());
            } else {
                code = String.valueOf(info.versionCode);
            }
            return "中国移动 " + info.versionName + " (" + code + ")";
        } catch (Throwable e) {
            return "中国移动 (未安装或无法读取)";
        }
    }
}
