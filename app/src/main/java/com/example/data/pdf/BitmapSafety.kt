package com.example.data.pdf

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Memory and resolution guardrails for PDF rendering, OCR preprocessing, and Gemini crops.
 */
object BitmapSafety {
    const val MAX_RENDER_DIMENSION_PX = 2500

    /**
     * Computes proportional (width, height) constrained so max(width, height) <= [maxDimensionPx].
     */
    fun calculateProportionalTargetSize(
        width: Int,
        height: Int,
        maxDimensionPx: Int = MAX_RENDER_DIMENSION_PX,
    ): Pair<Int, Int> {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val safeMax = maxDimensionPx.coerceAtLeast(1)
        val longestSide = max(safeW, safeH)
        if (longestSide <= safeMax) {
            return safeW to safeH
        }
        val scale = safeMax.toFloat() / longestSide.toFloat()
        val targetW = (safeW * scale).roundToInt().coerceIn(1, safeMax)
        val targetH = (safeH * scale).roundToInt().coerceIn(1, safeMax)
        return targetW to targetH
    }

    /**
     * Downscales [bitmap] proportionally when width or height exceeds [maxDimensionPx] (default 2500px).
     * When a new scaled bitmap is created and [recycleOriginal] is true, the oversized original
     * bitmap is immediately recycled to prevent OOM.
     */
    fun downsampleToMaxDimension(
        bitmap: Bitmap,
        maxDimensionPx: Int = MAX_RENDER_DIMENSION_PX,
        recycleOriginal: Boolean = true,
    ): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val safeMax = maxDimensionPx.coerceAtLeast(1)
        if (bitmap.width <= safeMax && bitmap.height <= safeMax) {
            return bitmap
        }

        val (targetW, targetH) = calculateProportionalTargetSize(
            width = bitmap.width,
            height = bitmap.height,
            maxDimensionPx = safeMax,
        )
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (recycleOriginal && scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }

    fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
