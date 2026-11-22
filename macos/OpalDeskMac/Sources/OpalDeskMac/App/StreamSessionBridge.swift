import OpalDeskCore
import Foundation
import os

/// Sleeps for `nanoseconds` and returns `false` if cancelled (eliminates `try? Task.sleep` boilerplate).
func sleepCancellable(nanoseconds: UInt64) async -> Bool {
    do {
        try await Task.sleep(nanoseconds: nanoseconds)
        return !Task.isCancelled
    } catch {
        return false
    }
}

/// Shared OpalCast stream pipeline bridge used by both `AppModel` (main desktop canvas)
/// and `PopoutAppController` (standalone Android app windows).
/// Centralizes fast-path `networkQueue` video/audio routing, logged payload decoding,
/// `HELLO`/control framing, and canvas input callback wiring.
@MainActor
public final class StreamSessionBridge {
    public let session: OpalCastSession
    private let logger: Logger
    private var nextInputTextSeq: Int64 = 1

    public init(session: OpalCastSession, logger: Logger) {
        self.session = session
        self.logger = logger
    }

    public static var localClientName: String {
        Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    }

    /// Decodes a wire payload with consistent error logging instead of silent `try?`.
    public nonisolated static func decodePayload<T>(
        _ label: String,
        logger: Logger,
        _ decode: () throws -> T
    ) -> T? {
        do {
            return try decode()
        } catch {
            logger.error("Failed to decode \(label, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// Fast-path event router: dispatches high-frequency video/audio frames synchronously on
    /// `networkQueue` in strict FIFO order without bouncing through `@MainActor`
    /// (preventing HEVC frame reordering and UI jank).
    public nonisolated static func makeEventHandler(
        videoRenderer: VideoDisplayRenderer,
        audioPlayer: AudioStreamPlayer? = nil,
        onVideoDisplayMode: (@MainActor @Sendable (OpalCastDisplayMode) -> Void)? = nil,
        onControlEvent: @escaping @MainActor @Sendable (OpalCastSessionEvent) -> Void
    ) -> @Sendable (OpalCastSessionEvent) -> Void {
        return { event in
            if case .frame(let frame) = event {
                switch frame.type {
                case .videoFrame:
                    let flags = OpalCastVideoFrameFlags(rawValue: frame.flags)
                    videoRenderer.enqueueFrame(payload: frame.payload, flags: flags, timestampUs: frame.timestampUs)
                    return

                case .videoConfig:
                    if let config = decodePayload("VIDEO_CONFIG", logger: OpalDeskLog.video, {
                        try VideoConfigPayload.decode(from: frame.payload)
                    }) {
                        videoRenderer.configure(config)
                        if let onVideoDisplayMode {
                            let mode = config.displayMode
                            Task { @MainActor in onVideoDisplayMode(mode) }
                        }
                    }
                    return

                case .audioConfig:
                    if let audioPlayer, let config = decodePayload("AUDIO_CONFIG", logger: OpalDeskLog.audio, {
                        try AudioConfigPayload.decode(from: frame.payload)
                    }) {
                        audioPlayer.configure(config)
                    }
                    return

                case .audioFrame:
                    audioPlayer?.enqueuePacket(frame.payload)
                    return

                case .pong:
                    return

                default:
                    break
                }
            }

            Task { @MainActor in onControlEvent(event) }
        }
    }

    /// Builds `DisplayCanvasCallbacks` wiring mouse, touch, keyboard, IME text, three-finger swipes,
    /// optional toolbar hover auto-hide, resize, canvas interaction, and drag-and-drop cleanly for both main and popout windows.
    public func makeCanvasCallbacks(
        isAuthenticated: @escaping () -> Bool,
        toolbar: HoverToolbarController? = nil,
        onFilesDropped: @escaping ([URL]) -> Void,
        onDragHoverChange: @escaping (Bool) -> Void,
        onResize: @escaping (CGSize, CGFloat) -> Void,
        onCanvasInteract: (() -> Void)? = nil,
        onQuickLauncher: (() -> Void)? = nil,
        onTogglePin: (() -> Void)? = nil
    ) -> DisplayCanvasCallbacks {
        DisplayCanvasCallbacks(
            onMouse: { [weak self] payload in self?.sendMouse(payload, isAuthenticated: isAuthenticated()) },
            onTouch: { [weak self] payload in self?.sendTouch(payload, isAuthenticated: isAuthenticated()) },
            onKey: { [weak self] payload in self?.sendKey(payload, isAuthenticated: isAuthenticated()) },
            onCommitText: { [weak self] text in self?.sendCommittedText(text, isAuthenticated: isAuthenticated()) },
            onFilesDropped: onFilesDropped,
            onDragHoverChange: onDragHoverChange,
            onResize: onResize,
            onTopEdgeHover: { nearTop in toolbar?.handleTopEdgeHover(nearTop) },
            onCursorClearedTopZone: { toolbar?.handleCursorClearedTopZone() },
            onCanvasInteract: {
                toolbar?.handleCanvasInteract()
                onCanvasInteract?()
            },
            onQuickLauncher: onQuickLauncher,
            onTogglePin: onTogglePin,
            onThreeFingerSwipe: { [weak self] dir in self?.handleThreeFingerSwipe(dir, isAuthenticated: isAuthenticated()) }
        )
    }

    // MARK: - Handshake & Control Frames

    public func sendHello(_ payload: HelloPayload) {
        do {
            session.send(try OpalCastFrame.hello(payload))
        } catch {
            logger.error("Failed to encode HELLO handshake: \(error.localizedDescription, privacy: .public)")
        }
    }

    public func sendResize(width: Int, height: Int, dpi: Int, fps: Int, bitrateKbps: Int) {
        let payload = ResizeConfigPayload(
            width: UInt16(clamping: width),
            height: UInt16(clamping: height),
            dpi: UInt16(clamping: dpi),
            fpsX100: UInt16(clamping: fps * 100),
            bitrateKbps: UInt32(clamping: bitrateKbps),
            displayMode: ResizeConfigPayload.unchangedByte,
            rotation: ResizeConfigPayload.unchangedByte
        )
        session.send(.resizeConfig(payload))
    }

    public func sendWindowAction(_ payload: WindowActionPayload, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        do {
            session.send(try OpalCastFrame.windowAction(payload))
        } catch {
            logger.error(
                "Failed to encode WINDOW_ACTION (\(payload.action.rawValue, privacy: .public)): \(error.localizedDescription, privacy: .public)"
            )
        }
    }

    public func sendSystemAction(_ kind: OpalCastSystemActionKind, arg: Int32 = 0, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        session.send(.systemAction(SystemActionPayload(action: kind, arg: arg)))
    }

    public func sendRequestIDR() {
        session.send(.requestIdr())
    }

    // MARK: - Input Forwarding

    public func sendMouse(_ payload: InputMousePayload, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        session.send(.inputMouse(payload), allowDropOnCongestion: payload.action == .moveAbs)
    }

    public func sendTouch(_ payload: InputTouchPayload, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        session.send(.inputTouch(payload))
    }

    public func sendKey(_ payload: InputKeyPayload, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        session.send(.inputKey(payload))
    }

    public func sendCommittedText(_ text: String, isAuthenticated: Bool) {
        guard isAuthenticated, !text.isEmpty else { return }
        let seq = nextInputTextSeq
        nextInputTextSeq += 1
        session.send(.inputText(InputTextPayload(mode: .commitTextPaste, seq: seq, text: text)))
    }

    public func handleThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection, isAuthenticated: Bool) {
        guard isAuthenticated else { return }
        switch direction {
        case .left, .right: sendSystemAction(.back, isAuthenticated: true)
        case .down: sendSystemAction(.home, isAuthenticated: true)
        case .up: sendSystemAction(.recents, isAuthenticated: true)
        }
    }
}
