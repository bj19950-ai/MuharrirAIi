package com.example.data.gemini

import android.graphics.Bitmap
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiModelConfig
import com.example.domain.model.GeminiPageAnalysisRecord
import com.example.domain.model.PageRecord
import kotlinx.coroutines.delay

sealed interface GeminiKeyTestOutcome {
    data class Valid(val key: GeminiKeyRecord, val messageAr: String) : GeminiKeyTestOutcome
    data class Failed(val key: GeminiKeyRecord?, val reasonAr: String) : GeminiKeyTestOutcome
}

sealed interface GeminiPageAnalysisOutcome {
    data class Success(
        val record: GeminiPageAnalysisRecord,
        val usedKeyLabel: String,
        val attemptCount: Int,
    ) : GeminiPageAnalysisOutcome

    data class Failure(
        val reasonAr: String,
        val attemptsMade: Int,
    ) : GeminiPageAnalysisOutcome
}

/**
 * Coordinates Gemini key testing, multimodal payload preparation, strict schema validation,
 * and deterministic multi-key failover without infinite loops.
 */
class GeminiAnalysisCoordinator(
    private val keyVault: GeminiApiKeyVault,
    private val client: GeminiPageClient,
    private val backoffDelayMillis: suspend (Long) -> Unit = { ms -> delay(ms) },
) {

    suspend fun testApiKey(keyId: String): GeminiKeyTestOutcome {
        val keyRecord = keyVault.listKeys().firstOrNull { it.id == keyId }
            ?: return GeminiKeyTestOutcome.Failed(null, "المفتاح المطلوب غير موجود.")

        val rawSecret = keyVault.getRawKeySecret(keyId)
            ?: return GeminiKeyTestOutcome.Failed(keyRecord, "تعذر استرجاع المفتاح السري من المخزن المشفر.")

        val modelId = keyVault.getSelectedModelId()
        SensitiveKeyMasker.logSafe(
            tag = TAG,
            message = "Testing Gemini key '${keyRecord.label}' (${keyRecord.maskedPrefix}) on $modelId",
            knownSecrets = listOf(rawSecret),
        )

        val result = client.validateKey(apiKey = rawSecret, modelId = modelId)
        return result.fold(
            onSuccess = {
                keyVault.touchKeyUsed(keyId)
                val updated = keyVault.updateKeyStatus(
                    id = keyId,
                    status = GeminiKeyStatus.VALID,
                    lastErrorAr = null,
                    disableKey = false,
                ) ?: keyRecord.copy(status = GeminiKeyStatus.VALID)
                val msg = "المفتاح «${keyRecord.label}» (${keyRecord.maskedPrefix}) صالح ويعمل على نموذج $modelId."
                keyVault.recordRequestOutcome(
                    keyId = keyId,
                    keyLabel = keyRecord.label,
                    statusAr = msg,
                    succeeded = true,
                    incrementDailyCounter = true,
                )
                GeminiKeyTestOutcome.Valid(updated, msg)
            },
            onFailure = { throwable ->
                val classified = GeminiErrorClassifier.classify(throwable, listOf(rawSecret))
                SensitiveKeyMasker.logSafe(
                    tag = TAG,
                    message = "Key test failed for '${keyRecord.label}' (${keyRecord.maskedPrefix}): ${classified.diagnosticSafeSummary}",
                    knownSecrets = listOf(rawSecret),
                    isWarning = true,
                )
                val statusToStore = classified.keyStatusUpdate ?: keyRecord.status
                val updated = keyVault.updateKeyStatus(
                    id = keyId,
                    status = statusToStore,
                    lastErrorAr = classified.arabicMessage,
                    disableKey = classified.shouldDisableKey,
                )
                keyVault.recordRequestOutcome(
                    keyId = keyId,
                    keyLabel = keyRecord.label,
                    statusAr = classified.arabicMessage,
                    succeeded = false,
                    incrementDailyCounter = classified.kind != GeminiErrorKind.NETWORK_ERROR,
                )
                GeminiKeyTestOutcome.Failed(updated ?: keyRecord, classified.arabicMessage)
            },
        )
    }

    /**
     * Analyzes ONE page per request using the original page bitmap (downscaled <= 1024px, < 2 MB)
     * plus Module 03 OCR text/segments, applying strict schema validation and deterministic failover:
     * - 400 / 401 -> INVALID, disable key, immediately try next enabled key.
     * - 403 -> PERMISSION_DENIED, disable key, immediately try next enabled key.
     * - 429 quota exhausted -> QUOTA_EXHAUSTED, disable key, immediately try next enabled key.
     * - 429 rate limited -> mark RATE_LIMITED, try next key; if all keys rate-limited, exponential backoff
     *   capped strictly at max 2 retries per key per page.
     * - 5xx -> retry same key once, then move to next enabled key.
     * - Malformed JSON -> retry once before failing the page.
     * - Network failure -> abort cleanly without disabling valid keys.
     */
    suspend fun analyzePageWithFailover(
        page: PageRecord,
        rawPageBitmap: Bitmap,
    ): GeminiPageAnalysisOutcome {
        val ocrResult = page.ocrResult
            ?: return GeminiPageAnalysisOutcome.Failure(
                reasonAr = "يجب إتمام استخراج النص (OCR) للصفحة ${page.originalPageNumber} قبل تحليل Gemini.",
                attemptsMade = 0,
            )

        val initialKeys = keyVault.listKeys()
        if (initialKeys.none { it.isEnabled }) {
            return GeminiPageAnalysisOutcome.Failure(
                reasonAr = "لا يوجد مفتاح Gemini API مفعّل حالياً. أضف أو فعّل مفتاحاً من الإعدادات.",
                attemptsMade = 0,
            )
        }

        val prepared = GeminiImagePreparer.prepareForGemini(rawPageBitmap)
        try {
            if (prepared.byteSize >= GeminiModelConfig.MAX_IMAGE_BYTES) {
                return GeminiPageAnalysisOutcome.Failure(
                    reasonAr = "حجم صورة الصفحة ${page.originalPageNumber} بعد الضغط لا يزال يتجاوز حد 2 ميجابايت.",
                    attemptsMade = 0,
                )
            }

            val payload = GeminiPromptBuilder.buildPayload(
                page = page,
                ocrResult = ocrResult,
                preparedWidthPx = prepared.widthPx,
                preparedHeightPx = prepared.heightPx,
            )
            val modelId = keyVault.getSelectedModelId()

            val permanentlySkippedForPage = mutableSetOf<String>()
            val rateLimitedThisRound = mutableSetOf<String>()
            val rateLimitAttemptsPerKey = mutableMapOf<String, Int>()
            val serverRetryUsedPerKey = mutableSetOf<String>()
            var backoffRound = 0
            var malformedRetryCount = 0
            var totalAttempts = 0
            val maxRateLimitRetries = GeminiModelConfig.MAX_RATE_LIMIT_RETRIES_PER_KEY_PER_PAGE
            val maxAttemptsPerKeyOnRateLimit = 1 + maxRateLimitRetries // initial attempt + max 2 retries

            while (true) {
                val candidateKey = keyVault.getNextEnabledKey(
                    excludingIds = permanentlySkippedForPage + rateLimitedThisRound,
                )
                val selectedKey = if (candidateKey != null) {
                    candidateKey
                } else {
                    // No unattempted enabled keys left in this round; check if any enabled keys
                    // that hit transient RATE_LIMITED still have retry budget for a backoff round.
                    val retryableKeys = keyVault.listKeys().filter { k ->
                        k.isEnabled &&
                            k.id !in permanentlySkippedForPage &&
                            k.status != GeminiKeyStatus.INVALID &&
                            k.status != GeminiKeyStatus.PERMISSION_DENIED &&
                            k.status != GeminiKeyStatus.QUOTA_EXHAUSTED &&
                            (rateLimitAttemptsPerKey[k.id] ?: 0) < maxAttemptsPerKeyOnRateLimit
                    }
                    if (retryableKeys.isEmpty()) {
                        return GeminiPageAnalysisOutcome.Failure(
                            reasonAr = "تعذر إكمال التحليل: جميع مفاتيح Gemini المفعلة إما غير صالحة أو نفدت حصتها أو استنفدت محاولات المعدل لهذه الصفحة.",
                            attemptsMade = totalAttempts,
                        )
                    }
                    val backoffMs = 1000L * (1L shl backoffRound.coerceAtMost(3))
                    backoffRound++
                    rateLimitedThisRound.clear()
                    backoffDelayMillis(backoffMs)
                    retryableKeys.first()
                }

                val rawSecret = keyVault.getRawKeySecret(selectedKey.id)
                if (rawSecret.isNullOrBlank()) {
                    keyVault.updateKeyStatus(
                        id = selectedKey.id,
                        status = GeminiKeyStatus.INVALID,
                        lastErrorAr = "تعذر العثور على قيمة المفتاح المشفرة.",
                        disableKey = true,
                    )
                    permanentlySkippedForPage.add(selectedKey.id)
                    continue
                }

                totalAttempts++
                SensitiveKeyMasker.logSafe(
                    tag = TAG,
                    message = "Calling Gemini ($modelId) page=${page.originalPageNumber} using '${selectedKey.label}' (${selectedKey.maskedPrefix})",
                    knownSecrets = listOf(rawSecret),
                )

                val callResult = client.analyzePage(
                    apiKey = rawSecret,
                    modelId = modelId,
                    pageBitmap = prepared.bitmap,
                    payload = payload,
                )

                var shouldReturn: GeminiPageAnalysisOutcome? = null

                callResult.fold(
                    onSuccess = { rawOutput ->
                        keyVault.touchKeyUsed(selectedKey.id)
                        keyVault.updateKeyStatus(
                            id = selectedKey.id,
                            status = GeminiKeyStatus.VALID,
                            lastErrorAr = null,
                            disableKey = false,
                        )
                        when (val validation = GeminiSchemaValidator.validate(rawOutput)) {
                            is GeminiValidationResult.Valid -> {
                                val normalizedResponse = validation.response
                                val record = GeminiPageAnalysisRecord(
                                    pageId = page.pageId,
                                    documentId = page.documentId,
                                    pageNumber = page.originalPageNumber,
                                    modelId = modelId,
                                    usedKeyLabel = "${selectedKey.label} (${selectedKey.maskedPrefix})",
                                    items = normalizedResponse.items,
                                    rawValidatedJson = validation.normalizedJson,
                                    analyzedAtMillis = System.currentTimeMillis(),
                                )
                                val statusMsg = "اكتمل تحليل الصفحة ${page.originalPageNumber} (${normalizedResponse.items.size} ملاحظة)."
                                keyVault.recordRequestOutcome(
                                    keyId = selectedKey.id,
                                    keyLabel = selectedKey.label,
                                    statusAr = statusMsg,
                                    succeeded = true,
                                    incrementDailyCounter = true,
                                )
                                shouldReturn = GeminiPageAnalysisOutcome.Success(
                                    record = record,
                                    usedKeyLabel = selectedKey.label,
                                    attemptCount = totalAttempts,
                                )
                            }
                            is GeminiValidationResult.Malformed -> {
                                keyVault.recordRequestOutcome(
                                    keyId = selectedKey.id,
                                    keyLabel = selectedKey.label,
                                    statusAr = validation.reasonAr,
                                    succeeded = false,
                                    incrementDailyCounter = true,
                                )
                                if (malformedRetryCount < 1) {
                                    malformedRetryCount++
                                    // Retry once with the same valid key
                                } else {
                                    shouldReturn = GeminiPageAnalysisOutcome.Failure(
                                        reasonAr = "استجاب Gemini بصيغة غير مطابقة للمخطط بعد إعادة المحاولة: ${validation.reasonAr}",
                                        attemptsMade = totalAttempts,
                                    )
                                }
                            }
                        }
                    },
                    onFailure = { throwable ->
                        val classified = GeminiErrorClassifier.classify(throwable, listOf(rawSecret))
                        SensitiveKeyMasker.logSafe(
                            tag = TAG,
                            message = "Gemini failure on key '${selectedKey.label}' (${selectedKey.maskedPrefix}): ${classified.diagnosticSafeSummary}",
                            knownSecrets = listOf(rawSecret),
                            isWarning = true,
                        )

                        keyVault.recordRequestOutcome(
                            keyId = selectedKey.id,
                            keyLabel = selectedKey.label,
                            statusAr = classified.arabicMessage,
                            succeeded = false,
                            incrementDailyCounter = classified.kind != GeminiErrorKind.NETWORK_ERROR,
                        )

                        when (classified.kind) {
                            GeminiErrorKind.INVALID_KEY,
                            GeminiErrorKind.PERMISSION_DENIED,
                            GeminiErrorKind.QUOTA_EXHAUSTED -> {
                                keyVault.updateKeyStatus(
                                    id = selectedKey.id,
                                    status = classified.keyStatusUpdate ?: GeminiKeyStatus.INVALID,
                                    lastErrorAr = classified.arabicMessage,
                                    disableKey = true,
                                )
                                permanentlySkippedForPage.add(selectedKey.id)
                            }
                            GeminiErrorKind.RATE_LIMITED -> {
                                keyVault.updateKeyStatus(
                                    id = selectedKey.id,
                                    status = GeminiKeyStatus.RATE_LIMITED,
                                    lastErrorAr = classified.arabicMessage,
                                    disableKey = false,
                                )
                                val nextCount = (rateLimitAttemptsPerKey[selectedKey.id] ?: 0) + 1
                                rateLimitAttemptsPerKey[selectedKey.id] = nextCount
                                rateLimitedThisRound.add(selectedKey.id)
                                if (nextCount >= maxAttemptsPerKeyOnRateLimit) {
                                    permanentlySkippedForPage.add(selectedKey.id)
                                }
                            }
                            GeminiErrorKind.SERVER_ERROR -> {
                                if (selectedKey.id !in serverRetryUsedPerKey) {
                                    serverRetryUsedPerKey.add(selectedKey.id)
                                    // Retry same key once
                                } else {
                                    permanentlySkippedForPage.add(selectedKey.id)
                                }
                            }
                            GeminiErrorKind.NETWORK_ERROR -> {
                                shouldReturn = GeminiPageAnalysisOutcome.Failure(
                                    reasonAr = classified.arabicMessage,
                                    attemptsMade = totalAttempts,
                                )
                            }
                            GeminiErrorKind.MALFORMED_OUTPUT -> {
                                if (malformedRetryCount < 1) {
                                    malformedRetryCount++
                                } else {
                                    shouldReturn = GeminiPageAnalysisOutcome.Failure(
                                        reasonAr = classified.arabicMessage,
                                        attemptsMade = totalAttempts,
                                    )
                                }
                            }
                            GeminiErrorKind.UNKNOWN_FAILURE -> {
                                permanentlySkippedForPage.add(selectedKey.id)
                            }
                        }
                    },
                )

                if (shouldReturn != null) {
                    return shouldReturn!!
                }
            }
        } finally {
            prepared.recycleSafely()
        }
    }

    /**
     * Fallback OCR extraction when local Tesseract yields empty or suspiciously short text.
     * Reuses GeminiImagePreparer and multi-key failover without enforcing JSON schema.
     */
    suspend fun extractTextFallbackWithFailover(
        pageNumber: Int,
        rawPageBitmap: Bitmap,
        prompt: String = GeminiPromptBuilder.FALLBACK_OCR_PROMPT,
    ): Result<String> {
        val initialKeys = keyVault.listKeys()
        if (initialKeys.none { it.isEnabled }) {
            return Result.failure(
                IllegalStateException("لا يوجد مفتاح Gemini API مفعّل لتنفيذ الاستخراج الاحتياطي."),
            )
        }

        val prepared = GeminiImagePreparer.prepareForGemini(rawPageBitmap)
        try {
            if (prepared.byteSize >= GeminiModelConfig.MAX_IMAGE_BYTES) {
                return Result.failure(
                    IllegalStateException("حجم صورة الصفحة $pageNumber يتجاوز الحد المسموح للاستخراج الاحتياطي."),
                )
            }

            val modelId = keyVault.getSelectedModelId()
            val permanentlySkippedForPage = mutableSetOf<String>()
            val rateLimitedThisRound = mutableSetOf<String>()
            val rateLimitAttemptsPerKey = mutableMapOf<String, Int>()
            val serverRetryUsedPerKey = mutableSetOf<String>()
            var backoffRound = 0
            val maxAttemptsPerKeyOnRateLimit = 1 + GeminiModelConfig.MAX_RATE_LIMIT_RETRIES_PER_KEY_PER_PAGE

            while (true) {
                val candidateKey = keyVault.getNextEnabledKey(
                    excludingIds = permanentlySkippedForPage + rateLimitedThisRound,
                )
                val selectedKey = if (candidateKey != null) {
                    candidateKey
                } else {
                    val retryableKeys = keyVault.listKeys().filter { k ->
                        k.isEnabled &&
                            k.id !in permanentlySkippedForPage &&
                            k.status != GeminiKeyStatus.INVALID &&
                            k.status != GeminiKeyStatus.PERMISSION_DENIED &&
                            k.status != GeminiKeyStatus.QUOTA_EXHAUSTED &&
                            (rateLimitAttemptsPerKey[k.id] ?: 0) < maxAttemptsPerKeyOnRateLimit
                    }
                    if (retryableKeys.isEmpty()) {
                        return Result.failure(
                            IllegalStateException("استُنفدت جميع مفاتيح Gemini المتاحة للاستخراج الاحتياطي للصفحة $pageNumber."),
                        )
                    }
                    val backoffMs = 1000L * (1L shl backoffRound.coerceAtMost(3))
                    backoffRound++
                    rateLimitedThisRound.clear()
                    backoffDelayMillis(backoffMs)
                    retryableKeys.first()
                }

                val rawSecret = keyVault.getRawKeySecret(selectedKey.id)
                if (rawSecret.isNullOrBlank()) {
                    keyVault.updateKeyStatus(
                        id = selectedKey.id,
                        status = GeminiKeyStatus.INVALID,
                        lastErrorAr = "تعذر العثور على قيمة المفتاح المشفرة.",
                        disableKey = true,
                    )
                    permanentlySkippedForPage.add(selectedKey.id)
                    continue
                }

                SensitiveKeyMasker.logSafe(
                    tag = TAG,
                    message = "Fallback Gemini OCR ($modelId) page=$pageNumber using '${selectedKey.label}' (${selectedKey.maskedPrefix})",
                    knownSecrets = listOf(rawSecret),
                )

                val callResult = client.extractPlainText(
                    apiKey = rawSecret,
                    modelId = modelId,
                    pageBitmap = prepared.bitmap,
                    prompt = prompt,
                )

                var finalResult: Result<String>? = null
                callResult.fold(
                    onSuccess = { extractedText ->
                        keyVault.touchKeyUsed(selectedKey.id)
                        keyVault.updateKeyStatus(
                            id = selectedKey.id,
                            status = GeminiKeyStatus.VALID,
                            lastErrorAr = null,
                            disableKey = false,
                        )
                        val cleaned = extractedText.trim()
                        val statusMsg = "اكتمل الاستخراج الاحتياطي للصفحة $pageNumber عبر Gemini (${cleaned.length} حرفاً)."
                        keyVault.recordRequestOutcome(
                            keyId = selectedKey.id,
                            keyLabel = selectedKey.label,
                            statusAr = statusMsg,
                            succeeded = true,
                            incrementDailyCounter = true,
                        )
                        finalResult = if (cleaned.isNotEmpty()) {
                            Result.success(cleaned)
                        } else {
                            Result.failure(IllegalStateException("أرجع Gemini نصاً فارغاً أثناء الاستخراج الاحتياطي."))
                        }
                    },
                    onFailure = { throwable ->
                        val classified = GeminiErrorClassifier.classify(throwable, listOf(rawSecret))
                        SensitiveKeyMasker.logSafe(
                            tag = TAG,
                            message = "Fallback Gemini OCR failed on '${selectedKey.label}': ${classified.diagnosticSafeSummary}",
                            knownSecrets = listOf(rawSecret),
                            isWarning = true,
                        )
                        keyVault.recordRequestOutcome(
                            keyId = selectedKey.id,
                            keyLabel = selectedKey.label,
                            statusAr = classified.arabicMessage,
                            succeeded = false,
                            incrementDailyCounter = classified.kind != GeminiErrorKind.NETWORK_ERROR,
                        )

                        when (classified.kind) {
                            GeminiErrorKind.INVALID_KEY,
                            GeminiErrorKind.PERMISSION_DENIED,
                            GeminiErrorKind.QUOTA_EXHAUSTED -> {
                                keyVault.updateKeyStatus(
                                    id = selectedKey.id,
                                    status = classified.keyStatusUpdate ?: GeminiKeyStatus.INVALID,
                                    lastErrorAr = classified.arabicMessage,
                                    disableKey = true,
                                )
                                permanentlySkippedForPage.add(selectedKey.id)
                            }
                            GeminiErrorKind.RATE_LIMITED -> {
                                keyVault.updateKeyStatus(
                                    id = selectedKey.id,
                                    status = GeminiKeyStatus.RATE_LIMITED,
                                    lastErrorAr = classified.arabicMessage,
                                    disableKey = false,
                                )
                                val nextCount = (rateLimitAttemptsPerKey[selectedKey.id] ?: 0) + 1
                                rateLimitAttemptsPerKey[selectedKey.id] = nextCount
                                rateLimitedThisRound.add(selectedKey.id)
                                if (nextCount >= maxAttemptsPerKeyOnRateLimit) {
                                    permanentlySkippedForPage.add(selectedKey.id)
                                }
                            }
                            GeminiErrorKind.SERVER_ERROR -> {
                                if (selectedKey.id !in serverRetryUsedPerKey) {
                                    serverRetryUsedPerKey.add(selectedKey.id)
                                } else {
                                    permanentlySkippedForPage.add(selectedKey.id)
                                }
                            }
                            GeminiErrorKind.NETWORK_ERROR -> {
                                finalResult = Result.failure(IllegalStateException(classified.arabicMessage))
                            }
                            GeminiErrorKind.MALFORMED_OUTPUT,
                            GeminiErrorKind.UNKNOWN_FAILURE -> {
                                permanentlySkippedForPage.add(selectedKey.id)
                            }
                        }
                    },
                )

                if (finalResult != null) {
                    return finalResult!!
                }
            }
        } finally {
            prepared.recycleSafely()
        }
    }

    companion object {
        private const val TAG = "MuharrirGeminiCoord"
    }
}
