package com.example.domain.ocr

import android.graphics.Bitmap
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrResult

/**
 * Unified on-device OCR adapter contract for Module 03+.
 * Keeps Google ML Kit Text Recognition v2 and optional Tesseract4Android
 * behind a single interface so engines can be selected or swapped cleanly.
 */
interface OcrEngineAdapter {
    val engineType: OcrEngineType
    val supportsArabicScript: Boolean
    val capabilityNoteAr: String

    suspend fun checkAvailability(): OcrAvailabilityResult

    suspend fun recognizePage(
        documentId: String,
        pageId: String,
        pageNumber: Int,
        pageBitmap: Bitmap,
    ): Result<OcrResult>
}

sealed interface OcrAvailabilityResult {
    data object Ready : OcrAvailabilityResult
    data class LimitedCapability(val warningAr: String) : OcrAvailabilityResult
    data class Unavailable(val reasonAr: String) : OcrAvailabilityResult
}
