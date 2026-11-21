package com.example.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Manages Storage Access Framework (SAF) persistable read URI permissions so imported source
 * PDFs remain readable across app restarts and device reboots without re-prompting the user.
 */
object PersistableUriPermissionManager {

    /**
     * Requests a persistable read permission grant for [uri] immediately on SAF selection/import.
     * Safely ignores URIs/providers that do not support persistable grants (e.g., file:// or temp URIs).
     */
    fun takePersistableReadPermission(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Boolean {
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun hasPersistedReadPermission(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Boolean {
        return runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)
    }

    fun openReadOnlyDescriptor(
        context: Context,
        uriString: String?,
    ): ParcelFileDescriptor? {
        if (uriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
