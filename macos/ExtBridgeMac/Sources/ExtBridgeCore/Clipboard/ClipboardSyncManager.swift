import AppKit
import CoreGraphics
import CryptoKit
import Foundation

/// Bidirectional macOS <-> Android text & PNG image clipboard synchronizer with SHA-256 loop prevention.
@MainActor
public final class ClipboardSyncManager {
    private static let maxSyncedUTF8Bytes = 256 * 1024 // 256 KiB
    private static let pollIntervalNs: UInt64 = 450_000_000 // 0.45s
    nonisolated private static let maxDownscaleAttempts = 6
    nonisolated private static let initialDownscaleFactor = 0.75
    nonisolated private static let subsequentDownscaleStep = 0.7
    nonisolated private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    private struct RawPasteboardImage: Sendable {
        let data: Data
        let isTIFF: Bool
    }

    private let pasteboard: NSPasteboard
    private var pollTask: Task<Void, Never>?
    private var imageWorkTask: Task<Void, Never>?
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
        let initialChangeCount = pasteboard.changeCount
        self.lastObservedChangeCount = initialChangeCount

        // Seed loop-prevention digest without blocking MainActor on large image normalization.
        if let rawImage = snapshotLocalImage(from: pasteboard) {
            imageWorkTask = Task { [weak self] in
                guard let normalized = await Self.normalizeAndDigestOffMain(rawImage) else { return }
                guard !Task.isCancelled, let self, self.lastObservedChangeCount == initialChangeCount else { return }
                self.lastSyncedDigest = normalized.digest
            }
        } else if let existing = pasteboard.string(forType: .string) {
            self.lastSyncedDigest = Self.textDigest(existing)
        }

        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: Self.pollIntervalNs)
                } catch {
                    break
                }
                guard !Task.isCancelled, let self else { break }
                self.pollLocalPasteboard()
            }
        }
    }

    public func stop() {
        pollTask?.cancel()
        pollTask = nil
        imageWorkTask?.cancel()
        imageWorkTask = nil
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

        imageWorkTask?.cancel()
        imageWorkTask = nil
        lastSyncedDigest = incomingDigest
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastObservedChangeCount = pasteboard.changeCount
    }

    /// Applies PNG image pushed from Android (`0x11 CLIPBOARD_PUSH`, `flags = 0x01`) without triggering a send-back loop.
    public func applyRemoteClipboardImage(seq: Int64, pngData: Data) {
        guard isEnabled else { return }
        guard !pngData.isEmpty, pngData.count <= ExtCastProtocol.maxClipboardImageBytes else {
            ExtBridgeLog.clipboard.warning("Rejected remote clipboard PNG: invalid size \(pngData.count)")
            return
        }
        guard Self.hasPNGSignature(pngData) else {
            ExtBridgeLog.clipboard.warning("Rejected remote clipboard PNG: invalid PNG signature")
            return
        }
        if seq > 0 && seq <= lastRemoteSeq {
            return
        }
        lastRemoteSeq = max(lastRemoteSeq, seq)

        let incomingDigest = Self.imageDigest(pngData)
        if incomingDigest == lastSyncedDigest {
            return
        }

        imageWorkTask?.cancel()
        imageWorkTask = nil
        lastSyncedDigest = incomingDigest
        pasteboard.clearContents()
        pasteboard.setData(pngData, forType: .png)
        lastObservedChangeCount = pasteboard.changeCount
    }

    /// Converts `.png` or `.tiff` image bytes into a valid PNG guaranteed to fit within `maxBytes` (default 8 MiB).
    /// Pure helper safe to execute off `@MainActor` (also exposed for unit tests).
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
            ExtBridgeLog.clipboard.warning("Failed to decode clipboard bitmap (\(rawData.count) bytes, isTIFF=\(isTIFF))")
            return nil
        }

        if let directPNG = bitmap.representation(using: .png, properties: [:]),
           directPNG.count <= maxBytes
        {
            return directPNG
        }

        // Iteratively downscale oversized images until the PNG fits strictly within `maxBytes`.
        guard let cgImage = bitmap.cgImage else { return nil }
        var scale = initialDownscaleFactor
        for _ in 0..<maxDownscaleAttempts {
            let targetW = max(1, Int((Double(cgImage.width) * scale).rounded()))
            let targetH = max(1, Int((Double(cgImage.height) * scale).rounded()))
            if let scaledPNG = renderScaledPNG(cgImage: cgImage, width: targetW, height: targetH),
               scaledPNG.count <= maxBytes
            {
                return scaledPNG
            }
            scale *= subsequentDownscaleStep
        }
        ExtBridgeLog.clipboard.warning("Clipboard image exceeded \(maxBytes) bytes even after \(maxDownscaleAttempts) downscale passes")
        return nil
    }

    nonisolated public static func hasPNGSignature(_ data: Data) -> Bool {
        guard data.count >= pngSignature.count else { return false }
        return data.prefix(pngSignature.count).elementsEqual(pngSignature)
    }

    nonisolated public static func textDigest(_ text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return "txt:" + hash.map { String(format: "%02x", $0) }.joined()
    }

    nonisolated public static func imageDigest(_ data: Data) -> String {
        let hash = SHA256.hash(data: data)
        return "img:" + hash.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Private Polling & Off-MainActor Encoding Helpers

    private func pollLocalPasteboard() {
        guard isEnabled else { return }
        let currentCount = pasteboard.changeCount
        guard currentCount != lastObservedChangeCount else { return }
        lastObservedChangeCount = currentCount

        // Prefer image payload when screenshot/image is copied (excluding Finder file URL copies).
        if onLocalImageClipboardChange != nil, let rawImage = snapshotLocalImage(from: pasteboard) {
            imageWorkTask?.cancel()
            imageWorkTask = Task { [weak self] in
                guard let normalized = await Self.normalizeAndDigestOffMain(rawImage) else { return }
                guard !Task.isCancelled, let self, self.isEnabled, self.lastObservedChangeCount == currentCount else {
                    return
                }
                guard normalized.digest != self.lastSyncedDigest else { return }
                self.lastSyncedDigest = normalized.digest
                let seq = self.nextLocalSeq
                self.nextLocalSeq += 1
                self.onLocalImageClipboardChange?(seq, normalized.pngData)
            }
            return
        }

        imageWorkTask?.cancel()
        imageWorkTask = nil

        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            return
        }
        guard text.utf8.count <= Self.maxSyncedUTF8Bytes else {
            ExtBridgeLog.clipboard.debug("Skipping oversized clipboard text (\(text.utf8.count) UTF-8 bytes)")
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

    private func snapshotLocalImage(from pasteboard: NSPasteboard) -> RawPasteboardImage? {
        let types = pasteboard.types ?? []
        if types.contains(.png), let pngRaw = pasteboard.data(forType: .png) {
            return RawPasteboardImage(data: pngRaw, isTIFF: false)
        }
        // Avoid treating Finder file copies (which carry `.fileURL` + an icon `.tiff`) as clipboard screenshots.
        if !types.contains(.fileURL), types.contains(.tiff), let tiffRaw = pasteboard.data(forType: .tiff) {
            return RawPasteboardImage(data: tiffRaw, isTIFF: true)
        }
        return nil
    }

    nonisolated private static func normalizeAndDigestOffMain(
        _ rawImage: RawPasteboardImage
    ) async -> (pngData: Data, digest: String)? {
        await Task.detached(priority: .utility) {
            guard let pngData = normalizePNGData(rawImage.data, isTIFF: rawImage.isTIFF) else {
                return nil
            }
            return (pngData, imageDigest(pngData))
        }.value
    }

    nonisolated private static func renderScaledPNG(cgImage: CGImage, width: Int, height: Int) -> Data? {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }
        ctx.interpolationQuality = .high
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let scaledCG = ctx.makeImage() else { return nil }
        return NSBitmapImageRep(cgImage: scaledCG).representation(using: .png, properties: [:])
    }
}
