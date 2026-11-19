import AppKit
import CoreGraphics
import Foundation

enum IconGeneratorError: Error, CustomStringConvertible {
    case invalidArguments(program: String)
    case bitmapCreationFailed(px: Int)
    case graphicsContextFailed(px: Int)
    case gradientCreationFailed
    case pngEncodingFailed(px: Int)

    var description: String {
        switch self {
        case .invalidArguments(let program):
            return "Usage: swift \(program) <output-iconset-dir>"
        case .bitmapCreationFailed(let px):
            return "Failed to allocate \(px)x\(px) RGBA bitmap buffer"
        case .graphicsContextFailed(let px):
            return "Failed to create NSGraphicsContext for \(px)x\(px) icon"
        case .gradientCreationFailed:
            return "Failed to create background CGGradient"
        case .pngEncodingFailed(let px):
            return "Failed to encode \(px)x\(px) bitmap as PNG"
        }
    }
}

let iconSpecs: [(px: Int, filename: String)] = [
    (16, "icon_16x16.png"),
    (32, "icon_16x16@2x.png"),
    (32, "icon_32x32.png"),
    (64, "icon_32x32@2x.png"),
    (128, "icon_128x128.png"),
    (256, "icon_128x128@2x.png"),
    (256, "icon_256x256.png"),
    (512, "icon_256x256@2x.png"),
    (512, "icon_512x512.png"),
    (1024, "icon_512x512@2x.png"),
]

func fillRoundedRect(
    in cg: CGContext,
    rect: CGRect,
    cornerRadius: CGFloat,
    color: CGColor
) {
    let path = CGPath(roundedRect: rect, cornerWidth: cornerRadius, cornerHeight: cornerRadius, transform: nil)
    cg.setFillColor(color)
    cg.addPath(path)
    cg.fillPath()
}

func renderIcon(px: Int) throws -> Data {
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: px,
        pixelsHigh: px,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else {
        throw IconGeneratorError.bitmapCreationFailed(px: px)
    }
    rep.size = NSSize(width: px, height: px)

    guard let ctx = NSGraphicsContext(bitmapImageRep: rep) else {
        throw IconGeneratorError.graphicsContextFailed(px: px)
    }

    NSGraphicsContext.saveGraphicsState()
    defer { NSGraphicsContext.restoreGraphicsState() }
    NSGraphicsContext.current = ctx

    let cg = ctx.cgContext
    let s = CGFloat(px)

    // macOS squircle background with deep slate-to-indigo studio gradient
    let inset = s * 0.08
    let bgRect = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let bgPath = CGPath(roundedRect: bgRect, cornerWidth: s * 0.21, cornerHeight: s * 0.21, transform: nil)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let gradientColors = [
        CGColor(red: 0.09, green: 0.14, blue: 0.26, alpha: 1.0),
        CGColor(red: 0.14, green: 0.34, blue: 0.68, alpha: 1.0),
    ] as CFArray
    guard let gradient = CGGradient(colorsSpace: colorSpace, colors: gradientColors, locations: [0.0, 1.0]) else {
        throw IconGeneratorError.gradientCreationFailed
    }

    cg.saveGState()
    cg.addPath(bgPath)
    cg.clip()
    cg.drawLinearGradient(gradient, start: CGPoint(x: 0, y: s), end: CGPoint(x: s, y: 0), options: [])
    cg.restoreGState()

    // Display frame silhouette & inner viewport accent
    let screenRect = CGRect(x: s * 0.19, y: s * 0.34, width: s * 0.56, height: s * 0.39)
    fillRoundedRect(in: cg, rect: screenRect, cornerRadius: s * 0.045, color: CGColor(red: 1, green: 1, blue: 1, alpha: 0.94))

    let innerRect = screenRect.insetBy(dx: s * 0.032, dy: s * 0.032)
    fillRoundedRect(in: cg, rect: innerRect, cornerRadius: s * 0.022, color: CGColor(red: 0.13, green: 0.45, blue: 0.88, alpha: 1.0))

    // Display stand
    let standRect = CGRect(x: s * 0.39, y: s * 0.26, width: s * 0.16, height: s * 0.05)
    fillRoundedRect(in: cg, rect: standRect, cornerRadius: s * 0.02, color: CGColor(red: 1, green: 1, blue: 1, alpha: 0.88))

    // Overlapping phone silhouette (bottom-right)
    let phoneRect = CGRect(x: s * 0.60, y: s * 0.22, width: s * 0.22, height: s * 0.38)
    fillRoundedRect(in: cg, rect: phoneRect, cornerRadius: s * 0.04, color: CGColor(red: 0.98, green: 0.99, blue: 1.0, alpha: 1.0))

    let phoneInner = phoneRect.insetBy(dx: s * 0.025, dy: s * 0.03)
    fillRoundedRect(in: cg, rect: phoneInner, cornerRadius: s * 0.022, color: CGColor(red: 0.08, green: 0.68, blue: 0.62, alpha: 1.0))

    guard let png = rep.representation(using: .png, properties: [:]) else {
        throw IconGeneratorError.pngEncodingFailed(px: px)
    }
    return png
}

do {
    guard CommandLine.arguments.count == 2, !CommandLine.arguments[1].isEmpty else {
        throw IconGeneratorError.invalidArguments(program: CommandLine.arguments.first ?? "generate-macos-icon.swift")
    }
    let outDir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

    for (px, filename) in iconSpecs {
        let png = try renderIcon(px: px)
        try png.write(to: outDir.appendingPathComponent(filename), options: .atomic)
    }
} catch {
    let message = "ERROR: \(error)\n"
    if let data = message.data(using: .utf8) {
        FileHandle.standardError.write(data)
    }
    exit(1)
}
