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
                HStack(spacing: 8) {
                    Circle()
                        .fill(model.isAuthenticated ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(conn.deviceName)
                            .font(.headline)
                        Text("\(conn.transport.shortBadge) · \(Int(model.videoMetrics.currentFPS.rounded())) fps · \(Int(model.rttMs.rounded())) ms")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.bottom, 2)

                Divider()

                Button {
                    openMainWindow()
                    model.openQuickLauncher()
                } label: {
                    Label("手机应用快启 (⌘K)", systemImage: "magnifyingglass")
                }
                .disabled(!model.isAuthenticated)

                Button {
                    model.popoutCurrentTopApp()
                } label: {
                    Label("弹出当前 App 到独立窗", systemImage: "macwindow.badge.plus")
                }
                .disabled(!model.isAuthenticated)

                if !model.popoutManager.controllers.isEmpty {
                    Divider()
                    Text("已弹出的独立窗口 (\(model.popoutManager.controllers.count)/\(PopoutWindowManager.maxConcurrentWindows))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ForEach(model.popoutManager.controllers) { popout in
                        HStack {
                            Button {
                                model.popoutManager.focusWindow(id: popout.id)
                            } label: {
                                Label(popout.appLabel, systemImage: popout.isPinnedOnTop ? "pin.fill" : "macwindow")
                                    .lineLimit(1)
                            }
                            .buttonStyle(.borderless)
                            Spacer()
                            Button {
                                model.popoutManager.closeWindow(id: popout.id)
                            } label: {
                                Image(systemName: "xmark.circle")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.borderless)
                            .help("关闭该弹出窗")
                        }
                    }
                }

                Divider()

                Button {
                    model.toggleDisplayMode()
                } label: {
                    Label(
                        model.displayMode == .dex ? "切换到屏幕镜像" : "切换到扩展桌面 (DeX)",
                        systemImage: model.displayMode == .dex ? "rectangle.on.rectangle" : "macwindow"
                    )
                }

                Button {
                    model.toggleBlackout()
                } label: {
                    Label(
                        model.isBlackoutActive ? "点亮手机主屏" : "一键熄灭手机主屏",
                        systemImage: model.isBlackoutActive ? "iphone" : "iphone.slash"
                    )
                }

                Button {
                    model.toggleAudio()
                } label: {
                    Label(
                        model.isAudioMuted ? "开启音频回传" : "静音音频回传",
                        systemImage: model.isAudioMuted ? "speaker.wave.2" : "speaker.slash"
                    )
                }

                Divider()

                Button(role: .destructive) {
                    model.disconnect()
                } label: {
                    Label("断开连接", systemImage: "xmark.circle")
                }
            } else {
                Text("ExtBridge 未连接")
                    .font(.headline)

                if !model.usbDevices.isEmpty {
                    Divider()
                    Text("ADB 设备（USB / 无线调试）")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ForEach(model.usbDevices) { device in
                        Button {
                            openMainWindow()
                            Task { await model.connectWiredUSB(device) }
                        } label: {
                            Label(device.displayName, systemImage: device.isWirelessAdb ? "wifi" : "cable.connector")
                        }
                        .disabled(!device.isReady)
                    }
                }

                if !model.bonjourDevices.isEmpty {
                    Divider()
                    Text("无线局域网设备")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ForEach(model.bonjourDevices) { device in
                        Button {
                            openMainWindow()
                            model.connectBonjourDevice(device)
                        } label: {
                            Label(device.displayName, systemImage: "wifi")
                        }
                    }
                }
            }

            Divider()

            Button {
                openMainWindow()
            } label: {
                Label("显示主窗口", systemImage: "macwindow")
            }

            SettingsLink {
                Label("偏好设置...", systemImage: "gearshape")
            }

            Divider()

            Button("退出 ExtBridgeMac") {
                model.cleanupOnTerminate()
                NSApp.terminate(nil)
            }
            .keyboardShortcut("q")
        }
        .padding(10)
        .frame(width: 264)
    }

    private func openMainWindow() {
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}
