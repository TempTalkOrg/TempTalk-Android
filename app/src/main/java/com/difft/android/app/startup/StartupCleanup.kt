package com.difft.android.app.startup

import android.app.Application
import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.group.create.CreateGroupViewModel
import com.difft.android.chat.dependencies.ApplicationDependencyProvider
import dagger.hilt.android.EntryPointAccessors










fun cleanupLegacySqlCipherArtifacts(ctx: Context) {
    try {
        val keyValueDbDeleted = ctx.deleteDatabase("signal-key-value.db")
        val jobDbDeleted = ctx.deleteDatabase("signal-jobmanager.db")
        if (keyValueDbDeleted || jobDbDeleted) {
            L.i {
                "[LegacyCleanup] deleted: signal-key-value.db=$keyValueDbDeleted, " +
                    "signal-jobmanager.db=$jobDbDeleted"
            }
        }
    } catch (e: Exception) {
        
        
        L.w { "[LegacyCleanup] cleanupLegacySqlCipherArtifacts failed: ${e.stackTraceToString()}" }
    }
}










fun sweepStaleSendingMessages(app: Application) {
    val wcdbJobStorage = EntryPointAccessors
        .fromApplication(app, ApplicationDependencyProvider.DepsEntryPoint::class.java)
        .wcdbJobStorage()
    wcdbJobStorage.sweepStaleSendingMessages()
    wcdbJobStorage.reconcileFailedRoomTags()
}












fun sweepAvatarCropTemp(ctx: Context) {
    AvatarPickTempCleaner.sweepPickTempDirs(ctx)
    sweepExifTempFiles(ctx)
    
    CreateGroupViewModel.sweepStaleDrafts(ctx)
}

private fun sweepExifTempFiles(ctx: Context) {
    var deleted = 0
    var failed = 0
    
    
    val generatedName = Regex("temp-?[0-9]+tmp")
    runCatching {
        
        ctx.cacheDir.listFiles { file -> file.isFile && file.name.matches(generatedName) }
            ?.forEach { if (it.delete()) deleted++ else failed++ }
    }.onFailure {
        failed++
        L.w { "[ExifCleanup] sweep result=failed reason=io" }
    }
    val event = "[ExifCleanup] sweep deleted=$deleted failed=$failed"
    L.i { event }
}
