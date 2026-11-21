package com.example.domain.model

import com.squareup.moshi.JsonClass

/**
 * Arabic workflow statuses for documents in Muharrir AI.
 */
enum class DocumentStatus(val arabicLabel: String) {
    NEW("جديد"),
    PROCESSING("قيد المعالجة"),
    EXTRACTING_TEXT("استخراج النص"),
    ANALYZING_TEXT("تحليل النص"),
    NEEDS_REVIEW("يحتاج إلى مراجعة"),
    REVIEW_COMPLETED("المراجعة مكتملة"),
    GENERATING_WORD("إنشاء ملف Word"),
    COMPLETED("مكتمل"),
    PAUSED("متوقف"),
    FAILED("فشل"),
}

/**
 * Local import & persistence lifecycle in Module 02.
 */
enum class ImportStatus(val arabicLabel: String) {
    IMPORTING("جارٍ الاستيراد"),
    IMPORTED("تم الاستيراد"),
    READY_FOR_PROCESSING("جاهز للمعالجة"),
    INTERRUPTED("منقطع"),
    FAILED("فشل"),
    DELETED("محذوف"),
}

/**
 * Per-page OCR execution status required by Module 03.
 */
enum class OcrPageStatus(val arabicLabel: String) {
    PENDING("قيد الانتظار"),
    RUNNING("جارٍ الاستخراج"),
    DONE("مكتمل"),
    FAILED("فشل"),
}

enum class PageStatus(val arabicLabel: String) {
    PENDING("بانتظار الاستخراج"),
    IMAGE_READY("الصورة جاهزة"),
    OCR_DONE("اكتمل OCR"),
    ANALYZED("تم التحليل"),
    NEEDS_REVIEW("تحتاج مراجعة"),
    REVIEWED("تمت المراجعة"),
    FAILED("تعذر المعالجة"),
}

enum class ReviewStatus(val arabicLabel: String) {
    PENDING("بانتظار المراجعة"),
    APPROVED_GEMINI("اقتراح Gemini مقبول"),
    MANUALLY_EDITED("معدّل يدوياً"),
    UNRESOLVED("غير محسوم"),
    IGNORED("مستبعد"),
    STALE("يتطلب إعادة مراجعة (تغيّر الأصل)"),
}

enum class ComparisonCategory(val code: String, val arabicLabel: String) {
    AGREEMENT("A", "تطابق"),
    DISAGREEMENT("B", "اختلاف"),
    UNCERTAIN_RECOGNITION("C", "تعرّف غير مؤكد"),
    UNRESOLVED("D", "غير محسوم بصرياً"),
    STRUCTURAL_AMBIGUITY("E", "التباس بنيوي"),
    ;

    companion object {
        fun fromPersisted(raw: String): ComparisonCategory {
            return entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) || it.code.equals(raw, ignoreCase = true)
            } ?: UNCERTAIN_RECOGNITION
        }
    }
}

enum class OcrEngineType(
    val displayNameAr: String,
    val modelInfoAr: String,
    val repositoryUrl: String,
) {
    TESSERACT(
        displayNameAr = "Tesseract4Android — المحرك الأساسي (العربية)",
        modelInfoAr = "المحرك الأساسي للعربية: Tesseract 5.5 على الجهاز مع بيانات اللغة العربية المدمجة (ara.traineddata).",
        repositoryUrl = "https://github.com/tesseract-ocr/tessdata",
    ),
    ML_KIT(
        displayNameAr = "Google ML Kit v2 — ثانوي (اللاتينية والأرقام فقط)",
        modelInfoAr = "ثانوي — للخط اللاتيني والأرقام فقط، لا يدعم اللغة العربية. يُستخدم فقط للمستندات ذات المحتوى اللاتيني أو الرقمي.",
        repositoryUrl = "https://developers.google.com/ml-kit/vision/text-recognition/v2",
    ),
}

enum class ExportMode(
    val arabicTitle: String,
    val arabicDescription: String,
) {
    MATCH_ORIGINAL(
        arabicTitle = "مطابقة الأصل",
        arabicDescription = "الحفاظ على البنية الأصلية وتسلسل النص والفقرات كما وردت في ملف PDF.",
    ),
    ACADEMIC_FORMAT(
        arabicTitle = "التنسيق الأكاديمي",
        arabicDescription = "تحسين التنظيم البصري والهوامش والعناوين مع الحفاظ التام على جميع النصوص الأصلية دون حذف أو إعادة صياغة.",
    ),
}

enum class StageModuleAvailability(val badgeAr: String) {
    READY_IN_MODULE_01("متاح في المرحلة 01"),
    READY_IN_MODULE_02("متاح في المرحلة 02"),
    READY_IN_MODULE_03("متاح في المرحلة 03"),
    READY_IN_MODULE_04("متاح في المرحلة 04"),
    READY_IN_MODULE_05("متاح في المرحلة 05"),
    READY_IN_MODULE_06("متاح في المرحلة 06"),
    READY_IN_MODULE_07("متاح في المرحلة 07"),
    PLANNED_MODULE_02_BACKEND("عقد API جاهز — بانتظار الخادم"),
    PLANNED_MODULE_04_05("مخطط للمرحلة 05"),
    PLANNED_MODULE_06("مخطط للمرحلة 06"),
    PLANNED_MODULE_07("مخطط للمرحلة 07"),
}

data class PipelineStageInfo(
    val id: String,
    val titleAr: String,
    val subtitleAr: String,
    val availability: StageModuleAvailability,
    val isCompletedLocally: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@JsonClass(generateAdapter = true)
data class PointCoord(
    val x: Int,
    val y: Int,
)

enum class OcrElementLevel {
    BLOCK,
    LINE,
    ELEMENT,
}

@JsonClass(generateAdapter = true)
data class OcrSegment(
    val segmentId: String,
    val text: String,
    val boundingBox: BoundingBox?,
    val confidence: Float?,
    val level: OcrElementLevel = OcrElementLevel.ELEMENT,
    val cornerPoints: List<PointCoord>? = null,
    val blockIndex: Int = 0,
    val lineIndex: Int? = null,
    val elementIndex: Int? = null,
    val parentBlockId: String? = null,
    val parentLineId: String? = null,
)

data class OcrResult(
    val pageId: String,
    val documentId: String = "",
    val pageNumber: Int = 1,
    val recognizedText: String,
    val segments: List<OcrSegment>,
    val boundingBoxes: List<BoundingBox>,
    val confidence: Float?,
    val engineType: OcrEngineType,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val recognizedAtMillis: Long = System.currentTimeMillis(),
)

data class ReviewItem(
    val reviewItemId: String,
    val documentId: String = "",
    val pageId: String,
    val pageNumber: Int,
    val originalOcrText: String,
    val geminiSuggestion: String?,
    val explanation: String?,
    val approvedText: String?,
    val status: ReviewStatus,
    val category: ComparisonCategory = ComparisonCategory.UNCERTAIN_RECOGNITION,
    val priority: Int = 4,
    val ocrConfidence: Float? = null,
    val geminiConfidence: Float? = null,
    val reasonType: String = "context",
    val staleNoteAr: String? = null,
    val cropBox: BoundingBox? = null,
    val resolvedAt: Long? = null,
    val blockIndex: Int = 0,
    val lineIndex: Int = 0,
    val elementIndex: Int = 0,
)

object PageFailureReasons {
    const val TIMEOUT = "timeout"
    const val QUOTA = "quota"
    const val RATE_LIMITED = "rate_limited"
    const val PARSE_ERROR = "parse_error"
    const val NETWORK_ERROR = "network_error"
    const val INVALID_KEY = "invalid_key"
    const val PERMISSION_DENIED = "permission_denied"
    const val SERVER_ERROR = "server_error"
    const val RENDER_ERROR = "render_error"
    const val UNKNOWN_ERROR = "unknown_error"
    const val USER_SKIPPED = "user_skipped"
}

data class PageRecord(
    val pageId: String,
    val documentId: String,
    val originalPageNumber: Int,
    val imageRef: String?,
    val status: PageStatus,
    val widthPoints: Int? = null,
    val heightPoints: Int? = null,
    val thumbnailCachePath: String? = null,
    val ocrStatus: OcrPageStatus = OcrPageStatus.PENDING,
    val ocrEngineUsed: OcrEngineType? = null,
    val ocrErrorMessageAr: String? = null,
    val ocrResult: OcrResult? = null,
    val geminiAnalysis: GeminiPageAnalysisRecord? = null,
    val hasMissingContent: Boolean = false,
    val failureReason: String? = null,
)

data class ExportedDocument(
    val documentId: String,
    val docxFileRef: String,
    val exportMode: ExportMode,
    val createdAtMillis: Long,
)

data class ReviewProgress(
    val totalItems: Int = 0,
    val resolvedItems: Int = 0,
    val unresolvedItems: Int = 0,
)

data class DocumentRecord(
    val id: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val uploadDateMillis: Long,
    val pageCount: Int?,
    val status: DocumentStatus,
    val originalFileRef: String,
    val isValidationPassed: Boolean,
    val validationNote: String? = null,
    val internalStorageRef: String = "",
    val lastModifiedMillis: Long = uploadDateMillis,
    val importStatus: ImportStatus = ImportStatus.READY_FOR_PROCESSING,
    val indexedPagesCount: Int = 0,
    val importProgressPercent: Int = 100,
    val processingProgressPercent: Int = 0,
    val errorMessageAr: String? = null,
    val pages: List<PageRecord> = emptyList(),
    val reviewItems: List<ReviewItem> = emptyList(),
    val reviewProgress: ReviewProgress = ReviewProgress(),
    val reviewApprovedAt: Long? = null,
    val exportedDocument: ExportedDocument? = null,
)

sealed interface BackendConnectionState {
    data class NotConfigured(val reasonAr: String) : BackendConnectionState
    data class Configured(val endpointSummary: String) : BackendConnectionState
}

enum class ApiKeyStatus(val arabicLabel: String) {
    PENDING_BACKEND("بانتظار ربط الخادم (المرحلة 04)"),
    ACTIVE("مفعّل"),
    DISABLED("معطّل"),
    QUOTA_LIMITED("محدود الحصة"),
    INVALID("غير صالح"),
}

/**
 * Frontend-safe metadata representation for Gemini API keys.
 * Raw API keys must NEVER be hardcoded or persisted in frontend source/bundles.
 */
data class ApiKeyMetadata(
    val id: String,
    val label: String,
    val isEnabled: Boolean,
    val status: ApiKeyStatus,
    val createdAtMillis: Long,
)

data class ProcessingPreferences(
    val preferredOcrEngine: OcrEngineType = OcrEngineType.TESSERACT,
    val preferredExportMode: ExportMode = ExportMode.MATCH_ORIGINAL,
    val highlightUnresolvedInDocx: Boolean = true,
    val autoSaveCheckpoints: Boolean = true,
    val resumeAfterInterruption: Boolean = true,
    val batchSizePages: Int = 10,
)

/**
 * Centralized Gemini model and multimodal payload configuration for Module 04.
 *
 * Flash-class models (`gemini-flash-latest` rolling alias) are used by default because
 * Muharrir processes per-page multimodal requests (page image + OCR segment context)
 * that prioritize low latency and lower token cost per page while avoiding obsolete
 * retired model IDs (e.g. gemini-1.5-flash, gemini-2.0-flash).
 */
object GeminiModelConfig {
    const val DEFAULT_MODEL_ID = "gemini-flash-latest"
    const val MAX_LONG_SIDE_PX = 1024
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024 // Strictly < 2 MB per request
    const val TARGET_IMAGE_BYTES = 1_850_000
    const val TEMPERATURE = 0.1f
    const val MAX_RATE_LIMIT_RETRIES_PER_KEY_PER_PAGE = 2

    val AVAILABLE_MODELS: List<GeminiModelOption> = listOf(
        GeminiModelOption(
            modelId = "gemini-flash-latest",
            displayNameAr = "Gemini Flash (الافتراضي المستقر)",
            rationaleAr = "نموذج فئة Flash المتجدد — مناسب لتحليل صور الصفحات عالية الدقة مع تكلفة منخفضة وزمن استجابة سريع.",
        ),
        GeminiModelOption(
            modelId = "gemini-flash-lite-latest",
            displayNameAr = "Gemini Flash-Lite (اقتصادي)",
            rationaleAr = "خيار أخف وأسرع للصفحات النصية الواضحة عند الرغبة في تقليل استهلاك الحصة.",
        ),
    )
}

data class GeminiModelOption(
    val modelId: String,
    val displayNameAr: String,
    val rationaleAr: String,
)

enum class GeminiKeyStatus(val arabicLabel: String) {
    VALID("صالح"),
    INVALID("غير صالح"),
    RATE_LIMITED("محدود المعدل"),
    QUOTA_EXHAUSTED("نفدت الحصة"),
    PERMISSION_DENIED("مرفوض الصلاحية"),
    UNKNOWN("غير مختبر"),
}

/**
 * UI-safe metadata for a Gemini API key persisted in EncryptedSharedPreferences.
 * Only a masked prefix (first 6 characters + "...") is ever exposed outside the secure vault.
 */
data class GeminiKeyRecord(
    val id: String,
    val label: String,
    val maskedPrefix: String,
    val isEnabled: Boolean,
    val status: GeminiKeyStatus,
    val lastErrorAr: String? = null,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long? = null,
)

data class GeminiUsageStats(
    val activeKeyId: String? = null,
    val activeKeyLabel: String? = null,
    val selectedModelId: String = GeminiModelConfig.DEFAULT_MODEL_ID,
    val lastRequestStatusAr: String = "لم يُرسل أي طلب إلى Gemini بعد",
    val lastRequestSucceeded: Boolean? = null,
    val requestsTodayCount: Int = 0,
    val lastRequestTimeMillis: Long? = null,
)

enum class GeminiReasonType(val wireValue: String, val arabicLabel: String) {
    VISUAL("visual", "دليل بصري (شكل الحرف/النقاط)"),
    CONTEXT("context", "دليل سياقي"),
    LEXICON("lexicon", "دليل معجمي/اصطلاحي"),
    MORPHOLOGY("morphology", "دليل صرفي/نحوي"),
    STRUCTURE("structure", "بنية النص (عنوان/حاشية)"),
    ;

    companion object {
        fun fromWireValue(value: String): GeminiReasonType? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}

@JsonClass(generateAdapter = true)
data class GeminiSuggestionItem(
    val ocr_text: String,
    val suggested_text: String,
    val bbox: List<Float>,
    val confidence: Float,
    val evidence: String,
    val reason_type: String,
    val requires_review: Boolean,
)

@JsonClass(generateAdapter = true)
data class GeminiPageAnalysisResponse(
    val page_id: String,
    val page_number: Int,
    val items: List<GeminiSuggestionItem>,
    val isMissingLargeContent: Boolean = false,
)

data class GeminiPageAnalysisRecord(
    val pageId: String,
    val documentId: String,
    val pageNumber: Int,
    val modelId: String,
    val usedKeyLabel: String,
    val items: List<GeminiSuggestionItem>,
    val rawValidatedJson: String,
    val analyzedAtMillis: Long,
    val isMissingLargeContent: Boolean = false,
)

