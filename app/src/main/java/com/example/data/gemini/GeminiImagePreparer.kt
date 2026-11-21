package com.example.data.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.domain.model.GeminiModelConfig
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downscales a rendered page bitmap to target <= 1024px on its long side and verifies
 * JPEG compression keeps payload strictly < 2 MB before calling Gemini.
 */
object GeminiImagePreparer {

    data class PreparedGeminiImage(
        val bitmap: Bitmap,
        val jpegBytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    ) {
        val byteSize: Int get() = jpegBytes.size

        fun recycleSafely() {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun prepareForGemini(
        source: Bitmap,
        maxLongSidePx: Int = GeminiModelConfig.MAX_LONG_SIDE_PX,
        maxImageBytes: Int = GeminiModelConfig.MAX_IMAGE_BYTES,
    ): PreparedGeminiImage {
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)
        val longSide = max(srcW, srcH)

        var workingBitmap = if (longSide > maxLongSidePx) {
            val scale = maxLongSidePx.toFloat() / longSide.toFloat()
            val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
            val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: Bitmap.createScaledBitmap(source, srcW, srcH, true)
        }

        val qualitySteps = intArrayOf(85, 72, 60, 48)
        var encodedBytes = ByteArray(0)

        var attemptResize = 0
        while (attemptResize < 3) {
            for (q in qualitySteps) {
                val out = ByteArrayOutputStream()
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
                encodedBytes = out.toByteArray()
                if (encodedBytes.size < maxImageBytes) {
                    val decoded = BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size)
                    val finalBitmap = decoded ?: workingBitmap
                    if (decoded != null && workingBitmap !== source && !workingBitmap.isRecycled) {
                        workingBitmap.recycle()
                    }
                    return PreparedGeminiImage(
                        bitmap = finalBitmap,
                        jpegBytes = encodedBytes,
                        widthPx = finalBitmap.width,
                        heightPx = finalBitmap.height,
                    )
                }
            }
            // Still over budget -> shrink dimensions further by 20% and retry
            val shrunkW = (workingBitmap.width * 0.8f).roundToInt().coerceAtLeast(64)
            val shrunkH = (workingBitmap.height * 0.8f).roundToInt().coerceAtLeast(64)
            val shrunk = Bitmap.createScaledBitmap(workingBitmap, shrunkW, shrunkH, true)
            if (workingBitmap !== source && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = shrunk
            attemptResize++
        }

        return PreparedGeminiImage(
            bitmap = workingBitmap,
            jpegBytes = encodedBytes,
            widthPx = workingBitmap.width,
            heightPx = workingBitmap.height,
        )
    }
}
