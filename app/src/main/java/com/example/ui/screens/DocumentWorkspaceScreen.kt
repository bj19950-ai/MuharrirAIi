package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Scanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.example.domain.model.BackendConnectionState
import com.example.domain.model.DocumentRecord
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiReasonType
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageRecord
import com.example.domain.model.PipelineStageInfo
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.export.ui.WordExportSection
import com.example.export.ui.WordExportViewModel
import com.example.ui.components.DocumentStatusBadge
import com.example.ui.components.ImportStatusBadge
import com.example.ui.components.ModuleAvailabilityPill
import com.example.ui.components.OcrPageStatusBadge
import com.example.ui.components.ScholarlyInfoBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatDateTimeArabic
import com.example.ui.components.formatFileSizeArabic
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.MuharrirTheme
import com.example.ui.viewmodel.GeminiExecutionUiState
import com.example.ui.viewmodel.OcrExecutionUiState

@Composable
fun DocumentWorkspaceScreen(
    document: DocumentRecord?,
    stages: List<PipelineStageInfo>,
    preferredExportMode: ExportMode,
    backendConnectionState: BackendConnectionState,
    ocrState: OcrExecutionUiState,
    selectedPageRange: IntRange? = null,
    pagesNeedingReprocessingCount: Int = 0,
    geminiState: GeminiExecutionUiState,
    geminiKeys: List<GeminiKeyRecord>,
    geminiUsageStats: GeminiUsageStats,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
    isLoadingPreviewPage: Boolean,
    pageThumbnails: Map<Int, Bitmap>,
    workspaceNoticeAr: String?,
    onDismissNotice: () -> Unit,
    onRequestPreviewPage: (android.content.Context, DocumentRecord, Int) -> Unit,
    onRequestPageThumbnail: (DocumentRecord, Int) -> Unit,
    onSelectOcrEngine: (OcrEngineType) -> Unit,
    onStartOrResumeOcr: (android.content.Context, String, Int?, Int?) -> Unit,
    onPauseOcr: () -> Unit,
    onReprocessFailedPages: (android.content.Context, String) -> Unit,
    onRetrySingleOcrPage: (android.content.Context, String, String) -> Unit,
    onStartGeminiAnalysis: (String, String?) -> Unit,
    onPauseGeminiAnalysis: () -> Unit,
    wordExportViewModel: WordExportViewModel,
    onOpenSettings: () -> Unit,
    onOpenReviewWorkspace: () -> Unit,
    onOpenReviewSummary: () -> Unit,
    onCreateNewDocument: () -> Unit,
    onReplaceDocument: (DocumentRecord) -> Unit,
    onRemoveDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showOriginalPdfViewer by remember(document?.id) { mutableStateOf(true) }
    var showDeleteConfirmation by remember(document?.id) { mutableStateOf(false) }

    LaunchedEffect(document?.id, showOriginalPdfViewer) {
        if (document != null &&
            document.importStatus == ImportStatus.READY_FOR_PROCESSING &&
            showOriginalPdfViewer &&
            previewBitmap == null
        ) {
            onRequestPreviewPage(context, document, previewPageIndex)
        }
    }

    if (showDeleteConfirmation && document != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("تأكيد حذف المستند المحلي") },
            text = {
                Text(
                    text = "سيتم حذف «${document.fileName}» وسجلات صفحاته ونسخته المحفوظة في التخزين الخاص للتطبيق فقط. لا توجد نسخة سحابية متصلة حالياً.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onRemoveDocument(document.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_delete_document_button"),
                ) {
                    Text("حذف النسخة المحلية")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("إلغاء")
                }
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("document_workspace_screen"),
    ) {
        if (document == null) {
            EmptyWorkspaceState(
                onCreateNewDocument = onCreateNewDocument,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
            return@Box
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (workspaceNoticeAr != null) {
                item {
                    val ext = MuharrirTheme.extendedColors
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = ext.successContainer,
                        ),
                        border = BorderStroke(1.dp, ext.success),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = workspaceNoticeAr,
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.onSuccessContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissNotice) {
                                Text("إغلاق", color = ext.onSuccessContainer)
                            }
                        }
                    }
                }
            }

            item {
                WorkspaceHeaderCard(
                    document = document,
                    onReplace = { onReplaceDocument(document) },
                    onRequestDelete = { showDeleteConfirmation = true },
                )
            }

            if (document.errorMessageAr != null &&
                (document.importStatus == ImportStatus.INTERRUPTED || document.importStatus == ImportStatus.FAILED)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "تعذر إكمال الاستيراد",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = document.errorMessageAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            if (document.pages.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "إدارة وفهرسة الصفحات (${document.pages.size} صفحة)",
                        subtitle = "انقر على أي صفحة مصغّرة لمعاينة الأصل من النسخة الخاصة",
                        icon = Icons.Rounded.ViewCarousel,
                    )
                }

                item {
                    PageIndexStripCard(
                        document = document,
                        pages = document.pages,
                        selectedPageIndex = previewPageIndex,
                        thumbnails = pageThumbnails,
                        onRequestThumbnail = { pageNumber ->
                            onRequestPageThumbnail(document, pageNumber)
                        },
                        onSelectPage = { pageIndex ->
                            showOriginalPdfViewer = true
                            onRequestPreviewPage(context, document, pageIndex)
                        },
                    )
                }
            }

            item {
                OriginalPdfAccessCard(
                    document = document,
                    isViewerExpanded = showOriginalPdfViewer,
                    previewBitmap = previewBitmap,
                    previewPageIndex = previewPageIndex,
                    isLoadingPage = isLoadingPreviewPage,
                    onToggleViewer = {
                        showOriginalPdfViewer = !showOriginalPdfViewer
                        if (showOriginalPdfViewer && previewBitmap == null) {
                            onRequestPreviewPage(context, document, previewPageIndex)
                        }
                    },
                    onChangePage = { nextIndex ->
                        onRequestPreviewPage(context, document, nextIndex)
                    },
                )
            }

            if (document.importStatus == ImportStatus.READY_FOR_PROCESSING && document.pages.isNotEmpty()) {
                item {
                    OnDeviceOcrCard(
                        document = document,
                        ocrState = ocrState,
                        selectedPageRange = selectedPageRange,
                        pagesNeedingReprocessingCount = pagesNeedingReprocessingCount,
                        onSelectOcrEngine = onSelectOcrEngine,
                        onStartOrResume = { startPage, endPage ->
                            onStartOrResumeOcr(context, document.id, startPage, endPage)
                        },
                        onPause = onPauseOcr,
                        onReprocessFailedPages = { onReprocessFailedPages(context, document.id) },
                    )
                }

                item {
                    GeminiAnalysisWorkspaceCard(
                        document = document,
                        selectedPageIndex = previewPageIndex,
                        geminiState = geminiState,
                        geminiKeys = geminiKeys,
                        usageStats = geminiUsageStats,
                        onStartAllPending = { onStartGeminiAnalysis(document.id, null) },
                        onAnalyzeSelectedPage = { pageId -> onStartGeminiAnalysis(document.id, pageId) },
                        onPause = onPauseGeminiAnalysis,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }

            item {
                SectionHeader(
                    title = "تقدم المعالجة والمراحل",
                    subtitle = "تمييز واضح بين المهام المحلية المنفذة والمراحل المخططة لاحقاً",
                    icon = Icons.Rounded.Layers,
                )
            }

            item {
                ProcessingProgressCard(document = document, stages = stages)
            }

            item {
                BackendConnectionStatusCard(backendConnectionState = backendConnectionState)
            }

            item {
                ReviewProgressSectionCard(
                    document = document,
                    onOpenReviewWorkspace = onOpenReviewWorkspace,
                    onOpenReviewSummary = onOpenReviewSummary,
                )
            }

            item {
                WorkingTextCard(
                    document = document,
                    selectedPageIndex = previewPageIndex,
                    isOcrRunning = ocrState.isRunning,
                    onRetrySinglePage = { pageId ->
                        onRetrySingleOcrPage(context, document.id, pageId)
                    },
                )
            }

            item {
                FinalWordExportCard(
                    document = document,
                    preferredExportMode = preferredExportMode,
                    wordExportViewModel = wordExportViewModel,
                    onOpenReviewSummary = onOpenReviewSummary,
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceState(
    onCreateNewDocument: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .widthIn(max = 520.dp)
            .testTag("empty_workspace_state"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = "لا يوجد مستند مفتوح حالياً",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "استورد ملف PDF جديداً أو اختر مستنداً من الرئيسية لعرض صفحاته المفهرسة ومساحة العمل الخاصة به.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onCreateNewDocument,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("workspace_add_pdf_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("استيراد ملف PDF جديد")
            }
        }
    }
}

@Composable
private fun WorkspaceHeaderCard(
    document: DocumentRecord,
    onReplace: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "معلومات المستند الأصلي",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImportStatusBadge(importStatus = document.importStatus)
                    DocumentStatusBadge(status = document.status)
                }
            }

            if (document.importStatus == ImportStatus.READY_FOR_PROCESSING) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "محفوظ في التخزين الخاص للتطبيق — يظل متاحاً حتى لو نُقل الملف الخارجي أو حُذف.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            InfoLine(label = "حجم الملف", value = formatFileSizeArabic(document.fileSizeBytes))
            InfoLine(
                label = "عدد الصفحات الفعلي",
                value = document.pageCount?.let { "$it صفحة" } ?: "غير محدد",
            )
            InfoLine(
                label = "الصفحات المفهرسة",
                value = "${document.indexedPagesCount} من ${document.pageCount ?: 0}",
            )
            InfoLine(label = "تاريخ الاستيراد", value = formatDateTimeArabic(document.uploadDateMillis))
            InfoLine(label = "آخر تعديل", value = formatDateTimeArabic(document.lastModifiedMillis))

            if (document.validationNote != null) {
                Text(
                    text = document.validationNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onReplace,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("workspace_replace_doc_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استبدال الملف")
                }

                OutlinedButton(
                    onClick = onRequestDelete,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("workspace_remove_doc_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "حذف المستند",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("حذف المستند")
                }
            }
        }
    }
}

@Composable
private fun PageIndexStripCard(
    document: DocumentRecord,
    pages: List<PageRecord>,
    selectedPageIndex: Int,
    thumbnails: Map<Int, Bitmap>,
    onRequestThumbnail: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("page_index_strip_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        LazyRow(
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = pages,
                key = { it.pageId },
            ) { page ->
                LaunchedEffect(document.id, page.originalPageNumber) {
                    onRequestThumbnail(page.originalPageNumber)
                }

                val isSelected = (page.originalPageNumber - 1) == selectedPageIndex
                val thumb = thumbnails[page.originalPageNumber]

                Surface(
                    modifier = Modifier
                        .width(106.dp)
                        .clickable { onSelectPage(page.originalPageNumber - 1) }
                        .testTag("page_thumb_${page.originalPageNumber}"),
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = "مصغّر صفحة ${page.originalPageNumber}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    text = "${page.originalPageNumber}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "صفحة ${page.originalPageNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (page.widthPoints != null && page.heightPoints != null) {
                            Text(
                                text = "${page.widthPoints}×${page.heightPoints}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OcrPageStatusBadge(ocrStatus = page.ocrStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProcessingProgressCard(
    document: DocumentRecord,
    stages: List<PipelineStageInfo>,
) {
    val completedStagesCount = stages.count { it.isCompletedLocally }
    val progressRatio = if (stages.isEmpty()) 0f else completedStagesCount.toFloat() / stages.size.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "مؤشر مراحل خط المعالجة",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$completedStagesCount / ${stages.size} مرحلة منفذة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            )

            Text(
                text = "حالة الاستيراد: ${document.importStatus.arabicLabel} • حالة المعالجة: ${document.status.arabicLabel}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            stages.forEach { stage ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (stage.isCompletedLocally) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = if (stage.isCompletedLocally) {
                            MuharrirTheme.extendedColors.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stage.titleAr,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            ModuleAvailabilityPill(
                                availability = stage.availability,
                                isCompletedLocally = stage.isCompletedLocally,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stage.subtitleAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackendConnectionStatusCard(
    backendConnectionState: BackendConnectionState,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backend_connection_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "حالة الاتصال بالخادم الخلفي",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                val description = when (backendConnectionState) {
                    is BackendConnectionState.NotConfigured -> backendConnectionState.reasonAr
                    is BackendConnectionState.Configured -> "متصل بالخادم: ${backendConnectionState.endpointSummary}"
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewProgressSectionCard(
    document: DocumentRecord,
    onOpenReviewWorkspace: () -> Unit,
    onOpenReviewSummary: () -> Unit,
) {
    val approvedAt = document.reviewApprovedAt
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace_review_progress_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RateReview,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "المراجعة البشرية التفاعلية",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (approvedAt != null) {
                    val ext = MuharrirTheme.extendedColors
                    Surface(
                        color = ext.successContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "معتمدة",
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.onSuccessContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = "المحسوم: ${document.reviewProgress.resolvedItems} من ${document.reviewProgress.totalItems} • غير المحسوم: ${document.reviewProgress.unresolvedItems}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (approvedAt != null) {
                Text(
                    text = "تاريخ اعتماد المراجعة: ${formatDateTimeArabic(approvedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MuharrirTheme.extendedColors.success,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenReviewWorkspace,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("workspace_open_review_button"),
                ) {
                    Text("بدء / متابعة المراجعة")
                }
                OutlinedButton(
                    onClick = onOpenReviewSummary,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("workspace_open_review_summary_button"),
                ) {
                    Text("ملخص المراجعة")
                }
            }
        }
    }
}

@Composable
private fun OriginalPdfAccessCard(
    document: DocumentRecord,
    isViewerExpanded: Boolean,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
    isLoadingPage: Boolean,
    onToggleViewer: () -> Unit,
    onChangePage: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "معاينة ملف PDF الأصلي",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = onToggleViewer,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("toggle_original_pdf_button"),
                ) {
                    Text(if (isViewerExpanded) "إخفاء المعاينة" else "معاينة الأصل")
                }
            }

            Text(
                text = "تُعرض الصفحات مباشرة من النسخة المحفوظة في التخزين الخاص للتطبيق دون تعديل الملف الأصلي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isViewerExpanded) {
                val total = document.pageCount ?: 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onChangePage(previewPageIndex - 1) },
                        enabled = previewPageIndex > 0 && !isLoadingPage,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "الصفحة السابقة",
                        )
                    }
                    Text(
                        text = "صفحة ${previewPageIndex + 1} من $total",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(
                        onClick = { onChangePage(previewPageIndex + 1) },
                        enabled = previewPageIndex < total - 1 && !isLoadingPage,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = "الصفحة التالية",
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 460.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "صفحة ${previewPageIndex + 1} من الملف الأصلي",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    } else if (!isLoadingPage) {
                        Text(
                            text = if (document.importStatus == ImportStatus.READY_FOR_PROCESSING) {
                                "تعذر تصيير هذه الصفحة محلياً."
                            } else {
                                "لا تتوفر معاينة الصفحة لأن الملف لم يكتمل استيراده."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    if (isLoadingPage) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun OnDeviceOcrCard(
    document: DocumentRecord,
    ocrState: OcrExecutionUiState,
    selectedPageRange: IntRange?,
    pagesNeedingReprocessingCount: Int,
    onSelectOcrEngine: (OcrEngineType) -> Unit,
    onStartOrResume: (Int, Int) -> Unit,
    onPause: () -> Unit,
    onReprocessFailedPages: () -> Unit,
) {
    val pages = document.pages
    val totalCount = pages.size
    val doneCount = pages.count { it.ocrStatus == OcrPageStatus.DONE }
    val failedCount = pages.count { it.ocrStatus == OcrPageStatus.FAILED }
    val pendingCount = pages.count { it.ocrStatus == OcrPageStatus.PENDING || it.ocrStatus == OcrPageStatus.RUNNING }
    val isThisDocRunning = ocrState.isRunning && ocrState.activeDocumentId == document.id
    val isEngineAvailable = ocrState.availability !is OcrAvailabilityResult.Unavailable
    val progressRatio = if (totalCount == 0) 0f else doneCount.toFloat() / totalCount.toFloat()

    val fallbackCandidateCount = remember(pages) {
        pages.count { page ->
            page.ocrStatus == OcrPageStatus.FAILED ||
                (page.ocrResult != null && page.ocrResult.recognizedText.trim().length < 150) ||
                (page.ocrStatus == OcrPageStatus.DONE && page.ocrResult == null)
        }
    }
    val effectiveReprocessCount = if (pagesNeedingReprocessingCount > 0) {
        pagesNeedingReprocessingCount
    } else {
        fallbackCandidateCount
    }

    var startPageText by remember(document.id, totalCount, selectedPageRange) {
        val defaultStart = (selectedPageRange?.first ?: 1).coerceIn(1, totalCount.coerceAtLeast(1))
        mutableStateOf(defaultStart.toString())
    }
    var endPageText by remember(document.id, totalCount, selectedPageRange) {
        val defaultEnd = (selectedPageRange?.last ?: totalCount).coerceIn(1, totalCount.coerceAtLeast(1))
        mutableStateOf(defaultEnd.toString())
    }
    var showReprocessConfirmation by remember(document.id) { mutableStateOf(false) }

    val parsedStart = startPageText.trim().toIntOrNull()
    val parsedEnd = endPageText.trim().toIntOrNull()
    val rangeErrorAr = when {
        totalCount == 0 -> null
        parsedStart == null || parsedEnd == null -> "يرجى إدخال رقمي صفحة بداية ونهاية صحيحين."
        parsedStart < 1 -> "يجب أن تبدأ صفحة البداية من 1 على الأقل."
        parsedEnd > totalCount -> "يجب ألا تتجاوز صفحة النهاية إجمالي الصفحات ($totalCount)."
        parsedStart > parsedEnd -> "يجب أن تكون «من صفحة» أصغر من أو تساوي «إلى صفحة»."
        else -> null
    }
    val validRange = if (rangeErrorAr == null && parsedStart != null && parsedEnd != null) {
        parsedStart..parsedEnd
    } else {
        null
    }
    val pendingInRangeCount = if (validRange != null) {
        pages.count {
            it.originalPageNumber in validRange &&
                (it.ocrStatus == OcrPageStatus.PENDING || it.ocrStatus == OcrPageStatus.RUNNING)
        }
    } else {
        0
    }

    if (showReprocessConfirmation) {
        AlertDialog(
            onDismissRequest = { showReprocessConfirmation = false },
            title = { Text("إعادة معالجة الصفحات الفاشلة فقط") },
            text = {
                Text(
                    text = "سيتم إعادة معالجة $effectiveReprocessCount صفحة فقط. هل أنت متأكد؟",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReprocessConfirmation = false
                        onReprocessFailedPages()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("confirm_reprocess_failed_pages_button"),
                ) {
                    Text("تأكيد وإعادة المعالجة")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showReprocessConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("إلغاء")
                }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("on_device_ocr_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Scanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "استخراج النص على الجهاز (OCR)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "$doneCount / $totalCount مكتمل",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OcrEngineType.entries.forEach { engine ->
                    val shortLabel = when (engine) {
                        OcrEngineType.TESSERACT -> "Tesseract (العربية)"
                        OcrEngineType.ML_KIT -> "ML Kit (ثانوي: لاتيني)"
                    }
                    FilterChip(
                        selected = ocrState.activeEngine == engine,
                        onClick = { onSelectOcrEngine(engine) },
                        enabled = !ocrState.isRunning,
                        label = { Text(shortLabel, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("workspace_ocr_engine_${engine.name}"),
                    )
                }
            }

            Text(
                text = ocrState.activeEngine.modelInfoAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val availability = ocrState.availability) {
                is OcrAvailabilityResult.Ready -> {
                    val ext = MuharrirTheme.extendedColors
                    ScholarlyInfoBanner(
                        title = "المحرك الأساسي للعربية جاهز",
                        body = "تم التحقق من ملف ara.traineddata وتهيئة محرك Tesseract للغة العربية (ara) على الجهاز.",
                        icon = Icons.Rounded.CheckCircle,
                        containerColor = ext.successContainer,
                        contentColor = ext.onSuccessContainer,
                    )
                }
                is OcrAvailabilityResult.LimitedCapability -> {
                    val ext = MuharrirTheme.extendedColors
                    ScholarlyInfoBanner(
                        title = "تنبيه: محرك ثانوي للاتينية والأرقام فقط",
                        body = availability.warningAr,
                        icon = Icons.Rounded.WarningAmber,
                        containerColor = ext.warningContainer,
                        contentColor = ext.onWarningContainer,
                    )
                }
                is OcrAvailabilityResult.Unavailable -> {
                    ScholarlyInfoBanner(
                        title = "تعذر تشغيل المحرك المحدد (غير متاح)",
                        body = availability.reasonAr,
                        icon = Icons.Rounded.ErrorOutline,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                null -> Unit
            }

            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            )

            Text(
                text = if (isThisDocRunning) {
                    "جارٍ استخراج الصفحة ${ocrState.currentPageNumber} من $totalCount — مكتمل: $doneCount • فشل: $failedCount"
                } else {
                    "مكتمل: $doneCount • قيد الانتظار: $pendingCount • فشل: $failedCount"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (totalCount > 0 && !isThisDocRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = startPageText,
                        onValueChange = { startPageText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("من صفحة") },
                        singleLine = true,
                        enabled = !ocrState.isRunning,
                        isError = rangeErrorAr != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workspace_ocr_start_page_input"),
                    )
                    OutlinedTextField(
                        value = endPageText,
                        onValueChange = { endPageText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("إلى صفحة") },
                        singleLine = true,
                        enabled = !ocrState.isRunning,
                        isError = rangeErrorAr != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workspace_ocr_end_page_input"),
                    )
                }
                if (rangeErrorAr != null) {
                    Text(
                        text = rangeErrorAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("workspace_ocr_page_range_error"),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isThisDocRunning) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ocr_pause_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إيقاف مؤقت")
                    }
                } else {
                    Button(
                        onClick = {
                            if (validRange != null) {
                                onStartOrResume(validRange.first, validRange.last)
                            }
                        },
                        enabled = isEngineAvailable && !ocrState.isRunning && validRange != null && pendingInRangeCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ocr_start_resume_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                !isEngineAvailable -> "المحرك غير متاح"
                                validRange != null && pendingInRangeCount == 0 && doneCount > 0 -> "اكتمل النطاق المحدد"
                                doneCount > 0 && pendingInRangeCount > 0 -> "استئناف OCR"
                                else -> "بدء استخراج النص"
                            },
                        )
                    }

                    if (effectiveReprocessCount > 0) {
                        OutlinedButton(
                            onClick = { showReprocessConfirmation = true },
                            enabled = isEngineAvailable && !ocrState.isRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("reprocess_failed_pages_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إعادة معالجة الصفحات الفاشلة فقط")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingTextCard(
    document: DocumentRecord,
    selectedPageIndex: Int,
    isOcrRunning: Boolean,
    onRetrySinglePage: (String) -> Unit,
) {
    val selectedPage = document.pages.getOrNull(selectedPageIndex)
    val ocrResult = selectedPage?.ocrResult

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("working_text_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = selectedPage?.let {
                            "النص المستخرج — صفحة ${it.originalPageNumber}"
                        } ?: "النص قيد العمل",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (selectedPage != null) {
                    OcrPageStatusBadge(ocrStatus = selectedPage.ocrStatus)
                }
            }

            if (selectedPage == null) {
                Text(
                    text = "لا توجد صفحة محددة لعرض نتائج OCR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (selectedPage.ocrStatus == OcrPageStatus.FAILED) {
                Text(
                    text = selectedPage.ocrErrorMessageAr ?: "فشل استخراج النص لهذه الصفحة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { onRetrySinglePage(selectedPage.pageId) },
                    enabled = !isOcrRunning,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("ocr_retry_single_page_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إعادة محاولة هذه الصفحة")
                }
            } else if (ocrResult == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                ) {
                    Text(
                        text = "لم يُشغَّل استخراج النص لهذه الصفحة بعد. اضغط «بدء استخراج النص» بالأعلى لحفظ مخرجات OCR وإحداثياتها في قاعدة البيانات.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmiriFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val confidenceText = ocrResult.confidence?.let { conf ->
                    "${(conf * 100f).toInt()}%"
                } ?: "غير متوفرة"

                Text(
                    text = "المحرك: ${ocrResult.engineType.displayNameAr} • العناصر: ${ocrResult.segments.size} • الثقة: $confidenceText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                ) {
                    Text(
                        text = ocrResult.recognizedText.ifBlank {
                            "لم يلتقط المحرك نصاً مقروءاً في هذه الصفحة (قد يعود ذلك لغياب دعم الخط العربي في محرك ML Kit v2 أو لصفحة صورة فارغة)."
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmiriFontFamily),
                        color = if (ocrResult.recognizedText.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FinalWordExportCard(
    document: DocumentRecord,
    preferredExportMode: ExportMode,
    wordExportViewModel: WordExportViewModel,
    onOpenReviewSummary: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("final_word_export_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "مستند Word النهائي (DOCX)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "المرحلة 07",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = "إنشاء ملف Word عربي داعم للاتجاه (RTL) مع تمييز المواضع غير المحسومة، باستخدام النمط الافتراضي «${preferredExportMode.arabicTitle}» أو اختيار النمط عند التصدير.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WordExportSection(
                documentId = document.id,
                documentTitle = document.fileName,
                isReviewApproved = document.reviewApprovedAt != null,
                preferredExportMode = preferredExportMode,
                exportedDocument = document.exportedDocument,
                exportViewModel = wordExportViewModel,
                onOpenReviewSummary = onOpenReviewSummary,
            )
        }
    }
}

@Composable
private fun GeminiAnalysisWorkspaceCard(
    document: DocumentRecord,
    selectedPageIndex: Int,
    geminiState: GeminiExecutionUiState,
    geminiKeys: List<GeminiKeyRecord>,
    usageStats: GeminiUsageStats,
    onStartAllPending: () -> Unit,
    onAnalyzeSelectedPage: (String) -> Unit,
    onPause: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val pages = document.pages
    val ocrDonePages = pages.filter { it.ocrResult != null }
    val analyzedCount = ocrDonePages.count { it.geminiAnalysis != null }
    val pendingGeminiCount = ocrDonePages.count { it.geminiAnalysis == null }
    val hasUsableKey = geminiKeys.any {
        it.isEnabled &&
            it.status != GeminiKeyStatus.INVALID &&
            it.status != GeminiKeyStatus.PERMISSION_DENIED &&
            it.status != GeminiKeyStatus.QUOTA_EXHAUSTED
    }
    val isThisDocRunning = geminiState.isRunning && geminiState.activeDocumentId == document.id
    val selectedPage = pages.getOrNull(selectedPageIndex)
    val selectedAnalysis = selectedPage?.geminiAnalysis

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gemini_analysis_workspace_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "تحليل Gemini البصري واللغوي",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "$analyzedCount / ${ocrDonePages.size} صفحة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = "النموذج النشط: ${usageStats.selectedModelId} • ترسل هذه المرحلة صورة الصفحة المصغّرة (≤1024px) مع مخرجات OCR إلى Google Gemini مباشرة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!hasUsableKey) {
                val ext = MuharrirTheme.extendedColors
                ScholarlyInfoBanner(
                    title = "يلزم مفتاح Gemini API مفعّل",
                    body = "أضف مفتاحاً واحداً على الأقل في الإعدادات (يُحفظ مشفراً عبر Android Keystore) لتفعيل التحليل البصري.",
                    icon = Icons.Rounded.WarningAmber,
                    containerColor = ext.warningContainer,
                    contentColor = ext.onWarningContainer,
                )
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("open_settings_for_gemini_button"),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("فتح إعدادات مفاتيح Gemini")
                }
            } else {
                if (isThisDocRunning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(MaterialTheme.shapes.extraSmall),
                    )
                    Text(
                        text = "جارٍ تحليل صفحة ${geminiState.currentPageNumber} — مكتمل: ${geminiState.analyzedPages} • متعذر: ${geminiState.failedPages}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إيقاف تحليل Gemini مؤقتاً")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onStartAllPending,
                            enabled = !geminiState.isRunning && ocrDonePages.isNotEmpty() && pendingGeminiCount > 0,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("run_gemini_analysis_button"),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    ocrDonePages.isEmpty() -> "أتمّ OCR أولاً"
                                    pendingGeminiCount == 0 -> "اكتمل التحليل"
                                    else -> "تحليل ($pendingGeminiCount) صفحة"
                                },
                            )
                        }

                        if (selectedPage != null && selectedPage.ocrResult != null) {
                            OutlinedButton(
                                onClick = { onAnalyzeSelectedPage(selectedPage.pageId) },
                                enabled = !geminiState.isRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("analyze_current_page_gemini_button"),
                            ) {
                                Text("تحليل صفحة ${selectedPage.originalPageNumber}")
                            }
                        }
                    }
                }
            }

            if (selectedAnalysis != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "مخرجات Gemini المهيكلة — صفحة ${selectedAnalysis.pageNumber} (${selectedAnalysis.items.size} ملاحظة)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (selectedAnalysis.items.isEmpty()) {
                    Text(
                        text = "لم يرصد Gemini مواضع تستوجب التصحيح أو الشك في هذه الصفحة (النص مطابق للصورة).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MuharrirTheme.extendedColors.success,
                    )
                } else {
                    selectedAnalysis.items.take(8).forEach { item ->
                        val reasonAr = GeminiReasonType.fromWireValue(item.reason_type)?.arabicLabel
                            ?: item.reason_type
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "OCR: «${item.ocr_text}» ← المقترح: «${item.suggested_text}»",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmiriFontFamily),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "$reasonAr • ثقة: ${(item.confidence * 100).toInt()}% • ${if (item.requires_review) "يحتاج مراجعة" else "مؤكد"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "الدليل: ${item.evidence}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

