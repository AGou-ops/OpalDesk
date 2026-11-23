package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateLogicTest {

    @Test
    fun semverComparisonHandlesPatchMinorPreReleaseAndPrefix() {
        assertTrue(AppUpdateLogic.isNewerVersion("1.0.2", "v1.0.3"))
        assertTrue(AppUpdateLogic.isNewerVersion("1.0.9", "1.0.10"))
        assertTrue(AppUpdateLogic.isNewerVersion("1.0.3-beta", "1.0.3"))
        assertTrue(AppUpdateLogic.isNewerVersion("1.0.3", "1.1.0"))
        assertFalse(AppUpdateLogic.isNewerVersion("1.0.3", "v1.0.3"))
        assertFalse(AppUpdateLogic.isNewerVersion("1.0.3", "1.0.2"))
        assertFalse(AppUpdateLogic.isNewerVersion("1.0", "1.0.0"))
        assertEquals(0, AppUpdateLogic.compareVersions("v1.0.3", "1.0.3"))
    }

    @Test
    fun parseLatestReleaseJsonSelectsSignedApkAndExtractsSha256() {
        val sampleJson = """
            {
              "tag_name": "v1.0.4",
              "name": "OpalDesk v1.0.4",
              "draft": false,
              "published_at": "2026-11-24T08:15:00Z",
              "html_url": "https://github.com/lengsukq/OpalDesk/releases/tag/v1.0.4",
              "body": "### Highlights\r\n- **In-app updater** with SHA-256 verification\r\n- Smooth 120fps scrolling",
              "assets": [
                {
                  "name": "opaldesk.jar",
                  "size": 169000,
                  "browser_download_url": "https://github.com/lengsukq/OpalDesk/releases/download/v1.0.4/opaldesk.jar"
                },
                {
                  "name": "OpalDesk_v1.0.4-unsigned.apk",
                  "size": 5500000,
                  "browser_download_url": "https://github.com/lengsukq/OpalDesk/releases/download/v1.0.4/OpalDesk_v1.0.4-unsigned.apk"
                },
                {
                  "name": "OpalDesk_v1.0.4.apk",
                  "size": 5720123,
                  "digest": "sha256:4614c3ac2b6bde6f3c84ed9625cd11be6670c1ddb2e2aef5937bdc58a953b46a",
                  "browser_download_url": "https://github.com/lengsukq/OpalDesk/releases/download/v1.0.4/OpalDesk_v1.0.4.apk"
                },
                {
                  "name": "OpalDesk_v1.0.4-macos.zip",
                  "size": 2513757,
                  "browser_download_url": "https://github.com/lengsukq/OpalDesk/releases/download/v1.0.4/OpalDesk_v1.0.4-macos.zip"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateLogic.parseLatestReleaseJson(sampleJson)
        assertNotNull(parsed)
        parsed!!
        assertEquals("v1.0.4", parsed.tagName)
        assertEquals("1.0.4", parsed.versionName)
        assertEquals("2026-11-24", parsed.publishedDate)
        assertEquals("OpalDesk_v1.0.4.apk", parsed.apkAssetName)
        assertEquals(5720123L, parsed.apkSizeBytes)
        assertEquals("4614c3ac2b6bde6f3c84ed9625cd11be6670c1ddb2e2aef5937bdc58a953b46a", parsed.apkSha256)
        assertTrue(parsed.body.contains("In-app updater"))
        assertFalse(parsed.body.contains("**"))
    }

    @Test
    fun parseLatestReleaseJsonSkipsDrafts() {
        val draft = """{"tag_name":"v9.9.9","draft":true,"assets":[]}"""
        assertNull(AppUpdateLogic.parseLatestReleaseJson(draft))
    }

    @Test
    fun urlAllowlistBlocksNonHttpsAndUntrustedHosts() {
        assertTrue(AppUpdateLogic.isAllowedReleaseUrl("https://api.github.com/repos/lengsukq/OpalDesk/releases/latest"))
        assertTrue(AppUpdateLogic.isAllowedReleaseUrl("https://github.com/lengsukq/OpalDesk/releases/download/v1.0.3/OpalDesk_v1.0.3.apk"))
        assertTrue(AppUpdateLogic.isAllowedReleaseUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(AppUpdateLogic.isAllowedReleaseUrl("http://github.com/lengsukq/OpalDesk/releases/download/v1.0.3/OpalDesk_v1.0.3.apk"))
        assertFalse(AppUpdateLogic.isAllowedReleaseUrl("https://evil.example.com/OpalDesk.apk"))
        assertFalse(AppUpdateLogic.isAllowedReleaseUrl("file:///data/local/tmp/test.apk"))
    }

    @Test
    fun fallbackFromWebRedirectExtractsTagAndOfficialApkUrl() {
        val info = AppUpdateLogic.buildFallbackReleaseFromTagRedirect(
            "https://github.com/lengsukq/OpalDesk/releases/tag/v1.0.5",
        )
        assertNotNull(info)
        info!!
        assertEquals("v1.0.5", info.tagName)
        assertEquals("1.0.5", info.versionName)
        assertEquals(
            "https://github.com/lengsukq/OpalDesk/releases/download/v1.0.5/OpalDesk_v1.0.5.apk",
            info.apkDownloadUrl,
        )
    }

    @Test
    fun formatByteSizeFormatsHumanReadableUnits() {
        assertEquals("5.4 MB", AppUpdateLogic.formatByteSize(5_662_310L))
        assertEquals("512 KB", AppUpdateLogic.formatByteSize(524_288L))
        assertEquals("未知大小", AppUpdateLogic.formatByteSize(0L))
    }
}
