package com.qoder.cmcchook;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int TAB_HOME = 0;
    private static final int TAB_FUNC = 1;
    private static final int TAB_LOG = 2;
    private static final long ACTIVE_WINDOW_MS = 5 * 60 * 1000L;
    private static final long LOG_AUTO_MS = 2500L;
    private static final int MAX_UI_ENTRIES = 2500;
    private static final int REQUEST_STORAGE = 1001;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_HTTP = 1;
    private static final int FILTER_H5 = 2;
    private static final int FILTER_UI = 3;
    private static final int FILTER_COOKIE = 4;
    private static final int FILTER_ERROR = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private View pageHome;
    private View pageFunc;
    private View pageLog;
    private TextView pageTitle;
    private TextView statusText;
    private View statusDot;
    private TextView navHome;
    private TextView navFunc;
    private TextView navLog;

    private View homeDot;
    private TextView homeStatus;
    private TextView homeHeartbeat;
    private TextView homeTarget;
    private TextView homeLogCount;

    private RecyclerView logList;
    private LogAdapter adapter;
    private TextView logEmpty;
    private TextView logHint;
    private MaterialButton btnExport;
    private TextView chipAll;
    private TextView chipHttp;
    private TextView chipH5;
    private TextView chipUi;
    private TextView chipCookie;
    private TextView chipError;

    private int tab = TAB_HOME;
    private int filter = FILTER_ALL;
    private List<LogEntry> allEntries = new ArrayList<>();
    private long[] lastStamp = new long[]{-1, -1, -1, -1};
    private boolean loading;
    private int generation;
    private boolean pendingExport;

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 3000);
        }
    };

    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            if (tab == TAB_LOG) {
                refreshLogs(false, false);
            }
            handler.postDelayed(this, LOG_AUTO_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pageHome = findViewById(R.id.pageHome);
        pageFunc = findViewById(R.id.pageFunc);
        pageLog = findViewById(R.id.pageLog);
        pageTitle = findViewById(R.id.pageTitle);
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        navHome = findViewById(R.id.navHome);
        navFunc = findViewById(R.id.navFunc);
        navLog = findViewById(R.id.navLog);

        homeDot = findViewById(R.id.homeDot);
        homeStatus = findViewById(R.id.homeStatus);
        homeHeartbeat = findViewById(R.id.homeHeartbeat);
        homeTarget = findViewById(R.id.homeTarget);
        homeLogCount = findViewById(R.id.homeLogCount);
        TextView homeVersion = findViewById(R.id.homeVersion);
        homeVersion.setText(getString(R.string.app_name) + "  v" + versionName());

        navHome.setOnClickListener(v -> selectTab(TAB_HOME));
        navFunc.setOnClickListener(v -> selectTab(TAB_FUNC));
        navLog.setOnClickListener(v -> selectTab(TAB_LOG));

        findViewById(R.id.btnOpenCmcc).setOnClickListener(v -> openTarget());
        findViewById(R.id.btnCopyWelfare).setOnClickListener(v -> copyWelfareUrl());
        findViewById(R.id.btnOpenLogs).setOnClickListener(v -> selectTab(TAB_LOG));

        bindSwitch(R.id.switchH5, AppPrefs.KEY_CAPTURE_H5);
        bindSwitch(R.id.switchNative, AppPrefs.KEY_CAPTURE_NATIVE);
        bindSwitch(R.id.switchUi, AppPrefs.KEY_CAPTURE_UI);
        bindSwitch(R.id.switchStorage, AppPrefs.KEY_CAPTURE_STORAGE);
        bindSwitch(R.id.switchFilter, AppPrefs.KEY_FILTER_10086);
        bindSwitch(R.id.switchH5Noise, AppPrefs.KEY_FILTER_H5_NOISE);
        bindSwitch(R.id.switchVerboseRaw, AppPrefs.KEY_VERBOSE_RAW, false);
        bindSwitch(R.id.switchSecondary, AppPrefs.KEY_HOOK_SECONDARY, false);
        bindSwitch(R.id.switchAutoSign, AppPrefs.KEY_AUTO_SIGN);
        bindSwitch(R.id.switchKeepAlive, AppPrefs.KEY_KEEPALIVE);
        bindSwitch(R.id.switchPageSign, AppPrefs.KEY_PAGE_SIGN);
        bindSwitch(R.id.switchPageEgg, AppPrefs.KEY_PAGE_EGG);
        bindSwitch(R.id.switchPageGoose, AppPrefs.KEY_PAGE_GOOSE);
        findViewById(R.id.btnRunAll).setOnClickListener(
                v -> runTask(TaskRunner.CMD_ALL, "过任务"));

        // 每次进界面都按当前开关重排一次，版本升级 / 清数据后也能自动恢复。
        TaskAlarm.sync(this);

        setupLogPage();
        selectTab(TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateLogCount();
        handler.removeCallbacks(statusTick);
        handler.postDelayed(statusTick, 3000);
        handler.removeCallbacks(logTick);
        handler.postDelayed(logTick, LOG_AUTO_MS);
        if (tab == TAB_LOG) {
            refreshLogs(true, false);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(logTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(logTick);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 导航

    private void selectTab(int target) {
        tab = target;
        pageHome.setVisibility(target == TAB_HOME ? View.VISIBLE : View.GONE);
        pageFunc.setVisibility(target == TAB_FUNC ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(target == TAB_LOG ? View.VISIBLE : View.GONE);
        pageTitle.setText(target == TAB_HOME ? R.string.tab_home
                : target == TAB_FUNC ? R.string.tab_func : R.string.tab_log);
        paintNav();
        if (target == TAB_LOG) {
            refreshLogs(true, false);
            handler.removeCallbacks(logTick);
            handler.postDelayed(logTick, LOG_AUTO_MS);
        }
    }

    private void paintNav() {
        TextView[] navs = new TextView[]{navHome, navFunc, navLog};
        for (int i = 0; i < navs.length; i++) {
            boolean selected = i == tab;
            navs[i].setSelected(selected);
            navs[i].setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.text_primary : R.color.text_secondary));
            navs[i].setTypeface(Typeface.create(
                    selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
    }

    // ---------------------------------------------------------------- 首页

    private void updateStatus() {
        boolean installed = isTargetInstalled();
        long heartbeat = AppPrefs.getLastHeartbeat(this);
        long now = System.currentTimeMillis();
        boolean active = installed && heartbeat > 0 && now - heartbeat < ACTIVE_WINDOW_MS;

        int colorRes = !installed ? R.color.error : active ? R.color.success : R.color.warning;
        int color = ContextCompat.getColor(this, colorRes);
        tintDot(statusDot, color);
        tintDot(homeDot, color);

        String status = !installed ? getString(R.string.status_no_target)
                : active ? getString(R.string.status_active) : getString(R.string.status_waiting);
        statusText.setText(status);
        homeStatus.setText(status);

        if (heartbeat > 0) {
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(heartbeat));
            String process = AppPrefs.getLastProcess(this);
            String module = AppPrefs.getLastModuleVersion(this);
            StringBuilder sb = new StringBuilder("最近心跳：").append(time);
            if (!process.isEmpty()) {
                sb.append(" · ").append(shortProcess(process));
            }
            if (!module.isEmpty()) {
                sb.append(" · 模块 v").append(module);
            }
            homeHeartbeat.setText(sb.toString());
        } else {
            homeHeartbeat.setText(R.string.heartbeat_never);
        }

        // 目标版本 + 定时自检状态（用户不用抓日志就能判断保活有没有在跑）
        long tick = AppPrefs.getLastTick(this);
        String tickText;
        if (!AppPrefs.getBool(this, AppPrefs.KEY_KEEPALIVE)) {
            tickText = " · 自检已关";
        } else if (tick <= 0) {
            tickText = " · 自检待触发";
        } else {
            tickText = " · 自检 " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(tick));
        }
        homeTarget.setText("目标：" + targetVersion() + tickText);
    }

    private void updateLogCount() {
        int count = LogStore.count(this);
        homeLogCount.setText(getString(R.string.log_count_home, count));
    }

    private void tintDot(View view, int color) {
        view.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    /**
     * 下指令。
     *
     * 优先后台广播：中国移动进程还活着（90 秒内有心跳）就直接在它进程里跑，
     * 模块界面不动、也不切 App。只有进程不在了才退回到「拉起 App」。
     */
    private void runTask(String cmd, String label) {
        AppPrefs.triggerTask(this, cmd);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(cmd)) {
            String hint = TaskRunner.CMD_GOOSE.equals(cmd)
                    ? "已发到后台；收鹅蛋要等页面注入，结果看日志页"
                    : "已发到后台执行，" + label + "结果看日志页";
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            handler.postDelayed(() -> selectTab(TAB_LOG), 800);
            return;
        }
        launchTarget(cmd, label);
    }

    private boolean sendTaskBroadcast(String cmd) {
        try {
            Intent intent = new Intent(TaskReceiver.ACTION_RUN);
            intent.setPackage(AppPrefs.TARGET_PACKAGE);
            intent.putExtra("cmd", cmd);
            sendBroadcast(intent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void launchTarget(String cmd, String label) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, "没有找到中国移动 App", Toast.LENGTH_LONG).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            Toast.makeText(this, "中国移动没在运行，已拉起，" + label + "结果看日志页",
                    Toast.LENGTH_LONG).show();
            handler.postDelayed(() -> selectTab(TAB_LOG), 1200);
        } catch (Throwable e) {
            Toast.makeText(this, "唤起失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    private void bindSwitch(int viewId, String key) {
        bindSwitch(viewId, key, true);
    }

    private void bindSwitch(int viewId, String key, boolean defaultValue) {
        SwitchMaterial view = findViewById(viewId);
        view.setOnCheckedChangeListener(null);
        view.setChecked(AppPrefs.settings(this).getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppPrefs.setBool(MainActivity.this, key, isChecked);
            if (AppPrefs.KEY_AUTO_SIGN.equals(key) || AppPrefs.KEY_KEEPALIVE.equals(key)) {
                // 总开关或定时自检一变，立刻重排闹钟，不用等下次进界面。
                TaskAlarm.sync(MainActivity.this);
                if (AppPrefs.KEY_KEEPALIVE.equals(key)) {
                    Toast.makeText(MainActivity.this,
                            isChecked ? "定时自检已开启（"
                                    + TaskAlarm.describe(MainActivity.this) + "，不打开界面）"
                                    : "已关闭定时自检",
                            Toast.LENGTH_SHORT).show();
                }
            }
            if (AppPrefs.KEY_FILTER_H5_NOISE.equals(key)) {
                Toast.makeText(MainActivity.this,
                        isChecked ? "已过滤 H5 埋点噪音" : "已关闭过滤，H5 日志将逐条记录",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean isTargetInstalled() {
        try {
            getPackageManager().getPackageInfo(AppPrefs.TARGET_PACKAGE, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private String targetVersion() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(AppPrefs.TARGET_PACKAGE, 0);
            String code = Build.VERSION.SDK_INT >= 28
                    ? String.valueOf(info.getLongVersionCode())
                    : String.valueOf(info.versionCode);
            return "中国移动 " + info.versionName + " (" + code + ")";
        } catch (Throwable e) {
            return "中国移动（未安装）";
        }
    }

    private String shortProcess(String process) {
        if (process == null || process.isEmpty()) {
            return "";
        }
        int colon = process.indexOf(':');
        String suffix = colon >= 0 ? process.substring(colon) : "";
        return "中国移动" + suffix;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "";
        }
    }

    private void openTarget() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, R.string.cmcc_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开中国移动 App：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyWelfareUrl() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("福利中心链接", AppPrefs.WELFARE_URL));
            Toast.makeText(this, R.string.welfare_url_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 日志页

    private void setupLogPage() {
        logList = findViewById(R.id.logList);
        logEmpty = findViewById(R.id.logEmpty);
        logHint = findViewById(R.id.logHint);
        btnExport = findViewById(R.id.btnExport);
        chipAll = findViewById(R.id.chipAll);
        chipHttp = findViewById(R.id.chipHttp);
        chipH5 = findViewById(R.id.chipH5);
        chipUi = findViewById(R.id.chipUi);
        chipCookie = findViewById(R.id.chipCookie);
        chipError = findViewById(R.id.chipError);

        adapter = new LogAdapter();
        logList.setLayoutManager(new LinearLayoutManager(this));
        logList.setAdapter(adapter);
        logList.setItemAnimator(null);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshLogs(true, true));
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        btnExport.setOnClickListener(v -> beginExport());

        chipAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        chipHttp.setOnClickListener(v -> setFilter(FILTER_HTTP));
        chipH5.setOnClickListener(v -> setFilter(FILTER_H5));
        chipUi.setOnClickListener(v -> setFilter(FILTER_UI));
        chipCookie.setOnClickListener(v -> setFilter(FILTER_COOKIE));
        chipError.setOnClickListener(v -> setFilter(FILTER_ERROR));
        setFilter(FILTER_ALL);
    }

    private void setFilter(int value) {
        filter = value;
        TextView[] chips = new TextView[]{
                chipAll, chipHttp, chipH5, chipUi, chipCookie, chipError
        };
        for (int i = 0; i < chips.length; i++) {
            chips[i].setSelected(i == value);
        }
        applyFilter();
    }

    private void refreshLogs(boolean force, boolean toast) {
        if (loading) {
            return;
        }
        long[] stamp = LogStore.stamp(this);
        if (!force && Arrays.equals(stamp, lastStamp)) {
            return;
        }
        lastStamp = stamp;
        loading = true;
        final int gen = generation;
        final boolean wasAtBottom = adapter.size() == 0
                || !logList.canScrollVertically(1)
                || isNearBottom();
        final int oldSize = adapter.size();
        worker.execute(() -> {
            List<LogEntry> entries = LogStore.read(this, MAX_UI_ENTRIES);
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed() || gen != generation) {
                    return;
                }
                allEntries = entries;
                applyFilter();
                if (adapter.size() > oldSize && wasAtBottom && filter == FILTER_ALL) {
                    logList.scrollToPosition(adapter.size() - 1);
                }
                if (toast) {
                    Toast.makeText(this, entries.isEmpty() ? "暂无日志" : "已刷新",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void applyFilter() {
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry entry : allEntries) {
            if (matches(entry.tag)) {
                filtered.add(entry);
            }
        }
        adapter.submit(filtered);

        boolean empty = filtered.isEmpty();
        logEmpty.setText(allEntries.isEmpty()
                ? R.string.log_empty : R.string.log_filtered_empty);
        logEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        logList.setVisibility(empty ? View.GONE : View.VISIBLE);

        long kb = LogStore.totalBytes(this) / 1024L;
        String size = kb >= 1024
                ? String.format(Locale.US, "%.1f MB", kb / 1024f) : kb + " KB";
        logHint.setText(getString(R.string.log_hint_format, filtered.size(), size));
    }

    private boolean matches(String tag) {
        if (tag == null) {
            return filter == FILTER_ALL;
        }
        switch (filter) {
            case FILTER_HTTP:
                return tag.startsWith("HTTP") || tag.startsWith("OKHTTP");
            case FILTER_H5:
                return tag.startsWith("H5");
            case FILTER_UI:
                return tag.startsWith("ACTIVITY") || tag.startsWith("WEBVIEW")
                        || tag.startsWith("UI") || tag.startsWith("CLICK");
            case FILTER_COOKIE:
                return tag.startsWith("COOKIE");
            case FILTER_ERROR:
                return tag.contains("ERROR") || tag.equals("CRASH")
                        || tag.equals("EXIT") || tag.equals("EXIT-INFO");
            case FILTER_ALL:
            default:
                return true;
        }
    }

    private boolean isNearBottom() {
        LinearLayoutManager manager = (LinearLayoutManager) logList.getLayoutManager();
        return manager != null
                && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 3;
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(R.string.clear_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_clear, (dialog, which) -> {
                    LogStore.clear(this);
                    adapter.clear();
                    allEntries = new ArrayList<>();
                    generation++;
                    loading = false;
                    lastStamp = new long[]{-1, -1, -1, -1};
                    refreshLogs(true, false);
                    updateLogCount();
                    Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void beginExport() {
        if (adapter.size() == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= 28
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        doExport();
    }

    private void doExport() {
        btnExport.setEnabled(false);
        btnExport.setText(R.string.btn_exporting);
        new Thread(() -> {
            String message;
            boolean success = false;
            try {
                List<LogEntry> all = LogStore.read(this, 0);
                if (all.isEmpty()) {
                    message = getString(R.string.export_empty);
                } else {
                    String path = LogExporter.export(this, all);
                    message = getString(R.string.export_success, path);
                    success = true;
                }
            } catch (Throwable e) {
                message = getString(R.string.export_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String finalMessage = message;
            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnExport.setEnabled(true);
                btnExport.setText(R.string.btn_export_short);
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                if (finalSuccess) {
                    updateLogCount();
                }
            });
        }, "log-export").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingExport && granted) {
            pendingExport = false;
            doExport();
        } else if (pendingExport) {
            pendingExport = false;
            Toast.makeText(this, R.string.export_permission_denied, Toast.LENGTH_SHORT).show();
        }
    }
}
