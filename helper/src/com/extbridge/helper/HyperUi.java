package com.extbridge.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Shared Xiaomi HyperOS 2.0 visual tokens and anti-aliased vector icon drawables
 * for Android View-based peripheral overlays (BridgeService Dock/Flyout/Split/Sidebar,
 * LauncherActivity desktop bar, and SideControlActivity).
 */
public final class HyperUi {
    private HyperUi() {}

    // HyperOS 2.0 Signature Palette
    public static final int HYPER_BLUE = 0xFF0D84FF;
    public static final int HYPER_BLUE_SOFT = 0x2E0D84FF;
    public static final int HYPER_BLUE_TEXT = 0xFF5EAAFF;
    public static final int HYPER_AMBER = 0xFFFF9F0A;
    public static final int HYPER_RED = 0xFFFF453A;
    public static final int HYPER_GREEN = 0xFF30D158;

    public static final int FLYOUT_BG = 0xF2191B20;
    public static final int FLYOUT_STROKE = 0x26FFFFFF;
    public static final int DOCK_BG = 0xEB181A20;
    public static final int DOCK_STROKE = 0x28FFFFFF;
    public static final int TILE_INACTIVE = 0xFF2A2D35;
    public static final int CHIP_INACTIVE = 0xFF262931;
    public static final int CHIP_ACTIVE_BG = 0x330D84FF;
    public static final int CHIP_ACTIVE_STROKE = 0x770D84FF;

    // Vector Icon Kinds
    public static final int ICON_DEX = 1;
    public static final int ICON_MIRROR = 2;
    public static final int ICON_SPLIT = 3;
    public static final int ICON_PIN = 4;
    public static final int ICON_MOON = 5;
    public static final int ICON_SUN = 6;
    public static final int ICON_ROTATE = 7;
    public static final int ICON_FULLSCREEN = 8;
    public static final int ICON_SLIDERS = 9;
    public static final int ICON_SETTINGS = 10;
    public static final int ICON_SEARCH = 11;
    public static final int ICON_WALLPAPER = 12;
    public static final int ICON_APPS = 13;
    public static final int ICON_SWAP = 14;
    public static final int ICON_CLOSE = 15;
    public static final int ICON_WINDOW = 16;
    public static final int ICON_TOUCHPAD = 17;
    public static final int ICON_SIDEBAR = 18;
    public static final int ICON_KEYBOARD = 19;
    public static final int ICON_MEDIA = 20;
    public static final int ICON_CALENDAR = 21;
    public static final int ICON_CPU = 22;
    public static final int ICON_NET = 23;
    public static final int ICON_STORAGE = 24;
    public static final int ICON_TIMER = 25;
    public static final int ICON_MEMO = 26;
    public static final int ICON_ZOOM = 27;
    public static final int ICON_RATIO = 28;
    public static final int ICON_CHEVRON_LEFT = 29;
    public static final int ICON_CHEVRON_RIGHT = 30;
    public static final int ICON_VOLUME_MUTE = 31;
    public static final int ICON_PLUS = 32;
    public static final int ICON_MINUS = 33;
    public static final int ICON_PLAY = 34;
    public static final int ICON_PAUSE = 35;
    public static final int ICON_PREV = 36;
    public static final int ICON_NEXT = 37;

    public static int dp(Context ctx, float v) {
        return Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics())));
    }

    /** Strips leading Emoji / symbol prefixes from legacy labels cleanly. */
    public static String cleanLabel(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int i = 0;
        int n = raw.length();
        while (i < n) {
            int cp = raw.codePointAt(i);
            boolean isAsciiOrCjk = (cp >= '0' && cp <= '9')
                    || (cp >= 'A' && cp <= 'Z')
                    || (cp >= 'a' && cp <= 'z')
                    || (cp >= 0x4E00 && cp <= 0x9FFF)
                    || cp == '(' || cp == '[' || cp == '【';
            if (isAsciiOrCjk) break;
            i += Character.charCount(cp);
        }
        String trimmed = raw.substring(i).trim();
        return trimmed.isEmpty() ? raw.trim() : trimmed;
    }

    public static Drawable icon(Context ctx, int kind, int color, float sizeDp) {
        int px = dp(ctx, sizeDp);
        VectorIconDrawable d = new VectorIconDrawable(kind, color, px);
        d.setBounds(0, 0, px, px);
        return d;
    }

    public static void setStartIcon(TextView tv, int kind, int color, float sizeDp, float padDp) {
        if (tv == null) return;
        Context ctx = tv.getContext();
        Drawable d = icon(ctx, kind, color, sizeDp);
        tv.setCompoundDrawablesRelative(d, null, null, null);
        tv.setCompoundDrawablePadding(dp(ctx, padDp));
    }

    public static void styleHyperChip(TextView tv, boolean active) {
        if (tv == null) return;
        Context ctx = tv.getContext();
        GradientDrawable bg;
        if (tv.getBackground() instanceof GradientDrawable) {
            bg = (GradientDrawable) tv.getBackground();
        } else {
            bg = new GradientDrawable();
            tv.setBackground(bg);
        }
        bg.setCornerRadius(dp(ctx, 13f));
        if (active) {
            bg.setColor(CHIP_ACTIVE_BG);
            bg.setStroke(dp(ctx, 1f), CHIP_ACTIVE_STROKE);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(CHIP_INACTIVE);
            bg.setStroke(dp(ctx, 1f), 0x1EFFFFFF);
            tv.setTextColor(0xFFD8DEE9);
        }
    }

    private static final class VectorIconDrawable extends Drawable {
        private final int mKind;
        private final int mSize;
        private int mColor;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private final Path mPath = new Path();

        VectorIconDrawable(int kind, int color, int sizePx) {
            mKind = kind;
            mColor = color;
            mSize = sizePx;
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() { return mSize; }

        @Override
        public int getIntrinsicHeight() { return mSize; }

        @Override
        public void setAlpha(int alpha) { mP.setAlpha(alpha); }

        @Override
        public void setColorFilter(ColorFilter colorFilter) { mP.setColorFilter(colorFilter); }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h);
            float sw = Math.max(1.5f, s * 0.105f);

            c.save();
            c.translate(b.left, b.top);
            mP.setColor(mColor);
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeWidth(sw);
            mPath.reset();

            float cx = w * 0.5f;
            float cy = h * 0.5f;

            switch (mKind) {
                case ICON_DEX: {
                    mR.set(w * 0.12f, h * 0.16f, w * 0.88f, h * 0.68f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(cx, h * 0.68f, cx, h * 0.84f, mP);
                    c.drawLine(w * 0.30f, h * 0.84f, w * 0.70f, h * 0.84f, mP);
                    break;
                }
                case ICON_MIRROR: {
                    mR.set(w * 0.24f, h * 0.16f, w * 0.90f, h * 0.64f);
                    c.drawRoundRect(mR, s * 0.11f, s * 0.11f, mP);
                    mR.set(w * 0.10f, h * 0.34f, w * 0.66f, h * 0.84f);
                    c.drawRoundRect(mR, s * 0.11f, s * 0.11f, mP);
                    break;
                }
                case ICON_SPLIT: {
                    mR.set(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f);
                    c.drawRoundRect(mR, s * 0.14f, s * 0.14f, mP);
                    c.drawLine(cx, h * 0.18f, cx, h * 0.82f, mP);
                    break;
                }
                case ICON_PIN: {
                    mR.set(w * 0.30f, h * 0.16f, w * 0.70f, h * 0.54f);
                    c.drawRoundRect(mR, s * 0.10f, s * 0.10f, mP);
                    c.drawLine(w * 0.22f, h * 0.56f, w * 0.78f, h * 0.56f, mP);
                    c.drawLine(cx, h * 0.56f, cx, h * 0.86f, mP);
                    break;
                }
                case ICON_MOON: {
                    mPath.moveTo(w * 0.66f, h * 0.18f);
                    mPath.quadTo(w * 0.26f, h * 0.22f, w * 0.24f, h * 0.56f);
                    mPath.quadTo(w * 0.24f, h * 0.84f, w * 0.62f, h * 0.84f);
                    mPath.quadTo(w * 0.80f, h * 0.82f, w * 0.84f, h * 0.68f);
                    mPath.quadTo(w * 0.50f, h * 0.72f, w * 0.48f, h * 0.42f);
                    mPath.quadTo(w * 0.48f, h * 0.26f, w * 0.66f, h * 0.18f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_SUN: {
                    c.drawCircle(cx, cy, s * 0.20f, mP);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI * 0.25;
                        float x1 = cx + (float) Math.cos(a) * s * 0.30f;
                        float y1 = cy + (float) Math.sin(a) * s * 0.30f;
                        float x2 = cx + (float) Math.cos(a) * s * 0.41f;
                        float y2 = cy + (float) Math.sin(a) * s * 0.41f;
                        c.drawLine(x1, y1, x2, y2, mP);
                    }
                    break;
                }
                case ICON_ROTATE: {
                    mR.set(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f);
                    c.drawArc(mR, -45f, 275f, false, mP);
                    mPath.moveTo(w * 0.62f, h * 0.14f);
                    mPath.lineTo(w * 0.78f, h * 0.26f);
                    mPath.lineTo(w * 0.60f, h * 0.34f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_FULLSCREEN: {
                    float m = w * 0.16f, r = w * 0.84f, t = h * 0.20f, bt = h * 0.80f, l = s * 0.18f;
                    c.drawLine(m, t + l, m, t, mP); c.drawLine(m, t, m + l, t, mP);
                    c.drawLine(r - l, t, r, t, mP); c.drawLine(r, t, r, t + l, mP);
                    c.drawLine(m, bt - l, m, bt, mP); c.drawLine(m, bt, m + l, bt, mP);
                    c.drawLine(r - l, bt, r, bt, mP); c.drawLine(r, bt, r, bt - l, mP);
                    break;
                }
                case ICON_SLIDERS: {
                    c.drawLine(w * 0.16f, h * 0.32f, w * 0.84f, h * 0.32f, mP);
                    c.drawLine(w * 0.16f, h * 0.68f, w * 0.84f, h * 0.68f, mP);
                    mP.setStyle(Paint.Style.FILL);
                    c.drawCircle(w * 0.38f, h * 0.32f, s * 0.11f, mP);
                    c.drawCircle(w * 0.64f, h * 0.68f, s * 0.11f, mP);
                    break;
                }
                case ICON_SETTINGS: {
                    c.drawCircle(cx, cy, s * 0.17f, mP);
                    c.drawCircle(cx, cy, s * 0.36f, mP);
                    break;
                }
                case ICON_SEARCH: {
                    c.drawCircle(w * 0.44f, h * 0.44f, s * 0.26f, mP);
                    c.drawLine(w * 0.63f, h * 0.63f, w * 0.84f, h * 0.84f, mP);
                    break;
                }
                case ICON_WALLPAPER: {
                    mR.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f);
                    c.drawRoundRect(mR, s * 0.13f, s * 0.13f, mP);
                    c.drawCircle(w * 0.36f, h * 0.38f, s * 0.07f, mP);
                    mPath.moveTo(w * 0.18f, h * 0.76f);
                    mPath.lineTo(w * 0.46f, h * 0.50f);
                    mPath.lineTo(w * 0.64f, h * 0.66f);
                    mPath.lineTo(w * 0.76f, h * 0.56f);
                    mPath.lineTo(w * 0.84f, h * 0.76f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_APPS: {
                    float g = s * 0.07f;
                    mR.set(w * 0.16f, h * 0.16f, cx - g, cy - g);
                    c.drawRoundRect(mR, s * 0.08f, s * 0.08f, mP);
                    mR.set(cx + g, h * 0.16f, w * 0.84f, cy - g);
                    c.drawRoundRect(mR, s * 0.08f, s * 0.08f, mP);
                    mR.set(w * 0.16f, cy + g, cx - g, h * 0.84f);
                    c.drawRoundRect(mR, s * 0.08f, s * 0.08f, mP);
                    mR.set(cx + g, cy + g, w * 0.84f, h * 0.84f);
                    c.drawRoundRect(mR, s * 0.08f, s * 0.08f, mP);
                    break;
                }
                case ICON_SWAP: {
                    c.drawLine(w * 0.16f, h * 0.36f, w * 0.82f, h * 0.36f, mP);
                    mPath.moveTo(w * 0.64f, h * 0.20f);
                    mPath.lineTo(w * 0.82f, h * 0.36f);
                    mPath.lineTo(w * 0.64f, h * 0.50f);
                    c.drawLine(w * 0.84f, h * 0.64f, w * 0.18f, h * 0.64f, mP);
                    mPath.moveTo(w * 0.36f, h * 0.50f);
                    mPath.lineTo(w * 0.18f, h * 0.64f);
                    mPath.lineTo(w * 0.36f, h * 0.80f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_CLOSE: {
                    c.drawLine(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f, mP);
                    c.drawLine(w * 0.76f, h * 0.24f, w * 0.24f, h * 0.76f, mP);
                    break;
                }
                case ICON_WINDOW: {
                    mR.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.80f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.38f, mP);
                    break;
                }
                case ICON_TOUCHPAD: {
                    mR.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f);
                    c.drawRoundRect(mR, s * 0.13f, s * 0.13f, mP);
                    c.drawLine(w * 0.14f, h * 0.64f, w * 0.86f, h * 0.64f, mP);
                    c.drawLine(cx, h * 0.64f, cx, h * 0.82f, mP);
                    break;
                }
                case ICON_SIDEBAR: {
                    mR.set(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f);
                    c.drawRoundRect(mR, s * 0.13f, s * 0.13f, mP);
                    c.drawLine(w * 0.62f, h * 0.18f, w * 0.62f, h * 0.82f, mP);
                    break;
                }
                case ICON_KEYBOARD: {
                    mR.set(w * 0.10f, h * 0.24f, w * 0.90f, h * 0.76f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.32f, h * 0.62f, w * 0.68f, h * 0.62f, mP);
                    mP.setStyle(Paint.Style.FILL);
                    c.drawCircle(w * 0.30f, h * 0.42f, sw * 0.65f, mP);
                    c.drawCircle(cx, h * 0.42f, sw * 0.65f, mP);
                    c.drawCircle(w * 0.70f, h * 0.42f, sw * 0.65f, mP);
                    break;
                }
                case ICON_MEDIA: {
                    c.drawCircle(w * 0.34f, h * 0.72f, s * 0.12f, mP);
                    c.drawCircle(w * 0.72f, h * 0.64f, s * 0.12f, mP);
                    c.drawLine(w * 0.46f, h * 0.72f, w * 0.46f, h * 0.24f, mP);
                    c.drawLine(w * 0.84f, h * 0.64f, w * 0.84f, h * 0.18f, mP);
                    c.drawLine(w * 0.46f, h * 0.24f, w * 0.84f, h * 0.18f, mP);
                    break;
                }
                case ICON_CALENDAR: {
                    mR.set(w * 0.14f, h * 0.24f, w * 0.86f, h * 0.84f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.14f, h * 0.42f, w * 0.86f, h * 0.42f, mP);
                    c.drawLine(w * 0.34f, h * 0.14f, w * 0.34f, h * 0.30f, mP);
                    c.drawLine(w * 0.66f, h * 0.14f, w * 0.66f, h * 0.30f, mP);
                    break;
                }
                case ICON_CPU: {
                    mR.set(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f);
                    c.drawRoundRect(mR, s * 0.10f, s * 0.10f, mP);
                    c.drawLine(cx, h * 0.12f, cx, h * 0.24f, mP);
                    c.drawLine(cx, h * 0.76f, cx, h * 0.88f, mP);
                    c.drawLine(w * 0.12f, cy, w * 0.24f, cy, mP);
                    c.drawLine(w * 0.76f, cy, w * 0.88f, cy, mP);
                    break;
                }
                case ICON_NET: {
                    c.drawCircle(cx, cy, s * 0.36f, mP);
                    c.drawLine(w * 0.14f, cy, w * 0.86f, cy, mP);
                    mR.set(w * 0.34f, h * 0.14f, w * 0.66f, h * 0.86f);
                    c.drawOval(mR, mP);
                    break;
                }
                case ICON_STORAGE: {
                    mR.set(w * 0.16f, h * 0.20f, w * 0.84f, h * 0.80f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.16f, h * 0.56f, w * 0.84f, h * 0.56f, mP);
                    mP.setStyle(Paint.Style.FILL);
                    c.drawCircle(w * 0.68f, h * 0.68f, sw * 0.7f, mP);
                    break;
                }
                case ICON_TIMER: {
                    c.drawCircle(cx, h * 0.54f, s * 0.32f, mP);
                    c.drawLine(w * 0.38f, h * 0.14f, w * 0.62f, h * 0.14f, mP);
                    c.drawLine(cx, h * 0.54f, cx, h * 0.36f, mP);
                    c.drawLine(cx, h * 0.54f, w * 0.64f, h * 0.54f, mP);
                    break;
                }
                case ICON_MEMO: {
                    mR.set(w * 0.18f, h * 0.16f, w * 0.82f, h * 0.84f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.32f, h * 0.38f, w * 0.68f, h * 0.38f, mP);
                    c.drawLine(w * 0.32f, h * 0.54f, w * 0.68f, h * 0.54f, mP);
                    c.drawLine(w * 0.32f, h * 0.70f, w * 0.54f, h * 0.70f, mP);
                    break;
                }
                case ICON_ZOOM: {
                    c.drawCircle(w * 0.44f, h * 0.44f, s * 0.26f, mP);
                    c.drawLine(w * 0.63f, h * 0.63f, w * 0.84f, h * 0.84f, mP);
                    c.drawLine(w * 0.34f, h * 0.44f, w * 0.54f, h * 0.44f, mP);
                    c.drawLine(w * 0.44f, h * 0.34f, w * 0.44f, h * 0.54f, mP);
                    break;
                }
                case ICON_RATIO: {
                    mR.set(w * 0.14f, h * 0.22f, w * 0.86f, h * 0.78f);
                    c.drawRoundRect(mR, s * 0.12f, s * 0.12f, mP);
                    c.drawLine(w * 0.28f, h * 0.64f, w * 0.72f, h * 0.36f, mP);
                    break;
                }
                case ICON_CHEVRON_LEFT: {
                    mPath.moveTo(w * 0.62f, h * 0.22f);
                    mPath.lineTo(w * 0.34f, cy);
                    mPath.lineTo(w * 0.62f, h * 0.78f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_CHEVRON_RIGHT: {
                    mPath.moveTo(w * 0.38f, h * 0.22f);
                    mPath.lineTo(w * 0.66f, cy);
                    mPath.lineTo(w * 0.38f, h * 0.78f);
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_VOLUME_MUTE: {
                    mPath.moveTo(w * 0.16f, h * 0.38f);
                    mPath.lineTo(w * 0.32f, h * 0.38f);
                    mPath.lineTo(w * 0.52f, h * 0.22f);
                    mPath.lineTo(w * 0.52f, h * 0.78f);
                    mPath.lineTo(w * 0.32f, h * 0.62f);
                    mPath.lineTo(w * 0.16f, h * 0.62f);
                    mPath.close();
                    c.drawPath(mPath, mP);
                    c.drawLine(w * 0.64f, h * 0.38f, w * 0.84f, h * 0.62f, mP);
                    c.drawLine(w * 0.84f, h * 0.38f, w * 0.64f, h * 0.62f, mP);
                    break;
                }
                case ICON_PLUS: {
                    c.drawLine(w * 0.24f, cy, w * 0.76f, cy, mP);
                    c.drawLine(cx, h * 0.24f, cx, h * 0.76f, mP);
                    break;
                }
                case ICON_MINUS: {
                    c.drawLine(w * 0.24f, cy, w * 0.76f, cy, mP);
                    break;
                }
                case ICON_PLAY: {
                    mP.setStyle(Paint.Style.FILL);
                    mPath.moveTo(w * 0.30f, h * 0.22f);
                    mPath.lineTo(w * 0.76f, cy);
                    mPath.lineTo(w * 0.30f, h * 0.78f);
                    mPath.close();
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_PAUSE: {
                    c.drawLine(w * 0.36f, h * 0.24f, w * 0.36f, h * 0.76f, mP);
                    c.drawLine(w * 0.64f, h * 0.24f, w * 0.64f, h * 0.76f, mP);
                    break;
                }
                case ICON_PREV: {
                    c.drawLine(w * 0.26f, h * 0.26f, w * 0.26f, h * 0.74f, mP);
                    mPath.moveTo(w * 0.74f, h * 0.24f);
                    mPath.lineTo(w * 0.38f, cy);
                    mPath.lineTo(w * 0.74f, h * 0.76f);
                    mPath.close();
                    c.drawPath(mPath, mP);
                    break;
                }
                case ICON_NEXT: {
                    c.drawLine(w * 0.74f, h * 0.26f, w * 0.74f, h * 0.74f, mP);
                    mPath.moveTo(w * 0.26f, h * 0.24f);
                    mPath.lineTo(w * 0.62f, cy);
                    mPath.lineTo(w * 0.26f, h * 0.76f);
                    mPath.close();
                    c.drawPath(mPath, mP);
                    break;
                }
            }
            c.restore();
        }
    }
}
