package com.example.data.ocr

import android.content.Context
import java.io.File

sealed interface TrainedDataInstallResult {
    data class Ready(
        val file: File,
        val copiedFromAssets: Boolean,
        val sizeBytes: Long,
    ) : TrainedDataInstallResult

    data class Missing(
        val reasonAr: String,
    ) : TrainedDataInstallResult
}

/**
 * Installs bundled Arabic traineddata (`assets/tessdata/ara.traineddata`) into
 * `context.filesDir/tessdata/ara.traineddata` on first app launch and verifies
 * that the target file exists and is non-empty before OCR runs.
 * Never performs any runtime network download.
 */
object TesseractDataInstaller {
    private const val TESSDATA_DIR_NAME = "tessdata"
    private const val ARA_TRAINEDDATA = "ara.traineddata"
    private const val ASSET_ARA_PATH = "$TESSDATA_DIR_NAME/$ARA_TRAINEDDATA"

    fun tessDataParentDir(context: Context): File = context.filesDir

    fun targetAraFile(context: Context): File =
        File(File(tessDataParentDir(context), TESSDATA_DIR_NAME), ARA_TRAINEDDATA)

    fun ensureArabicTrainedData(context: Context): TrainedDataInstallResult {
        val tessDir = File(tessDataParentDir(context), TESSDATA_DIR_NAME)
        val targetFile = File(tessDir, ARA_TRAINEDDATA)
        if (targetFile.exists() && targetFile.length() > 0L) {
            return TrainedDataInstallResult.Ready(
                file = targetFile,
                copiedFromAssets = false,
                sizeBytes = targetFile.length(),
            )
        }

        return runCatching {
            val bundledFiles = context.assets.list(TESSDATA_DIR_NAME)?.toSet().orEmpty()
            if (ARA_TRAINEDDATA !in bundledFiles) {
                return@runCatching TrainedDataInstallResult.Missing(
                    reasonAr = "ملف اللغة العربية ($ARA_TRAINEDDATA) غير موجود داخل أصول التطبيق (assets/tessdata).",
                )
            }

            if (!tessDir.exists()) {
                tessDir.mkdirs()
            }
            val tempFile = File(tessDir, "$ARA_TRAINEDDATA.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            context.assets.open(ASSET_ARA_PATH).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return@runCatching TrainedDataInstallResult.Missing(
                    reasonAr = "فشل التحقق من حجم ملف $ARA_TRAINEDDATA بعد نسخه من الأصول.",
                )
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            if (targetFile.exists() && targetFile.length() > 0L) {
                TrainedDataInstallResult.Ready(
                    file = targetFile,
                    copiedFromAssets = true,
                    sizeBytes = targetFile.length(),
                )
            } else {
                TrainedDataInstallResult.Missing(
                    reasonAr = "تعذر التحقق من وجود ملف $ARA_TRAINEDDATA في التخزين الخاص بعد النسخ.",
                )
            }
        }.getOrElse { error ->
            TrainedDataInstallResult.Missing(
                reasonAr = "تعذر نسخ ملف اللغة العربية ($ARA_TRAINEDDATA) إلى مجلد التطبيق: ${error.localizedMessage ?: "خطأ إدخال/إخراج"}",
            )
        }
    }
}
