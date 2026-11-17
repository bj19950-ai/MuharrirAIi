package com.example.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Minimal, non-destructive image preprocessing for on-device OCR.
 *
 * Design rationale (Module 03):
 * - Always creates a fresh in-memory ARGB_8888 copy so the caller's rendered page
 *   or original PDF is never mutated.
 * - Applies only light contrast normalization (+12%) to improve faded ink separation
 *   on scanned Arabic lectures/legal pages.
 * - Intentionally skips aggressive thresholding/binarization and blind deskewing:
 *   hard binary thresholding erodes Arabic diacritics (تشكيل) and letter dots (نقط).
 */
object OcrImagePreprocessor {

    data class PreparedOcrBitmap(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int,
        val preprocessingNoteAr: String,
    ) {
        fun recycleSafely() {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun prepareForOcr(source: Bitmap, contrastGain: Float = 1.12f): PreparedOcrBitmap {
        require(!source.isRecycled) { "Cannot preprocess a recycled bitmap." }
        require(source.width > 0 && source.height > 0) {
            "Bitmap dimensions must be positive (${source.width}x${source.height})."
        }

        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val translate = (-0.5f * contrastGain + 0.5f) * 255f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrastGain, 0f, 0f, 0f, translate,
                0f, contrastGain, 0f, 0f, translate,
                0f, 0f, contrastGain, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)

        return PreparedOcrBitmap(
            bitmap = output,
            widthPx = width,
            heightPx = height,
            preprocessingNoteAr = "تعديل تباين خفيف غير إتلافي (+12%) على نسخة الذاكرة دون عتبة حادة للحفاظ على النقط والتشكيل.",
        )
    }
}
