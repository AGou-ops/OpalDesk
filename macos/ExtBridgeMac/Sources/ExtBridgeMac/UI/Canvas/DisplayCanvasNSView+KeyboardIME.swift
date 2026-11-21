import AppKit
import Carbon.HIToolbox
import ExtBridgeCore

// MARK: - Hardware Keyboard, Local Shortcuts & CJK IME (`NSTextInputClient`)

extension DisplayCanvasNSView: @preconcurrency NSTextInputClient {
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

    public override func flagsChanged(with event: NSEvent) {
        let current = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let action = KeyCodeMapper.modifierKeyAction(
            for: event.keyCode,
            currentFlags: current,
            previousFlags: lastModifierFlags
        )
        lastModifierFlags = current

        if let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: current,
            mapCommandToCtrl: false
        ) {
            callbacks.onKey(InputKeyPayload(
                action: action,
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

    private func handleLocalShortcutIfNeeded(_ event: NSEvent) -> Bool {
        false
    }

    private func sendHardwareKey(event: NSEvent, action: ExtCastKeyAction) {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        guard let mapped = KeyCodeMapper.map(
            macKeyCode: event.keyCode,
            modifiers: modifiers,
            mapCommandToCtrl: config.mapCommandToCtrl
        ) else {
            return
        }
        callbacks.onKey(InputKeyPayload(
            action: action,
            androidKeyCode: mapped.androidKeyCode,
            metaState: mapped.metaState,
            repeatCount: event.isARepeat ? 1 : 0
        ))
    }

    // MARK: - NSTextInputClient

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
            callbacks.onCommitText(text)
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
}
