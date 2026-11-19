#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SERIAL=""
BUILD_VARIANT="debug"
SKIP_BUILD=0
TAIL_LOGS=0

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Builds and installs ExtBridge Helper (+ embedded extbridge.jar daemon),
pushes and runs start_extbridge.sh via ADB, and optionally tails dual logs.

Options:
  -s, --serial <id>   Target ADB device serial
      --release       Build & install release variant instead of debug
      --skip-build    Skip Gradle build/install; only push & run start_extbridge.sh
  -l, --logs          Tail both daemon log (/data/local/tmp/extbridge.log) and logcat
  -h, --help          Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --release)
      BUILD_VARIANT="release"
      shift
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    -l|--logs)
      TAIL_LOGS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

detect_jdk21() {
  local candidates=()
  if [[ -n "${JAVA_HOME:-}" ]]; then
    candidates+=("$JAVA_HOME")
  fi
  candidates+=(
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  )
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jh21
    jh21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -n "$jh21" ]] && candidates+=("$jh21")
  fi

  local cand ver major
  for cand in "${candidates[@]}"; do
    if [[ -x "$cand/bin/java" ]]; then
      ver="$("$cand/bin/java" -version 2>&1 | head -n 1)"
      major="$(echo "$ver" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
      if [[ -n "$major" && "$major" -ge 21 ]]; then
        echo "$cand"
        return 0
      fi
    fi
  done
  return 1
}

detect_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  local candidates=(
    "${ANDROID_HOME:-}/platform-tools/adb"
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
    "$HOME/Library/Android/sdk/platform-tools/adb"
    "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
    "/opt/homebrew/bin/adb"
  )
  local cand
  for cand in "${candidates[@]}"; do
    if [[ -n "$cand" && -x "$cand" ]]; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}

ADB_BIN="$(detect_adb || true)"
if [[ -z "$ADB_BIN" ]]; then
  echo "ERROR: adb not found. Install Android platform-tools or set ANDROID_HOME." >&2
  exit 1
fi

ADB_CMD=("$ADB_BIN")
if [[ -n "$SERIAL" ]]; then
  ADB_CMD+=(-s "$SERIAL")
fi

# Verify ADB connectivity
DEVICE_STATE="$("${ADB_CMD[@]}" get-state 2>/dev/null || true)"
if [[ "$DEVICE_STATE" != "device" ]]; then
  echo "ERROR: No ready ADB device found (state='$DEVICE_STATE'). Connected devices:" >&2
  "$ADB_BIN" devices -l >&2
  exit 1
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  JDK21_HOME="$(detect_jdk21 || true)"
  if [[ -z "$JDK21_HOME" ]]; then
    echo "ERROR: JDK 21 not found. Install Android Studio or OpenJDK 21." >&2
    exit 1
  fi
  echo "==> Using JAVA_HOME=$JDK21_HOME"
  INSTALL_TASK=":helper:installDebug"
  if [[ "$BUILD_VARIANT" == "release" ]]; then
    INSTALL_TASK=":helper:installRelease"
  fi
  echo "==> Running ./gradlew $INSTALL_TASK (auto-builds :bridge:dexBridgeJar & embeds extbridge.jar)"
  (
    cd "$ROOT_DIR"
    if [[ -n "$SERIAL" ]]; then
      export ANDROID_SERIAL="$SERIAL"
    fi
    JAVA_HOME="$JDK21_HOME" ./gradlew "$INSTALL_TASK"
  )
fi

echo "==> Pushing start_extbridge.sh to /data/local/tmp/start_extbridge.sh"
"${ADB_CMD[@]}" push "$ROOT_DIR/start_extbridge.sh" /data/local/tmp/start_extbridge.sh >/dev/null
"${ADB_CMD[@]}" shell "chmod 755 /data/local/tmp/start_extbridge.sh"

echo "==> Starting ExtBridge daemon on device..."
"${ADB_CMD[@]}" shell "sh /data/local/tmp/start_extbridge.sh"

echo "==> Checking daemon process status:"
"${ADB_CMD[@]}" shell "pgrep -af 'app_process.*ExtBridge' || true"

if [[ "$TAIL_LOGS" -eq 1 ]]; then
  echo "==> Tailing daemon log & logcat (press Ctrl+C to stop)..."
  PIDS=()
  cleanup() {
    for pid in "${PIDS[@]:-}"; do
      kill "$pid" 2>/dev/null || true
    done
  }
  trap cleanup INT TERM EXIT

  "${ADB_CMD[@]}" shell "tail -F /data/local/tmp/extbridge.log 2>/dev/null" | sed 's/^/[daemon] /' &
  PIDS+=($!)
  "${ADB_CMD[@]}" logcat -v time ExtBridge:V ExtBridgeHelper:V AndroidRuntime:E '*:S' | sed 's/^/[helper] /' &
  PIDS+=($!)

  wait
fi
