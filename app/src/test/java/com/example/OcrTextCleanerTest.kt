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
    fun `cleanPageText removes noisy headers footers dates serials watermarks and merges broken lines`() {
        val noisySample = """
            جامعة سيدي محمد بن عبد الله - كلية العلوم القانونية
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

        // Verify all header/footer/watermark/serial noise lines are removed
        assertFalse(cleaned.contains("الصفحة1"))
        assertFalse(cleaned.contains("عسوتل0"))
        assertFalse(cleaned.contains("1150326"))
        assertFalse(cleaned.contains("301101006"))
        assertFalse(cleaned.contains("2025-4"))
        assertFalse(cleaned.contains("1:53"))
        assertFalse(cleaned.contains("Espace Juridique", ignoreCase = true))
        assertFalse(cleaned.contains("جامعة سيدي محمد"))
        assertFalse(cleaned.contains("اال"))

        val expectedLines = listOf(
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
    fun `word generator academic mode formats headings as 16pt bold and body paragraphs as 14pt`() {
        val generator = WordGenerator()
        val doc = ExportDocumentModel("doc-acad", "ملف_أكاديمي.pdf", reviewApprovedAt = 1700000000000L)
        val pages = listOf(
            DocumentPageModel(
                id = "p1",
                documentId = "doc-acad",
                pageNumber = 1,
                // Paragraph 1 (trailing colon -> standalone; or short heading on last line without punctuation)
                ocrText = """
                    يعد العقد شريعة المتعاقدين
                    ويجب تنفيذه بحسن نية.
                    أركان العقد الأساسية
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
                // Document title + Page 1 heading + Body paragraph + Heading paragraph
                assertEquals(4, paragraphs.length)

                // Check body paragraph ("يعد العقد شريعة المتعاقدين ويجب تنفيذه بحسن نية.") uses 14pt (w:sz val="28") and not bold
                val bodyParaXml = xmlBytes.toString(Charsets.UTF_8)
                assertTrue(bodyParaXml.contains("يعد العقد شريعة المتعاقدين ويجب تنفيذه بحسن نية."))
                assertTrue(bodyParaXml.contains("أركان العقد الأساسية"))
                // 14pt = 28 half-points, 16pt = 32 half-points
                assertTrue("Expected 14pt (w:val=\"28\") for academic body text", bodyParaXml.contains("w:val=\"28\""))
                assertTrue("Expected 16pt (w:val=\"32\") for academic heading", bodyParaXml.contains("w:val=\"32\""))
            }
        } finally {
            tempOut.delete()
        }
    }
}
