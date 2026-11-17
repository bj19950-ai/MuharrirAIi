package com.example.data.storage

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

sealed interface StreamCopyResult {
    data class Success(
        val finalFile: File,
        val bytesWritten: Long,
    ) : StreamCopyResult

    data class Failure(
        val reasonAr: String,
        val isInterrupted: Boolean = false,
    ) : StreamCopyResult
}

/**
 * Manages application-private storage for imported PDFs and bounded thumbnail caches.
 * Imported files reside inside `filesDir` and remain available even if external source files move/delete.
 */
class PrivateDocumentStorage(context: Context) {
    private val appContext = context.applicationContext
    private val docsRootDir = File(appContext.filesDir, "muharrir_docs").apply { mkdirs() }
    private val thumbsRootDir = File(appContext.cacheDir, "muharrir_thumbs").apply { mkdirs() }

    fun getDocumentDirectory(documentId: String): File =
        File(docsRootDir, documentId).apply { mkdirs() }

    fun getPrivatePdfFile(documentId: String): File =
        File(getDocumentDirectory(documentId), "original.pdf")

    fun getStagingPdfFile(documentId: String): File =
        File(getDocumentDirectory(documentId), "original.pdf.part")

    fun getReplacementStagingFile(documentId: String): File =
        File(getDocumentDirectory(documentId), "replacement.pdf.part")

    fun getThumbnailFile(documentId: String, pageNumber: Int): File {
        val docThumbDir = File(thumbsRootDir, documentId).apply { mkdirs() }
        return File(docThumbDir, "page_$pageNumber.jpg")
    }

    fun getAvailableInternalBytes(): Long {
        val statBytes = try {
            StatFs(docsRootDir.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
        return if (statBytes > 0L) statBytes else docsRootDir.usableSpace
    }

    fun hasSufficientSpace(expectedBytes: Long): Boolean {
        if (expectedBytes <= 0L) return true
        val safetyMarginBytes = 2L * 1024L * 1024L // 2 MB headroom
        return getAvailableInternalBytes() >= expectedBytes + safetyMarginBytes
    }

    /**
     * Streams PDF bytes into a staging file without loading the entire document into memory.
     * Validates the `%PDF-` magic signature on the initial chunk.
     */
    suspend fun streamCopyToStaging(
        inputStream: InputStream,
        stagingFile: File,
        expectedBytes: Long,
        onBytesCopied: suspend (copiedBytes: Long) -> Unit = {},
    ): StreamCopyResult {
        if (expectedBytes > 0L && !hasSufficientSpace(expectedBytes)) {
            return StreamCopyResult.Failure(
                reasonAr = "المساحة التخزينية الداخلية غير كافية لاستيراد هذا الملف.",
            )
        }

        stagingFile.parentFile?.mkdirs()
        if (stagingFile.exists()) {
            stagingFile.delete()
        }

        val buffer = ByteArray(32 * 1024)
        var totalWritten = 0L
        var headerChecked = false
        val pdfMagic = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-

        try {
            FileOutputStream(stagingFile).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = inputStream.read(buffer)
                    if (read <= 0) break

                    if (!headerChecked) {
                        headerChecked = true
                        if (read < pdfMagic.size ||
                            !buffer.copyOfRange(0, pdfMagic.size).contentEquals(pdfMagic)
                        ) {
                            stagingFile.delete()
                            return StreamCopyResult.Failure(
                                reasonAr = "محتوى الملف لا يطابق صيغة PDF القياسية (%PDF-).",
                            )
                        }
                    }

                    output.write(buffer, 0, read)
                    totalWritten += read
                    onBytesCopied(totalWritten)
                }
                output.flush()
            }

            if (totalWritten == 0L) {
                stagingFile.delete()
                return StreamCopyResult.Failure("الملف المحدد فارغ (0 بايت).")
            }

            return StreamCopyResult.Success(
                finalFile = stagingFile,
                bytesWritten = totalWritten,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            stagingFile.delete()
            throw e
        } catch (e: IOException) {
            stagingFile.delete()
            val message = e.message?.lowercase().orEmpty()
            val reason = if (message.contains("enospc") || message.contains("no space")) {
                "نفدت المساحة التخزينية أثناء حفظ الملف في التخزين الخاص."
            } else {
                "انقطع نسخ الملف إلى التخزين الخاص: ${e.localizedMessage ?: "خطأ إدخال/إخراج"}"
            }
            return StreamCopyResult.Failure(reasonAr = reason, isInterrupted = true)
        }
    }

    fun promoteStagingToPrivatePdf(documentId: String, stagingFile: File): File? {
        val target = getPrivatePdfFile(documentId)
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) {
            return null
        }
        return if (stagingFile.renameTo(target)) {
            target
        } else {
            // Fallback copy if rename fails across mounts
            try {
                stagingFile.copyTo(target, overwrite = true)
                stagingFile.delete()
                target
            } catch (_: IOException) {
                null
            }
        }
    }

    fun deleteDocumentFiles(documentId: String) {
        File(docsRootDir, documentId).deleteRecursively()
        File(thumbsRootDir, documentId).deleteRecursively()
    }

    fun resolvePrivatePdfIfExists(internalStoragePath: String): File? {
        if (internalStoragePath.isBlank()) return null
        val file = File(internalStoragePath)
        return if (file.exists() && file.isFile && file.length() > 0L) file else null
    }
}
