package com.example.export.model

import com.example.domain.model.ExportMode
import com.example.review.model.ReviewItemModel

enum class WordExportMode(
    val titleAr: String,
    val descriptionAr: String,
) {
    MATCH_ORIGINAL(
        titleAr = "مطابقة الأصل",
        descriptionAr = "إخراج النص بفقرات بسيطة تحافظ على البنية الأصلية دون تنسيق ثقيل.",
    ),
    ACADEMIC(
        titleAr = "التنسيق الأكاديمي",
        descriptionAr = "تطبيق أحجام خطوط متناسقة وعناوين بارزة وتباعد فقرات منتظم.",
    ),
}

fun ExportMode.toWordExportMode(): WordExportMode = when (this) {
    ExportMode.MATCH_ORIGINAL -> WordExportMode.MATCH_ORIGINAL
    ExportMode.ACADEMIC_FORMAT -> WordExportMode.ACADEMIC
}

fun WordExportMode.toDomainExportMode(): ExportMode = when (this) {
    WordExportMode.MATCH_ORIGINAL -> ExportMode.MATCH_ORIGINAL
    WordExportMode.ACADEMIC -> ExportMode.ACADEMIC_FORMAT
}

data class DocumentPageModel(
    val id: String,
    val documentId: String,
    val pageNumber: Int,
    val ocrText: String,
)

data class ExportDocumentModel(
    val id: String,
    val title: String,
    val reviewApprovedAt: Long?,
)

data class WordTextSegment(
    val text: String,
    val isUnresolvedHighlight: Boolean,
)

data class WordPageSection(
    val pageNumber: Int,
    val segments: List<WordTextSegment>,
    val paragraphs: List<List<WordTextSegment>> = segments.map { listOf(it) },
    val textLossWarning: String? = null,
)

data class WordDocumentPlan(
    val documentTitle: String,
    val mode: WordExportMode,
    val pages: List<WordPageSection>,
)

/**
 * Gateway querying Room data for final Word export (Modules 01–06).
 */
interface WordExportDataGateway {
    suspend fun getDocument(documentId: String): ExportDocumentModel?
    suspend fun getPagesInOrder(documentId: String): List<DocumentPageModel>
    suspend fun getReviewItemsInOrder(documentId: String): List<ReviewItemModel>
    suspend fun shouldHighlightUnresolved(): Boolean = true
    suspend fun recordExportedDocument(
        documentId: String,
        docxFilePath: String,
        mode: WordExportMode,
        exportedAtMillis: Long,
    ) {}
}
