#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_PATH="$ROOT_DIR/exthelper.p12"
ALIAS="extbridge"
FORCE=0
PASSWORD="${EXTBRIDGE_KEYSTORE_PASS:-}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Generates a permanent (100-year) PKCS#12 release signing keystore at:
  $KEYSTORE_PATH (alias: $ALIAS)

Options:
  -p, --password <pass>  Keystore password (or set EXTBRIDGE_KEYSTORE_PASS)
  -f, --force            Overwrite existing exthelper.p12
  -h, --help             Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--password)
      PASSWORD="${2:-}"
      shift 2
      ;;
    -f|--force)
      FORCE=1
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

find_keytool() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    echo "$JAVA_HOME/bin/keytool"
    return 0
  fi
  local studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
  if [[ -x "$studio_jbr" ]]; then
    echo "$studio_jbr"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jh
    jh="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$jh" && -x "$jh/bin/keytool" ]]; then
      echo "$jh/bin/keytool"
      return 0
    fi
  fi
  if command -v keytool >/dev/null 2>&1; then
    command -v keytool
    return 0
  fi
  return 1
}

KEYTOOL="$(find_keytool || true)"
if [[ -z "$KEYTOOL" ]]; then
  echo "ERROR: keytool not found. Install JDK 17/21 or Android Studio." >&2
  exit 1
fi

if [[ -f "$KEYSTORE_PATH" && "$FORCE" -ne 1 ]]; then
  echo "ERROR: $KEYSTORE_PATH already exists. Use --force to overwrite." >&2
  exit 1
fi

if [[ -z "$PASSWORD" ]]; then
  if [[ -t 0 ]]; then
    read -r -s -p "Enter keystore password (leave empty for default 'extbridge'): " PASSWORD
    echo ""
  fi
  PASSWORD="${PASSWORD:-extbridge}"
fi

if [[ ${#PASSWORD} -lt 6 ]]; then
  echo "ERROR: Keystore password must be at least 6 characters." >&2
  exit 1
fi

if [[ -f "$KEYSTORE_PATH" ]]; then
  rm -f "$KEYSTORE_PATH"
fi

"$KEYTOOL" -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore "$KEYSTORE_PATH" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 36500 \
  -dname "CN=ExtBridge, OU=ExtBridge, O=ExtBridge, L=Local, ST=Local, C=US" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" >/dev/null

chmod 600 "$KEYSTORE_PATH"

if command -v shasum >/dev/null 2>&1; then
  CERT_SHA256="$("$KEYTOOL" -exportcert -keystore "$KEYSTORE_PATH" -storetype PKCS12 -alias "$ALIAS" -storepass "$PASSWORD" 2>/dev/null | shasum -a 256 | awk '{print tolower($1)}')"
elif command -v sha256sum >/dev/null 2>&1; then
  CERT_SHA256="$("$KEYTOOL" -exportcert -keystore "$KEYSTORE_PATH" -storetype PKCS12 -alias "$ALIAS" -storepass "$PASSWORD" 2>/dev/null | sha256sum | awk '{print tolower($1)}')"
else
  CERT_SHA256="$("$KEYTOOL" -exportcert -keystore "$KEYSTORE_PATH" -storetype PKCS12 -alias "$ALIAS" -storepass "$PASSWORD" 2>/dev/null | openssl dgst -sha256 -r | awk '{print tolower($1)}')"
fi

if base64 --help 2>&1 | grep -q -- "-w"; then
  KEYSTORE_B64="$(base64 -w 0 "$KEYSTORE_PATH")"
else
  KEYSTORE_B64="$(base64 < "$KEYSTORE_PATH" | tr -d '\r\n')"
fi

cat <<EOF

========================================================================
ExtBridge Signing Certificate Generated
========================================================================
Keystore Path : $KEYSTORE_PATH
Store Type    : PKCS12
Key Alias     : $ALIAS
SHA-256 (hex) : $CERT_SHA256

Local Gradle builds (:bridge & :helper) will automatically detect
exthelper.p12 and embed this SHA-256 into BridgeBuildConfig.

For GitHub Actions Secrets (Settings -> Secrets and variables -> Actions):
  EXTBRIDGE_KEYSTORE_PASS        = <the password you entered>
  EXTBRIDGE_EXPECTED_CERT_SHA256 = $CERT_SHA256 (optional strict pin)
  EXTBRIDGE_KEYSTORE_BASE64      =
$KEYSTORE_B64
========================================================================
EOF
