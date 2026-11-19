import AppKit
import ExtBridgeCore
import SwiftUI

/// Native macOS tabbed Settings window covering stream codec/FPS/adaptive single width,
/// input backend/keyboard/clipboard sync, ADB path detection, and trusted wireless devices.
public struct SettingsView: View {
    @Bindable public var model: AppModel
    @State private var confirmRevokeAll: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        TabView {
            streamSettingsTab
                .tabItem {
                    Label("画面与布局", systemImage: "display")
                }

            inputAndSyncTab
                .tabItem {
                    Label("输入与同步", systemImage: "keyboard")
                }

            adbSettingsTab
                .tabItem {
                    Label("有线 ADB", systemImage: "cable.connector")
                }

            trustedDevicesTab
                .tabItem {
                    Label("已信任设备", systemImage: "lock.shield")
                }
        }
        .frame(width: 560, height: 470)
    }

    // MARK: - Tab 1: Stream, High-FPS & Adaptive Single Layout

    private var streamSettingsTab: some View {
        Form {
            Section("编码与刷新率") {
                Picker("视频编码器偏好", selection: Binding(
                    get: { model.preferences.preferredCodec },
                    set: { model.preferences.preferredCodec = $0 }
                )) {
                    Text("自动 HEVC (H.265，画质更佳、省带宽)").tag(ExtCastVideoCodec.hevc)
                    Text("兼容 H.264 (AVC，老机型兼容性更好)").tag(ExtCastVideoCodec.h264)
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("视频目标码率")
                        Spacer()
                        Text("\(model.preferences.bitrateKbps / 1000) Mbps")
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(model.preferences.bitrateKbps) },
                            set: { model.preferences.bitrateKbps = Int(($0 / 2000.0).rounded()) * 2000 }
                        ),
                        in: 4000...32000,
                        step: 2000
                    )
                }

                Picker("目标刷新率", selection: Binding(
                    get: { model.preferences.targetFPS },
                    set: { model.applyTargetFPS($0) }
                )) {
                    Text("60 fps（标准）").tag(60)
                    Text("90 fps（流畅高刷）").tag(90)
                    Text("120 fps（ProMotion 极速）").tag(120)
                }

                Picker("默认显示模式", selection: Binding(
                    get: { model.preferences.defaultDisplayMode },
                    set: { model.preferences.defaultDisplayMode = $0 }
                )) {
                    Text("扩展桌面 (DeX)").tag(ExtCastDisplayMode.dex)
                    Text("屏幕镜像").tag(ExtCastDisplayMode.mirror)
                }

                Picker("默认显示密度 (DPI)", selection: Binding(
                    get: { model.preferences.defaultDPI },
                    set: { model.selectDPI($0) }
                )) {
                    ForEach([160, 200, 240, 280, 320], id: \.self) { dpi in
                        Text("\(dpi) DPI").tag(dpi)
                    }
                }
            }

            Section("单应用默认黄金尺寸") {
                Toggle("开启单应用默认黄金宽度居中排版", isOn: Binding(
                    get: { model.preferences.adaptiveSingleEnabled },
                    set: { enabled in
                        model.setAdaptiveSingle(
                            enabled: enabled,
                            defaultWidthDp: model.preferences.defaultSingleWidthDp
                        )
                    }
                ))

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("默认单应用宽度")
                        Spacer()
                        Text("\(model.preferences.defaultSingleWidthDp) dp")
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(model.preferences.defaultSingleWidthDp) },
                            set: { newVal in
                                let stepped = Int((newVal / 20.0).rounded()) * 20
                                model.setAdaptiveSingle(
                                    enabled: model.preferences.adaptiveSingleEnabled,
                                    defaultWidthDp: stepped
                                )
                            }
                        ),
                        in: 320...1200,
                        step: 20
                    )
                    .disabled(!model.preferences.adaptiveSingleEnabled)
                }
            }

            Section("辅助显示") {
                Toggle("默认开启手机音频回传", isOn: Binding(
                    get: { model.preferences.audioEnabled },
                    set: { model.preferences.audioEnabled = $0 }
                ))

                Toggle("在悬浮控制栏显示实时 FPS / RTT / 码率 / UHID HUD", isOn: Binding(
                    get: { model.preferences.showHUDStats },
                    set: { model.preferences.showHUDStats = $0 }
                ))
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 2: Input Engine, Shortcuts & Bidirectional Clipboard Sync

    private var inputAndSyncTab: some View {
        Form {
            Section("输入注入引擎") {
                Picker("输入引擎模式", selection: Binding(
                    get: { model.preferences.inputBackend },
                    set: { model.setInputBackend($0) }
                )) {
                    Text("自动（优先系统注入，受限时切换 UHID）").tag(ExtCastInputBackend.auto)
                    Text("UHID 虚拟外设（绕过 MIUI/HyperOS 注入拦截）").tag(ExtCastInputBackend.uhid)
                    Text("系统事件注入 (INJECT_EVENTS)").tag(ExtCastInputBackend.inject)
                }
                Text("UHID 模式会在手机内核注册虚拟键鼠外设，无需开启额外系统安全模拟点击开关。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("键盘与触控板快捷键") {
                Toggle("将 ⌘C / ⌘V / ⌘X / ⌘A / ⌘Z / ⌘W 等自动映射为 Android Ctrl 快捷键", isOn: Binding(
                    get: { model.preferences.mapCommandToCtrl },
                    set: { model.preferences.mapCommandToCtrl = $0 }
                ))
                Text("内置快捷键：⌘K 打开手机应用快启器 · ⌘⇧T 置顶独立弹出窗 · 触控板三指上滑多任务 / 下滑主页 / 左右滑返回。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("双向剪贴板同步（文本 + PNG 截图）") {
                Toggle("自动同步 Mac 与 Android 之间的文本及 PNG 图片剪贴板", isOn: Binding(
                    get: { model.preferences.clipboardSyncEnabled },
                    set: {
                        model.preferences.clipboardSyncEnabled = $0
                        model.applyClipboardSyncPreference()
                    }
                ))
                Text("支持 256 KiB 以内纯文本与 8 MiB 以内 PNG/TIFF 截图自动压缩同步，内置 SHA-256 防回环去重。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 3: ADB Path

    private var adbSettingsTab: some View {
        Form {
            Section("当前识别状态") {
                HStack {
                    Image(systemName: model.adbBinaryPath != nil ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(model.adbBinaryPath != nil ? .green : .orange)
                    Text(model.adbBinaryPath ?? "未检测到可用的 adb 可执行文件")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                    Spacer()
                }
            }

            Section("自定义 adb 路径（留空则自动探测）") {
                HStack(spacing: 8) {
                    TextField("/opt/homebrew/bin/adb", text: Binding(
                        get: { model.preferences.customAdbPath },
                        set: {
                            model.preferences.customAdbPath = $0
                            model.refreshAdbPath()
                        }
                    ))
                    .textFieldStyle(.roundedBorder)

                    Button("浏览...") {
                        pickCustomAdbBinary()
                    }
                }

                Text("自动探测顺序：App 内置 → ANDROID_HOME → Homebrew Android CLI Tools → ~/Library/Android/sdk。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Tab 4: Trusted Wireless Devices

    private var trustedDevicesTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已信任的无线 Android 设备")
                        .font(.headline)
                    Text("配对或无线 ADB 自动升级后采用 SHA-256 证书指纹固定（TOFU）校验，防止局域网中间人劫持。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if !model.trustedDevices.isEmpty {
                    Button("清除全部", role: .destructive) {
                        confirmRevokeAll = true
                    }
                    .buttonStyle(.bordered)
                }
            }

            if model.trustedDevices.isEmpty {
                ContentUnavailableView(
                    "暂无已信任的无线设备",
                    systemImage: "lock.shield",
                    description: Text("首次无线配对或通过无线 ADB 自动升级 Wi-Fi 直连后，设备会出现在此处。")
                )
            } else {
                List {
                    ForEach(model.trustedDevices) { device in
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(device.deviceName)
                                    .font(.body.weight(.medium))
                                HStack(spacing: 8) {
                                    Text(device.shortFingerprint)
                                        .font(.caption.monospaced())
                                    if let host = device.lastHost, let port = device.lastPort {
                                        Text("· \(host):\(port)")
                                            .font(.caption.monospaced())
                                    }
                                }
                                .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("撤销", role: .destructive) {
                                model.revokeTrustedDevice(device)
                            }
                            .buttonStyle(.borderless)
                        }
                        .padding(.vertical, 4)
                    }
                }
                .listStyle(.inset)
            }
        }
        .padding(16)
        .confirmationDialog(
            "确定要撤销全部已信任的无线 Android 设备吗？",
            isPresented: $confirmRevokeAll,
            titleVisibility: .visible
        ) {
            Button("全部撤销", role: .destructive) {
                model.revokeAllTrustedDevices()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("撤销后再次无线连接需要重新核对证书指纹并输入 6 位配对 PIN 码。")
        }
    }

    private func pickCustomAdbBinary() {
        let panel = NSOpenPanel()
        panel.title = "选择 adb 可执行文件"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.showsHiddenFiles = true
        if panel.runModal() == .OK, let url = panel.url {
            model.preferences.customAdbPath = url.path
            model.refreshAdbPath()
        }
    }
}
