package com.example.data.ocr

import android.content.Context
import com.example.domain.model.OcrEngineType
import com.example.domain.ocr.OcrEngineAdapter

class OcrEngineRegistry(
    private val adapters: Map<OcrEngineType, OcrEngineAdapter>,
) {
    fun getAdapter(engineType: OcrEngineType): OcrEngineAdapter {
        return adapters[engineType]
            ?: adapters[OcrEngineType.TESSERACT]
            ?: adapters.values.first()
    }

    companion object {
        fun createDefault(context: Context): OcrEngineRegistry {
            val appContext = context.applicationContext
            return OcrEngineRegistry(
                adapters = mapOf(
                    OcrEngineType.TESSERACT to TesseractAndroidOcrEngine(appContext),
                    OcrEngineType.ML_KIT to MlKitOcrEngine(),
                ),
            )
        }
    }
}
