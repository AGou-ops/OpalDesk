import AppKit
import ExtBridgeCore
import SwiftUI

/// Trackpad three-finger swipe direction mapped to Android navigation gestures.
public enum ThreeFingerSwipeDirection: Sendable, Equatable {
    case left
    case right
    case up
    case down
}

/// Immutable rendering & input configuration for `InteractiveDisplayNSView`.
public struct DisplayCanvasConfig: Equatable, Sendable {
    public let videoWidth: Int
    public let videoHeight: Int
    public let mapCommandToCtrl: Bool

    public init(videoWidth: Int, videoHeight: Int, mapCommandToCtrl: Bool) {
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
        self.mapCommandToCtrl = mapCommandToCtrl
    }
}

/// Grouped input, resize, hover, drag-drop, and shortcut callbacks for `InteractiveDisplayNSView`.
@MainActor
public struct DisplayCanvasCallbacks {
    public let onMouse: (InputMousePayload) -> Void
    public let onTouch: (InputTouchPayload) -> Void
    public let onKey: (InputKeyPayload) -> Void
    public let onCommitText: (String) -> Void
    public let onFilesDropped: ([URL]) -> Void
    public let onDragHoverChange: (Bool) -> Void
    public let onResize: (CGSize, CGFloat) -> Void
    public let onTopEdgeHover: (Bool) -> Void
    public let onCursorClearedTopZone: (() -> Void)?
    public let onCanvasInteract: (() -> Void)?
    public let onQuickLauncher: (() -> Void)?
    public let onTogglePin: (() -> Void)?
    public let onThreeFingerSwipe: ((ThreeFingerSwipeDirection) -> Void)?

    public init(
        onMouse: @escaping (InputMousePayload) -> Void,
        onTouch: @escaping (InputTouchPayload) -> Void,
        onKey: @escaping (InputKeyPayload) -> Void,
        onCommitText: @escaping (String) -> Void,
        onFilesDropped: @escaping ([URL]) -> Void,
        onDragHoverChange: @escaping (Bool) -> Void,
        onResize: @escaping (CGSize, CGFloat) -> Void,
        onTopEdgeHover: @escaping (Bool) -> Void,
        onCursorClearedTopZone: (() -> Void)? = nil,
        onCanvasInteract: (() -> Void)? = nil,
        onQuickLauncher: (() -> Void)? = nil,
        onTogglePin: (() -> Void)? = nil,
        onThreeFingerSwipe: ((ThreeFingerSwipeDirection) -> Void)? = nil
    ) {
        self.onMouse = onMouse
        self.onTouch = onTouch
        self.onKey = onKey
        self.onCommitText = onCommitText
        self.onFilesDropped = onFilesDropped
        self.onDragHoverChange = onDragHoverChange
        self.onResize = onResize
        self.onTopEdgeHover = onTopEdgeHover
        self.onCursorClearedTopZone = onCursorClearedTopZone
        self.onCanvasInteract = onCanvasInteract
        self.onQuickLauncher = onQuickLauncher
        self.onTogglePin = onTogglePin
        self.onThreeFingerSwipe = onThreeFingerSwipe
    }
}

/// AppKit bridge hosting `AVSampleBufferDisplayLayer` with complete pointer, scroll, trackpad pinch,
/// three-finger swipe navigation, hardware keyboard, CJK IME (`NSTextInputClient`), and Finder file drag-and-drop capture.
public struct InteractiveDisplayNSView: NSViewRepresentable {
    public let videoRenderer: VideoDisplayRenderer
    public let config: DisplayCanvasConfig
    public let callbacks: DisplayCanvasCallbacks

    public init(
        videoRenderer: VideoDisplayRenderer,
        config: DisplayCanvasConfig,
        callbacks: DisplayCanvasCallbacks
    ) {
        self.videoRenderer = videoRenderer
        self.config = config
        self.callbacks = callbacks
    }

    public func makeNSView(context: Context) -> DisplayCanvasNSView {
        let view = DisplayCanvasNSView(
            displayLayer: videoRenderer.displayLayer,
            config: config,
            callbacks: callbacks
        )
        return view
    }

    public func updateNSView(_ nsView: DisplayCanvasNSView, context: Context) {
        nsView.config = config
        nsView.callbacks = callbacks
    }
}
