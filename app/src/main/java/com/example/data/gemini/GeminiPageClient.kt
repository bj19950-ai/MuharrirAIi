package com.example.data.gemini

import android.graphics.Bitmap
import com.example.domain.model.GeminiModelConfig
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrResult
import com.example.domain.model.PageRecord
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException

data class GeminiPagePromptPayload(
    val pageId: String,
    val pageNumber: Int,
    val ocrText: String,
    val structuredSegmentsSummary: String,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
)

interface GeminiPageClient {
    suspend fun validateKey(apiKey: String, modelId: String): Result<Unit>

    suspend fun analyzePage(
        apiKey: String,
        modelId: String,
        pageBitmap: Bitmap,
        payload: GeminiPagePromptPayload,
    ): Result<String>
}

object GeminiPromptBuilder {

    val SYSTEM_INSTRUCTION = """
        You are Muharrir AI (مُحَرِّر AI), an expert Arabic paleography, academic, and legal document verification engine.
        You receive ONE scanned page image and its local OCR output with segment bounding boxes.
        STRICT CONTENT PRESERVATION RULES:
        1. Never summarize, omit paragraphs, remove repetitions, or rewrite the author's wording.
        2. Never replace specialized legal, religious, or academic terminology with more "natural" words.
        3. Compare the OCR output carefully against the original page image.
        4. Suggest corrections ONLY for distorted, misread, or uncertain words where the original image clearly supports the correction.
        5. Do NOT suggest changes for text that is already clearly accurate.
        6. If the image region is blurry/ambiguous and does not clearly confirm a correction, DO NOT invent text: set "suggested_text" equal to "ocr_text", set "requires_review" to true, and explain the visual ambiguity in "evidence".
        7. Recognize headings or footnotes where clearly visible ("reason_type": "structure").
        8. Every suggestion item MUST include concrete evidence citing letter shapes, diacritic dots (نقاط الحروف), context, lexicon, morphology, or structure.
        9. Output strictly valid JSON matching the exact schema with no extra wrapper text:
        {
          "page_id": "string",
          "page_number": 1,
          "items": [
            {
              "ocr_text": "string",
              "suggested_text": "string",
              "bbox": [0.0, 0.0, 100.0, 40.0],
              "confidence": 0.92,
              "evidence": "string",
              "reason_type": "visual | context | lexicon | morphology | structure",
              "requires_review": true
            }
          ]
        }
    """.trimIndent()

    fun buildPayload(
        page: PageRecord,
        ocrResult: OcrResult,
        preparedWidthPx: Int,
        preparedHeightPx: Int,
    ): GeminiPagePromptPayload {
        val blocksAndLines = ocrResult.segments
            .filter { it.level == OcrElementLevel.BLOCK || it.level == OcrElementLevel.LINE }
            .take(120)
            .joinToString("\n") { seg ->
                val boxStr = seg.boundingBox?.let { b ->
                    "[${b.left.toInt()}, ${b.top.toInt()}, ${b.right.toInt()}, ${b.bottom.toInt()}]"
                } ?: "[0, 0, 0, 0]"
                "${seg.level.name} (block=${seg.blockIndex}, line=${seg.lineIndex ?: -1}) $boxStr: ${seg.text}"
            }

        return GeminiPagePromptPayload(
            pageId = page.pageId,
            pageNumber = page.originalPageNumber,
            ocrText = ocrResult.recognizedText,
            structuredSegmentsSummary = blocksAndLines,
            imageWidthPx = preparedWidthPx,
            imageHeightPx = preparedHeightPx,
        )
    }

    fun buildUserPrompt(payload: GeminiPagePromptPayload): String = buildString {
        appendLine("Analyze this single Arabic scanned document page.")
        appendLine("page_id: \"${payload.pageId}\"")
        appendLine("page_number: ${payload.pageNumber}")
        appendLine("image_dimensions_px: ${payload.imageWidthPx}x${payload.imageHeightPx}")
        appendLine()
        appendLine("--- FULL OCR TEXT ---")
        appendLine(payload.ocrText.ifBlank { "(empty OCR text)" })
        appendLine()
        if (payload.structuredSegmentsSummary.isNotBlank()) {
            appendLine("--- OCR STRUCTURE & BOUNDING BOXES ---")
            appendLine(payload.structuredSegmentsSummary)
            appendLine()
        }
        appendLine("Return ONLY valid JSON conforming to the schema. Keep \"items\": [] if OCR matches the image cleanly.")
    }
}

/**
 * Official Google AI client SDK (`com.google.ai.client.generativeai:generativeai`) implementation.
 * All requests go directly from the Android device to Google's API via the provided user key.
 */
class GoogleAiGeminiClient : GeminiPageClient {

    override suspend fun validateKey(apiKey: String, modelId: String): Result<Unit> {
        return try {
            val model = GenerativeModel(
                modelName = modelId,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0f
                    maxOutputTokens = 8
                },
            )
            val response = model.generateContent("أجب بكلمة: جاهز")
            if (response.text != null) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("استجابة فارغة من خادم Gemini أثناء التحقق من المفتاح."))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun analyzePage(
        apiKey: String,
        modelId: String,
        pageBitmap: Bitmap,
        payload: GeminiPagePromptPayload,
    ): Result<String> {
        return try {
            val model = GenerativeModel(
                modelName = modelId,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = GeminiModelConfig.TEMPERATURE
                    responseMimeType = "application/json"
                },
                systemInstruction = content {
                    text(GeminiPromptBuilder.SYSTEM_INSTRUCTION)
                },
            )

            val input = content {
                image(pageBitmap)
                text(GeminiPromptBuilder.buildUserPrompt(payload))
            }

            val response = model.generateContent(input)
            val text = response.text?.trim()
            if (text.isNullOrEmpty()) {
                Result.failure(IllegalStateException("أرجع نموذج Gemini استجابة نصية فارغة."))
            } else {
                Result.success(text)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
