package com.example.data.gemini

import com.example.domain.model.GeminiKeyStatus
import com.google.ai.client.generativeai.type.GoogleGenerativeAIException
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.SerializationException
import com.google.ai.client.generativeai.type.ServerException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class GeminiErrorKind {
    INVALID_KEY,        // 400 / 401 / InvalidAPIKeyException -> disable key, failover
    PERMISSION_DENIED,  // 403 -> disable key, failover
    QUOTA_EXHAUSTED,    // 429 hard quota exhaustion -> disable key, failover
    RATE_LIMITED,       // 429 transient RPM/TPM throttle -> try next key / bounded backoff
    SERVER_ERROR,       // 5xx -> retry once on same key, then next key
    NETWORK_ERROR,      // IOException / DNS / timeout -> stop cleanly without disabling valid keys
    MALFORMED_OUTPUT,   // Response blocked / invalid JSON -> retry once
    UNKNOWN_FAILURE,    // Other non-fatal failure
}

data class ClassifiedGeminiError(
    val kind: GeminiErrorKind,
    val keyStatusUpdate: GeminiKeyStatus?,
    val shouldDisableKey: Boolean,
    val arabicMessage: String,
    val diagnosticSafeSummary: String,
)

/**
 * Classifies exceptions from the Google AI Android SDK (`generativeai`) and transport layer
 * without ever leaking raw API keys.
 */
object GeminiErrorClassifier {

    fun classify(
        throwable: Throwable,
        knownSecrets: Collection<String> = emptyList(),
    ): ClassifiedGeminiError {
        val rawMsg = buildString {
            append(throwable.message.orEmpty())
            throwable.cause?.message?.let {
                append(" | ")
                append(it)
            }
        }
        val cleanMsg = SensitiveKeyMasker.scrubSecrets(rawMsg, knownSecrets)
        val lower = cleanMsg.lowercase()

        // 1. Network / transport errors
        if (throwable is UnknownHostException ||
            throwable is SocketTimeoutException ||
            throwable is IOException ||
            lower.contains("unable to resolve host") ||
            lower.contains("failed to connect") ||
            lower.contains("timeout") ||
            lower.contains("network")
        ) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.NETWORK_ERROR,
                keyStatusUpdate = null, // Do NOT invalidate a good key due to offline network
                shouldDisableKey = false,
                arabicMessage = "تعذر الاتصال بخادم Gemini بسبب انقطاع الشبكة أو انتهاء المهلة.",
                diagnosticSafeSummary = "network_error: $cleanMsg",
            )
        }

        // 2. Invalid API key (400 INVALID_ARGUMENT about key / 401 UNAUTHENTICATED / InvalidAPIKeyException)
        if (throwable is InvalidAPIKeyException ||
            lower.contains("api key not valid") ||
            lower.contains("api_key_invalid") ||
            lower.contains("401") ||
            lower.contains("unauthenticated") ||
            (lower.contains("400") && (lower.contains("key") || lower.contains("invalid_argument")))
        ) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.INVALID_KEY,
                keyStatusUpdate = GeminiKeyStatus.INVALID,
                shouldDisableKey = true,
                arabicMessage = "مفتاح Gemini API غير صالح (400/401) وتم تعطيله تلقائياً.",
                diagnosticSafeSummary = "invalid_key: $cleanMsg",
            )
        }

        // 3. Permission denied (403)
        if (lower.contains("403") ||
            lower.contains("permission_denied") ||
            lower.contains("permission denied") ||
            lower.contains("forbidden")
        ) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.PERMISSION_DENIED,
                keyStatusUpdate = GeminiKeyStatus.PERMISSION_DENIED,
                shouldDisableKey = true,
                arabicMessage = "رُفضت الصلاحية لهذا المفتاح (403 PERMISSION_DENIED) وتم تعطيله تلقائياً.",
                diagnosticSafeSummary = "permission_denied: $cleanMsg",
            )
        }

        // 4. Quota exhausted vs Rate limit (429 / QuotaExceededException)
        val is429OrQuota = throwable is QuotaExceededException ||
            lower.contains("429") ||
            lower.contains("resource_exhausted") ||
            lower.contains("quota") ||
            lower.contains("rate limit") ||
            lower.contains("too many requests")

        if (is429OrQuota) {
            val isExplicitTransientRateLimit = lower.contains("rate limit") ||
                lower.contains("rate_limit") ||
                lower.contains("per minute") ||
                lower.contains("rpm") ||
                lower.contains("tpm") ||
                lower.contains("too many requests") ||
                lower.contains("try again in")

            val isHardQuotaExhausted = !isExplicitTransientRateLimit && (
                lower.contains("quota_exhausted") ||
                    lower.contains("quota exceeded") ||
                    lower.contains("exceeded your current quota") ||
                    lower.contains("limit: 0") ||
                    throwable is QuotaExceededException
                )

            return if (isHardQuotaExhausted) {
                ClassifiedGeminiError(
                    kind = GeminiErrorKind.QUOTA_EXHAUSTED,
                    keyStatusUpdate = GeminiKeyStatus.QUOTA_EXHAUSTED,
                    shouldDisableKey = true,
                    arabicMessage = "نفدت الحصة المخصصة لهذا المفتاح (429 QUOTA_EXHAUSTED) وتم تعطيله تلقائياً.",
                    diagnosticSafeSummary = "quota_exhausted: $cleanMsg",
                )
            } else {
                ClassifiedGeminiError(
                    kind = GeminiErrorKind.RATE_LIMITED,
                    keyStatusUpdate = GeminiKeyStatus.RATE_LIMITED,
                    shouldDisableKey = false,
                    arabicMessage = "تجاوز مؤقت لمعدل الطلبات (429 RATE_LIMITED)؛ سيتم الانتقال للمفتاح التالي أو الانتظار المحدود.",
                    diagnosticSafeSummary = "rate_limited: $cleanMsg",
                )
            }
        }

        // 5. Server errors (5xx)
        if (throwable is ServerException ||
            Regex("""\b50[0-9]\b""").containsMatchIn(lower) ||
            lower.contains("internal server error") ||
            lower.contains("service unavailable") ||
            lower.contains("unavailable")
        ) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.SERVER_ERROR,
                keyStatusUpdate = null,
                shouldDisableKey = false,
                arabicMessage = "خطأ مؤقت من خادم Google Gemini (5xx)؛ ستُعاد المحاولة.",
                diagnosticSafeSummary = "server_error: $cleanMsg",
            )
        }

        // 6. Serialization / Response stopped
        if (throwable is ResponseStoppedException || throwable is SerializationException) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.MALFORMED_OUTPUT,
                keyStatusUpdate = null,
                shouldDisableKey = false,
                arabicMessage = "توقفت أو تعذرت قراءة استجابة Gemini بالشكل المطلوب.",
                diagnosticSafeSummary = "malformed_or_stopped: $cleanMsg",
            )
        }

        // 7. Generic GoogleGenerativeAIException or unknown
        if (throwable is GoogleGenerativeAIException) {
            return ClassifiedGeminiError(
                kind = GeminiErrorKind.UNKNOWN_FAILURE,
                keyStatusUpdate = null,
                shouldDisableKey = false,
                arabicMessage = "تعذر إتمام طلب Gemini: ${cleanMsg.ifBlank { "خطأ غير متوقع من الواجهة" }}",
                diagnosticSafeSummary = "sdk_error: $cleanMsg",
            )
        }

        return ClassifiedGeminiError(
            kind = GeminiErrorKind.UNKNOWN_FAILURE,
            keyStatusUpdate = null,
            shouldDisableKey = false,
            arabicMessage = "خطأ غير متوقع أثناء استدعاء Gemini: ${cleanMsg.ifBlank { throwable::class.java.simpleName }}",
            diagnosticSafeSummary = "unknown_error: $cleanMsg",
        )
    }
}
