import AppKit
import SwiftUI

/// Configures the hosting macOS `NSWindow` chrome cleanly between immersive full-bleed dark canvas
/// (`DisplayWindowView`) and standard native window chrome (`DeviceHubView`).
struct WindowChromeConfigurator: NSViewRepresentable {
    let isImmersiveCanvas: Bool
    var onWindowChanged: ((NSWindow?) -> Void)? = nil

    func makeNSView(context: Context) -> ChromeConfiguratorNSView {
        let view = ChromeConfiguratorNSView()
        view.isImmersiveCanvas = isImmersiveCanvas
        view.onWindowChanged = onWindowChanged
        return view
    }

    func updateNSView(_ nsView: ChromeConfiguratorNSView, context: Context) {
        nsView.isImmersiveCanvas = isImmersiveCanvas
        nsView.onWindowChanged = onWindowChanged
        nsView.applyIfNeeded()
    }

    @MainActor
    final class ChromeConfiguratorNSView: NSView {
        var isImmersiveCanvas: Bool = false
        var onWindowChanged: ((NSWindow?) -> Void)?
        private var lastAppliedMode: Bool?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            lastAppliedMode = nil
            onWindowChanged?(window)
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
                window.titleVisibility = .visible
                window.backgroundColor = .windowBackgroundColor
                window.appearance = nil
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
