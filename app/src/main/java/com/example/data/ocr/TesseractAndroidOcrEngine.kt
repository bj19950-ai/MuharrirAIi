package com.example.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.domain.model.BoundingBox
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.domain.ocr.OcrEngineAdapter
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Primary on-device Arabic OCR engine backed by Tesseract4Android (Tesseract 5.5.1).
 *
 * Configuration:
 * - dataPath = context.filesDir.absolutePath
 * - language = "ara"
 * - pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
 *
 * Copies bundled `assets/tessdata/ara.traineddata` to `filesDir/tessdata/ara.traineddata`
 * on first launch without any network download, and verifies both file presence and
 * `TessBaseAPI.init(dataPath, "ara")` before marking the engine Ready.
 */
class TesseractAndroidOcrEngine(
    private val context: Context,
    private val trainedDataProvider: (Context) -> TrainedDataInstallResult = TesseractDataInstaller::ensureArabicTrainedData,
    private val nativeInitVerifier: (dataParentDir: File) -> NativeInitCheckResult = ::verifyTessBaseApiInit,
) : OcrEngineAdapter {

    sealed interface NativeInitCheckResult {
        data object Initialized : NativeInitCheckResult
        data class Failed(val reasonAr: String) : NativeInitCheckResult
    }

    override val engineType: OcrEngineType = OcrEngineType.TESSERACT

    override val supportsArabicScript: Boolean = true

    override val capabilityNoteAr: String =
        "المحرك الأساسي للعربية: Tesseract 5.5 على الجهاز باستخدام ملف اللغة العربية المدمج (ara.traineddata) — يعالج العربية دون اتصال بالإنترنت."

    override suspend fun checkAvailability(): OcrAvailabilityResult {
        val installResult = trainedDataProvider(context)
        if (installResult is TrainedDataInstallResult.Missing) {
            return OcrAvailabilityResult.Unavailable(reasonAr = installResult.reasonAr)
        }

        val dataParentDir = TesseractDataInstaller.tessDataParentDir(context)
        return when (val initResult = nativeInitVerifier(dataParentDir)) {
            NativeInitCheckResult.Initialized -> OcrAvailabilityResult.Ready
            is NativeInitCheckResult.Failed -> OcrAvailabilityResult.Unavailable(reasonAr = initResult.reasonAr)
        }
    }

    override suspend fun recognizePage(
        documentId: String,
        pageId: String,
        pageNumber: Int,
        pageBitmap: Bitmap,
    ): Result<OcrResult> = runCatching {
        val installResult = trainedDataProvider(context)
        if (installResult is TrainedDataInstallResult.Missing) {
            error(installResult.reasonAr)
        }

        val dataPath = TesseractDataInstaller.tessDataParentDir(context).absolutePath
        val tess = TessBaseAPI()
        try {
            val initialized = tess.init(dataPath, LANGUAGE_ARABIC)
            check(initialized) {
                "فشلت تهيئة محرك Tesseract للغة العربية ($LANGUAGE_ARABIC) في المسار $dataPath."
            }

            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            tess.setImage(pageBitmap)
            val fullText = tess.utF8Text.orEmpty().trim()

            val segments = mutableListOf<OcrSegment>()
            val wordBoxes = mutableListOf<BoundingBox>()
            val wordConfidences = mutableListOf<Float>()

            val iterator = tess.resultIterator
            if (iterator != null) {
                try {
                    iterator.begin()
                    var blockIndex = -1
                    var lineIndex = -1
                    var elementIndex = 0
                    var currentBlockId = "${pageId}_b0"
                    var currentLineId = "${pageId}_b0_l0"

                    do {
                        if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) {
                            blockIndex++
                            lineIndex = -1
                            currentBlockId = "${pageId}_b${blockIndex.coerceAtLeast(0)}"
                            val blockText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_BLOCK).orEmpty()
                            val blockBox = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)
                                ?.toDomainBoundingBox()
                            segments.add(
                                OcrSegment(
                                    segmentId = currentBlockId,
                                    text = blockText,
                                    boundingBox = blockBox,
                                    confidence = null,
                                    level = OcrElementLevel.BLOCK,
                                    cornerPoints = null,
                                    blockIndex = blockIndex.coerceAtLeast(0),
                                    lineIndex = null,
                                    elementIndex = null,
                                    parentBlockId = currentBlockId,
                                    parentLineId = null,
                                ),
                            )
                        }

                        if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                            lineIndex++
                            elementIndex = 0
                            currentLineId = "${currentBlockId}_l${lineIndex.coerceAtLeast(0)}"
                            val lineText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE).orEmpty()
                            val lineBox = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                                ?.toDomainBoundingBox()
                            val lineConf = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                                .normalizeTesseractConfidence()
                            segments.add(
                                OcrSegment(
                                    segmentId = currentLineId,
                                    text = lineText,
                                    boundingBox = lineBox,
                                    confidence = lineConf,
                                    level = OcrElementLevel.LINE,
                                    cornerPoints = null,
                                    blockIndex = blockIndex.coerceAtLeast(0),
                                    lineIndex = lineIndex.coerceAtLeast(0),
                                    elementIndex = null,
                                    parentBlockId = currentBlockId,
                                    parentLineId = currentLineId,
                                ),
                            )
                        }

                        val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!wordText.isNullOrBlank()) {
                            val wordBox = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                ?.toDomainBoundingBox()
                            val wordConf = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                .normalizeTesseractConfidence()
                            if (wordBox != null) wordBoxes.add(wordBox)
                            if (wordConf != null) wordConfidences.add(wordConf)

                            segments.add(
                                OcrSegment(
                                    segmentId = "${currentLineId}_e$elementIndex",
                                    text = wordText,
                                    boundingBox = wordBox,
                                    confidence = wordConf,
                                    level = OcrElementLevel.ELEMENT,
                                    cornerPoints = null,
                                    blockIndex = blockIndex.coerceAtLeast(0),
                                    lineIndex = lineIndex.coerceAtLeast(0),
                                    elementIndex = elementIndex,
                                    parentBlockId = currentBlockId,
                                    parentLineId = currentLineId,
                                ),
                            )
                            elementIndex++
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    iterator.delete()
                }
            }

            val pageConfidence = if (wordConfidences.isNotEmpty()) {
                wordConfidences.average().toFloat()
            } else {
                null
            }

            OcrResult(
                pageId = pageId,
                documentId = documentId,
                pageNumber = pageNumber,
                recognizedText = fullText,
                segments = segments,
                boundingBoxes = wordBoxes,
                confidence = pageConfidence,
                engineType = OcrEngineType.TESSERACT,
                imageWidthPx = pageBitmap.width,
                imageHeightPx = pageBitmap.height,
                recognizedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            tess.clear()
            tess.recycle()
        }
    }

    private fun IntArray.toDomainBoundingBox(): BoundingBox? {
        if (size < 4) return null
        return BoundingBox(
            left = this[0].toFloat(),
            top = this[1].toFloat(),
            right = this[2].toFloat(),
            bottom = this[3].toFloat(),
        )
    }

    private fun Float.normalizeTesseractConfidence(): Float? {
        if (isNaN() || this < 0f) return null
        val normalized = this / 100f
        return normalized.coerceIn(0f, 1f)
    }

    companion object {
        const val LANGUAGE_ARABIC = "ara"

        private fun verifyTessBaseApiInit(dataParentDir: File): NativeInitCheckResult {
            return try {
                val tess = TessBaseAPI()
                try {
                    val ok = tess.init(dataParentDir.absolutePath, LANGUAGE_ARABIC)
                    if (ok) {
                        NativeInitCheckResult.Initialized
                    } else {
                        NativeInitCheckResult.Failed(
                            "فشلت تهيئة TessBaseAPI للغة العربية ($LANGUAGE_ARABIC) بالرغم من وجود ملف البيانات.",
                        )
                    }
                } finally {
                    tess.clear()
                    tess.recycle()
                }
            } catch (_: UnsatisfiedLinkError) {
                NativeInitCheckResult.Failed(
                    "مكتبات Tesseract الأصلية (JNI) غير متاحة في بيئة التشغيل الحالية؛ يتطلب التنفيذ الفعلي جهاز Android أو محاكياً.",
                )
            } catch (error: Throwable) {
                NativeInitCheckResult.Failed(
                    "تعذر تهيئة محرك Tesseract للغة العربية: ${error.localizedMessage ?: "خطأ غير متوقع"}",
                )
            }
        }
    }
}
