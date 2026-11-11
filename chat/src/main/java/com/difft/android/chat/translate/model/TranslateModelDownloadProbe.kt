package com.difft.android.chat.translate.model

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import com.difft.android.base.log.lumberjack.L



















internal class TranslateModelDownloadProbe(private val context: Context) {

    data class Snapshot(
        val downloadId: Long,
        val status: Int,
        val reason: Int,
        val bytesSoFar: Long,
        val totalBytes: Long,
    )

    private val downloadManager: DownloadManager?
        get() = runCatching {
            context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        }.getOrNull()

    
    fun isAvailable(): Boolean = downloadManager != null

    



    fun snapshot(): Snapshot? {
        val manager = downloadManager ?: return null
        return runCatching {
            manager.query(DownloadManager.Query())?.use { cursor -> newestModelRow(cursor) }
        }.getOrElse {
            L.w { "[TranslateModel] DownloadManager query failed: ${it.stackTraceToString()}" }
            null
        }
    }

    



    fun remove(downloadId: Long) {
        runCatching { downloadManager?.remove(downloadId) }
            .onFailure { L.w { "[TranslateModel] DownloadManager remove failed id=$downloadId" } }
    }

    private fun newestModelRow(cursor: Cursor): Snapshot? {
        val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
        val soFarIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        if (idIndex < 0 || statusIndex < 0) return null

        var newest: Snapshot? = null
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            val uri = if (uriIndex >= 0) cursor.getString(uriIndex).orEmpty() else ""
            if (!uri.contains(MODEL_URI_MARKER)) {
                L.w { "[TranslateModel] ignoring unrelated DownloadManager row id=$id" }
                continue
            }
            val row = Snapshot(
                downloadId = id,
                status = cursor.getInt(statusIndex),
                reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0,
                bytesSoFar = if (soFarIndex >= 0) cursor.getLong(soFarIndex) else 0L,
                totalBytes = if (totalIndex >= 0) cursor.getLong(totalIndex) else 0L,
            )
            
            if (newest == null || row.downloadId > newest.downloadId) newest = row
        }
        return newest
    }

    private companion object {
        
        const val MODEL_URI_MARKER = "/translate/offline/"
    }
}
