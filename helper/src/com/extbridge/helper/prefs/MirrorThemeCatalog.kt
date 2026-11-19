package com.extbridge.helper.prefs

import kotlin.math.roundToInt

data class MirrorTheme(
    val id: String,
    val name: String,
    val bgColor: Int,
    val headerBgColor: Int,
    val cardBgColor: Int,
    val cardStrokeColor: Int,
    val widgetBgColor: Int,
    val pillBgColor: Int,
    val accentBtnColor: Int,
    val titleColor: Int,
    val subColor: Int,
    val accentColor: Int,
    val lightMode: Boolean,
)

/**
 * Pure-Kotlin catalog of Native Mirror sidebar themes, telemetry card presets,
 * cursor speed multipliers, and widget aspect-ratio presets.
 */
object MirrorThemeCatalog {
    const val MIRROR_WIDGET_HOST_ID = 0xEB01
    const val DEFAULT_MIRROR_PRESETS = "clock,battery,wifi,bt,audio,media,screendim"

    val MIRROR_THEME_KEYS = arrayOf(
        "night", "oled", "frost", "cyber", "emerald", "amber", "custom",
    )

    val MIRROR_THEME_TITLES = arrayOf(
        "极夜深蓝", "OLED纯黑", "晨雾浅白", "赛博紫青", "翡翠墨绿", "暖暮琥珀", "自定义颜色",
    )

    val MIRROR_SPEED_LABELS = arrayOf(
        "0.9x 精细", "1.4x 标准", "1.9x 流畅", "2.6x 极速", "3.4x 飞快",
    )

    val MIRROR_SPEED_MULTS = floatArrayOf(0.9f, 1.4f, 1.9f, 2.6f, 3.4f)

    val MIRROR_WIDGET_RATIO_LABELS = arrayOf(
        "自动原比例", "方形 1:1", "竖屏 3:4", "宽屏 16:9", "铺满槽位",
    )

    val MIRROR_PRESET_KEYS = arrayOf(
        "clock", "battery", "wifi", "bt", "audio", "media", "calendar",
        "perf", "netspeed", "storage", "pomodoro", "memo", "quickctrl", "screendim",
    )

    val MIRROR_PRESET_TITLES = arrayOf(
        "大数字时钟",
        "电池温压详情",
        "Wi-Fi 与信号",
        "蓝牙与外设",
        "音频通道与音量",
        "媒体播放与切歌",
        "日历与近期日程",
        "内存与系统负载",
        "实时网速监控",
        "存储与亮度信息",
        "专注番茄钟",
        "快捷桌面便签",
        "镜像快捷控制台",
        "主屏最低亮度",
    )

    val MIRROR_PRESET_DESCS = arrayOf(
        "超大时间 + 日期星期（高对比醒目字号）",
        "电量百分比、快充功耗、电池温度与电压",
        "当前 Wi-Fi 名称、连接速率与信号强度",
        "蓝牙开关状态与已连接耳机/键鼠名称",
        "实时输出通道 + 触控音量加减与静音",
        "当前媒体曲目/App + 上一曲/播放暂停/下一曲",
        "读取系统日历近两日日程提醒",
        "实时可用 RAM 内存占比条 + 系统运行负载",
        "每秒实时下行 ↓ / 上行 ↑ 网络吞吐速率",
        "机身存储可用空间 GB + 系统当前亮度状态",
        "25 分钟倒计时专注钟，支持边栏直接启停/重置",
        "自定义文字待办/会议备忘，随时在控制台修改",
        "边栏直达快捷按钮：切回 DeX / 切换对齐方位 / 切换键鼠 / 最低亮度",
        "一键把平板/手机主屏亮度压至硬件最低（省电护眼），再点恢复",
    )

    fun mirrorPresetIndex(key: String?): Int =
        if (key == null) -1 else MIRROR_PRESET_KEYS.indexOf(key)

    fun mirrorPresetTitle(key: String?): String {
        val idx = mirrorPresetIndex(key)
        return if (idx >= 0) MIRROR_PRESET_TITLES[idx] else key.toString()
    }

    fun resolveMirrorTheme(id: String?, customBg: Int): MirrorTheme = when (id) {
        "oled" -> MirrorTheme(
            "oled", "OLED纯黑",
            0xFF000000.toInt(), 0xFF0A0D14.toInt(), 0xE60D111A.toInt(), 0x38475569,
            0xB3090D14.toInt(), 0xFF181E29.toInt(), 0xFF1D4ED8.toInt(), 0xFFFFFFFF.toInt(),
            0xFF94A3B8.toInt(), 0xFF60A5FA.toInt(), false,
        )
        "frost" -> MirrorTheme(
            "frost", "晨雾浅白",
            0xFFF1F5F9.toInt(), 0xFFE2E8F0.toInt(), 0xF2FFFFFF.toInt(), 0x3864748B,
            0xE6FFFFFF.toInt(), 0xFFE2E8F0.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt(),
            0xFF475569.toInt(), 0xFF0284C7.toInt(), true,
        )
        "cyber" -> MirrorTheme(
            "cyber", "赛博紫青",
            0xFF110722.toInt(), 0xFF190C33.toInt(), 0xDE1E1038.toInt(), 0x4CA855F7,
            0x99190D30.toInt(), 0xFF2E1065.toInt(), 0xFF7C3AED.toInt(), 0xFFFDF4FF.toInt(),
            0xFFD8B4FE.toInt(), 0xFF22D3EE.toInt(), false,
        )
        "emerald" -> MirrorTheme(
            "emerald", "翡翠墨绿",
            0xFF031410.toInt(), 0xFF062019.toInt(), 0xDE0A241E.toInt(), 0x4C10B981,
            0x99071C17.toInt(), 0xFF134E4A.toInt(), 0xFF059669.toInt(), 0xFFECFDF5.toInt(),
            0xFFA7F3D0.toInt(), 0xFF34D399.toInt(), false,
        )
        "amber" -> MirrorTheme(
            "amber", "暖暮琥珀",
            0xFF180E06.toInt(), 0xFF241509.toInt(), 0xDE29180B.toInt(), 0x4CF59E0B,
            0x99201207.toInt(), 0xFF451A03.toInt(), 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(),
            0xFFFDE68A.toInt(), 0xFFFBBF24.toInt(), false,
        )
        "custom" -> resolveCustomTheme(customBg)
        else -> MirrorTheme(
            "night", "极夜深蓝",
            0xFF060A14.toInt(), 0xFF0D1526.toInt(), 0xDE0F172A.toInt(), 0x4294A3B8,
            0x990F172A.toInt(), 0xFF1E293B.toInt(), 0xFF312E81.toInt(), 0xFFF8FAFC.toInt(),
            0xFFCBD5E1.toInt(), 0xFF38BDF8.toInt(), false,
        )
    }

    private fun resolveCustomTheme(customBg: Int): MirrorTheme {
        val opaqueBg = 0xFF000000.toInt() or (customBg and 0x00FFFFFF)
        val r = (opaqueBg shr 16) and 0xFF
        val g = (opaqueBg shr 8) and 0xFF
        val b = opaqueBg and 0xFF
        val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        return if (luma > 0.58f) {
            val hdr = blendColor(opaqueBg, 0xFFCBD5E1.toInt(), 0.22f)
            MirrorTheme(
                "custom", "自定义浅色",
                opaqueBg, hdr, 0xEAFFFFFF.toInt(), 0x3864748B, 0xDEFFFFFF.toInt(),
                0xFFE2E8F0.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt(),
                0xFF475569.toInt(), 0xFF0284C7.toInt(), true,
            )
        } else {
            val hdr = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.08f)
            val cardRgb = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.11f) and 0x00FFFFFF
            val wRgb = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.07f) and 0x00FFFFFF
            val pill = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.18f)
            MirrorTheme(
                "custom", "自定义深色",
                opaqueBg, hdr, 0xDC000000.toInt() or cardRgb, 0x44FFFFFF,
                0x9E000000.toInt() or wRgb, pill, 0xFF2563EB.toInt(),
                0xFFF8FAFC.toInt(), 0xFFCBD5E1.toInt(), 0xFF38BDF8.toInt(), false,
            )
        }
    }

    fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * ratio).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * ratio).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * ratio).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}
