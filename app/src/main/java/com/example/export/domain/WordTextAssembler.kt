package com.example.export.domain

import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordDocumentPlan
import com.example.export.model.WordExportMode
import com.example.export.model.WordPageSection
import com.example.export.model.WordTextSegment
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus

/**
 * Pure Kotlin assembler that orders pages by pageNumber and items by reading order,
 * selecting userText for approved/manually-edited items, ocrText when no review was needed,
 * and flagging unresolved/pending/stale items for highlighting in Word.
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

        val sections = sortedPages.map { page ->
            val pageItems = itemsByPage[page.pageNumber].orEmpty()
            val paragraphs: List<List<WordTextSegment>>
            val segments: List<WordTextSegment>

            when {
                pageItems.isEmpty() -> {
                    val rawSegments = buildSegmentsFromRawPageOcr(page.ocrText)
                    segments = rawSegments
                    paragraphs = rawSegments.map { listOf(it) }
                }

                shouldSpliceIntoFullPageOcr(page.ocrText, pageItems) -> {
                    paragraphs = spliceItemsIntoFullPageOcr(page.ocrText, pageItems, highlightUnresolved)
                    segments = paragraphs.flatten()
                }

                else -> {
                    val resolved = pageItems.mapNotNull { item -> resolveItemSegment(item, highlightUnresolved) }
                    segments = resolved
                    paragraphs = resolved.map { listOf(it) }
                }
            }

            WordPageSection(
                pageNumber = page.pageNumber,
                segments = segments,
                paragraphs = paragraphs,
            )
        }

        return WordDocumentPlan(
            documentTitle = document.title.ifBlank { "مستند مراجع" },
            mode = mode,
            pages = sections,
        )
    }

    private fun shouldSpliceIntoFullPageOcr(
        rawOcrText: String,
        pageItems: List<ReviewItemModel>,
    ): Boolean {
        val trimmedOcr = rawOcrText.trim()
        if (trimmedOcr.isEmpty()) return false
        val itemsOcrLength = pageItems.sumOf { it.ocrText.trim().length }.coerceAtLeast(1)
        val hasDirectMatch = pageItems.any { item ->
            val needle = item.ocrText.trim()
            needle.isNotEmpty() && trimmedOcr.contains(needle)
        }
        return hasDirectMatch && trimmedOcr.length > (itemsOcrLength * 1.4f)
    }

    private fun spliceItemsIntoFullPageOcr(
        rawOcrText: String,
        pageItems: List<ReviewItemModel>,
        highlightUnresolved: Boolean,
    ): List<List<WordTextSegment>> {
        val remainingItems = pageItems.toMutableList()
        val lines = rawOcrText
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val resultParagraphs = mutableListOf<List<WordTextSegment>>()

        for (line in lines) {
            val lineSegments = mutableListOf<WordTextSegment>()
            var cursor = 0

            while (cursor < line.length && remainingItems.isNotEmpty()) {
                var earliestMatchPos = Int.MAX_VALUE
                var matchedItemIndex = -1

                for (idx in remainingItems.indices) {
                    val needle = remainingItems[idx].ocrText.trim()
                    if (needle.isEmpty()) continue
                    val pos = line.indexOf(needle, startIndex = cursor)
                    if (pos >= 0 && pos < earliestMatchPos) {
                        earliestMatchPos = pos
                        matchedItemIndex = idx
                    }
                }

                if (matchedItemIndex < 0) break

                val item = remainingItems.removeAt(matchedItemIndex)
                val needle = item.ocrText.trim()

                if (earliestMatchPos > cursor) {
                    lineSegments.add(
                        WordTextSegment(
                            text = line.substring(cursor, earliestMatchPos),
                            isUnresolvedHighlight = false,
                        ),
                    )
                }

                resolveItemSegment(item, highlightUnresolved)?.let { resolved ->
                    lineSegments.add(resolved)
                }

                cursor = earliestMatchPos + needle.length
            }

            if (cursor < line.length) {
                lineSegments.add(
                    WordTextSegment(
                        text = line.substring(cursor),
                        isUnresolvedHighlight = false,
                    ),
                )
            }

            if (lineSegments.isNotEmpty()) {
                resultParagraphs.add(lineSegments)
            }
        }

        // Preserve any item whose ocrText was not matched verbatim in full OCR lines
        remainingItems.forEach { leftover ->
            resolveItemSegment(leftover, highlightUnresolved)?.let { seg ->
                resultParagraphs.add(listOf(seg))
            }
        }

        return resultParagraphs
    }

    private fun buildSegmentsFromRawPageOcr(rawOcrText: String): List<WordTextSegment> {
        if (rawOcrText.isBlank()) return emptyList()
        return rawOcrText
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                WordTextSegment(
                    text = line,
                    isUnresolvedHighlight = false,
                )
            }
    }

    private fun resolveItemSegment(
        item: ReviewItemModel,
        highlightUnresolved: Boolean,
    ): WordTextSegment? {
        return when {
            item.isStale ||
                item.reviewStatus == ReviewStatus.UNRESOLVED ||
                item.reviewStatus == ReviewStatus.PENDING ||
                item.reviewStatus == ReviewStatus.STALE -> {
                val unresolvedText = item.ocrText.ifBlank { item.suggestedText.orEmpty() }.trim()
                if (unresolvedText.isEmpty()) {
                    null
                } else {
                    WordTextSegment(
                        text = unresolvedText,
                        isUnresolvedHighlight = highlightUnresolved,
                    )
                }
            }

            item.reviewStatus == ReviewStatus.APPROVED_GEMINI ||
                item.reviewStatus == ReviewStatus.MANUALLY_EDITED -> {
                val approvedText = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.suggestedText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText
                val trimmed = approvedText.trim()
                if (trimmed.isEmpty()) {
                    null
                } else {
                    WordTextSegment(
                        text = trimmed,
                        isUnresolvedHighlight = false,
                    )
                }
            }

            item.category == DiscrepancyCategory.A -> {
                val finalText = (item.userText?.takeIf { it.isNotBlank() } ?: item.ocrText).trim()
                if (finalText.isEmpty()) {
                    null
                } else {
                    WordTextSegment(
                        text = finalText,
                        isUnresolvedHighlight = false,
                    )
                }
            }

            else -> {
                val fallback = item.ocrText.trim()
                if (fallback.isEmpty()) {
                    null
                } else {
                    WordTextSegment(text = fallback, isUnresolvedHighlight = false)
                }
            }
        }
    }
}
