package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.example.domain.model.DocumentRecord
import com.example.domain.model.ReviewStatus
import com.example.ui.components.ScholarlyInfoBanner
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AmiriFontFamily

@Composable
fun ReviewWorkspaceScreen(
    document: DocumentRecord?,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
    onRequestPreviewPage: (android.content.Context, DocumentRecord, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var draftApprovedText by remember { mutableStateOf("") }

    LaunchedEffect(document?.id) {
        if (document != null && previewBitmap == null) {
            onRequestPreviewPage(context, document, previewPageIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("review_workspace_screen"),
    ) {
        val isWide = maxWidth >= 820.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1120.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionHeader(
                    title = "مساحة المراجعة البشرية التفاعلية",
                    subtitle = document?.let { "المستند المرتبط: ${it.fileName}" }
                        ?: "تجهيز هيكل المراجعة البشرية المعتمد على مقارنة PDF مع OCR و Gemini",
                    icon = Icons.Rounded.RateReview,
                )
            }

            item {
                ReviewNavigationHeaderBar()
            }

            item {
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            OriginalPageViewerPanel(
                                document = document,
                                previewBitmap = previewBitmap,
                                previewPageIndex = previewPageIndex,
                            )
                            EnlargedRegionCropPanel()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            OcrResultPanel()
                            GeminiSuggestionPanel()
                            UserApprovedTextPanel(
                                draftText = draftApprovedText,
                                onDraftChange = { draftApprovedText = it },
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OriginalPageViewerPanel(
                            document = document,
                            previewBitmap = previewBitmap,
                            previewPageIndex = previewPageIndex,
                        )
                        EnlargedRegionCropPanel()
                        OcrResultPanel()
                        GeminiSuggestionPanel()
                        UserApprovedTextPanel(
                            draftText = draftApprovedText,
                            onDraftChange = { draftApprovedText = it },
                        )
                    }
                }
            }

            item {
                ScholarlyInfoBanner(
                    title = "ضمانات الأمانة النصية (المرحلة 06)",
                    body = "لن يُعتمد أي تصحيح تلقائياً بصمت، ولن يُستبدل أي نص وافق عليه المستخدم. ستعرض هذه المساحة في المرحلة 06 المواضع غير المؤكدة فقط مع الدليل البصري من صفحة PDF الأصلية.",
                    icon = Icons.Rounded.Info,
                )
            }
        }
    }
}

@Composable
private fun ReviewNavigationHeaderBar() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "التنقل وحالة المراجعة",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "حالة العنصر: ${ReviewStatus.UNRESOLVED.arabicLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        text = "0 / 0 عنصر مراجعة",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("review_prev_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "العنصر السابق",
                    )
                    Text("السابق")
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("review_next_button"),
                ) {
                    Text("التالي")
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = "العنصر التالي",
                    )
                }
            }
        }
    }
}

@Composable
private fun OriginalPageViewerPanel(
    document: DocumentRecord?,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_original_page_panel"),
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
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "عارض صفحة PDF الأصلية",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (document != null && previewBitmap != null) {
                Text(
                    text = "معاينة الصفحة ${previewPageIndex + 1} من الملف المحدد محلياً:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "صفحة PDF الأصلية للمراجعة",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 340.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ستظهر هنا صفحة PDF الأصلية مع إبراز إحداثيات الكلمة أو الفقرة محل المراجعة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnlargedRegionCropPanel() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_enlarged_crop_panel"),
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
                Icon(
                    imageVector = Icons.Rounded.Crop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "منطقة النص المكبّرة (قصّ من الأصل)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "سيُعرض هنا القصّ المكبّر من صورة الصفحة الأصلية للموضع غير المؤكد (المرحلة 06).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun OcrResultPanel() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_ocr_result_panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.TextSnippet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "نتيجة OCR الأصلية",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
            ) {
                Text(
                    text = "لم يتم تشغيل محرك OCR بعد. لن تُعرض أي نتائج تعرّف وهمية في المرحلة 01.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmiriFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GeminiSuggestionPanel() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_gemini_panel"),
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
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "اقتراح Gemini وتفسير التصحيح",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "الاقتراح الموضعي: غير متاح بعد (المرحلة 04–05)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "تفسير التصحيح: سيوضح Gemini سبب الاقتراح بالاستناد إلى الدليل البصري في الصفحة الأصلية والسياق دون إعادة صياغة المستند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UserApprovedTextPanel(
    draftText: String,
    onDraftChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_user_approval_panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "النص المعتمد من المستخدم",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftChange,
                enabled = false,
                label = { Text("حقل اعتماد أو تعديل النص (يُفعّل عند وجود عناصر مراجعة)") },
                placeholder = { Text("سيُتاح التحرير المباشر هنا لكل موضع غير مؤكد في المرحلة 06") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_approved_text_field"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("review_accept_button"),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("قبول الاقتراح")
                }

                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("review_leave_unresolved_button"),
                ) {
                    Icon(Icons.Rounded.HourglassEmpty, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إبقاء دون حسم")
                }
            }
        }
    }
}
