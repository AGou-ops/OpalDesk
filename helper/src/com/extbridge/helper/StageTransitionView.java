package com.extbridge.helper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Hardware-accelerated compositor overlay on the external VirtualDisplay that renders
 * 60-120fps HyperOS-style window animations:
 *  - Dock Minimize (live TaskSnapshot morphing into the squircle Dock app icon)
 *  - Dock Restore / App Switch (squircle Dock icon expanding into live window)
 *  - Split-Screen Enter / Slot Assign / Ratio Morph / Slot Crossover Swap / Exit to Fullscreen
 */
public final class StageTransitionView extends View {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_MINIMIZE_TO_ICON = 1;
    public static final int MODE_RESTORE_FROM_ICON = 2;
    public static final int MODE_SWAP_OVER = 3;
    public static final int MODE_SWAP_UNDER = 4;
    public static final int MODE_SPLIT_RECEDE = 5;

    private static final Interpolator INTERP_MINIMIZE = new PathInterpolator(0.30f, 0.0f, 0.12f, 1.0f);
    private static final Interpolator INTERP_SPRING = new PathInterpolator(0.18f, 0.96f, 0.26f, 1.0f);
    private static final Interpolator INTERP_SWAP = new PathInterpolator(0.24f, 0.94f, 0.30f, 1.0f);
    private static final Interpolator INTERP_DISSOLVE = new PathInterpolator(0.33f, 0.0f, 0.67f, 1.0f);

    public static final class CardSpec {
        public Bitmap snapshot;
        public boolean ownSnapshot;
        public Drawable icon;
        public String label;
        public final RectF startRect = new RectF();
        public final RectF endRect = new RectF();
        public float startCornerPx;
        public float endCornerPx;
        public float startAlpha = 1f;
        public float endAlpha = 1f;
        public int mode = MODE_NORMAL;

        public CardSpec setRects(RectF s, RectF e) {
            if (s != null) startRect.set(s);
            if (e != null) endRect.set(e);
            return this;
        }

        public CardSpec setRects(Rect s, Rect e) {
            if (s != null) startRect.set(s);
            if (e != null) endRect.set(e);
            return this;
        }
    }

    private final Paint mCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconPlatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Path mClipPath = new Path();
    private final RectF mCurRect = new RectF();
    private final RectF mShadowRect = new RectF();
    private final RectF mIconPlateRect = new RectF();
    private final Matrix mBmpMatrix = new Matrix();

    private CardSpec mCard0;
    private CardSpec mCard1;
    private boolean mDrawSplitSeam = false;
    private boolean mSplitPortrait = false;
    private float mScrimMaxAlpha = 0f;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private ValueAnimator mMainAnimator;
    private ValueAnimator mDissolveAnimator;
    private int mAnimSeq = 0;
    private float mProgress = 0f;
    private float mMasterAlpha = 1f;
    private boolean mAnimating = false;
    private boolean mWaitBackendBeforeDissolve = false;
    private boolean mBackendReady = true;
    private boolean mMotionDone = false;
    private boolean mBounceFired = false;
    private long mBackendWaitStartMs = 0L;
    private Runnable mOnBounce;
    private Runnable mOnFinished;

    private final Runnable mHoldWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!mAnimating || !mMotionDone) return;
            if (mBackendReady || (SystemClock.uptimeMillis() - mBackendWaitStartMs) >= 240L) {
                startDissolveOut();
            } else {
                mUiHandler.postDelayed(this, 16L);
            }
        }
    };

    public StageTransitionView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setVisibility(GONE);

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(dp(1.2f));

        mShadowPaint.setStyle(Paint.Style.FILL);
        mIconPlatePaint.setStyle(Paint.Style.FILL);
        mSeamPaint.setStyle(Paint.Style.FILL);
        mScrimPaint.setStyle(Paint.Style.FILL);

        mLabelPaint.setColor(0xFFF1F5F9);
        mLabelPaint.setTextSize(dp(13.5f));
        mLabelPaint.setFakeBoldText(true);
        mLabelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public boolean isAnimating() {
        return mAnimating;
    }

    public int getActiveToken() {
        return mAnimSeq;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelActiveAnimation();
        super.onDetachedFromWindow();
    }

    public void cancelActiveAnimation() {
        mAnimSeq++;
        mUiHandler.removeCallbacksAndMessages(null);
        if (mMainAnimator != null) {
            mMainAnimator.removeAllListeners();
            mMainAnimator.cancel();
            mMainAnimator = null;
        }
        if (mDissolveAnimator != null) {
            mDissolveAnimator.removeAllListeners();
            mDissolveAnimator.cancel();
            mDissolveAnimator = null;
        }
        releaseCardResources(mCard0);
        releaseCardResources(mCard1);
        mCard0 = null;
        mCard1 = null;
        mOnBounce = null;
        Runnable prevFinish = mOnFinished;
        mOnFinished = null;
        mAnimating = false;
        setVisibility(GONE);
        if (prevFinish != null) {
            prevFinish.run();
        }
    }

    private static void releaseCardResources(CardSpec c) {
        if (c == null) return;
        Bitmap b = c.snapshot;
        c.snapshot = null;
        if (b != null && c.ownSnapshot && !b.isRecycled()) {
            try { b.recycle(); } catch (Throwable ignored) {}
        }
    }

    public void notifyBackendReady() {
        final int tok = mAnimSeq;
        notifyBackendReady(tok);
    }

    public void notifyBackendReady(final int expectedToken) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mUiHandler.post(() -> notifyBackendReadyOnUi(expectedToken));
        } else {
            notifyBackendReadyOnUi(expectedToken);
        }
    }

    private void notifyBackendReadyOnUi(int expectedToken) {
        if (!mAnimating || expectedToken != mAnimSeq) return;
        mBackendReady = true;
        if (mMotionDone && mWaitBackendBeforeDissolve && mDissolveAnimator == null) {
            mUiHandler.removeCallbacks(mHoldWatchdog);
            // Give SurfaceFlinger 1 VSync (~18ms) to composite the newly resized/resumed task buffer
            mUiHandler.postDelayed(this::startDissolveOut, 20L);
        }
    }

    public void playMinimizeToDock(
            CardSpec card,
            Runnable onLandBounce,
            Runnable onFinished) {
        if (card == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        card.mode = MODE_MINIMIZE_TO_ICON;
        card.startCornerPx = dp(2f);
        card.endCornerPx = dp(9f);
        startChoreography(card, null, 255L, INTERP_MINIMIZE, false, false, false, 0.10f, onLandBounce, onFinished);
    }

    public void playRestoreFromDock(
            CardSpec card,
            boolean targetIsSplit,
            Runnable onFinished) {
        if (card == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        card.mode = MODE_RESTORE_FROM_ICON;
        card.startCornerPx = dp(10f);
        card.endCornerPx = targetIsSplit ? dp(10f) : dp(0f);
        startChoreography(card, null, 265L, INTERP_SPRING, true, false, false, 0.14f, null, onFinished);
    }

    public void playSplitTransition(
            CardSpec card0,
            CardSpec card1,
            boolean portrait,
            long durationMs,
            boolean swapMode,
            Runnable onFinished) {
        Interpolator interp = swapMode ? INTERP_SWAP : INTERP_SPRING;
        startChoreography(card0, card1, durationMs, interp, true, true, portrait, 0.18f, null, onFinished);
    }

    public void playSplitExit(
            CardSpec expandingCard,
            CardSpec recedingCard,
            Runnable onFinished) {
        if (expandingCard != null) {
            expandingCard.mode = MODE_NORMAL;
            expandingCard.startCornerPx = dp(12f);
            expandingCard.endCornerPx = dp(0f);
        }
        if (recedingCard != null) {
            recedingCard.mode = MODE_SPLIT_RECEDE;
            recedingCard.startCornerPx = dp(12f);
            recedingCard.endCornerPx = dp(18f);
        }
        startChoreography(expandingCard, recedingCard, 250L, INTERP_SPRING, true, false, false, 0.12f, null, onFinished);
    }

    private void startChoreography(
            CardSpec c0,
            CardSpec c1,
            long durationMs,
            Interpolator interp,
            boolean waitBackendBeforeDissolve,
            boolean drawSplitSeam,
            boolean splitPortrait,
            float scrimMaxAlpha,
            Runnable onBounce,
            Runnable onFinished) {
        cancelActiveAnimation();
        final int curToken = ++mAnimSeq;
        mCard0 = c0;
        mCard1 = c1;
        mDrawSplitSeam = drawSplitSeam;
        mSplitPortrait = splitPortrait;
        mScrimMaxAlpha = scrimMaxAlpha;
        mWaitBackendBeforeDissolve = waitBackendBeforeDissolve;
        mBackendReady = !waitBackendBeforeDissolve;
        mMotionDone = false;
        mBounceFired = false;
        mProgress = 0f;
        mMasterAlpha = 1f;
        mOnBounce = onBounce;
        mOnFinished = onFinished;
        mAnimating = true;

        setVisibility(VISIBLE);
        invalidate();

        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(durationMs);
        va.setInterpolator(interp);
        va.addUpdateListener(anim -> {
            mProgress = (float) anim.getAnimatedValue();
            if (!mBounceFired && mOnBounce != null && mProgress >= 0.84f) {
                mBounceFired = true;
                Runnable b = mOnBounce;
                mOnBounce = null;
                b.run();
            }
            invalidate();
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mAnimating || mMainAnimator != animation) return;
                mProgress = 1f;
                mMotionDone = true;
                if (!mBounceFired && mOnBounce != null) {
                    mBounceFired = true;
                    Runnable b = mOnBounce;
                    mOnBounce = null;
                    b.run();
                }
                if (!mWaitBackendBeforeDissolve) {
                    finishAndCleanup();
                } else if (mBackendReady) {
                    mUiHandler.postDelayed(() -> {
                        if (mAnimating && mAnimSeq == curToken) startDissolveOut();
                    }, 18L);
                } else {
                    mBackendWaitStartMs = SystemClock.uptimeMillis();
                    mUiHandler.postDelayed(mHoldWatchdog, 16L);
                }
            }
        });
        mMainAnimator = va;
        va.start();
    }

    private void startDissolveOut() {
        if (!mAnimating || mDissolveAnimator != null) return;
        ValueAnimator dis = ValueAnimator.ofFloat(1f, 0f);
        dis.setDuration(85L);
        dis.setInterpolator(INTERP_DISSOLVE);
        dis.addUpdateListener(anim -> {
            mMasterAlpha = (float) anim.getAnimatedValue();
            invalidate();
        });
        dis.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mDissolveAnimator == animation) {
                    finishAndCleanup();
                }
            }
        });
        mDissolveAnimator = dis;
        dis.start();
    }

    private void finishAndCleanup() {
        mUiHandler.removeCallbacksAndMessages(null);
        mMainAnimator = null;
        mDissolveAnimator = null;
        releaseCardResources(mCard0);
        releaseCardResources(mCard1);
        mCard0 = null;
        mCard1 = null;
        mOnBounce = null;
        Runnable fin = mOnFinished;
        mOnFinished = null;
        mAnimating = false;
        setVisibility(GONE);
        if (fin != null) {
            fin.run();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mAnimating) return;
        float p = Math.max(0f, Math.min(1f, mProgress));
        float master = Math.max(0f, Math.min(1f, mMasterAlpha));
        if (master <= 0.005f) return;

        // Subtle ambient backdrop scrim during split/restore transitions so window edges pop cleanly
        if (mScrimMaxAlpha > 0f) {
            float bell = (mCard0 != null && mCard0.mode == MODE_MINIMIZE_TO_ICON)
                    ? (1f - p) * mScrimMaxAlpha
                    : (float) Math.sin(Math.min(1f, p * 1.15f) * Math.PI * 0.5f) * mScrimMaxAlpha * master;
            int sa = Math.round(Math.max(0f, Math.min(1f, bell)) * 255f);
            if (sa > 2) {
                mScrimPaint.setColor((sa << 24) | 0x050811);
                canvas.drawRect(0, 0, getWidth(), getHeight(), mScrimPaint);
            }
        }

        // Draw UNDER card first during slot swap so OVER card crosses cleanly above it
        if (mCard1 != null && mCard0 != null && mCard0.mode == MODE_SWAP_OVER) {
            drawSingleCard(canvas, mCard1, p, master);
            drawSingleCard(canvas, mCard0, p, master);
        } else {
            if (mCard0 != null) drawSingleCard(canvas, mCard0, p, master);
            if (mCard1 != null) drawSingleCard(canvas, mCard1, p, master);
        }

        // Glowing HyperOS split seam between the two split cards
        if (mDrawSplitSeam && mCard0 != null && mCard1 != null) {
            drawSeamHighlight(canvas, p, master);
        }
    }

    private void drawSingleCard(Canvas canvas, CardSpec c, float p, float master) {
        float px = p;
        float py = p;
        if (c.mode == MODE_MINIMIZE_TO_ICON) {
            // Slight organic HyperOS inward curve: X converges slightly ahead of Y into the Dock slot
            px = (float) Math.pow(p, 0.84);
            py = p;
        } else if (c.mode == MODE_RESTORE_FROM_ICON) {
            px = 1f - (float) Math.pow(1f - p, 1.15);
            py = p;
        }

        float l = lerp(c.startRect.left, c.endRect.left, px);
        float t = lerp(c.startRect.top, c.endRect.top, py);
        float r = lerp(c.startRect.right, c.endRect.right, px);
        float b = lerp(c.startRect.bottom, c.endRect.bottom, py);
        mCurRect.set(l, t, r, b);

        float scaleAdjust = 1f;
        if (c.mode == MODE_SWAP_OVER) {
            scaleAdjust = 1f - 0.045f * (float) Math.sin(p * Math.PI);
        } else if (c.mode == MODE_SWAP_UNDER) {
            scaleAdjust = 1f - 0.105f * (float) Math.sin(p * Math.PI);
        }
        if (scaleAdjust != 1f) {
            float cx = mCurRect.centerX();
            float cy = mCurRect.centerY();
            float hw = mCurRect.width() * 0.5f * scaleAdjust;
            float hh = mCurRect.height() * 0.5f * scaleAdjust;
            mCurRect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }

        if (mCurRect.width() <= 1f || mCurRect.height() <= 1f) return;

        float cardAlpha = lerp(c.startAlpha, c.endAlpha, p) * master;
        if (c.mode == MODE_MINIMIZE_TO_ICON) {
            // Hold opaque while shrinking off fullscreen, then melt seamlessly into the real Dock icon
            if (p > 0.76f) {
                cardAlpha *= Math.max(0f, 1f - (p - 0.76f) / 0.24f);
            }
        } else if (c.mode == MODE_RESTORE_FROM_ICON) {
            if (p < 0.18f) {
                cardAlpha *= (0.45f + 0.55f * (p / 0.18f));
            }
        } else if (c.mode == MODE_SPLIT_RECEDE) {
            cardAlpha *= Math.max(0f, 1f - p * 1.18f);
        }
        if (cardAlpha <= 0.01f) return;

        float corner = lerp(c.startCornerPx, c.endCornerPx, p);
        if (c.mode == MODE_SWAP_OVER || c.mode == MODE_SWAP_UNDER) {
            corner = Math.max(corner, dp(14f) * (float) Math.sin(p * Math.PI));
        }
        corner = Math.min(corner, Math.min(mCurRect.width(), mCurRect.height()) * 0.30f);

        // Soft layered elevation shadow around floating card
        float shadowStrength = (c.mode == MODE_SWAP_OVER)
                ? (0.36f * (float) Math.sin(p * Math.PI) + 0.12f)
                : (0.22f * cardAlpha);
        int shAlpha = Math.round(Math.max(0f, Math.min(1f, shadowStrength * cardAlpha)) * 255f);
        if (shAlpha > 4 && corner > dp(1f)) {
            float spread = dp(c.mode == MODE_SWAP_OVER ? 10f : 5f);
            mShadowRect.set(mCurRect.left - spread * 0.5f, mCurRect.top + dp(2f),
                    mCurRect.right + spread * 0.5f, mCurRect.bottom + spread);
            mShadowPaint.setColor((shAlpha << 24));
            canvas.drawRoundRect(mShadowRect, corner + spread * 0.5f, corner + spread * 0.5f, mShadowPaint);
        }

        int save = canvas.save();
        mClipPath.reset();
        mClipPath.addRoundRect(mCurRect, corner, corner, Path.Direction.CW);
        canvas.clipPath(mClipPath);

        int alphaInt = Math.round(cardAlpha * 255f);
        Bitmap bmp = c.snapshot;
        boolean hasBmp = (bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0 && bmp.getHeight() > 0);
        if (hasBmp && Build.VERSION.SDK_INT >= 26
                && bmp.getConfig() == Bitmap.Config.HARDWARE
                && !canvas.isHardwareAccelerated()) {
            hasBmp = false;
        }

        // Compute HyperOS Window <-> App Icon crossfade factor
        float iconMorph = 0f;
        if (c.mode == MODE_MINIMIZE_TO_ICON && c.icon != null) {
            iconMorph = smoothStep(0.36f, 0.80f, p);
        } else if (c.mode == MODE_RESTORE_FROM_ICON && c.icon != null) {
            iconMorph = 1f - smoothStep(0.10f, 0.48f, p);
        }

        if (hasBmp) {
            // Base surface fill behind bitmap
            mCardBgPaint.setShader(null);
            mCardBgPaint.setColor((alphaInt << 24) | 0x101622);
            canvas.drawRect(mCurRect, mCardBgPaint);

            float contentAlpha = cardAlpha * (1f - iconMorph * 0.88f);
            if (contentAlpha > 0.02f) {
                mBmpPaint.setAlpha(Math.round(contentAlpha * 255f));
                configureCenterCropMatrix(bmp.getWidth(), bmp.getHeight(), mCurRect, mBmpMatrix);
                canvas.drawBitmap(bmp, mBmpMatrix, mBmpPaint);
            }
        } else {
            // Sleek HyperOS 2.0 Frosted Glass App Stage Surface (fallback for cold start / FLAG_SECURE)
            mCardBgPaint.setShader(new LinearGradient(
                    mCurRect.left, mCurRect.top, mCurRect.left, mCurRect.bottom,
                    (alphaInt << 24) | 0x182234,
                    (alphaInt << 24) | 0x0E1420,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(mCurRect, mCardBgPaint);
            mCardBgPaint.setShader(null);

            if (c.icon != null && iconMorph < 0.99f) {
                float baseIconSize = Math.min(dp(64f), Math.min(mCurRect.width(), mCurRect.height()) * 0.42f);
                drawCenteredAppIconAndLabel(canvas, c.icon, c.label, mCurRect, baseIconSize,
                        cardAlpha * (1f - iconMorph));
            }
        }

        // Draw HyperOS Window-to-Icon Morph overlay when near the Dock icon
        if (iconMorph > 0.01f && c.icon != null) {
            int morphAlpha = Math.round(cardAlpha * iconMorph * 255f);
            mIconPlatePaint.setColor((morphAlpha << 24) | 0x1E293B);
            canvas.drawRect(mCurRect, mIconPlatePaint);

            float pad = Math.min(mCurRect.width(), mCurRect.height()) * 0.12f;
            int il = Math.round(mCurRect.left + pad);
            int it = Math.round(mCurRect.top + pad);
            int ir = Math.round(mCurRect.right - pad);
            int ib = Math.round(mCurRect.bottom - pad);
            if (ir > il && ib > it) {
                c.icon.setBounds(il, it, ir, ib);
                c.icon.setAlpha(morphAlpha);
                c.icon.draw(canvas);
                c.icon.setAlpha(255);
            }
        }

        canvas.restoreToCount(save);

        // Subtle luminous border during motion
        float borderVisibility = (c.endCornerPx <= dp(1f) && p > 0.8f) ? (1f - (p - 0.8f) / 0.2f) : 1f;
        int strokeAlpha = Math.round(cardAlpha * borderVisibility * 0.42f * 255f);
        if (strokeAlpha > 4) {
            mStrokePaint.setColor((strokeAlpha << 24) | 0x38BDF8);
            canvas.drawRoundRect(mCurRect, corner, corner, mStrokePaint);
        }
    }

    private void drawCenteredAppIconAndLabel(
            Canvas canvas, Drawable icon, String label, RectF rect, float iconSize, float alpha) {
        if (alpha <= 0.02f || iconSize <= 4f) return;
        int a = Math.round(alpha * 255f);
        boolean showLabel = !TextUtils.isEmpty(label) && rect.width() > dp(150f) && rect.height() > dp(130f);
        float cy = showLabel ? (rect.centerY() - dp(12f)) : rect.centerY();
        float cx = rect.centerX();
        float half = iconSize * 0.5f;

        // Subtle squircle glass plate behind centered icon
        float plateHalf = half + dp(8f);
        mIconPlateRect.set(cx - plateHalf, cy - plateHalf, cx + plateHalf, cy + plateHalf);
        mIconPlatePaint.setColor((Math.round(a * 0.35f) << 24) | 0xFFFFFF);
        canvas.drawRoundRect(mIconPlateRect, dp(16f), dp(16f), mIconPlatePaint);

        icon.setBounds(Math.round(cx - half), Math.round(cy - half), Math.round(cx + half), Math.round(cy + half));
        icon.setAlpha(a);
        icon.draw(canvas);
        icon.setAlpha(255);

        if (showLabel) {
            mLabelPaint.setAlpha(Math.round(a * 0.92f));
            CharSequence ellip = TextUtils.ellipsize(label, mLabelPaint, rect.width() - dp(32f), TextUtils.TruncateAt.END);
            canvas.drawText(ellip, 0, ellip.length(), cx, cy + plateHalf + dp(20f), mLabelPaint);
        }
    }

    private void drawSeamHighlight(Canvas canvas, float p, float master) {
        float seamAlpha = (float) Math.sin(Math.min(1f, p * 1.1f) * Math.PI * 0.5f) * master * 0.65f;
        int a = Math.round(Math.max(0f, Math.min(1f, seamAlpha)) * 255f);
        if (a <= 4) return;
        mSeamPaint.setColor((a << 24) | 0x0D84FF);
        if (!mSplitPortrait) {
            float seamX = (lerp(mCard0.startRect.right, mCard0.endRect.right, p)
                    + lerp(mCard1.startRect.left, mCard1.endRect.left, p)) * 0.5f;
            float padY = getHeight() * 0.18f;
            canvas.drawRoundRect(seamX - dp(1.5f), padY, seamX + dp(1.5f), getHeight() - padY,
                    dp(1.5f), dp(1.5f), mSeamPaint);
        } else {
            float seamY = (lerp(mCard0.startRect.bottom, mCard0.endRect.bottom, p)
                    + lerp(mCard1.startRect.top, mCard1.endRect.top, p)) * 0.5f;
            float padX = getWidth() * 0.18f;
            canvas.drawRoundRect(padX, seamY - dp(1.5f), getWidth() - padX, seamY + dp(1.5f),
                    dp(1.5f), dp(1.5f), mSeamPaint);
        }
    }

    private static void configureCenterCropMatrix(int bw, int bh, RectF dst, Matrix out) {
        float dw = dst.width();
        float dh = dst.height();
        float scale;
        float dx = dst.left;
        float dy = dst.top;
        // Preserve aspect ratio cleanly while filling card bounds from top-center (avoids stretching UI headers)
        if (bw * dh > dw * bh) {
            scale = dh / (float) bh;
            dx += (dw - bw * scale) * 0.5f;
        } else {
            scale = dw / (float) bw;
        }
        out.reset();
        out.setScale(scale, scale);
        out.postTranslate(dx, dy);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothStep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x >= edge1 ? 1f : 0f;
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
