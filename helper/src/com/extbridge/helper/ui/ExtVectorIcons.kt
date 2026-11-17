package com.extbridge.helper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Clean 24x24 linear vector icons matching Xiaomi HyperOS 2.0 / Miuix stroke weight.
 * Eliminates Emoji clutter across all Compose UI surfaces.
 */
object ExtVectorIcons {

    private inline fun strokeIcon(
        name: String,
        strokeWidth: Float = 2.0f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()

    val Dashboard: ImageVector by lazy {
        strokeIcon("Dashboard") {
            // 2x2 rounded bento grid
            moveTo(4f, 5.5f); lineTo(10f, 5.5f); lineTo(10f, 11.5f); lineTo(4f, 11.5f); close()
            moveTo(14f, 5.5f); lineTo(20f, 5.5f); lineTo(20f, 9.5f); lineTo(14f, 9.5f); close()
            moveTo(14f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 19.5f); lineTo(14f, 19.5f); close()
            moveTo(4f, 15.5f); lineTo(10f, 15.5f); lineTo(10f, 19.5f); lineTo(4f, 19.5f); close()
        }
    }

    val DisplayTuning: ImageVector by lazy {
        strokeIcon("DisplayTuning") {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 16f); lineTo(3f, 16f); close()
            moveTo(12f, 16f); lineTo(12f, 20f)
            moveTo(8f, 20f); lineTo(16f, 20f)
            moveTo(7f, 9f); lineTo(10f, 9f)
            moveTo(7f, 12f); lineTo(13f, 12f)
        }
    }

    val AppRules: ImageVector by lazy {
        strokeIcon("AppRules") {
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 15.5f); lineTo(20f, 15.5f)
            moveTo(14f, 19f); lineTo(18f, 19f)
        }
    }

    val SettingsGear: ImageVector by lazy {
        strokeIcon("SettingsGear") {
            moveTo(12f, 8.8f)
            arcTo(3.2f, 3.2f, 0f, true, true, 11.99f, 8.8f)
            moveTo(12f, 3.5f); lineTo(12f, 5.5f)
            moveTo(12f, 18.5f); lineTo(12f, 20.5f)
            moveTo(3.5f, 12f); lineTo(5.5f, 12f)
            moveTo(18.5f, 12f); lineTo(20.5f, 12f)
            moveTo(6.0f, 6.0f); lineTo(7.5f, 7.5f)
            moveTo(16.5f, 16.5f); lineTo(18.0f, 18.0f)
            moveTo(18.0f, 6.0f); lineTo(16.5f, 7.5f)
            moveTo(7.5f, 16.5f); lineTo(6.0f, 18.0f)
        }
    }

    val MonitorDex: ImageVector by lazy {
        strokeIcon("MonitorDex") {
            moveTo(3f, 4.5f); lineTo(21f, 4.5f); lineTo(21f, 16.5f); lineTo(3f, 16.5f); close()
            moveTo(12f, 16.5f); lineTo(12f, 20f)
            moveTo(7.5f, 20f); lineTo(16.5f, 20f)
        }
    }

    val ScreenMirror: ImageVector by lazy {
        strokeIcon("ScreenMirror") {
            moveTo(7f, 4.5f); lineTo(21f, 4.5f); lineTo(21f, 14.5f); lineTo(17f, 14.5f)
            moveTo(3f, 8.5f); lineTo(15f, 8.5f); lineTo(15f, 19.5f); lineTo(3f, 19.5f); close()
        }
    }

    val RouteSwap: ImageVector by lazy {
        strokeIcon("RouteSwap") {
            moveTo(4f, 8.5f); lineTo(20f, 8.5f)
            moveTo(16f, 5f); lineTo(20f, 8.5f); lineTo(16f, 12f)
            moveTo(20f, 15.5f); lineTo(4f, 15.5f)
            moveTo(8f, 12f); lineTo(4f, 15.5f); lineTo(8f, 19f)
        }
    }

    val Touchpad: ImageVector by lazy {
        strokeIcon("Touchpad") {
            moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
            moveTo(3.5f, 14.5f); lineTo(20.5f, 14.5f)
            moveTo(12f, 14.5f); lineTo(12f, 19f)
        }
    }

    val MoonDim: ImageVector by lazy {
        strokeIcon("MoonDim") {
            moveTo(20.2f, 14.5f)
            arcTo(8.2f, 8.2f, 0f, true, true, 9.5f, 3.8f)
            arcTo(6.4f, 6.4f, 0f, false, false, 20.2f, 14.5f)
            close()
        }
    }

    val SidePanel: ImageVector by lazy {
        strokeIcon("SidePanel") {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(15f, 5f); lineTo(15f, 19f)
            moveTo(17.5f, 9f); lineTo(18.5f, 9f)
            moveTo(17.5f, 12f); lineTo(18.5f, 12f)
        }
    }

    val BoltActivate: ImageVector by lazy {
        strokeIcon("BoltActivate") {
            moveTo(13f, 2.5f); lineTo(5.5f, 13.5f); lineTo(11.5f, 13.5f)
            lineTo(10.5f, 21.5f); lineTo(18.5f, 10.5f); lineTo(12.5f, 10.5f); close()
        }
    }

    val RefreshRender: ImageVector by lazy {
        strokeIcon("RefreshRender") {
            moveTo(20f, 11f)
            arcTo(8f, 8f, 0f, true, false, 18.2f, 16.5f)
            moveTo(20f, 6f); lineTo(20f, 11f); lineTo(15f, 11f)
        }
    }

    val Diagnostics: ImageVector by lazy {
        strokeIcon("Diagnostics") {
            moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9.5f, 6.5f)
            lineTo(13.5f, 17.5f); lineTo(16.5f, 12f); lineTo(21f, 12f)
        }
    }

    val ShieldCheck: ImageVector by lazy {
        strokeIcon("ShieldCheck") {
            moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11.5f)
            quadTo(19f, 17.5f, 12f, 21f)
            quadTo(5f, 17.5f, 5f, 11.5f)
            lineTo(5f, 6f); close()
            moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.5f, 9.8f)
        }
    }

    val CrownRoot: ImageVector by lazy {
        strokeIcon("CrownRoot") {
            moveTo(4f, 18f); lineTo(20f, 18f)
            moveTo(4.5f, 15f); lineTo(3.5f, 7.5f); lineTo(8.5f, 11.5f)
            lineTo(12f, 6f); lineTo(15.5f, 11.5f); lineTo(20.5f, 7.5f); lineTo(19.5f, 15f); close()
        }
    }

    val WirelessWave: ImageVector by lazy {
        strokeIcon("WirelessWave") {
            moveTo(3.5f, 9f); quadTo(12f, 2.5f, 20.5f, 9f)
            moveTo(6.8f, 12.8f); quadTo(12f, 8.2f, 17.2f, 12.8f)
            moveTo(10.2f, 16.5f); quadTo(12f, 14.8f, 13.8f, 16.5f)
            moveTo(12f, 19.5f); lineTo(12.02f, 19.5f)
        }
    }

    val TerminalCode: ImageVector by lazy {
        strokeIcon("TerminalCode") {
            moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
            moveTo(7.5f, 9.5f); lineTo(10.5f, 12f); lineTo(7.5f, 14.5f)
            moveTo(12.5f, 14.5f); lineTo(16.5f, 14.5f)
        }
    }

    val Keyboard: ImageVector by lazy {
        strokeIcon("Keyboard") {
            moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
            moveTo(8f, 14.5f); lineTo(16f, 14.5f)
            moveTo(7f, 10f); lineTo(7.2f, 10f)
            moveTo(12f, 10f); lineTo(12.2f, 10f)
            moveTo(17f, 10f); lineTo(17.2f, 10f)
        }
    }

    val WidgetCard: ImageVector by lazy {
        strokeIcon("WidgetCard") {
            moveTo(4f, 4.5f); lineTo(13f, 4.5f); lineTo(13f, 11.5f); lineTo(4f, 11.5f); close()
            moveTo(16f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 11.5f); lineTo(16f, 11.5f); close()
            moveTo(4f, 14.5f); lineTo(20f, 14.5f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        }
    }

    val Palette: ImageVector by lazy {
        strokeIcon("Palette") {
            moveTo(12f, 3.5f)
            arcTo(8.5f, 8.5f, 0f, true, false, 12f, 20.5f)
            quadTo(14.5f, 20.5f, 14.5f, 18.2f)
            quadTo(14.5f, 16f, 16.8f, 16f)
            lineTo(18.2f, 16f)
            quadTo(20.5f, 16f, 20.5f, 12f)
            arcTo(8.5f, 8.5f, 0f, false, false, 12f, 3.5f)
            moveTo(8.5f, 9.5f); lineTo(8.6f, 9.5f)
            moveTo(12f, 7.8f); lineTo(12.1f, 7.8f)
            moveTo(15.5f, 9.5f); lineTo(15.6f, 9.5f)
        }
    }

    val PowerExit: ImageVector by lazy {
        strokeIcon("PowerExit") {
            moveTo(12f, 3.5f); lineTo(12f, 11.5f)
            moveTo(7.2f, 6.2f)
            arcTo(7.5f, 7.5f, 0f, true, false, 16.8f, 6.2f)
        }
    }

    val ArrowBack: ImageVector by lazy {
        strokeIcon("ArrowBack") {
            moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f)
        }
    }

    val CloseX: ImageVector by lazy {
        strokeIcon("CloseX") {
            moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
            moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp") {
            moveTo(12f, 18.5f); lineTo(12f, 5.5f)
            moveTo(7f, 10.5f); lineTo(12f, 5.5f); lineTo(17f, 10.5f)
        }
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon("ArrowDown") {
            moveTo(12f, 5.5f); lineTo(12f, 18.5f)
            moveTo(7f, 13.5f); lineTo(12f, 18.5f); lineTo(17f, 13.5f)
        }
    }

    val TrashDelete: ImageVector by lazy {
        strokeIcon("TrashDelete") {
            moveTo(4.5f, 7f); lineTo(19.5f, 7f)
            moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
            moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 7f)
        }
    }

    val CopyDoc: ImageVector by lazy {
        strokeIcon("CopyDoc") {
            moveTo(9f, 8f); lineTo(19f, 8f); lineTo(19f, 20f); lineTo(9f, 20f); close()
            moveTo(15f, 8f); lineTo(15f, 4f); lineTo(5f, 4f); lineTo(5f, 16f); lineTo(9f, 16f)
        }
    }

    val SearchLens: ImageVector by lazy {
        strokeIcon("SearchLens") {
            moveTo(11f, 5f)
            arcTo(6f, 6f, 0f, true, true, 10.99f, 5f)
            moveTo(15.5f, 15.5f); lineTo(20f, 20f)
        }
    }

    val OneHand: ImageVector by lazy {
        strokeIcon("OneHand") {
            moveTo(6f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(6f, 20f); close()
            moveTo(6f, 11.5f); lineTo(18f, 11.5f)
        }
    }
}
