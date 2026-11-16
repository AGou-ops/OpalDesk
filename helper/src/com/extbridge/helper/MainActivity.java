package com.extbridge.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int[][] RES_PRESETS = {
            {3840, 2160},
            {2560, 1600},
            {2560, 1440},
            {1920, 1200},
            {1920, 1080},
            {1600, 900},
            {1280, 720}
    };
    private static final String[] RES_LABELS = {
            "3840×2160\n4K 16:9",
            "2560×1600\n2.5K 16:10",
            "2560×1440\n2K 原生★",
            "1920×1200\n16:10",
            "1920×1080\n1080p 16:9",
            "1600×900\n900p 16:9",
            "1280×720\n720p 省流"
    };

    private static final int[] DPI_PRESETS = {160, 200, 240, 280, 320};
    private static final String[] DPI_LABELS = {"160\n超高密", "200\n紧凑桌面", "240\n标准★", "280\n大字", "320\n超大"};
    private static final float[] FPS_PRESETS = {165f, 144f, 120f, 90f, 75f, 60f};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mSelW = 2560;
    private int mSelH = 1440;
    private int mSelDpi = 240;
    private float mSelFps = 60f;
    private int mSelPhysModeId = -1;
    private boolean mInitedFromDaemon = false;

    private TextView mTvStatusTitle;
    private TextView mTvStatusDetail;
    private TextView mBtnToggleInput;
    private TextView mBtnToggleMirror;
    private TextView mBtnFullscreenPolicy;
    private TextView mBtnAppModes;
    private TextView mBtnActivate;
    private TextView mTvSwInfo;
    private TextView mTvGestureStatus;
    private EditText mEdW;
    private EditText mEdH;
    private LinearLayout mPhysModesWrap;
    private String mLastModesRaw = "";

    private AlertDialog mActivationDialog;
    private boolean mUserSkippedDialog = false;
    private boolean mActWorking = false;
    private boolean mHostEdited = false;
    private boolean mPairPortEdited = false;
    private boolean mConnPortEdited = false;
    private EditText mEdPairHost;
    private EditText mEdPairPort;
    private EditText mEdPairCode;
    private EditText mEdConnPort;
    private TextView mTvMdnsHint;
    private TextView mTvActStatus;

    private final List<TextView> mResChips = new ArrayList<>();
    private final List<TextView> mDpiChips = new ArrayList<>();
    private final List<TextView> mFpsChips = new ArrayList<>();

    private final Runnable mRefreshLoop = new Runnable() {
        @Override
        public void run() {
            BridgeClient.Status st = BridgeClient.getStatus();
            if (!st.connected) {
                BridgeClient.requestBinder(MainActivity.this);
            }
            refreshStatus(false);
            mHandler.postDelayed(this, st.connected ? 2000 : 1200);
        }
    };

    private final Runnable mAutoPromptCheck = () -> {
        if (isFinishing()) return;
        BridgeClient.Status st = BridgeClient.getStatus();
        if (!st.connected && !mUserSkippedDialog && (mActivationDialog == null || !mActivationDialog.isShowing())) {
            showActivationDialog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ensureOnDisplay0()) return;
        BridgeService.ensureStarted(this);
        buildUi();
        refreshStatus(true);
    }

    private boolean ensureOnDisplay0() {
        try {
            if (getDisplay() != null && getDisplay().getDisplayId() != 0) {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                android.app.ActivityOptions o0 = android.app.ActivityOptions.makeBasic();
                o0.setLaunchDisplayId(0);
                startActivity(i, o0.toBundle());
                finishAndRemoveTask();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeClient.setOnBinderReady(() -> mHandler.post(() -> refreshStatus(true)));
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this);
        BridgeService.ensureStarted(this);
        refreshStatus(false);
        mHandler.post(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
        mHandler.postDelayed(mAutoPromptCheck, 450);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeClient.setOnBinderReady(null);
        mHandler.removeCallbacks(mRefreshLoop);
        mHandler.removeCallbacks(mAutoPromptCheck);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mActivationDialog != null && mActivationDialog.isShowing()) {
            mActivationDialog.dismiss();
        }
        AdbBootstrap.stopDiscovery();
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B1120);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 64, 28, 48);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sys = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(Math.max(28, sys.left + 20), Math.max(48, sys.top + 16),
                        Math.max(28, sys.right + 20), Math.max(42, sys.bottom + 24));
                return insets;
            });
        }

        // Header
        TextView appTitle = new TextView(this);
        appTitle.setText("ExtBridge 扩展屏控制台 v3");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(22f);
        appTitle.getPaint().setFakeBoldText(true);
        root.addView(appTitle);

        TextView appSub = new TextView(this);
        appSub.setText("USB-C 独立扩展桌面 · 通知栏快切 · 大触摸板与输入法转发 · 通知栏可随时常驻调用");
        appSub.setTextColor(0xFF94A3B8);
        appSub.setTextSize(13f);
        appSub.setPadding(0, 4, 0, 18);
        root.addView(appSub);

        // Card 1: Connection Status + Hero Actions
        LinearLayout statusCard = makeCard();
        mTvStatusTitle = new TextView(this);
        mTvStatusTitle.setTextColor(Color.WHITE);
        mTvStatusTitle.setTextSize(16f);
        mTvStatusTitle.getPaint().setFakeBoldText(true);
        statusCard.addView(mTvStatusTitle);

        mTvStatusDetail = new TextView(this);
        mTvStatusDetail.setTextColor(0xFFCBD5E1);
        mTvStatusDetail.setTextSize(13f);
        mTvStatusDetail.setPadding(0, 6, 0, 14);
        statusCard.addView(mTvStatusDetail);

        List<View> heroBtns = new ArrayList<>();
        mBtnToggleInput = makeActionBtn("⇄ 切换键鼠方向", 0xFF2563EB);
        mBtnToggleInput.setOnClickListener(v -> {
            int m = BridgeClient.toggleInput();
            Toast.makeText(this, m == 1 ? "键鼠已切换至：扩展屏" : "键鼠已切换至：平板内屏", Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        heroBtns.add(mBtnToggleInput);

        TextView btnTouchpad = makeActionBtn("🖐 打开大触摸板 + 虚拟键盘", 0xFF0284C7);
        btnTouchpad.setOnClickListener(v -> {
            android.app.ActivityOptions o0 = android.app.ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(0);
            startActivity(new Intent(this, TouchpadActivity.class), o0.toBundle());
        });
        heroBtns.add(btnTouchpad);

        TextView btnLauncher = makeActionBtn("⊞ 扩展屏打开桌面", 0xFF0F766E);
        btnLauncher.setOnClickListener(v -> BridgeClient.relaunchLauncher());
        heroBtns.add(btnLauncher);

        TextView btnRebuild = makeActionBtn("↻ 刷新/重建渲染", 0xFF334155);
        btnRebuild.setOnClickListener(v -> {
            BridgeClient.rebuild();
            Toast.makeText(this, "已发送渲染重建指令", Toast.LENGTH_SHORT).show();
        });
        heroBtns.add(btnRebuild);

        mBtnToggleMirror = makeActionBtn("📺 切换镜像 (当前:DeX)", 0xFF7C3AED);
        mBtnToggleMirror.setOnClickListener(v -> {
            int m = BridgeClient.toggleMirrorMode();
            Toast.makeText(this,
                    m == 1 ? "已切换为【原生镜像模式】" : "已切换为【ExtBridge DeX 独立桌面】",
                    Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        heroBtns.add(mBtnToggleMirror);

        mBtnFullscreenPolicy = makeActionBtn("⛶ 桌面锁横屏:开", 0xFF0E7490);
        mBtnFullscreenPolicy.setOnClickListener(v -> {
            int fp = BridgeClient.toggleFullscreenPolicy();
            Toast.makeText(this,
                    fp == 1 ? "已开启：DeX 默认横屏平板大屏桌面" : "已关闭：DeX 桌面默认横屏锁定",
                    Toast.LENGTH_SHORT).show();
            BridgeService.refreshFromReceiver(this);
            refreshStatus(false);
        });
        heroBtns.add(mBtnFullscreenPolicy);

        mBtnAppModes = makeActionBtn("🧩 分应用显示模式 (微信/夸克/红果…)", 0xFF0284C7);
        mBtnAppModes.setOnClickListener(v -> showAppModesDialog());
        heroBtns.add(mBtnAppModes);

        mBtnActivate = makeActionBtn("⚡ 激活后台服务 (无线调试 / ADB)", 0xFFD97706);
        mBtnActivate.setOnClickListener(v -> {
            mUserSkippedDialog = false;
            showActivationDialog();
        });
        heroBtns.add(mBtnActivate);

        addChipWrap(statusCard, heroBtns);
        root.addView(statusCard);

        // Card 2: Resolution + Custom
        LinearLayout resCard = makeCard();
        addCardHeader(resCard, "输出分辨率预设 (支持高分超采样 / 16:10 / 16:9 自适应缩放)");
        List<View> resViews = new ArrayList<>();
        mResChips.clear();
        for (int i = 0; i < RES_PRESETS.length; i++) {
            final int w = RES_PRESETS[i][0];
            final int h = RES_PRESETS[i][1];
            TextView chip = makeChip(RES_LABELS[i]);
            chip.setOnClickListener(v -> {
                mSelW = w;
                mSelH = h;
                mEdW.setText(String.valueOf(w));
                mEdH.setText(String.valueOf(h));
                updateSelectionStyles();
            });
            mResChips.add(chip);
            resViews.add(chip);
        }
        addChipWrap(resCard, resViews);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, 12, 0, 0);

        TextView customLbl = new TextView(this);
        customLbl.setText("自定义分辨率: ");
        customLbl.setTextColor(0xFF94A3B8);
        customLbl.setTextSize(13f);
        customRow.addView(customLbl);

        mEdW = makeNumBox(String.valueOf(mSelW));
        mEdH = makeNumBox(String.valueOf(mSelH));
        TextView xLbl = new TextView(this);
        xLbl.setText("  ×  ");
        xLbl.setTextColor(Color.WHITE);
        customRow.addView(mEdW);
        customRow.addView(xLbl);
        customRow.addView(mEdH);
        resCard.addView(customRow);
        root.addView(resCard);

        // Card 3: DPI + Refresh Rate
        LinearLayout rateCard = makeCard();
        addCardHeader(rateCard, "界面缩放 DPI");
        List<View> dpiViews = new ArrayList<>();
        mDpiChips.clear();
        for (int i = 0; i < DPI_PRESETS.length; i++) {
            final int dpi = DPI_PRESETS[i];
            TextView chip = makeChip(DPI_LABELS[i]);
            chip.setOnClickListener(v -> {
                mSelDpi = dpi;
                updateSelectionStyles();
            });
            mDpiChips.add(chip);
            dpiViews.add(chip);
        }
        addChipWrap(rateCard, dpiViews);

        addCardHeaderTopPad(rateCard, "目标刷新率预设 (支持多档显示器优先切硬件 EDID 刷新率)");
        List<View> fpsViews = new ArrayList<>();
        mFpsChips.clear();
        for (float f : FPS_PRESETS) {
            TextView chip = makeChip(Math.round(f) + " Hz");
            chip.setOnClickListener(v -> {
                mSelFps = f;
                mSelPhysModeId = -1;
                updateSelectionStyles();
            });
            mFpsChips.add(chip);
            fpsViews.add(chip);
        }
        addChipWrap(rateCard, fpsViews);

        addCardHeaderTopPad(rateCard, "当前连接显示器硬件报告模式 (EDID 实测可用挡位)");
        mPhysModesWrap = new LinearLayout(this);
        mPhysModesWrap.setOrientation(LinearLayout.VERTICAL);
        rateCard.addView(mPhysModesWrap);

        mTvSwInfo = new TextView(this);
        mTvSwInfo.setTextColor(0xFF94A3B8);
        mTvSwInfo.setTextSize(12.5f);
        mTvSwInfo.setPadding(0, 14, 0, 8);
        rateCard.addView(mTvSwInfo);

        TextView btnPhoneTabletPreset = makeActionBtn("📱 手机推荐：一键横屏平板大屏 (1920×1080 @ 240dpi)", 0xFF0369A1);
        btnPhoneTabletPreset.setTextSize(12.5f);
        btnPhoneTabletPreset.setOnClickListener(v -> {
            mSelW = 1920; mSelH = 1080; mSelDpi = 240; mSelFps = 60f; mSelPhysModeId = -1;
            mEdW.setText("1920"); mEdH.setText("1080");
            updateSelectionStyles();
            applyChosenConfig();
        });
        rateCard.addView(btnPhoneTabletPreset);

        LinearLayout applyRow = new LinearLayout(this);
        applyRow.setOrientation(LinearLayout.HORIZONTAL);
        applyRow.setPadding(0, 16, 0, 0);

        TextView btnApply = makeActionBtn("✓ 应用分辨率与刷新率", 0xFF16A34A);
        btnApply.setOnClickListener(v -> applyChosenConfig());
        TextView btnReset = makeActionBtn("↺ 恢复推荐默认", 0xFF334155);
        btnReset.setOnClickListener(v -> {
            mSelW = 2560; mSelH = 1440; mSelDpi = 240; mSelFps = 60f; mSelPhysModeId = -1;
            mEdW.setText("2560"); mEdH.setText("1440");
            updateSelectionStyles();
            applyChosenConfig();
        });
        applyRow.addView(btnApply);
        applyRow.addView(btnReset);
        rateCard.addView(applyRow);
        root.addView(rateCard);

        // Card 4: Tablet Gesture Protection & Notification Health
        LinearLayout gestCard = makeCard();
        addCardHeader(gestCard, "平板手势与权限健康检查");
        mTvGestureStatus = new TextView(this);
        mTvGestureStatus.setTextColor(0xFFCBD5E1);
        mTvGestureStatus.setTextSize(13f);
        mTvGestureStatus.setPadding(0, 4, 0, 12);
        gestCard.addView(mTvGestureStatus);

        List<View> permBtns = new ArrayList<>();
        TextView btnA11y = makeActionBtn("辅助手势设置 (路径2备用)", 0xFF334155);
        btnA11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permBtns.add(btnA11y);

        TextView btnOverlay = makeActionBtn("悬浮窗权限", 0xFF334155);
        btnOverlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        permBtns.add(btnOverlay);

        TextView btnNotif = makeActionBtn("通知栏权限", 0xFF334155);
        btnNotif.setOnClickListener(v -> {
            Intent ni = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(ni);
        });
        permBtns.add(btnNotif);

        addChipWrap(gestCard, permBtns);
        root.addView(gestCard);

        sv.addView(root);
        setContentView(sv);
        updateSelectionStyles();
    }

    private void applyChosenConfig() {
        try {
            int cw = Integer.parseInt(mEdW.getText().toString().trim());
            int ch = Integer.parseInt(mEdH.getText().toString().trim());
            mSelW = Math.max(640, Math.min(7680, cw));
            mSelH = Math.max(480, Math.min(4320, ch));
        } catch (Throwable ignored) {}

        boolean ok = BridgeClient.applyConfig(mSelW, mSelH, mSelDpi, mSelFps, mSelPhysModeId);
        Toast.makeText(this,
                ok ? ("已应用：" + mSelW + "×" + mSelH + " · " + mSelDpi + "dpi · " + Math.round(mSelFps) + "Hz")
                   : "应用失败，请确认 ExtBridge 后台服务正在运行",
                Toast.LENGTH_SHORT).show();
        BridgeService.refreshFromReceiver(this);
        refreshStatus(false);
    }

    private void refreshStatus(boolean syncSelection) {
        BridgeClient.Status st = BridgeClient.getStatus();
        if (!st.connected) {
            mTvStatusTitle.setText("● 未检测到 ExtBridge 后台守护进程");
            mTvStatusTitle.setTextColor(0xFFF87171);
            mTvStatusDetail.setText("请通过「本机无线调试」或「电脑 ADB 命令」一键激活，无需手动传输任何脚本。");
            mBtnToggleInput.setText("⇄ 切换键鼠方向");
            if (mBtnToggleMirror != null) mBtnToggleMirror.setText("📺 切换模式 (镜像/DeX)");
            if (mBtnFullscreenPolicy != null) mBtnFullscreenPolicy.setText("⛶ 全屏策略");
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.VISIBLE);
        } else {
            mUserSkippedDialog = false;
            if (mBtnActivate != null) mBtnActivate.setVisibility(View.GONE);
            if (mActivationDialog != null && mActivationDialog.isShowing()) {
                mActivationDialog.dismiss();
                AdbBootstrap.stopDiscovery();
                AdbBootstrap.cancelPairingNotification(this);
                Toast.makeText(this, "✅ ExtBridge 服务激活成功，已进入控制台", Toast.LENGTH_SHORT).show();
            }
            if (syncSelection && !mInitedFromDaemon) {
                mInitedFromDaemon = true;
                mSelW = st.virtW;
                mSelH = st.virtH;
                mSelDpi = st.virtDpi;
                mSelFps = st.virtFps;
                mEdW.setText(String.valueOf(mSelW));
                mEdH.setText(String.valueOf(mSelH));
                updateSelectionStyles();
            }
            if (st.mirrorMode == 1 && st.extId > 0) {
                mTvStatusTitle.setText("● 原生镜像模式 · 物理 Display #" + st.extId);
                mTvStatusTitle.setTextColor(0xFFA78BFA);
                mTvStatusDetail.setText("外接屏正在原生同步手机/平板主屏（ExtBridge DeX 独立桌面已暂停）。\n"
                        + "点击上方「🖥 切回 DeX 模式」或下拉通知栏按钮可随时切回独立大屏。\n"
                        + "物理信号：" + st.physW + "×" + st.physH + " @ " + String.format("%.1f", st.physFps) + "Hz");
            } else if (st.vdId > 0) {
                mTvStatusTitle.setText("● 已连接扩展屏 · 物理 Display #" + st.extId + " -> 扩展 Display #" + st.vdId);
                mTvStatusTitle.setTextColor(0xFF4ADE80);
                String target = st.inputToExt == 1 ? "外接扩展屏 (按切回可在通知栏/此页切平板)" : "平板内屏";
                mTvStatusDetail.setText("当前键鼠控制：【" + target + "】  (检测到外接设备 " + st.extInputCnt + " 个)\n"
                        + "虚拟桌面：" + st.virtW + "×" + st.virtH + " @ " + st.virtDpi + "dpi · 请求 " + Math.round(st.virtFps) + "Hz (" + st.renderMode + ") · 全屏策略:" + (st.fullscreenPolicy == 1 ? "开" : "关") + "\n"
                        + "物理信号：" + st.physW + "×" + st.physH + " @ " + String.format("%.1f", st.physFps) + "Hz");
            } else {
                mTvStatusTitle.setText("● 后台服务待机中 · 请插入 Type-C 外接显示器");
                mTvStatusTitle.setTextColor(0xFFFBBF24);
                mTvStatusDetail.setText("已保存目标预设：" + st.virtW + "×" + st.virtH + " @ " + st.virtDpi + "dpi · " + Math.round(st.virtFps) + "Hz"
                        + (st.mirrorMode == 1 ? " (当前开关为原生镜像模式)" : ""));
            }
            mBtnToggleInput.setText(st.inputToExt == 1 ? "⇄ 键鼠切回平板 (当前:扩展)" : "⇄ 键鼠切回扩展 (当前:平板)");
            if (mBtnToggleMirror != null) {
                mBtnToggleMirror.setText(st.mirrorMode == 1 ? "🖥 切回 DeX 模式 (当前:镜像)" : "📺 切换镜像模式 (当前:DeX)");
            }
            if (mBtnFullscreenPolicy != null) {
                mBtnFullscreenPolicy.setText(st.fullscreenPolicy == 1 ? "⛶ 桌面锁横屏:开" : "⛶ 桌面锁横屏:关");
            }
            if (!st.modesRaw.equals(mLastModesRaw)) {
                mLastModesRaw = st.modesRaw;
                rebuildPhysModes(st.modesRaw);
            }
        }

        boolean canOverlay = Settings.canDrawOverlays(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean canNotif = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
        boolean a11yOn = NavAssistService.isRunning();
        mTvGestureStatus.setText(
                "• 路径1 (原生手势保护): 已自动启用 (无系统装饰占位，平板左右侧滑返回与底部上滑回桌面均可正常使用)\n"
                + "• 路径2 (辅助手势服务): " + (a11yOn ? "已开启" : "未开启 (当前路径1已正常，备用)") + "\n"
                + "• 悬浮窗权限: " + (canOverlay ? "已授权" : "未授权 (点击下方开启以显示鼠标光标与浮动条)") + "   |   "
                + "通知栏开关权限: " + (canNotif ? "已授权" : "未授权"));
    }

    private void rebuildPhysModes(String raw) {
        mPhysModesWrap.removeAllViews();
        if (raw == null || raw.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("暂无外接硬件模式数据 (插屏后自动刷新)");
            tv.setTextColor(0xFF64748B);
            tv.setTextSize(12.5f);
            mPhysModesWrap.addView(tv);
            return;
        }
        List<View> modeBtns = new ArrayList<>();
        for (String item : raw.split(";")) {
            String[] kv = item.split(":");
            if (kv.length != 2) continue;
            try {
                final int modeId = Integer.parseInt(kv[0]);
                final String desc = kv[1];
                TextView chip = makeChip("物理模式 #" + modeId + " · " + desc + "Hz");
                chip.setOnClickListener(v -> {
                    mSelPhysModeId = modeId;
                    try {
                        String[] rf = desc.split("@");
                        String[] wh = rf[0].split("x");
                        mSelW = Integer.parseInt(wh[0]);
                        mSelH = Integer.parseInt(wh[1]);
                        mSelFps = Float.parseFloat(rf[1]);
                        mEdW.setText(String.valueOf(mSelW));
                        mEdH.setText(String.valueOf(mSelH));
                        updateSelectionStyles();
                    } catch (Throwable ignored) {}
                    applyChosenConfig();
                });
                modeBtns.add(chip);
            } catch (Throwable ignored) {}
        }
        addChipWrap(mPhysModesWrap, modeBtns);
    }

    private void updateSelectionStyles() {
        for (int i = 0; i < RES_PRESETS.length && i < mResChips.size(); i++) {
            boolean sel = (RES_PRESETS[i][0] == mSelW && RES_PRESETS[i][1] == mSelH);
            styleChip(mResChips.get(i), sel);
        }
        for (int i = 0; i < DPI_PRESETS.length && i < mDpiChips.size(); i++) {
            styleChip(mDpiChips.get(i), DPI_PRESETS[i] == mSelDpi);
        }
        for (int i = 0; i < FPS_PRESETS.length && i < mFpsChips.size(); i++) {
            styleChip(mFpsChips.get(i), Math.abs(FPS_PRESETS[i] - mSelFps) < 1f);
        }
        updateSwHint();
    }

    private void updateSwHint() {
        if (mTvSwInfo == null) return;
        int cw = mSelW;
        int ch = mSelH;
        try {
            int pw = Integer.parseInt(mEdW.getText().toString().trim());
            int ph = Integer.parseInt(mEdH.getText().toString().trim());
            if (pw > 0 && ph > 0) { cw = pw; ch = ph; }
        } catch (Throwable ignored) {}
        int minPx = Math.min(cw, ch);
        int swDp = Math.round(minPx * 160f / Math.max(120, mSelDpi));
        boolean tabletLayout = swDp >= 600;
        mTvSwInfo.setText("当前等效最小宽度 sw" + swDp + "dp → "
                + (tabletLayout ? "✅ 已达平板大屏阈值 (sw≥600dp，视频软件优先横向大屏)"
                                : "⚠️ 低于平板阈值 (sw<600dp，建议 1920×1080 @ 240dpi 或更低 DPI)"));
        mTvSwInfo.setTextColor(tabletLayout ? 0xFF7DD3FC : 0xFFFBBF24);
    }

    private void styleChip(TextView tv, boolean selected) {
        GradientDrawable bg = (GradientDrawable) tv.getBackground();
        if (selected) {
            bg.setColor(0xFF1D4ED8);
            bg.setStroke(2, 0xFF38BDF8);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0xFF1E293B);
            bg.setStroke(1, 0x3364748B);
            tv.setTextColor(0xFFCBD5E1);
        }
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF131C2E);
        bg.setCornerRadius(20f);
        bg.setStroke(1, 0x2238BDF8);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        card.setLayoutParams(lp);
        return card;
    }

    private void addCardHeader(LinearLayout card, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(14.5f);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, 0, 0, 10);
        card.addView(tv);
    }

    private void addCardHeaderTopPad(LinearLayout card, String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(14.5f);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, 16, 0, 10);
        card.addView(tv);
    }

    private TextView makeChip(String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12.5f);
        tv.setPadding(20, 11, 20, 11);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(14f);
        bg.setStroke(1, 0x3364748B);
        tv.setBackground(bg);
        return tv;
    }

    private TextView makeActionBtn(String txt, int color) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(22, 13, 22, 13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(14f);
        tv.setBackground(bg);
        return tv;
    }

    private EditText makeNumBox(String val) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setTextColor(Color.WHITE);
        ed.setTextSize(14f);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER);
        ed.setGravity(Gravity.CENTER);
        ed.setPadding(16, 8, 16, 8);
        ed.setMinWidth(130);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0F172A);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0xFF475569);
        ed.setBackground(bg);
        return ed;
    }

    private void addChipWrap(LinearLayout container, List<View> views) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxW = Math.max(480, dm.widthPixels - 120);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, 10);
        int curW = 0;

        for (View v : views) {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int vw = v.getMeasuredWidth() + 14;
            if (curW > 0 && curW + vw > maxW) {
                container.addView(row);
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, 10);
                curW = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            row.addView(v, lp);
            curW += vw;
        }
        if (row.getChildCount() > 0) container.addView(row);
    }

    private void showActivationDialog() {
        if (isFinishing()) return;
        if (mActivationDialog != null && mActivationDialog.isShowing()) return;

        mHostEdited = false;
        mPairPortEdited = false;
        mConnPortEdited = false;

        ScrollView sv = new ScrollView(this);
        GradientDrawable dlgBg = new GradientDrawable();
        dlgBg.setColor(0xFF0F172A);
        dlgBg.setCornerRadius(24f);
        dlgBg.setStroke(2, 0xFF334155);
        sv.setBackground(dlgBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 30, 34, 28);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("⚡ 激活 ExtBridge 核心服务");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("未检测到后台守护进程（新安装或重启后需激活一次）。请选择激活方式，检测连接成功后将自动进入应用：");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(12.5f);
        sub.setPadding(0, 8, 0, 18);
        root.addView(sub);

        // Two mode selector buttons
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabLp1.rightMargin = 10;
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        final TextView btnModeWireless = makeActionBtn("📡 无线调试\n(本机免电脑)", 0xFF1E293B);
        final TextView btnModeAdb = makeActionBtn("💻 ADB 命令\n(电脑执行一句)", 0xFF1E293B);
        btnModeWireless.setTextSize(13f);
        btnModeAdb.setTextSize(13f);
        tabRow.addView(btnModeWireless, tabLp1);
        tabRow.addView(btnModeAdb, tabLp2);
        root.addView(tabRow);

        // Panel 1: Wireless Debugging
        final LinearLayout wirelessPane = new LinearLayout(this);
        wirelessPane.setOrientation(LinearLayout.VERTICAL);
        wirelessPane.setPadding(0, 18, 0, 0);
        wirelessPane.setVisibility(View.GONE);

        mTvMdnsHint = new TextView(this);
        mTvMdnsHint.setTextColor(0xFF38BDF8);
        mTvMdnsHint.setTextSize(12f);
        mTvMdnsHint.setPadding(14, 10, 14, 10);
        GradientDrawable mdnsBg = new GradientDrawable();
        mdnsBg.setColor(0xFF1E293B);
        mdnsBg.setCornerRadius(12f);
        mTvMdnsHint.setBackground(mdnsBg);
        wirelessPane.addView(mTvMdnsHint);

        // Step helper buttons
        LinearLayout guideRow = new LinearLayout(this);
        guideRow.setOrientation(LinearLayout.HORIZONTAL);
        guideRow.setPadding(0, 12, 0, 12);
        LinearLayout.LayoutParams gLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gLp1.rightMargin = 8;
        LinearLayout.LayoutParams gLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnOpenDev = makeActionBtn("1. 去开启无线调试 + 配对通知", 0xFF0369A1);
        btnOpenDev.setTextSize(12f);
        btnOpenDev.setOnClickListener(v -> {
            ensureNotifPermAndShowPairNotif();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Throwable ignored) {}
            }
        });
        guideRow.addView(btnOpenDev, gLp1);

        TextView btnPairNotif = makeActionBtn("🔔 发送/刷新通知快捷配对", 0xFF334155);
        btnPairNotif.setTextSize(12f);
        btnPairNotif.setOnClickListener(v -> {
            ensureNotifPermAndShowPairNotif();
            Toast.makeText(this, "已发送配对通知：在系统配对弹窗上方下拉通知即可直接填6位配对码", Toast.LENGTH_LONG).show();
        });
        guideRow.addView(btnPairNotif, gLp2);
        wirelessPane.addView(guideRow);

        // Input fields: IP + Pair Port + Pair Code + Connect Port
        mEdPairHost = makeDialogInput(AdbBootstrap.getLocalIpv4(), "本机 IP 地址 (如 192.168.1.5)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mEdPairPort = makeDialogInput("", "配对端口 (弹窗上的端口，如 36565)", InputType.TYPE_CLASS_NUMBER);
        mEdPairCode = makeDialogInput("", "6 位 WLAN 配对码 (如 551857)", InputType.TYPE_CLASS_NUMBER);
        mEdConnPort = makeDialogInput("", "无线调试主页连接端口 (若已配对直接填，如 37991)", InputType.TYPE_CLASS_NUMBER);

        attachEditFlag(mEdPairHost, () -> mHostEdited = true);
        attachEditFlag(mEdPairPort, () -> mPairPortEdited = true);
        attachEditFlag(mEdConnPort, () -> mConnPortEdited = true);

        addLabeledField(wirelessPane, "IP 地址", mEdPairHost);
        addLabeledField(wirelessPane, "配对端口 & 6位配对码 (新设备配对必填)", mEdPairPort);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = 6;
        wirelessPane.addView(mEdPairCode, codeLp);
        addLabeledField(wirelessPane, "连接端口 (已配对过可直接填此端口跳过配对)", mEdConnPort);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);
        runRow.setPadding(0, 14, 0, 8);
        LinearLayout.LayoutParams rLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rLp1.rightMargin = 8;
        LinearLayout.LayoutParams rLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnPairRun = makeActionBtn("🔗 配对并激活", 0xFF2563EB);
        TextView btnConnRun = makeActionBtn("⚡ 已配对·直接连接", 0xFF0F766E);
        runRow.addView(btnPairRun, rLp1);
        runRow.addView(btnConnRun, rLp2);
        wirelessPane.addView(runRow);

        mTvActStatus = new TextView(this);
        mTvActStatus.setText("提示：也可下拉通知栏在「ExtBridge 无线调试配对助手」里直接输入6位配对码，不会导致弹窗验证码失效。");
        mTvActStatus.setTextColor(0xFFCBD5E1);
        mTvActStatus.setTextSize(12f);
        mTvActStatus.setPadding(12, 10, 12, 10);
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(0xFF020617);
        stBg.setCornerRadius(10f);
        mTvActStatus.setBackground(stBg);
        wirelessPane.addView(mTvActStatus);
        root.addView(wirelessPane);

        // Panel 2: PC ADB Command
        final LinearLayout adbPane = new LinearLayout(this);
        adbPane.setOrientation(LinearLayout.VERTICAL);
        adbPane.setPadding(0, 18, 0, 0);
        adbPane.setVisibility(View.GONE);

        TextView adbDesc = new TextView(this);
        adbDesc.setText("在电脑通过 USB 或无线 ADB 连接本机后，复制并执行下方命令（全自动释放内置 extbridge.jar、授权并启动服务）：");
        adbDesc.setTextColor(0xFFCBD5E1);
        adbDesc.setTextSize(12.5f);
        adbDesc.setPadding(0, 0, 0, 10);
        adbPane.addView(adbDesc);

        final String cmdText = AdbBootstrap.buildPcAdbCommand();
        TextView tvCmd = new TextView(this);
        tvCmd.setText(cmdText);
        tvCmd.setTextColor(0xFF86EFAC);
        tvCmd.setTextSize(11f);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        tvCmd.setTextIsSelectable(true);
        tvCmd.setPadding(16, 14, 16, 14);
        GradientDrawable cmdBg = new GradientDrawable();
        cmdBg.setColor(0xFF020617);
        cmdBg.setCornerRadius(12f);
        cmdBg.setStroke(1, 0xFF334155);
        tvCmd.setBackground(cmdBg);
        adbPane.addView(tvCmd);

        TextView btnCopyCmd = makeActionBtn("📋 复制 ADB 激活命令", 0xFF2563EB);
        btnCopyCmd.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("extbridge_adb", cmdText));
            }
            Toast.makeText(this, "已复制命令到剪贴板，请在电脑终端粘贴运行", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = 12;
        adbPane.addView(btnCopyCmd, copyLp);

        TextView tvWait = new TextView(this);
        tvWait.setText("⏳ 正在自动检测连接状态… 电脑命令执行完成后将自动关闭此窗口并进入应用。");
        tvWait.setTextColor(0xFFFBBF24);
        tvWait.setTextSize(12f);
        tvWait.setPadding(0, 12, 0, 0);
        adbPane.addView(tvWait);
        root.addView(adbPane);

        // Skip button at bottom
        TextView btnSkip = new TextView(this);
        btnSkip.setText("暂不激活，先浏览控制台界面 →");
        btnSkip.setTextColor(0xFF64748B);
        btnSkip.setTextSize(12.5f);
        btnSkip.setGravity(Gravity.CENTER);
        btnSkip.setPadding(12, 20, 12, 6);
        btnSkip.setOnClickListener(v -> {
            mUserSkippedDialog = true;
            if (mActivationDialog != null) mActivationDialog.dismiss();
        });
        root.addView(btnSkip);

        final Runnable selectWireless = () -> {
            wirelessPane.setVisibility(View.VISIBLE);
            adbPane.setVisibility(View.GONE);
            setTabSelected(btnModeWireless, true);
            setTabSelected(btnModeAdb, false);
            AdbBootstrap.startDiscovery(this, this::applyDiscoveryStateToUi);
        };
        final Runnable selectAdb = () -> {
            wirelessPane.setVisibility(View.GONE);
            adbPane.setVisibility(View.VISIBLE);
            setTabSelected(btnModeWireless, false);
            setTabSelected(btnModeAdb, true);
        };

        btnModeWireless.setOnClickListener(v -> selectWireless.run());
        btnModeAdb.setOnClickListener(v -> selectAdb.run());

        btnPairRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int pairPort = parseIntStr(mEdPairPort.getText().toString(), 0);
            String code = mEdPairCode.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在执行配对并启动服务...", 0xFF38BDF8);
            AdbBootstrap.pairAndBootstrapAsync(this, host, pairPort, code, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        btnConnRun.setOnClickListener(v -> {
            if (mActWorking) return;
            String host = mEdPairHost.getText().toString().trim();
            int connPort = parseIntStr(mEdConnPort.getText().toString(), 0);
            mActWorking = true;
            setActStatus("⏳ 正在连接无线调试并启动服务...", 0xFF38BDF8);
            AdbBootstrap.connectAndBootstrapAsync(this, host, connPort,
                    new AdbBootstrap.ProgressListener() {
                        @Override
                        public void onStep(String message) {
                            setActStatus("⏳ " + message, 0xFF38BDF8);
                        }

                        @Override
                        public void onResult(boolean success, String message) {
                            mActWorking = false;
                            setActStatus((success ? "✅ " : "❌ ") + message, success ? 0xFF4ADE80 : 0xFFF87171);
                            refreshStatus(true);
                        }
                    });
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(sv);
        builder.setCancelable(false);
        mActivationDialog = builder.create();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        mActivationDialog.show();
        if (mActivationDialog.getWindow() != null) {
            mActivationDialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            mActivationDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void applyDiscoveryStateToUi(AdbBootstrap.DiscoveryState ds) {
        if (ds == null) return;
        if (mTvMdnsHint != null) {
            String pStr = ds.pairingPort > 0 ? (ds.pairingHost + ":" + ds.pairingPort) : "未开启配对弹窗";
            String cStr = ds.connectPort > 0 ? (ds.connectHost + ":" + ds.connectPort) : "待检测";
            mTvMdnsHint.setText("🔍 mDNS 自动发现：配对=[" + pStr + "] · 连接=[" + cStr + "]");
        }
        if (mEdPairHost != null && !mHostEdited) {
            String bestHost = !ds.pairingHost.isEmpty() ? ds.pairingHost : ds.connectHost;
            if (!bestHost.isEmpty() && !bestHost.equals(mEdPairHost.getText().toString())) {
                mEdPairHost.setText(bestHost);
                mHostEdited = false;
            }
        }
        if (mEdPairPort != null && !mPairPortEdited && ds.pairingPort > 0) {
            mEdPairPort.setText(String.valueOf(ds.pairingPort));
            mPairPortEdited = false;
        }
        if (mEdConnPort != null && !mConnPortEdited && ds.connectPort > 0) {
            mEdConnPort.setText(String.valueOf(ds.connectPort));
            mConnPortEdited = false;
        }
    }

    private void setActStatus(String msg, int color) {
        if (mTvActStatus != null) {
            mTvActStatus.setText(msg);
            mTvActStatus.setTextColor(color);
        }
    }

    private void ensureNotifPermAndShowPairNotif() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3902);
        }
        AdbBootstrap.showPairingNotification(this, null);
    }

    private void setTabSelected(TextView tv, boolean sel) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sel ? 0xFF1D4ED8 : 0xFF1E293B);
        bg.setCornerRadius(14f);
        bg.setStroke(2, sel ? 0xFF60A5FA : 0x4464748B);
        tv.setBackground(bg);
    }

    private EditText makeDialogInput(String val, String hint, int inputType) {
        EditText ed = new EditText(this);
        ed.setText(val);
        ed.setHint(hint);
        ed.setHintTextColor(0xFF64748B);
        ed.setTextColor(Color.WHITE);
        ed.setTextSize(13.5f);
        ed.setInputType(inputType);
        ed.setSingleLine(true);
        ed.setPadding(18, 14, 18, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF020617);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0xFF475569);
        ed.setBackground(bg);
        return ed;
    }

    private void addLabeledField(LinearLayout parent, String label, View field) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(11.5f);
        tv.setPadding(2, 10, 0, 4);
        parent.addView(tv);
        parent.addView(field);
    }

    private void attachEditFlag(EditText ed, final Runnable onUserEdit) {
        ed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (ed.hasFocus()) onUserEdit.run();
            }
        });
    }

    private static final class AppModeRow {
        String label;
        String pkg;
        int mode;
    }

    private void showAppModesDialog() {
        if (BridgeClient.getService() == null) {
            Toast.makeText(this, "请先激活后台服务再配置分应用显示模式", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 28, 36, 24);
        root.setBackgroundColor(0xFF0F172A);

        TextView title = new TextView(this);
        title.setText("🧩 分应用显示模式管理");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("只有设为非「跟随系统默认」的应用才会注入兼容或全屏参数，微信、夸克、拼多多等建议保持「跟随系统默认」即可恢复正常。");
        desc.setTextColor(0xFF94A3B8);
        desc.setTextSize(12f);
        desc.setPadding(0, 6, 0, 12);
        root.addView(desc);

        EditText edFilter = makeDialogInput("", "搜索应用名或包名（如：微信 / 夸克 / 红果 / pinduoduo）", InputType.TYPE_CLASS_TEXT);
        root.addView(edFilter);

        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(0, 8, 0, 4);

        TextView loading = new TextView(this);
        loading.setText("正在读取已安装应用与当前兼容配置...");
        loading.setTextColor(0xFF94A3B8);
        loading.setTextSize(12.5f);
        loading.setPadding(8, 16, 8, 16);
        listWrap.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(listWrap);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(420, (int) (dm.heightPixels * 0.48f)));
        svLp.topMargin = 10;
        root.addView(sv, svLp);

        b.setView(root);
        b.setNeutralButton("↺ 全部重置为默认", null);
        b.setNegativeButton("关闭", null);
        AlertDialog dlg = b.create();
        dlg.show();

        final List<AppModeRow> allRows = new ArrayList<>();
        final Runnable refreshList = () -> rebuildAppModeRows(listWrap, allRows, edFilter.getText().toString(), dlg);

        edFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshList.run();
            }
        });

        View resetBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (resetBtn != null) {
            resetBtn.setOnClickListener(v -> new Thread(() -> {
                BridgeClient.resetAllAppModes();
                Map<String, Integer> latest = BridgeClient.getAllAppModes();
                runOnUiThread(() -> {
                    for (AppModeRow r : allRows) {
                        Integer mv = latest.get(r.pkg);
                        r.mode = mv != null ? mv : 0;
                    }
                    refreshList.run();
                    BridgeService.refreshFromReceiver(this);
                    Toast.makeText(this, "已清空全部自定义兼容覆盖并恢复系统默认", Toast.LENGTH_SHORT).show();
                });
            }).start());
        }

        new Thread(() -> {
            Map<String, Integer> modes = BridgeClient.getAllAppModes();
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            Map<String, AppModeRow> dedup = new HashMap<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (getPackageName().equals(pkg) || dedup.containsKey(pkg)) continue;
                AppModeRow row = new AppModeRow();
                row.pkg = pkg;
                row.label = String.valueOf(ri.loadLabel(pm));
                Integer mv = modes.get(pkg);
                row.mode = mv != null ? mv : 0;
                dedup.put(pkg, row);
            }
            List<AppModeRow> loaded = new ArrayList<>(dedup.values());
            Collections.sort(loaded, (a, c) -> {
                int ca = a.mode > 0 ? 0 : 1;
                int cb = c.mode > 0 ? 0 : 1;
                if (ca != cb) return ca - cb;
                return a.label.compareToIgnoreCase(c.label);
            });
            runOnUiThread(() -> {
                if (!dlg.isShowing()) return;
                allRows.clear();
                allRows.addAll(loaded);
                refreshList.run();
            });
        }).start();
    }

    private void rebuildAppModeRows(LinearLayout container, List<AppModeRow> allRows, String query, AlertDialog parentDlg) {
        container.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AppModeRow r : allRows) {
            if (!q.isEmpty()) {
                boolean match = r.label.toLowerCase(Locale.ROOT).contains(q)
                        || r.pkg.toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(20, 14, 20, 14);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(r.mode > 0 ? 0xFF172554 : 0xFF1E293B);
            bg.setCornerRadius(14f);
            bg.setStroke(1, r.mode > 0 ? 0xFF38BDF8 : 0xFF334155);
            card.setBackground(bg);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView tvName = new TextView(this);
            tvName.setText(r.label);
            tvName.setTextColor(0xFFF1F5F9);
            tvName.setTextSize(14f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            left.addView(tvName);

            TextView tvPkg = new TextView(this);
            tvPkg.setText(r.pkg);
            tvPkg.setTextColor(0xFF94A3B8);
            tvPkg.setTextSize(11f);
            left.addView(tvPkg);
            card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pill = new TextView(this);
            pill.setText(BridgeClient.appModeLabel(r.mode));
            pill.setTextSize(11.5f);
            pill.setPadding(16, 8, 16, 8);
            GradientDrawable pBg = new GradientDrawable();
            int pColor = r.mode == BridgeClient.APP_MODE_LAND_FULL ? 0xFF0284C7
                    : (r.mode == BridgeClient.APP_MODE_PORT_COMPAT ? 0xFFD97706
                    : (r.mode == BridgeClient.APP_MODE_FREEFORM ? 0xFF7C3AED : 0xFF334155));
            pBg.setColor(pColor);
            pBg.setCornerRadius(12f);
            pill.setBackground(pBg);
            pill.setTextColor(Color.WHITE);
            card.addView(pill);

            card.setOnClickListener(v -> showSingleAppModePicker(r, () -> {
                if (parentDlg.isShowing()) {
                    rebuildAppModeRows(container, allRows, query, parentDlg);
                }
            }));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 10;
            container.addView(card, lp);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("未找到匹配的应用");
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(10, 20, 10, 20);
            container.addView(empty);
        }
    }

    private void showSingleAppModePicker(AppModeRow row, Runnable onUpdated) {
        String[] choices = {
                (row.mode == 0 ? "● " : "") + "跟随系统默认（不注入兼容参数，推荐微信/夸克/拼多多）",
                (row.mode == 1 ? "● " : "") + "强制横屏全屏（适合红果短剧等无法横屏的视频 App）",
                (row.mode == 2 ? "● " : "") + "竖屏居中兼容（保持手机竖屏比例不拉伸变形）",
                (row.mode == 3 ? "● " : "") + "自由小窗模式（以 PC 自由缩放小窗打开）"
        };
        new AlertDialog.Builder(this)
                .setTitle(row.label + "\n" + row.pkg)
                .setItems(choices, (d, which) -> {
                    Toast.makeText(this, "正在切换并重启「" + row.label + "」...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        BridgeClient.setAppMode(row.pkg, which, true);
                        row.mode = which;
                        runOnUiThread(() -> {
                            BridgeService.refreshFromReceiver(this);
                            onUpdated.run();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int parseIntStr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
