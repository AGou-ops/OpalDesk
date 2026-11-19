import AppKit
import ExtBridgeCore
import SwiftUI

/// Floating translucent control bar displayed over the top edge of `DisplayWindowView`,
/// offering telemetry HUD (with UHID badge), Quick Launcher, standalone popout, layout/split menu,
/// resolution/DPI/FPS selector, Android navigation, and disconnect controls.
struct DisplayControlBar: View {
    @Bindable var model: AppModel
    @Bindable var toolbar: HoverToolbarController

    private static let singleWidthPresets: [(title: String, pct: Int)] = [
        ("窄手机 (34%)", 34),
        ("黄金手机 (42% · 推荐)", 42),
        ("大屏手机 (52%)", 52),
        ("平板宽幅 (70%)", 70),
        ("铺满全屏 (100%)", 100)
    ]

    var body: some View {
        HStack(spacing: 8) {
            deviceStatusCluster.padding(.leading, 4)

            if model.preferences.showHUDStats && model.isAuthenticated {
                Divider().frame(height: 16)
                hudMetricsPill
            }

            Divider().frame(height: 16)

            HStack(spacing: 4) {
                actionButton("magnifyingglass", help: "手机应用快启 (⌘K)", active: model.isQuickLauncherPresented) {
                    model.toggleQuickLauncher()
                }
                actionButton("macwindow.badge.plus", label: "独立窗", help: popoutButtonHelpText, active: !model.popoutManager.controllers.isEmpty) {
                    model.popoutCurrentTopApp()
                }
                layoutMenu
            }

            Divider().frame(height: 16)
            displayAndNavigationCluster
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .floatingCapsuleChrome()
    }

    private var deviceStatusCluster: some View {
        HStack(spacing: 6) {
            if let conn = model.activeConnection {
                Image(systemName: conn.transport.systemImage)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(conn.transport.badgeColor)
                Text(conn.deviceName)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                StatusBadgeView(
                    text: conn.transport.shortBadge,
                    color: conn.transport.badgeColor,
                    font: .system(size: 10, weight: .semibold),
                    horizontalPadding: 6,
                    backgroundOpacity: 0.16
                )
                if model.deviceState.batteryPct >= 0 {
                    Text("\(model.deviceState.batteryPct)%")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var displayAndNavigationCluster: some View {
        HStack(spacing: 4) {
            actionButton(
                model.displayMode == .dex ? "macwindow" : "rectangle.on.rectangle",
                label: model.displayMode == .dex ? "DeX" : "镜像",
                help: "切换扩展桌面 (DeX) 与屏幕镜像",
                active: model.displayMode == .dex
            ) { model.toggleDisplayMode() }

            actionButton(model.isBlackoutActive ? "iphone.slash" : "iphone", help: "一键熄灭手机主屏防烧屏与误触", active: model.isBlackoutActive) {
                model.toggleBlackout()
            }
            actionButton(model.isAudioMuted ? "speaker.slash.fill" : "speaker.wave.2.fill", help: model.isAudioMuted ? "取消静音" : "静音音频", active: !model.isAudioMuted) {
                model.toggleAudio()
            }
            actionButton("rotate.right", help: "顺时针旋转 90°") { model.rotateClockwise() }
            resolutionMenu

            Divider().frame(height: 16)
            actionButton("chevron.backward", help: "Android 返回") { model.sendAndroidBack() }
            actionButton("circle", help: "Android 主页") { model.sendAndroidHome() }
            actionButton("square.on.square", help: "Android 多任务") { model.sendAndroidRecents() }

            Divider().frame(height: 16)
            actionButton(toolbar.isPinned ? "pin.fill" : "pin", help: toolbar.isPinned ? "取消固定控制栏" : "固定控制栏", active: toolbar.isPinned) {
                toolbar.togglePinned()
            }
            actionButton("arrow.up.left.and.arrow.down.right", help: "切换全屏") {
                NSApp.keyWindow?.toggleFullScreen(nil)
            }

            Button(role: .destructive) { model.disconnect() } label: {
                Label("断开", systemImage: "xmark.circle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.system(size: 12, weight: .medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.red)
            .help("断开当前连接并返回设备中心")
        }
    }

    private var hudMetricsPill: some View {
        let fps = Int(model.videoMetrics.currentFPS.rounded())
        let rtt = Int(model.rttMs.rounded())
        let mbps = model.videoMetrics.bitrateKbps / 1000.0
        let codecName = model.videoMetrics.codec?.displayName ?? model.preferences.preferredCodec.displayName

        return HStack(spacing: 7) {
            Text("\(fps) fps")
            Text("·").foregroundStyle(.tertiary)
            Text("\(rtt) ms")
            Text("·").foregroundStyle(.tertiary)
            Text(String(format: "%.1f Mbps", mbps))
            Text("·").foregroundStyle(.tertiary)
            Text("\(model.currentVideoWidth)×\(model.currentVideoHeight) \(codecName)")

            if model.injectStatus == .uhid {
                StatusBadgeView(
                    text: "UHID",
                    color: .green,
                    font: .system(size: 10, weight: .bold, design: .rounded),
                    horizontalPadding: 6,
                    verticalPadding: 1.5,
                    backgroundOpacity: 0.22
                )
                .help("UHID 虚拟键鼠外设已激活，原生绕过系统注入限制")
            }
        }
        .font(.system(size: 11, weight: .medium, design: .monospaced))
        .foregroundStyle(.secondary)
    }

    private var layoutMenu: some View {
        let currentLayoutMode = model.deviceState.layoutMode
        return Menu {
            Section("单应用自适应宽幅") {
                checkmarkMenuItem(
                    "单应用默认黄金尺寸 (\(model.preferences.defaultSingleWidthDp)dp)",
                    isSelected: model.deviceState.adaptiveSingleEnabled
                ) {
                    model.setAdaptiveSingle(
                        enabled: !model.deviceState.adaptiveSingleEnabled,
                        defaultWidthDp: model.preferences.defaultSingleWidthDp
                    )
                }
            }
            Section("5 档单应用宽幅快选") {
                ForEach(Self.singleWidthPresets, id: \.pct) { item in
                    Button(item.title) { model.setSingleWidthPreset(widthPct: item.pct, remember: true) }
                }
            }
            Section("分屏 / 多窗布局") {
                ForEach(ExtCastTilingTopology.allCases) { topology in
                    let isSelected = Self.layoutMode(for: topology).map { currentLayoutMode == $0 } ?? false
                    checkmarkMenuItem(topology.displayName, isSelected: isSelected) {
                        model.setTilingTopology(topology)
                    }
                }
            }
            Divider()
            Button { model.reloadCurrentAppDPI() } label: {
                Label("重载当前 App DPI", systemImage: "textformat.size")
            }
            .disabled(model.deviceState.topAppPackage.isEmpty)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "rectangle.split.2x2")
                Text("布局").font(.system(size: 11, weight: .medium))
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("单应用黄金尺寸、5 档宽幅与一键分屏布局")
    }

    private var resolutionMenu: some View {
        Menu {
            Section("分辨率预设") {
                ForEach(DisplayResolutionPreset.allCases) { preset in
                    checkmarkMenuItem(preset.title, isSelected: model.selectedPreset == preset) {
                        model.selectResolutionPreset(preset)
                    }
                }
            }
            Section("显示密度 (DPI)") {
                ForEach(DisplayPresets.dpiOptions, id: \.self) { dpi in
                    checkmarkMenuItem("\(dpi) DPI", isSelected: model.selectedDPI == dpi) {
                        model.selectDPI(dpi)
                    }
                }
            }
            Section("刷新率") {
                ForEach(DisplayPresets.fpsOptions, id: \.self) { fps in
                    checkmarkMenuItem("\(fps) fps", isSelected: model.preferences.targetFPS == fps) {
                        model.applyTargetFPS(fps)
                    }
                }
            }
            Divider()
            Button("强制刷新关键帧 (IDR)") { model.requestIDR() }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "aspectratio")
                Text(model.selectedPreset == .matchWindow ? "自适应" : "\(model.currentVideoWidth)×\(model.currentVideoHeight)")
                    .font(.system(size: 11, weight: .medium))
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .help("切换分辨率预设、DPI 与目标刷新率")
    }

    private var popoutButtonHelpText: String {
        let pkg = model.deviceState.topAppPackage
        guard !pkg.isEmpty else { return "选择手机应用并弹出为主窗口外的独立小窗 (⌘K)" }
        let label = model.deviceState.topAppLabel.isEmpty ? pkg : model.deviceState.topAppLabel
        return "将当前焦点应用「\(label)」弹出为主窗口外的独立小窗"
    }

    private func checkmarkMenuItem(_ title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                if isSelected { Image(systemName: "checkmark") }
            }
        }
    }

    private func actionButton(
        _ icon: String,
        label: String? = nil,
        help: String,
        active: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 12, weight: .medium))
                if let label { Text(label).font(.system(size: 11, weight: .medium)) }
            }
            .padding(.horizontal, label == nil ? 6 : 8)
            .padding(.vertical, 4)
            .background(active ? Color.accentColor.opacity(0.24) : Color.clear, in: Capsule())
            .foregroundStyle(active ? Color.accentColor : Color.primary)
        }
        .buttonStyle(.plain)
        .help(help)
        .accessibilityLabel(label ?? help)
    }

    private static func layoutMode(for topology: ExtCastTilingTopology) -> ExtCastLayoutMode? {
        switch topology {
        case .split2H: return .split2H
        case .split2V: return .split2V
        case .split3Master: return .split3Master
        case .split3Cols: return .split3Cols
        case .grid2x2: return .grid2x2
        case .exit: return nil
        }
    }
}
