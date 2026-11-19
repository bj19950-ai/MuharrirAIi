package com.example.review.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.formatDateTimeArabic
import com.example.ui.theme.MuharrirTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSummaryScreen(
    viewModel: ReviewViewModel,
    onNavigateBackToReview: () -> Unit,
    onNavigateToWorkspaceForExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler {
        if (uiState.showPartialApprovalDialog) {
            viewModel.dismissPartialApprovalDialog()
        } else {
            onNavigateBackToReview()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("review_summary_screen"),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "ملخص المراجعة والاعتماد",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBackToReview,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("summary_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة إلى المراجعة",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val ext = MuharrirTheme.extendedColors
                    // Document Header & Approval Status Card
                    val isApproved = uiState.reviewApprovedAt != null
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isApproved) ext.successContainer else MaterialTheme.colorScheme.surface,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isApproved) ext.success else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (isApproved) Icons.Default.Verified else Icons.Default.HourglassEmpty,
                                    contentDescription = null,
                                    tint = if (isApproved) ext.onSuccessContainer else MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = uiState.documentTitle.ifBlank { "المستند" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isApproved) ext.onSuccessContainer else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = if (isApproved) {
                                    "تم اعتماد المراجعة النهائية في ${formatDateTimeArabic(uiState.reviewApprovedAt!!)} — يمكنك الآن تصدير ملف Word النهائي."
                                } else {
                                    "راجع الإحصاءات أدناه ثم اضغط «اعتماد المراجعة» لتفعيل إنشاء وتصدير ملف Word (DOCX)."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isApproved) ext.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    AnimatedVisibility(visible = uiState.errorMessage != null) {
                        uiState.errorMessage?.let { errorMsg ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("summary_error_banner"),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = uiState.savedConfirmationVisible && uiState.confirmationMessage != null) {
                        uiState.confirmationMessage?.let { msg ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("summary_confirmation_banner"),
                                shape = RoundedCornerShape(12.dp),
                                color = ext.successContainer,
                                border = BorderStroke(1.dp, ext.success),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = ext.onSuccessContainer,
                                    )
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ext.onSuccessContainer,
                                    )
                                }
                            }
                        }
                    }

                    // Breakdown Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("summary_stats_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "تفصيل حالات المراجعة (${uiState.stats.total} موضعاً)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            SummaryStatRow(
                                label = "مقبول من اقتراح Gemini",
                                count = uiState.stats.approvedGemini,
                                icon = Icons.Default.CheckCircle,
                                tint = ext.success,
                            )
                            SummaryStatRow(
                                label = "معدّل يدوياً",
                                count = uiState.stats.manuallyEdited,
                                icon = Icons.Default.Edit,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            SummaryStatRow(
                                label = "متروك دون حسم (سيُميَّز بالأصفر والأحمر في Word)",
                                count = uiState.stats.unresolved,
                                icon = Icons.Default.HelpOutline,
                                tint = ext.warning,
                            )
                            SummaryStatRow(
                                label = "مستبعد (محذوف من المستند)",
                                count = uiState.stats.ignored,
                                icon = Icons.Default.ErrorOutline,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SummaryStatRow(
                                label = "متبقٍ قيد الانتظار / يتطلب إعادة مراجعة",
                                count = uiState.stats.remaining,
                                icon = Icons.Default.HourglassEmpty,
                                tint = if (uiState.stats.remaining > 0) MaterialTheme.colorScheme.error else ext.success,
                            )
                        }
                    }

                    if (uiState.stats.remaining > 0) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = ext.warningContainer,
                            border = BorderStroke(1.dp, ext.warning),
                        ) {
                            Text(
                                text = "يوجد ${uiState.stats.remaining} موضعاً لم يُحسم بعد. يمكنك إكمال مراجعتها أو اعتماد المراجعة جزئياً مع تمييزها بوضوح داخل ملف Word.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.onWarningContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    // Primary Actions
                    Button(
                        onClick = viewModel::onApproveReviewClicked,
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("approve_review_button"),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري حفظ الاعتماد...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isApproved) "إعادة تأكيد اعتماد المراجعة" else "اعتماد المراجعة")
                        }
                    }

                    if (isApproved) {
                        Button(
                            onClick = onNavigateToWorkspaceForExport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("go_to_export_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("الانتقال لتصدير ملف Word (DOCX)")
                        }
                    }

                    OutlinedButton(
                        onClick = onNavigateBackToReview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("back_to_review_button"),
                    ) {
                        Text("العودة إلى قائمة المراجعة")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (uiState.showPartialApprovalDialog) {
            AlertDialog(
                onDismissRequest = viewModel::dismissPartialApprovalDialog,
                title = { Text("تأكيد الاعتماد مع وجود مواضع متبقية") },
                text = {
                    Text(
                        "لا يزال هناك ${uiState.stats.remaining} موضعاً غير مراجع أو متطلباً لإعادة المراجعة. في حال الاعتماد الآن، ستُميَّز المواضع غير المحسومة باللون الأصفر والنص الأحمر داخل ملف Word النهائي لتتمكن من إكمالها لاحقاً.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::confirmPartialApproval,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("confirm_partial_approval_button"),
                    ) {
                        Text("اعتماد المراجعة")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::dismissPartialApprovalDialog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cancel_partial_approval_button"),
                    ) {
                        Text("العودة للمراجعة")
                    }
                },
            )
        }
    }
}

@Composable
private fun SummaryStatRow(
    label: String,
    count: Int,
    icon: ImageVector,
    tint: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}
