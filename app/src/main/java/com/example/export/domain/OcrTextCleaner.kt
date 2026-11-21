package com.example.export.domain

/**
 * Preserves cleaned per-line text alongside optional visual/layout metadata extracted from OCR.
 */
data class OcrLine(
    val text: String,
    val fontSizePx: Float? = null,
    val top: Int? = null,
    val pageNumber: Int = 1,
    val precededByBlankLine: Boolean = false,
)

/**
 * Module 09 / 10 / 19 — Text Sanitizer & Safe Reconstruction Cleanup (Layer 1).
 *
 * Cleans raw per-page OCR output before structure parsing and Word (.docx) export:
 * 1a. Removes internal warnings ("تحذير...", "يرجى مراجعة...", "لم يتم استخراج نصها بالكامل", etc.).
 * 1b. Fixes broken Arabic hamza and ligature errors ("األ" -> "الأ", "اإل" -> "الإ", "اآل" -> "الآ", "اال" -> "ال", "لال" -> "لل").
 * 1c. Removes OCR gibberish / noise lines without losing valid Arabic headings, bullets, or prose.
 * 1d. Removes standalone page markers ("الصفحة 1", "الصفحة1", etc.) and metadata tokens.
 * 1e. De-duplicates consecutive identical lines within a page.
 * 1f. Preserves line-level metadata (fontSizePx, top, pageNumber) via [OcrLine].
 */
class OcrTextCleaner {

    fun cleanPageText(rawText: String, pageNumber: Int): String {
        val cleanedLines = cleanPageToOcrLines(rawText = rawText, pageNumber = pageNumber)
        if (cleanedLines.isEmpty()) return ""

        val paragraphs = mutableListOf<String>()
        val currentParagraph = StringBuilder()

        for (ocrLine in cleanedLines) {
            val line = ocrLine.text
            if (isBulletLine(line)) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(currentParagraph.toString().trim())
                    currentParagraph.clear()
                }
                paragraphs.add(line)
                continue
            }

            if (ocrLine.precededByBlankLine && currentParagraph.isNotEmpty()) {
                val prevText = currentParagraph.toString().trimEnd()
                if (prevText.isNotEmpty() && prevText.last() in SENTENCE_END_PUNCTUATION) {
                    paragraphs.add(prevText.trim())
                    currentParagraph.clear()
                }
            }

            if (currentParagraph.isNotEmpty()) {
                currentParagraph.append(' ')
            }
            currentParagraph.append(line)

            if (endsWithSentencePunctuation(line)) {
                paragraphs.add(currentParagraph.toString().trim())
                currentParagraph.clear()
            }
        }

        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph.toString().trim())
        }

        return paragraphs
            .map { collapseHorizontalSpaces(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun cleanPageToOcrLines(
        rawText: String,
        pageNumber: Int,
        sourceLines: List<OcrLine> = emptyList(),
    ): List<OcrLine> {
        val inputLines: List<OcrLine> = if (sourceLines.isNotEmpty()) {
            sourceLines.map { line ->
                line.copy(
                    text = collapseHorizontalSpaces(line.text),
                    pageNumber = if (line.pageNumber > 0) line.pageNumber else pageNumber,
                )
            }
        } else {
            if (rawText.isBlank()) return emptyList()
            val split = rawText.split(LINE_SPLIT_REGEX)
            val mapped = mutableListOf<OcrLine>()
            var blankSeen = false
            for (raw in split) {
                val collapsed = collapseHorizontalSpaces(raw)
                if (collapsed.isEmpty()) {
                    if (mapped.isNotEmpty()) {
                        blankSeen = true
                    }
                } else {
                    mapped.add(
                        OcrLine(
                            text = collapsed,
                            fontSizePx = null,
                            top = null,
                            pageNumber = pageNumber,
                            precededByBlankLine = blankSeen,
                        ),
                    )
                    blankSeen = false
                }
            }
            mapped
        }

        if (inputLines.isEmpty()) return emptyList()

        val result = mutableListOf<OcrLine>()
        var carryBlankLine = false

        for (candidate in inputLines) {
            if (candidate.text.isEmpty()) {
                carryBlankLine = carryBlankLine || result.isNotEmpty()
                continue
            }

            if (isInternalWarningLine(candidate.text)) {
                carryBlankLine = carryBlankLine || candidate.precededByBlankLine
                continue
            }

            val stripped = stripMetadataIfSafe(candidate.text, pageNumber)
            if (stripped == null) {
                carryBlankLine = carryBlankLine || candidate.precededByBlankLine
                continue
            }

            val fixedLine = cleanArabicText(stripped)
            if (fixedLine.isEmpty() || isInternalWarningLine(fixedLine)) {
                carryBlankLine = carryBlankLine || candidate.precededByBlankLine
                continue
            }

            if (ISOLATED_PUNCTUATION_REGEX.matches(fixedLine)) {
                if (result.isNotEmpty()) {
                    val lastIdx = result.lastIndex
                    val prev = result[lastIdx]
                    result[lastIdx] = prev.copy(text = prev.text + fixedLine)
                }
                continue
            }

            if (isOcrNoiseLine(fixedLine, pageNumber)) {
                carryBlankLine = carryBlankLine || candidate.precededByBlankLine
                continue
            }

            // 1e. De-duplicate consecutive identical lines within the same page.
            if (result.isNotEmpty() && result.last().text == fixedLine) {
                continue
            }

            result.add(
                candidate.copy(
                    text = fixedLine,
                    pageNumber = pageNumber,
                    precededByBlankLine = candidate.precededByBlankLine || carryBlankLine,
                ),
            )
            carryBlankLine = false
        }

        return result
    }

    fun cleanArabicText(text: String): String {
        if (text.isBlank()) return ""
        var result = normalizeArabicPresentationLigatures(collapseHorizontalSpaces(text))

        // 1b. Fix broken Arabic hamza forms emitted by OCR / LTR PDF ligature confusion
        result = result
            .replace("األ", "الأ")
            .replace("اإل", "الإ")
            .replace("اآل", "الآ")

        // Preserve double-lam stem forms like "االلتزام" -> "الالتزام" and "لاللتزام" -> "للالتزام",
        // then fix remaining "اال" -> "ال" and word-initial "لال" -> "لل" without corrupting words like "خلال".
        result = result.replace("االل", "الال")
        result = result.replace("اال", "ال")
        result = result.replace("لالل", "للال")
        result = WORD_START_LAM_ALEF_LAM_REGEX.replace(result) { match ->
            "${match.groupValues[1]}لل"
        }

        return collapseHorizontalSpaces(result)
    }

    fun isInternalWarningLine(text: String): Boolean {
        val trimmed = collapseHorizontalSpaces(text)
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("تحذير") || trimmed.startsWith(":تحذير") || trimmed.startsWith("[تحذير")) {
            return true
        }
        return INTERNAL_WARNING_SUBSTRINGS.any { trimmed.contains(it) }
    }

    fun isOcrNoiseLine(
        text: String,
        pageNumber: Int = 0,
    ): Boolean {
        val collapsed = collapseHorizontalSpaces(text)
        if (collapsed.isEmpty()) return true
        if (isInternalWarningLine(collapsed)) return true
        if (isHeaderOrFooterNoise(collapsed, pageNumber)) return true

        val nonWhitespace = collapsed.filter { !it.isWhitespace() }
        if (nonWhitespace.isEmpty()) return true

        val arabicLetterCount = nonWhitespace.count { isArabicLetter(it) }

        // Rule 1: length < 4 with no Arabic letters
        if (collapsed.length < 4 && arabicLetterCount == 0) {
            return true
        }

        // Rule 2: Arabic letters ratio < 30% of non-whitespace length
        val arabicRatio = arabicLetterCount.toFloat() / nonWhitespace.length.toFloat()
        if (arabicRatio < MIN_ARABIC_RATIO) {
            return true
        }

        // Rule 3: > 5 consecutive non-Arabic, non-digit characters (Latin/symbol junk)
        if (CONSECUTIVE_NON_ARABIC_NOISE_REGEX.containsMatchIn(collapsed)) {
            return true
        }
        if (REPEATED_PUNCTUATION_NOISE_REGEX.containsMatchIn(collapsed)) {
            return true
        }

        // Rule 4: Mixed glued Arabic+digit fragments, repetitive stammer tokens, or consonant-soup gibberish
        val tokens = collapsed.split(WHITESPACE_REGEX)
            .map { token -> token.trim(*PUNCTUATION_TRIM_CHARS) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true

        val hasSentenceEnding = endsWithSentencePunctuation(collapsed)
        if (!hasSentenceEnding && tokens.size <= 4 && tokens.any { GLUED_ARABIC_DIGIT_REGEX.containsMatchIn(it) }) {
            return true
        }

        // Repetitive short gibberish lines like "الل لل الل الل"
        if (tokens.size >= 3) {
            val distinctCount = tokens.distinct().size
            val maxFreq = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (distinctCount <= tokens.size / 2 && maxFreq >= 2 && tokens.all { it.length <= 4 }) {
                return true
            }
        }

        // Standalone single-word consonant soup (e.g. "لمعصعتندت") with zero Arabic long vowels/matres
        if (tokens.size == 1 && !hasSentenceEnding) {
            val arabicOnly = tokens[0].filter { isArabicLetter(it) }
            if (arabicOnly.length >= 7 && arabicOnly.none { it in ARABIC_VOWEL_MATRES }) {
                return true
            }
        }

        return false
    }

    fun isHeaderOrFooterNoise(
        text: String,
        pageNumber: Int = 0,
    ): Boolean {
        val collapsed = collapseHorizontalSpaces(text)
        if (collapsed.isEmpty()) return true

        val normalizedDigits = normalizeDigits(collapsed)
        if (EXACT_PAGE_WORD_WITH_NUMBER_REGEX.matches(collapsed)) return true
        if (EXACT_STANDALONE_NUMBER_REGEX.matches(collapsed)) return true
        if (YEAR_RANGE_REGEX.matches(normalizedDigits)) return true

        val trimmedEdgeDigits = normalizedDigits.trim(' ', '-', '–', '—', '[', ']', '(', ')', '/', ':', '.', '،')
        if (pageNumber > 0 && trimmedEdgeDigits == pageNumber.toString()) return true

        var stripped = collapsed
        stripped = NOISE_PAGE_MARKER_REGEX.replace(stripped, " ")
        stripped = YEAR_RANGE_REGEX.replace(stripped, " ")
        stripped = DATE_PATTERN_REGEX.replace(stripped, " ")
        stripped = TIME_PATTERN_REGEX.replace(stripped, " ")
        stripped = WATERMARK_TEXT_REGEX.replace(stripped, " ")
        stripped = GARBLED_FOOTER_NOISE_REGEX.replace(stripped, " ")
        stripped = SERIAL_NUMBER_REGEX.replace(stripped, " ")

        if (stripped == collapsed) return false

        val remaining = stripped.replace(NOISE_PUNCTUATION_STRIP_REGEX, "").trim()
        return remaining.isEmpty()
    }

    private fun stripMetadataIfSafe(
        line: String,
        pageNumber: Int,
    ): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (isInternalWarningLine(trimmed)) return null

        // Delete ONLY when the entire line is an exact page-number marker.
        if (EXACT_PAGE_WORD_WITH_NUMBER_REGEX.matches(trimmed)) return null
        if (EXACT_STANDALONE_NUMBER_REGEX.matches(trimmed)) return null
        val normalizedEdgeDigits = normalizeDigits(trimmed.trim(' ', '-', '–', '—', '[', ']', '(', ')', '/'))
        if (normalizedEdgeDigits == pageNumber.toString()) return null

        // On mixed or pure-metadata lines, strip only the metadata tokens and retain all surrounding text.
        var stripped = trimmed
        stripped = INLINE_PAGE_WORD_WITH_NUMBER_REGEX.replace(stripped, " ")
        stripped = DATE_PATTERN_REGEX.replace(stripped, " ")
        stripped = TIME_PATTERN_REGEX.replace(stripped, " ")
        stripped = WATERMARK_TEXT_REGEX.replace(stripped, " ")
        stripped = GARBLED_FOOTER_NOISE_REGEX.replace(stripped, " ")
        stripped = SERIAL_NUMBER_REGEX.replace(stripped, " ")

        val collapsed = collapseHorizontalSpaces(stripped)
        return collapsed.ifEmpty { null }
    }

    private fun normalizeArabicPresentationLigatures(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\uFEF5', '\uFEF6' -> sb.append("لآ")
                '\uFEF7', '\uFEF8' -> sb.append("لأ")
                '\uFEF9', '\uFEFA' -> sb.append("لإ")
                '\uFEFB', '\uFEFC' -> sb.append("لا")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isArabicLetter(ch: Char): Boolean =
        ch in '\u0621'..'\u064A' || ch in '\u066E'..'\u06D3' || ch == '\u0671'

    private fun isBulletLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.isNotEmpty() && trimmed[0] in BULLET_CHARS
    }

    private fun endsWithSentencePunctuation(line: String): Boolean {
        val trimmed = line.trimEnd()
        return trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END_PUNCTUATION
    }

    private fun collapseHorizontalSpaces(text: String): String =
        text.replace(HORIZONTAL_SPACES_REGEX, " ").trim()

    private fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        private val LINE_SPLIT_REGEX = Regex("\\r?\\n")
        private val HORIZONTAL_SPACES_REGEX = Regex("[ \\t\\u00A0]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val ISOLATED_PUNCTUATION_REGEX = Regex("^[.,،؛:؟!\"'»«()…]+$")
        private val WORD_START_LAM_ALEF_LAM_REGEX = Regex("(^|[\\s«\"(وف])لال(?!ل)")

        private val EXACT_PAGE_WORD_WITH_NUMBER_REGEX = Regex(
            "^(?:[-–—\\[\\]()*\\s]*)(?:الصفحة|صفحة|ص\\.?)\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+(?:\\s*(?:من|/)\\s*[0-9٠-٩]+)?(?:[-–—\\[\\]()*\\s]*)$",
        )
        private val EXACT_STANDALONE_NUMBER_REGEX = Regex("^[-–—\\[\\]()\\s]*[0-9٠-٩]{1,4}[-–—\\[\\]()\\s]*$")
        private val INLINE_PAGE_WORD_WITH_NUMBER_REGEX = Regex("الصفحة\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+")
        private val NOISE_PAGE_MARKER_REGEX = Regex(
            "(?:الصفحة|صفحة|ص\\.?)\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+(?:\\s*(?:من|/)\\s*[0-9٠-٩]+)?",
        )
        private val YEAR_RANGE_REGEX = Regex("\\b[0-9٠-٩]{4}\\s*[-/–—]\\s*[0-9٠-٩]{4}\\b")
        private val DATE_PATTERN_REGEX = Regex("\\b\\d{4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b")
        private val TIME_PATTERN_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM|am|pm|ص|م))?\\b")
        private val WATERMARK_TEXT_REGEX = Regex("Espace\\s+Juridique", RegexOption.IGNORE_CASE)
        private val SERIAL_NUMBER_REGEX = Regex("\\b[0-9٠-٩]{6,}\\b")
        private val GARBLED_FOOTER_NOISE_REGEX = Regex("عسوتل\\s*[0-9٠-٩]*")
        private val NOISE_PUNCTUATION_STRIP_REGEX = Regex("[\\s\\-–—_/|:.,،؛:()\\[\\]«»\"'©•*]+")

        private const val MIN_ARABIC_RATIO = 0.30f
        private val CONSECUTIVE_NON_ARABIC_NOISE_REGEX = Regex("[^\\u0600-\\u06FF0-9٠-٩\\s.,،؛:؟!«»\"()]{6,}")
        private val REPEATED_PUNCTUATION_NOISE_REGEX = Regex("[\\-_=*#/\\\\|~`]{6,}")
        private val GLUED_ARABIC_DIGIT_REGEX = Regex("[\\u0621-\\u064A]+[0-9٠-٩]+|[0-9٠-٩]+[\\u0621-\\u064A]+")
        private val ARABIC_VOWEL_MATRES = setOf('ا', 'أ', 'إ', 'آ', 'و', 'ي', 'ى', 'ة', 'ؤ', 'ئ')
        private val PUNCTUATION_TRIM_CHARS = charArrayOf('.', ',', '،', '؛', ':', '؟', '!', '"', '\'', '«', '»', '(', ')', '[', ']', '-', '–', '—')

        private val INTERNAL_WARNING_SUBSTRINGS = listOf(
            "يرجى مراجعة",
            "يرجى مراجعتها",
            "لم يتم استخراج نصها بالكامل",
            "تم حذف جزء كبير من النص",
            "قد يكون نص هذه الصفحة ناقصاً",
        )

        private val SENTENCE_END_PUNCTUATION = setOf('.', '؟', '!', ':', '"', ')')
        private val BULLET_CHARS = setOf('-', '•', '✓', '❖')

        private val defaultInstance = OcrTextCleaner()

        fun cleanPageText(rawText: String, pageNumber: Int): String =
            defaultInstance.cleanPageText(rawText, pageNumber)

        fun cleanPageToOcrLines(
            rawText: String,
            pageNumber: Int,
            sourceLines: List<OcrLine> = emptyList(),
        ): List<OcrLine> = defaultInstance.cleanPageToOcrLines(
            rawText = rawText,
            pageNumber = pageNumber,
            sourceLines = sourceLines,
        )

        fun cleanArabicText(text: String): String =
            defaultInstance.cleanArabicText(text)

        fun isOcrNoiseLine(text: String, pageNumber: Int = 0): Boolean =
            defaultInstance.isOcrNoiseLine(text, pageNumber)

        fun isInternalWarningLine(text: String): Boolean =
            defaultInstance.isInternalWarningLine(text)

        fun isHeaderOrFooterNoise(text: String, pageNumber: Int = 0): Boolean =
            defaultInstance.isHeaderOrFooterNoise(text, pageNumber)
    }
}

