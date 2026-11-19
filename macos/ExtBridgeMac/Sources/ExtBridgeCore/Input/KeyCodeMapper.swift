import AppKit
import Carbon.HIToolbox

/// Android `android.view.KeyEvent` meta-state bitmasks.
public enum AndroidMetaState {
    public static let shiftOn: Int32 = 0x0000_0001
    public static let altOn: Int32 = 0x0000_0002
    public static let altLeftOn: Int32 = 0x0000_0010
    public static let shiftLeftOn: Int32 = 0x0000_0040
    public static let ctrlOn: Int32 = 0x0000_1000
    public static let ctrlLeftOn: Int32 = 0x0000_2000
    public static let metaOn: Int32 = 0x0001_0000
    public static let metaLeftOn: Int32 = 0x0002_0000
    public static let capsLockOn: Int32 = 0x0010_0000
}

/// Translated Android key event ready to encode as `0x25 INPUT_KEY`.
public struct MappedAndroidKey: Sendable, Equatable {
    public let androidKeyCode: Int32
    public let metaState: Int32

    public init(androidKeyCode: Int32, metaState: Int32) {
        self.androidKeyCode = androidKeyCode
        self.metaState = metaState
    }
}

/// Pure mapper translating macOS virtual `CGKeyCode` + `NSEvent.ModifierFlags`
/// to Android `KeyEvent.KEYCODE_*` and `META_*` masks.
public enum KeyCodeMapper {
    /// Virtual keys that trigger standard desktop shortcuts when combined with Command (`⌘`).
    private static let commandToCtrlKeyCodes: Set<UInt16> = [
        UInt16(kVK_ANSI_A), // Select All
        UInt16(kVK_ANSI_B), // Bold
        UInt16(kVK_ANSI_C), // Copy
        UInt16(kVK_ANSI_F), // Find
        UInt16(kVK_ANSI_I), // Italic
        UInt16(kVK_ANSI_K), // Link / Command palette
        UInt16(kVK_ANSI_L), // Address bar
        UInt16(kVK_ANSI_N), // New
        UInt16(kVK_ANSI_O), // Open
        UInt16(kVK_ANSI_P), // Print
        UInt16(kVK_ANSI_R), // Reload
        UInt16(kVK_ANSI_S), // Save
        UInt16(kVK_ANSI_T), // New Tab
        UInt16(kVK_ANSI_U), // Underline
        UInt16(kVK_ANSI_V), // Paste
        UInt16(kVK_ANSI_W), // Close Tab/Window
        UInt16(kVK_ANSI_X), // Cut
        UInt16(kVK_ANSI_Y), // Redo
        UInt16(kVK_ANSI_Z), // Undo / Shift+Z Redo
        UInt16(kVK_ANSI_Equal), // Zoom in
        UInt16(kVK_ANSI_Minus), // Zoom out
        UInt16(kVK_ANSI_0), // Reset zoom
        UInt16(kVK_ANSI_1), UInt16(kVK_ANSI_2), UInt16(kVK_ANSI_3),
        UInt16(kVK_ANSI_4), UInt16(kVK_ANSI_5), UInt16(kVK_ANSI_6),
        UInt16(kVK_ANSI_7), UInt16(kVK_ANSI_8), UInt16(kVK_ANSI_9)
    ]

    /// Maps a macOS hardware virtual keyCode + modifier flags to Android keyCode & metaState.
    /// When `mapCommandToCtrl` is `true`, standard editing/navigation shortcuts like `⌘C/V/X/A/Z/W`
    /// automatically translate `Command` into Android `Ctrl`.
    public static func map(
        macKeyCode: UInt16,
        modifiers: NSEvent.ModifierFlags,
        mapCommandToCtrl: Bool = true
    ) -> MappedAndroidKey? {
        guard let androidKeyCode = macToAndroidKeyCode[macKeyCode] else {
            return nil
        }
        let promoteCommandToCtrl = mapCommandToCtrl
            && modifiers.contains(.command)
            && !modifiers.contains(.control)
            && commandToCtrlKeyCodes.contains(macKeyCode)

        let meta = mapModifiers(modifiers, promoteCommandToCtrl: promoteCommandToCtrl)
        return MappedAndroidKey(androidKeyCode: androidKeyCode, metaState: meta)
    }

    /// Maps macOS modifier flags to Android `KeyEvent.META_*` bits.
    public static func mapModifiers(
        _ modifiers: NSEvent.ModifierFlags,
        promoteCommandToCtrl: Bool = false
    ) -> Int32 {
        var meta: Int32 = 0
        if modifiers.contains(.shift) {
            meta |= AndroidMetaState.shiftOn | AndroidMetaState.shiftLeftOn
        }
        if modifiers.contains(.option) {
            meta |= AndroidMetaState.altOn | AndroidMetaState.altLeftOn
        }
        if modifiers.contains(.control) || (promoteCommandToCtrl && modifiers.contains(.command)) {
            meta |= AndroidMetaState.ctrlOn | AndroidMetaState.ctrlLeftOn
        }
        if modifiers.contains(.command) && !promoteCommandToCtrl {
            meta |= AndroidMetaState.metaOn | AndroidMetaState.metaLeftOn
        }
        if modifiers.contains(.capsLock) {
            meta |= AndroidMetaState.capsLockOn
        }
        return meta
    }

    /// Returns the `NSEvent.ModifierFlags` bit corresponding to a modifier virtual keyCode (`kVK_*`),
    /// or `nil` if `macKeyCode` is not a modifier key.
    public static func modifierFlag(for macKeyCode: UInt16) -> NSEvent.ModifierFlags? {
        switch Int(macKeyCode) {
        case kVK_Shift, kVK_RightShift:
            return .shift
        case kVK_Control, kVK_RightControl:
            return .control
        case kVK_Option, kVK_RightOption:
            return .option
        case kVK_Command, kVK_RightCommand:
            return .command
        case kVK_CapsLock:
            return .capsLock
        default:
            return nil
        }
    }

    /// Resolves whether a `flagsChanged` transition for `macKeyCode` is a key-down or key-up event.
    public static func modifierKeyAction(
        for macKeyCode: UInt16,
        currentFlags: NSEvent.ModifierFlags,
        previousFlags: NSEvent.ModifierFlags
    ) -> ExtCastKeyAction {
        if let flag = modifierFlag(for: macKeyCode) {
            return currentFlags.contains(flag) ? .down : .up
        }
        return currentFlags.rawValue > previousFlags.rawValue ? .down : .up
    }

    /// Returns `true` when `macKeyCode` is itself a modifier key (Shift/Ctrl/Option/Command/CapsLock).
    public static func isModifierKeyCode(_ macKeyCode: UInt16) -> Bool {
        modifierFlag(for: macKeyCode) != nil
    }

    // Complete ANSI macOS CGKeyCode -> Android KeyEvent.KEYCODE_* lookup table.
    private static let macToAndroidKeyCode: [UInt16: Int32] = [
        // Letters (KEYCODE_A = 29 ... KEYCODE_Z = 54)
        UInt16(kVK_ANSI_A): 29,
        UInt16(kVK_ANSI_B): 30,
        UInt16(kVK_ANSI_C): 31,
        UInt16(kVK_ANSI_D): 32,
        UInt16(kVK_ANSI_E): 33,
        UInt16(kVK_ANSI_F): 34,
        UInt16(kVK_ANSI_G): 35,
        UInt16(kVK_ANSI_H): 36,
        UInt16(kVK_ANSI_I): 37,
        UInt16(kVK_ANSI_J): 38,
        UInt16(kVK_ANSI_K): 39,
        UInt16(kVK_ANSI_L): 40,
        UInt16(kVK_ANSI_M): 41,
        UInt16(kVK_ANSI_N): 42,
        UInt16(kVK_ANSI_O): 43,
        UInt16(kVK_ANSI_P): 44,
        UInt16(kVK_ANSI_Q): 45,
        UInt16(kVK_ANSI_R): 46,
        UInt16(kVK_ANSI_S): 47,
        UInt16(kVK_ANSI_T): 48,
        UInt16(kVK_ANSI_U): 49,
        UInt16(kVK_ANSI_V): 50,
        UInt16(kVK_ANSI_W): 51,
        UInt16(kVK_ANSI_X): 52,
        UInt16(kVK_ANSI_Y): 53,
        UInt16(kVK_ANSI_Z): 54,

        // Top row digits (KEYCODE_0 = 7 ... KEYCODE_9 = 16)
        UInt16(kVK_ANSI_0): 7,
        UInt16(kVK_ANSI_1): 8,
        UInt16(kVK_ANSI_2): 9,
        UInt16(kVK_ANSI_3): 10,
        UInt16(kVK_ANSI_4): 11,
        UInt16(kVK_ANSI_5): 12,
        UInt16(kVK_ANSI_6): 13,
        UInt16(kVK_ANSI_7): 14,
        UInt16(kVK_ANSI_8): 15,
        UInt16(kVK_ANSI_9): 16,

        // Punctuation & symbols
        UInt16(kVK_ANSI_Comma): 55,        // KEYCODE_COMMA
        UInt16(kVK_ANSI_Period): 56,       // KEYCODE_PERIOD
        UInt16(kVK_ANSI_Grave): 68,        // KEYCODE_GRAVE
        UInt16(kVK_ANSI_Minus): 69,        // KEYCODE_MINUS
        UInt16(kVK_ANSI_Equal): 70,        // KEYCODE_EQUALS
        UInt16(kVK_ANSI_LeftBracket): 71,  // KEYCODE_LEFT_BRACKET
        UInt16(kVK_ANSI_RightBracket): 72, // KEYCODE_RIGHT_BRACKET
        UInt16(kVK_ANSI_Backslash): 73,    // KEYCODE_BACKSLASH
        UInt16(kVK_ANSI_Semicolon): 74,    // KEYCODE_SEMICOLON
        UInt16(kVK_ANSI_Quote): 75,        // KEYCODE_APOSTROPHE
        UInt16(kVK_ANSI_Slash): 76,        // KEYCODE_SLASH

        // Control & editing keys
        UInt16(kVK_Tab): 61,               // KEYCODE_TAB
        UInt16(kVK_Space): 62,             // KEYCODE_SPACE
        UInt16(kVK_Return): 66,            // KEYCODE_ENTER
        UInt16(kVK_Delete): 67,            // KEYCODE_DEL (Backspace)
        UInt16(kVK_Escape): 111,           // KEYCODE_ESCAPE
        UInt16(kVK_ForwardDelete): 112,    // KEYCODE_FORWARD_DEL
        UInt16(kVK_PageUp): 92,            // KEYCODE_PAGE_UP
        UInt16(kVK_PageDown): 93,          // KEYCODE_PAGE_DOWN
        UInt16(kVK_Home): 122,             // KEYCODE_MOVE_HOME
        UInt16(kVK_End): 123,              // KEYCODE_MOVE_END

        // Arrow / D-pad keys
        UInt16(kVK_UpArrow): 19,           // KEYCODE_DPAD_UP
        UInt16(kVK_DownArrow): 20,         // KEYCODE_DPAD_DOWN
        UInt16(kVK_LeftArrow): 21,         // KEYCODE_DPAD_LEFT
        UInt16(kVK_RightArrow): 22,        // KEYCODE_DPAD_RIGHT

        // Function keys F1-F12 (KEYCODE_F1 = 131 ... KEYCODE_F12 = 142)
        UInt16(kVK_F1): 131,
        UInt16(kVK_F2): 132,
        UInt16(kVK_F3): 133,
        UInt16(kVK_F4): 134,
        UInt16(kVK_F5): 135,
        UInt16(kVK_F6): 136,
        UInt16(kVK_F7): 137,
        UInt16(kVK_F8): 138,
        UInt16(kVK_F9): 139,
        UInt16(kVK_F10): 140,
        UInt16(kVK_F11): 141,
        UInt16(kVK_F12): 142,

        // Numpad
        UInt16(kVK_ANSI_Keypad0): 144,
        UInt16(kVK_ANSI_Keypad1): 145,
        UInt16(kVK_ANSI_Keypad2): 146,
        UInt16(kVK_ANSI_Keypad3): 147,
        UInt16(kVK_ANSI_Keypad4): 148,
        UInt16(kVK_ANSI_Keypad5): 149,
        UInt16(kVK_ANSI_Keypad6): 150,
        UInt16(kVK_ANSI_Keypad7): 151,
        UInt16(kVK_ANSI_Keypad8): 152,
        UInt16(kVK_ANSI_Keypad9): 153,
        UInt16(kVK_ANSI_KeypadDivide): 154,
        UInt16(kVK_ANSI_KeypadMultiply): 155,
        UInt16(kVK_ANSI_KeypadMinus): 156,
        UInt16(kVK_ANSI_KeypadPlus): 157,
        UInt16(kVK_ANSI_KeypadDecimal): 158,
        UInt16(kVK_ANSI_KeypadEnter): 160,
        UInt16(kVK_ANSI_KeypadEquals): 161,

        // Modifiers
        UInt16(kVK_Option): 57,            // KEYCODE_ALT_LEFT
        UInt16(kVK_RightOption): 58,       // KEYCODE_ALT_RIGHT
        UInt16(kVK_Shift): 59,             // KEYCODE_SHIFT_LEFT
        UInt16(kVK_RightShift): 60,        // KEYCODE_SHIFT_RIGHT
        UInt16(kVK_Control): 113,          // KEYCODE_CTRL_LEFT
        UInt16(kVK_RightControl): 114,     // KEYCODE_CTRL_RIGHT
        UInt16(kVK_CapsLock): 115,         // KEYCODE_CAPS_LOCK
        UInt16(kVK_Command): 117,          // KEYCODE_META_LEFT
        UInt16(kVK_RightCommand): 118,     // KEYCODE_META_RIGHT

        // Media / Volume
        UInt16(kVK_VolumeUp): 24,
        UInt16(kVK_VolumeDown): 25,
        UInt16(kVK_Mute): 164
    ]
}
