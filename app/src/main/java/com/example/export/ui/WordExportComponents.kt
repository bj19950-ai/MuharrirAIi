package com.example.export.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ExportMode
import com.example.domain.model.ExportedDocument
import com.example.export.model.WordExportMode
import com.example.export.model.toWordExportMode
import com.example.ui.components.formatDateTimeArabic
import com.example.ui.theme.MuharrirTheme
import java.io.File

@Composable
fun WordExportSection(
    documentId: String,
    documentTitle: String,
    isReviewApproved: Boolean,
    preferredExportMode: ExportMode,
    exportedDocument: ExportedDocument?,
    exportViewModel: WordExportViewModel,
    onOpenReviewSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by exportViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(exportViewModel) {
        exportViewModel.events.collect { event ->
            when (event) {
                is WordExportEvent.ShareDocxFile -> {
                    WordShareLauncher.shareDocx(
                        context = context,
                        file = event.file,
                        documentTitle = event.documentTitle,
                    ).onFailure {
                        exportViewModel.reportShareError(
                            "تم حفظ ملف Word بنجاح، لكن تعذر فتح نافذة المشاركة على هذا الجهاز.",
                        )
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = exportViewModel::openModeChooser,
                enabled = isReviewApproved && !uiState.isExporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("export_to_word_button"),
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("جاري إنشاء ملف Word...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (exportedDocument != null) {
                            "إعادة إنشاء وتصدير ملف Word"
                        } else {
                            "تصدير إلى Word"
                        },
                    )
                }
            }

            if (!isReviewApproved) {
                Text(
                    text = "يتفعّل التصدير إلى Word بعد اعتماد المراجعة من شاشة ملخص المراجعة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("export_disabled_hint"),
                )
                OutlinedButton(
                    onClick = onOpenReviewSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("open_summary_for_approval_button"),
                ) {
                    Text("فتح ملخص المراجعة للاعتماد")
                }
            }

            val ext = MuharrirTheme.extendedColors
            if (exportedDocument != null) {
                val fileName = File(exportedDocument.docxFileRef).name
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exported_docx_summary_card"),
                    shape = RoundedCornerShape(10.dp),
                    color = ext.successContainer,
                    border = BorderStroke(1.dp, ext.success),
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
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = ext.onSuccessContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "آخر ملف Word مُنشأ: $fileName",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ext.onSuccessContainer,
                            )
                        }
                        Text(
                            text = "النمط: ${exportedDocument.exportMode.arabicTitle} • ${formatDateTimeArabic(exportedDocument.createdAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onSuccessContainer,
                        )
                        OutlinedButton(
                            onClick = {
                                exportViewModel.shareExistingDocx(
                                    filePath = exportedDocument.docxFileRef,
                                    documentTitle = documentTitle,
                                )
                            },
                            border = BorderStroke(1.dp, ext.success),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ext.onSuccessContainer,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("share_existing_docx_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مشاركة أو فتح ملف Word")
                        }
                    }
                }
            }

            uiState.statusMessage?.let { statusMsg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_status_banner"),
                    shape = RoundedCornerShape(10.dp),
                    color = ext.successContainer,
                    border = BorderStroke(1.dp, ext.success),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onSuccessContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = exportViewModel::dismissStatusMessage) {
                            Text("إغلاق", color = ext.onSuccessContainer)
                        }
                    }
                }
            }

            uiState.errorMessage?.let { errorMsg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_error_banner"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = exportViewModel::dismissError) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        }

        if (uiState.isModeDialogVisible) {
            WordExportModeDialog(
                recommendedMode = preferredExportMode.toWordExportMode(),
                onSelectMode = { mode ->
                    exportViewModel.exportDocument(
                        context = context,
                        documentId = documentId,
                        mode = mode,
                    )
                },
                onDismiss = exportViewModel::dismissModeChooser,
            )
        }
    }
}

@Composable
fun WordExportModeDialog(
    recommendedMode: WordExportMode = WordExportMode.MATCH_ORIGINAL,
    onSelectMode: (WordExportMode) -> Unit,
    onDismiss: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("اختر نمط تنسيق Word") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WordExportMode.entries.forEach { mode ->
                        val isPreferred = mode == recommendedMode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectMode(mode) }
                                .minimumInteractiveComponentSize()
                                .testTag("export_mode_${mode.name.lowercase()}"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPreferred) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = if (isPreferred) 1.5.dp else 1.dp,
                                color = if (isPreferred) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = mode.titleAr,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (isPreferred) {
                                        Text(
                                            text = "الافتراضي",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Text(
                                    text = mode.descriptionAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("cancel_export_mode_dialog"),
                ) {
                    Text("إلغاء")
                }
            },
        )
    }
}
