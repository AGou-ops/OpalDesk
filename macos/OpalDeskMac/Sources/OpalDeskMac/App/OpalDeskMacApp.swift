import AppKit
import SwiftUI

enum WindowID {
    static let main = "main"
}

@MainActor
final class OpalDeskAppDelegate: NSObject, NSApplicationDelegate {
    var onTerminate: (@MainActor () -> Void)?

    func applicationWillTerminate(_ notification: Notification) {
        onTerminate?()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // Stay resident in the macOS Menu Bar when the main window is closed.
        false
    }
}

@main
struct OpalDeskMacApp: App {
    @NSApplicationDelegateAdaptor(OpalDeskAppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup("OpalDesk", id: WindowID.main) {
            Group {
                if model.isShowingDisplayCanvas {
                    DisplayWindowView(model: model)
                        .navigationTitle(windowTitle)
                } else {
                    DeviceHubView(model: model)
                        .navigationTitle("OpalDesk")
                }
            }
            .frame(minWidth: 680, minHeight: 480)
            .onAppear(perform: registerTerminationHandler)
        }
        .defaultSize(width: 1180, height: 740)
        .commands {
            CommandMenu("投屏控制") {
                Button("返回") {
                    model.sendAndroidBack()
                }
                .keyboardShortcut("[", modifiers: [.command])
                .disabled(!model.isAuthenticated)

                Button("桌面") {
                    model.sendAndroidHome()
                }
                .keyboardShortcut("h", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("多任务") {
                    model.sendAndroidRecents()
                }
                .disabled(!model.isAuthenticated)

                Divider()

                Button("重载当前应用 DPI") {
                    model.reloadCurrentAppDPI()
                }
                .disabled(!model.isAuthenticated || model.deviceState.topAppPackage.isEmpty)

                Divider()

                Button(model.isBlackoutActive ? "点亮手机主屏" : "熄灭手机主屏") {
                    model.toggleBlackout()
                }
                .keyboardShortcut("b", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button(model.isAudioMuted ? "取消静音" : "静音音频") {
                    model.toggleAudio()
                }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("切换 DeX / 镜像") {
                    model.toggleDisplayMode()
                }
                .keyboardShortcut("d", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("顺时针旋转 90°") {
                    model.rotateClockwise()
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(!model.isAuthenticated)

                Button("强制刷新关键帧 (IDR)") {
                    model.requestIDR()
                }
                .disabled(!model.isAuthenticated)

                Divider()

                Button("断开并返回列表") {
                    model.forceDisconnectAndReturnHome()
                }
                .keyboardShortcut("w", modifiers: [.command, .shift])
                .disabled(model.activeConnection == nil && !model.isConnectingFlow)

                Button("强制退出 OpalDesk") {
                    model.forceTerminateApp()
                }
                .keyboardShortcut("q", modifiers: [.command, .option])
            }
        }

        MenuBarExtra {
            MenuBarView(model: model)
                .onAppear(perform: registerTerminationHandler)
        } label: {
            Image(systemName: model.isAuthenticated ? "macbook.and.iphone" : "display")
                .symbolRenderingMode(.hierarchical)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
        }
    }

    private func registerTerminationHandler() {
        appDelegate.onTerminate = { [model] in
            model.cleanupOnTerminate()
        }
    }

    private var windowTitle: String {
        guard let conn = model.activeConnection else { return "OpalDesk" }
        let appLabel = model.deviceState.topAppLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        return appLabel.isEmpty ? conn.deviceName : "\(conn.deviceName) · \(appLabel)"
    }
}
