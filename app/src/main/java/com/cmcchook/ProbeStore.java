package com.cmcchook;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 探路报告的存取。
 *
 * <p>报告是一份 JSON，里面按页面记了标题、正文摘要、可点控件、
 * 页面发的请求和原生请求。文件放私有目录，不做压缩 ——
 * 用户点「导出」时原样发出去，比在日志里翻几十屏强。
 */
final class ProbeStore {

    private static final String FILE = "probe-report.json";
    private static final String FILE_META = "probe-report.at";
    /** 单活动报告的索引时间戳：界面靠它发现「某个活动刚采完」。 */
    private static final String FILE_ACT_INDEX = "probe-act-index.at";
    private static final String ACT_PREFIX = "probe-act-";

    private ProbeStore() {
    }

    static void save(Context ctx, String json) {
        if (ctx == null || json == null || json.isEmpty()) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
            FileOutputStream meta = new FileOutputStream(new File(ctx.getFilesDir(), FILE_META));
            meta.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            meta.close();
        } catch (Throwable e) {
            HookLog.logcat("probe store failed: " + e);
        }
    }

    static String load(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            File file = new File(ctx.getFilesDir(), FILE);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    static long at(Context ctx) {
        String raw = readText(ctx, FILE_META);
        try {
            return Long.parseLong(raw.trim());
        } catch (Throwable e) {
            return 0L;
        }
    }

    static boolean has(Context ctx) {
        return load(ctx) != null;
    }

    // ------------------------------------------------------- 单活动报告

    /**
     * 单个活动的采集报告。
     *
     * <p>跟全局报告分开存：全局那份是「福利专区有什么」，这份是
     * 「这个活动内部长什么样」，一份覆盖另一份会丢东西。
     */
    static void saveAct(Context ctx, String frag, String json) {
        if (ctx == null || frag == null || frag.isEmpty() || json == null || json.isEmpty()) {
            return;
        }
        try {
            writeText(ctx, actFile(frag), json);
            writeText(ctx, FILE_ACT_INDEX, String.valueOf(System.currentTimeMillis()));
        } catch (Throwable e) {
            HookLog.logcat("probe act store failed: " + e);
        }
    }

    static String loadAct(Context ctx, String frag) {
        if (ctx == null || frag == null || frag.isEmpty()) {
            return null;
        }
        String text = readText(ctx, actFile(frag));
        return text.isEmpty() ? null : text;
    }

    static boolean hasAct(Context ctx, String frag) {
        return loadAct(ctx, frag) != null;
    }

    static long actAt(Context ctx, String frag) {
        if (ctx == null || frag == null || frag.isEmpty()) {
            return 0L;
        }
        File file = new File(ctx.getFilesDir(), actFile(frag));
        return file.exists() ? file.lastModified() : 0L;
    }

    /** 最近一次单活动采集的时间；界面轮询用，只读一个很小的文件。 */
    static long actsAt(Context ctx) {
        String raw = readText(ctx, FILE_ACT_INDEX);
        try {
            return Long.parseLong(raw.trim());
        } catch (Throwable e) {
            return 0L;
        }
    }

    static String actExportName(String frag, String name) {
        SimpleDateFormat fmt = new SimpleDateFormat("MMdd-HHmm", Locale.US);
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        if (clean.isEmpty()) {
            clean = "activity";
        }
        return clean + "-" + (frag == null ? "" : frag) + "-"
                + fmt.format(new Date()) + ".json";
    }

    private static String actFile(String frag) {
        return ACT_PREFIX + frag.replaceAll("[^A-Za-z0-9_-]", "_") + ".json";
    }

    static int sizeKb(Context ctx) {
        String json = load(ctx);
        return json == null ? 0 : json.length() / 1024;
    }

    /** 报告里发现的入口，直接给福利清单用。 */
    static List<String[]> candidates(Context ctx) {
        List<String[]> list = new ArrayList<>();
        String json = load(ctx);
        if (json == null) {
            return list;
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("candidates");
            if (array == null) {
                return list;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject one = array.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String url = one.optString("u", "").trim();
                String name = one.optString("n", "").trim();
                if (url.isEmpty()) {
                    continue;
                }
                list.add(new String[]{name, url});
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 界面上显示的三行摘要。 */
    static String summary(Context ctx) {
        long at = at(ctx);
        if (at == 0L) {
            return "还没采过。回模块点「扫描福利专区」手动跑一次。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("上次采集: ").append(format(at))
                .append(" · ").append(sizeKb(ctx)).append(" KB");
        String json = load(ctx);
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            JSONObject sum = root.optJSONObject("summary");
            if (sum != null) {
                sb.append('\n').append("页面 ").append(sum.optInt("pages", 0))
                        .append(" 个 · 请求 ").append(sum.optInt("reqs", 0))
                        .append(" 条 · 入口 ").append(sum.optInt("candidates", 0))
                        .append(" 个");
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 导出用的文件名：带时间，便于区分。 */
    static String exportName(Context ctx) {
        long at = at(ctx);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return "cmcc-probe-" + fmt.format(new Date(at == 0L ? System.currentTimeMillis() : at))
                + ".json";
    }

    private static void writeText(Context ctx, String name, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    private static String readText(Context ctx, String name) {
        if (ctx == null) {
            return "";
        }
        try {
            File file = new File(ctx.getFilesDir(), name);
            if (!file.exists()) {
                return "";
            }
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    static String format(long at) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return fmt.format(new Date(at));
    }
}
