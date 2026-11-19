package com.example.data.ocr

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences-backed cache for page-level inspection flags
 * (whether a page bitmap is visually blank, and whether Gemini fallback extraction
 * has already been applied to fix a suspicious page) without altering the Room schema.
 */
class PageInspectionCacheStore(
    private val prefs: SharedPreferences,
) {
    fun isVisuallyEmptyCached(pageId: String): Boolean? {
        val key = KEY_BLANK_PREFIX + pageId
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setVisuallyEmpty(pageId: String, isEmpty: Boolean) {
        prefs.edit().putBoolean(KEY_BLANK_PREFIX + pageId, isEmpty).apply()
    }

    fun isGeminiFallbackApplied(pageId: String): Boolean =
        prefs.getBoolean(KEY_GEMINI_FIXED_PREFIX + pageId, false)

    fun setGeminiFallbackApplied(pageId: String, applied: Boolean) {
        prefs.edit().putBoolean(KEY_GEMINI_FIXED_PREFIX + pageId, applied).apply()
    }

    fun clearPageFlags(pageId: String) {
        prefs.edit()
            .remove(KEY_BLANK_PREFIX + pageId)
            .remove(KEY_GEMINI_FIXED_PREFIX + pageId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "muharrir_page_inspection_cache"
        private const val KEY_BLANK_PREFIX = "blank_"
        private const val KEY_GEMINI_FIXED_PREFIX = "gemini_fixed_"

        @Volatile
        private var instance: PageInspectionCacheStore? = null

        fun getInstance(context: Context): PageInspectionCacheStore {
            return instance ?: synchronized(this) {
                instance ?: PageInspectionCacheStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
        }
    }
}
