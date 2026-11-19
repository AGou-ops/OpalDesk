import AppKit
import ExtBridgeCore
import SwiftUI

final class ExtBridgeAppDelegate: NSObject, NSApplicationDelegate {
    var onTerminate: (@MainActor () -> Void)?

    func applicationWillTerminate(_ notification: Notification) {
        if let onTerminate {
            MainActor.assumeIsolated {
                onTerminate()
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // Stay resident in the macOS Menu Bar when the main window is closed.
        false
    }
}

@main
struct ExtBridgeMacApp: App {
    @NSApplicationDelegateAdaptor(ExtBridgeAppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup("ExtBridgeMac", id: "main") {
            Group {
                if model.isShowingDisplayCanvas {
                    DisplayWindowView(model: model)
                        .navigationTitle(windowTitle)
                } else {
                    DeviceHubView(model: model)
                        .navigationTitle("ExtBridgeMac")
                }
            }
            .frame(minWidth: 760, minHeight: 500)
            .onAppear {
                appDelegate.onTerminate = { [model] in
                    model.cleanupOnTerminate()
                }
            }
        }
        .defaultSize(width: 1120, height: 700)
        .commands {
            CommandMenu("大屏控制") {
                Button("手机应用快启器...") {
                    model.toggleQuickLauncher()
                }
                .keyboardShortcut("k", modifiers: [.command])
                .disabled(!model.isAuthenticated)

                Button("弹出当前应用到独立窗口") {
                    model.popoutCurrentTopApp()
                }
                .keyboardShortcut("p", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("重载当前应用 DPI") {
                    model.reloadCurrentAppDPI()
                }
                .disabled(!model.isAuthenticated || model.deviceState.topAppPackage.isEmpty)

                Divider()

                Button("切换扩展桌面 (DeX) / 镜像") {
                    model.toggleDisplayMode()
                }
                .keyboardShortcut("d", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button(model.isBlackoutActive ? "点亮手机主屏" : "一键熄灭手机主屏") {
                    model.toggleBlackout()
                }
                .keyboardShortcut("b", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button(model.isAudioMuted ? "取消静音" : "静音音频") {
                    model.toggleAudio()
                }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("顺时针旋转 90°") {
                    model.rotateClockwise()
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("强制刷新画面关键帧 (IDR)") {
                    model.requestIDR()
                }
                .disabled(!model.isAuthenticated)

                Divider()

                Button("Android 返回") { model.sendAndroidBack() }
                    .disabled(!model.isAuthenticated)
                Button("Android 主页") { model.sendAndroidHome() }
                    .disabled(!model.isAuthenticated)
                Button("Android 多任务") { model.sendAndroidRecents() }
                    .disabled(!model.isAuthenticated)

                Divider()

                Button("断开当前连接") {
                    model.disconnect()
                }
                .disabled(model.activeConnection == nil)
            }
        }

        MenuBarExtra {
            MenuBarView(model: model)
        } label: {
            Image(systemName: model.isAuthenticated ? "macbook.and.iphone" : "display")
                .symbolRenderingMode(.hierarchical)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
        }
    }

    private var windowTitle: String {
        guard let conn = model.activeConnection else { return "ExtBridgeMac" }
        return "\(conn.deviceName) — ExtBridgeMac"
    }
}
