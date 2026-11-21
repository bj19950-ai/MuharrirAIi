package com.example.export.docx

import android.content.Context
import android.os.Environment
import com.example.export.domain.BlockType
import com.example.export.domain.StructuredBlock
import com.example.export.domain.TextStructureParser
import com.example.export.domain.WordTextAssembler
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordDocumentPlan
import com.example.export.model.WordExportMode
import com.example.export.model.WordTextSegment
import com.example.review.model.ReviewItemModel
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFStyle
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordGenerator(
    private val arabicFontName: String = DEFAULT_BODY_FONT,
) {

    fun generate(
        document: ExportDocumentModel,
        pages: List<DocumentPageModel>,
        reviewItems: List<ReviewItemModel>,
        mode: WordExportMode,
        outputFile: File,
        highlightUnresolved: Boolean = true,
        includeWarnings: Boolean = false,
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

        val warningSummary = if (includeWarnings) {
            WordTextAssembler.inspectWarnings(pages, reviewItems)
        } else {
            null
        }

        outputFile.parentFile?.mkdirs()
        ensureStaxProvidersConfigured()

        val xwpfDocument = XWPFDocument()
        try {
            registerAcademicStyles(xwpfDocument)
            enforceDocumentRtlSection(xwpfDocument)
            writePlanToDocument(
                xwpfDocument = xwpfDocument,
                plan = plan,
                includeWarnings = includeWarnings,
                warningPageNumbers = warningSummary?.warningPageNumbers ?: emptySet(),
            )
            FileOutputStream(outputFile).use { fos ->
                xwpfDocument.write(fos)
            }
        } finally {
            xwpfDocument.close()
        }

        return outputFile
    }

    fun generateFromBlocks(
        blocks: List<StructuredBlock>,
        outputFile: File,
        includeWarnings: Boolean = false,
        warningPageNumbers: Set<Int> = emptySet(),
    ): File {
        outputFile.parentFile?.mkdirs()
        ensureStaxProvidersConfigured()

        val xwpfDocument = XWPFDocument()
        try {
            registerAcademicStyles(xwpfDocument)
            enforceDocumentRtlSection(xwpfDocument)
            writeAcademicBlocks(
                xwpfDocument = xwpfDocument,
                blocks = blocks,
                includeWarnings = includeWarnings,
                warningPageNumbers = warningPageNumbers,
            )
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

    private fun writePlanToDocument(
        xwpfDocument: XWPFDocument,
        plan: WordDocumentPlan,
        includeWarnings: Boolean,
        warningPageNumbers: Set<Int>,
    ) {
        when (plan.mode) {
            WordExportMode.MATCH_ORIGINAL -> writeMatchOriginalMode(
                xwpfDocument = xwpfDocument,
                plan = plan,
                includeWarnings = includeWarnings,
                warningPageNumbers = warningPageNumbers,
            )
            WordExportMode.ACADEMIC -> {
                val blocks = if (plan.academicBlocks.isNotEmpty()) {
                    plan.academicBlocks
                } else {
                    fallbackBlocksFromPlan(plan)
                }
                writeAcademicBlocks(
                    xwpfDocument = xwpfDocument,
                    blocks = blocks,
                    includeWarnings = includeWarnings,
                    warningPageNumbers = warningPageNumbers,
                )
            }
        }
    }

    private fun writeMatchOriginalMode(
        xwpfDocument: XWPFDocument,
        plan: WordDocumentPlan,
        includeWarnings: Boolean,
        warningPageNumbers: Set<Int>,
    ) {
        val font = resolveBodyFont()
        plan.pages.forEachIndexed { pageIdx, page ->
            if (page.paragraphs.isEmpty()) {
                val emptyParagraph = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT)
                createStyledRun(
                    paragraph = emptyParagraph,
                    text = "",
                    fontFamily = font,
                    fontSize = ACADEMIC_BODY_FONT_SIZE_PT,
                    isBold = false,
                    isUnresolvedHighlight = false,
                )
            } else {
                page.paragraphs.forEach { runs ->
                    val paragraph = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT)
                    paragraph.spacingAfter = 100
                    runs.forEach { segment ->
                        createStyledRun(
                            paragraph = paragraph,
                            text = segment.text,
                            fontFamily = font,
                            fontSize = ACADEMIC_BODY_FONT_SIZE_PT,
                            isBold = false,
                            isUnresolvedHighlight = segment.isUnresolvedHighlight,
                        )
                    }
                }
            }

            if (includeWarnings && (page.pageNumber in warningPageNumbers || page.hasCriticalWarning)) {
                writePageWarningFootnote(xwpfDocument)
            }

            if (pageIdx < plan.pages.lastIndex) {
                writePageBreak(xwpfDocument)
            }
        }
    }

    private fun writeAcademicBlocks(
        xwpfDocument: XWPFDocument,
        blocks: List<StructuredBlock>,
        includeWarnings: Boolean,
        warningPageNumbers: Set<Int>,
    ) {
        if (blocks.isEmpty()) return

        var currentPageNumber = blocks.firstOrNull { it.type != BlockType.PAGE_BREAK }?.pageNumber ?: 1
        var pageHasWarningWritten = false

        fun flushPageWarningIfNeeded(pageNum: Int) {
            if (includeWarnings && !pageHasWarningWritten && pageNum in warningPageNumbers) {
                writePageWarningFootnote(xwpfDocument)
                pageHasWarningWritten = true
            }
        }

        for (block in blocks) {
            if (block.type == BlockType.PAGE_BREAK) {
                flushPageWarningIfNeeded(currentPageNumber)
                writePageBreak(xwpfDocument)
                currentPageNumber = block.pageNumber
                pageHasWarningWritten = false
                continue
            }

            if (block.pageNumber != currentPageNumber) {
                flushPageWarningIfNeeded(currentPageNumber)
                currentPageNumber = block.pageNumber
                pageHasWarningWritten = false
            }

            writeStructuredBlock(xwpfDocument, block)
        }

        flushPageWarningIfNeeded(currentPageNumber)
    }

    private fun writeStructuredBlock(
        xwpfDocument: XWPFDocument,
        block: StructuredBlock,
    ) {
        val runs = block.segments.ifEmpty {
            listOf(WordTextSegment(text = block.text, isUnresolvedHighlight = false))
        }
        val bodyFont = resolveBodyFont()
        val headingFont = resolveHeadingFont()

        when (block.type) {
            BlockType.HEADING_1 -> {
                val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.CENTER, styleId = "Heading1").apply {
                    spacingBefore = 240
                    spacingAfter = 140
                }
                runs.forEach { seg ->
                    createStyledRun(
                        paragraph = p,
                        text = seg.text,
                        fontFamily = headingFont,
                        fontSize = ACADEMIC_H1_FONT_SIZE_PT,
                        isBold = true,
                        isUnresolvedHighlight = seg.isUnresolvedHighlight,
                    )
                }
            }

            BlockType.HEADING_2 -> {
                val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT, styleId = "Heading2").apply {
                    spacingBefore = 200
                    spacingAfter = 100
                }
                runs.forEach { seg ->
                    createStyledRun(
                        paragraph = p,
                        text = seg.text,
                        fontFamily = headingFont,
                        fontSize = ACADEMIC_H2_FONT_SIZE_PT,
                        isBold = true,
                        isUnresolvedHighlight = seg.isUnresolvedHighlight,
                    )
                }
            }

            BlockType.HEADING_3 -> {
                val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT, styleId = "Heading3").apply {
                    spacingBefore = 160
                    spacingAfter = 80
                }
                runs.forEach { seg ->
                    createStyledRun(
                        paragraph = p,
                        text = seg.text,
                        fontFamily = headingFont,
                        fontSize = ACADEMIC_H3_FONT_SIZE_PT,
                        isBold = true,
                        isUnresolvedHighlight = seg.isUnresolvedHighlight,
                    )
                }
            }

            BlockType.BODY -> {
                val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.BOTH).apply {
                    spacingBefore = 40
                    spacingAfter = 120
                    setSpacingBetween(BODY_LINE_SPACING)
                    indentationFirstLine = FIRST_LINE_INDENT_TWIPS
                }
                runs.forEach { seg ->
                    createStyledRun(
                        paragraph = p,
                        text = seg.text,
                        fontFamily = bodyFont,
                        fontSize = ACADEMIC_BODY_FONT_SIZE_PT,
                        isBold = false,
                        isUnresolvedHighlight = seg.isUnresolvedHighlight,
                    )
                }
            }

            BlockType.QUOTE -> {
                val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.BOTH).apply {
                    spacingBefore = 100
                    spacingAfter = 120
                    indentationLeft = QUOTE_SIDE_INDENT_TWIPS
                    indentationRight = QUOTE_SIDE_INDENT_TWIPS
                }
                runs.forEach { seg ->
                    createStyledRun(
                        paragraph = p,
                        text = seg.text,
                        fontFamily = bodyFont,
                        fontSize = ACADEMIC_QUOTE_FONT_SIZE_PT,
                        isBold = false,
                        isItalic = true,
                        isUnresolvedHighlight = seg.isUnresolvedHighlight,
                    )
                }
            }

            BlockType.PAGE_BREAK -> {
                writePageBreak(xwpfDocument)
            }
        }
    }

    private fun fallbackBlocksFromPlan(plan: WordDocumentPlan): List<StructuredBlock> {
        val result = mutableListOf<StructuredBlock>()
        plan.pages.forEachIndexed { idx, page ->
            page.paragraphs.forEach { segs ->
                val text = segs.joinToString("") { it.text }.trim()
                if (text.isNotEmpty()) {
                    val parsed = TextStructureParser.parseSegmentedLines(
                        listOf(
                            com.example.export.domain.OcrLine(
                                text = text,
                                pageNumber = page.pageNumber,
                            ) to segs,
                        ),
                    )
                    result.addAll(parsed)
                }
            }
            if (idx < plan.pages.lastIndex) {
                result.add(
                    StructuredBlock(
                        type = BlockType.PAGE_BREAK,
                        text = "",
                        pageNumber = plan.pages[idx + 1].pageNumber,
                    ),
                )
            }
        }
        return result
    }

    private fun writePageBreak(xwpfDocument: XWPFDocument) {
        val p = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT)
        p.createRun().addBreak(BreakType.PAGE)
    }

    private fun writePageWarningFootnote(xwpfDocument: XWPFDocument) {
        val warningParagraph = createRtlParagraph(xwpfDocument, ParagraphAlignment.RIGHT).apply {
            spacingBefore = 80
            spacingAfter = 100
        }
        createStyledRun(
            paragraph = warningParagraph,
            text = EXPORT_PAGE_WARNING_FOOTNOTE,
            fontFamily = resolveBodyFont(),
            fontSize = 10,
            isBold = true,
            isItalic = false,
            isUnresolvedHighlight = false,
            textColorHex = UNRESOLVED_TEXT_RED_HEX,
        )
    }

    internal fun findIncompletePages(pages: List<com.example.export.model.WordPageSection>): Set<Int> {
        return WordTextAssembler.findShortPages(pages)
    }

    private fun registerAcademicStyles(xwpfDocument: XWPFDocument) {
        val styles = xwpfDocument.createStyles()
        addHeadingStyle(styles, "Heading1", "Heading 1", ACADEMIC_H1_FONT_SIZE_PT)
        addHeadingStyle(styles, "Heading2", "Heading 2", ACADEMIC_H2_FONT_SIZE_PT)
        addHeadingStyle(styles, "Heading3", "Heading 3", ACADEMIC_H3_FONT_SIZE_PT)
    }

    private fun addHeadingStyle(
        styles: org.apache.poi.xwpf.usermodel.XWPFStyles,
        styleId: String,
        styleName: String,
        fontSizePt: Int,
    ) {
        if (styles.styleExist(styleId)) return
        val ctStyle = CTStyle.Factory.newInstance().apply {
            this.styleId = styleId
            type = STStyleType.PARAGRAPH
            addNewName().setVal(styleName)
            val pPr = addNewPPr()
            appendWmlElementWithAttr(pPr, "bidi", "val", "1")
            val rPr = addNewRPr()
            rPr.addNewB()
            rPr.addNewBCs()
            val halfPoints = BigInteger.valueOf((fontSizePt * 2).toLong())
            rPr.addNewSz().setVal(halfPoints)
            rPr.addNewSzCs().setVal(halfPoints)
            val fonts = rPr.addNewRFonts()
            fonts.ascii = DEFAULT_HEADING_FONT
            fonts.hAnsi = DEFAULT_HEADING_FONT
            fonts.cs = DEFAULT_HEADING_FONT
        }
        styles.addStyle(XWPFStyle(ctStyle))
    }

    private fun createRtlParagraph(
        xwpfDocument: XWPFDocument,
        alignment: ParagraphAlignment,
        styleId: String? = null,
    ): XWPFParagraph {
        val paragraph = xwpfDocument.createParagraph()
        paragraph.alignment = alignment
        if (!styleId.isNullOrBlank()) {
            paragraph.style = styleId
        }
        val pPr = paragraph.ctp.pPr ?: paragraph.ctp.addNewPPr()
        appendWmlElementWithAttr(pPr, "bidi", "val", "1")
        return paragraph
    }

    private fun createStyledRun(
        paragraph: XWPFParagraph,
        text: String,
        fontFamily: String,
        fontSize: Int,
        isBold: Boolean,
        isItalic: Boolean = false,
        isUnresolvedHighlight: Boolean,
        textColorHex: String? = null,
    ): XWPFRun {
        val run = paragraph.createRun()
        run.fontSize = fontSize
        run.isBold = isBold
        run.isItalic = isItalic
        run.setFontFamily(fontFamily, XWPFRun.FontCharRange.ascii)
        run.setFontFamily(fontFamily, XWPFRun.FontCharRange.hAnsi)
        run.setFontFamily(fontFamily, XWPFRun.FontCharRange.cs)

        val rPr = run.ctr.rPr ?: run.ctr.addNewRPr()
        if (!rPr.xmlText().contains("szCs")) {
            appendWmlElementWithAttr(rPr, "szCs", "val", (fontSize * 2).toString())
        }
        if (isUnresolvedHighlight) {
            run.color = UNRESOLVED_TEXT_RED_HEX
            appendWmlElementWithAttr(
                parent = rPr,
                localName = "highlight",
                attrName = "val",
                attrValue = "yellow",
            )
        } else if (textColorHex != null) {
            run.color = textColorHex
        }
        appendWmlElementWithAttr(rPr, "rtl", "val", "1")
        appendArabicLanguageElement(rPr, DEFAULT_DOCUMENT_LANG)
        appendRunTextElement(run, text)
        return run
    }

    private fun resolveHeadingFont(): String =
        if (arabicFontName != DEFAULT_BODY_FONT) arabicFontName else DEFAULT_HEADING_FONT

    private fun resolveBodyFont(): String = arabicFontName

    private fun appendArabicLanguageElement(parent: XmlObject, langTag: String) {
        val cursor = parent.newCursor()
        try {
            cursor.toEndToken()
            cursor.beginElement("lang", WML_NAMESPACE)
            cursor.insertAttributeWithValue("val", WML_NAMESPACE, langTag)
            cursor.insertAttributeWithValue("bidi", WML_NAMESPACE, langTag)
        } finally {
            cursor.dispose()
        }
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
        appendWmlElementWithAttr(sectPr, "bidi", "val", "1")
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
        const val DEFAULT_HEADING_FONT = "Traditional Arabic"
        const val DEFAULT_BODY_FONT = "Simplified Arabic"
        const val DEFAULT_ARABIC_FONT = DEFAULT_BODY_FONT
        const val DEFAULT_DOCUMENT_LANG = "ar-TN"
        const val UNRESOLVED_TEXT_RED_HEX = "B71C1C"

        const val ACADEMIC_H1_FONT_SIZE_PT = 18
        const val ACADEMIC_H2_FONT_SIZE_PT = 16
        const val ACADEMIC_H3_FONT_SIZE_PT = 14
        const val ACADEMIC_BODY_FONT_SIZE_PT = 12
        const val ACADEMIC_QUOTE_FONT_SIZE_PT = 10
        const val ACADEMIC_HEADING_FONT_SIZE_PT = ACADEMIC_H2_FONT_SIZE_PT

        const val BODY_LINE_SPACING = 1.5
        const val FIRST_LINE_INDENT_TWIPS = 709 // ~1.25 cm
        const val QUOTE_SIDE_INDENT_TWIPS = 567 // ~1.00 cm

        const val EXPORT_PAGE_WARNING_FOOTNOTE =
            "[تحذير: قد يكون نص هذه الصفحة ناقصاً - راجع الصورة الأصلية]"
        const val INCOMPLETE_PAGE_WARNING_MESSAGE = EXPORT_PAGE_WARNING_FOOTNOTE

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

