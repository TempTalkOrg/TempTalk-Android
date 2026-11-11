package com.difft.android.base.utils

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File






object SharedUriGuard {

    
    fun appStorageRoots(context: Context): List<File> = buildList {
        add(context.dataDir)
        context.getExternalFilesDirs(null).filterNotNull().mapNotNullTo(this) { it.parentFile }
        context.externalCacheDirs.filterNotNull().mapNotNullTo(this) { it.parentFile }
    }

    
    fun pointsIntoAppStorage(uri: Uri, appRoots: List<File>): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return false
        val path = uri.path ?: return true
        val target = runCatching { File(path).canonicalPath }.getOrNull() ?: return true
        return appRoots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == rootPath || target.startsWith(rootPath + File.separator)
        }
    }

    
    fun isOwnProviderUri(context: Context, uri: Uri): Boolean {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return false
        
        val authority = uri.authority?.substringAfterLast('@') ?: return true
        
        
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner == context.packageName
    }

    



    fun excludeOwnTargets(context: Context, chooser: Intent): Intent {
        val target = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java) ?: return chooser
        val own = context.packageManager.queryIntentActivities(target, 0)
            .map { it.activityInfo }
            .filter { it.packageName == context.packageName }
            .map { ComponentName(it.packageName, it.name) }
        if (own.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
        return chooser
    }
}
