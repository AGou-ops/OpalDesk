package io.github.lengsukq.opaldesk.ui

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

    val Keyboard: ImageVector by lazy {
        strokeIcon("Keyboard") {
            moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
            moveTo(8f, 14.5f); lineTo(16f, 14.5f)
            moveTo(7f, 10f); lineTo(7.2f, 10f)
            moveTo(12f, 10f); lineTo(12.2f, 10f)
            moveTo(17f, 10f); lineTo(17.2f, 10f)
        }
    }

    val ArrowBack: ImageVector by lazy {
        strokeIcon("ArrowBack") {
            moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f)
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

    val LaptopMac: ImageVector by lazy {
        strokeIcon("LaptopMac") {
            moveTo(4.5f, 5.5f); lineTo(19.5f, 5.5f); lineTo(19.5f, 16f); lineTo(4.5f, 16f); close()
            moveTo(2.5f, 18.5f); lineTo(21.5f, 18.5f)
        }
    }
}
