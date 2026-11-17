package com.example.data.gemini

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiKeyStatus
import com.example.domain.model.GeminiModelConfig
import com.example.domain.model.GeminiUsageStats
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface GeminiApiKeyVault {
    val keys: Flow<List<GeminiKeyRecord>>
    val usageStats: Flow<GeminiUsageStats>

    suspend fun listKeys(): List<GeminiKeyRecord>
    suspend fun addKey(label: String, rawApiKey: String): Result<GeminiKeyRecord>
    suspend fun setKeyEnabled(id: String, enabled: Boolean): GeminiKeyRecord?
    suspend fun updateKeyStatus(
        id: String,
        status: GeminiKeyStatus,
        lastErrorAr: String? = null,
        disableKey: Boolean = false,
    ): GeminiKeyRecord?
    suspend fun touchKeyUsed(id: String)
    suspend fun removeKey(id: String)
    suspend fun getRawKeySecret(id: String): String?
    suspend fun getNextEnabledKey(excludingIds: Set<String> = emptySet()): GeminiKeyRecord?
    suspend fun getSelectedModelId(): String
    suspend fun setSelectedModelId(modelId: String)
    suspend fun recordRequestOutcome(
        keyId: String?,
        keyLabel: String?,
        statusAr: String,
        succeeded: Boolean,
        incrementDailyCounter: Boolean,
    )
    suspend fun getUsageStats(): GeminiUsageStats
}

@JsonClass(generateAdapter = true)
internal data class StoredKeyMetadataJson(
    val id: String,
    val label: String,
    val maskedPrefix: String,
    val isEnabled: Boolean,
    val status: String,
    val lastErrorAr: String? = null,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long? = null,
)

/**
 * Hardware/Keystore-backed encrypted vault using AndroidX Security Crypto (`EncryptedSharedPreferences`).
 * Raw API keys are stored exclusively inside encrypted key/value pairs and never in Room or plain prefs.
 */
@Suppress("DEPRECATION")
class EncryptedGeminiApiKeyVault @VisibleForTesting internal constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val dayProvider: () -> String = { currentLocalDayString() },
) : GeminiApiKeyVault {

    constructor(context: Context) : this(
        prefsProvider = {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        },
    )

    private val mutex = Mutex()
    private val moshi = Moshi.Builder().build()
    private val listType = Types.newParameterizedType(List::class.java, StoredKeyMetadataJson::class.java)
    private val metadataAdapter = moshi.adapter<List<StoredKeyMetadataJson>>(listType)

    private val encryptedPrefs: SharedPreferences by lazy { prefsProvider() }

    private val _keys = MutableStateFlow<List<GeminiKeyRecord>>(emptyList())
    override val keys: Flow<List<GeminiKeyRecord>> = _keys.asStateFlow()

    private val _usageStats = MutableStateFlow(GeminiUsageStats())
    override val usageStats: Flow<GeminiUsageStats> = _usageStats.asStateFlow()

    init {
        runCatching {
            _keys.value = readMetadataListLocked()
            _usageStats.value = readUsageStatsLocked()
        }
    }

    override suspend fun listKeys(): List<GeminiKeyRecord> = mutex.withLock {
        val list = readMetadataListLocked()
        _keys.value = list
        list
    }

    override suspend fun addKey(label: String, rawApiKey: String): Result<GeminiKeyRecord> = mutex.withLock {
        val trimmedKey = rawApiKey.trim()
        if (trimmedKey.length < 16) {
            return@withLock Result.failure(
                IllegalArgumentException("صيغة مفتاح Gemini API غير مكتملة أو قصيرة جداً."),
            )
        }
        val cleanLabel = label.trim().ifEmpty { "مفتاح Gemini ${_keys.value.size + 1}" }
        val id = UUID.randomUUID().toString()
        val record = GeminiKeyRecord(
            id = id,
            label = cleanLabel,
            maskedPrefix = SensitiveKeyMasker.maskKey(trimmedKey),
            isEnabled = true,
            status = GeminiKeyStatus.UNKNOWN,
            lastErrorAr = null,
            createdAtMillis = System.currentTimeMillis(),
            lastUsedAtMillis = null,
        )

        val current = readMetadataListLocked().toMutableList()
        current.add(record)

        encryptedPrefs.edit()
            .putString(secretStorageKey(id), trimmedKey)
            .putString(PREF_KEY_METADATA_LIST, serializeMetadataList(current))
            .apply()

        _keys.value = current
        SensitiveKeyMasker.logSafe(
            tag = TAG,
            message = "Added Gemini API key '${record.label}' (${record.maskedPrefix})",
            knownSecrets = listOf(trimmedKey),
        )
        Result.success(record)
    }

    override suspend fun setKeyEnabled(id: String, enabled: Boolean): GeminiKeyRecord? = mutex.withLock {
        val current = readMetadataListLocked().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx == -1) return@withLock null
        val updated = current[idx].copy(isEnabled = enabled)
        current[idx] = updated
        persistMetadataListLocked(current)
        updated
    }

    override suspend fun updateKeyStatus(
        id: String,
        status: GeminiKeyStatus,
        lastErrorAr: String?,
        disableKey: Boolean,
    ): GeminiKeyRecord? = mutex.withLock {
        val current = readMetadataListLocked().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx == -1) return@withLock null
        val existing = current[idx]
        val updated = existing.copy(
            status = status,
            lastErrorAr = lastErrorAr,
            isEnabled = if (disableKey) false else existing.isEnabled,
        )
        current[idx] = updated
        persistMetadataListLocked(current)
        updated
    }

    override suspend fun touchKeyUsed(id: String) {
        mutex.withLock {
            val current = readMetadataListLocked().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx != -1) {
                current[idx] = current[idx].copy(lastUsedAtMillis = System.currentTimeMillis())
                persistMetadataListLocked(current)
            }
        }
    }

    override suspend fun removeKey(id: String) = mutex.withLock {
        val current = readMetadataListLocked().filterNot { it.id == id }
        encryptedPrefs.edit()
            .remove(secretStorageKey(id))
            .putString(PREF_KEY_METADATA_LIST, serializeMetadataList(current))
            .apply()
        _keys.value = current
    }

    override suspend fun getRawKeySecret(id: String): String? = mutex.withLock {
        encryptedPrefs.getString(secretStorageKey(id), null)?.takeIf { it.isNotBlank() }
    }

    override suspend fun getNextEnabledKey(excludingIds: Set<String>): GeminiKeyRecord? = mutex.withLock {
        val all = readMetadataListLocked()
        all.firstOrNull { key ->
            key.isEnabled &&
                key.id !in excludingIds &&
                key.status != GeminiKeyStatus.INVALID &&
                key.status != GeminiKeyStatus.PERMISSION_DENIED &&
                key.status != GeminiKeyStatus.QUOTA_EXHAUSTED
        }
    }

    override suspend fun getSelectedModelId(): String = mutex.withLock {
        encryptedPrefs.getString(PREF_SELECTED_MODEL_ID, GeminiModelConfig.DEFAULT_MODEL_ID)
            ?: GeminiModelConfig.DEFAULT_MODEL_ID
    }

    override suspend fun setSelectedModelId(modelId: String) = mutex.withLock {
        val clean = modelId.trim().ifEmpty { GeminiModelConfig.DEFAULT_MODEL_ID }
        encryptedPrefs.edit().putString(PREF_SELECTED_MODEL_ID, clean).apply()
        _usageStats.value = readUsageStatsLocked()
    }

    override suspend fun recordRequestOutcome(
        keyId: String?,
        keyLabel: String?,
        statusAr: String,
        succeeded: Boolean,
        incrementDailyCounter: Boolean,
    ) = mutex.withLock {
        val today = dayProvider()
        val storedDay = encryptedPrefs.getString(PREF_USAGE_DATE_YMD, null)
        val baseCount = if (storedDay == today) encryptedPrefs.getInt(PREF_USAGE_TODAY_COUNT, 0) else 0
        val nextCount = if (incrementDailyCounter) baseCount + 1 else baseCount
        val now = System.currentTimeMillis()

        encryptedPrefs.edit()
            .putString(PREF_USAGE_DATE_YMD, today)
            .putInt(PREF_USAGE_TODAY_COUNT, nextCount)
            .putString(PREF_USAGE_LAST_STATUS_AR, statusAr)
            .putBoolean(PREF_USAGE_LAST_SUCCEEDED, succeeded)
            .putBoolean(PREF_USAGE_HAS_OUTCOME, true)
            .putString(PREF_USAGE_ACTIVE_KEY_ID, keyId)
            .putString(PREF_USAGE_ACTIVE_KEY_LABEL, keyLabel)
            .putLong(PREF_USAGE_LAST_TIME_MILLIS, now)
            .apply()

        _usageStats.value = readUsageStatsLocked()
    }

    override suspend fun getUsageStats(): GeminiUsageStats = mutex.withLock {
        val stats = readUsageStatsLocked()
        _usageStats.value = stats
        stats
    }

    private fun readMetadataListLocked(): List<GeminiKeyRecord> {
        val json = encryptedPrefs.getString(PREF_KEY_METADATA_LIST, null) ?: return emptyList()
        val raw = runCatching { metadataAdapter.fromJson(json) }.getOrNull().orEmpty()
        return raw.map { item ->
            val status = runCatching { GeminiKeyStatus.valueOf(item.status) }
                .getOrDefault(GeminiKeyStatus.UNKNOWN)
            GeminiKeyRecord(
                id = item.id,
                label = item.label,
                maskedPrefix = item.maskedPrefix,
                isEnabled = item.isEnabled,
                status = status,
                lastErrorAr = item.lastErrorAr,
                createdAtMillis = item.createdAtMillis,
                lastUsedAtMillis = item.lastUsedAtMillis,
            )
        }
    }

    private fun persistMetadataListLocked(list: List<GeminiKeyRecord>) {
        encryptedPrefs.edit()
            .putString(PREF_KEY_METADATA_LIST, serializeMetadataList(list))
            .apply()
        _keys.value = list
    }

    private fun serializeMetadataList(list: List<GeminiKeyRecord>): String {
        val mapped = list.map {
            StoredKeyMetadataJson(
                id = it.id,
                label = it.label,
                maskedPrefix = it.maskedPrefix,
                isEnabled = it.isEnabled,
                status = it.status.name,
                lastErrorAr = it.lastErrorAr,
                createdAtMillis = it.createdAtMillis,
                lastUsedAtMillis = it.lastUsedAtMillis,
            )
        }
        return metadataAdapter.toJson(mapped)
    }

    private fun readUsageStatsLocked(): GeminiUsageStats {
        val today = dayProvider()
        val storedDay = encryptedPrefs.getString(PREF_USAGE_DATE_YMD, null)
        val todayCount = if (storedDay == today) encryptedPrefs.getInt(PREF_USAGE_TODAY_COUNT, 0) else 0
        val hasOutcome = encryptedPrefs.getBoolean(PREF_USAGE_HAS_OUTCOME, false)
        val modelId = encryptedPrefs.getString(PREF_SELECTED_MODEL_ID, GeminiModelConfig.DEFAULT_MODEL_ID)
            ?: GeminiModelConfig.DEFAULT_MODEL_ID

        return GeminiUsageStats(
            activeKeyId = encryptedPrefs.getString(PREF_USAGE_ACTIVE_KEY_ID, null),
            activeKeyLabel = encryptedPrefs.getString(PREF_USAGE_ACTIVE_KEY_LABEL, null),
            selectedModelId = modelId,
            lastRequestStatusAr = encryptedPrefs.getString(
                PREF_USAGE_LAST_STATUS_AR,
                "لم يُرسل أي طلب إلى Gemini بعد",
            ) ?: "لم يُرسل أي طلب إلى Gemini بعد",
            lastRequestSucceeded = if (hasOutcome) {
                encryptedPrefs.getBoolean(PREF_USAGE_LAST_SUCCEEDED, false)
            } else {
                null
            },
            requestsTodayCount = todayCount,
            lastRequestTimeMillis = if (hasOutcome) {
                encryptedPrefs.getLong(PREF_USAGE_LAST_TIME_MILLIS, 0L).takeIf { it > 0L }
            } else {
                null
            },
        )
    }

    companion object {
        private const val TAG = "MuharrirKeyVault"
        const val ENCRYPTED_PREFS_FILE = "muharrir_gemini_encrypted_vault"
        private const val PREF_KEY_METADATA_LIST = "gemini_key_metadata_list"
        private const val PREF_SELECTED_MODEL_ID = "gemini_selected_model_id"
        private const val PREF_USAGE_DATE_YMD = "gemini_usage_date_ymd"
        private const val PREF_USAGE_TODAY_COUNT = "gemini_usage_today_count"
        private const val PREF_USAGE_LAST_STATUS_AR = "gemini_usage_last_status_ar"
        private const val PREF_USAGE_LAST_SUCCEEDED = "gemini_usage_last_succeeded"
        private const val PREF_USAGE_HAS_OUTCOME = "gemini_usage_has_outcome"
        private const val PREF_USAGE_ACTIVE_KEY_ID = "gemini_usage_active_key_id"
        private const val PREF_USAGE_ACTIVE_KEY_LABEL = "gemini_usage_active_key_label"
        private const val PREF_USAGE_LAST_TIME_MILLIS = "gemini_usage_last_time_millis"

        private fun secretStorageKey(id: String) = "gemini_raw_secret_$id"

        private fun currentLocalDayString(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
