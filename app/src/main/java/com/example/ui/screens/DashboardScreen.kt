package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.DocumentRecord
import com.example.domain.model.ImportStatus
import com.example.ui.components.DocumentStatusBadge
import com.example.ui.components.ImportStatusBadge
import com.example.ui.components.ScholarlyInfoBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.formatDateTimeArabic
import com.example.ui.components.formatFileSizeArabic
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.MuharrirTheme
import com.example.ui.viewmodel.DashboardFilter
import com.example.ui.viewmodel.DashboardUiState

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCreateNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectFilter: (DashboardFilter) -> Unit,
    onOpenDocument: (DocumentRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
    ) {
        val isWide = maxWidth >= 720.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1040.dp)
                .align(Alignment.TopCenter)
                .testTag("dashboard_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HeroHeaderCard(
                    onCreateNewTask = onCreateNewTask,
                    onOpenSettings = onOpenSettings,
                )
            }

            item {
                StatusOverviewGrid(
                    state = state,
                    isWide = isWide,
                )
            }

            item {
                SectionHeader(
                    title = "المستندات الأخيرة",
                    subtitle = if (state.totalCount == 0) {
                        "لا توجد مهام تحويل مسجلة في هذه الجلسة بعد"
                    } else {
                        "إجمالي المستندات المحددة: ${state.totalCount}"
                    },
                    icon = Icons.Rounded.Description,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DashboardFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.selectedFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            label = {
                                Text(
                                    text = filter.arabicLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("filter_chip_${filter.name}"),
                        )
                    }
                }
            }

            if (state.filteredDocuments.isEmpty()) {
                item {
                    DashboardEmptyState(
                        hasAnyDocuments = state.totalCount > 0,
                        onCreateNewTask = onCreateNewTask,
                    )
                }
            } else {
                items(
                    items = state.filteredDocuments,
                    key = { it.id },
                ) { document ->
                    DocumentTaskCard(
                        document = document,
                        onClick = { onOpenDocument(document) },
                    )
                }
            }

            item {
                ScholarlyInfoBanner(
                    title = "المحرّر — حفظ أمين للنص الأصلي",
                    body = "يحفظ التطبيق ملفات PDF داخل التخزين الخاص، وينفّذ استخراج النص محلياً (OCR) والتحليل البصري عبر Gemini، مع مراجعة تفاعلية وتصدير Word عربي متكامل دون اختصار أو إعادة صياغة.",
                    icon = Icons.Rounded.Info,
                )
            }
        }
    }
}

@Composable
private fun HeroHeaderCard(
    onCreateNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ext = MuharrirTheme.extendedColors
    Surface(
        shape = MaterialTheme.shapes.large,
        color = ext.headerGradientStart,
        border = BorderStroke(1.dp, ext.headerOutline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_hero_header"),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ext.headerGradientStart,
                            ext.headerGradientEnd,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, ext.headerOutline),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_muharrir_icon_fg),
                                contentDescription = "شعار المحرّر",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "المحرّر",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = AmiriFontFamily,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = ext.onHeader,
                                modifier = Modifier.testTag("dashboard_app_title"),
                            )
                            Text(
                                text = "تحويل أمين لمستندات PDF العربية المصوّرة إلى ملفات Word قابلة للتعديل",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ext.onHeaderMuted,
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("dashboard_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "فتح الإعدادات",
                            tint = ext.onHeader,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onCreateNewTask,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("new_task_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "مهمة تحويل جديدة",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        border = BorderStroke(1.dp, ext.headerOutline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.onHeader),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("dashboard_settings_chip"),
                    ) {
                        Text(
                            text = "الإعدادات",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOverviewGrid(
    state: DashboardUiState,
    isWide: Boolean,
) {
    val cards = listOf(
        StatusMetricData(
            label = "قيد المعالجة",
            count = state.processingCount,
            icon = Icons.Rounded.HourglassEmpty,
            accentColor = MaterialTheme.colorScheme.secondary,
        ),
        StatusMetricData(
            label = "بانتظار المراجعة",
            count = state.awaitingReviewCount,
            icon = Icons.Rounded.RateReview,
            accentColor = MaterialTheme.colorScheme.tertiary,
        ),
        StatusMetricData(
            label = "المكتملة",
            count = state.completedCount,
            icon = Icons.Rounded.CheckCircleOutline,
            accentColor = MaterialTheme.colorScheme.primary,
        ),
        StatusMetricData(
            label = "متوقفة أو فاشلة",
            count = state.failedOrStoppedCount,
            icon = Icons.Rounded.ErrorOutline,
            accentColor = MaterialTheme.colorScheme.error,
        ),
    )

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cards.forEach { metric ->
                StatusMetricCard(
                    data = metric,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusMetricCard(data = cards[0], modifier = Modifier.weight(1f))
                StatusMetricCard(data = cards[1], modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusMetricCard(data = cards[2], modifier = Modifier.weight(1f))
                StatusMetricCard(data = cards[3], modifier = Modifier.weight(1f))
            }
        }
    }
}

private data class StatusMetricData(
    val label: String,
    val count: Int,
    val icon: ImageVector,
    val accentColor: Color,
)

@Composable
private fun StatusMetricCard(
    data: StatusMetricData,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.count.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = data.accentColor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = data.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = data.icon,
                contentDescription = data.label,
                tint = data.accentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun DashboardEmptyState(
    hasAnyDocuments: Boolean,
    onCreateNewTask: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_empty_state"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_academic_desk),
                contentDescription = "مكتب أكاديمي لمراجعة المخطوطات والمستندات",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = if (hasAnyDocuments) {
                    "لا توجد مستندات ضمن هذا التصنيف"
                } else {
                    "ابدأ بإضافة أول مستند PDF"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (hasAnyDocuments) {
                    "جرّب اختيار تصنيف آخر لعرض المستندات المسجلة، أو أضف ملف PDF جديداً."
                } else {
                    "لم يتم اختيار أي ملف PDF بعد. لا يعرض التطبيق إحصاءات أو مستندات وهمية؛ اختر ملفاً حقيقياً للتحقق من بياناته وفتح مساحة العمل الخاصة به."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onCreateNewTask,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("empty_state_new_task_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("اختيار ملف PDF جديد")
            }
        }
    }
}

@Composable
private fun DocumentTaskCard(
    document: DocumentRecord,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("document_card_${document.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pagesLabel = document.pageCount?.let { "$it صفحة" } ?: "عدد الصفحات غير محدد"
                Text(
                    text = "${formatFileSizeArabic(document.fileSizeBytes)}  •  $pagesLabel  •  ${formatDateTimeArabic(document.uploadDateMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImportStatusBadge(importStatus = document.importStatus)
                    DocumentStatusBadge(status = document.status)
                }
                if (document.errorMessageAr != null &&
                    (document.importStatus == ImportStatus.INTERRUPTED || document.importStatus == ImportStatus.FAILED)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = document.errorMessageAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "فتح مساحة عمل المستند",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
