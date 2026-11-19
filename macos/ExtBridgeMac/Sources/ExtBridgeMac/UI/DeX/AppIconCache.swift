import AppKit
import ExtBridgeCore
import SwiftUI
import os

/// Shared visual tokens for the native Samsung DeX for Mac desktop shell.
enum DeXTheme {
    static let taskbarHeight: CGFloat = 46
    static let popoverCornerRadius: CGFloat = 20
    static let tileCornerRadius: CGFloat = 14
    static let pillCornerRadius: CGFloat = 10

    /// Deep slate-obsidian canvas `#0B0E14` matching dark DeX chrome and split seams.
    static let canvasBackground = Color(red: 0.043, green: 0.055, blue: 0.078)
    static let canvasNSColor = NSColor(calibratedRed: 0.043, green: 0.055, blue: 0.078, alpha: 1.0)

    /// Frosted dark taskbar surface `#0F141E` @ 76%.
    static let taskbarTint = Color(red: 0.059, green: 0.078, blue: 0.118).opacity(0.76)

    /// Frosted floating popover surface `#141A26` @ 78%.
    static let popoverTint = Color(red: 0.078, green: 0.102, blue: 0.149).opacity(0.78)

    static let subtleSurface = Color.white.opacity(0.07)
    static let hoverSurface = Color.white.opacity(0.11)
    static let hairlineBorder = Color.white.opacity(0.14)
    static let taskbarTopDivider = Color.white.opacity(0.12)
}

extension View {
    /// Unified frosted dark DeX popover container surface (`20pt` continuous corners, hairline border, soft elevation).
    func dexPopoverSurface() -> some View {
        self
            .background {
                ZStack {
                    RoundedRectangle(cornerRadius: DeXTheme.popoverCornerRadius, style: .continuous)
                        .fill(.regularMaterial)
                    RoundedRectangle(cornerRadius: DeXTheme.popoverCornerRadius, style: .continuous)
                        .fill(DeXTheme.popoverTint)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: DeXTheme.popoverCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DeXTheme.popoverCornerRadius, style: .continuous)
                    .strokeBorder(DeXTheme.hairlineBorder, lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.48), radius: 28, y: 12)
    }
}

/// `@MainActor` bounded LRU cache decoding Android `0x15 APP_LIST` Base64 PNG icons into `NSImage`.
@MainActor
final class AppIconCache {
    static let shared = AppIconCache()

    private let cache: NSCache<NSString, NSImage> = {
        let store = NSCache<NSString, NSImage>()
        store.countLimit = 256
        return store
    }()

    private init() {}

    func image(for pkg: String, base64: String?) -> NSImage? {
        guard let raw = base64?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }

        let cacheKey = "\(pkg)#\(raw.count)#\(raw.prefix(16))#\(raw.suffix(16))" as NSString
        if let cached = cache.object(forKey: cacheKey) {
            return cached
        }

        guard let data = Data(base64Encoded: raw, options: .ignoreUnknownCharacters),
              let decoded = NSImage(data: data),
              decoded.size.width > 0,
              decoded.size.height > 0
        else {
            ExtBridgeLog.app.debug("Failed to decode Base64 icon for package \(pkg, privacy: .public)")
            return nil
        }

        cache.setObject(decoded, forKey: cacheKey)
        return decoded
    }
}

/// Renders an Android app's real Base64 PNG icon with crisp continuous rounding,
/// falling back gracefully to a rich deterministic monogram tile when no icon is available.
struct DeXAppIconView: View {
    let app: RemoteAppInfo
    var size: CGFloat = 32
    var cornerRadius: CGFloat? = nil

    private var resolvedRadius: CGFloat {
        cornerRadius ?? (size * 0.24)
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: resolvedRadius, style: .continuous)
        if let nsImage = AppIconCache.shared.image(for: app.pkg, base64: app.iconBase64) {
            Image(nsImage: nsImage)
                .resizable()
                .interpolation(.high)
                .aspectRatio(contentMode: .fill)
                .frame(width: size, height: size)
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color.white.opacity(0.14), lineWidth: 0.6))
        } else {
            monogramTile(shape: shape)
        }
    }

    private func monogramTile(shape: RoundedRectangle) -> some View {
        let tint = Self.deterministicColor(for: app.pkg)
        let initial = String(app.displayLabel.prefix(1)).uppercased()
        return ZStack {
            shape.fill(
                LinearGradient(
                    colors: [tint.opacity(0.88), tint.opacity(0.56)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            Text(initial.isEmpty ? "A" : initial)
                .font(.system(size: max(10, size * 0.44), weight: .bold, design: .rounded))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .overlay(shape.strokeBorder(Color.white.opacity(0.18), lineWidth: 0.6))
    }

    private static func deterministicColor(for key: String) -> Color {
        let palette: [Color] = [
            Color(red: 0.16, green: 0.48, blue: 0.96),
            Color(red: 0.12, green: 0.64, blue: 0.56),
            Color(red: 0.36, green: 0.38, blue: 0.90),
            Color(red: 0.18, green: 0.66, blue: 0.38),
            Color(red: 0.90, green: 0.48, blue: 0.18),
            Color(red: 0.62, green: 0.34, blue: 0.88),
            Color(red: 0.86, green: 0.28, blue: 0.52),
            Color(red: 0.12, green: 0.58, blue: 0.78)
        ]
        let hash = key.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }
        return palette[abs(hash) % palette.count]
    }
}
