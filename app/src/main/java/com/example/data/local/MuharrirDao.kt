package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.domain.model.ReviewStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE importStatus != :deletedStatus ORDER BY importDateMillis DESC")
    fun observeActiveDocuments(deletedStatus: String = "DELETED"): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    fun observeDocumentById(documentId: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    suspend fun getDocumentById(documentId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE importStatus = :importingStatus")
    suspend fun getDocumentsByImportStatus(importingStatus: String = "IMPORTING"): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: DocumentEntity)

    @Query(
        """
        UPDATE documents
        SET importStatus = :importStatus,
            processingStatus = :processingStatus,
            indexedPagesCount = :indexedPagesCount,
            importProgressPercent = :importProgressPercent,
            lastModifiedMillis = :lastModifiedMillis,
            errorMessageAr = :errorMessageAr
        WHERE id = :documentId
        """,
    )
    suspend fun updateImportProgress(
        documentId: String,
        importStatus: String,
        processingStatus: String,
        indexedPagesCount: Int,
        importProgressPercent: Int,
        lastModifiedMillis: Long,
        errorMessageAr: String?,
    )

    @Query(
        """
        UPDATE documents
        SET processingStatus = :processingStatus,
            processingProgressPercent = :processingProgressPercent,
            lastModifiedMillis = :lastModifiedMillis,
            errorMessageAr = :errorMessageAr
        WHERE id = :documentId
        """,
    )
    suspend fun updateProcessingProgress(
        documentId: String,
        processingStatus: String,
        processingProgressPercent: Int,
        lastModifiedMillis: Long,
        errorMessageAr: String?,
    )

    @Query(
        """
        UPDATE documents
        SET reviewApprovedAt = :approvedAtMillis,
            processingStatus = :processingStatus,
            lastModifiedMillis = :lastModifiedMillis
        WHERE id = :documentId
        """,
    )
    suspend fun updateReviewApprovedAt(
        documentId: String,
        approvedAtMillis: Long,
        processingStatus: String,
        lastModifiedMillis: Long,
    )

    @Query(
        """
        UPDATE documents
        SET exportedDocxPath = :docxPath,
            exportedMode = :exportMode,
            exportedAtMillis = :exportedAtMillis,
            processingStatus = :processingStatus,
            lastModifiedMillis = :lastModifiedMillis
        WHERE id = :documentId
        """,
    )
    suspend fun updateExportedDocument(
        documentId: String,
        docxPath: String,
        exportMode: String,
        exportedAtMillis: Long,
        processingStatus: String,
        lastModifiedMillis: Long,
    )

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocumentById(documentId: String)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY originalPageNumber ASC")
    fun observePagesForDocument(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY originalPageNumber ASC")
    suspend fun getPagesForDocument(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND ocrStatus = :ocrStatus ORDER BY originalPageNumber ASC")
    suspend fun getPagesByOcrStatus(documentId: String, ocrStatus: String): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("UPDATE pages SET thumbnailCachePath = :thumbnailPath WHERE pageId = :pageId")
    suspend fun updateThumbnailPath(pageId: String, thumbnailPath: String)

    @Query(
        """
        UPDATE pages
        SET pageStatus = :pageStatus,
            ocrStatus = :ocrStatus,
            ocrEngineUsed = :ocrEngineUsed,
            ocrErrorMessageAr = :ocrErrorMessageAr,
            ocrUpdatedAtMillis = :ocrUpdatedAtMillis
        WHERE pageId = :pageId
        """,
    )
    suspend fun updatePageOcrState(
        pageId: String,
        pageStatus: String,
        ocrStatus: String,
        ocrEngineUsed: String?,
        ocrErrorMessageAr: String?,
        ocrUpdatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE pages
        SET ocrStatus = :pendingStatus
        WHERE ocrStatus = :runningStatus
        """,
    )
    suspend fun resetRunningOcrToPending(
        runningStatus: String = "RUNNING",
        pendingStatus: String = "PENDING",
    ): Int

    @Query(
        """
        UPDATE pages
        SET ocrStatus = :pendingStatus
        WHERE documentId = :documentId AND ocrStatus = :runningStatus
        """,
    )
    suspend fun resetDocumentRunningOcrToPending(
        documentId: String,
        runningStatus: String = "RUNNING",
        pendingStatus: String = "PENDING",
    ): Int

    @Query("UPDATE pages SET pageStatus = :pageStatus WHERE pageId = :pageId")
    suspend fun updatePageStatus(pageId: String, pageStatus: String)

    @Query("UPDATE pages SET hasMissingContent = :hasMissingContent WHERE pageId = :pageId")
    suspend fun updatePageMissingContent(pageId: String, hasMissingContent: Boolean)

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND hasMissingContent = 1 ORDER BY originalPageNumber ASC")
    suspend fun getPagesWithMissingContent(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND hasMissingContent = 1 ORDER BY originalPageNumber ASC")
    fun observePagesWithMissingContent(documentId: String): Flow<List<PageEntity>>

    @Query("UPDATE pages SET pageStatus = :pageStatus, failureReason = :failureReason WHERE pageId = :pageId")
    suspend fun updatePageFailureState(pageId: String, pageStatus: String, failureReason: String?)

    @Query(
        """
        SELECT * FROM pages
        WHERE documentId = :documentId
          AND pageStatus = 'FAILED'
          AND (failureReason IS NULL OR failureReason != 'user_skipped')
        ORDER BY originalPageNumber ASC
        """,
    )
    suspend fun getPagesWithFailedAnalysis(documentId: String): List<PageEntity>

    @Query(
        """
        SELECT * FROM pages
        WHERE documentId = :documentId
          AND pageStatus = 'FAILED'
          AND (failureReason IS NULL OR failureReason != 'user_skipped')
        ORDER BY originalPageNumber ASC
        """,
    )
    fun observePagesWithFailedAnalysis(documentId: String): Flow<List<PageEntity>>

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePagesForDocument(documentId: String)

    @Query(
        """
        SELECT pages.* FROM pages
        LEFT JOIN ocr_results ON pages.pageId = ocr_results.pageId
        WHERE pages.documentId = :documentId
          AND (
              pages.ocrStatus = 'FAILED'
              OR (
                  ocr_results.recognizedText IS NOT NULL
                  AND length(trim(ocr_results.recognizedText, char(9) || char(10) || char(13) || ' ')) < 150
              )
              OR (pages.ocrStatus = 'DONE' AND ocr_results.recognizedText IS NULL)
          )
        ORDER BY pages.originalPageNumber ASC
        """,
    )
    suspend fun getPagesNeedingReprocessing(documentId: String): List<PageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM pages
        LEFT JOIN ocr_results ON pages.pageId = ocr_results.pageId
        WHERE pages.documentId = :documentId
          AND (
              pages.ocrStatus = 'FAILED'
              OR (
                  ocr_results.recognizedText IS NOT NULL
                  AND length(trim(ocr_results.recognizedText, char(9) || char(10) || char(13) || ' ')) < 150
              )
              OR (pages.ocrStatus = 'DONE' AND ocr_results.recognizedText IS NULL)
          )
        """,
    )
    fun observeCountPagesNeedingReprocessing(documentId: String): Flow<Int>

    @Transaction
    suspend fun replaceDocumentPages(documentId: String, pages: List<PageEntity>) {
        deletePagesForDocument(documentId)
        if (pages.isNotEmpty()) {
            insertPages(pages)
        }
    }
}

@Dao
interface OcrResultDao {
    @Query("SELECT * FROM ocr_results WHERE documentId = :documentId ORDER BY pageNumber ASC")
    fun observeOcrResultsForDocument(documentId: String): Flow<List<OcrResultEntity>>

    @Query("SELECT * FROM ocr_results WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getOcrResultsForDocument(documentId: String): List<OcrResultEntity>

    @Query("SELECT * FROM ocr_results WHERE pageId = :pageId LIMIT 1")
    suspend fun getOcrResultForPage(pageId: String): OcrResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOcrResult(result: OcrResultEntity)

    @Query("DELETE FROM ocr_results WHERE documentId = :documentId")
    suspend fun deleteOcrResultsForDocument(documentId: String)
}

@Dao
interface GeminiAnalysisDao {
    @Query("SELECT * FROM gemini_page_analyses WHERE documentId = :documentId ORDER BY pageNumber ASC")
    fun observeAnalysesForDocument(documentId: String): Flow<List<GeminiAnalysisEntity>>

    @Query("SELECT * FROM gemini_page_analyses WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getAnalysesForDocument(documentId: String): List<GeminiAnalysisEntity>

    @Query("SELECT * FROM gemini_page_analyses WHERE pageId = :pageId LIMIT 1")
    suspend fun getAnalysisForPage(pageId: String): GeminiAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(analysis: GeminiAnalysisEntity)

    @Query("DELETE FROM gemini_page_analyses WHERE documentId = :documentId")
    suspend fun deleteAnalysesForDocument(documentId: String)
}

@Dao
interface PreferencesDao {
    @Query("SELECT * FROM preferences WHERE singletonId = 1 LIMIT 1")
    fun observePreferences(): Flow<PreferencesEntity?>

    @Query("SELECT * FROM preferences WHERE singletonId = 1 LIMIT 1")
    suspend fun getPreferences(): PreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreferences(preferences: PreferencesEntity)
}

@Dao
interface ApiKeySlotDao {
    @Query("SELECT * FROM api_key_slots ORDER BY createdAtMillis ASC")
    fun observeApiKeySlots(): Flow<List<ApiKeySlotEntity>>

    @Query("SELECT * FROM api_key_slots WHERE id = :id LIMIT 1")
    suspend fun getSlotById(id: String): ApiKeySlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlot(slot: ApiKeySlotEntity)

    @Query("DELETE FROM api_key_slots WHERE id = :id")
    suspend fun deleteSlotById(id: String)
}

data class ReviewStatusCountTuple(
    @ColumnInfo(name = "review_status") val reviewStatus: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
)

@Dao
interface ReviewItemDao {
    @Query(
        """
        SELECT * FROM review_items
        WHERE document_id = :documentId
        ORDER BY priority ASC, page_number ASC, block_index ASC, line_index ASC, element_index ASC, id ASC
        """,
    )
    fun observeReviewItemsForDocument(documentId: String): Flow<List<ReviewItemEntity>>

    @Query(
        """
        SELECT * FROM review_items
        WHERE document_id = :documentId
        ORDER BY page_number ASC, block_index ASC, line_index ASC, element_index ASC, id ASC
        """,
    )
    suspend fun getReviewItemsInReadingOrder(documentId: String): List<ReviewItemEntity>

    @Query("SELECT * FROM review_items WHERE page_id = :pageId ORDER BY priority ASC, element_index ASC, id ASC")
    suspend fun getItemsForPage(pageId: String): List<ReviewItemEntity>

    @Query("SELECT * FROM review_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): ReviewItemEntity?

    @Query(
        """
        SELECT * FROM review_items
        WHERE document_id = :documentId
          AND (:includeAgreements = 1 OR category != 'AGREEMENT')
          AND (:filterStatusName IS NULL OR review_status = :filterStatusName)
          AND (:filterPage IS NULL OR page_number = :filterPage)
        ORDER BY
          CASE WHEN :orderByPriority = 1 THEN priority ELSE page_number END ASC,
          page_number ASC,
          block_index ASC,
          line_index ASC,
          element_index ASC,
          id ASC
        """,
    )
    suspend fun queryReviewQueue(
        documentId: String,
        filterStatusName: String?,
        filterPage: Int?,
        orderByPriority: Boolean,
        includeAgreements: Boolean,
    ): List<ReviewItemEntity>

    suspend fun getReviewQueue(
        documentId: String,
        filterStatus: ReviewStatus? = null,
        filterPage: Int? = null,
        orderByPriority: Boolean = true,
        includeAgreements: Boolean = false,
    ): List<ReviewItemEntity> = queryReviewQueue(
        documentId = documentId,
        filterStatusName = filterStatus?.name,
        filterPage = filterPage,
        orderByPriority = orderByPriority,
        includeAgreements = includeAgreements,
    )

    @Query(
        """
        SELECT review_status, COUNT(*) AS item_count
        FROM review_items
        WHERE document_id = :documentId
          AND (:includeAgreements = 1 OR category != 'AGREEMENT')
        GROUP BY review_status
        """,
    )
    suspend fun queryStatusCounts(
        documentId: String,
        includeAgreements: Boolean = false,
    ): List<ReviewStatusCountTuple>

    @Query(
        """
        SELECT review_status, COUNT(*) AS item_count
        FROM review_items
        WHERE document_id = :documentId
          AND (:includeAgreements = 1 OR category != 'AGREEMENT')
        GROUP BY review_status
        """,
    )
    fun observeStatusCounts(
        documentId: String,
        includeAgreements: Boolean = false,
    ): Flow<List<ReviewStatusCountTuple>>

    suspend fun countByStatus(
        documentId: String,
        includeAgreements: Boolean = false,
    ): Map<ReviewStatus, Int> {
        val raw = queryStatusCounts(documentId, includeAgreements)
            .mapNotNull { tuple ->
                runCatching { ReviewStatus.valueOf(tuple.reviewStatus) }.getOrNull()
                    ?.let { status -> status to tuple.itemCount }
            }
            .toMap()
        return ReviewStatus.entries.associateWith { raw[it] ?: 0 }
    }

    @Query(
        """
        SELECT * FROM review_items
        WHERE document_id = :documentId
          AND review_status = 'PENDING'
          AND category != 'AGREEMENT'
          AND (
            priority > :afterPriority
            OR (priority = :afterPriority AND page_number > :afterPageNumber)
            OR (priority = :afterPriority AND page_number = :afterPageNumber AND element_index > :afterElementIndex)
          )
        ORDER BY priority ASC, page_number ASC, block_index ASC, line_index ASC, element_index ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun getNextPending(
        documentId: String,
        afterPriority: Int,
        afterPageNumber: Int,
        afterElementIndex: Int,
    ): ReviewItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReviewItemEntity>): List<Long>

    @Update
    suspend fun updateItems(items: List<ReviewItemEntity>)

    @Query(
        """
        UPDATE review_items
        SET review_status = :reviewStatus,
            user_text = :userText,
            resolved_at = :resolvedAt,
            stale_note_ar = NULL
        WHERE id = :itemId
        """,
    )
    suspend fun saveReviewDecision(
        itemId: Long,
        reviewStatus: String,
        userText: String?,
        resolvedAt: Long,
    ): Int

    @Query(
        """
        UPDATE review_items
        SET review_status = 'APPROVED_GEMINI',
            user_text = suggested_text,
            resolved_at = :resolvedAt,
            stale_note_ar = NULL
        WHERE id IN (:itemIds)
          AND suggested_text IS NOT NULL
          AND trim(suggested_text) != ''
        """,
    )
    suspend fun bulkApproveGeminiByIds(
        itemIds: List<Long>,
        resolvedAt: Long,
    ): Int

    @Query(
        """
        UPDATE review_items
        SET review_status = 'PENDING',
            stale_note_ar = NULL
        WHERE id = :itemId
        """,
    )
    suspend fun reopenReviewItemAsPending(itemId: Long): Int

    @Query("DELETE FROM review_items WHERE page_id = :pageId AND review_status = 'PENDING'")
    suspend fun deletePendingForPage(pageId: String)

    @Query("DELETE FROM review_items WHERE page_id = :pageId")
    suspend fun deleteAllForPage(pageId: String): Int

    @Query("DELETE FROM review_items WHERE document_id = :documentId")
    suspend fun deleteAllForDocument(documentId: String)

    @Transaction
    suspend fun replacePageComparisonItems(
        pageId: String,
        updatedResolvedItems: List<ReviewItemEntity>,
        newPendingItems: List<ReviewItemEntity>,
    ) {
        deletePendingForPage(pageId)
        if (updatedResolvedItems.isNotEmpty()) {
            updateItems(updatedResolvedItems)
        }
        if (newPendingItems.isNotEmpty()) {
            insertAll(newPendingItems)
        }
    }
}
