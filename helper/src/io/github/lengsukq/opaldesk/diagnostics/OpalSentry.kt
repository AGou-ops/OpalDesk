package io.github.lengsukq.opaldesk.diagnostics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.lengsukq.opaldesk.BuildConfig
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class ManualReportOutcome(
    val success: Boolean,
    val eventId: String = "",
    val shortCode: String = "",
    val errorMessage: String = "",
    val quota: FeedbackQuotaSnapshot,
)

/**
 * Central Sentry runtime coordinator for OpalDesk Android (:helper).
 * - Initializes SentryAndroid SDK with privacy-first defaults (no PII, no screenshots).
 * - Coordinates manual diagnostic log uploads with strict 5/day local natural-day rate limiting
 *   and confirmed HTTP 2xx receipt before incrementing quota.
 */
object OpalSentry {
    private val uploadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalSentryUpload").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    fun isConfigured(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val appCtx = ctx.applicationContext
            val dsn = BuildConfig.SENTRY_DSN.trim()
            if (dsn.isEmpty()) {
                OpalLogStore.i("Sentry", "SENTRY_DSN not configured in this build; Sentry SDK disabled")
                return
            }
            val installationId = AppPrefs.getOrCreateInstallationId(appCtx)
            val environment = if (BuildConfig.DEBUG) "debug" else "production"
            val release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            runCatching {
                SentryAndroid.init(appCtx) { options ->
                    options.dsn = dsn
                    options.environment = environment
                    options.release = release
                    options.isSendDefaultPii = false
                    options.isAttachScreenshot = false
                    options.isAttachViewHierarchy = false
                    options.isEnableAutoSessionTracking = true
                    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                        event.setTag("installation_id", installationId)
                        event.message?.let { msg ->
                            msg.formatted = msg.formatted?.let(DiagnosticSanitizer::sanitizeText)
                            msg.message = msg.message?.let(DiagnosticSanitizer::sanitizeText)
                        }
                        event
                    }
                }
                Sentry.configureScope { scope ->
                    scope.setTag("installation_id", installationId)
                    scope.user = User().apply {
                        id = installationId
                        val savedName = AppPrefs.getFeedbackUsername(appCtx)
                        if (savedName.isNotBlank()) {
                            username = savedName
                        }
                    }
                }
                OpalLogStore.i("Sentry", "Sentry initialized (env=$environment, release=$release)")
            }.onFailure { t ->
                OpalLogStore.w("Sentry", "Sentry initialization failed", t)
            }
        }
    }

    fun createRateLimiter(ctx: Context): FeedbackRateLimiter =
        FeedbackRateLimiter(storage = AppPrefs.createFeedbackQuotaStorage(ctx))

    fun currentQuota(ctx: Context): FeedbackQuotaSnapshot =
        createRateLimiter(ctx).currentQuota()

    fun submitManualReportAsync(
        ctx: Context,
        reporterName: String,
        problemDescription: String,
        appVersion: String,
        status: DaemonStatus,
        scene: CompositorScene,
        remote: RemoteStreamStatus,
        helperEntries: List<OpalLogEntry>,
        daemonLogText: String,
        onComplete: (ManualReportOutcome) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        val cleanUser = reporterName.trim().take(64)
        val cleanDesc = problemDescription.trim().take(2000)
        if (cleanUser.isNotEmpty()) {
            AppPrefs.setFeedbackUsername(appCtx, cleanUser)
        }

        uploadExecutor.execute {
            val limiter = createRateLimiter(appCtx)
            val beforeQuota = limiter.currentQuota()
            if (beforeQuota.isExhausted) {
                dispatchMain(
                    onComplete,
                    ManualReportOutcome(
                        success = false,
                        errorMessage = limiter.limitReachedMessage(),
                        quota = beforeQuota,
                    ),
                )
                return@execute
            }

            val deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val now = Date()
            val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
            val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

            val sanitizedReport = DiagnosticSanitizer.buildSanitizedReportText(
                appVersion = appVersion,
                deviceSummary = deviceSummary,
                reporterName = cleanUser,
                problemDescription = cleanDesc,
                status = status,
                scene = scene,
                remote = remote,
                helperEntries = helperEntries,
                daemonLogText = daemonLogText,
                generatedAtText = generatedAt,
            )
            val gzipBytes = DiagnosticSanitizer.gzipCompress(sanitizedReport)
            val installationId = limiter.installationId()
            val daemonLineCount = daemonLogText.lineSequence()
                .count { it.isNotBlank() }
                .coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_DAEMON_LINES)

            val payload = ManualReportPayload(
                installationId = installationId,
                reporterName = cleanUser,
                problemDescription = cleanDesc,
                appVersion = appVersion,
                appVersionCode = BuildConfig.VERSION_CODE,
                environment = if (BuildConfig.DEBUG) "debug" else "production",
                deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                deviceModel = Build.MODEL.orEmpty(),
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                androidApiLevel = Build.VERSION.SDK_INT,
                daemonConnected = status.connected,
                helperLogCount = helperEntries.size.coerceAtMost(DiagnosticSanitizer.DEFAULT_MAX_HELPER_ENTRIES),
                daemonLogCount = daemonLineCount,
                attachmentFilename = "opaldesk_diag_$fileStamp.log.gz",
                compressedLogBytes = gzipBytes,
            )

            val uploadResult = SentryEnvelopeTransport.sendEnvelope(
                rawDsn = BuildConfig.SENTRY_DSN,
                tunnelUrl = BuildConfig.SENTRY_TUNNEL_URL,
                payload = payload,
            )

            val outcome = when (uploadResult) {
                is ManualUploadResult.Success -> {
                    val afterQuota = limiter.recordConfirmedUpload()
                    OpalLogStore.i(
                        "Sentry",
                        "Manual diagnostic report confirmed: eventId=${uploadResult.eventId} (${afterQuota.usedToday}/${afterQuota.maxDaily})",
                    )
                    ManualReportOutcome(
                        success = true,
                        eventId = uploadResult.eventId,
                        shortCode = uploadResult.shortCode,
                        quota = afterQuota,
                    )
                }
                is ManualUploadResult.Failure -> {
                    OpalLogStore.w("Sentry", "Manual diagnostic report failed: ${uploadResult.reason}")
                    ManualReportOutcome(
                        success = false,
                        errorMessage = uploadResult.reason,
                        quota = limiter.currentQuota(),
                    )
                }
            }
            dispatchMain(onComplete, outcome)
        }
    }

    private fun dispatchMain(
        callback: (ManualReportOutcome) -> Unit,
        outcome: ManualReportOutcome,
    ) {
        mainHandler.post { callback(outcome) }
    }
}
