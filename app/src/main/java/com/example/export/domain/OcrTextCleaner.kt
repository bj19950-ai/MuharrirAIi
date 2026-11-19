package com.example.export.domain

/**
 * Module 09 — Text Reconstruction & Formatting Cleanup.
 *
 * Cleans raw per-page OCR output before Word (.docx) assembly/export:
 * 1. Removes typical PDF header/footer noise (page counters, timestamps, serials, watermarks, university headers).
 * 2. Fixes common Arabic OCR ligature errors ("اال" -> "ال", "لال" -> "لل") and attaches isolated punctuation.
 * 3. Reconstructs full paragraphs by merging visually broken lines while preserving sentence endings and bullets.
 * 4. Removes empty lines and collapses excessive whitespace.
 */
class OcrTextCleaner {

    fun cleanPageText(rawText: String, pageNumber: Int): String {
        if (rawText.isBlank()) return ""

        // Step 1: Split into trimmed lines, collapse horizontal spaces, drop blank lines & noise headers/footers.
        val rawLines = rawText
            .split(LINE_SPLIT_REGEX)
            .map { collapseHorizontalSpaces(it) }
            .filter { it.isNotEmpty() }

        if (rawLines.isEmpty()) return ""

        val filteredLines = rawLines.filterIndexed { index, line ->
            !isHeaderOrFooterNoise(
                line = line,
                lineIndex = index,
                totalLines = rawLines.size,
                pageNumber = pageNumber,
            )
        }

        if (filteredLines.isEmpty()) return ""

        // Step 2: Fix Arabic OCR ligature errors and attach isolated punctuation marks to the previous line.
        val normalizedLines = mutableListOf<String>()
        for (line in filteredLines) {
            val cleanedLine = cleanArabicText(line)
            if (cleanedLine.isEmpty()) continue

            if (ISOLATED_PUNCTUATION_REGEX.matches(cleanedLine)) {
                if (normalizedLines.isNotEmpty()) {
                    val lastIdx = normalizedLines.lastIndex
                    normalizedLines[lastIdx] = normalizedLines[lastIdx] + cleanedLine
                }
            } else {
                normalizedLines.add(cleanedLine)
            }
        }

        if (normalizedLines.isEmpty()) return ""

        // Step 3: Merge visually broken lines into proper paragraphs.
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

    private fun isHeaderOrFooterNoise(
        line: String,
        lineIndex: Int,
        totalLines: Int,
        pageNumber: Int,
    ): Boolean {
        val isNearEdge = lineIndex <= EDGE_WINDOW_LINES || lineIndex >= (totalLines - 1 - EDGE_WINDOW_LINES)

        // Contains "الصفحة" followed by a number (e.g. "الصفحة1", "الصفحة 1", "الصفحة ١٢")
        if (PAGE_WORD_WITH_NUMBER_REGEX.containsMatchIn(line)) return true

        // Short page marker like "صفحة 3" or "ص 3"
        if (SHORT_PAGE_MARKER_REGEX.matches(line)) return true

        // Standalone page number matching current pageNumber or short isolated numeral at top/bottom
        if (isNearEdge && STANDALONE_PAGE_NUMBER_REGEX.matches(line)) return true
        val trimmedDigits = normalizeDigits(line.trim(' ', '-', '–', '—', '[', ']', '(', ')', '/'))
        if (trimmedDigits == pageNumber.toString()) return true

        // Contains dates like "2025-4", "2025/04/19", or times like "1:53"
        if (DATE_PATTERN_REGEX.containsMatchIn(line)) return true
        if (TIME_PATTERN_REGEX.containsMatchIn(line)) return true

        // Contains "Espace Juridique" watermark/header
        if (line.contains("espace juridique", ignoreCase = true)) return true

        // Contains random serial numbers (e.g., "1150326", "301101006")
        if (SERIAL_NUMBER_REGEX.containsMatchIn(line)) return true

        // Known garbled Arabic OCR header/footer artifacts (e.g., "عسوتل0")
        if (GARBLED_FOOTER_NOISE_REGEX.containsMatchIn(line)) return true

        // Repeated university names, faculties, or course metadata at the very top or bottom of a page
        if (isNearEdge && UNIVERSITY_OR_COURSE_HEADER_REGEX.containsMatchIn(line)) return true

        return false
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
        private const val EDGE_WINDOW_LINES = 2
        private val LINE_SPLIT_REGEX = Regex("\\r?\\n")
        private val HORIZONTAL_SPACES_REGEX = Regex("[ \\t\\u00A0]+")
        private val ISOLATED_PUNCTUATION_REGEX = Regex("^[.,،؛:؟!\"'»«()…]+$")
        private val WORD_START_LAM_ALEF_LAM_REGEX = Regex("(^|[\\s«\"(وف])لال(?!ل)")

        private val PAGE_WORD_WITH_NUMBER_REGEX = Regex("الصفحة\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+")
        private val SHORT_PAGE_MARKER_REGEX = Regex("^(?:صفحة|ص)\\s*[:\\-/\\.]?\\s*[0-9٠-٩]+(?:\\s*(?:من|/)\\s*[0-9٠-٩]+)?$")
        private val STANDALONE_PAGE_NUMBER_REGEX = Regex("^[-–—\\[\\]()\\s]*[0-9٠-٩]{1,4}[-–—\\[\\]()\\s]*$")
        private val DATE_PATTERN_REGEX = Regex("\\b\\d{4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?\\b|\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b")
        private val TIME_PATTERN_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM|am|pm|ص|م))?\\b")
        private val SERIAL_NUMBER_REGEX = Regex("\\b[0-9٠-٩]{6,}\\b")
        private val GARBLED_FOOTER_NOISE_REGEX = Regex("عسوتل\\s*[0-9٠-٩]*")
        private val UNIVERSITY_OR_COURSE_HEADER_REGEX = Regex(
            "(?:جامعة\\s+[\\u0621-\\u064A]+|" +
                "كلية\\s+(?:الحقوق|العلوم\\s+القانونية|الشريعة|الآداب|الاقتصاد)|" +
                "الموسم\\s+الجامعي|السنة\\s+الجامعية|" +
                "السداسي\\s+(?:الأول|الثاني|الثالث|الرابع|الخامس|السادس)|" +
                "مسلك\\s+القانون|شعبة\\s+القانون|" +
                "محاضرات\\s+(?:في|مادة)|" +
                "^(?:الأستاذ|ذ\\.|وحدة|مادة)\\s*:)",
        )

        private val SENTENCE_END_PUNCTUATION = setOf('.', '؟', '!', ':', '"', ')')
        private val BULLET_CHARS = setOf('-', '•', '✓', '❖')

        private val defaultInstance = OcrTextCleaner()

        fun cleanPageText(rawText: String, pageNumber: Int): String =
            defaultInstance.cleanPageText(rawText, pageNumber)

        fun cleanArabicText(text: String): String =
            defaultInstance.cleanArabicText(text)
    }
}
