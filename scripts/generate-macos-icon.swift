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
            return """
            Usage:
              swift \(program) <output-iconset-dir>
              swift \(program) --android <helper-res-dir>
            """
        case .bitmapCreationFailed(let px):
            return "Failed to allocate \(px)x\(px) RGBA bitmap buffer"
        case .graphicsContextFailed(let px):
            return "Failed to create NSGraphicsContext for \(px)x\(px) icon"
        case .gradientCreationFailed:
            return "Failed to create CGGradient"
        case .pngEncodingFailed(let px):
            return "Failed to encode \(px)x\(px) bitmap as PNG"
        }
    }
}

enum RenderStyle {
    case macOSSquircle
    case androidLegacyRounded
    case androidAdaptiveBackground
    case androidAdaptiveForeground
}

let macIconSpecs: [(px: Int, filename: String)] = [
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

let androidLegacySpecs: [(dir: String, px: Int)] = [
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]

let androidAdaptiveSpecs: [(dir: String, px: Int)] = [
    ("mipmap-hdpi", 162),
    ("mipmap-xhdpi", 216),
    ("mipmap-xxhdpi", 324),
    ("mipmap-xxxhdpi", 432),
]

func makeRGBA(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat) -> CGColor {
    CGColor(red: r, green: g, blue: b, alpha: a)
}

func roundedPath(_ rect: CGRect, radius: CGFloat) -> CGPath {
    CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

func drawRadialGlow(
    in cg: CGContext,
    colorSpace: CGColorSpace,
    center: CGPoint,
    radius: CGFloat,
    innerColor: CGColor,
    outerColor: CGColor
) throws {
    guard let grad = CGGradient(
        colorsSpace: colorSpace,
        colors: [innerColor, outerColor] as CFArray,
        locations: [0.0, 1.0]
    ) else {
        throw IconGeneratorError.gradientCreationFailed
    }
    cg.drawRadialGradient(
        grad,
        startCenter: center,
        startRadius: 0,
        endCenter: center,
        endRadius: radius,
        options: [.drawsAfterEndLocation]
    )
}

func drawOpalBackdrop(in cg: CGContext, rect: CGRect, cornerRadius: CGFloat, drawRim: Bool) throws {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let path = cornerRadius > 0 ? roundedPath(rect, radius: cornerRadius) : CGPath(rect: rect, transform: nil)

    cg.saveGState()
    cg.addPath(path)
    cg.clip()

    // Deep obsidian-sapphire studio base gradient
    let baseColors = [
        makeRGBA(0.055, 0.085, 0.175, 1.0),
        makeRGBA(0.095, 0.150, 0.295, 1.0),
        makeRGBA(0.065, 0.105, 0.220, 1.0),
    ] as CFArray
    guard let baseGrad = CGGradient(colorsSpace: colorSpace, colors: baseColors, locations: [0.0, 0.55, 1.0]) else {
        throw IconGeneratorError.gradientCreationFailed
    }
    cg.drawLinearGradient(
        baseGrad,
        start: CGPoint(x: rect.minX, y: rect.maxY),
        end: CGPoint(x: rect.maxX, y: rect.minY),
        options: []
    )

    // Iridescent Opal ambient aurora glows (aqua cyan, opalescent violet, soft pearl rose)
    try drawRadialGlow(
        in: cg,
        colorSpace: colorSpace,
        center: CGPoint(x: rect.minX + rect.width * 0.80, y: rect.minY + rect.height * 0.82),
        radius: rect.width * 0.68,
        innerColor: makeRGBA(0.18, 0.84, 0.92, 0.62),
        outerColor: makeRGBA(0.18, 0.84, 0.92, 0.0)
    )
    try drawRadialGlow(
        in: cg,
        colorSpace: colorSpace,
        center: CGPoint(x: rect.minX + rect.width * 0.22, y: rect.minY + rect.height * 0.26),
        radius: rect.width * 0.65,
        innerColor: makeRGBA(0.50, 0.42, 0.98, 0.58),
        outerColor: makeRGBA(0.50, 0.42, 0.98, 0.0)
    )
    try drawRadialGlow(
        in: cg,
        colorSpace: colorSpace,
        center: CGPoint(x: rect.minX + rect.width * 0.74, y: rect.minY + rect.height * 0.20),
        radius: rect.width * 0.48,
        innerColor: makeRGBA(0.95, 0.52, 0.78, 0.32),
        outerColor: makeRGBA(0.95, 0.52, 0.78, 0.0)
    )

    cg.restoreGState()

    if drawRim {
        cg.saveGState()
        cg.addPath(path)
        cg.setLineWidth(max(1.0, rect.width * 0.012))
        cg.setStrokeColor(makeRGBA(1.0, 1.0, 1.0, 0.22))
        cg.strokePath()
        cg.restoreGState()
    }
}

func drawGlassPane(
    in cg: CGContext,
    rect: CGRect,
    cornerRadius: CGFloat,
    topColor: CGColor,
    bottomColor: CGColor,
    rimAlpha: CGFloat,
    shadowOffset: CGSize,
    shadowBlur: CGFloat,
    shadowAlpha: CGFloat
) throws {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let path = roundedPath(rect, radius: cornerRadius)

    // Soft studio drop shadow
    cg.saveGState()
    cg.setShadow(offset: shadowOffset, blur: shadowBlur, color: makeRGBA(0.02, 0.04, 0.10, shadowAlpha))
    cg.addPath(path)
    cg.setFillColor(makeRGBA(0.10, 0.16, 0.28, 0.55))
    cg.fillPath()
    cg.restoreGState()

    // Translucent frosted glass fill gradient
    guard let grad = CGGradient(colorsSpace: colorSpace, colors: [topColor, bottomColor] as CFArray, locations: [0.0, 1.0]) else {
        throw IconGeneratorError.gradientCreationFailed
    }
    cg.saveGState()
    cg.addPath(path)
    cg.clip()
    cg.drawLinearGradient(
        grad,
        start: CGPoint(x: rect.minX, y: rect.maxY),
        end: CGPoint(x: rect.maxX, y: rect.minY),
        options: []
    )

    // Top specular glass highlight band
    let sheenRect = CGRect(x: rect.minX, y: rect.maxY - rect.height * 0.36, width: rect.width, height: rect.height * 0.36)
    guard let sheenGrad = CGGradient(
        colorsSpace: colorSpace,
        colors: [makeRGBA(1, 1, 1, 0.26), makeRGBA(1, 1, 1, 0.0)] as CFArray,
        locations: [0.0, 1.0]
    ) else {
        throw IconGeneratorError.gradientCreationFailed
    }
    cg.drawLinearGradient(
        sheenGrad,
        start: CGPoint(x: sheenRect.midX, y: sheenRect.maxY),
        end: CGPoint(x: sheenRect.midX, y: sheenRect.minY),
        options: []
    )
    cg.restoreGState()

    // Specular rim stroke
    cg.saveGState()
    cg.addPath(path)
    cg.setLineWidth(max(1.0, rect.width * 0.018))
    cg.setStrokeColor(makeRGBA(1.0, 1.0, 1.0, rimAlpha))
    cg.strokePath()
    cg.restoreGState()
}

func drawOpalForeground(in cg: CGContext, box: CGRect) throws {
    let w = box.width
    let h = box.height
    let ox = box.minX
    let oy = box.minY

    // Back frosted secondary window (upper-left — evokes multi-window / dual-screen projection)
    let backRect = CGRect(
        x: ox + w * 0.12,
        y: oy + h * 0.42,
        width: w * 0.46,
        height: h * 0.40
    )
    try drawGlassPane(
        in: cg,
        rect: backRect,
        cornerRadius: w * 0.085,
        topColor: makeRGBA(0.58, 0.90, 0.98, 0.42),
        bottomColor: makeRGBA(0.35, 0.56, 0.94, 0.28),
        rimAlpha: 0.48,
        shadowOffset: CGSize(width: 0, height: -w * 0.015),
        shadowBlur: w * 0.045,
        shadowAlpha: 0.32
    )

    // Front main Liquid Glass desktop window (center-right)
    let frontRect = CGRect(
        x: ox + w * 0.26,
        y: oy + h * 0.28,
        width: w * 0.62,
        height: h * 0.50
    )
    try drawGlassPane(
        in: cg,
        rect: frontRect,
        cornerRadius: w * 0.10,
        topColor: makeRGBA(0.95, 0.98, 1.0, 0.88),
        bottomColor: makeRGBA(0.76, 0.88, 0.99, 0.72),
        rimAlpha: 0.82,
        shadowOffset: CGSize(width: 0, height: -w * 0.025),
        shadowBlur: w * 0.075,
        shadowAlpha: 0.44
    )

    // Minimalist window control dots inside front window top-left
    let dotDia = w * 0.036
    let dotY = frontRect.maxY - w * 0.068
    let dotStartX = frontRect.minX + w * 0.062
    let dotGap = w * 0.052
    let dotColors: [CGColor] = [
        makeRGBA(0.98, 0.41, 0.44, 0.95),
        makeRGBA(0.99, 0.74, 0.25, 0.95),
        makeRGBA(0.24, 0.82, 0.48, 0.95),
    ]
    for (idx, col) in dotColors.enumerated() {
        let dotRect = CGRect(x: dotStartX + CGFloat(idx) * dotGap, y: dotY, width: dotDia, height: dotDia)
        cg.setFillColor(col)
        cg.fillEllipse(in: dotRect)
    }

    // Split / Tiling content cards inside the front desktop window
    let innerTopY = frontRect.maxY - w * 0.125
    let innerBotY = frontRect.minY + w * 0.055
    let innerH = innerTopY - innerBotY
    let leftTile = CGRect(
        x: frontRect.minX + w * 0.055,
        y: innerBotY,
        width: frontRect.width * 0.48,
        height: innerH
    )
    let rightTileTop = CGRect(
        x: leftTile.maxX + w * 0.035,
        y: innerBotY + innerH * 0.46,
        width: frontRect.maxX - (leftTile.maxX + w * 0.035) - w * 0.055,
        height: innerH * 0.54
    )
    let rightTileBot = CGRect(
        x: rightTileTop.minX,
        y: innerBotY,
        width: rightTileTop.width,
        height: innerH * 0.36
    )

    let colorSpace = CGColorSpaceCreateDeviceRGB()
    if let tileGrad = CGGradient(
        colorsSpace: colorSpace,
        colors: [makeRGBA(0.14, 0.54, 0.96, 0.85), makeRGBA(0.10, 0.74, 0.82, 0.82)] as CFArray,
        locations: [0.0, 1.0]
    ) {
        cg.saveGState()
        cg.addPath(roundedPath(leftTile, radius: w * 0.045))
        cg.clip()
        cg.drawLinearGradient(
            tileGrad,
            start: CGPoint(x: leftTile.minX, y: leftTile.maxY),
            end: CGPoint(x: leftTile.maxX, y: leftTile.minY),
            options: []
        )
        cg.restoreGState()
    }

    cg.setFillColor(makeRGBA(0.46, 0.42, 0.94, 0.65))
    cg.addPath(roundedPath(rightTileTop, radius: w * 0.04))
    cg.fillPath()

    cg.setFillColor(makeRGBA(0.20, 0.30, 0.50, 0.24))
    cg.addPath(roundedPath(rightTileBot, radius: w * 0.036))
    cg.fillPath()

    // Floating Liquid Glass Dock capsule at bottom
    let dockW = w * 0.54
    let dockH = h * 0.115
    let dockRect = CGRect(
        x: ox + (w - dockW) * 0.5,
        y: oy + h * 0.115,
        width: dockW,
        height: dockH
    )
    try drawGlassPane(
        in: cg,
        rect: dockRect,
        cornerRadius: dockH * 0.5,
        topColor: makeRGBA(1.0, 1.0, 1.0, 0.66),
        bottomColor: makeRGBA(0.84, 0.92, 1.0, 0.42),
        rimAlpha: 0.78,
        shadowOffset: CGSize(width: 0, height: -w * 0.014),
        shadowBlur: w * 0.04,
        shadowAlpha: 0.36
    )

    // 4 luminous opal app gems inside the floating dock
    let gemSize = dockH * 0.52
    let gemGap = w * 0.05
    let totalGemsW = gemSize * 4 + gemGap * 3
    let gemStartX = dockRect.midX - totalGemsW * 0.5
    let gemY = dockRect.midY - gemSize * 0.5
    let gemColors: [CGColor] = [
        makeRGBA(0.14, 0.62, 0.99, 0.96),
        makeRGBA(0.14, 0.80, 0.78, 0.96),
        makeRGBA(0.56, 0.44, 0.98, 0.96),
        makeRGBA(0.96, 0.48, 0.72, 0.94),
    ]
    for (idx, col) in gemColors.enumerated() {
        let gRect = CGRect(x: gemStartX + CGFloat(idx) * (gemSize + gemGap), y: gemY, width: gemSize, height: gemSize)
        cg.setFillColor(col)
        cg.addPath(roundedPath(gRect, radius: gemSize * 0.34))
        cg.fillPath()
    }
}

func renderIcon(px: Int, style: RenderStyle) throws -> Data {
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
    cg.interpolationQuality = .high
    cg.setAllowsAntialiasing(true)
    cg.setShouldAntialias(true)

    let s = CGFloat(px)
    let fullRect = CGRect(x: 0, y: 0, width: s, height: s)

    switch style {
    case .macOSSquircle:
        let inset = s * 0.08
        let bgRect = fullRect.insetBy(dx: inset, dy: inset)
        // Subtle macOS icon outer shadow
        cg.saveGState()
        cg.setShadow(offset: CGSize(width: 0, height: -s * 0.015), blur: s * 0.032, color: makeRGBA(0, 0, 0, 0.34))
        cg.addPath(roundedPath(bgRect, radius: s * 0.21))
        cg.setFillColor(makeRGBA(0.06, 0.09, 0.18, 1.0))
        cg.fillPath()
        cg.restoreGState()

        try drawOpalBackdrop(in: cg, rect: bgRect, cornerRadius: s * 0.21, drawRim: true)
        try drawOpalForeground(in: cg, box: bgRect.insetBy(dx: bgRect.width * 0.04, dy: bgRect.height * 0.04))

    case .androidLegacyRounded:
        let inset = s * 0.03
        let bgRect = fullRect.insetBy(dx: inset, dy: inset)
        try drawOpalBackdrop(in: cg, rect: bgRect, cornerRadius: s * 0.22, drawRim: true)
        try drawOpalForeground(in: cg, box: bgRect.insetBy(dx: bgRect.width * 0.05, dy: bgRect.height * 0.05))

    case .androidAdaptiveBackground:
        try drawOpalBackdrop(in: cg, rect: fullRect, cornerRadius: 0, drawRim: false)

    case .androidAdaptiveForeground:
        // Android adaptive icon safe zone is centered 66/108 (~61%); place emblem cleanly within 62% box.
        let margin = s * 0.19
        try drawOpalForeground(in: cg, box: fullRect.insetBy(dx: margin, dy: margin))
    }

    guard let png = rep.representation(using: .png, properties: [:]) else {
        throw IconGeneratorError.pngEncodingFailed(px: px)
    }
    return png
}

do {
    let args = CommandLine.arguments
    if args.count == 3 && args[1] == "--android" && !args[2].isEmpty {
        let resDir = URL(fileURLWithPath: args[2], isDirectory: true)
        let fm = FileManager.default

        for (dirName, px) in androidLegacySpecs {
            let dir = resDir.appendingPathComponent(dirName, isDirectory: true)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let legacyPng = try renderIcon(px: px, style: .androidLegacyRounded)
            try legacyPng.write(to: dir.appendingPathComponent("ic_launcher.png"), options: .atomic)
        }

        for (dirName, px) in androidAdaptiveSpecs {
            let dir = resDir.appendingPathComponent(dirName, isDirectory: true)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let bgPng = try renderIcon(px: px, style: .androidAdaptiveBackground)
            try bgPng.write(to: dir.appendingPathComponent("ic_launcher_background.png"), options: .atomic)
            let fgPng = try renderIcon(px: px, style: .androidAdaptiveForeground)
            try fgPng.write(to: dir.appendingPathComponent("ic_launcher_foreground.png"), options: .atomic)
        }
    } else if args.count == 2 && !args[1].isEmpty {
        let outDir = URL(fileURLWithPath: args[1], isDirectory: true)
        try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

        for (px, filename) in macIconSpecs {
            let png = try renderIcon(px: px, style: .macOSSquircle)
            try png.write(to: outDir.appendingPathComponent(filename), options: .atomic)
        }
    } else {
        throw IconGeneratorError.invalidArguments(program: args.first ?? "generate-macos-icon.swift")
    }
} catch {
    let message = "ERROR: \(error)\n"
    if let data = message.data(using: .utf8) {
        FileHandle.standardError.write(data)
    }
    exit(1)
}
