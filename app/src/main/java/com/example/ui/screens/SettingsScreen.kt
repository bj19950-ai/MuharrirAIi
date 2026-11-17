package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Scanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiModelConfig
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.OcrEngineType
import com.example.domain.model.ProcessingPreferences
import com.example.ui.components.ScholarlyInfoBanner
import com.example.ui.components.SectionHeader
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessContainer
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.StatusWarningContainer

@Composable
fun SettingsScreen(
    preferences: ProcessingPreferences,
    geminiKeys: List<GeminiKeyRecord>,
    geminiUsageStats: GeminiUsageStats,
    testingKeyId: String?,
    noticeMessageAr: String?,
    onDismissNotice: () -> Unit,
    onAddGeminiKey: (label: String, rawKey: String) -> Unit,
    onTestGeminiKey: (keyId: String) -> Unit,
    onToggleGeminiKey: (keyId: String, enabled: Boolean) -> Unit,
    onRemoveGeminiKey: (keyId: String) -> Unit,
    onSelectGeminiModel: (modelId: String) -> Unit,
    onSelectOcrEngine: (OcrEngineType) -> Unit,
    onSelectExportMode: (ExportMode) -> Unit,
    onToggleHighlightUnresolved: (Boolean) -> Unit,
    onToggleAutoSave: (Boolean) -> Unit,
    onToggleResumeAfterInterruption: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddKeyDialog by remember { mutableStateOf(false) }

    if (showAddKeyDialog) {
        AddGeminiApiKeyDialog(
            onDismiss = { showAddKeyDialog = false },
            onConfirm = { label, rawKey ->
                onAddGeminiKey(label, rawKey)
                showAddKeyDialog = false
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (noticeMessageAr != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = noticeMessageAr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onDismissNotice) {
                                    Text("إغلاق التنبيه")
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "مفاتيح Gemini API (مشفّرة محلياً)",
                    subtitle = "تُحفظ المفاتيح داخل EncryptedSharedPreferences وAndroid Keystore وتُرسل مباشرة إلى Google Gemini API فقط.",
                    icon = Icons.Rounded.Key,
                )
            }

            item {
                GeminiApiKeysCard(
                    geminiKeys = geminiKeys,
                    testingKeyId = testingKeyId,
                    onAddClick = { showAddKeyDialog = true },
                    onTestClick = onTestGeminiKey,
                    onToggleClick = onToggleGeminiKey,
                    onRemoveClick = onRemoveGeminiKey,
                )
            }

            item {
                SectionHeader(
                    title = "نموذج Gemini ومراقبة الاستخدام المحلي",
                    subtitle = "اختيار نموذج Flash المناسب ومتابعة حالة وآخر طلبات التطبيق محلياً",
                    icon = Icons.Rounded.BarChart,
                )
            }

            item {
                GeminiModelAndUsageCard(
                    usageStats = geminiUsageStats,
                    onSelectModel = onSelectGeminiModel,
                )
            }

            item {
                SectionHeader(
                    title = "إعدادات محرك OCR على الجهاز",
                    subtitle = "تنفيذ التعرف الضوئي محلياً عبر OcrEngineAdapter دون خادم خارجي (المرحلة 03)",
                    icon = Icons.Rounded.Scanner,
                )
            }

            item {
                OcrConfigurationCard(
                    selectedEngine = preferences.preferredOcrEngine,
                    onSelectEngine = onSelectOcrEngine,
                )
            }

            item {
                SectionHeader(
                    title = "تفضيلات تنسيق Word (DOCX)",
                    subtitle = "إعدادات التصدير الداعم للعربية والاتجاه RTL (المرحلة 07)",
                    icon = Icons.Rounded.Description,
                )
            }

            item {
                WordFormattingCard(
                    selectedMode = preferences.preferredExportMode,
                    highlightUnresolved = preferences.highlightUnresolvedInDocx,
                    onSelectMode = onSelectExportMode,
                    onToggleHighlight = onToggleHighlightUnresolved,
                )
            }

            item {
                SectionHeader(
                    title = "تفضيلات المعالجة والاستئناف",
                    subtitle = "معالجة قائمة على الصفحات والدفعات الداخلية دون حد أقصى مصطنع للصفحات",
                    icon = Icons.Rounded.Tune,
                )
            }

            item {
                ProcessingPreferencesCard(
                    preferences = preferences,
                    onToggleAutoSave = onToggleAutoSave,
                    onToggleResume = onToggleResumeAfterInterruption,
                )
            }

            item {
                SectionHeader(
                    title = "معلومات التطبيق",
                    subtitle = "الهوية وقواعد الحفاظ على المحتوى",
                    icon = Icons.Rounded.Info,
                )
            }

            item {
                AppInfoCard()
            }
        }
    }
}

@Composable
private fun GeminiApiKeysCard(
    geminiKeys: List<GeminiKeyRecord>,
    testingKeyId: String?,
    onAddClick: () -> Unit,
    onTestClick: (String) -> Unit,
    onToggleClick: (String, Boolean) -> Unit,
    onRemoveClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gemini_api_keys_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScholarlyInfoBanner(
                title = "أمان المفاتيح وتنبيه الحصص (Quota)",
                body = "تُشفَّر المفاتيح باستخدام MasterKey (AES256-GCM) ولا تُخزَّن في Room أو السجلات، وتُعرض هنا بأول 6 أحرف فقط. تنبيه مهم: إنشاء عدة مفاتيح API ضمن نفس مشروع Google Cloud لا يزيد الحصة اليومية أو حد المعدل.",
                icon = Icons.Rounded.Security,
            )

            Button(
                onClick = onAddClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("add_api_key_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("إضافة مفتاح Gemini API مشفّر")
            }

            if (geminiKeys.isEmpty()) {
                Text(
                    text = "لا توجد مفاتيح Gemini محفوظة بعد. أضف مفتاحاً واحداً أو أكثر من مشاريع مستقلة للتبديل التلقائي عند الفشل أو تقييد المعدل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                geminiKeys.forEach { keyItem ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = keyItem.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "المعرّف المقنّع: ${keyItem.maskedPrefix}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GeminiKeyStatusPill(status = keyItem.status)
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = keyItem.isEnabled,
                                onCheckedChange = { checked -> onToggleClick(keyItem.id, checked) },
                            )
                        }

                        if (!keyItem.lastErrorAr.isNullOrBlank()) {
                            Text(
                                text = keyItem.lastErrorAr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isTestingThis = testingKeyId == keyItem.id
                            OutlinedButton(
                                onClick = { onTestClick(keyItem.id) },
                                enabled = testingKeyId == null,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("test_key_${keyItem.id}"),
                            ) {
                                if (isTestingThis) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("جارٍ الاختبار...")
                                } else {
                                    Text("اختبار المفتاح")
                                }
                            }
                            IconButton(
                                onClick = { onRemoveClick(keyItem.id) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "إزالة المفتاح",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiKeyStatusPill(status: GeminiKeyStatus) {
    val (bg, fg) = when (status) {
        GeminiKeyStatus.VALID -> StatusSuccessContainer to StatusSuccess
        GeminiKeyStatus.RATE_LIMITED -> StatusWarningContainer to StatusWarning
        GeminiKeyStatus.INVALID,
        GeminiKeyStatus.QUOTA_EXHAUSTED,
        GeminiKeyStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        GeminiKeyStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = status.arabicLabel,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GeminiModelAndUsageCard(
    usageStats: GeminiUsageStats,
    onSelectModel: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gemini_usage_card"),
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
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "اختيار نموذج Gemini (فئة Flash)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            GeminiModelConfig.AVAILABLE_MODELS.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(
                        selected = usageStats.selectedModelId == option.modelId,
                        onClick = { onSelectModel(option.modelId) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${option.displayNameAr} (${option.modelId})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = option.rationaleAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "حالة الاستخدام المسجّلة على الجهاز",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            UsageStatRow(
                label = "المفتاح النشط أخيراً",
                value = usageStats.activeKeyLabel ?: "لا يوجد طلب ناجح/نشط مسجل بعد",
            )
            UsageStatRow(
                label = "النموذج المحدد",
                value = usageStats.selectedModelId,
            )
            UsageStatRow(
                label = "حالة آخر طلب",
                value = usageStats.lastRequestStatusAr,
            )
            UsageStatRow(
                label = "طلبات اليوم (عداد محلي)",
                value = "${usageStats.requestsTodayCount} طلب",
            )

            Text(
                text = "ملاحظة شفافية: العداد أعلاه يحصي الطلبات الصادرة من هذا الجهاز خلال اليوم فقط، ولا يعرض أرقام حصة خادمة غير متاحة من Google.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddGeminiApiKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, keyInput: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var keyInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مفتاح Gemini API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "يُخزَّن المفتاح داخل EncryptedSharedPreferences بمفتاح Android Keystore، ولا يُرسَل إلا إلى خادم Google Gemini عبر العميل الرسمي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("تسمية المفتاح") },
                    placeholder = { Text("مثال: مفتاح المشروع الأول") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("مفتاح Gemini API") },
                    placeholder = { Text("AIza...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(label, keyInput) },
                enabled = keyInput.trim().length >= 16,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("حفظ مشفّر")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("إلغاء")
            }
        },
    )
}

@Composable
private fun OcrConfigurationCard(
    selectedEngine: OcrEngineType,
    onSelectEngine: (OcrEngineType) -> Unit,
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
            OcrEngineType.entries.forEach { engine ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedEngine == engine,
                        onClick = { onSelectEngine(engine) },
                        modifier = Modifier.testTag("ocr_radio_${engine.name}"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = engine.displayNameAr,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = engine.modelInfoAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "المحرك الافتراضي هو Tesseract4Android (العربية) باستخدام ملف ara.traineddata المدمج والمنسوخ محلياً عند الإطلاق الأول؛ أما Google ML Kit v2 فهو محرك ثانوي للنصوص اللاتينية والأرقام فقط ولا يدعم العربية.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordFormattingCard(
    selectedMode: ExportMode,
    highlightUnresolved: Boolean,
    onSelectMode: (ExportMode) -> Unit,
    onToggleHighlight: (Boolean) -> Unit,
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
            ExportMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { onSelectMode(mode) },
                        modifier = Modifier.testTag("export_radio_${mode.name}"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.arabicTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = mode.arabicDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تمييز الكلمات غير المحسومة في DOCX",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "إبراز المواضع التي تركها المراجع دون حسم بوضوح داخل ملف Word.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = highlightUnresolved,
                    onCheckedChange = onToggleHighlight,
                )
            }
        }
    }
}

@Composable
private fun ProcessingPreferencesCard(
    preferences: ProcessingPreferences,
    onToggleAutoSave: (Boolean) -> Unit,
    onToggleResume: (Boolean) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الحفظ التلقائي لنقاط التقدم",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "تجهيز خيار حفظ التقدم لكل صفحة عند تفعيل المهام الخلفية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.autoSaveCheckpoints,
                    onCheckedChange = onToggleAutoSave,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الاستئناف التلقائي بعد الانقطاع",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "متابعة معالجة المستندات الطويلة من آخر صفحة مكتملة دون إعادة التقسيم يدوياً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.resumeAfterInterruption,
                    onCheckedChange = onToggleResume,
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "حجم الدفعة الداخلية المخطط: ${preferences.batchSizePages} صفحات لكل دفعة — لا يفرض التطبيق أي حد أقصى مصطنع لعدد صفحات ملف PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AppInfoCard() {
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "مُحَرِّر AI — Muharrir AI (المرحلة 04 / 07)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "مبادئ الحفاظ على المحتوى: لا تلخيص، لا حذف فقرات، لا إزالة تكرار، لا إعادة صياغة لأسلوب المؤلف، ولا اختلاق عناوين أو مراجع. يظل ملف PDF الأصلي المرجع الأعلى دوماً.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "البنية الحالية: تخزين خاص محلي، قاعدة بيانات Room v3، محرك OCR محلي (Tesseract العربية / ML Kit)، وتكامل مباشر مع Google Gemini SDK ومخزن مفاتيح مشفر.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
