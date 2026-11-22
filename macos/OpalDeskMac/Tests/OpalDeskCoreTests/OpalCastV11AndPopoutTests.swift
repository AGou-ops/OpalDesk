import AppKit
import CoreGraphics
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("OpalCast v1.1 Protocol Extensions, Popout Geometry, Clipboard PNG & Wireless ADB")
struct OpalCastV11AndPopoutTests {

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
            transportHint: OpalCastTransportHint.wifiTls.rawValue,
            sessionRole: OpalCastSessionRole.standaloneApp.rawValue,
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

    @Test("DEVICE_STATE v1.1 new fields roundtrip and default cleanly aligned with Java on v1.0 JSON")
    func deviceStateV11Roundtrip() throws {
        let state = DeviceStatePayload(
            deviceName: "Xiaomi 15 Ultra",
            sinkType: 4,
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
            injectStatus: OpalCastInjectStatus.uhid.rawValue,
            adaptiveSingleEnabled: true,
            defaultSingleWidthDp: 460,
            activeLayoutMode: OpalCastLayoutMode.split2H.rawValue,
            topAppPackage: "com.xingin.xhs",
            topAppLabel: "小红书",
            tilingSlots: ["com.tencent.mm", "tv.danmaku.bili"],
            tilingRatios: [45, 55]
        )
        let decoded = try DeviceStatePayload.decode(from: try state.encode())
        #expect(decoded == state)
        #expect(decoded.sinkType == 4)
        #expect(decoded.injectStatus == 2)
        #expect(decoded.adaptiveSingleEnabled == true)
        #expect(decoded.defaultSingleWidthDp == 460)
        #expect(decoded.activeLayoutMode == "SPLIT_2_H")
        #expect(decoded.layoutMode == .split2H)
        #expect(decoded.topAppPackage == "com.xingin.xhs")
        #expect(decoded.topAppLabel == "小红书")
        #expect(decoded.tilingSlots == ["com.tencent.mm", "tv.danmaku.bili"])
        #expect(decoded.tilingRatios == [45, 55])

        let minimalJSON = Data(#"{"deviceName":"LegacyPhone"}"#.utf8)
        let defaulted = try DeviceStatePayload.decode(from: minimalJSON)
        let fresh = DeviceStatePayload(deviceName: "LegacyPhone")
        #expect(defaulted == fresh)
        #expect(defaulted.width == 1920)
        #expect(defaulted.height == 1080)
        #expect(defaulted.bitrateKbps == 12000)
        #expect(defaulted.audioEnabled == false)
        #expect(defaulted.adaptiveSingleEnabled == true)
        #expect(defaulted.defaultSingleWidthDp == DisplayPresets.defaultSingleWidthDp)
        #expect(defaulted.injectStatus == 0)
        #expect(defaulted.activeLayoutMode == OpalCastLayoutMode.fullscreen.rawValue)
        #expect(defaulted.layoutMode == .fullscreen)
        #expect(defaulted.topAppPackage == "")
        #expect(defaulted.topAppLabel == "")
        #expect(defaulted.tilingSlots.isEmpty)
        #expect(defaulted.tilingRatios.isEmpty)
    }

    @Test("0x14 WIFI_UPGRADE_READY, 0x15 APP_LIST, 0x29 REQUEST_WIFI_UPGRADE, 0x2A LIST_APPS frame roundtrip")
    func newControlFramesWireRoundtrip() throws {
        let fp = String(repeating: "ab", count: 32)
        let tok = String(repeating: "cd", count: 32)
        let upgrade = WifiUpgradeReadyPayload(
            ok: true,
            host: "192.168.1.42",
            port: 19878,
            certFingerprint: fp,
            authToken: tok,
            message: ""
        )
        let upgradeFrame = try OpalCastFrame.wifiUpgradeReady(upgrade)
        #expect(upgradeFrame.type == .wifiUpgradeReady)
        #expect(upgradeFrame.type.rawValue == 0x14)

        let sampleIconBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        let apps = AppListPayload(apps: [
            RemoteAppInfo(
                pkg: "com.tencent.mm",
                cls: ".ui.LauncherUI",
                label: "微信",
                runningOnExt: true,
                taskId: 123,
                iconBase64: sampleIconBase64
            ),
            RemoteAppInfo(pkg: "com.xunmeng.pinduoduo", cls: "", label: "拼多多", runningOnExt: false, taskId: -1)
        ])
        let appsFrame = try OpalCastFrame.appList(apps)
        #expect(appsFrame.type == .appList)
        #expect(appsFrame.type.rawValue == 0x15)

        let reqUpgradeFrame = OpalCastFrame.requestWifiUpgrade()
        #expect(reqUpgradeFrame.type == .requestWifiUpgrade)
        #expect(reqUpgradeFrame.type.rawValue == 0x29)
        #expect(reqUpgradeFrame.payload.isEmpty)

        let listAppsFrame = OpalCastFrame.listApps()
        #expect(listAppsFrame.type == .listApps)
        #expect(listAppsFrame.type.rawValue == 0x2A)
        #expect(listAppsFrame.payload.isEmpty)

        var wire = Data()
        for f in [upgradeFrame, appsFrame, reqUpgradeFrame, listAppsFrame] {
            wire.append(try f.encode())
        }

        var decoder = OpalCastStreamDecoder()
        decoder.append(wire)
        let frames = try decoder.decodeAvailableFrames()
        #expect(frames.count == 4)

        let decodedUpgrade = try WifiUpgradeReadyPayload.decode(from: frames[0].payload)
        #expect(decodedUpgrade == upgrade)
        #expect(decodedUpgrade.validPort == 19878)

        let decodedApps = try AppListPayload.decode(from: frames[1].payload)
        #expect(decodedApps == apps)
        #expect(decodedApps.apps[0].iconBase64 == sampleIconBase64)
        #expect(decodedApps.apps[1].iconBase64 == nil)

        // Verify raw wire JSON uses exact key "icon" and stays backward compatible when omitted
        let rawIconJSON = Data(#"{"apps":[{"pkg":"com.demo","label":"Demo","icon":"QUJD"},{"pkg":"com.legacy","label":"Legacy"}]}"#.utf8)
        let decodedRawApps = try AppListPayload.decode(from: rawIconJSON)
        #expect(decodedRawApps.apps.count == 2)
        #expect(decodedRawApps.apps[0].iconBase64 == "QUJD")
        #expect(decodedRawApps.apps[1].iconBase64 == nil)

        #expect(frames[2].type == .requestWifiUpgrade)
        #expect(frames[2].payload.isEmpty)
        #expect(frames[3].type == .listApps)
        #expect(frames[3].payload.isEmpty)
    }

    @Test("0x2B WINDOW_ACTION covers all active v1.1 typed actions with exact JSON schema and clamping")
    func windowActionPayloads() throws {
        let actions: [WindowActionPayload] = [
            .setAdaptiveSingle(enabled: true, defaultWidthDp: 460),
            .setSingleWidth(pkg: "com.tencent.mm", widthPct: 42, remember: true),
            .launchApp(pkg: "com.tencent.mm", cls: ".ui.LauncherUI", mode: .adaptive, slot: 0),
            .setTiling(topology: .split3Master, pkgs: ["com.a", "com.b", "com.c"], ratios: [55, 45]),
            .reloadAppDpi(pkg: "com.tencent.mm"),
            .returnToDesktop(pkg: "com.tencent.mm", taskId: 123),
            .setInputBackend(.uhid),
            .focusWindow(taskId: 101, pkg: "com.tencent.mm"),
            .minimizeWindow(taskId: 102, pkg: "com.tencent.mm"),
            .closeWindow(taskId: 103, pkg: "tv.danmaku.bili"),
            .closeWindow(taskId: 104)
        ]

        for action in actions {
            let frame = try OpalCastFrame.windowAction(action)
            #expect(frame.type == .windowAction)
            #expect(frame.type.rawValue == 0x2B)

            var decoder = OpalCastStreamDecoder()
            decoder.append(try frame.encode())
            let decodedFrame = try #require(try decoder.decodeNextFrame())
            let decodedAction = try WindowActionPayload.decode(from: decodedFrame.payload)
            #expect(decodedAction == action)
        }

        // Verify shared clamping constants
        #expect(WindowActionPayload.setAdaptiveSingle(enabled: true, defaultWidthDp: 100).defaultWidthDp == 320)
        #expect(WindowActionPayload.setAdaptiveSingle(enabled: true, defaultWidthDp: 9999).defaultWidthDp == 1200)
        #expect(WindowActionPayload.setSingleWidth(pkg: "a", widthPct: 5).widthPct == 28)
        #expect(WindowActionPayload.setSingleWidth(pkg: "a", widthPct: 200).widthPct == 100)

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

        let focusJSON = try JSONSerialization.jsonObject(
            with: try WindowActionPayload.focusWindow(taskId: 42, pkg: "com.tencent.mm").encode()
        ) as? [String: Any]
        #expect(focusJSON?["action"] as? String == "focus_window")
        #expect(focusJSON?["taskId"] as? Int == 42)
        #expect(focusJSON?["pkg"] as? String == "com.tencent.mm")

        let minimizeJSON = try JSONSerialization.jsonObject(
            with: try WindowActionPayload.minimizeWindow(taskId: 43).encode()
        ) as? [String: Any]
        #expect(minimizeJSON?["action"] as? String == "minimize_window")
        #expect(minimizeJSON?["taskId"] as? Int == 43)
        #expect(minimizeJSON?["pkg"] == nil)

        let closeJSON = try JSONSerialization.jsonObject(
            with: try WindowActionPayload.closeWindow(taskId: 44, pkg: "tv.danmaku.bili").encode()
        ) as? [String: Any]
        #expect(closeJSON?["action"] as? String == "close_window")
        #expect(closeJSON?["taskId"] as? Int == 44)
        #expect(closeJSON?["pkg"] as? String == "tv.danmaku.bili")
    }

    @Test("PNG clipboard frames (flags=0x01) exact wire layout and 8 MiB limit enforcement")
    func clipboardPngImageWireLayoutAndLimit() throws {
        let fakePNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])

        // Downlink 0x11 CLIPBOARD_PUSH (flags = 0x01): int64 seq + raw PNG bytes
        let push = ClipboardImagePushPayload(seq: 0x0102_0304_0506_0708, pngData: fakePNG)
        let pushFrame = try OpalCastFrame.clipboardImagePush(push)
        #expect(pushFrame.type == .clipboardPush)
        #expect(pushFrame.flags == OpalCastProtocol.flagClipboardImagePng)
        #expect(pushFrame.flags == 0x01)
        #expect(pushFrame.payload.count == 8 + fakePNG.count)
        #expect(Array(pushFrame.payload.prefix(8)) == [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])
        #expect(Data(pushFrame.payload.dropFirst(8)) == fakePNG)

        let decodedPush = try ClipboardImagePushPayload.decode(from: pushFrame.payload)
        #expect(decodedPush == push)

        // Uplink 0x26 INPUT_TEXT (flags = 0x01): uint8 mode + int64 seq + raw PNG bytes
        let inputImg = InputImagePayload(mode: .setClipboardImageOnly, seq: 99, pngData: fakePNG)
        let inputFrame = try OpalCastFrame.inputImage(inputImg)
        #expect(inputFrame.type == .inputText)
        #expect(inputFrame.flags == 0x01)
        #expect(inputFrame.payload.count == 9 + fakePNG.count)
        #expect(inputFrame.payload.first == 1)
        let decodedInput = try InputImagePayload.decode(from: inputFrame.payload)
        #expect(decodedInput == inputImg)

        // Ensure text frame remains flags == 0
        let textFrame = OpalCastFrame.inputText(InputTextPayload(mode: .commitTextPaste, seq: 1, text: "hello"))
        #expect(textFrame.flags == 0x00)

        // Reject PNG larger than 8 MiB
        let oversized = Data(repeating: 0x42, count: OpalCastProtocol.maxClipboardImageBytes + 1)
        #expect(throws: OpalCastCodecError.payloadTooLarge(UInt32(OpalCastProtocol.maxClipboardImageBytes + 1))) {
            _ = try InputImagePayload(mode: .setClipboardImageOnly, seq: 2, pngData: oversized).encode()
        }
        #expect(throws: OpalCastCodecError.payloadTooLarge(UInt32(OpalCastProtocol.maxClipboardImageBytes + 1))) {
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
        #expect(converted.count <= OpalCastProtocol.maxClipboardImageBytes)

        // Direct PNG passthrough preserves exact bytes when under limit
        let passthrough = try #require(ClipboardSyncManager.normalizePNGData(converted, isTIFF: false))
        #expect(passthrough == converted)
    }

    @Test("AppFuzzyMatcher ranks exact, prefix, contains, package, subsequence, and running tiebreakers")
    func appFuzzyMatcherRanking() {
        let apps = [
            RemoteAppInfo(pkg: "com.tencent.mm", label: "WeChat", runningOnExt: false),
            RemoteAppInfo(pkg: "com.example.chat", label: "ChatBox", runningOnExt: true),
            RemoteAppInfo(pkg: "com.example.ch", label: "Ch", runningOnExt: false),
            RemoteAppInfo(pkg: "com.wechat.tools", label: "Toolkit", runningOnExt: false)
        ]

        // Blank query returns input unchanged
        #expect(AppFuzzyMatcher.filter(apps, query: "   ") == apps)

        // Exact > prefix > contains > package > subsequence
        let chResults = AppFuzzyMatcher.filter(apps, query: "ch")
        #expect(chResults.map(\.pkg) == [
            "com.example.ch",      // exact label ("ch")
            "com.example.chat",    // label prefix ("chatbox")
            "com.tencent.mm",      // label contains ("wechat")
            "com.wechat.tools"     // package contains ("com.wechat.tools")
        ])

        // Subsequence match on label ("wct" -> WeChat)
        let subseq = AppFuzzyMatcher.filter(apps, query: "wct")
        #expect(subseq.first?.pkg == "com.tencent.mm")

        // Non-matching query returns empty
        #expect(AppFuzzyMatcher.filter(apps, query: "zzzzz").isEmpty)
    }

    @Test("AdbManager externalVolumeAdbCandidates discovers SDK platform-tools on external volumes")
    func adbExternalVolumeDiscovery() throws {
        let fm = FileManager.default
        let fakeVolumesRoot = fm.temporaryDirectory
            .appendingPathComponent("opaldesk-volumes-\(UUID().uuidString)", isDirectory: true)
        let adbDir = fakeVolumesRoot
            .appendingPathComponent("lightlsle/dependencies/android-sdk/platform-tools", isDirectory: true)
        try fm.createDirectory(at: adbDir, withIntermediateDirectories: true)
        defer {
            do {
                try fm.removeItem(at: fakeVolumesRoot)
            } catch {
                Issue.record("Failed to clean up temp volumes fixture: \(error)")
            }
        }

        let fakeAdb = adbDir.appendingPathComponent("adb")
        try Data("#!/bin/sh\n".utf8).write(to: fakeAdb)
        try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fakeAdb.path)

        let candidates = AdbManager.externalVolumeAdbCandidates(volumesRoot: fakeVolumesRoot.path, fileManager: fm)
        #expect(candidates.contains(fakeAdb.path))
        #expect(candidates.first(where: { fm.isExecutableFile(atPath: $0) }) == fakeAdb.path)
    }
}
