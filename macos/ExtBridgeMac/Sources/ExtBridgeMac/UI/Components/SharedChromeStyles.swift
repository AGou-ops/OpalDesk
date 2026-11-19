import SwiftUI

extension ActiveTransportKind {
    var badgeColor: Color {
        switch self {
        case .wiredUSB: return .accentColor
        case .wirelessADB: return .orange
        case .wirelessWiFi: return .green
        }
    }
}

/// Compact capsule status pill shared across Device Hub, Display Control Bar, Quick Launcher, and Pairing sheets.
struct StatusBadgeView: View {
    let text: String
    let color: Color
    var font: Font = .caption2.weight(.semibold)
    var horizontalPadding: CGFloat = 7
    var verticalPadding: CGFloat = 2
    var backgroundOpacity: Double = 0.14

    var body: some View {
        Text(text)
            .font(font)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .background(color.opacity(backgroundOpacity), in: Capsule())
            .foregroundStyle(color)
    }
}

/// Translucent floating notification pill displayed over the display canvas.
struct ToastPillView: View {
    let message: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles")
                .font(.caption)
                .foregroundStyle(Color.accentColor)
            Text(message)
                .font(.system(size: 12, weight: .medium))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .floatingCapsuleChrome(shadowRadius: 10, shadowY: 4, shadowOpacity: 0.28)
    }
}

extension View {
    /// Standard macOS native grouped card container chrome.
    func extBridgeCardContainer(cornerRadius: CGFloat = 12, borderOpacity: Double = 0.08) -> some View {
        self
            .background(
                Color(nsColor: .controlBackgroundColor),
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(Color.primary.opacity(borderOpacity), lineWidth: 1)
            )
    }

    /// Translucent floating capsule chrome shared by control bars and toast pills.
    func floatingCapsuleChrome(
        strokeColor: Color = Color.white.opacity(0.16),
        lineWidth: CGFloat = 0.8,
        shadowRadius: CGFloat = 14,
        shadowY: CGFloat = 6,
        shadowOpacity: Double = 0.35
    ) -> some View {
        self
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(strokeColor, lineWidth: lineWidth)
            )
            .shadow(color: Color.black.opacity(shadowOpacity), radius: shadowRadius, y: shadowY)
    }
}
