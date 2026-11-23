package io.github.lengsukq.opaldesk.update

import java.net.URI
import java.util.Locale

/**
 * Pure-JVM testable release metadata, semantic version comparison, GitHub Releases JSON parsing,
 * and strict HTTPS host allowlist for OpalDesk in-app updates.
 */
object AppUpdateLogic {
    const val REPO_OWNER = "lengsukq"
    const val REPO_NAME = "OpalDesk"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val LATEST_RELEASE_WEB_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val ALL_RELEASES_WEB_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "codeload.github.com",
    )

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val body: String,
        val publishedDate: String,
        val htmlUrl: String,
        val apkAssetName: String,
        val apkDownloadUrl: String,
        val apkSizeBytes: Long,
        val apkSha256: String?,
    )

    fun normalizeVersion(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) {
            trimmed.substring(1).trim()
        } else {
            trimmed
        }
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.3" vs "v1.0.2", "1.0.3-beta" vs "1.0.3").
     * Returns positive if [a] > [b], negative if [a] < [b], and 0 if equal.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseSemver(a)
        val pb = parseSemver(b)
        val maxLen = maxOf(pa.nums.size, pb.nums.size)
        for (i in 0 until maxLen) {
            val va = pa.nums.getOrElse(i) { 0 }
            val vb = pb.nums.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return when {
            pa.preRelease.isEmpty() && pb.preRelease.isEmpty() -> 0
            pa.preRelease.isEmpty() -> 1
            pb.preRelease.isEmpty() -> -1
            else -> pa.preRelease.compareTo(pb.preRelease, ignoreCase = true)
        }
    }

    fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
        if (candidateVersion.isBlank()) return false
        return compareVersions(candidateVersion, currentVersion) > 0
    }

    fun isAllowedReleaseUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return ALLOWED_HOSTS.contains(host) || host.endsWith(".githubusercontent.com")
    }

    /**
     * Parses a GitHub `GET /repos/{owner}/{repo}/releases/latest` JSON payload using a pure-Kotlin
     * parser so unit tests run deterministically on JVM without Android `org.json` stubs.
     */
    fun parseLatestReleaseJson(json: String): ReleaseInfo? {
        val root = JsonLite.parse(json) as? Map<*, *> ?: return null
        if (root["draft"] == true) return null
        val tagName = (root["tag_name"] as? String)?.trim().orEmpty()
        if (tagName.isEmpty()) return null
        val versionName = normalizeVersion(tagName)
        val title = (root["name"] as? String)?.trim().orEmpty().ifBlank { "OpalDesk $tagName" }
        val rawBody = (root["body"] as? String).orEmpty()
        val publishedRaw = (root["published_at"] as? String).orEmpty()
        val publishedDate = publishedRaw.take(10).takeIf { it.length == 10 && it[4] == '-' } ?: ""
        val htmlUrl = (root["html_url"] as? String)?.trim().orEmpty()
            .takeIf { isAllowedReleaseUrl(it) }
            ?: "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$tagName"

        val assets = root["assets"] as? List<*> ?: emptyList<Any>()
        val selectedAsset = selectBestApkAsset(assets)
        val assetName = selectedAsset?.name.orEmpty()
        val assetUrl = selectedAsset?.downloadUrl.orEmpty()
        val assetSize = selectedAsset?.sizeBytes ?: 0L
        val assetSha256 = selectedAsset?.sha256

        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            body = cleanReleaseNotes(rawBody),
            publishedDate = publishedDate,
            htmlUrl = htmlUrl,
            apkAssetName = assetName,
            apkDownloadUrl = assetUrl,
            apkSizeBytes = assetSize,
            apkSha256 = assetSha256,
        )
    }

    /**
     * Fallback builder when GitHub REST API is rate-limited (HTTP 403/429) and only the redirect
     * target of `/releases/latest` -> `/releases/tag/<tag>` is available.
     */
    fun buildFallbackReleaseFromTagRedirect(locationUrl: String): ReleaseInfo? {
        if (!isAllowedReleaseUrl(locationUrl)) return null
        val marker = "/releases/tag/"
        val idx = locationUrl.indexOf(marker)
        if (idx < 0) return null
        val tag = locationUrl.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (tag.isEmpty()) return null
        val ver = normalizeVersion(tag)
        val apkName = "OpalDesk_$tag.apk"
        val apkUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$apkName"
        return ReleaseInfo(
            tagName = tag,
            versionName = ver,
            title = "OpalDesk $tag",
            body = "点击「下载并安装」获取最新版本，或前往 GitHub 查看完整更新日志。",
            publishedDate = "",
            htmlUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$tag",
            apkAssetName = apkName,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = 0L,
            apkSha256 = null,
        )
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "未知大小"
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun cleanReleaseNotes(raw: String, maxChars: Int = 1800): String {
        if (raw.isBlank()) return "包含体验优化与稳定性修复。"
        val cleaned = raw
            .replace("\r\n", "\n")
            .lines()
            .map { line ->
                line.trimEnd()
                    .replace(Regex("^#{1,6}\\s+"), "")
                    .replace("**", "")
                    .replace("__", "")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars).trimEnd() + "…" else cleaned
    }

    private data class ParsedSemver(val nums: List<Int>, val preRelease: String)

    private fun parseSemver(raw: String): ParsedSemver {
        val norm = normalizeVersion(raw).substringBefore('+')
        val dash = norm.indexOf('-')
        val mainPart = if (dash >= 0) norm.substring(0, dash) else norm
        val prePart = if (dash >= 0) norm.substring(dash + 1) else ""
        val nums = mainPart.split('.').map { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedSemver(nums = nums, preRelease = prePart)
    }

    private data class ApkAssetCandidate(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String?,
        val priority: Int,
    )

    private fun selectBestApkAsset(assets: List<*>): ApkAssetCandidate? {
        val candidates = ArrayList<ApkAssetCandidate>()
        for (item in assets) {
            val map = item as? Map<*, *> ?: continue
            val name = (map["name"] as? String)?.trim().orEmpty()
            val url = (map["browser_download_url"] as? String)?.trim().orEmpty()
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (!isAllowedReleaseUrl(url)) continue
            val lower = name.lowercase(Locale.US)
            if (lower.contains("unsigned") || lower.contains("debug")) continue
            val size = (map["size"] as? Number)?.toLong() ?: 0L
            val rawDigest = (map["digest"] as? String)?.trim().orEmpty()
            val sha256 = if (rawDigest.startsWith("sha256:", ignoreCase = true)) {
                rawDigest.substring(7).trim().lowercase(Locale.US)
                    .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            } else {
                null
            }
            val priority = when {
                lower.startsWith("opaldesk") -> 0
                else -> 1
            }
            candidates.add(ApkAssetCandidate(name, url, size, sha256, priority))
        }
        return candidates.minByOrNull { it.priority }
    }

    /**
     * Minimal RFC 8259 recursive-descent JSON parser with zero external or Android dependencies.
     */
    internal object JsonLite {
        fun parse(text: String): Any? {
            val parser = Parser(text)
            return try {
                parser.skipWs()
                parser.parseValue()
            } catch (_: Exception) {
                null
            }
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun skipWs() {
                while (i < s.length && s[i] <= ' ') i++
            }

            fun parseValue(): Any? {
                skipWs()
                if (i >= s.length) return null
                return when (s[i]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> expectLiteral("true", true)
                    'f' -> expectLiteral("false", false)
                    'n' -> expectLiteral("null", null)
                    '-', in '0'..'9' -> parseNumber()
                    else -> throw IllegalArgumentException("Unexpected char at $i")
                }
            }

            private fun parseObject(): Map<String, Any?> {
                val out = LinkedHashMap<String, Any?>()
                i++ // '{'
                skipWs()
                if (i < s.length && s[i] == '}') {
                    i++
                    return out
                }
                while (i < s.length) {
                    skipWs()
                    val key = parseString()
                    skipWs()
                    if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':'")
                    i++
                    out[key] = parseValue()
                    skipWs()
                    if (i >= s.length) break
                    when (s[i]) {
                        ',' -> i++
                        '}' -> {
                            i++
                            return out
                        }
                        else -> throw IllegalArgumentException("Expected ',' or '}'")
                    }
                }
                return out
            }

            private fun parseArray(): List<Any?> {
                val out = ArrayList<Any?>()
                i++ // '['
                skipWs()
                if (i < s.length && s[i] == ']') {
                    i++
                    return out
                }
                while (i < s.length) {
                    out.add(parseValue())
                    skipWs()
                    if (i >= s.length) break
                    when (s[i]) {
                        ',' -> i++
                        ']' -> {
                            i++
                            return out
                        }
                        else -> throw IllegalArgumentException("Expected ',' or ']'")
                    }
                }
                return out
            }

            private fun parseString(): String {
                if (s[i] != '"') throw IllegalArgumentException("Expected '\"'")
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= s.length) throw IllegalArgumentException("Unterminated escape")
                            when (val esc = s[i++]) {
                                '"', '\\', '/' -> sb.append(esc)
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (i + 4 > s.length) throw IllegalArgumentException("Bad unicode")
                                    val code = s.substring(i, i + 4).toInt(16)
                                    sb.append(code.toChar())
                                    i += 4
                                }
                                else -> sb.append(esc)
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                throw IllegalArgumentException("Unterminated string")
            }

            private fun parseNumber(): Number {
                val start = i
                if (s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
                var isFloat = false
                if (i < s.length && s[i] == '.') {
                    isFloat = true
                    i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    isFloat = true
                    i++
                    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                val token = s.substring(start, i)
                return if (isFloat) token.toDouble() else token.toLong()
            }

            private fun <T> expectLiteral(lit: String, value: T): T {
                if (!s.startsWith(lit, i)) throw IllegalArgumentException("Expected $lit")
                i += lit.length
                return value
            }
        }
    }
}
