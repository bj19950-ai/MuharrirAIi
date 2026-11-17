package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.comparison.ArabicLookAlikeDetector
import com.example.data.comparison.ComparisonClassificationInput
import com.example.data.comparison.ComparisonClassifier
import com.example.data.comparison.OcrGeminiComparisonEngine
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.local.DocumentEntity
import com.example.data.local.GeminiAnalysisEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.OcrSegmentsJsonCodec
import com.example.data.local.PageEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.GeminiRunOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.BoundingBox
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.GeminiPageAnalysisResponse
import com.example.domain.model.GeminiSuggestionItem
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrSegment
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.domain.model.StageModuleAvailability
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class Module05ComparisonTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_m05.db"
    private val migrationDbName = "test_muharrir_migration_3_4.db"
    private val testVaultPrefsName = "test_m05_vault_prefs"
    private val moshi = Moshi.Builder().build()
    private val responseAdapter = moshi.adapter(GeminiPageAnalysisResponse::class.java)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        context.deleteSharedPreferences(testVaultPrefsName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        context.deleteSharedPreferences(testVaultPrefsName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

    private suspend fun seedDocumentAndPages(
        db: MuharrirDatabase,
        docId: String = "doc-m05",
        pageCount: Int = 2,
    ) {
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "محاضرة_القانون.pdf",
                internalStoragePath = "/tmp/$docId.pdf",
                fileSizeBytes = 4096L,
                importDateMillis = 1000L,
                lastModifiedMillis = 1000L,
                pageCount = pageCount,
                indexedPagesCount = pageCount,
                processingStatus = DocumentStatus.ANALYZING_TEXT.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 50,
                isValidationPassed = true,
                validationNote = "ok",
                errorMessageAr = null,
            ),
        )
        val pages = (1..pageCount).map { pageNum ->
            PageEntity(
                pageId = "${docId}_page_$pageNum",
                documentId = docId,
                originalPageNumber = pageNum,
                widthPoints = 595,
                heightPoints = 842,
                renderingReference = "/tmp/$docId.pdf",
                thumbnailCachePath = null,
                pageStatus = PageStatus.ANALYZED.name,
                ocrStatus = OcrPageStatus.DONE.name,
                ocrEngineUsed = OcrEngineType.TESSERACT.name,
            )
        }
        db.pageDao().insertPages(pages)
    }

    @Test
    fun `arabic look-alike pairs always classify as DISAGREEMENT priority 1 even at high confidence`() {
        val pairs = listOf(
            "المدينه" to "المدينة",   // ه / ة
            "على" to "علي",           // ى / ي
            "اعلان" to "إعلان",       // ا / إ
            "احمد" to "أحمد",         // ا / أ
            "إسلام" to "أسلام",       // إ / أ
            "مدين" to "مذين",         // د / ذ
            "حرب" to "حزب",           // ر / ز
            "مسول" to "مسؤل",         // و / ؤ
            "سيل" to "سئل",           // ي / ئ
        )

        for ((ocr, suggested) in pairs) {
            assertTrue(
                "Expected look-alike detection for $ocr vs $suggested",
                ArabicLookAlikeDetector.hasOnlyLookAlikeDifference(ocr, suggested),
            )
            // Symmetric check
            assertTrue(
                "Expected symmetric look-alike detection for $suggested vs $ocr",
                ArabicLookAlikeDetector.hasOnlyLookAlikeDifference(suggested, ocr),
            )

            val result = ComparisonClassifier.classify(
                ComparisonClassificationInput(
                    ocrText = ocr,
                    suggestedText = suggested,
                    ocrConfidence = 0.98f,
                    geminiConfidence = 0.99f,
                    evidence = "نقطتا التاء المربوطة واضحتان في الصورة",
                    reasonType = "visual",
                    requiresReview = false,
                ),
            )
            assertEquals("Category for $ocr vs $suggested", ComparisonCategory.DISAGREEMENT, result.category)
            assertEquals("Priority for $ocr vs $suggested", 1, result.priority)
            assertTrue(result.isArabicLookAlikeDisagreement)
            assertEquals(suggested, result.persistedSuggestedText)
        }

        // Identical text without look-alike difference should NOT trigger look-alike disagreement
        assertFalse(ArabicLookAlikeDetector.hasOnlyLookAlikeDifference("القانون", "القانون"))
        // Differing length should not count as exclusive look-alike substitution
        assertFalse(ArabicLookAlikeDetector.hasOnlyLookAlikeDifference("المدينه", "المدينة المنورة"))
    }

    @Test
    fun `classification categories A through E and priorities 1 through 6 follow exact rules`() {
        // A: AGREEMENT (priority 6)
        val agreement = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "الدستور",
                suggestedText = "الدستور",
                ocrConfidence = 0.92f,
                geminiConfidence = 0.95f,
                evidence = "الكلمة مطابقة للصورة",
                reasonType = "visual",
                requiresReview = false,
            ),
        )
        assertEquals(ComparisonCategory.AGREEMENT, agreement.category)
        assertEquals(6, agreement.priority)
        assertEquals("الدستور", agreement.persistedSuggestedText)

        // B: DISAGREEMENT (priority 1)
        val disagreement = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "المحكمه",
                suggestedText = "المحكمة",
                ocrConfidence = 0.85f,
                geminiConfidence = 0.91f,
                evidence = "نقطتا التاء المربوطة فوق الحرف الأخير",
                reasonType = "visual",
                requiresReview = false,
            ),
        )
        assertEquals(ComparisonCategory.DISAGREEMENT, disagreement.category)
        assertEquals(1, disagreement.priority)

        // D: UNRESOLVED (priority 2) -> suggested_text MUST be null
        val unresolvedAmbiguous = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "المادة",
                suggestedText = "المادة",
                ocrConfidence = 0.45f,
                geminiConfidence = 0.50f,
                evidence = "الموضع مطموس وضبابي في المسح ولا يكفي للجزم بالحرف",
                reasonType = "visual",
                requiresReview = true,
            ),
        )
        assertEquals(ComparisonCategory.UNRESOLVED, unresolvedAmbiguous.category)
        assertEquals(2, unresolvedAmbiguous.priority)
        assertNull("UNRESOLVED must persist suggested_text as null", unresolvedAmbiguous.persistedSuggestedText)

        val unresolvedBlankSuggestion = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "غير واضح",
                suggestedText = "   ",
                ocrConfidence = 0.40f,
                geminiConfidence = 0.30f,
                evidence = "حبر باهت",
                reasonType = "visual",
                requiresReview = true,
            ),
        )
        assertEquals(ComparisonCategory.UNRESOLVED, unresolvedBlankSuggestion.category)
        assertEquals(2, unresolvedBlankSuggestion.priority)
        assertNull(unresolvedBlankSuggestion.persistedSuggestedText)

        // C: UNCERTAIN_RECOGNITION with ocrConfidence < 0.50 -> priority 3
        val uncertainVeryLowOcr = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "التشريع",
                suggestedText = "التشريع",
                ocrConfidence = 0.42f,
                geminiConfidence = 0.85f,
                evidence = "السياق النحوي يدعم الكلمة",
                reasonType = "context",
                requiresReview = false,
            ),
        )
        assertEquals(ComparisonCategory.UNCERTAIN_RECOGNITION, uncertainVeryLowOcr.category)
        assertEquals(3, uncertainVeryLowOcr.priority)

        // C: UNCERTAIN_RECOGNITION with ocrConfidence 0.50..0.69 or Gemini uncertainty -> priority 4
        val uncertainModerateOcr = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "الالتزام",
                suggestedText = "الالتزام",
                ocrConfidence = 0.65f,
                geminiConfidence = 0.88f,
                evidence = "دقة OCR متوسطة",
                reasonType = "lexicon",
                requiresReview = false,
            ),
        )
        assertEquals(ComparisonCategory.UNCERTAIN_RECOGNITION, uncertainModerateOcr.category)
        assertEquals(4, uncertainModerateOcr.priority)

        val uncertainGeminiLowConf = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "الحيازة",
                suggestedText = "الحيازة",
                ocrConfidence = 0.90f,
                geminiConfidence = 0.55f,
                evidence = "السياق الاصطلاحي يحتاج تحققاً",
                reasonType = "lexicon",
                requiresReview = false,
            ),
        )
        assertEquals(ComparisonCategory.UNCERTAIN_RECOGNITION, uncertainGeminiLowConf.category)
        assertEquals(4, uncertainGeminiLowConf.priority)

        // E: STRUCTURAL_AMBIGUITY (priority 5)
        val structural = ComparisonClassifier.classify(
            ComparisonClassificationInput(
                ocrText = "المبحث الأول",
                suggestedText = "المبحث الأول",
                ocrConfidence = 0.93f,
                geminiConfidence = 0.88f,
                evidence = "عنوان فرعي يختلف محاذاته وقد يلتبس بحاشية",
                reasonType = "structure",
                requiresReview = true,
            ),
        )
        assertEquals(ComparisonCategory.STRUCTURAL_AMBIGUITY, structural.category)
        assertEquals(5, structural.priority)
    }

    @Test
    fun `room migration 3 to 4 creates review_items table with all 4 indices and preserves v3 data`() = runTest {
        val dbFile = context.getDatabasePath(migrationDbName)
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
            "INSERT INTO documents VALUES ('v3-doc', 'أصول_الفقه.pdf', '/tmp/v3.pdf', 3000, 500, 500, 1, 1, 'ANALYZING_TEXT', 'READY_FOR_PROCESSING', 100, 80, 1, 'ok', NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO pages VALUES ('v3-doc_page_1', 'v3-doc', 1, 595, 842, '/tmp/v3.pdf', NULL, 'ANALYZED', 'DONE', 'TESSERACT', NULL, 600)",
        )
        sqlite.execSQL(
            "INSERT INTO ocr_results VALUES ('v3-doc_page_1', 'v3-doc', 1, 'TESSERACT', 'القياس الشرعي', '[]', 0.88, 800, 1100, 600)",
        )
        val v3Json = responseAdapter.toJson(
            GeminiPageAnalysisResponse(page_id = "v3-doc_page_1", page_number = 1, items = emptyList()),
        )
        sqlite.execSQL(
            "INSERT INTO gemini_page_analyses VALUES ('v3-doc_page_1', 'v3-doc', 1, 'gemini-flash-latest', 'Key1', ?, 0, 700)",
            arrayOf(v3Json),
        )
        sqlite.version = 3
        sqlite.close()

        val migratedDb = Room.databaseBuilder(context, MuharrirDatabase::class.java, migrationDbName)
            .addMigrations(
                MuharrirDatabase.MIGRATION_1_2,
                MuharrirDatabase.MIGRATION_2_3,
                MuharrirDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()

        val repo = MuharrirRepository(migratedDb, storage)
        val migratedDoc = repo.getDocumentById("v3-doc")
        assertNotNull(migratedDoc)
        assertEquals("أصول_الفقه.pdf", migratedDoc!!.fileName)
        assertEquals("القياس الشرعي", migratedDoc.pages.first().ocrResult?.recognizedText)
        assertNotNull(migratedDoc.pages.first().geminiAnalysis)

        // Verify all 4 indices exist on review_items
        val indexNames = mutableSetOf<String>()
        migratedDb.openHelper.writableDatabase.query("PRAGMA index_list('review_items')").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) indexNames.add(cursor.getString(nameIdx))
            }
        }
        assertTrue(indexNames.contains("index_review_items_document_id"))
        assertTrue(indexNames.contains("index_review_items_page_id"))
        assertTrue(indexNames.contains("index_review_items_review_status"))
        assertTrue(indexNames.contains("index_review_items_priority"))

        migratedDb.close()
    }

    @Test
    fun `comparison engine orders queue by priority and reading order and DAO filters work accurately`() = runTest {
        val db = openTestDatabase()
        seedDocumentAndPages(db, docId = "doc-queue", pageCount = 2)

        // Page 1: contains Agreement (prio 6), Uncertain low OCR (prio 3), Disagreement (prio 1), Structural (prio 5)
        val page1Segments = listOf(
            OcrSegment("s0", "عنوان الفصل", BoundingBox(300f, 50f, 500f, 90f), 0.94f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 0),
            OcrSegment("s1", "موافقة", BoundingBox(300f, 120f, 450f, 160f), 0.96f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 1, elementIndex = 0),
            OcrSegment("s2", "غير واضح", BoundingBox(150f, 120f, 280f, 160f), 0.42f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 1, elementIndex = 1),
            OcrSegment("s3", "المدينه", BoundingBox(100f, 200f, 250f, 240f), 0.89f, OcrElementLevel.ELEMENT, blockIndex = 1, lineIndex = 0, elementIndex = 0),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(
                pageId = "doc-queue_page_1",
                documentId = "doc-queue",
                pageNumber = 1,
                engineType = OcrEngineType.TESSERACT.name,
                recognizedText = "عنوان الفصل موافقة غير واضح المدينه",
                segmentsJson = OcrSegmentsJsonCodec.toJson(page1Segments),
                confidence = 0.80f,
                imageWidthPx = 600,
                imageHeightPx = 900,
                recognizedAtMillis = 1000L,
            ),
        )
        val p1Items = listOf(
            GeminiSuggestionItem("عنوان الفصل", "عنوان الفصل", listOf(300f, 50f, 500f, 90f), 0.92f, "عنوان في أعلى الصفحة", "structure", true),
            GeminiSuggestionItem("موافقة", "موافقة", listOf(300f, 120f, 450f, 160f), 0.97f, "واضحة تماماً", "visual", false),
            GeminiSuggestionItem("غير واضح", "غير واضح", listOf(150f, 120f, 280f, 160f), 0.82f, "الحروف مقروءة بسياق الجملة", "context", false),
            GeminiSuggestionItem("المدينه", "المدينة", listOf(100f, 200f, 250f, 240f), 0.95f, "نقطتا التاء المربوطة ظاهرتان", "visual", false),
        )
        db.geminiAnalysisDao().upsertAnalysis(
            GeminiAnalysisEntity(
                pageId = "doc-queue_page_1",
                documentId = "doc-queue",
                pageNumber = 1,
                modelId = "gemini-flash-latest",
                usedKeyLabel = "K1",
                rawValidatedJson = responseAdapter.toJson(GeminiPageAnalysisResponse("doc-queue_page_1", 1, p1Items)),
                itemsCount = p1Items.size,
                analyzedAtMillis = 1100L,
            ),
        )

        // Page 2: contains Unresolved (prio 2) + Disagreement (prio 1)
        val page2Segments = listOf(
            OcrSegment("p2s0", "كلمة مطموسة", BoundingBox(200f, 100f, 380f, 140f), 0.35f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 0),
            OcrSegment("p2s1", "المشرع", BoundingBox(200f, 200f, 350f, 240f), 0.85f, OcrElementLevel.ELEMENT, blockIndex = 1, lineIndex = 0, elementIndex = 0),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(
                pageId = "doc-queue_page_2",
                documentId = "doc-queue",
                pageNumber = 2,
                engineType = OcrEngineType.TESSERACT.name,
                recognizedText = "كلمة مطموسة المشرع",
                segmentsJson = OcrSegmentsJsonCodec.toJson(page2Segments),
                confidence = 0.60f,
                imageWidthPx = 600,
                imageHeightPx = 900,
                recognizedAtMillis = 1000L,
            ),
        )
        val p2Items = listOf(
            GeminiSuggestionItem("كلمة مطموسة", "كلمة مطموسة", listOf(200f, 100f, 380f, 140f), 0.45f, "المنطقة ضبابية ومطموسة لا يكفي للجزم", "visual", true),
            GeminiSuggestionItem("المشرع", "المشترع", listOf(200f, 200f, 350f, 240f), 0.90f, "حرف التاء ظاهر بين الشين والراء", "visual", true),
        )
        db.geminiAnalysisDao().upsertAnalysis(
            GeminiAnalysisEntity(
                pageId = "doc-queue_page_2",
                documentId = "doc-queue",
                pageNumber = 2,
                modelId = "gemini-flash-latest",
                usedKeyLabel = "K1",
                rawValidatedJson = responseAdapter.toJson(GeminiPageAnalysisResponse("doc-queue_page_2", 2, p2Items)),
                itemsCount = p2Items.size,
                analyzedAtMillis = 1200L,
            ),
        )

        val engine = OcrGeminiComparisonEngine(db)
        engine.compareAndPersistForPage("doc-queue", "doc-queue_page_1")
        engine.compareAndPersistForPage("doc-queue", "doc-queue_page_2")

        val dao = db.reviewItemDao()

        // Default queue excludes AGREEMENT ("موافقة") -> 5 items sorted by (priority, pageNumber, readingOrder)
        val defaultQueue = dao.getReviewQueue("doc-queue")
        assertEquals(5, defaultQueue.size)
        assertTrue(defaultQueue.none { it.category == ComparisonCategory.AGREEMENT.name })

        // Verify exact priority ordering:
        // 1: page 1 "المدينه" (B, prio 1)
        // 2: page 2 "المشرع" (B, prio 1)
        // 3: page 2 "كلمة مطموسة" (D, prio 2, suggestedText = null)
        // 4: page 1 "غير واضح" (C, prio 3 because ocrConf=0.42 < 0.50)
        // 5: page 1 "عنوان الفصل" (E, prio 5)
        assertEquals(listOf("المدينه", "المشرع", "كلمة مطموسة", "غير واضح", "عنوان الفصل"), defaultQueue.map { it.ocrText })
        assertEquals(listOf(1, 1, 2, 3, 5), defaultQueue.map { it.priority })
        assertNull(defaultQueue[2].suggestedText)

        // Include agreements -> 6 items with AGREEMENT (prio 6) last
        val allWithAgreements = dao.getReviewQueue("doc-queue", includeAgreements = true)
        assertEquals(6, allWithAgreements.size)
        assertEquals(ComparisonCategory.AGREEMENT.name, allWithAgreements.last().category)
        assertEquals(6, allWithAgreements.last().priority)

        // Filter by page 1 and page-reading order
        val page1ReadingOrder = dao.getReviewQueue("doc-queue", filterPage = 1, orderByPriority = false)
        assertEquals(listOf("عنوان الفصل", "غير واضح", "المدينه"), page1ReadingOrder.map { it.ocrText })

        // countByStatus excludes AGREEMENT by default
        val counts = dao.countByStatus("doc-queue")
        assertEquals(5, counts[ReviewStatus.PENDING])
        assertEquals(0, counts[ReviewStatus.APPROVED_GEMINI])
        assertEquals(0, counts[ReviewStatus.STALE])

        // getNextPending sequential traversal across priorities and pages
        val first = dao.getNextPending("doc-queue", afterPriority = 0, afterPageNumber = 0, afterElementIndex = -1)!!
        assertEquals("المدينه", first.ocrText)
        val second = dao.getNextPending("doc-queue", first.priority, first.pageNumber, first.elementIndex)!!
        assertEquals("المشرع", second.ocrText)
        val third = dao.getNextPending("doc-queue", second.priority, second.pageNumber, second.elementIndex)!!
        assertEquals("كلمة مطموسة", third.ocrText)
        val fourth = dao.getNextPending("doc-queue", third.priority, third.pageNumber, third.elementIndex)!!
        assertEquals("غير واضح", fourth.ocrText)
        val fifth = dao.getNextPending("doc-queue", fourth.priority, fourth.pageNumber, fourth.elementIndex)!!
        assertEquals("عنوان الفصل", fifth.ocrText)
        val end = dao.getNextPending("doc-queue", fifth.priority, fifth.pageNumber, fifth.elementIndex)
        assertNull(end)

        db.close()
    }

    @Test
    fun `re-analysis replaces pending items, preserves unchanged resolved items, and marks changed resolved items STALE`() = runTest {
        val db = openTestDatabase()
        seedDocumentAndPages(db, docId = "doc-reanalyze", pageCount = 1)
        val pageId = "doc-reanalyze_page_1"

        val segmentsV1 = listOf(
            OcrSegment("s0", "المدينه", BoundingBox(50f, 50f, 200f, 90f), 0.90f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 0),
            OcrSegment("s1", "الحكم", BoundingBox(50f, 120f, 200f, 160f), 0.88f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 1, elementIndex = 0),
            OcrSegment("s2", "القانوني", BoundingBox(50f, 190f, 200f, 230f), 0.62f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 2, elementIndex = 0),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(pageId, "doc-reanalyze", 1, OcrEngineType.TESSERACT.name, "المدينه الحكم القانوني", OcrSegmentsJsonCodec.toJson(segmentsV1), 0.80f, 500, 800, 1000L),
        )
        val geminiV1 = listOf(
            GeminiSuggestionItem("المدينه", "المدينة", listOf(50f, 50f, 200f, 90f), 0.95f, "تاء مربوطة", "visual", true),
            GeminiSuggestionItem("الحكم", "الحكم", listOf(50f, 120f, 200f, 160f), 0.90f, "تأكيد الصياغة", "context", true),
            GeminiSuggestionItem("القانوني", "القانوني", listOf(50f, 190f, 200f, 230f), 0.85f, "دقة OCR متوسطة", "context", false),
        )
        db.geminiAnalysisDao().upsertAnalysis(
            GeminiAnalysisEntity(pageId, "doc-reanalyze", 1, "gemini-flash-latest", "K1", responseAdapter.toJson(GeminiPageAnalysisResponse(pageId, 1, geminiV1)), geminiV1.size, 1000L),
        )

        val engine = OcrGeminiComparisonEngine(db)
        engine.compareAndPersistForPage("doc-reanalyze", pageId)

        val dao = db.reviewItemDao()
        val initial = dao.getItemsForPage(pageId)
        assertEquals(3, initial.size)

        // Simulate user resolving two items and leaving one PENDING:
        // - "المدينه" -> APPROVED_GEMINI (will stay unchanged on re-run)
        // - "الحكم" -> MANUALLY_EDITED (underlying suggested_text will change on re-run -> must become STALE!)
        // - "القانوني" -> remains PENDING (will be deleted and replaced cleanly)
        val approvedItem = initial.first { it.ocrText == "المدينه" }.copy(
            reviewStatus = ReviewStatus.APPROVED_GEMINI.name,
            userText = "المدينة",
            resolvedAt = 2000L,
        )
        val editedItem = initial.first { it.ocrText == "الحكم" }.copy(
            reviewStatus = ReviewStatus.MANUALLY_EDITED.name,
            userText = "الأحكام",
            resolvedAt = 2500L,
        )
        dao.updateItems(listOf(approvedItem, editedItem))

        // Re-run analysis with:
        // - "المدينه" unchanged
        // - "الحكم" changed suggested_text from "الحكم" to "الحكومة"
        // - "القانوني" OCR improved to "القانونية"
        val segmentsV2 = listOf(
            OcrSegment("s0", "المدينه", BoundingBox(50f, 50f, 200f, 90f), 0.90f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 0),
            OcrSegment("s1", "الحكم", BoundingBox(50f, 120f, 200f, 160f), 0.88f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 1, elementIndex = 0),
            OcrSegment("s2", "القانونية", BoundingBox(50f, 190f, 200f, 230f), 0.65f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 2, elementIndex = 0),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(pageId, "doc-reanalyze", 1, OcrEngineType.TESSERACT.name, "المدينه الحكم القانونية", OcrSegmentsJsonCodec.toJson(segmentsV2), 0.81f, 500, 800, 3000L),
        )
        val geminiV2 = listOf(
            GeminiSuggestionItem("المدينه", "المدينة", listOf(50f, 50f, 200f, 90f), 0.95f, "تاء مربوطة", "visual", true),
            GeminiSuggestionItem("الحكم", "الحكومة", listOf(50f, 120f, 200f, 160f), 0.92f, "اختلاف الكلمة في الصورة", "visual", true),
            GeminiSuggestionItem("القانونية", "القانونية", listOf(50f, 190f, 200f, 230f), 0.88f, "دقة OCR متوسطة", "context", false),
        )
        db.geminiAnalysisDao().upsertAnalysis(
            GeminiAnalysisEntity(pageId, "doc-reanalyze", 1, "gemini-flash-latest", "K1", responseAdapter.toJson(GeminiPageAnalysisResponse(pageId, 1, geminiV2)), geminiV2.size, 3000L),
        )

        val summary = engine.compareAndPersistForPage("doc-reanalyze", pageId)
        assertEquals(3, summary.totalComputed)
        assertEquals(1, summary.insertedPending)
        assertEquals(2, summary.preservedResolved)
        assertEquals(1, summary.markedStale)

        val after = dao.getItemsForPage(pageId)
        assertEquals("Expected no duplicates after re-analysis", 3, after.size)

        val preservedApproved = after.first { it.ocrText == "المدينه" }
        assertEquals(ReviewStatus.APPROVED_GEMINI.name, preservedApproved.reviewStatus)
        assertEquals("المدينة", preservedApproved.userText)
        assertNull(preservedApproved.staleNoteAr)

        val staleEdited = after.first { it.ocrText == "الحكم" }
        assertEquals(ReviewStatus.STALE.name, staleEdited.reviewStatus)
        assertEquals("الأحكام", staleEdited.userText)
        assertEquals("الحكومة", staleEdited.suggestedText)
        assertNotNull(staleEdited.staleNoteAr)
        assertTrue(staleEdited.staleNoteAr!!.contains("إعادة"))

        val replacedPending = after.first { it.ocrText == "القانونية" }
        assertEquals(ReviewStatus.PENDING.name, replacedPending.reviewStatus)

        db.close()
    }

    @Test
    fun `runGeminiAnalysis triggers comparison engine automatically and populates repository review data`() = runTest {
        val db = openTestDatabase()
        val docId = "doc-repo-m05"
        val pdfFile = storage.getPrivatePdfFile(docId).apply {
            parentFile?.mkdirs()
            writeBytes("%PDF-1.4\n%content".toByteArray())
        }
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "مذكرة_الأستاذ.pdf",
                internalStoragePath = pdfFile.absolutePath,
                fileSizeBytes = pdfFile.length(),
                importDateMillis = 1000L,
                lastModifiedMillis = 1000L,
                pageCount = 1,
                indexedPagesCount = 1,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 50,
                isValidationPassed = true,
                validationNote = "ok",
                errorMessageAr = null,
            ),
        )
        db.pageDao().insertPages(
            listOf(
                PageEntity(
                    pageId = "${docId}_page_1",
                    documentId = docId,
                    originalPageNumber = 1,
                    widthPoints = 595,
                    heightPoints = 842,
                    renderingReference = pdfFile.absolutePath,
                    thumbnailCachePath = null,
                    pageStatus = PageStatus.OCR_DONE.name,
                    ocrStatus = OcrPageStatus.DONE.name,
                    ocrEngineUsed = OcrEngineType.TESSERACT.name,
                ),
            ),
        )
        val ocrSegments = listOf(
            OcrSegment("w1", "القضاه", BoundingBox(40f, 50f, 180f, 95f), 0.94f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 0),
            OcrSegment("w2", "العادل", BoundingBox(200f, 50f, 340f, 95f), 0.96f, OcrElementLevel.ELEMENT, blockIndex = 0, lineIndex = 0, elementIndex = 1),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(
                pageId = "${docId}_page_1",
                documentId = docId,
                pageNumber = 1,
                engineType = OcrEngineType.TESSERACT.name,
                recognizedText = "القضاه العادل",
                segmentsJson = OcrSegmentsJsonCodec.toJson(ocrSegments),
                confidence = 0.95f,
                imageWidthPx = 800,
                imageHeightPx = 1100,
                recognizedAtMillis = 1000L,
            ),
        )

        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences(testVaultPrefsName, Context.MODE_PRIVATE) },
            dayProvider = { "2026-11-17" },
        )
        vault.addKey("Key1", "AIzaSyM05AutoCompareKey99999")

        val fakeClient = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ): Result<String> {
                val resp = GeminiPageAnalysisResponse(
                    page_id = payload.pageId,
                    page_number = payload.pageNumber,
                    items = listOf(
                        GeminiSuggestionItem("القضاه", "القضاة", listOf(40f, 50f, 180f, 95f), 0.97f, "تاء مربوطة واضحة", "visual", false),
                        GeminiSuggestionItem("العادل", "العادل", listOf(200f, 50f, 340f, 95f), 0.98f, "تطابق تام", "visual", false),
                    ),
                )
                return Result.success(responseAdapter.toJson(resp))
            }
        }
        val fakeEngine = object : PdfPageEngine {
            override suspend fun indexPages(
                pdfFile: File,
                batchSize: Int,
                onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
            ) = PrivatePdfIndexResult.Success(1, listOf(IndexedPdfPageMeta(1, 595, 842)))

            override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap =
                Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakeEngine,
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, fakeClient) { },
        )

        val outcome = repo.runGeminiAnalysis(docId)
        assertTrue(outcome is GeminiRunOutcome.Completed)

        // Default queue excludes AGREEMENT ("العادل"), returning only the look-alike DISAGREEMENT ("القضاه" -> "القضاة")
        val queue = repo.getReviewQueue(docId)
        assertEquals(1, queue.size)
        assertEquals(ComparisonCategory.DISAGREEMENT, queue.first().category)
        assertEquals(1, queue.first().priority)
        assertEquals("القضاه", queue.first().originalOcrText)
        assertEquals("القضاة", queue.first().geminiSuggestion)

        val counts = repo.countReviewByStatus(docId)
        assertEquals(1, counts[ReviewStatus.PENDING])

        val doc = repo.getDocumentById(docId)!!
        assertEquals(1, doc.reviewProgress.totalItems)
        assertEquals(2, doc.reviewItems.size)

        val compareStage = repo.buildPlannedStages(doc).first { it.id == "stage_compare" }
        assertEquals(StageModuleAvailability.READY_IN_MODULE_05, compareStage.availability)
        assertTrue(compareStage.isCompletedLocally)

        db.close()
    }
}
