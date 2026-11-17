package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.MuharrirDocumentReviewGateway
import com.example.data.repository.MuharrirPageBitmapSource
import com.example.data.repository.MuharrirRepository
import com.example.data.repository.MuharrirReviewQueueGateway
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.review.model.BoundingBox
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.DocumentReviewGateway
import com.example.review.model.DocumentReviewInfo
import com.example.review.model.PageBitmapSource
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewQueueGateway
import com.example.review.model.ReviewStatusCounts
import com.example.review.ui.ReopenReason
import com.example.review.ui.ReviewFilter
import com.example.review.ui.ReviewPositionStore
import com.example.review.ui.ReviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module06ReviewTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_m06.db"
    private val migrationDbName = "test_muharrir_migration_4_5.db"
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `room migration 4 to 5 adds review approval and export columns while preserving documents`() = runTest {
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
            "INSERT INTO documents VALUES ('v4-doc', 'كتاب_النحو.pdf', '/tmp/v4.pdf', 2048, 1000, 1000, 1, 1, 'NEEDS_REVIEW', 'READY_FOR_PROCESSING', 100, 90, 1, 'ok', NULL)",
        )
        sqlite.version = 4
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

        val entity = migratedDb.documentDao().getDocumentById("v4-doc")
        assertNotNull(entity)
        assertEquals("كتاب_النحو.pdf", entity!!.originalFileName)
        assertNull(entity.reviewApprovedAt)
        assertNull(entity.exportedDocxPath)

        val repo = MuharrirRepository(migratedDb, storage)
        val domainDoc = repo.getDocumentById("v4-doc")!!
        assertNull(domainDoc.reviewApprovedAt)
        assertNull(domainDoc.exportedDocument)

        migratedDb.close()
    }

    @Test
    fun `room gateways persist decisions, advance pending queue, reopen stale items, and approve document`() = runTest {
        val db = openTestDatabase()
        val docId = "doc-m06-gw"
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "المصادر.pdf",
                internalStoragePath = "/tmp/m06.pdf",
                fileSizeBytes = 1024L,
                importDateMillis = 100L,
                lastModifiedMillis = 100L,
                pageCount = 1,
                indexedPagesCount = 1,
                processingStatus = DocumentStatus.NEEDS_REVIEW.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 90,
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
                    renderingReference = "/tmp/m06.pdf",
                    thumbnailCachePath = null,
                    pageStatus = PageStatus.NEEDS_REVIEW.name,
                    ocrStatus = OcrPageStatus.DONE.name,
                ),
            ),
        )
        val insertedIds = db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_1",
                    ocrResultId = null,
                    geminiAnalysisId = null,
                    pageNumber = 1,
                    x1 = 50,
                    y1 = 60,
                    x2 = 180,
                    y2 = 100,
                    ocrText = "المدينه",
                    suggestedText = "المدينة",
                    ocrConfidence = 0.85f,
                    geminiConfidence = 0.96f,
                    category = ComparisonCategory.DISAGREEMENT.name,
                    priority = 1,
                    reviewStatus = ReviewStatus.PENDING.name,
                    userText = null,
                    evidence = "نقطتا التاء المربوطة ظاهرتان",
                    reasonType = "visual",
                    createdAt = 200L,
                    resolvedAt = null,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 0,
                ),
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_1",
                    ocrResultId = null,
                    geminiAnalysisId = null,
                    pageNumber = 1,
                    x1 = 200,
                    y1 = 60,
                    x2 = 340,
                    y2 = 100,
                    ocrText = "الفقهيه",
                    suggestedText = "الفقهية",
                    ocrConfidence = 0.72f,
                    geminiConfidence = 0.90f,
                    category = ComparisonCategory.UNCERTAIN_RECOGNITION.name,
                    priority = 4,
                    reviewStatus = ReviewStatus.STALE.name,
                    userText = "الفقهيّة",
                    evidence = "تحديث التحليل",
                    reasonType = "context",
                    createdAt = 210L,
                    resolvedAt = 250L,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 1,
                    staleNoteAr = "تغير النص المقترح بعد إعادة التحليل",
                ),
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_1",
                    ocrResultId = null,
                    geminiAnalysisId = null,
                    pageNumber = 1,
                    x1 = 360,
                    y1 = 60,
                    x2 = 450,
                    y2 = 100,
                    ocrText = "المطابق",
                    suggestedText = "المطابق",
                    ocrConfidence = 0.98f,
                    geminiConfidence = 0.99f,
                    category = ComparisonCategory.AGREEMENT.name,
                    priority = 6,
                    reviewStatus = ReviewStatus.PENDING.name,
                    userText = null,
                    evidence = "تطابق تام",
                    reasonType = "visual",
                    createdAt = 220L,
                    resolvedAt = null,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 2,
                ),
            ),
        )

        val queueGateway = MuharrirReviewQueueGateway(db)
        val docGateway = MuharrirDocumentReviewGateway(db)

        val initialCounts = queueGateway.countByStatus(docId).first()
        assertEquals(2, initialCounts.total)
        assertEquals(2, initialCounts.remaining)

        val firstId = insertedIds[0]
        val secondId = insertedIds[1]
        val nextPending = queueGateway.getNextPending(docId, afterItemId = firstId)
        assertNotNull(nextPending)
        assertEquals(secondId, nextPending!!.id)

        // Save decision on first item
        queueGateway.saveDecision(
            itemId = firstId,
            newStatus = ReviewStatus.APPROVED_GEMINI,
            userText = "المدينة",
            resolvedAt = 5000L,
        )
        val updatedFirst = db.reviewItemDao().getItemById(firstId)!!
        assertEquals(ReviewStatus.APPROVED_GEMINI.name, updatedFirst.reviewStatus)
        assertEquals("المدينة", updatedFirst.userText)
        assertEquals(5000L, updatedFirst.resolvedAt)

        // Reopen stale second item
        queueGateway.reopenItemAsPending(secondId)
        val reopenedSecond = db.reviewItemDao().getItemById(secondId)!!
        assertEquals(ReviewStatus.PENDING.name, reopenedSecond.reviewStatus)
        assertNull(reopenedSecond.staleNoteAr)

        // Approve document
        docGateway.setReviewApprovedAt(docId, 9000L)
        val docInfo = docGateway.observeDocument(docId).first()
        assertEquals(9000L, docInfo?.reviewApprovedAt)
        assertEquals(DocumentStatus.REVIEW_COMPLETED.name, db.documentDao().getDocumentById(docId)?.processingStatus)

        db.close()
    }

    @Test
    fun `review viewmodel handles actions, crop, page jump, reopen dialog, partial approval, and rollback on failure`() = runTest(mainDispatcher) {
        val fakeQueue = FakeReviewQueueGateway(
            initialItems = listOf(
                ReviewItemModel(
                    id = 1L,
                    documentId = "doc-vm",
                    pageNumber = 1,
                    category = DiscrepancyCategory.B,
                    ocrText = "المدينه",
                    suggestedText = "المدينة",
                    ocrConfidence = 0.88f,
                    geminiConfidence = 0.96f,
                    evidence = "نقطتا التاء واضحتان",
                    bbox = BoundingBox(40f, 50f, 160f, 90f),
                    reviewStatus = ReviewStatus.PENDING,
                    userText = null,
                    resolvedAt = null,
                    orderIndex = 0,
                ),
                ReviewItemModel(
                    id = 2L,
                    documentId = "doc-vm",
                    pageNumber = 2,
                    category = DiscrepancyCategory.C,
                    ocrText = "الدستوريه",
                    suggestedText = "الدستورية",
                    ocrConfidence = 0.61f,
                    geminiConfidence = 0.89f,
                    evidence = "السياق النحوي",
                    bbox = BoundingBox(60f, 120f, 220f, 170f),
                    reviewStatus = ReviewStatus.STALE,
                    userText = "الدستوري",
                    resolvedAt = 100L,
                    isStale = true,
                    staleNoteAr = "تغير النص المقترح بعد إعادة التحليل",
                    orderIndex = 1,
                ),
                ReviewItemModel(
                    id = 3L,
                    documentId = "doc-vm",
                    pageNumber = 2,
                    category = DiscrepancyCategory.A,
                    ocrText = "القانون",
                    suggestedText = "القانون",
                    ocrConfidence = 0.99f,
                    geminiConfidence = 0.99f,
                    evidence = "تطابق",
                    bbox = BoundingBox(60f, 200f, 180f, 240f),
                    reviewStatus = ReviewStatus.PENDING,
                    userText = null,
                    resolvedAt = null,
                    orderIndex = 2,
                ),
            ),
        )
        val fakeDoc = FakeDocumentReviewGateway("doc-vm", "رسالة_ماجستير.pdf")
        val fakeBitmaps = object : PageBitmapSource {
            override suspend fun loadPageBitmap(documentId: String, pageNumber: Int): Bitmap =
                Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        }

        val vm = ReviewViewModel(
            documentId = "doc-vm",
            reviewQueueGateway = fakeQueue,
            documentReviewGateway = fakeDoc,
            pageBitmapSource = fakeBitmaps,
            savedStateHandle = SavedStateHandle(),
            ioDispatcher = mainDispatcher,
            defaultDispatcher = mainDispatcher,
            clock = { 123456L },
        )
        advanceUntilIdle()

        // Default excludes Category A
        assertEquals(2, vm.uiState.value.filteredItems.size)
        assertEquals(1L, vm.uiState.value.currentItem?.id)
        assertNotNull(vm.uiState.value.pageBitmap)
        assertNotNull(vm.uiState.value.enlargedCropBitmap)
        assertEquals(1L, ReviewPositionStore.getLastItemId("doc-vm"))

        // Action 1: approve Gemini suggestion -> saves & jumps to next pending (id 2L)
        vm.approveGeminiSuggestion()
        advanceUntilIdle()
        assertEquals(2L, vm.uiState.value.currentItem?.id)
        assertEquals(ReviewStatus.APPROVED_GEMINI, fakeQueue.itemsFlow.value.first { it.id == 1L }.reviewStatus)
        assertEquals("المدينة", fakeQueue.itemsFlow.value.first { it.id == 1L }.userText)

        // Toggle agreements on -> category A now included
        vm.toggleIncludeAgreement()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.filteredItems.size)

        // Page jump error for non-existent page
        vm.onPageJumpInputChanged("99")
        vm.jumpToPage()
        assertNotNull(vm.uiState.value.pageJumpError)

        // Valid page jump to page 1
        vm.onPageJumpInputChanged("1")
        vm.jumpToPage()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.currentItem?.pageNumber)

        // Reopen item dialog -> confirm
        vm.requestReopenItem(ReopenReason.MANUAL_REOPEN)
        assertNotNull(vm.uiState.value.reopenDialog)
        vm.confirmReopenItem()
        advanceUntilIdle()
        assertNull(vm.uiState.value.reopenDialog)
        assertEquals(ReviewStatus.PENDING, fakeQueue.itemsFlow.value.first { it.id == 1L }.reviewStatus)

        // Filter to discrepancy only
        vm.selectFilter(ReviewFilter.DISCREPANCY_ONLY)
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.uiState.value.filteredItems.map { it.id })

        // Test rollback on saveDecision error
        fakeQueue.shouldFailNextSave = true
        vm.enableManualEdit()
        vm.onEditedTextChanged("نص خاطئ مؤقت")
        vm.saveManualEdit()
        advanceUntilIdle()
        assertEquals(ReviewViewModel.SAVE_ERROR_MESSAGE, vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.savedConfirmationVisible)
        assertEquals(ReviewStatus.PENDING, fakeQueue.itemsFlow.value.first { it.id == 1L }.reviewStatus)

        // Save manual edit succeeds when failure flag cleared
        fakeQueue.shouldFailNextSave = false
        vm.enableManualEdit()
        vm.onEditedTextChanged("المدينة المنورة")
        vm.saveManualEdit()
        advanceUntilIdle()
        assertEquals(ReviewStatus.MANUALLY_EDITED, fakeQueue.itemsFlow.value.first { it.id == 1L }.reviewStatus)
        assertEquals("المدينة المنورة", fakeQueue.itemsFlow.value.first { it.id == 1L }.userText)

        // Remaining > 0 -> clicking approve triggers partial approval dialog first
        vm.onApproveReviewClicked()
        assertTrue(vm.uiState.value.showPartialApprovalDialog)
        vm.confirmPartialApproval()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showPartialApprovalDialog)
        assertEquals(123456L, vm.uiState.value.reviewApprovedAt)
        assertTrue(vm.uiState.value.approvalSucceeded)
    }

    @Test
    fun `page bitmap source uses exact ocr image width when rendering page`() = runTest {
        val db = openTestDatabase()
        val docId = "doc-bmp-m06"
        val pdfFile = storage.getPrivatePdfFile(docId).apply {
            parentFile?.mkdirs()
            writeBytes("%PDF-1.4\n%fake".toByteArray())
        }
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "مقاس.pdf",
                internalStoragePath = pdfFile.absolutePath,
                fileSizeBytes = pdfFile.length(),
                importDateMillis = 100L,
                lastModifiedMillis = 100L,
                pageCount = 1,
                indexedPagesCount = 1,
                processingStatus = DocumentStatus.NEEDS_REVIEW.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 90,
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
                ),
            ),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(
                pageId = "${docId}_page_1",
                documentId = docId,
                pageNumber = 1,
                engineType = OcrEngineType.TESSERACT.name,
                recognizedText = "نص",
                segmentsJson = "[]",
                confidence = 0.9f,
                imageWidthPx = 1440,
                imageHeightPx = 2048,
                recognizedAtMillis = 100L,
            ),
        )

        var requestedWidth = -1
        val fakeEngine = object : PdfPageEngine {
            override suspend fun indexPages(
                pdfFile: File,
                batchSize: Int,
                onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
            ) = PrivatePdfIndexResult.Success(1, listOf(IndexedPdfPageMeta(1, 595, 842)))

            override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap {
                requestedWidth = maxWidthPx
                return Bitmap.createBitmap(maxWidthPx, 2048, Bitmap.Config.ARGB_8888)
            }
        }

        val source = MuharrirPageBitmapSource(db, storage, fakeEngine)
        val bmp = source.loadPageBitmap(docId, 1)
        assertNotNull(bmp)
        assertEquals(1440, requestedWidth)
        assertEquals(1440, bmp!!.width)

        db.close()
    }

    private class FakeReviewQueueGateway(initialItems: List<ReviewItemModel>) : ReviewQueueGateway {
        val itemsFlow = MutableStateFlow(initialItems)
        var shouldFailNextSave = false

        override fun getReviewQueue(documentId: String): Flow<List<ReviewItemModel>> = itemsFlow

        override fun countByStatus(documentId: String): Flow<ReviewStatusCounts> =
            itemsFlow.map { list ->
                val nonAgreement = list.filter { it.category != DiscrepancyCategory.A }
                val approved = nonAgreement.count { it.reviewStatus == ReviewStatus.APPROVED_GEMINI }
                val edited = nonAgreement.count { it.reviewStatus == ReviewStatus.MANUALLY_EDITED }
                val unresolved = nonAgreement.count { it.reviewStatus == ReviewStatus.UNRESOLVED }
                val remaining = nonAgreement.count { it.reviewStatus == ReviewStatus.PENDING || it.isStale }
                ReviewStatusCounts(
                    total = nonAgreement.size,
                    approvedGemini = approved,
                    manuallyEdited = edited,
                    unresolved = unresolved,
                    remaining = remaining,
                )
            }

        override suspend fun getNextPending(documentId: String, afterItemId: Long): ReviewItemModel? {
            val list = itemsFlow.value.filter { it.category != DiscrepancyCategory.A }
            val afterIdx = list.indexOfFirst { it.id == afterItemId }
            val afterList = if (afterIdx >= 0) list.drop(afterIdx + 1) else list
            return afterList.firstOrNull { it.reviewStatus == ReviewStatus.PENDING || it.isStale }
                ?: list.firstOrNull { it.id != afterItemId && (it.reviewStatus == ReviewStatus.PENDING || it.isStale) }
        }

        override suspend fun saveDecision(
            itemId: Long,
            newStatus: ReviewStatus,
            userText: String?,
            resolvedAt: Long,
        ) {
            if (shouldFailNextSave) {
                throw IllegalStateException("Simulated disk error")
            }
            itemsFlow.value = itemsFlow.value.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        reviewStatus = newStatus,
                        userText = userText,
                        resolvedAt = resolvedAt,
                        isStale = false,
                        staleNoteAr = null,
                    )
                } else {
                    item
                }
            }
        }

        override suspend fun reopenItemAsPending(itemId: Long) {
            itemsFlow.value = itemsFlow.value.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        reviewStatus = ReviewStatus.PENDING,
                        isStale = false,
                        staleNoteAr = null,
                    )
                } else {
                    item
                }
            }
        }
    }

    private class FakeDocumentReviewGateway(
        documentId: String,
        title: String,
    ) : DocumentReviewGateway {
        private val docFlow = MutableStateFlow(DocumentReviewInfo(documentId, title, null))

        override fun observeDocument(documentId: String): Flow<DocumentReviewInfo?> = docFlow

        override suspend fun setReviewApprovedAt(documentId: String, approvedAtMillis: Long) {
            docFlow.value = docFlow.value.copy(reviewApprovedAt = approvedAtMillis)
        }
    }
}
