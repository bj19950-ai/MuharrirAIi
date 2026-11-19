package com.example.data.gemini

import com.example.domain.model.GeminiPageAnalysisResponse
import com.example.domain.model.GeminiReasonType
import com.example.domain.model.GeminiSuggestionItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

sealed interface GeminiValidationResult {
    data class Valid(
        val response: GeminiPageAnalysisResponse,
        val normalizedJson: String,
    ) : GeminiValidationResult

    data class Malformed(
        val reasonAr: String,
    ) : GeminiValidationResult
}

/**
 * Strictly validates Gemini JSON output using Moshi against the Module 04 schema:
 * {
 *   "page_id": "string",
 *   "page_number": 1,
 *   "isMissingLargeContent": false,
 *   "items": [
 *     {
 *       "ocr_text": "string",
 *       "suggested_text": "string",
 *       "bbox": [x1, y1, x2, y2],
 *       "confidence": 0.92,
 *       "evidence": "string",
 *       "reason_type": "visual | context | lexicon | morphology | structure",
 *       "requires_review": true
 *     }
 *   ]
 * }
 */
object GeminiSchemaValidator {

    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val responseAdapter = moshi.adapter(GeminiPageAnalysisResponse::class.java)

    fun validate(rawOutput: String): GeminiValidationResult {
        val stripped = stripOptionalMarkdownFences(rawOutput)
        if (stripped.isBlank()) {
            return GeminiValidationResult.Malformed("استجابة Gemini فارغة ولا تحتوي على JSON صالح.")
        }

        val rootMap = runCatching { mapAdapter.fromJson(stripped) }.getOrNull()
            ?: return GeminiValidationResult.Malformed("تعذر تحليل استجابة Gemini بصيغة JSON مطابقة للمخطط.")

        val pageId = rootMap["page_id"] as? String
        if (pageId.isNullOrBlank()) {
            return GeminiValidationResult.Malformed("حقل page_id مفقود أو فارغ في استجابة Gemini.")
        }

        val pageNumberNum = rootMap["page_number"] as? Number
            ?: return GeminiValidationResult.Malformed("حقل page_number مفقود أو غير رقمي.")
        val pageNumberDouble = pageNumberNum.toDouble()
        if (pageNumberDouble % 1.0 != 0.0 || pageNumberNum.toInt() < 1) {
            return GeminiValidationResult.Malformed("حقل page_number يجب أن يكون عدداً صحيحاً أكبر من أو يساوي 1.")
        }

        val isMissingLargeContent = when (val rawMissingFlag = rootMap["isMissingLargeContent"]) {
            null -> false
            is Boolean -> rawMissingFlag
            else -> return GeminiValidationResult.Malformed(
                "حقل isMissingLargeContent يجب أن يكون منطقياً (true/false).",
            )
        }

        val rawItems = rootMap["items"] as? List<*>
            ?: return GeminiValidationResult.Malformed("حقل items مفقود أو ليس مصفوفة JSON.")

        val validatedItems = mutableListOf<GeminiSuggestionItem>()
        for ((index, rawItem) in rawItems.withIndex()) {
            val itemMap = rawItem as? Map<*, *>
                ?: return GeminiValidationResult.Malformed("العنصر رقم ${index + 1} في items ليس كائن JSON صالحاً.")

            val ocrText = itemMap["ocr_text"] as? String
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل ocr_text مفقود أو غير نصي.")
            val suggestedText = itemMap["suggested_text"] as? String
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل suggested_text مفقود أو غير نصي.")

            val rawBbox = itemMap["bbox"] as? List<*>
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل bbox مفقود أو ليس مصفوفة.")
            if (rawBbox.size != 4) {
                return GeminiValidationResult.Malformed("العنصر ${index + 1}: bbox يجب أن يحتوي على 4 إحداثيات بالضبط.")
            }
            val bboxFloats = rawBbox.map { coord ->
                val num = (coord as? Number)?.toFloat()
                    ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: إحداثي غير رقمي داخل bbox.")
                if (!num.isFinite()) {
                    return GeminiValidationResult.Malformed("العنصر ${index + 1}: قيمة غير منتهية داخل bbox.")
                }
                num
            }
            if (bboxFloats[0] > bboxFloats[2] || bboxFloats[1] > bboxFloats[3]) {
                return GeminiValidationResult.Malformed("العنصر ${index + 1}: إحداثيات bbox غير مرتبة (x1 <= x2 و y1 <= y2).")
            }

            val confNum = itemMap["confidence"] as? Number
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل confidence مفقود أو غير رقمي.")
            val confidence = confNum.toFloat()
            if (!confidence.isFinite() || confidence < 0f || confidence > 1f) {
                return GeminiValidationResult.Malformed("العنصر ${index + 1}: confidence يجب أن تكون بين 0.0 و 1.0.")
            }

            val evidence = itemMap["evidence"] as? String
            if (evidence.isNullOrBlank()) {
                return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل evidence مفقود أو فارغ (الدليل إلزامي).")
            }

            val reasonTypeRaw = itemMap["reason_type"] as? String
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل reason_type مفقود.")
            val parsedReason = GeminiReasonType.fromWireValue(reasonTypeRaw)
                ?: return GeminiValidationResult.Malformed(
                    "العنصر ${index + 1}: قيمة reason_type غير صالحة ($reasonTypeRaw).",
                )

            val requiresReview = itemMap["requires_review"] as? Boolean
                ?: return GeminiValidationResult.Malformed("العنصر ${index + 1}: حقل requires_review مفقود أو غير منطقي.")

            validatedItems.add(
                GeminiSuggestionItem(
                    ocr_text = ocrText,
                    suggested_text = suggestedText,
                    bbox = bboxFloats,
                    confidence = confidence,
                    evidence = evidence.trim(),
                    reason_type = parsedReason.wireValue,
                    requires_review = requiresReview,
                ),
            )
        }

        val typed = GeminiPageAnalysisResponse(
            page_id = pageId.trim(),
            page_number = pageNumberNum.toInt(),
            items = validatedItems,
            isMissingLargeContent = isMissingLargeContent,
        )
        val normalizedJson = responseAdapter.toJson(typed)
        return GeminiValidationResult.Valid(response = typed, normalizedJson = normalizedJson)
    }

    private fun stripOptionalMarkdownFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed
        val droppedFirst = lines.drop(1)
        val droppedLast = if (droppedFirst.lastOrNull()?.trim() == "```") {
            droppedFirst.dropLast(1)
        } else {
            droppedFirst
        }
        return droppedLast.joinToString("\n").trim()
    }
}
