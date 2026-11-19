import AppKit
import SwiftUI

/// Configures the hosting macOS `NSWindow` chrome cleanly between immersive full-bleed dark canvas
/// (`DisplayWindowView`) and standard native window chrome (`DeviceHubView`).
struct WindowChromeConfigurator: NSViewRepresentable {
    let isImmersiveCanvas: Bool

    func makeNSView(context: Context) -> ChromeConfiguratorNSView {
        let view = ChromeConfiguratorNSView()
        view.isImmersiveCanvas = isImmersiveCanvas
        return view
    }

    func updateNSView(_ nsView: ChromeConfiguratorNSView, context: Context) {
        nsView.isImmersiveCanvas = isImmersiveCanvas
        nsView.applyIfNeeded()
    }

    @MainActor
    final class ChromeConfiguratorNSView: NSView {
        var isImmersiveCanvas: Bool = false
        private var lastAppliedMode: Bool?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            lastAppliedMode = nil
            applyIfNeeded()
        }

        func applyIfNeeded() {
            guard let window else { return }
            if lastAppliedMode == isImmersiveCanvas {
                return
            }
            lastAppliedMode = isImmersiveCanvas

            if isImmersiveCanvas {
                window.titlebarAppearsTransparent = true
                window.titleVisibility = .hidden
                window.backgroundColor = .black
                window.appearance = NSAppearance(named: .darkAqua)
                window.isMovableByWindowBackground = false
            } else {
                window.titlebarAppearsTransparent = false
                window.titleVisibility = .visible
                window.backgroundColor = .windowBackgroundColor
                window.appearance = nil
                window.isMovableByWindowBackground = false
            }
        }
    }
}
