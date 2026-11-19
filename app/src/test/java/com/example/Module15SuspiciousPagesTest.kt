package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.GeminiRunOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.DocumentRecord
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrResult
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.example.domain.usecase.DetectSuspiciousPagesUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module15SuspiciousPagesTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "muharrir_documents").deleteRecursively()
    }

    private fun openInMemoryDb(): MuharrirDatabase =
        Room.inMemoryDatabaseBuilder(context, MuharrirDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun sampleTextOfLength(targetLength: Int): String {
        val base = "أ"
        return base.repeat(targetLength)
    }

    @Test
    fun `detectSuspiciousPages identifies 50-char pages below 20 percent of average and ignores 1000-char pages`() = runTest {
        val pages = (1..6).map { pageNum ->
            val text = if (pageNum == 1 || pageNum == 4) {
                sampleTextOfLength(50)
            } else {
                sampleTextOfLength(1000)
            }
            PageRecord(
                pageId = "doc-sim_page_$pageNum",
                documentId = "doc-sim",
                originalPageNumber = pageNum,
                imageRef = null,
                status = PageStatus.OCR_DONE,
                ocrStatus = OcrPageStatus.DONE,
                ocrResult = OcrResult(
                    pageId = "doc-sim_page_$pageNum",
                    documentId = "doc-sim",
                    pageNumber = pageNum,
                    recognizedText = text,
                    segments = emptyList(),
                    boundingBoxes = emptyList(),
                    confidence = 0.9f,
                    engineType = OcrEngineType.TESSERACT,
                ),
            )
        }

        val useCase = DetectSuspiciousPagesUseCase()
        val analysis = useCase.analyze(pages)

        // Average = (4 * 1000 + 2 * 50) / 6 = 683.33 -> 20% = 136.67
        assertEquals((4 * 1000 + 2 * 50) / 6.0, analysis.averageCharCount, 0.01)
        assertEquals(listOf(1, 4), analysis.suspiciousPages.map { it.originalPageNumber })

        val db = openInMemoryDb()
        val repo = MuharrirRepository(database = db, storage = storage)
        val doc = DocumentRecord(
            id = "doc-sim",
            fileName = "simulated.pdf",
            fileSizeBytes = 1024L,
            uploadDateMillis = 1000L,
            pageCount = 6,
            status = DocumentStatus.PROCESSING,
            originalFileRef = "",
            isValidationPassed = true,
            pages = pages,
        )
        val repoDetected = repo.detectSuspiciousPages(doc)
        assertEquals(listOf(1, 4), repoDetected.map { it.originalPageNumber })
        db.close()
    }

    @Test
    fun `detectSuspiciousPages excludes completely blank visually empty pages`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-blank-exclusion"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }

        seedDocAndPages(
            db = db,
            docId = docId,
            pdfPath = pdfFile.absolutePath,
            pageLengths = mapOf(
                1 to 1000,
                2 to 1000,
                3 to 1000,
                4 to 50, // non-blank short page -> suspicious
                5 to 10, // visually blank page -> must be excluded
            ),
        )

        val engine = object : PdfPageEngine {
            override suspend fun indexPages(
                pdfFile: File,
                batchSize: Int,
                onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
            ) = PrivatePdfIndexResult.Success(5, (1..5).map { IndexedPdfPageMeta(it, 595, 842) })

            override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap {
                val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                // Page 5 (index 4) stays pure white; pages 1..4 have dark text ink
                if (pageIndex != 4) {
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply { color = Color.BLACK }
                    canvas.drawRect(20f, 20f, 180f, 260f, paint)
                }
                return bmp
            }
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = engine,
        )
        val doc = repo.getDocumentById(docId)!!
        val suspicious = repo.detectSuspiciousPages(doc)

        assertEquals(listOf(4), suspicious.map { it.originalPageNumber })
        db.close()
    }

    @Test
    fun `fixSuspiciousPagesWithGemini extracts only suspicious pages ignoring 150-char limit and overwrites Room`() = runTest {
        val db = openInMemoryDb()
        val docId = "doc-gemini-fix-m15"
        val docDir = File(context.filesDir, "muharrir_documents/$docId").apply { mkdirs() }
        val pdfFile = File(docDir, "original.pdf").apply { writeText("%PDF-1.4 mock") }

        // Average = (4*1200 + 180)/5 = 996 -> 20% = 199.2 -> 180 chars IS suspicious even though > 150!
        seedDocAndPages(
            db = db,
            docId = docId,
            pdfPath = pdfFile.absolutePath,
            pageLengths = mapOf(
                1 to 1200,
                2 to 180,
                3 to 1200,
                4 to 1200,
                5 to 1200,
            ),
        )

        val prefsName = "test_m15_vault"
        context.deleteSharedPreferences(prefsName)
        val vault = EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE) },
        )
        vault.addKey("M15Key", "AIzaSyM15TestKey123456")

        var geminiExtractionCalls = 0
        val renderedPageIndices = mutableListOf<Int>()

        val fullGeminiText = "النص الكامل المستخرج من الصفحة الثانية عبر Gemini " + "محتوى ".repeat(180)
        val client = object : GeminiPageClient {
            override suspend fun validateKey(apiKey: String, modelId: String) = Result.success(Unit)
            override suspend fun analyzePage(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                payload: GeminiPagePromptPayload,
            ) = Result.success("{}")

            override suspend fun extractPlainText(
                apiKey: String,
                modelId: String,
                pageBitmap: Bitmap,
                prompt: String,
            ): Result<String> {
                geminiExtractionCalls++
                return Result.success(fullGeminiText)
            }
        }

        val engine = object : PdfPageEngine {
            override suspend fun indexPages(
                pdfFile: File,
                batchSize: Int,
                onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
            ) = PrivatePdfIndexResult.Success(5, (1..5).map { IndexedPdfPageMeta(it, 595, 842) })

            override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap {
                renderedPageIndices.add(pageIndex)
                val bmp = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val canvas = Canvas(bmp)
                val paint = Paint().apply { color = Color.BLACK }
                canvas.drawRect(25f, 25f, 210f, 320f, paint)
                return bmp
            }
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = engine,
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, client),
        )

        val beforeDoc = repo.getDocumentById(docId)!!
        assertEquals(listOf(2), repo.detectSuspiciousPages(beforeDoc).map { it.originalPageNumber })

        val outcome = repo.fixSuspiciousPagesWithGemini(docId) as GeminiRunOutcome.Completed
        assertEquals(1, outcome.analyzedNow)
        assertEquals(0, outcome.failedPages)
        assertEquals(1, geminiExtractionCalls)
        // Page index 1 corresponds to pageNumber 2 (once for detect, once for full render)
        assertTrue(renderedPageIndices.all { it == 1 })

        val afterDoc = repo.getDocumentById(docId)!!
        val repairedPage2Text = afterDoc.pages.first { it.originalPageNumber == 2 }.ocrResult!!.recognizedText
        assertEquals(fullGeminiText.trim(), repairedPage2Text)
        assertEquals(sampleTextOfLength(1200), afterDoc.pages.first { it.originalPageNumber == 1 }.ocrResult!!.recognizedText)
        assertTrue(repo.detectSuspiciousPages(afterDoc).isEmpty())

        context.deleteSharedPreferences(prefsName)
        db.close()
    }

    private suspend fun seedDocAndPages(
        db: MuharrirDatabase,
        docId: String,
        pdfPath: String,
        pageLengths: Map<Int, Int>,
    ) {
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "test.pdf",
                internalStoragePath = pdfPath,
                fileSizeBytes = 2048L,
                importDateMillis = 1000L,
                lastModifiedMillis = 1000L,
                pageCount = pageLengths.size,
                indexedPagesCount = pageLengths.size,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 100,
                isValidationPassed = true,
                validationNote = null,
                errorMessageAr = null,
            ),
        )
        val pages = pageLengths.keys.sorted().map { num ->
            PageEntity(
                pageId = "${docId}_page_$num",
                documentId = docId,
                originalPageNumber = num,
                widthPoints = 595,
                heightPoints = 842,
                renderingReference = pdfPath,
                thumbnailCachePath = null,
                pageStatus = PageStatus.OCR_DONE.name,
                ocrStatus = OcrPageStatus.DONE.name,
                ocrEngineUsed = OcrEngineType.TESSERACT.name,
            )
        }
        db.pageDao().insertPages(pages)

        for ((num, len) in pageLengths) {
            db.ocrResultDao().upsertOcrResult(
                OcrResultEntity(
                    pageId = "${docId}_page_$num",
                    documentId = docId,
                    pageNumber = num,
                    engineType = OcrEngineType.TESSERACT.name,
                    recognizedText = sampleTextOfLength(len),
                    segmentsJson = "[]",
                    confidence = 0.85f,
                    imageWidthPx = 400,
                    imageHeightPx = 600,
                    recognizedAtMillis = 1000L,
                ),
            )
        }
    }
}
