package com.extbridge.helper

import com.extbridge.helper.ipc.DaemonStateStore
import com.extbridge.helper.overlay.OverlayPopover
import com.extbridge.helper.overlay.PopoverCoordinator
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeParsers
import com.extbridge.helper.protocol.BridgeProtocol
import com.extbridge.helper.protocol.ConnectionKind
import com.extbridge.helper.protocol.ConnectionTone
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.DisplayGeometry
import com.extbridge.helper.protocol.EdidMode
import com.extbridge.helper.protocol.IpcTrustVerifier
import com.extbridge.helper.protocol.SysStatus
import com.extbridge.helper.protocol.WindowStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationLogicTest {

    @Test
    fun ipcTrustVerifierAllowlistsRootSystemShellAndSelfOnly() {
        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(0))
        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(1000))
        assertTrue(IpcTrustVerifier.isTrustedDaemonUid(2000))
        assertFalse(IpcTrustVerifier.isTrustedDaemonUid(10234))

        assertTrue(IpcTrustVerifier.isTrustedCallerUid(uid = 10234, selfUid = 10234))
        assertFalse(IpcTrustVerifier.isTrustedCallerUid(uid = 10235, selfUid = 10234))
        assertTrue(IpcTrustVerifier.isTrustedCallerUid(uid = 2000, selfUid = 10234))
    }

    @Test
    fun strictMiniJsonParserRejectsMalformedPayloadsCleanly() {
        val malformedPayloads = listOf(
            "{broken json",
            """{"layoutMode":"FREEFORM" "adaptiveSingleEnabled":true}""",
            """{"layoutMode":"UNTERMINATED""",
            """{"layoutMode":"FREEFORM"} trailing""",
            """{"ratios":[50, 50}""",
            """{"layoutMode":tru}""",
            """{"windows":[{"pkg":"com.a","taskId":1,}]}""",
            """[1, 2, 3]""",
        )
        for (bad in malformedPayloads) {
            val snap = BridgeParsers.parseWindowStateSnapshot(bad)
            assertEquals("Expected default layoutMode for: $bad", BridgeProtocol.LayoutMode.FULLSCREEN, snap.layoutMode)
            assertTrue("Expected empty windows for: $bad", snap.windows.isEmpty())
            assertTrue("Expected empty ratios for: $bad", snap.ratios.isEmpty())
        }
    }

    @Test
    fun pickBestEdidModePrefersAreaAnd50PlusRefreshRate() {
        assertNull(DisplayGeometry.pickBestEdidMode(emptyList()))
        assertNull(DisplayGeometry.pickBestEdidMode(listOf(EdidMode(1, 320, 240, 60f))))

        val modes = listOf(
            EdidMode(1, 3840, 2160, 30.0f),
            EdidMode(2, 2560, 1440, 60.0f),
            EdidMode(3, 2560, 1440, 120.0f),
            EdidMode(4, 1920, 1080, 144.0f),
        )
        val best = DisplayGeometry.pickBestEdidMode(modes)
        assertEquals(EdidMode(3, 2560, 1440, 120.0f), best)

        // When only 30Hz modes exist, still picks the largest valid resolution.
        val only30 = listOf(
            EdidMode(1, 1920, 1080, 30.0f),
            EdidMode(2, 3840, 2160, 30.0f),
        )
        assertEquals(EdidMode(2, 3840, 2160, 30.0f), DisplayGeometry.pickBestEdidMode(only30))
    }

    @Test
    fun connectionSummaryClassifierCoversAllStates() {
        val disc = BridgeLabels.classifyConnectionSummary(DaemonStatus(connected = false))
        assertEquals(ConnectionKind.DISCONNECTED, disc.kind)
        assertEquals(ConnectionTone.ERROR, disc.tone)
        assertEquals("未激活", disc.badgeLabel)

        val usb = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(
                connected = true,
                sinkType = BridgeProtocol.SinkType.REMOTE_USB,
                remotePeerName = "Leo Mac",
                streamFpsX100 = 6000,
                streamBitrateKbps = 18000,
            ),
        )
        assertEquals(ConnectionKind.REMOTE_USB, usb.kind)
        assertEquals(ConnectionTone.SUCCESS, usb.tone)
        assertTrue(usb.notificationTitle.contains("Leo Mac"))
        assertTrue(usb.detail.contains("60 fps"))
        assertTrue(usb.detail.contains("18.0 Mbps"))

        val mirror = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(
                connected = true,
                mirrorMode = BridgeProtocol.MirrorMode.MIRROR,
                extId = 2,
                physW = 2560,
                physH = 1440,
            ),
        )
        assertEquals(ConnectionKind.PHYSICAL_MIRROR, mirror.kind)
        assertEquals("镜像投屏", mirror.badgeLabel)

        val dex = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(
                connected = true,
                vdId = 5,
                virtW = 2560,
                virtH = 1440,
                virtFps = 120f,
                inputToExt = 1,
            ),
            usingA11yOverlay = true,
        )
        assertEquals(ConnectionKind.DEX_ACTIVE, dex.kind)
        assertTrue(dex.notificationTitle.contains("无障碍B"))
        assertEquals("键鼠切回本机", dex.toggleActionLabel)

        val wifiListen = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(
                connected = true,
                wifiServerEnabled = 1,
                wifiServerPort = 19876,
            ),
            localLanIp = "192.168.1.50",
        )
        assertEquals(ConnectionKind.WIFI_LISTENING, wifiListen.kind)
        assertTrue(wifiListen.detail.contains("192.168.1.50:19876"))

        val standby = BridgeLabels.classifyConnectionSummary(DaemonStatus(connected = true))
        assertEquals(ConnectionKind.STANDBY, standby.kind)
        assertEquals("待机就绪", standby.badgeLabel)
    }

    @Test
    fun oemInjectHintClassifiesVendors() {
        assertTrue(BridgeLabels.classifyOemInjectHint("Xiaomi Redmi").contains("HyperOS"))
        assertTrue(BridgeLabels.classifyOemInjectHint("OnePlus OPPO").contains("ColorOS"))
        assertTrue(BridgeLabels.classifyOemInjectHint("vivo iQOO").contains("OriginOS"))
        assertTrue(BridgeLabels.classifyOemInjectHint("Google Pixel").contains("开发者选项"))
    }

    @Test
    fun daemonStateStoreMaintainsStructuralEqualityAndResetsCleanly() {
        DaemonStateStore.resetDisconnected()
        assertFalse(DaemonStateStore.status.value.connected)

        val s1 = DaemonStatus(connected = true, vdId = 7, splitRatioPct = 50)
        DaemonStateStore.updateStatus(s1)
        assertEquals(s1, DaemonStateStore.status.value)

        DaemonStateStore.transformStatus { it.copy(splitRatioPct = 65) }
        assertEquals(65, DaemonStateStore.status.value.splitRatioPct)

        DaemonStateStore.updateSysStatus(SysStatus(valid = true, wifiOn = true, wifiSsid = "Office"))
        assertEquals("Office", DaemonStateStore.sysStatus.value.wifiSsid)
        // Invalid SysStatus does not overwrite valid state
        DaemonStateStore.updateSysStatus(SysStatus(valid = false))
        assertEquals("Office", DaemonStateStore.sysStatus.value.wifiSsid)

        DaemonStateStore.updateWindowSnapshot(WindowStateSnapshot(layoutMode = BridgeProtocol.LayoutMode.FREEFORM))
        assertEquals(BridgeProtocol.LayoutMode.FREEFORM, DaemonStateStore.windowSnapshot.value.layoutMode)

        DaemonStateStore.resetDisconnected()
        assertFalse(DaemonStateStore.status.value.connected)
        assertFalse(DaemonStateStore.sysStatus.value.valid)
        assertEquals(BridgeProtocol.LayoutMode.FULLSCREEN, DaemonStateStore.windowSnapshot.value.layoutMode)
    }

    @Test
    fun popoverCoordinatorEnforcesMutualExclusion() {
        class FakePopover(override val id: String, override var isOpen: Boolean = false) : OverlayPopover {
            var dismissCount = 0
            override fun dismiss() {
                isOpen = false
                dismissCount++
            }
        }

        val coord = PopoverCoordinator()
        val panel = FakePopover("panel", isOpen = true)
        val split = FakePopover("split", isOpen = true)
        val bubble = FakePopover("bubble", isOpen = false)
        coord.register(panel)
        coord.register(split)
        coord.register(bubble)

        assertTrue(coord.isAnyOpen())
        coord.dismissAllExcept("panel")
        assertTrue(panel.isOpen)
        assertFalse(split.isOpen)
        assertEquals(0, panel.dismissCount)
        assertEquals(1, split.dismissCount)
        assertEquals(0, bubble.dismissCount)

        coord.dismissAll()
        assertFalse(coord.isAnyOpen())
        assertEquals(1, panel.dismissCount)
    }

    @Test
    fun bridgeParsersHandleUnicodeEscapesNumericBooleansDepthLimitsAndEdgePayloads() {
        // Escaped strings, numeric booleans (1/0), negative coords, out-of-range defaultSingleWidthDp clamped, missing pkg skipped.
        val json = """
            {
              "layoutMode": "ADAPTIVE_SINGLE",
              "adaptiveSingleEnabled": 0,
              "defaultSingleWidthDp": 9999,
              "tilingTopology": "SPLIT_3_COLS",
              "ratios": [30, "bad", 40, 30],
              "windows": [
                {"taskId": 1, "pkg": "", "mode": "FREEFORM"},
                {"taskId": 7, "pkg": "com.example.\u0061pp", "mode": "", "left": -10, "top": 0, "right": 400, "bottom": 800, "focused": 1, "minimized": 0}
              ]
            }
        """.trimIndent()
        val snap = BridgeParsers.parseWindowStateSnapshot(json)
        assertEquals(BridgeProtocol.LayoutMode.ADAPTIVE_SINGLE, snap.layoutMode)
        assertFalse(snap.adaptiveSingleEnabled)
        assertEquals(DisplayGeometry.MAX_SINGLE_WIDTH_DP, snap.defaultSingleWidthDp)
        assertEquals(BridgeProtocol.LayoutMode.SPLIT_3_COLS, snap.tilingTopology)
        assertEquals(listOf(30, 40, 30), snap.ratios)
        assertEquals(1, snap.windows.size)
        assertEquals("com.example.app", snap.windows[0].pkg)
        assertEquals(BridgeProtocol.LayoutMode.FULLSCREEN, snap.windows[0].mode)
        assertEquals(-10, snap.windows[0].left)
        assertTrue(snap.windows[0].focused)
        assertFalse(snap.windows[0].minimized)

        // Excessive nesting (> 32) and bad escapes reject cleanly to defaults.
        val deepJson = "{\"a\":".repeat(40) + "1" + "}".repeat(40)
        assertTrue(BridgeParsers.parseWindowStateSnapshot(deepJson).windows.isEmpty())
        assertEquals(
            BridgeProtocol.LayoutMode.FULLSCREEN,
            BridgeParsers.parseWindowStateSnapshot("""{"layoutMode":"bad\xescape"}""").layoutMode,
        )
        assertEquals(
            BridgeProtocol.LayoutMode.FULLSCREEN,
            BridgeParsers.parseWindowStateSnapshot("""{"layoutMode":"bad\u12"}""").layoutMode,
        )

        // parseEdidModes edge cases: blank, negative/zero dimensions, non-numeric fps fallback to 60f.
        assertTrue(BridgeParsers.parseEdidModes(null).isEmpty())
        assertTrue(BridgeParsers.parseEdidModes("   ").isEmpty())
        val edid = BridgeParsers.parseEdidModes("1:1920x1080@badfps; 2:1920x-10@60; 3:1920x1080@0; 4:2560x1440@144.0")
        assertEquals(2, edid.size)
        assertEquals(EdidMode(1, 1920, 1080, 60f), edid[0])
        assertEquals(EdidMode(4, 2560, 1440, 144.0f), edid[1])

        // parseAppModes edge cases: negative mode, >3 mode, non-numeric, overwrite on duplicate pkg.
        assertTrue(BridgeParsers.parseAppModes(null).isEmpty())
        val appModes = BridgeParsers.parseAppModes("com.a=-1;com.b=4;com.c=abc;com.d=1;com.d=2; =0")
        assertEquals(mapOf("com.d" to 2), appModes)

        // parseTrustedMacClients: preserves '=' inside clientName and keeps first on duplicate ID.
        val clients = BridgeParsers.parseTrustedMacClients("id1=Mac=Pro; id1=SecondName; ; id2= ; noequals")
        assertEquals(2, clients.size)
        assertEquals("Mac=Pro", clients[0].clientName)
        assertEquals("id2", clients[1].clientName)
    }

    @Test
    fun displayGeometryEdgeBoundariesAndClamping() {
        // alignResolution16 rounding midpoint (+8 rounds up) & custom bounds.
        assertEquals(1088, DisplayGeometry.alignResolution16(1080))
        assertEquals(1072, DisplayGeometry.alignResolution16(1079))
        assertEquals(1280, DisplayGeometry.alignResolution16(1000, minVal = 1280, maxVal = 3840))
        assertEquals(3840, DisplayGeometry.alignResolution16(5000, minVal = 1280, maxVal = 3840))

        // recommendDpiForResolution exact tier thresholds & portrait orientation invariance.
        assertEquals(320, DisplayGeometry.recommendDpiForResolution(2000, 3200))
        assertEquals(240, DisplayGeometry.recommendDpiForResolution(1999, 3200))
        assertEquals(240, DisplayGeometry.recommendDpiForResolution(1360, 2400))
        assertEquals(200, DisplayGeometry.recommendDpiForResolution(1359, 2400))
        assertEquals(200, DisplayGeometry.recommendDpiForResolution(1000, 1800))
        assertEquals(160, DisplayGeometry.recommendDpiForResolution(999, 1800))

        // snapWidthDp tolerance boundaries (+/-14 snaps, +/-15 does not), tiny fullWidthDp, and preset > fullWidthDp guard.
        assertEquals(460 to false, DisplayGeometry.snapWidthDp(446, 1280))
        assertEquals(445 to false, DisplayGeometry.snapWidthDp(445, 1280))
        assertEquals(460 to false, DisplayGeometry.snapWidthDp(474, 1280))
        assertEquals(475 to false, DisplayGeometry.snapWidthDp(475, 1280))
        assertEquals(320 to false, DisplayGeometry.snapWidthDp(100, 1280))
        assertEquals(600 to false, DisplayGeometry.snapWidthDp(600, 680)) // 680 preset is NOT < safeFull (680)
        assertEquals(680 to true, DisplayGeometry.snapWidthDp(650, 680)) // >= 94% of 680 -> fullscreen
        assertEquals(320 to true, DisplayGeometry.snapWidthDp(200, 200)) // safeFull floor 320

        // computeCenteredSingleBoundsPx with dockReservePx, huge reserve floor (240px), and narrow display clamping.
        val withDock = DisplayGeometry.computeCenteredSingleBoundsPx(2560, 1440, 240, 460, dockReservePx = 120)
        assertEquals(listOf(935, 0, 1625, 1320), withDock.toList())
        val hugeDock = DisplayGeometry.computeCenteredSingleBoundsPx(2560, 1440, 240, 460, dockReservePx = 2000)
        assertEquals(240, hugeDock[3])
        val narrow = DisplayGeometry.computeCenteredSingleBoundsPx(500, 800, 240, 1200)
        assertEquals(listOf(0, 0, 500, 800), narrow.toList())

        // normalizeTilingRatios 2-element cumulative vs direct widths for SPLIT_3_COLS & lowercased topology.
        assertEquals(listOf(30, 40, 30), DisplayGeometry.normalizeTilingRatios("split_3_cols", listOf(30, 70)))
        assertEquals(listOf(30, 35, 35), DisplayGeometry.normalizeTilingRatios("SPLIT_3_COLS", listOf(30, 35)))
        assertEquals(listOf(25, 75), DisplayGeometry.normalizeTilingRatios("SPLIT_3_MASTER", listOf(5, 95)))
    }

    @Test
    fun bridgeLabelsCoversAllRemainingModesAndSummaryVariants() {
        // Remote Wi-Fi ADB & Wi-Fi TLS with blank peer name fallback.
        val wifiAdb = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(
                connected = true,
                sinkType = BridgeProtocol.SinkType.REMOTE_WIFI_ADB,
                remotePeerName = "",
                virtW = 1920,
                virtH = 1080,
            ),
        )
        assertEquals(ConnectionKind.REMOTE_WIFI_ADB, wifiAdb.kind)
        assertEquals(ConnectionTone.PRIMARY, wifiAdb.tone)
        assertTrue(wifiAdb.headline.contains("Mac 客户端"))
        assertTrue(wifiAdb.notificationTitle.contains("Mac"))

        val wifiDirect = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(connected = true, sinkType = BridgeProtocol.SinkType.REMOTE_WIFI, remotePeerName = "Studio"),
        )
        assertEquals(ConnectionKind.REMOTE_WIFI, wifiDirect.kind)
        assertEquals(ConnectionTone.ACCENT, wifiDirect.tone)
        assertEquals("断开 Mac", wifiDirect.toggleActionLabel)

        // DeX active with compat overlay + input on tablet.
        val dexCompat = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(connected = true, vdId = 3, virtW = 1920, virtH = 1080, virtFps = 60f, inputToExt = 0),
            usingA11yOverlay = false,
        )
        assertTrue(dexCompat.notificationTitle.contains("兼容A"))
        assertEquals("键鼠切至扩展屏", dexCompat.toggleActionLabel)

        // Wi-Fi listening with blank LAN IP & default port fallback.
        val wifiListenDefault = BridgeLabels.classifyConnectionSummary(
            DaemonStatus(connected = true, wifiServerEnabled = 1, wifiServerPort = 0),
        )
        assertEquals(ConnectionKind.WIFI_LISTENING, wifiListenDefault.kind)
        assertTrue(wifiListenDefault.detail.contains("LAN:${BridgeProtocol.RemoteStream.DEFAULT_WIFI_PORT}"))

        // Label helpers & boundary checks.
        assertEquals("纯黑居中", BridgeLabels.mirrorSideModeLabel(BridgeProtocol.MirrorSideMode.OFF))
        assertEquals("靠右/靠下 · 融合大边栏", BridgeLabels.mirrorSideModeLabel(BridgeProtocol.MirrorSideMode.ALIGN_RIGHT))
        assertEquals("主屏居中 · 双信息条", BridgeLabels.mirrorSideModeLabel(BridgeProtocol.MirrorSideMode.CENTER_DUAL))
        assertEquals("靠左/靠上 · 融合大边栏", BridgeLabels.mirrorSideModeLabel(BridgeProtocol.MirrorSideMode.ALIGN_LEFT))

        assertEquals("被系统拦截 (-1)", BridgeLabels.injectStatusLabel(BridgeProtocol.InputBackend.INJECT_STATUS_BLOCKED))
        assertEquals("系统事件注入正常", BridgeLabels.injectStatusLabel(BridgeProtocol.InputBackend.INJECT_STATUS_SYSTEM))
        assertEquals("仅系统注入", BridgeLabels.inputBackendLabel(BridgeProtocol.InputBackend.INJECT_EVENTS))
        assertEquals("自动 (优先系统/UHID 兜底)", BridgeLabels.inputBackendLabel(BridgeProtocol.InputBackend.AUTO))

        assertEquals("520dp · 手机竖屏", BridgeLabels.classifyWidthPresetLabel(520, isFullscreen = false))
        assertEquals("521dp · 折叠屏", BridgeLabels.classifyWidthPresetLabel(521, isFullscreen = false))
        assertEquals("780dp · 折叠屏", BridgeLabels.classifyWidthPresetLabel(780, isFullscreen = false))
        assertEquals("781dp · 平板双栏", BridgeLabels.classifyWidthPresetLabel(781, isFullscreen = false))

        assertEquals("AB12-CD", BridgeLabels.formatShortFingerprint("ab:12-cd"))
        assertEquals("默认桌面", BridgeLabels.layoutModeLabel("UNKNOWN"))
        assertEquals("0° 横屏", BridgeLabels.rotationLabel(4))
        assertEquals("180° 倒置", BridgeLabels.rotationLabel(-2))
    }
}
