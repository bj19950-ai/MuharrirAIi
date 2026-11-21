package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ApiKeyMetadata
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.BoundingBox
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentRecord
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiPageAnalysisRecord
import com.example.domain.model.GeminiPageAnalysisResponse
import com.example.domain.model.GeminiSuggestionItem
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.example.domain.model.ProcessingPreferences
import com.example.domain.model.ReviewItem
import com.example.domain.model.ReviewProgress
import com.example.domain.model.ReviewStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val originalFileName: String,
    val internalStoragePath: String,
    val fileSizeBytes: Long,
    val importDateMillis: Long,
    val lastModifiedMillis: Long,
    val pageCount: Int?,
    val indexedPagesCount: Int,
    val processingStatus: String,
    val importStatus: String,
    val importProgressPercent: Int,
    val processingProgressPercent: Int,
    val isValidationPassed: Boolean,
    val validationNote: String?,
    val errorMessageAr: String?,
    val reviewApprovedAt: Long? = null,
    val exportedDocxPath: String? = null,
    val exportedMode: String? = null,
    val exportedAtMillis: Long? = null,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "originalPageNumber"])],
)
data class PageEntity(
    @PrimaryKey val pageId: String,
    val documentId: String,
    val originalPageNumber: Int,
    val widthPoints: Int?,
    val heightPoints: Int?,
    val renderingReference: String,
    val thumbnailCachePath: String?,
    val pageStatus: String,
    val ocrStatus: String = OcrPageStatus.PENDING.name,
    val ocrEngineUsed: String? = null,
    val ocrErrorMessageAr: String? = null,
    val ocrUpdatedAtMillis: Long? = null,
    val hasMissingContent: Boolean = false,
    val failureReason: String? = null,
)

@Entity(
    tableName = "ocr_results",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "pageNumber"])],
)
data class OcrResultEntity(
    @PrimaryKey val pageId: String,
    val documentId: String,
    val pageNumber: Int,
    val engineType: String,
    val recognizedText: String,
    val segmentsJson: String,
    val confidence: Float?,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val recognizedAtMillis: Long,
)

@Entity(tableName = "preferences")
data class PreferencesEntity(
    @PrimaryKey val singletonId: Int = 1,
    val preferredOcrEngine: String,
    val preferredExportMode: String,
    val highlightUnresolvedInDocx: Boolean,
    val autoSaveCheckpoints: Boolean,
    val resumeAfterInterruption: Boolean,
    val batchSizePages: Int,
)

@Entity(tableName = "api_key_slots")
data class ApiKeySlotEntity(
    @PrimaryKey val id: String,
    val label: String,
    val isEnabled: Boolean,
    val status: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "gemini_page_analyses",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "pageNumber"])],
)
data class GeminiAnalysisEntity(
    @PrimaryKey val pageId: String,
    val documentId: String,
    val pageNumber: Int,
    val modelId: String,
    val usedKeyLabel: String,
    val rawValidatedJson: String,
    val itemsCount: Int,
    val analyzedAtMillis: Long,
)

@Entity(
    tableName = "review_items",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["page_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OcrResultEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["ocr_result_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = GeminiAnalysisEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["gemini_analysis_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["document_id"], name = "index_review_items_document_id"),
        Index(value = ["page_id"], name = "index_review_items_page_id"),
        Index(value = ["review_status"], name = "index_review_items_review_status"),
        Index(value = ["priority"], name = "index_review_items_priority"),
    ],
)
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "ocr_result_id") val ocrResultId: String?,
    @ColumnInfo(name = "gemini_analysis_id") val geminiAnalysisId: String?,
    @ColumnInfo(name = "page_number") val pageNumber: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
    @ColumnInfo(name = "suggested_text") val suggestedText: String?,
    @ColumnInfo(name = "ocr_confidence") val ocrConfidence: Float?,
    @ColumnInfo(name = "gemini_confidence") val geminiConfidence: Float?,
    val category: String,
    val priority: Int,
    @ColumnInfo(name = "review_status") val reviewStatus: String,
    @ColumnInfo(name = "user_text") val userText: String?,
    val evidence: String,
    @ColumnInfo(name = "reason_type") val reasonType: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long?,
    @ColumnInfo(name = "block_index") val blockIndex: Int = 0,
    @ColumnInfo(name = "line_index") val lineIndex: Int = 0,
    @ColumnInfo(name = "element_index") val elementIndex: Int = 0,
    @ColumnInfo(name = "stale_note_ar") val staleNoteAr: String? = null,
)

object OcrSegmentsJsonCodec {
    private val moshi = Moshi.Builder().build()
    private val segmentListType = Types.newParameterizedType(List::class.java, OcrSegment::class.java)
    private val adapter = moshi.adapter<List<OcrSegment>>(segmentListType)

    fun toJson(segments: List<OcrSegment>): String = adapter.toJson(segments)

    fun fromJson(json: String): List<OcrSegment> {
        if (json.isBlank()) return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    }
}

object GeminiAnalysisJsonCodec {
    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(GeminiPageAnalysisResponse::class.java)

    fun responseFromValidatedJson(json: String): GeminiPageAnalysisResponse? {
        if (json.isBlank()) return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    fun itemsFromValidatedJson(json: String): List<GeminiSuggestionItem> {
        return responseFromValidatedJson(json)?.items.orEmpty()
    }
}

fun DocumentEntity.toDomain(
    pages: List<PageRecord> = emptyList(),
    reviewItems: List<ReviewItem> = emptyList(),
): DocumentRecord {
    val safeProcessingStatus = runCatching { DocumentStatus.valueOf(processingStatus) }
        .getOrDefault(DocumentStatus.NEW)
    val safeImportStatus = runCatching { ImportStatus.valueOf(importStatus) }
        .getOrDefault(ImportStatus.READY_FOR_PROCESSING)

    val actionableItems = reviewItems.filter { it.category != ComparisonCategory.AGREEMENT }
    val progress = ReviewProgress(
        totalItems = actionableItems.size,
        resolvedItems = actionableItems.count {
            it.status == ReviewStatus.APPROVED_GEMINI ||
                it.status == ReviewStatus.MANUALLY_EDITED ||
                it.status == ReviewStatus.IGNORED
        },
        unresolvedItems = actionableItems.count { it.status == ReviewStatus.UNRESOLVED },
    )

    val safeExported = if (exportedDocxPath != null && exportedAtMillis != null) {
        val safeExportMode = runCatching {
            ExportMode.valueOf(exportedMode ?: ExportMode.MATCH_ORIGINAL.name)
        }.getOrDefault(ExportMode.MATCH_ORIGINAL)
        com.example.domain.model.ExportedDocument(
            documentId = id,
            docxFileRef = exportedDocxPath,
            exportMode = safeExportMode,
            createdAtMillis = exportedAtMillis,
        )
    } else {
        null
    }

    return DocumentRecord(
        id = id,
        fileName = originalFileName,
        fileSizeBytes = fileSizeBytes,
        uploadDateMillis = importDateMillis,
        pageCount = pageCount,
        status = safeProcessingStatus,
        originalFileRef = internalStoragePath,
        isValidationPassed = isValidationPassed,
        validationNote = validationNote,
        internalStorageRef = internalStoragePath,
        lastModifiedMillis = lastModifiedMillis,
        importStatus = safeImportStatus,
        indexedPagesCount = indexedPagesCount,
        importProgressPercent = importProgressPercent,
        processingProgressPercent = processingProgressPercent,
        errorMessageAr = errorMessageAr,
        pages = pages,
        reviewItems = reviewItems,
        reviewProgress = progress,
        reviewApprovedAt = reviewApprovedAt,
        exportedDocument = safeExported,
    )
}

fun PageEntity.toDomain(
    ocrResultEntity: OcrResultEntity? = null,
    geminiAnalysisEntity: GeminiAnalysisEntity? = null,
): PageRecord {
    val safeStatus = runCatching { PageStatus.valueOf(pageStatus) }
        .getOrDefault(PageStatus.PENDING)
    val safeOcrStatus = runCatching { OcrPageStatus.valueOf(ocrStatus) }
        .getOrDefault(OcrPageStatus.PENDING)
    val safeEngine = ocrEngineUsed?.let { raw ->
        runCatching { OcrEngineType.valueOf(raw) }.getOrNull()
    }
    return PageRecord(
        pageId = pageId,
        documentId = documentId,
        originalPageNumber = originalPageNumber,
        imageRef = renderingReference,
        status = safeStatus,
        widthPoints = widthPoints,
        heightPoints = heightPoints,
        thumbnailCachePath = thumbnailCachePath,
        ocrStatus = safeOcrStatus,
        ocrEngineUsed = safeEngine,
        ocrErrorMessageAr = ocrErrorMessageAr,
        ocrResult = ocrResultEntity?.toDomain(),
        geminiAnalysis = geminiAnalysisEntity?.toDomain(),
        hasMissingContent = hasMissingContent,
        failureReason = failureReason,
    )
}

fun GeminiAnalysisEntity.toDomain(): GeminiPageAnalysisRecord {
    val decoded = GeminiAnalysisJsonCodec.responseFromValidatedJson(rawValidatedJson)
    return GeminiPageAnalysisRecord(
        pageId = pageId,
        documentId = documentId,
        pageNumber = pageNumber,
        modelId = modelId,
        usedKeyLabel = usedKeyLabel,
        items = decoded?.items.orEmpty(),
        rawValidatedJson = rawValidatedJson,
        analyzedAtMillis = analyzedAtMillis,
        isMissingLargeContent = decoded?.isMissingLargeContent == true,
    )
}

fun GeminiPageAnalysisRecord.toEntity(): GeminiAnalysisEntity = GeminiAnalysisEntity(
    pageId = pageId,
    documentId = documentId,
    pageNumber = pageNumber,
    modelId = modelId,
    usedKeyLabel = usedKeyLabel,
    rawValidatedJson = rawValidatedJson,
    itemsCount = items.size,
    analyzedAtMillis = analyzedAtMillis,
)

fun OcrResultEntity.toDomain(): OcrResult {
    val safeEngine = runCatching { OcrEngineType.valueOf(engineType) }
        .getOrDefault(OcrEngineType.TESSERACT)
    val segments = OcrSegmentsJsonCodec.fromJson(segmentsJson)
    val elementBoxes = segments
        .filter { it.level == OcrElementLevel.ELEMENT }
        .mapNotNull { it.boundingBox }
        .ifEmpty { segments.mapNotNull { it.boundingBox } }

    return OcrResult(
        pageId = pageId,
        documentId = documentId,
        pageNumber = pageNumber,
        recognizedText = recognizedText,
        segments = segments,
        boundingBoxes = elementBoxes,
        confidence = confidence,
        engineType = safeEngine,
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx,
        recognizedAtMillis = recognizedAtMillis,
    )
}

fun OcrResult.toEntity(): OcrResultEntity = OcrResultEntity(
    pageId = pageId,
    documentId = documentId,
    pageNumber = pageNumber,
    engineType = engineType.name,
    recognizedText = recognizedText,
    segmentsJson = OcrSegmentsJsonCodec.toJson(segments),
    confidence = confidence,
    imageWidthPx = imageWidthPx,
    imageHeightPx = imageHeightPx,
    recognizedAtMillis = recognizedAtMillis,
)

fun PreferencesEntity.toDomain(): ProcessingPreferences {
    val safeEngine = runCatching { OcrEngineType.valueOf(preferredOcrEngine) }
        .getOrDefault(OcrEngineType.TESSERACT)
    val safeMode = runCatching { ExportMode.valueOf(preferredExportMode) }
        .getOrDefault(ExportMode.MATCH_ORIGINAL)
    return ProcessingPreferences(
        preferredOcrEngine = safeEngine,
        preferredExportMode = safeMode,
        highlightUnresolvedInDocx = highlightUnresolvedInDocx,
        autoSaveCheckpoints = autoSaveCheckpoints,
        resumeAfterInterruption = resumeAfterInterruption,
        batchSizePages = batchSizePages,
    )
}

fun ProcessingPreferences.toEntity(): PreferencesEntity = PreferencesEntity(
    singletonId = 1,
    preferredOcrEngine = preferredOcrEngine.name,
    preferredExportMode = preferredExportMode.name,
    highlightUnresolvedInDocx = highlightUnresolvedInDocx,
    autoSaveCheckpoints = autoSaveCheckpoints,
    resumeAfterInterruption = resumeAfterInterruption,
    batchSizePages = batchSizePages,
)

fun ApiKeySlotEntity.toDomain(): ApiKeyMetadata {
    val safeStatus = runCatching { ApiKeyStatus.valueOf(status) }
        .getOrDefault(ApiKeyStatus.PENDING_BACKEND)
    return ApiKeyMetadata(
        id = id,
        label = label,
        isEnabled = isEnabled,
        status = safeStatus,
        createdAtMillis = createdAtMillis,
    )
}

fun ReviewItemEntity.toDomain(): ReviewItem {
    val safeStatus = runCatching { ReviewStatus.valueOf(reviewStatus) }
        .getOrDefault(ReviewStatus.PENDING)
    val safeCategory = ComparisonCategory.fromPersisted(category)
    return ReviewItem(
        reviewItemId = id.toString(),
        documentId = documentId,
        pageId = pageId,
        pageNumber = pageNumber,
        originalOcrText = ocrText,
        geminiSuggestion = suggestedText,
        explanation = evidence,
        approvedText = userText,
        status = safeStatus,
        category = safeCategory,
        priority = priority,
        ocrConfidence = ocrConfidence,
        geminiConfidence = geminiConfidence,
        reasonType = reasonType,
        staleNoteAr = staleNoteAr,
        cropBox = BoundingBox(
            left = x1.toFloat(),
            top = y1.toFloat(),
            right = x2.toFloat(),
            bottom = y2.toFloat(),
        ),
        resolvedAt = resolvedAt,
        blockIndex = blockIndex,
        lineIndex = lineIndex,
        elementIndex = elementIndex,
    )
}
