import ExtBridgeCore
import SwiftUI

/// Primary discovery hub presenting USB wired devices (USB 2.0/3.0 without DP Alt Mode)
/// and wireless LAN devices discovered via Bonjour (`_extbridge._tcp`) or manual IP:Port.
public struct DeviceHubView: View {
    @Bindable public var model: AppModel

    @State private var manualHost: String = ""
    @State private var manualPortText: String = String(ExtCastProtocol.defaultWifiPort)
    @State private var showManualConnect: Bool = false

    public init(model: AppModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                headerSection

                if let status = model.statusMessage {
                    infoBanner(icon: "hourglass", text: status, tint: .accentColor, isLoading: true)
                }

                if let error = model.bannerError {
                    errorBanner(error)
                }

                wiredUSBSection
                wirelessLANSection
                manualConnectSection
            }
            .padding(24)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .background(WindowChromeConfigurator(isImmersiveCanvas: false))
        .sheet(item: $model.pairingContext) { ctx in
            PairingSheetView(
                context: ctx,
                onSubmit: { pin in
                    model.submitPairing(pinCode: pin)
                },
                onCancel: {
                    model.cancelPairing()
                }
            )
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        HStack(alignment: .center, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.accentColor.opacity(0.14))
                    .frame(width: 46, height: 46)
                Image(systemName: "macbook.and.iphone")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("ExtBridge Mac 大屏中心")
                    .font(.title2.weight(.semibold))
                Text("无物理外接屏 · 硬件编解码低延迟 Android 桌面 / 镜像")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            HStack(spacing: 8) {
                Picker("模式", selection: Binding(
                    get: { model.preferences.defaultDisplayMode },
                    set: {
                        model.preferences.defaultDisplayMode = $0
                        model.displayMode = $0
                    }
                )) {
                    Text("DeX 桌面").tag(ExtCastDisplayMode.dex)
                    Text("屏幕镜像").tag(ExtCastDisplayMode.mirror)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 156)
                .help("选择连接后默认进入扩展桌面 (DeX) 或屏幕镜像")

                SettingsLink {
                    Image(systemName: "gearshape")
                        .font(.system(size: 14))
                }
                .buttonStyle(.bordered)
                .help("打开偏好设置 (⌘,)")
            }
        }
    }

    // MARK: - Wired USB Section

    private var wiredUSBSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label("ADB 设备（USB / 无线调试）", systemImage: "cable.connector")
                    .font(.headline)

                Text("支持 USB 2.0 / 3.0 · 无需 DP Alt Mode")
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.12), in: Capsule())
                    .foregroundStyle(Color.accentColor)

                Spacer()

                if model.isScanningUSB {
                    ProgressView()
                        .controlSize(.small)
                }

                Button {
                    Task { await model.refreshUSBDevices() }
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
                .disabled(model.isScanningUSB)
            }

            VStack(spacing: 0) {
                if model.adbBinaryPath == nil {
                    adbMissingEmptyState
                } else if model.usbDevices.isEmpty {
                    usbEmptyState
                } else {
                    ForEach(Array(model.usbDevices.enumerated()), id: \.element.id) { index, device in
                        usbDeviceRow(device)
                        if index < model.usbDevices.count - 1 {
                            Divider().padding(.leading, 56)
                        }
                    }
                }
            }
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
            )
        }
    }

    private func usbDeviceRow(_ device: AdbDevice) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 20))
                .foregroundStyle(device.isReady ? Color.accentColor : Color.secondary)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName)
                        .font(.body.weight(.medium))
                    statusBadge(
                        text: device.isWirelessAdb ? "无线 ADB 隧道" : "USB 有线",
                        color: device.isWirelessAdb ? .blue : .accentColor
                    )
                    statusBadge(
                        text: device.state.displayLabel,
                        color: device.isReady ? .green : .orange
                    )
                }
                Text(device.isWirelessAdb
                    ? "地址：\(device.serial) · 连接成功后自动升级为原生 Wi-Fi TLS 1.3 直连并释放 ADB 隧道"
                    : "序列号：\(device.serial)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button("一键连接大屏") {
                Task { await model.connectWiredUSB(device) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(!device.isReady || model.isConnectingFlow)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    private var usbEmptyState: some View {
        HStack(spacing: 12) {
            Image(systemName: "cable.connector.horizontal")
                .font(.system(size: 22))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text("未检测到 USB 连接的 Android 设备")
                    .font(.subheadline.weight(.medium))
                Text("用任意 USB 数据线连接手机并在开发者选项中开启「USB 调试」，即插即用。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(16)
    }

    private var adbMissingEmptyState: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 22))
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 3) {
                Text("未找到 adb 工具")
                    .font(.subheadline.weight(.medium))
                Text("请安装 Android Platform Tools，或在「设置 → 有线 ADB」中手动指定 adb 路径。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            SettingsLink {
                Text("配置 ADB")
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
    }

    // MARK: - Wireless LAN Section

    private var wirelessLANSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label("无线局域网设备", systemImage: "wifi")
                    .font(.headline)

                Text("Bonjour 自动发现 · TLS 加密")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Spacer()

                HStack(spacing: 5) {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 7, height: 7)
                    Text("监听 _extbridge._tcp")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
            }

            VStack(spacing: 0) {
                if model.bonjourDevices.isEmpty {
                    wirelessEmptyState
                } else {
                    ForEach(Array(model.bonjourDevices.enumerated()), id: \.element.id) { index, device in
                        bonjourDeviceRow(device)
                        if index < model.bonjourDevices.count - 1 {
                            Divider().padding(.leading, 56)
                        }
                    }
                }
            }
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
            )
        }
    }

    private func bonjourDeviceRow(_ device: DiscoveredBonjourDevice) -> some View {
        let isTrusted = model.trustStore.find(byFingerprint: device.fingerprintPrefix) != nil

        return HStack(spacing: 12) {
            Image(systemName: "iphone.radiowaves.left.and.right")
                .font(.system(size: 20))
                .foregroundStyle(Color.accentColor)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(device.displayName)
                        .font(.body.weight(.medium))
                    if isTrusted {
                        statusBadge(text: "已信任", color: .green)
                    } else {
                        statusBadge(text: "需 PIN 配对", color: .blue)
                    }
                }
                HStack(spacing: 8) {
                    if !device.model.isEmpty {
                        Text(device.model)
                    }
                    if !device.fingerprintPrefix.isEmpty {
                        Text("指纹 \(device.shortFingerprint)")
                            .font(.caption.monospaced())
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Button(isTrusted ? "连接" : "配对并连接") {
                model.connectBonjourDevice(device)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.isConnectingFlow || model.isProbingManualHost)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    private var wirelessEmptyState: some View {
        HStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 22))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text("正在等待局域网内的 Android 手机...")
                    .font(.subheadline.weight(.medium))
                Text("请在手机端 ExtBridge Helper 的「Mac / 无线大屏」卡片中开启无线大屏服务（默认安全关闭）。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(16)
    }

    // MARK: - Manual IP:Port Connection

    private var manualConnectSection: some View {
        DisclosureGroup(isExpanded: $showManualConnect) {
            HStack(spacing: 10) {
                TextField("手机局域网 IP（如 192.168.31.102）", text: $manualHost)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { triggerManualConnect() }

                TextField("端口", text: $manualPortText)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 82)
                    .onSubmit { triggerManualConnect() }

                Button {
                    triggerManualConnect()
                } label: {
                    if model.isProbingManualHost {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("核验指纹并连接")
                    }
                }
                .buttonStyle(.bordered)
                .disabled(manualHost.trimmingCharacters(in: .whitespaces).isEmpty || model.isProbingManualHost || model.isConnectingFlow)
            }
            .padding(.top, 10)
        } label: {
            Label("手动输入 IP 与端口连接（跨子网或 mDNS 受限时使用）", systemImage: "network")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }

    private func triggerManualConnect() {
        let port = UInt16(manualPortText.trimmingCharacters(in: .whitespaces)) ?? ExtCastProtocol.defaultWifiPort
        Task {
            await model.connectManualHost(host: manualHost, port: port)
        }
    }

    // MARK: - Helper Badges & Banners

    private func statusBadge(text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(color.opacity(0.14), in: Capsule())
            .foregroundStyle(color)
    }

    private func infoBanner(icon: String, text: String, tint: Color, isLoading: Bool) -> some View {
        HStack(spacing: 10) {
            if isLoading {
                ProgressView().controlSize(.small)
            } else {
                Image(systemName: icon).foregroundStyle(tint)
            }
            Text(text)
                .font(.callout)
            Spacer()
        }
        .padding(12)
        .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
            Text(message)
                .font(.callout)
                .textSelection(.enabled)
            Spacer()
            Button {
                model.bannerError = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.caption)
            }
            .buttonStyle(.borderless)
            .help("关闭提示")
        }
        .padding(12)
        .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.red.opacity(0.22), lineWidth: 1)
        )
    }
}
