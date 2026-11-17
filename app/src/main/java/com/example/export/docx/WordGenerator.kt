package com.example.export.docx

import android.content.Context
import android.os.Environment
import com.example.export.domain.WordTextAssembler
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordDocumentPlan
import com.example.export.model.WordExportMode
import com.example.export.model.WordTextSegment
import com.example.review.model.ReviewItemModel
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.xmlbeans.XmlObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordGenerator(
    private val arabicFontName: String = DEFAULT_ARABIC_FONT,
) {

    fun generate(
        document: ExportDocumentModel,
        pages: List<DocumentPageModel>,
        reviewItems: List<ReviewItemModel>,
        mode: WordExportMode,
        outputFile: File,
        highlightUnresolved: Boolean = true,
    ): File {
        require(document.reviewApprovedAt != null) {
            "لا يمكن إنشاء ملف Word قبل اعتماد المراجعة."
        }

        val plan = WordTextAssembler.buildPlan(
            document = document,
            pages = pages,
            reviewItems = reviewItems,
            mode = mode,
            highlightUnresolved = highlightUnresolved,
        )

        outputFile.parentFile?.mkdirs()

        ensureStaxProvidersConfigured()

        val xwpfDocument = XWPFDocument()
        try {
            enforceDocumentRtlSection(xwpfDocument)
            writePlanToDocument(xwpfDocument, plan)
            FileOutputStream(outputFile).use { fos ->
                xwpfDocument.write(fos)
            }
        } finally {
            xwpfDocument.close()
        }

        return outputFile
    }

    fun buildOutputFile(
        context: Context,
        documentTitle: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "Documents")
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        val safeName = sanitizeFileName(documentTitle)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
        return File(documentsDir, "${safeName}_$timestamp.docx")
    }

    private fun writePlanToDocument(xwpfDocument: XWPFDocument, plan: WordDocumentPlan) {
        when (plan.mode) {
            WordExportMode.MATCH_ORIGINAL -> writeMatchOriginalMode(xwpfDocument, plan)
            WordExportMode.ACADEMIC -> writeAcademicMode(xwpfDocument, plan)
        }
    }

    private fun writeMatchOriginalMode(xwpfDocument: XWPFDocument, plan: WordDocumentPlan) {
        plan.pages.forEachIndexed { pageIdx, page ->
            if (page.paragraphs.isEmpty()) {
                val emptyParagraph = createRtlParagraph(xwpfDocument)
                createStyledRun(
                    paragraph = emptyParagraph,
                    text = "",
                    fontSize = 12,
                    isBold = false,
                    isUnresolvedHighlight = false,
                )
            } else {
                page.paragraphs.forEach { runs ->
                    val paragraph = createRtlParagraph(xwpfDocument)
                    paragraph.spacingAfter = 80
                    runs.forEach { segment ->
                        createStyledRun(
                            paragraph = paragraph,
                            text = segment.text,
                            fontSize = 12,
                            isBold = false,
                            isUnresolvedHighlight = segment.isUnresolvedHighlight,
                        )
                    }
                }
            }

            if (pageIdx < plan.pages.lastIndex) {
                val spacer = createRtlParagraph(xwpfDocument)
                spacer.spacingAfter = 160
            }
        }
    }

    private fun writeAcademicMode(xwpfDocument: XWPFDocument, plan: WordDocumentPlan) {
        val titleParagraph = createRtlParagraph(xwpfDocument).apply {
            spacingBefore = 120
            spacingAfter = 240
        }
        createStyledRun(
            paragraph = titleParagraph,
            text = plan.documentTitle.removeSuffix(".pdf").removeSuffix(".PDF"),
            fontSize = 20,
            isBold = true,
            isUnresolvedHighlight = false,
        )

        plan.pages.forEach { page ->
            val headingParagraph = createRtlParagraph(xwpfDocument).apply {
                spacingBefore = 200
                spacingAfter = 120
            }
            createStyledRun(
                paragraph = headingParagraph,
                text = "الصفحة ${page.pageNumber}",
                fontSize = 15,
                isBold = true,
                isUnresolvedHighlight = false,
            )

            page.paragraphs.forEach { runs ->
                writeAcademicSegmentParagraph(xwpfDocument, runs)
            }
        }
    }

    private fun writeAcademicSegmentParagraph(
        xwpfDocument: XWPFDocument,
        segments: List<WordTextSegment>,
    ) {
        val paragraph = createRtlParagraph(xwpfDocument).apply {
            spacingBefore = 60
            spacingAfter = 160
            setSpacingBetween(1.5)
        }
        segments.forEach { segment ->
            createStyledRun(
                paragraph = paragraph,
                text = segment.text,
                fontSize = 14,
                isBold = false,
                isUnresolvedHighlight = segment.isUnresolvedHighlight,
            )
        }
    }

    private fun createRtlParagraph(xwpfDocument: XWPFDocument): XWPFParagraph {
        val paragraph = xwpfDocument.createParagraph()
        paragraph.alignment = ParagraphAlignment.RIGHT
        val pPr = paragraph.ctp.pPr ?: paragraph.ctp.addNewPPr()
        appendWmlElement(pPr, "bidi")
        return paragraph
    }

    private fun createStyledRun(
        paragraph: XWPFParagraph,
        text: String,
        fontSize: Int,
        isBold: Boolean,
        isUnresolvedHighlight: Boolean,
    ): XWPFRun {
        val run = paragraph.createRun()
        run.fontSize = fontSize
        run.isBold = isBold
        run.setFontFamily(arabicFontName, XWPFRun.FontCharRange.ascii)
        run.setFontFamily(arabicFontName, XWPFRun.FontCharRange.hAnsi)
        run.setFontFamily(arabicFontName, XWPFRun.FontCharRange.cs)

        val rPr = run.ctr.rPr ?: run.ctr.addNewRPr()
        if (isUnresolvedHighlight) {
            run.color = UNRESOLVED_TEXT_RED_HEX
            appendWmlElementWithAttr(
                parent = rPr,
                localName = "highlight",
                attrName = "val",
                attrValue = "yellow",
            )
        }
        appendWmlElement(rPr, "rtl")
        appendRunTextElement(run, text)
        return run
    }

    private fun appendRunTextElement(run: XWPFRun, text: String) {
        val cursor = run.ctr.newCursor()
        try {
            cursor.toEndToken()
            cursor.beginElement("t", WML_NAMESPACE)
            if (text.startsWith(" ") || text.endsWith(" ") || text.contains("  ")) {
                cursor.insertAttributeWithValue("space", XML_NAMESPACE, "preserve")
            }
            if (text.isNotEmpty()) {
                cursor.insertChars(text)
            }
        } finally {
            cursor.dispose()
        }
    }

    private fun enforceDocumentRtlSection(xwpfDocument: XWPFDocument) {
        val body = xwpfDocument.document.body
        val sectPr = body.sectPr ?: body.addNewSectPr()
        appendWmlElement(sectPr, "bidi")
    }

    private fun appendWmlElement(parent: XmlObject, localName: String) {
        val cursor = parent.newCursor()
        try {
            cursor.toEndToken()
            cursor.beginElement(localName, WML_NAMESPACE)
        } finally {
            cursor.dispose()
        }
    }

    private fun appendWmlElementWithAttr(
        parent: XmlObject,
        localName: String,
        attrName: String,
        attrValue: String,
    ) {
        val cursor = parent.newCursor()
        try {
            cursor.toEndToken()
            cursor.beginElement(localName, WML_NAMESPACE)
            cursor.insertAttributeWithValue(attrName, WML_NAMESPACE, attrValue)
        } finally {
            cursor.dispose()
        }
    }

    private fun sanitizeFileName(rawTitle: String): String {
        val cleaned = rawTitle
            .trim()
            .removeSuffix(".pdf")
            .removeSuffix(".PDF")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
        return cleaned.ifBlank { "exported_document" }.take(48)
    }

    companion object {
        const val DEFAULT_ARABIC_FONT = "Traditional Arabic"
        const val UNRESOLVED_TEXT_RED_HEX = "B71C1C"
        private const val WML_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"

        fun ensureStaxProvidersConfigured() {
            setPropertyIfBlank(
                "org.apache.poi.javax.xml.stream.XMLInputFactory",
                "com.fasterxml.aalto.stax.InputFactoryImpl",
            )
            setPropertyIfBlank(
                "org.apache.poi.javax.xml.stream.XMLOutputFactory",
                "com.fasterxml.aalto.stax.OutputFactoryImpl",
            )
            setPropertyIfBlank(
                "org.apache.poi.javax.xml.stream.XMLEventFactory",
                "com.fasterxml.aalto.stax.EventFactoryImpl",
            )
        }

        private fun setPropertyIfBlank(key: String, value: String) {
            if (System.getProperty(key).isNullOrBlank()) {
                System.setProperty(key, value)
            }
        }
    }
}
