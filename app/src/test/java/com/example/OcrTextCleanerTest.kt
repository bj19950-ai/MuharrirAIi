package com.example

import com.example.export.docx.WordGenerator
import com.example.export.domain.OcrTextCleaner
import com.example.export.domain.WordTextAssembler
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportMode
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class OcrTextCleanerTest {

    @Test
    fun `cleanPageText removes exact noise headers dates serials watermarks while retaining edge content and merging broken lines`() {
        val noisySample = """
            جامعة سيدي محمد بن عبد الله - كلية العلوم القانونية:
            2025-4-18   1:53
            Espace Juridique
            
            يعد العقد توافق إرادتين على إنشاء
            االلتزام القانوني بين الأطراف المتعاقدة
            .
            فما هي شروط صحة االتفاق
            القانوني؟
            وتنطبق قواعد االحق المدني في الحالات التالية:
            - أهلية التعاقد الكاملة
            • سلامة الرضا من العيوب
            ✓ مشروعية المحل والسبب
            ❖ احترام النظام العام
            
            ويمتد أثر العقد لاللتزام بتنفيذه بحسن نية
            خلال المدة المتفق عليها
            "
            
            عسوتل0
            1150326
            301101006
            الصفحة1
        """.trimIndent()

        val cleaned = OcrTextCleaner.cleanPageText(noisySample, pageNumber = 1)
        val lines = cleaned.split("\n")

        // Exact metadata lines are removed
        assertFalse(cleaned.contains("الصفحة1"))
        assertFalse(cleaned.contains("عسوتل0"))
        assertFalse(cleaned.contains("1150326"))
        assertFalse(cleaned.contains("301101006"))
        assertFalse(cleaned.contains("2025-4"))
        assertFalse(cleaned.contains("1:53"))
        assertFalse(cleaned.contains("Espace Juridique", ignoreCase = true))
        assertFalse(cleaned.contains("اال"))

        // Top-edge real text is retained cleanly
        assertTrue(cleaned.contains("جامعة سيدي محمد بن عبد الله - كلية العلوم القانونية:"))

        val expectedLines = listOf(
            "جامعة سيدي محمد بن عبد الله - كلية العلوم القانونية:",
            "يعد العقد توافق إرادتين على إنشاء الالتزام القانوني بين الأطراف المتعاقدة.",
            "فما هي شروط صحة التفاق القانوني؟",
            "وتنطبق قواعد الحق المدني في الحالات التالية:",
            "- أهلية التعاقد الكاملة",
            "• سلامة الرضا من العيوب",
            "✓ مشروعية المحل والسبب",
            "❖ احترام النظام العام",
            "ويمتد أثر العقد للالتزام بتنفيذه بحسن نية خلال المدة المتفق عليها\"",
        )
        assertEquals(expectedLines, lines)
    }

    @Test
    fun `cleanPageText retains paragraphs near top and bottom edges of page`() {
        val edgeSample = """
            الفصل الأول في النظرية العامة للالتزامات والعقود المدنية
            والتجارية المعاصرة.
            جامعة القاضي عياض بمراكش تفتح شعبة الدراسات القانونية.
            المادة 12 من القانون المدني تنص على وجوب احترام العقد.
        """.trimIndent()

        val cleaned = OcrTextCleaner.cleanPageText(edgeSample, pageNumber = 1)
        assertTrue(cleaned.contains("الفصل الأول في النظرية العامة للالتزامات والعقود المدنية والتجارية المعاصرة."))
        assertTrue(cleaned.contains("جامعة القاضي عياض بمراكش تفتح شعبة الدراسات القانونية."))
        assertTrue(cleaned.contains("المادة 12 من القانون المدني تنص على وجوب احترام العقد."))
    }

    @Test
    fun `cleanPageText strips only metadata from mixed lines and retains surrounding text`() {
        val mixedSample = """
            2025-4 النظرية العامة للالتزام
            Espace Juridique أركان العقد الصحيح 1:53
            صفحة 4
            3
        """.trimIndent()

        val cleaned = OcrTextCleaner.cleanPageText(mixedSample, pageNumber = 3)
        assertFalse(cleaned.contains("2025-4"))
        assertFalse(cleaned.contains("1:53"))
        assertFalse(cleaned.contains("Espace Juridique", ignoreCase = true))
        assertFalse(cleaned.contains("صفحة 4"))
        assertEquals("النظرية العامة للالتزام أركان العقد الصحيح", cleaned)
    }

    @Test
    fun `cleanArabicText fixes اال and word-initial لال ligatures without corrupting valid words`() {
        assertEquals("الالتزام", OcrTextCleaner.cleanArabicText("االلتزام"))
        assertEquals("للالتزام", OcrTextCleaner.cleanArabicText("لاللتزام"))
        assertEquals("للمرجع خلال مدة الاستقلال", OcrTextCleaner.cleanArabicText("لالمرجع خلال مدة الاستقلال"))
        assertEquals("والالتزام بالحق", OcrTextCleaner.cleanArabicText("وااللتزام   باالحق"))
    }

    @Test
    fun `word text assembler cleans raw OCR pages and review item texts before building sections`() {
        val doc = ExportDocumentModel("doc-m09", "قانون_الالتزامات.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel(
                id = "p1",
                documentId = "doc-m09",
                pageNumber = 1,
                ocrText = """
                    Espace Juridique
                    2025-4 1:53
                    يخضع العقد لقواعد
                    االلتزام العامة.
                    الصفحة1
                """.trimIndent(),
            ),
            DocumentPageModel(
                id = "p2",
                documentId = "doc-m09",
                pageNumber = 2,
                ocrText = "بديل قصير",
            ),
        )
        val reviewItems = listOf(
            ReviewItemModel(
                id = 1L,
                documentId = "doc-m09",
                pageNumber = 2,
                category = DiscrepancyCategory.B,
                ocrText = "االلتزام",
                suggestedText = "الالتزام",
                ocrConfidence = 0.8f,
                geminiConfidence = 0.96f,
                evidence = "تصحيح همزة/لام",
                bbox = null,
                reviewStatus = ReviewStatus.APPROVED_GEMINI,
                userText = "االلتزام التعاقدي",
                resolvedAt = 2000L,
                orderIndex = 0,
            ),
            ReviewItemModel(
                id = 2L,
                documentId = "doc-m09",
                pageNumber = 2,
                category = DiscrepancyCategory.D,
                ocrText = "واالعتبار",
                suggestedText = null,
                ocrConfidence = 0.4f,
                geminiConfidence = 0.3f,
                evidence = "طمس",
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
            reviewItems = reviewItems,
            mode = WordExportMode.ACADEMIC,
            highlightUnresolved = true,
        )

        assertEquals(2, plan.pages.size)

        // Page 1 (raw OCR): noise stripped, lines merged, "االلتزام" fixed
        val page1Segments = plan.pages[0].segments
        assertEquals(1, page1Segments.size)
        assertEquals("يخضع العقد لقواعد الالتزام العامة.", page1Segments[0].text)

        // Page 2 (review items): individual item texts cleaned of "اال" errors
        val page2Segments = plan.pages[1].segments
        assertEquals("الالتزام التعاقدي", page2Segments[0].text)
        assertEquals("والعتبار", page2Segments[1].text)
        assertTrue(page2Segments[1].isUnresolvedHighlight)
    }

    @Test
    fun `word generator academic mode formats headings as bold Traditional Arabic and body paragraphs as 12pt Simplified Arabic`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-acad", "ملف_أكاديمي.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel(
                id = "p1",
                documentId = "doc-acad",
                pageNumber = 1,
                ocrText = """
                    الفصل الأول
                    يعد العقد شريعة المتعاقدين
                    ويجب تنفيذه بحسن نية.
                    المبحث الأول
                """.trimIndent(),
            ),
        )

        val tempOut = kotlin.io.path.createTempFile("m09_academic_", ".docx").toFile()
        try {
            generator.generate(
                document = doc,
                pages = pages,
                reviewItems = emptyList(),
                mode = WordExportMode.ACADEMIC,
                outputFile = tempOut,
                highlightUnresolved = true,
            )

            ZipFile(tempOut).use { zip ->
                val xmlBytes = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes()
                val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(xmlBytes.inputStream())

                val paragraphs = dom.getElementsByTagNameNS("*", "p")
                // H1 + Body + H2 (no artificial Page 1 / filename header)
                assertEquals(3, paragraphs.length)

                val bodyParaXml = xmlBytes.toString(Charsets.UTF_8)
                assertTrue(bodyParaXml.contains("يعد العقد شريعة المتعاقدين ويجب تنفيذه بحسن نية."))
                assertTrue(bodyParaXml.contains("الفصل الأول"))
                assertTrue(bodyParaXml.contains("المبحث الأول"))
                assertFalse(bodyParaXml.contains("الصفحة 1"))
                // 12pt = 24 half-points (body), 16pt = 32 (H2), 18pt = 36 (H1)
                assertTrue("Expected 12pt (w:val=\"24\") for academic body text", bodyParaXml.contains("w:val=\"24\""))
                assertTrue("Expected 16pt (w:val=\"32\") for H2 heading", bodyParaXml.contains("w:val=\"32\""))
                assertTrue("Expected 18pt (w:val=\"36\") for H1 heading", bodyParaXml.contains("w:val=\"36\""))
                assertFalse(bodyParaXml.contains(WordGenerator.EXPORT_PAGE_WARNING_FOOTNOTE))
                assertFalse(bodyParaXml.contains("تحذير"))
            }
        } finally {
            tempOut.delete()
        }
    }

    @Test
    fun `word text assembler retains surrounding page text even when review items cover most of page`() {
        val doc = ExportDocumentModel("doc-full", "مقارن.pdf", reviewApprovedAt = 1700000000000L)
        val pageText = "مقدمة: العقد شريعة المتعاقدين ويجب تنفيذه بحسن نية كاملة."
        val pages = listOf(DocumentPageModel("p1", "doc-full", pageNumber = 1, ocrText = pageText))
        val items = listOf(
            ReviewItemModel(
                id = 1L,
                documentId = "doc-full",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "العقد شريعة المتعاقدين ويجب تنفيذه بحسن",
                suggestedText = "العقد شريعة المتعاقدين ويلتزم بتنفيذه بحسن",
                ocrConfidence = 0.85f,
                geminiConfidence = 0.95f,
                evidence = "دقة الصياغة",
                bbox = null,
                reviewStatus = ReviewStatus.APPROVED_GEMINI,
                userText = "العقد شريعة المتعاقدين ويلتزم بتنفيذه بحسن",
                resolvedAt = 2000L,
                orderIndex = 0,
            ),
        )

        val plan = WordTextAssembler.buildPlan(doc, pages, items, WordExportMode.ACADEMIC)
        val combined = plan.pages.single().segments.joinToString("") { it.text }
        assertTrue(combined.contains("مقدمة:"))
        assertTrue(combined.contains("العقد شريعة المتعاقدين ويلتزم بتنفيذه بحسن"))
        assertTrue(combined.contains("نية كاملة."))
    }

    @Test
    fun `word generator writes red warning note when includeWarnings is true and cleaned text drops below 80 percent of raw OCR`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-loss", "تحذير_فقد.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel(
                id = "p1",
                documentId = "doc-loss",
                pageNumber = 1,
                ocrText = """
                    Espace Juridique
                    2025-4 1:53
                    1150326 301101006
                    عسوتل0
                    النص القصير المتبقي.
                """.trimIndent(),
            ),
        )

        val tempOut = kotlin.io.path.createTempFile("m10_loss_warning_", ".docx").toFile()
        try {
            generator.generate(
                document = doc,
                pages = pages,
                reviewItems = emptyList(),
                mode = WordExportMode.MATCH_ORIGINAL,
                outputFile = tempOut,
                includeWarnings = true,
            )
            ZipFile(tempOut).use { zip ->
                val xmlString = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(xmlString.contains(WordGenerator.EXPORT_PAGE_WARNING_FOOTNOTE))
                assertTrue(xmlString.contains(WordGenerator.UNRESOLVED_TEXT_RED_HEX))
                assertTrue(xmlString.contains("النص القصير المتبقي."))
            }
        } finally {
            tempOut.delete()
        }
    }
}
