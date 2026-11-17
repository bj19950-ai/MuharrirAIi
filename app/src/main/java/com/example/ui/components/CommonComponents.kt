package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.StageModuleAvailability
import com.example.ui.theme.MuharrirTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatFileSizeArabic(bytes: Long): String {
    if (bytes < 1024) return "$bytes بايت"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f ك.ب", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f م.ب", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f ج.ب", gb)
}

fun formatDateTimeArabic(millis: Long): String {
    val formatter = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US)
    return formatter.format(Date(millis))
}

@Composable
fun ImportStatusBadge(
    importStatus: ImportStatus,
    modifier: Modifier = Modifier,
) {
    val ext = MuharrirTheme.extendedColors
    val (containerColor, contentColor, icon) = when (importStatus) {
        ImportStatus.IMPORTING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.HourglassTop,
        )
        ImportStatus.IMPORTED,
        ImportStatus.READY_FOR_PROCESSING -> Triple(
            ext.successContainer,
            ext.onSuccessContainer,
            Icons.Rounded.CheckCircle,
        )
        ImportStatus.INTERRUPTED -> Triple(
            ext.warningContainer,
            ext.onWarningContainer,
            Icons.Rounded.PauseCircleOutline,
        )
        ImportStatus.FAILED,
        ImportStatus.DELETED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.ErrorOutline,
        )
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = importStatus.arabicLabel,
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = importStatus.arabicLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DocumentStatusBadge(
    status: DocumentStatus,
    modifier: Modifier = Modifier,
) {
    val ext = MuharrirTheme.extendedColors
    val (containerColor, contentColor, icon) = when (status) {
        DocumentStatus.NEW -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.Schedule,
        )
        DocumentStatus.PROCESSING,
        DocumentStatus.EXTRACTING_TEXT,
        DocumentStatus.ANALYZING_TEXT,
        DocumentStatus.GENERATING_WORD -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.HourglassTop,
        )
        DocumentStatus.NEEDS_REVIEW -> Triple(
            ext.warningContainer,
            ext.onWarningContainer,
            Icons.Rounded.ErrorOutline,
        )
        DocumentStatus.REVIEW_COMPLETED,
        DocumentStatus.COMPLETED -> Triple(
            ext.successContainer,
            ext.onSuccessContainer,
            Icons.Rounded.CheckCircle,
        )
        DocumentStatus.PAUSED -> Triple(
            ext.pausedContainer,
            ext.onPausedContainer,
            Icons.Rounded.PauseCircleOutline,
        )
        DocumentStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Rounded.ErrorOutline,
        )
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = status.arabicLabel,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = status.arabicLabel,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ModuleAvailabilityPill(
    availability: StageModuleAvailability,
    isCompletedLocally: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = MuharrirTheme.extendedColors
    val container: Color
    val content: Color
    val text: String

    when {
        isCompletedLocally -> {
            container = ext.successContainer
            content = ext.onSuccessContainer
            text = "مكتمل محلياً"
        }
        availability == StageModuleAvailability.READY_IN_MODULE_01 ||
            availability == StageModuleAvailability.READY_IN_MODULE_02 ||
            availability == StageModuleAvailability.READY_IN_MODULE_03 ||
            availability == StageModuleAvailability.READY_IN_MODULE_04 ||
            availability == StageModuleAvailability.READY_IN_MODULE_05 ||
            availability == StageModuleAvailability.READY_IN_MODULE_06 ||
            availability == StageModuleAvailability.READY_IN_MODULE_07 -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
            text = availability.badgeAr
        }
        else -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
            text = availability.badgeAr
        }
    }

    Surface(
        modifier = modifier,
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun OcrPageStatusBadge(
    ocrStatus: OcrPageStatus,
    modifier: Modifier = Modifier,
) {
    val ext = MuharrirTheme.extendedColors
    val (containerColor, contentColor) = when (ocrStatus) {
        OcrPageStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        OcrPageStatus.RUNNING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        OcrPageStatus.DONE -> ext.successContainer to ext.onSuccessContainer
        OcrPageStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = ocrStatus.arabicLabel,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ScholarlyInfoBanner(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}
