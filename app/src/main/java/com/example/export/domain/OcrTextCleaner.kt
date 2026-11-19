package com.example.export.domain

/**
 * Module 09 / 10 — Safe Text Reconstruction & Formatting Cleanup.
 *
 * Cleans raw per-page OCR output before Word (.docx) assembly/export without dropping real text:
 * 1. Deletes a line ONLY when it is an exact match for a standalone page number, date/time, serial, or watermark.
 * 2. Strips inline metadata tokens (dates, times, watermarks, serials) from mixed lines while retaining all real text.
 * 3. Fixes common Arabic OCR ligature errors ("اال" -> "ال", "لال" -> "لل") and attaches isolated punctuation.
 * 4. Reconstructs full paragraphs by merging visually broken lines while preserving sentence endings and bullets.
 */
class OcrTextCleaner {

    fun cleanPageText(rawText: String, pageNumber: Int): String {
        if (rawText.isBlank()) return ""

        // Step 1: Split into trimmed lines, collapse horizontal spaces, strip exact/inline metadata without dropping text.
        val rawLines = rawText
            .split(LINE_SPLIT_REGEX)
            .map { collapseHorizontalSpaces(it) }
            .filter { it.isNotEmpty() }

        if (rawLines.isEmpty()) return ""

        val cleanedLines = rawLines.mapNotNull { line ->
            stripMetadataIfSafe(line, pageNumber)
        }

        if (cleanedLines.isEmpty()) return ""

        // Step 2: Fix Arabic OCR ligature errors and attach isolated punctuation marks to the previous line.
        val normalizedLines = mutableListOf<String>()
        for (line in cleanedLines) {
            val fixedLine = cleanArabicText(line)
            if (fixedLine.isEmpty()) continue

            if (ISOLATED_PUNCTUATION_REGEX.matches(fixedLine)) {
                if (normalizedLines.isNotEmpty()) {
                    val lastIdx = normalizedLines.lastIndex
                    normalizedLines[lastIdx] = normalizedLines[lastIdx] + fixedLine
                } else {
                    normalizedLines.add(fixedLine)
                }
            } else {
                normalizedLines.add(fixedLine)
            }
        }

        if (normalizedLines.isEmpty()) return ""

        // Step 3: Merge visually broken lines into proper paragraphs without ever dropping a line.
        val paragraphs = mutableListOf<String>()
        val currentParagraph = StringBuilder()

        for (line in normalizedLines) {
            if (isBulletLine(line)) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(currentParagraph.toString().trim())
                    currentParagraph.clear()
                }
                paragraphs.add(line)
                continue
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

    fun cleanArabicText(text: String): String {
        if (text.isBlank()) return ""
        var result = collapseHorizontalSpaces(text)

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
        private val ISOLATED_PUNCTUATION_REGEX = Regex("^[.,،؛:؟!\"'»«()…]+$")
        private val WORD_START_LAM_ALEF_LAM_REGEX = Regex("(^|[\\s«\"(وف])لال(?!ل)")

        private val EXACT_PAGE_WORD_WITH_NUMBER_REGEX = Regex(
            "^(?:الصفحة|صفحة|ص)\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+(?:\\s*(?:من|/)\\s*[0-9٠-٩]+)?$",
        )
        private val EXACT_STANDALONE_NUMBER_REGEX = Regex("^[-–—\\[\\]()\\s]*[0-9٠-٩]{1,4}[-–—\\[\\]()\\s]*$")
        private val INLINE_PAGE_WORD_WITH_NUMBER_REGEX = Regex("الصفحة\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+")
        private val NOISE_PAGE_MARKER_REGEX = Regex(
            "(?:الصفحة|صفحة|ص)\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+(?:\\s*(?:من|/)\\s*[0-9٠-٩]+)?",
        )
        private val YEAR_RANGE_REGEX = Regex("\\b[0-9٠-٩]{4}\\s*[-/–—]\\s*[0-9٠-٩]{4}\\b")
        private val DATE_PATTERN_REGEX = Regex("\\b\\d{4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b")
        private val TIME_PATTERN_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM|am|pm|ص|م))?\\b")
        private val WATERMARK_TEXT_REGEX = Regex("Espace\\s+Juridique", RegexOption.IGNORE_CASE)
        private val SERIAL_NUMBER_REGEX = Regex("\\b[0-9٠-٩]{6,}\\b")
        private val GARBLED_FOOTER_NOISE_REGEX = Regex("عسوتل\\s*[0-9٠-٩]*")
        private val NOISE_PUNCTUATION_STRIP_REGEX = Regex("[\\s\\-–—_/|:.,،؛:()\\[\\]«»\"'©•*]+")

        private val SENTENCE_END_PUNCTUATION = setOf('.', '؟', '!', ':', '"', ')')
        private val BULLET_CHARS = setOf('-', '•', '✓', '❖')

        private val defaultInstance = OcrTextCleaner()

        fun cleanPageText(rawText: String, pageNumber: Int): String =
            defaultInstance.cleanPageText(rawText, pageNumber)

        fun cleanArabicText(text: String): String =
            defaultInstance.cleanArabicText(text)

        fun isHeaderOrFooterNoise(text: String, pageNumber: Int = 0): Boolean =
            defaultInstance.isHeaderOrFooterNoise(text, pageNumber)
    }
}
