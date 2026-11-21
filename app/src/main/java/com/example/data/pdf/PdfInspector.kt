package com.example.data.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.data.storage.PersistableUriPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface PdfInspectionResult {
    data class Valid(
        val uriString: String,
        val fileName: String,
        val fileSizeBytes: Long,
        val pageCount: Int?,
        val previewBitmap: Bitmap?,
        val previewNoteAr: String?,
    ) : PdfInspectionResult

    data class Invalid(
        val reasonAr: String,
    ) : PdfInspectionResult
}

sealed interface PdfValidationCheck {
    data object Passed : PdfValidationCheck
    data class Failed(val reasonAr: String) : PdfValidationCheck
}

data class IndexedPdfPageMeta(
    val pageNumber: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)

sealed interface PrivatePdfIndexResult {
    data class Success(
        val pageCount: Int,
        val pages: List<IndexedPdfPageMeta>,
    ) : PrivatePdfIndexResult

    data class Failure(
        val reasonAr: String,
    ) : PrivatePdfIndexResult
}

data class SourcePdfMetadata(
    val fileName: String,
    val declaredSizeBytes: Long,
    val mimeType: String?,
)

interface PdfPageEngine {
    suspend fun indexPages(
        pdfFile: File,
        batchSize: Int = 25,
        onBatchIndexed: suspend (indexedCount: Int, totalPages: Int, batch: List<IndexedPdfPageMeta>) -> Unit = { _, _, _ -> },
    ): PrivatePdfIndexResult

    suspend fun renderFromFile(
        pdfFile: File,
        pageIndex: Int,
        maxWidthPx: Int = 1080,
    ): Bitmap?
}

object AndroidPdfRendererEngine : PdfPageEngine {
    override suspend fun indexPages(
        pdfFile: File,
        batchSize: Int,
        onBatchIndexed: suspend (indexedCount: Int, totalPages: Int, batch: List<IndexedPdfPageMeta>) -> Unit,
    ): PrivatePdfIndexResult = PdfInspector.indexPrivatePdfPages(pdfFile, batchSize, onBatchIndexed)

    override suspend fun renderFromFile(
        pdfFile: File,
        pageIndex: Int,
        maxWidthPx: Int,
    ): Bitmap? = PdfInspector.renderFromPrivateFilePublic(pdfFile, pageIndex, maxWidthPx)
}

object PdfInspector {
    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-

    // Bounded in-memory LRU cache (~16 MB max) to avoid OOM on large multi-page PDFs.
    private val pageBitmapCache: LruCache<String, Bitmap> by lazy {
        val maxKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
            .toInt()
            .coerceIn(4 * 1024, 24 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun clearMemoryCache() {
        pageBitmapCache.evictAll()
    }

    fun validatePdfSignatureAndMetadata(
        fileName: String?,
        mimeType: String?,
        fileSizeBytes: Long,
        headerBytes: ByteArray,
    ): PdfValidationCheck {
        if (fileSizeBytes == 0L) {
            return PdfValidationCheck.Failed("الملف المحدد فارغ (0 بايت).")
        }
        val normalizedName = fileName.orEmpty().lowercase()
        val isPdfMime = mimeType == "application/pdf" ||
            mimeType == "application/x-pdf" ||
            (mimeType == null && normalizedName.endsWith(".pdf")) ||
            (mimeType == "application/octet-stream" && normalizedName.endsWith(".pdf"))

        if (!isPdfMime && !normalizedName.endsWith(".pdf")) {
            return PdfValidationCheck.Failed("صيغة الملف غير مدعومة. يرجى اختيار ملف PDF صالح.")
        }

        if (headerBytes.size < PDF_MAGIC.size ||
            !headerBytes.copyOfRange(0, PDF_MAGIC.size).contentEquals(PDF_MAGIC)
        ) {
            return PdfValidationCheck.Failed("ترويسة الملف لا تطابق صيغة PDF القياسية (%PDF-).")
        }

        return PdfValidationCheck.Passed
    }

    fun querySourceMetadata(context: Context, uri: Uri): SourcePdfMetadata {
        val resolver = context.contentResolver
        var displayName = "document.pdf"
        var declaredSize = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let {
                            displayName = it
                        }
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        declaredSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        return SourcePdfMetadata(
            fileName = displayName,
            declaredSizeBytes = declaredSize,
            mimeType = resolver.getType(uri),
        )
    }

    suspend fun inspectPdf(
        context: Context,
        uri: Uri,
    ): PdfInspectionResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        PersistableUriPermissionManager.takePersistableReadPermission(resolver, uri)

        val meta = querySourceMetadata(context, uri)
        val headerBuffer = ByteArray(PDF_MAGIC.size)
        var bytesRead = 0
        var computedSize = meta.declaredSizeBytes

        try {
            resolver.openInputStream(uri)?.use { stream ->
                bytesRead = stream.read(headerBuffer)
                if (computedSize < 0L) {
                    var total = if (bytesRead > 0) bytesRead.toLong() else 0L
                    val scratch = ByteArray(8192)
                    while (true) {
                        val count = stream.read(scratch)
                        if (count <= 0) break
                        total += count
                    }
                    computedSize = total
                }
            } ?: return@withContext PdfInspectionResult.Invalid("تعذر فتح الملف للقراءة من النظام.")
        } catch (e: SecurityException) {
            return@withContext PdfInspectionResult.Invalid("لا توجد صلاحية لقراءة هذا الملف: ${e.localizedMessage ?: "مرفوض"}")
        } catch (e: IOException) {
            return@withContext PdfInspectionResult.Invalid("حدث خطأ أثناء قراءة الملف: ${e.localizedMessage ?: "خطأ إدخال/إخراج"}")
        }

        val actualHeader = if (bytesRead > 0) headerBuffer.copyOf(bytesRead) else ByteArray(0)
        when (val validation = validatePdfSignatureAndMetadata(meta.fileName, meta.mimeType, computedSize, actualHeader)) {
            is PdfValidationCheck.Failed -> return@withContext PdfInspectionResult.Invalid(validation.reasonAr)
            PdfValidationCheck.Passed -> Unit
        }

        var pageCount: Int? = null
        var previewBitmap: Bitmap? = null
        var previewNote: String? = null

        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (renderer.pageCount > 0) {
                        previewBitmap = renderRendererPage(renderer, pageIndex = 0, maxWidthPx = 1080)
                    } else {
                        return@withContext PdfInspectionResult.Invalid("ملف PDF المحدد لا يحتوي على أي صفحات.")
                    }
                }
            } ?: run {
                previewNote = "تعذر فتح واصف الملف لمعاينة الصفحات محلياً."
            }
        } catch (_: SecurityException) {
            return@withContext PdfInspectionResult.Invalid("ملف PDF محمي بكلمة مرور أو مشفر؛ يتعذر استيراده حالياً.")
        } catch (_: IOException) {
            return@withContext PdfInspectionResult.Invalid("ملف PDF تالف أو غير مدعوم أو تعذر فتحه.")
        } catch (_: IllegalStateException) {
            previewNote = "تعذر تهيئة معاينة PDF محلياً."
        }

        PdfInspectionResult.Valid(
            uriString = uri.toString(),
            fileName = meta.fileName,
            fileSizeBytes = computedSize.coerceAtLeast(0L),
            pageCount = pageCount,
            previewBitmap = previewBitmap,
            previewNoteAr = previewNote,
        )
    }

    /**
     * Indexes all pages of an imported private PDF file page-by-page without allocating bitmaps.
     * No artificial page count limit is imposed.
     */
    suspend fun indexPrivatePdfPages(
        pdfFile: File,
        batchSize: Int = 25,
        onBatchIndexed: suspend (indexedCount: Int, totalPages: Int, batch: List<IndexedPdfPageMeta>) -> Unit = { _, _, _ -> },
    ): PrivatePdfIndexResult = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            return@withContext PrivatePdfIndexResult.Failure("الملف المحفوظ غير موجود أو فارغ.")
        }

        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val total = renderer.pageCount
                    if (total <= 0) {
                        return@withContext PrivatePdfIndexResult.Failure("ملف PDF لا يحتوي على أي صفحات قابلة للفهرسة.")
                    }

                    val allPages = ArrayList<IndexedPdfPageMeta>(total)
                    val currentBatch = ArrayList<IndexedPdfPageMeta>(batchSize)

                    for (index in 0 until total) {
                        currentCoroutineContext().ensureActive()
                        val meta = renderer.openPage(index).use { page ->
                            IndexedPdfPageMeta(
                                pageNumber = index + 1,
                                widthPoints = page.width,
                                heightPoints = page.height,
                            )
                        }
                        allPages.add(meta)
                        currentBatch.add(meta)

                        if (currentBatch.size >= batchSize || index == total - 1) {
                            onBatchIndexed(index + 1, total, currentBatch.toList())
                            currentBatch.clear()
                        }
                    }

                    PrivatePdfIndexResult.Success(
                        pageCount = total,
                        pages = allPages,
                    )
                }
            }
        } catch (_: SecurityException) {
            PrivatePdfIndexResult.Failure("ملف PDF محمي بكلمة مرور أو مشفر؛ تعذرت فهرسة صفحاته.")
        } catch (_: IOException) {
            PrivatePdfIndexResult.Failure("ملف PDF تالف أو غير مدعوم؛ تعذرت قراءة صفحاته.")
        } catch (_: IllegalStateException) {
            PrivatePdfIndexResult.Failure("تعذرت فهرسة صفحات PDF بسبب حالة غير صالحة في عارض النظام.")
        }
    }

    suspend fun renderPage(
        context: Context,
        sourceRef: String,
        pageIndex: Int,
        maxWidthPx: Int = 1080,
        pageEngine: PdfPageEngine = AndroidPdfRendererEngine,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${sourceRef}#${pageIndex}#${maxWidthPx}"
        pageBitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        val localFile = File(sourceRef)
        val rawBitmap = if (localFile.exists() && localFile.isFile) {
            pageEngine.renderFromFile(localFile, pageIndex, maxWidthPx)
        } else {
            val uri = runCatching { Uri.parse(sourceRef) }.getOrNull() ?: return@withContext null
            renderFromUri(context, uri, pageIndex, maxWidthPx)
        }

        val bitmap = rawBitmap?.let {
            BitmapSafety.downsampleToMaxDimension(
                bitmap = it,
                maxDimensionPx = BitmapSafety.MAX_RENDER_DIMENSION_PX,
                recycleOriginal = true,
            )
        }

        if (bitmap != null) {
            pageBitmapCache.put(cacheKey, bitmap)
        }
        bitmap
    }

    /**
     * Generates or loads a lightweight JPEG thumbnail (~320px width) on demand without storing full-res images.
     */
    suspend fun getOrGenerateThumbnail(
        privatePdfFile: File,
        thumbnailFile: File,
        pageIndex: Int,
        maxWidthPx: Int = 320,
        pageEngine: PdfPageEngine = AndroidPdfRendererEngine,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "thumb:${privatePdfFile.absolutePath}#${pageIndex}"
        pageBitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        if (thumbnailFile.exists() && thumbnailFile.length() > 0L) {
            val decoded = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
            if (decoded != null) {
                pageBitmapCache.put(cacheKey, decoded)
                return@withContext decoded
            }
        }

        val rendered = pageEngine.renderFromFile(privatePdfFile, pageIndex, maxWidthPx) ?: return@withContext null
        try {
            thumbnailFile.parentFile?.mkdirs()
            FileOutputStream(thumbnailFile).use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
        } catch (_: IOException) {
            // Disk thumbnail cache failure is non-fatal; return rendered bitmap.
        }
        pageBitmapCache.put(cacheKey, rendered)
        rendered
    }

    fun renderFromPrivateFilePublic(
        pdfFile: File,
        pageIndex: Int,
        maxWidthPx: Int,
    ): Bitmap? = renderFromPrivateFile(pdfFile, pageIndex, maxWidthPx)

    private fun renderFromPrivateFile(
        pdfFile: File,
        pageIndex: Int,
        maxWidthPx: Int,
    ): Bitmap? {
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex in 0 until renderer.pageCount) {
                        renderRendererPage(renderer, pageIndex, maxWidthPx)
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderFromUri(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        maxWidthPx: Int,
    ): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex in 0 until renderer.pageCount) {
                        renderRendererPage(renderer, pageIndex, maxWidthPx)
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderRendererPage(
        renderer: PdfRenderer,
        pageIndex: Int,
        maxWidthPx: Int,
    ): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val cappedMaxWidth = maxWidthPx.coerceIn(1, BitmapSafety.MAX_RENDER_DIMENSION_PX)
            val initialWidth = page.width.coerceAtLeast(1).let { w ->
                if (w > cappedMaxWidth) cappedMaxWidth else (w * 2).coerceAtMost(cappedMaxWidth)
            }
            val scale = initialWidth.toFloat() / page.width.coerceAtLeast(1).toFloat()
            val initialHeight = (page.height * scale).toInt().coerceAtLeast(1)
            val (targetWidth, targetHeight) = BitmapSafety.calculateProportionalTargetSize(
                width = initialWidth,
                height = initialHeight,
                maxDimensionPx = BitmapSafety.MAX_RENDER_DIMENSION_PX,
            )
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }
}
