package com.extbridge.helper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app live mirror controller for Native Mirror's black-margin sidebar and system AppWidgets.
 * Allows direct finger tap/swipe/scroll control of the sidebar without external mouse or keyboard.
 */
public class SideControlActivity extends Activity implements TextureView.SurfaceTextureListener {

    private Handler mHandler;
    private ExecutorService mTouchExec;
    private TextureView mTextureView;
    private FrameLayout mMirrorStage;
    private LinearLayout mRootView;
    private LinearLayout mHeaderView;
    private HorizontalScrollView mBarScroll;
    private TextView mTitleView;
    private BridgeClient.MirrorTheme mTheme = BridgeClient.resolveMirrorTheme("night", 0xFF0F172A);
    private FrameLayout mMirrorCardFrame;
    private TouchRippleOverlay mRippleOverlay;
    private TextView mTvInfo;
    private TextView mBtnWingLeft;
    private TextView mBtnWingRight;
    private LinearLayout mWingSwitchRow;
    private TextView mBtnZoomMode;
    private TextView mTvEmptyHint;

    private Surface mTexSurface;
    private int mTexW = 0;
    private int mTexH = 0;
    private int mWingSlot = 0;
    private boolean mWideTouchBoost = true;
    private boolean mRunning = false;
    private boolean mUseSoftwareDraw = false;
    private Bitmap mSwBmp;
    private Canvas mSwCanvas;
    private int mSwW = 0;
    private int mSwH = 0;

    private int mLastMetaX = 0, mLastMetaY = 0, mLastMetaW = 0, mLastMetaH = 0;
    private int mLastStageW = 0, mLastStageH = 0;
    private boolean mLastWideBoost = true;
    private int mLastWingSlot = -1;
    private boolean mSideTouchDown = false;
    private float mLastSideTouchX = 0f;
    private float mLastSideTouchY = 0f;

    private boolean ensureOnDisplay0() {
        try {
            android.view.Display d = getDisplay();
            if (d != null && d.getDisplayId() != 0) {
                Intent i = new Intent(this, SideControlActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(0);
                startActivity(i, opts.toBundle());
                finishAndRemoveTask();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void cancelHeldSideTouchIfNeeded() {
        if (!mSideTouchDown) return;
        mSideTouchDown = false;
        final float sx = mLastSideTouchX;
        final float sy = mLastSideTouchY;
        if (mRippleOverlay != null) mRippleOverlay.setTouchPoint(-1f, -1f, false);
        new Thread(() -> BridgeClient.sendSideTouch(MotionEvent.ACTION_CANCEL, sx, sy)).start();
    }

    private final Runnable mFrameTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            syncStageLayoutAndMeta();
            renderMirrorFrame();
            mHandler.postDelayed(this, 33L);
        }
    };

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView makeBtn(String text, int bgColor, int iconKind) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (iconKind > 0) {
            HyperUi.setStartIcon(tv, iconKind, Color.WHITE, 14f, 5f);
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(13));
        gd.setStroke(dp(1), 0x24FFFFFF);
        tv.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ensureOnDisplay0()) return;
        getWindow().getDecorView().setFilterTouchesWhenObscured(true);
        mHandler = new Handler(Looper.getMainLooper());
        mTouchExec = Executors.newSingleThreadExecutor();

        mTheme = BridgeService.getActiveMirrorTheme(this);
        mRootView = new LinearLayout(this);
        LinearLayout root = mRootView;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mTheme.bgColor);
        root.setFitsSystemWindows(true);

        // Header
        mHeaderView = new LinearLayout(this);
        LinearLayout header = mHeaderView;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(14), dp(10));
        header.setBackgroundColor(mTheme.headerBgColor);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        mTitleView = new TextView(this);
        TextView title = mTitleView;
        title.setText("副屏镜像直控");
        title.setTextColor(mTheme.titleColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.getPaint().setFakeBoldText(true);
        HyperUi.setStartIcon(title, HyperUi.ICON_SIDEBAR, HyperUi.HYPER_BLUE, 17f, 6f);
        titleCol.addView(title);

        mTvInfo = new TextView(this);
        mTvInfo.setText("正在连接原生镜像侧边栏…");
        mTvInfo.setTextColor(mTheme.accentColor);
        mTvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleCol.addView(mTvInfo);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mBtnZoomMode = makeBtn("宽屏易触", HyperUi.HYPER_BLUE, HyperUi.ICON_ZOOM);
        mBtnZoomMode.setOnClickListener(v -> {
            mWideTouchBoost = !mWideTouchBoost;
            mBtnZoomMode.setText(mWideTouchBoost ? "宽屏易触" : "原比例");
            HyperUi.setStartIcon(mBtnZoomMode,
                    mWideTouchBoost ? HyperUi.ICON_ZOOM : HyperUi.ICON_RATIO, Color.WHITE, 14f, 5f);
            ((GradientDrawable) mBtnZoomMode.getBackground()).setColor(
                    mWideTouchBoost ? HyperUi.HYPER_BLUE : HyperUi.CHIP_INACTIVE);
            mLastMetaW = 0;
            syncStageLayoutAndMeta();
        });
        header.addView(mBtnZoomMode);

        TextView btnClose = makeBtn("退出", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CLOSE);
        btnClose.setOnClickListener(v -> finish());
        header.addView(btnClose);
        root.addView(header);

        // Dual-wing switcher row (shown only in dual-wing centered mode)
        mWingSwitchRow = new LinearLayout(this);
        mWingSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
        mWingSwitchRow.setGravity(Gravity.CENTER);
        mWingSwitchRow.setPadding(dp(12), dp(6), dp(12), dp(4));
        mWingSwitchRow.setVisibility(View.GONE);
        mBtnWingLeft = makeBtn("左/上侧信息条", HyperUi.HYPER_BLUE, HyperUi.ICON_CHEVRON_LEFT);
        mBtnWingLeft.setOnClickListener(v -> {
            mWingSlot = 0;
            updateWingBtns();
        });
        mBtnWingRight = makeBtn("右/下侧信息条", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CHEVRON_RIGHT);
        mBtnWingRight.setOnClickListener(v -> {
            mWingSlot = 1;
            updateWingBtns();
        });
        mWingSwitchRow.addView(mBtnWingLeft);
        mWingSwitchRow.addView(mBtnWingRight);
        root.addView(mWingSwitchRow);

        // Center Stage
        mMirrorStage = new FrameLayout(this);
        mMirrorStage.setPadding(dp(12), dp(8), dp(12), dp(8));

        mMirrorCardFrame = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF0B1221);
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), HyperUi.HYPER_BLUE);
        mMirrorCardFrame.setBackground(cardBg);
        mMirrorCardFrame.setClipToOutline(true);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setOnTouchListener((v, ev) -> handleMirrorTouch(ev));
        mMirrorCardFrame.addView(mTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mRippleOverlay = new TouchRippleOverlay(this);
        mMirrorCardFrame.addView(mRippleOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(dp(260), dp(420), Gravity.CENTER);
        mMirrorStage.addView(mMirrorCardFrame, cardLp);

        mTvEmptyHint = new TextView(this);
        mTvEmptyHint.setText("当前未处于「原生镜像 + 融合边栏」模式\n请在控制台开启「原生镜像」并选择边栏对齐后使用");
        mTvEmptyHint.setTextColor(0xFF94A3B8);
        mTvEmptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mTvEmptyHint.setGravity(Gravity.CENTER);
        mTvEmptyHint.setVisibility(View.GONE);
        mMirrorStage.addView(mTvEmptyHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(mMirrorStage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom quick bar
        mBarScroll = new HorizontalScrollView(this);
        HorizontalScrollView barScroll = mBarScroll;
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setBackgroundColor(mTheme.headerBgColor);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(10));

        TextView btnAlign = makeBtn("切换边栏方位", HyperUi.HYPER_BLUE, HyperUi.ICON_SWAP);
        btnAlign.setOnClickListener(v -> mTouchExec.execute(() -> {
            int cur = BridgeClient.getStatus().mirrorSideMode;
            int next = (cur == BridgeClient.MIRROR_SIDE_ALIGN_LEFT)
                    ? BridgeClient.MIRROR_SIDE_ALIGN_RIGHT
                    : BridgeClient.MIRROR_SIDE_ALIGN_LEFT;
            BridgeClient.setMirrorSideMode(next);
            BridgeService.refreshFromReceiver(this);
        }));

        TextView btnMain = makeBtn("卡片与小组件配置", HyperUi.CHIP_INACTIVE, HyperUi.ICON_SLIDERS);
        btnMain.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });

        TextView btnTp = makeBtn("大触摸板", HyperUi.CHIP_INACTIVE, HyperUi.ICON_TOUCHPAD);
        btnTp.setOnClickListener(v -> {
            startActivity(new Intent(this, TouchpadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });

        TextView btnDex = makeBtn("切回 DeX", HyperUi.CHIP_INACTIVE, HyperUi.ICON_DEX);
        btnDex.setOnClickListener(v -> mTouchExec.execute(() -> {
            BridgeClient.setMirrorMode(BridgeClient.MIRROR_MODE_DEX);
            BridgeService.refreshFromReceiver(this);
            runOnUiThread(() -> {
                Toast.makeText(this, "已切回 DeX 扩展桌面", Toast.LENGTH_SHORT).show();
                finish();
            });
        }));

        bar.addView(btnAlign);
        bar.addView(btnMain);
        bar.addView(btnTp);
        bar.addView(btnDex);
        barScroll.addView(bar);
        root.addView(barScroll);

        setContentView(root);
    }

    private void updateWingBtns() {
        ((GradientDrawable) mBtnWingLeft.getBackground()).setColor(
                mWingSlot == 0 ? HyperUi.HYPER_BLUE : HyperUi.CHIP_INACTIVE);
        ((GradientDrawable) mBtnWingRight.getBackground()).setColor(
                mWingSlot == 1 ? HyperUi.HYPER_BLUE : HyperUi.CHIP_INACTIVE);
        mLastMetaW = 0;
        syncStageLayoutAndMeta();
    }

    private void syncStageLayoutAndMeta() {
        BridgeClient.MirrorTheme latestTheme = BridgeService.getActiveMirrorTheme(this);
        if (latestTheme.bgColor != mTheme.bgColor || latestTheme.titleColor != mTheme.titleColor) {
            mTheme = latestTheme;
            if (mRootView != null) mRootView.setBackgroundColor(mTheme.bgColor);
            if (mHeaderView != null) mHeaderView.setBackgroundColor(mTheme.headerBgColor);
            if (mBarScroll != null) mBarScroll.setBackgroundColor(mTheme.headerBgColor);
            if (mTitleView != null) mTitleView.setTextColor(mTheme.titleColor);
            if (mTvInfo != null) mTvInfo.setTextColor(mTheme.accentColor);
        }
        int[] meta = BridgeService.getMirrorWingMeta(mWingSlot);
        if (meta == null) {
            mMirrorCardFrame.setVisibility(View.INVISIBLE);
            mTvEmptyHint.setVisibility(View.VISIBLE);
            mWingSwitchRow.setVisibility(View.GONE);
            mTvInfo.setText("未检测到融合边栏（请先进入原生镜像模式）");
            return;
        }
        mMirrorCardFrame.setVisibility(View.VISIBLE);
        mTvEmptyHint.setVisibility(View.GONE);

        int wx = meta[0], wy = meta[1], ww = meta[2], wh = meta[3];
        int hasDual = meta[7];
        int sideMode = meta[8];
        mWingSwitchRow.setVisibility(hasDual == 1 ? View.VISIBLE : View.GONE);
        if (hasDual == 0 && mWingSlot != 0) mWingSlot = 0;

        int availW = Math.max(dp(120), mMirrorStage.getWidth() - mMirrorStage.getPaddingLeft() - mMirrorStage.getPaddingRight());
        int availH = Math.max(dp(120), mMirrorStage.getHeight() - mMirrorStage.getPaddingTop() - mMirrorStage.getPaddingBottom());
        if (mLastMetaW == ww && mLastMetaH == wh && mLastMetaX == wx && mLastMetaY == wy
                && mLastStageW == availW && mLastStageH == availH
                && mLastWideBoost == mWideTouchBoost && mLastWingSlot == mWingSlot) {
            return;
        }
        mLastMetaX = wx; mLastMetaY = wy; mLastMetaW = ww; mLastMetaH = wh;
        mLastStageW = availW; mLastStageH = availH;
        mLastWideBoost = mWideTouchBoost;
        mLastWingSlot = mWingSlot;

        float fitScale = Math.min(availW / (float) ww, availH / (float) wh);
        int dstW = Math.max(dp(80), Math.round(ww * fitScale));
        int dstH = Math.max(dp(80), Math.round(wh * fitScale));
        if (mWideTouchBoost) {
            if (ww < wh) {
                dstW = Math.min(availW, Math.max(dstW, Math.min(dp(340), Math.round(dstW * 1.55f))));
            } else if (ww > wh) {
                dstH = Math.min(availH, Math.max(dstH, Math.min(dp(300), Math.round(dstH * 1.35f))));
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mMirrorCardFrame.getLayoutParams();
        if (lp.width != dstW || lp.height != dstH) {
            lp.width = dstW;
            lp.height = dstH;
            lp.gravity = Gravity.CENTER;
            mMirrorCardFrame.setLayoutParams(lp);
        }
        mTvInfo.setText(BridgeClient.mirrorSideModeLabel(sideMode)
                + " · " + ww + "×" + wh + " @(" + wx + "," + wy + ") · 直接点按/滑动即控");
    }

    private void renderMirrorFrame() {
        if (mTexSurface == null || !mTexSurface.isValid() || mTexW <= 0 || mTexH <= 0) return;
        int[] meta = BridgeService.getMirrorWingMeta(mWingSlot);
        if (meta == null) return;
        if (!mUseSoftwareDraw) {
            Canvas hw = null;
            try {
                hw = mTexSurface.lockHardwareCanvas();
                hw.drawColor(mTheme.bgColor);
                BridgeService.renderMirrorWingToCanvas(hw, mTexW, mTexH, mWingSlot);
                mTexSurface.unlockCanvasAndPost(hw);
                hw = null;
                return;
            } catch (Throwable t) {
                if (hw != null) {
                    try { mTexSurface.unlockCanvasAndPost(hw); } catch (Throwable ignored) {}
                }
                mUseSoftwareDraw = true;
            }
        }
        // Safe software fallback if cross-display RenderNode causes HW canvas conflict (capped for 4K/5K displays)
        try {
            int ww = meta[2], wh = meta[3];
            float swScale = Math.min(1f, Math.min(900f / Math.max(1, ww), 1440f / Math.max(1, wh)));
            int swW = Math.max(1, Math.round(ww * swScale));
            int swH = Math.max(1, Math.round(wh * swScale));
            if (mSwBmp == null || mSwW != swW || mSwH != swH) {
                if (mSwBmp != null) mSwBmp.recycle();
                mSwBmp = Bitmap.createBitmap(swW, swH, Bitmap.Config.ARGB_8888);
                mSwCanvas = new Canvas(mSwBmp);
                mSwW = swW;
                mSwH = swH;
            }
            mSwBmp.eraseColor(mTheme.bgColor);
            BridgeService.renderMirrorWingToCanvas(mSwCanvas, swW, swH, mWingSlot);
            Canvas c = mTexSurface.lockCanvas(null);
            if (c != null) {
                try {
                    c.drawBitmap(mSwBmp,
                            new android.graphics.Rect(0, 0, swW, swH),
                            new android.graphics.Rect(0, 0, mTexW, mTexH), null);
                } finally {
                    mTexSurface.unlockCanvasAndPost(c);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean handleMirrorTouch(MotionEvent ev) {
        if (ev == null) return false;
        int[] meta = BridgeService.getMirrorWingMeta(mWingSlot);
        if (meta == null || mTextureView.getWidth() <= 0 || mTextureView.getHeight() <= 0) return false;
        int act = ev.getActionMasked();
        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE
                && act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL) {
            return true;
        }
        float rx = Math.max(0f, Math.min(1f, ev.getX() / (float) mTextureView.getWidth()));
        float ry = Math.max(0f, Math.min(1f, ev.getY() / (float) mTextureView.getHeight()));
        final float sx = meta[0] + rx * meta[2];
        final float sy = meta[1] + ry * meta[3];
        mLastSideTouchX = sx;
        mLastSideTouchY = sy;
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            mSideTouchDown = true;
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            mSideTouchDown = false;
        }
        if (mRippleOverlay != null) {
            boolean show = (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE);
            mRippleOverlay.setTouchPoint(ev.getX(), ev.getY(), show);
        }
        mTouchExec.execute(() -> BridgeClient.sendSideTouch(act, sx, sy));
        if (act == MotionEvent.ACTION_UP) {
            mHandler.postDelayed(this::renderMirrorFrame, 60L);
            mHandler.postDelayed(this::renderMirrorFrame, 180L);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunning = true;
        mHandler.removeCallbacks(mFrameTick);
        mHandler.post(mFrameTick);
    }

    @Override
    protected void onPause() {
        cancelHeldSideTouchIfNeeded();
        mRunning = false;
        mHandler.removeCallbacks(mFrameTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelHeldSideTouchIfNeeded();
        mRunning = false;
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        if (mTexSurface != null) {
            mTexSurface.release();
            mTexSurface = null;
        }
        if (mSwBmp != null) {
            mSwBmp.recycle();
            mSwBmp = null;
        }
        if (mTouchExec != null) {
            mTouchExec.shutdownNow();
            mTouchExec = null;
        }
        super.onDestroy();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (mTexSurface != null) mTexSurface.release();
        mTexSurface = new Surface(surface);
        mTexW = width;
        mTexH = height;
        renderMirrorFrame();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        mTexW = width;
        mTexH = height;
        renderMirrorFrame();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (mTexSurface != null) {
            mTexSurface.release();
            mTexSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private static final class TouchRippleOverlay extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mX = -1f, mY = -1f;
        private boolean mVisible = false;

        TouchRippleOverlay(android.content.Context ctx) {
            super(ctx);
            mPaint.setColor(0x8838BDF8);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(4f);
        }

        void setTouchPoint(float x, float y, boolean visible) {
            mX = x;
            mY = y;
            mVisible = visible;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mVisible && mX >= 0f && mY >= 0f) {
                canvas.drawCircle(mX, mY, 22f, mPaint);
            }
        }
    }
}
