#!/system/bin/sh
# ExtBridge daemon bootstrap script (safe under both shell uid=2000 and root uid=0).
#
# Exit codes (contract with AdbBootstrap / caller diagnostics):
#   20 - Unsafe script path or failed to drop root (uid=0) to shell (uid=2000)
#   21 - Failed to stage/extract a valid assets/extbridge.jar from helper APK
#   22 - Failed to replace /data/local/tmp/extbridge.jar
#   23 - Helper APK not installed and no valid existing /data/local/tmp/extbridge.jar
#   24 - Helper APK signing certificate rejected by daemon --verify-helper
#   25 - Staged JAR SHA-256 did not match EXTBRIDGE_JAR_SHA256

EXIT_DROP_ROOT=20
EXIT_STAGE_JAR=21
EXIT_SWAP_JAR=22
EXIT_NO_JAR=23
EXIT_UNTRUSTED_APK=24
EXIT_JAR_HASH=25

HELPER_PKG="com.extbridge.helper"
DAEMON_CLASS="com.extbridge.daemon.ExtBridge"
DAEMON_JAR="/data/local/tmp/extbridge.jar"
DAEMON_LOG="/data/local/tmp/extbridge.log"
DAEMON_PATTERN='^(/.*/)?app_process.*ExtBridge'
# MIUI/HyperOS hidden AppOps code OP_BACKGROUND_START_ACTIVITY (allows launching external display activities from background).
MIUI_OP_BACKGROUND_START=10021

if [ "$(id -u 2>/dev/null)" = "0" ]; then
    SCRIPT_REAL=$(realpath "$0" 2>/dev/null || echo "$0")
    case "$SCRIPT_REAL" in
        *\'*|"")
            echo "ERR_UNSAFE_SCRIPT_PATH: refusing unsafe script path under root" >&2
            exit "$EXIT_DROP_ROOT"
            ;;
    esac
    if [ ! -f "$SCRIPT_REAL" ] || [ -L "$SCRIPT_REAL" ]; then
        echo "ERR_INVALID_SCRIPT: script path must be a regular file" >&2
        exit "$EXIT_DROP_ROOT"
    fi
    exec su 2000 -c "/system/bin/sh '$SCRIPT_REAL'" 2>/dev/null \
        || exec su shell -c "/system/bin/sh '$SCRIPT_REAL'" 2>/dev/null \
        || exec su 2000 /system/bin/sh "$SCRIPT_REAL"
    echo "ERR_DROP_ROOT: failed to switch from root to shell uid 2000 before touching /data/local/tmp" >&2
    exit "$EXIT_DROP_ROOT"
fi

stop_existing_daemon() {
    # Request a graceful shutdown via IPC first so the daemon can release virtual displays cleanly.
    timeout 8 env CLASSPATH="$1" /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" --stop >/dev/null 2>&1 || true
    if pgrep -f "$DAEMON_PATTERN" >/dev/null 2>&1; then
        pkill -f "$DAEMON_PATTERN" 2>/dev/null || true
        sleep 1
        pkill -KILL -f "$DAEMON_PATTERN" 2>/dev/null || true
    fi
}

APK_PATH=$(pm path "$HELPER_PKG" 2>/dev/null | head -n1 | cut -d: -f2)

if [ -n "$APK_PATH" ]; then
    TMP_JAR=$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null)
    if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to create exclusive temp file in /data/local/tmp" >&2
        exit "$EXIT_STAGE_JAR"
    fi

    if ! unzip -p "$APK_PATH" assets/extbridge.jar > "$TMP_JAR" 2>/dev/null \
        || [ ! -s "$TMP_JAR" ] \
        || [ -L "$TMP_JAR" ] \
        || ! unzip -tq "$TMP_JAR" >/dev/null 2>&1; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to extract valid assets/extbridge.jar from $APK_PATH" >&2
        exit "$EXIT_STAGE_JAR"
    fi

    if [ -n "$EXTBRIDGE_JAR_SHA256" ]; then
        ACTUAL_SHA=$(sha256sum "$TMP_JAR" 2>/dev/null | cut -d' ' -f1)
        if [ "$ACTUAL_SHA" != "$EXTBRIDGE_JAR_SHA256" ]; then
            rm -f "$TMP_JAR" 2>/dev/null
            echo "ERR_JAR_HASH: expected $EXTBRIDGE_JAR_SHA256, got $ACTUAL_SHA" >&2
            exit "$EXIT_JAR_HASH"
        fi
    fi

    chmod 644 "$TMP_JAR" || { rm -f "$TMP_JAR" 2>/dev/null; exit "$EXIT_STAGE_JAR"; }

    if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp "$DAEMON_CLASS" --verify-helper >/dev/null 2>&1; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_UNTRUSTED_APK: extracted daemon rejected $HELPER_PKG signing certificate" >&2
        exit "$EXIT_UNTRUSTED_APK"
    fi

    stop_existing_daemon "$TMP_JAR"

    rm -f "$DAEMON_JAR" 2>/dev/null
    if ! mv -f "$TMP_JAR" "$DAEMON_JAR" \
        || [ -L "$DAEMON_JAR" ] \
        || [ ! -s "$DAEMON_JAR" ]; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_SWAP_JAR: failed to replace $DAEMON_JAR" >&2
        exit "$EXIT_SWAP_JAR"
    fi

    # Best-effort permission/AppOps grants; individual ops may not exist on all Android versions or OEMs.
    appops set "$HELPER_PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null
    appops set "$HELPER_PKG" POST_NOTIFICATION allow 2>/dev/null
    appops set "$HELPER_PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
    appops set "$HELPER_PKG" "$MIUI_OP_BACKGROUND_START" allow 2>/dev/null
    appops set "$HELPER_PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
    pm grant "$HELPER_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
    dumpsys deviceidle whitelist "+$HELPER_PKG" >/dev/null 2>&1
else
    if [ -L "$DAEMON_JAR" ] || [ ! -s "$DAEMON_JAR" ] || ! unzip -tq "$DAEMON_JAR" >/dev/null 2>&1; then
        echo "ERR_NO_JAR: $HELPER_PKG not installed and $DAEMON_JAR missing or invalid" >&2
        exit "$EXIT_NO_JAR"
    fi
    stop_existing_daemon "$DAEMON_JAR"
fi

LOG_DST=/dev/null
rm -f "$DAEMON_LOG" 2>/dev/null
# Create the log file with noclobber (set -C) to defend against /data/local/tmp symlink attacks.
if (umask 022; set -C; : > "$DAEMON_LOG") 2>/dev/null && [ -f "$DAEMON_LOG" ] && [ ! -L "$DAEMON_LOG" ]; then
    chown -h 2000:2000 "$DAEMON_LOG" 2>/dev/null
    LOG_DST="$DAEMON_LOG"
fi
chown -h 2000:2000 "$DAEMON_JAR" /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf /data/local/tmp/extbridge_healed_v2 2>/dev/null

export CLASSPATH="$DAEMON_JAR"
CUR_CTX=$(id -Z 2>/dev/null || echo "")
case "$CUR_CTX" in
    *:shell:*)
        (setsid /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" </dev/null >>"$LOG_DST" 2>&1 &)
        sleep 0.5
        ;;
    *)
        # Prefer the standard shell SELinux domain when invoked from an unconfined/root-transitioned context.
        (setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" </dev/null >>"$LOG_DST" 2>&1 &)
        sleep 0.5
        if ! pgrep -f "$DAEMON_PATTERN" >/dev/null 2>&1; then
            (setsid /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
        fi
        ;;
esac
if ! pgrep -f "$DAEMON_PATTERN" >/dev/null 2>&1; then
    nohup app_process /data/local/tmp "$DAEMON_CLASS" >>"$LOG_DST" 2>&1 </dev/null &
fi
sleep 1
if [ "$LOG_DST" != "/dev/null" ]; then
    cat "$LOG_DST"
fi
