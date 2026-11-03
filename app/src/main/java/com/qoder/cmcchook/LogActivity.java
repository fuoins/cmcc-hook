package com.qoder.cmcchook;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogActivity extends AppCompatActivity {
    private static final int REQUEST_STORAGE = 1001;
    private static final int MAX_UI_ENTRIES = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refresh(false, false);
            handler.postDelayed(this, 2500);
        }
    };

    private RecyclerView listView;
    private LogAdapter adapter;
    private TextView countText;
    private View emptyView;
    private Button exportButton;
    private long[] lastStamp = new long[]{-1, -1, -1, -1};
    private boolean pendingExport;
    private boolean loading;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        listView = findViewById(R.id.logList);
        countText = findViewById(R.id.countText);
        emptyView = findViewById(R.id.emptyView);
        exportButton = findViewById(R.id.exportButton);
        Button refreshButton = findViewById(R.id.refreshButton);
        Button clearButton = findViewById(R.id.clearButton);

        adapter = new LogAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);

        refreshButton.setOnClickListener(v -> refresh(true, true));
        clearButton.setOnClickListener(v -> confirmClear());
        exportButton.setOnClickListener(v -> beginExport());

        refresh(true, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(true, false);
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, 2500);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh(boolean force, boolean toast) {
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
                || !listView.canScrollVertically(1)
                || isNearBottom();
        final int oldSize = adapter.size();
        worker.execute(() -> {
            List<LogEntry> entries = LogStore.read(this, MAX_UI_ENTRIES);
            long kb = LogStore.totalBytes(this) / 1024L;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed() || gen != generation) {
                    return;
                }
                adapter.submit(entries);
                emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                listView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);

                String suffix = kb >= 1024 ? String.format(Locale.US, "%.1f MB", kb / 1024f)
                        : kb + " KB";
                String count = "共 " + entries.size() + " 条 · " + suffix + " · 每 2.5 秒自动刷新";
                if (entries.size() >= MAX_UI_ENTRIES) {
                    count = "显示最近 " + MAX_UI_ENTRIES + " 条 · " + suffix
                            + " · 每 2.5 秒自动刷新";
                }
                countText.setText(count);

                if (entries.size() > oldSize && wasAtBottom) {
                    listView.scrollToPosition(entries.size() - 1);
                }
                if (toast) {
                    Toast.makeText(this, entries.isEmpty() ? "暂无日志" : "已刷新",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private boolean isNearBottom() {
        LinearLayoutManager manager = (LinearLayoutManager) listView.getLayoutManager();
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
                    generation++;
                    loading = false;
                    lastStamp = new long[]{-1, -1, -1, -1};
                    refresh(true, false);
                    toast(getString(R.string.log_cleared));
                })
                .show();
    }

    private void beginExport() {
        if (adapter.size() == 0) {
            toast(getString(R.string.export_empty));
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
        exportButton.setEnabled(false);
        exportButton.setText(R.string.btn_exporting);
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
                exportButton.setEnabled(true);
                exportButton.setText(R.string.btn_export);
                Toast.makeText(this, finalMessage,
                        finalSuccess ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
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
            toast(getString(R.string.export_permission_denied));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
