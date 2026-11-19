import AppKit
import ExtBridgeCore
import SwiftUI

/// Immersive Android display canvas with hover-revealed macOS translucent control bar,
/// live telemetry HUD (with UHID badge), standalone popout trigger, split/layout menu,
/// ⌘K Quick Launcher overlay, injectStatus smart hint banner, and file drag-drop feedback.
public struct DisplayWindowView: View {
    @Bindable public var model: AppModel

    @State private var isTopBarRevealed: Bool = true
    @State private var isToolbarPinned: Bool = false
    @State private var isHoveringToolbar: Bool = false
    @State private var isDragOverCanvas: Bool = false
    @State private var isInjectWarningDismissed: Bool = false
    @State private var autoHideTask: Task<Void, Never>?

    private static let singleWidthPresets: [(title: String, pct: Int)] = [
        ("窄手机 (34%)", 34),
        ("黄金手机 (42% · 推荐)", 42),
        ("大屏手机 (52%)", 52),
        ("平板宽幅 (70%)", 70),
        ("铺满全屏 (100%)", 100)
    ]

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()

            InteractiveDisplayNSView(
                videoRenderer: model.videoRenderer,
                videoWidth: model.currentVideoWidth,
                videoHeight: model.currentVideoHeight,
                mapCommandToCtrl: model.preferences.mapCommandToCtrl,
                onMouse: { model.sendMouse($0) },
                onTouch: { model.sendTouch($0) },
                onKey: { model.sendKey($0) },
                onCommitText: { model.sendCommittedText($0) },
                onFilesDropped: { model.sendFiles($0) },
                onDragHoverChange: { hovering in
                    withAnimation(.easeInOut(duration: 0.15)) {
                        isDragOverCanvas = hovering
                    }
                },
                onResize: { size, scale in
                    model.handleCanvasResize(viewSize: size, backingScale: scale)
                },
                onTopEdgeHover: { nearTop in
                    if nearTop {
                        revealToolbarTemporarily(hideAfter: 1.8)
                    } else if !isHoveringToolbar && !isToolbarPinned {
                        scheduleToolbarHide(delaySeconds: 0.4)
                    }
                },
                onCursorClearedTopZone: {
                    if !isToolbarPinned && (isTopBarRevealed || isHoveringToolbar) {
                        isHoveringToolbar = false
                        scheduleToolbarHide(delaySeconds: 0.28)
                    }
                },
                onCanvasInteract: {
                    if !isToolbarPinned && (isTopBarRevealed || isHoveringToolbar) {
                        autoHideTask?.cancel()
                        isHoveringToolbar = false
                        isTopBarRevealed = false
                    }
                },
                onQuickLauncher: {
                    model.toggleQuickLauncher()
                },
                onThreeFingerSwipe: { direction in
                    model.handleThreeFingerSwipe(direction)
                }
            )
            .ignoresSafeArea()

            if !model.isAuthenticated || model.videoMetrics.totalFramesDecoded == 0 {
                connectionOverlay
            }

            if isDragOverCanvas {
                fileDropOverlay
            }

            VStack {
                if shouldShowToolbar {
                    floatingControlBar
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .onHover { hovering in
                            isHoveringToolbar = hovering
                            if hovering {
                                autoHideTask?.cancel()
                            } else if !isToolbarPinned {
                                scheduleToolbarHide(delaySeconds: 0.4)
                            }
                        }
                }
                Spacer()

                VStack(spacing: 10) {
                    if let toast = model.toastNotice {
                        toastPill(toast)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if model.isAuthenticated && model.injectStatus == .denied && !isInjectWarningDismissed {
                        injectDeniedSmartBanner
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    if !model.activeTransfers.isEmpty {
                        HStack {
                            Spacer()
                            transferToastStack
                        }
                    }
                }
                .padding(16)
            }

            if model.isQuickLauncherPresented {
                QuickLauncherView(model: model)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
        }
        .preferredColorScheme(.dark)
        .background(WindowChromeConfigurator(isImmersiveCanvas: true))
        .animation(.easeOut(duration: 0.18), value: shouldShowToolbar)
        .animation(.easeOut(duration: 0.18), value: model.isQuickLauncherPresented)
        .animation(.easeOut(duration: 0.2), value: model.toastNotice)
        .animation(.easeOut(duration: 0.2), value: model.injectStatus)
        .onChange(of: model.injectStatus) { _, newStatus in
            if newStatus != .denied {
                isInjectWarningDismissed = false
            }
        }
        .onAppear {
            revealToolbarTemporarily(hideAfter: 2.0)
        }
        .onDisappear {
            autoHideTask?.cancel()
        }
    }

    private var shouldShowToolbar: Bool {
        isToolbarPinned || isTopBarRevealed || isHoveringToolbar || !model.isAuthenticated
    }

    // MARK: - Top Translucent Floating Control Bar

    private var floatingControlBar: some View {
        HStack(spacing: 8) {
            // Device, transport badge & battery
            HStack(spacing: 6) {
                if let conn = model.activeConnection {
                    Image(systemName: conn.transport.systemImage)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(transportBadgeColor(for: conn.transport))
                    Text(conn.deviceName)
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1)
                    Text(conn.transport.shortBadge)
                        .font(.system(size: 10, weight: .semibold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(transportBadgeColor(for: conn.transport).opacity(0.16), in: Capsule())
                        .foregroundStyle(transportBadgeColor(for: conn.transport))
                    if model.deviceState.batteryPct >= 0 {
                        Text("\(model.deviceState.batteryPct)%")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.leading, 4)

            if model.preferences.showHUDStats && model.isAuthenticated {
                Divider().frame(height: 16)
                hudMetricsPill
            }

            Divider().frame(height: 16)

            // Quick App Launcher (⌘K), Popout Current App, and Split/Layout Menu
            HStack(spacing: 4) {
                toolbarActionButton(
                    icon: "magnifyingglass",
                    label: nil,
                    help: "手机应用快启 (⌘K)",
                    isActive: model.isQuickLauncherPresented
                ) {
                    model.toggleQuickLauncher()
                }

                toolbarActionButton(
                    icon: "macwindow.badge.plus",
                    label: "独立窗",
                    help: popoutButtonHelpText,
                    isActive: !model.popoutManager.controllers.isEmpty
                ) {
                    model.popoutCurrentTopApp()
                }

                layoutMenu
            }

            Divider().frame(height: 16)

            // Display & Device Action Buttons
            HStack(spacing: 4) {
                toolbarActionButton(
                    icon: model.displayMode == .dex ? "macwindow" : "rectangle.on.rectangle",
                    label: model.displayMode == .dex ? "DeX" : "镜像",
                    help: "切换扩展桌面 (DeX) 与屏幕镜像",
                    isActive: model.displayMode == .dex
                ) {
                    model.toggleDisplayMode()
                }

                toolbarActionButton(
                    icon: model.isBlackoutActive ? "iphone.slash" : "iphone",
                    label: nil,
                    help: "一键熄灭手机主屏防烧屏与误触",
                    isActive: model.isBlackoutActive
                ) {
                    model.toggleBlackout()
                }

                toolbarActionButton(
                    icon: model.isAudioMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                    label: nil,
                    help: model.isAudioMuted ? "取消静音" : "静音音频",
                    isActive: !model.isAudioMuted
                ) {
                    model.toggleAudio()
                }

                toolbarActionButton(
                    icon: "rotate.right",
                    label: nil,
                    help: "顺时针旋转 90°",
                    isActive: false
                ) {
                    model.rotateClockwise()
                }

                resolutionMenu

                Divider().frame(height: 16)

                toolbarActionButton(icon: "chevron.backward", label: nil, help: "Android 返回", isActive: false) {
                    model.sendAndroidBack()
                }
                toolbarActionButton(icon: "circle", label: nil, help: "Android 主页", isActive: false) {
                    model.sendAndroidHome()
                }
                toolbarActionButton(icon: "square.on.square", label: nil, help: "Android 多任务", isActive: false) {
                    model.sendAndroidRecents()
                }

                Divider().frame(height: 16)

                toolbarActionButton(
                    icon: isToolbarPinned ? "pin.fill" : "pin",
                    label: nil,
                    help: isToolbarPinned ? "取消固定控制栏" : "固定控制栏",
                    isActive: isToolbarPinned
                ) {
                    isToolbarPinned.toggle()
                }

                toolbarActionButton(
                    icon: "arrow.up.left.and.arrow.down.right",
                    label: nil,
                    help: "切换全屏",
                    isActive: false
                ) {
                    NSApp.keyWindow?.toggleFullScreen(nil)
                }

                Button(role: .destructive) {
                    model.disconnect()
                } label: {
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
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8)
        )
        .shadow(color: Color.black.opacity(0.35), radius: 14, y: 6)
    }

    private var popoutButtonHelpText: String {
        let pkg = model.deviceState.topAppPackage
        if !pkg.isEmpty {
            let label = model.deviceState.topAppLabel.isEmpty ? pkg : model.deviceState.topAppLabel
            return "将当前焦点应用「\(label)」弹出为主窗口外的独立小窗"
        }
        return "选择手机应用并弹出为主窗口外的独立小窗 (⌘K)"
    }

    private func transportBadgeColor(for transport: ActiveTransportKind) -> Color {
        switch transport {
        case .wiredUSB: return .accentColor
        case .wirelessADB: return .orange
        case .wirelessWiFi: return .green
        }
    }

    private var hudMetricsPill: some View {
        let fps = Int(model.videoMetrics.currentFPS.rounded())
        let rtt = Int(model.rttMs.rounded())
        let mbps = model.videoMetrics.bitrateKbps / 1000.0
        let codecName = model.videoMetrics.codec?.displayName ?? model.preferences.preferredCodec.displayName

        return HStack(spacing: 7) {
            Text("\(fps) fps")
            Text("·")
                .foregroundStyle(.tertiary)
            Text("\(rtt) ms")
            Text("·")
                .foregroundStyle(.tertiary)
            Text(String(format: "%.1f Mbps", mbps))
            Text("·")
                .foregroundStyle(.tertiary)
            Text("\(model.currentVideoWidth)×\(model.currentVideoHeight) \(codecName)")

            if model.injectStatus == .uhid {
                Text("UHID")
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1.5)
                    .background(Color.green.opacity(0.22), in: Capsule())
                    .foregroundStyle(.green)
                    .help("UHID 虚拟键鼠外设已激活，原生绕过系统注入限制")
            }
        }
        .font(.system(size: 11, weight: .medium, design: .monospaced))
        .foregroundStyle(.secondary)
    }

    private var layoutMenu: some View {
        Menu {
            Section("单应用自适应宽幅") {
                Button {
                    let next = !model.deviceState.adaptiveSingleEnabled
                    model.setAdaptiveSingle(
                        enabled: next,
                        defaultWidthDp: model.preferences.defaultSingleWidthDp
                    )
                } label: {
                    HStack {
                        Text("单应用默认黄金尺寸 (\(model.preferences.defaultSingleWidthDp)dp)")
                        if model.deviceState.adaptiveSingleEnabled {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }

            Section("5 档单应用宽幅快选") {
                ForEach(Self.singleWidthPresets, id: \.pct) { item in
                    Button {
                        model.setSingleWidthPreset(widthPct: item.pct, remember: true)
                    } label: {
                        Text(item.title)
                    }
                }
            }

            Section("分屏 / 多窗布局") {
                ForEach(ExtCastTilingTopology.allCases) { topology in
                    Button {
                        model.setTilingTopology(topology)
                    } label: {
                        HStack {
                            Text(topology.displayName)
                            if model.deviceState.activeLayoutMode == topology.rawValue {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }

            Divider()

            Button {
                model.reloadCurrentAppDPI()
            } label: {
                Label("重载当前 App DPI", systemImage: "textformat.size")
            }
            .disabled(model.deviceState.topAppPackage.isEmpty)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "rectangle.split.2x2")
                Text("布局")
                    .font(.system(size: 11, weight: .medium))
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
                    Button {
                        model.selectResolutionPreset(preset)
                    } label: {
                        HStack {
                            Text(preset.title)
                            if model.selectedPreset == preset {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
            Section("显示密度 (DPI)") {
                ForEach([160, 200, 240, 280, 320], id: \.self) { dpi in
                    Button {
                        model.selectDPI(dpi)
                    } label: {
                        HStack {
                            Text("\(dpi) DPI")
                            if model.selectedDPI == dpi {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
            Section("刷新率") {
                ForEach([60, 90, 120], id: \.self) { fps in
                    Button {
                        model.applyTargetFPS(fps)
                    } label: {
                        HStack {
                            Text("\(fps) fps")
                            if model.preferences.targetFPS == fps {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            }
            Divider()
            Button("强制刷新关键帧 (IDR)") {
                model.requestIDR()
            }
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

    private func toolbarActionButton(
        icon: String,
        label: String?,
        help: String,
        isActive: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .medium))
                if let label {
                    Text(label)
                        .font(.system(size: 11, weight: .medium))
                }
            }
            .padding(.horizontal, label == nil ? 6 : 8)
            .padding(.vertical, 4)
            .background(
                isActive ? Color.accentColor.opacity(0.24) : Color.clear,
                in: Capsule()
            )
            .foregroundStyle(isActive ? Color.accentColor : Color.primary)
        }
        .buttonStyle(.plain)
        .help(help)
        .accessibilityLabel(label ?? help)
    }

    // MARK: - Smart Inject Status Banner & Toast

    private var injectDeniedSmartBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 16))
                .foregroundStyle(.orange)

            VStack(alignment: .leading, spacing: 2) {
                Text("手机系统阻止了默认输入注入（如安全限制 / 未开模拟点击）")
                    .font(.system(size: 12, weight: .semibold))
                Text("切换至 UHID 虚拟键鼠外设可原生绕过限制，获得低延迟完整控屏体验。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }

            Button {
                model.setInputBackend(.uhid)
            } label: {
                Label("一键切换 UHID 虚拟键鼠", systemImage: "keyboard.badge.ellipsis")
                    .font(.system(size: 11, weight: .semibold))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            Button {
                isInjectWarningDismissed = true
            } label: {
                Image(systemName: "xmark")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .help("暂时隐藏此提示")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.orange.opacity(0.4), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.32), radius: 12, y: 5)
    }

    private func toastPill(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles")
                .font(.caption)
                .foregroundStyle(Color.accentColor)
            Text(message)
                .font(.system(size: 12, weight: .medium))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8)
        )
        .shadow(color: Color.black.opacity(0.28), radius: 10, y: 4)
    }

    // MARK: - Overlays

    private var connectionOverlay: some View {
        VStack(spacing: 12) {
            ProgressView()
                .controlSize(.large)
                .tint(.white)
            Text(connectionStatusCaption)
                .font(.headline)
                .foregroundStyle(.white)
            if let conn = model.activeConnection {
                Text("\(conn.deviceName) · \(conn.transport.shortBadge)")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var connectionStatusCaption: String {
        switch model.sessionState {
        case .connecting:
            return "正在建立低延迟连接..."
        case .reconnecting(let attempt):
            return "连接中断，正在尝试第 \(attempt) 次重连..."
        case .connected:
            return model.isAuthenticated ? "正在等待 Android 首个关键帧..." : "正在进行握手与身份校验..."
        default:
            return "准备显示画面..."
        }
    }

    private var fileDropOverlay: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [10, 6]))
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                VStack(spacing: 10) {
                    Image(systemName: "arrow.down.doc.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.accentColor)
                    Text("松开鼠标发送文件到 Android 手机")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                    Text("默认保存到手机 Download/ExtBridge 目录")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.8))
                }
                .padding(24)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(24)
            .allowsHitTesting(false)
    }

    private var transferToastStack: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(model.activeTransfers) { transfer in
                HStack(spacing: 10) {
                    Image(systemName: transferIcon(for: transfer.status))
                        .foregroundStyle(transferColor(for: transfer.status))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(transfer.fileName)
                            .font(.system(size: 12, weight: .semibold))
                            .lineLimit(1)
                        switch transfer.status {
                        case .sending:
                            ProgressView(value: transfer.fractionCompleted)
                                .frame(width: 170)
                        case .awaitingAck:
                            Text("等待手机端落盘确认...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        case .completed(let remotePath):
                            Text(remotePath.isEmpty ? "已发送到手机" : "已保存：\(remotePath)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        case .failed(let reason):
                            Text("失败：\(reason)")
                                .font(.caption)
                                .foregroundStyle(.red)
                                .lineLimit(2)
                        case .cancelled:
                            Text("已取消")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if transfer.status == .sending {
                        Button {
                            model.cancelFileTransfer(transfer.transferId)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption)
                        }
                        .buttonStyle(.borderless)
                        .help("取消传输")
                    } else {
                        Button {
                            model.dismissTransfer(transfer.transferId)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption)
                        }
                        .buttonStyle(.borderless)
                        .help("关闭提示")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.8)
                )
            }
        }
    }

    private func transferIcon(for status: FileTransferStatus) -> String {
        switch status {
        case .sending, .awaitingAck: return "arrow.up.circle.fill"
        case .completed: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "slash.circle"
        }
    }

    private func transferColor(for status: FileTransferStatus) -> Color {
        switch status {
        case .sending, .awaitingAck: return .accentColor
        case .completed: return .green
        case .failed: return .red
        case .cancelled: return .secondary
        }
    }

    private func revealToolbarTemporarily(hideAfter seconds: Double = 2.2) {
        autoHideTask?.cancel()
        isTopBarRevealed = true
        if !isToolbarPinned {
            scheduleToolbarHide(delaySeconds: seconds)
        }
    }

    private func scheduleToolbarHide(delaySeconds: Double) {
        autoHideTask?.cancel()
        autoHideTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            if !isToolbarPinned && !isHoveringToolbar {
                isTopBarRevealed = false
            }
        }
    }
}

/// Configures the hosting macOS `NSWindow` chrome cleanly between immersive full-bleed dark canvas
/// (`DisplayWindowView`) and standard native window chrome (`DeviceHubView`).
struct WindowChromeConfigurator: NSViewRepresentable {
    let isImmersiveCanvas: Bool

    func makeNSView(context: Context) -> ChromeConfiguratorNSView {
        let view = ChromeConfiguratorNSView()
        view.isImmersiveCanvas = isImmersiveCanvas
        return view
    }

    func updateNSView(_ nsView: ChromeConfiguratorNSView, context: Context) {
        nsView.isImmersiveCanvas = isImmersiveCanvas
        nsView.applyIfNeeded()
    }

    final class ChromeConfiguratorNSView: NSView {
        var isImmersiveCanvas: Bool = false
        private var lastAppliedMode: Bool?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            lastAppliedMode = nil
            applyIfNeeded()
        }

        func applyIfNeeded() {
            guard let window else { return }
            if lastAppliedMode == isImmersiveCanvas {
                return
            }
            lastAppliedMode = isImmersiveCanvas

            if isImmersiveCanvas {
                window.titlebarAppearsTransparent = true
                window.titleVisibility = .hidden
                window.backgroundColor = .black
                window.appearance = NSAppearance(named: .darkAqua)
                window.isMovableByWindowBackground = false
            } else {
                window.titlebarAppearsTransparent = false
                window.titleVisibility = .visible
                window.backgroundColor = .windowBackgroundColor
                window.appearance = nil
                window.isMovableByWindowBackground = false
            }
        }
    }
}
