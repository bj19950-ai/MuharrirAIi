package com.example.export.domain

import com.example.domain.model.PageStatus
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.ExportWarningSummary
import com.example.export.model.WordDocumentPlan
import com.example.export.model.WordExportMode
import com.example.export.model.WordPageSection
import com.example.export.model.WordTextSegment
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus

/**
 * Pure Kotlin assembler (Modules 07–19) that orders pages by pageNumber and items by reading order,
 * cleans text with Layer 1 ([OcrTextCleaner]), parses academic structure with Layer 2 ([TextStructureParser]),
 * selects userText for approved/manually-edited items, and flags unresolved items and page warnings.
 */
object WordTextAssembler {

    fun buildPlan(
        document: ExportDocumentModel,
        pages: List<DocumentPageModel>,
        reviewItems: List<ReviewItemModel>,
        mode: WordExportMode,
        highlightUnresolved: Boolean = true,
    ): WordDocumentPlan {
        val sortedPages = pages.sortedBy { it.pageNumber }
        val itemsByPage = reviewItems
            .groupBy { it.pageNumber }
            .mapValues { (_, items) ->
                items.sortedWith(
                    compareBy<ReviewItemModel>(
                        { it.orderIndex },
                        { it.bbox?.top ?: Float.MAX_VALUE },
                        { -(it.bbox?.right ?: 0f) },
                        { it.id },
                    ),
                )
            }

        val allStructuredInputLines = mutableListOf<Pair<OcrLine, List<WordTextSegment>>>()

        val sections = sortedPages.map { page ->
            val pageItems = itemsByPage[page.pageNumber].orEmpty()
            val cleanedOcrLines = OcrTextCleaner.cleanPageToOcrLines(
                rawText = page.ocrText,
                pageNumber = page.pageNumber,
                sourceLines = page.ocrLines,
            )
            val cleanedPageOcr = OcrTextCleaner.cleanPageText(page.ocrText, page.pageNumber)
            val paragraphs: List<List<WordTextSegment>>
            val segments: List<WordTextSegment>

            when {
                pageItems.isEmpty() -> {
                    val rawSegments = buildSegmentsFromRawPageOcr(cleanedPageOcr)
                    segments = rawSegments
                    paragraphs = rawSegments.map { listOf(it) }
                    cleanedOcrLines.forEach { ocrLine ->
                        allStructuredInputLines.add(
                            ocrLine to listOf(WordTextSegment(ocrLine.text, isUnresolvedHighlight = false)),
                        )
                    }
                }

                shouldSpliceIntoFullPageOcr(cleanedPageOcr, pageItems) -> {
                    paragraphs = spliceItemsIntoFullPageOcr(cleanedPageOcr, pageItems, highlightUnresolved)
                    segments = paragraphs.flatten()

                    val structuredSpliced = if (cleanedOcrLines.isNotEmpty()) {
                        spliceItemsIntoOcrLines(cleanedOcrLines, pageItems, highlightUnresolved)
                    } else {
                        paragraphs.map { paraSegs ->
                            OcrLine(
                                text = paraSegs.joinToString("") { it.text },
                                pageNumber = page.pageNumber,
                            ) to paraSegs
                        }
                    }
                    allStructuredInputLines.addAll(structuredSpliced)
                }

                else -> {
                    val resolved = pageItems.mapNotNull { item -> resolveItemSegment(item, highlightUnresolved) }
                    segments = resolved
                    paragraphs = resolved.map { listOf(it) }
                    resolved.forEach { seg ->
                        allStructuredInputLines.add(
                            OcrLine(
                                text = seg.text,
                                pageNumber = page.pageNumber,
                            ) to listOf(seg),
                        )
                    }
                }
            }

            val rawNonWhitespaceCount = page.ocrText.count { !it.isWhitespace() }
            val cleanedNonWhitespaceCount = cleanedPageOcr.count { !it.isWhitespace() }
            val textLossWarning = if (
                rawNonWhitespaceCount > 0 &&
                cleanedNonWhitespaceCount < (rawNonWhitespaceCount * TEXT_LOSS_WARNING_RATIO)
            ) {
                TEXT_LOSS_WARNING_MESSAGE
            } else {
                null
            }

            WordPageSection(
                pageNumber = page.pageNumber,
                segments = segments,
                paragraphs = paragraphs,
                textLossWarning = textLossWarning,
                hasMissingContent = page.hasMissingContent,
                pageStatus = page.pageStatus,
                failureReason = page.failureReason,
            )
        }

        val academicBlocks = if (mode == WordExportMode.ACADEMIC) {
            TextStructureParser.parseSegmentedLines(allStructuredInputLines)
        } else {
            emptyList()
        }

        return WordDocumentPlan(
            documentTitle = document.title.ifBlank { "مستند مراجع" },
            mode = mode,
            pages = sections,
            academicBlocks = academicBlocks,
        )
    }

    fun inspectWarnings(
        pages: List<DocumentPageModel>,
        reviewItems: List<ReviewItemModel> = emptyList(),
    ): ExportWarningSummary {
        if (pages.isEmpty()) return ExportWarningSummary()
        val plan = buildPlan(
            document = ExportDocumentModel(id = "", title = "", reviewApprovedAt = 0L),
            pages = pages,
            reviewItems = reviewItems,
            mode = WordExportMode.MATCH_ORIGINAL,
            highlightUnresolved = false,
        )

        val missingContentPages = plan.pages
            .filter { it.hasMissingContent }
            .map { it.pageNumber }
            .toSet()

        val failedOrSkippedPages = plan.pages
            .filter { it.pageStatus == PageStatus.FAILED || !it.failureReason.isNullOrBlank() }
            .map { it.pageNumber }
            .toSet()

        val criticalCleaningPages = plan.pages
            .filter { it.textLossWarning != null }
            .map { it.pageNumber }
            .toSet()

        val shortPages = findShortPages(plan.pages)

        val allWarned = buildSet {
            addAll(missingContentPages)
            addAll(failedOrSkippedPages)
            addAll(criticalCleaningPages)
            addAll(shortPages)
        }

        return ExportWarningSummary(
            warningPageNumbers = allWarned,
            missingContentPages = missingContentPages,
            failedOrSkippedPages = failedOrSkippedPages,
            shortPages = shortPages,
            criticalCleaningPages = criticalCleaningPages,
        )
    }

    internal fun findShortPages(pages: List<WordPageSection>): Set<Int> {
        if (pages.size <= 1) return emptySet()
        val charCounts = pages.map { page ->
            page.paragraphs.sumOf { runs -> runs.sumOf { it.text.length } }
        }
        return pages.indices.mapNotNull { idx ->
            val otherSum = charCounts.sum() - charCounts[idx]
            val otherAvg = otherSum.toDouble() / (pages.size - 1)
            if (otherAvg > 0.0 && charCounts[idx] < (otherAvg * SHORT_PAGE_THRESHOLD_RATIO)) {
                pages[idx].pageNumber
            } else {
                null
            }
        }.toSet()
    }

    private fun shouldSpliceIntoFullPageOcr(
        cleanedOcrText: String,
        pageItems: List<ReviewItemModel>,
    ): Boolean {
        val trimmedOcr = cleanedOcrText.trim()
        if (trimmedOcr.isEmpty()) return false
        val hasDirectMatch = pageItems.any { item ->
            candidateNeedles(item).any { needle -> trimmedOcr.contains(needle) }
        }
        return hasDirectMatch || trimmedOcr.length >= MIN_FULL_PAGE_OCR_LENGTH
    }

    private fun spliceItemsIntoOcrLines(
        ocrLines: List<OcrLine>,
        pageItems: List<ReviewItemModel>,
        highlightUnresolved: Boolean,
    ): List<Pair<OcrLine, List<WordTextSegment>>> {
        val remainingItems = pageItems.toMutableList()
        val result = mutableListOf<Pair<OcrLine, List<WordTextSegment>>>()

        for (ocrLine in ocrLines) {
            val lineSegments = spliceSingleLineText(ocrLine.text, remainingItems, highlightUnresolved)
            if (lineSegments.isNotEmpty()) {
                val updatedText = lineSegments.joinToString("") { it.text }.trim()
                result.add(ocrLine.copy(text = updatedText) to lineSegments)
            }
        }

        remainingItems.forEach { leftover ->
            resolveItemSegment(leftover, highlightUnresolved)?.let { seg ->
                val pageNum = ocrLines.firstOrNull()?.pageNumber ?: leftover.pageNumber
                result.add(
                    OcrLine(text = seg.text, pageNumber = pageNum) to listOf(seg),
                )
            }
        }

        return result
    }

    private fun spliceItemsIntoFullPageOcr(
        cleanedOcrText: String,
        pageItems: List<ReviewItemModel>,
        highlightUnresolved: Boolean,
    ): List<List<WordTextSegment>> {
        val remainingItems = pageItems.toMutableList()
        val paragraphsText = cleanedOcrText
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val resultParagraphs = mutableListOf<List<WordTextSegment>>()

        for (paragraphLine in paragraphsText) {
            val lineSegments = spliceSingleLineText(paragraphLine, remainingItems, highlightUnresolved)
            if (lineSegments.isNotEmpty()) {
                resultParagraphs.add(lineSegments)
            }
        }

        // Preserve any item whose ocrText was not matched verbatim in cleaned OCR paragraphs
        remainingItems.forEach { leftover ->
            resolveItemSegment(leftover, highlightUnresolved)?.let { seg ->
                resultParagraphs.add(listOf(seg))
            }
        }

        return resultParagraphs
    }

    private fun spliceSingleLineText(
        paragraphLine: String,
        remainingItems: MutableList<ReviewItemModel>,
        highlightUnresolved: Boolean,
    ): List<WordTextSegment> {
        val lineSegments = mutableListOf<WordTextSegment>()
        var cursor = 0

        while (cursor < paragraphLine.length && remainingItems.isNotEmpty()) {
                var earliestMatchPos = Int.MAX_VALUE
                var matchedItemIndex = -1
                var matchedNeedleLength = 0

                for (idx in remainingItems.indices) {
                    for (needle in candidateNeedles(remainingItems[idx])) {
                        val pos = paragraphLine.indexOf(needle, startIndex = cursor)
                        if (pos >= 0 && pos < earliestMatchPos) {
                            earliestMatchPos = pos
                            matchedItemIndex = idx
                            matchedNeedleLength = needle.length
                        }
                    }
                }

                if (matchedItemIndex < 0) break

                val item = remainingItems.removeAt(matchedItemIndex)

                if (earliestMatchPos > cursor) {
                    val prefix = OcrTextCleaner.cleanArabicText(
                        paragraphLine.substring(cursor, earliestMatchPos),
                    )
                    val leadingSpace = if (paragraphLine[cursor].isWhitespace() && !prefix.startsWith(" ")) " " else ""
                    val trailingSpace = if (
                        earliestMatchPos > cursor &&
                        paragraphLine[earliestMatchPos - 1].isWhitespace() &&
                        prefix.isNotEmpty() &&
                        !prefix.endsWith(" ")
                    ) {
                        " "
                    } else {
                        ""
                    }
                    val formattedPrefix = if (prefix.isEmpty()) {
                        if (paragraphLine.substring(cursor, earliestMatchPos).isNotEmpty()) " " else ""
                    } else {
                        "$leadingSpace$prefix$trailingSpace"
                    }
                    if (formattedPrefix.isNotEmpty()) {
                        lineSegments.add(
                            WordTextSegment(
                                text = formattedPrefix,
                                isUnresolvedHighlight = false,
                            ),
                        )
                    }
                }

                resolveItemSegment(item, highlightUnresolved)?.let { resolved ->
                    lineSegments.add(resolved)
                }

                cursor = earliestMatchPos + matchedNeedleLength
            }

        if (cursor < paragraphLine.length) {
            val rawSuffix = paragraphLine.substring(cursor)
            val cleanedSuffix = OcrTextCleaner.cleanArabicText(rawSuffix)
            val leadingSpace = if (rawSuffix.firstOrNull()?.isWhitespace() == true && cleanedSuffix.isNotEmpty()) " " else ""
            val formattedSuffix = "$leadingSpace$cleanedSuffix"
            if (formattedSuffix.isNotEmpty()) {
                lineSegments.add(
                    WordTextSegment(
                        text = formattedSuffix,
                        isUnresolvedHighlight = false,
                    ),
                )
            }
        }

        return lineSegments
    }

    private fun candidateNeedles(item: ReviewItemModel): List<String> {
        val rawNeedle = item.ocrText.trim()
        val singleLineNeedle = rawNeedle.replace(WHITESPACE_REGEX, " ").trim()
        val cleanedNeedle = OcrTextCleaner.cleanArabicText(singleLineNeedle)
        return buildList {
            if (rawNeedle.isNotEmpty()) add(rawNeedle)
            if (singleLineNeedle.isNotEmpty() && singleLineNeedle !in this) add(singleLineNeedle)
            if (cleanedNeedle.isNotEmpty() && cleanedNeedle !in this) add(cleanedNeedle)
        }
    }

    private fun buildSegmentsFromRawPageOcr(cleanedOcrText: String): List<WordTextSegment> {
        if (cleanedOcrText.isBlank()) return emptyList()
        return cleanedOcrText
            .split(Regex("\\r?\\n"))
            .map { OcrTextCleaner.cleanArabicText(it) }
            .filter { it.isNotEmpty() }
            .map { paragraph ->
                WordTextSegment(
                    text = paragraph,
                    isUnresolvedHighlight = false,
                )
            }
    }

    private fun resolveItemSegment(
        item: ReviewItemModel,
        highlightUnresolved: Boolean,
    ): WordTextSegment? {
        if (item.reviewStatus == ReviewStatus.IGNORED) {
            return null
        }

        return when {
            item.isStale ||
                item.reviewStatus == ReviewStatus.UNRESOLVED ||
                item.reviewStatus == ReviewStatus.PENDING ||
                item.reviewStatus == ReviewStatus.STALE -> {
                val rawFallback = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val unresolvedText = OcrTextCleaner.cleanArabicText(rawFallback)
                    .ifBlank { rawFallback.trim() }
                WordTextSegment(
                    text = unresolvedText,
                    isUnresolvedHighlight = highlightUnresolved,
                )
            }

            item.reviewStatus == ReviewStatus.APPROVED_GEMINI ||
                item.reviewStatus == ReviewStatus.MANUALLY_EDITED -> {
                val rawApproved = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.suggestedText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText
                val cleaned = OcrTextCleaner.cleanArabicText(rawApproved)
                    .ifBlank { rawApproved.trim() }
                WordTextSegment(
                    text = cleaned,
                    isUnresolvedHighlight = false,
                )
            }

            item.category == DiscrepancyCategory.A -> {
                val rawAgreement = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val finalText = OcrTextCleaner.cleanArabicText(rawAgreement)
                    .ifBlank { rawAgreement.trim() }
                WordTextSegment(
                    text = finalText,
                    isUnresolvedHighlight = false,
                )
            }

            else -> {
                val rawOther = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val fallback = OcrTextCleaner.cleanArabicText(rawOther)
                    .ifBlank { rawOther.trim() }
                WordTextSegment(
                    text = fallback,
                    isUnresolvedHighlight = false,
                )
            }
        }
    }

    private const val TEXT_LOSS_WARNING_RATIO = 0.80
    private const val SHORT_PAGE_THRESHOLD_RATIO = 0.20
    private const val MIN_FULL_PAGE_OCR_LENGTH = 20
    private val WHITESPACE_REGEX = Regex("\\s+")
    const val TEXT_LOSS_WARNING_MESSAGE =
        "تحذير: تم حذف جزء كبير من النص في هذه الصفحة أثناء التنظيف، يرجى مراجعتها"
}
