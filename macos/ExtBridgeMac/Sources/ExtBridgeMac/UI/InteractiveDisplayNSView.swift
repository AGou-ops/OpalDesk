import AVFoundation
import AppKit
import Carbon.HIToolbox
import ExtBridgeCore
import QuartzCore
import SwiftUI
import UniformTypeIdentifiers

public enum ThreeFingerSwipeDirection: Sendable, Equatable {
    case left
    case right
    case up
    case down
}

/// AppKit bridge hosting `AVSampleBufferDisplayLayer` with complete pointer, scroll, trackpad pinch,
/// three-finger swipe navigation, hardware keyboard, CJK IME (`NSTextInputClient`), and Finder file drag-and-drop capture.
public struct InteractiveDisplayNSView: NSViewRepresentable {
    public let videoRenderer: VideoDisplayRenderer
    public let videoWidth: Int
    public let videoHeight: Int
    public let mapCommandToCtrl: Bool
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
        videoRenderer: VideoDisplayRenderer,
        videoWidth: Int,
        videoHeight: Int,
        mapCommandToCtrl: Bool,
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
        self.videoRenderer = videoRenderer
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
        self.mapCommandToCtrl = mapCommandToCtrl
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

    public func makeNSView(context: Context) -> DisplayCanvasNSView {
        let view = DisplayCanvasNSView(displayLayer: videoRenderer.displayLayer)
        applyBindings(to: view)
        return view
    }

    public func updateNSView(_ nsView: DisplayCanvasNSView, context: Context) {
        applyBindings(to: nsView)
    }

    private func applyBindings(to view: DisplayCanvasNSView) {
        view.videoWidth = videoWidth
        view.videoHeight = videoHeight
        view.mapCommandToCtrl = mapCommandToCtrl
        view.onMouse = onMouse
        view.onTouch = onTouch
        view.onKey = onKey
        view.onCommitText = onCommitText
        view.onFilesDropped = onFilesDropped
        view.onDragHoverChange = onDragHoverChange
        view.onResize = onResize
        view.onTopEdgeHover = onTopEdgeHover
        view.onCursorClearedTopZone = onCursorClearedTopZone
        view.onCanvasInteract = onCanvasInteract
        view.onQuickLauncher = onQuickLauncher
        view.onTogglePin = onTogglePin
        view.onThreeFingerSwipe = onThreeFingerSwipe
    }
}

@MainActor
public final class DisplayCanvasNSView: NSView, @preconcurrency NSTextInputClient {
    private static let topHoverZoneHeight: CGFloat = 18
    private static let toolbarClearZoneY: CGFloat = 84
    private static let pinchBaseHalfSpan: CGFloat = 140

    private let videoLayer: AVSampleBufferDisplayLayer
    private var trackingAreaRef: NSTrackingArea?

    public var videoWidth: Int = 1920
    public var videoHeight: Int = 1080
    public var mapCommandToCtrl: Bool = true

    public var onMouse: ((InputMousePayload) -> Void)?
    public var onTouch: ((InputTouchPayload) -> Void)?
    public var onKey: ((InputKeyPayload) -> Void)?
    public var onCommitText: ((String) -> Void)?
    public var onFilesDropped: (([URL]) -> Void)?
    public var onDragHoverChange: ((Bool) -> Void)?
    public var onResize: ((CGSize, CGFloat) -> Void)?
    public var onTopEdgeHover: ((Bool) -> Void)?
    public var onCursorClearedTopZone: (() -> Void)?
    public var onCanvasInteract: (() -> Void)?
    public var onQuickLauncher: (() -> Void)?
    public var onTogglePin: (() -> Void)?
    public var onThreeFingerSwipe: ((ThreeFingerSwipeDirection) -> Void)?

    // Pointer & gesture state
    private var pressedButtons = ExtCastMouseButtons()
    private var lastDisplayPoint = CGPoint(x: 960, y: 540)
    private var lastReportedTopHover: Bool = false
    private var lastMouseMoveSentNs: UInt64 = 0
    private static let mouseMoveMinIntervalNs: UInt64 = 8_000_000 // ~120Hz coalescing
    private var isPinching: Bool = false
    private var pinchCenter = CGPoint(x: 960, y: 540)
    private var pinchScale: CGFloat = 1.0

    // Three-finger trackpad gesture state
    private var threeFingerStartCentroid: CGPoint?
    private var threeFingerDidTrigger: Bool = false
    private var lastThreeFingerTriggerNs: UInt64 = 0
    private static let threeFingerSwipeThreshold: CGFloat = 0.14
    private static let threeFingerCooldownNs: UInt64 = 450_000_000

    // IME (`NSTextInputClient`) composition state
    private var markedString: String = ""
    private var markedSelectedRange = NSRange(location: 0, length: 0)
    private var imeHandledCurrentKeyDown: Bool = false
    private var lastModifierFlags: NSEvent.ModifierFlags = []
    private var consumedShortcutKeyCodes: Set<UInt16> = []

    public init(displayLayer: AVSampleBufferDisplayLayer) {
        self.videoLayer = displayLayer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        allowedTouchTypes = [.indirect]
        wantsRestingTouches = false
        videoLayer.frame = bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        if videoLayer.superlayer !== layer {
            videoLayer.removeFromSuperlayer()
            layer?.addSublayer(videoLayer)
        }
        registerForDraggedTypes([.fileURL])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override var isFlipped: Bool { true }
    public override var acceptsFirstResponder: Bool { true }

    public override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.acceptsMouseMovedEvents = true
        window?.makeFirstResponder(self)
        reportCurrentSize()
    }

    public override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if let scale = window?.backingScaleFactor {
            videoLayer.contentsScale = scale
        }
        reportCurrentSize()
    }

    public override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.frame = bounds
        CATransaction.commit()
        reportCurrentSize()
    }

    public override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = trackingAreaRef {
            removeTrackingArea(existing)
        }
        let area = NSTrackingArea(
            rect: bounds,
            options: [.activeAlways, .mouseMoved, .mouseEnteredAndExited, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(area)
        trackingAreaRef = area
    }

    private func reportCurrentSize() {
        guard bounds.width > 0, bounds.height > 0 else { return }
        let scale = window?.backingScaleFactor ?? 2.0
        onResize?(bounds.size, scale)
    }

    // MARK: - Mouse & Scroll Events

    private func mapEventPoint(_ event: NSEvent) -> CGPoint {
        let localPoint = convert(event.locationInWindow, from: nil)
        let nearTop = localPoint.y <= Self.topHoverZoneHeight && localPoint.y >= 0
        if nearTop != lastReportedTopHover {
            lastReportedTopHover = nearTop
            onTopEdgeHover?(nearTop)
        }
        if localPoint.y > Self.toolbarClearZoneY {
            onCursorClearedTopZone?()
        }
        if let mapped = CoordinateMapper.mapViewPointToDisplay(
            viewPoint: localPoint,
            viewSize: bounds.size,
            videoWidth: videoWidth,
            videoHeight: videoHeight,
            isViewFlipped: true,
            clampToBounds: true
        ) {
            lastDisplayPoint = mapped
        }
        return lastDisplayPoint
    }

    public override func mouseMoved(with event: NSEvent) {
        let pt = mapEventPoint(event)
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if nowNs >= lastMouseMoveSentNs && (nowNs - lastMouseMoveSentNs) < Self.mouseMoveMinIntervalNs {
            return
        }
        lastMouseMoveSentNs = nowNs
        onMouse?(InputMousePayload(
            action: .moveAbs,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons
        ))
    }

    public override func mouseDragged(with event: NSEvent) {
        mouseMoved(with: event)
    }

    public override func rightMouseDragged(with event: NSEvent) {
        mouseMoved(with: event)
    }

    public override func otherMouseDragged(with event: NSEvent) {
        mouseMoved(with: event)
    }

    public override func mouseExited(with event: NSEvent) {
        lastReportedTopHover = false
        onTopEdgeHover?(false)
        onCursorClearedTopZone?()
        onMouse?(InputMousePayload(
            action: .hoverExit,
            x: Float(lastDisplayPoint.x),
            y: Float(lastDisplayPoint.y),
            buttons: pressedButtons
        ))
    }

    public override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        onCanvasInteract?()
        handleButton(event: event, button: .primary, isDown: true)
    }

    public override func mouseUp(with event: NSEvent) {
        handleButton(event: event, button: .primary, isDown: false)
    }

    public override func rightMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        onCanvasInteract?()
        handleButton(event: event, button: .secondary, isDown: true)
    }

    public override func rightMouseUp(with event: NSEvent) {
        handleButton(event: event, button: .secondary, isDown: false)
    }

    public override func otherMouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        handleButton(event: event, button: mapOtherMouseButton(event.buttonNumber), isDown: true)
    }

    public override func otherMouseUp(with event: NSEvent) {
        handleButton(event: event, button: mapOtherMouseButton(event.buttonNumber), isDown: false)
    }

    private func mapOtherMouseButton(_ buttonNumber: Int) -> ExtCastMouseButtons {
        switch buttonNumber {
        case 2: return .tertiary
        case 3: return .back
        case 4: return .forward
        default: return .tertiary
        }
    }

    private func handleButton(event: NSEvent, button: ExtCastMouseButtons, isDown: Bool) {
        let pt = mapEventPoint(event)
        if isDown {
            pressedButtons.insert(button)
        } else {
            pressedButtons.remove(button)
        }
        onMouse?(InputMousePayload(
            action: isDown ? .buttonDown : .buttonUp,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons,
            actionButton: button.rawValue
        ))
    }

    public override func scrollWheel(with event: NSEvent) {
        onCanvasInteract?()
        let pt = mapEventPoint(event)
        let divisor: CGFloat = event.hasPreciseScrollingDeltas ? 18.0 : 1.0
        let hScroll = Float(event.scrollingDeltaX / divisor)
        let vScroll = Float(event.scrollingDeltaY / divisor)
        guard hScroll != 0 || vScroll != 0 else { return }
        onMouse?(InputMousePayload(
            action: .scroll,
            x: Float(pt.x),
            y: Float(pt.y),
            buttons: pressedButtons,
            hScroll: hScroll,
            vScroll: vScroll
        ))
    }

    // MARK: - Trackpad Pinch-to-Zoom -> Synthetic 2-Finger Android Touch

    public override func magnify(with event: NSEvent) {
        let pt = mapEventPoint(event)
        switch event.phase {
        case .began:
            isPinching = true
            pinchCenter = pt
            pinchScale = 1.0
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            onTouch?(InputTouchPayload(action: .down, actionIndex: 0, pointers: [p1]))
            onTouch?(InputTouchPayload(action: .pointerDown, actionIndex: 1, pointers: [p1, p2]))

        case .changed:
            guard isPinching else { return }
            pinchScale = min(max(pinchScale * (1.0 + event.magnification), 0.25), 4.0)
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            onTouch?(InputTouchPayload(action: .move, actionIndex: 0, pointers: [p1, p2]))

        case .ended:
            guard isPinching else { return }
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            onTouch?(InputTouchPayload(action: .pointerUp, actionIndex: 1, pointers: [p1, p2]))
            onTouch?(InputTouchPayload(action: .up, actionIndex: 0, pointers: [p1]))
            isPinching = false

        case .cancelled:
            guard isPinching else { return }
            let (p1, p2) = pinchPointers(center: pinchCenter, scale: pinchScale)
            onTouch?(InputTouchPayload(action: .cancel, actionIndex: 0, pointers: [p1, p2]))
            isPinching = false

        default:
            break
        }
    }

    private func pinchPointers(center: CGPoint, scale: CGFloat) -> (TouchPointer, TouchPointer) {
        let halfSpan = Self.pinchBaseHalfSpan * scale
        let maxX = CGFloat(max(videoWidth - 1, 1))
        let maxY = CGFloat(max(videoHeight - 1, 1))
        let x1 = min(max(center.x - halfSpan, 0), maxX)
        let x2 = min(max(center.x + halfSpan, 0), maxX)
        let y = min(max(center.y, 0), maxY)
        return (
            TouchPointer(pointerId: 1, x: Float(x1), y: Float(y), pressure: 1.0),
            TouchPointer(pointerId: 2, x: Float(x2), y: Float(y), pressure: 1.0)
        )
    }

    // MARK: - Trackpad Three-Finger Gestures

    public override func touchesBegan(with event: NSEvent) {
        super.touchesBegan(with: event)
        let active = event.touches(matching: .touching, in: self)
        if active.count == 3 {
            threeFingerStartCentroid = centroid(of: active)
            threeFingerDidTrigger = false
        } else {
            threeFingerStartCentroid = nil
            threeFingerDidTrigger = false
        }
    }

    public override func touchesMoved(with event: NSEvent) {
        super.touchesMoved(with: event)
        let active = event.touches(matching: .touching, in: self)
        guard active.count == 3 else {
            if active.count != 3 {
                threeFingerStartCentroid = nil
            }
            return
        }
        let current = centroid(of: active)
        guard let start = threeFingerStartCentroid else {
            threeFingerStartCentroid = current
            threeFingerDidTrigger = false
            return
        }
        guard !threeFingerDidTrigger else { return }

        let dx = current.x - start.x
        let dy = current.y - start.y
        if abs(dx) >= Self.threeFingerSwipeThreshold || abs(dy) >= Self.threeFingerSwipeThreshold {
            threeFingerDidTrigger = true
            let direction: ThreeFingerSwipeDirection
            if abs(dx) >= abs(dy) {
                direction = dx > 0 ? .right : .left
            } else {
                direction = dy > 0 ? .up : .down
            }
            emitThreeFingerSwipe(direction)
        }
    }

    public override func touchesEnded(with event: NSEvent) {
        super.touchesEnded(with: event)
        if event.touches(matching: .touching, in: self).count < 3 {
            threeFingerStartCentroid = nil
            threeFingerDidTrigger = false
        }
    }

    public override func touchesCancelled(with event: NSEvent) {
        super.touchesCancelled(with: event)
        threeFingerStartCentroid = nil
        threeFingerDidTrigger = false
    }

    public override func swipe(with event: NSEvent) {
        if event.deltaX > 0 {
            emitThreeFingerSwipe(.right)
        } else if event.deltaX < 0 {
            emitThreeFingerSwipe(.left)
        } else if event.deltaY > 0 {
            emitThreeFingerSwipe(.up)
        } else if event.deltaY < 0 {
            emitThreeFingerSwipe(.down)
        }
    }

    private func centroid(of touches: Set<NSTouch>) -> CGPoint {
        guard !touches.isEmpty else { return .zero }
        var sumX: CGFloat = 0
        var sumY: CGFloat = 0
        for t in touches {
            sumX += t.normalizedPosition.x
            sumY += t.normalizedPosition.y
        }
        let count = CGFloat(touches.count)
        return CGPoint(x: sumX / count, y: sumY / count)
    }

    private func emitThreeFingerSwipe(_ direction: ThreeFingerSwipeDirection) {
        let nowNs = DispatchTime.now().uptimeNanoseconds
        if nowNs >= lastThreeFingerTriggerNs && (nowNs - lastThreeFingerTriggerNs) < Self.threeFingerCooldownNs {
            return
        }
        lastThreeFingerTriggerNs = nowNs
        onThreeFingerSwipe?(direction)
    }

    // MARK: - Hardware Keyboard + CJK IME (`NSTextInputClient`)

    public override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if handleLocalShortcutIfNeeded(event) {
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    public override func keyDown(with event: NSEvent) {
        if handleLocalShortcutIfNeeded(event) {
            return
        }

        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let isShortcut = modifiers.contains(.command) || modifiers.contains(.control)

        // When a shortcut is pressed and we are not actively composing CJK marked text, bypass IME directly.
        if isShortcut && !hasMarkedText() {
            sendHardwareKey(event: event, action: .down)
            return
        }

        // Let NSTextInputContext handle CJK composition, dead keys, and Unicode characters.
        imeHandledCurrentKeyDown = false
        let wasComposing = hasMarkedText()
        interpretKeyEvents([event])

        // If IME did not consume the key (e.g. Escape, arrows, Enter, Backspace, or plain ASCII), forward hardware key.
        if !imeHandledCurrentKeyDown && !wasComposing && !hasMarkedText() {
            sendHardwareKey(event: event, action: .down)
        }
    }

    public override func keyUp(with event: NSEvent) {
        if consumedShortcutKeyCodes.remove(event.keyCode) != nil {
            return
        }
        guard !hasMarkedText() else { return }
        sendHardwareKey(event: event, action: .up)
    }

    private func handleLocalShortcutIfNeeded(_ event: NSEvent) -> Bool {
        let mods = event.modifierFlags.intersection([.command, .shift, .control, .option])
        // ⌘K -> Quick App Launcher
        if mods == .command && event.keyCode == UInt16(kVK_ANSI_K), let onQuickLauncher {
            consumedShortcutKeyCodes.insert(event.keyCode)
            onQuickLauncher()
            return true
        }
        // ⌘⇧T -> Toggle Always-on-Top (Popout window)
        if mods == [.command, .shift] && event.keyCode == UInt16(kVK_ANSI_T), let onTogglePin {
            consumedShortcutKeyCodes.insert(event.keyCode)
            onTogglePin()
            return true
        }
        return false
    }

    public override func flagsChanged(with event: NSEvent) {
        let current = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let isKeyDown: Bool
        switch Int(event.keyCode) {
        case 56, 60: isKeyDown = current.contains(.shift)
        case 59, 62: isKeyDown = current.contains(.control)
        case 58, 61: isKeyDown = current.contains(.option)
        case 55, 54: isKeyDown = current.contains(.command)
        case 57: isKeyDown = current.contains(.capsLock)
        default: isKeyDown = current.rawValue > lastModifierFlags.rawValue
        }
        lastModifierFlags = current

        if let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: current,
            mapCommandToCtrl: false
        ) {
            onKey?(InputKeyPayload(
                action: isKeyDown ? .down : .up,
                androidKeyCode: mapped.androidKeyCode,
                metaState: mapped.metaState,
                repeatCount: 0
            ))
        }
    }

    public override func doCommand(by selector: Selector) {
        // Leave navigation/editing commands (Enter, Tab, Backspace, Escape, arrows) to hardware key forwarding.
        imeHandledCurrentKeyDown = false
    }

    private func sendHardwareKey(event: NSEvent, action: ExtCastKeyAction) {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: modifiers,
            mapCommandToCtrl: mapCommandToCtrl
        ) else {
            return
        }
        onKey?(InputKeyPayload(
            action: action,
            androidKeyCode: mapped.androidKeyCode,
            metaState: mapped.metaState,
            repeatCount: event.isARepeat ? 1 : 0
        ))
    }

    // MARK: NSTextInputClient

    public func insertText(_ string: Any, replacementRange: NSRange) {
        let text: String
        if let attr = string as? NSAttributedString {
            text = attr.string
        } else if let str = string as? String {
            text = str
        } else {
            return
        }

        let wasMarked = !markedString.isEmpty
        markedString = ""
        markedSelectedRange = NSRange(location: 0, length: 0)

        guard !text.isEmpty else { return }

        // Use INPUT_TEXT commit-paste for IME-composed strings, multi-character strings, or non-ASCII text (Chinese/emoji).
        let isPureSingleASCII = text.count == 1 && (text.unicodeScalars.first?.isASCII == true)
        if wasMarked || !isPureSingleASCII {
            imeHandledCurrentKeyDown = true
            onCommitText?(text)
        } else {
            // Single uncomposed ASCII character: let hardware keyDown forward native Android KeyEvent.
            imeHandledCurrentKeyDown = false
        }
    }

    public func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        if let attr = string as? NSAttributedString {
            markedString = attr.string
        } else if let str = string as? String {
            markedString = str
        } else {
            markedString = ""
        }
        markedSelectedRange = selectedRange
        imeHandledCurrentKeyDown = true
    }

    public func unmarkText() {
        markedString = ""
        markedSelectedRange = NSRange(location: 0, length: 0)
    }

    public func selectedRange() -> NSRange {
        NSRange(location: NSNotFound, length: 0)
    }

    public func markedRange() -> NSRange {
        markedString.isEmpty
            ? NSRange(location: NSNotFound, length: 0)
            : NSRange(location: 0, length: (markedString as NSString).length)
    }

    public func hasMarkedText() -> Bool {
        !markedString.isEmpty
    }

    public func attributedSubstring(
        forProposedRange range: NSRange,
        actualRange: NSRangePointer?
    ) -> NSAttributedString? {
        nil
    }

    public func validAttributesForMarkedText() -> [NSAttributedString.Key] {
        []
    }

    public func firstRect(
        forCharacterRange range: NSRange,
        actualRange: NSRangePointer?
    ) -> NSRect {
        guard let window else { return .zero }
        // Anchor macOS IME candidate window near the bottom-center of the canvas.
        let localAnchor = NSRect(x: bounds.midX - 60, y: bounds.height - 64, width: 120, height: 24)
        let windowRect = convert(localAnchor, to: nil)
        return window.convertToScreen(windowRect)
    }

    public func characterIndex(for point: NSPoint) -> Int {
        0
    }

    // MARK: - Finder File Drag & Drop

    public override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        guard extractFileURLs(from: sender).isEmpty == false else {
            return []
        }
        onDragHoverChange?(true)
        return .copy
    }

    public override func draggingExited(_ sender: (any NSDraggingInfo)?) {
        onDragHoverChange?(false)
    }

    public override func draggingEnded(_ sender: any NSDraggingInfo) {
        onDragHoverChange?(false)
    }

    public override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        onDragHoverChange?(false)
        let urls = extractFileURLs(from: sender)
        guard !urls.isEmpty else { return false }
        onFilesDropped?(urls)
        return true
    }

    private func extractFileURLs(from sender: any NSDraggingInfo) -> [URL] {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        guard let objects = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL] else {
            return []
        }
        return objects.filter(\.isFileURL)
    }
}
