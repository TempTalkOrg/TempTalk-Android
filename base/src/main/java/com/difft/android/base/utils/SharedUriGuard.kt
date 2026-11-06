package com.difft.android.base.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Screens URIs that other apps hand to the share entry (#1429). The app opens them with its own
 * permissions, so a `file://` URI into its own storage would copy private data on the sender's
 * behalf.
 */
object SharedUriGuard {

    /** Directories owned by this app: its private data dir and its app-specific external dirs. */
    fun appStorageRoots(context: Context): List<File> = buildList {
        add(context.dataDir)
        context.getExternalFilesDirs(null).filterNotNull().mapNotNullTo(this) { it.parentFile }
        context.externalCacheDirs.filterNotNull().mapNotNullTo(this) { it.parentFile }
    }

    /** True when [uri] is a `file://` URI that resolves inside one of [appRoots], or cannot be resolved. */
    fun pointsIntoAppStorage(uri: Uri, appRoots: List<File>): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return false
        val path = uri.path ?: return true
        val target = runCatching { File(path).canonicalPath }.getOrNull() ?: return true
        return appRoots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == rootPath || target.startsWith(rootPath + File.separator)
        }
    }
}
