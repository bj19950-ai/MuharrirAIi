package com.example

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.local.PreferencesEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.repository.MuharrirWordExportDataGateway
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageStatus
import com.example.domain.model.ReviewStatus
import com.example.export.docx.WordGenerator
import com.example.export.domain.WordTextAssembler
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportMode
import com.example.export.ui.WordExportEvent
import com.example.export.ui.WordExportViewModel
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module07WordExportTest {

    private lateinit var context: Context
    private val testDbName = "test_muharrir_m07.db"
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.deleteRecursively()
        File(context.filesDir, "Documents").deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(testDbName)
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.deleteRecursively()
        File(context.filesDir, "Documents").deleteRecursively()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

    @Test
    fun `word text assembler preserves page reading order, spliced full OCR, and unresolved highlight preference`() {
        val doc = ExportDocumentModel("doc-1", "تاريخ_الكتاب.pdf", reviewApprovedAt = 9999L)
        val pages = listOf(
            DocumentPageModel("p2", "doc-1", pageNumber = 2, ocrText = "نص الصفحة الثانية غير المعدل"),
            DocumentPageModel("p1", "doc-1", pageNumber = 1, ocrText = "بديل"),
            DocumentPageModel(
                "p3",
                "doc-1",
                pageNumber = 3,
                ocrText = "تقع المدينه في وادي النيل وهي عامرة بالسكان ومبانيها مطموسة.",
            ),
        )
        val items = listOf(
            ReviewItemModel(
                id = 2L,
                documentId = "doc-1",
                pageNumber = 1,
                category = DiscrepancyCategory.D,
                ocrText = "كلمة غير واضحة",
                suggestedText = null,
                ocrConfidence = 0.35f,
                geminiConfidence = 0.25f,
                evidence = "مطموس",
                bbox = null,
                reviewStatus = ReviewStatus.UNRESOLVED,
                userText = null,
                resolvedAt = null,
                orderIndex = 1,
            ),
            ReviewItemModel(
                id = 1L,
                documentId = "doc-1",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "خطا",
                suggestedText = "خطأ",
                ocrConfidence = 0.85f,
                geminiConfidence = 0.96f,
                evidence = "همزة",
                bbox = null,
                reviewStatus = ReviewStatus.MANUALLY_EDITED,
                userText = "خطأ إملائي",
                resolvedAt = 5000L,
                orderIndex = 0,
            ),
            ReviewItemModel(
                id = 3L,
                documentId = "doc-1",
                pageNumber = 3,
                category = DiscrepancyCategory.B,
                ocrText = "المدينه",
                suggestedText = "المدينة",
                ocrConfidence = 0.90f,
                geminiConfidence = 0.98f,
                evidence = "تاء مربوطة",
                bbox = null,
                reviewStatus = ReviewStatus.APPROVED_GEMINI,
                userText = "المدينة",
                resolvedAt = 6000L,
                orderIndex = 0,
            ),
            ReviewItemModel(
                id = 4L,
                documentId = "doc-1",
                pageNumber = 3,
                category = DiscrepancyCategory.D,
                ocrText = "مطموسة",
                suggestedText = null,
                ocrConfidence = 0.30f,
                geminiConfidence = 0.40f,
                evidence = "حبر باهت",
                bbox = null,
                reviewStatus = ReviewStatus.UNRESOLVED,
                userText = null,
                resolvedAt = null,
                orderIndex = 1,
            ),
        )

        val plan = WordTextAssembler.buildPlan(
            document = doc,
            pages = pages,
            reviewItems = items,
            mode = WordExportMode.ACADEMIC,
            highlightUnresolved = true,
        )
        assertEquals(listOf(1, 2, 3), plan.pages.map { it.pageNumber })

        // Page 1 uses item orderIndex (0 then 1)
        val p1 = plan.pages[0].segments
        assertEquals("خطأ إملائي", p1[0].text)
        assertFalse(p1[0].isUnresolvedHighlight)
        assertEquals("كلمة غير واضحة", p1[1].text)
        assertTrue(p1[1].isUnresolvedHighlight)

        // Page 2 falls back to full OCR when no review items exist
        val p2 = plan.pages[1].segments
        assertEquals(1, p2.size)
        assertEquals("نص الصفحة الثانية غير المعدل", p2[0].text)

        // Page 3 splices "المدينة" and unresolved "مطموسة" inline inside full-page OCR
        val p3Text = plan.pages[2].segments.joinToString("") { it.text }
        assertEquals("تقع المدينة في وادي النيل وهي عامرة بالسكان ومبانيها مطموسة.", p3Text)
        val p3Unresolved = plan.pages[2].segments.filter { it.isUnresolvedHighlight }.map { it.text }
        assertEquals(listOf("مطموسة"), p3Unresolved)

        // When highlightUnresolved is disabled in preferences, no segment is marked highlighted
        val noHighlightPlan = WordTextAssembler.buildPlan(
            document = doc,
            pages = pages,
            reviewItems = items,
            mode = WordExportMode.MATCH_ORIGINAL,
            highlightUnresolved = false,
        )
        assertTrue(noHighlightPlan.pages.flatMap { it.segments }.none { it.isUnresolvedHighlight })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `word generator refuses to generate docx before review approval`() {
        val generator = WordGenerator()
        val unapprovedDoc = ExportDocumentModel("doc-unapproved", "غير_معتمد.pdf", reviewApprovedAt = null)
        val outFile = File(context.filesDir, "should_not_exist.docx")
        generator.generate(
            document = unapprovedDoc,
            pages = emptyList(),
            reviewItems = emptyList(),
            mode = WordExportMode.ACADEMIC,
            outputFile = outFile,
        )
    }

    @Test
    fun `word generator writes valid rtl docx with highlighting in academic and match original modes`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-gen", "مقال_تاريخي.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel("p1", "doc-gen", pageNumber = 1, ocrText = "محتوى الصفحة"),
        )
        val items = listOf(
            ReviewItemModel(
                id = 10L,
                documentId = "doc-gen",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "المدينه",
                suggestedText = "المدينة",
                ocrConfidence = 0.85f,
                geminiConfidence = 0.95f,
                evidence = "تاء",
                bbox = null,
                reviewStatus = ReviewStatus.APPROVED_GEMINI,
                userText = "المدينة",
                resolvedAt = 100L,
                orderIndex = 0,
            ),
            ReviewItemModel(
                id = 11L,
                documentId = "doc-gen",
                pageNumber = 1,
                category = DiscrepancyCategory.D,
                ocrText = "كلمة_مبهمة",
                suggestedText = null,
                ocrConfidence = 0.30f,
                geminiConfidence = 0.30f,
                evidence = "طمس",
                bbox = null,
                reviewStatus = ReviewStatus.UNRESOLVED,
                userText = null,
                resolvedAt = null,
                orderIndex = 1,
            ),
        )

        val academicFile = generator.buildOutputFile(context, "مقال_تاريخي.pdf", nowMillis = 1700000001000L)
        generator.generate(doc, pages, items, WordExportMode.ACADEMIC, academicFile, highlightUnresolved = true)
        assertTrue(academicFile.exists())
        assertTrue(academicFile.length() > 0L)

        ZipFile(academicFile).use { zip ->
            assertNotNull(zip.getEntry("[Content_Types].xml"))
            assertNotNull(zip.getEntry("_rels/.rels"))
            val docEntry = zip.getEntry("word/document.xml")
            assertNotNull(docEntry)

            val xmlBytes = zip.getInputStream(docEntry).readBytes()
            val xmlString = xmlBytes.toString(Charsets.UTF_8)
            val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(xmlBytes.inputStream())

            val sectBidiNodes = dom.getElementsByTagNameNS("*", "bidi")
            assertTrue("Expected <w:bidi> elements in sectPr and pPr", sectBidiNodes.length >= 2)

            val paragraphs = dom.getElementsByTagNameNS("*", "p")
            assertTrue(paragraphs.length >= 1)

            val jcNodes = dom.getElementsByTagNameNS("*", "jc")
            assertEquals(paragraphs.length, jcNodes.length)
            for (i in 0 until jcNodes.length) {
                val valAttr = jcNodes.item(i).attributes.getNamedItemNS("*", "val")?.nodeValue
                    ?: jcNodes.item(i).attributes.getNamedItem("w:val")?.nodeValue
                assertTrue(valAttr in setOf("right", "both", "center"))
            }

            assertFalse(xmlString.contains("الصفحة 1"))
            assertTrue(xmlString.contains("المدينة"))
            assertTrue(xmlString.contains("كلمة_مبهمة"))
            assertTrue(xmlString.contains(WordGenerator.UNRESOLVED_TEXT_RED_HEX))
            assertTrue(xmlString.contains("yellow"))
            assertTrue(xmlString.contains("rtl"))
            assertTrue(xmlString.contains(WordGenerator.DEFAULT_BODY_FONT))
        }

        // Verify MATCH_ORIGINAL generates without title/page headings
        val matchFile = File(academicFile.parentFile, "match_original_test.docx")
        generator.generate(doc, pages, items, WordExportMode.MATCH_ORIGINAL, matchFile, highlightUnresolved = true)
        assertTrue(matchFile.exists())
        ZipFile(matchFile).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
            assertNotNull(docEntry)
            val xmlString = zip.getInputStream(docEntry).readBytes().toString(Charsets.UTF_8)
            assertFalse(xmlString.contains("الصفحة 1"))
            assertTrue(xmlString.contains("المدينة"))
            assertTrue(xmlString.contains("كلمة_مبهمة"))
        }
    }

    @Test
    fun `word export viewmodel reads room in reading order, exports docx, updates db, and emits FileProvider-valid share event`() = runTest(mainDispatcher) {
        val db = openTestDatabase()
        val docId = "doc-m07-e2e"
        db.preferencesDao().upsertPreferences(
            PreferencesEntity(
                singletonId = 1,
                preferredOcrEngine = OcrEngineType.TESSERACT.name,
                preferredExportMode = ExportMode.ACADEMIC_FORMAT.name,
                highlightUnresolvedInDocx = true,
                autoSaveCheckpoints = true,
                resumeAfterInterruption = true,
                batchSizePages = 10,
            ),
        )
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "بحث_مقارن.pdf",
                internalStoragePath = "/tmp/m07.pdf",
                fileSizeBytes = 2048L,
                importDateMillis = 100L,
                lastModifiedMillis = 100L,
                pageCount = 1,
                indexedPagesCount = 1,
                processingStatus = DocumentStatus.REVIEW_COMPLETED.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 100,
                isValidationPassed = true,
                validationNote = "ok",
                errorMessageAr = null,
                reviewApprovedAt = 50000L,
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
                    renderingReference = "/tmp/m07.pdf",
                    thumbnailCachePath = null,
                    pageStatus = PageStatus.REVIEWED.name,
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
                recognizedText = "الدستور العام والمدينه",
                segmentsJson = "[]",
                confidence = 0.90f,
                imageWidthPx = 800,
                imageHeightPx = 1100,
                recognizedAtMillis = 100L,
            ),
        )
        // Insert out-of-order elementIndex to ensure Room reading-order query sorts them properly
        db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_1",
                    ocrResultId = "${docId}_page_1",
                    geminiAnalysisId = null,
                    pageNumber = 1,
                    x1 = 200,
                    y1 = 50,
                    x2 = 300,
                    y2 = 90,
                    ocrText = "المدينه",
                    suggestedText = "المدينة",
                    ocrConfidence = 0.85f,
                    geminiConfidence = 0.95f,
                    category = ComparisonCategory.DISAGREEMENT.name,
                    priority = 1,
                    reviewStatus = ReviewStatus.APPROVED_GEMINI.name,
                    userText = "المدينة",
                    evidence = "تاء مربوطة",
                    reasonType = "visual",
                    createdAt = 100L,
                    resolvedAt = 40000L,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 1,
                ),
                ReviewItemEntity(
                    documentId = docId,
                    pageId = "${docId}_page_1",
                    ocrResultId = "${docId}_page_1",
                    geminiAnalysisId = null,
                    pageNumber = 1,
                    x1 = 50,
                    y1 = 50,
                    x2 = 150,
                    y2 = 90,
                    ocrText = "الدستور",
                    suggestedText = "الدستور",
                    ocrConfidence = 0.97f,
                    geminiConfidence = 0.98f,
                    category = ComparisonCategory.AGREEMENT.name,
                    priority = 6,
                    reviewStatus = ReviewStatus.PENDING.name,
                    userText = null,
                    evidence = "مطابق",
                    reasonType = "visual",
                    createdAt = 100L,
                    resolvedAt = null,
                    blockIndex = 0,
                    lineIndex = 0,
                    elementIndex = 0,
                ),
            ),
        )

        val gateway = MuharrirWordExportDataGateway(db, ioDispatcher = mainDispatcher)
        val orderedItems = gateway.getReviewItemsInOrder(docId)
        assertEquals(listOf(0, 1), orderedItems.map { it.orderIndex })
        assertEquals(listOf("الدستور", "المدينه"), orderedItems.map { it.ocrText })

        val exportVm = WordExportViewModel(
            dataGateway = gateway,
            ioDispatcher = mainDispatcher,
            clock = { 1700000099000L },
        )

        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) { exportVm.events.first() }
        exportVm.exportDocument(context, docId, WordExportMode.ACADEMIC)
        advanceUntilIdle()

        val uiState = exportVm.uiState.value
        assertFalse(uiState.isExporting)
        assertNotNull(uiState.lastExportedFile)
        assertTrue(uiState.lastExportedFile!!.exists())

        val shareEvent = eventDeferred.await() as WordExportEvent.ShareDocxFile
        assertEquals("بحث_مقارن.pdf", shareEvent.documentTitle)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareEvent.file)
        assertNotNull(uri)
        assertTrue(uri.toString().startsWith("content://${context.packageName}.fileprovider"))

        val updatedDoc = db.documentDao().getDocumentById(docId)!!
        assertEquals(DocumentStatus.COMPLETED.name, updatedDoc.processingStatus)
        assertEquals(shareEvent.file.absolutePath, updatedDoc.exportedDocxPath)
        assertEquals(ExportMode.ACADEMIC_FORMAT.name, updatedDoc.exportedMode)
        assertEquals(1700000099000L, updatedDoc.exportedAtMillis)

        db.close()
    }

    @Test
    fun `word export includes all non-ignored review items even when they do not match raw OCR text`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-m11-leftover", "اكتمال_العناصر.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel(
                id = "p1",
                documentId = "doc-m11-leftover",
                pageNumber = 1,
                ocrText = "هذا النص الأصلي في الصفحة يظل محفوظاً بالكامل دون أي حذف.",
            ),
        )
        val items = listOf(
            ReviewItemModel(
                id = 1L,
                documentId = "doc-m11-leftover",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "جملة غير موجودة حرفياً",
                suggestedText = "عبارة معدلة من المراجع",
                ocrConfidence = 0.7f,
                geminiConfidence = 0.9f,
                evidence = "إضافة",
                bbox = null,
                reviewStatus = ReviewStatus.MANUALLY_EDITED,
                userText = "عبارة مضافة من المستخدم",
                resolvedAt = 100L,
                orderIndex = 0,
            ),
            ReviewItemModel(
                id = 2L,
                documentId = "doc-m11-leftover",
                pageNumber = 1,
                category = DiscrepancyCategory.D,
                ocrText = "كلمة متبقية غير مطابقة",
                suggestedText = null,
                ocrConfidence = 0.3f,
                geminiConfidence = 0.4f,
                evidence = "طمس",
                bbox = null,
                reviewStatus = ReviewStatus.UNRESOLVED,
                userText = null,
                resolvedAt = null,
                orderIndex = 1,
            ),
            ReviewItemModel(
                id = 3L,
                documentId = "doc-m11-leftover",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "عنصر مستبعد تماماً",
                suggestedText = "عنصر مستبعد تماماً",
                ocrConfidence = 0.8f,
                geminiConfidence = 0.8f,
                evidence = "مستبعد",
                bbox = null,
                reviewStatus = ReviewStatus.IGNORED,
                userText = null,
                resolvedAt = 150L,
                orderIndex = 2,
            ),
        )

        val outFile = File(context.filesDir, "m11_all_items.docx")
        generator.generate(doc, pages, items, WordExportMode.ACADEMIC, outFile, highlightUnresolved = true)

        ZipFile(outFile).use { zip ->
            val xmlString = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(xmlString.contains("هذا النص الأصلي في الصفحة يظل محفوظاً بالكامل دون أي حذف."))
            assertTrue(xmlString.contains("عبارة مضافة من المستخدم"))
            assertTrue(xmlString.contains("كلمة متبقية غير مطابقة"))
            assertFalse(xmlString.contains("عنصر مستبعد تماماً"))
        }
    }

    @Test
    fun `word generator inserts red completeness warning when page output is below 20 percent of other pages average`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-m11-complete", "فحص_الاكتمال.pdf", reviewApprovedAt = 1700000000000L)
        val denseText = "تنص المادة الأولى من القانون المدني على وجوب تنفيذ الالتزامات التعاقدية بحسن نية بين جميع الأطراف المتعاقدة وفق القواعد القانونية المرعية."
        val pages = listOf(
            DocumentPageModel("p1", "doc-m11-complete", pageNumber = 1, ocrText = "سطر قصير."),
            DocumentPageModel("p2", "doc-m11-complete", pageNumber = 2, ocrText = denseText),
            DocumentPageModel("p3", "doc-m11-complete", pageNumber = 3, ocrText = denseText),
        )

        val outFile = File(context.filesDir, "m11_completeness.docx")
        generator.generate(
            document = doc,
            pages = pages,
            reviewItems = emptyList(),
            mode = WordExportMode.ACADEMIC,
            outputFile = outFile,
            highlightUnresolved = true,
            includeWarnings = true,
        )

        ZipFile(outFile).use { zip ->
            val xmlString = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(xmlString.contains(WordGenerator.EXPORT_PAGE_WARNING_FOOTNOTE))
            assertTrue(xmlString.contains(WordGenerator.UNRESOLVED_TEXT_RED_HEX))
            // Page 1 flagged once; Pages 2 & 3 should not repeat the warning
            val count = Regex(Regex.escape(WordGenerator.EXPORT_PAGE_WARNING_FOOTNOTE)).findAll(xmlString).count()
            assertEquals(1, count)
        }
    }
}
