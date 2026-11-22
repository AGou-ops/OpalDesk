package io.github.lengsukq.opaldesk.daemon.display;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.display.NativeMirrorController.MirrorGeom;
import io.github.lengsukq.opaldesk.daemon.input.InputInjector;
import io.github.lengsukq.opaldesk.daemon.input.LocalInputController;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.SurfaceLayerHelper;

/**
 * Manages the custom 48x48 BLAST cursor overlay in physical mirror mode, Display 0 hardware
 * cursor polling, edge-capture entry/exit transitions, and sidebar touch/captured-mouse routing.
 */
final class MirrorCursorController {
    private static final int EDGE_MARGIN_MIN_PX = NativeMirrorController.EDGE_MARGIN_MIN_PX;
    private static final int CURSOR_SIZE_PX = 48;
    private static final long CURSOR_AUTO_HIDE_MS = 8000L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final HelperClientBridge helperBridge;
    private final InputInjector injector;
    private final LocalInputController localInput;
    private final NativeMirrorController owner;
    private final Handler mainHandler;

    private volatile Object cursorWrapSc, cursorSc;
    private volatile SurfaceLayerHelper.BlastSurface cursorBlast;
    private volatile boolean cursorVisible = false;
    private volatile boolean lastDrawnPress = false;
    private volatile long lastCursorMoveMs = 0L;
    private volatile long lastCursorErrMs = 0L;

    private volatile Runnable cursorPollTask;
    private volatile long lastMirD0SizeCheckMs = 0L;
    private volatile boolean mirMouseCaptured = false;
    private volatile boolean justExitedSideEdge = false;
    private volatile long mirEdgeCooldownUntilMs = 0L;
    private volatile float lastPollD0X = -1f, lastPollD0Y = -1f;
    private volatile boolean hasLastPollD0 = false;
    private long sideTouchDownTime = 0L;

    private final Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (config.shuttingDown || config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR || cursorWrapSc == null) return;
            if (mirMouseCaptured || localInput.isAnyMouseButtonDown()) {
                if (mainHandler != null) mainHandler.postDelayed(this, 3000L);
                return;
            }
            long idle = SystemClock.uptimeMillis() - lastCursorMoveMs;
            if (idle >= CURSOR_AUTO_HIDE_MS - 200L) {
                updateSurface(localInput.getCursorX(), localInput.getCursorY(), false, false);
            } else if (mainHandler != null) {
                mainHandler.postDelayed(this, Math.max(500L, CURSOR_AUTO_HIDE_MS - idle));
            }
        }
    };

    MirrorCursorController(
            AndroidServices services, DaemonConfig config, HelperClientBridge helperBridge,
            InputInjector injector, LocalInputController localInput,
            NativeMirrorController owner, Handler mainHandler) {
        this.services = services;
        this.config = config;
        this.helperBridge = helperBridge;
        this.injector = injector;
        this.localInput = localInput;
        this.owner = owner;
        this.mainHandler = mainHandler;
    }

    void initOverlay(Object rootSc) {
        try {
            cursorWrapSc = SurfaceLayerHelper.buildContainerLayer("ExtMirCursorWrap", rootSc, true);
            cursorSc = SurfaceLayerHelper.buildBlastLayer("ExtMirCursor", cursorWrapSc, false);
            cursorBlast = SurfaceLayerHelper.createBlastSurface("ExtMirCursorBBQ", cursorSc, CURSOR_SIZE_PX, CURSOR_SIZE_PX, 1);
            cursorVisible = false;
            lastDrawnPress = false;
            drawBitmap(false);
            updateSurface(localInput.getCursorX(), localInput.getCursorY(), false, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MIR_CURSOR_INIT_ERR: " + e.getMessage());
        }
    }

    Object[] detachOverlayLayers() {
        stopPoller();
        SurfaceLayerHelper.BlastSurface b = cursorBlast;
        cursorBlast = null;
        if (b != null) b.close();
        Object[] scs = {cursorSc, cursorWrapSc};
        cursorSc = null;
        cursorWrapSc = null;
        return scs;
    }

    void updateSurface(float cx, float cy, boolean pressing, boolean show) {
        Object wrap = cursorWrapSc, cur = cursorSc;
        if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR || cur == null || wrap == null) return;
        try {
            if (show && pressing != lastDrawnPress) drawBitmap(pressing);
            float sc = Math.max(0.8f, Math.min(2.2f, Math.min(config.physW, config.physH) / 1440f));
            try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
                tx.setLayer(wrap, 100).setMatrix(wrap, sc, 0f, 0f, sc)
                        .setPosition(wrap, cx - 6f * sc, cy - 4f * sc).setAlpha(wrap, show ? 1.0f : 0.0f)
                        .setLayer(cur, 0).setWindowCrop(cur, CURSOR_SIZE_PX, CURSOR_SIZE_PX)
                        .setPosition(cur, 0f, 0f).setAlpha(cur, show ? 1.0f : 0.0f);
                if (show) {
                    tx.show(cur).show(wrap);
                    cursorVisible = true;
                    lastCursorMoveMs = SystemClock.uptimeMillis();
                    if (mainHandler != null && !config.shuttingDown) {
                        mainHandler.removeCallbacks(autoHideRunnable);
                        mainHandler.postDelayed(autoHideRunnable, CURSOR_AUTO_HIDE_MS);
                    }
                } else {
                    tx.hide(wrap);
                    cursorVisible = false;
                }
                tx.apply();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            long now = SystemClock.uptimeMillis();
            if (now - lastCursorErrMs > 1000L) {
                lastCursorErrMs = now;
                System.err.println("OPALDESK_MIR_CUR_ERR: " + e.getMessage());
            }
        }
    }

    void startPoller() {
        stopPoller();
        if (mainHandler == null || services.getCursorPositionMethod == null || services.iInputManager == null) return;
        hasLastPollD0 = false;
        cursorPollTask = new Runnable() {
            private long lastActiveMoveMs = SystemClock.uptimeMillis();
            @Override
            public void run() {
                if (config.shuttingDown || config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR
                        || cursorSc == null || cursorPollTask != this) return;
                int delayMs = 250;
                long now = SystemClock.uptimeMillis();
                if (now - lastMirD0SizeCheckMs >= 240L) {
                    lastMirD0SizeCheckMs = now;
                    Point d0 = services.readDisplayRealSize(0);
                    MirrorGeom g0 = owner.getMirrorGeom();
                    if (d0.x > 0 && d0.y > 0 && (d0.x != g0.d0W || d0.y != g0.d0H)) owner.updateMirrorGeometry(true);
                }
                MirrorGeom g = owner.getMirrorGeom();
                if (!mirMouseCaptured && injector.hasExternalCursorDevice()
                        && g.mirW > 0 && g.mirH > 0 && g.d0W > 0 && g.d0H > 0) {
                    long idleMs = now - lastActiveMoveMs;
                    delayMs = (idleMs < 500L) ? 16 : (idleMs < 2000L ? 50 : 150);
                    if (pollDisplay0CursorOnce(g, now)) { lastActiveMoveMs = now; delayMs = 16; }
                }
                if (mainHandler != null && !config.shuttingDown
                        && config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && cursorPollTask == this) {
                    mainHandler.postDelayed(this, delayMs);
                }
            }
        };
        mainHandler.postDelayed(cursorPollTask, 80L);
    }

    void stopPoller() {
        Runnable r = cursorPollTask;
        cursorPollTask = null;
        if (mainHandler != null) {
            if (r != null) mainHandler.removeCallbacks(r);
            mainHandler.removeCallbacks(autoHideRunnable);
        }
        boolean wasCaptured;
        float cx, cy;
        synchronized (localInput.getCursorLock()) {
            wasCaptured = mirMouseCaptured;
            mirMouseCaptured = false;
            localInput.clearDragStateLocked();
            sideTouchDownTime = 0L;
            cx = localInput.getCursorX();
            cy = localInput.getCursorY();
        }
        if (wasCaptured) helperBridge.notifyMirrorEdgeCapture(false, cx, cy);
        hasLastPollD0 = false;
        justExitedSideEdge = false;
        cursorVisible = false;
    }

    void handleSideDirectTouch(int action, float sx, float sy) {
        int sideVdId = owner.getSideVdId();
        if (!Float.isFinite(sx) || !Float.isFinite(sy)) return;
        synchronized (localInput.getCursorLock()) {
            if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR || sideVdId <= 0 || !injector.canInject()) return;
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) sideTouchDownTime = now;
            float cx = clamp(sx, config.physW - 1f), cy = clamp(sy, config.physH - 1f);
            injector.dispatchTouch(action, sideTouchDownTime > 0 ? sideTouchDownTime : now, now, cx, cy, sideVdId);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sideTouchDownTime = 0L;
                if (action == MotionEvent.ACTION_UP) owner.scheduleMirrorSideClickPostChecks();
            }
            if (cursorVisible) updateSurface(cx, cy, false, false);
        }
    }

    boolean handleCapturedMirrorMouse(int actionType, float a1, float a2, float a3) {
        int sideVdId = owner.getSideVdId();
        if (!Float.isFinite(a1) || !Float.isFinite(a2) || !Float.isFinite(a3)) return false;
        synchronized (localInput.getCursorLock()) {
            if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR
                    || sideVdId <= 0 || config.physW <= 0 || config.physH <= 0) {
                mirMouseCaptured = false;
                return false;
            }
            if (actionType == BridgeProtocol.CAPTURED_REASSERT_D0_FOCUS) {
                if (!mirMouseCaptured) return false;
                reassertDisplay0Focus();
                return true;
            }
            if (!mirMouseCaptured && actionType != BridgeProtocol.CAPTURED_EXIT) return false;

            long now = SystemClock.uptimeMillis();
            if (actionType == BridgeProtocol.CAPTURED_EXIT) {
                releaseCapturedButtonsLocked(sideVdId, now);
                mirMouseCaptured = false;
                hasLastPollD0 = false;
                justExitedSideEdge = false;
                mirEdgeCooldownUntilMs = now + 320L;
                updateSurface(localInput.getCursorX(), localInput.getCursorY(), false, false);
                return false;
            }

            float curX = Float.isFinite(localInput.getCursorX()) ? localInput.getCursorX() : config.physW * 0.5f;
            float curY = Float.isFinite(localInput.getCursorY()) ? localInput.getCursorY() : config.physH * 0.5f;
            float nx = (actionType == BridgeProtocol.CAPTURED_MOVE_REL) ? clamp(curX + a1, config.physW - 1f) : curX;
            float ny = (actionType == BridgeProtocol.CAPTURED_MOVE_REL) ? clamp(curY + a2, config.physH - 1f) : curY;

            if (actionType == BridgeProtocol.CAPTURED_MOVE_REL && hasCrossedBackToMain(nx, ny)) {
                releaseCapturedButtonsLocked(sideVdId, now);
                MirrorGeom g = owner.getMirrorGeom();
                float rx = Math.max(g.mirX, Math.min(g.mirX + g.mirW - 1f, nx));
                float ry = Math.max(g.mirY, Math.min(g.mirY + g.mirH - 1f, ny));
                localInput.setCursorLocked(rx, ry);
                mirMouseCaptured = false;
                hasLastPollD0 = false;
                justExitedSideEdge = true;
                mirEdgeCooldownUntilMs = now + 700L;
                updateSurface(rx, ry, false, true);
                return false;
            }

            mirMouseCaptured = true;
            localInput.setCursorLocked(nx, ny);
            dispatchCapturedActionLocked(sideVdId, actionType, a1, a3, nx, ny, now);
            updateSurface(nx, ny, localInput.getMouseButtonsLocked() != 0 || actionType == BridgeProtocol.CAPTURED_LEFT_DOWN, true);
            return true;
        }
    }

    boolean triggerEdgeHoverEntry(float ptX, float ptY) {
        if (!Float.isFinite(ptX) || !Float.isFinite(ptY)) return false;
        synchronized (localInput.getCursorLock()) {
            if (mirMouseCaptured) return true;
            long now = SystemClock.uptimeMillis();
            if (now < mirEdgeCooldownUntilMs || now - localInput.getLastTouchpadMs() < 350L || justExitedSideEdge) return false;
            float[] entry = computeEdgeEntryPoint(ptX, ptY, ptX, ptY, true);
            if (entry == null) return false;
            mirMouseCaptured = true;
            localInput.setCursorLocked(entry[0], entry[1]);
            updateSurface(entry[0], entry[1], false, true);
            return true;
        }
    }

    void onHelperDeathLocked(long now, float cx, float cy) {
        int sideVdId = owner.getSideVdId();
        if (sideTouchDownTime > 0L) {
            long down = sideTouchDownTime;
            sideTouchDownTime = 0L;
            if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && sideVdId > 0) {
                injector.dispatchTouch(MotionEvent.ACTION_CANCEL, down, now, cx, cy, sideVdId);
            }
        }
        if (mirMouseCaptured) {
            mirMouseCaptured = false;
            updateSurface(cx, cy, false, false);
        }
    }

    void reassertDisplay0Focus() {
        if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && mirMouseCaptured) {
            services.moveDisplayToTop(0, "extMirSideCap");
        }
    }

    private void drawBitmap(boolean pressing) {
        Surface surf = (cursorBlast != null) ? cursorBlast.surface : null;
        if (surf == null || !surf.isValid()) return;
        Canvas c = null;
        try {
            c = surf.lockCanvas(null);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (pressing) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setColor(0xAA38BDF8);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(2.6f);
                c.drawCircle(7f, 5f, 9f, ring);
            }
            Path arrow = new Path();
            arrow.moveTo(6f, 4f); arrow.lineTo(6f, 34f); arrow.lineTo(13.5f, 26.5f);
            arrow.lineTo(19f, 38f); arrow.lineTo(23.5f, 36f); arrow.lineTo(18f, 24.5f);
            arrow.lineTo(28f, 24.5f); arrow.close();

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF0F172A);
            fill.setStyle(Paint.Style.FILL);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.8f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            lastDrawnPress = pressing;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_MIR_CURSOR_DRAW_WARN: " + e.getMessage());
        } finally {
            if (c != null) {
                try { surf.unlockCanvasAndPost(c); }
                catch (RuntimeException e) { System.err.println("OPALDESK_MIR_CURSOR_UNLOCK_WARN: " + e.getMessage()); }
            }
        }
    }

    private boolean pollDisplay0CursorOnce(MirrorGeom g, long now) {
        try {
            Object res = services.getCursorPositionMethod.invoke(services.iInputManager, 0);
            if (!(res instanceof PointF)) return false;
            PointF pt = (PointF) res;
            if (!Float.isFinite(pt.x) || !Float.isFinite(pt.y)
                    || pt.x < -1f || pt.y < -1f || pt.x > g.d0W + 1f || pt.y > g.d0H + 1f) return false;
            if (hasLastPollD0 && Math.abs(pt.x - lastPollD0X) <= 0.25f && Math.abs(pt.y - lastPollD0Y) <= 0.25f) return false;

            boolean first = !hasLastPollD0;
            float prevX = lastPollD0X, prevY = lastPollD0Y;
            lastPollD0X = pt.x;
            lastPollD0Y = pt.y;
            hasLastPollD0 = true;
            if (first || now - localInput.getLastTouchpadMs() <= 350L) return true;

            if (justExitedSideEdge && pt.x > 10f && pt.x < g.d0W - 10f && pt.y > 10f && pt.y < g.d0H - 10f) {
                justExitedSideEdge = false;
            }
            float[] entry = (now >= mirEdgeCooldownUntilMs && !justExitedSideEdge)
                    ? computeEdgeEntryPoint(pt.x, pt.y, prevX, prevY, false) : null;
            if (entry != null) {
                synchronized (localInput.getCursorLock()) {
                    mirMouseCaptured = true;
                    localInput.setCursorLocked(entry[0], entry[1]);
                }
                updateSurface(entry[0], entry[1], false, true);
                helperBridge.notifyMirrorEdgeCapture(true, entry[0], entry[1]);
            } else {
                float mx = clamp(g.mirX + clamp(pt.x, g.d0W) * (g.mirW / (float) Math.max(1, g.d0W)), config.physW - 1f);
                float my = clamp(g.mirY + clamp(pt.y, g.d0H) * (g.mirH / (float) Math.max(1, g.d0H)), config.physH - 1f);
                localInput.setCursorPosition(mx, my);
                updateSurface(mx, my, false, true);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private float[] computeEdgeEntryPoint(float ptX, float ptY, float prevX, float prevY, boolean fromHover) {
        MirrorGeom g = owner.getMirrorGeom();
        if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR || config.mirrorSideMode <= 0
                || owner.getSideVdId() <= 0 || g.d0W <= 0 || g.d0H <= 0 || g.mirW <= 0 || g.mirH <= 0) return null;
        int physW = config.physW, physH = config.physH;
        float mx = clamp(g.mirX + ptX * (g.mirW / (float) g.d0W), physW - 1f);
        float my = clamp(g.mirY + ptY * (g.mirH / (float) g.d0H), physH - 1f);
        if (g.mirX >= EDGE_MARGIN_MIN_PX && (fromHover ? ptX <= 4.5f : (prevX > 2.5f && ptX <= 2.5f)))
            return new float[]{Math.max(0f, g.mirX - 10f), my};
        if (physW - (g.mirX + g.mirW) >= EDGE_MARGIN_MIN_PX
                && (fromHover ? ptX >= g.d0W - 5.5f : (prevX < g.d0W - 3.5f && ptX >= g.d0W - 3.5f)))
            return new float[]{Math.min(physW - 1f, g.mirX + g.mirW + 10f), my};
        if (physW - g.mirW < EDGE_MARGIN_MIN_PX) {
            if (g.mirY >= EDGE_MARGIN_MIN_PX && (fromHover ? ptY <= 4.5f : (prevY > 2.5f && ptY <= 2.5f)))
                return new float[]{mx, Math.max(0f, g.mirY - 10f)};
            if (physH - (g.mirY + g.mirH) >= EDGE_MARGIN_MIN_PX
                    && (fromHover ? ptY >= g.d0H - 5.5f : (prevY < g.d0H - 3.5f && ptY >= g.d0H - 3.5f)))
                return new float[]{mx, Math.min(physH - 1f, g.mirY + g.mirH + 10f)};
        }
        return null;
    }

    private boolean hasCrossedBackToMain(float nx, float ny) {
        MirrorGeom g = owner.getMirrorGeom();
        int physW = config.physW, physH = config.physH;
        boolean hasH = (physW - g.mirW >= EDGE_MARGIN_MIN_PX);
        boolean hasV = !hasH && (physH - g.mirH >= EDGE_MARGIN_MIN_PX);
        boolean inH = !hasH || (nx >= g.mirX + (g.mirX >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                && nx <= (g.mirX + g.mirW) - ((physW - g.mirX - g.mirW >= EDGE_MARGIN_MIN_PX) ? 5f : 0f));
        boolean inV = !hasV || (ny >= g.mirY + (g.mirY >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                && ny <= (g.mirY + g.mirH) - ((physH - g.mirY - g.mirH >= EDGE_MARGIN_MIN_PX) ? 5f : 0f));
        return inH && inV;
    }

    private void releaseCapturedButtonsLocked(int sideVdId, long now) {
        int btns = localInput.getMouseButtonsLocked();
        if (btns == 0) return;
        injector.releaseHeldButtons(localInput.getMouseDownTimeLocked(), now,
                localInput.getCursorX(), localInput.getCursorY(), btns, true, sideVdId);
        localInput.setMouseButtonsLocked(0, 0L);
    }

    private void dispatchCapturedActionLocked(int sideVdId, int action, float a1, float a3, float cx, float cy, long now) {
        int btns = localInput.getMouseButtonsLocked();
        long down = localInput.getMouseDownTimeLocked();
        switch (action) {
            case BridgeProtocol.CAPTURED_MOVE_REL:
                if ((btns & MotionEvent.BUTTON_PRIMARY) != 0) injector.dispatchTouch(MotionEvent.ACTION_MOVE, down, now, cx, cy, sideVdId);
                else if (btns != 0) injector.dispatchMouse(MotionEvent.ACTION_MOVE, down, now, cx, cy, btns, 0, 0f, 0f, sideVdId);
                else injector.dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, cx, cy, 0, 0, 0f, 0f, sideVdId);
                break;
            case BridgeProtocol.CAPTURED_LEFT_DOWN:
                if (btns == 0) {
                    localInput.setMouseButtonsLocked(MotionEvent.BUTTON_PRIMARY, now);
                    injector.dispatchTouch(MotionEvent.ACTION_DOWN, now, now, cx, cy, sideVdId);
                    reassertDisplay0Focus();
                }
                break;
            case BridgeProtocol.CAPTURED_LEFT_UP:
                if ((btns & MotionEvent.BUTTON_PRIMARY) != 0) {
                    localInput.setMouseButtonsLocked(btns & ~MotionEvent.BUTTON_PRIMARY, 0L);
                    injector.dispatchTouch(MotionEvent.ACTION_UP, down, now, cx, cy, sideVdId);
                    owner.scheduleMirrorSideClickPostChecks();
                }
                break;
            case BridgeProtocol.CAPTURED_SCROLL:
                injector.dispatchMouse(MotionEvent.ACTION_SCROLL, now, now, cx, cy, btns, 0, a1, a3, sideVdId);
                break;
            case BridgeProtocol.CAPTURED_EXTRA_BTN_DOWN: {
                int btn = Math.round(a1);
                if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                    int next = btns | btn;
                    localInput.setMouseButtonsLocked(next, now);
                    injector.dispatchMouse(MotionEvent.ACTION_DOWN, now, now, cx, cy, next, 0, 0f, 0f, sideVdId);
                    injector.dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, now, now, cx, cy, next, btn, 0f, 0f, sideVdId);
                }
                break;
            }
            case BridgeProtocol.CAPTURED_EXTRA_BTN_UP: {
                int btn = Math.round(a1);
                if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                    int next = btns & ~btn;
                    localInput.setMouseButtonsLocked(next, next == 0 ? 0L : down);
                    injector.dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, down, now, cx, cy, next, btn, 0f, 0f, sideVdId);
                    injector.dispatchMouse(MotionEvent.ACTION_UP, down, now, cx, cy, next, 0, 0f, 0f, sideVdId);
                    injector.dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now + 4L, now + 4L, cx, cy, 0, 0, 0f, 0f, sideVdId);
                }
                break;
            }
            default:
                break;
        }
    }

    private static float clamp(float v, float maxVal) {
        return Math.max(0f, Math.min(Math.max(0f, maxVal), v));
    }
}
