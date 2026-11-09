package com.cmcchook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 福利中心盘点：把「就地采集」到的入口并进清单。
 *
 * <p>数据只有一处来源：{@link WelfareCollect} 在福利专区页面里读到的楼层数据。
 * 这里不再自己请求任何接口，也不再拼楼层规则——页面里有什么，清单里就是什么。
 *
 * <p>过滤规则只保留可能带奖励的活动入口，商品、直播、查询页不进清单。
 */
final class WelfareFeed {

    /** 真的可能带奖励任务的入口。 */
    private static final String[] KEEP = {
            "qwhdhub", "hlwyxhdhub", "rightsmarket", "coc3", "coc/web6",
    };

    private WelfareFeed() {
    }

    static TaskRunner.Result discover() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return TaskRunner.Result.fail("盘点失败：模块上下文不可用", "", "重启一次 App 再试。");
        }
        if (!ProbeStore.has(ctx)) {
            return TaskRunner.Result.fail("盘点失败：还没有福利专区数据",
                    "采集缓存是空的。",
                    "先打开一次中国移动 App 的「福利专区」，"
                            + "再回模块点「扫描福利专区」。");
        }

        List<String[]> found = ProbeStore.candidates(ctx);
        StringBuilder detail = new StringBuilder();
        detail.append("采集时间: ").append(ProbeStore.summary(ctx)).append('\n');
        detail.append("采集入口: ").append(found.size()).append(" 个\n");

        JSONArray keep = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        int kept = 0;
        int known = 0;
        int skipped = 0;
        for (String[] one : found) {
            String name = one[0];
            String url = one[1];
            if (!worthKeeping(url)) {
                skipped++;
                continue;
            }
            String key = keyOf(url);
            if (!seen.add(key)) {
                continue;
            }
            if (WelfareCatalog.known(url)) {
                known++;
                continue;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("n", name);
                item.put("u", url);
                keep.put(item);
                kept++;
            } catch (Throwable ignored) {
            }
        }

        try {
            Intent intent = new Intent(LogReceiver.ACTION_WELFARE);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra("json", keep.toString());
            intent.putExtra("total", found.size());
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("盘点失败：写入清单出错", detail + "\n" + e, "");
        }

        detail.append("保留: ").append(kept).append(" 个新入口，")
                .append(known).append(" 个已收录，")
                .append(skipped).append(" 个非活动页\n");
        String summary = kept > 0
                ? "福利清单 · 新发现 " + kept + " 个入口"
                : "福利清单 · 没有新入口（" + known + " 个已收录）";
        return TaskRunner.Result.ok(summary, detail.toString(), summary);
    }

    private static String keyOf(String url) {
        String key = url == null ? "" : url;
        int q = key.indexOf('?');
        if (q > 0) {
            key = key.substring(0, q);
        }
        return key;
    }

    private static boolean worthKeeping(String url) {
        if (url == null || url.length() < 12) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        for (String hint : KEEP) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
