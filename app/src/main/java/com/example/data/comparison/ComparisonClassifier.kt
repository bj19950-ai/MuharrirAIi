package com.example.data.comparison

import com.example.domain.model.ComparisonCategory
import com.example.domain.model.GeminiReasonType

data class ComparisonClassificationInput(
    val ocrText: String,
    val suggestedText: String,
    val ocrConfidence: Float?,
    val geminiConfidence: Float?,
    val evidence: String,
    val reasonType: String,
    val requiresReview: Boolean,
)

data class ComparisonClassificationResult(
    val category: ComparisonCategory,
    val priority: Int,
    val persistedOcrText: String,
    val persistedSuggestedText: String?,
    val isArabicLookAlikeDisagreement: Boolean,
)

/**
 * Implements Module 05 deterministic classification (A..E) and priority ranking (1..6).
 *
 * Precedence:
 * 1. Exclusive Arabic look-alike char differences (ه/ة, ي/ى, أ/إ/ا, د/ذ, ر/ز, و/ؤ, ي/ئ)
 *    -> DISAGREEMENT (B), priority 1, regardless of confidence or requires_review.
 * 2. Blank suggested_text, OR equal text flagged uncertain/low-confidence with explicit
 *    ambiguity/blur/uncertainty/illegibility cues in evidence (and not structural)
 *    -> UNRESOLVED (D), priority 2, suggested_text = null.
 * 3. Non-blank suggested_text != ocr_text
 *    -> DISAGREEMENT (B), priority 1.
 * 4. reason_type == "structure" (when text is not blank and doesn't disagree)
 *    -> STRUCTURAL_AMBIGUITY (E), priority 5.
 * 5. ocrConfidence < 0.70f || requires_review || geminiConfidence < 0.60f
 *    -> UNCERTAIN_RECOGNITION (C), priority 3 if ocrConfidence < 0.50f else priority 4.
 * 6. Otherwise
 *    -> AGREEMENT (A), priority 6.
 */
object ComparisonClassifier {

    const val PRIORITY_DISAGREEMENT = 1
    const val PRIORITY_UNRESOLVED = 2
    const val PRIORITY_UNCERTAIN_VERY_LOW_OCR = 3
    const val PRIORITY_UNCERTAIN_MODERATE = 4
    const val PRIORITY_STRUCTURAL_AMBIGUITY = 5
    const val PRIORITY_AGREEMENT = 6

    private val AMBIGUITY_EVIDENCE_MARKERS = listOf(
        "غامض",
        "غموض",
        "ضباب",
        "ضبابي",
        "غير واضح",
        "طمس",
        "مطموس",
        "غير مؤكد",
        "ملتبس",
        "التباس",
        "لا يكفي",
        "مبهم",
        "غير مقروء",
        "يتعذر الجزم",
        "تشويش",
        "ambiguous",
        "ambiguity",
        "blur",
        "blurry",
        "unclear",
        "uncertain",
        "uncertainty",
        "insufficient",
        "illegible",
        "indistinct",
        "unresolved",
    )

    fun classify(input: ComparisonClassificationInput): ComparisonClassificationResult {
        val cleanOcr = input.ocrText.trim()
        val cleanSuggested = input.suggestedText.trim()
        val isStructureReason = input.reasonType.trim()
            .equals(GeminiReasonType.STRUCTURE.wireValue, ignoreCase = true)

        // 1. Arabic look-alike rule (§3): always DISAGREEMENT regardless of confidence/requires_review
        if (ArabicLookAlikeDetector.hasOnlyLookAlikeDifference(cleanOcr, cleanSuggested)) {
            return ComparisonClassificationResult(
                category = ComparisonCategory.DISAGREEMENT,
                priority = PRIORITY_DISAGREEMENT,
                persistedOcrText = cleanOcr,
                persistedSuggestedText = cleanSuggested,
                isArabicLookAlikeDisagreement = true,
            )
        }

        // 2. UNRESOLVED (D): blank suggestion, or flagged equal text with explicit ambiguity evidence
        val hasExplicitAmbiguity = containsAmbiguityEvidence(input.evidence)
        val hasLowGeminiConfForUnresolved = input.geminiConfidence != null && input.geminiConfidence < 0.70f
        if (cleanSuggested.isBlank() ||
            (!isStructureReason &&
                cleanSuggested == cleanOcr &&
                (input.requiresReview || hasLowGeminiConfForUnresolved) &&
                hasExplicitAmbiguity)
        ) {
            return ComparisonClassificationResult(
                category = ComparisonCategory.UNRESOLVED,
                priority = PRIORITY_UNRESOLVED,
                persistedOcrText = cleanOcr,
                persistedSuggestedText = null,
                isArabicLookAlikeDisagreement = false,
            )
        }

        // 3. DISAGREEMENT (B): ocr_text differs from suggested_text
        if (cleanSuggested != cleanOcr) {
            return ComparisonClassificationResult(
                category = ComparisonCategory.DISAGREEMENT,
                priority = PRIORITY_DISAGREEMENT,
                persistedOcrText = cleanOcr,
                persistedSuggestedText = cleanSuggested,
                isArabicLookAlikeDisagreement = false,
            )
        }

        // 4. STRUCTURAL_AMBIGUITY (E): reason_type == "structure"
        if (isStructureReason) {
            return ComparisonClassificationResult(
                category = ComparisonCategory.STRUCTURAL_AMBIGUITY,
                priority = PRIORITY_STRUCTURAL_AMBIGUITY,
                persistedOcrText = cleanOcr,
                persistedSuggestedText = cleanSuggested,
                isArabicLookAlikeDisagreement = false,
            )
        }

        // 5. UNCERTAIN_RECOGNITION (C)
        val isLowOcr = input.ocrConfidence != null && input.ocrConfidence < 0.70f
        val isLowGemini = input.geminiConfidence != null && input.geminiConfidence < 0.60f
        if (isLowOcr || input.requiresReview || isLowGemini) {
            val priority = if (input.ocrConfidence != null && input.ocrConfidence < 0.50f) {
                PRIORITY_UNCERTAIN_VERY_LOW_OCR
            } else {
                PRIORITY_UNCERTAIN_MODERATE
            }
            return ComparisonClassificationResult(
                category = ComparisonCategory.UNCERTAIN_RECOGNITION,
                priority = priority,
                persistedOcrText = cleanOcr,
                persistedSuggestedText = cleanSuggested,
                isArabicLookAlikeDisagreement = false,
            )
        }

        // 6. AGREEMENT (A)
        return ComparisonClassificationResult(
            category = ComparisonCategory.AGREEMENT,
            priority = PRIORITY_AGREEMENT,
            persistedOcrText = cleanOcr,
            persistedSuggestedText = cleanSuggested,
            isArabicLookAlikeDisagreement = false,
        )
    }

    internal fun containsAmbiguityEvidence(evidence: String): Boolean {
        val lower = evidence.trim().lowercase()
        if (lower.isEmpty()) return false
        return AMBIGUITY_EVIDENCE_MARKERS.any { marker -> lower.contains(marker) }
    }
}
