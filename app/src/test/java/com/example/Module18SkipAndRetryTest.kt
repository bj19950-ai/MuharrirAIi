package com.example

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiPageAnalysisOutcome
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.GeminiRunOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.BackendConnectionState
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiReasonType
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageFailureReasons
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.export.ui.WordExportViewModel
import com.example.ui.screens.DocumentWorkspaceScreen
import com.example.ui.theme.MuharrirTheme
import com.example.ui.viewmodel.MuharrirViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module18SkipAndRetryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var storage: PrivateDocumentStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        application = context.applicationContext as Application
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "muharrir_documents").deleteRecursively()
        context.deleteDatabase("m18_v6_migration_test.db")
        context.deleteSharedPreferences("test_m18_vault_batch")
        context.deleteSharedPreferences("test_m18_vault_retry")
        context.deleteSharedPreferences("test_m18_vault_skip")
        context.deleteSharedPreferences("test_m18_vault_ui")
        context.deleteSharedPreferences("test_m18_vault_timeout")
    }

    private fun openInMemoryDb(): MuharrirDatabase =
        Room.inMemoryDatabaseBuilder(context, MuharrirDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

    private fun fakePdfEngine(pageCount: Int): PdfPageEngine = object : PdfPageEngine {
        override suspend fun indexPages(
            pdfFile: File,
            batchSize: Int,
            onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
        ) = PrivatePdfIndexResult.Success(
            pageCount = pageCount,
            pages = (1..pageCount).map { IndexedPdfPageMeta(it, 595, 842) },
        )

        override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(240, 340, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(20f, 20f, 220f, 300f, paint)
            return bmp
        }
    }

    @Test
    fun `room migration 6 to 7 adds failureReason column to pages`() = runTest {
        val dbName = "m18_v6_migration_test.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS documents (
                id TEXT NOT NULL PRIMARY KEY,
                originalFileName TEXT NOT NULL,
                internalStoragePath TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                importDateMillis INTEGER NOT NULL,
                lastModifiedMillis INTEGER NOT NULL,
                pageCount INTEGER,
                indexedPagesCount INTEGER NOT NULL,
                processingStatus TEXT NOT NULL,
                importStatus TEXT NOT NULL,
                importProgressPercent INTEGER NOT NULL,
                processingProgressPercent INTEGER NOT NULL,
                isValidationPassed INTEGER NOT NULL,
                validationNote TEXT,
                errorMessageAr TEXT,
                reviewApprovedAt INTEGER,
                exportedDocxPath TEXT,
                exportedMode TEXT,
                exportedAtMillis INTEGER
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pages (
                pageId TEXT NOT NULL PRIMARY KEY,
                documentId TEXT NOT NULL,
                originalPageNumber INTEGER NOT NULL,
                widthPoints INTEGER,
                heightPoints INTEGER,
                renderingReference TEXT NOT NULL,
                thumbnailCachePath TEXT,
                pageStatus TEXT NOT NULL,
                ocrStatus TEXT NOT NULL DEFAULT 'PENDING',
                ocrEngineUsed TEXT,
                ocrErrorMessageAr TEXT,
                ocrUpdatedAtMillis INTEGER,
                hasMissingContent INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_pages_documentId_originalPageNumber ON pages(documentId, originalPageNumber)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ocr_results (
                pageId TEXT NOT NULL PRIMARY KEY,
                documentId TEXT NOT NULL,
                pageNumber INTEGER NOT NULL,
                engineType TEXT NOT NULL,
                recognizedText TEXT NOT NULL,
                segmentsJson TEXT NOT NULL,
                confidence REAL,
                imageWidthPx INTEGER NOT NULL,
                imageHeightPx INTEGER NOT NULL,
                recognizedAtMillis INTEGER NOT NULL,
                FOREIGN KEY(pageId) REFERENCES pages(pageId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_ocr_results_documentId_pageNumber ON ocr_results(documentId, pageNumber)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gemini_page_analyses (
                pageId TEXT NOT NULL PRIMARY KEY,
                documentId TEXT NOT NULL,
                pageNumber INTEGER NOT NULL,
                modelId TEXT NOT NULL,
                usedKeyLabel TEXT NOT NULL,
                rawValidatedJson TEXT NOT NULL,
                itemsCount INTEGER NOT NULL,
                analyzedAtMillis INTEGER NOT NULL,
                FOREIGN KEY(pageId) REFERENCES pages(pageId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_gemini_page_analyses_documentId_pageNumber ON gemini_page_analyses(documentId, pageNumber)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS review_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                document_id TEXT NOT NULL,
                page_id TEXT NOT NULL,
                ocr_result_id TEXT,
                gemini_analysis_id TEXT,
                page_number INTEGER NOT NULL,
                x1 INTEGER NOT NULL,
                y1 INTEGER NOT NULL,
                x2 INTEGER NOT NULL,
                y2 INTEGER NOT NULL,
                ocr_text TEXT NOT NULL,
                suggested_text TEXT,
                ocr_confidence REAL,
                gemini_confidence REAL,
                category TEXT NOT NULL,
                priority INTEGER NOT NULL,
                review_status TEXT NOT NULL,
                user_text TEXT,
                evidence TEXT NOT NULL,
                reason_type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                block_index INTEGER NOT NULL,
                line_index INTEGER NOT NULL,
                element_index INTEGER NOT NULL,
                stale_note_ar TEXT,
                FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE,
                FOREIGN KEY(page_id) REFERENCES pages(pageId) ON DELETE CASCADE,
                FOREIGN KEY(ocr_result_id) REFERENCES ocr_results(pageId) ON DELETE SET NULL,
                FOREIGN KEY(gemini_analysis_id) REFERENCES gemini_page_analyses(pageId) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_document_id ON review_items(document_id)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_page_id ON review_items(page_id)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_review_status ON review_items(review_status)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_priority ON review_items(priority)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS preferences (
                singletonId INTEGER NOT NULL PRIMARY KEY,
                preferredOcrEngine TEXT NOT NULL,
                preferredExportMode TEXT NOT NULL,
                highlightUnresolvedInDocx INTEGER NOT NULL,
                autoSaveCheckpoints INTEGER NOT NULL,
                resumeAfterInterruption INTEGER NOT NULL,
                batchSizePages INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS api_key_slots (
                id TEXT NOT NULL PRIMARY KEY,
                label TEXT NOT NULL,
                isEnabled INTEGER NOT NULL,
                status TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        sqlite.execSQL(
            "INSERT INTO documents VALUES ('v6-doc', 'كتاب_المستصفى.pdf', '/tmp/v6.pdf', 1024, 100, 100, 1, 1, 'PROCESSING', 'READY_FOR_PROCESSING', 100, 50, 1, 'ok', NULL, NULL, NULL, NULL, NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO pages VALUES ('v6-doc_page_1', 'v6-doc', 1, 595, 842, '/tmp/v6.pdf', NULL, 'OCR_DONE', 'DONE', 'TESSERACT', NULL, 100, 0)",
        )
        sqlite.version = 6
        sqlite.close()

        val migratedDb = Room.databaseBuilder(context, MuharrirDatabase::class.java, dbName)
            .addMigrations(
                MuharrirDatabase.MIGRATION_1_2,
                MuharrirDatabase.MIGRATION_2_3,
                MuharrirDatabase.MIGRATION_3_4,
                MuharrirDatabase.MIGRATION_4_5,
                MuharrirDatabase.MIGRATION_5_6,
                MuharrirDatabase.MIGRATION_6_7,
                MuharrirDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

        val repo = MuharrirRepository(migratedDb, storage)
        val doc = repo.getDocumentById("v6-doc")
        assertNotNull(doc)
        assertNull(doc!!.pages.first().failureReason)
        assertTrue(repo.getPagesWithFailedAnalysis("v6-doc").isEmpty())
        migratedDb.close()
    }

    @Test
    fun `test 1 bulk analysis with one failing page completes batch and marks only failing page FAILED`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-m18-batch"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }
        seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 3)

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m18_vault_batch", Context.MODE_PRIVATE) },
        )
        vault.addKey("BatchKey", "AIzaSyM18BatchTestKey111")

        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                return if (payload.pageNumber == 2) {
                    Result.failure(UnknownHostException("simulated page 2 network failure"))
                } else {
                    Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
                }
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("نص مستخرج")
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(3),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )

        val outcome = repo.runGeminiAnalysis(documentId = docId)
        assertTrue(outcome is GeminiRunOutcome.Completed)
        val completed = outcome as GeminiRunOutcome.Completed
        assertEquals(2, completed.analyzedNow)
        assertEquals(1, completed.failedPages)

        val doc = repo.getDocumentById(docId)!!
        val pagesByNum = doc.pages.associateBy { it.originalPageNumber }
        assertEquals(PageStatus.ANALYZED, pagesByNum[1]?.status)
        assertNull(pagesByNum[1]?.failureReason)
        assertEquals(PageStatus.FAILED, pagesByNum[2]?.status)
        assertEquals(PageFailureReasons.NETWORK_ERROR, pagesByNum[2]?.failureReason)
        assertEquals(PageStatus.ANALYZED, pagesByNum[3]?.status)
        assertNull(pagesByNum[3]?.failureReason)

        // Failed pages list contains only page 2, and OCR text was preserved
        val failedList = repo.getPagesWithFailedAnalysis(docId)
        assertEquals(listOf(2), failedList.map { it.originalPageNumber })
        assertNotNull(pagesByNum[2]?.ocrResult)

        db.close()
    }

    @Test
    fun `test 2 retryPageAnalysis sets status REVIEWED and clears failureReason on success or updates reason on failure`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-m18-retry"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }
        seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 2)

        db.pageDao().updatePageFailureState("${docId}_page_2", PageStatus.FAILED.name, PageFailureReasons.PARSE_ERROR)

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m18_vault_retry", Context.MODE_PRIVATE) },
        )
        vault.addKey("RetryKey", "AIzaSyM18RetryTestKey222")

        var shouldSucceed = false
        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                return if (!shouldSucceed) {
                    Result.failure(UnknownHostException("still offline"))
                } else {
                    Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
                }
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("")
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(2),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )

        // First retry fails -> stays FAILED and failureReason updated
        val firstAttempt = repo.retryPageAnalysis(docId, 2)
        assertTrue(firstAttempt.isFailure)
        val afterFailure = repo.getDocumentById(docId)!!.pages.first { it.originalPageNumber == 2 }
        assertEquals(PageStatus.FAILED, afterFailure.status)
        assertEquals(PageFailureReasons.NETWORK_ERROR, afterFailure.failureReason)

        // Second retry succeeds -> status REVIEWED and failureReason cleared
        shouldSucceed = true
        val secondAttempt = repo.retryPageAnalysis(docId, 2)
        assertTrue(secondAttempt.isSuccess)
        val afterSuccess = repo.getDocumentById(docId)!!.pages.first { it.originalPageNumber == 2 }
        assertEquals(PageStatus.REVIEWED, afterSuccess.status)
        assertNull(afterSuccess.failureReason)
        assertTrue(repo.getPagesWithFailedAnalysis(docId).isEmpty())

        db.close()
    }

    @Test
    fun `test 3 skipPageAnalysis marks user_skipped, preserves OCR and review items, and skips future batch analysis`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-m18-skip"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }
        seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 2)

        // Seed page 2 as FAILED and attach an existing review item + OCR result
        db.pageDao().updatePageFailureState("${docId}_page_2", PageStatus.FAILED.name, PageFailureReasons.TIMEOUT)
        db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_2",
                    ocrResultId = "${docId}_page_2",
                    geminiAnalysisId = null,
                    pageNumber = 2,
                    x1 = 0,
                    y1 = 0,
                    x2 = 40,
                    y2 = 20,
                    ocrText = "الكلمة",
                    suggestedText = "الكلية",
                    ocrConfidence = 0.70f,
                    geminiConfidence = 0.90f,
                    category = ComparisonCategory.DISAGREEMENT.name,
                    priority = 1,
                    reviewStatus = ReviewStatus.PENDING.name,
                    userText = null,
                    evidence = "مراجعة قائمة",
                    reasonType = GeminiReasonType.VISUAL.wireValue,
                    createdAt = 1000L,
                    resolvedAt = null,
                ),
            ),
        )

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m18_vault_skip", Context.MODE_PRIVATE) },
        )
        vault.addKey("SkipKey", "AIzaSyM18SkipTestKey333")

        val analyzedPageNumbers = mutableListOf<Int>()
        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                analyzedPageNumbers.add(payload.pageNumber)
                return Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("")
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(2),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )

        assertEquals(listOf(2), repo.getPagesWithFailedAnalysis(docId).map { it.originalPageNumber })

        val skipResult = repo.skipPageAnalysis(docId, 2)
        assertTrue(skipResult.isSuccess)

        // Removed from failed list and persisted with user_skipped
        assertTrue(repo.getPagesWithFailedAnalysis(docId).isEmpty())
        val skippedPage = repo.getDocumentById(docId)!!.pages.first { it.originalPageNumber == 2 }
        assertEquals(PageStatus.FAILED, skippedPage.status)
        assertEquals(PageFailureReasons.USER_SKIPPED, skippedPage.failureReason)
        // Existing OCR text and review items must NOT be deleted
        assertEquals("نص الفقه القديم في صفحة 2", skippedPage.ocrResult?.recognizedText)
        assertEquals(1, db.reviewItemDao().getItemsForPage("${docId}_page_2").size)

        // Running batch Gemini analysis skips page 2 and only analyzes page 1
        val outcome = repo.runGeminiAnalysis(docId) as GeminiRunOutcome.Completed
        assertEquals(1, outcome.analyzedNow)
        assertEquals(listOf(1), analyzedPageNumbers)

        db.close()
    }

    @Test
    fun `test 4 ui displays failed card above missing-content card with spinner on retry and hides skipped or resolved pages`() {
        val db = openInMemoryDb()
        val docId = "doc-m18-ui"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m18_vault_ui", Context.MODE_PRIVATE) },
        )

        runBlocking {
            seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 4)
            db.pageDao().updatePageFailureState("${docId}_page_2", PageStatus.FAILED.name, PageFailureReasons.TIMEOUT)
            db.pageDao().updatePageFailureState("${docId}_page_4", PageStatus.FAILED.name, PageFailureReasons.QUOTA)
            db.pageDao().updatePageMissingContent("${docId}_page_3", true)
            vault.addKey("UiKey", "AIzaSyM18UiTestKey4444")
        }

        val retryGate = CompletableDeferred<Unit>()
        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                retryGate.await()
                return Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("نص بديل")
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(4),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )
        val viewModel = MuharrirViewModel(application, repo)
        val wordExportVm = WordExportViewModel(repo.wordExportDataGateway, ioDispatcher = Dispatchers.Main)
        viewModel.selectDocument(docId)

        composeRule.setContent {
            MuharrirTheme {
                val doc by viewModel.activeDocument.collectAsStateWithLifecycle()
                val pagesMissing by viewModel.pagesWithMissingContent.collectAsStateWithLifecycle()
                val reprocessingIds by viewModel.reprocessingMissingPageIds.collectAsStateWithLifecycle()
                val failedPages by viewModel.pagesWithFailedAnalysis.collectAsStateWithLifecycle()
                val retryingIds by viewModel.retryingPageIds.collectAsStateWithLifecycle()
                val ocrState by viewModel.ocrExecutionState.collectAsStateWithLifecycle()
                val geminiState by viewModel.geminiExecutionState.collectAsStateWithLifecycle()
                val keys by viewModel.geminiKeys.collectAsStateWithLifecycle()
                val stats by viewModel.geminiUsageStats.collectAsStateWithLifecycle()
                val notice by viewModel.workspaceNoticeAr.collectAsStateWithLifecycle()

                DocumentWorkspaceScreen(
                    document = doc,
                    stages = viewModel.getPlannedStages(doc),
                    preferredExportMode = ExportMode.MATCH_ORIGINAL,
                    backendConnectionState = BackendConnectionState.NotConfigured("محلي"),
                    ocrState = ocrState,
                    pagesWithMissingContent = pagesMissing,
                    reprocessingMissingPageIds = reprocessingIds,
                    pagesWithFailedAnalysis = failedPages,
                    retryingPageIds = retryingIds,
                    geminiState = geminiState,
                    geminiKeys = keys,
                    geminiUsageStats = stats,
                    previewBitmap = null,
                    previewPageIndex = 0,
                    isLoadingPreviewPage = false,
                    pageThumbnails = emptyMap(),
                    workspaceNoticeAr = notice,
                    onDismissNotice = viewModel::dismissWorkspaceNotice,
                    onRequestPreviewPage = { _, _, _ -> },
                    onRequestPageThumbnail = { _, _ -> },
                    onSelectOcrEngine = {},
                    onStartOrResumeOcr = { _, _, _, _ -> },
                    onPauseOcr = {},
                    onReprocessFailedPages = { _, _ -> },
                    onRetrySingleOcrPage = { _, _, _ -> },
                    onStartGeminiAnalysis = { _, _, _, _ -> },
                    onForceGeminiExtractionForPage = viewModel::forceGeminiExtractionForPage,
                    onRetryFailedPage = viewModel::retryPageAnalysis,
                    onSkipFailedPage = viewModel::skipPageAnalysis,
                    onRetryAllFailedPages = viewModel::retryAllFailed,
                    onPauseGeminiAnalysis = {},
                    wordExportViewModel = wordExportVm,
                    onOpenSettings = {},
                    onOpenReviewWorkspace = {},
                    onOpenReviewSummary = {},
                    onCreateNewDocument = {},
                    onReplaceDocument = {},
                    onRemoveDocument = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            viewModel.pagesWithFailedAnalysis.value.map { it.originalPageNumber } == listOf(2, 4) &&
                viewModel.pagesWithMissingContent.value.map { it.originalPageNumber } == listOf(3)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("missing_content_audit_card"))
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("failed_analysis_card"))

        val failedCard = composeRule.onNodeWithTag("failed_analysis_card")
        failedCard.assertIsDisplayed()
        composeRule.onNodeWithTag("failed_analysis_text")
            .assertTextContains("تعذر تحليل الصفحات التالية: [2، 4]")
        composeRule.onNodeWithTag("retry_all_failed_button").assertIsDisplayed()

        val failedBounds = failedCard.fetchSemanticsNode().boundsInRoot
        val missingBounds = composeRule.onNodeWithTag("missing_content_audit_card")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Expected failed analysis card to appear above missing content card",
            failedBounds.bottom <= missingBounds.top + 1f,
        )

        // Retry page 2 -> shows spinner while in flight
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("failed_page_retry_button_2"))
        composeRule.onNodeWithTag("failed_page_retry_button_2").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            ShadowLooper.idleMainLooper()
            viewModel.retryingPageIds.value.contains(2)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("failed_page_spinner_2").assertIsDisplayed()

        // Complete retry -> page 2 removed and marked REVIEWED
        retryGate.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            viewModel.pagesWithFailedAnalysis.value.map { it.originalPageNumber } == listOf(4) &&
                viewModel.retryingPageIds.value.isEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("failed_page_retry_button_2").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("failed_analysis_text"))
        composeRule.onNodeWithTag("failed_analysis_text")
            .assertTextContains("تعذر تحليل الصفحات التالية: [4]")

        // Skip page 4 -> removed from failed list and card disappears cleanly
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("failed_page_skip_button_4"))
        composeRule.onNodeWithTag("failed_page_skip_button_4").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            viewModel.pagesWithFailedAnalysis.value.isEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("failed_analysis_card").assertDoesNotExist()

        db.close()
    }

    @Test
    fun `test 5 timeout marks page FAILED with timeout reason after 2 retries without aborting batch`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-m18-timeout"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }
        seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 2)

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m18_vault_timeout", Context.MODE_PRIVATE) },
        )
        vault.addKey("TimeoutKey", "AIzaSyM18TimeoutTestKey555")

        var directTimeoutAttempts = 0
        val slowDirectClient = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                directTimeoutAttempts++
                delay(5_000L)
                return Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("")
        }

        // 1. Coordinator-level timeout + 2 attempts verification (virtual time in runTest)
        val fastTimeoutCoord = GeminiAnalysisCoordinator(
            keyVault = vault,
            client = slowDirectClient,
            pageTimeoutMillis = 500L,
            backoffDelayMillis = {},
        )
        val page1Record = db.pageDao().getPagesForDocument(docId).first().let {
            PageRecord(
                pageId = it.pageId,
                documentId = it.documentId,
                originalPageNumber = it.originalPageNumber,
                imageRef = it.renderingReference,
                status = PageStatus.OCR_DONE,
                ocrResult = com.example.domain.model.OcrResult(
                    pageId = it.pageId,
                    documentId = docId,
                    pageNumber = 1,
                    recognizedText = "نص عربي تجريبي",
                    segments = emptyList(),
                    boundingBoxes = emptyList(),
                    confidence = 0.85f,
                    engineType = OcrEngineType.TESSERACT,
                ),
            )
        }
        val sampleBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val coordOutcome = fastTimeoutCoord.analyzePageWithFailover(page1Record, sampleBmp)
        sampleBmp.recycle()

        assertTrue(coordOutcome is GeminiPageAnalysisOutcome.Failure)
        val timeoutFailure = coordOutcome as GeminiPageAnalysisOutcome.Failure
        assertEquals(2, directTimeoutAttempts)
        assertEquals(2, timeoutFailure.attemptsMade)
        assertEquals(PageFailureReasons.TIMEOUT, timeoutFailure.failureReason)

        // 2. Repository batch-level test: page 1 times out, page 2 completes normally without deadlock
        var page1Calls = 0
        val mixedBatchClient = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                return if (payload.pageNumber == 1) {
                    page1Calls++
                    delay(1_200L)
                    Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
                } else {
                    Result.success(validEmptyJson(payload.pageId, payload.pageNumber))
                }
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ) = Result.success("")
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(2),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(
                keyVault = vault,
                client = mixedBatchClient,
                pageTimeoutMillis = 150L,
                backoffDelayMillis = {},
            ),
        )

        val batchOutcome = repo.runGeminiAnalysis(docId) as GeminiRunOutcome.Completed
        assertEquals(1, batchOutcome.analyzedNow)
        assertEquals(1, batchOutcome.failedPages)
        assertEquals(2, page1Calls)

        val pagesAfter = repo.getDocumentById(docId)!!.pages.associateBy { it.originalPageNumber }
        assertEquals(PageStatus.FAILED, pagesAfter[1]?.status)
        assertEquals(PageFailureReasons.TIMEOUT, pagesAfter[1]?.failureReason)
        assertEquals(PageStatus.ANALYZED, pagesAfter[2]?.status)
        assertNull(pagesAfter[2]?.failureReason)

        db.close()
    }

    private fun validEmptyJson(pageId: String, pageNumber: Int): String =
        """{"page_id":"$pageId","page_number":$pageNumber,"isMissingLargeContent":false,"items":[]}"""

    private suspend fun seedDocAndPages(
        db: MuharrirDatabase,
        docId: String,
        pdfPath: String,
        pageCount: Int,
    ) {
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "كتاب_الفقه.pdf",
                internalStoragePath = pdfPath,
                fileSizeBytes = 2048L,
                importDateMillis = 1000L,
                lastModifiedMillis = 1000L,
                pageCount = pageCount,
                indexedPagesCount = pageCount,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 100,
                isValidationPassed = true,
                validationNote = null,
                errorMessageAr = null,
            ),
        )
        val pages = (1..pageCount).map { num ->
            PageEntity(
                pageId = "${docId}_page_$num",
                documentId = docId,
                originalPageNumber = num,
                widthPoints = 595,
                heightPoints = 842,
                renderingReference = pdfPath,
                thumbnailCachePath = null,
                pageStatus = PageStatus.OCR_DONE.name,
                ocrStatus = OcrPageStatus.DONE.name,
                ocrEngineUsed = OcrEngineType.TESSERACT.name,
            )
        }
        db.pageDao().insertPages(pages)

        for (num in 1..pageCount) {
            db.ocrResultDao().upsertOcrResult(
                OcrResultEntity(
                    pageId = "${docId}_page_$num",
                    documentId = docId,
                    pageNumber = num,
                    engineType = OcrEngineType.TESSERACT.name,
                    recognizedText = "نص الفقه القديم في صفحة $num",
                    segmentsJson = "[]",
                    confidence = 0.85f,
                    imageWidthPx = 400,
                    imageHeightPx = 600,
                    recognizedAtMillis = 1000L,
                ),
            )
        }
    }
}
