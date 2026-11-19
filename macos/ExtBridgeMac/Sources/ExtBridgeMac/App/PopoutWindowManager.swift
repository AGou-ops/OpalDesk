import AppKit
import ExtBridgeCore
import Foundation
import Observation
import SwiftUI

/// Snapshot of the active authenticated connection shared by standalone popout app windows.
public struct PopoutConnectionContext: Sendable, Equatable {
    public let endpoint: ExtCastEndpoint
    public let transportHint: ExtCastTransportHint
    public let clientId: String
    public let authToken: String
    public let preferredCodec: ExtCastVideoCodec
    public let targetFPS: Int
    public let bitrateKbps: Int
    public let mapCommandToCtrl: Bool

    public init(
        endpoint: ExtCastEndpoint,
        transportHint: ExtCastTransportHint,
        clientId: String,
        authToken: String,
        preferredCodec: ExtCastVideoCodec,
        targetFPS: Int,
        bitrateKbps: Int,
        mapCommandToCtrl: Bool
    ) {
        self.endpoint = endpoint
        self.transportHint = transportHint
        self.clientId = clientId
        self.authToken = authToken
        self.preferredCodec = preferredCodec
        self.targetFPS = targetFPS
        self.bitrateKbps = bitrateKbps
        self.mapCommandToCtrl = mapCommandToCtrl
    }
}

public enum PopoutOpenResult: Sendable, Equatable {
    case opened
    case focusedExisting
    case limitReached(maxCount: Int)
}

/// Manages the lifecycle, video stream, input, and desktop window state of one standalone popout Android app.
@MainActor
@Observable
public final class PopoutAppController: Identifiable {
    public let id = UUID()
    public let appPackage: String
    public let appActivity: String
    public var appLabel: String
    public var taskId: Int

    public let session: ExtCastSession
    public let videoRenderer: VideoDisplayRenderer

    public var sessionState: ExtCastSessionState = .idle
    public var isAuthenticated: Bool = false
    public var deviceState = DeviceStatePayload()
    public var videoMetrics = VideoRendererMetrics()
    public var rttMs: Double = 0
    public var errorMessage: String?

    public var isPinnedOnTop: Bool = false
    public var windowOpacity: Double = 1.0
    public var activePreset: PopoutAspectPreset? = .phone
    public var mapCommandToCtrl: Bool

    public weak var hostWindow: NSWindow?
    public var onRequestClose: (() -> Void)?

    private let connectionContext: PopoutConnectionContext
    private var resizeDebounceTask: Task<Void, Never>?
    private var firstFrameNudgeTask: Task<Void, Never>?
    private var lastSentGeometry: PopoutStreamGeometry?
    private var lastCanvasSize: CGSize = PopoutAspectPreset.phone.windowSize
    private var lastBackingScale: CGFloat = 2.0
    private var nextInputTextSeq: Int64 = 1
    private var isClosing: Bool = false

    public init(
        appPackage: String,
        appActivity: String,
        appLabel: String,
        taskId: Int = -1,
        connectionContext: PopoutConnectionContext,
        trustStore: TrustStore
    ) {
        self.appPackage = appPackage
        self.appActivity = appActivity
        self.appLabel = appLabel.isEmpty ? appPackage : appLabel
        self.taskId = taskId
        self.connectionContext = connectionContext
        self.mapCommandToCtrl = connectionContext.mapCommandToCtrl
        self.session = ExtCastSession(trustStore: trustStore)
        self.videoRenderer = VideoDisplayRenderer()
    }

    public var currentVideoWidth: Int {
        if videoMetrics.width > 0 { return videoMetrics.width }
        if deviceState.width > 0 { return deviceState.width }
        return 848
    }

    public var currentVideoHeight: Int {
        if videoMetrics.height > 0 { return videoMetrics.height }
        if deviceState.height > 0 { return deviceState.height }
        return 1680
    }

    public func start() {
        errorMessage = nil
        firstFrameNudgeTask?.cancel()
        videoRenderer.setCallbacks(
            onRequestIDR: { [weak self] in
                self?.session.send(.requestIdr())
            },
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
        session.connect(to: connectionContext.endpoint, onEvent: makeSessionEventHandler())
    }

    public func retryStart() {
        guard !isClosing else { return }
        session.disconnect()
        videoRenderer.reset()
        isAuthenticated = false
        errorMessage = nil
        start()
    }

    // MARK: - Window Controls

    public func togglePinnedOnTop() {
        isPinnedOnTop.toggle()
        hostWindow?.level = isPinnedOnTop ? .floating : .normal
    }

    public func setWindowOpacity(_ value: Double) {
        let clamped = min(max(value, 0.4), 1.0)
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
                newOrigin.x = min(max(newOrigin.x, visible.minX + 12), visible.maxX - targetSize.width - 12)
                newOrigin.y = min(max(newOrigin.y, visible.minY + 12), visible.maxY - targetSize.height - 12)
            }
            let targetFrame = NSRect(origin: newOrigin, size: targetSize)
            window.setFrame(targetFrame, display: true, animate: true)
            lastBackingScale = window.backingScaleFactor
        }

        resizeDebounceTask?.cancel()
        if isAuthenticated {
            let geom = CoordinateMapper.popoutStreamGeometry(
                viewSize: targetSize,
                backingScale: lastBackingScale
            )
            sendResizeIfNeeded(geom, force: true)
        }
    }

    public func handleCanvasResize(viewSize: CGSize, backingScale: CGFloat) {
        guard viewSize.width >= 200, viewSize.height >= 200 else { return }
        lastCanvasSize = viewSize
        lastBackingScale = backingScale
        activePreset = Self.matchingPreset(for: viewSize)

        guard isAuthenticated else { return }
        resizeDebounceTask?.cancel()
        resizeDebounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 260_000_000) // 260ms debounce on drag end
            guard !Task.isCancelled, let self else { return }
            let geom = CoordinateMapper.popoutStreamGeometry(
                viewSize: viewSize,
                backingScale: backingScale
            )
            self.sendResizeIfNeeded(geom, force: false)
        }
    }

    // MARK: - Remote Actions & Input

    public func reloadAppDPI() {
        guard isAuthenticated else { return }
        if let frame = try? ExtCastFrame.windowAction(.reloadAppDpi(pkg: appPackage)) {
            session.send(frame)
        }
    }

    public func sendAndroidBack() {
        guard isAuthenticated else { return }
        session.send(.systemAction(SystemActionPayload(action: .back, arg: 0)))
    }

    public func requestIDR() {
        guard isAuthenticated else { return }
        session.send(.requestIdr())
    }

    public func returnToDesktop() {
        guard !isClosing else { return }
        Task { [weak self] in
            guard let self else { return }
            if self.isAuthenticated,
               let frame = try? ExtCastFrame.windowAction(.returnToDesktop(pkg: self.appPackage, taskId: self.taskId))
            {
                self.session.send(frame)
                try? await Task.sleep(nanoseconds: 90_000_000)
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

        if sendDisconnect && isAuthenticated {
            session.send(.systemAction(SystemActionPayload(action: .disconnect, arg: 0)))
        }
        session.disconnect()
        videoRenderer.reset()
        isAuthenticated = false

        let callback = onRequestClose
        onRequestClose = nil
        callback?()
    }

    public func sendMouse(_ payload: InputMousePayload) {
        guard isAuthenticated else { return }
        session.send(.inputMouse(payload), allowDropOnCongestion: payload.action == .moveAbs)
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
        session.send(.inputText(InputTextPayload(mode: .commitTextPaste, seq: seq, text: text)))
    }

    public func handleThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection) {
        guard isAuthenticated else { return }
        switch direction {
        case .left, .right:
            sendAndroidBack()
        case .down:
            session.send(.systemAction(SystemActionPayload(action: .home, arg: 0)))
        case .up:
            session.send(.systemAction(SystemActionPayload(action: .recents, arg: 0)))
        }
    }

    // MARK: - Private Stream Pipeline

    private func makeSessionEventHandler() -> @Sendable (ExtCastSessionEvent) -> Void {
        let renderer = self.videoRenderer
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
                    }
                    return
                case .audioConfig, .audioFrame, .pong:
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
        guard !isClosing else { return }
        switch event {
        case .stateChanged(let newState):
            sessionState = newState
            switch newState {
            case .connected:
                sendStandaloneHello()
            case .failed(let message):
                isAuthenticated = false
                errorMessage = message
            case .disconnected:
                isAuthenticated = false
            default:
                break
            }

        case .rttUpdated(let rtt):
            rttMs = rtt

        case .frame(let frame):
            handleControlFrame(frame)

        case .error(let err):
            if case .certificateFingerprintMismatch = err {
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

        let hello = HelloPayload(
            protocolVersion: ExtCastProtocol.version,
            clientName: Host.current().localizedName ?? ProcessInfo.processInfo.hostName,
            clientId: connectionContext.clientId,
            preferredCodec: connectionContext.preferredCodec.preferredName,
            width: geom.width,
            height: geom.height,
            dpi: geom.dpi,
            fps: connectionContext.targetFPS,
            bitrateKbps: min(connectionContext.bitrateKbps, 12_000),
            displayMode: Int(ExtCastDisplayMode.dex.rawValue),
            audioEnabled: false,
            authToken: connectionContext.authToken,
            pinCode: "",
            transportHint: connectionContext.transportHint.rawValue,
            sessionRole: ExtCastSessionRole.standaloneApp.rawValue,
            appPackage: appPackage,
            appActivity: appActivity
        )

        if let frame = try? ExtCastFrame.hello(hello) {
            session.send(frame)
        }
    }

    private func handleControlFrame(_ frame: ExtCastFrame) {
        switch frame.type {
        case .authResult:
            if let auth = try? AuthResultPayload.decode(from: frame.payload) {
                if auth.ok {
                    isAuthenticated = true
                    errorMessage = nil
                    let geom = CoordinateMapper.popoutStreamGeometry(
                        viewSize: lastCanvasSize,
                        backingScale: hostWindow?.backingScaleFactor ?? lastBackingScale
                    )
                    sendResizeIfNeeded(geom, force: false)
                    startFirstFrameNudge()
                } else {
                    isAuthenticated = false
                    errorMessage = auth.message.isEmpty ? "弹出窗连接鉴权失败" : auth.message
                }
            }

        case .deviceState:
            if let state = try? DeviceStatePayload.decode(from: frame.payload) {
                deviceState = state
                if !state.topAppLabel.isEmpty && (appLabel.isEmpty || appLabel == appPackage) {
                    appLabel = state.topAppLabel
                }
            }

        default:
            break
        }
    }

    private func startFirstFrameNudge() {
        firstFrameNudgeTask?.cancel()
        firstFrameNudgeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard !Task.isCancelled, let self, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
            self.session.send(.requestIdr())
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            guard !Task.isCancelled, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
            self.session.send(.requestIdr())
            try? await Task.sleep(nanoseconds: 5_500_000_000)
            guard !Task.isCancelled, self.isAuthenticated, self.videoMetrics.totalFramesDecoded == 0 else { return }
            self.errorMessage = "应用启动超时或未能渲染首帧，请点击重试"
        }
    }

    private func sendResizeIfNeeded(_ geom: PopoutStreamGeometry, force: Bool) {
        if !force, lastSentGeometry == geom {
            return
        }
        lastSentGeometry = geom
        let payload = ResizeConfigPayload(
            width: UInt16(clamping: geom.width),
            height: UInt16(clamping: geom.height),
            dpi: UInt16(clamping: geom.dpi),
            fpsX100: UInt16(clamping: connectionContext.targetFPS * 100),
            bitrateKbps: UInt32(clamping: min(connectionContext.bitrateKbps, 12_000)),
            displayMode: ResizeConfigPayload.unchangedByte,
            rotation: ResizeConfigPayload.unchangedByte
        )
        session.send(.resizeConfig(payload))
    }

    private static func matchingPreset(for size: CGSize) -> PopoutAspectPreset? {
        for preset in PopoutAspectPreset.allCases {
            if abs(size.width - preset.windowSize.width) <= 14
                && abs(size.height - preset.windowSize.height) <= 14
            {
                return preset
            }
        }
        return nil
    }
}

/// Native AppKit `NSWindow` coordinator hosting up to 3 independent standalone Android app windows.
/// Popout windows stay alive even if the main `ExtBridgeMac` window is minimized or closed.
@MainActor
@Observable
public final class PopoutWindowManager {
    public static let maxConcurrentWindows: Int = 3

    public private(set) var controllers: [PopoutAppController] = []

    @ObservationIgnored
    private var hosts: [UUID: PopoutWindowHost] = [:]

    public init() {}

    public func openPopout(
        appPackage: String,
        appActivity: String = "",
        appLabel: String = "",
        taskId: Int = -1,
        context: PopoutConnectionContext,
        trustStore: TrustStore,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> PopoutOpenResult {
        let normalizedPkg = appPackage.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedPkg.isEmpty else {
            return .limitReached(maxCount: Self.maxConcurrentWindows)
        }

        // Bring existing popout window for the same package to front rather than spawning duplicates.
        if let existing = controllers.first(where: { $0.appPackage == normalizedPkg }) {
            existing.hostWindow?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return .focusedExisting
        }

        guard controllers.count < Self.maxConcurrentWindows else {
            return .limitReached(maxCount: Self.maxConcurrentWindows)
        }

        let controller = PopoutAppController(
            appPackage: normalizedPkg,
            appActivity: appActivity,
            appLabel: appLabel.isEmpty ? normalizedPkg : appLabel,
            taskId: taskId,
            connectionContext: context,
            trustStore: trustStore
        )

        let window = makePopoutWindow(for: controller, cascadeIndex: controllers.count, onFilesDropped: onFilesDropped)
        controller.hostWindow = window

        let host = PopoutWindowHost(window: window) { [weak self, id = controller.id] in
            self?.handleHostWindowClosed(id: id)
        }
        window.delegate = host

        controller.onRequestClose = { [weak self, id = controller.id] in
            self?.closeWindow(id: id, sendDisconnect: false)
        }

        hosts[controller.id] = host
        controllers.append(controller)

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        controller.start()
        return .opened
    }

    public func focusWindow(id: UUID) {
        guard let host = hosts[id] else { return }
        host.window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func closeWindow(id: UUID, sendDisconnect: Bool = true) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)?.window.close()
            return
        }
        let controller = controllers.remove(at: idx)
        let host = hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: sendDisconnect)
        if let host {
            host.window.delegate = nil
            host.window.orderOut(nil)
            host.window.close()
        }
    }

    public func closeAll(sendDisconnect: Bool = true) {
        let ids = controllers.map(\.id)
        for id in ids {
            closeWindow(id: id, sendDisconnect: sendDisconnect)
        }
    }

    // MARK: - Private Window Factory

    private func handleHostWindowClosed(id: UUID) {
        guard let idx = controllers.firstIndex(where: { $0.id == id }) else {
            hosts.removeValue(forKey: id)
            return
        }
        let controller = controllers.remove(at: idx)
        hosts.removeValue(forKey: id)
        controller.onRequestClose = nil
        controller.close(sendDisconnect: true)
    }

    private func makePopoutWindow(
        for controller: PopoutAppController,
        cascadeIndex: Int,
        onFilesDropped: @escaping ([URL]) -> Void
    ) -> NSWindow {
        let initialSize = PopoutAspectPreset.phone.windowSize
        let origin = initialOrigin(for: initialSize, cascadeIndex: cascadeIndex)
        let rect = NSRect(origin: origin, size: initialSize)

        let window = NSWindow(
            contentRect: rect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = controller.appLabel
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = false
        window.isReleasedWhenClosed = false
        window.backgroundColor = .black
        window.appearance = NSAppearance(named: .darkAqua)
        window.minSize = NSSize(width: 320, height: 480)
        window.hasShadow = true
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]

        // Hide macOS traffic lights so the popout becomes a clean borderless rounded phone screen when idle.
        window.standardWindowButton(.closeButton)?.isHidden = true
        window.standardWindowButton(.miniaturizeButton)?.isHidden = true
        window.standardWindowButton(.zoomButton)?.isHidden = true

        let rootView = PopoutAppWindowView(
            controller: controller,
            onFilesDropped: onFilesDropped
        )
        let hostingView = NSHostingView(rootView: rootView)
        hostingView.frame = NSRect(origin: .zero, size: initialSize)
        hostingView.autoresizingMask = [.width, .height]
        window.contentView = hostingView
        return window
    }

    private func initialOrigin(for size: CGSize, cascadeIndex: Int) -> CGPoint {
        guard let visible = NSScreen.main?.visibleFrame else {
            return CGPoint(x: 160 + CGFloat(cascadeIndex) * 40, y: 120)
        }
        let offset = CGFloat(cascadeIndex) * 38.0
        let x = max(visible.minX + 20, visible.maxX - size.width - 36 - offset)
        let y = max(visible.minY + 20, visible.midY - size.height * 0.5 - CGFloat(cascadeIndex) * 18.0)
        return CGPoint(x: x, y: y)
    }
}

@MainActor
private final class PopoutWindowHost: NSObject, NSWindowDelegate {
    let window: NSWindow
    private var onClose: (() -> Void)?

    init(window: NSWindow, onClose: @escaping () -> Void) {
        self.window = window
        self.onClose = onClose
        super.init()
    }

    func windowWillClose(_ notification: Notification) {
        let cb = onClose
        onClose = nil
        cb?()
    }
}
