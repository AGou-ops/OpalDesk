#!/system/bin/sh
# ExtBridge daemon bootstrap script (safe under both shell uid=2000 and root uid=0)

if [ "$(id -u 2>/dev/null)" = "0" ]; then
    SCRIPT_REAL=$(realpath "$0" 2>/dev/null || echo "$0")
    case "$SCRIPT_REAL" in
        *\'*|"")
            echo "ERR_UNSAFE_SCRIPT_PATH: refusing unsafe script path under root" >&2
            exit 20
            ;;
    esac
    if [ ! -f "$SCRIPT_REAL" ] || [ -L "$SCRIPT_REAL" ]; then
        echo "ERR_INVALID_SCRIPT: script path must be a regular file" >&2
        exit 20
    fi
    exec runcon u:r:shell:s0 su 2000 -c "/system/bin/sh '$SCRIPT_REAL'" 2>/dev/null \
        || exec su 2000 -c "/system/bin/sh '$SCRIPT_REAL'"
    echo "ERR_DROP_ROOT: failed to switch from root to shell uid 2000 before touching /data/local/tmp" >&2
    exit 20
fi

APK_PATH=$(pm path com.extbridge.helper 2>/dev/null | head -n1 | cut -d: -f2)

if [ -n "$APK_PATH" ]; then
    TMP_JAR=$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null)
    if [ -z "$TMP_JAR" ] || [ -L "$TMP_JAR" ]; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to create exclusive temp file in /data/local/tmp" >&2
        exit 21
    fi

    if ! unzip -p "$APK_PATH" assets/extbridge.jar > "$TMP_JAR" 2>/dev/null \
        || [ ! -s "$TMP_JAR" ] \
        || [ -L "$TMP_JAR" ] \
        || ! unzip -tq "$TMP_JAR" >/dev/null 2>&1; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_STAGE_JAR: failed to extract valid assets/extbridge.jar from $APK_PATH" >&2
        exit 21
    fi

    if [ -n "$EXTBRIDGE_JAR_SHA256" ]; then
        ACTUAL_SHA=$(sha256sum "$TMP_JAR" 2>/dev/null | cut -d' ' -f1)
        if [ "$ACTUAL_SHA" != "$EXTBRIDGE_JAR_SHA256" ]; then
            rm -f "$TMP_JAR" 2>/dev/null
            echo "ERR_JAR_HASH: expected $EXTBRIDGE_JAR_SHA256, got $ACTUAL_SHA" >&2
            exit 25
        fi
    fi

    chmod 644 "$TMP_JAR" || { rm -f "$TMP_JAR" 2>/dev/null; exit 21; }

    if ! timeout 6 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_UNTRUSTED_APK: extracted daemon rejected com.extbridge.helper signing certificate" >&2
        exit 24
    fi

    (timeout 8 env CLASSPATH="$TMP_JAR" app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true)
    if pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1; then
        (pkill -f '^app_process.*ExtBridge' 2>/dev/null || true)
        sleep 1.0
        (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null || true)
    fi

    rm -f /data/local/tmp/extbridge.jar 2>/dev/null
    if ! mv -f "$TMP_JAR" /data/local/tmp/extbridge.jar \
        || [ -L /data/local/tmp/extbridge.jar ] \
        || [ ! -s /data/local/tmp/extbridge.jar ]; then
        rm -f "$TMP_JAR" 2>/dev/null
        echo "ERR_SWAP_JAR: failed to replace /data/local/tmp/extbridge.jar" >&2
        exit 22
    fi

    appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null
    appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null
    appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null
    appops set com.extbridge.helper 10021 allow 2>/dev/null
    appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
    pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null
    dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1
else
    if [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ] || ! unzip -tq /data/local/tmp/extbridge.jar >/dev/null 2>&1; then
        echo "ERR_NO_JAR: com.extbridge.helper not installed and /data/local/tmp/extbridge.jar missing or invalid" >&2
        exit 23
    fi
    (CLASSPATH=/data/local/tmp/extbridge.jar app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true)
    if pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1; then
        (pkill -f '^app_process.*ExtBridge' 2>/dev/null || true)
        sleep 1.0
        (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null || true)
    fi
fi

[ -L /data/local/tmp/extbridge.log ] && rm -f /data/local/tmp/extbridge.log 2>/dev/null
chown -h 2000:2000 /data/local/tmp/extbridge.jar /data/local/tmp/extbridge.conf /data/local/tmp/extbridge_apps.conf /data/local/tmp/extbridge_healed_v2 2>/dev/null

export CLASSPATH=/data/local/tmp/extbridge.jar
nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &
sleep 1.5
cat /data/local/tmp/extbridge.log
