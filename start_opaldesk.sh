#!/system/bin/sh
# OpalDesk Universal Compositor daemon bootstrap script (safe under both shell uid=2000 and root uid=0).
#
# Exit codes (contract with AdbBootstrap / caller diagnostics):
#   20 - Unsafe script path or failed to drop root (uid=0) to shell (uid=2000)
#   21 - Failed to stage/extract a valid assets/opaldesk.jar from helper APK
#   22 - Failed to replace /data/local/tmp/opaldesk.jar
#   23 - Helper APK not installed and no valid existing /data/local/tmp/opaldesk.jar
#   24 - Helper APK signing certificate rejected by daemon --verify-helper
#   25 - Staged JAR SHA-256 did not match OPALDESK_JAR_SHA256

EXIT_DROP_ROOT=20
EXIT_STAGE_JAR=21
EXIT_SWAP_JAR=22
EXIT_NO_JAR=23
EXIT_UNTRUSTED_APK=24
EXIT_JAR_HASH=25

HELPER_PKG="io.github.lengsukq.opaldesk"
DAEMON_CLASS="io.github.lengsukq.opaldesk.daemon.OpalDeskDaemon"
DAEMON_JAR="/data/local/tmp/opaldesk.jar"
DAEMON_LOG="/data/local/tmp/opaldesk.log"
DAEMON_PAT_SHORT='^app_process.*OpalDeskDaemon'
DAEMON_PAT_FULL='^/system/bin/app_process.*OpalDeskDaemon'

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
    if [ "${OPALDESK_ROOT_ENHANCE:-0}" = "1" ]; then
        settings put global force_resizable_activities 1 >/dev/null 2>&1 || true
        settings put global enable_non_resizable_multi_window 1 >/dev/null 2>&1 || true
    fi
    exec su 2000 -c "OPALDESK_ROOT_ENHANCE='${OPALDESK_ROOT_ENHANCE:-0}' /system/bin/sh '$SCRIPT_REAL'" 2>/dev/null \
        || exec su shell -c "OPALDESK_ROOT_ENHANCE='${OPALDESK_ROOT_ENHANCE:-0}' /system/bin/sh '$SCRIPT_REAL'" 2>/dev/null \
        || exec su 2000 /system/bin/sh "$SCRIPT_REAL"
    echo "ERR_DROP_ROOT: failed to switch from root to shell uid 2000 before touching /data/local/tmp" >&2
    exit "$EXIT_DROP_ROOT"
fi

is_daemon_alive() {
    pgrep -f "$DAEMON_PAT_SHORT" >/dev/null 2>&1 || pgrep -f "$DAEMON_PAT_FULL" >/dev/null 2>&1
}

stop_existing_daemon() {
    timeout 8 env CLASSPATH="$1" /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" --stop >/dev/null 2>&1 || true
    if is_daemon_alive; then
        pkill -f "$DAEMON_PAT_SHORT" 2>/dev/null || true
        pkill -f "$DAEMON_PAT_FULL" 2>/dev/null || true
        sleep 1
        pkill -KILL -f "$DAEMON_PAT_SHORT" 2>/dev/null || true
        pkill -KILL -f "$DAEMON_PAT_FULL" 2>/dev/null || true
    fi
}

is_pk_zip() {
    [ -f "$1" ] && [ ! -L "$1" ] && [ -s "$1" ] && [ "$(dd if="$1" bs=1 count=2 2>/dev/null)" = "PK" ]
}

APK_PATHS=$(pm path "$HELPER_PKG" 2>/dev/null | sed -n 's/^package://p' | tr -d '\r')

if [ -n "$APK_PATHS" ]; then
    TMP_JAR=$(mktemp /data/local/tmp/opaldesk.XXXXXX 2>/dev/null)
    if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to create exclusive temp file in /data/local/tmp" >&2
        exit "$EXIT_STAGE_JAR"
    fi

    for p in $APK_PATHS; do
        if [ -n "$p" ] && [ -f "$p" ] && [ ! -L "$p" ]; then
            unzip -p "$p" assets/opaldesk.jar > "$TMP_JAR" 2>/dev/null
            if is_pk_zip "$TMP_JAR"; then
                break
            fi
        fi
    done

    if ! is_pk_zip "$TMP_JAR"; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to extract valid assets/opaldesk.jar from $HELPER_PKG" >&2
        exit "$EXIT_STAGE_JAR"
    fi

    if [ -n "$OPALDESK_JAR_SHA256" ]; then
        ACTUAL_SHA=$(sha256sum "$TMP_JAR" 2>/dev/null | cut -d' ' -f1)
        if [ "$ACTUAL_SHA" != "$OPALDESK_JAR_SHA256" ]; then
            rm -f "$TMP_JAR" 2>/dev/null
            echo "ERR_JAR_HASH: expected $OPALDESK_JAR_SHA256, got $ACTUAL_SHA" >&2
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

    # Vendor-neutral AOSP permissions (zero OEM/MIUI hardcodes, zero SYSTEM_ALERT_WINDOW).
    appops set "$HELPER_PKG" POST_NOTIFICATION allow 2>/dev/null
    appops set "$HELPER_PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
    appops set "$HELPER_PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
    pm grant "$HELPER_PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
    pm grant "$HELPER_PKG" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null
    dumpsys deviceidle whitelist "+$HELPER_PKG" >/dev/null 2>&1
else
    if ! is_pk_zip "$DAEMON_JAR"; then
        echo "ERR_NO_JAR: $HELPER_PKG not installed and $DAEMON_JAR missing or invalid" >&2
        exit "$EXIT_NO_JAR"
    fi
    stop_existing_daemon "$DAEMON_JAR"
fi

LOG_DST=/dev/null
rm -f "$DAEMON_LOG" 2>/dev/null
if (umask 022; set -C; : > "$DAEMON_LOG") 2>/dev/null && [ -f "$DAEMON_LOG" ] && [ ! -L "$DAEMON_LOG" ]; then
    chown -h 2000:2000 "$DAEMON_LOG" 2>/dev/null
    LOG_DST="$DAEMON_LOG"
fi
chown -h 2000:2000 "$DAEMON_JAR" /data/local/tmp/opaldesk_v2.conf /data/local/tmp/opaldesk_v2_apps.conf 2>/dev/null

DAEMON_EXTRA_ARG=""
if [ "${OPALDESK_ROOT_ENHANCE:-0}" = "1" ]; then
    DAEMON_EXTRA_ARG="--root-enhance"
fi

export CLASSPATH="$DAEMON_JAR"
CUR_CTX=$(id -Z 2>/dev/null || echo "")
case "$CUR_CTX" in
    *:shell:*)
        (setsid /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" $DAEMON_EXTRA_ARG </dev/null >>"$LOG_DST" 2>&1 &)
        sleep 0.5
        ;;
    *)
        (setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" $DAEMON_EXTRA_ARG </dev/null >>"$LOG_DST" 2>&1 &)
        sleep 0.5
        if ! is_daemon_alive; then
            (setsid /system/bin/app_process /data/local/tmp "$DAEMON_CLASS" $DAEMON_EXTRA_ARG </dev/null >>"$LOG_DST" 2>&1 &)
            sleep 0.5
        fi
        ;;
esac
if ! is_daemon_alive; then
    nohup app_process /data/local/tmp "$DAEMON_CLASS" $DAEMON_EXTRA_ARG >>"$LOG_DST" 2>&1 </dev/null &
fi
sleep 1
if [ "$LOG_DST" != "/dev/null" ]; then
    cat "$LOG_DST"
fi
