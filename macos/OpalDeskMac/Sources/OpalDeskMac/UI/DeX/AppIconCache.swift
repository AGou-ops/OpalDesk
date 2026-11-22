import AppKit
import SwiftUI

/// Minimal dark canvas chrome color matching the projected Android display stage.
enum DeXTheme {
    /// Deep slate-obsidian canvas `#0B0E14` matching dark DeX chrome and split seams.
    static let canvasBackground = Color(red: 0.043, green: 0.055, blue: 0.078)
    static let canvasNSColor = NSColor(calibratedRed: 0.043, green: 0.055, blue: 0.078, alpha: 1.0)
}
