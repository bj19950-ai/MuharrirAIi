package com.example.data.comparison

/**
 * Detects differences between OCR text and Gemini suggested text that consist
 * solely of commonly confused Arabic look-alike characters (including dot/hamza/taa-marbuta pairs).
 *
 * Module 05 §3 mandatory rule:
 * - ه / ة
 * - ي / ى
 * - أ / إ / ا
 * - د / ذ
 * - ر / ز
 * - و / ؤ
 * - ي / ئ
 */
object ArabicLookAlikeDetector {

    private val LOOK_ALIKE_PAIRS: Set<Pair<Char, Char>> = buildSet {
        fun addSymmetric(a: Char, b: Char) {
            add(a to b)
            add(b to a)
        }
        addSymmetric('ه', 'ة')
        addSymmetric('ي', 'ى')
        addSymmetric('أ', 'إ')
        addSymmetric('أ', 'ا')
        addSymmetric('إ', 'ا')
        addSymmetric('د', 'ذ')
        addSymmetric('ر', 'ز')
        addSymmetric('و', 'ؤ')
        addSymmetric('ي', 'ئ')
    }

    fun isLookAlikePair(first: Char, second: Char): Boolean {
        return (first to second) in LOOK_ALIKE_PAIRS
    }

    /**
     * Returns all differing character pairs if and only if [ocrText] and [suggestedText] (trimmed)
     * have equal length, at least one difference, and every single differing character pair
     * belongs to [LOOK_ALIKE_PAIRS]. Returns empty list otherwise.
     */
    fun findExclusiveLookAlikeDifferences(
        ocrText: String,
        suggestedText: String,
    ): List<Pair<Char, Char>> {
        val left = ocrText.trim()
        val right = suggestedText.trim()
        if (left.isEmpty() || left.length != right.length) return emptyList()

        val diffs = mutableListOf<Pair<Char, Char>>()
        for (index in left.indices) {
            val c1 = left[index]
            val c2 = right[index]
            if (c1 == c2) continue
            if (!isLookAlikePair(c1, c2)) {
                return emptyList()
            }
            diffs.add(c1 to c2)
        }
        return diffs
    }

    fun hasOnlyLookAlikeDifference(
        ocrText: String,
        suggestedText: String,
    ): Boolean = findExclusiveLookAlikeDifferences(ocrText, suggestedText).isNotEmpty()
}
