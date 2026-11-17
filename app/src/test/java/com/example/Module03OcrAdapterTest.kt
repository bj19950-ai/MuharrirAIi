package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.PageEntity
import com.example.data.ocr.MlKitOcrEngine
import com.example.data.ocr.OcrEngineRegistry
import com.example.data.ocr.OcrImagePreprocessor
import com.example.data.ocr.TesseractAndroidOcrEngine
import com.example.data.ocr.TesseractDataInstaller
import com.example.data.ocr.TrainedDataInstallResult
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.MuharrirRepository
import com.example.data.repository.OcrRunOutcome
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.BoundingBox
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.model.PageStatus
import com.example.domain.model.PointCoord
import com.example.domain.model.ProcessingPreferences
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.domain.ocr.OcrEngineAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module03OcrAdapterTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_ocr.db"
    private val migrationDbName = "test_muharrir_migration_1_2.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.filesDir, "tessdata").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.filesDir, "tessdata").deleteRecursively()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

    private class FakePdfPageEngine(
        private val totalPages: Int = 4,
    ) : PdfPageEngine {
        override suspend fun indexPages(
            pdfFile: File,
            batchSize: Int,
            onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
        ): PrivatePdfIndexResult {
            val pages = (1..totalPages).map { IndexedPdfPageMeta(it, 595, 842) }
            onBatchIndexed(totalPages, totalPages, pages)
            return PrivatePdfIndexResult.Success(totalPages, pages)
        }

        override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap? {
            if (!pdfFile.exists() || pageIndex !in 0 until totalPages) return null
            return Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
        }
    }

    private class FakeOcrEngineAdapter(
        override val engineType: OcrEngineType = OcrEngineType.ML_KIT,
        override val supportsArabicScript: Boolean = true,
        private val failingPages: MutableSet<Int> = mutableSetOf(),
    ) : OcrEngineAdapter {
        override val capabilityNoteAr: String = "محوّل اختباري لمرحلة 03"
        val processedPageNumbers = mutableListOf<Int>()

        override suspend fun checkAvailability(): OcrAvailabilityResult = OcrAvailabilityResult.Ready

        override suspend fun recognizePage(
            documentId: String,
            pageId: String,
            pageNumber: Int,
            pageBitmap: Bitmap,
        ): Result<OcrResult> {
            processedPageNumbers.add(pageNumber)
            if (pageNumber in failingPages) {
                return Result.failure(IllegalStateException("فشل تجريبي في الصفحة $pageNumber"))
            }

            val blockId = "${pageId}_b0"
            val lineId = "${blockId}_l0"
            val elemBox = BoundingBox(left = 40f, top = 80f, right = 340f, bottom = 120f)
            val corners = listOf(
                PointCoord(40, 80),
                PointCoord(340, 80),
                PointCoord(340, 120),
                PointCoord(40, 120),
            )
            val segments = listOf(
                OcrSegment(
                    segmentId = blockId,
                    text = "فقرة قانونية — صفحة $pageNumber",
                    boundingBox = elemBox,
                    confidence = null,
                    level = OcrElementLevel.BLOCK,
                    cornerPoints = corners,
                    blockIndex = 0,
                    lineIndex = null,
                    elementIndex = null,
                    parentBlockId = blockId,
                    parentLineId = null,
                ),
                OcrSegment(
                    segmentId = lineId,
                    text = "المادة رقم $pageNumber من القانون.",
                    boundingBox = elemBox,
                    confidence = null, // Explicitly verify null confidence is never fabricated
                    level = OcrElementLevel.LINE,
                    cornerPoints = corners,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = null,
                    parentBlockId = blockId,
                    parentLineId = lineId,
                ),
                OcrSegment(
                    segmentId = "${lineId}_e0",
                    text = "المادة",
                    boundingBox = BoundingBox(240f, 80f, 340f, 120f),
                    confidence = null,
                    level = OcrElementLevel.ELEMENT,
                    cornerPoints = corners,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 0,
                    parentBlockId = blockId,
                    parentLineId = lineId,
                ),
            )

            return Result.success(
                OcrResult(
                    pageId = pageId,
                    documentId = documentId,
                    pageNumber = pageNumber,
                    recognizedText = "المادة رقم $pageNumber من القانون.",
                    segments = segments,
                    boundingBoxes = listOf(BoundingBox(240f, 80f, 340f, 120f)),
                    confidence = null, // Page-level null confidence must stay null
                    engineType = engineType,
                    imageWidthPx = pageBitmap.width,
                    imageHeightPx = pageBitmap.height,
                ),
            )
        }
    }

    private suspend fun seedDocument(
        db: MuharrirDatabase,
        docId: String = "doc-ocr-01",
        pageCount: Int = 4,
    ): File {
        val privateFile = storage.getPrivatePdfFile(docId)
        privateFile.parentFile?.mkdirs()
        privateFile.writeBytes("%PDF-1.5\n%sample".toByteArray())

        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "محاضرة_القانون.pdf",
                internalStoragePath = privateFile.absolutePath,
                fileSizeBytes = privateFile.length(),
                importDateMillis = 1_700_000_000_000L,
                lastModifiedMillis = 1_700_000_000_000L,
                pageCount = pageCount,
                indexedPagesCount = pageCount,
                processingStatus = DocumentStatus.NEW.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 0,
                isValidationPassed = true,
                validationNote = "مفهرس",
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
                renderingReference = privateFile.absolutePath,
                thumbnailCachePath = null,
                pageStatus = PageStatus.PENDING.name,
                ocrStatus = OcrPageStatus.PENDING.name,
            )
        }
        db.pageDao().insertPages(pages)
        return privateFile
    }

    @Test
    fun `preprocessor creates non-destructive in-memory copy without mutating source bitmap`() {
        val original = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 210, 210))
        }
        val prepared = OcrImagePreprocessor.prepareForOcr(original)
        assertNotSame(original, prepared.bitmap)
        assertEquals(120, prepared.widthPx)
        assertEquals(180, prepared.heightPx)
        assertFalse(original.isRecycled)
        assertFalse(prepared.bitmap.isRecycled)
        prepared.recycleSafely()
        assertTrue(prepared.bitmap.isRecycled)
        assertFalse(original.isRecycled)
        original.recycle()
    }

    @Test
    fun `ocr persistence preserves reading order bounding boxes corner points and null confidence across restarts`() = runTest {
        val db1 = openTestDatabase()
        seedDocument(db1, docId = "doc-ocr-persist", pageCount = 3)
        val fakeAdapter = FakeOcrEngineAdapter()
        val registry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to fakeAdapter))
        val repo1 = MuharrirRepository(
            database = db1,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 3),
            ocrEngineRegistry = registry,
        )

        val outcome = repo1.runDocumentOcr(context, "doc-ocr-persist", OcrEngineType.ML_KIT)
        assertTrue(outcome is OcrRunOutcome.Completed)
        val completed = outcome as OcrRunOutcome.Completed
        assertEquals(3, completed.processedNow)
        assertEquals(3, completed.totalDonePages)
        assertEquals(0, completed.totalFailedPages)
        db1.close()

        // Reopen database and repository to verify Room durability
        val db2 = openTestDatabase()
        val repo2 = MuharrirRepository(database = db2, storage = storage)
        val loaded = repo2.getDocumentById("doc-ocr-persist")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.pages.size)

        val page2 = loaded.pages[1]
        assertEquals(OcrPageStatus.DONE, page2.ocrStatus)
        assertEquals(PageStatus.OCR_DONE, page2.status)
        assertEquals(OcrEngineType.ML_KIT, page2.ocrEngineUsed)

        val ocr2 = page2.ocrResult
        assertNotNull(ocr2)
        assertEquals(2, ocr2!!.pageNumber)
        assertEquals("المادة رقم 2 من القانون.", ocr2.recognizedText)
        assertNull("Null confidence must remain null and never be fabricated", ocr2.confidence)
        assertEquals(3, ocr2.segments.size)

        val elem = ocr2.segments[2]
        assertEquals(OcrElementLevel.ELEMENT, elem.level)
        assertEquals(0, elem.blockIndex)
        assertEquals(0, elem.lineIndex)
        assertEquals(0, elem.elementIndex)
        assertEquals("doc-ocr-persist_page_2_b0", elem.parentBlockId)
        assertEquals("doc-ocr-persist_page_2_b0_l0", elem.parentLineId)
        assertEquals(4, elem.cornerPoints?.size)
        assertEquals(240f, elem.boundingBox?.left)
        assertNull(elem.confidence)

        db2.close()
    }

    @Test
    fun `resumability skips done pages recovers running pages and retries only failed pages`() = runTest {
        val db = openTestDatabase()
        seedDocument(db, docId = "doc-ocr-resume", pageCount = 4)

        val failingSet = mutableSetOf(2, 4)
        val firstAdapter = FakeOcrEngineAdapter(failingPages = failingSet)
        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 4),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to firstAdapter)),
        )

        val firstRun = repo.runDocumentOcr(context, "doc-ocr-resume", OcrEngineType.ML_KIT) as OcrRunOutcome.Completed
        assertEquals(2, firstRun.totalDonePages)
        assertEquals(2, firstRun.totalFailedPages)
        assertEquals(listOf(1, 2, 3, 4), firstAdapter.processedPageNumbers)

        // Simulate page 3 somehow stuck in RUNNING on crash & page 1 DONE, page 2 FAILED
        db.pageDao().updatePageOcrState(
            pageId = "doc-ocr-resume_page_3",
            pageStatus = PageStatus.PENDING.name,
            ocrStatus = OcrPageStatus.RUNNING.name,
            ocrEngineUsed = OcrEngineType.ML_KIT.name,
            ocrErrorMessageAr = null,
            ocrUpdatedAtMillis = System.currentTimeMillis(),
        )
        repo.recoverInterruptedOcr()
        val afterRecovery = repo.getDocumentById("doc-ocr-resume")!!
        assertEquals(OcrPageStatus.DONE, afterRecovery.pages[0].ocrStatus)
        assertEquals(OcrPageStatus.FAILED, afterRecovery.pages[1].ocrStatus)
        assertEquals(OcrPageStatus.PENDING, afterRecovery.pages[2].ocrStatus)
        assertEquals(OcrPageStatus.FAILED, afterRecovery.pages[3].ocrStatus)

        // Resume: must process ONLY page 3 (PENDING), skipping DONE page 1 and FAILED pages 2 & 4
        val resumeAdapter = FakeOcrEngineAdapter()
        val resumeRepo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 4),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to resumeAdapter)),
        )
        val resumeOutcome = resumeRepo.runDocumentOcr(
            context = context,
            documentId = "doc-ocr-resume",
            engineType = OcrEngineType.ML_KIT,
            retryFailedOnly = false,
        ) as OcrRunOutcome.Completed
        assertEquals(listOf(3), resumeAdapter.processedPageNumbers)
        assertEquals(2, resumeOutcome.totalDonePages)
        assertEquals(2, resumeOutcome.totalFailedPages)
        db.close()
    }

    @Test
    fun `retryFailedOnly retries only failed pages without touching done pages`() = runTest {
        val db = openTestDatabase()
        seedDocument(db, docId = "doc-retry-failed", pageCount = 4)

        val failingAdapter = FakeOcrEngineAdapter(failingPages = mutableSetOf(2, 4))
        val repo1 = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 4),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to failingAdapter)),
        )
        repo1.runDocumentOcr(context, "doc-retry-failed", OcrEngineType.ML_KIT)

        val beforeRetry = repo1.getDocumentById("doc-retry-failed")!!
        assertTrue(beforeRetry.pages[1].ocrErrorMessageAr?.contains("فشل تجريبي") == true)

        // Now fix page 2 and keep page 4 failing -> retryFailedOnly must run ONLY pages 2 and 4
        val retryAdapter = FakeOcrEngineAdapter(failingPages = mutableSetOf(4))
        val repo2 = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 4),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to retryAdapter)),
        )
        val retryOutcome = repo2.runDocumentOcr(
            context = context,
            documentId = "doc-retry-failed",
            engineType = OcrEngineType.ML_KIT,
            retryFailedOnly = true,
        ) as OcrRunOutcome.Completed

        assertEquals(listOf(2, 4), retryAdapter.processedPageNumbers)
        assertEquals(3, retryOutcome.totalDonePages)
        assertEquals(1, retryOutcome.totalFailedPages)

        // Single-page retry on page 4 with clean adapter
        val singleAdapter = FakeOcrEngineAdapter()
        val repo3 = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 4),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.ML_KIT to singleAdapter)),
        )
        val singleOutcome = repo3.runDocumentOcr(
            context = context,
            documentId = "doc-retry-failed",
            engineType = OcrEngineType.ML_KIT,
            singlePageId = "doc-retry-failed_page_4",
        ) as OcrRunOutcome.Completed

        assertEquals(listOf(4), singleAdapter.processedPageNumbers)
        assertEquals(4, singleOutcome.totalDonePages)
        assertEquals(0, singleOutcome.totalFailedPages)
        db.close()
    }

    @Test
    fun `room migration 1 to 2 upgrades existing documents and pages without data loss`() = runTest {
        val dbFile = context.getDatabasePath(migrationDbName)
        dbFile.parentFile?.mkdirs()

        // Create authentic v1 SQLite schema manually
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
                errorMessageAr TEXT
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
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_pages_documentId_originalPageNumber ON pages(documentId, originalPageNumber)")
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
            """
            INSERT INTO documents VALUES (
                'v1-doc', 'كتاب_قديم.pdf', '/tmp/v1.pdf', 2048, 1000, 1000, 1, 1,
                'NEW', 'READY_FOR_PROCESSING', 100, 0, 1, 'ok', NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO pages VALUES (
                'v1-doc_page_1', 'v1-doc', 1, 595, 842, '/tmp/v1.pdf', NULL, 'PENDING'
            )
            """.trimIndent(),
        )
        sqlite.version = 1
        sqlite.close()

        val migratedDb = Room.databaseBuilder(context, MuharrirDatabase::class.java, migrationDbName)
            .addMigrations(
                MuharrirDatabase.MIGRATION_1_2,
                MuharrirDatabase.MIGRATION_2_3,
                MuharrirDatabase.MIGRATION_3_4,
                MuharrirDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()

        val repo = MuharrirRepository(migratedDb, storage)
        val doc = repo.getDocumentById("v1-doc")
        assertNotNull(doc)
        assertEquals("كتاب_قديم.pdf", doc!!.fileName)
        assertEquals(1, doc.pages.size)
        assertEquals(OcrPageStatus.PENDING, doc.pages.first().ocrStatus)
        assertNull(doc.pages.first().ocrResult)
        migratedDb.close()
    }

    @Test
    fun `default engine is Tesseract and bundled ara traineddata copies cleanly to filesDir`() = runTest {
        assertEquals(OcrEngineType.TESSERACT, ProcessingPreferences().preferredOcrEngine)
        assertEquals(OcrEngineType.TESSERACT, OcrEngineType.entries.first())

        val db = openTestDatabase()
        val repo = MuharrirRepository(db, storage)
        assertEquals(OcrEngineType.TESSERACT, repo.preferences.first().preferredOcrEngine)
        db.close()

        val targetFile = TesseractDataInstaller.targetAraFile(context)
        assertFalse(targetFile.exists())

        val installResult = TesseractDataInstaller.ensureArabicTrainedData(context)
        assertTrue("Expected bundled ara.traineddata copy to succeed, got $installResult", installResult is TrainedDataInstallResult.Ready)
        val ready = installResult as TrainedDataInstallResult.Ready
        assertTrue(ready.copiedFromAssets)
        assertTrue(ready.file.exists())
        assertTrue(ready.sizeBytes > 1_000_000L)

        // Second call should reuse the existing file without recopying
        val secondInstall = TesseractDataInstaller.ensureArabicTrainedData(context) as TrainedDataInstallResult.Ready
        assertFalse(secondInstall.copiedFromAssets)
        assertEquals(ready.sizeBytes, secondInstall.sizeBytes)
    }

    @Test
    fun `engine availability checks honestly report Tesseract Ready or Unavailable and ML Kit secondary Latin limitation`() = runTest {
        val mlKit = MlKitOcrEngine()
        assertFalse(mlKit.supportsArabicScript)
        val mlKitAvailability = mlKit.checkAvailability()
        assertTrue(mlKitAvailability is OcrAvailabilityResult.LimitedCapability)
        assertTrue((mlKitAvailability as OcrAvailabilityResult.LimitedCapability).warningAr.contains("ثانوي"))

        // When ara.traineddata is missing, Tesseract must report Unavailable with clear Arabic message
        val missingTesseract = TesseractAndroidOcrEngine(
            context = context,
            trainedDataProvider = { TrainedDataInstallResult.Missing("ملف اللغة العربية (ara.traineddata) غير متوفر.") },
        )
        assertTrue(missingTesseract.supportsArabicScript)
        val missingAvailability = missingTesseract.checkAvailability()
        assertTrue(missingAvailability is OcrAvailabilityResult.Unavailable)
        assertTrue((missingAvailability as OcrAvailabilityResult.Unavailable).reasonAr.contains("ara.traineddata"))

        // When ara.traineddata is present and native init succeeds, Tesseract reports Ready
        val readyTesseract = TesseractAndroidOcrEngine(
            context = context,
            nativeInitVerifier = { TesseractAndroidOcrEngine.NativeInitCheckResult.Initialized },
        )
        assertEquals(OcrAvailabilityResult.Ready, readyTesseract.checkAvailability())

        // On host JVM without native JNI .so, default native verifier fails gracefully as Unavailable rather than crashing
        val hostJvmTesseract = TesseractAndroidOcrEngine(context = context)
        assertTrue(hostJvmTesseract.checkAvailability() is OcrAvailabilityResult.Unavailable)
    }

    @Test
    fun `runDocumentOcr blocks execution with Arabic warning when selected engine is Unavailable`() = runTest {
        val db = openTestDatabase()
        seedDocument(db, docId = "doc-unavailable", pageCount = 2)

        val unavailableTesseract = TesseractAndroidOcrEngine(
            context = context,
            trainedDataProvider = { TrainedDataInstallResult.Missing("ملف اللغة العربية غير موجود.") },
        )
        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = FakePdfPageEngine(totalPages = 2),
            ocrEngineRegistry = OcrEngineRegistry(mapOf(OcrEngineType.TESSERACT to unavailableTesseract)),
        )

        val outcome = repo.runDocumentOcr(context, "doc-unavailable", OcrEngineType.TESSERACT)
        assertTrue(outcome is OcrRunOutcome.Failure)
        assertTrue((outcome as OcrRunOutcome.Failure).reasonAr.contains("ملف اللغة العربية"))

        // Pages must remain untouched in PENDING state
        val doc = repo.getDocumentById("doc-unavailable")!!
        assertTrue(doc.pages.all { it.ocrStatus == OcrPageStatus.PENDING })
        db.close()
    }
}
