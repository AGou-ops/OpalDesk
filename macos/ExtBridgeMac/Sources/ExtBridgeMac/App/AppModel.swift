import AppKit
import ExtBridgeCore
import Foundation
import Observation

public enum ActiveTransportKind: Sendable, Equatable {
    case wiredUSB(serial: String, localPort: UInt16)
    case wirelessADB(serial: String, localPort: UInt16)
    case wirelessWiFi(displayTarget: String, host: String?, port: UInt16?)

    public var shortBadge: String {
        switch self {
        case .wiredUSB: return "USB 有线"
        case .wirelessADB: return "无线 ADB"
        case .wirelessWiFi: return "Wi-Fi 直连"
        }
    }

    public var systemImage: String {
        switch self {
        case .wiredUSB: return "cable.connector"
        case .wirelessADB: return "wifi"
        case .wirelessWiFi: return "wifi"
        }
    }

    public var isWirelessTransport: Bool {
        switch self {
        case .wiredUSB: return false
        case .wirelessADB, .wirelessWiFi: return true
        }
    }

    public var transportHint: ExtCastTransportHint {
        switch self {
        case .wiredUSB: return .usb
        case .wirelessADB: return .wifiAdb
        case .wirelessWiFi: return .wifiTls
        }
    }
}

public struct ActiveConnectionInfo: Sendable, Equatable {
    public var deviceName: String
    public var model: String
    public var transport: ActiveTransportKind
    public var fingerprint: String
    public var connectedAt: Date
}

public struct PairingPromptContext: Identifiable, Sendable, Equatable {
    public enum Source: Sendable, Equatable {
        case bonjour(DiscoveredBonjourDevice)
        case manualHostPort(host: String, port: UInt16)
    }

    public let id = UUID()
    public let source: Source
    public let displayName: String
    public let model: String
    public let expectedFingerprint: String
    public var errorMessage: String?
    public var isSubmitting: Bool = false

    public var shortFingerprint: String {
        FingerprintUtil.formatShort(expectedFingerprint)
    }
}

/// Central `@MainActor` state coordinator for `ExtBridgeMac`.
@MainActor
@Observable
public final class AppModel {
    public let preferences: PreferencesStore
    public let trustStore: TrustStore
    public let adbManager: AdbManager
    public let bonjourBrowser: BonjourBrowser
    public let session: ExtCastSession
    public let videoRenderer: VideoDisplayRenderer
    public let audioPlayer: AudioStreamPlayer
    public let clipboardSync: ClipboardSyncManager
    public let fileSender: FileSender
    public let popoutManager: PopoutWindowManager

    // Discovery state
    public var usbDevices: [AdbDevice] = []
    public var isScanningUSB: Bool = false
    public var adbBinaryPath: String?
    public var bonjourDevices: [DiscoveredBonjourDevice] = []
    public var trustedDevices: [TrustedDevice] = []

    // Active connection & stream state
    public var activeConnection: ActiveConnectionInfo?
    public var sessionState: ExtCastSessionState = .idle
    public var isAuthenticated: Bool = false
    public var isConnectingFlow: Bool = false
    public var deviceState = DeviceStatePayload()
    public var videoMetrics = VideoRendererMetrics()
    public var rttMs: Double = 0
    public var isBlackoutActive: Bool = false
    public var isAudioMuted: Bool = false
    public var displayMode: ExtCastDisplayMode = .dex
    public var selectedPreset: DisplayResolutionPreset = .matchWindow
    public var selectedDPI: Int = 240

    // Quick Launcher & remote apps state
    public var isQuickLauncherPresented: Bool = false
    public var remoteApps: [RemoteAppInfo] = []
    public var isLoadingApps: Bool = false

    // Modals, transfers & notifications
    public var pairingContext: PairingPromptContext?
    public var isProbingManualHost: Bool = false
    public var activeTransfers: [FileTransferProgress] = []
    public var bannerError: String?
    public var statusMessage: String?
    public var toastNotice: String?

    private var activeAdbForward: AdbForwardHandle?
    private var activeSessionEndpoint: ExtCastEndpoint?
    private var pendingAuthToken: String = ""
    private var pendingPinCode: String = ""
    private var pendingHostForTrust: String?
    private var pendingPortForTrust: UInt16?
    private var pendingDeviceModelForTrust: String = ""
    private var pendingDeviceNameForTrust: String = ""
    private var pendingPairingSource: PairingPromptContext.Source?

    // Wireless ADB -> Wi-Fi TLS seamless auto-upgrade state
    private var hasRequestedWifiUpgrade: Bool = false
    private var isUpgradingToWifiTls: Bool = false
    private var upgradeProbeTask: Task<Void, Never>?
    private var fallbackAdbForward: AdbForwardHandle?
    private var fallbackAdbTransport: ActiveTransportKind?

    private var usbPollTask: Task<Void, Never>?
    private var resizeDebounceTask: Task<Void, Never>?
    private var toastClearTask: Task<Void, Never>?
    private var lastRequestedSize: (width: Int, height: Int, dpi: Int)?
    private var lastCanvasSize: CGSize = CGSize(width: 1280, height: 720)
    private var lastBackingScale: CGFloat = 2.0
    private var nextInputTextSeq: Int64 = 1

    public init() {
        let prefs = PreferencesStore()
        let store = TrustStore()
        let adb = AdbManager(customAdbPath: prefs.customAdbPath)

        self.preferences = prefs
        self.trustStore = store
        self.adbManager = adb
        self.bonjourBrowser = BonjourBrowser()
        self.session = ExtCastSession(trustStore: store)
        self.videoRenderer = VideoDisplayRenderer()
        self.audioPlayer = AudioStreamPlayer()
        self.clipboardSync = ClipboardSyncManager()
        self.fileSender = FileSender()
        self.popoutManager = PopoutWindowManager()

        self.displayMode = prefs.defaultDisplayMode
        self.selectedPreset = prefs.resolutionPreset
        self.selectedDPI = prefs.defaultDPI
        self.isAudioMuted = !prefs.audioEnabled
        self.trustedDevices = store.allDevices()
        self.adbBinaryPath = adb.locateAdb()

        configureCallbacks()
        startDiscovery()
    }

    public var isShowingDisplayCanvas: Bool {
        activeConnection != nil && (sessionState == .connected || sessionState == .connecting || isReconnecting || isUpgradingToWifiTls)
    }

    public var isReconnecting: Bool {
        if case .reconnecting = sessionState { return true }
        return false
    }

    public var currentVideoWidth: Int {
        if videoMetrics.width > 0 { return videoMetrics.width }
        if deviceState.width > 0 { return deviceState.width }
        return 1920
    }

    public var currentVideoHeight: Int {
        if videoMetrics.height > 0 { return videoMetrics.height }
        if deviceState.height > 0 { return deviceState.height }
        return 1080
    }

    public var injectStatus: ExtCastInjectStatus {
        ExtCastInjectStatus(rawStatus: deviceState.injectStatus)
    }

    // MARK: - Discovery & Settings Sync

    public func startDiscovery() {
        refreshAdbPath()
        bonjourBrowser.start { [weak self] devices in
            Task { @MainActor [weak self] in
                self?.bonjourDevices = devices
            }
        }
        startUSBPolling()
    }

    public func refreshAdbPath() {
        adbManager.setCustomAdbPath(preferences.customAdbPath)
        adbBinaryPath = adbManager.locateAdb()
    }

    public func refreshUSBDevices() async {
        refreshAdbPath()
        guard adbBinaryPath != nil else {
            usbDevices = []
            return
        }
        isScanningUSB = true
        defer { isScanningUSB = false }
        do {
            usbDevices = try await adbManager.listDevices()
        } catch {
            usbDevices = []
        }
    }

    public func refreshTrustedDevices() {
        trustedDevices = trustStore.allDevices()
    }

    public func revokeTrustedDevice(_ device: TrustedDevice) {
        try? trustStore.revoke(id: device.id)
        refreshTrustedDevices()
    }

    public func revokeAllTrustedDevices() {
        try? trustStore.revokeAll()
        refreshTrustedDevices()
    }

    public func applyClipboardSyncPreference() {
        clipboardSync.isEnabled = preferences.clipboardSyncEnabled
    }

    public func showToast(_ message: String, durationSeconds: Double = 3.2) {
        toastClearTask?.cancel()
        toastNotice = message
        toastClearTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(durationSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.toastNotice = nil
        }
    }

    // MARK: - Wired USB / Wireless ADB Connection

    public func connectWiredUSB(_ device: AdbDevice) async {
        guard device.isReady else {
            bannerError = "设备 \(device.displayName) 当前状态为「\(device.state.displayLabel)」，请先在手机上允许 USB 调试"
            return
        }
        guard !isConnectingFlow else { return }

        bannerError = nil
        statusMessage = device.isWirelessAdb
            ? "正在通过无线 ADB 拉起守护进程并准备升级 Wi-Fi 直连..."
            : "正在拉起手机端 ExtBridge 守护进程并建立 USB 隧道..."
        isConnectingFlow = true

        await teardownCurrentSession(sendRemoteDisconnect: false)

        do {
            let forward = try await adbManager.bootstrapDaemon(serial: device.serial)
            activeAdbForward = forward

            pendingAuthToken = ""
            pendingPinCode = ""
            pendingHostForTrust = nil
            pendingPortForTrust = nil
            pendingDeviceNameForTrust = device.displayName
            pendingDeviceModelForTrust = device.model
            pendingPairingSource = nil
            hasRequestedWifiUpgrade = false
            isUpgradingToWifiTls = false

            let transport: ActiveTransportKind = device.isWirelessAdb
                ? .wirelessADB(serial: device.serial, localPort: forward.localPort)
                : .wiredUSB(serial: device.serial, localPort: forward.localPort)
            activeConnection = ActiveConnectionInfo(
                deviceName: device.displayName,
                model: device.model,
                transport: transport,
                fingerprint: "",
                connectedAt: Date()
            )
            displayMode = preferences.defaultDisplayMode
            selectedPreset = preferences.resolutionPreset
            selectedDPI = preferences.defaultDPI
            isAudioMuted = !preferences.audioEnabled
            audioPlayer.setMuted(isAudioMuted)

            let endpoint = ExtCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: forward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            )
            activeSessionEndpoint = endpoint
            session.connect(to: endpoint, onEvent: makeSessionEventHandler())
            statusMessage = nil
        } catch {
            isConnectingFlow = false
            statusMessage = nil
            bannerError = error.localizedDescription
            await teardownCurrentSession(sendRemoteDisconnect: false)
        }
    }

    // MARK: - Wireless Wi-Fi Connection & TOFU Pairing

    public func connectBonjourDevice(_ device: DiscoveredBonjourDevice) {
        bannerError = nil
        if let trusted = trustStore.find(byFingerprint: device.fingerprintPrefix), !trusted.authToken.isEmpty {
            openWirelessSession(
                source: .bonjour(device),
                displayName: device.displayName,
                model: device.model,
                expectedFingerprint: trusted.certFingerprint,
                authToken: trusted.authToken,
                pinCode: ""
            )
            return
        }

        if device.fingerprintPrefix.count < FingerprintUtil.minimumPrefixHexLength {
            Task {
                await probeAndPromptBonjour(device)
            }
            return
        }

        pairingContext = PairingPromptContext(
            source: .bonjour(device),
            displayName: device.displayName,
            model: device.model,
            expectedFingerprint: device.fingerprintPrefix
        )
    }

    public func connectManualHost(host: String, port: UInt16) async {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            bannerError = "请输入有效的局域网 IP 地址或主机名"
            return
        }

        bannerError = nil
        if let trusted = trustStore.find(byHost: trimmedHost, port: port), !trusted.authToken.isEmpty {
            openWirelessSession(
                source: .manualHostPort(host: trimmedHost, port: port),
                displayName: trusted.deviceName,
                model: trusted.model,
                expectedFingerprint: trusted.certFingerprint,
                authToken: trusted.authToken,
                pinCode: ""
            )
            return
        }

        isProbingManualHost = true
        statusMessage = "正在安全探测 \(trimmedHost):\(port) 的 TLS 证书指纹..."
        defer {
            isProbingManualHost = false
            statusMessage = nil
        }

        do {
            let fingerprint = try await ExtCastSession.probeCertificateFingerprint(host: trimmedHost, port: port)
            if let trusted = trustStore.find(byFingerprint: fingerprint), !trusted.authToken.isEmpty {
                openWirelessSession(
                    source: .manualHostPort(host: trimmedHost, port: port),
                    displayName: trusted.deviceName,
                    model: trusted.model,
                    expectedFingerprint: trusted.certFingerprint,
                    authToken: trusted.authToken,
                    pinCode: ""
                )
                return
            }

            pairingContext = PairingPromptContext(
                source: .manualHostPort(host: trimmedHost, port: port),
                displayName: "\(trimmedHost):\(port)",
                model: "手动无线主机",
                expectedFingerprint: fingerprint
            )
        } catch {
            bannerError = error.localizedDescription
        }
    }

    public func submitPairing(pinCode: String) {
        guard var context = pairingContext else { return }
        let cleanedPin = pinCode.filter(\.isNumber)
        guard cleanedPin.count == 6 else {
            context.errorMessage = "请输入手机端显示的 6 位数字配对 PIN 码"
            pairingContext = context
            return
        }
        context.errorMessage = nil
        context.isSubmitting = true
        pairingContext = context

        openWirelessSession(
            source: context.source,
            displayName: context.displayName,
            model: context.model,
            expectedFingerprint: context.expectedFingerprint,
            authToken: "",
            pinCode: cleanedPin
        )
    }

    public func cancelPairing() {
        pairingContext = nil
        if !isAuthenticated {
            Task {
                await teardownCurrentSession(sendRemoteDisconnect: false)
            }
        }
    }

    public func disconnect() {
        Task {
            await teardownCurrentSession(sendRemoteDisconnect: true)
        }
    }

    public func cleanupOnTerminate() {
        usbPollTask?.cancel()
        resizeDebounceTask?.cancel()
        upgradeProbeTask?.cancel()
        toastClearTask?.cancel()
        popoutManager.closeAll(sendDisconnect: false)
        bonjourBrowser.stop()
        clipboardSync.stop()
        audioPlayer.stop()
        session.disconnect()

        let forwardsToClean = [activeAdbForward, fallbackAdbForward].compactMap { $0 }
        activeAdbForward = nil
        fallbackAdbForward = nil

        if !forwardsToClean.isEmpty {
            let manager = adbManager
            let sem = DispatchSemaphore(value: 0)
            Task.detached {
                for forward in forwardsToClean {
                    await manager.removeForward(forward)
                }
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 1.5)
        }
    }

    // MARK: - Standalone Popout Windows & Quick Launcher (v4.1)

    public func openQuickLauncher() {
        guard isAuthenticated else { return }
        isQuickLauncherPresented = true
        requestAppList()
    }

    public func toggleQuickLauncher() {
        guard isAuthenticated else { return }
        if isQuickLauncherPresented {
            isQuickLauncherPresented = false
        } else {
            openQuickLauncher()
        }
    }

    public func requestAppList() {
        guard isAuthenticated else { return }
        isLoadingApps = true
        session.send(.listApps())
    }

    public func popoutCurrentTopApp() {
        guard isAuthenticated else { return }
        let pkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pkg.isEmpty else {
            openQuickLauncher()
            return
        }
        let label = deviceState.topAppLabel.isEmpty ? pkg : deviceState.topAppLabel
        let matching = remoteApps.first(where: { $0.pkg == pkg })
        popoutApp(
            package: pkg,
            activity: matching?.cls ?? "",
            label: label,
            taskId: matching?.taskId ?? -1
        )
    }

    public func popoutApp(_ app: RemoteAppInfo) {
        popoutApp(
            package: app.pkg,
            activity: app.cls,
            label: app.displayLabel,
            taskId: app.taskId
        )
    }

    public func popoutApp(package: String, activity: String = "", label: String = "", taskId: Int = -1) {
        guard let context = buildPopoutConnectionContext() else {
            showToast("请先连接并完成手机鉴权后再弹出独立窗口")
            return
        }
        let result = popoutManager.openPopout(
            appPackage: package,
            appActivity: activity,
            appLabel: label.isEmpty ? package : label,
            taskId: taskId,
            context: context,
            trustStore: trustStore,
            onFilesDropped: { [weak self] urls in
                self?.sendFiles(urls)
            }
        )
        switch result {
        case .opened:
            isQuickLauncherPresented = false
        case .focusedExisting:
            isQuickLauncherPresented = false
        case .limitReached(let maxCount):
            showToast("最多同时支持 \(maxCount) 个独立弹出 App 窗口，请先关闭或收回其中一个")
        }
    }

    private func buildPopoutConnectionContext() -> PopoutConnectionContext? {
        guard isAuthenticated, let conn = activeConnection, let endpoint = activeSessionEndpoint else {
            return nil
        }
        let token: String
        if case let .wirelessWiFi(_, host, port) = conn.transport {
            if !pendingAuthToken.isEmpty {
                token = pendingAuthToken
            } else if !conn.fingerprint.isEmpty, let trusted = trustStore.find(byFingerprint: conn.fingerprint) {
                token = trusted.authToken
            } else if let host, let port, let trusted = trustStore.find(byHost: host, port: port) {
                token = trusted.authToken
            } else {
                token = ""
            }
        } else {
            token = ""
        }
        return PopoutConnectionContext(
            endpoint: endpoint,
            transportHint: conn.transport.transportHint,
            clientId: trustStore.clientId,
            authToken: token,
            preferredCodec: preferences.preferredCodec,
            targetFPS: preferences.targetFPS,
            bitrateKbps: effectiveBitrateKbps(),
            mapCommandToCtrl: preferences.mapCommandToCtrl
        )
    }

    // MARK: - Window Layout & Input Engine Controls (`0x2B WINDOW_ACTION`)

    public func sendWindowAction(_ payload: WindowActionPayload) {
        guard isAuthenticated else { return }
        if let frame = try? ExtCastFrame.windowAction(payload) {
            session.send(frame)
        }
    }

    public func setAdaptiveSingle(enabled: Bool, defaultWidthDp: Int) {
        let clampedDp = min(max(defaultWidthDp, 320), 1200)
        preferences.adaptiveSingleEnabled = enabled
        preferences.defaultSingleWidthDp = clampedDp
        deviceState.adaptiveSingleEnabled = enabled
        deviceState.defaultSingleWidthDp = clampedDp
        sendWindowAction(.setAdaptiveSingle(enabled: enabled, defaultWidthDp: clampedDp))
    }

    public func setSingleWidthPreset(widthPct: Int, remember: Bool = true) {
        let clampedPct = min(max(widthPct, 28), 100)
        sendWindowAction(.setSingleWidth(pkg: deviceState.topAppPackage, widthPct: clampedPct, remember: remember))
        showToast("已调整单应用宽度为 \(clampedPct)%")
    }

    public func launchApp(_ app: RemoteAppInfo, mode: ExtCastLaunchMode = .adaptive, slot: Int = 0) {
        sendWindowAction(.launchApp(pkg: app.pkg, cls: app.cls, mode: mode, slot: slot))
        isQuickLauncherPresented = false
    }

    public func setTilingTopology(_ topology: ExtCastTilingTopology, pkgs: [String] = [], ratios: [Int]? = nil) {
        sendWindowAction(.setTiling(topology: topology, pkgs: pkgs, ratios: ratios))
        showToast(topology == .exit ? "已退出分屏布局" : "已切换为「\(topology.displayName)」")
    }

    public func reloadCurrentAppDPI() {
        let pkg = deviceState.topAppPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pkg.isEmpty else {
            showToast("当前未检测到可重载 DPI 的前台应用")
            return
        }
        sendWindowAction(.reloadAppDpi(pkg: pkg))
        let name = deviceState.topAppLabel.isEmpty ? pkg : deviceState.topAppLabel
        showToast("正在重载「\(name)」的显示 DPI...")
    }

    public func setInputBackend(_ backend: ExtCastInputBackend) {
        preferences.inputBackend = backend
        sendWindowAction(.setInputBackend(backend))
        if isAuthenticated {
            showToast("输入引擎已切换为：\(backend.displayName)")
        }
    }

    // MARK: - Display & System Controls

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= 200, viewSize.height >= 200 else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale

        guard isAuthenticated, selectedPreset == .matchWindow else { return }
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000) // 300ms debounce
            guard !Task.isCancelled, let self else { return }
            let maxEdge = self.effectiveAdaptiveMaxLongEdge()
            let target = CoordinateMapper.alignedAdaptiveResolution(
                viewSize: viewSize,
                backingScale: backingScale,
                maxLongEdge: maxEdge
            )
            self.sendResizeIfNeeded(width: target.width, height: target.height, dpi: self.selectedDPI)
        }
    }

    private func effectiveAdaptiveMaxLongEdge() -> Int {
        if case .wirelessADB = activeConnection?.transport {
            return 1920
        }
        return 2560
    }

    private func effectiveBitrateKbps() -> Int {
        if case .wirelessADB = activeConnection?.transport {
            return min(preferences.bitrateKbps, 10_000)
        }
        return preferences.bitrateKbps
    }

    public func selectResolutionPreset(_ preset: DisplayResolutionPreset) {
        selectedPreset = preset
        preferences.resolutionPreset = preset
        guard isAuthenticated else { return }

        if let fixed = preset.fixedSize {
            sendResizeIfNeeded(width: fixed.width, height: fixed.height, dpi: selectedDPI, force: true)
        } else {
            let target = CoordinateMapper.alignedAdaptiveResolution(
                viewSize: lastCanvasSize,
                backingScale: lastBackingScale,
                maxLongEdge: effectiveAdaptiveMaxLongEdge()
            )
            sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: true)
        }
    }

    public func selectDPI(_ dpi: Int) {
        selectedDPI = dpi
        preferences.defaultDPI = dpi
        guard isAuthenticated else { return }

        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: dpi, force: true)
    }

    public func applyTargetFPS(_ fps: Int) {
        preferences.targetFPS = fps
        guard isAuthenticated else { return }
        let target = resolveCurrentTargetDimensions()
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: true)
    }

    private func resolveCurrentTargetDimensions() -> (width: Int, height: Int) {
        if let fixed = selectedPreset.fixedSize {
            return fixed
        }
        return CoordinateMapper.alignedAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
    }

    public func toggleDisplayMode() {
        let nextMode: ExtCastDisplayMode = (displayMode == .dex) ? .mirror : .dex
        displayMode = nextMode
        sendSystemAction(.toggleDisplayMode, arg: Int32(nextMode.rawValue))
    }

    public func toggleBlackout() {
        isBlackoutActive.toggle()
        sendSystemAction(.toggleBlackout, arg: isBlackoutActive ? 1 : 0)
    }

    public func toggleAudio() {
        isAudioMuted.toggle()
        audioPlayer.setMuted(isAudioMuted)
        sendSystemAction(.setAudioEnabled, arg: isAudioMuted ? 0 : 1)
    }

    public func rotateClockwise() {
        sendSystemAction(.rotateCW, arg: 0)
    }

    public func sendAndroidHome() {
        sendSystemAction(.home, arg: 0)
    }

    public func sendAndroidBack() {
        sendSystemAction(.back, arg: 0)
    }

    public func sendAndroidRecents() {
        sendSystemAction(.recents, arg: 0)
    }

    public func requestIDR() {
        session.send(.requestIdr())
    }

    public func handleThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection) {
        guard isAuthenticated else { return }
        switch direction {
        case .left, .right:
            sendAndroidBack()
        case .down:
            sendAndroidHome()
        case .up:
            sendAndroidRecents()
        }
    }

    // MARK: - Input & File Forwarding

    public func sendMouse(_ payload: InputMousePayload) {
        guard isAuthenticated else { return }
        let droppable = payload.action == .moveAbs
        session.send(.inputMouse(payload), allowDropOnCongestion: droppable)
    }

    public func sendTouch(_ payload: InputTouchPayload) {
        guard isAuthenticated else { return }
        session.send(.inputTouch(payload))
    }

    public func sendKey(_ payload: InputKeyPayload) {
        guard isAuthenticated else { return }
        session.send(.inputKey(payload))
    }

    public func sendCommittedText(_ text: String) {
        guard isAuthenticated, !text.isEmpty else { return }
        let seq = nextInputTextSeq
        nextInputTextSeq += 1
        let payload = InputTextPayload(mode: .commitTextPaste, seq: seq, text: text)
        session.send(.inputText(payload))
    }

    public func sendFiles(_ urls: [URL]) {
        guard isAuthenticated else { return }
        for url in urls {
            do {
                try fileSender.sendFile(
                    at: url,
                    sendFrame: { [weak self] frame in
                        self?.session.send(frame)
                    },
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in
                            self?.upsertTransferProgress(progress)
                        }
                    }
                )
            } catch {
                bannerError = error.localizedDescription
            }
        }
    }

    public func cancelFileTransfer(_ transferId: UInt32) {
        fileSender.cancel(
            transferId: transferId,
            sendFrame: { [weak self] frame in
                self?.session.send(frame)
            },
            onProgress: { [weak self] progress in
                Task { @MainActor [weak self] in
                    self?.upsertTransferProgress(progress)
                }
            }
        )
    }

    public func dismissTransfer(_ transferId: UInt32) {
        activeTransfers.removeAll { $0.transferId == transferId }
    }

    // MARK: - Private Implementation

    private func configureCallbacks() {
        videoRenderer.setCallbacks(
            onRequestIDR: { [weak self] in
                self?.session.send(.requestIdr())
            },
            onMetricsUpdate: { [weak self] metrics in
                Task { @MainActor [weak self] in
                    self?.videoMetrics = metrics
                }
            }
        )
    }

    private func startUSBPolling() {
        usbPollTask?.cancel()
        usbPollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                if !self.isShowingDisplayCanvas {
                    await self.refreshUSBDevices()
                }
                let sleepSeconds: UInt64 = self.isShowingDisplayCanvas ? 10 : 3
                try? await Task.sleep(nanoseconds: sleepSeconds * 1_000_000_000)
            }
        }
    }

    private func probeAndPromptBonjour(_ device: DiscoveredBonjourDevice) async {
        isProbingManualHost = true
        statusMessage = "正在探测 \(device.displayName) 的证书指纹..."
        defer {
            isProbingManualHost = false
            statusMessage = nil
        }
        do {
            let fp = try await ExtCastSession.probeCertificateFingerprint(nwEndpoint: device.nwEndpoint)
            pairingContext = PairingPromptContext(
                source: .bonjour(device),
                displayName: device.displayName,
                model: device.model,
                expectedFingerprint: fp
            )
        } catch {
            bannerError = error.localizedDescription
        }
    }

    private func openWirelessSession(
        source: PairingPromptContext.Source,
        displayName: String,
        model: String,
        expectedFingerprint: String,
        authToken: String,
        pinCode: String
    ) {
        Task { [weak self] in
            guard let self else { return }
            await self.teardownCurrentSession(sendRemoteDisconnect: false)

            self.isConnectingFlow = true
            self.pendingAuthToken = authToken
            self.pendingPinCode = pinCode
            self.pendingDeviceNameForTrust = displayName
            self.pendingDeviceModelForTrust = model
            self.pendingPairingSource = source
            self.hasRequestedWifiUpgrade = false
            self.isUpgradingToWifiTls = false

            let target: ExtCastEndpoint.Target
            let transportKind: ActiveTransportKind

            switch source {
            case .bonjour(let bonjourDevice):
                target = .bonjour(serviceName: bonjourDevice.serviceName, domain: bonjourDevice.domain)
                transportKind = .wirelessWiFi(displayTarget: bonjourDevice.displayName, host: nil, port: nil)
                self.pendingHostForTrust = nil
                self.pendingPortForTrust = nil
            case let .manualHostPort(host, port):
                target = .hostPort(host: host, port: port)
                transportKind = .wirelessWiFi(displayTarget: "\(host):\(port)", host: host, port: port)
                self.pendingHostForTrust = host
                self.pendingPortForTrust = port
            }

            self.activeConnection = ActiveConnectionInfo(
                deviceName: displayName,
                model: model,
                transport: transportKind,
                fingerprint: expectedFingerprint,
                connectedAt: Date()
            )
            self.displayMode = self.preferences.defaultDisplayMode
            self.selectedPreset = self.preferences.resolutionPreset
            self.selectedDPI = self.preferences.defaultDPI
            self.isAudioMuted = !self.preferences.audioEnabled
            self.audioPlayer.setMuted(self.isAudioMuted)

            let endpoint = ExtCastEndpoint(
                target: target,
                security: .tlsPinned(expectedFingerprint: expectedFingerprint),
                autoReconnect: pinCode.isEmpty // Only auto-reconnect after token-based trust is established
            )
            self.activeSessionEndpoint = endpoint
            self.session.connect(to: endpoint, onEvent: self.makeSessionEventHandler())
        }
    }

    /// Fast-path event router: dispatches high-frequency video/audio frames synchronously on `networkQueue`
    /// in strict FIFO order without bouncing through `@MainActor` (preventing HEVC frame reordering and UI jank).
    private func makeSessionEventHandler() -> @Sendable (ExtCastSessionEvent) -> Void {
        let renderer = self.videoRenderer
        let audio = self.audioPlayer
        return { [weak self] event in
            if case .frame(let frame) = event {
                switch frame.type {
                case .videoFrame:
                    let flags = ExtCastVideoFrameFlags(rawValue: frame.flags)
                    renderer.enqueueFrame(payload: frame.payload, flags: flags, timestampUs: frame.timestampUs)
                    return

                case .videoConfig:
                    if let config = try? VideoConfigPayload.decode(from: frame.payload) {
                        renderer.configure(config)
                        let mode = config.displayMode
                        Task { @MainActor [weak self] in
                            self?.displayMode = mode
                        }
                    }
                    return

                case .audioConfig:
                    if let config = try? AudioConfigPayload.decode(from: frame.payload) {
                        audio.configure(config)
                    }
                    return

                case .audioFrame:
                    audio.enqueuePacket(frame.payload)
                    return

                case .pong:
                    return

                default:
                    break
                }
            }
            Task { @MainActor [weak self] in
                self?.handleSessionEvent(event)
            }
        }
    }

    private func handleSessionEvent(_ event: ExtCastSessionEvent) {
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                sendHelloHandshake()
            case .disconnected:
                if isUpgradingToWifiTls {
                    return
                }
                isConnectingFlow = false
                isAuthenticated = false
                clipboardSync.stop()
                audioPlayer.stop()
            case .failed(let message):
                if isUpgradingToWifiTls {
                    revertFailedWifiUpgradeSilently()
                    return
                }
                isConnectingFlow = false
                isAuthenticated = false
                if var ctx = pairingContext {
                    ctx.isSubmitting = false
                    ctx.errorMessage = message
                    pairingContext = ctx
                } else {
                    bannerError = message
                    activeConnection = nil
                }
            default:
                break
            }

        case .verifiedPeerFingerprint(let fullHex):
            if activeConnection != nil {
                activeConnection?.fingerprint = fullHex
            }

        case .rttUpdated(let rtt):
            rttMs = rtt

        case .error(let sessionError):
            if !isUpgradingToWifiTls, case .certificateFingerprintMismatch = sessionError {
                bannerError = sessionError.localizedDescription
            }

        case .frame(let frame):
            handleIncomingFrame(frame)
        }
    }

    private func sendHelloHandshake() {
        let initialSize = resolveCurrentTargetDimensions()
        lastRequestedSize = (initialSize.width, initialSize.height, selectedDPI)
        let transportHint = activeConnection?.transport.transportHint ?? .usb

        let hello = HelloPayload(
            protocolVersion: ExtCastProtocol.version,
            clientName: Host.current().localizedName ?? ProcessInfo.processInfo.hostName,
            clientId: trustStore.clientId,
            preferredCodec: preferences.preferredCodec.preferredName,
            width: initialSize.width,
            height: initialSize.height,
            dpi: selectedDPI,
            fps: preferences.targetFPS,
            bitrateKbps: effectiveBitrateKbps(),
            displayMode: Int(displayMode.rawValue),
            audioEnabled: !isAudioMuted,
            authToken: pendingAuthToken,
            pinCode: pendingPinCode,
            transportHint: transportHint.rawValue,
            sessionRole: ExtCastSessionRole.desktop.rawValue,
            appPackage: "",
            appActivity: ""
        )

        if let frame = try? ExtCastFrame.hello(hello) {
            session.send(frame)
        }
    }

    private func handleIncomingFrame(_ frame: ExtCastFrame) {
        switch frame.type {
        case .videoConfig:
            if let config = try? VideoConfigPayload.decode(from: frame.payload) {
                videoRenderer.configure(config)
                displayMode = config.displayMode
            }

        case .videoFrame:
            let flags = ExtCastVideoFrameFlags(rawValue: frame.flags)
            videoRenderer.enqueueFrame(payload: frame.payload, flags: flags, timestampUs: frame.timestampUs)

        case .audioConfig:
            if let config = try? AudioConfigPayload.decode(from: frame.payload) {
                audioPlayer.configure(config)
            }

        case .audioFrame:
            audioPlayer.enqueuePacket(frame.payload)

        case .deviceState:
            if let state = try? DeviceStatePayload.decode(from: frame.payload) {
                deviceState = state
                isBlackoutActive = state.blackout
                if let mode = ExtCastDisplayMode(rawValue: UInt8(clamping: state.displayMode)), mode != .unchanged {
                    displayMode = mode
                }
                if !state.deviceName.isEmpty {
                    activeConnection?.deviceName = state.deviceName
                }
            }

        case .clipboardPush:
            if (frame.flags & ExtCastProtocol.flagClipboardImagePng) != 0 {
                if let clipImg = try? ClipboardImagePushPayload.decode(from: frame.payload) {
                    clipboardSync.applyRemoteClipboardImage(seq: clipImg.seq, pngData: clipImg.pngData)
                }
            } else {
                if let clip = try? ClipboardPushPayload.decode(from: frame.payload) {
                    clipboardSync.applyRemoteClipboard(seq: clip.seq, text: clip.text)
                }
            }

        case .authResult:
            if let auth = try? AuthResultPayload.decode(from: frame.payload) {
                handleAuthResult(auth)
            }

        case .fileTransferAck:
            if let ack = try? FileTransferAckPayload.decode(from: frame.payload) {
                fileSender.handleAck(ack) { [weak self] progress in
                    Task { @MainActor [weak self] in
                        self?.upsertTransferProgress(progress)
                    }
                }
            }

        case .wifiUpgradeReady:
            if let ready = try? WifiUpgradeReadyPayload.decode(from: frame.payload) {
                handleWifiUpgradeReady(ready)
            }

        case .appList:
            if let list = try? AppListPayload.decode(from: frame.payload) {
                isLoadingApps = false
                remoteApps = list.apps.sorted { lhs, rhs in
                    if lhs.runningOnExt != rhs.runningOnExt {
                        return lhs.runningOnExt && !rhs.runningOnExt
                    }
                    return lhs.displayLabel.localizedCaseInsensitiveCompare(rhs.displayLabel) == .orderedAscending
                }
            }

        case .pong:
            break

        default:
            break
        }
    }

    private func handleAuthResult(_ auth: AuthResultPayload) {
        isConnectingFlow = false
        if auth.ok {
            isAuthenticated = true
            pairingContext = nil
            bannerError = nil

            let resolvedName = auth.deviceName.isEmpty ? pendingDeviceNameForTrust : auth.deviceName
            if !resolvedName.isEmpty {
                activeConnection?.deviceName = resolvedName
            }

            let resolvedFp = FingerprintUtil.normalize(
                session.verifiedPeerFingerprint ?? (auth.certFingerprint.isEmpty ? (activeConnection?.fingerprint ?? "") : auth.certFingerprint)
            )

            // Persist wireless trust token & full 64-char SHA-256 fingerprint on successful auth.
            if !resolvedFp.isEmpty && !auth.authToken.isEmpty {
                let deviceId = String(resolvedFp.prefix(16))
                let record = TrustedDevice(
                    id: deviceId,
                    deviceName: resolvedName.isEmpty ? "Android 设备" : resolvedName,
                    model: pendingDeviceModelForTrust,
                    certFingerprint: resolvedFp,
                    authToken: auth.authToken,
                    lastHost: pendingHostForTrust,
                    lastPort: pendingPortForTrust
                )
                _ = try? trustStore.upsert(record)
                refreshTrustedDevices()
            }

            // Clear one-time PIN from memory after successful auth.
            pendingPinCode = ""
            if !auth.authToken.isEmpty {
                pendingAuthToken = auth.authToken
            }

            // Finalize wireless ADB -> native Wi-Fi TLS upgrade and release the obsolete ADB forward.
            if isUpgradingToWifiTls {
                isUpgradingToWifiTls = false
                let oldForward = fallbackAdbForward
                fallbackAdbForward = nil
                fallbackAdbTransport = nil
                activeAdbForward = nil
                if let oldForward {
                    Task { [adbManager] in
                        await adbManager.removeForward(oldForward)
                    }
                }
                showToast("已自动无缝切换至原生 Wi-Fi TLS 直连")
            } else if case .wirelessADB = activeConnection?.transport, !hasRequestedWifiUpgrade {
                // Automatically request native Wi-Fi TLS upgrade from an authenticated wireless ADB session.
                hasRequestedWifiUpgrade = true
                session.send(.requestWifiUpgrade())
            }

            startClipboardSyncIfEnabled()
            syncInitialDesktopPreferences()
            syncCanvasSizeAfterAuth()
        } else {
            if isUpgradingToWifiTls {
                revertFailedWifiUpgradeSilently()
                return
            }

            isAuthenticated = false
            let failureText = auth.message.isEmpty ? "身份认证被手机端拒绝，请核对 PIN 码后重试" : auth.message

            if var ctx = pairingContext {
                ctx.isSubmitting = false
                ctx.errorMessage = failureText
                pairingContext = ctx
                session.disconnect()
            } else if let source = pendingPairingSource {
                // Stored authToken was revoked on Android; prompt user for a fresh 6-digit PIN.
                let fp = session.verifiedPeerFingerprint ?? (activeConnection?.fingerprint ?? "")
                session.disconnect()
                activeConnection = nil
                pairingContext = PairingPromptContext(
                    source: source,
                    displayName: pendingDeviceNameForTrust,
                    model: pendingDeviceModelForTrust,
                    expectedFingerprint: fp,
                    errorMessage: "原有授权已失效，请输入手机端新显示的 6 位配对 PIN 码"
                )
            } else {
                bannerError = failureText
                Task {
                    await teardownCurrentSession(sendRemoteDisconnect: false)
                }
            }
        }
    }

    private func handleWifiUpgradeReady(_ ready: WifiUpgradeReadyPayload) {
        guard case .wirelessADB = activeConnection?.transport else { return }
        guard ready.ok,
              !ready.host.isEmpty,
              let port = ready.validPort,
              !ready.authToken.isEmpty
        else {
            return
        }

        let normalizedExpectedFp = FingerprintUtil.normalize(ready.certFingerprint)
        guard normalizedExpectedFp.count >= FingerprintUtil.minimumPrefixHexLength else {
            return
        }

        let currentDeviceName = activeConnection?.deviceName ?? pendingDeviceNameForTrust
        let currentModel = activeConnection?.model ?? pendingDeviceModelForTrust

        // Store trust credentials immediately so future Wi-Fi connects are zero-PIN.
        let initialRecord = TrustedDevice(
            id: String(normalizedExpectedFp.prefix(16)),
            deviceName: currentDeviceName.isEmpty ? "Android 设备" : currentDeviceName,
            model: currentModel,
            certFingerprint: normalizedExpectedFp,
            authToken: ready.authToken,
            lastHost: ready.host,
            lastPort: port
        )
        _ = try? trustStore.upsert(initialRecord)
        refreshTrustedDevices()

        // Probe LAN TLS reachability & verify certificate pin BEFORE touching the live wireless ADB session.
        // If LAN direct fails (AP isolation / firewall), wireless ADB stays 100% uninterrupted!
        upgradeProbeTask?.cancel()
        upgradeProbeTask = Task { [weak self] in
            guard let self else { return }
            do {
                let actualFullFp = try await ExtCastSession.probeCertificateFingerprint(
                    host: ready.host,
                    port: port,
                    timeoutSeconds: 3.5
                )
                guard !Task.isCancelled else { return }
                guard FingerprintUtil.matches(actualFullHex: actualFullFp, expected: normalizedExpectedFp) else {
                    return
                }
                guard case .wirelessADB = self.activeConnection?.transport, self.isAuthenticated else {
                    return
                }

                // Update TrustStore with exact full 64-char verified fingerprint.
                let verifiedRecord = TrustedDevice(
                    id: String(actualFullFp.prefix(16)),
                    deviceName: currentDeviceName.isEmpty ? "Android 设备" : currentDeviceName,
                    model: currentModel,
                    certFingerprint: actualFullFp,
                    authToken: ready.authToken,
                    lastHost: ready.host,
                    lastPort: port
                )
                _ = try? self.trustStore.upsert(verifiedRecord)
                self.refreshTrustedDevices()

                // Stage seamless handoff while retaining `activeAdbForward` as silent fallback until TLS auth succeeds.
                self.fallbackAdbForward = self.activeAdbForward
                self.fallbackAdbTransport = self.activeConnection?.transport
                self.isUpgradingToWifiTls = true
                self.pendingAuthToken = ready.authToken
                self.pendingPinCode = ""
                self.pendingHostForTrust = ready.host
                self.pendingPortForTrust = port

                self.activeConnection?.transport = .wirelessWiFi(
                    displayTarget: "\(ready.host):\(port)",
                    host: ready.host,
                    port: port
                )
                self.activeConnection?.fingerprint = actualFullFp

                let tlsEndpoint = ExtCastEndpoint(
                    target: .hostPort(host: ready.host, port: port),
                    security: .tlsPinned(expectedFingerprint: actualFullFp),
                    autoReconnect: true
                )
                self.activeSessionEndpoint = tlsEndpoint
                self.session.connect(to: tlsEndpoint, onEvent: self.makeSessionEventHandler())
            } catch {
                // LAN direct TLS unreachable: silently remain on the live wireless ADB tunnel without disruption.
            }
        }
    }

    private func revertFailedWifiUpgradeSilently() {
        isUpgradingToWifiTls = false
        let adbForward = fallbackAdbForward
        let adbTransport = fallbackAdbTransport
        fallbackAdbForward = nil
        fallbackAdbTransport = nil
        pendingAuthToken = ""
        pendingHostForTrust = nil
        pendingPortForTrust = nil

        if let adbTransport {
            activeConnection?.transport = adbTransport
        }
        if let adbForward {
            activeAdbForward = adbForward
            let adbEndpoint = ExtCastEndpoint(
                target: .hostPort(host: "127.0.0.1", port: adbForward.localPort),
                security: .wiredLocalhostPlaintext,
                autoReconnect: true
            )
            activeSessionEndpoint = adbEndpoint
            session.connect(to: adbEndpoint, onEvent: makeSessionEventHandler())
        }
    }

    private func syncInitialDesktopPreferences() {
        sendWindowAction(.setAdaptiveSingle(
            enabled: preferences.adaptiveSingleEnabled,
            defaultWidthDp: preferences.defaultSingleWidthDp
        ))
        if preferences.inputBackend != .auto {
            sendWindowAction(.setInputBackend(preferences.inputBackend))
        }
    }

    /// Immediately aligns the Android VirtualDisplay resolution to the actual macOS window canvas
    /// once authentication succeeds (and again after a Wireless ADB -> Wi-Fi TLS upgrade where max edge lifts to 2560px),
    /// eliminating black letterbox bars on initial connect.
    private func syncCanvasSizeAfterAuth() {
        guard isAuthenticated, selectedPreset == .matchWindow else { return }
        guard lastCanvasSize.width >= 200, lastCanvasSize.height >= 200 else { return }
        let target = CoordinateMapper.alignedAdaptiveResolution(
            viewSize: lastCanvasSize,
            backingScale: lastBackingScale,
            maxLongEdge: effectiveAdaptiveMaxLongEdge()
        )
        sendResizeIfNeeded(width: target.width, height: target.height, dpi: selectedDPI, force: false)
    }

    private func startClipboardSyncIfEnabled() {
        clipboardSync.isEnabled = preferences.clipboardSyncEnabled
        clipboardSync.start(
            onLocalChange: { [weak self] seq, text in
                guard let self, self.isAuthenticated else { return }
                let payload = InputTextPayload(mode: .setClipboardOnly, seq: seq, text: text)
                self.session.send(.inputText(payload))
            },
            onLocalImageChange: { [weak self] seq, pngData in
                guard let self, self.isAuthenticated else { return }
                let payload = InputImagePayload(mode: .setClipboardImageOnly, seq: seq, pngData: pngData)
                if let frame = try? ExtCastFrame.inputImage(payload) {
                    self.session.send(frame)
                }
            }
        )
    }

    private func sendResizeIfNeeded(width: Int, height: Int, dpi: Int, force: Bool = false) {
        let alignedW = CoordinateMapper.alignTo16(width)
        let alignedH = CoordinateMapper.alignTo16(height)

        if !force, let last = lastRequestedSize,
           last.width == alignedW && last.height == alignedH && last.dpi == dpi
        {
            return
        }
        lastRequestedSize = (alignedW, alignedH, dpi)

        let payload = ResizeConfigPayload(
            width: UInt16(clamping: alignedW),
            height: UInt16(clamping: alignedH),
            dpi: UInt16(clamping: dpi),
            fpsX100: UInt16(clamping: preferences.targetFPS * 100),
            bitrateKbps: UInt32(clamping: effectiveBitrateKbps()),
            displayMode: ResizeConfigPayload.unchangedByte,
            rotation: ResizeConfigPayload.unchangedByte
        )
        session.send(.resizeConfig(payload))
    }

    private func sendSystemAction(_ kind: ExtCastSystemActionKind, arg: Int32) {
        guard isAuthenticated else { return }
        session.send(.systemAction(SystemActionPayload(action: kind, arg: arg)))
    }

    private func upsertTransferProgress(_ progress: FileTransferProgress) {
        if let idx = activeTransfers.firstIndex(where: { $0.transferId == progress.transferId }) {
            activeTransfers[idx] = progress
        } else {
            activeTransfers.insert(progress, at: 0)
        }
        if activeTransfers.count > 6 {
            activeTransfers = Array(activeTransfers.prefix(6))
        }
    }

    private func teardownCurrentSession(sendRemoteDisconnect: Bool) async {
        resizeDebounceTask?.cancel()
        upgradeProbeTask?.cancel()
        upgradeProbeTask = nil
        isUpgradingToWifiTls = false
        hasRequestedWifiUpgrade = false
        isQuickLauncherPresented = false
        remoteApps.removeAll()
        isLoadingApps = false

        popoutManager.closeAll(sendDisconnect: sendRemoteDisconnect)

        if sendRemoteDisconnect && isAuthenticated {
            session.send(.systemAction(SystemActionPayload(action: .disconnect, arg: 0)))
            try? await Task.sleep(nanoseconds: 60_000_000)
        }

        clipboardSync.stop()
        audioPlayer.stop()
        videoRenderer.reset()
        session.disconnect()

        let forwards = [activeAdbForward, fallbackAdbForward].compactMap { $0 }
        activeAdbForward = nil
        fallbackAdbForward = nil
        fallbackAdbTransport = nil
        for forward in forwards {
            await adbManager.removeForward(forward)
        }

        activeSessionEndpoint = nil
        isConnectingFlow = false
        isAuthenticated = false
        activeConnection = nil
        sessionState = .idle
        rttMs = 0
        videoMetrics = VideoRendererMetrics()
        deviceState = DeviceStatePayload()
        lastRequestedSize = nil
        activeTransfers.removeAll()
    }
}
