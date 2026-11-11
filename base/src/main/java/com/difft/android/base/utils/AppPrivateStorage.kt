package com.difft.android.base.utils

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.IOException







object AppPrivateStorage {

    private val roots: List<String> by lazy { canonicalRoots(application) }

    
    fun isAppPrivate(path: String): Boolean = isUnderAnyRoot(path, roots)

    






    @VisibleForTesting
    internal fun canonicalRoots(context: Context): List<String> = buildList {
        canonicalOrNull(context.dataDir)?.let(::add)
        context.getExternalFilesDirs(null).filterNotNull()
            .mapNotNull { it.parentFile }
            .forEach { canonicalOrNull(it)?.let(::add) }
    }.distinct()

    




    @VisibleForTesting
    internal fun isUnderAnyRoot(path: String, roots: List<String>): Boolean {
        if (path.isBlank() || roots.isEmpty()) return false
        val candidate = canonicalOrNull(File(path)) ?: return false
        return roots.any { candidate == it || candidate.startsWith(it + File.separator) }
    }

    private fun canonicalOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (e: IOException) {
        
        L.w { "[AppPrivateStorage] canonicalize failed: ${e.javaClass.simpleName}" }
        null
    }
}
