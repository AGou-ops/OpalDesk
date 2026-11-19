import ExtBridgeCore
import SwiftUI

/// Permanent 46pt frosted dark Samsung DeX taskbar pinned cleanly below the display canvas.
/// Hosts the 9-dot App Drawer trigger, running apps strip with context menus, classic 3-button
/// Android navigation, snap-layout & popout triggers, and the One UI system tray + live clock.
struct DeXTaskbarView: View {
    @Bindable var model: AppModel
    @Environment(\.displayScale) private var displayScale

    private static let zhCNLocale = Locale(identifier: "zh_CN")

    var body: some View {
        HStack(spacing: 8) {
            leftCluster
            Spacer(minLength: 8)
            rightCluster
        }
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity)
        .frame(height: DeXTheme.taskbarHeight)
        .background {
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                DeXTheme.taskbarTint
            }
        }
        .overlay(alignment: .top) {
            Rectangle()
                .fill(DeXTheme.taskbarTopDivider)
                .frame(height: 1.0 / max(displayScale, 1.0))
        }
    }

    // MARK: - Left Cluster (App Drawer + Running Apps Strip)

    private var leftCluster: some View {
        HStack(spacing: 8) {
            TaskbarIconButton(
                systemImage: "square.grid.3x3.fill",
                iconSize: 15,
                size: CGSize(width: 36, height: 34),
                isActive: model.activeDeXPanel == .appDrawer,
                helpText: "应用抽屉 (⌘K)",
                accessibilityLabel: "应用抽屉"
            ) {
                model.toggleDeXPanel(.appDrawer)
            }
            .disabled(!model.isAuthenticated)

            taskbarDivider

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(model.taskbarApps) { app in
                        TaskbarRunningAppItem(
                            app: app,
                            focusState: focusState(for: app),
                            onTap: { model.focusOrToggleTaskbarApp(app) },
                            onFocus: { model.focusRemoteApp(app) },
                            onMaximize: { model.launchApp(app, mode: .fullscreen) },
                            onCentered: { model.launchApp(app, mode: .adaptive) },
                            onPlaceLeft: { model.placeAppInSplitSlot(app, slot: 0) },
                            onPlaceRight: { model.placeAppInSplitSlot(app, slot: 1) },
                            onPopout: { model.popoutApp(app) },
                            onClose: { model.closeRemoteApp(app) }
                        )
                    }
                }
                .padding(.vertical, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func focusState(for app: RemoteAppInfo) -> TaskbarRunningAppItem.FocusState {
        if app.pkg == model.deviceState.topAppPackage {
            return .focused
        }
        if model.isTilingActive && model.effectiveTilingSlots.contains(app.pkg) {
            return .tiledSecondary
        }
        return .backgroundRunning
    }

    // MARK: - Right Cluster (Nav + Layout + Popout + System Tray & Clock)

    private var rightCluster: some View {
        HStack(spacing: 6) {
            HStack(spacing: 2) {
                TaskbarIconButton(
                    systemImage: "chevron.backward",
                    iconSize: 13,
                    size: CGSize(width: 32, height: 32),
                    helpText: "返回 (⌘[)",
                    accessibilityLabel: "返回"
                ) {
                    model.sendAndroidBack()
                }
                TaskbarIconButton(
                    systemImage: "circle",
                    iconSize: 13,
                    size: CGSize(width: 32, height: 32),
                    helpText: "桌面 (⌘⇧H)",
                    accessibilityLabel: "主页"
                ) {
                    model.sendAndroidHome()
                }
                TaskbarIconButton(
                    systemImage: "square.on.square",
                    iconSize: 13,
                    size: CGSize(width: 32, height: 32),
                    helpText: "多任务视图",
                    accessibilityLabel: "多任务"
                ) {
                    model.sendAndroidRecents()
                }
            }
            .disabled(!model.isAuthenticated)

            taskbarDivider

            HStack(spacing: 4) {
                TaskbarIconButton(
                    systemImage: model.activeTilingTopology?.systemImage ?? "rectangle.split.2x1",
                    iconSize: 14,
                    size: CGSize(width: 34, height: 32),
                    isActive: model.isTilingActive || model.activeDeXPanel == .layoutPicker,
                    helpText: "窗口布局与分屏 (⌘⇧S)",
                    accessibilityLabel: "窗口布局与分屏"
                ) {
                    model.toggleDeXPanel(.layoutPicker)
                }

                TaskbarIconButton(
                    systemImage: "macwindow.badge.plus",
                    iconSize: 14,
                    size: CGSize(width: 34, height: 32),
                    isActive: !model.popoutManager.controllers.isEmpty,
                    helpText: popoutHelpText,
                    accessibilityLabel: "弹出独立窗口"
                ) {
                    model.popoutCurrentTopApp()
                }
            }
            .disabled(!model.isAuthenticated)

            taskbarDivider

            SystemTrayCapsuleButton(
                isActive: model.activeDeXPanel == .quickSettings,
                isUHID: model.injectStatus == .uhid,
                isAudioMuted: model.isAudioMuted,
                batteryPct: model.deviceState.batteryPct,
                locale: Self.zhCNLocale,
                action: { model.toggleDeXPanel(.quickSettings) }
            )
            .disabled(!model.isAuthenticated)
        }
    }

    private var popoutHelpText: String {
        let label = model.deviceState.topAppLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !label.isEmpty else { return "弹出当前应用为独立窗口 (⌘⇧P)" }
        return "弹出「\(label)」为独立窗口 (⌘⇧P)"
    }

    private var taskbarDivider: some View {
        Rectangle()
            .fill(Color.white.opacity(0.14))
            .frame(width: 1, height: 20)
    }
}

// MARK: - Taskbar Running App Pill

private struct TaskbarRunningAppItem: View {
    enum FocusState {
        case focused
        case tiledSecondary
        case backgroundRunning
    }

    let app: RemoteAppInfo
    let focusState: FocusState
    let onTap: () -> Void
    let onFocus: () -> Void
    let onMaximize: () -> Void
    let onCentered: () -> Void
    let onPlaceLeft: () -> Void
    let onPlaceRight: () -> Void
    let onPopout: () -> Void
    let onClose: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 3) {
                DeXAppIconView(app: app, size: 26, cornerRadius: 7)
                indicatorBar
            }
            .frame(width: 38, height: 36)
            .background(pillBackground, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .strokeBorder(
                        focusState == .focused ? Color.white.opacity(0.14) : Color.clear,
                        lineWidth: 0.7
                    )
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .help(app.displayLabel)
        .accessibilityLabel(app.displayLabel)
        .contextMenu {
            Button("切换到前台", systemImage: "macwindow", action: onFocus)
            Button("最大化铺满", systemImage: "arrow.up.left.and.arrow.down.right", action: onMaximize)
            Button("居中手机窗", systemImage: "iphone", action: onCentered)
            Divider()
            Button("放到左侧分屏", systemImage: "rectangle.lefthalf.filled", action: onPlaceLeft)
            Button("放到右侧分屏", systemImage: "rectangle.righthalf.filled", action: onPlaceRight)
            Button("弹出独立窗口", systemImage: "macwindow.badge.plus", action: onPopout)
            Divider()
            Button("关闭应用", systemImage: "xmark.circle", role: .destructive, action: onClose)
        }
    }

    private var pillBackground: Color {
        if focusState == .focused {
            return isHovered ? Color.white.opacity(0.18) : Color.white.opacity(0.11)
        }
        return isHovered ? DeXTheme.hoverSurface : Color.clear
    }

    private var indicatorBar: some View {
        Capsule()
            .fill(indicatorColor)
            .frame(width: indicatorWidth, height: 2.5)
    }

    private var indicatorWidth: CGFloat {
        switch focusState {
        case .focused: return 14
        case .tiledSecondary: return 10
        case .backgroundRunning: return 4
        }
    }

    private var indicatorColor: Color {
        switch focusState {
        case .focused: return Color.accentColor
        case .tiledSecondary: return Color.white.opacity(0.75)
        case .backgroundRunning: return Color.white.opacity(0.45)
        }
    }
}

// MARK: - Taskbar Icon Button

private struct TaskbarIconButton: View {
    let systemImage: String
    let iconSize: CGFloat
    let size: CGSize
    var isActive: Bool = false
    let helpText: String
    let accessibilityLabel: String
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: .semibold))
                .foregroundStyle(isActive ? Color.accentColor : Color.white.opacity(0.90))
                .frame(width: size.width, height: size.height)
                .background(backgroundColor, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .help(helpText)
        .accessibilityLabel(accessibilityLabel)
    }

    private var backgroundColor: Color {
        if isActive {
            return Color.accentColor.opacity(isHovered ? 0.28 : 0.22)
        }
        return isHovered ? DeXTheme.hoverSurface : Color.clear
    }
}

// MARK: - System Tray & Live Clock Capsule

private struct SystemTrayCapsuleButton: View {
    let isActive: Bool
    let isUHID: Bool
    let isAudioMuted: Bool
    let batteryPct: Int
    let locale: Locale
    let action: () -> Void

    @State private var isHovered: Bool = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isUHID {
                    StatusBadgeView(
                        text: "UHID",
                        color: .green,
                        font: .system(size: 9.5, weight: .bold, design: .rounded),
                        horizontalPadding: 5,
                        verticalPadding: 1.5,
                        backgroundOpacity: 0.22
                    )
                }

                Image(systemName: isAudioMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(isAudioMuted ? Color.orange : Color.white.opacity(0.86))

                if batteryPct >= 0 {
                    HStack(spacing: 3) {
                        Image(systemName: batterySymbol(for: batteryPct))
                            .font(.system(size: 12))
                            .foregroundStyle(batteryPct <= 15 ? Color.red : Color.white.opacity(0.86))
                        Text("\(batteryPct)%")
                            .font(.system(size: 10.5, weight: .medium).monospacedDigit())
                            .foregroundStyle(Color.white.opacity(0.86))
                    }
                }

                TimelineView(.periodic(from: .now, by: 15)) { context in
                    VStack(alignment: .trailing, spacing: 0.5) {
                        Text(formattedTime(context.date))
                            .font(.system(size: 11, weight: .semibold).monospacedDigit())
                            .foregroundStyle(.white)
                        Text(formattedDate(context.date))
                            .font(.system(size: 9.5, weight: .medium))
                            .foregroundStyle(Color.white.opacity(0.68))
                    }
                }
            }
            .padding(.horizontal, 10)
            .frame(height: 36)
            .background(capsuleBackground, in: RoundedRectangle(cornerRadius: DeXTheme.pillCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DeXTheme.pillCornerRadius, style: .continuous)
                    .strokeBorder(isActive ? Color.accentColor.opacity(0.5) : Color.clear, lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .help("快捷设置与设备状态")
        .accessibilityLabel("快捷设置与时钟")
    }

    private var capsuleBackground: Color {
        if isActive {
            return Color.accentColor.opacity(0.22)
        }
        return isHovered ? DeXTheme.hoverSurface : Color.white.opacity(0.05)
    }

    private func formattedTime(_ date: Date) -> String {
        date.formatted(
            Date.FormatStyle(locale: locale)
                .hour(.twoDigits(amPM: .omitted))
                .minute(.twoDigits)
        )
    }

    private func formattedDate(_ date: Date) -> String {
        date.formatted(
            Date.FormatStyle(locale: locale)
                .month(.defaultDigits)
                .day(.defaultDigits)
                .weekday(.abbreviated)
        )
    }

    private func batterySymbol(for pct: Int) -> String {
        switch pct {
        case 85...: return "battery.100"
        case 60..<85: return "battery.75"
        case 35..<60: return "battery.50"
        case 15..<35: return "battery.25"
        default: return "battery.0"
        }
    }
}
