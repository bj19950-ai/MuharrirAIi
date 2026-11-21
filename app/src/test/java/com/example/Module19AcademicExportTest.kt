package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.PageFailureReasons
import com.example.domain.model.PageStatus
import com.example.domain.model.ProcessingPreferences
import com.example.export.docx.WordGenerator
import com.example.export.domain.BlockType
import com.example.export.domain.OcrLine
import com.example.export.domain.OcrTextCleaner
import com.example.export.domain.StructuredBlock
import com.example.export.domain.TextStructureParser
import com.example.export.domain.WordTextAssembler
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportDataGateway
import com.example.export.model.WordExportMode
import com.example.export.ui.PendingExportWarningAction
import com.example.export.ui.WordExportSection
import com.example.export.ui.WordExportViewModel
import com.example.review.model.ReviewItemModel
import com.example.review.model.SmartReviewPreferencesStore
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MuharrirTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Rule
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
class Module19AcademicExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        SmartReviewPreferencesStore.resetForTesting()
        context.getSharedPreferences(SmartReviewPreferencesStore.PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "Documents").deleteRecursively()
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.deleteRecursively()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SmartReviewPreferencesStore.resetForTesting()
        File(context.filesDir, "Documents").deleteRecursively()
        context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // LAYER 1: TEXT SANITIZER TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `layer 1 strips internal warnings, noise lines, page markers, duplicates, and fixes Arabic hamza`() {
        val raw = """
            تحذير: يبدو أن هذه الصفحة لم يتم استخراج نصها بالكامل. يرجى مراجعة الصورة الأصلية.
            :تحذير داخلي
            تم حذف جزء كبير من النص في هذه الصفحة، يرجى مراجعتها
            األحكام العامة واإلجراءات واآلثار
            األحكام العامة واإلجراءات واآلثار
            د بح بسح5
            اجا امار5
            لمعصعتندت
            الل لل الل الل
            xyz
            abcdef ح
            الصفحة 1
            الصفحة1
            صفحة 12
            تنص المادة 12 على وجوب تنفيذ العقد.
        """.trimIndent()

        val cleanedLines = OcrTextCleaner.cleanPageToOcrLines(raw, pageNumber = 1)
        val texts = cleanedLines.map { it.text }

        assertEquals(
            listOf(
                "الأحكام العامة والإجراءات والآثار",
                "تنص المادة 12 على وجوب تنفيذ العقد.",
            ),
            texts,
        )
        assertTrue(cleanedLines.all { it.pageNumber == 1 })
    }

    @Test
    fun `layer 1 preserves OcrLine metadata fontSizePx top and pageNumber`() {
        val source = listOf(
            OcrLine(text = "تحذير: نص مفقود يرجى مراجعتها", fontSizePx = 18f, top = 10, pageNumber = 3),
            OcrLine(text = "الفصل األول", fontSizePx = 28f, top = 50, pageNumber = 3),
            OcrLine(text = "الفصل األول", fontSizePx = 28f, top = 52, pageNumber = 3),
            OcrLine(text = "اجا امار5", fontSizePx = 18f, top = 90, pageNumber = 3),
            OcrLine(text = "يسري القانون اإلداري منذ تاريخ النشر.", fontSizePx = 18f, top = 120, pageNumber = 3),
        )

        val cleaned = OcrTextCleaner.cleanPageToOcrLines("", pageNumber = 3, sourceLines = source)
        assertEquals(2, cleaned.size)
        assertEquals("الفصل الأول", cleaned[0].text)
        assertEquals(28f, cleaned[0].fontSizePx)
        assertEquals(50, cleaned[0].top)
        assertEquals(3, cleaned[0].pageNumber)

        assertEquals("يسري القانون الإداري منذ تاريخ النشر.", cleaned[1].text)
        assertEquals(18f, cleaned[1].fontSizePx)
        assertEquals(120, cleaned[1].top)
        assertEquals(3, cleaned[1].pageNumber)
    }

    // -------------------------------------------------------------------------
    // LAYER 2: STRUCTURE PARSER TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `layer 2 detects heading levels, excludes statutory citations, groups body lines, and adds page breaks`() {
        val lines = listOf(
            OcrLine(text = "الفصل الأول", fontSizePx = 28f, top = 40, pageNumber = 1),
            OcrLine(text = "المبحث 1", fontSizePx = 24f, top = 90, pageNumber = 1),
            OcrLine(text = "1- خصائص قانون الإجراءات", fontSizePx = 23f, top = 130, pageNumber = 1),
            OcrLine(text = "I. الإطار التشريعي", fontSizePx = 22f, top = 170, pageNumber = 1),
            OcrLine(text = "ينطبق", fontSizePx = 18f, top = 210, pageNumber = 1),
            OcrLine(text = "الفصل 213 م أ ج", fontSizePx = 18f, top = 240, pageNumber = 1),
            OcrLine(text = "على النزاعات الجبائية.", fontSizePx = 18f, top = 270, pageNumber = 1),
            OcrLine(text = "«إن العدل أساس العمران واستقرار المعاملات»", fontSizePx = 17f, top = 320, pageNumber = 1),
            OcrLine(text = "الباب الثاني", fontSizePx = 28f, top = 40, pageNumber = 2),
            OcrLine(text = "تبدأ أحكام الصفحة الثانية هنا.", fontSizePx = 18f, top = 100, pageNumber = 2),
        )

        val blocks = TextStructureParser.parse(lines)

        assertEquals(
            listOf(
                BlockType.HEADING_1,
                BlockType.HEADING_2,
                BlockType.HEADING_2,
                BlockType.HEADING_3,
                BlockType.BODY,
                BlockType.QUOTE,
                BlockType.PAGE_BREAK,
                BlockType.HEADING_1,
                BlockType.BODY,
            ),
            blocks.map { it.type },
        )
        assertEquals("الفصل الأول", blocks[0].text)
        assertEquals("ينطبق الفصل 213 م أ ج على النزاعات الجبائية.", blocks[4].text)
        assertEquals("«إن العدل أساس العمران واستقرار المعاملات»", blocks[5].text)
        assertEquals(2, blocks[6].pageNumber)
    }

    @Test
    fun `layer 2 without fontSizePx still excludes statutory citation while detecting real headings`() {
        val lines = listOf(
            OcrLine(text = "المبحث 1", fontSizePx = null, pageNumber = 1),
            OcrLine(text = "الفصل 213 م أ ج", fontSizePx = null, pageNumber = 1),
            OcrLine(text = "ينص على القاعدة القانونية.", fontSizePx = null, pageNumber = 1),
        )

        val blocks = TextStructureParser.parse(lines)
        assertEquals(listOf(BlockType.HEADING_2, BlockType.BODY), blocks.map { it.type })
        assertEquals("الفصل 213 م أ ج ينص على القاعدة القانونية.", blocks[1].text)
    }

    // -------------------------------------------------------------------------
    // LAYER 3: WORD GENERATOR TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `layer 3 writes academic XML typography, Heading1-3 styles, RTL bidi, ar-TN, page breaks, and western digits`() {
        val blocks = listOf(
            StructuredBlock(BlockType.HEADING_1, "الفصل الأول", pageNumber = 1),
            StructuredBlock(BlockType.HEADING_2, "المبحث 1: القواعد العامة", pageNumber = 1),
            StructuredBlock(BlockType.HEADING_3, "I. الضمانات:", pageNumber = 1),
            StructuredBlock(BlockType.BODY, "تنص المادة 213 على حماية الحقوق 100 مرة.", pageNumber = 1),
            StructuredBlock(BlockType.QUOTE, "«الأصل براءة الذمة في كل الأحوال»", pageNumber = 1),
            StructuredBlock(BlockType.PAGE_BREAK, "", pageNumber = 2),
            StructuredBlock(BlockType.BODY, "تتمة النص في الصفحة الثانية.", pageNumber = 2),
        )

        val outFile = kotlin.io.path.createTempFile("m19_layer3_", ".docx").toFile()
        try {
            WordGenerator().generateFromBlocks(
                blocks = blocks,
                outputFile = outFile,
                includeWarnings = false,
            )

            ZipFile(outFile).use { zip ->
                val docXml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
                val stylesXml = zip.getInputStream(zip.getEntry("word/styles.xml")).readBytes().toString(Charsets.UTF_8)

                // Styles & Sizes
                assertTrue(docXml.contains("w:pStyle w:val=\"Heading1\"") || docXml.contains("Heading1"))
                assertTrue(docXml.contains("w:pStyle w:val=\"Heading2\"") || docXml.contains("Heading2"))
                assertTrue(docXml.contains("w:pStyle w:val=\"Heading3\"") || docXml.contains("Heading3"))
                assertTrue(stylesXml.contains("Heading1"))
                assertTrue(docXml.contains("Traditional Arabic"))
                assertTrue(docXml.contains("Simplified Arabic"))
                assertTrue(docXml.contains("w:val=\"36\"")) // H1 18pt
                assertTrue(docXml.contains("w:val=\"32\"")) // H2 16pt
                assertTrue(docXml.contains("w:val=\"28\"")) // H3 14pt
                assertTrue(docXml.contains("w:val=\"24\"")) // Body 12pt
                assertTrue(docXml.contains("w:val=\"20\"")) // Quote 10pt

                // Justification, spacing 1.5 (360), indents (709 firstLine, 567 quote)
                assertTrue(docXml.contains("w:val=\"center\""))
                assertTrue(docXml.contains("w:val=\"both\""))
                assertTrue(docXml.contains("w:line=\"360\""))
                assertTrue(docXml.contains("w:firstLine=\"709\""))
                assertTrue(docXml.contains("w:left=\"567\""))

                // RTL, ar-TN, page break
                assertTrue(docXml.contains("w:bidi w:val=\"1\""))
                assertTrue(docXml.contains("w:rtl w:val=\"1\""))
                assertTrue(docXml.contains("ar-TN"))
                assertTrue(docXml.contains("w:br w:type=\"page\"") || docXml.contains("type=\"page\""))

                // Western digits preserved
                assertTrue(docXml.contains("213"))
                assertTrue(docXml.contains("100"))
                assertFalse(docXml.contains("٢١٣"))

                // Clean export must have ZERO warning text or artificial "الصفحة 1"
                assertFalse(docXml.contains("تحذير"))
                assertFalse(docXml.contains("الصفحة 1"))
            }
        } finally {
            outFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // LAYER 4: PRE-EXPORT WARNING GATE & UI TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `layer 4 viewmodel gates export and share when warnings exist and toggle is ON, bypasses when OFF`() = runTest(mainDispatcher) {
        val doc = ExportDocumentModel("doc-l4", "دراسة.pdf", reviewApprovedAt = 1000L)
        val warnedPages = listOf(
            DocumentPageModel("p1", "doc-l4", 1, "نص طويل وكامل للمادة الأولى من الدراسة القانونية الموثقة.", hasMissingContent = true),
            DocumentPageModel("p2", "doc-l4", 2, "محتوى الصفحة الثانية.", pageStatus = PageStatus.FAILED, failureReason = PageFailureReasons.TIMEOUT),
            DocumentPageModel("p3", "doc-l4", 3, "سطر كامل وموثق لا توجد فيه أي مشاكل أو نواقص في النص العربي المقروء."),
        )
        var showWarningsPref = true
        val existingFile = File(context.filesDir, "existing_test.docx").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }

        val fakeGateway = object : WordExportDataGateway {
            override suspend fun getDocument(documentId: String) = doc
            override suspend fun getPagesInOrder(documentId: String) = warnedPages
            override suspend fun getReviewItemsInOrder(documentId: String): List<ReviewItemModel> = emptyList()
            override suspend fun shouldShowExportWarnings() = showWarningsPref
        }

        val vm = WordExportViewModel(dataGateway = fakeGateway, ioDispatcher = mainDispatcher, clock = { 5000L })

        // 1. Export button with toggle ON & warnings -> opens Pre-Export Warning Dialog (2 warned pages)
        vm.requestExport("doc-l4")
        advanceUntilIdle()
        val warningState = vm.uiState.value.warningDialogState
        assertNotNull(warningState)
        assertEquals(2, warningState!!.summary.warningPageCount)
        assertTrue(warningState.pendingAction is PendingExportWarningAction.OpenModeChooser)

        // 2. Dismiss ("العودة للمراجعة") cancels dialog without opening mode chooser
        vm.dismissWarningDialog()
        assertNull(vm.uiState.value.warningDialogState)
        assertFalse(vm.uiState.value.isModeDialogVisible)

        // 3. Choose "تصدير مع التحذيرات" -> opens mode chooser with pendingIncludeWarnings = true, exports footnote
        vm.requestExport("doc-l4")
        advanceUntilIdle()
        vm.confirmExportWarningChoice(context, includeWarnings = true)
        assertTrue(vm.uiState.value.isModeDialogVisible)
        assertTrue(vm.uiState.value.pendingIncludeWarnings)
        vm.exportDocument(context, "doc-l4", WordExportMode.ACADEMIC)
        advanceUntilIdle()
        val fileWithWarnings = vm.uiState.value.lastExportedFile!!
        ZipFile(fileWithWarnings).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(xml.contains(WordGenerator.EXPORT_PAGE_WARNING_FOOTNOTE))
        }

        // 4. Share existing DOCX with toggle ON -> also gated by warning dialog
        vm.requestShare("doc-l4", existingFile.absolutePath, "دراسة.pdf", WordExportMode.ACADEMIC)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.warningDialogState?.pendingAction is PendingExportWarningAction.ShareDocument)
        vm.confirmExportWarningChoice(context, includeWarnings = false)
        advanceUntilIdle()
        val cleanReexported = vm.uiState.value.lastExportedFile!!
        ZipFile(cleanReexported).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertFalse(xml.contains("تحذير"))
        }

        // 5. Toggle OFF -> opens mode chooser directly without warning dialog
        showWarningsPref = false
        vm.requestExport("doc-l4")
        advanceUntilIdle()
        assertNull(vm.uiState.value.warningDialogState)
        assertTrue(vm.uiState.value.isModeDialogVisible)
    }

    @Test
    fun `layer 4 UI renders pre-export warning dialog and settings export-warnings switch defaulted to ON`() {
        val store = SmartReviewPreferencesStore.getInstance(context)
        assertTrue(store.isShowExportWarningsEnabled())

        val doc = ExportDocumentModel("doc-ui", "عقد.pdf", reviewApprovedAt = 1000L)
        val pages = listOf(
            DocumentPageModel("p1", "doc-ui", 1, "نص صفحة فيها تحذير", failureReason = PageFailureReasons.USER_SKIPPED),
        )
        val gateway = object : WordExportDataGateway {
            override suspend fun getDocument(documentId: String) = doc
            override suspend fun getPagesInOrder(documentId: String) = pages
            override suspend fun getReviewItemsInOrder(documentId: String): List<ReviewItemModel> = emptyList()
            override suspend fun shouldShowExportWarnings() = store.isShowExportWarningsEnabled()
        }
        val vm = WordExportViewModel(dataGateway = gateway, ioDispatcher = mainDispatcher)

        composeRule.setContent {
            MuharrirTheme {
                WordExportSection(
                    documentId = "doc-ui",
                    documentTitle = "عقد.pdf",
                    isReviewApproved = true,
                    preferredExportMode = com.example.domain.model.ExportMode.ACADEMIC_FORMAT,
                    exportedDocument = null,
                    exportViewModel = vm,
                    onOpenReviewSummary = {},
                )
            }
        }

        composeRule.onNodeWithTag("export_to_word_button").performClick()
        mainDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pre_export_warning_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("تنبيه قبل التصدير").assertIsDisplayed()
        composeRule.onNodeWithTag("export_clean_only_button").assertIsDisplayed()
        composeRule.onNodeWithTag("export_with_warnings_button").assertIsDisplayed()
        composeRule.onNodeWithTag("return_to_review_button").assertIsDisplayed()
    }

    @Test
    fun `settings screen shows export warnings toggle defaulted to ON`() {
        composeRule.setContent {
            MuharrirTheme {
                SettingsScreen(
                    preferences = ProcessingPreferences(),
                    geminiKeys = emptyList(),
                    geminiUsageStats = GeminiUsageStats(),
                    testingKeyId = null,
                    noticeMessageAr = null,
                    onDismissNotice = {},
                    onAddGeminiKey = { _, _ -> },
                    onTestGeminiKey = {},
                    onToggleGeminiKey = { _, _ -> },
                    onRemoveGeminiKey = {},
                    onSelectGeminiModel = {},
                    onSelectOcrEngine = {},
                    onSelectExportMode = {},
                    onToggleHighlightUnresolved = {},
                    onToggleAutoSave = {},
                    onToggleResumeAfterInterruption = {},
                    showExportWarnings = true,
                    onToggleShowExportWarnings = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_show_export_warnings_switch"))
        composeRule.onNodeWithText("إظهار تحذيرات التصدير").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_show_export_warnings_switch").assertIsOn()
    }

    // -------------------------------------------------------------------------
    // END-TO-END INTEGRATION TEST (RAW DIRTY OCR -> LAYERS 1..3 -> CLEAN DOCX)
    // -------------------------------------------------------------------------

    @Test
    fun `full pipeline transforms dirty multi-page OCR into clean structured academic DOCX`() {
        val doc = ExportDocumentModel("doc-e2e", "ملخص_قانوني.pdf", reviewApprovedAt = 123456L)
        val rawPage1 = """
            تحذير: يبدو أن هذه الصفحة لم يتم استخراج نصها بالكامل.
            الفصل األول
            المبحث 1: مصادر الالتزام
            د بح بسح5
            اجا امار5
            لمعصعتندت
            الل لل الل الل
            يعد العقد توافق إرادتين على إنشاء التزام
            قانوني بموجب الفصل 213 م أ ج دون إكراه.
            يعد العقد توافق إرادتين على إنشاء التزام قانوني بموجب الفصل 213 م أ ج دون إكراه.
            الصفحة 1
        """.trimIndent()
        val rawPage2 = """
            تم حذف جزء كبير من النص يرجى مراجعتها
            الباب الثاني
            1- خصائص القانون المدني
            تهدف اإلصلاحات التشريعية إلى تحقيق التوازن
            واآلثار الإيجابية في المعاملات التجارية.
            الصفحة 2
        """.trimIndent()

        val pages = listOf(
            DocumentPageModel("p1", "doc-e2e", 1, rawPage1, hasMissingContent = true),
            DocumentPageModel("p2", "doc-e2e", 2, rawPage2),
        )

        val outFile = kotlin.io.path.createTempFile("m19_e2e_clean_", ".docx").toFile()
        try {
            WordGenerator().generate(
                document = doc,
                pages = pages,
                reviewItems = emptyList(),
                mode = WordExportMode.ACADEMIC,
                outputFile = outFile,
                includeWarnings = false,
            )

            ZipFile(outFile).use { zip ->
                val xmlBytes = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes()
                val xmlString = xmlBytes.toString(Charsets.UTF_8)
                val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(xmlBytes.inputStream())

                val textNodes = dom.getElementsByTagNameNS("*", "t")
                val plainText = buildString {
                    for (i in 0 until textNodes.length) {
                        append(textNodes.item(i).textContent).append(' ')
                    }
                }.trim()

                // Hamza fixed, no noise, no internal warnings, no "الصفحة 1" text
                assertTrue(plainText.contains("الفصل الأول"))
                assertTrue(plainText.contains("المبحث 1: مصادر الالتزام"))
                assertTrue(plainText.contains("ينطبق") || plainText.contains("يعد العقد توافق إرادتين"))
                assertTrue(plainText.contains("الفصل 213 م أ ج"))
                assertTrue(plainText.contains("الباب الثاني"))
                assertTrue(plainText.contains("الإصلاحات"))
                assertTrue(plainText.contains("والآثار"))

                assertFalse(plainText.contains("تحذير"))
                assertFalse(plainText.contains("الصفحة 1"))
                assertFalse(plainText.contains("الصفحة 2"))
                assertFalse(plainText.contains("د بح بسح5"))
                assertFalse(plainText.contains("اجا امار5"))
                assertFalse(plainText.contains("لمعصعتندت"))
                assertFalse(plainText.contains("الل لل الل الل"))
                assertFalse(plainText.contains("األ"))
                assertFalse(plainText.contains("اإل"))
                assertFalse(plainText.contains("اآل"))

                // Page break exists between page 1 and page 2
                assertTrue(xmlString.contains("w:br w:type=\"page\"") || xmlString.contains("type=\"page\""))
            }
        } finally {
            outFile.delete()
        }
    }
}
