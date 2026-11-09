package com.cmcchook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「消耗流量」测速 + 「流量领取」活动入口 —— v4.0.3，从 {@code flow-assistant 1.9.6}
 * 原样搬过来的两块独立原生功能，跑在模块 App 进程，不依赖 Xposed hook、不碰中国移动
 * App 的登录态，也不读取任何账号数据。
 *
 * <p>严格照搬其行为：
 * <ul>
 *   <li><b>消耗</b>：4 线程循环下载所选测速源（URL 加随机参数防缓存），
 *       {@link AtomicLong} 累计接收字节，每秒刷新，达到阈值自动停止，可手动停止/重置；</li>
 *   <li><b>领取</b>：活动入口卡片，点击用系统浏览器 {@code ACTION_VIEW} 打开官方活动页，
 *       模块自己不打开活动、不提交领取请求；</li>
 *   <li><b>配置</b>：测速源与领取入口来自 {@code https://zy520.de5.net/flow.json}
 *       （{@code speed_sources} / {@code claim_groups}），拉取失败用内置兜底。</li>
 * </ul>
 *
 * <p>如实提示：测速会<b>真实消耗流量</b>，累计接收字节不等于最终计费值；连 WiFi 时
 * 消耗的是 WiFi 而非移动数据；活动有地域和资格限制。这些说明都画在界面上。
 *
 * <p>{@link FlowView} 每次收到广播/切页都会整页重画，所以这里把运行状态放静态字段、
 * 把当前控件放静态引用：重建 View 时按状态回填，正在跑的消耗不会中断。
 */
final class FlowAssist {

    private FlowAssist() {
    }

    private static final String CONFIG_URL = "https://zy520.de5.net/flow.json";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /* ---------- 运行状态（跨 FlowView.render 重建保持） ---------- */
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong wasted = new AtomicLong(0);
    private static final ConcurrentLinkedQueue<HttpURLConnection> activeConn =
            new ConcurrentLinkedQueue<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile Runnable ticker;

    private static volatile List<String[]> sources = new ArrayList<>();   // {name,url,type}
    private static volatile List<String> sourceNames = new ArrayList<>();
    private static volatile List<String[]> claims = new ArrayList<>();     // {group,name,url}
    private static volatile int sourceIndex = 0;
    private static volatile String thresholdText = "";
    private static volatile String ipTextValue = "";
    private static volatile boolean defaultsBuilt = false;
    private static volatile boolean configLoaded = false;
    private static volatile boolean configLoading = false;

    /* ---------- 当前控件引用（render 重建后由 build 回填） ---------- */
    private static volatile TextView curPicker;
    private static volatile TextView curWaste;
    private static volatile TextView curIp;
    private static volatile Button curStart;
    private static volatile LinearLayout curClaimsBox;

    /** 画两块功能区并回填当前状态。返回的 View 由 FlowView addView 到页面底部。 */
    static View build(final Context c) {
        ensureDefaults();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(title(c, "消耗流量", R.color.primary));
        root.addView(note(c, "4 线程下载所选资源并累计接收字节，用来消耗/测试流量。"
                + "会真实产生流量，累计字节不等于最终计费值；连 WiFi 时消耗的是 WiFi 而非移动数据。\n"
                + "请使用有权访问、允许重复下载的资源，不要仅凭「定向」标签认定按定向流量计费。"));
        root.addView(consumeCard(c));

        root.addView(title(c, "流量领取", R.color.warning));
        root.addView(note(c, "点击条目用系统浏览器打开官方活动页，自行领取。模块不代领、"
                + "不提交任何领取请求；活动有地域与资格限制，入口由远程配置或内置兜底提供。"));
        LinearLayout claimsBox = new LinearLayout(c);
        claimsBox.setOrientation(LinearLayout.VERTICAL);
        curClaimsBox = claimsBox;
        renderClaims(c, claimsBox);
        root.addView(claimsBox);

        if (!configLoaded && !configLoading) {
            fetchConfig(c);
        }
        return root;
    }

    /** Activity 销毁时调用：停下载、停计时、释放控件引用，避免后台偷跑流量 / 泄漏。 */
    static void stopAll() {
        stopConsume();
        ui.removeCallbacksAndMessages(null);
        curPicker = null;
        curWaste = null;
        curIp = null;
        curStart = null;
        curClaimsBox = null;
    }

    // -------------------------------------------------------------- 内置兜底（照搬 flow-assistant buildDefaults）

    private static synchronized void ensureDefaults() {
        if (defaultsBuilt) {
            return;
        }
        defaultsBuilt = true;
        List<String[]> s = new ArrayList<>();
        s.add(new String[]{"今日头条", "https://lf9-static.bytednsdoc.com/obj/eden-cn/uhbfnupkbps/video/earth_v6.mp4", "通用"});
        s.add(new String[]{"字节跳动①", "https://lf1-cdn-tos.bytescm.com/obj/static/ies/bytedance_official/_next/static/images/8-4@2x-f85835b5e482bccf94c824067caac899.png", "通用"});
        s.add(new String[]{"字节跳动②", "https://lf3-cdn-tos.bytescm.com/obj/ttfe/ATSX/mainland/video-poster_1576231362701.png", "通用"});
        s.add(new String[]{"移动云盘①", "https://yun.mcloud.139.com/hongseyunpan/2.43G.zip", "定向"});
        s.add(new String[]{"移动云盘②", "https://open.yun.139.com/static/img/case_bg_1.16716f8f.png", "定向"});
        s.add(new String[]{"咪咕视频①", "https://img.cmvideo.cn/publish/noms/2026/09/01/1O7GU70S9OLS2.gif", "定向"});
        s.add(new String[]{"咪咕视频②", "https://img.cmvideo.cn/publish/noms/2022/10/14/1O3VIGPVP6HTS.jpg", "定向"});
        s.add(new String[]{"咪咕爱看", "https://img.aikan.miguvideo.com/publish/noms/2024/04/22/1O5MNLF3AN2JL.gif", "定向"});
        s.add(new String[]{"咪咕快游①", "https://ggv.cmvideo.cn/v1/iflyad/deliverysystem/dsp/mgc_transfiles/1290/2026/8/21/3NMgeOQQt1danQNEClrM10/48ca7568/custom_95113/3NMgeOQQt1danQNEClrM10custom_95113MP4.mp4", "定向"});
        s.add(new String[]{"咪咕快游②", "https://freeserver.migufun.com/resource/beta/video/system/20210924112351666.mp4", "定向"});
        s.add(new String[]{"移动商城", "https://img1.shop.10086.cn/goods/tcqtjwurkdsfcxgr_940x7200", "定向"});
        sources = s;

        List<String[]> cl = new ArrayList<>();
        cl.add(new String[]{"辽宁专属活动", "流量嗨翻天活动", "https://lyh.lncmcc.com/lyhVue/index.html#/llhft?actNo=lljf0419&shopId=ff8080817356d7320173573c4ade03c4&pc=Ki1nxVQshUxGgwDA9ijpwc%2FWj6E5YaXHhCimHfcUjlX2C7ZKayCuhJ5ycKe1gDAPiTmP0Dp6UiAF%0AjejCgdJM3fJb8DEoBjWSwaDNZnFXV%2Fl43qfRx7YShvzpokFOICFhpcQfcRgX%2B5FMSWs2ABIbzOX6%0AyeJ4%2F%2FAHR18lKBRRUqBsWxYlmOU79lwATEeOAZkvWuohrqYk4O%2FneCF7PvGdzLnyxsUreeVFmjSx%0AbdGQhWVfXRm%2FYi3j3Y2ZMNYxtbkmxM84CChaPTUnSbN277%2F4uxzpJLUtZI6GnLfP%2BDVRrRahazAl%0Angy47FjZnsOw%2F7l6i7SJGiH9MjkFWLxeNQdaZ4sepri8bv9TpTrAy3a%2FZNdsrfSC4M0JYS1P7nDA%0A8Fo6%2BQgZaEvJpEGmt7sGDhjaBjFEfDEPzup66WLcz9nzOQfQn8SVikUwJXwY6ZTHPjZFqH39rpK%2F%0A89taHQuTxbB3qs2Vh3Csvhtdy0DBOQy74qrCG83Khgo1kGA%2FG6xy%2BdrQM18hC5BdGW38IAn7kFTS%0AQGMF%2FlP8EvfRm0QxnMA9WLO6ciFkDiDfpigQhfmksaSGzw%2BFt2%2FBqJ8ZVWj%2FtbZWDmi7e3sYdKkx%0ANDPNKXggpoRskfSMF8bU2hDbsxWorF2wZL9G%2Fd9ANzJs7G9MKQgYp9jRMgj0YYP3v47zP7dIa3Iz%0ANH%2FUwcFNkiPK11qbQb6ZakLcdevX3lUgb5We7RDy%2Ffg1TwAbznPgEz0y5CKIIc8emUVnxAM9s%2BfN%0A%2F89B4593z6%2FV9yFuiZI6IbepEk8yzMEikKL%2BB3b42K4d45aBtiThvF2pQOD8Fb2j%2Bx%2F%2F5PhIaJyb%0AvdY6gg7kP7qa24kAuXnsSIh9AVZA%2F%2FmL1yfRfKg5qsPeVlhfPeM8URT5gJTfqC1Rb7a5uUYrJCm3%0AZHOHyKSNiQUH2K5iH5wnvb4njWFnrDLLTIWJ29l6ZoP9lv2Y4osAXT1CuMmxBN%2BqqdDh9uoZfMjp%0AJ0sMVWTj1RPxrT%2FIUtPw2riMGfF56tbgD1cvVrQXB9iubOap419%2F8uqY6VT%2B1lCtnKlevU55HvA9%0AaZQ83wAXC9VMaKQjpBZVelJUtP9ZvYma5SKD5vcPybrzK1uapHNN2vNI3IBla0zjA5U%3D&pk=438DB3667C7DDD7497B387AAE8DDB717D62293A55E252647C7E0FC879C1E22A1282F621A5F8A64F6E87A3A9518AF32CEE1C1CA09D4C9ED5EF1CA6CADE38C80F57397798B6483F0D1A9228F25ECB75B49529F5992FC06CACCB7D831A1D3624A70B199939A49A5A044B5B0A6150446A4A46B8D592BD4456F4E09F9F64186B8AFF9&_sid_=-1"});
        cl.add(new String[]{"全国通用定向流量", "30GB 咪咕悦看定向流量", "https://n.cmread.com/nap/p/mlln30G.jsp?z=1&ln=100975_1015599_3801_8_380_L1&cm=00000000&vt=3&key=1&isShare=1"});
        cl.add(new String[]{"全国通用定向流量", "50GB 咪咕视频定向流量", "https://m.miguvideo.com/m/provincevml/e0fa9f12a77b471c9cdbb7a439b86679?needLazyimg=0&sharefrom=miguvideoapp&pwId=c195ee02662b436fbc533939e99e127c"});
        cl.add(new String[]{"全国通用定向流量", "50GB 咪咕快游定向流量", "https://www.migufun.com/miguplay/html/personalTraffic/personalTrafficUp50?statisticChannel=bSj&channel=40257748296&from=778135&appChannel=40257748296"});
        cl.add(new String[]{"全国通用定向流量", "30GB 咪咕阅读定向流量", "https://n.cmread.com/nap/p/dh25hl.jsp?cm=D0022802&mbid=2bJNkIDFqp6ak7ZTm+qhh0q8OivyoMI2aOHP3YCC1T8%3D&is_np=1"});
        cl.add(new String[]{"全国通用定向流量", "30GB 咪咕音乐定向流量", "https://h5.nf.migu.cn/app/v4/zt/2021/directed-flow/index.html#/home"});
        cl.add(new String[]{"全国通用定向流量", "30GB 移动云盘定向流量", "https://caiyun.feixin.10086.cn:7071/portal/caiyunOfficialAccount/index.html?path=zeroPurchase&sourceid=1283#/zeroPurchase"});
        claims = cl;
        applySourceList(s);
    }

    // -------------------------------------------------------------- 远程配置

    private static void fetchConfig(final Context c) {
        configLoading = true;
        new Thread(() -> {
            JSONObject o = null;
            String[] urls = {CONFIG_URL, CONFIG_URL.replace("https://", "http://")};
            for (String u : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", UA);
                    conn.connect();
                    InputStream in = conn.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                    in.close();
                    conn.disconnect();
                    o = new JSONObject(sb.toString());
                    break;
                } catch (Throwable ignored) {
                }
            }
            final JSONObject cfg = o;
            ui.post(() -> {
                configLoading = false;
                if (cfg == null) {
                    return; // 拉取失败：保留内置兜底，不打扰用户
                }
                configLoaded = true;
                try {
                    JSONArray ss = cfg.optJSONArray("speed_sources");
                    if (ss != null && ss.length() > 0) {
                        List<String[]> list = new ArrayList<>();
                        for (int i = 0; i < ss.length(); i++) {
                            JSONObject s = ss.getJSONObject(i);
                            list.add(new String[]{s.optString("name"),
                                    s.optString("url"), s.optString("type", "")});
                        }
                        applySourceList(list);
                    }
                    JSONArray cg = cfg.optJSONArray("claim_groups");
                    if (cg != null && cg.length() > 0) {
                        List<String[]> list = new ArrayList<>();
                        for (int i = 0; i < cg.length(); i++) {
                            JSONObject g = cg.getJSONObject(i);
                            JSONArray links = g.optJSONArray("links");
                            if (links == null) {
                                continue;
                            }
                            for (int j = 0; j < links.length(); j++) {
                                JSONObject l = links.getJSONObject(j);
                                list.add(new String[]{g.optString("title"),
                                        l.optString("name"), l.optString("url")});
                            }
                        }
                        if (!list.isEmpty()) {
                            claims = list;
                            LinearLayout box = curClaimsBox;
                            if (box != null) {
                                renderClaims(c, box);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
        }, "cmcc-flow-config").start();
    }

    private static void applySourceList(List<String[]> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        sources = list;
        List<String> names = new ArrayList<>();
        for (String[] s : list) {
            String label = s[0];
            if (s.length > 2 && s[2] != null && !s[2].isEmpty()) {
                label += "（" + s[2] + "）";
            }
            names.add(label);
        }
        sourceNames = names;
        if (sourceIndex >= names.size()) {
            sourceIndex = 0;
        }
        updatePicker();
    }

    private static void updatePicker() {
        TextView p = curPicker;
        List<String> names = sourceNames;
        if (p != null && !names.isEmpty()
                && sourceIndex >= 0 && sourceIndex < names.size()) {
            p.setText(names.get(sourceIndex));
        }
    }

    // -------------------------------------------------------------- 消耗流量区

    private static View consumeCard(final Context c) {
        LinearLayout box = card(c);

        TextView hint = new TextView(c);
        hint.setText("选择测速源");
        hint.setTextSize(11);
        hint.setTextColor(col(c, R.color.text_secondary));
        hint.setPadding(0, 0, 0, dp(c, 5));
        box.addView(hint);

        // 测速源选择器（卡片样式 + 单选弹窗）
        LinearLayout pickerRow = new LinearLayout(c);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER_VERTICAL);
        pickerRow.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        GradientDrawable prBg = new GradientDrawable();
        prBg.setColor(col(c, R.color.container));
        prBg.setCornerRadius(dp(c, 12));
        prBg.setStroke(1, col(c, R.color.divider));
        pickerRow.setBackground(prBg);
        pickerRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 50)));
        TextView picker = new TextView(c);
        picker.setTextSize(14);
        picker.setTextColor(col(c, R.color.text_primary));
        picker.setSingleLine(true);
        picker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        picker.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        curPicker = picker;
        pickerRow.addView(picker);
        TextView arrow = new TextView(c);
        arrow.setText("▾");
        arrow.setTextSize(16);
        arrow.setTextColor(col(c, R.color.text_secondary));
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(c, 8), 0, 0, 0);
        pickerRow.addView(arrow, new LinearLayout.LayoutParams(dp(c, 28),
                LinearLayout.LayoutParams.MATCH_PARENT));
        final Runnable showDialog = () -> showSourceDialog(c);
        pickerRow.setOnClickListener(v -> showDialog.run());
        pickerRow.setOnLongClickListener(v -> {
            showDialog.run();
            return true;
        });
        box.addView(pickerRow);
        updatePicker();

        // 开始 / 停止
        Button start = new Button(c);
        start.setText(running.get() ? "停止" : "开始消耗");
        start.setAllCaps(false);
        start.setTextSize(17);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setTextColor(col(c, R.color.header_text));
        start.setBackground(pill(c, running.get() ? R.color.error : R.color.primary, 27));
        start.setOnClickListener(v -> toggleConsume(c));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 52));
        sbp.topMargin = dp(c, 10);
        start.setLayoutParams(sbp);
        curStart = start;
        box.addView(start);

        // 已消耗计数
        TextView waste = new TextView(c);
        waste.setText("已消耗 " + formatSize(wasted.get()));
        waste.setTextSize(24);
        waste.setTypeface(Typeface.DEFAULT_BOLD);
        waste.setTextColor(col(c, R.color.text_primary));
        waste.setGravity(Gravity.CENTER);
        waste.setPadding(0, dp(c, 14), 0, dp(c, 2));
        curWaste = waste;
        box.addView(waste);

        Button reset = new Button(c);
        reset.setText("重置计数");
        reset.setAllCaps(false);
        reset.setTextSize(12);
        reset.setTextColor(col(c, R.color.text_primary));
        reset.setBackground(pill(c, R.color.container_high, 16));
        reset.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        reset.setOnClickListener(v -> {
            wasted.set(0);
            TextView w = curWaste;
            if (w != null) {
                w.setText("已消耗 0.00 B");
            }
            Toast.makeText(c, "已重置", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 32));
        rbp.gravity = Gravity.CENTER_HORIZONTAL;
        rbp.topMargin = dp(c, 2);
        reset.setLayoutParams(rbp);
        box.addView(reset);

        // 自动停止阈值
        LinearLayout thRow = new LinearLayout(c);
        thRow.setOrientation(LinearLayout.HORIZONTAL);
        thRow.setGravity(Gravity.CENTER_VERTICAL);
        thRow.setPadding(0, dp(c, 10), 0, 0);
        TextView thLabel = new TextView(c);
        thLabel.setText("自动停止阈值");
        thLabel.setTextSize(14);
        thLabel.setTextColor(col(c, R.color.text_secondary));
        thRow.addView(thLabel);
        EditText threshold = new EditText(c);
        threshold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        threshold.setHint("0");
        threshold.setText(thresholdText);
        threshold.setTextSize(14);
        threshold.setGravity(Gravity.CENTER);
        threshold.setTextColor(col(c, R.color.text_primary));
        threshold.setHintTextColor(col(c, R.color.text_secondary));
        GradientDrawable thBg = new GradientDrawable();
        thBg.setColor(col(c, R.color.container_low));
        thBg.setCornerRadius(dp(c, 10));
        thBg.setStroke(1, col(c, R.color.divider));
        threshold.setBackground(thBg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(c, 110), dp(c, 42));
        tlp.leftMargin = dp(c, 8);
        threshold.setLayoutParams(tlp);
        threshold.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                thresholdText = s == null ? "" : s.toString();
            }
        });
        thRow.addView(threshold);
        TextView thUnit = new TextView(c);
        thUnit.setText("GB");
        thUnit.setTextSize(14);
        thUnit.setTextColor(col(c, R.color.text_secondary));
        thUnit.setPadding(dp(c, 6), 0, 0, 0);
        thRow.addView(thUnit);
        box.addView(thRow);

        TextView ip = new TextView(c);
        ip.setText(ipTextValue.isEmpty() ? "查询IP中…" : ipTextValue);
        ip.setTextSize(12);
        ip.setTextColor(col(c, R.color.text_secondary));
        ip.setGravity(Gravity.CENTER);
        ip.setPadding(0, dp(c, 12), 0, 0);
        curIp = ip;
        box.addView(ip);
        if (ipTextValue.isEmpty() && running.get()) {
            fetchIP();
        }
        return box;
    }

    private static void showSourceDialog(Context c) {
        final List<String> names = sourceNames;
        if (names == null || names.isEmpty()) {
            Toast.makeText(c, "暂无可选测速源", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(c)
                .setTitle("选择测速源")
                .setSingleChoiceItems(names.toArray(new String[0]), sourceIndex,
                        (DialogInterface d, int which) -> {
                            sourceIndex = which;
                            updatePicker();
                            d.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void toggleConsume(Context c) {
        if (running.get()) {
            stopConsume();
        } else {
            startConsume(c);
        }
    }

    private static void startConsume(Context c) {
        int pos = sourceIndex;
        List<String[]> src = sources;
        if (src.isEmpty() || pos < 0 || pos >= src.size()) {
            Toast.makeText(c, "无可用测速源", Toast.LENGTH_SHORT).show();
            return;
        }
        final String url = src.get(pos)[1];
        if (url == null || url.isEmpty()) {
            Toast.makeText(c, "测速源无效", Toast.LENGTH_SHORT).show();
            return;
        }
        running.set(true);
        wasted.set(0);
        Button b = curStart;
        if (b != null) {
            b.setText("停止");
            b.setBackground(pill(c, R.color.error, 27));
        }
        TextView w = curWaste;
        if (w != null) {
            w.setText("已消耗 0.00 B");
        }
        for (int i = 0; i < 4; i++) {
            new Thread(() -> consumeLoop(url), "cmcc-flow-consume").start();
        }
        stopTicker();
        ticker = new Runnable() {
            @Override public void run() {
                if (!running.get()) {
                    return;
                }
                TextView wt = curWaste;
                if (wt != null) {
                    wt.setText("已消耗 " + formatSize(wasted.get()));
                }
                String t = thresholdText;
                if (!t.isEmpty()) {
                    try {
                        double gb = Double.parseDouble(t.trim());
                        if (gb > 0 && wasted.get() >= gb * 1024 * 1024 * 1024) {
                            stopConsume();
                            Toast.makeText(c, "已达阈值，自动停止", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(ticker, 1000);
        fetchIP();
    }

    private static void consumeLoop(String base) {
        byte[] buf = new byte[65536];
        while (running.get()) {
            HttpURLConnection conn = null;
            try {
                String sep = base.contains("?") ? "&" : "?";
                URL u = new URL(base + sep + "r=" + Math.random());
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Cache-Control", "no-store");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.connect();
                activeConn.add(conn);
                InputStream in = conn.getInputStream();
                int n;
                while (running.get() && (n = in.read(buf)) > 0) {
                    wasted.addAndGet(n);
                }
                in.close();
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    activeConn.remove(conn);
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void stopConsume() {
        if (!running.getAndSet(false)) {
            return;
        }
        for (HttpURLConnection conn : activeConn) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
        activeConn.clear();
        stopTicker();
        ui.post(() -> {
            Button b = curStart;
            Context ctx = b == null ? null : b.getContext();
            if (b != null) {
                b.setText("开始消耗");
                b.setBackground(pill(ctx, R.color.primary, 27));
            }
            TextView w = curWaste;
            if (w != null) {
                w.setText("已消耗 " + formatSize(wasted.get()));
            }
        });
    }

    private static void stopTicker() {
        Runnable t = ticker;
        if (t != null) {
            ui.removeCallbacks(t);
        }
        ticker = null;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return String.format(Locale.CHINA, "%.2f B", (double) bytes);
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private static void fetchIP() {
        new Thread(() -> {
            String result = "IP查询失败";
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("https://api.leqw.top/api/ip").openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", UA);
                conn.connect();
                InputStream in = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                in.close();
                conn.disconnect();
                JSONObject o = new JSONObject(sb.toString());
                String ip = o.optJSONObject("data") != null
                        ? o.optJSONObject("data").optString("ip") : "";
                String area = o.optString("area", "");
                result = "当前IP: " + ip + (area.isEmpty() ? "" : "（" + area + "）");
            } catch (Throwable ignored) {
            }
            final String text = result;
            ui.post(() -> {
                ipTextValue = text;
                TextView v = curIp;
                if (v != null) {
                    v.setText(text);
                }
            });
        }, "cmcc-flow-ip").start();
    }

    // -------------------------------------------------------------- 流量领取区

    private static void renderClaims(Context c, LinearLayout box) {
        if (box == null) {
            return;
        }
        box.removeAllViews();
        List<String[]> list = claims;
        String lastGroup = null;
        for (String[] cl : list) {
            String group = cl.length > 0 ? cl[0] : "";
            String name = cl.length > 1 ? cl[1] : "";
            final String url = cl.length > 2 ? cl[2] : "";
            if (url.isEmpty()) {
                continue;
            }
            if (!group.equals(lastGroup)) {
                TextView gt = new TextView(c);
                gt.setText(group);
                gt.setTextSize(11);
                gt.setTypeface(Typeface.DEFAULT_BOLD);
                gt.setTextColor(col(c, R.color.warning));
                gt.setPadding(0, dp(c, 10), 0, dp(c, 4));
                box.addView(gt);
                lastGroup = group;
            }
            box.addView(claimCard(c, name, url));
        }
        if (list.isEmpty()) {
            TextView e = new TextView(c);
            e.setText("暂无活动入口");
            e.setTextSize(12);
            e.setTextColor(col(c, R.color.text_secondary));
            box.addView(e);
        }
    }

    private static View claimCard(final Context c, String name, final String url) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col(c, R.color.container_low));
        bg.setCornerRadius(dp(c, 14));
        row.setBackground(bg);
        row.setElevation(dp(c, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        row.setLayoutParams(lp);

        View dot = new View(c);
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(col(c, R.color.warning));
        dg.setCornerRadius(dp(c, 3));
        dot.setBackground(dg);
        row.addView(dot, new LinearLayout.LayoutParams(dp(c, 6), dp(c, 6)));

        TextView t = new TextView(c);
        t.setText(name);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_primary));
        t.setPadding(dp(c, 10), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        TextView chevron = new TextView(c);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(col(c, R.color.text_secondary));
        row.addView(chevron);

        row.setOnClickListener(v -> {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
            } catch (Throwable e) {
                Toast.makeText(c, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });
        return row;
    }

    // -------------------------------------------------------------- 工具

    private static View title(Context c, String text, int colorRes) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 14), 0, dp(c, 5));
        View bar = new View(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col(c, colorRes));
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

    private static View note(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setLineSpacing(dp(c, 3), 1f);
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

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
        lp.topMargin = dp(c, 4);
        box.setLayoutParams(lp);
        return box;
    }

    private static GradientDrawable pill(Context c, int colorRes, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(c == null ? 0xFF1B6EF3 : col(c, colorRes));
        gd.setCornerRadius(dp(c == null ? null : c, radiusDp));
        return gd;
    }

    private static int col(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }

    private static int dp(Context c, float v) {
        if (c == null) {
            return Math.round(v * 3f);
        }
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
