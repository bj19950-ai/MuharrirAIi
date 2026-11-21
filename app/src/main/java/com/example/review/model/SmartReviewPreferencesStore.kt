package com.example.review.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists user-level smart review automation toggles (no Room schema migration needed).
 */
class SmartReviewPreferencesStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
    )

    private val _autoAcceptHighConfidence = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_ACCEPT_HIGH_CONFIDENCE, false),
    )
    val autoAcceptHighConfidence: StateFlow<Boolean> = _autoAcceptHighConfidence.asStateFlow()

    private val _showExportWarnings = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_EXPORT_WARNINGS, true),
    )
    val showExportWarnings: StateFlow<Boolean> = _showExportWarnings.asStateFlow()

    fun isAutoAcceptHighConfidenceEnabled(): Boolean = _autoAcceptHighConfidence.value

    fun setAutoAcceptHighConfidenceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT_HIGH_CONFIDENCE, enabled).apply()
        _autoAcceptHighConfidence.value = enabled
    }

    fun isShowExportWarningsEnabled(): Boolean = _showExportWarnings.value

    fun setShowExportWarningsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_EXPORT_WARNINGS, enabled).apply()
        _showExportWarnings.value = enabled
    }

    companion object {
        const val PREFS_FILE_NAME = "muharrir_smart_review_prefs"
        private const val KEY_AUTO_ACCEPT_HIGH_CONFIDENCE = "auto_accept_high_confidence_gemini"
        private const val KEY_SHOW_EXPORT_WARNINGS = "show_export_warnings"

        @Volatile
        private var instance: SmartReviewPreferencesStore? = null

        fun getInstance(context: Context): SmartReviewPreferencesStore {
            return instance ?: synchronized(this) {
                instance ?: SmartReviewPreferencesStore(context.applicationContext).also {
                    instance = it
                }
            }
        }

        internal fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
