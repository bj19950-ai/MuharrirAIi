package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.ScholarlyInfoBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatFileSizeArabic
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.viewmodel.NewDocumentUiState

@Composable
fun NewDocumentScreen(
    state: NewDocumentUiState,
    onPdfPicked: (android.content.Context, android.net.Uri) -> Unit,
    onChangePreviewPage: (android.content.Context, Int) -> Unit,
    onClearSelection: () -> Unit,
    onCancelReplacementMode: () -> Unit,
    onStartDocumentTask: (android.content.Context) -> Unit,
    onCancelActiveImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onPdfPicked(context, uri)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("new_document_screen"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionHeader(
                    title = if (state.replaceDocumentId != null) {
                        "استبدال مستند PDF"
                    } else {
                        "إضافة مستند PDF جديد"
                    },
                    subtitle = "اختيار ملف PDF واستيراده إلى التخزين الخاص للتطبيق وفهرسة صفحاته دون حد أقصى لعدد الصفحات",
                    icon = Icons.Rounded.PictureAsPdf,
                )
            }

            if (state.replaceDocumentId != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "وضع استبدال المستند",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "سيُستبدل الملف: ${state.replaceDocumentName.orEmpty()} — تبقى النسخة الحالية محفوظة إن فشل التحقق من الملف الجديد.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = onCancelReplacementMode,
                                enabled = !state.isImporting,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("إلغاء الاستبدال")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.FileOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }

                        Text(
                            text = if (state.hasValidSelection) {
                                "ملف PDF جاهز للاستيراد والحفظ الدائم"
                            } else {
                                "اختر ملف PDF من جهازك"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "يُنسخ الملف بتدفق مباشر إلى التخزين الخاص للتطبيق ليظل متاحاً حتى لو نُقل الملف الأصلي أو حُذف من الجهاز، ودون حد أقصى لعدد الصفحات.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                enabled = !state.isImporting,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("select_pdf_button"),
                            ) {
                                Icon(
                                    imageVector = if (state.hasValidSelection) Icons.Rounded.SwapHoriz else Icons.Rounded.FileOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (state.hasValidSelection) "تغيير الملف" else "اختيار ملف PDF",
                                )
                            }

                            if ((state.hasValidSelection || state.validationErrorAr != null) && !state.isImporting) {
                                OutlinedButton(
                                    onClick = onClearSelection,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("remove_pdf_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "إزالة الملف المحدد",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إزالة")
                                }
                            }
                        }
                    }
                }
            }

            if (state.isInspecting) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                            Text(
                                text = "جارٍ فحص محتوى ملف PDF والتحقق من الترويسة وعدد الصفحات...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            if (state.isImporting) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_import_progress_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
                                Text(
                                    text = state.importPhaseLabelAr ?: "جارٍ الاستيراد...",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${state.importProgressPercent}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (state.importProgressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(MaterialTheme.shapes.extraSmall),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = onCancelActiveImport,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("cancel_import_button"),
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إيقاف الاستيراد")
                                }
                            }
                        }
                    }
                }
            }

            if (state.validationErrorAr != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_validation_error"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "خطأ تحقق",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Column {
                                Text(
                                    text = "تعذر قبول أو استيراد الملف",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = state.validationErrorAr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            if (state.hasValidSelection) {
                item {
                    SelectedFileMetadataCard(state = state)
                }

                item {
                    PdfPreviewCard(
                        state = state,
                        onPrevPage = { onChangePreviewPage(context, -1) },
                        onNextPage = { onChangePreviewPage(context, 1) },
                    )
                }

                item {
                    Button(
                        onClick = { onStartDocumentTask(context) },
                        enabled = !state.isImporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("start_document_task_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SaveAlt,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.replaceDocumentId != null) {
                                "استيراد وحفظ الملف البديل"
                            } else {
                                "استيراد وحفظ كمهمة مستند جديد"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            item {
                ScholarlyInfoBanner(
                    title = "التخزين الخاص الدائم والرفع المستقبلي",
                    body = "عند الضغط على الاستيراد يُحفظ المستند فعلياً في التخزين الخاص للتطبيق وتُفهرس صفحاته في قاعدة البيانات المحلية Room. الرفع إلى الخادم الخلفي سيُفعّل عند توفر خادم متصل في المراحل القادمة.",
                    icon = Icons.Rounded.Info,
                )
            }
        }
    }
}

@Composable
private fun SelectedFileMetadataCard(state: NewDocumentUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("selected_pdf_metadata"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = StatusSuccessContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = StatusSuccess,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "ملف PDF صالح",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusSuccess,
                        )
                    }
                }
                Text(
                    text = "بيانات الملف المحدد",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            MetadataRow(label = "اسم الملف", value = state.fileName.orEmpty())
            MetadataRow(
                label = "حجم الملف",
                value = formatFileSizeArabic(state.fileSizeBytes ?: 0L),
            )
            MetadataRow(
                label = "عدد الصفحات",
                value = state.pageCount?.let { "$it صفحة" } ?: "تعذر التحديد محلياً",
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
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
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PdfPreviewCard(
    state: NewDocumentUiState,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pdf_preview_card"),
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
                    text = "معاينة PDF المحلية",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                val total = state.pageCount
                if (total != null && total > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPrevPage,
                            enabled = state.currentPreviewPage > 0 && !state.isLoadingPreviewPage,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "الصفحة السابقة",
                            )
                        }
                        Text(
                            text = "صفحة ${state.currentPreviewPage + 1} من $total",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        IconButton(
                            onClick = onNextPage,
                            enabled = state.currentPreviewPage < total - 1 && !state.isLoadingPreviewPage,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = "الصفحة التالية",
                            )
                        }
                    }
                }
            }

            val bitmap = state.previewBitmap
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 440.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "معاينة صفحة ${state.currentPreviewPage + 1} من ${state.fileName}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                    if (state.isLoadingPreviewPage) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.previewNoteAr ?: "لا تتوفر معاينة مرئية لهذه الصفحة على هذا الجهاز.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
