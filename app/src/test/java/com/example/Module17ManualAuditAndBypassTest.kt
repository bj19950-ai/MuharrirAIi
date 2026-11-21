package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.database.sqlite.SQLiteDatabase
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
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.gemini.GeminiPromptBuilder
import com.example.data.gemini.GeminiSchemaValidator
import com.example.data.gemini.GeminiValidationResult
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
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.export.ui.WordExportViewModel
import com.example.ui.screens.DocumentWorkspaceScreen
import com.example.ui.theme.MuharrirTheme
import com.example.ui.viewmodel.MuharrirViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module17ManualAuditAndBypassTest {

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
        context.deleteDatabase("m17_v5_migration_test.db")
        context.deleteSharedPreferences("test_m17_vault")
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
    fun `prompt instruction and schema validator handle isMissingLargeContent accurately`() {
        assertTrue(GeminiPromptBuilder.SYSTEM_INSTRUCTION.contains("isMissingLargeContent"))
        assertTrue(
            GeminiPromptBuilder.SYSTEM_INSTRUCTION.contains(
                "Does the OCR text miss entire paragraphs, sections, or headings",
            ),
        )

        val validTrue = GeminiSchemaValidator.validate(
            """
            {
              "page_id": "doc_page_1",
              "page_number": 1,
              "isMissingLargeContent": true,
              "items": []
            }
            """.trimIndent(),
        )
        assertTrue(validTrue is GeminiValidationResult.Valid)
        assertTrue((validTrue as GeminiValidationResult.Valid).response.isMissingLargeContent)

        val validOmittedDefaultsFalse = GeminiSchemaValidator.validate(
            """
            {
              "page_id": "doc_page_2",
              "page_number": 2,
              "items": []
            }
            """.trimIndent(),
        )
        assertTrue(validOmittedDefaultsFalse is GeminiValidationResult.Valid)
        assertFalse((validOmittedDefaultsFalse as GeminiValidationResult.Valid).response.isMissingLargeContent)

        val invalidType = GeminiSchemaValidator.validate(
            """
            {
              "page_id": "doc_page_3",
              "page_number": 3,
              "isMissingLargeContent": "yes",
              "items": []
            }
            """.trimIndent(),
        )
        assertTrue(invalidType is GeminiValidationResult.Malformed)
    }

    @Test
    fun `room migration 5 to 6 adds hasMissingContent column with default false`() = runTest {
        val dbName = "m17_v5_migration_test.db"
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
            "INSERT INTO documents VALUES ('v5-doc', 'مخطوط.pdf', '/tmp/v5.pdf', 1024, 100, 100, 1, 1, 'PROCESSING', 'READY_FOR_PROCESSING', 100, 50, 1, 'ok', NULL, NULL, NULL, NULL, NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO pages VALUES ('v5-doc_page_1', 'v5-doc', 1, 595, 842, '/tmp/v5.pdf', NULL, 'OCR_DONE', 'DONE', 'TESSERACT', NULL, 100)",
        )
        sqlite.version = 5
        sqlite.close()

        val migratedDb = Room.databaseBuilder(context, MuharrirDatabase::class.java, dbName)
            .addMigrations(
                MuharrirDatabase.MIGRATION_1_2,
                MuharrirDatabase.MIGRATION_2_3,
                MuharrirDatabase.MIGRATION_3_4,
                MuharrirDatabase.MIGRATION_4_5,
                MuharrirDatabase.MIGRATION_5_6,
                MuharrirDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()

        val repo = MuharrirRepository(migratedDb, storage)
        val doc = repo.getDocumentById("v5-doc")
        assertNotNull(doc)
        assertFalse(doc!!.pages.first().hasMissingContent)
        assertTrue(repo.getPagesWithMissingContent("v5-doc").isEmpty())
        migratedDb.close()
    }

    @Test
    fun `runGeminiAnalysis persists hasMissingContent without auto-extracting and forceGeminiExtractionForPage bypasses review queue`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-m17-repo"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }

        seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 3)

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m17_vault", Context.MODE_PRIVATE) },
        )
        vault.addKey("M17Key", "AIzaSyM17TestKey987654")

        var plainTextExtractionCalls = 0
        val extractedPage1FullText = "هذا هو النص الكامل للصفحة الأولى المستخرج عبر Gemini متضمناً العنوان والفقرات المفقودة."

        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)

            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                val missing = payload.pageNumber == 1 || payload.pageNumber == 3
                val itemsJson = if (payload.pageNumber <= 2) {
                    """
                    [
                      {
                        "ocr_text": "الفقه",
                        "suggested_text": "الفقهي",
                        "bbox": [10.0, 10.0, 80.0, 30.0],
                        "confidence": 0.85,
                        "evidence": "الياء ظاهرة بوضوح في نهاية الكلمة",
                        "reason_type": "visual",
                        "requires_review": true
                      }
                    ]
                    """.trimIndent()
                } else {
                    "[]"
                }
                return Result.success(
                    """
                    {
                      "page_id": "${payload.pageId}",
                      "page_number": ${payload.pageNumber},
                      "isMissingLargeContent": $missing,
                      "items": $itemsJson
                    }
                    """.trimIndent(),
                )
            }

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ): Result<String> {
                plainTextExtractionCalls++
                return Result.success(extractedPage1FullText)
            }
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(3),
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )

        val outcome = repo.runGeminiAnalysis(docId) as GeminiRunOutcome.Completed
        assertEquals(3, outcome.analyzedNow)
        assertEquals(0, plainTextExtractionCalls)

        val missingPages = repo.getPagesWithMissingContent(docId)
        assertEquals(listOf(1, 3), missingPages.map { it.originalPageNumber })

        val page1Id = "${docId}_page_1"
        val page2Id = "${docId}_page_2"
        assertTrue(db.reviewItemDao().getItemsForPage(page1Id).isNotEmpty())
        assertTrue(db.reviewItemDao().getItemsForPage(page2Id).isNotEmpty())

        // Manually trigger full extraction for page 1
        val forceResult = repo.forceGeminiExtractionForPage(docId, 1)
        assertTrue(forceResult.isSuccess)
        assertEquals(1, plainTextExtractionCalls)

        // Verify recognizedText overwritten, hasMissingContent cleared, status REVIEWED
        val updatedDoc = repo.getDocumentById(docId)!!
        val page1After = updatedDoc.pages.first { it.originalPageNumber == 1 }
        assertEquals(extractedPage1FullText, page1After.ocrResult?.recognizedText)
        assertFalse(page1After.hasMissingContent)
        assertEquals(PageStatus.REVIEWED, page1After.status)

        // CRITICAL: ALL review items for page 1 must be deleted and ZERO new review items created; page 2 untouched
        assertTrue(db.reviewItemDao().getItemsForPage(page1Id).isEmpty())
        assertTrue(db.reviewItemDao().getItemsForPage(page2Id).isNotEmpty())
        assertEquals(listOf(3), repo.getPagesWithMissingContent(docId).map { it.originalPageNumber })

        // Verify WordExportDataGateway also reflects page 1 with clean text and 0 review items
        val exportPage1 = repo.wordExportDataGateway.getPagesInOrder(docId)
            .first { it.pageNumber == 1 }
        assertEquals(extractedPage1FullText, exportPage1.ocrText)
        assertTrue(
            repo.wordExportDataGateway.getReviewItemsInOrder(docId)
                .none { it.pageNumber == 1 },
        )

        db.close()
    }

    @Test
    fun `workspace screen displays missing content card above Gemini card and removes page after reprocessing`() {
        val db = openInMemoryDb()
        val docId = "doc-m17-ui"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences("test_m17_vault", Context.MODE_PRIVATE) },
        )

        runBlocking {
            seedDocAndPages(db, docId, pdfFile.absolutePath, pageCount = 3)
            db.pageDao().updatePageMissingContent("${docId}_page_1", true)
            db.pageDao().updatePageMissingContent("${docId}_page_3", true)
            db.reviewItemDao().insertAll(
                listOf(
                    ReviewItemEntity(
                        documentId = docId,
                        pageId = "${docId}_page_1",
                        ocrResultId = "${docId}_page_1",
                        geminiAnalysisId = null,
                        pageNumber = 1,
                        x1 = 0,
                        y1 = 0,
                        x2 = 50,
                        y2 = 20,
                        ocrText = "نص ناقص",
                        suggestedText = "نص بديل",
                        ocrConfidence = 0.7f,
                        geminiConfidence = 0.8f,
                        category = ComparisonCategory.DISAGREEMENT.name,
                        priority = 1,
                        reviewStatus = ReviewStatus.PENDING.name,
                        userText = null,
                        evidence = "دليل",
                        reasonType = GeminiReasonType.VISUAL.wireValue,
                        createdAt = 1000L,
                        resolvedAt = null,
                    ),
                ),
            )
            vault.addKey("M17UiKey", "AIzaSyM17UiTestKey12345")
        }

        val extractionGate = CompletableDeferred<Unit>()
        val extractedText = "النص الكامل المستخرج يدوياً للصفحة الأولى."
        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ) = Result.success("""{"page_id":"${payload.pageId}","page_number":${payload.pageNumber},"isMissingLargeContent":false,"items":[]}""")

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ): Result<String> {
                extractionGate.await()
                return Result.success(extractedText)
            }
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakePdfEngine(3),
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

        // Wait until Room Flow delivers activeDocument and pagesWithMissingContent
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            viewModel.pagesWithMissingContent.value.size == 2
        }
        composeRule.waitForIdle()

        // Scroll to the MissingContentAuditCard and GeminiAnalysisWorkspaceCard
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("gemini_analysis_workspace_card"))
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("missing_content_audit_card"))

        val auditCardNode = composeRule.onNodeWithTag("missing_content_audit_card")
        auditCardNode.assertIsDisplayed()
        composeRule.onNodeWithTag("missing_content_audit_text")
            .assertTextContains("تحذير: اكتشف Gemini أن النص مفقود في هذه الصفحات: [1، 3]")

        val geminiCardBounds = composeRule.onNodeWithTag("gemini_analysis_workspace_card")
            .fetchSemanticsNode()
            .boundsInRoot
        val auditCardBounds = auditCardNode.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected missing content audit card to be rendered above Gemini card",
            auditCardBounds.bottom <= geminiCardBounds.top + 1f,
        )

        // Click "إعادة استخراج" for page 1 -> spinner displays while deferred is awaited
        composeRule.onNodeWithTag("missing_content_reprocess_button_1").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            ShadowLooper.idleMainLooper()
            viewModel.reprocessingMissingPageIds.value.contains("${docId}_page_1")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("missing_content_spinner_1").assertIsDisplayed()

        // Complete extraction -> page 1 removed from warning card and review items deleted
        extractionGate.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            viewModel.pagesWithMissingContent.value.map { it.originalPageNumber } == listOf(3) &&
                viewModel.reprocessingMissingPageIds.value.isEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("missing_content_reprocess_button_1").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("missing_content_reprocess_button_3"))
        composeRule.onNodeWithTag("missing_content_reprocess_button_3").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace_lazy_column")
            .performScrollToNode(hasTestTag("missing_content_audit_text"))
        composeRule.onNodeWithTag("missing_content_audit_text")
            .assertTextContains("تحذير: اكتشف Gemini أن النص مفقود في هذه الصفحات: [3]")
        runBlocking {
            assertTrue(db.reviewItemDao().getItemsForPage("${docId}_page_1").isEmpty())
        }

        db.close()
    }

    private suspend fun seedDocAndPages(
        db: MuharrirDatabase,
        docId: String,
        pdfPath: String,
        pageCount: Int,
    ) {
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "كتاب_المراجعة.pdf",
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
