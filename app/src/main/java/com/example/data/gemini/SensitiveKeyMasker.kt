package com.example.data.gemini

import android.util.Log

/**
 * Ensures raw Gemini API keys never appear in logs, UI labels, Room tables, or error messages.
 */
object SensitiveKeyMasker {

    private val GOOGLE_API_KEY_REGEX = Regex("""AIza[0-9A-Za-z_\-]{10,}""")

    /**
     * Masks an API key down to its first 6 characters + "..." (or "***" if shorter).
     */
    fun maskKey(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return "***"
        return if (trimmed.length >= 6) {
            "${trimmed.take(6)}..."
        } else {
            "${trimmed.take(2)}***"
        }
    }

    /**
     * Replaces any known secret strings or Google AIza... tokens in text with masked prefixes.
     */
    fun scrubSecrets(text: String, knownSecrets: Collection<String> = emptyList()): String {
        var sanitized = text
        knownSecrets.forEach { secret ->
            val trimmed = secret.trim()
            if (trimmed.length >= 6 && sanitized.contains(trimmed)) {
                sanitized = sanitized.replace(trimmed, maskKey(trimmed))
            }
        }
        sanitized = GOOGLE_API_KEY_REGEX.replace(sanitized) { match ->
            maskKey(match.value)
        }
        return sanitized
    }

    fun logSafe(
        tag: String,
        message: String,
        knownSecrets: Collection<String> = emptyList(),
        isWarning: Boolean = false,
    ) {
        val clean = scrubSecrets(message, knownSecrets)
        if (isWarning) {
            Log.w(tag, clean)
        } else {
            Log.d(tag, clean)
        }
    }
}
