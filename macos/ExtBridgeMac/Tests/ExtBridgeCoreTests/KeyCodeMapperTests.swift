import AppKit
import Carbon.HIToolbox
import Testing
@testable import ExtBridgeCore

@Suite("KeyCodeMapper & macOS Command-to-Ctrl Translation")
struct KeyCodeMapperTests {

    @Test("Maps standard letters, digits, arrows, and function keys to Android KEYCODE_*")
    func basicKeyMappings() {
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_ANSI_A), modifiers: [])?.androidKeyCode == 29)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_ANSI_Z), modifiers: [])?.androidKeyCode == 54)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_ANSI_0), modifiers: [])?.androidKeyCode == 7)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_ANSI_9), modifiers: [])?.androidKeyCode == 16)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_Return), modifiers: [])?.androidKeyCode == 66)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_Escape), modifiers: [])?.androidKeyCode == 111)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_Delete), modifiers: [])?.androidKeyCode == 67)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_UpArrow), modifiers: [])?.androidKeyCode == 19)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_DownArrow), modifiers: [])?.androidKeyCode == 20)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_LeftArrow), modifiers: [])?.androidKeyCode == 21)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_RightArrow), modifiers: [])?.androidKeyCode == 22)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_F1), modifiers: [])?.androidKeyCode == 131)
        #expect(KeyCodeMapper.map(macKeyCode: UInt16(kVK_F12), modifiers: [])?.androidKeyCode == 142)
    }

    @Test("Translates Cmd+C/V/X/A/Z/W to Android Ctrl when mapCommandToCtrl is enabled")
    func commandToCtrlSmartShortcuts() {
        let shortcuts: [(Int, Int32)] = [
            (kVK_ANSI_C, 31),
            (kVK_ANSI_V, 50),
            (kVK_ANSI_X, 52),
            (kVK_ANSI_A, 29),
            (kVK_ANSI_Z, 54),
            (kVK_ANSI_W, 51)
        ]

        for (macKey, expectedAndroidCode) in shortcuts {
            let mapped = KeyCodeMapper.map(
                macKeyCode: UInt16(macKey),
                modifiers: [.command],
                mapCommandToCtrl: true
            )
            #expect(mapped?.androidKeyCode == expectedAndroidCode)
            let meta = mapped?.metaState ?? 0
            #expect((meta & AndroidMetaState.ctrlOn) != 0)
            #expect((meta & AndroidMetaState.metaOn) == 0)
        }

        // Cmd+Shift+Z -> Ctrl+Shift+Z (Redo)
        let redo = KeyCodeMapper.map(
            macKeyCode: UInt16(kVK_ANSI_Z),
            modifiers: [.command, .shift],
            mapCommandToCtrl: true
        )
        #expect(redo?.androidKeyCode == 54)
        #expect(((redo?.metaState ?? 0) & AndroidMetaState.ctrlOn) != 0)
        #expect(((redo?.metaState ?? 0) & AndroidMetaState.shiftOn) != 0)
        #expect(((redo?.metaState ?? 0) & AndroidMetaState.metaOn) == 0)
    }

    @Test("Preserves Android Meta when mapCommandToCtrl is disabled")
    func preservesMetaWhenDisabled() {
        let mapped = KeyCodeMapper.map(
            macKeyCode: UInt16(kVK_ANSI_C),
            modifiers: [.command],
            mapCommandToCtrl: false
        )
        #expect(mapped?.androidKeyCode == 31)
        let meta = mapped?.metaState ?? 0
        #expect((meta & AndroidMetaState.metaOn) != 0)
        #expect((meta & AndroidMetaState.ctrlOn) == 0)
    }

    @Test("Maps modifier virtual keyCodes via Carbon kVK_* constants for down and up transitions")
    func modifierKeyHelperTransitions() {
        let cases: [(Int, NSEvent.ModifierFlags)] = [
            (kVK_Shift, .shift),
            (kVK_RightShift, .shift),
            (kVK_Control, .control),
            (kVK_RightControl, .control),
            (kVK_Option, .option),
            (kVK_RightOption, .option),
            (kVK_Command, .command),
            (kVK_RightCommand, .command),
            (kVK_CapsLock, .capsLock)
        ]

        for (vk, expectedFlag) in cases {
            let code = UInt16(vk)
            #expect(KeyCodeMapper.isModifierKeyCode(code))
            #expect(KeyCodeMapper.modifierFlag(for: code) == expectedFlag)
            #expect(KeyCodeMapper.modifierKeyAction(for: code, currentFlags: expectedFlag, previousFlags: []) == .down)
            #expect(KeyCodeMapper.modifierKeyAction(for: code, currentFlags: [], previousFlags: expectedFlag) == .up)
        }

        #expect(!KeyCodeMapper.isModifierKeyCode(UInt16(kVK_ANSI_A)))
        #expect(KeyCodeMapper.modifierFlag(for: UInt16(kVK_ANSI_A)) == nil)
    }
}
