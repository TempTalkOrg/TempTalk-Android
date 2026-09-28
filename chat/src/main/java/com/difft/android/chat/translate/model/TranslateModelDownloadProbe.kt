package com.difft.android.chat.translate.model

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import com.difft.android.base.log.lumberjack.L

/**
 * Reads the translate backend's model download out of the system [DownloadManager].
 *
 * Deliberately free of any backend dependency: it observes the platform, not the engine, so it stays
 * in `:chat` while everything engine-specific lives behind `TranslateBackend`.
 *
 * The backend gives no progress callback of its own — its download call resolves once, at the end.
 * It does enqueue the model zip on the platform DownloadManager, so the bytes are observable, which
 * is what turns "spinner forever" into "37% / stalled".
 *
 * ROW OWNERSHIP. `DownloadManager.query` is UID-scoped: it only returns rows this app enqueued. The
 * backend sets neither a title nor a description on its Request (verified against the Google ML Kit
 * translate 17.0.3 / common 18.11.0 artifacts — the request only gets `setAllowedNetworkTypes`,
 * `setRequiresCharging` and a User-Agent header), so the URI is the only discriminator available.
 * The translate model URLs are `https://dl.google.com/translate/offline/<...>.zip` and
 * `https://redirector.gvt1.com/edgedl/translate/offline/<...>.zip`, hence the [MODEL_URI_MARKER]
 * path match. Anything else is logged and ignored rather than assumed to be ours.
 *
 * GUARD FOR FUTURE MAINTAINERS: this app must not enqueue its own DownloadManager work. If it ever
 * does, tighten the matcher here — do not relax it at the call site.
 */
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

    /** True when the platform DownloadManager is missing or disabled by the user. */
    fun isAvailable(): Boolean = downloadManager != null

    /**
     * The newest translation-model row, or null when no model is being downloaded through
     * DownloadManager right now.
     */
    fun snapshot(): Snapshot? {
        val manager = downloadManager ?: return null
        return runCatching {
            manager.query(DownloadManager.Query())?.use { cursor -> newestModelRow(cursor) }
        }.getOrElse {
            L.w { "[TranslateModel] DownloadManager query failed: ${it.stackTraceToString()}" }
            null
        }
    }

    /**
     * Drops a row from DownloadManager. Called on STATUS_FAILED (a dead row would otherwise be
     * adopted as an in-flight download on the next snapshot) and when the user cancels.
     */
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
            // Row ids are monotonically increasing, so the largest one is the most recent attempt.
            if (newest == null || row.downloadId > newest.downloadId) newest = row
        }
        return newest
    }

    private companion object {
        /** Shared by both translate-model hosts; see the class KDoc. */
        const val MODEL_URI_MARKER = "/translate/offline/"
    }
}
