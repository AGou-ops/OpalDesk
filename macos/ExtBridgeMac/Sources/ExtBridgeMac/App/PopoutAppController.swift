import AppKit
import ExtBridgeCore
import Foundation
import Observation
import os

/// Manages the lifecycle, video stream, input forwarding, and desktop window state
/// of one standalone popout Android app window.
@MainActor
@Observable
public final class PopoutAppController: Identifiable {
    private enum Tuning {
        static let defaultVideoWidth: Int = 848
        static let defaultVideoHeight: Int = 1680
        static let maxBitrateKbps: Int = 12_000
        static let minCanvasDimension: CGFloat = 200
        static let resizeDebounceNs: UInt64 = 260_000_000
        static let returnToDesktopFlushNs: UInt64 = 90_000_000
        static let firstFrameNudgeDelaysNs: [UInt64] = [900_000_000, 1_800_000_000]
        static let firstFrameTimeoutNs: UInt64 = 5_500_000_000
        static let presetMatchTolerancePt: CGFloat = 14
        static let screenEdgePaddingPt: CGFloat = 12
    }

    public let id = UUID()
    public let appPackage: String
    public let appActivity: String
    public var appLabel: String
    public var taskId: Int

    public let session: ExtCastSession
    public let streamBridge: StreamSessionBridge
    public let videoRenderer: VideoDisplayRenderer

    public var sessionState: ExtCastSessionState = .idle
    public var isAuthenticated: Bool = false
    public var isMigratingConnection: Bool = false
    public var deviceState = DeviceStatePayload()
    public var videoMetrics = VideoRendererMetrics()
    public var rttMs: Double = 0
    public var errorMessage: String?

    public var isPinnedOnTop: Bool = false
    public var windowOpacity: Double = DisplayPresets.popoutOpacityRange.upperBound
    public var activePreset: PopoutAspectPreset? = .phone
    public var mapCommandToCtrl: Bool

    public weak var hostWindow: NSWindow?
    public var onRequestClose: (() -> Void)?

    @ObservationIgnored private var connectionContext: PopoutConnectionContext
    @ObservationIgnored private var resizeDebounceTask: Task<Void, Never>?
    @ObservationIgnored private var firstFrameNudgeTask: Task<Void, Never>?
    @ObservationIgnored private var lastSentGeometry: PopoutStreamGeometry?
    @ObservationIgnored private var lastCanvasSize: CGSize = PopoutAspectPreset.phone.windowSize
    @ObservationIgnored private var lastBackingScale: CGFloat = 2.0
    @ObservationIgnored private var isClosing: Bool = false

    public init(
        appPackage: String,
        appActivity: String,
        appLabel: String,
        taskId: Int = -1,
        connectionContext: PopoutConnectionContext,
        trustStore: TrustStore
    ) {
        let extSession = ExtCastSession(trustStore: trustStore)
        self.appPackage = appPackage
        self.appActivity = appActivity
        self.appLabel = appLabel.isEmpty ? appPackage : appLabel
        self.taskId = taskId
        self.connectionContext = connectionContext
        self.mapCommandToCtrl = connectionContext.mapCommandToCtrl
        self.session = extSession
        self.streamBridge = StreamSessionBridge(session: extSession, logger: ExtBridgeLog.popout)
        self.videoRenderer = VideoDisplayRenderer()
    }

    public var currentVideoWidth: Int {
        if videoMetrics.width > 0 { return videoMetrics.width }
        if deviceState.width > 0 { return deviceState.width }
        return Tuning.defaultVideoWidth
    }

    public var currentVideoHeight: Int {
        if videoMetrics.height > 0 { return videoMetrics.height }
        if deviceState.height > 0 { return deviceState.height }
        return Tuning.defaultVideoHeight
    }

    public var isShowingLoadingOverlay: Bool {
        if errorMessage != nil { return true }
        if isMigratingConnection && videoMetrics.totalFramesDecoded > 0 { return false }
        return !isAuthenticated || videoMetrics.totalFramesDecoded == 0
    }

    /// Returns `true` when this popout session's endpoint targets localhost on `port` (e.g., an ADB forward).
    public func isUsingLocalPort(_ port: UInt16) -> Bool {
        guard case let .hostPort(host, endpointPort) = connectionContext.endpoint.target else { return false }
        return (host == "127.0.0.1" || host == "localhost") && endpointPort == port
    }

    public func start() {
        errorMessage = nil
        firstFrameNudgeTask?.cancel()
        videoRenderer.setCallbacks(
            onRequestIDR: { [weak self] in self?.session.send(.requestIdr()) },
            onMetricsUpdate: { [weak self] metrics in
                Task { @MainActor [weak self] in
                    self?.videoMetrics = metrics
                    if metrics.totalFramesDecoded > 0 {
                        self?.firstFrameNudgeTask?.cancel()
                        self?.firstFrameNudgeTask = nil
                    }
                }
            }
        )
        connectCurrentEndpoint()
    }

    public func retryStart() {
        guard !isClosing else { return }
        session.disconnect()
        videoRenderer.reset()
        isAuthenticated = false
        isMigratingConnection = false
        errorMessage = nil
        start()
    }

    /// Seamlessly migrates this popout window to a newly upgraded transport context (e.g., Wireless ADB -> Wi-Fi TLS)
    /// without closing or recreating the window.
    public func migrateConnectionContext(to newContext: PopoutConnectionContext) {
        guard !isClosing else { return }
        guard connectionContext.endpoint != newContext.endpoint || connectionContext.authToken != newContext.authToken else {
            return
        }
        ExtBridgeLog.popout.info(
            "Migrating popout \(self.appPackage, privacy: .public) to transport \(newContext.transportHint.rawValue, privacy: .public)"
        )
        connectionContext = newContext
        mapCommandToCtrl = newContext.mapCommandToCtrl
        isMigratingConnection = true
        errorMessage = nil
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = nil
        lastSentGeometry = nil
        connectCurrentEndpoint()
    }

    // MARK: - Window & Remote Controls

    public func togglePinnedOnTop() {
        isPinnedOnTop.toggle()
        hostWindow?.level = isPinnedOnTop ? .floating : .normal
    }

    public func setWindowOpacity(_ value: Double) {
        let clamped = DisplayPresets.clampPopoutOpacity(value)
        windowOpacity = clamped
        hostWindow?.alphaValue = CGFloat(clamped)
    }

    public func applyAspectPreset(_ preset: PopoutAspectPreset) {
        activePreset = preset
        let targetSize = preset.windowSize
        lastCanvasSize = targetSize

        if let window = hostWindow {
            let currentFrame = window.frame
            var newOrigin = CGPoint(
                x: currentFrame.midX - targetSize.width * 0.5,
                y: currentFrame.maxY - targetSize.height
            )
            if let visible = (window.screen ?? NSScreen.main)?.visibleFrame {
                let pad = Tuning.screenEdgePaddingPt
                newOrigin.x = min(max(newOrigin.x, visible.minX + pad), visible.maxX - targetSize.width - pad)
                newOrigin.y = min(max(newOrigin.y, visible.minY + pad), visible.maxY - targetSize.height - pad)
            }
            window.setFrame(NSRect(origin: newOrigin, size: targetSize), display: true, animate: true)
            lastBackingScale = window.backingScaleFactor
        }

        resizeDebounceTask?.cancel()
        if isAuthenticated {
            let geom = CoordinateMapper.popoutStreamGeometry(viewSize: targetSize, backingScale: lastBackingScale)
            sendResizeIfNeeded(geom, force: true)
        }
    }

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= Tuning.minCanvasDimension, viewSize.height >= Tuning.minCanvasDimension else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale
        activePreset = Self.matchingPreset(for: viewSize)

        guard isAuthenticated else { return }
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            guard await sleepCancellable(nanoseconds: Tuning.resizeDebounceNs), let self else { return }
            let geom = CoordinateMapper.popoutStreamGeometry(viewSize: viewSize, backingScale: backingScale)
            self.sendResizeIfNeeded(geom, force: false)
        }
    }

    public func reloadAppDPI() {
        streamBridge.sendWindowAction(.reloadAppDpi(pkg: appPackage), isAuthenticated: isAuthenticated)
    }

    public func sendAndroidBack() {
        streamBridge.sendSystemAction(.back, isAuthenticated: isAuthenticated)
    }

    public func requestIDR() {
        guard isAuthenticated else { return }
        streamBridge.sendRequestIDR()
    }

    public func returnToDesktop() {
        guard !isClosing else { return }
        Task { [weak self] in
            guard let self else { return }
            if self.isAuthenticated {
                self.streamBridge.sendWindowAction(
                    .returnToDesktop(pkg: self.appPackage, taskId: self.taskId),
                    isAuthenticated: true
                )
                guard await sleepCancellable(nanoseconds: Tuning.returnToDesktopFlushNs) else { return }
            }
            self.close(sendDisconnect: false)
        }
    }

    public func close(sendDisconnect: Bool = true) {
        guard !isClosing else { return }
        isClosing = true
        resizeDebounceTask?.cancel()
        resizeDebounceTask = nil
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = nil

        if sendDisconnect {
            streamBridge.sendSystemAction(.disconnect, isAuthenticated: isAuthenticated)
        }
        session.disconnect()
        videoRenderer.reset()
        isAuthenticated = false
        isMigratingConnection = false

        let callback = onRequestClose
        onRequestClose = nil
        callback?()
    }

    // MARK: - Private Stream Pipeline

    private func connectCurrentEndpoint() {
        let handler = StreamSessionBridge.makeEventHandler(
            videoRenderer: videoRenderer,
            onControlEvent: { [weak self] event in self?.handleSessionEvent(event) }
        )
        session.connect(to: connectionContext.endpoint, onEvent: handler)
    }

    private func handleSessionEvent(_ event: ExtCastSessionEvent) {
        guard !isClosing else { return }
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                sendStandaloneHello()
            case .failed(let message):
                isAuthenticated = false
                isMigratingConnection = false
                errorMessage = message
            case .disconnected:
                if !isMigratingConnection { isAuthenticated = false }
            default:
                break
            }
        case .rttUpdated(let rtt):
            rttMs = rtt
        case .frame(let frame):
            handleControlFrame(frame)
        case .error(let err):
            if case .certificateFingerprintMismatch = err {
                isMigratingConnection = false
                errorMessage = err.localizedDescription
            }
        case .verifiedPeerFingerprint:
            break
        }
    }

    private func sendStandaloneHello() {
        let scale = hostWindow?.backingScaleFactor ?? lastBackingScale
        lastBackingScale = scale
        let geom = CoordinateMapper.popoutStreamGeometry(viewSize: lastCanvasSize, backingScale: scale)
        lastSentGeometry = geom

        streamBridge.sendHello(HelloPayload(
            clientName: StreamSessionBridge.localClientName,
            clientId: connectionContext.clientId,
            preferredCodec: connectionContext.preferredCodec.preferredName,
            width: geom.width,
            height: geom.height,
            dpi: geom.dpi,
            fps: connectionContext.targetFPS,
            bitrateKbps: min(connectionContext.bitrateKbps, Tuning.maxBitrateKbps),
            displayMode: Int(ExtCastDisplayMode.dex.rawValue),
            audioEnabled: false,
            authToken: connectionContext.authToken,
            transportHint: connectionContext.transportHint.rawValue,
            sessionRole: ExtCastSessionRole.standaloneApp.rawValue,
            appPackage: appPackage,
            appActivity: appActivity
        ))
    }

    private func handleControlFrame(_ frame: ExtCastFrame) {
        switch frame.type {
        case .authResult:
            guard let auth = StreamSessionBridge.decodePayload("popout AUTH_RESULT", logger: ExtBridgeLog.popout, {
                try AuthResultPayload.decode(from: frame.payload)
            }) else { return }
            if auth.ok {
                isAuthenticated = true
                isMigratingConnection = false
                errorMessage = nil
                let geom = CoordinateMapper.popoutStreamGeometry(
                    viewSize: lastCanvasSize,
                    backingScale: hostWindow?.backingScaleFactor ?? lastBackingScale
                )
                sendResizeIfNeeded(geom, force: false)
                if videoMetrics.totalFramesDecoded == 0 { startFirstFrameNudge() }
            } else {
                isAuthenticated = false
                isMigratingConnection = false
                errorMessage = auth.message.isEmpty ? "弹出窗连接鉴权失败" : auth.message
            }

        case .deviceState:
            guard let state = StreamSessionBridge.decodePayload("popout DEVICE_STATE", logger: ExtBridgeLog.popout, {
                try DeviceStatePayload.decode(from: frame.payload)
            }) else { return }
            deviceState = state
            if !state.topAppLabel.isEmpty && (appLabel.isEmpty || appLabel == appPackage) {
                appLabel = state.topAppLabel
            }

        default:
            break
        }
    }

    private func startFirstFrameNudge() {
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = Task { [weak self] in
            for delayNs in Tuning.firstFrameNudgeDelaysNs {
                guard await sleepCancellable(nanoseconds: delayNs),
                      let self, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
                self.streamBridge.sendRequestIDR()
            }
            guard await sleepCancellable(nanoseconds: Tuning.firstFrameTimeoutNs),
                  let self, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
            self.errorMessage = "应用启动超时或未能渲染首帧，请点击重试"
        }
    }

    private func sendResizeIfNeeded(_ geom: PopoutStreamGeometry, force: Bool) {
        guard force || lastSentGeometry != geom else { return }
        lastSentGeometry = geom
        streamBridge.sendResize(
            width: geom.width,
            height: geom.height,
            dpi: geom.dpi,
            fps: connectionContext.targetFPS,
            bitrateKbps: min(connectionContext.bitrateKbps, Tuning.maxBitrateKbps)
        )
    }

    private static func matchingPreset(for size: CGSize) -> PopoutAspectPreset? {
        PopoutAspectPreset.allCases.first { preset in
            abs(size.width - preset.windowSize.width) <= Tuning.presetMatchTolerancePt
                && abs(size.height - preset.windowSize.height) <= Tuning.presetMatchTolerancePt
        }
    }
}
