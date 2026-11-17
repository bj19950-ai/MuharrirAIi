package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode(
    val arabicLabel: String,
    val arabicDescription: String,
) {
    LIGHT(
        arabicLabel = "الوضع النهاري",
        arabicDescription = "خلفية فاتحة مريحة للقراءة والتحرير النهاري",
    ),
    DARK(
        arabicLabel = "الوضع الليلي",
        arabicDescription = "ثيم داكن حقيقي متباين ومريح للعين في الإضاءة المنخفضة",
    ),
    SYSTEM(
        arabicLabel = "تلقائي / حسب إعدادات الجهاز",
        arabicDescription = "يتطابق تلقائياً مع مظهر النظام في جهازك",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AppearanceMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

enum class AppThemePalette(
    val arabicTitle: String,
    val arabicSubtitle: String,
) {
    NEUTRAL(
        arabicTitle = "محايد هادئ",
        arabicSubtitle = "توازن واضح مع ترويسة أردوازية داكنة",
    ),
    WARM_IVORY(
        arabicTitle = "ورقي عاجي دافئ",
        arabicSubtitle = "درجات الورق الكلاسيكي المريحة للقراءة الطويلة",
    ),
    SOFT_GRAY(
        arabicTitle = "رمادي هادئ",
        arabicSubtitle = "رمادي محايد ناعم بتفاصيل واضحة",
    ),
    MUTED_BLUE_GRAY(
        arabicTitle = "أزرق رمادي هادئ",
        arabicSubtitle = "لمسة أردوازية هادئة غير مشبعة",
    ),
    SOFT_SAGE(
        arabicTitle = "أخضر رمادي ناعم",
        arabicSubtitle = "درجات مريمية هادئة ومريحة للتركيز",
    ),
    CHARCOAL_SLATE(
        arabicTitle = "فحمي داكن",
        arabicSubtitle = "حبر فحمي رصين في النهار والليل",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AppThemePalette =
            entries.firstOrNull { it.name == value } ?: NEUTRAL
    }
}

data class AppearancePreferences(
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val themePalette: AppThemePalette = AppThemePalette.NEUTRAL,
)

class AppearancePreferencesStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE),
    )

    private val _preferences = MutableStateFlow(readPersisted())
    val preferences: StateFlow<AppearancePreferences> = _preferences.asStateFlow()

    private fun readPersisted(): AppearancePreferences {
        val mode = AppearanceMode.fromStored(prefs.getString(KEY_APPEARANCE_MODE, null))
        val palette = AppThemePalette.fromStored(prefs.getString(KEY_THEME_PALETTE, null))
        return AppearancePreferences(
            appearanceMode = mode,
            themePalette = palette,
        )
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.name).apply()
        _preferences.value = _preferences.value.copy(appearanceMode = mode)
    }

    fun setThemePalette(palette: AppThemePalette) {
        prefs.edit().putString(KEY_THEME_PALETTE, palette.name).apply()
        _preferences.value = _preferences.value.copy(themePalette = palette)
    }

    fun refreshFromDisk() {
        _preferences.value = readPersisted()
    }

    companion object {
        const val PREFS_FILE_NAME = "muharrir_appearance_prefs"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_THEME_PALETTE = "theme_palette"

        @Volatile
        private var instance: AppearancePreferencesStore? = null

        fun getInstance(context: Context): AppearancePreferencesStore {
            return instance ?: synchronized(this) {
                instance ?: AppearancePreferencesStore(context.applicationContext).also {
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
