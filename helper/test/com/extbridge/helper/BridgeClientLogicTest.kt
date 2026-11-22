package com.extbridge.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeClientLogicTest {

    @Test
    fun statusDefaultsAndRemoteSinkHelpers() {
        val st = BridgeClient.Status()
        assertFalse(st.connected)
        assertEquals(BridgeClient.SINK_NONE, st.sinkType)
        assertFalse(st.isRemoteSink)
        assertFalse(st.isRemoteDisplayActive)
        assertFalse(st.isRemoteStreaming)
        assertFalse(st.isWifiServerEnabled)
        assertEquals(0f, st.streamFps, 0.001f)
        assertEquals("", st.shortCertFingerprint)
        assertEquals(1, st.adaptiveSingleEnabled)
        assertTrue(st.isAdaptiveSingleEnabled)
        assertEquals(460, st.defaultSingleWidthDp)
        assertEquals(BridgeClient.LAYOUT_FULLSCREEN, st.activeLayoutMode)
        assertFalse(st.isUhidInputActive)
        assertFalse(st.isMultiTilingActive)

        val stPhys = st.copy(connected = true, sinkType = BridgeClient.SINK_PHYSICAL)
        assertFalse(stPhys.isRemoteSink)
        assertFalse(stPhys.isRemoteDisplayActive)
        assertFalse(stPhys.isRemoteStreaming)

        // Disconnected status with stale remote sinkType must NOT report active remote streaming.
        val stUsbDisconnected = st.copy(connected = false, sinkType = BridgeClient.SINK_REMOTE_USB)
        assertTrue(stUsbDisconnected.isRemoteDisplayActive)
        assertFalse(stUsbDisconnected.isRemoteStreaming)

        val stUsb = st.copy(connected = true, sinkType = BridgeClient.SINK_REMOTE_USB)
        assertTrue(stUsb.isRemoteSink)
        assertTrue(stUsb.isRemoteDisplayActive)
        assertTrue(stUsb.isRemoteStreaming)

        val stWifiAdb = st.copy(connected = true, sinkType = BridgeClient.SINK_REMOTE_WIFI_ADB)
        assertEquals(4, BridgeClient.SINK_REMOTE_WIFI_ADB)
        assertTrue(stWifiAdb.isRemoteSink)
        assertTrue(stWifiAdb.isRemoteDisplayActive)
        assertTrue(stWifiAdb.isRemoteStreaming)
        assertTrue(BridgeClient.isRemoteDisplayActive(BridgeClient.SINK_REMOTE_WIFI_ADB))

        val stWifi = st.copy(
            connected = true,
            sinkType = BridgeClient.SINK_REMOTE_WIFI,
            wifiServerEnabled = 1,
            wifiServerPort = BridgeClient.DEFAULT_WIFI_REMOTE_PORT,
            streamFpsX100 = 5994,
            streamBitrateKbps = 16500,
            wifiCertFingerprint = "a1b2c3d4e5f6071899aabbccddeeff00112233445566778899aabbccddeeff00",
            activePairingPin = "482910",
            injectStatus = BridgeClient.INJECT_STATUS_UHID,
            activeLayoutMode = BridgeClient.LAYOUT_GRID_2X2,
        )

        assertTrue(stWifi.isRemoteSink)
        assertTrue(stWifi.isRemoteDisplayActive)
        assertTrue(stWifi.isRemoteStreaming)
        assertTrue(stWifi.isWifiServerEnabled)
        assertTrue(stWifi.isUhidInputActive)
        assertTrue(stWifi.isMultiTilingActive)
        assertEquals(59.94f, stWifi.streamFps, 0.001f)
        assertEquals("A1B2-C3D4-E5F6-0718", stWifi.shortCertFingerprint)
    }

    @Test
    fun parseTrustedMacClientsHandlesNormalMalformedAndDuplicates() {
        assertTrue(BridgeClient.parseTrustedMacClients(null).isEmpty())
        assertTrue(BridgeClient.parseTrustedMacClients("").isEmpty())
        assertTrue(BridgeClient.parseTrustedMacClients("  ; ; =noname ").isEmpty())

        val parsed = BridgeClient.parseTrustedMacClients(
            "mac-01=Leo's MacBook Pro; mac-02=Studio Display Mac; mac-01=DuplicateIgnored; mac-03=; invalid_entry",
        )
        assertEquals(3, parsed.size)
        assertEquals(BridgeClient.TrustedMacClient("mac-01", "Leo's MacBook Pro"), parsed[0])
        assertEquals(BridgeClient.TrustedMacClient("mac-02", "Studio Display Mac"), parsed[1])
        assertEquals(BridgeClient.TrustedMacClient("mac-03", "mac-03"), parsed[2])
    }

    @Test
    fun parseAppModesFiltersInvalidModes() {
        val modes = BridgeClient.parseAppModes(
            "com.tencent.mm=0;com.example.drama=1;com.example.shop=2;com.bad=9;broken;=1;com.free=3",
        )
        assertEquals(4, modes.size)
        assertEquals(0, modes["com.tencent.mm"])
        assertEquals(1, modes["com.example.drama"])
        assertEquals(2, modes["com.example.shop"])
        assertEquals(3, modes["com.free"])
    }

    @Test
    fun parseEdidModesParsesValidEntriesOnly() {
        val raw = "1:2560x1440@60.0;2:1920x1080@120.0;bad_entry;3:0x1080@60.0;4:3840x2160@59.94"
        val modes = BridgeClient.parseEdidModes(raw)
        assertEquals(3, modes.size)
        assertEquals(BridgeClient.EdidMode(1, 2560, 1440, 60.0f), modes[0])
        assertEquals(BridgeClient.EdidMode(2, 1920, 1080, 120.0f), modes[1])
        assertEquals(BridgeClient.EdidMode(4, 3840, 2160, 59.94f), modes[2])
    }

    @Test
    fun formattingHelpersProduceExpectedDisplayStrings() {
        assertEquals("A1B2-C3D4-E5F6-0718", BridgeClient.formatShortFingerprint("a1:b2:c3:d4:e5:f6:07:18:ff:ee"))
        assertEquals("", BridgeClient.formatShortFingerprint("  "))
        assertEquals("16.5 Mbps", BridgeClient.formatBitrateMbps(16500))
        assertEquals("--", BridgeClient.formatBitrateMbps(0))
        assertEquals("60 fps", BridgeClient.formatStreamFps(6000))
        assertEquals("59.9 fps", BridgeClient.formatStreamFps(5994))
        assertEquals("--", BridgeClient.formatStreamFps(-1))
        assertEquals("Mac 有线大屏 (USB)", BridgeClient.sinkTypeLabel(BridgeClient.SINK_REMOTE_USB))
        assertEquals("Mac 无线 ADB 大屏", BridgeClient.sinkTypeLabel(BridgeClient.SINK_REMOTE_WIFI_ADB))
        assertEquals("Mac 无线 Wi-Fi 大屏", BridgeClient.sinkTypeLabel(BridgeClient.SINK_REMOTE_WIFI))
        assertEquals("物理外接显示器", BridgeClient.sinkTypeLabel(BridgeClient.SINK_PHYSICAL))
        assertEquals("本机 DeX 桌面", BridgeClient.sinkTypeLabel(BridgeClient.SINK_LOCAL))
        assertEquals("未连接输出", BridgeClient.sinkTypeLabel(BridgeClient.SINK_NONE))
    }

    @Test
    fun localDexStatusSummaryAndDpiHelpers() {
        val stLocal = BridgeClient.Status(
            connected = true,
            vdId = 7,
            virtW = 2400,
            virtH = 1080,
            virtDpi = 240,
            virtFps = 120f,
            sinkType = BridgeClient.SINK_LOCAL,
            renderMode = "LOCAL_DEX",
        )
        assertTrue(stLocal.isLocalDexActive)
        assertFalse(stLocal.isRemoteSink)
        assertTrue(BridgeClient.isLocalDexSink(BridgeClient.SINK_LOCAL))

        val summary = com.extbridge.helper.protocol.BridgeLabels.classifyConnectionSummary(stLocal)
        assertEquals(com.extbridge.helper.protocol.ConnectionKind.LOCAL_DEX, summary.kind)
        assertEquals("本机 DeX", summary.badgeLabel)
        assertTrue(summary.headline.contains("Display #7"))

        // Phone 2400x1080 (sw=411dp) -> 240dpi (sw=720dp)
        assertEquals(240, BridgeClient.computeLocalDexDpi(2400, 1080, 411))
        // Phone 2712x1220 (sw=411dp) -> 268dpi (sw=728dp >= 720dp)
        assertEquals(268, BridgeClient.computeLocalDexDpi(2712, 1220, 411))
        // Tablet 2560x1600 (sw=800dp) -> 304dpi (sw=842dp)
        assertEquals(304, BridgeClient.computeLocalDexDpi(2560, 1600, 800))
    }

    @Test
    fun displayDpiAndResolutionMathHelpers() {
        // 2560x1440 requesting 240dpi -> minEdge=1440, maxDpiForEdge=320 -> 240
        assertEquals(240, BridgeClient.computeOverlayDpi(2560, 1440, 240))
        // 1280x720 requesting 240dpi -> minEdge=720, maxDpiForEdge=160 -> capped at 160
        assertEquals(160, BridgeClient.computeOverlayDpi(1280, 720, 240))
        // Smallest width dp calculation
        assertEquals(960, BridgeClient.computeSmallestWidthDp(2560, 1440, 240))
        assertEquals(720, BridgeClient.computeSmallestWidthDp(1920, 1080, 240))

        // Recommended DPI tiers
        assertEquals(320, BridgeClient.recommendDpiForResolution(3840, 2160))
        assertEquals(240, BridgeClient.recommendDpiForResolution(2560, 1440))
        assertEquals(200, BridgeClient.recommendDpiForResolution(1920, 1080))
        assertEquals(160, BridgeClient.recommendDpiForResolution(1280, 720))

        // 16-pixel alignment for hardware encoders
        assertEquals(1920, BridgeClient.alignResolution16(1920))
        assertEquals(1088, BridgeClient.alignResolution16(1082))
        assertEquals(640, BridgeClient.alignResolution16(100))
        assertEquals(7680, BridgeClient.alignResolution16(9999))
    }

    @Test
    fun rotationAppModeAndMirrorThemeHelpers() {
        assertEquals("0° 横屏", BridgeClient.rotationLabel(0))
        assertEquals("90° 竖屏", BridgeClient.rotationLabel(1))
        assertEquals("180° 倒置", BridgeClient.rotationLabel(2))
        assertEquals("270° 竖屏", BridgeClient.rotationLabel(3))
        assertEquals("270° 竖屏", BridgeClient.rotationLabel(-1))

        assertEquals("强制横屏全屏", BridgeClient.appModeLabel(BridgeClient.APP_MODE_LAND_FULL))
        assertEquals("竖屏兼容", BridgeClient.appModeBadge(BridgeClient.APP_MODE_PORT_COMPAT))
        assertEquals("原生镜像", BridgeClient.mirrorModeLabel(BridgeClient.MIRROR_MODE_MIRROR))
        assertEquals("DeX 扩展桌面", BridgeClient.mirrorModeLabel(BridgeClient.MIRROR_MODE_DEX))

        val darkCustom = BridgeClient.resolveMirrorTheme("custom", 0x101828)
        assertFalse(darkCustom.lightMode)
        assertEquals("自定义深色", darkCustom.name)

        val lightCustom = BridgeClient.resolveMirrorTheme("custom", 0xF5F7FA)
        assertTrue(lightCustom.lightMode)
        assertEquals("自定义浅色", lightCustom.name)

        assertEquals(0, BridgeClient.mirrorPresetIndex("clock"))
        assertEquals("大数字时钟", BridgeClient.mirrorPresetTitle("clock"))
        assertEquals(-1, BridgeClient.mirrorPresetIndex("nonexistent"))
    }

    @Test
    fun parseWindowStateSnapshotHandlesFullMultiWindowAndMalformedJson() {
        val defaultEmpty = BridgeClient.parseWindowStateSnapshot(null)
        assertEquals(BridgeClient.LAYOUT_FULLSCREEN, defaultEmpty.layoutMode)
        assertTrue(defaultEmpty.adaptiveSingleEnabled)
        assertEquals(460, defaultEmpty.defaultSingleWidthDp)
        assertEquals(BridgeClient.LAYOUT_NONE, defaultEmpty.tilingTopology)
        assertTrue(defaultEmpty.windows.isEmpty())

        val malformed = BridgeClient.parseWindowStateSnapshot("{broken json")
        assertEquals(BridgeClient.LAYOUT_FULLSCREEN, malformed.layoutMode)
        assertTrue(malformed.windows.isEmpty())

        val json = """
            {
              "layoutMode": "FREEFORM",
              "adaptiveSingleEnabled": true,
              "defaultSingleWidthDp": 680,
              "tilingTopology": "NONE",
              "ratios": [50, 50],
              "windows": [
                {
                  "taskId": 12,
                  "pkg": "com.tencent.mm",
                  "mode": "ADAPTIVE_SINGLE",
                  "left": 500,
                  "top": 20,
                  "right": 1420,
                  "bottom": 1000,
                  "focused": true,
                  "minimized": false
                },
                {
                  "taskId": 19,
                  "pkg": "com.xingin.xhs",
                  "mode": "FREEFORM",
                  "left": 120,
                  "top": 80,
                  "right": 840,
                  "bottom": 960,
                  "focused": false,
                  "minimized": true
                }
              ]
            }
        """.trimIndent()
        val snap = BridgeClient.parseWindowStateSnapshot(json)
        assertEquals("FREEFORM", snap.layoutMode)
        assertTrue(snap.adaptiveSingleEnabled)
        assertEquals(680, snap.defaultSingleWidthDp)
        assertEquals("NONE", snap.tilingTopology)
        assertEquals(listOf(50, 50), snap.ratios)
        assertEquals(2, snap.windows.size)

        val w0 = snap.windows[0]
        assertEquals(12, w0.taskId)
        assertEquals("com.tencent.mm", w0.pkg)
        assertEquals("ADAPTIVE_SINGLE", w0.mode)
        assertEquals(500, w0.left)
        assertEquals(20, w0.top)
        assertEquals(1420, w0.right)
        assertEquals(1000, w0.bottom)
        assertEquals(920, w0.widthPx)
        assertEquals(980, w0.heightPx)
        assertTrue(w0.focused)
        assertFalse(w0.minimized)

        val w1 = snap.windows[1]
        assertEquals(19, w1.taskId)
        assertEquals("com.xingin.xhs", w1.pkg)
        assertEquals("FREEFORM", w1.mode)
        assertFalse(w1.focused)
        assertTrue(w1.minimized)
    }

    @Test
    fun adaptiveWidthRatioAndTopologyMathHelpers() {
        assertEquals(460, BridgeClient.pxToDp(690, 240))
        assertEquals(690, BridgeClient.dpToPx(460, 240))

        assertEquals("460dp · 手机竖屏", BridgeClient.classifyWidthPresetLabel(460, false))
        assertEquals("680dp · 折叠屏", BridgeClient.classifyWidthPresetLabel(680, false))
        assertEquals("920dp · 平板双栏", BridgeClient.classifyWidthPresetLabel(920, false))
        assertEquals("全屏", BridgeClient.classifyWidthPresetLabel(1280, true))

        // Magnetic snapping near 460 / 680 / 900 and fullscreen >= 94%
        assertEquals(460 to false, BridgeClient.snapWidthDp(468, 1280))
        assertEquals(680 to false, BridgeClient.snapWidthDp(672, 1280))
        assertEquals(900 to false, BridgeClient.snapWidthDp(910, 1280))
        assertEquals(1280 to true, BridgeClient.snapWidthDp(1220, 1280))
        assertEquals(550 to false, BridgeClient.snapWidthDp(550, 1280))

        // Centered single bounds at 2560x1440 @ 240dpi with 460dp -> 690px wide -> [935, 0, 1625, 1440]
        val centered = BridgeClient.computeCenteredSingleBoundsPx(2560, 1440, 240, 460)
        assertEquals(935, centered[0])
        assertEquals(0, centered[1])
        assertEquals(1625, centered[2])
        assertEquals(1440, centered[3])
        assertEquals(690, centered[2] - centered[0])

        // Topology ratio normalization
        assertEquals(listOf(33, 34, 33), BridgeClient.normalizeTilingRatios(BridgeClient.LAYOUT_SPLIT_3_COLS, emptyList()))
        assertEquals(listOf(30, 40, 30), BridgeClient.normalizeTilingRatios(BridgeClient.LAYOUT_SPLIT_3_COLS, listOf(30, 40, 30)))
        assertEquals(listOf(60, 45), BridgeClient.normalizeTilingRatios(BridgeClient.LAYOUT_SPLIT_3_MASTER, listOf(60, 45)))
        assertEquals(listOf(50, 50), BridgeClient.normalizeTilingRatios(BridgeClient.LAYOUT_GRID_2X2, emptyList()))
        assertEquals(listOf(40, 60), BridgeClient.normalizeTilingRatios(BridgeClient.LAYOUT_SPLIT_2_H, listOf(40)))

        // Labels
        assertTrue(BridgeClient.isTilingTopology(BridgeClient.LAYOUT_SPLIT_3_MASTER))
        assertTrue(BridgeClient.isTilingTopology(BridgeClient.LAYOUT_SPLIT_3_COLS))
        assertTrue(BridgeClient.isTilingTopology(BridgeClient.LAYOUT_GRID_2X2))
        assertFalse(BridgeClient.isTilingTopology(BridgeClient.LAYOUT_ADAPTIVE_SINGLE))
        assertEquals("UHID 硬件外设注入已激活", BridgeClient.injectStatusLabel(BridgeClient.INJECT_STATUS_UHID))
        assertEquals("强制 UHID 虚拟键鼠", BridgeClient.inputBackendLabel(BridgeClient.INPUT_BACKEND_UHID))
        assertEquals("2×2 四宫格", BridgeClient.layoutModeLabel(BridgeClient.LAYOUT_GRID_2X2))
    }
}
