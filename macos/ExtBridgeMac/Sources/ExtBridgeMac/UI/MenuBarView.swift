import AppKit
import ExtBridgeCore
import SwiftUI

/// Menu bar resident dropdown for instant device connection, quick launcher, standalone popout windows,
/// blackout/audio control, and main window focus.
public struct MenuBarView: View {
    @Bindable public var model: AppModel
    @Environment(\.openWindow) private var openWindow

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let conn = model.activeConnection {
                connectedSection(conn)
            } else {
                disconnectedDevicesSection
            }

            Divider()
            Button("显示主窗口", systemImage: "macwindow") { openMainWindow() }
            SettingsLink { Label("偏好设置...", systemImage: "gearshape") }

            Divider()
            Button("退出 ExtBridgeMac") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
        }
        .padding(10)
        .frame(width: 264)
    }

    // MARK: - Connected Controls

    @ViewBuilder
    private func connectedSection(_ conn: ActiveConnectionInfo) -> some View {
        HStack(spacing: 8) {
            Circle()
                .fill(model.isAuthenticated ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(conn.deviceName).font(.headline)
                Text("\(conn.transport.shortBadge) · \(Int(model.videoMetrics.currentFPS.rounded())) fps · \(Int(model.rttMs.rounded())) ms")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.bottom, 2)

        Divider()

        Button("应用抽屉 (⌘K)", systemImage: "square.grid.3x3.fill") {
            openMainWindow()
            model.openQuickLauncher()
        }
        .disabled(!model.isAuthenticated)

        Button("快捷左右分屏 (⌘⇧S)", systemImage: "rectangle.split.2x1") {
            openMainWindow()
            model.startQuickTwoSplit()
        }
        .disabled(!model.isAuthenticated)

        Button("弹出当前 App 到独立窗", systemImage: "macwindow.badge.plus") {
            model.popoutCurrentTopApp()
        }
        .disabled(!model.isAuthenticated)

        if !model.popoutManager.controllers.isEmpty {
            popoutWindowsSection
        }

        Divider()

        Button(
            model.displayMode == .dex ? "切换到屏幕镜像" : "切换到扩展桌面 (DeX)",
            systemImage: model.displayMode == .dex ? "rectangle.on.rectangle" : "macwindow"
        ) { model.toggleDisplayMode() }

        Button(
            model.isBlackoutActive ? "点亮手机主屏" : "一键熄灭手机主屏",
            systemImage: model.isBlackoutActive ? "iphone" : "iphone.slash"
        ) { model.toggleBlackout() }

        Button(
            model.isAudioMuted ? "开启音频回传" : "静音音频回传",
            systemImage: model.isAudioMuted ? "speaker.wave.2" : "speaker.slash"
        ) { model.toggleAudio() }

        Divider()
        Button("断开连接", systemImage: "xmark.circle", role: .destructive) { model.disconnect() }
    }

    @ViewBuilder
    private var popoutWindowsSection: some View {
        let controllers = model.popoutManager.controllers
        Divider()
        Text("已弹出的独立窗口 (\(controllers.count)/\(PopoutWindowManager.maxConcurrentWindows))")
            .font(.caption)
            .foregroundStyle(.secondary)
        ForEach(controllers) { popout in
            HStack {
                Button { model.popoutManager.focusWindow(id: popout.id) } label: {
                    Label(popout.appLabel, systemImage: popout.isPinnedOnTop ? "pin.fill" : "macwindow").lineLimit(1)
                }
                .buttonStyle(.borderless)
                Spacer()
                Button { model.popoutManager.closeWindow(id: popout.id) } label: {
                    Image(systemName: "xmark.circle").foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("关闭该弹出窗")
            }
        }
    }

    // MARK: - Disconnected Quick Connect

    @ViewBuilder
    private var disconnectedDevicesSection: some View {
        let usbDevices = model.discovery.usbDevices
        let bonjourDevices = model.discovery.bonjourDevices

        Text("ExtBridge 未连接").font(.headline)

        if !usbDevices.isEmpty {
            Divider()
            Text("ADB 设备（USB / 无线调试）").font(.caption).foregroundStyle(.secondary)
            ForEach(usbDevices) { device in
                Button(device.displayName, systemImage: device.isWirelessAdb ? "wifi" : "cable.connector") {
                    openMainWindow()
                    Task { await model.connectWiredUSB(device) }
                }
                .disabled(!device.isReady)
            }
        }

        if !bonjourDevices.isEmpty {
            Divider()
            Text("无线局域网设备").font(.caption).foregroundStyle(.secondary)
            ForEach(bonjourDevices) { device in
                Button(device.displayName, systemImage: "wifi") {
                    openMainWindow()
                    model.connectBonjourDevice(device)
                }
            }
        }
    }

    private func openMainWindow() {
        openWindow(id: WindowID.main)
        NSApp.activate(ignoringOtherApps: true)
    }
}
