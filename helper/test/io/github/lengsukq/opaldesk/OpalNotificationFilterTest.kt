package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.notifications.OpalNotificationFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalNotificationFilterTest {

    @Test
    fun `allows normal messaging app notifications`() {
        assertTrue(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "微信",
                text = "你好，收到一条新消息",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
    }

    @Test
    fun `filters self package systemui ongoing and group summary notifications`() {
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "io.github.lengsukq.opaldesk",
                title = "OpalDesk",
                text = "运行中",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "微信",
                text = "3 条未读",
                isOngoing = false,
                isGroupSummary = true,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.netease.cloudmusic",
                title = "正在播放",
                text = "歌曲",
                isOngoing = true,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
        assertFalse(
            OpalNotificationFilter.shouldInclude(
                packageName = "com.tencent.mm",
                title = "",
                text = "",
                isOngoing = false,
                isGroupSummary = false,
                isForegroundService = false,
            ),
        )
    }
}
