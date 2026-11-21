package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.GeminiRunOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.repository.OcrRunOutcome
import com.example.domain.model.OcrEngineType
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.ui.viewmodel.GeminiExecutionUiState
import com.example.ui.viewmodel.SuspiciousFixUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground Service keeping OCR, Gemini visual analysis, and suspicious-page repair loops alive
 * when the app is minimized or the screen is turned off.
 */
class ProcessingForegroundService : Service() {

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(serviceJob + ioDispatcher)
    private var activeTaskJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    internal fun overrideIoDispatcherForTesting(dispatcher: CoroutineDispatcher) {
        ioDispatcher = dispatcher
        serviceJob.cancel()
        serviceJob = SupervisorJob()
        serviceScope = CoroutineScope(serviceJob + ioDispatcher)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel(this)

        when (intent?.action) {
            ACTION_CANCEL_PROCESSING -> {
                cancelProcessingAndStop(
                    noticeAr = "تم إيقاف المعالجة مؤقتاً. النتائج المكتملة محفوظة محلياً.",
                )
                return START_NOT_STICKY
            }

            ACTION_START_OCR -> {
                val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
                if (documentId.isBlank()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                if (activeTaskJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                val engineName = intent.getStringExtra(EXTRA_ENGINE_TYPE)
                val engineOverride = engineName?.let { raw ->
                    runCatching { OcrEngineType.valueOf(raw) }.getOrNull()
                }
                val retryFailedOnly = intent.getBooleanExtra(EXTRA_RETRY_FAILED_ONLY, false)
                val singlePageId = intent.getStringExtra(EXTRA_SINGLE_PAGE_ID)
                val startPage = intent.getIntExtra(EXTRA_START_PAGE, 1)
                val endPage = intent.getIntExtra(EXTRA_END_PAGE, Int.MAX_VALUE)
                val reprocessShortOnly = intent.getBooleanExtra(EXTRA_REPROCESS_SHORT_ONLY, false)

                promoteToForeground("جاري تهيئة استخراج النص...", 0, 0)
                launchOcrLoop(
                    documentId = documentId,
                    engineOverride = engineOverride,
                    retryFailedOnly = retryFailedOnly,
                    singlePageId = singlePageId,
                    startPage = startPage,
                    endPage = endPage,
                    reprocessShortOnly = reprocessShortOnly,
                )
            }

            ACTION_START_GEMINI -> {
                val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
                if (documentId.isBlank()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                if (activeTaskJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                val singlePageId = intent.getStringExtra(EXTRA_SINGLE_PAGE_ID)
                val startPage = intent.getIntExtra(EXTRA_START_PAGE, 1)
                val endPage = intent.getIntExtra(EXTRA_END_PAGE, Int.MAX_VALUE)

                promoteToForeground("جاري تهيئة تحليل Gemini...", 0, 0)
                launchGeminiLoop(
                    documentId = documentId,
                    singlePageId = singlePageId,
                    startPage = startPage,
                    endPage = endPage,
                )
            }

            ACTION_START_SUSPICIOUS_FIX -> {
                val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
                if (documentId.isBlank()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                if (activeTaskJob?.isActive == true) {
                    return START_NOT_STICKY
                }
                promoteToForeground("جاري فحص وإصلاح الصفحات الناقصة...", 0, 0)
                launchSuspiciousFixLoop(documentId = documentId)
            }

            else -> {
                stopSelfIfIdle()
            }
        }

        return START_NOT_STICKY
    }

    private fun launchOcrLoop(
        documentId: String,
        engineOverride: OcrEngineType?,
        retryFailedOnly: Boolean,
        singlePageId: String?,
        startPage: Int,
        endPage: Int,
        reprocessShortOnly: Boolean,
    ) {
        acquireWakeLockSafely()
        ProcessingStateBridge.setActiveTask(ActiveBackgroundTask.OCR)

        activeTaskJob = serviceScope.launch {
            try {
                val repo: MuharrirRepository = ProcessingStateBridge.resolveRepository(applicationContext)
                val engineType = engineOverride ?: repo.preferences.first().preferredOcrEngine
                val availability = repo.checkOcrEngineAvailability(applicationContext, engineType)

                ProcessingStateBridge.updateOcrAvailability(engineType, availability)
                if (availability is OcrAvailabilityResult.Unavailable) {
                    ProcessingStateBridge.setWorkspaceNotice(availability.reasonAr)
                    return@launch
                }

                ProcessingStateBridge.updateOcrState {
                    it.copy(
                        isRunning = true,
                        activeDocumentId = documentId,
                        currentPageNumber = 0,
                        activeEngine = engineType,
                    )
                }

                val outcome = repo.runDocumentOcr(
                    context = applicationContext,
                    documentId = documentId,
                    engineType = engineType,
                    retryFailedOnly = retryFailedOnly,
                    singlePageId = singlePageId,
                    startPage = startPage,
                    endPage = endPage,
                    reprocessFailedAndShortOnly = reprocessShortOnly,
                    onProgress = { progress ->
                        ProcessingStateBridge.updateOcrState {
                            it.copy(
                                isRunning = true,
                                activeDocumentId = progress.documentId,
                                currentPageNumber = progress.currentPageNumber,
                                completedPages = progress.completedPages,
                                failedPages = progress.failedPages,
                                totalPages = progress.totalPages,
                                activeEngine = progress.engineType,
                            )
                        }
                        updateNotification(
                            contentText = formatPageProgressText(
                                currentPage = progress.currentPageNumber,
                                totalPages = progress.totalPages,
                            ),
                            completed = progress.completedPages + progress.failedPages,
                            total = progress.totalPages,
                        )
                    },
                )

                when (outcome) {
                    is OcrRunOutcome.Completed -> {
                        val notice = when {
                            reprocessShortOnly && outcome.processedNow == 0 ->
                                "لا توجد صفحات فاشلة أو ذات نص ناقص تحتاج لإعادة المعالجة."
                            reprocessShortOnly ->
                                "أُعيدت معالجة ${outcome.processedNow} صفحة؛ الإجمالي المكتمل الآن ${outcome.totalDonePages} من ${outcome.totalPages}."
                            outcome.processedNow == 0 && outcome.totalFailedPages > 0 ->
                                "لا توجد صفحات معلّقة في النطاق المحدد؛ يوجد ${outcome.totalFailedPages} صفحة فاشلة يمكنك إعادة معالجتها."
                            outcome.processedNow == 0 ->
                                "جميع صفحات النطاق المحدد (${outcome.totalDonePages} من ${outcome.totalPages}) مكتملة الاستخراج بالفعل."
                            outcome.totalFailedPages > 0 ->
                                "اكتمل استخراج ${outcome.totalDonePages} من ${outcome.totalPages} صفحة، وفشلت ${outcome.totalFailedPages} صفحة."
                            else ->
                                "تم استخراج وحفظ نتائج ${outcome.totalDonePages} من ${outcome.totalPages} صفحة بنجاح عبر ${engineType.displayNameAr}."
                        }
                        ProcessingStateBridge.setWorkspaceNotice(notice)
                    }
                    is OcrRunOutcome.Failure -> {
                        ProcessingStateBridge.setWorkspaceNotice(outcome.reasonAr)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                ProcessingStateBridge.setWorkspaceNotice(
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "تعذر إكمال استخراج النص في الخلفية.",
                )
            } finally {
                ProcessingStateBridge.updateOcrState { it.copy(isRunning = false) }
                ProcessingStateBridge.setActiveTask(null)
                releaseWakeLockSafely()
                stopForegroundAndSelf()
            }
        }
    }

    private fun launchGeminiLoop(
        documentId: String,
        singlePageId: String?,
        startPage: Int,
        endPage: Int,
    ) {
        acquireWakeLockSafely()
        ProcessingStateBridge.setActiveTask(ActiveBackgroundTask.GEMINI_ANALYSIS)
        ProcessingStateBridge.updateGeminiState {
            GeminiExecutionUiState(
                isRunning = true,
                activeDocumentId = documentId,
            )
        }

        activeTaskJob = serviceScope.launch {
            try {
                val repo = ProcessingStateBridge.resolveRepository(applicationContext)
                val outcome = repo.runGeminiAnalysis(
                    documentId = documentId,
                    singlePageId = singlePageId,
                    startPage = startPage,
                    endPage = endPage,
                    onProgress = { progress ->
                        ProcessingStateBridge.updateGeminiState {
                            GeminiExecutionUiState(
                                isRunning = true,
                                activeDocumentId = progress.documentId,
                                currentPageNumber = progress.currentPageNumber,
                                analyzedPages = progress.analyzedPages,
                                failedPages = progress.failedPages,
                                totalTargetPages = progress.totalTargetPages,
                            )
                        }
                        updateNotification(
                            contentText = formatPageProgressText(
                                currentPage = progress.currentPageNumber,
                                totalPages = progress.totalTargetPages,
                            ),
                            completed = progress.analyzedPages + progress.failedPages,
                            total = progress.totalTargetPages,
                        )
                    },
                )
                when (outcome) {
                    is GeminiRunOutcome.Completed -> {
                        ProcessingStateBridge.setWorkspaceNotice(outcome.lastMessageAr)
                    }
                    is GeminiRunOutcome.Failure -> {
                        ProcessingStateBridge.setWorkspaceNotice(outcome.reasonAr)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                ProcessingStateBridge.setWorkspaceNotice(
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "تعذر إكمال تحليل Gemini في الخلفية.",
                )
            } finally {
                ProcessingStateBridge.updateGeminiState { it.copy(isRunning = false) }
                ProcessingStateBridge.setActiveTask(null)
                releaseWakeLockSafely()
                stopForegroundAndSelf()
            }
        }
    }

    private fun launchSuspiciousFixLoop(documentId: String) {
        acquireWakeLockSafely()
        ProcessingStateBridge.setActiveTask(ActiveBackgroundTask.SUSPICIOUS_FIX)
        ProcessingStateBridge.updateSuspiciousFixState {
            SuspiciousFixUiState(
                isRunning = true,
                activeDocumentId = documentId,
            )
        }

        activeTaskJob = serviceScope.launch {
            try {
                val repo = ProcessingStateBridge.resolveRepository(applicationContext)
                val outcome = repo.fixSuspiciousPagesWithGemini(
                    documentId = documentId,
                    onProgress = { progress ->
                        ProcessingStateBridge.updateSuspiciousFixState {
                            SuspiciousFixUiState(
                                isRunning = true,
                                activeDocumentId = progress.documentId,
                                currentPageNumber = progress.currentPageNumber,
                                fixedPages = progress.analyzedPages,
                                failedPages = progress.failedPages,
                                totalSuspiciousPages = progress.totalTargetPages,
                            )
                        }
                        updateNotification(
                            contentText = formatPageProgressText(
                                currentPage = progress.currentPageNumber,
                                totalPages = progress.totalTargetPages,
                            ),
                            completed = progress.analyzedPages + progress.failedPages,
                            total = progress.totalTargetPages,
                        )
                    },
                )
                when (outcome) {
                    is GeminiRunOutcome.Completed -> {
                        ProcessingStateBridge.setWorkspaceNotice(outcome.lastMessageAr)
                    }
                    is GeminiRunOutcome.Failure -> {
                        ProcessingStateBridge.setWorkspaceNotice(outcome.reasonAr)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                ProcessingStateBridge.setWorkspaceNotice(
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "تعذر إكمال إصلاح الصفحات الناقصة في الخلفية.",
                )
            } finally {
                ProcessingStateBridge.updateSuspiciousFixState { it.copy(isRunning = false) }
                ProcessingStateBridge.incrementSuspiciousRefreshTrigger()
                ProcessingStateBridge.setActiveTask(null)
                releaseWakeLockSafely()
                stopForegroundAndSelf()
            }
        }
    }

    private fun cancelProcessingAndStop(noticeAr: String) {
        activeTaskJob?.cancel()
        activeTaskJob = null
        ProcessingStateBridge.markAllStopped()
        ProcessingStateBridge.setWorkspaceNotice(noticeAr)
        releaseWakeLockSafely()
        stopForegroundAndSelf()
    }

    private fun promoteToForeground(
        contentText: String,
        completed: Int,
        total: Int,
    ) {
        val notification = buildNotification(contentText, completed, total)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(
        contentText: String,
        completed: Int,
        total: Int,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(contentText, completed, total))
    }

    internal fun buildNotification(
        contentText: String,
        completed: Int,
        total: Int,
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, ProcessingForegroundService::class.java).apply {
            action = ACTION_CANCEL_PROCESSING
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val safeMax = total.coerceAtLeast(1)
        val safeProgress = completed.coerceIn(0, safeMax)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_muharrir)
            .setContentTitle("المحرّر — معالجة المستند")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(safeMax, safeProgress, total <= 0)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إلغاء",
                cancelPendingIntent,
            )
            .build()
    }

    private fun acquireWakeLockSafely() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLockSafely() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSelfIfIdle() {
        if (activeTaskJob?.isActive != true) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        activeTaskJob?.cancel()
        releaseWakeLockSafely()
        ProcessingStateBridge.markAllStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "muharrir_processing_channel"
        const val NOTIFICATION_ID = 2201

        const val ACTION_START_OCR = "com.example.action.START_OCR"
        const val ACTION_START_GEMINI = "com.example.action.START_GEMINI"
        const val ACTION_START_SUSPICIOUS_FIX = "com.example.action.START_SUSPICIOUS_FIX"
        const val ACTION_CANCEL_PROCESSING = "com.example.action.CANCEL_PROCESSING"

        const val EXTRA_DOCUMENT_ID = "extra_document_id"
        const val EXTRA_ENGINE_TYPE = "extra_engine_type"
        const val EXTRA_RETRY_FAILED_ONLY = "extra_retry_failed_only"
        const val EXTRA_SINGLE_PAGE_ID = "extra_single_page_id"
        const val EXTRA_START_PAGE = "extra_start_page"
        const val EXTRA_END_PAGE = "extra_end_page"
        const val EXTRA_REPROCESS_SHORT_ONLY = "extra_reprocess_short_only"

        private const val REQUEST_CODE_OPEN_APP = 1000
        private const val REQUEST_CODE_CANCEL = 1001
        private const val WAKELOCK_TAG = "muharrir:ProcessingWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

        fun formatPageProgressText(currentPage: Int, totalPages: Int): String {
            return if (totalPages > 0 && currentPage > 0) {
                "جاري معالجة الصفحة $currentPage من $totalPages"
            } else {
                "جاري معالجة صفحات المستند..."
            }
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "معالجة المستندات في الخلفية",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "يعرض تقدم استخراج النص والتحليل الذكي للمستندات في الخلفية."
                        setShowBadge(false)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        fun startOcr(
            context: Context,
            documentId: String,
            engineType: OcrEngineType? = null,
            retryFailedOnly: Boolean = false,
            singlePageId: String? = null,
            startPage: Int = 1,
            endPage: Int = Int.MAX_VALUE,
            reprocessShortOnly: Boolean = false,
        ) {
            val intent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ACTION_START_OCR
                putExtra(EXTRA_DOCUMENT_ID, documentId)
                engineType?.let { putExtra(EXTRA_ENGINE_TYPE, it.name) }
                putExtra(EXTRA_RETRY_FAILED_ONLY, retryFailedOnly)
                singlePageId?.let { putExtra(EXTRA_SINGLE_PAGE_ID, it) }
                putExtra(EXTRA_START_PAGE, startPage)
                putExtra(EXTRA_END_PAGE, endPage)
                putExtra(EXTRA_REPROCESS_SHORT_ONLY, reprocessShortOnly)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startGemini(
            context: Context,
            documentId: String,
            singlePageId: String? = null,
            startPage: Int = 1,
            endPage: Int = Int.MAX_VALUE,
        ) {
            val intent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ACTION_START_GEMINI
                putExtra(EXTRA_DOCUMENT_ID, documentId)
                singlePageId?.let { putExtra(EXTRA_SINGLE_PAGE_ID, it) }
                putExtra(EXTRA_START_PAGE, startPage)
                putExtra(EXTRA_END_PAGE, endPage)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSuspiciousFix(
            context: Context,
            documentId: String,
        ) {
            val intent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ACTION_START_SUSPICIOUS_FIX
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ACTION_CANCEL_PROCESSING
            }
            runCatching { context.startService(intent) }
        }
    }
}
