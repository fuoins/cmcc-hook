package com.cmcchook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流量页展示层 —— v4.0.3，UI 与信息架构高保真对齐 {@code flow-assistant} 1.9.6。
 *
 * <p>相比 v4.0.2 的「平铺文字卡」，这版补齐了用户要的三件事：
 * <ul>
 *   <li><b>每一项都能展开看</b>：顶部「流量详情 ▾」总面板列出每一项（名称·分类 +
 *       已用/剩余/总量 + 进度条）；每个分类小卡点一下再展开该组下每一包明细；</li>
 *   <li><b>内容更全更清晰</b>：渐变头部（剩余大字 / 已消耗 / 占比条）、
 *       全宽「基础套餐合计」、两列分类卡（图标 + 剩余大字 + 总量 + 进度条）、
 *       账户信息（话费 / 通话）；</li>
 *   <li><b>消耗流量 / 领取活动</b>：由 {@link FlowAssist} 画在页面下半部分，
 *       与余量数据无关，没查到余量时也能用。</li>
 * </ul>
 *
 * <p>严格只读：数据来自 {@link FlowStore}（目标进程离屏读首页 localStorage 的明文缓存），
 * 这里不联网、不解密、不拼签名、不提交业务请求。单位换算基准与 flow-assistant 一致
 * （1024 进制）。色值全部走模块 {@code @color} 资源，自动跟随系统深浅色。
 */
final class FlowView {

    private FlowView() {
    }

    private static final Pattern AMT = Pattern.compile(
            "([\\d.]+)\\s*(KB|MB|GB|TB)", Pattern.CASE_INSENSITIVE);

    /** 跨 render 保持展开状态（FlowView 每次收到广播/切页都会整页重画）。 */
    private static volatile boolean detailOpen = false;
    /** 只在主线程访问，无需并发集合。 */
    private static final Set<String> OPEN_GROUPS = new HashSet<>();

    private static final class Item {
        String name = "";
        String cat = "";
        String used = "";
        String remain = "";
        String total = "";
    }

    private static final class Group {
        String key = "";
        double totalGB;
        double remainGB;
        double usedGB;
        int count;
        final List<Item> items = new ArrayList<>();
    }

    /** 从磁盘读最近一次结果，重画整个流量页（工具区也一起重建，但消耗状态由 FlowAssist 静态保持）。 */
    static void render(Context c, View page) {
        if (page == null) {
            return;
        }
        TextView notify = page.findViewById(R.id.flowNotify);
        TextView at = page.findViewById(R.id.flowAt);
        LinearLayout root = page.findViewById(R.id.flowRoot);
        if (root == null) {
            return;
        }
        root.removeAllViews();

        List<Item> items = new ArrayList<>();
        String fee = "";
        String voice = "";
        String state = "";
        String error = "";
        String domText = "";
        long ts = FlowStore.at(c);
        boolean parsed = false;
        String json = FlowStore.json(c);
        if (!json.isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                state = o.optString("state", "");
                error = o.optString("error", "");
                domText = o.optString("domText", "");
                ts = o.optLong("at", ts);
                fee = o.optString("fee", "");
                voice = o.optString("voice", "");
                items = parseItems(o);
                parsed = true;
            } catch (Throwable ignored) {
            }
        }
        boolean domFallback = false;
        if (items.isEmpty() && !domText.isEmpty()) {
            items = fromDom(domText);
            domFallback = !items.isEmpty();
        }

        if (at != null) {
            at.setText(ts > 0 ? c.getString(R.string.flow_at,
                    new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts))) : "");
        }
        if (notify != null) {
            if (!parsed && !json.isEmpty()) {
                notify.setText(c.getString(R.string.flow_fail, "结果解析失败"));
            } else if ("error".equals(state)) {
                if (error.contains("登录")) {
                    notify.setText(R.string.flow_login);
                } else {
                    notify.setText(c.getString(R.string.flow_fail,
                            error.isEmpty() ? "未知原因" : error));
                }
            } else if (items.isEmpty()) {
                notify.setText(json.isEmpty()
                        ? c.getString(R.string.flow_never)
                        : (error.isEmpty() ? c.getString(R.string.flow_empty) : error));
            } else {
                String tip = c.getString(R.string.flow_ok, items.size());
                if (domFallback) {
                    tip += " · 页面明文兜底";
                }
                if ("loading".equals(state)) {
                    tip += " · 结构化数据未就绪";
                }
                notify.setText(tip);
            }
        }

        LinkedHashMap<String, Group> groups = group(items);

        // Hero 数据：优先「通用流量」，否则按国内套餐汇总（共享/国际/每日/套餐外另列，不重复加算）。
        Group hero = groups.get("通用流量");
        double allTotal = 0, allRemain = 0, allUsed = 0;
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            String k = e.getKey();
            if ("共享流量".equals(k) || "国际流量".equals(k)
                    || "每日流量".equals(k) || "套餐外流量".equals(k)) {
                continue;
            }
            allTotal += e.getValue().totalGB;
            allRemain += e.getValue().remainGB;
            allUsed += e.getValue().usedGB;
        }
        Group heroGroup = hero != null ? hero : new Group();
        if (hero == null) {
            heroGroup.totalGB = allTotal;
            heroGroup.remainGB = allRemain;
            heroGroup.usedGB = allUsed;
        }

        // 1) 渐变头部 + 2) 全局「流量详情」面板
        final LinearLayout detailPanel = detailPanel(c, items);
        detailPanel.setVisibility(detailOpen ? View.VISIBLE : View.GONE);
        root.addView(heroCard(c, heroGroup, hero == null, items.size(), detailPanel));
        root.addView(detailPanel);

        // 3) 余量汇总
        root.addView(sectionTitle(c, "余量汇总", col(c, R.color.primary)));
        if (items.isEmpty()) {
            root.addView(emptyCard(c));
        } else {
            root.addView(wideCard(c, "基础套餐合计", "合", R.color.primary,
                    "已消耗 " + fmt(allUsed) + " · 按国内套餐汇总 · 共享信息另列",
                    allTotal, allRemain, R.color.primary));
            if (!fee.isEmpty() || !voice.isEmpty()) {
                root.addView(accountCard(c, fee, voice));
            }
            // 两列小卡：每张都可点开看该组的每一包明细
            List<View> mini = new ArrayList<>();
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                Group g = e.getValue();
                mini.add(miniCard(c, g, g.count + "包"));
            }
            for (int i = 0; i < mini.size(); i += 2) {
                root.addView(miniRow(c, mini.get(i), i + 1 < mini.size() ? mini.get(i + 1) : null));
            }
        }

        // 4) 消耗流量 + 领取活动（flow-assistant 搬过来的独立功能，始终可用）
        root.addView(FlowAssist.build(c));
    }

    // -------------------------------------------------------------- 解析

    private static List<Item> parseItems(JSONObject o) {
        List<Item> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("items");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) {
                continue;
            }
            Item it = new Item();
            it.name = x.optString("name", "");
            it.cat = x.optString("cat", "");
            it.used = x.optString("used", "");
            it.remain = x.optString("remain", "");
            it.total = x.optString("total", "");
            if (it.name.isEmpty() && it.remain.isEmpty()) {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    /** 页面明文兜底：从「国内通用流量 剩余 3.24 GB 已用 38.76 GB」这类文本里抠出余量。 */
    private static List<Item> fromDom(String text) {
        List<Item> out = new ArrayList<>();
        String remain = findFirst(text,
                Pattern.compile("([\\d.]+\\s*(?:GB|MB|KB|TB))\\s*(?:国内)?通用流量\\s*剩余"),
                Pattern.compile("剩余\\s*([\\d.]+\\s*(?:GB|MB|KB|TB))"));
        String used = findFirst(text,
                Pattern.compile("(?:已用|已消耗)\\s*([\\d.]+\\s*(?:GB|MB|KB|TB))"));
        String total = findFirst(text,
                Pattern.compile("(?:共|总量|总计)\\s*([\\d.]+\\s*(?:GB|MB|KB|TB))"));
        if (remain != null) {
            Item it = new Item();
            it.name = "国内通用流量";
            it.cat = "国内通用流量";
            it.remain = remain.replace(" ", "");
            it.used = used == null ? "" : used.replace(" ", "");
            it.total = total == null ? "" : total.replace(" ", "");
            out.add(it);
        }
        return out;
    }

    private static String findFirst(String text, Pattern... ps) {
        for (Pattern p : ps) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    private static LinkedHashMap<String, Group> group(List<Item> items) {
        LinkedHashMap<String, Group> g = new LinkedHashMap<>();
        for (Item it : items) {
            String key = groupKey(it.cat, it.name);
            Group one = g.get(key);
            if (one == null) {
                one = new Group();
                one.key = key;
                g.put(key, one);
            }
            one.totalGB += toGB(it.total);
            one.remainGB += toGB(it.remain);
            one.usedGB += toGB(it.used);
            one.count++;
            one.items.add(it);
        }
        return g;
    }

    private static String groupKey(String cat, String name) {
        String c = cat == null ? "" : cat;
        String n = name == null ? "" : name;
        if (c.contains("通用")) return "通用流量";
        if (c.contains("定向")) {
            if (n.contains("咪咕视频")) return "咪咕视频";
            if (n.contains("云盘")) return "移动云盘";
            if (n.contains("快游")) return "咪咕快游";
            return "定向流量";
        }
        if (c.contains("共享")) return "共享流量";
        if (c.contains("国际")) return "国际流量";
        if (c.contains("WLAN")) return "WLAN流量";
        if (c.contains("每日")) return "每日流量";
        if (c.contains("套餐外")) return "套餐外流量";
        if (c.contains("国内其他")) return "国内其他流量";
        return "其他流量";
    }

    /** 分组 → 图标字 + 强调色，对齐 flow-assistant 的分类语义。 */
    private static int groupColor(String key) {
        if ("通用流量".equals(key) || "每日流量".equals(key)) return R.color.success;
        if ("咪咕视频".equals(key) || "套餐外流量".equals(key)) return R.color.error;
        if ("移动云盘".equals(key) || "定向流量".equals(key)) return R.color.warning;
        if ("咪咕快游".equals(key) || "国际流量".equals(key)) return R.color.tag_ui;
        if ("共享流量".equals(key) || "WLAN流量".equals(key)) return R.color.primary;
        return R.color.text_secondary;
    }

    private static String groupGlyph(String key) {
        if ("通用流量".equals(key)) return "通";
        if ("咪咕视频".equals(key)) return "视";
        if ("移动云盘".equals(key)) return "盘";
        if ("咪咕快游".equals(key)) return "游";
        if ("定向流量".equals(key)) return "专";
        if ("共享流量".equals(key)) return "享";
        if ("国际流量".equals(key)) return "境";
        if ("每日流量".equals(key)) return "日";
        if ("WLAN流量".equals(key)) return "网";
        if ("套餐外流量".equals(key)) return "外";
        return "他";
    }

    private static double toGB(String s) {
        if (s == null) {
            return 0;
        }
        Matcher m = AMT.matcher(s.trim());
        if (!m.find()) {
            return 0;
        }
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (Throwable e) {
            return 0;
        }
        String u = m.group(2).toUpperCase(Locale.US);
        if ("KB".equals(u)) return v / 1024 / 1024;
        if ("MB".equals(u)) return v / 1024;
        if ("TB".equals(u)) return v * 1024;
        return v;
    }

    private static String fmt(double gb) {
        if (gb >= 1024) return String.format(Locale.CHINA, "%.2f TB", gb / 1024);
        if (gb >= 1) return String.format(Locale.CHINA, "%.2f GB", gb);
        if (gb >= 0.001) return String.format(Locale.CHINA, "%.1f MB", gb * 1024);
        return "0 MB";
    }

    private static String[] splitNumUnit(double gb) {
        if (gb >= 1024) return new String[]{String.format(Locale.CHINA, "%.2f", gb / 1024), "TB"};
        if (gb >= 1) return new String[]{String.format(Locale.CHINA, "%.2f", gb), "GB"};
        if (gb >= 0.001) return new String[]{String.format(Locale.CHINA, "%.1f", gb * 1024), "MB"};
        return new String[]{"0", "MB"};
    }

    /** 明细行里的数值：优先格式化成 GB/MB（比 "3317.96MB" 清晰），空值显示「—」。 */
    private static String amt(String raw) {
        if (raw == null || raw.isEmpty()) return "—";
        if (raw.contains("不限")) return "不限";
        if (!AMT.matcher(raw).find()) return raw;
        return fmt(toGB(raw));
    }

    // -------------------------------------------------------------- 卡片

    private static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(col(c, R.color.container_low));
        gd.setCornerRadius(dp(c, 16));
        box.setBackground(gd);
        box.setElevation(dp(c, 2));
        int h = dp(c, 14);
        box.setPadding(h, dp(c, 12), h, dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        box.setLayoutParams(lp);
        return box;
    }

    private static View sectionTitle(Context c, String text, int color) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 12), 0, dp(c, 5));
        View bar = new View(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, 2));
        bar.setBackground(bg);
        row.addView(bar, new LinearLayout.LayoutParams(dp(c, 4), dp(c, 14)));
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_primary));
        t.setPadding(dp(c, 8), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private static TextView iconBadge(Context c, int color, String glyph, int sizeDp) {
        TextView v = new TextView(c);
        v.setText(glyph);
        v.setTextSize(sizeDp >= 30 ? 14 : 11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        // 图标字统一纯白（对齐 flow-assistant），彩色圆角块上两种主题都清晰。
        v.setTextColor(0xFFFFFFFF);
        v.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(c, sizeDp >= 30 ? 9 : 8));
        v.setBackground(gd);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** 渐变头部：剩余大字 / 已消耗 / 占比条 / 流量详情展开入口。对齐 flow-assistant buildNativeScreen。 */
    private static View heroCard(Context c, Group hero, boolean isTotal,
                                 int itemCount, final LinearLayout detailPanel) {
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        GradientDrawable hd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{col(c, R.color.brand_start), col(c, R.color.brand_end)});
        hd.setCornerRadius(dp(c, 18));
        header.setBackground(hd);
        header.setElevation(dp(c, 3));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(c, 4);
        header.setLayoutParams(hlp);

        // 行1：标签 + 流量详情展开
        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(row1);
        TextView label = new TextView(c);
        label.setText(isTotal ? "流量总剩余" : "国内通用流量 · 剩余");
        label.setTextSize(12);
        label.setTextColor(col(c, R.color.header_text_dim));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(label);
        final TextView link = new TextView(c);
        link.setText(detailOpen ? "流量详情 ▴" : "流量详情 ▾");
        link.setTextSize(12);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setTextColor(col(c, R.color.header_text));
        link.setPadding(dp(c, 8), dp(c, 2), dp(c, 2), dp(c, 2));
        row1.addView(link);

        // 行2：剩余大字（左） + 已消耗（右）
        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(0, dp(c, 6), 0, 0);
        header.addView(row2);
        LinearLayout remainCol = new LinearLayout(c);
        remainCol.setOrientation(LinearLayout.HORIZONTAL);
        remainCol.setGravity(Gravity.BOTTOM);
        String[] nu = splitNumUnit(hero.remainGB);
        TextView num = new TextView(c);
        num.setText(nu[0]);
        num.setTextSize(38);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextColor(col(c, R.color.header_text));
        remainCol.addView(num);
        TextView unit = new TextView(c);
        unit.setText(nu[1]);
        unit.setTextSize(14);
        unit.setTextColor(col(c, R.color.header_text_dim));
        unit.setPadding(dp(c, 4), 0, 0, dp(c, 5));
        remainCol.addView(unit);
        row2.addView(remainCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout usedCol = new LinearLayout(c);
        usedCol.setOrientation(LinearLayout.VERTICAL);
        usedCol.setGravity(Gravity.RIGHT);
        TextView usedLab = new TextView(c);
        usedLab.setText("已消耗");
        usedLab.setTextSize(10);
        usedLab.setTextColor(col(c, R.color.header_text_dim));
        usedLab.setGravity(Gravity.RIGHT);
        usedCol.addView(usedLab);
        TextView usedVal = new TextView(c);
        usedVal.setText(fmt(hero.usedGB));
        usedVal.setTextSize(15);
        usedVal.setTypeface(Typeface.DEFAULT_BOLD);
        usedVal.setTextColor(col(c, R.color.header_text));
        usedVal.setGravity(Gravity.RIGHT);
        usedCol.addView(usedVal);
        row2.addView(usedCol);

        // 行3：占比条
        RatioBar bar = new RatioBar(c);
        bar.setColors(0x33FFFFFF, col(c, R.color.header_text));
        bar.setRatio(hero.totalGB > 0 ? (float) (hero.remainGB / hero.totalGB) : 0f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 6));
        blp.topMargin = dp(c, 8);
        header.addView(bar, blp);

        // 行4：汇总说明
        TextView hint = new TextView(c);
        hint.setText("已用 " + fmt(hero.usedGB) + " / 共 " + fmt(hero.totalGB)
                + " · 共 " + itemCount + " 项余量，点卡片任意处展开/收起");
        hint.setTextSize(11);
        hint.setTextColor(col(c, R.color.header_text_dim));
        hint.setPadding(0, dp(c, 6), 0, 0);
        header.addView(hint);
        // v4.0.5：整张头部卡都能点开/收起「流量详情」，不用非得点到右上角那行小字。
        // 内部子 View 没有单独监听器，点任意位置都只会走这里一次，不会重复 toggle。
        header.setClickable(true);
        header.setOnClickListener(v -> {
            detailOpen = !detailOpen;
            detailPanel.setVisibility(detailOpen ? View.VISIBLE : View.GONE);
            link.setText(detailOpen ? "流量详情 ▴" : "流量详情 ▾");
        });
        return header;
    }

    /** 全局「流量详情」面板：每一项（名称·分类 + 已用/剩余/总量 + 进度条）都列全。 */
    private static LinearLayout detailPanel(Context c, List<Item> items) {
        LinearLayout box = card(c);
        if (items.isEmpty()) {
            TextView e = new TextView(c);
            e.setText("暂无数据，点「一键查询」读取一次余量缓存");
            e.setTextSize(12);
            e.setTextColor(col(c, R.color.text_secondary));
            box.addView(e);
            return box;
        }
        TextView h = new TextView(c);
        h.setText("全部流量明细 · " + items.size() + " 项");
        h.setTextSize(13);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(col(c, R.color.text_primary));
        h.setPadding(0, 0, 0, dp(c, 6));
        box.addView(h);
        for (Item it : items) {
            String cat = (it.cat == null || it.cat.isEmpty() || it.cat.equals(it.name))
                    ? "" : " · " + it.cat;
            TextView line = new TextView(c);
            line.setText((it.name.isEmpty() ? "流量包" : it.name) + cat);
            line.setTextSize(13);
            line.setTypeface(Typeface.DEFAULT_BOLD);
            line.setTextColor(col(c, R.color.text_primary));
            line.setPadding(0, dp(c, 6), 0, 0);
            box.addView(line);
            TextView sub = new TextView(c);
            sub.setText("已用 " + amt(it.used) + "　剩余 " + amt(it.remain)
                    + "　总量 " + amt(it.total));
            sub.setTextSize(11);
            sub.setTextColor(col(c, R.color.text_secondary));
            sub.setPadding(0, dp(c, 2), 0, dp(c, 4));
            box.addView(sub);
            double total = toGB(it.total), remain = toGB(it.remain);
            RatioBar rb = new RatioBar(c);
            rb.setColors(col(c, R.color.divider), col(c, R.color.primary));
            rb.setRatio(total > 0 ? (float) (remain / total) : 0f);
            box.addView(rb, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 4)));
        }
        return box;
    }

    /** 全宽合计卡，对齐 flow-assistant makeWideCard。 */
    private static View wideCard(Context c, String title, String glyph, int colorRes,
                                 String sub, double total, double remain, int barColorRes) {
        LinearLayout box = card(c);
        int color = col(c, colorRes);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(head);
        head.addView(iconBadge(c, color, glyph, 30));
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(c, 8), 0, 0, 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_primary));
        mid.addView(t);
        TextView st = new TextView(c);
        st.setText(sub);
        st.setTextSize(10);
        st.setTextColor(col(c, R.color.text_secondary));
        mid.addView(st);
        head.addView(mid);
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT);
        TextView rem = new TextView(c);
        rem.setText(fmt(remain));
        rem.setTextSize(16);
        rem.setTypeface(Typeface.DEFAULT_BOLD);
        rem.setTextColor(col(c, R.color.text_primary));
        rem.setGravity(Gravity.RIGHT);
        right.addView(rem);
        TextView tot = new TextView(c);
        tot.setText("总量 " + fmt(total));
        tot.setTextSize(9);
        tot.setTextColor(col(c, R.color.text_secondary));
        tot.setGravity(Gravity.RIGHT);
        right.addView(tot);
        head.addView(right);
        RatioBar bar = new RatioBar(c);
        bar.setColors(col(c, R.color.divider), col(c, barColorRes));
        bar.setRatio(total > 0 ? (float) (remain / total) : 0f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 5));
        blp.topMargin = dp(c, 8);
        box.addView(bar, blp);
        return box;
    }

    /** 账户信息：话费余额 / 通话剩余（来自首页同一份明文缓存，不参与流量换算）。 */
    private static View accountCard(Context c, String fee, String voice) {
        LinearLayout box = card(c);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconBadge(c, col(c, R.color.success), "账", 26));
        TextView t = new TextView(c);
        t.setText("账户信息");
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_primary));
        t.setPadding(dp(c, 8), 0, 0, 0);
        head.addView(t);
        box.addView(head);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(c, 8), 0, 0);
        if (!fee.isEmpty()) {
            TextView a = new TextView(c);
            a.setText("话费余额 " + fee);
            a.setTextSize(13);
            a.setTypeface(Typeface.DEFAULT_BOLD);
            a.setTextColor(col(c, R.color.primary));
            a.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(a);
        }
        if (!voice.isEmpty()) {
            TextView b = new TextView(c);
            b.setText("通话剩余 " + voice);
            b.setTextSize(13);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(col(c, R.color.primary));
            row.addView(b);
        }
        box.addView(row);
        return box;
    }

    /** 分类小卡：图标 + 剩余大字 + 总量 + 进度条；点标题栏展开该组下每一包明细。 */
    private static View miniCard(Context c, final Group g, String sub) {
        final String key = g.key;
        int color = col(c, groupColor(key));
        LinearLayout box = card(c);
        box.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconBadge(c, color, groupGlyph(key), 22));
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(c, 6), 0, 0, 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(c);
        t.setText(key);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_primary));
        t.setSingleLine(true);
        mid.addView(t);
        TextView st = new TextView(c);
        st.setText(sub);
        st.setTextSize(9);
        st.setTextColor(col(c, R.color.text_secondary));
        mid.addView(st);
        head.addView(mid);
        final TextView arrow = new TextView(c);
        arrow.setText("▾");
        arrow.setTextSize(12);
        arrow.setTextColor(col(c, R.color.text_secondary));
        head.addView(arrow);
        box.addView(head);

        TextView rem = new TextView(c);
        rem.setText(fmt(g.remainGB));
        rem.setTextSize(17);
        rem.setTypeface(Typeface.DEFAULT_BOLD);
        rem.setTextColor(col(c, R.color.text_primary));
        rem.setPadding(0, dp(c, 6), 0, 0);
        box.addView(rem);
        TextView tot = new TextView(c);
        tot.setText("总量 " + fmt(g.totalGB) + " · 已用 " + fmt(g.usedGB));
        tot.setTextSize(9);
        tot.setTextColor(col(c, R.color.text_secondary));
        box.addView(tot);
        RatioBar bar = new RatioBar(c);
        bar.setColors(col(c, R.color.divider), color);
        bar.setRatio(g.totalGB > 0 ? (float) (g.remainGB / g.totalGB) : 0f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 3));
        blp.topMargin = dp(c, 6);
        box.addView(bar, blp);

        // 展开明细：该组下每一包（名称 + 已用/剩余/总量）
        final LinearLayout det = new LinearLayout(c);
        det.setOrientation(LinearLayout.VERTICAL);
        det.setPadding(0, dp(c, 6), 0, 0);
        for (Item it : g.items) {
            TextView n = new TextView(c);
            n.setText(it.name.isEmpty() ? "流量包" : it.name);
            n.setTextSize(10);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextColor(col(c, R.color.text_primary));
            det.addView(n);
            TextView s = new TextView(c);
            s.setText("已用 " + amt(it.used) + " · 剩余 " + amt(it.remain)
                    + " · 总量 " + amt(it.total));
            s.setTextSize(9);
            s.setTextColor(col(c, R.color.text_secondary));
            s.setPadding(0, dp(c, 1), 0, dp(c, 5));
            det.addView(s);
        }
        boolean open = OPEN_GROUPS.contains(key);
        det.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setText(open ? "▴" : "▾");
        box.addView(det);
        // v4.0.5：整张分类卡都能点开/收起该组明细，不再限定只能点标题栏那个箭头。
        box.setClickable(true);
        box.setOnClickListener(v -> {
            boolean visible = det.getVisibility() == View.VISIBLE;
            det.setVisibility(visible ? View.GONE : View.VISIBLE);
            arrow.setText(visible ? "▾" : "▴");
            if (visible) {
                OPEN_GROUPS.remove(key);
            } else {
                OPEN_GROUPS.add(key);
            }
        });
        return box;
    }

    private static View miniRow(Context c, View a, View b) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(c, 6);
        row.setLayoutParams(rlp);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        la.rightMargin = dp(c, 4);
        row.addView(a, la);
        if (b != null) {
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lb.leftMargin = dp(c, 4);
            row.addView(b, lb);
        } else {
            // 单数时补一个透明占位，保证左卡只占半宽
            View spacer = new View(c);
            row.addView(spacer, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private static View emptyCard(Context c) {
        LinearLayout box = card(c);
        TextView t = new TextView(c);
        t.setText("这次没读到余量缓存，进页会自动重试，也可点「一键查询」。\n"
                + "数据源是中国移动首页自己写进 localStorage 的明文余量，不用手动进「余量查询」页。");
        t.setTextSize(12);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setLineSpacing(dp(c, 3), 1f);
        box.addView(t);
        return box;
    }

    // -------------------------------------------------------------- 工具

    private static int col(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 圆角占比条：对齐 flow-assistant 的 RatioBar（比系统 ProgressBar 更贴合卡片风格）。 */
    static final class RatioBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float ratio = 0f;

        RatioBar(Context c) {
            super(c);
        }

        void setColors(int trackColor, int fillColor) {
            trackPaint.setColor(trackColor);
            fillPaint.setColor(fillColor);
            invalidate();
        }

        void setRatio(float r) {
            ratio = Math.max(0f, Math.min(1f, r));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float h = getHeight();
            float w = getWidth();
            float r = h / 2f;
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, trackPaint);
            float fw = w * ratio;
            if (fw > r * 2) {
                cv.drawRoundRect(new RectF(0, 0, fw, h), r, r, fillPaint);
            }
        }
    }
}
