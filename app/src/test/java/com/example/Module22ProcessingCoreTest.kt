package com.example

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.pdf.BitmapSafety
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfInspector
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.ImportOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.repository.OcrRunOutcome
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.ocr.OcrEngineRegistry
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrResult
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.domain.ocr.OcrEngineAdapter
import com.example.export.docx.WordGenerator
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportDataGateway
import com.example.export.model.WordExportMode
import com.example.export.ui.WordExportViewModel
import com.example.review.model.BoundingBox
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.DocumentReviewGateway
import com.example.review.model.DocumentReviewInfo
import com.example.review.model.PageBitmapSource
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewQueueGateway
import com.example.review.model.ReviewStatusCounts
import com.example.review.ui.ReviewViewModel
import com.example.service.ProcessingForegroundService
import com.example.service.ProcessingStateBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module22ProcessingCoreTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_m22.db"
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        ProcessingStateBridge.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.cacheDir, "muharrir_thumbs").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        ProcessingStateBridge.resetForTests()
        Dispatchers.resetMain()
        context.deleteDatabase(testDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.cacheDir, "muharrir_thumbs").deleteRecursively()
        PdfInspector.clearMemoryCache()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

    @Test
    fun `downsampling reduces 4000x4000 bitmap to 2500x2500 and recycles original`() {
        val oversized = Bitmap.createBitmap(4000, 4000, Bitmap.Config.ARGB_8888)
        val downsampled = BitmapSafety.downsampleToMaxDimension(
            bitmap = oversized,
            maxDimensionPx = 2500,
            recycleOriginal = true,
        )

        assertEquals(2500, downsampled.width)
        assertEquals(2500, downsampled.height)
        assertTrue(oversized.isRecycled)
        assertFalse(downsampled.isRecycled)
        downsampled.recycle()

        val wideBitmap = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)
        val scaledWide = BitmapSafety.downsampleToMaxDimension(
            bitmap = wideBitmap,
            maxDimensionPx = 2500,
            recycleOriginal = true,
        )
        assertEquals(2500, scaledWide.width)
        assertEquals(1250, scaledWide.height)
        assertTrue(wideBitmap.isRecycled)
        scaledWide.recycle()

        val safeBitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val unchanged = BitmapSafety.downsampleToMaxDimension(
            bitmap = safeBitmap,
            maxDimensionPx = 2500,
            recycleOriginal = true,
        )
        assertSame(safeBitmap, unchanged)
        assertFalse(safeBitmap.isRecycled)
        safeBitmap.recycle()
    }

    @Test
    fun `word export runs strictly on Dispatchers IO without blocking main thread and rejects duplicate triggers`() =
        runBlockingMainSafeTest {
            val exportStartedLatch = CountDownLatch(1)
            val allowExportFinishLatch = CountDownLatch(1)
            val fetchCalls = AtomicInteger(0)
            val exportThreadNameRef = java.util.concurrent.atomic.AtomicReference("")
            val mainThreadName = Thread.currentThread().name

            val gateway = object : WordExportDataGateway {
                override suspend fun getDocument(documentId: String): ExportDocumentModel {
                    return ExportDocumentModel(
                        id = documentId,
                        title = "كتاب الأصول",
                        reviewApprovedAt = 123456L,
                    )
                }

                override suspend fun getPagesInOrder(documentId: String): List<DocumentPageModel> {
                    fetchCalls.incrementAndGet()
                    exportThreadNameRef.set(Thread.currentThread().name)
                    exportStartedLatch.countDown()
                    allowExportFinishLatch.await(5, TimeUnit.SECONDS)
                    return listOf(
                        DocumentPageModel(
                            id = "${documentId}_p1",
                            documentId = documentId,
                            pageNumber = 1,
                            ocrText = "نص الصفحة الأولى من الكتاب.",
                        ),
                    )
                }

                override suspend fun getReviewItemsInOrder(documentId: String): List<com.example.review.model.ReviewItemModel> =
                    emptyList()

                override suspend fun shouldHighlightUnresolved(): Boolean = true

                override suspend fun shouldShowExportWarnings(): Boolean = false

                override suspend fun recordExportedDocument(
                    documentId: String,
                    docxFilePath: String,
                    mode: WordExportMode,
                    exportedAtMillis: Long,
                ) = Unit
            }

            val viewModel = WordExportViewModel(
                dataGateway = gateway,
                wordGenerator = WordGenerator(),
                ioDispatcher = Dispatchers.IO,
            )

            viewModel.exportDocument(context, "doc-export-io", WordExportMode.MATCH_ORIGINAL, false)
            assertTrue("Expected isExporting immediately", viewModel.uiState.value.isExporting)

            // Duplicate call while already exporting must be ignored
            viewModel.exportDocument(context, "doc-export-io", WordExportMode.MATCH_ORIGINAL, false)
            viewModel.requestExport("doc-export-io")

            // Drain Main dispatcher scheduler until the background IO export begins (getDocument -> Main -> getPagesInOrder on IO)
            val startDeadline = System.currentTimeMillis() + 3_000L
            while (exportStartedLatch.count > 0L && System.currentTimeMillis() < startDeadline) {
                mainDispatcher.scheduler.runCurrent()
                Thread.sleep(10)
            }

            assertTrue(
                "Export should start on IO dispatcher quickly",
                exportStartedLatch.await(100, TimeUnit.MILLISECONDS),
            )
            assertTrue(
                "Main thread was not blocked while IO export waited",
                viewModel.uiState.value.isExporting,
            )
            assertTrue(
                "Expected Apache POI / gateway execution off the Main dispatcher thread, got ${exportThreadNameRef.get()}",
                exportThreadNameRef.get() != mainThreadName,
            )
            assertEquals("Duplicate export trigger must not invoke gateway twice", 1, fetchCalls.get())

            allowExportFinishLatch.countDown()

            val deadline = System.currentTimeMillis() + 5_000L
            while (viewModel.uiState.value.isExporting && System.currentTimeMillis() < deadline) {
                mainDispatcher.scheduler.runCurrent()
                Thread.sleep(20)
            }
            mainDispatcher.scheduler.runCurrent()

            assertFalse("Export should finish cleanly", viewModel.uiState.value.isExporting)
            assertNotNull("DOCX file should be created", viewModel.uiState.value.lastExportedFile)
            assertTrue(viewModel.uiState.value.lastExportedFile!!.exists())
        }

    @Test
    fun `saf pdf import invokes takePersistableUriPermission and preserves sourceUri across db restart`() =
        runTest {
            val safUri = Uri.parse("content://com.example.mockdoc/document/kitab_42.pdf")
            val pdfBytes = "%PDF-1.4\n%Sample PDF stream\n%%EOF".toByteArray()
            val backedFile = File(context.cacheDir, "mock_saf_source.pdf").apply {
                writeBytes(pdfBytes)
            }

            var capturedPermissionUri: Uri? = null
            var capturedPermissionFlags = 0

            val spyResolver = object : ContentResolver(context) {
                override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
                    capturedPermissionUri = uri
                    capturedPermissionFlags = modeFlags
                    super.takePersistableUriPermission(uri, modeFlags)
                }
            }

            val spyContext = object : ContextWrapper(context) {
                override fun getContentResolver(): ContentResolver = spyResolver
            }

            Shadows.shadowOf(spyResolver).registerInputStream(safUri, ByteArrayInputStream(pdfBytes))
            Shadows.shadowOf(context.contentResolver).registerInputStream(safUri, ByteArrayInputStream(pdfBytes))

            val fakeEngine = object : PdfPageEngine {
                override suspend fun indexPages(
                    pdfFile: File,
                    batchSize: Int,
                    onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
                ): PrivatePdfIndexResult {
                    val pages = listOf(IndexedPdfPageMeta(1, 595, 842))
                    onBatchIndexed(1, 1, pages)
                    return PrivatePdfIndexResult.Success(1, pages)
                }

                override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap? = null
            }

            val db1 = openTestDatabase()
            val repo1 = MuharrirRepository(db1, storage, pageEngine = fakeEngine)
            val outcome = repo1.importPdfFromUri(spyContext, safUri)
            assertTrue(outcome is ImportOutcome.Success)
            val importedDoc = (outcome as ImportOutcome.Success).document

            assertEquals(safUri, capturedPermissionUri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, capturedPermissionFlags)
            assertTrue(
                "Persisted URI permissions should retain read grant",
                spyResolver.persistedUriPermissions.any {
                    it.uri == safUri && it.isReadPermission
                },
            )
            assertEquals(safUri.toString(), importedDoc.originalFileRef)
            db1.close()

            // Simulate app/device restart by reopening Room and querying persisted document
            val db2 = openTestDatabase()
            val repo2 = MuharrirRepository(db2, storage, pageEngine = fakeEngine)
            val persistedAfterRestart = repo2.getDocumentById(importedDoc.id)
            assertNotNull(persistedAfterRestart)
            assertEquals(safUri.toString(), persistedAfterRestart!!.originalFileRef)

            // Verify fallback/descriptor reopen works post-restart
            ParcelFileDescriptor.open(backedFile, ParcelFileDescriptor.MODE_READ_ONLY).use { backedPfd ->
                val afd = AssetFileDescriptor(backedPfd, 0, pdfBytes.size.toLong())
                assertNotNull(afd)
            }
            val reopenedDescriptor = repo2.reopenOriginalPdfDescriptor(context, importedDoc.id)
            assertNotNull("Should reopen PDF descriptor cleanly after restart", reopenedDescriptor)
            reopenedDescriptor?.close()

            db2.close()
            backedFile.delete()
        }

    @Test
    fun `database migration 7 to 8 preserves documents and adds nullable sourceUri column`() = runTest {
        val migDbName = "test_mig_7_8.db"
        context.deleteDatabase(migDbName)

        val sqlitePath = context.getDatabasePath(migDbName).apply { parentFile?.mkdirs() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(sqlitePath, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `documents` (
                `id` TEXT NOT NULL,
                `originalFileName` TEXT NOT NULL,
                `internalStoragePath` TEXT NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL,
                `importDateMillis` INTEGER NOT NULL,
                `lastModifiedMillis` INTEGER NOT NULL,
                `pageCount` INTEGER,
                `indexedPagesCount` INTEGER NOT NULL,
                `processingStatus` TEXT NOT NULL,
                `importStatus` TEXT NOT NULL,
                `importProgressPercent` INTEGER NOT NULL,
                `processingProgressPercent` INTEGER NOT NULL,
                `isValidationPassed` INTEGER NOT NULL,
                `validationNote` TEXT,
                `errorMessageAr` TEXT,
                `reviewApprovedAt` INTEGER,
                `exportedDocxPath` TEXT,
                `exportedMode` TEXT,
                `exportedAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pages` (
                `pageId` TEXT NOT NULL PRIMARY KEY,
                `documentId` TEXT NOT NULL,
                `originalPageNumber` INTEGER NOT NULL,
                `widthPoints` INTEGER,
                `heightPoints` INTEGER,
                `renderingReference` TEXT NOT NULL,
                `thumbnailCachePath` TEXT,
                `pageStatus` TEXT NOT NULL,
                `ocrStatus` TEXT NOT NULL DEFAULT 'PENDING',
                `ocrEngineUsed` TEXT,
                `ocrErrorMessageAr` TEXT,
                `ocrUpdatedAtMillis` INTEGER,
                `hasMissingContent` INTEGER NOT NULL DEFAULT 0,
                `failureReason` TEXT,
                FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_documentId_originalPageNumber` ON `pages` (`documentId`, `originalPageNumber`)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ocr_results` (
                `pageId` TEXT NOT NULL PRIMARY KEY,
                `documentId` TEXT NOT NULL,
                `pageNumber` INTEGER NOT NULL,
                `engineType` TEXT NOT NULL,
                `recognizedText` TEXT NOT NULL,
                `segmentsJson` TEXT NOT NULL,
                `confidence` REAL,
                `imageWidthPx` INTEGER NOT NULL,
                `imageHeightPx` INTEGER NOT NULL,
                `recognizedAtMillis` INTEGER NOT NULL,
                FOREIGN KEY(`pageId`) REFERENCES `pages`(`pageId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_results_documentId_pageNumber` ON `ocr_results` (`documentId`, `pageNumber`)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gemini_page_analyses` (
                `pageId` TEXT NOT NULL PRIMARY KEY,
                `documentId` TEXT NOT NULL,
                `pageNumber` INTEGER NOT NULL,
                `modelId` TEXT NOT NULL,
                `usedKeyLabel` TEXT NOT NULL,
                `rawValidatedJson` TEXT NOT NULL,
                `itemsCount` INTEGER NOT NULL,
                `analyzedAtMillis` INTEGER NOT NULL,
                FOREIGN KEY(`pageId`) REFERENCES `pages`(`pageId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_gemini_page_analyses_documentId_pageNumber` ON `gemini_page_analyses` (`documentId`, `pageNumber`)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `review_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `document_id` TEXT NOT NULL,
                `page_id` TEXT NOT NULL,
                `ocr_result_id` TEXT,
                `gemini_analysis_id` TEXT,
                `page_number` INTEGER NOT NULL,
                `x1` INTEGER NOT NULL,
                `y1` INTEGER NOT NULL,
                `x2` INTEGER NOT NULL,
                `y2` INTEGER NOT NULL,
                `ocr_text` TEXT NOT NULL,
                `suggested_text` TEXT,
                `ocr_confidence` REAL,
                `gemini_confidence` REAL,
                `category` TEXT NOT NULL,
                `priority` INTEGER NOT NULL,
                `review_status` TEXT NOT NULL,
                `user_text` TEXT,
                `evidence` TEXT NOT NULL,
                `reason_type` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                `block_index` INTEGER NOT NULL,
                `line_index` INTEGER NOT NULL,
                `element_index` INTEGER NOT NULL,
                `stale_note_ar` TEXT,
                FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`page_id`) REFERENCES `pages`(`pageId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`ocr_result_id`) REFERENCES `ocr_results`(`pageId`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`gemini_analysis_id`) REFERENCES `gemini_page_analyses`(`pageId`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_document_id` ON `review_items` (`document_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_page_id` ON `review_items` (`page_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_review_status` ON `review_items` (`review_status`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_priority` ON `review_items` (`priority`)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `preferences` (
                `singletonId` INTEGER NOT NULL PRIMARY KEY,
                `preferredOcrEngine` TEXT NOT NULL,
                `preferredExportMode` TEXT NOT NULL,
                `highlightUnresolvedInDocx` INTEGER NOT NULL,
                `autoSaveCheckpoints` INTEGER NOT NULL,
                `resumeAfterInterruption` INTEGER NOT NULL,
                `batchSizePages` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `api_key_slots` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `label` TEXT NOT NULL,
                `isEnabled` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL("INSERT INTO documents VALUES ('v7-doc', 'كتاب_المتن.pdf', '/tmp/v7.pdf', 1024, 10, 20, 1, 1, 'NEW', 'READY_FOR_PROCESSING', 100, 0, 1, NULL, NULL, NULL, NULL, NULL, NULL)")
        sqlite.execSQL("INSERT INTO pages VALUES ('v7-doc_page_1', 'v7-doc', 1, 595, 842, '/tmp/v7.pdf', NULL, 'PENDING', 'PENDING', NULL, NULL, NULL, 0, NULL)")
        sqlite.version = 7
        sqlite.close()

        val migrated = Room.databaseBuilder(context, MuharrirDatabase::class.java, migDbName)
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

        val docEntity = migrated.documentDao().getDocumentById("v7-doc")
        assertNotNull(docEntity)
        assertEquals("كتاب_المتن.pdf", docEntity!!.originalFileName)
        assertEquals(null, docEntity.sourceUri)
        migrated.close()
        context.deleteDatabase(migDbName)
    }

    @Test
    fun `ocr and gemini processing recycle every rendered bitmap immediately after page processing`() =
        runTest {
            val db = openTestDatabase()
            val docId = "doc-recycle-loop"
            val pdfFile = storage.getPrivatePdfFile(docId).apply {
                parentFile?.mkdirs()
                writeBytes("%PDF-1.4\n%test".toByteArray())
            }

            db.documentDao().upsertDocument(
                DocumentEntity(
                    id = docId,
                    originalFileName = "المخطوط.pdf",
                    internalStoragePath = pdfFile.absolutePath,
                    sourceUri = null,
                    fileSizeBytes = pdfFile.length(),
                    importDateMillis = 100L,
                    lastModifiedMillis = 100L,
                    pageCount = 2,
                    indexedPagesCount = 2,
                    processingStatus = DocumentStatus.NEW.name,
                    importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                    importProgressPercent = 100,
                    processingProgressPercent = 0,
                    isValidationPassed = true,
                    validationNote = null,
                    errorMessageAr = null,
                ),
            )
            db.pageDao().replaceDocumentPages(
                docId,
                listOf(
                    PageEntity("${docId}_page_1", docId, 1, 595, 842, pdfFile.absolutePath, null, PageStatus.PENDING.name),
                    PageEntity("${docId}_page_2", docId, 2, 595, 842, pdfFile.absolutePath, null, PageStatus.PENDING.name),
                ),
            )

            val renderedBitmaps = mutableListOf<Bitmap>()
            val bitmapSizesObservedByOcr = mutableListOf<Pair<Int, Int>>()

            val oversizedPageEngine = object : PdfPageEngine {
                override suspend fun indexPages(
                    pdfFile: File,
                    batchSize: Int,
                    onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
                ): PrivatePdfIndexResult = PrivatePdfIndexResult.Failure("unused")

                override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap {
                    // Return an oversized 3000x3000 bitmap to verify both downsampling and recycling
                    return Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888).also {
                        renderedBitmaps.add(it)
                    }
                }
            }

            val fakeOcrAdapter = object : OcrEngineAdapter {
                override val engineType: OcrEngineType = OcrEngineType.TESSERACT
                override val supportsArabicScript: Boolean = true
                override val capabilityNoteAr: String = "اختبار"
                override suspend fun checkAvailability(): OcrAvailabilityResult = OcrAvailabilityResult.Ready
                override suspend fun recognizePage(
                    documentId: String,
                    pageId: String,
                    pageNumber: Int,
                    pageBitmap: Bitmap,
                ): Result<OcrResult> {
                    bitmapSizesObservedByOcr.add(pageBitmap.width to pageBitmap.height)
                    return Result.success(
                        OcrResult(
                            pageId = pageId,
                            documentId = documentId,
                            pageNumber = pageNumber,
                            recognizedText = "نص طويل واضح من الصفحة رقم $pageNumber يحتوي على عدة كلمات عربية متصلة.",
                            segments = emptyList(),
                            boundingBoxes = emptyList(),
                            confidence = 0.9f,
                            engineType = OcrEngineType.TESSERACT,
                            imageWidthPx = pageBitmap.width,
                            imageHeightPx = pageBitmap.height,
                        ),
                    )
                }
            }
            val registry = OcrEngineRegistry(mapOf(OcrEngineType.TESSERACT to fakeOcrAdapter))

            val vaultPrefsName = "m22_test_vault_prefs"
            context.deleteSharedPreferences(vaultPrefsName)
            val fakeVault = EncryptedGeminiApiKeyVault(
                prefsProvider = { context.getSharedPreferences(vaultPrefsName, Context.MODE_PRIVATE) },
            )
            fakeVault.addKey("testKey", "AIzaTestKey1234567890")

            val fakeGeminiClient = object : GeminiPageClient {
                override suspend fun analyzePage(
                    apiKey: String,
                    modelId: String,
                    pageBitmap: Bitmap,
                    payload: GeminiPagePromptPayload,
                ): Result<String> {
                    return Result.success(
                        """{"page_id":"${payload.pageId}","page_number":${payload.pageNumber},"isMissingLargeContent":false,"items":[]}""",
                    )
                }

                override suspend fun extractPlainText(
                    apiKey: String,
                    modelId: String,
                    pageBitmap: Bitmap,
                    prompt: String,
                ): Result<String> = Result.success("نص مستخرج")

                override suspend fun validateKey(apiKey: String, modelId: String): Result<Unit> = Result.success(Unit)
            }

            val coordinator = GeminiAnalysisCoordinator(fakeVault, fakeGeminiClient)
            val repo = MuharrirRepository(
                database = db,
                storage = storage,
                pageEngine = oversizedPageEngine,
                ocrEngineRegistry = registry,
                geminiKeyVault = fakeVault,
                geminiCoordinator = coordinator,
            )

            val ocrOutcome = repo.runDocumentOcr(context, docId, OcrEngineType.TESSERACT)
            assertTrue(ocrOutcome is OcrRunOutcome.Completed)
            assertEquals(2, renderedBitmaps.size)
            assertTrue(
                "All bitmaps passed to OCR preprocessor must have been downsampled <= 2500px",
                bitmapSizesObservedByOcr.all { (w, h) -> w <= 2500 && h <= 2500 },
            )
            assertTrue(
                "Every rendered bitmap from OCR loop must be recycled",
                renderedBitmaps.all { it.isRecycled },
            )

            renderedBitmaps.clear()
            repo.runGeminiAnalysis(docId)
            assertEquals(2, renderedBitmaps.size)
            assertTrue(
                "Every rendered bitmap from Gemini loop must be recycled",
                renderedBitmaps.all { it.isRecycled },
            )
            db.close()
        }

    @Test
    fun `review viewModel retains only active page bitmap and recycles previous bitmaps`() = runTest {
        val page1Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val page2Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)

        val item1 = ReviewItemModel(
            id = 1L,
            documentId = "doc-rv",
            pageNumber = 1,
            category = DiscrepancyCategory.B,
            ocrText = "الكلمة",
            suggestedText = "الكلمةُ",
            ocrConfidence = 0.7f,
            geminiConfidence = 0.9f,
            evidence = "واضحة",
            bbox = BoundingBox(10f, 10f, 100f, 80f),
            reviewStatus = ReviewStatus.PENDING,
            userText = null,
            resolvedAt = null,
            isStale = false,
            staleNoteAr = null,
            orderIndex = 0,
        )
        val item2 = item1.copy(id = 2L, pageNumber = 2, orderIndex = 1)

        val queueFlow = MutableStateFlow(listOf(item1, item2))
        val queueGateway = object : ReviewQueueGateway {
            override fun getReviewQueue(documentId: String) = queueFlow
            override fun countByStatus(documentId: String) = flowOf(ReviewStatusCounts(total = 2, remaining = 2))
            override suspend fun getNextPending(documentId: String, afterItemId: Long) = item2
            override suspend fun saveDecision(itemId: Long, newStatus: ReviewStatus, userText: String?, resolvedAt: Long) = Unit
            override suspend fun reopenItemAsPending(itemId: Long) = Unit
            override suspend fun approveAllPendingGemini(documentId: String, itemIds: List<Long>, resolvedAt: Long) = Unit
        }
        val docGateway = object : DocumentReviewGateway {
            override fun observeDocument(documentId: String) =
                flowOf(DocumentReviewInfo(documentId, "كتاب.pdf", null))
            override suspend fun setReviewApprovedAt(documentId: String, approvedAtMillis: Long) = Unit
        }
        val bitmapSource = object : PageBitmapSource {
            override suspend fun loadPageBitmap(documentId: String, pageNumber: Int): Bitmap? {
                return if (pageNumber == 1) page1Bitmap else page2Bitmap
            }
        }

        val vm = ReviewViewModel(
            documentId = "doc-rv",
            reviewQueueGateway = queueGateway,
            documentReviewGateway = docGateway,
            pageBitmapSource = bitmapSource,
            ioDispatcher = mainDispatcher,
            defaultDispatcher = mainDispatcher,
        )
        advanceUntilIdle()

        assertSame(page1Bitmap, vm.uiState.value.pageBitmap)
        assertFalse(page1Bitmap.isRecycled)

        // Move to item2 on page 2
        vm.goToNext()
        advanceUntilIdle()

        assertSame(page2Bitmap, vm.uiState.value.pageBitmap)
        assertTrue("Previous page 1 bitmap should be recycled once page 2 is active", page1Bitmap.isRecycled)
        assertFalse(page2Bitmap.isRecycled)

        // Simulate ViewModel clearing via reflection since onCleared is protected
        val onClearedMethod = vm::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)

        assertTrue("Active page 2 bitmap must be recycled on ViewModel clear", page2Bitmap.isRecycled)
    }

    @Test
    fun `foreground service formats Arabic progress notification with cancel action and stops cleanly on cancel`() =
        runBlockingMainSafeTest {
            val controller = Robolectric.buildService(ProcessingForegroundService::class.java)
            val service = controller.create().get()
            service.overrideIoDispatcherForTesting(UnconfinedTestDispatcher())

            assertEquals(
                "جاري معالجة الصفحة 3 من 10",
                ProcessingForegroundService.formatPageProgressText(3, 10),
            )

            val notif = service.buildNotification(
                contentText = ProcessingForegroundService.formatPageProgressText(2, 5),
                completed = 2,
                total = 5,
            )
            assertEquals("جاري معالجة الصفحة 2 من 5", NotificationCompat.getContentText(notif))
            assertTrue(
                "Notification must expose Arabic cancel action",
                notif.actions.any { it.title?.toString() == "إلغاء" },
            )

            val db = openTestDatabase()
            val docId = "doc-fgs-cancel"
            val pdfFile = storage.getPrivatePdfFile(docId).apply {
                parentFile?.mkdirs()
                writeBytes("%PDF-1.4\n%test".toByteArray())
            }
            db.documentDao().upsertDocument(
                DocumentEntity(
                    id = docId,
                    originalFileName = "مستند_خدمة.pdf",
                    internalStoragePath = pdfFile.absolutePath,
                    sourceUri = null,
                    fileSizeBytes = pdfFile.length(),
                    importDateMillis = 100L,
                    lastModifiedMillis = 100L,
                    pageCount = 2,
                    indexedPagesCount = 2,
                    processingStatus = DocumentStatus.NEW.name,
                    importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                    importProgressPercent = 100,
                    processingProgressPercent = 0,
                    isValidationPassed = true,
                    validationNote = null,
                    errorMessageAr = null,
                ),
            )
            db.pageDao().replaceDocumentPages(
                docId,
                listOf(
                    PageEntity("${docId}_page_1", docId, 1, 595, 842, pdfFile.absolutePath, null, PageStatus.PENDING.name),
                    PageEntity("${docId}_page_2", docId, 2, 595, 842, pdfFile.absolutePath, null, PageStatus.PENDING.name),
                ),
            )

            val page1Started = CountDownLatch(1)
            val blockingAdapter = object : OcrEngineAdapter {
                override val engineType = OcrEngineType.TESSERACT
                override val supportsArabicScript: Boolean = true
                override val capabilityNoteAr: String = "اختبار"
                override suspend fun checkAvailability() = OcrAvailabilityResult.Ready
                override suspend fun recognizePage(
                    documentId: String,
                    pageId: String,
                    pageNumber: Int,
                    pageBitmap: Bitmap,
                ): Result<OcrResult> {
                    page1Started.countDown()
                    delay(5_000)
                    return Result.success(
                        OcrResult(
                            pageId = pageId,
                            documentId = documentId,
                            pageNumber = pageNumber,
                            recognizedText = "نص",
                            segments = emptyList(),
                            boundingBoxes = emptyList(),
                            confidence = 0.9f,
                            engineType = OcrEngineType.TESSERACT,
                            imageWidthPx = 100,
                            imageHeightPx = 100,
                        ),
                    )
                }
            }
            val fakeEngine = object : PdfPageEngine {
                override suspend fun indexPages(
                    pdfFile: File,
                    batchSize: Int,
                    onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
                ) = PrivatePdfIndexResult.Failure("unused")

                override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap =
                    Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            }
            val repo = MuharrirRepository(
                database = db,
                storage = storage,
                pageEngine = fakeEngine,
                ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.TESSERACT to blockingAdapter)),
            )
            ProcessingStateBridge.setRepositoryProvider { repo }

            val startIntent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ProcessingForegroundService.ACTION_START_OCR
                putExtra(ProcessingForegroundService.EXTRA_DOCUMENT_ID, docId)
                putExtra(ProcessingForegroundService.EXTRA_ENGINE_TYPE, OcrEngineType.TESSERACT.name)
            }
            service.onStartCommand(startIntent, 0, 1)

            assertTrue("Service should begin OCR loop", page1Started.await(3, TimeUnit.SECONDS))
            assertTrue(ProcessingStateBridge.ocrExecutionState.value.isRunning)

            val cancelIntent = Intent(context, ProcessingForegroundService::class.java).apply {
                action = ProcessingForegroundService.ACTION_CANCEL_PROCESSING
            }
            service.onStartCommand(cancelIntent, 0, 2)

            assertFalse(ProcessingStateBridge.ocrExecutionState.value.isRunning)
            assertTrue(
                ProcessingStateBridge.workspaceNoticeAr.value?.contains("إيقاف") == true,
            )

            controller.destroy()
            db.close()
        }

    private fun runBlockingMainSafeTest(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
