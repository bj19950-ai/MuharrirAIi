package com.example.review.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus
import com.example.review.ui.components.EnlargedCropCard
import com.example.review.ui.components.ZoomablePageViewer
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.MuharrirTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onNavigateBack: () -> Unit,
    onOpenSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler {
        when {
            uiState.showBulkApproveDialog -> viewModel.dismissBulkApproveDialog()
            uiState.reopenDialog != null -> viewModel.dismissReopenDialog()
            else -> onNavigateBack()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.documentTitle.ifBlank { "مراجعة المستند" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "المتبقي: ${uiState.stats.remaining} • مستبعد: ${uiState.stats.ignored} • الإجمالي: ${uiState.stats.total}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("review_stats_bar"),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("review_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة",
                            )
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = onOpenSummary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("review_summary_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Assessment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ملخص المراجعة")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            bottomBar = {
                ReviewBottomNavigationBar(
                    uiState = uiState,
                    onPrevious = viewModel::goToPrevious,
                    onNext = viewModel::goToNext,
                    onSelectFilter = viewModel::selectFilter,
                    onToggleIncludeAgreement = viewModel::toggleIncludeAgreement,
                    onToggleIgnoreMinorDifferences = viewModel::toggleIgnoreMinorDifferences,
                    onPageJumpChanged = viewModel::onPageJumpInputChanged,
                    onJumpToPage = viewModel::jumpToPage,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ReviewSmartActionsBar(
                    pendingGeminiApproveCount = uiState.pendingGeminiApproveCount,
                    isSaving = uiState.isSaving,
                    autoAcceptHighConfidence = uiState.autoAcceptHighConfidence,
                    onBulkApproveClick = viewModel::requestBulkApproveGemini,
                    onToggleAutoAcceptHighConfidence = viewModel::toggleAutoAcceptHighConfidence,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text("جاري تحميل قائمة المراجعة...")
                            }
                        }
                    }

                    uiState.currentItem == null -> {
                        EmptyReviewQueueView(
                            hasAnyItems = uiState.allItems.isNotEmpty(),
                            onResetFilter = { viewModel.selectFilter(ReviewFilter.ALL) },
                            onOpenSummary = onOpenSummary,
                            onNavigateBack = onNavigateBack,
                        )
                    }

                    else -> {
                        val currentItem = uiState.currentItem!!
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val isWideScreen = maxWidth >= 720.dp
                            if (isWideScreen) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    ReviewCardPane(
                                        uiState = uiState,
                                        item = currentItem,
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    )
                                    ZoomablePageViewer(
                                        pageBitmap = uiState.pageBitmap,
                                        bbox = currentItem.bbox,
                                        itemId = currentItem.id,
                                        isLoading = uiState.isBitmapLoading,
                                        isError = uiState.bitmapLoadError,
                                        modifier = Modifier
                                            .weight(1.15f)
                                            .fillMaxHeight(),
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ZoomablePageViewer(
                                        pageBitmap = uiState.pageBitmap,
                                        bbox = currentItem.bbox,
                                        itemId = currentItem.id,
                                        isLoading = uiState.isBitmapLoading,
                                        isError = uiState.bitmapLoadError,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.42f),
                                    )
                                    ReviewCardPane(
                                        uiState = uiState,
                                        item = currentItem,
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.58f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        if (uiState.showBulkApproveDialog) {
            AlertDialog(
                onDismissRequest = viewModel::dismissBulkApproveDialog,
                title = { Text("قبول كل اقتراحات Gemini") },
                text = {
                    Text(
                        "سيتم قبول جميع اقتراحات Gemini المتبقية (${uiState.pendingGeminiApproveCount} موضع). هل أنت متأكد؟",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmBulkApproveGemini,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("confirm_bulk_approve_button"),
                    ) {
                        Text("نعم، قبول الكل")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::dismissBulkApproveDialog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cancel_bulk_approve_button"),
                    ) {
                        Text("إلغاء")
                    }
                },
            )
        }

        uiState.reopenDialog?.let { request ->
            val dialogMessage = if (request.reason == ReopenReason.STALE_REANALYSIS) {
                "هل تريد إعادة هذا الموضع إلى حالة قيد المراجعة لمراجعته من جديد؟"
            } else {
                "هل تريد إعادة فتح هذا الموضع للمراجعة؟ سيعود إلى حالة قيد الانتظار دون حذف السجل السابق."
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissReopenDialog,
                title = { Text("تأكيد إعادة المراجعة") },
                text = { Text(dialogMessage) },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmReopenItem,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("confirm_reopen_button"),
                    ) {
                        Text("نعم، إعادة للمراجعة")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::dismissReopenDialog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cancel_reopen_button"),
                    ) {
                        Text("إلغاء")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCardPane(
    uiState: ReviewUiState,
    item: ReviewItemModel,
    viewModel: ReviewViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EnlargedCropCard(croppedBitmap = uiState.enlargedCropBitmap)

        AnimatedVisibility(visible = uiState.errorMessage != null) {
            uiState.errorMessage?.let { errorMsg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_error_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = viewModel::dismissFeedbackBanner,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق التنبيه",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        val ext = MuharrirTheme.extendedColors

        AnimatedVisibility(visible = uiState.savedConfirmationVisible && uiState.confirmationMessage != null) {
            uiState.confirmationMessage?.let { confirmMsg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_confirmation_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = ext.successContainer,
                    border = BorderStroke(1.dp, ext.success),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ext.onSuccessContainer,
                        )
                        Text(
                            text = confirmMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onSuccessContainer,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = viewModel::dismissFeedbackBanner,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق التأكيد",
                                tint = ext.onSuccessContainer,
                            )
                        }
                    }
                }
            }
        }

        if (item.isStale || item.reviewStatus == ReviewStatus.STALE) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stale_warning_banner"),
                shape = RoundedCornerShape(12.dp),
                color = ext.warningContainer,
                border = BorderStroke(1.dp, ext.warning),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = ext.onWarningContainer,
                        )
                        Text(
                            text = "تغيّر النص الأصلي بعد إعادة التحليل — يتطلب إعادة مراجعة",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ext.onWarningContainer,
                        )
                    }
                    item.staleNoteAr?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onWarningContainer,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestReopenItem(ReopenReason.STALE_REANALYSIS) },
                        enabled = !uiState.isSaving,
                        border = BorderStroke(1.dp, ext.warning),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ext.onWarningContainer,
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("reopen_stale_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة فتح للمراجعة")
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review_item_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text("صفحة ${item.pageNumber}") },
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(ReviewArabicFormatter.categoryLabel(item.category)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = when (item.category) {
                                DiscrepancyCategory.A -> ext.successContainer
                                DiscrepancyCategory.B -> MaterialTheme.colorScheme.errorContainer
                                DiscrepancyCategory.C, DiscrepancyCategory.E -> ext.warningContainer
                                DiscrepancyCategory.D -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            labelColor = when (item.category) {
                                DiscrepancyCategory.A -> ext.onSuccessContainer
                                DiscrepancyCategory.B -> MaterialTheme.colorScheme.onErrorContainer
                                DiscrepancyCategory.C, DiscrepancyCategory.E -> ext.onWarningContainer
                                DiscrepancyCategory.D -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(ReviewArabicFormatter.statusLabel(item.reviewStatus, item.isStale)) },
                    )
                }

                item.resolvedAt?.let { ts ->
                    Text(
                        text = ReviewArabicFormatter.formatResolvedTimestamp(ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // OCR Original Output
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "قراءة OCR الأصلية",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = item.ocrText.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AmiriFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Gemini Suggestion + Collapsible Evidence
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "اقتراح Gemini",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = item.suggestedText?.takeIf { it.isNotBlank() } ?: "لا يوجد اقتراح نصي لهذا الموضع",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AmiriFontFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    item.evidence?.takeIf { it.isNotBlank() }?.let { evidence ->
                        var isEvidenceExpanded by remember(item.id) { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { isEvidenceExpanded = !isEvidenceExpanded }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("evidence_toggle_row"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "السبب (اضغط للعرض)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = if (isEvidenceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isEvidenceExpanded) "إخفاء السبب" else "عرض السبب",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                AnimatedVisibility(visible = isEvidenceExpanded) {
                                    Text(
                                        text = evidence,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                            .testTag("evidence_content_text"),
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Tesseract: ${ReviewArabicFormatter.formatConfidence(item.ocrConfidence)} | Gemini: ${ReviewArabicFormatter.formatConfidence(item.geminiConfidence)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("combined_confidence_label"),
                    )
                }

                // Existing user decision preview (when resolved & not editing)
                if (!uiState.isManualEditEnabled && !item.userText.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "النص المعتمد حالياً",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ext.success,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ext.successContainer)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = item.userText,
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = AmiriFontFamily),
                                fontWeight = FontWeight.SemiBold,
                                color = ext.onSuccessContainer,
                            )
                        }
                    }
                }

                // Manual Edit Field
                AnimatedVisibility(visible = uiState.isManualEditEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = uiState.editedText,
                            onValueChange = viewModel::onEditedTextChanged,
                            label = { Text("النص المعتمد يدوياً") },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = AmiriFontFamily),
                            enabled = !uiState.isSaving,
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_edit_text_field"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = viewModel::saveManualEdit,
                                enabled = !uiState.isSaving && uiState.editedText.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("save_manual_edit_button"),
                            ) {
                                Text("حفظ التعديل")
                            }
                            OutlinedButton(
                                onClick = viewModel::cancelManualEdit,
                                enabled = !uiState.isSaving,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("cancel_manual_edit_button"),
                            ) {
                                Text("إلغاء")
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Three Primary Review Actions
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::approveGeminiSuggestion,
                        enabled = !uiState.isSaving && !item.suggestedText.isNullOrBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("approve_gemini_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("قبول اقتراح Gemini")
                    }

                    FilledTonalButton(
                        onClick = viewModel::enableManualEdit,
                        enabled = !uiState.isSaving && !uiState.isManualEditEnabled,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("manual_edit_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تعديل يدوي")
                    }

                    OutlinedButton(
                        onClick = viewModel::markUnresolved,
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mark_unresolved_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ترك دون حسم")
                    }

                    OutlinedButton(
                        onClick = viewModel::ignoreItem,
                        enabled = !uiState.isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ignore_item_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استبعاد (حذف من المستند)")
                    }
                }

                if (!item.isStale &&
                    item.reviewStatus in setOf(
                        ReviewStatus.APPROVED_GEMINI,
                        ReviewStatus.MANUALLY_EDITED,
                        ReviewStatus.UNRESOLVED,
                        ReviewStatus.IGNORED,
                    )
                ) {
                    TextButton(
                        onClick = { viewModel.requestReopenItem(ReopenReason.MANUAL_REOPEN) },
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("manual_reopen_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة فتح هذا الموضع للمراجعة")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ReviewBottomNavigationBar(
    uiState: ReviewUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectFilter: (ReviewFilter) -> Unit,
    onToggleIncludeAgreement: () -> Unit,
    onToggleIgnoreMinorDifferences: () -> Unit,
    onPageJumpChanged: (String) -> Unit,
    onJumpToPage: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReviewFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = uiState.selectedFilter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.labelAr, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review_filter_${filter.name.lowercase()}"),
                    )
                }
                if (uiState.selectedFilter == ReviewFilter.ALL) {
                    FilterChip(
                        selected = uiState.includeAgreementInAll,
                        onClick = onToggleIncludeAgreement,
                        label = { Text("تضمين المتطابق", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("toggle_include_agreement_chip"),
                    )
                }
                if (uiState.selectedFilter in setOf(
                        ReviewFilter.ALL,
                        ReviewFilter.DISCREPANCY_ONLY,
                        ReviewFilter.PENDING_ONLY,
                    )
                ) {
                    FilterChip(
                        selected = uiState.ignoreMinorDifferences,
                        onClick = onToggleIgnoreMinorDifferences,
                        label = { Text("تجاهل فروقات الهمزات", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("toggle_ignore_minor_differences_chip"),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = uiState.canGoPrevious,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("review_prev_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "السابق",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("السابق")
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${uiState.displayPosition} / ${uiState.totalFilteredCount}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    uiState.currentItem?.let { current ->
                        Text(
                            text = "صفحة ${current.pageNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onNext,
                    enabled = uiState.canGoNext,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("review_next_button"),
                ) {
                    Text("التالي")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "التالي",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.pageJumpInput,
                    onValueChange = onPageJumpChanged,
                    placeholder = { Text("رقم الصفحة", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onJumpToPage() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("page_jump_input"),
                )
                FilledTonalButton(
                    onClick = onJumpToPage,
                    enabled = uiState.pageJumpInput.isNotBlank(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("page_jump_button"),
                ) {
                    Text("انتقال")
                }
            }

            uiState.pageJumpError?.let { jumpError ->
                Text(
                    text = jumpError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("page_jump_error"),
                )
            }
        }
    }
}

@Composable
private fun EmptyReviewQueueView(
    hasAnyItems: Boolean,
    onResetFilter: () -> Unit,
    onOpenSummary: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("empty_review_queue_view"),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (hasAnyItems) MaterialTheme.colorScheme.primary else MuharrirTheme.extendedColors.success,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = if (hasAnyItems) {
                        "لا توجد مواضع مطابقة للفلتر المحدد"
                    } else {
                        "لا توجد مواضع تحتاج إلى مراجعة حالياً"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (hasAnyItems) {
                        "يمكنك إعادة ضبط الفلتر إلى «الكل» أو فتح ملخص المراجعة لاعتماد النتائج."
                    } else {
                        "تظهر هنا المواضع التي رصدتها مقارنة OCR مع Gemini. إذا اكتمل التحليل دون اختلافات، يمكنك الانتقال مباشرة إلى ملخص المراجعة لاعتماد المستند."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasAnyItems) {
                        Button(
                            onClick = onResetFilter,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reset_review_filter_button"),
                        ) {
                            Text("عرض الكل")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Text("العودة لمساحة العمل")
                        }
                    }
                    FilledTonalButton(
                        onClick = onOpenSummary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("empty_open_summary_button"),
                    ) {
                        Text("ملخص المراجعة")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewSmartActionsBar(
    pendingGeminiApproveCount: Int,
    isSaving: Boolean,
    autoAcceptHighConfidence: Boolean,
    onBulkApproveClick: () -> Unit,
    onToggleAutoAcceptHighConfidence: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_smart_actions_bar"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledTonalButton(
                onClick = onBulkApproveClick,
                enabled = !isSaving && pendingGeminiApproveCount > 0,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("bulk_approve_gemini_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (pendingGeminiApproveCount > 0) {
                        "قبول كل اقتراحات Gemini ($pendingGeminiApproveCount)"
                    } else {
                        "قبول كل اقتراحات Gemini"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = "القبول التلقائي لاقتراحات Gemini عالية الثقة",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = autoAcceptHighConfidence,
                    onCheckedChange = onToggleAutoAcceptHighConfidence,
                    enabled = !isSaving,
                    modifier = Modifier.testTag("review_auto_accept_high_confidence_switch"),
                )
            }
        }
    }
}
