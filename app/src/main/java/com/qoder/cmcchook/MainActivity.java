package com.qoder.cmcchook;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final long ACTIVE_WINDOW_MS = 5L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 3000);
        }
    };

    private TextView statusDot;
    private TextView statusText;
    private TextView heartbeatText;
    private TextView targetText;
    private TextView logCountText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        heartbeatText = findViewById(R.id.heartbeatText);
        targetText = findViewById(R.id.targetText);
        logCountText = findViewById(R.id.logCountText);

        bindSwitch(R.id.switchH5, AppPrefs.KEY_CAPTURE_H5);
        bindSwitch(R.id.switchNative, AppPrefs.KEY_CAPTURE_NATIVE);
        bindSwitch(R.id.switchUi, AppPrefs.KEY_CAPTURE_UI);
        bindSwitch(R.id.switchStorage, AppPrefs.KEY_CAPTURE_STORAGE);
        bindSwitch(R.id.switchFilter, AppPrefs.KEY_FILTER_10086);
        bindSwitch(R.id.switchH5Noise, AppPrefs.KEY_FILTER_H5_NOISE);
        bindSwitch(R.id.switchSecondary, AppPrefs.KEY_HOOK_SECONDARY, false);

        MaterialButton openCmcc = findViewById(R.id.openCmccButton);
        MaterialButton copyWelfare = findViewById(R.id.copyWelfareButton);
        MaterialButton openLogs = findViewById(R.id.openLogsButton);

        openCmcc.setOnClickListener(v -> openTarget());
        copyWelfare.setOnClickListener(v -> copyWelfareUrl());
        openLogs.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateLogCount();
        handler.removeCallbacks(refreshStatus);
        handler.postDelayed(refreshStatus, 3000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    private void bindSwitch(int viewId, String key) {
        bindSwitch(viewId, key, true);
    }

    private void bindSwitch(int viewId, String key, boolean defaultValue) {
        SwitchMaterial view = findViewById(viewId);
        view.setOnCheckedChangeListener(null);
        view.setChecked(AppPrefs.settings(this).getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.setBool(MainActivity.this, key, isChecked));
    }

    private void updateStatus() {
        boolean installed = isTargetInstalled();
        long heartbeat = AppPrefs.getLastHeartbeat(this);
        long now = System.currentTimeMillis();
        boolean active = installed && heartbeat > 0 && now - heartbeat < ACTIVE_WINDOW_MS;

        if (!installed) {
            statusText.setText(R.string.status_no_target);
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.error));
        } else if (active) {
            statusText.setText(R.string.status_active);
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else {
            statusText.setText(R.string.status_waiting);
            statusDot.setTextColor(ContextCompat.getColor(this, R.color.warning));
        }

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
            heartbeatText.setText(sb.toString());
        } else {
            heartbeatText.setText(R.string.heartbeat_never);
        }

        targetText.setText("目标：" + targetVersion());
    }

    private void updateLogCount() {
        int count = LogStore.count(this);
        logCountText.setText(getString(R.string.log_count_home, count));
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
}
