// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ExtBridgeMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "ExtBridgeCore",
            targets: ["ExtBridgeCore"]
        ),
        .executable(
            name: "ExtBridgeMac",
            targets: ["ExtBridgeMac"]
        )
    ],
    targets: [
        .target(
            name: "ExtBridgeCore",
            path: "Sources/ExtBridgeCore",
            linkerSettings: [
                .linkedFramework("Network"),
                .linkedFramework("Security"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("AppKit")
            ]
        ),
        .executableTarget(
            name: "ExtBridgeMac",
            dependencies: ["ExtBridgeCore"],
            path: "Sources/ExtBridgeMac"
        ),
        .testTarget(
            name: "ExtBridgeCoreTests",
            dependencies: ["ExtBridgeCore"],
            path: "Tests/ExtBridgeCoreTests"
        )
    ],
    swiftLanguageModes: [.v6]
)
