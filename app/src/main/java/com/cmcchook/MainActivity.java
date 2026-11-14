package com.cmcchook;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private static final int TAB_WELFARE = 1;
    private static final int TAB_FUNC = 2;
    private static final int TAB_LOG = 3;
    private static final int TAB_COUPON = 4;
    private static final long ACTIVE_WINDOW_MS = 5 * 60 * 1000L;
    private static final long LOG_AUTO_MS = 2500L;
    private static final int MAX_UI_ENTRIES = 2500;
    private static final int REQUEST_STORAGE = 1001;
    private static final int REQUEST_SMS = 1002;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_TASK = 1;
    private static final int FILTER_API = 2;
    private static final int FILTER_HTTP = 3;
    private static final int FILTER_H5 = 4;
    private static final int FILTER_UI = 5;
    private static final int FILTER_COOKIE = 6;
    private static final int FILTER_ERROR = 7;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * v4.2.0：广播发出去没人接就补发。
     *
     * <p>用户反馈「切换到优惠券和流量，有时候没有自动拉取数据」——目标是 App 刚被系统
     * 冻结/进程刚拉起时，广播打过去没人收，界面就一直停在「正在刷新」。这里 4 秒没收到
     * 任何进度就补发一次，最多两次；目标进程只要活着，收到第一条就会开始报进度。
     */
    private int resendLeft;
    private String resendScope;

    private final Runnable resendTask = new Runnable() {
        @Override
        public void run() {
            if (resendLeft <= 0 || resendScope == null) {
                return;
            }
            resendLeft--;
            if ("flow".equals(resendScope) && flowLoading) {
                // v4.10.0-r2：补发也不许把移动拉到前台，刷新统一「新开一屏」。
                sendTaskBroadcast(TaskRunner.CMD_FLOW, false);
                handler.postDelayed(this, 4_000L);
            } else if ("coupon".equals(resendScope)) {
                sendTaskBroadcast(CouponTask.CMD_SCAN, false);
                handler.postDelayed(this, 4_000L);
            }
        }
    };

    private void scheduleResend(String scope, int times) {
        handler.removeCallbacks(resendTask);
        resendScope = scope;
        resendLeft = times;
        if (times > 0) {
            handler.postDelayed(resendTask, 4_000L);
        }
    }

    private void cancelResend() {
        handler.removeCallbacks(resendTask);
        resendLeft = 0;
        resendScope = null;
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private View pageHome;
    private View pageWelfare;
    private View pageFunc;
    private View pageLog;
    private View pageCoupon;
    /** v4.0 增强容器里的两个子页 + 底部一行子导航。 */
    private View pageFlow;
    private View pageCouponBody;
    private TextView subFlow;
    private TextView subCoupon;
    private TextView flowNotify;
    private boolean flowTab = true;       // 当前子页：true=流量，false=优惠券
    private boolean flowLoading = false;  // 离屏读取进行中
    private long flowAutoAt = 0L;         // 上次「进页自动查询」时间，做节流防抖
    private static final long FLOW_AUTO_GAP_MS = 30_000L;   // 30s 内不重复自动查
    private static final long FLOW_FRESH_MS = 10 * 60_000L; // 缓存 10 分钟内视为新鲜
    /** v4.1.0：券页进页自动刷新的节流与新鲜度（和流量同一套规则）。 */
    private long couponAutoAt = 0L;
    /** 本次刷新是进页自动触发的（静默）：读完不弹「扫描完成」Toast。 */
    private boolean couponQuietScan = false;
    private static final long COUPON_AUTO_GAP_MS = 30_000L;
    private static final long COUPON_FRESH_MS = 10 * 60_000L;
    /**
     * 流量刷新整体超时。真页要拉起 + 等页面请求解密（最长 20s），读不到还要重载、
     * 再退离屏兜底，50 秒会误报超时 —— 放宽到 120 秒，结果回来会提前收工。
     */
    private static final long FLOW_TIMEOUT_MS = 120_000L;
    /** v4.10.0-r2：移动没启动时流量/券统一弹这一句，不再替用户拉起 App。 */
    private static final String NEED_APP_HINT = "请先打开中国移动，建议用小窗运行";
    private TextView pageTitle;
    /** v4.0.9：顶部蓝色品牌区。增强页不需要它，隐藏后内容顶到状态栏下，省一屏空间。 */
    private View appHeader;
    private TextView statusText;
    private TextView accountText;
    private View statusDot;
    private TextView navHome;
    private TextView navWelfare;
    private TextView navFunc;
    private TextView navLog;
    private TextView navCoupon;

    private View homeDot;
    private TextView homeStatus;
    private TextView homeHeartbeat;
    private TextView homeTarget;
    private TextView homeLogCount;
    private TextView homeTasks;

    private RecyclerView logList;
    private LogAdapter adapter;
    private TextView logEmpty;
    private TextView logHint;
    private MaterialButton btnExport;
    private TextView chipAll;
    private TextView chipTask;
    private TextView chipApi;
    private TextView chipHttp;
    private TextView chipH5;
    private TextView chipUi;
    private TextView chipCookie;
    private TextView chipError;

    /** 福利中心页 */
    private LinearLayout welfareList;
    private TextView welfareSummary;
    private TextView welfareAt;
    /** v4.10.0-r7：福利页顶部「本轮结果」条，就地看结果，点一下才进日志明细。 */
    private View welfareResult;
    private TextView welfareResultLine;
    private TextView welfareResultHint;
    /** v4.2.0：抢券卡的状态行（倒计时 / 抢到没有）。 */
    private TextView grabStatus;
    /** r45：天天领权益「优先抢」摘要行。 */
    private TextView tvPerksPick;
    /** 本机记录的抢券状态，避免重复点同一条把等待取消掉。 */
    private String grabRunningKind = "";
    private long welfareStamp = -1L;
    private TextView probeSummary;
    private long probeStamp = -1L;
    /** 单活动采集的最近一次落盘时间，用来发现「刚采完」并刷新行标记。 */
    private long actStamp = -1L;

    /** 优惠券页 */
    private LinearLayout couponListView;
    private TextView couponSummary;
    private TextView couponAt;
    private TextView couponBar;
    private MaterialButton btnCouponScan;
    private MaterialButton btnCouponAll;
    /** v4.1.0：一键勾选流量券 / 话费兑换券（替换旧版的「反选」「一键全用」）。 */
    private MaterialButton btnCouponPickFlow;
    private MaterialButton btnCouponPickPhone;
    private MaterialButton btnCouponUse;
    /**
     * v3.52：冷启动时清一次券清单（只清一次，旋转屏幕重建 Activity 不清）。
     * 用户要求「关闭软件重进后重新扫描，别直接显示之前的卡券数据，不然会出错」。
     */
    private static boolean sCouponPurged = false;
    /** v3.42：按有效期排序；v4.2.2：「隐藏满减券」实际藏的是所有不可用券。 */
    private CheckBox cbCouponSort;
    private CheckBox cbCouponHide;
    private boolean couponSortByValid = true;
    private boolean couponHideBlocked = true;
    private org.json.JSONArray couponItems = new org.json.JSONArray();
    private boolean[] couponChecked = new boolean[0];
    private boolean couponRunning;
    private boolean couponNeedingCode;
    private int couponNeedIdx = -1;
    private String couponNeedName = "";
    /** 手动输入验证码兜底弹框，自动读码成功后要关掉。 */
    private android.app.Dialog couponCodeDialog;

    private int tab = TAB_HOME;
    // v4.10.0-r7：日志页默认只看「任务」，用户进来第一眼就是任务进展，
    // 不用再从「全部」里翻。要看接口/H5/Cookie 再手动切 chip。
    private int filter = FILTER_TASK;
    /** v4.10.0-r7：本轮任务发起时间/名称，纯 UI 内存态，用于顶部结果条显示「进行中」。 */
    private long runStartedAt;
    private String runStartedLabel = "";
    /** 上次见到过的任务结果时间，变化就重建福利列表（条目状态行跟着刷新）。 */
    private long welfareTaskStamp;
    private List<LogEntry> allEntries = new ArrayList<>();
    private long[] lastStamp = new long[]{-1, -1, -1, -1};
    private boolean loading;
    private int generation;
    private boolean pendingExport;
    private int exportMode = LogExporter.MODE_FULL;

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

    /** 流量刷新超时兜底：{@link #FLOW_TIMEOUT_MS} 没收到结果就解锁，别让「正在读取」一直挂着。 */
    private final Runnable flowTimeout = new Runnable() {
        @Override
        public void run() {
            if (flowLoading) {
                flowLoading = false;
                if (flowNotify != null) {
                    flowNotify.setText("读取超时：没收到数据。请确认中国移动 App 已打开过、模块已注入，再点「刷新」重试。");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // v3.52：冷启动清空上一次的券清单，强制重新扫描。旧清单里的券可能已经
        // 用掉/过期，按旧数据勾选再批量使用必然出错。静态标记用来区分
        // 「进程重建（=关掉软件重进）」和「旋转屏幕重建 Activity」。
        if (!sCouponPurged) {
            sCouponPurged = true;
            CouponStore.clearList(this);
        }

        pageHome = findViewById(R.id.pageHome);
        pageWelfare = findViewById(R.id.pageWelfare);
        pageFunc = findViewById(R.id.pageFunc);
        pageLog = findViewById(R.id.pageLog);
        pageCoupon = findViewById(R.id.pageCoupon);
        pageTitle = findViewById(R.id.pageTitle);
        appHeader = findViewById(R.id.appHeader);
        statusText = findViewById(R.id.statusText);
        accountText = findViewById(R.id.accountText);
        statusDot = findViewById(R.id.statusDot);
        navHome = findViewById(R.id.navHome);
        navWelfare = findViewById(R.id.navWelfare);
        navFunc = findViewById(R.id.navFunc);
        navLog = findViewById(R.id.navLog);
        navCoupon = findViewById(R.id.navCoupon);

        // v4.32.0：顶部框 5 个凭证「提取」按钮 —— 点一下复制该字段当前值。
        // 值由目标进程通过 ACTION_CRED 广播同步，冷启动后最多 45 秒到位。
        bindCredButton(R.id.btnCredAppToken, "app_token");
        bindCredButton(R.id.btnCredPhone, "phone");
        bindCredButton(R.id.btnCredProvince, "province_code");
        bindCredButton(R.id.btnCredCity, "city_code");
        bindCredButton(R.id.btnCredCookie, "cookie");
        bindForceStopButton();

        homeDot = findViewById(R.id.homeDot);
        homeStatus = findViewById(R.id.homeStatus);
        homeHeartbeat = findViewById(R.id.homeHeartbeat);
        homeTarget = findViewById(R.id.homeTarget);
        homeLogCount = findViewById(R.id.homeLogCount);
        homeTasks = findViewById(R.id.homeTasks);
        TextView homeVersion = findViewById(R.id.homeVersion);
        homeVersion.setText(getString(R.string.app_name) + "  v" + versionName());

        navHome.setOnClickListener(v -> selectTab(TAB_HOME));
        navWelfare.setOnClickListener(v -> selectTab(TAB_WELFARE));
        navFunc.setOnClickListener(v -> selectTab(TAB_FUNC));
        navLog.setOnClickListener(v -> selectTab(TAB_LOG));
        navCoupon.setOnClickListener(v -> selectTab(TAB_COUPON));

        welfareList = findViewById(R.id.welfareList);
        welfareSummary = findViewById(R.id.welfareSummary);
        welfareAt = findViewById(R.id.welfareAt);
        welfareResult = findViewById(R.id.welfareResult);
        welfareResultLine = findViewById(R.id.welfareResultLine);
        welfareResultHint = findViewById(R.id.welfareResultHint);
        // 点结果条才进日志（此时日志默认已是「任务」视图），不强制跳页。
        welfareResult.setOnClickListener(v -> selectTab(TAB_LOG));
        grabStatus = findViewById(R.id.grabStatus);
        tvPerksPick = findViewById(R.id.tvPerksPick);
        findViewById(R.id.btnPerksPick).setOnClickListener(v -> askPerksPick());
        refreshPerksPick();
        findViewById(R.id.btnGrabWed).setOnClickListener(v -> grabStart("wed"));
        findViewById(R.id.btnGrabNoon).setOnClickListener(v -> grabStart("seckill"));
        findViewById(R.id.btnGrabLingxi).setOnClickListener(v -> grabStart("lingxi"));
        findViewById(R.id.btnGrabPerks10).setOnClickListener(v -> grabStart("perks10"));
        findViewById(R.id.btnGrabPerks12).setOnClickListener(v -> grabStart("perks12"));
        findViewById(R.id.btnLingxiSend).setOnClickListener(v -> askLingxiCmd());
        View cmdState = findViewById(R.id.lingxiCmdState);
        if (cmdState != null) {
            cmdState.setOnClickListener(v -> askLingxiCmd());
        }
        // v4.10.0-r2：福利页顶部改成「立即全部跑一次」；原来的「刷新（重读清单）」
        // 按用户要求删掉，CMD_WELFARE 命令本身保留，供探路 / 采集自己用。
        findViewById(R.id.btnWelfareRunAll).setOnClickListener(
                v -> runTask(TaskRunner.CMD_ALL, "过任务"));
        probeSummary = findViewById(R.id.probeSummary);
        findViewById(R.id.btnProbeStart).setOnClickListener(v -> startProbe());
        findViewById(R.id.btnProbeExport).setOnClickListener(v -> exportProbe());

        // v4.2.1：捐赠从首页「关于」区挪到标题栏右上角，所有页面都能点到。
        findViewById(R.id.btnDonateTop).setOnClickListener(
                v -> openExternalLink(getString(R.string.about_donate_url)));
        findViewById(R.id.btnGroup).setOnClickListener(
                v -> openExternalLink(getString(R.string.about_group_url)));
        findViewById(R.id.btnQq).setOnClickListener(v -> openQQGroup());
        findViewById(R.id.btnProject).setOnClickListener(
                v -> openExternalLink(getString(R.string.about_project_url)));

        couponListView = findViewById(R.id.couponList);
        couponSummary = findViewById(R.id.couponSummary);
        couponAt = findViewById(R.id.couponAt);
        couponBar = findViewById(R.id.couponBar);
        btnCouponScan = findViewById(R.id.btnCouponScan);
        btnCouponAll = findViewById(R.id.btnCouponAll);
        btnCouponPickFlow = findViewById(R.id.btnCouponPickFlow);
        btnCouponPickPhone = findViewById(R.id.btnCouponPickPhone);
        btnCouponUse = findViewById(R.id.btnCouponUse);
        // v4.1.0：券页按钮 = 刷新 / 全选 / 勾选流量券 / 勾选话费兑换券 / 使用。
        // 「反选」「一键全用」已删除；进页会自动刷新一次。
        btnCouponScan.setOnClickListener(v -> couponScan());
        btnCouponAll.setOnClickListener(v -> couponSelectAll());
        if (btnCouponPickFlow != null) {
            btnCouponPickFlow.setOnClickListener(v -> couponPick(false));
        }
        if (btnCouponPickPhone != null) {
            btnCouponPickPhone.setOnClickListener(v -> couponPick(true));
        }
        btnCouponUse.setOnClickListener(v -> couponUse());
        // v4.0.8：券页只留「一键扫描」，删掉 v4.0.7 的「打开卡券中心」前置入口。
        // 扫描改为纯离屏，AppBridges 已把真页的私有 JS 桥装回离屏页。
        // v4.32.0：删掉「小米 / HyperOS 读不到验证码？点这里看自检步骤」入口，
        // 换成券页里一条静态说明（LSPosed 推荐应用全点开，验证码自动填写）。
        cbCouponSort = findViewById(R.id.cbCouponSort);
        cbCouponHide = findViewById(R.id.cbCouponHide);
        if (cbCouponSort != null) {
            cbCouponSort.setChecked(couponSortByValid);
            cbCouponSort.setOnCheckedChangeListener((b, on) -> {
                couponSortByValid = on;
                buildCoupon();
            });
        }
        if (cbCouponHide != null) {
            cbCouponHide.setChecked(couponHideBlocked);
            cbCouponHide.setOnCheckedChangeListener((b, on) -> {
                couponHideBlocked = on;
                buildCoupon();
            });
        }

        // v4.0 增强页：底栏「优惠券」→「增强」，页内底部一行子导航（流量 / 优惠券）。
        pageFlow = findViewById(R.id.pageFlow);
        pageCouponBody = findViewById(R.id.pageCouponBody);
        subFlow = findViewById(R.id.subFlow);
        subCoupon = findViewById(R.id.subCoupon);
        flowNotify = findViewById(R.id.flowNotify);
        if (subFlow != null) {
            subFlow.setOnClickListener(v -> selectFlowSub(true));
        }
        if (subCoupon != null) {
            subCoupon.setOnClickListener(v -> selectFlowSub(false));
        }
        View btnFlowRefresh = findViewById(R.id.btnFlowRefresh);
        if (btnFlowRefresh != null) {
            btnFlowRefresh.setOnClickListener(v -> flowRefresh());
        }
        // v4.0.6：删掉增强页顶部「一键查扫描」工具条。
        // 流量子页用「一键查询」、优惠券子页用「一键扫描」，各管各的，合并按钮反而绕。
        selectFlowSub(true); // 默认落在流量子页

        // r39：三个默认值对老用户也生效 —— prefs 里已存的旧值会盖住新 default，
        // 必须在读开关之前显式迁移一次（只写一次，之后用户自己改就保持）。
        AppPrefs.migrateR39Defaults(this);
        // r39：「只抓业务接口」默认关，埋点/广告/静态资源也照常记，抓包更全。
        bindSwitch(R.id.switchApiOnly, AppPrefs.KEY_API_ONLY, false);
        bindSwitch(R.id.switchH5, AppPrefs.KEY_CAPTURE_H5);
        bindSwitch(R.id.switchNative, AppPrefs.KEY_CAPTURE_NATIVE);
        bindSwitch(R.id.switchUi, AppPrefs.KEY_CAPTURE_UI);
        bindSwitch(R.id.switchStorage, AppPrefs.KEY_CAPTURE_STORAGE);
        bindSwitch(R.id.switchFilter, AppPrefs.KEY_FILTER_10086, false);
        bindSwitch(R.id.switchH5Noise, AppPrefs.KEY_FILTER_H5_NOISE);
        bindSwitch(R.id.switchVerboseRaw, AppPrefs.KEY_VERBOSE_RAW, true);
        bindSwitch(R.id.switchSecondary, AppPrefs.KEY_HOOK_SECONDARY, false);
        bindSwitch(R.id.switchAdBlock, AppPrefs.KEY_AD_BLOCK);
        bindSwitch(R.id.switchAutoSign, AppPrefs.KEY_AUTO_SIGN);
        bindSwitch(R.id.switchKeepAlive, AppPrefs.KEY_KEEPALIVE);
        // r39：快路径默认开（失败自动兜底）；常驻采集默认开，手动做一遍活动
        // 就能录到全量原始请求，不用每次先去功能页拨开关。
        bindSwitch(R.id.switchFastPath, AppPrefs.KEY_FAST_PATH, true);
        bindSwitch(R.id.switchRawCapture, AppPrefs.KEY_RAW_CAPTURE, true);
        refreshCaptureState();
        View btnCaptureExport = findViewById(R.id.btnCaptureExport);
        if (btnCaptureExport != null) {
            btnCaptureExport.setOnClickListener(v -> exportCapture());
        }
        View btnCaptureClear = findViewById(R.id.btnCaptureClear);
        if (btnCaptureClear != null) {
            btnCaptureClear.setOnClickListener(v -> clearCapture());
        }
        // r20：一键录制 / 导出原始流（全量、脱敏）。两个入口都是纯新增。
        View btnCaptureRecord = findViewById(R.id.btnCaptureRecord);
        if (btnCaptureRecord != null) {
            btnCaptureRecord.setOnClickListener(v -> askRecordCapture());
        }
        View btnCaptureRaw = findViewById(R.id.btnCaptureRaw);
        if (btnCaptureRaw != null) {
            btnCaptureRaw.setOnClickListener(v -> {
                CaptureSession.exportNow(this, true);
                Toast.makeText(this, "正在导出全量原始流，产物在 Downloads/CmccHook",
                        Toast.LENGTH_LONG).show();
            });
        }
        View btnCaptureDesc = findViewById(R.id.btnCaptureDesc);
        if (btnCaptureDesc != null) {
            btnCaptureDesc.setOnClickListener(v -> askDescriptorMenu());
        }
        // v4.10.0-r2：「立即全部跑一次」已挪到福利中心顶部，功能页不再有这个按钮。

        // 每次进界面都按当前开关重排一次，版本升级 / 清数据后也能自动恢复。
        TaskAlarm.sync(this);
        // 升级自愈：擦掉旧版「登录态失效按天封杀」误判写进去的历史标记。
        // 该机制 v3.14.1 起已停用，但旧 settings 里可能残留今天的值。
        try {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().remove("login_lost_day").apply();
        } catch (Throwable ignored) {
        }
        // r39：日志导出只保留「完整」，不再弹范围选择框，也不再沿用上次选择。
        exportMode = LogExporter.MODE_FULL;

        setupLogPage();
        LogReceiver.setCouponWatcher(this::onCouponEvent);
        LogReceiver.setFlowWatcher(this::onFlowEvent);
        // v4.2.0：刷新 / 抢券的实时步骤，直接写到状态行，用户不用干等。
        LogReceiver.setProgressWatcher(this::onProgressEvent);
        selectTab(TAB_HOME);
        // 短信权限只申请一次，用户拒绝也不阻塞其它功能。
        ensureSmsPermission(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLingxiCmd();
        updateStatus();
        updateLogCount();
        handler.removeCallbacks(statusTick);
        handler.postDelayed(statusTick, 3000);
        handler.removeCallbacks(logTick);
        handler.postDelayed(logTick, LOG_AUTO_MS);
        if (tab == TAB_LOG) {
            refreshLogs(true, false);
        }
        // v4.0.2：从中国移动 App 切回模块、当前正停在「增强·流量」页时，
        // 顺手自动查一次（缓存新鲜/节流会挡住重复查询）。
        if (tab == TAB_COUPON) {
            if (flowTab) {
                maybeAutoFlow();
            } else {
                maybeAutoCoupon();
            }
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(logTick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // r18：任务实际跑在中国移动进程里，模块进程的 TaskRunner.RUNNING 永远是 false，
        // r17 用它判断「任务仍在跑」等于永假。改成看任务下发时间戳：15 分钟内算在跑。
        long seq = AppPrefs.getTaskSeq(this);
        boolean running = seq > 0 && System.currentTimeMillis() - seq < 15 * 60 * 1000L;
        if (running) {
            HookLog.log("APP", "模块界面退到后台 · 本轮任务可能仍在跑",
                    "保活服务由模块进程启动，任务结束会收到 TASK_END 自动停止。\n"
                            + "如果这之后日志仍中断，说明被杀的是中国移动进程，不是模块。");
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(logTick);
        worker.shutdownNow();
        LogReceiver.setCouponWatcher(null);
        LogReceiver.setFlowWatcher(null);
        LogReceiver.setProgressWatcher(null);
        handler.removeCallbacks(flowTimeout);
        // v4.0.3：停掉消耗测速的下载线程与计时器，避免退出后后台继续偷跑流量。
        FlowAssist.stopAll();
        dismissCodeDialog();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 导航

    private void selectTab(int target) {
        tab = target;
        pageHome.setVisibility(target == TAB_HOME ? View.VISIBLE : View.GONE);
        pageWelfare.setVisibility(target == TAB_WELFARE ? View.VISIBLE : View.GONE);
        pageFunc.setVisibility(target == TAB_FUNC ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(target == TAB_LOG ? View.VISIBLE : View.GONE);
        pageCoupon.setVisibility(target == TAB_COUPON ? View.VISIBLE : View.GONE);
        // v4.0.9：增强页去掉顶部蓝色标题框，内容直接顶到状态栏下，省出一屏空间。
        if (appHeader != null) {
            appHeader.setVisibility(target == TAB_COUPON ? View.GONE : View.VISIBLE);
        }
        if (pageCoupon != null) {
            // header 拿掉后要自己让出状态栏高度，否则内容会钻到状态栏底下。
            pageCoupon.setPadding(0, target == TAB_COUPON ? statusBarPx() : 0, 0, 0);
        }
        pageTitle.setText(target == TAB_HOME ? R.string.tab_home
                : target == TAB_WELFARE ? R.string.tab_welfare
                : target == TAB_FUNC ? R.string.tab_func
                : target == TAB_COUPON ? R.string.tab_coupon : R.string.tab_log);
        paintNav();
        if (target == TAB_WELFARE) {
            buildWelfare();
        }
        if (target == TAB_COUPON) {
            buildCoupon();
            if (flowTab && pageFlow != null) {
                FlowView.render(this, pageFlow);
                // v4.0.2：进「增强·流量」页自动刷新余量，不用手动点按钮、
                // 更不用手动进中国移动 App 的余量查询页。带节流，缓存新鲜就跳过。
                maybeAutoFlow();
            } else if (!flowTab) {
                // v4.1.0：进「优惠券」子页也自动刷新一次券列表。
                maybeAutoCoupon();
            }
        }
        if (target == TAB_LOG) {
            refreshLogs(true, false);
            handler.removeCallbacks(logTick);
            handler.postDelayed(logTick, LOG_AUTO_MS);
        }
    }

    /** 状态栏高度（px）；取不到就按 24dp 估。 */
    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int px = getResources().getDimensionPixelSize(id);
            if (px > 0) {
                return px;
            }
        }
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    private void paintNav() {
        TextView[] navs = new TextView[]{navHome, navWelfare, navFunc, navLog, navCoupon};
        for (int i = 0; i < navs.length; i++) {
            boolean selected = i == tab;
            navs[i].setSelected(selected);
            navs[i].setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.on_primary : R.color.text_secondary));
            navs[i].setTypeface(Typeface.create(
                    selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
    }

    // ---------------------------------------------------------------- 首页

    /**
     * v3.57：刷新顶部账号标签。账号由目标进程随心跳/日志广播同步上来。
     *
     * <p>切号时 {@link AppPrefs#setAccount} 已经清了两边的券清单，这里补一个提示，
     * 并把券页重新渲染一次 —— 否则界面上还挂着上一个账号的券。
     */
    private void refreshAccount() {
        if (accountText == null) {
            return;
        }
        String label = AppPrefs.accountLabel(this);
        if (label.isEmpty()) {
            accountText.setVisibility(View.GONE);
        } else {
            accountText.setText(getString(R.string.account_fmt, label));
            accountText.setVisibility(View.VISIBLE);
        }
        String from = AppPrefs.consumeAccountSwitch(this);
        if (from.isEmpty()) {
            return;
        }
        Toast.makeText(this, getString(R.string.account_switched, label),
                Toast.LENGTH_LONG).show();
        couponChecked = new boolean[0];
        couponItems = new org.json.JSONArray();
        buildCoupon();
    }

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

        refreshAccount();

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
        homeTasks.setText(todayTasks());
        // v4.10.0-r7：状态栏每 3 秒刷新一次，顺带让福利页顶部结果条跟着 STATE 广播
        // 落地的结果更新（进行中 → 完成/失败），不用切页、也不用重新进福利页。
        refreshWelfareResult();

        // 盘点结果由目标进程广播回来，落在 prefs 里；这里按时间戳发现变化就重建列表。
        // v4.10.0-r7：任务结果时间变了也要重建 —— 每个活动的状态行才不会停在旧的
        // 「待跑」，跑完一轮后能逐项看到 ✓/✗/失败原因。
        long taskAt = AppPrefs.getLastTaskAt(this);
        boolean taskChanged = taskAt != welfareTaskStamp;
        if (taskChanged) {
            welfareTaskStamp = taskAt;
        }
        if (tab == TAB_WELFARE
                && (taskChanged
                || WelfareCatalog.liveAt(this) != welfareStamp
                || ProbeStore.at(this) != probeStamp
                || ProbeStore.actsAt(this) != actStamp)) {
            buildWelfare();
        }
    }

    /**
     * 今日全部已接入活动的完成状态，一眼能看出哪个没跑。
     *
     * 任务跑在中国移动 App 进程里，结果写在那边；模块这边靠 STATE 广播同步，
     * 所以读的是 AppPrefs 而不是 TaskRunner 的本地存储。
     */
    private String todayTasks() {
        String[][] all = {
                {"签到", TaskRunner.CMD_SIGN}, {"砸蛋", TaskRunner.CMD_EGG},
                {"鹅蛋", TaskRunner.CMD_GOOSE}, {"球赛", TaskRunner.CMD_SOCCER},
                {"礼盒", TaskRunner.CMD_TEARBOX}, {"周六", TaskRunner.CMD_SATURDAY},
                {"追剧", TaskRunner.CMD_VIDEO}, {"日签", TaskRunner.CMD_DAILY7},
                {"豆苗", TaskRunner.CMD_MIAO},
                {"充值", TaskRunner.CMD_RECHARGE}, {"周三", TaskRunner.CMD_WED},
                {"抢券", TaskRunner.CMD_SECKILL}, {"三日", TaskRunner.CMD_LUCKY3},
                {"周礼", TaskRunner.CMD_WEEKLY}, {"签抽", TaskRunner.CMD_SIGNLOTTERY},
                {"问答", TaskRunner.CMD_QUIZ}
        };
        // v4.10.0-r7：先给一行四态汇总（信息更全），再列逐项 ✓/✗/○。
        int[] c = todayStats();
        StringBuilder sb = new StringBuilder("今日：")
                .append(c[ST_DONE]).append(" 完成 · ")
                .append(c[ST_FAIL]).append(" 失败 · ")
                .append(c[ST_PENDING]).append(" 待跑");
        if (c[ST_OFF] > 0) {
            sb.append(" · ").append(c[ST_OFF]).append(" 未启用");
        }
        sb.append('\n').append("　　　");
        for (int i = 0; i < all.length; i++) {
            if (i > 0) {
                if (i % 4 == 0) {
                    sb.append('\n').append("　　　");
                } else {
                    sb.append("   ");
                }
            }
            sb.append(taskMark(all[i][0], all[i][1]));
        }
        return sb.toString();
    }

    private String taskMark(String name, String cmd) {
        String day = AppPrefs.today();
        String result = AppPrefs.getTaskState(this, cmd, day);
        int times = AppPrefs.getTaskAttempts(this, cmd, day);
        if (result.isEmpty()) {
            return name + (times > 0 ? " ✗" + times : " ○");
        }
        if (result.startsWith("OK")) {
            return name + " ✓";
        }
        return name + (times > 1 ? " ✗" + times : " ✗");
    }

    // ------------------------------------------- v4.10.0-r7 今日状态（纯展示层）
    // 只读 AppPrefs.status（STATE 广播落地）与开关，不碰 TaskRunner / 广播 / 目标进程。
    private static final int ST_DONE = 0, ST_FAIL = 1, ST_PENDING = 2, ST_OFF = 3;

    /** 单活动今日四态：完成 / 失败 / 待跑 / 未启用。item 可为 null（首页紧凑总览）。 */
    private int stateKind(String cmd, WelfareCatalog.Item item) {
        String day = AppPrefs.today();
        String result = AppPrefs.getTaskState(this, cmd, day);
        int times = AppPrefs.getTaskAttempts(this, cmd, day);
        if (result.startsWith("OK")) {
            return ST_DONE;
        }
        if (result.startsWith("FAIL") || times > 0) {
            return ST_FAIL;
        }
        if (item != null && !WelfareCatalog.enabled(this, item)) {
            return ST_OFF;
        }
        return ST_PENDING;
    }

    private int stateColor(int kind) {
        return kind == ST_DONE ? R.color.success
                : kind == ST_FAIL ? R.color.error
                : kind == ST_PENDING ? R.color.warning
                : R.color.text_secondary;
    }

    /** 去掉 OK / FAIL 前缀，拿到成功说明或失败原因；截到 48 字。 */
    private static String cleanState(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("OK ")) {
            s = s.substring(3).trim();
        } else if (s.startsWith("FAIL ")) {
            s = s.substring(5).trim();
        }
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }

    /** 活动条目第二行：今日状态 + 结果/失败原因 + 时间 + 尝试次数。 */
    private String stateLine(String cmd, int kind) {
        String day = AppPrefs.today();
        String raw = AppPrefs.getTaskState(this, cmd, day);
        int times = AppPrefs.getTaskAttempts(this, cmd, day);
        long at = AppPrefs.getTaskAt(this, cmd, day);
        String time = at > 0
                ? new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)) : "";
        String brief = cleanState(raw);
        StringBuilder sb = new StringBuilder();
        switch (kind) {
            case ST_DONE:
                sb.append("✓ 已完成");
                if (!brief.isEmpty()) {
                    sb.append(" · ").append(brief);
                }
                break;
            case ST_FAIL:
                sb.append("✗ 失败");
                if (times > 1) {
                    sb.append(" ×").append(times);
                }
                if (!brief.isEmpty()) {
                    sb.append(" · ").append(brief);
                }
                break;
            case ST_PENDING:
                sb.append("○ 待跑 · 今天还没跑");
                break;
            default:
                sb.append("– 未启用 · 开关关着，本轮不会跑");
        }
        if (!time.isEmpty() && (kind == ST_DONE || kind == ST_FAIL)) {
            sb.append(" · ").append(time);
        }
        return sb.toString();
    }

    /** 今日四态计数，首页汇总与福利页顶部结果条共用。 */
    private int[] todayStats() {
        int[] c = new int[4];
        for (WelfareCatalog.Item item : WelfareCatalog.items(this)) {
            if (item.state == WelfareCatalog.STATE_IMPL && item.cmd != null) {
                c[stateKind(item.cmd, item)]++;
            }
        }
        return c;
    }

    /**
     * 福利页顶部「本轮结果」条：今日四态汇总 + 最近一次任务 / 进行中。
     * 点结果条才进日志明细 —— 一键跑完不再强制切页。
     */
    private void refreshWelfareResult() {
        if (welfareResultLine == null) {
            return;
        }
        int[] c = todayStats();
        int total = c[0] + c[1] + c[2] + c[3];
        long lastAt = AppPrefs.getLastTaskAt(this);
        String last = AppPrefs.getLastTaskLine(this);
        boolean running = runStartedAt > 0 && runStartedAt >= lastAt
                && System.currentTimeMillis() - runStartedAt < 5 * 60 * 1000L;

        StringBuilder sb = new StringBuilder();
        sb.append("今日 ").append(c[0]).append(" 完成 · ").append(c[1]).append(" 失败 · ")
                .append(c[2]).append(" 待跑");
        if (c[3] > 0) {
            sb.append(" · ").append(c[3]).append(" 未启用");
        }
        sb.append("（共 ").append(total).append(" 项）");
        if (running) {
            sb.append('\n').append("● 正在执行")
                    .append(runStartedLabel.isEmpty() ? "" : "「" + runStartedLabel + "」")
                    .append("… 跑完结果会自动刷到本页");
        } else if (lastAt > 0 && !last.isEmpty()) {
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(lastAt));
            String lastBrief = cleanState(last)
                    .replace("· OK ", "· ").replace("· FAIL ", "· ");
            sb.append('\n').append("最近 ").append(time).append(" · ").append(lastBrief);
        } else {
            sb.append('\n').append(getString(R.string.welfare_result_empty));
        }
        welfareResultLine.setText(sb.toString());
        welfareResultHint.setVisibility((running || lastAt > 0) ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ 福利中心

    /**
     * 重建福利中心列表。
     *
     * 分三组：已接入（能直接跑）、待接入（开关先记着）、只能手动。
     * 每行一个开关，签到/砸蛋/鹅蛋和「功能」页共用同一个 key，不会出现
     * 一处开着一处关着、任务却不动的情况。
     */
    private void buildWelfare() {
        if (welfareList == null) {
            return;
        }
        welfareList.removeAllViews();
        List<WelfareCatalog.Item> items = WelfareCatalog.items(this);
        int impl = 0;
        int on = 0;
        for (WelfareCatalog.Item item : items) {
            if (item.state == WelfareCatalog.STATE_IMPL) {
                impl++;
            }
            if (WelfareCatalog.enabled(this, item)) {
                on++;
            }
        }
        welfareSummary.setText("共 " + items.size() + " 项 · 已接入 " + impl + " 项 · 已开 " + on + " 项");
        long at = WelfareCatalog.liveAt(this);
        if (at > 0) {
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at));
            welfareAt.setText(getString(R.string.welfare_at, time, WelfareCatalog.liveCount(this)));
        } else {
            welfareAt.setText(R.string.welfare_never);
        }

        int[] groups = {WelfareCatalog.STATE_IMPL, WelfareCatalog.STATE_NEW,
                WelfareCatalog.STATE_PENDING, WelfareCatalog.STATE_MANUAL};
        for (int state : groups) {
            boolean header = false;
            for (WelfareCatalog.Item item : items) {
                if (item.state != state) {
                    continue;
                }
                if (!header) {
                    welfareList.addView(groupHeader(state));
                    header = true;
                }
                welfareList.addView(welfareRow(item));
            }
        }
        welfareStamp = at;
        actStamp = ProbeStore.actsAt(this);
        refreshProbeSummary();
        refreshWelfareResult();
    }

    /** 探路摘要：时间 + 页面/请求/入口数。 */
    private void refreshProbeSummary() {
        if (probeSummary == null) {
            return;
        }
        probeSummary.setText(ProbeStore.summary(this));
        probeStamp = ProbeStore.at(this);
    }

    private TextView groupHeader(int state) {
        TextView tv = new TextView(this);
        tv.setText(state == WelfareCatalog.STATE_IMPL ? R.string.welfare_group_impl
                : state == WelfareCatalog.STATE_NEW ? R.string.welfare_group_new
                : state == WelfareCatalog.STATE_PENDING ? R.string.welfare_group_pending
                : R.string.welfare_group_manual);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(ContextCompat.getColor(this, R.color.primary));
        int top = (int) (16 * getResources().getDisplayMetrics().density);
        int bottom = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, top, 0, bottom);
        return tv;
    }

    /** v4.2.0：删掉一条扫描发现的入口（内置的不允许删）。 */
    private void confirmDeleteWelfare(final WelfareCatalog.Item item) {
        if (item == null) {
            return;
        }
        if (!item.live) {
            Toast.makeText(this, "内置活动不能删除，只能关闭开关", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除这一条")
                .setMessage(item.name + "\n\n只会从清单里去掉，下次扫描扫到同一条也不会再出现。"
                        + "想恢复就清除应用数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    WelfareCatalog.hide(this, item.frag);
                    buildWelfare();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private View welfareRow(final WelfareCatalog.Item item) {
        View row = getLayoutInflater().inflate(R.layout.item_welfare, welfareList, false);
        TextView name = row.findViewById(R.id.welfareName);
        TextView desc = row.findViewById(R.id.welfareDesc);
        TextView tag = row.findViewById(R.id.welfareTag);
        TextView state = row.findViewById(R.id.welfareState);
        TextView tasks = row.findViewById(R.id.welfareTasks);
        SwitchMaterial toggle = row.findViewById(R.id.welfareSwitch);

        name.setText(item.name);
        String text = item.desc;
        long actAt = ProbeStore.actAt(this, item.frag);
        if (actAt > 0) {
            text = text + "　已采集 "
                    + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(actAt));
        }
        desc.setText(text);

        // v4.10.0-r7：状态独立一行（四态 + 结果/失败原因 + 时间 + 次数，按态着色），
        // 下面一行只留「这个活动能自动做什么」，不再把状态挤在同一行里。
        if (item.state == WelfareCatalog.STATE_IMPL && item.cmd != null) {
            int kind = stateKind(item.cmd, item);
            state.setVisibility(View.VISIBLE);
            state.setText(stateLine(item.cmd, kind));
            state.setTextColor(ContextCompat.getColor(this, stateColor(kind)));
            tasks.setVisibility(View.VISIBLE);
            tasks.setText("能做：" + tasksOf(item.cmd));
        } else {
            state.setVisibility(View.GONE);
            tasks.setVisibility(View.GONE);
        }

        tag.setText(WelfareCatalog.stateName(item.state));
        int color = item.state == WelfareCatalog.STATE_IMPL ? R.color.success
                : item.state == WelfareCatalog.STATE_PENDING ? R.color.warning
                : R.color.text_secondary;
        tag.setTextColor(ContextCompat.getColor(this, color));

        if (!WelfareCatalog.runnable(item)) {
            // 没有引擎认领的条目不给开关：给了也点不动，只会让人以为能跑。
            toggle.setVisibility(View.GONE);
        } else {
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(WelfareCatalog.enabled(this, item));
            toggle.setOnCheckedChangeListener((buttonView, checked) -> {
                WelfareCatalog.setEnabled(MainActivity.this, item, checked);
                syncTaskSwitches();
                // 回调里直接把这一行拆掉容易踩到 View 的状态，推迟到下一个消息再重建。
                handler.post(MainActivity.this::buildWelfare);
                if (item.state == WelfareCatalog.STATE_NEW && checked) {
                    Toast.makeText(MainActivity.this, R.string.welfare_toggle_new,
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        // v4.10.0-r2：点活动行只做一件事 —— 执行这个活动的任务（用户要求
        // 「每个活动点击只保留执行任务」）。没有引擎的活动没有任务可跑，
        // 才退回原来的菜单（打开页面 / 采集 / 导出）。
        row.setOnClickListener(v -> {
            if (WelfareCatalog.runnable(item)) {
                runOneAct(item);
                return;
            }
            showWelfareActions(item);
        });
        return row;
    }

    /** 已接入活动的任务能力一行字，挂在活动行下面。 */
    private String tasksOf(String cmd) {
        if (TaskRunner.CMD_SIGN.equals(cmd)) {
            return "每日签到 · 每日任务 · AI豆 · 累计奖励 · 月签 · 组队 · 浏览";
        }
        if (TaskRunner.CMD_EGG.equals(cmd)) {
            return "浏览任务 ×8 · 当日次数抽奖";
        }
        if (TaskRunner.CMD_GOOSE.equals(cmd)) {
            return "打卡 · 投喂 · 收蛋 · 领任务";
        }
        if (TaskRunner.CMD_VIDEO.equals(cmd)) {
            return "视频日签到 · 视频任务 · 抽奖";
        }
        if (TaskRunner.CMD_DAILY7.equals(cmd)) {
            return "进页面点打卡 · 连签 7 天";
        }
        if (TaskRunner.CMD_MIAO.equals(cmd)) {
            return "签到领阳光值 · 浇水施肥";
        }
        if (TaskRunner.CMD_RECHARGE.equals(cmd)) {
            return "浏览任务换次数 · 砸蛋抽奖";
        }
        if (TaskRunner.CMD_WED.equals(cmd)) {
            return "七天打卡 · 8折充值券 · 抽奖";
        }
        if (TaskRunner.CMD_SECKILL.equals(cmd)) {
            return "签到有礼 · 每日 12 点秒杀话费券";
        }
        if (TaskRunner.CMD_LUCKY3.equals(cmd)) {
            return "累计签到 · 每三天抽奖";
        }
        if (TaskRunner.CMD_WEEKLY.equals(cmd)) {
            return "每周/月度翻牌";
        }
        if (TaskRunner.CMD_SIGNLOTTERY.equals(cmd)) {
            return "每日签到 · 任务 · 转盘抽奖";
        }
        if (TaskRunner.CMD_QUIZ.equals(cmd)) {
            return "每天 3 道判断题 · 答案随题目下发";
        }
        if (cmd != null && cmd.startsWith(TaskRunner.CMD_DIY)) {
            return "每日任务 · 浏览 · 抽奖";
        }
        return "自动任务";
    }

    /**
     * 点一行：先把状态和地址说清楚，再给动作。
     *
     * <p>「软件内打开」是把链接交给中国移动 App 自己处理，不是模块另开浏览器：
     * 登录态、活动参数、页面里那套 JS 都是 App 现成的。
     * 「采集这个活动」只进这一页把控件和接口带回来，用来给它写执行逻辑；
     * 「导出」把那一次采集的原始报告发出去。
     */
    private void showWelfareActions(final WelfareCatalog.Item item) {
        final String url = WelfareCatalog.liveUrl(this, item);
        final long actAt = ProbeStore.actAt(this, item.frag);
        final float d = getResources().getDisplayMetrics().density;

        // 框架里 setMessage 与 setItems 互斥（message 优先，列表整个不渲染），
        // 所以详情和动作都放进自定义内容区，逐行显式排版，不依赖 message。
        final List<String> actions = new ArrayList<>();
        final List<Runnable> handlers = new ArrayList<>();
        if (!url.isEmpty()) {
            actions.add("在软件内打开");
            handlers.add(() -> openInTargetApp(url));
            actions.add("复制链接");
            handlers.add(() -> copyLink(url));
        }
        // 已接入的活动给「执行任务」：只跑这一个活动，方便逐个测试。
        if (WelfareCatalog.runnable(item)) {
            actions.add("执行任务");
            handlers.add(() -> runOneAct(item));
        }
        if (!url.isEmpty()) {
            actions.add("引导采集 · 点按钮");
            handlers.add(() -> collectLive(item, url));
            actions.add("自动采集 · 只开页");
            handlers.add(() -> collectOne(item, url));
        }
        // 导出固定留一个位置：没有报告时点了会提示「还没采集过」，比按钮忽隐忽现好懂。
        actions.add("导出采集报告");
        handlers.add(() -> exportAct(item));
        // v4.2.0：扫描发现的条目可以删。内置的不给这个选项 —— 内置是模块自己维护的
        // 活动表，删了下次升级又会回来，反而让人以为没删掉。
        if (item.live) {
            actions.add("删除这一条");
            handlers.add(() -> confirmDeleteWelfare(item));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);

        TextView desc = new TextView(this);
        desc.setText(item.desc);
        desc.setTextSize(14);
        desc.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        box.addView(desc);

        TextView state = new TextView(this);
        state.setText("状态：" + WelfareCatalog.stateName(item.state));
        state.setTextSize(14);
        state.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        state.setPadding(0, (int) (8 * d), 0, 0);
        box.addView(state);

        TextView link = new TextView(this);
        if (url.isEmpty()) {
            link.setText("链接：还没有可用地址");
        } else {
            link.setText("链接：" + url);
        }
        link.setTextSize(12);
        link.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        link.setTypeface(android.graphics.Typeface.MONOSPACE);
        link.setPadding(0, (int) (6 * d), 0, 0);
        box.addView(link);

        TextView collected = new TextView(this);
        collected.setText(actAt > 0 ? "已采集：" + new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new Date(actAt)) : "还没有采集过");
        collected.setTextSize(12);
        collected.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        collected.setPadding(0, (int) (4 * d), 0, 0);
        box.addView(collected);

        View divider = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * d));
        lp.setMargins(0, (int) (8 * d), 0, 0);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        box.addView(divider);

        final android.app.Dialog[] holder = new android.app.Dialog[1];
        for (int i = 0; i < actions.size(); i++) {
            final Runnable run = handlers.get(i);
            TextView row = new TextView(this);
            row.setText(actions.get(i));
            row.setTextSize(16);
            row.setTextColor(ContextCompat.getColor(this, R.color.primary));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
            row.setMinHeight((int) (48 * d));
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setOnClickListener(v -> {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                run.run();
            });
            box.addView(row);
        }

        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
    }

    /**
     * 只跑这一个活动的自动任务（测试用）。
     *
     * <p>发的是活动自己的任务命令，跟自动执行走同一条通道、同一套引擎，
     * 结果写日志页。今天已经跑过也照样再跑一遍，方便反复验证。
     */
    private void runOneAct(final WelfareCatalog.Item item) {
        if (item == null || item.cmd == null || item.cmd.isEmpty()) {
            Toast.makeText(this, "这个活动还没接入自动任务", Toast.LENGTH_SHORT).show();
            return;
        }
        AppPrefs.triggerTask(this, item.cmd);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(item.cmd)) {
            markRunStarted(item.name);
            Toast.makeText(this, "正在执行「" + item.name + "」，结果看本页顶部",
                    Toast.LENGTH_LONG).show();
            return;
        }
        launchTarget(item.cmd, "执行「" + item.name + "」任务", item.name);
    }

    /**
     * 「登录态失效按天封杀」已停用（v3.14.1），恒 false。
     *
     * <p>保留方法签名供旧调用点编译。qwhdsso/login 是正常换票中转页，
     * 原判定会误伤且形成无法自愈的死锁，详见 TaskRunner.loginLostNow()。
     */
    private boolean loginLostToday() {
        return false;
    }

    /**
     * 引导式采集：把活动页在前台打开，你在页面上手动点按钮，
     * 模块记下每次点击和点击后的请求链，页面右下角浮层点一下结束。
     *
     * <p>适合「点按钮之后才发请求」的活动（领券/秒杀/报名类），
     * 自动采集只开页不点按钮，抓不到这些。
     */
    private void collectLive(final WelfareCatalog.Item item, String url) {
        final String cmd = TaskRunner.CMD_PROBE_LIVE + item.frag + "|" + url;
        AppPrefs.triggerTask(this, cmd);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(cmd)) {
            Toast.makeText(this,
                    "活动页已打开：点活动里的按钮，右下角浮层点「结束」完成采集",
                    Toast.LENGTH_LONG).show();
            return;
        }
        launchTarget(cmd, "引导采集「" + item.name + "」");
    }

    /**
     * 采这一个活动。
     *
     * <p>必须在中国移动进程里跑：登录态、X5 内核、页面缓存都在那边，
     * 模块进程自己开页只会落到登录页。所以跟跑任务走同一条通道。
     */
    private void collectOne(final WelfareCatalog.Item item, String url) {
        final String cmd = TaskRunner.CMD_PROBE_ONE + item.frag + "|" + url;
        AppPrefs.triggerTask(this, cmd);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(cmd)) {
            Toast.makeText(this,
                    "正在采集「" + item.name + "」，采完这一行会标出来，大约十几秒",
                    Toast.LENGTH_LONG).show();
            return;
        }
        launchTarget(cmd, "采集「" + item.name + "」");
    }

    /** 导出这一个活动的采集报告（JSON，可直接发给别人分析）。 */
    private void exportAct(final WelfareCatalog.Item item) {
        String json = ProbeStore.loadAct(this, item.frag);
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, "这个活动还没采集过", Toast.LENGTH_LONG).show();
            return;
        }
        final String name = ProbeStore.actExportName(item.frag, item.name);
        new Thread(() -> {
            try {
                LogExporter.Output out = LogExporter.exportFile(this, name,
                        "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (out.uri != null) {
                        shareProbe(out.uri);
                    } else {
                        Toast.makeText(this, "已保存到 " + out.path, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + e, Toast.LENGTH_LONG).show());
            }
        }, "act-export").start();
    }

    /**
     * 交给中国移动 App 自己开这个地址。
     *
     * <p>App 在前台时发指令给它自己开：那边是同 UID，不受 Activity 导出限制，
     * 而且页面就在用户刚用过的那个 App 里。进程不在才由模块直接拉 Activity，
     * 再不行退回系统 ACTION_VIEW，最后才复制链接。
     */
    private void openInTargetApp(final String url) {
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(TaskRunner.CMD_OPEN + url)) {
            Toast.makeText(this, R.string.welfare_opening, Toast.LENGTH_SHORT).show();
            return;
        }
        // App 进程不在：模块进程直开必然 NEW_TASK 新栈，容器拿不到路由上下文
        // 不加载页面（转圈的根源）。改为先拉起 App，等 receiver 就位后把
        // 指令补发过去，由 App 进程换票 + 同栈打开，和手动路径一致。
        Intent launch = getPackageManager().getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
        if (launch == null) {
            copyLink(url);
            Toast.makeText(this, R.string.status_no_target, Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, R.string.welfare_opening, Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                sendTaskBroadcast(TaskRunner.CMD_OPEN + url);
            }
        }, 4_000L);
    }

    /** v4.32.0：绑定一个「提取」按钮，点击把对应凭证复制到剪贴板。 */
    private void bindCredButton(int viewId, String key) {
        View button = findViewById(viewId);
        if (button == null) {
            return;
        }
        button.setOnClickListener(v -> copyCredential(key));
    }

    private void copyCredential(String key) {
        String value = AppPrefs.credential(this, key);
        if (value == null || value.isEmpty()) {
            Toast.makeText(this, getString(R.string.cred_empty, key), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText(key, value));
            // v4.33.0：文案变长（含「需要刷新请进入[签到有礼]」），用 LONG 才看得全。
            Toast.makeText(this, getString(R.string.cred_copied, key, value.length()),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private void copyLink(String url) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null) {
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText("url", url));
            Toast.makeText(this, R.string.welfare_url_copied, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------- v4.33.0 强行停止中国移动

    private void bindForceStopButton() {
        View button = findViewById(R.id.btnForceStop);
        if (button == null) {
            return;
        }
        button.setOnClickListener(v -> confirmForceStop());
    }

    /** 危险操作，和 5 个「提取」按钮挨着，点完先二次确认。 */
    private void confirmForceStop() {
        try {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.force_stop_cmcc)
                    .setMessage(R.string.force_stop_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.force_stop_yes,
                            (dialog, which) -> forceStopTarget())
                    .show();
        } catch (Throwable ignored) {
            forceStopTarget();
        }
    }

    /**
     * 主路径是 {@code am force-stop}（会把包打进 stopped 状态，服务/通知/任务一起停），
     * LSPosed 环境一般能拿到 root。依次降级：su -c → 直接 am → 反射框架 API，
     * 全失败再发广播让目标进程自己收尾，至少把卡住的任务和保活停掉。
     */
    private void forceStopTarget() {
        final String pkg = AppPrefs.TARGET_PACKAGE;
        Toast.makeText(this, R.string.force_stop_running, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean stopped = runForceStopCmd(
                        new String[]{"su", "-c", "am force-stop " + pkg});
                if (!stopped) {
                    stopped = runForceStopCmd(new String[]{"am", "force-stop", pkg});
                }
                if (!stopped) {
                    stopped = forceStopViaActivityManager(pkg);
                }
                final int msg;
                if (stopped) {
                    msg = R.string.force_stop_done;
                } else {
                    boolean sent = sendTaskBroadcast(TaskRunner.CMD_FORCESTOP, false);
                    msg = sent ? R.string.force_stop_partial : R.string.force_stop_failed;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "cmcc-force-stop").start();
    }

    /** exit 0 且输出里没有权限/异常字样，才算真的停掉（am 失败时也常回 0）。 */
    private static boolean runForceStopCmd(String[] cmd) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            if (proc.waitFor() != 0) {
                return false;
            }
            String s = out.toString().toLowerCase(Locale.US);
            return !(s.contains("permission") || s.contains("denial")
                    || s.contains("not allowed") || s.contains("securityexception")
                    || s.contains("not found") || s.contains("unknown command")
                    || s.contains("error") || s.contains("exception"));
        } catch (Throwable e) {
            return false;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 没有 shell 权限时再试框架 API；普通 App 通常被 SecurityException 挡下。 */
    private boolean forceStopViaActivityManager(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            java.lang.reflect.Method m = am.getClass()
                    .getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 福利页改了开关，功能页那几个开关要跟着变，别两处显示不一致。 */
    private void syncTaskSwitches() {
    }

    /**
     * 重读清单：只把目标进程里已缓存的采集结果重新读一遍，不重新采集。
     * 要重新采集走「立即采集」。
     */
    /**
     * v4.2.0：抢券入口。
     *
     * <p>「提前不是模块提前，是用户提前进，会在页面等着」—— 所以这里不做任何时间判断，
     * 点了就把 App 拉到前台、打开抢券页停住；目标进程的 {@link GrabTask} 自己算还差多久，
     * 到点再动手。已经过了开抢时间就直接试。
     */
    /** r45：刷新抢兑卡上的「优先抢」摘要行。 */
    private void refreshPerksPick() {
        if (tvPerksPick == null) {
            return;
        }
        String a = AppPrefs.perksWords(this, "1");
        String b = AppPrefs.perksWords(this, "2");
        if (a.isEmpty() && b.isEmpty()) {
            tvPerksPick.setText(R.string.perks_pick_none);
        } else {
            tvPerksPick.setText("优先抢：10点[" + (a.isEmpty() ? "顺序" : a)
                    + "] · 12点[" + (b.isEmpty() ? "顺序" : b) + "]");
        }
    }

    /**
     * r45：两档各一套优先奖品关键词。奖品名不硬编码 —— 从目标进程回传的
     * {@code queryActivityInfo} 奖池里列出来，点一下就填进输入框。
     */
    private void askPerksPick() {
        JSONObject rules = null;
        try {
            String j = AppPrefs.perksRules(this);
            if (!j.isEmpty()) {
                rules = new JSONObject(j);
            }
        } catch (Throwable ignored) {
        }

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(box, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(this);
        desc.setText(R.string.perks_pick_desc);
        desc.setTextSize(13f);
        box.addView(desc);

        final String[] keys = {"1", "2"};
        final int[] titles = {R.string.perks_pick_r1, R.string.perks_pick_r2};
        final EditText[] inputs = new EditText[2];
        for (int i = 0; i < 2; i++) {
            TextView head = new TextView(this);
            head.setText(titles[i]);
            head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            head.setTextSize(14f);
            head.setPadding(0, pad / 2, 0, 0);
            box.addView(head);

            EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setHint(R.string.perks_pick_hint);
            String saved = AppPrefs.perksWords(this, keys[i]);
            et.setText(saved);
            et.setSelection(saved.length());
            box.addView(et, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            inputs[i] = et;

            JSONArray prizes = null;
            if (rules != null) {
                try {
                    JSONObject r = rules.optJSONObject(keys[i]);
                    if (r != null) {
                        prizes = r.optJSONArray("prizes");
                    }
                } catch (Throwable ignored) {
                }
            }
            if (prizes == null || prizes.length() == 0) {
                TextView none = new TextView(this);
                none.setText(R.string.perks_pick_no_rules);
                none.setTextSize(12f);
                none.setPadding(0, 4, 0, 4);
                box.addView(none);
            } else {
                for (int k = 0; k < prizes.length(); k++) {
                    String name = "";
                    String status = "";
                    try {
                        JSONObject pz = prizes.optJSONObject(k);
                        if (pz != null) {
                            name = pz.optString("name", "");
                            status = pz.optString("status", "");
                        }
                    } catch (Throwable ignored) {
                    }
                    if (name.isEmpty()) {
                        continue;
                    }
                    final String word = name;
                    final EditText target = et;
                    TextView chip = new TextView(this);
                    chip.setText("· " + name + ("1".equals(status) ? "" : "  [" + status + "]"));
                    chip.setTextSize(13f);
                    chip.setPadding(0, 6, 0, 6);
                    chip.setClickable(true);
                    chip.setOnClickListener(v -> appendPerksWord(target, word));
                    box.addView(chip);
                }
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perks_pick_title)
                .setView(scroll)
                .setPositiveButton(R.string.perks_pick_save, (dialog, which) -> {
                    AppPrefs.setPerksWords(this, "1", inputs[0].getText().toString());
                    AppPrefs.setPerksWords(this, "2", inputs[1].getText().toString());
                    refreshPerksPick();
                    Toast.makeText(this, R.string.perks_pick_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.perks_pick_clear, (dialog, which) -> {
                    AppPrefs.setPerksWords(this, "1", "");
                    AppPrefs.setPerksWords(this, "2", "");
                    refreshPerksPick();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 点奖品名 = 追加成关键词（逗号分隔、去重、最多 8 个）。 */
    private void appendPerksWord(EditText et, String word) {
        String cur = et.getText().toString().trim();
        String[] parts = cur.isEmpty() ? new String[0] : cur.split("[,，;；\\s]+");
        for (String one : parts) {
            if (one.equals(word)) {
                return;
            }
        }
        if (parts.length >= 8) {
            return;
        }
        et.setText(cur.isEmpty() ? word : cur + "," + word);
        et.setSelection(et.getText().length());
    }

    private void grabStart(String kind) {
        if (kind == null || kind.isEmpty()) {
            return;
        }
        boolean same = kind.equals(grabRunningKind);
        String cmdText;
        if ("lingxi".equals(kind)) {
            cmdText = LingxiCmd.text(this);
        } else if (kind.startsWith("perks")) {
            // r45：把这一档的「优先抢」关键词随命令下发到目标进程。
            cmdText = AppPrefs.perksWords(this, "perks10".equals(kind) ? "1" : "2");
        } else {
            cmdText = "";
        }
        if (!sendTaskBroadcast(GrabTask.CMD + kind, "text", cmdText, true)) {
            Toast.makeText(this, "指令发送失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (same) {
            grabRunningKind = "";
            if (grabStatus != null) {
                grabStatus.setText(getString(R.string.grab_idle));
            }
            Toast.makeText(this, "已取消等待", Toast.LENGTH_SHORT).show();
            return;
        }
        grabRunningKind = kind;
        if (grabStatus != null) {
            grabStatus.setText("· 打开抢券页…");
        }
        Toast.makeText(this, "lingxi".equals(kind)
                ? "已开始等待；请停在灵犀的券页，到点会自动点"
                : "已进入抢券页，正在等待开抢", Toast.LENGTH_SHORT).show();
    }

    /**
     * v4.10.0-r2：福利页的「刷新（重读本机清单）」入口已按用户要求删除。
     * 需要重新盘点时走下面的「扫描福利专区」（{@link #startProbe()}），
     * 那条链路会真正进活动页采集，比只读本机清单有用。
     */

    /**
     * 探路：跟刷新清单走同一条通道，但目标进程会用离屏 WebView 真的把页面走一遍。
     * 跑完通过广播把报告发回来，这里只负责展示摘要和导出。
     */
    private void startProbe() {
        AppPrefs.triggerTask(this, TaskRunner.CMD_PROBE);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(TaskRunner.CMD_PROBE)) {
            Toast.makeText(this, R.string.probe_sent, Toast.LENGTH_LONG).show();
            selectTab(TAB_LOG);
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, R.string.status_no_target, Toast.LENGTH_LONG).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            Toast.makeText(this, R.string.probe_need_app, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, "唤起失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    /** r13：显示最近捕获到的灵犀口令，换号后不用再问用户。 */
    private void refreshLingxiCmd() {
        TextView tv = findViewById(R.id.lingxiCmdState);
        if (tv == null) {
            return;
        }
        String text = LingxiCmd.text(this);
        if (text.isEmpty()) {
            tv.setText(R.string.lingxi_cmd_none);
            return;
        }
        long at = LingxiCmd.at(this);
        String when = at > 0
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at)) : "刚刚";
        String source = LingxiCmd.source(this);
        if ("ws".equals(source)) {
            source = "帧";
        } else if ("input".equals(source)) {
            source = "输入框";
        } else if ("bubble".equals(source)) {
            source = "气泡";
        } else if (source.isEmpty()) {
            source = "手动";
        }
        tv.setText(getString(R.string.lingxi_cmd_state, text, when, source));
    }

    /**
     * r13：手动发口令。
     *
     * <p>口令每次活动不同，所以不预置默认值：带按钮的号点一次按钮，模块会把候选
     * 记进设置；这里预填最近一次的值，用户确认或改写后发给没有按钮的号。
     */
    private void askLingxiCmd() {
        String saved = LingxiCmd.text(this);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.lingxi_cmd_hint);
        if (!saved.isEmpty()) {
            input.setText(saved);
            input.setSelection(saved.length());
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lingxi_cmd_title)
                .setMessage(R.string.lingxi_cmd_desc)
                .setView(box)
                .setPositiveButton(R.string.lingxi_send, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(this, R.string.lingxi_send_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    LingxiCmd.save(this, text, "manual", "");
                    refreshLingxiCmd();
                    if (grabStatus != null) {
                        grabStatus.setText("· 正在发送口令…");
                    }
                    if (!sendTaskBroadcast(LingxiGrab.CMD_SEND, "text", text, true)) {
                        Toast.makeText(this, R.string.lingxi_send_fail, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.lingxi_cmd_clear, (dialog, which) -> {
                    LingxiCmd.clear(this);
                    refreshLingxiCmd();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * r10：采集状态一行字。
     *
     * <p>r8 只显示「还没录到请求」，用户没法判断断在开关、广播还是落盘。
     * 现在把模块侧开关、已落盘条数、最后写入时间直接摊开；导出的报告里
     * 还有目标进程自检（它真实读到的开关值）。
     */
    private void refreshCaptureState() {
        TextView tv = findViewById(R.id.captureState);
        if (tv == null) {
            return;
        }
        boolean on = AppPrefs.settings(this).getBoolean(AppPrefs.KEY_RAW_CAPTURE, true);
        int n = RawCaptureStore.count(this);
        long at = RawCaptureStore.lastAt(this);
        String when = at > 0
                ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(at))
                : "—";
        tv.setText(getString(R.string.capture_state, on ? "开" : "关", n, when));
    }

    private void exportCapture() {
        refreshCaptureState();
        int n = RawCaptureStore.count(this);
        if (n == 0) {
            boolean on = AppPrefs.settings(this).getBoolean(AppPrefs.KEY_RAW_CAPTURE, true);
            Toast.makeText(this, on ? R.string.capture_no_data_on : R.string.capture_no_data_off,
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String name = RawCaptureStore.exportName(this);
        new Thread(() -> {
            try {
                String report = RawCaptureStore.buildReport(this);
                // r12：把采集摘要直接写进模块日志。这样只导日志也能看到「几个活动、
                // 每个活动几步几接口几密文」，不用再打开几 MB 的 JSON 翻。
                try {
                    LogStore.append(this, new LogEntry(System.currentTimeMillis(),
                            "CAPTURE", "采集报告已生成 · " + name,
                            RawCaptureStore.brief(report)));
                } catch (Throwable ignored) {
                }
                LogExporter.Output out = LogExporter.exportFile(this, name,
                        "application/json",
                        report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (out.uri != null) {
                        shareProbe(out.uri);
                    } else {
                        Toast.makeText(this, "已保存到 " + out.path, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.capture_export_fail) + " " + e,
                        Toast.LENGTH_LONG).show());
            }
        }, "capture-export").start();
    }

    private void clearCapture() {
        RawCaptureStore.clear(this);
        refreshCaptureState();
        Toast.makeText(this, R.string.capture_cleared, Toast.LENGTH_SHORT).show();
    }

    /**
     * r20：一键录制 —— 让用户选「录哪个活动」，然后编排
     * 开采集 → 清旧流 → 跑一次 → TASK_END 自动导出全量原始流。
     *
     * <p>之所以要用户选：录制的目的通常是「某个还没打通的活动，手动/自动跑一遍
     * 把请求序列录下来发我」。一键全跑也留了入口，覆盖「整套链路复现」的场景。
     */
    private void askRecordCapture() {
        final String[][] acts = {
                {"一键全跑（整套链路）", TaskRunner.CMD_ALL},
                {"豆苗", TaskRunner.CMD_MIAO}, {"鹅蛋", TaskRunner.CMD_GOOSE},
                {"周三充值日", TaskRunner.CMD_WED}, {"周六游戏日", TaskRunner.CMD_SATURDAY},
                {"周周有好礼", TaskRunner.CMD_WEEKLY}, {"签到抽奖", TaskRunner.CMD_SIGNLOTTERY},
                {"幸运三日签", TaskRunner.CMD_LUCKY3}, {"充值先领券", TaskRunner.CMD_RECHARGE},
                {"追剧领福利", TaskRunner.CMD_VIDEO}, {"球赛", TaskRunner.CMD_SOCCER}
        };
        String[] names = new String[acts.length];
        for (int i = 0; i < acts.length; i++) {
            names[i] = acts[i][0];
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("一键录制 · 选一个活动")
                .setMessage("会临时打开采集、清空旧流、跑一次选中的活动，结束后自动导出"
                        + "全量原始流（脱敏）到 Downloads/CmccHook。把 *.jsonl 发给我即可离线分析。\n"
                        + "跑完会自动关掉采集，不影响平时使用。")
                .setItems(names, (d, which) -> {
                    String inner = acts[which][1];
                    String hint = CaptureSession.begin(this, inner, true);
                    Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
                    refreshCaptureState();
                    // 用模块进程现有的命令通道把「跑活动」发出去；
                    // TASK_END 回来时 CaptureSession 会自动导出并关开关。
                    runTask(inner, "录制 · " + acts[which][0]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** r20：描述清单 / 导入 / 跑一条描述。 */
    private void askDescriptorMenu() {
        String[] items = {"查看描述清单与校验结果", "导入描述 JSON", "跑一条启用的描述"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("活动描述（r20 闭环）")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        String rep = ActivityDescriptor.report(this);
                        HookLog.log("DESC", "活动描述清单", rep);
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("描述清单（完整内容已写进日志）")
                                .setMessage(rep)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    } else if (which == 1) {
                        askImportDescriptor();
                    } else {
                        askRunDescriptor();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 从已保存且启用的描述里挑一条跑，描述 JSON 随命令广播直传到目标进程。 */
    private void askRunDescriptor() {
        org.json.JSONArray arr = ActivityDescriptor.load(this);
        java.util.List<org.json.JSONObject> enabled = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject one = arr.optJSONObject(i);
            if (one != null && one.optBoolean("enabled", false)) {
                enabled.add(one);
                names.add(one.optString("name", one.optString("id", "?")));
            }
        }
        if (enabled.isEmpty()) {
            Toast.makeText(this, "没有 enabled=true 的描述。先导入，人工核对后再启用。",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("跑哪条描述")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    String id = enabled.get(w).optString("id", "");
                    String carried = descriptorPayload();
                    runTaskWithDescriptor(ActivityDescriptor.CMD_DESC + id, carried);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 把整个 descriptors.json 原样作为 extra 内容，目标进程按 id 自己挑一条。 */
    private String descriptorPayload() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), ActivityDescriptor.FILE);
            if (!f.exists()) {
                return "";
            }
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] c = new byte[8192];
            int n;
            while ((n = in.read(c)) > 0) {
                buf.write(c, 0, n);
            }
            in.close();
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    /** desc: 命令专用：把描述 JSON 塞进广播 extra，绕过跨进程读不到私有文件的问题。 */
    private void runTaskWithDescriptor(String cmd, String payload) {
        AppPrefs.triggerTask(this, cmd);
        KeepAliveService.start(this);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(cmd, "desc_json", payload, true)) {
            Toast.makeText(this, "已下发描述执行，结果看本页顶部", Toast.LENGTH_LONG).show();
            markRunStarted("描述执行");
            return;
        }
        launchTarget(cmd, "描述执行", "描述执行");
    }

    /**
     * r20：导入活动描述。把离线分析产出的 descriptors.json 内容粘进来，
     * 校验通过后存进模块私有目录；enabled=true 的才接管命令。
     */
    private void askImportDescriptor() {
        EditText input = new EditText(this);
        input.setHint("粘贴 {\"descriptors\":[...]} JSON");
        input.setSingleLine(false);
        input.setMaxLines(10);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入活动描述")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("校验并保存", (d, w) -> {
                    String r = ActivityDescriptor.save(this, input.getText().toString().trim());
                    Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                    HookLog.log("DESC", "导入活动描述 · " + r.split("\n")[0],
                            r + "\n\n" + ActivityDescriptor.report(this));
                })
                .show();
    }

    private void exportProbe() {
        String json = ProbeStore.load(this);
        if (json == null || json.isEmpty()) {
            Toast.makeText(this, R.string.probe_no_report, Toast.LENGTH_LONG).show();
            return;
        }
        final String name = ProbeStore.exportName(this);
        new Thread(() -> {
            try {
                LogExporter.Output out = LogExporter.exportFile(this, name,
                        "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (out.uri != null) {
                        shareProbe(out.uri);
                    } else {
                        Toast.makeText(this, "已保存到 " + out.path, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.probe_export_fail) + " " + e,
                        Toast.LENGTH_LONG).show());
            }
        }, "probe-export").start();
    }

    private void shareProbe(android.net.Uri uri) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.probe_export)));
        } catch (Throwable e) {
            Toast.makeText(this, String.valueOf(e), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ 优惠券

    /**
     * v3.42：改成白名单 —— 只有「流量券」和「话费兑换券」可以选。
     * 日志里 32 张券还包括 话费满减券 / 话费加赠券 / 话费折扣券 / 会员权益券 等，
     * 这些点「去使用」要么是充值页要么是第三方页，核销脚本跑不动，一律置灰。
     * 待生效 / 已失效 / 需详情页 之类状态也照样排除。
     */
    private boolean couponBlocked(org.json.JSONObject item) {
        String name = item.optString("name");
        String type = item.optString("type");
        String raw = item.optString("raw");
        String state = item.optString("state");
        // v4.36.0：扫描端把「按钮=去查看」的券标成 usable=false —— 这批多是权益券/
        // 第三方券，点「去查看」会跳外部页，批量用一定失败。这里直接拦掉置灰，
        // 免得 UI 让人勾上一张点不动的券；日志里仍然逐张列出来。
        if (item.has("usable") && !item.optBoolean("usable", true)) {
            return true;
        }
        String all = name + " " + type + " " + raw;
        if (state.contains("待生效") || state.contains("未生效") || state.contains("尚未生效")) {
            return true;
        }
        if (state.contains("需详情页") || state.contains("需激活")) {
            return true;
        }
        if (state.contains("已失效") || state.contains("已过期")
                || state.contains("已使用") || state.contains("已核销")) {
            return true;
        }
        if (all.contains("话费加赠") || all.contains("加赠券")) {
            return true;
        }
        // 白名单：流量券（type 或名称含「流量」+「券」）、话费兑换券。
        boolean flowCoupon = (type.contains("流量") || name.contains("流量"))
                && all.contains("券");
        boolean phoneExchange = name.contains("话费兑换券")
                || (all.contains("话费") && name.contains("兑换券"));
        return !(flowCoupon || phoneExchange);
    }

    /** 重建券列表。清单来自目标进程扫描后广播回来的 JSON，落盘在 CouponStore。 */
    private void buildCoupon() {
        if (couponListView == null) {
            return;
        }
        couponListView.removeAllViews();
        org.json.JSONArray raw = new org.json.JSONArray();
        String json = CouponStore.list(this);
        long at = CouponStore.at(this);
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                raw = arr;
            }
        } catch (Throwable ignored) {
        }
        // v3.42：按有效期排序。扫描端把「有效期至日期」和「剩 N天 N小时」都换算成
        // 统一的到期时间戳 expire，这里升序排（快到期的在前），识别不到的排最后。
        List<org.json.JSONObject> list = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            org.json.JSONObject it = raw.optJSONObject(i);
            if (it != null) {
                list.add(it);
            }
        }
        if (couponSortByValid) {
            list.sort((a, b) -> {
                long ea = a.optLong("expire", 0L);
                long eb = b.optLong("expire", 0L);
                if (ea == 0L && eb == 0L) {
                    return 0;
                }
                if (ea == 0L) {
                    return 1;
                }
                if (eb == 0L) {
                    return -1;
                }
                return Long.compare(ea, eb);
            });
        }
        org.json.JSONArray items = new org.json.JSONArray();
        for (org.json.JSONObject it : list) {
            items.put(it);
        }
        // 保留旧勾选：优先用扫描序号 i 精确匹配（同名券不串），缺 i 时退回名字。
        boolean[] old = couponChecked;
        org.json.JSONArray oldItems = couponItems;
        boolean[] checked = new boolean[items.length()];
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject it = items.optJSONObject(i);
            if (it == null || couponBlocked(it)) {
                continue;
            }
            String name = it.optString("name");
            int seq = it.optInt("i", -1);
            for (int j = 0; j < old.length && j < oldItems.length(); j++) {
                org.json.JSONObject prev = oldItems.optJSONObject(j);
                if (prev == null || !old[j]) {
                    continue;
                }
                boolean same = (seq >= 0 && prev.optInt("i", -1) == seq)
                        || (seq < 0 && name.equals(prev.optString("name")));
                if (same) {
                    checked[i] = true;
                    break;
                }
            }
        }
        couponItems = items;
        couponChecked = checked;

        int usable = 0;
        int hidden = 0;
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject it = items.optJSONObject(i);
            if (it == null) {
                continue;
            }
            boolean blocked = couponBlocked(it);
            if (!blocked) {
                usable++;
            }
            // v4.2.2：按钮仍叫「隐藏满减券」，但实际效果改成「隐藏不可用」——
            // 凡是被 couponBlocked 拦下、列表里置灰勾不上的（满减/折扣/加赠、
            // 待生效、已失效、已使用、会员权益券等）全部不显示，只留能批量的。
            if (couponHideBlocked && blocked) {
                hidden++;
                continue;
            }
            couponListView.addView(couponRow(it, i));
        }
        if (items.length() == 0) {
            couponSummary.setText(R.string.coupon_summary_empty);
        } else {
            String s = "共 " + items.length() + " 张 · 可用 " + usable + " 张";
            if (hidden > 0) {
                s += " · 隐藏 " + hidden + " 张";
            }
            couponSummary.setText(s);
        }
        // v3.52：把扫描时刻 + 扫描耗时显示出来（用户要求「加上扫描的时间」）。
        // v3.48 把这行设成了 gone，现在改成有数据才显示，没数据给一句短提示。
        if (at > 0) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(at));
            long cost = CouponStore.cost(this);
            couponAt.setText(cost > 0
                    ? getString(R.string.coupon_at_cost, time, fmtCost(cost))
                    : getString(R.string.coupon_at, time));
            couponAt.setVisibility(View.VISIBLE);
        } else {
            // v4.32.0：删掉「还没刷新，点刷新读取卡券页」这句提示。
            // couponAt 改由扫描时刻专用，没扫过就整行不显示。
            couponAt.setVisibility(View.GONE);
        }
        refreshCouponBar();
    }

    private View couponRow(org.json.JSONObject item, final int pos) {
        View row = getLayoutInflater().inflate(R.layout.item_coupon, couponListView, false);
        CheckBox check = row.findViewById(R.id.couponCheck);
        TextView name = row.findViewById(R.id.couponName);
        TextView meta = row.findViewById(R.id.couponMeta);
        TextView tag = row.findViewById(R.id.couponTag);
        TextView state = row.findViewById(R.id.couponState);
        boolean blocked = couponBlocked(item);

        String amount = item.optString("amount");
        name.setText((amount.isEmpty() ? "" : amount + " ") + item.optString("name"));
        String type = item.optString("type");
        String valid = item.optString("valid");
        // 「剩 N天 N小时」是相对有效期，别再套一层「有效期」显得重复。
        String validText = valid.isEmpty() ? ""
                : (valid.startsWith("剩") ? " · " + valid : " · 有效期 " + valid);
        meta.setText((type.isEmpty() ? "卡券" : type) + validText);

        if (blocked) {
            check.setEnabled(false);
            tag.setText(R.string.coupon_excluded);
            tag.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            state.setVisibility(View.VISIBLE);
            state.setText("仅流量券 / 话费兑换券可批量使用 · " + item.optString("state"));
            row.setAlpha(0.55f);
        } else {
            check.setEnabled(true);
            check.setChecked(pos < couponChecked.length && couponChecked[pos]);
            tag.setText(R.string.coupon_usable);
            tag.setTextColor(ContextCompat.getColor(this, R.color.success));
            state.setVisibility(View.GONE);
        }
        check.setOnCheckedChangeListener((button, isChecked) -> {
            if (pos < couponChecked.length) {
                couponChecked[pos] = isChecked;
            }
            refreshCouponBar();
        });
        row.setOnClickListener(v -> {
            if (!blocked && check.isEnabled()) {
                check.setChecked(!check.isChecked());
            }
        });
        return row;
    }

    private int couponSelectedCount() {
        int n = 0;
        for (int i = 0; i < couponChecked.length && i < couponItems.length(); i++) {
            org.json.JSONObject it = couponItems.optJSONObject(i);
            if (couponChecked[i] && it != null && !couponBlocked(it)) {
                n++;
            }
        }
        return n;
    }

    private void refreshCouponBar() {
        if (couponBar == null) {
            return;
        }
        int n = couponSelectedCount();
        couponBar.setText(n > 0 ? getString(R.string.coupon_bar_selected, n)
                : getString(R.string.coupon_bar_idle));
        boolean enable = !couponRunning && couponSelectableCount() > 0;
        btnCouponUse.setEnabled(!couponRunning && n > 0);
        btnCouponAll.setEnabled(enable);
        if (btnCouponPickFlow != null) {
            btnCouponPickFlow.setEnabled(enable);
        }
        if (btnCouponPickPhone != null) {
            btnCouponPickPhone.setEnabled(enable);
        }
        btnCouponScan.setEnabled(!couponRunning);
        btnCouponScan.setText(couponRunning ? R.string.coupon_scanning : R.string.coupon_scan);
    }

    private int couponSelectableCount() {
        int n = 0;
        for (int i = 0; i < couponItems.length(); i++) {
            org.json.JSONObject it = couponItems.optJSONObject(i);
            if (it != null && !couponBlocked(it)) {
                n++;
            }
        }
        return n;
    }

    /** v3.52：把毫秒耗时格式化成「1.8 秒」这种短文案。 */
    private String fmtCost(long ms) {
        if (ms <= 0) {
            return "";
        }
        if (ms < 1000) {
            return ms + " 毫秒";
        }
        return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
    }

    private void couponScan() {
        if (couponRunning) {
            return;
        }
        // v4.10.0-r2：移动没开着就别拉起、别发命令，只提示。
        if (!targetAlive()) {
            Toast.makeText(this, NEED_APP_HINT, Toast.LENGTH_LONG).show();
            return;
        }
        // v3.52：记下起点，扫描结果广播回来时算「扫描耗时」并 Toast 提示。
        CouponStore.markScanStart(this);
        sendCouponCmd(CouponTask.CMD_SCAN, getString(R.string.coupon_scanning));
    }

    /**
     * v4.1.0：一键勾选某一类券 —— 只勾这类，其余全部取消（干净、可预期）。
     *
     * @param phone true = 话费兑换券；false = 流量券
     */
    private void couponPick(boolean phone) {
        int n = 0;
        for (int i = 0; i < couponChecked.length; i++) {
            org.json.JSONObject it = couponItems.optJSONObject(i);
            boolean hit = it != null && !couponBlocked(it) && couponIsPhone(it) == phone
                    && (phone || couponIsFlow(it));
            couponChecked[i] = hit;
            if (hit) {
                n++;
            }
        }
        buildCoupon();
        String what = phone ? "话费兑换券" : "流量券";
        Toast.makeText(this, n > 0 ? ("已勾选 " + n + " 张" + what) : ("没有可用的" + what),
                Toast.LENGTH_SHORT).show();
    }

    /** 流量券（券名/类型含「流量」，且是券）。 */
    private boolean couponIsFlow(org.json.JSONObject item) {
        String name = item.optString("name");
        String type = item.optString("type");
        return (type.contains("流量") || name.contains("流量"))
                && (name + type).contains("券");
    }

    /** 话费兑换券（兑换类的话费券；满减、折扣不算）。 */
    private boolean couponIsPhone(org.json.JSONObject item) {
        String name = item.optString("name");
        String all = name + " " + item.optString("type");
        if (all.contains("满减") || all.contains("折扣") || all.contains("加赠")) {
            return false;
        }
        return name.contains("话费兑换券") || (all.contains("话费") && name.contains("兑换券"));
    }

    private void couponSelectAll() {
        for (int i = 0; i < couponChecked.length; i++) {
            org.json.JSONObject it = couponItems.optJSONObject(i);
            couponChecked[i] = it != null && !couponBlocked(it);
        }
        buildCoupon();
    }

    /** 按勾选批量使用（「一键全用」已在 v4.1.0 删除）。 */
    private void couponUse() {
        if (couponRunning) {
            Toast.makeText(this, "上一批还在跑，先等它结束", Toast.LENGTH_SHORT).show();
            return;
        }
        org.json.JSONArray picked = new org.json.JSONArray();
        for (int i = 0; i < couponItems.length(); i++) {
            org.json.JSONObject it = couponItems.optJSONObject(i);
            if (it == null || couponBlocked(it)) {
                continue;
            }
            if (i < couponChecked.length && couponChecked[i]) {
                org.json.JSONObject one = new org.json.JSONObject();
                try {
                    one.put("name", it.optString("name"));
                    one.put("type", it.optString("type"));
                    one.put("valid", it.optString("valid"));
                    one.put("amount", it.optString("amount"));
                    // 扫描时给按钮打了 data-qc-btn 序号，带上就能精确点回同一张券。
                    one.put("i", it.optInt("i", -1));
                } catch (Throwable ignored) {
                }
                picked.put(one);
            }
        }
        if (picked.length() == 0) {
            Toast.makeText(this, "没有勾选任何券", Toast.LENGTH_SHORT).show();
            return;
        }
        final int count = picked.length();
        ensureSmsPermission(true);
        couponRunning = true;
        couponNeedingCode = false;
        dismissCodeDialog();
        refreshCouponBar();
        sendCouponCmd(CouponTask.CMD_USE + picked,
                getString(R.string.coupon_running, 1, count, "准备中"));
    }

    /**
     * v4.10.0-r2：券的指令（一键扫描 / 批量使用）都不再替用户拉起中国移动。
     * 没开着 → 只弹一句提示；开着 → 发命令，目标进程自己新开一屏真页，不抢前台。
     */
    private void sendCouponCmd(String cmd, String hint) {
        if (!targetAlive()) {
            Toast.makeText(this, NEED_APP_HINT, Toast.LENGTH_LONG).show();
            return;
        }
        if (sendTaskBroadcast(cmd, false)) {
            Toast.makeText(this, hint, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.status_no_target, Toast.LENGTH_SHORT).show();
    }

    /** 目标进程回传的券进度/结果，LogReceiver 在模块进程主线程转过来。 */
    private void onCouponEvent(String action, Intent intent) {
        runOnUiThread(() -> {
            if (LogReceiver.ACTION_COUPON_LIST.equals(action)) {
                buildCoupon();
                // v3.52：扫描成功 Toast + 扫描耗时（用户要求「加上扫描的时间 + 扫描成功 toast」）。
                // v4.1.0：进页自动刷新是静默的，不弹这个 Toast。
                boolean quiet = couponQuietScan;
                couponQuietScan = false;
                if (!quiet) {
                    long cost = CouponStore.cost(this);
                    int n = couponItems == null ? 0 : couponItems.length();
                    String tip = n > 0
                            ? ("刷新完成：" + n + " 张券" + (cost > 0 ? "，耗时 " + fmtCost(cost) : ""))
                            : "刷新完成，但没读到券";
                    Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
                }
            } else if (LogReceiver.ACTION_COUPON_PROGRESS.equals(action)) {
                String stage = intent.getStringExtra("stage");
                int idx = intent.getIntExtra("idx", -1);
                int total = intent.getIntExtra("total", 0);
                String name = intent.getStringExtra("name");
                if (stage != null && !"needcode".equals(stage) && couponNeedingCode) {
                    // 自动读码已经把流程推过验证码这一步，撤掉手动兜底框。
                    couponNeedingCode = false;
                    dismissCodeDialog();
                }
                if (!"done".equals(stage)) {
                    couponBar.setText(getString(R.string.coupon_running,
                            Math.max(1, idx + 1), Math.max(1, total),
                            name == null ? "" : name));
                }
            } else if (LogReceiver.ACTION_COUPON_NEED_CODE.equals(action)) {
                couponNeedingCode = true;
                couponNeedIdx = intent.getIntExtra("idx", -1);
                couponNeedName = intent.getStringExtra("name");
                couponBar.setText(getString(R.string.coupon_need_code,
                        couponNeedIdx + 1, couponNeedName == null ? "" : couponNeedName));
                // 自动读短信是主路径；30 秒没填上才弹手动输入，不打扰正常流程。
                handler.removeCallbacks(showCodeDialogRunnable);
                handler.postDelayed(showCodeDialogRunnable, 30_000L);
            } else if (LogReceiver.ACTION_COUPON_DONE.equals(action)) {
                couponRunning = false;
                couponNeedingCode = false;
                handler.removeCallbacks(showCodeDialogRunnable);
                dismissCodeDialog();
                int ok = intent.getIntExtra("ok", 0);
                int fail = intent.getIntExtra("fail", 0);
                String msg = intent.getStringExtra("msg");
                String text = getString(R.string.coupon_done, ok, fail);
                if (msg != null && !msg.isEmpty()) {
                    text += " · " + msg;
                }
                couponBar.setText(text);
                refreshCouponBar();
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------- v4.0 增强页 · 流量

    /** 切换增强容器里的子页（流量 / 优惠券）。 */
    private void selectFlowSub(boolean showFlow) {
        flowTab = showFlow;
        if (pageFlow != null) {
            pageFlow.setVisibility(showFlow ? View.VISIBLE : View.GONE);
        }
        if (pageCouponBody != null) {
            pageCouponBody.setVisibility(showFlow ? View.GONE : View.VISIBLE);
        }
        int selColor = ContextCompat.getColor(this, R.color.on_primary);
        int dimColor = ContextCompat.getColor(this, R.color.text_primary);
        // v4.0.1：必须 setSelected —— bg_nav_item 的蓝色高亮底是 state_selected 态，
        // 旧版只 setTextColor 没 setSelected，选中项一直用白色字(on_primary)却落在
        // 透明/白色底上 → 浅色模式下白字白底看不清。补上与主导航 paintNav 一致的处理。
        // 未选中项改用 text_primary（高对比主文字色），浅色/深色底上都清楚。
        if (subFlow != null) {
            subFlow.setSelected(showFlow);
            subFlow.setTextColor(showFlow ? selColor : dimColor);
            subFlow.setTypeface(Typeface.create(
                    showFlow ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
        if (subCoupon != null) {
            subCoupon.setSelected(!showFlow);
            subCoupon.setTextColor(showFlow ? dimColor : selColor);
            subCoupon.setTypeface(Typeface.create(
                    showFlow ? "sans-serif" : "sans-serif-medium", Typeface.NORMAL));
        }
        if (showFlow && pageFlow != null) {
            FlowView.render(this, pageFlow);
            // v4.0.2：从「优惠券」子页切回「流量」时也可自动查询（带节流）。
            // maybeAutoFlow 内有 tab==TAB_COUPON 守卫，onCreate 里 tab 还是 TAB_HOME，
            // 那次初始化渲染不会误触发离屏查询。
            if (tab == TAB_COUPON) {
                maybeAutoFlow();
            }
        } else if (!showFlow && tab == TAB_COUPON) {
            // v4.1.0：切到「优惠券」子页也自动刷新一次券列表（带节流）。
            maybeAutoCoupon();
        }
    }

    /**
     * 点「一键查询」：发 flow 命令让目标进程新开一屏余量页读 Vuex，结果由 FlowWatcher 回来。
     *
     * <p>v4.10.0-r2：移动没开着就只提示、不拉起、不发命令；开着也不把 App 抢到前台，
     * 由目标进程用 H5 容器自己新开一屏（绕开开屏广告那一层全屏窗口）。
     */
    private void flowRefresh() {
        if (flowLoading) {
            Toast.makeText(this, "正在读取，稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!targetAlive()) {
            Toast.makeText(this, NEED_APP_HINT, Toast.LENGTH_LONG).show();
            return;
        }
        flowAutoAt = System.currentTimeMillis();
        flowLoading = true;
        if (flowNotify != null) {
            flowNotify.setText(R.string.flow_refreshing);
        }
        handler.removeCallbacks(flowTimeout);
        handler.postDelayed(flowTimeout, FLOW_TIMEOUT_MS);
        if (!sendTaskBroadcast(TaskRunner.CMD_FLOW, false)) {
            handler.removeCallbacks(flowTimeout);
            flowLoading = false;
            if (flowNotify != null) {
                flowNotify.setText("指令发送失败，请重试。");
            }
            return;
        }
        scheduleResend("flow", 2);
    }

    /**
     * v4.0.2 进「增强·流量」页自动一键查询（静默）。满足下列任一条件就跳过：
     * <ul>
     *   <li>正在查询中 / 目标进程没在线（v4.10.0-r2 起用 {@link #targetAlive()} 判断，
     *       移动没开着就静默跳过，不弹 Toast、不拉起 App）；</li>
     *   <li>距上次自动查询不到 {@link #FLOW_AUTO_GAP_MS}（节流防抖）；</li>
     *   <li>本地缓存是 {@code ready}、有数据、且在 {@link #FLOW_FRESH_MS} 内（直接用缓存）。</li>
     * </ul>
     * 刷新一律走「目标进程新开一屏余量页」，不复用、不检查已开页面的页内数据。
     */
    private void maybeAutoFlow() {
        if (flowLoading || !flowTab || pageFlow == null || tab != TAB_COUPON) {
            return;
        }
        // v4.10.0-r2：用户没主动触发，移动没开着就静默跳过，别弹 Toast 打扰。
        if (!targetAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - flowAutoAt < FLOW_AUTO_GAP_MS) {
            return;
        }
        // 已有新鲜、成功的缓存就直接展示，不重复读。
        try {
            String cached = FlowStore.json(this);
            if (!cached.isEmpty()) {
                JSONObject o = new JSONObject(cached);
                boolean ready = "ready".equals(o.optString("state", ""))
                        && o.optJSONArray("items") != null
                        && o.optJSONArray("items").length() > 0;
                if (ready && now - FlowStore.at(this) < FLOW_FRESH_MS) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        flowAutoAt = now;
        flowLoading = true;
        if (flowNotify != null) {
            flowNotify.setText(R.string.flow_refreshing);
        }
        handler.removeCallbacks(flowTimeout);
        handler.postDelayed(flowTimeout, FLOW_TIMEOUT_MS);
        if (sendTaskBroadcast(TaskRunner.CMD_FLOW, false)) {
            scheduleResend("flow", 1);
        } else {
            handler.removeCallbacks(flowTimeout);
            flowLoading = false;
            // 静默：指令发不出去也不打扰用户，等下次进页重试
        }
    }

    /**
     * v4.1.0：进「优惠券」子页自动刷新一次券列表（静默）。
     *
     * <p>和 {@link #maybeAutoFlow()} 一个思路：正在跑 / 目标不在线 / 60 秒内刷过 /
     * 本地清单还新鲜 —— 任一命中就跳过，别每切一次页就重读一遍。
     * v4.10.0-r2：目标不在线改由 {@link #targetAlive()} 判断，静默跳过、不拉起 App；
     * 刷新一律新开一屏卡券页，不复用、不检查已开页面的页内数据。
     */
    private void maybeAutoCoupon() {
        if (couponRunning || flowTab || tab != TAB_COUPON) {
            return;
        }
        // v4.10.0-r2：用户没主动触发，移动没开着就静默跳过，别弹 Toast 打扰。
        if (!targetAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - couponAutoAt < COUPON_AUTO_GAP_MS) {
            return;
        }
        if (couponItems.length() > 0 && now - CouponStore.at(this) < COUPON_FRESH_MS) {
            return;
        }
        couponAutoAt = now;
        couponQuietScan = true;
        CouponStore.markScanStart(this);
        if (couponAt != null) {
            couponAt.setText(R.string.coupon_scanning);
        }
        // 静默：不发 Toast；广播没人接就补发一次（App 刚冻结时第一条常丢）。
        if (sendTaskBroadcast(CouponTask.CMD_SCAN, false)) {
            scheduleResend("coupon", 1);
        }
    }

    /** 流量结果广播回来：解锁 + 重画流量页（数据已由 LogReceiver 落盘）。 */
    private void onFlowEvent(String action, Intent intent) {
        runOnUiThread(() -> {
            if (LogReceiver.ACTION_FLOW_DATA.equals(action)) {
                handler.removeCallbacks(flowTimeout);
                cancelResend();
                flowLoading = false;
                if (pageFlow != null) {
                    FlowView.render(this, pageFlow);
                }
            }
        });
    }

    /**
     * 目标进程报上来的实时步骤（v4.2.0）。
     *
     * <p>流量刷新写到「流量」页的状态行，券刷新写到券页的进度行，抢券写到抢券卡。
     * 收到任意一条就说明目标进程活着并且在干活，把补发任务撤掉。
     */
    private void onProgressEvent(String scope, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            cancelResend();
            if ("flow".equals(scope)) {
                if (flowNotify != null && flowLoading) {
                    flowNotify.setText(text);
                }
            } else if ("grab".equals(scope)) {
                if (grabStatus != null) {
                    grabStatus.setText(text);
                }
                if (text.startsWith("✓") || text.contains("已取消") || text.contains("先不等了")) {
                    grabRunningKind = "";
                }
            } else if ("coupon".equals(scope)) {
                if (couponRunning) {
                    if (couponBar != null) {
                        couponBar.setText(text);
                    }
                } else if (couponAt != null) {
                    couponAt.setText(text);
                }
            }
        });
    }

    private final Runnable showCodeDialogRunnable = this::showCodeDialog;

    // ---------------------------------------------------- 短信验证码诊断

    /**
     * 把短信自检说明写进模块日志页（导出完整日志后可逐条核对）。
     *
     * <p>v4.32.0：券页那个「小米 / HyperOS 读不到验证码？点这里看自检步骤」
     * 入口已按用户要求删除，这里只保留「自动读码超时」时的兜底诊断。
     */
    private void logSmsDiag(String summary, String detail) {
        try {
            LogStore.append(this, new LogEntry(System.currentTimeMillis(),
                    "SMSDIAG", summary, detail));
        } catch (Throwable ignored) {
        }
    }

    /** 兜底：自动读码没成功时，仍可手动输入 6 位验证码。 */
    private void showCodeDialog() {
        if (!couponNeedingCode || isFinishing() || isDestroyed()) {
            return;
        }
        // v4.0.4：30 秒没自动填码 = 本轮三条短信通道都没读到。
        // 写一条可自证的诊断，把小米必做的系统开关带出来，不再让用户盲猜。
        logSmsDiag(getString(R.string.sms_diag_timeout_title),
                "券核销已发出短信验证码，但 30 秒内模块没有自动读到 ——\n"
                        + "三条通道（模块静态 SMS_RECEIVED 广播 / 短信库兜底 / 被注入进程转发）"
                        + "均未投递成功。\n\n"
                        + getString(R.string.sms_help_message)
                        + "\n\n────────────────────────\n"
                        + "核对方法：导出完整日志，搜 [SMSDIAG]（框架层通道是否被注入电话进程）、\n"
                        + "[SMSCODE]（有没有「短信到达」和「自动读到验证码」）；\n"
                        + "一条都没有 → 通道没装好或系统开关没关；\n"
                        + "有「短信到达」却标「非兑换类」→ 短信文案变了，按真实正文调白名单。");
        dismissCodeDialog();
        EditText input = new EditText(this);
        input.setHint(R.string.coupon_code_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setMaxLines(1);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        couponCodeDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.coupon_need_code_manual))
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.coupon_code_send, (d, w) -> {
                    String code = input.getText().toString().trim();
                    if (code.length() >= 4) {
                        sendTaskBroadcast(CouponTask.CMD_CODE + code);
                        couponNeedingCode = false;
                        refreshCouponBar();
                    }
                })
                .setOnDismissListener(d -> couponCodeDialog = null)
                .show();
    }

    private void dismissCodeDialog() {
        android.app.Dialog d = couponCodeDialog;
        couponCodeDialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 短信权限。RECEIVE_SMS / READ_SMS 是运行时权限，不授权静态广播也收不到。
     * 用户拒绝不阻塞功能：退回手动输入，并在弹框里说明。
     */
    private void ensureSmsPermission(boolean explain) {
        boolean need = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED;
        if (!need) {
            return;
        }
        if (explain) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.coupon_perm_title)
                    .setMessage(R.string.coupon_perm_message)
                    .setNegativeButton(R.string.coupon_perm_later, null)
                    .setPositiveButton(R.string.coupon_perm_grant,
                            (d, w) -> requestSmsPermission())
                    .show();
        } else {
            requestSmsPermission();
        }
    }

    private void requestSmsPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS},
                REQUEST_SMS);
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
        // r18：保活必须在这里（模块进程）启动。
        // r17 把它写在 TaskRunner.execute 里，而那个方法跑在中国移动进程里——
        // 用目标包的 Context 去启 com.cmcchook.KeepAliveService，目标包没有这个组件，
        // 直接抛异常被 catch 进 logcat，所以日志里一点痕迹都没有，模块照样被回收。
        // 此刻模块界面还在前台，startForegroundService 不受 Android 12+ 后台限制。
        KeepAliveService.start(this);
        boolean alive = System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
        if (alive && sendTaskBroadcast(cmd)) {
            String hint = TaskRunner.CMD_GOOSE.equals(cmd)
                    ? "已发到后台；收鹅蛋要等页面注入，结果看本页顶部"
                    : "已发到后台执行，" + label + "，结果看本页顶部";
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            markRunStarted(label);
            return;
        }
        launchTarget(cmd, label, label);
    }

    /** v4.10.0-r7：记下本轮发起，顶部结果条据此显示「进行中」，不再强制跳日志页。 */
    private void markRunStarted(String label) {
        runStartedAt = System.currentTimeMillis();
        runStartedLabel = label == null ? "" : label;
        refreshWelfareResult();
    }

    /** 目标 App 是否开着（90 秒内有心跳）。流量/券刷新统一用它决定「发不发命令」。 */
    private boolean targetAlive() {
        return System.currentTimeMillis() - AppPrefs.getLastHeartbeat(this) < 90_000L;
    }

    /**
     * 下指令（其它任务命令：一键全部 / 鹅蛋 / 活动…）。保持原样：先把中国移动切到前台，
     * 用户看得到执行过程，再发广播。
     */
    private boolean sendTaskBroadcast(String cmd) {
        return sendTaskBroadcast(cmd, true);
    }

    /**
     * v4.10.0-r2：流量/券刷新传 {@code bringToFront=false} —— 移动已经开着就够了，
     * 别把它的主界面抢到前台（也不再经过开屏广告那一层）。目标进程收到命令后自己
     * 用 H5 容器新开一屏真页读数据。
     */
    private boolean sendTaskBroadcast(String cmd, boolean bringToFront) {
        return sendTaskBroadcast(cmd, null, null, bringToFront);
    }

    /** r13：带一个字符串参数（灵犀口令）的命令通道。 */
    private boolean sendTaskBroadcast(String cmd, String key, String value, boolean bringToFront) {
        try {
            if (bringToFront) {
                // 模块界面此刻在前台，这里 startActivity 不受后台启动限制。
                bringTargetToFront();
            }
            Intent intent = new Intent(TaskReceiver.ACTION_RUN);
            intent.setPackage(AppPrefs.TARGET_PACKAGE);
            intent.putExtra("cmd", cmd);
            if (key != null && !key.isEmpty() && value != null) {
                intent.putExtra(key, value);
            }
            sendBroadcast(intent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * v4.0.9：把中国移动切到前台（仅「跑任务」类命令用）。
     *
     * <p>v4.10.0-r2：流量/券刷新不再调这里 —— 它们只发广播、由目标进程新开一屏，
     * 不把主界面抢到前台。这里保留给一键全部 / 鹅蛋等需要用户看到执行过程的命令。
     * App 已在前台时这次调用只是把它的任务栈提到最前，无副作用。
     */
    private void bringTargetToFront() {
        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
            if (intent == null) {
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 采集 / 引导类：不是跑任务，不记本轮发起态。 */
    private void launchTarget(String cmd, String label) {
        launchTarget(cmd, label, "");
    }

    private void launchTarget(String cmd, String label, String runLabel) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(AppPrefs.TARGET_PACKAGE);
        if (intent == null) {
            Toast.makeText(this, "没有找到中国移动 App", Toast.LENGTH_LONG).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            boolean isTask = runLabel != null && !runLabel.isEmpty();
            Toast.makeText(this, "中国移动没在运行，已拉起，" + label
                            + (isTask ? "，结果看本页顶部" : "，结果看本页摘要"),
                    Toast.LENGTH_LONG).show();
            // v4.10.0-r7：不再强制切到日志页。结果是就地看的：任务看顶部结果条，
            // 采集看本页探路摘要；想看原始明细再点结果条 / 手动切日志。
            if (isTask) {
                markRunStarted(runLabel);
            }
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
            if (AppPrefs.KEY_RAW_CAPTURE.equals(key)) {
                refreshCaptureState();
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

    /**
     * 关于页 QQ 群：打开 QQ 群资料页（到那儿用户自己点「加入」）。
     * 实现照搬小黄鱼 XHY（gitee.com/climax-jt/xhy）v1.2.7 的 openQqGroup。
     *
     * <p>只有群号时做不到「一键加群」——那需要 QQ 群管理里生成的 authKey / key。
     * 能稳定用的是 QQ 的群名片 scheme：
     * {@code mqqapi://card/show_pslcard?src_type=internal&version=1&uin=<群号>&card_type=group&source=qrcode}，
     * 它会把 QQ 拉到这个群的资料页。
     *
     * <p>⚠️ 依赖 AndroidManifest 的 {@code <queries>} 声明 QQ/TIM 与 mqqapi scheme：
     * Android 11+ 不声明就扫不到 QQ，明明装了也会抛 ActivityNotFoundException。
     *
     * <p>真没装 QQ 时不能静默失败 —— 吐司把群号显示出来让用户手搜，否则就是「点了没反应」。
     */
    private void openQQGroup() {
        final String uin = getString(R.string.about_qq_uin);
        String url = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin="
                + uin + "&card_type=group&source=qrcode";
        // 依次试：QQ → TIM → 交给系统随便挑（有些 ROM 装了第三方 QQ 客户端）
        String[] pkgs = {"com.tencent.mobileqq", "com.tencent.tim", null};
        for (int k = 0; k < pkgs.length; k++) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pkgs[k] != null) {
                    i.setPackage(pkgs[k]);
                }
                startActivity(i);
                return;
            } catch (Throwable ignored) {
                // 没装 / 不认这个 scheme，试下一个
            }
        }
        Toast.makeText(this, "没找到 QQ，请手动搜索群号 " + uin,
                Toast.LENGTH_LONG).show();
    }

    /** 关于页除 QQ 群外的三个链接一律走系统 ACTION_VIEW：交给浏览器（或对应 App）打开，不在模块内开 WebView。 */
    private void openExternalLink(String url) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", url));
            } catch (Exception ignored) { }
            Toast.makeText(this, R.string.about_link_copied, Toast.LENGTH_SHORT).show();
        }
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
        chipTask = findViewById(R.id.chipTask);
        chipApi = findViewById(R.id.chipApi);
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
        chipTask.setOnClickListener(v -> setFilter(FILTER_TASK));
        chipApi.setOnClickListener(v -> setFilter(FILTER_API));
        chipHttp.setOnClickListener(v -> setFilter(FILTER_HTTP));
        chipH5.setOnClickListener(v -> setFilter(FILTER_H5));
        chipUi.setOnClickListener(v -> setFilter(FILTER_UI));
        chipCookie.setOnClickListener(v -> setFilter(FILTER_COOKIE));
        chipError.setOnClickListener(v -> setFilter(FILTER_ERROR));
        // v4.10.0-r7：默认「任务」，与字段初值一致（进页面第一眼就是任务进展）。
        setFilter(FILTER_TASK);
    }

    private void setFilter(int value) {
        filter = value;
        TextView[] chips = new TextView[]{
                chipAll, chipTask, chipApi, chipHttp, chipH5, chipUi, chipCookie, chipError
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
                // v4.10.0-r7：原来只在「全部」视图自动滚到底；默认改成「任务」后
                // 新任务日志进来却停在顶部，看着像没进展。改成任何筛选下、只要用户
                // 原本就在底部就跟着滚。
                if (adapter.size() > oldSize && wasAtBottom) {
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
            case FILTER_TASK:
                return tag.startsWith("TASK") || tag.equals("SYSTEM")
                        || tag.equals("APP-START") || tag.startsWith("PROBE")
                        // r13：口令捕获是「换号能不能发」的关键线索，放在任务视图里，
                        // 不用切到「全部」去翻。
                        || tag.equals("CMD");
            case FILTER_API:
                return tag.startsWith("API");
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
        // r39：日志导出只保留「完整」一种。去掉原来的「导出哪些内容」三选一弹框，
        // 点导出直接产出完整日志（含请求/响应与 Cookie），少一步选择、也不会导错精简版。
        exportMode = LogExporter.MODE_FULL;
        ensurePermissionThenExport();
    }

    private void ensurePermissionThenExport() {
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
        final int mode = exportMode;
        new Thread(() -> {
            String message;
            boolean success = false;
            android.net.Uri shareUri = null;
            try {
                List<LogEntry> all = LogStore.read(this, 0);
                if (all.isEmpty()) {
                    message = getString(R.string.export_empty);
                } else {
                    LogExporter.Output out = LogExporter.export(this, all, mode);
                    message = getString(R.string.export_success,
                            LogExporter.modeName(mode) + " · " + out.path);
                    shareUri = out.uri;
                    success = true;
                }
            } catch (Throwable e) {
                message = getString(R.string.export_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String finalMessage = message;
            final boolean finalSuccess = success;
            final android.net.Uri finalUri = shareUri;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnExport.setEnabled(true);
                btnExport.setText(R.string.btn_export_short);
                if (finalUri != null) {
                    // 直接给一个「发送」入口，省得再去文件管理器里翻。
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.export_done_title)
                            .setMessage(finalMessage)
                            .setPositiveButton(R.string.export_share,
                                    (d, w) -> shareLog(finalUri))
                            .setNegativeButton(android.R.string.ok, null)
                            .show();
                } else {
                    Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                }
                if (finalSuccess) {
                    updateLogCount();
                }
            });
        }, "log-export").start();
    }

    private void shareLog(android.net.Uri uri) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.export_share)));
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.export_failed, String.valueOf(e)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS) {
            boolean smsGranted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!smsGranted) {
                Toast.makeText(this, R.string.coupon_perm_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
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
