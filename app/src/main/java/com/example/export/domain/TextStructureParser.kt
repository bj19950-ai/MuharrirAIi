package com.example.export.domain

import com.example.export.model.WordTextSegment

enum class BlockType {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BODY,
    QUOTE,
    PAGE_BREAK,
}

data class StructuredBlock(
    val type: BlockType,
    val text: String,
    val pageNumber: Int = 1,
    val segments: List<WordTextSegment> = emptyList(),
)

/**
 * Module 19 — Layer 2 Structure Parser.
 *
 * Converts cleaned [OcrLine]s into structural [StructuredBlock]s:
 * - Detects HEADING_1, HEADING_2, HEADING_3 when BOTH structural pattern (Condition A)
 *   and font-size/short-line heuristic (Condition B) hold.
 * - Detects standalone QUOTE blocks ("«...»").
 * - Groups consecutive BODY lines into unified BODY paragraphs until sentence punctuation ('.', '؟', '!'),
 *   empty line separators, or a structural heading/quote boundary.
 * - Inserts PAGE_BREAK blocks between distinct page numbers.
 */
object TextStructureParser {

    fun parse(lines: List<OcrLine>): List<StructuredBlock> {
        if (lines.isEmpty()) return emptyList()
        return parseSegmentedLines(
            lines.map { line ->
                line to listOf(WordTextSegment(text = line.text, isUnresolvedHighlight = false))
            },
        )
    }

    fun parseSegmentedLines(
        lines: List<Pair<OcrLine, List<WordTextSegment>>>,
    ): List<StructuredBlock> {
        val nonEmpty = lines.filter { it.first.text.isNotBlank() }
        if (nonEmpty.isEmpty()) return emptyList()

        val medianFontSize = computeBaselineFontSize(nonEmpty.map { it.first })
        val blocks = mutableListOf<StructuredBlock>()

        val bodyTextBuffer = StringBuilder()
        val bodySegmentsBuffer = mutableListOf<WordTextSegment>()
        var bodyPageNumber = nonEmpty.first().first.pageNumber
        var previousPageNumber: Int? = null

        fun flushBodyBuffer() {
            if (bodyTextBuffer.isEmpty()) return
            val mergedText = bodyTextBuffer.toString().trim()
            if (mergedText.isNotEmpty()) {
                blocks.add(
                    StructuredBlock(
                        type = BlockType.BODY,
                        text = mergedText,
                        pageNumber = bodyPageNumber,
                        segments = mergeAdjacentSegments(bodySegmentsBuffer),
                    ),
                )
            }
            bodyTextBuffer.clear()
            bodySegmentsBuffer.clear()
        }

        for ((ocrLine, lineSegments) in nonEmpty) {
            val currentPage = ocrLine.pageNumber

            if (previousPageNumber != null && currentPage != previousPageNumber) {
                flushBodyBuffer()
                blocks.add(
                    StructuredBlock(
                        type = BlockType.PAGE_BREAK,
                        text = "",
                        pageNumber = currentPage,
                        segments = emptyList(),
                    ),
                )
            }
            previousPageNumber = currentPage

            if (ocrLine.precededByBlankLine) {
                flushBodyBuffer()
            }

            val hasHighlightedSegment = lineSegments.any { it.isUnresolvedHighlight }
            val lineType = classifyLine(
                line = ocrLine,
                medianFontSize = medianFontSize,
                hasHighlightedSegment = hasHighlightedSegment,
            )

            when (lineType) {
                BlockType.HEADING_1,
                BlockType.HEADING_2,
                BlockType.HEADING_3,
                BlockType.QUOTE -> {
                    flushBodyBuffer()
                    val resolvedSegments = lineSegments.ifEmpty {
                        listOf(WordTextSegment(ocrLine.text.trim(), isUnresolvedHighlight = false))
                    }
                    blocks.add(
                        StructuredBlock(
                            type = lineType,
                            text = ocrLine.text.trim(),
                            pageNumber = currentPage,
                            segments = resolvedSegments,
                        ),
                    )
                }

                BlockType.BODY -> {
                    val trimmed = ocrLine.text.trim()
                    if (isBulletLine(trimmed)) {
                        flushBodyBuffer()
                        val resolvedSegments = lineSegments.ifEmpty {
                            listOf(WordTextSegment(trimmed, isUnresolvedHighlight = false))
                        }
                        blocks.add(
                            StructuredBlock(
                                type = BlockType.BODY,
                                text = trimmed,
                                pageNumber = currentPage,
                                segments = resolvedSegments,
                            ),
                        )
                    } else {
                        if (bodyTextBuffer.isEmpty()) {
                            bodyPageNumber = currentPage
                        } else {
                            bodyTextBuffer.append(' ')
                            bodySegmentsBuffer.add(
                                WordTextSegment(text = " ", isUnresolvedHighlight = false),
                            )
                        }
                        bodyTextBuffer.append(trimmed)
                        if (lineSegments.isNotEmpty()) {
                            bodySegmentsBuffer.addAll(lineSegments)
                        } else {
                            bodySegmentsBuffer.add(
                                WordTextSegment(text = trimmed, isUnresolvedHighlight = false),
                            )
                        }

                        if (endsWithParagraphBreakPunctuation(trimmed)) {
                            flushBodyBuffer()
                        }
                    }
                }

                BlockType.PAGE_BREAK -> {
                    flushBodyBuffer()
                    blocks.add(
                        StructuredBlock(
                            type = BlockType.PAGE_BREAK,
                            text = "",
                            pageNumber = currentPage,
                            segments = emptyList(),
                        ),
                    )
                }
            }
        }

        flushBodyBuffer()
        return blocks
    }

    internal fun computeBaselineFontSize(lines: List<OcrLine>): Float? {
        val sizes = lines.mapNotNull { it.fontSizePx?.takeIf { size -> size > 0f } }.sorted()
        if (sizes.isEmpty()) return null
        if (sizes.size <= 2) return sizes.first()

        // Prefer dominant body font size (rounded mode) so pages with many headings don't inflate median
        val buckets = sizes.groupBy { kotlin.math.round(it) }
        val maxCount = buckets.values.maxOf { it.size }
        if (maxCount > 1) {
            val dominantBucket = buckets.entries
                .filter { it.value.size == maxCount }
                .minByOrNull { it.key }
                ?.value
            if (!dominantBucket.isNullOrEmpty()) {
                return dominantBucket.average().toFloat()
            }
        }

        return if (sizes.size % 2 == 1) {
            sizes[sizes.size / 2]
        } else {
            (sizes[sizes.size / 2 - 1] + sizes[sizes.size / 2]) / 2f
        }
    }

    private fun classifyLine(
        line: OcrLine,
        medianFontSize: Float?,
        hasHighlightedSegment: Boolean,
    ): BlockType {
        val trimmed = line.text.trim()
        if (trimmed.isEmpty()) return BlockType.BODY
        if (isBulletLine(trimmed)) return BlockType.BODY

        // Check standalone quote block ("«...»")
        if (isQuoteBlock(trimmed)) {
            return BlockType.QUOTE
        }

        if (hasHighlightedSegment) return BlockType.BODY
        if (trimmed.last() in DISQUALIFYING_HEADING_END_CHARS) return BlockType.BODY

        // Condition A: Heading pattern match or short colon-terminated title
        val conditionALevel = matchConditionAHeadingLevel(trimmed) ?: return BlockType.BODY

        // Anti-citation guard: e.g., "الفصل 213 م أ ج" inside body prose when not significantly enlarged
        val isLargerByFont = line.fontSizePx != null &&
            medianFontSize != null &&
            medianFontSize > 0f &&
            line.fontSizePx >= medianFontSize * HEADING_FONT_MULTIPLIER

        if (!isLargerByFont && isStatutoryCitation(trimmed)) {
            return BlockType.BODY
        }

        // Condition B: Larger font than body median (or heuristic fallback when font metrics are absent)
        val conditionB = when {
            line.fontSizePx != null && medianFontSize != null && medianFontSize > 0f -> {
                isLargerByFont
            }
            else -> {
                trimmed.length < FALLBACK_HEADING_MAX_LENGTH ||
                    (trimmed.endsWith(":") && trimmed.length < COLON_HEADING_MAX_LENGTH)
            }
        }

        return if (conditionB) conditionALevel else BlockType.BODY
    }

    private fun matchConditionAHeadingLevel(trimmed: String): BlockType? {
        if (HEADING_1_CHAPTER_REGEX.matches(trimmed) ||
            HEADING_1_SECTION_WORD_REGEX.matches(trimmed) ||
            HEADING_1_ORDINAL_FIRST_REGEX.matches(trimmed)
        ) {
            return BlockType.HEADING_1
        }
        if (HEADING_2_SUBSECTION_REGEX.matches(trimmed) ||
            HEADING_2_NUMBERED_REGEX.matches(trimmed) ||
            HEADING_2_ORDINAL_OTHER_REGEX.matches(trimmed)
        ) {
            return BlockType.HEADING_2
        }
        if (HEADING_3_ROMAN_REGEX.matches(trimmed)) {
            return BlockType.HEADING_3
        }
        if (trimmed.endsWith(":") && trimmed.length < COLON_HEADING_MAX_LENGTH) {
            // Standalone colon-terminated title (not a long clause ending in ":")
            val wordCount = trimmed.split(WHITESPACE_REGEX).count { it.isNotEmpty() }
            if (wordCount <= COLON_HEADING_MAX_WORDS && trimmed.count { it == '،' } == 0) {
                return BlockType.HEADING_3
            }
        }
        return null
    }

    private fun isStatutoryCitation(trimmed: String): Boolean {
        return STATUTORY_CITATION_REGEX.containsMatchIn(trimmed)
    }

    private fun isQuoteBlock(trimmed: String): Boolean {
        if (trimmed.length < MIN_QUOTE_LENGTH) return false
        if (trimmed.startsWith("«") && trimmed.endsWith("»")) return true
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) return true
        return false
    }

    private fun isBulletLine(trimmed: String): Boolean {
        return trimmed.isNotEmpty() && trimmed[0] in BULLET_CHARS
    }

    private fun endsWithParagraphBreakPunctuation(trimmed: String): Boolean {
        return trimmed.isNotEmpty() && trimmed.last() in PARAGRAPH_BREAK_PUNCTUATION
    }

    private fun mergeAdjacentSegments(segments: List<WordTextSegment>): List<WordTextSegment> {
        if (segments.isEmpty()) return emptyList()
        val merged = mutableListOf<WordTextSegment>()
        for (seg in segments) {
            if (seg.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.isUnresolvedHighlight == seg.isUnresolvedHighlight) {
                merged[merged.lastIndex] = last.copy(text = last.text + seg.text)
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    private const val HEADING_FONT_MULTIPLIER = 1.15f
    private const val FALLBACK_HEADING_MAX_LENGTH = 40
    private const val COLON_HEADING_MAX_LENGTH = 80
    private const val COLON_HEADING_MAX_WORDS = 8
    private const val MIN_QUOTE_LENGTH = 15

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val HEADING_1_CHAPTER_REGEX = Regex("^(?:الفصل|الباب|القسم)\\s+.+")
    private val HEADING_1_SECTION_WORD_REGEX = Regex("^(?:المقدمة|الخاتمة|الخلاصة|التمهيد)(?:\\s*[:\\-–]?\\s*|\\s+.+)$")
    private val HEADING_1_ORDINAL_FIRST_REGEX = Regex("^أول[اً]?(?:\\s*[:\\-–.]\\s*.*|\\s+.*|$)")
    private val HEADING_2_SUBSECTION_REGEX = Regex("^(?:المبحث|الفرع|البند)\\s+.+")
    private val HEADING_2_NUMBERED_REGEX = Regex("^\\d+\\s*[-–.]\\s+.+")
    private val HEADING_2_ORDINAL_OTHER_REGEX = Regex("^(?:ثاني[اً]?|ثالث[اً]?|رابع[اً]?|خامس[اً]?|سادس[اً]?)(?:\\s*[:\\-–.]\\s*.*|\\s+.*|$)")
    private val HEADING_3_ROMAN_REGEX = Regex("^(?:I{1,3}|IV|V|VI{0,3})[.)]\\s+.+")
    private val STATUTORY_CITATION_REGEX = Regex(
        "^(?:الفصل|المادة)\\s+\\d+\\s+(?:مكرر\\s+)?(?:م\\s*\\.?\\s*أ\\s*\\.?\\s*ج|م\\s*\\.?\\s*إ\\s*\\.?\\s*ع|ق\\s*\\.?\\s*م|من\\s+(?:القانون|المجلة|الدستور|المرسوم)|(?:[\\u0621-\\u064A]\\s+){2,})",
    )

    private val DISQUALIFYING_HEADING_END_CHARS = setOf('.', '،', '؛', '؟', '!')
    private val PARAGRAPH_BREAK_PUNCTUATION = setOf('.', '؟', '!')
    private val BULLET_CHARS = setOf('-', '•', '✓', '❖')
}
