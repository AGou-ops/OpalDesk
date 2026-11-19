#!/usr/bin/env bash
set -euo pipefail

# Build and package ExtBridgeMac.app (and optionally .dmg) for macOS 14+ (Apple Silicon / Universal).
# Safe to invoke from any working directory (repo root or macos/ExtBridgeMac).
#
# Usage:
#   ./scripts/build-macos-app.sh [--dmg] [--skip-build] [--sign-identity <identity>]
#
# Environment overrides:
#   EXTBRIDGE_MAC_SIGN_IDENTITY  Code-signing identity (defaults to ad-hoc "-")
#   EXTBRIDGE_MAC_VERSION        Bundle version override (defaults to helper/build.gradle.kts versionName)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PKG_DIR="$REPO_ROOT/macos/ExtBridgeMac"
OUT_DIR="$REPO_ROOT/build/out"
APP_NAME="ExtBridgeMac"
BUNDLE_ID="com.extbridge.mac"
APP_BUNDLE="$OUT_DIR/${APP_NAME}.app"

CREATE_DMG=0
SKIP_BUILD=0
SIGN_IDENTITY="${EXTBRIDGE_MAC_SIGN_IDENTITY:--}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dmg)
            CREATE_DMG=1
            shift
            ;;
        --skip-build)
            SKIP_BUILD=1
            shift
            ;;
        --sign-identity)
            SIGN_IDENTITY="${2:-}"
            shift 2
            ;;
        -h|--help)
            sed -n '3,12p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

VERSION="${EXTBRIDGE_MAC_VERSION:-}"
if [[ -z "$VERSION" && -f "$REPO_ROOT/helper/build.gradle.kts" ]]; then
    VERSION="$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$REPO_ROOT/helper/build.gradle.kts" | head -n 1 || true)"
fi
VERSION="${VERSION:-3.5.3}"

echo "==> Packaging ${APP_NAME}.app (v${VERSION})"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    echo "==> Building release binary via SwiftPM..."
    (cd "$PKG_DIR" && swift build -c release)
fi

BIN_DIR="$(cd "$PKG_DIR" && swift build -c release --show-bin-path)"
BINARY_PATH="$BIN_DIR/$APP_NAME"
if [[ ! -x "$BINARY_PATH" ]]; then
    echo "ERROR: Release binary not found at $BINARY_PATH" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"

cp -f "$BINARY_PATH" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
printf 'APPL????' > "$APP_BUNDLE/Contents/PkgInfo"

# Generate crisp native vector AppIcon.icns when iconutil is available.
ICON_KEY_XML=""
if command -v iconutil >/dev/null 2>&1 && command -v swift >/dev/null 2>&1; then
    ICON_TMP="$(mktemp -d "${TMPDIR:-/tmp}/extbridge-icon.XXXXXX")"
    ICONSET_DIR="$ICON_TMP/AppIcon.iconset"
    mkdir -p "$ICONSET_DIR"
    if swift - "$ICONSET_DIR" <<'SWIFT' >/dev/null 2>&1
import AppKit
import CoreGraphics
import Foundation

let outDir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let sizes: [(Int, String)] = [
    (16, "icon_16x16.png"),
    (32, "icon_16x16@2x.png"),
    (32, "icon_32x32.png"),
    (64, "icon_32x32@2x.png"),
    (128, "icon_128x128.png"),
    (256, "icon_128x128@2x.png"),
    (256, "icon_256x256.png"),
    (512, "icon_256x256@2x.png"),
    (512, "icon_512x512.png"),
    (1024, "icon_512x512@2x.png")
]

func renderIcon(px: Int) -> Data? {
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: px,
        pixelsHigh: px,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else { return nil }
    rep.size = NSSize(width: px, height: px)
    guard let ctx = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = ctx
    let cg = ctx.cgContext
    let s = CGFloat(px)

    // macOS squircle background with deep slate-to-indigo studio gradient
    let inset = s * 0.08
    let bgRect = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let bgPath = CGPath(roundedRect: bgRect, cornerWidth: s * 0.21, cornerHeight: s * 0.21, transform: nil)
    cg.saveGState()
    cg.addPath(bgPath)
    cg.clip()

    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let colors = [
        CGColor(red: 0.09, green: 0.14, blue: 0.26, alpha: 1.0),
        CGColor(red: 0.14, green: 0.34, blue: 0.68, alpha: 1.0)
    ] as CFArray
    if let grad = CGGradient(colorsSpace: colorSpace, colors: colors, locations: [0.0, 1.0]) {
        cg.drawLinearGradient(grad, start: CGPoint(x: 0, y: s), end: CGPoint(x: s, y: 0), options: [])
    }
    cg.restoreGState()

    // Display frame silhouette
    let screenRect = CGRect(x: s * 0.19, y: s * 0.34, width: s * 0.56, height: s * 0.39)
    let screenPath = CGPath(roundedRect: screenRect, cornerWidth: s * 0.045, cornerHeight: s * 0.045, transform: nil)
    cg.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.94))
    cg.addPath(screenPath)
    cg.fillPath()

    // Inner display viewport accent
    let innerRect = screenRect.insetBy(dx: s * 0.032, dy: s * 0.032)
    let innerPath = CGPath(roundedRect: innerRect, cornerWidth: s * 0.022, cornerHeight: s * 0.022, transform: nil)
    cg.setFillColor(CGColor(red: 0.13, green: 0.45, blue: 0.88, alpha: 1.0))
    cg.addPath(innerPath)
    cg.fillPath()

    // Display stand
    let standRect = CGRect(x: s * 0.39, y: s * 0.26, width: s * 0.16, height: s * 0.05)
    let standPath = CGPath(roundedRect: standRect, cornerWidth: s * 0.02, cornerHeight: s * 0.02, transform: nil)
    cg.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.88))
    cg.addPath(standPath)
    cg.fillPath()

    // Overlapping phone silhouette (bottom-right)
    let phoneRect = CGRect(x: s * 0.60, y: s * 0.22, width: s * 0.22, height: s * 0.38)
    let phonePath = CGPath(roundedRect: phoneRect, cornerWidth: s * 0.04, cornerHeight: s * 0.04, transform: nil)
    cg.setFillColor(CGColor(red: 0.98, green: 0.99, blue: 1.0, alpha: 1.0))
    cg.addPath(phonePath)
    cg.fillPath()

    let phoneInner = phoneRect.insetBy(dx: s * 0.025, dy: s * 0.03)
    let phoneInnerPath = CGPath(roundedRect: phoneInner, cornerWidth: s * 0.022, cornerHeight: s * 0.022, transform: nil)
    cg.setFillColor(CGColor(red: 0.08, green: 0.68, blue: 0.62, alpha: 1.0))
    cg.addPath(phoneInnerPath)
    cg.fillPath()

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])
}

for (px, filename) in sizes {
    if let png = renderIcon(px: px) {
        try? png.write(to: outDir.appendingPathComponent(filename))
    }
}
SWIFT
    then
        if iconutil -c icns "$ICONSET_DIR" -o "$APP_BUNDLE/Contents/Resources/AppIcon.icns" >/dev/null 2>&1; then
            ICON_KEY_XML="    <key>CFBundleIconFile</key>
    <string>AppIcon</string>"
        fi
    fi
    rm -rf "$ICON_TMP"
fi

cat > "$APP_BUNDLE/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>zh_CN</string>
    <key>CFBundleDisplayName</key>
    <string>${APP_NAME}</string>
    <key>CFBundleExecutable</key>
    <string>${APP_NAME}</string>
    <key>CFBundleIdentifier</key>
    <string>${BUNDLE_ID}</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>${APP_NAME}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
${ICON_KEY_XML}
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.utilities</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_extbridge._tcp</string>
    </array>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSLocalNetworkUsageDescription</key>
    <string>ExtBridgeMac 需要访问局域网以通过 Bonjour 自动发现并低延迟连接同网络下的 Android 手机大屏服务。</string>
    <key>NSSupportsAutomaticGraphicsSwitching</key>
    <true/>
</dict>
</plist>
EOF

plutil -lint "$APP_BUNDLE/Contents/Info.plist"

if command -v codesign >/dev/null 2>&1; then
    echo "==> Code-signing bundle (identity: ${SIGN_IDENTITY})..."
    if [[ "$SIGN_IDENTITY" == "-" ]]; then
        codesign --force --deep --sign - "$APP_BUNDLE"
    else
        codesign --force --deep --options runtime --timestamp --sign "$SIGN_IDENTITY" "$APP_BUNDLE"
    fi
    codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"
fi

echo "==> Created app bundle: $APP_BUNDLE"

if [[ "$CREATE_DMG" -eq 1 ]]; then
    DMG_PATH="$OUT_DIR/${APP_NAME}_v${VERSION}.dmg"
    DMG_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/extbridge-dmg.XXXXXX")"
    cp -R "$APP_BUNDLE" "$DMG_STAGE/"
    ln -s /Applications "$DMG_STAGE/Applications"
    rm -f "$DMG_PATH"
    echo "==> Creating DMG image: $DMG_PATH"
    hdiutil create -volname "$APP_NAME" -srcfolder "$DMG_STAGE" -ov -format UDZO "$DMG_PATH"
    rm -rf "$DMG_STAGE"
    echo "==> Created DMG: $DMG_PATH"
fi
