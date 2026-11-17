package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiImagePreparer
import com.example.data.gemini.GeminiKeyTestOutcome
import com.example.data.gemini.GeminiPageAnalysisOutcome
import com.example.data.gemini.GeminiPageClient
import com.example.data.gemini.GeminiPagePromptPayload
import com.example.data.gemini.GeminiSchemaValidator
import com.example.data.gemini.GeminiValidationResult
import com.example.data.gemini.SensitiveKeyMasker
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
import com.example.domain.model.BoundingBox
import com.example.domain.model.DocumentStatus
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiModelConfig
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.ServerException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Module04GeminiTest {

    private lateinit var context: Context
    private lateinit var storage: PrivateDocumentStorage
    private val testDbName = "test_muharrir_m04.db"
    private val migrationDbName = "test_muharrir_migration_2_3.db"
    private val testVaultPrefsName = "test_encrypted_vault_backing"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        context.deleteSharedPreferences(testVaultPrefsName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
        storage = PrivateDocumentStorage(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
        context.deleteDatabase(migrationDbName)
        context.deleteSharedPreferences(testVaultPrefsName)
        File(context.filesDir, "muharrir_docs").deleteRecursively()
    }

    private fun openTestDatabase(): MuharrirDatabase =
        Room.databaseBuilder(context, MuharrirDatabase::class.java, testDbName)
            .allowMainThreadQueries()
            .build()

    private fun createVault(dayProvider: () -> String = { "2026-11-17" }): EncryptedGeminiApiKeyVault =
        EncryptedGeminiApiKeyVault(
            prefsProvider = { context.getSharedPreferences(testVaultPrefsName, Context.MODE_PRIVATE) },
            dayProvider = dayProvider,
        )

    private fun samplePageRecord(pageId: String = "doc1_page_1", pageNumber: Int = 1): PageRecord {
        val box = BoundingBox(100f, 120f, 340f, 175f)
        val seg = OcrSegment(
            segmentId = "${pageId}_b0",
            text = "القانون المدني العراقي",
            boundingBox = box,
            confidence = null,
            level = OcrElementLevel.LINE,
        )
        return PageRecord(
            pageId = pageId,
            documentId = "doc1",
            originalPageNumber = pageNumber,
            imageRef = "/tmp/doc1.pdf",
            status = PageStatus.OCR_DONE,
            widthPoints = 595,
            heightPoints = 842,
            ocrStatus = OcrPageStatus.DONE,
            ocrEngineUsed = OcrEngineType.TESSERACT,
            ocrResult = OcrResult(
                pageId = pageId,
                documentId = "doc1",
                pageNumber = pageNumber,
                recognizedText = "القانون المدبي العراقي",
                segments = listOf(seg),
                boundingBoxes = listOf(box),
                confidence = null,
                engineType = OcrEngineType.TESSERACT,
                imageWidthPx = 800,
                imageHeightPx = 1100,
            ),
        )
    }

    private fun sampleBitmap(width: Int = 800, height: Int = 1100): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(40f, 40f, (width - 40).toFloat(), (height - 40).toFloat(), paint)
        }

    private class FakeGeminiClient(
        private val onValidate: suspend (apiKey: String, modelId: String) -> Result<Unit> = { _, _ ->
            Result.success(Unit)
        },
        private val onAnalyze: suspend (apiKey: String, modelId: String, payload: GeminiPagePromptPayload) -> Result<String>,
    ) : GeminiPageClient {
        val analyzeCallKeys = mutableListOf<String>()

        override suspend fun validateKey(apiKey: String, modelId: String): Result<Unit> =
            onValidate(apiKey, modelId)

        override suspend fun analyzePage(
            apiKey: String,
            modelId: String,
            pageBitmap: Bitmap,
            payload: GeminiPagePromptPayload,
        ): Result<String> {
            analyzeCallKeys.add(apiKey)
            return onAnalyze(apiKey, modelId, payload)
        }
    }

    private fun validResponseJson(pageId: String = "doc1_page_1", pageNumber: Int = 1): String = """
        {
          "page_id": "$pageId",
          "page_number": $pageNumber,
          "items": [
            {
              "ocr_text": "المدبي",
              "suggested_text": "المدني",
              "bbox": [120.0, 140.0, 210.0, 175.0],
              "confidence": 0.93,
              "evidence": "تظهر النقطة العلوية لحرف النون واضحة قبل الياء في الصورة الأصلية وبحسب السياق القانوني",
              "reason_type": "visual",
              "requires_review": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `schema validator accepts strict valid json and rejects malformed payloads`() {
        val validResult = GeminiSchemaValidator.validate("```json\n${validResponseJson()}\n```")
        assertTrue(validResult is GeminiValidationResult.Valid)
        val parsed = (validResult as GeminiValidationResult.Valid).response
        assertEquals("doc1_page_1", parsed.page_id)
        assertEquals(1, parsed.page_number)
        assertEquals(1, parsed.items.size)
        assertEquals("المدني", parsed.items.first().suggested_text)

        // Missing evidence
        val missingEvidence = """
            {
              "page_id": "p1",
              "page_number": 1,
              "items": [{
                "ocr_text": "أ",
                "suggested_text": "ب",
                "bbox": [0, 0, 10, 10],
                "confidence": 0.8,
                "evidence": "",
                "reason_type": "visual",
                "requires_review": true
              }]
            }
        """.trimIndent()
        assertTrue(GeminiSchemaValidator.validate(missingEvidence) is GeminiValidationResult.Malformed)

        // Invalid reason_type
        val badReason = validResponseJson().replace("\"visual\"", "\"guessed\"")
        assertTrue(GeminiSchemaValidator.validate(badReason) is GeminiValidationResult.Malformed)

        // Out of range confidence
        val badConfidence = validResponseJson().replace("0.93", "1.45")
        assertTrue(GeminiSchemaValidator.validate(badConfidence) is GeminiValidationResult.Malformed)

        // Invalid bbox count
        val badBbox = validResponseJson().replace("[120.0, 140.0, 210.0, 175.0]", "[120.0, 140.0]")
        assertTrue(GeminiSchemaValidator.validate(badBbox) is GeminiValidationResult.Malformed)

        // Missing required key
        val missingRequiresReview = """
            {
              "page_id": "p1",
              "page_number": 1,
              "items": [{
                "ocr_text": "أ",
                "suggested_text": "أ",
                "bbox": [0, 0, 10, 10],
                "confidence": 0.8,
                "evidence": "واضح",
                "reason_type": "context"
              }]
            }
        """.trimIndent()
        assertTrue(GeminiSchemaValidator.validate(missingRequiresReview) is GeminiValidationResult.Malformed)
    }

    @Test
    fun `malformed gemini json retries once and recovers or fails cleanly`() = runTest {
        val vault = createVault()
        vault.addKey("Key1", "AIzaSyTestKeyOne1111111111")

        var callCount = 0
        val retrySucceedsClient = FakeGeminiClient { _, _, _ ->
            callCount++
            if (callCount == 1) {
                Result.success("{broken json")
            } else {
                Result.success(validResponseJson())
            }
        }
        val coordinator = GeminiAnalysisCoordinator(vault, retrySucceedsClient) { }
        val bitmap = sampleBitmap()
        val outcome = coordinator.analyzePageWithFailover(samplePageRecord(), bitmap)
        assertTrue(outcome is GeminiPageAnalysisOutcome.Success)
        assertEquals(2, (outcome as GeminiPageAnalysisOutcome.Success).attemptCount)

        // Two consecutive malformed outputs must fail cleanly without looping
        val alwaysMalformedClient = FakeGeminiClient { _, _, _ ->
            Result.success("{\"page_id\":\"doc1_page_1\"}")
        }
        val failingCoord = GeminiAnalysisCoordinator(vault, alwaysMalformedClient) { }
        val failOutcome = failingCoord.analyzePageWithFailover(samplePageRecord(), bitmap)
        assertTrue(failOutcome is GeminiPageAnalysisOutcome.Failure)
        assertEquals(2, (failOutcome as GeminiPageAnalysisOutcome.Failure).attemptsMade)
        bitmap.recycle()
    }

    @Test
    fun `failover disables invalid 401 403 and quota-exhausted keys and succeeds with next key`() = runTest {
        val vault = createVault()
        val k1 = vault.addKey("Bad401", "AIzaSyBadKey40111111111").getOrThrow()
        val k2 = vault.addKey("Denied403", "AIzaSyDenied4032222222").getOrThrow()
        val k3 = vault.addKey("Quota429", "AIzaSyQuota42933333333").getOrThrow()
        val k4 = vault.addKey("GoodKey", "AIzaSyValidKey444444444").getOrThrow()

        val client = FakeGeminiClient { key, _, _ ->
            when {
                key.contains("BadKey401") -> Result.failure(InvalidAPIKeyException("API key not valid (401)"))
                key.contains("Denied403") -> Result.failure(IllegalStateException("403 PERMISSION_DENIED"))
                key.contains("Quota429") -> Result.failure(QuotaExceededException("429 QUOTA_EXHAUSTED"))
                else -> Result.success(validResponseJson())
            }
        }

        val coordinator = GeminiAnalysisCoordinator(vault, client) { }
        val bitmap = sampleBitmap()
        val outcome = coordinator.analyzePageWithFailover(samplePageRecord(), bitmap)
        bitmap.recycle()

        assertTrue(outcome is GeminiPageAnalysisOutcome.Success)
        val success = outcome as GeminiPageAnalysisOutcome.Success
        assertEquals("GoodKey", success.usedKeyLabel)
        assertEquals(4, success.attemptCount)

        val keysAfter = vault.listKeys().associateBy { it.id }
        assertEquals(GeminiKeyStatus.INVALID, keysAfter[k1.id]?.status)
        assertFalse(keysAfter[k1.id]!!.isEnabled)

        assertEquals(GeminiKeyStatus.PERMISSION_DENIED, keysAfter[k2.id]?.status)
        assertFalse(keysAfter[k2.id]!!.isEnabled)

        assertEquals(GeminiKeyStatus.QUOTA_EXHAUSTED, keysAfter[k3.id]?.status)
        assertFalse(keysAfter[k3.id]!!.isEnabled)

        assertEquals(GeminiKeyStatus.VALID, keysAfter[k4.id]?.status)
        assertTrue(keysAfter[k4.id]!!.isEnabled)
    }

    @Test
    fun `rate limit rotates across keys and caps exponential backoff at max 2 retries per key per page`() = runTest {
        val vault = createVault()
        vault.addKey("K1", "AIzaSyRateKeyOne11111111")
        vault.addKey("K2", "AIzaSyRateKeyTwo22222222")

        val recordedBackoffs = mutableListOf<Long>()
        val client = FakeGeminiClient { _, _, _ ->
            Result.failure(IllegalStateException("429 RATE_LIMITED: too many requests per minute"))
        }

        val coordinator = GeminiAnalysisCoordinator(
            keyVault = vault,
            client = client,
            backoffDelayMillis = { ms -> recordedBackoffs.add(ms) },
        )
        val bitmap = sampleBitmap()
        val outcome = coordinator.analyzePageWithFailover(samplePageRecord(), bitmap)
        bitmap.recycle()

        assertTrue(outcome is GeminiPageAnalysisOutcome.Failure)
        // 2 keys * (1 initial + 2 retries) = 6 total bounded calls, never infinite loop
        assertEquals(6, client.analyzeCallKeys.size)
        // Round-robin rotation before each backoff round: K1, K2, [1000ms], K1, K2, [2000ms], K1, K2
        assertEquals(listOf(1000L, 2000L), recordedBackoffs)
        assertTrue(vault.listKeys().all { it.status == GeminiKeyStatus.RATE_LIMITED })
    }

    @Test
    fun `5xx retries same key once before failing over and network error stops cleanly without disabling keys`() = runTest {
        val vault = createVault()
        val k1 = vault.addKey("ServerErrKey", "AIzaSySrvErrKey11111111").getOrThrow()
        val k2 = vault.addKey("BackupGood", "AIzaSyBackupKey22222222").getOrThrow()

        val client = FakeGeminiClient { key, _, _ ->
            if (key.contains("SrvErrKey")) {
                Result.failure(ServerException("503 Service Unavailable"))
            } else {
                Result.success(validResponseJson())
            }
        }

        val coordinator = GeminiAnalysisCoordinator(vault, client) { }
        val bitmap = sampleBitmap()
        val outcome = coordinator.analyzePageWithFailover(samplePageRecord(), bitmap)
        assertTrue(outcome is GeminiPageAnalysisOutcome.Success)
        // K1 attempted twice (1 + 1 retry), then K2 once
        assertEquals(3, client.analyzeCallKeys.size)
        assertEquals("BackupGood", (outcome as GeminiPageAnalysisOutcome.Success).usedKeyLabel)

        // Network failure test: keys must remain enabled
        val netClient = FakeGeminiClient { _, _, _ ->
            Result.failure(UnknownHostException("generativelanguage.googleapis.com"))
        }
        val netCoord = GeminiAnalysisCoordinator(vault, netClient) { }
        val netOutcome = netCoord.analyzePageWithFailover(samplePageRecord(), bitmap)
        bitmap.recycle()
        assertTrue(netOutcome is GeminiPageAnalysisOutcome.Failure)
        assertTrue(vault.listKeys().first { it.id == k2.id }.isEnabled)
        assertTrue(vault.listKeys().first { it.id == k1.id }.isEnabled)
    }

    @Test
    fun `sensitive key masking never exposes full key and usage counter tracks today only`() = runTest {
        val secret = "AIzaSySecretToken9876543210"
        assertEquals("AIzaSy...", SensitiveKeyMasker.maskKey(secret))
        val scrubbed = SensitiveKeyMasker.scrubSecrets("HTTP failed with key $secret on host", listOf(secret))
        assertFalse(scrubbed.contains(secret))
        assertTrue(scrubbed.contains("AIzaSy..."))

        var fakeDay = "2026-11-17"
        val vault = createVault { fakeDay }
        val key = vault.addKey("AcademicKey", secret).getOrThrow()
        assertEquals("AIzaSy...", key.maskedPrefix)

        // Validate key test updates usage count & status
        val client = FakeGeminiClient(
            onValidate = { _, _ -> Result.success(Unit) },
            onAnalyze = { _, _, _ -> Result.success(validResponseJson()) },
        )
        val coord = GeminiAnalysisCoordinator(vault, client) { }
        val testOutcome = coord.testApiKey(key.id)
        assertTrue(testOutcome is GeminiKeyTestOutcome.Valid)
        assertEquals(1, vault.getUsageStats().requestsTodayCount)
        assertEquals("AcademicKey", vault.getUsageStats().activeKeyLabel)
        assertEquals(GeminiModelConfig.DEFAULT_MODEL_ID, vault.getUsageStats().selectedModelId)

        // Plain non-vault SharedPreferences must NOT contain any API keys
        val defaultPlainPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        assertTrue(defaultPlainPrefs.all.isEmpty())

        // Advancing calendar day resets daily local counter
        fakeDay = "2026-11-18"
        assertEquals(0, vault.getUsageStats().requestsTodayCount)
    }

    @Test
    fun `image preparer scales bitmap to max 1024px long side and keeps jpeg under 2mb`() {
        val large = sampleBitmap(width = 2400, height = 3200)
        val prepared = GeminiImagePreparer.prepareForGemini(large)

        assertTrue(maxOf(prepared.widthPx, prepared.heightPx) <= GeminiModelConfig.MAX_LONG_SIDE_PX)
        assertEquals(1024, prepared.heightPx)
        assertEquals(768, prepared.widthPx)
        assertTrue(prepared.byteSize < GeminiModelConfig.MAX_IMAGE_BYTES)
        assertFalse(large.isRecycled)

        prepared.recycleSafely()
        large.recycle()
    }

    @Test
    fun `room migration 2 to 3 preserves ocr data and repository persists validated gemini analysis`() = runTest {
        // 1. Verify MIGRATION_2_3 upgrades v2 SQLite database cleanly
        val migFile = context.getDatabasePath(migrationDbName)
        migFile.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(migFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS documents (
                id TEXT NOT NULL PRIMARY KEY,
                originalFileName TEXT NOT NULL,
                internalStoragePath TEXT NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                importDateMillis INTEGER NOT NULL,
                lastModifiedMillis INTEGER NOT NULL,
                pageCount INTEGER,
                indexedPagesCount INTEGER NOT NULL,
                processingStatus TEXT NOT NULL,
                importStatus TEXT NOT NULL,
                importProgressPercent INTEGER NOT NULL,
                processingProgressPercent INTEGER NOT NULL,
                isValidationPassed INTEGER NOT NULL,
                validationNote TEXT,
                errorMessageAr TEXT
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pages (
                pageId TEXT NOT NULL PRIMARY KEY,
                documentId TEXT NOT NULL,
                originalPageNumber INTEGER NOT NULL,
                widthPoints INTEGER,
                heightPoints INTEGER,
                renderingReference TEXT NOT NULL,
                thumbnailCachePath TEXT,
                pageStatus TEXT NOT NULL,
                ocrStatus TEXT NOT NULL DEFAULT 'PENDING',
                ocrEngineUsed TEXT,
                ocrErrorMessageAr TEXT,
                ocrUpdatedAtMillis INTEGER,
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_pages_documentId_originalPageNumber ON pages(documentId, originalPageNumber)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ocr_results (
                pageId TEXT NOT NULL PRIMARY KEY,
                documentId TEXT NOT NULL,
                pageNumber INTEGER NOT NULL,
                engineType TEXT NOT NULL,
                recognizedText TEXT NOT NULL,
                segmentsJson TEXT NOT NULL,
                confidence REAL,
                imageWidthPx INTEGER NOT NULL,
                imageHeightPx INTEGER NOT NULL,
                recognizedAtMillis INTEGER NOT NULL,
                FOREIGN KEY(pageId) REFERENCES pages(pageId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS index_ocr_results_documentId_pageNumber ON ocr_results(documentId, pageNumber)")
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS preferences (
                singletonId INTEGER NOT NULL PRIMARY KEY,
                preferredOcrEngine TEXT NOT NULL,
                preferredExportMode TEXT NOT NULL,
                highlightUnresolvedInDocx INTEGER NOT NULL,
                autoSaveCheckpoints INTEGER NOT NULL,
                resumeAfterInterruption INTEGER NOT NULL,
                batchSizePages INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS api_key_slots (
                id TEXT NOT NULL PRIMARY KEY,
                label TEXT NOT NULL,
                isEnabled INTEGER NOT NULL,
                status TEXT NOT NULL,
                createdAtMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            "INSERT INTO documents VALUES ('v2-doc', 'فقه.pdf', '/tmp/v2.pdf', 1000, 100, 100, 1, 1, 'PROCESSING', 'READY_FOR_PROCESSING', 100, 100, 1, 'ok', NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO pages VALUES ('v2-doc_page_1', 'v2-doc', 1, 595, 842, '/tmp/v2.pdf', NULL, 'OCR_DONE', 'DONE', 'TESSERACT', NULL, 200)",
        )
        sqlite.execSQL(
            "INSERT INTO ocr_results VALUES ('v2-doc_page_1', 'v2-doc', 1, 'TESSERACT', 'نص أصلي', '[]', 0.85, 800, 1100, 200)",
        )
        sqlite.version = 2
        sqlite.close()

        val migratedDb = Room.databaseBuilder(context, MuharrirDatabase::class.java, migrationDbName)
            .addMigrations(
                MuharrirDatabase.MIGRATION_1_2,
                MuharrirDatabase.MIGRATION_2_3,
                MuharrirDatabase.MIGRATION_3_4,
                MuharrirDatabase.MIGRATION_4_5,
            )
            .allowMainThreadQueries()
            .build()
        val migRepo = MuharrirRepository(migratedDb, storage)
        val migDoc = migRepo.getDocumentById("v2-doc")
        assertNotNull(migDoc)
        assertEquals("نص أصلي", migDoc!!.pages.first().ocrResult?.recognizedText)
        migratedDb.close()

        // 2. Full repository Gemini execution & Room persistence test
        val db = openTestDatabase()
        val docId = "doc-gemini-e2e"
        val pdfFile = storage.getPrivatePdfFile(docId).apply {
            parentFile?.mkdirs()
            writeBytes("%PDF-1.4\n%content".toByteArray())
        }
        db.documentDao().upsertDocument(
            DocumentEntity(
                id = docId,
                originalFileName = "محاضرة_تاريخ.pdf",
                internalStoragePath = pdfFile.absolutePath,
                fileSizeBytes = pdfFile.length(),
                importDateMillis = 1000L,
                lastModifiedMillis = 1000L,
                pageCount = 1,
                indexedPagesCount = 1,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                importProgressPercent = 100,
                processingProgressPercent = 50,
                isValidationPassed = true,
                validationNote = "ok",
                errorMessageAr = null,
            ),
        )
        db.pageDao().insertPages(
            listOf(
                PageEntity(
                    pageId = "${docId}_page_1",
                    documentId = docId,
                    originalPageNumber = 1,
                    widthPoints = 595,
                    heightPoints = 842,
                    renderingReference = pdfFile.absolutePath,
                    thumbnailCachePath = null,
                    pageStatus = PageStatus.OCR_DONE.name,
                    ocrStatus = OcrPageStatus.DONE.name,
                    ocrEngineUsed = OcrEngineType.TESSERACT.name,
                ),
            ),
        )
        db.ocrResultDao().upsertOcrResult(
            OcrResultEntity(
                pageId = "${docId}_page_1",
                documentId = docId,
                pageNumber = 1,
                engineType = OcrEngineType.TESSERACT.name,
                recognizedText = "الدولة العباسيه",
                segmentsJson = "[]",
                confidence = null,
                imageWidthPx = 800,
                imageHeightPx = 1100,
                recognizedAtMillis = 1000L,
            ),
        )

        val vault = createVault()
        vault.addKey("MainKey", "AIzaSyMainRepoKey55555555")
        val fakeClient = FakeGeminiClient { _, _, payload ->
            Result.success(validResponseJson(pageId = payload.pageId, pageNumber = payload.pageNumber))
        }
        val fakeEngine = object : PdfPageEngine {
            override suspend fun indexPages(
                pdfFile: File,
                batchSize: Int,
                onBatchIndexed: suspend (Int, Int, List<IndexedPdfPageMeta>) -> Unit,
            ) = PrivatePdfIndexResult.Success(1, listOf(IndexedPdfPageMeta(1, 595, 842)))

            override suspend fun renderFromFile(pdfFile: File, pageIndex: Int, maxWidthPx: Int): Bitmap =
                sampleBitmap(1600, 2200)
        }

        val repo = MuharrirRepository(
            database = db,
            storage = storage,
            pageEngine = fakeEngine,
            geminiKeyVault = vault,
            geminiCoordinator = GeminiAnalysisCoordinator(vault, fakeClient) { },
        )

        val runOutcome = repo.runGeminiAnalysis(docId)
        assertTrue(runOutcome is GeminiRunOutcome.Completed)
        val completed = runOutcome as GeminiRunOutcome.Completed
        assertEquals(1, completed.analyzedNow)
        assertEquals(0, completed.failedPages)

        val reloaded = repo.getDocumentById(docId)!!
        val page1 = reloaded.pages.first()
        assertEquals(PageStatus.ANALYZED, page1.status)
        assertNotNull(page1.geminiAnalysis)
        assertEquals("المدني", page1.geminiAnalysis!!.items.first().suggested_text)
        assertFalse(page1.geminiAnalysis!!.usedKeyLabel.contains("AIzaSyMainRepoKey55555555"))
        assertTrue(page1.geminiAnalysis!!.usedKeyLabel.contains("AIzaSy..."))
        assertTrue(repo.geminiUsageStats.first().requestsTodayCount >= 1)

        db.close()
    }
}
