package io.github.lengsukq.opaldesk.web

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Pure-JVM security policy, URL builders, and rate-limited one-time session code gate for
 * the GitHub Pages Web Receiver & LAN Activation Bridge.
 */
object WebActivationPolicy {
    const val DEFAULT_ACTIVATION_PORT = 19879
    const val DEFAULT_CAST_PORT = 19878
    const val AUTO_EXPIRE_MS = 5L * 60L * 1000L // 5 minutes
    const val MAX_FAILED_AUTH_ATTEMPTS = 5
    const val DEFAULT_WEB_CAST_URL = "https://lengsukq.github.io/OpalDesk/cast/"

    private const val ALLOWED_GH_HOST = "lengsukq.github.io"
    private const val ALLOWED_GH_PATH_PREFIX = "/OpalDesk/"
    private val SIX_DIGIT_REGEX = Regex("^\\d{6}$")
    private val SECURE_RANDOM = SecureRandom()

    fun generateSessionCode(): String =
        String.format(Locale.US, "%06d", SECURE_RANDOM.nextInt(1_000_000))

    fun isAllowedOrigin(rawOrigin: String?): Boolean {
        val trimmed = rawOrigin?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return false
        return try {
            val uri = URI.create(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            if (host.isEmpty() || uri.rawUserInfo != null) return false
            when (host) {
                ALLOWED_GH_HOST -> scheme == "https" && (uri.port == -1 || uri.port == 443)
                "localhost", "127.0.0.1", "[::1]" -> scheme == "http" || scheme == "https"
                else -> false
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun sanitizeReturnUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_WEB_CAST_URL
        return try {
            val uri = URI.create(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            val path = uri.rawPath.orEmpty()
            if (host.isEmpty() || uri.rawUserInfo != null) return DEFAULT_WEB_CAST_URL
            if (host == ALLOWED_GH_HOST && scheme == "https"
                && (uri.port == -1 || uri.port == 443)
                && path.startsWith(ALLOWED_GH_PATH_PREFIX)
            ) {
                return uri.toASCIIString()
            }
            if ((host == "localhost" || host == "127.0.0.1" || host == "[::1]")
                && (scheme == "http" || scheme == "https")
            ) {
                return uri.toASCIIString()
            }
            DEFAULT_WEB_CAST_URL
        } catch (_: IllegalArgumentException) {
            DEFAULT_WEB_CAST_URL
        }
    }

    /**
     * Builds the GitHub Pages LAN Activation URL.
     * The one-time session code is placed strictly in the URL fragment (`#code=...`) so it is
     * never transmitted in HTTP requests to GitHub Pages servers.
     */
    fun buildWebActivationUrl(
        lanIp: String,
        actPort: Int,
        castPort: Int = DEFAULT_CAST_PORT,
        sessionCode: String,
    ): String {
        val encHost = urlEncode(lanIp.trim())
        val encCode = urlEncode(sessionCode.trim())
        return "${DEFAULT_WEB_CAST_URL}?mode=activate&host=$encHost&actPort=$actPort&castPort=$castPort#code=$encCode"
    }

    fun buildWebCastUrl(lanIp: String, castPort: Int = DEFAULT_CAST_PORT): String {
        val encHost = urlEncode(lanIp.trim())
        return "${DEFAULT_WEB_CAST_URL}?host=$encHost&port=$castPort"
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun isValidSixDigitCode(code: String?): Boolean =
        code != null && SIX_DIGIT_REGEX.matches(code.trim())

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * Thread-safe, constant-time session code verifier with automatic rotation after
     * [maxFailures] invalid attempts to prevent LAN brute-forcing.
     */
    class SessionCodeGate(
        initialCode: String = generateSessionCode(),
        private val maxFailures: Int = MAX_FAILED_AUTH_ATTEMPTS,
    ) {
        data class VerifyResult(
            val ok: Boolean,
            val rotated: Boolean,
            val newCode: String,
            val remainingAttempts: Int,
            val message: String,
        )

        private val lock = Any()
        private var currentCode: String = initialCode
        private var failedAttempts: Int = 0

        fun getCode(): String = synchronized(lock) { currentCode }

        fun rotate(): String = synchronized(lock) {
            currentCode = generateSessionCode()
            failedAttempts = 0
            currentCode
        }

        fun verify(candidate: String?): VerifyResult = synchronized(lock) {
            val cleaned = candidate?.trim().orEmpty()
            val expectedBytes = currentCode.toByteArray(StandardCharsets.UTF_8)
            val actualBytes = cleaned.toByteArray(StandardCharsets.UTF_8)
            val matches = expectedBytes.size == actualBytes.size
                && MessageDigest.isEqual(expectedBytes, actualBytes)
            if (matches) {
                failedAttempts = 0
                return VerifyResult(
                    ok = true,
                    rotated = false,
                    newCode = currentCode,
                    remainingAttempts = maxFailures,
                    message = "会话访问码验证通过",
                )
            }
            failedAttempts++
            if (failedAttempts >= maxFailures) {
                currentCode = generateSessionCode()
                failedAttempts = 0
                return VerifyResult(
                    ok = false,
                    rotated = true,
                    newCode = currentCode,
                    remainingAttempts = maxFailures,
                    message = "错误次数过多，手机端已自动刷新 6 位访问码，请查看手机屏幕重试",
                )
            }
            val rem = (maxFailures - failedAttempts).coerceAtLeast(0)
            VerifyResult(
                ok = false,
                rotated = false,
                newCode = currentCode,
                remainingAttempts = rem,
                message = "访问码不正确（还可尝试 $rem 次）",
            )
        }
    }
}
