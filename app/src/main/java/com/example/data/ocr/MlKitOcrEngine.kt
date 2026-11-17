package com.example.data.ocr

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.example.domain.model.BoundingBox
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.model.PointCoord
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.domain.ocr.OcrEngineAdapter
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Secondary on-device OCR engine backed by Google ML Kit Text Recognition v2 (16.0.1 bundled offline model).
 *
 * Role & Honest Capability Boundary (Module 03-B):
 * Secondary — Latin and numerals only, does not support Arabic.
 * Intended strictly for mixed-content or Latin/numeric documents; Tesseract4Android (`ara`)
 * is the primary OCR engine for Arabic documents.
 */
class MlKitOcrEngine : OcrEngineAdapter {

    override val engineType: OcrEngineType = OcrEngineType.ML_KIT

    override val supportsArabicScript: Boolean = false

    override val capabilityNoteAr: String =
        "محرك ثانوي — يدعم الخط اللاتيني والأرقام فقط، ولا يدعم اللغة العربية إطلاقاً. استخدمه فقط للصفحات اللاتينية أو الرقمية."

    override suspend fun checkAvailability(): OcrAvailabilityResult {
        return OcrAvailabilityResult.LimitedCapability(warningAr = capabilityNoteAr)
    }

    override suspend fun recognizePage(
        documentId: String,
        pageId: String,
        pageNumber: Int,
        pageBitmap: Bitmap,
    ): Result<OcrResult> = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(pageBitmap, 0)
            val visionText = recognizer.process(image).awaitTask()
            mapVisionTextToOcrResult(
                documentId = documentId,
                pageId = pageId,
                pageNumber = pageNumber,
                widthPx = pageBitmap.width,
                heightPx = pageBitmap.height,
                visionText = visionText,
            )
        } finally {
            recognizer.close()
        }
    }

    private fun mapVisionTextToOcrResult(
        documentId: String,
        pageId: String,
        pageNumber: Int,
        widthPx: Int,
        heightPx: Int,
        visionText: Text,
    ): OcrResult {
        val segments = mutableListOf<OcrSegment>()
        val elementBoxes = mutableListOf<BoundingBox>()
        val elementConfidences = mutableListOf<Float>()

        visionText.textBlocks.forEachIndexed { blockIndex, block ->
            val blockId = "${pageId}_b$blockIndex"
            val blockBox = block.boundingBox.toDomainBoundingBox()
            segments.add(
                OcrSegment(
                    segmentId = blockId,
                    text = block.text,
                    boundingBox = blockBox,
                    confidence = null, // ML Kit TextBlock does not expose block confidence
                    level = OcrElementLevel.BLOCK,
                    cornerPoints = block.cornerPoints.toDomainCorners(),
                    blockIndex = blockIndex,
                    lineIndex = null,
                    elementIndex = null,
                    parentBlockId = blockId,
                    parentLineId = null,
                ),
            )

            block.lines.forEachIndexed { lineIndex, line ->
                val lineId = "${blockId}_l$lineIndex"
                val lineConfidence = line.confidence.normalizeConfidenceOrNull()
                segments.add(
                    OcrSegment(
                        segmentId = lineId,
                        text = line.text,
                        boundingBox = line.boundingBox.toDomainBoundingBox(),
                        confidence = lineConfidence,
                        level = OcrElementLevel.LINE,
                        cornerPoints = line.cornerPoints.toDomainCorners(),
                        blockIndex = blockIndex,
                        lineIndex = lineIndex,
                        elementIndex = null,
                        parentBlockId = blockId,
                        parentLineId = lineId,
                    ),
                )

                line.elements.forEachIndexed { elementIndex, element ->
                    val elemBox = element.boundingBox.toDomainBoundingBox()
                    val elemConfidence = element.confidence.normalizeConfidenceOrNull()
                    if (elemBox != null) {
                        elementBoxes.add(elemBox)
                    }
                    if (elemConfidence != null) {
                        elementConfidences.add(elemConfidence)
                    }
                    segments.add(
                        OcrSegment(
                            segmentId = "${lineId}_e$elementIndex",
                            text = element.text,
                            boundingBox = elemBox,
                            confidence = elemConfidence,
                            level = OcrElementLevel.ELEMENT,
                            cornerPoints = element.cornerPoints.toDomainCorners(),
                            blockIndex = blockIndex,
                            lineIndex = lineIndex,
                            elementIndex = elementIndex,
                            parentBlockId = blockId,
                            parentLineId = lineId,
                        ),
                    )
                }
            }
        }

        val pageConfidence = if (elementConfidences.isNotEmpty()) {
            elementConfidences.average().toFloat()
        } else {
            null
        }

        return OcrResult(
            pageId = pageId,
            documentId = documentId,
            pageNumber = pageNumber,
            recognizedText = visionText.text,
            segments = segments,
            boundingBoxes = elementBoxes,
            confidence = pageConfidence,
            engineType = OcrEngineType.ML_KIT,
            imageWidthPx = widthPx,
            imageHeightPx = heightPx,
            recognizedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun Float.normalizeConfidenceOrNull(): Float? {
        if (isNaN() || this < 0f || this > 1f) return null
        return this
    }

    private fun Rect?.toDomainBoundingBox(): BoundingBox? {
        if (this == null) return null
        return BoundingBox(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
        )
    }

    private fun Array<Point>?.toDomainCorners(): List<PointCoord>? {
        if (this == null || isEmpty()) return null
        return map { PointCoord(x = it.x, y = it.y) }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { ex ->
            if (cont.isActive) cont.resumeWithException(ex)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}
