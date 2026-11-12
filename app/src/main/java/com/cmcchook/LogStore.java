package com.cmcchook;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class LogStore {
    static final String FILE_NAME = "hook.log";
    static final String BACKUP_NAME = "hook.log.1";
    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_DETAIL_CHARS = 320_000;
    private static final int MAX_SUMMARY_CHARS = 1_200;

    /** r17：文件没变就不再全量解析。界面每 2.5 秒刷一次日志，任务结束后文件不动，
     *  以前每次都把最多 20MB 的两份文件重新按行 JSON.parse 一遍，白耗 CPU 与堆。 */
    private static long[] cacheStamp;
    private static List<LogEntry> cacheList;
    private static int cacheMax = -1;
    private static long[] countStamp;
    private static int countValue = -1;

    private LogStore() {
    }

    static synchronized void append(Context context, LogEntry entry) {
        try {
            File dir = context.getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, FILE_NAME);
            String summary = cut(entry.summary, MAX_SUMMARY_CHARS);
            String detail = cut(entry.detail, MAX_DETAIL_CHARS);
            JSONObject json = new JSONObject();
            json.put("ts", entry.time);
            json.put("tag", entry.tag);
            json.put("summary", summary);
            json.put("detail", detail);
            byte[] bytes = (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);

            if (file.length() > 0 && file.length() + bytes.length > MAX_FILE_BYTES) {
                rotate(dir, file);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
                out.flush();
            }
        } catch (Throwable ignored) {
            // The receiver must never crash because one malformed entry could not be stored.
        }
    }

    private static void rotate(File dir, File file) {
        File backup = new File(dir, BACKUP_NAME);
        if (backup.exists() && !backup.delete()) {
            // Keep writing to the current file if the old backup cannot be removed.
            return;
        }
        if (!file.renameTo(backup)) {
            // Rotation is best-effort; losing logging is worse than an oversized file.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static synchronized List<LogEntry> read(Context context, int maxEntries) {
        long[] st = stamp(context);
        if (cacheList != null && cacheMax == maxEntries
                && java.util.Arrays.equals(st, cacheStamp)) {
            return new ArrayList<>(cacheList);
        }
        List<LogEntry> current = new ArrayList<>();
        Deque<LogEntry> tail = new ArrayDeque<>();
        File dir = context.getFilesDir();
        readFile(new File(dir, BACKUP_NAME), maxEntries, tail);
        readFile(new File(dir, FILE_NAME), maxEntries, tail);
        current.addAll(tail);
        cacheStamp = st;
        cacheList = current;
        cacheMax = maxEntries;
        return new ArrayList<>(current);
    }

    private static void readFile(File file, int maxEntries, Deque<LogEntry> out) {
        if (!file.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject json = new JSONObject(line);
                    LogEntry entry = LogEntry.fromJson(json);
                    out.addLast(entry);
                    if (maxEntries > 0 && out.size() > maxEntries) {
                        out.removeFirst();
                    }
                } catch (Throwable ignored) {
                    // Ignore a partial last line or a corrupted record.
                }
            }
        } catch (Throwable ignored) {
            // No log file yet.
        }
    }

    static synchronized int count(Context context) {
        long[] st = stamp(context);
        if (countValue >= 0 && java.util.Arrays.equals(st, countStamp)) {
            return countValue;
        }
        int count = 0;
        File dir = context.getFilesDir();
        for (String name : new String[]{BACKUP_NAME, FILE_NAME}) {
            File file = new File(dir, name);
            if (!file.isFile()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("{")) {
                        count++;
                    }
                }
            } catch (Throwable ignored) {
                // Ignore unreadable files.
            }
        }
        countStamp = st;
        countValue = count;
        return count;
    }

    static long totalBytes(Context context) {
        File dir = context.getFilesDir();
        long total = 0L;
        File current = new File(dir, FILE_NAME);
        File backup = new File(dir, BACKUP_NAME);
        if (current.isFile()) {
            total += current.length();
        }
        if (backup.isFile()) {
            total += backup.length();
        }
        return total;
    }

    static long[] stamp(Context context) {
        File dir = context.getFilesDir();
        File current = new File(dir, FILE_NAME);
        File backup = new File(dir, BACKUP_NAME);
        return new long[]{
                current.length(), current.lastModified(),
                backup.length(), backup.lastModified()
        };
    }

    static void clear(Context context) {
        File dir = context.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, FILE_NAME).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, BACKUP_NAME).delete();
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n…[truncated]";
    }
}
