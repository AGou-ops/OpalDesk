import AppKit
import CoreGraphics
import Foundation
import Testing
@testable import ExtBridgeCore

@Suite("ExtCast v1.1 Protocol Extensions, Popout Geometry, Clipboard PNG & Wireless ADB")
struct ExtCastV11AndPopoutTests {

    @Test("HELLO v1.1 new fields roundtrip and stay backward compatible with v1.0 JSON")
    func helloV11RoundtripAndBackwardsCompat() throws {
        let hello = HelloPayload(
            protocolVersion: 1,
            clientName: "MacBook Pro",
            clientId: "cid-v41",
            preferredCodec: "hevc",
            width: 848,
            height: 1680,
            dpi: 320,
            fps: 120,
            bitrateKbps: 12000,
            displayMode: 0,
            audioEnabled: false,
            authToken: "tok_v41",
            pinCode: "",
            transportHint: ExtCastTransportHint.wifiTls.rawValue,
            sessionRole: ExtCastSessionRole.standaloneApp.rawValue,
            appPackage: "com.tencent.mm",
            appActivity: ".ui.LauncherUI"
        )
        let encoded = try hello.encode()
        let decoded = try HelloPayload.decode(from: encoded)
        #expect(decoded == hello)
        #expect(decoded.transportHint == "wifi_tls")
        #expect(decoded.sessionRole == "standalone_app")
        #expect(decoded.appPackage == "com.tencent.mm")
        #expect(decoded.appActivity == ".ui.LauncherUI")

        // v1.0 legacy JSON without v1.1 keys decodes with safe defaults
        let legacyJSON = Data("""
        {"protocolVersion":1,"clientName":"OldMac","clientId":"old-1","preferredCodec":"h264","width":1920,"height":1080,"dpi":240,"fps":60,"bitrateKbps":16000,"displayMode":0,"audioEnabled":true,"authToken":"","pinCode":""}
        """.utf8)
        let legacyDecoded = try HelloPayload.decode(from: legacyJSON)
        #expect(legacyDecoded.transportHint == "usb")
        #expect(legacyDecoded.sessionRole == "desktop")
        #expect(legacyDecoded.appPackage == "")
        #expect(legacyDecoded.appActivity == "")
    }

    @Test("DEVICE_STATE v1.1 new fields roundtrip and default cleanly on v1.0 JSON")
    func deviceStateV11Roundtrip() throws {
        let state = DeviceStatePayload(
            deviceName: "Xiaomi 15 Ultra",
            sinkType: ExtCastSinkType.remoteWiFiADB.rawValue,
            displayMode: 0,
            width: 2560,
            height: 1440,
            dpi: 240,
            fps: 120.0,
            rotation: 0,
            bitrateKbps: 20000,
            batteryPct: 92,
            blackout: true,
            audioEnabled: true,
            injectStatus: ExtCastInjectStatus.uhid.rawValue,
            adaptiveSingleEnabled: true,
            defaultSingleWidthDp: 460,
            activeLayoutMode: "ADAPTIVE_SINGLE",
            topAppPackage: "com.xingin.xhs",
            topAppLabel: "小红书"
        )
        let decoded = try DeviceStatePayload.decode(from: try state.encode())
        #expect(decoded == state)
        #expect(decoded.sinkType == 4)
        #expect(decoded.injectStatus == 2)
        #expect(decoded.adaptiveSingleEnabled == true)
        #expect(decoded.defaultSingleWidthDp == 460)
        #expect(decoded.activeLayoutMode == "ADAPTIVE_SINGLE")
        #expect(decoded.topAppPackage == "com.xingin.xhs")
        #expect(decoded.topAppLabel == "小红书")

        let minimalJSON = Data(#"{"deviceName":"LegacyPhone"}"#.utf8)
        let defaulted = try DeviceStatePayload.decode(from: minimalJSON)
        #expect(defaulted.adaptiveSingleEnabled == true)
        #expect(defaulted.defaultSingleWidthDp == 460)
        #expect(defaulted.injectStatus == 0)
        #expect(defaulted.activeLayoutMode == "")
        #expect(defaulted.topAppPackage == "")
        #expect(defaulted.topAppLabel == "")
    }

    @Test("0x14 WIFI_UPGRADE_READY, 0x15 APP_LIST, 0x29 REQUEST_WIFI_UPGRADE, 0x2A LIST_APPS frame roundtrip")
    func newControlFramesWireRoundtrip() throws {
        let fp = String(repeating: "ab", count: 32)
        let tok = String(repeating: "cd", count: 32)
        let upgrade = WifiUpgradeReadyPayload(
            ok: true,
            host: "192.168.1.42",
            port: 19876,
            certFingerprint: fp,
            authToken: tok,
            message: ""
        )
        let upgradeFrame = try ExtCastFrame.wifiUpgradeReady(upgrade)
        #expect(upgradeFrame.type == .wifiUpgradeReady)
        #expect(upgradeFrame.type.rawValue == 0x14)

        let apps = AppListPayload(apps: [
            RemoteAppInfo(pkg: "com.tencent.mm", cls: ".ui.LauncherUI", label: "微信", runningOnExt: true, taskId: 123),
            RemoteAppInfo(pkg: "com.xunmeng.pinduoduo", cls: "", label: "拼多多", runningOnExt: false, taskId: -1)
        ])
        let appsFrame = try ExtCastFrame.appList(apps)
        #expect(appsFrame.type == .appList)
        #expect(appsFrame.type.rawValue == 0x15)

        let reqUpgradeFrame = ExtCastFrame.requestWifiUpgrade()
        #expect(reqUpgradeFrame.type == .requestWifiUpgrade)
        #expect(reqUpgradeFrame.type.rawValue == 0x29)
        #expect(reqUpgradeFrame.payload.isEmpty)

        let listAppsFrame = ExtCastFrame.listApps()
        #expect(listAppsFrame.type == .listApps)
        #expect(listAppsFrame.type.rawValue == 0x2A)
        #expect(listAppsFrame.payload.isEmpty)

        var wire = Data()
        for f in [upgradeFrame, appsFrame, reqUpgradeFrame, listAppsFrame] {
            wire.append(try f.encode())
        }

        var decoder = ExtCastStreamDecoder()
        decoder.append(wire)
        let frames = try decoder.decodeAvailableFrames()
        #expect(frames.count == 4)

        let decodedUpgrade = try WifiUpgradeReadyPayload.decode(from: frames[0].payload)
        #expect(decodedUpgrade == upgrade)
        #expect(decodedUpgrade.validPort == 19876)

        let decodedApps = try AppListPayload.decode(from: frames[1].payload)
        #expect(decodedApps == apps)

        #expect(frames[2].type == .requestWifiUpgrade)
        #expect(frames[2].payload.isEmpty)
        #expect(frames[3].type == .listApps)
        #expect(frames[3].payload.isEmpty)
    }

    @Test("0x2B WINDOW_ACTION covers all v1.1 actions with exact JSON schema")
    func windowActionPayloads() throws {
        let actions: [WindowActionPayload] = [
            .setAdaptiveSingle(enabled: true, defaultWidthDp: 460),
            .setSingleWidth(pkg: "com.tencent.mm", widthPct: 42, remember: true),
            .launchApp(pkg: "com.tencent.mm", cls: ".ui.LauncherUI", mode: .adaptive, slot: 0),
            .updateWindow(taskId: 123, pkg: "com.tencent.mm", mode: .freeform, left: 100, top: 80, right: 700, bottom: 1000, remember: true),
            .focusWindow(taskId: 123),
            .closeWindow(taskId: 123),
            .minimizeWindow(taskId: 123),
            .setTiling(topology: .split3Master, pkgs: ["com.a", "com.b", "com.c"], ratios: [55, 45]),
            .reloadAppDpi(pkg: "com.tencent.mm"),
            .returnToDesktop(pkg: "com.tencent.mm", taskId: 123),
            .setInputBackend(.uhid)
        ]

        for action in actions {
            let frame = try ExtCastFrame.windowAction(action)
            #expect(frame.type == .windowAction)
            #expect(frame.type.rawValue == 0x2B)

            var decoder = ExtCastStreamDecoder()
            decoder.append(try frame.encode())
            let decodedFrame = try #require(try decoder.decodeNextFrame())
            let decodedAction = try WindowActionPayload.decode(from: decodedFrame.payload)
            #expect(decodedAction == action)
        }

        // Verify exact JSON keys on set_adaptive_single and set_input_backend
        let adaptiveJSON = try JSONSerialization.jsonObject(
            with: try WindowActionPayload.setAdaptiveSingle(enabled: true, defaultWidthDp: 460).encode()
        ) as? [String: Any]
        #expect(adaptiveJSON?["action"] as? String == "set_adaptive_single")
        #expect(adaptiveJSON?["enabled"] as? Bool == true)
        #expect(adaptiveJSON?["defaultWidthDp"] as? Int == 460)
        #expect(adaptiveJSON?["pkg"] == nil)

        let backendJSON = try JSONSerialization.jsonObject(
            with: try WindowActionPayload.setInputBackend(.uhid).encode()
        ) as? [String: Any]
        #expect(backendJSON?["action"] as? String == "set_input_backend")
        #expect(backendJSON?["backend"] as? String == "uhid")
    }

    @Test("PNG clipboard frames (flags=0x01) exact wire layout and 8 MiB limit enforcement")
    func clipboardPngImageWireLayoutAndLimit() throws {
        let fakePNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])

        // Downlink 0x11 CLIPBOARD_PUSH (flags = 0x01): int64 seq + raw PNG bytes
        let push = ClipboardImagePushPayload(seq: 0x0102_0304_0506_0708, pngData: fakePNG)
        let pushFrame = try ExtCastFrame.clipboardImagePush(push)
        #expect(pushFrame.type == .clipboardPush)
        #expect(pushFrame.flags == ExtCastProtocol.flagClipboardImagePng)
        #expect(pushFrame.flags == 0x01)
        #expect(pushFrame.payload.count == 8 + fakePNG.count)
        #expect(Array(pushFrame.payload.prefix(8)) == [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
        #expect(Data(pushFrame.payload.dropFirst(8)) == fakePNG)

        let decodedPush = try ClipboardImagePushPayload.decode(from: pushFrame.payload)
        #expect(decodedPush == push)

        // Uplink 0x26 INPUT_TEXT (flags = 0x01): uint8 mode + int64 seq + raw PNG bytes
        let inputImg = InputImagePayload(mode: .setClipboardImageOnly, seq: 99, pngData: fakePNG)
        let inputFrame = try ExtCastFrame.inputImage(inputImg)
        #expect(inputFrame.type == .inputText)
        #expect(inputFrame.flags == 0x01)
        #expect(inputFrame.payload.count == 9 + fakePNG.count)
        #expect(inputFrame.payload.first == 1)
        let decodedInput = try InputImagePayload.decode(from: inputFrame.payload)
        #expect(decodedInput == inputImg)

        // Ensure text frame remains flags == 0
        let textFrame = ExtCastFrame.inputText(InputTextPayload(mode: .commitTextPaste, seq: 1, text: "hello"))
        #expect(textFrame.flags == 0x00)

        // Reject PNG larger than 8 MiB
        let oversized = Data(repeating: 0x42, count: ExtCastProtocol.maxClipboardImageBytes + 1)
        #expect(throws: ExtCastCodecError.payloadTooLarge(UInt32(ExtCastProtocol.maxClipboardImageBytes + 1))) {
            _ = try InputImagePayload(mode: .setClipboardImageOnly, seq: 2, pngData: oversized).encode()
        }
        #expect(throws: ExtCastCodecError.payloadTooLarge(UInt32(ExtCastProtocol.maxClipboardImageBytes + 1))) {
            _ = try ClipboardImagePushPayload(seq: 2, pngData: oversized).encode()
        }
    }

    @Test("Popout stream geometry computes 16px-aligned resolution and adaptive phone/foldable/tablet DPI")
    func popoutAdaptiveDPIGeometry() {
        // Golden phone size (420x840 pt @ 2x Retina)
        let phone = CoordinateMapper.popoutStreamGeometry(
            viewSize: PopoutAspectPreset.phone.windowSize,
            backingScale: 2.0
        )
        #expect(phone.width % 16 == 0)
        #expect(phone.height % 16 == 0)
        #expect(phone.width == 848)
        #expect(phone.height == 1680)
        #expect(phone.dpi == 320)
        #expect(phone.smallestWidthDp >= 400 && phone.smallestWidthDp <= 440)

        // Foldable size (640x820 pt @ 2x Retina)
        let foldable = CoordinateMapper.popoutStreamGeometry(
            viewSize: PopoutAspectPreset.foldable.windowSize,
            backingScale: 2.0
        )
        #expect(foldable.width % 16 == 0)
        #expect(foldable.height % 16 == 0)
        #expect(foldable.smallestWidthDp >= 600 && foldable.smallestWidthDp <= 680)

        // Tablet landscape size (920x640 pt @ 2x Retina)
        let tablet = CoordinateMapper.popoutStreamGeometry(
            viewSize: PopoutAspectPreset.tablet.windowSize,
            backingScale: 2.0
        )
        #expect(tablet.width % 16 == 0)
        #expect(tablet.height % 16 == 0)
        #expect(tablet.width > tablet.height)
        #expect(tablet.smallestWidthDp >= 600)

        // Non-Retina 1x display still upscales to crisp >= 1.5x resolution
        let nonRetina = CoordinateMapper.popoutStreamGeometry(
            viewSize: CGSize(width: 420, height: 840),
            backingScale: 1.0
        )
        #expect(nonRetina.width >= 600)
        #expect(nonRetina.height >= 1200)
        #expect(nonRetina.dpi >= CoordinateMapper.popoutMinDPI && nonRetina.dpi <= CoordinateMapper.popoutMaxDPI)
    }

    @Test("AdbDevice.isWirelessAdb accurately distinguishes USB serials vs wireless ADB endpoints")
    func adbWirelessDetection() {
        let sampleOutput = """
        List of devices attached
        24129PN74C             device usb:1-1 product:xuanyuan model:24129PN74C device:xuanyuan transport_id:1
        192.168.1.42:5555      device product:xuanyuan model:Xiaomi_15_Pro device:xuanyuan transport_id:2
        adb-24129PN74C-x9Y8z7._adb-tls-connect._tcp device product:xuanyuan model:Xiaomi_15_Pro device:xuanyuan transport_id:3
        emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:4
        """
        let parsed = AdbManager.parseDevicesOutput(sampleOutput)
        #expect(parsed.count == 4)

        #expect(parsed[0].isWirelessAdb == false)
        #expect(parsed[1].isWirelessAdb == true)
        #expect(parsed[2].isWirelessAdb == true)
        #expect(parsed[3].isWirelessAdb == false)

        let mdnsTcp = AdbDevice(serial: "phone._adb._tcp", state: .device)
        #expect(mdnsTcp.isWirelessAdb == true)
    }

    @Test("ClipboardSyncManager converts TIFF to valid PNG within size limit")
    func clipboardTIFFToPNGConversion() throws {
        guard let rep = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: 64,
            pixelsHigh: 64,
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            Issue.record("Failed to create test bitmap")
            return
        }
        guard let tiffData = rep.tiffRepresentation else {
            Issue.record("Failed to encode TIFF")
            return
        }

        let converted = try #require(ClipboardSyncManager.normalizePNGData(tiffData, isTIFF: true))
        #expect(ClipboardSyncManager.hasPNGSignature(converted))
        #expect(converted.count <= ExtCastProtocol.maxClipboardImageBytes)

        // Direct PNG passthrough preserves exact bytes when under limit
        let passthrough = try #require(ClipboardSyncManager.normalizePNGData(converted, isTIFF: false))
        #expect(passthrough == converted)
    }
}
