import ExtBridgeCore
import Foundation
import Observation

/// UserDefaults-backed preferences for `ExtBridgeMac`.
@MainActor
@Observable
public final class PreferencesStore {
    private enum Key {
        static let preferredCodec = "extbridge.pref.codec"
        static let bitrateKbps = "extbridge.pref.bitrateKbps"
        static let defaultDisplayMode = "extbridge.pref.displayMode"
        static let defaultDPI = "extbridge.pref.dpi"
        static let targetFPS = "extbridge.pref.fps"
        static let audioEnabled = "extbridge.pref.audioEnabled"
        static let mapCommandToCtrl = "extbridge.pref.mapCommandToCtrl"
        static let clipboardSyncEnabled = "extbridge.pref.clipboardSyncEnabled"
        static let resolutionPreset = "extbridge.pref.resolutionPreset"
        static let customAdbPath = "extbridge.pref.customAdbPath"
        static let showHUDStats = "extbridge.pref.showHUDStats"
        static let adaptiveSingleEnabled = "extbridge.pref.adaptiveSingleEnabled"
        static let defaultSingleWidthDp = "extbridge.pref.defaultSingleWidthDp"
        static let inputBackend = "extbridge.pref.inputBackend"
    }

    private let defaults: UserDefaults

    public var preferredCodec: ExtCastVideoCodec {
        didSet { defaults.set(Int(preferredCodec.rawValue), forKey: Key.preferredCodec) }
    }

    public var bitrateKbps: Int {
        didSet { defaults.set(bitrateKbps, forKey: Key.bitrateKbps) }
    }

    public var defaultDisplayMode: ExtCastDisplayMode {
        didSet { defaults.set(Int(defaultDisplayMode.rawValue), forKey: Key.defaultDisplayMode) }
    }

    public var defaultDPI: Int {
        didSet { defaults.set(defaultDPI, forKey: Key.defaultDPI) }
    }

    public var targetFPS: Int {
        didSet { defaults.set(targetFPS, forKey: Key.targetFPS) }
    }

    public var audioEnabled: Bool {
        didSet { defaults.set(audioEnabled, forKey: Key.audioEnabled) }
    }

    public var mapCommandToCtrl: Bool {
        didSet { defaults.set(mapCommandToCtrl, forKey: Key.mapCommandToCtrl) }
    }

    public var clipboardSyncEnabled: Bool {
        didSet { defaults.set(clipboardSyncEnabled, forKey: Key.clipboardSyncEnabled) }
    }

    public var resolutionPreset: DisplayResolutionPreset {
        didSet { defaults.set(resolutionPreset.rawValue, forKey: Key.resolutionPreset) }
    }

    public var customAdbPath: String {
        didSet { defaults.set(customAdbPath, forKey: Key.customAdbPath) }
    }

    public var showHUDStats: Bool {
        didSet { defaults.set(showHUDStats, forKey: Key.showHUDStats) }
    }

    public var adaptiveSingleEnabled: Bool {
        didSet { defaults.set(adaptiveSingleEnabled, forKey: Key.adaptiveSingleEnabled) }
    }

    public var defaultSingleWidthDp: Int {
        didSet { defaults.set(defaultSingleWidthDp, forKey: Key.defaultSingleWidthDp) }
    }

    public var inputBackend: ExtCastInputBackend {
        didSet { defaults.set(inputBackend.rawValue, forKey: Key.inputBackend) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        let codecRaw = defaults.object(forKey: Key.preferredCodec) as? Int
        self.preferredCodec = codecRaw.flatMap { ExtCastVideoCodec(rawValue: UInt8($0)) } ?? .hevc

        let storedBitrate = defaults.integer(forKey: Key.bitrateKbps)
        self.bitrateKbps = storedBitrate > 0
            ? DisplayPresets.clampBitrateKbps(storedBitrate)
            : DisplayPresets.defaultBitrateKbps

        let modeRaw = defaults.object(forKey: Key.defaultDisplayMode) as? Int
        let parsedMode = modeRaw.flatMap { ExtCastDisplayMode(rawValue: UInt8($0)) }
        self.defaultDisplayMode = (parsedMode == .mirror) ? .mirror : .dex

        let storedDPI = defaults.integer(forKey: Key.defaultDPI)
        self.defaultDPI = DisplayPresets.dpiOptions.contains(storedDPI)
            ? storedDPI
            : DisplayPresets.defaultDPI

        let storedFPS = defaults.integer(forKey: Key.targetFPS)
        self.targetFPS = DisplayPresets.fpsOptions.contains(storedFPS)
            ? storedFPS
            : DisplayPresets.defaultFPS

        self.audioEnabled = defaults.object(forKey: Key.audioEnabled) as? Bool ?? true
        self.mapCommandToCtrl = defaults.object(forKey: Key.mapCommandToCtrl) as? Bool ?? true
        self.clipboardSyncEnabled = defaults.object(forKey: Key.clipboardSyncEnabled) as? Bool ?? true

        let presetRaw = defaults.string(forKey: Key.resolutionPreset) ?? DisplayResolutionPreset.matchWindow.rawValue
        self.resolutionPreset = DisplayResolutionPreset(rawValue: presetRaw) ?? .matchWindow

        self.customAdbPath = defaults.string(forKey: Key.customAdbPath) ?? ""
        self.showHUDStats = defaults.object(forKey: Key.showHUDStats) as? Bool ?? true

        self.adaptiveSingleEnabled = defaults.object(forKey: Key.adaptiveSingleEnabled) as? Bool ?? true
        let storedWidthDp = defaults.integer(forKey: Key.defaultSingleWidthDp)
        self.defaultSingleWidthDp = storedWidthDp > 0
            ? DisplayPresets.clampSingleWidthDp(storedWidthDp)
            : DisplayPresets.defaultSingleWidthDp

        let backendRaw = defaults.string(forKey: Key.inputBackend) ?? ExtCastInputBackend.auto.rawValue
        self.inputBackend = ExtCastInputBackend(rawValue: backendRaw) ?? .auto
    }
}
