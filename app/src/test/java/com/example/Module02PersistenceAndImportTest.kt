package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.PageEntity
import com.example.data.pdf.IndexedPdfPageMeta
import com.example.data.pdf.PdfInspector
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.repository.ImportOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.storage.PrivateDocumentStorage
import com.example.data.storage.StreamCopyResult
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.PageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module02PersistenceAndImportTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_restart.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.cacheDir, "muharrir_thumbs").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        File(context.cacheDir, "muharrir_thumbs").deleteRecursively()
        PdfInspector.clearMemoryCache()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

    private class HostPdfPageEngine(
        private val pageCountToReturn: Int = 3,
        private val shouldFailWithCorrupted: Boolean = false,
    ) : PdfPageEngine {
        override suspend fun indexPages(
            pdfFile: File,
            batchSize: Int,
            onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
        ): PrivatePdfIndexResult {
            if (shouldFailWithCorrupted) {
                return PrivatePdfIndexResult.Failure("ملف PDF تالف أو غير مدعوم؛ تعذرت قراءة صفحاته.")
            }
            val pages = (1..pageCountToReturn).map { num ->
                IndexedPdfPageMeta(pageNumber = num, widthPoints = 595, heightPoints = 842)
            }
            pages.chunked(batchSize).forEachIndexed { idx, batch ->
                val indexedSoFar = ((idx + 1) * batchSize).coerceAtMost(pageCountToReturn)
                onBatchIndexed(indexedSoFar, pageCountToReturn, batch)
            }
            return PrivatePdfIndexResult.Success(pageCount = pageCountToReturn, pages = pages)
        }

        override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap? {
            if (!pdfFile.exists() || pageIndex < 0 || pageIndex >= pageCountToReturn) return null
            return Bitmap.createBitmap(120, 170, Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun `documents and pages survive database close and repository recreation`() = runTest {
        val docId = "doc-restart-01"
        val privateFile = storage.getPrivatePdfFile(docId)
        privateFile.parentFile?.mkdirs()
        privateFile.writeBytes("%PDF-1.4\n%sample".toByteArray())

        val db1 = openTestDatabase()
        val pages = (1..350).map { pageNum ->
            PageEntity(
                pageId = "${docId}_page_$pageNum",
                documentId = docId,
                originalPageNumber = pageNum,
                widthPoints = 595,
                heightPoints = 842,
                renderingReference = privateFile.absolutePath,
                thumbnailCachePath = null,
                pageStatus = PageStatus.PENDING.name,
            )
        }
        db1.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "مذكرة_القانون_المدني.pdf",
                internalStoragePath = privateFile.absolutePath,
                fileSizeBytes = privateFile.length(),
                importDateMillis = 1_700_000_000_000L,
                lastModifiedMillis = 1_700_000_000_000L,
                pageCount = 350,
                indexedPagesCount = 350,
                processingStatus = DocumentStatus.NEW.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 0,
                isValidationPassed = true,
                validationNote = "مفهرس محلياً",
                errorMessageAr = null,
            ),
        )
        db1.pageDao().insertPages(pages)

        val repo1 = MuharrirRepository(db1, storage)
        repo1.updateOcrEngine(OcrEngineType.TESSERACT)
        repo1.updateExportMode(ExportMode.ACADEMIC_FORMAT)
        repo1.registerApiKeySlot("مفتاح الكلية")
        db1.close()

        // Simulate app restart with brand new DB & Repository instance
        val db2 = openTestDatabase()
        val repo2 = MuharrirRepository(db2, storage)
        val persistedDocs = repo2.documents.first()
        assertEquals(1, persistedDocs.size)
        val loadedDoc = repo2.getDocumentById(docId)
        assertNotNull(loadedDoc)
        assertEquals("مذكرة_القانون_المدني.pdf", loadedDoc!!.fileName)
        assertEquals(350, loadedDoc.pageCount)
        assertEquals(350, loadedDoc.pages.size)
        assertEquals(ImportStatus.READY_FOR_PROCESSING, loadedDoc.importStatus)
        assertEquals(595, loadedDoc.pages.first().widthPoints)
        assertEquals(842, loadedDoc.pages.last().heightPoints)

        val loadedPrefs = repo2.preferences.first()
        assertEquals(OcrEngineType.TESSERACT, loadedPrefs.preferredOcrEngine)
        assertEquals(ExportMode.ACADEMIC_FORMAT, loadedPrefs.preferredExportMode)

        val loadedKeys = repo2.apiKeys.first()
        assertEquals(1, loadedKeys.size)
        assertEquals("مفتاح الكلية", loadedKeys.first().label)
        assertEquals(ApiKeyStatus.PENDING_BACKEND, loadedKeys.first().status)

        repo2.deleteDocument(docId)
        assertTrue(repo2.documents.first().isEmpty())
        assertNull(repo2.getDocumentById(docId))
        assertFalse(privateFile.exists())
        db2.close()
    }

    @Test
    fun `interrupted imports recover cleanly on startup`() = runTest {
        val db = openTestDatabase()
        val docId = "doc-interrupted-01"
        val stagingFile = storage.getStagingPdfFile(docId)
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeBytes("%PDF-1.4\npartial".toByteArray())

        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "محاضرة_غير_مكتملة.pdf",
                internalStoragePath = "",
                fileSizeBytes = 1024L,
                importDateMillis = 1_700_000_000_000L,
                lastModifiedMillis = 1_700_000_000_000L,
                pageCount = null,
                indexedPagesCount = 0,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.IMPORTING.name,
                importProgressPercent = 30,
                processingProgressPercent = 0,
                isValidationPassed = false,
                validationNote = null,
                errorMessageAr = null,
            ),
        )

        val repo = MuharrirRepository(db, storage)
        repo.recoverInterruptedImports()

        val recovered = repo.getDocumentById(docId)
        assertNotNull(recovered)
        assertEquals(ImportStatus.INTERRUPTED, recovered!!.importStatus)
        assertEquals(DocumentStatus.PAUSED, recovered.status)
        assertNotNull(recovered.errorMessageAr)
        assertFalse(stagingFile.exists())
        db.close()
    }

    @Test
    fun `private storage rejects invalid non pdf streams and promotes valid streams`() = runTest {
        val badStaging = storage.getStagingPdfFile("bad-doc")
        val badResult = storage.streamCopyToStaging(
            inputStream = ByteArrayInputStream("NOT A PDF FILE".toByteArray()),
            stagingFile = badStaging,
            expectedBytes = 14L,
        )
        assertTrue(badResult is StreamCopyResult.Failure)
        assertFalse(badStaging.exists())

        val emptyStaging = storage.getStagingPdfFile("empty-doc")
        val emptyResult = storage.streamCopyToStaging(
            inputStream = ByteArrayInputStream(ByteArray(0)),
            stagingFile = emptyStaging,
            expectedBytes = 0L,
        )
        assertTrue(emptyResult is StreamCopyResult.Failure)

        val hugeStaging = storage.getStagingPdfFile("huge-doc")
        val noSpaceResult = storage.streamCopyToStaging(
            inputStream = ByteArrayInputStream("%PDF-1.7\nbody".toByteArray()),
            stagingFile = hugeStaging,
            expectedBytes = Long.MAX_VALUE / 2,
        )
        assertTrue(noSpaceResult is StreamCopyResult.Failure)

        val goodStaging = storage.getStagingPdfFile("good-doc")
        val goodResult = storage.streamCopyToStaging(
            inputStream = ByteArrayInputStream("%PDF-1.7\nvalid-content".toByteArray()),
            stagingFile = goodStaging,
            expectedBytes = 22L,
        )
        assertTrue(
            "Expected StreamCopyResult.Success but got $goodResult",
            goodResult is StreamCopyResult.Success,
        )
        val promoted = storage.promoteStagingToPrivatePdf("good-doc", goodStaging)
        assertNotNull(promoted)
        assertTrue(promoted!!.exists())
        assertFalse(goodStaging.exists())
    }

    @Test
    fun `end to end pdf import indexes pages renders preview replaces safely and persists after source deleted`() = runTest {
        val db = openTestDatabase()
        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = HostPdfPageEngine(pageCountToReturn = 4),
        )

        val externalSource = File(context.cacheDir, "external_downloads_sample.pdf")
        externalSource.writeBytes("%PDF-1.4\n%fake-pdf-body-for-stream-copy".toByteArray())
        val sourceUri = Uri.fromFile(externalSource)

        val outcome = repo.importPdfFromUri(context, sourceUri)
        assertTrue("Expected ImportOutcome.Success but got $outcome", outcome is ImportOutcome.Success)
        val imported = (outcome as ImportOutcome.Success).document
        assertEquals(4, imported.pageCount)
        assertEquals(4, imported.indexedPagesCount)
        assertEquals(4, imported.pages.size)
        assertEquals(ImportStatus.READY_FOR_PROCESSING, imported.importStatus)

        // Delete original external source file; private copy must remain functional
        assertTrue(externalSource.delete())

        val renderedPage2 = repo.renderDocumentPage(context, imported, pageIndex = 1)
        assertNotNull(renderedPage2)
        val thumbPage1 = repo.getOrGeneratePageThumbnail(imported, pageNumber = 1)
        assertNotNull(thumbPage1)
        assertTrue(storage.getThumbnailFile(imported.id, 1).exists())

        // Failed replacement must NOT destroy the existing imported document
        val invalidReplacement = File(context.cacheDir, "corrupted_replacement.pdf")
        invalidReplacement.writeBytes("NOT_A_PDF".toByteArray())
        val repoWithBadEngine = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = HostPdfPageEngine(shouldFailWithCorrupted = true),
        )
        val replaceFail = repoWithBadEngine.importPdfFromUri(
            context = context,
            uri = Uri.fromFile(invalidReplacement),
            replaceDocumentId = imported.id,
        )
        assertTrue(replaceFail is ImportOutcome.Failure)
        val stillIntact = repo.getDocumentById(imported.id)
        assertNotNull(stillIntact)
        assertEquals(4, stillIntact!!.pageCount)

        // Successful replacement updates page count and replaces private copy
        val validReplacement = File(context.cacheDir, "valid_replacement.pdf")
        validReplacement.writeBytes("%PDF-1.7\n%updated-pdf-content".toByteArray())
        val repoForReplace = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = HostPdfPageEngine(pageCountToReturn = 7),
        )
        val replaceOk = repoForReplace.importPdfFromUri(
            context = context,
            uri = Uri.fromFile(validReplacement),
            replaceDocumentId = imported.id,
        )
        assertTrue(replaceOk is ImportOutcome.Success)
        val replaced = repo.getDocumentById(imported.id)
        assertNotNull(replaced)
        assertEquals(7, replaced!!.pageCount)
        assertEquals(7, replaced.pages.size)

        db.close()
    }
}
