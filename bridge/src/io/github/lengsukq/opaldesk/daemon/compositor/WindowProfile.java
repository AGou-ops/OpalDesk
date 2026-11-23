package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

/**
 * Per-window VirtualDisplay density and aspect-ratio profile.
 * Allows mobile portrait apps (e.g. IM, short video) and wide desktop apps (e.g. browsers, IDEs)
 * to run side-by-side with independent DPIs and native fullscreen layouts.
 */
public enum WindowProfile {
    DESKTOP(BridgeProtocol.PROFILE_DESKTOP, 180),
    PORTRAIT(BridgeProtocol.PROFILE_PORTRAIT, 300),
    TABLET(BridgeProtocol.PROFILE_TABLET, 210);

    public final int code;
    public final int recommendedDpi;

    WindowProfile(int code, int recommendedDpi) {
        this.code = code;
        this.recommendedDpi = recommendedDpi;
    }

    public int resolveContentDpi(int contentW, int contentH) {
        return resolveContentDpi(contentW, contentH, 100);
    }

    public int resolveContentDpi(int contentW, int contentH, int dpiScalePct) {
        int shortEdge = Math.min(Math.max(240, contentW), Math.max(240, contentH));
        int baseDpi;
        switch (this) {
            case PORTRAIT:
                // Target ~412dp width so mobile apps render crisp native phone layouts
                baseDpi = Math.round(shortEdge * 160f / 412f);
                break;
            case TABLET:
                // Target ~640dp short edge for tablet 2-pane layouts
                baseDpi = Math.round(shortEdge * 160f / 640f);
                break;
            case DESKTOP:
            default:
                // Crisp desktop density: ~840dp short edge on large/2K+ monitors, scaling smoothly when shrunk.
                // If user drags a window into narrow portrait phone proportions, adopt a readable ~440dp width.
                int targetDp;
                if (contentH > contentW && contentW < 720) {
                    targetDp = 440;
                } else if (contentW >= 1600 && contentH >= 900) {
                    targetDp = 840;
                } else if (contentW >= 1080) {
                    targetDp = 720;
                } else {
                    targetDp = 560;
                }
                baseDpi = Math.round(shortEdge * 160f / targetDp);
                break;
        }
        int clampedPct = Math.max(70, Math.min(140, dpiScalePct <= 0 ? 100 : dpiScalePct));
        return clampDpi(Math.round(baseDpi * (clampedPct / 100f)));
    }

    private static int clampDpi(int dpi) {
        return Math.max(120, Math.min(560, dpi));
    }

    public static WindowProfile fromCode(int code, WindowProfile fallback) {
        switch (code) {
            case BridgeProtocol.PROFILE_DESKTOP: return DESKTOP;
            case BridgeProtocol.PROFILE_PORTRAIT: return PORTRAIT;
            case BridgeProtocol.PROFILE_TABLET: return TABLET;
            default: return fallback != null ? fallback : DESKTOP;
        }
    }

    public static WindowProfile fromName(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toUpperCase()) {
            case "DESKTOP": return DESKTOP;
            case "PORTRAIT": return PORTRAIT;
            case "TABLET": return TABLET;
            default: return null;
        }
    }
}
