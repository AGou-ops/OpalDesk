import AppKit
import CryptoKit
import Foundation

/// Bidirectional macOS <-> Android text & PNG image clipboard synchronizer with SHA-256 loop prevention.
@MainActor
public final class ClipboardSyncManager {
    private static let maxSyncedUTF8Bytes = 256 * 1024 // 256 KiB
    private static let pollIntervalSeconds: TimeInterval = 0.45
    nonisolated private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    private let pasteboard: NSPasteboard
    private var timer: Timer?
    private var lastObservedChangeCount: Int
    private var lastSyncedDigest: String = ""
    private var lastRemoteSeq: Int64 = 0
    private var nextLocalSeq: Int64 = 1
    private var onLocalClipboardChange: (@MainActor (Int64, String) -> Void)?
    private var onLocalImageClipboardChange: (@MainActor (Int64, Data) -> Void)?

    public var isEnabled: Bool = true

    public init(pasteboard: NSPasteboard = .general) {
        self.pasteboard = pasteboard
        self.lastObservedChangeCount = pasteboard.changeCount
    }

    public func start(
        onLocalChange: @escaping @MainActor (Int64, String) -> Void,
        onLocalImageChange: (@MainActor (Int64, Data) -> Void)? = nil
    ) {
        stop()
        self.onLocalClipboardChange = onLocalChange
        self.onLocalImageClipboardChange = onLocalImageChange
        self.lastObservedChangeCount = pasteboard.changeCount

        if let initialImage = extractLocalPNG(from: pasteboard) {
            self.lastSyncedDigest = Self.imageDigest(initialImage)
        } else if let existing = pasteboard.string(forType: .string) {
            self.lastSyncedDigest = Self.textDigest(existing)
        }

        let newTimer = Timer.scheduledTimer(withTimeInterval: Self.pollIntervalSeconds, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pollLocalPasteboard()
            }
        }
        RunLoop.main.add(newTimer, forMode: .common)
        self.timer = newTimer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
        onLocalClipboardChange = nil
        onLocalImageClipboardChange = nil
    }

    /// Applies clipboard text pushed from Android (`0x11 CLIPBOARD_PUSH`, `flags = 0`) without triggering a send-back loop.
    public func applyRemoteClipboard(seq: Int64, text: String) {
        guard isEnabled else { return }
        if seq > 0 && seq <= lastRemoteSeq {
            return
        }
        lastRemoteSeq = max(lastRemoteSeq, seq)

        let incomingDigest = Self.textDigest(text)
        if incomingDigest == lastSyncedDigest {
            return
        }

        lastSyncedDigest = incomingDigest
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastObservedChangeCount = pasteboard.changeCount
    }

    /// Applies PNG image pushed from Android (`0x11 CLIPBOARD_PUSH`, `flags = 0x01`) without triggering a send-back loop.
    public func applyRemoteClipboardImage(seq: Int64, pngData: Data) {
        guard isEnabled else { return }
        guard !pngData.isEmpty, pngData.count <= ExtCastProtocol.maxClipboardImageBytes else { return }
        guard Self.hasPNGSignature(pngData) else { return }
        if seq > 0 && seq <= lastRemoteSeq {
            return
        }
        lastRemoteSeq = max(lastRemoteSeq, seq)

        let incomingDigest = Self.imageDigest(pngData)
        if incomingDigest == lastSyncedDigest {
            return
        }

        lastSyncedDigest = incomingDigest
        pasteboard.clearContents()
        pasteboard.setData(pngData, forType: .png)
        lastObservedChangeCount = pasteboard.changeCount
    }

    /// Converts `.png` or `.tiff` image bytes into a valid PNG guaranteed to fit within `maxBytes` (default 8 MiB).
    /// Pure helper exposed for unit tests.
    nonisolated public static func normalizePNGData(
        _ rawData: Data,
        isTIFF: Bool,
        maxBytes: Int = ExtCastProtocol.maxClipboardImageBytes
    ) -> Data? {
        guard !rawData.isEmpty, maxBytes > 64 else { return nil }

        // Fast path: already a valid PNG within size limit.
        if !isTIFF && hasPNGSignature(rawData) && rawData.count <= maxBytes {
            return rawData
        }

        guard let bitmap = NSBitmapImageRep(data: rawData),
              bitmap.pixelsWide > 0,
              bitmap.pixelsHigh > 0
        else {
            return nil
        }

        if let directPNG = bitmap.representation(using: .png, properties: [:]),
           directPNG.count <= maxBytes
        {
            return directPNG
        }

        // Iteratively downscale oversized images until the PNG fits strictly within `maxBytes`.
        guard let cgImage = bitmap.cgImage else { return nil }
        var scale: Double = 0.75
        for _ in 0..<6 {
            let targetW = max(1, Int((Double(cgImage.width) * scale).rounded()))
            let targetH = max(1, Int((Double(cgImage.height) * scale).rounded()))
            if let scaledPNG = renderScaledPNG(cgImage: cgImage, width: targetW, height: targetH),
               scaledPNG.count <= maxBytes
            {
                return scaledPNG
            }
            scale *= 0.7
        }
        return nil
    }

    nonisolated public static func hasPNGSignature(_ data: Data) -> Bool {
        guard data.count >= pngSignature.count else { return false }
        return data.prefix(pngSignature.count).elementsEqual(pngSignature)
    }

    // MARK: - Private Polling & Encoding Helpers

    private func pollLocalPasteboard() {
        guard isEnabled else { return }
        let currentCount = pasteboard.changeCount
        guard currentCount != lastObservedChangeCount else { return }
        lastObservedChangeCount = currentCount

        // Prefer image payload when screenshot/image is copied (excluding Finder file URL copies that attach an icon TIFF).
        if onLocalImageClipboardChange != nil, let pngData = extractLocalPNG(from: pasteboard) {
            let currentDigest = Self.imageDigest(pngData)
            guard currentDigest != lastSyncedDigest else {
                return
            }
            lastSyncedDigest = currentDigest
            let seq = nextLocalSeq
            nextLocalSeq += 1
            onLocalImageClipboardChange?(seq, pngData)
            return
        }

        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            return
        }
        guard text.utf8.count <= Self.maxSyncedUTF8Bytes else {
            return
        }

        let currentDigest = Self.textDigest(text)
        guard currentDigest != lastSyncedDigest else {
            return
        }

        lastSyncedDigest = currentDigest
        let seq = nextLocalSeq
        nextLocalSeq += 1
        onLocalClipboardChange?(seq, text)
    }

    private func extractLocalPNG(from pasteboard: NSPasteboard) -> Data? {
        let types = pasteboard.types ?? []
        if types.contains(.png), let pngRaw = pasteboard.data(forType: .png) {
            return Self.normalizePNGData(pngRaw, isTIFF: false)
        }
        // Avoid treating Finder file copies (which carry `.fileURL` + an icon `.tiff`) as clipboard screenshots.
        if !types.contains(.fileURL), types.contains(.tiff), let tiffRaw = pasteboard.data(forType: .tiff) {
            return Self.normalizePNGData(tiffRaw, isTIFF: true)
        }
        return nil
    }

    nonisolated private static func renderScaledPNG(cgImage: CGImage, width: Int, height: Int) -> Data? {
        guard let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: width,
            pixelsHigh: height,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            return nil
        }
        rep.size = NSSize(width: width, height: height)
        guard let ctx = NSGraphicsContext(bitmapImageRep: rep) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = ctx
        ctx.imageInterpolation = .high
        ctx.cgContext.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .png, properties: [:])
    }

    nonisolated public static func textDigest(_ text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return "txt:" + hash.map { String(format: "%02x", $0) }.joined()
    }

    nonisolated public static func imageDigest(_ data: Data) -> String {
        let hash = SHA256.hash(data: data)
        return "img:" + hash.map { String(format: "%02x", $0) }.joined()
    }
}
