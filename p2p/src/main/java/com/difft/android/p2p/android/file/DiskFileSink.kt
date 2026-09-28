package com.difft.android.p2p.android.file

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.model.FileMetadata
import okio.Sink
import okio.sink
import okio.source
import java.io.File

/**
 * Receiver-side [FileSink] writing incoming files to a download directory. Each file streams into a
 * temp `.part` file; [finalizeFile] renames it to the final name only when the SHA-256 matched (or
 * no digest was declared), and deletes the partial on mismatch so no corrupt file is surfaced.
 *
 * [com.difft.android.p2p.engine.upload.UploadHandler] already hashes the stream; the recompute from
 * disk on finalize is a second, independent integrity check.
 */
class DiskFileSink(private val downloadDir: File) : FileSink {

    private val lock = Any()
    private val partFiles = HashMap<String, File>() // fileId -> temp part; guarded by [lock] (one connection per coroutine)

    override fun openWrite(meta: FileMetadata): Sink {
        downloadDir.mkdirs()
        val part = File(downloadDir, "${safeName(meta.id)}.part")
        synchronized(lock) { partFiles[meta.id] = part }
        return part.sink()
    }

    override fun discard(meta: FileMetadata) {
        synchronized(lock) { partFiles.remove(meta.id) }?.let { runCatching { it.delete() } }
    }

    override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> {
        val part = synchronized(lock) { partFiles.remove(meta.id) }
            ?: return Result.failure(IllegalStateException("no part file for ${meta.id}"))

        if (expectedSha256 != null) {
            val actual = runCatching { Sha256.hex(part.source()) }.getOrElse { e ->
                runCatching { part.delete() }
                return Result.failure(e)
            }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                runCatching { part.delete() }
                L.w { "[P2P][Receiver] integrity fail id=${meta.id} exp=${expectedSha256.take(12)} act=${actual.take(12)}" }
                return Result.failure(IllegalStateException("sha256 mismatch"))
            }
        }

        val finalFile = uniqueDestination(meta.fileName)
        return if (part.renameTo(finalFile)) {
            Result.success(finalFile.absolutePath)
        } else {
            // Cross-filesystem rename can fail; fall back to copy.
            runCatching {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
                finalFile.absolutePath
            }
        }
    }

    /** Resolves name collisions: `photo.jpg` → `photo (1).jpg`. */
    private fun uniqueDestination(fileName: String): File {
        val safe = safeName(fileName)
        var candidate = File(downloadDir, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(downloadDir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").ifBlank { "file" }
}
