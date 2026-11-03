package com.qoder.cmcchook;

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
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class LogExporter {
    private LogExporter() {
    }

    static String export(Context context, List<LogEntry> entries) throws Exception {
        String fileName = "cmcc-hook-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        String content = buildContent(context, entries);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= 29) {
            return exportModern(context, fileName, bytes);
        }
        return exportLegacy(fileName, bytes);
    }

    private static String exportModern(Context context, String fileName, byte[] bytes)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
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
            return "Download/CmccHook/" + fileName;
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

    private static String exportLegacy(String fileName, byte[] bytes) throws Exception {
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
        return file.getAbsolutePath();
    }

    private static String buildContent(Context context, List<LogEntry> entries) {
        StringBuilder sb = new StringBuilder(32_768);
        sb.append("移动签到助手 - LSPosed 抓取日志\n");
        sb.append("导出时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("目标应用: ").append(targetVersion(context)).append('\n');
        sb.append("模块版本: 1.0\n");
        sb.append("日志条数: ").append(entries.size()).append('\n');
        long heartbeat = AppPrefs.getLastHeartbeat(context);
        if (heartbeat > 0) {
            sb.append("最近心跳: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date(heartbeat)))
                    .append('\n');
        }
        sb.append("注意: 日志可能含有 Cookie / Token / Authorization / 手机号，请勿公开分享。\n");
        sb.append("====================================================================\n\n");

        for (LogEntry entry : entries) {
            sb.append('[').append(entry.formatFullTime()).append("] [").append(entry.tag)
                    .append("] ").append(entry.summary).append('\n');
            if (!entry.detail.isEmpty()) {
                sb.append(entry.detail).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
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
