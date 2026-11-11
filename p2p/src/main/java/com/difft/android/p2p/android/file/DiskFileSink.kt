package com.difft.android.p2p.android.file

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.model.FileMetadata
import okio.Sink
import okio.sink
import okio.source
import java.io.File









class DiskFileSink(private val downloadDir: File) : FileSink {

    private val lock = Any()
    private val partFiles = HashMap<String, File>() 

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
            
            runCatching {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
                finalFile.absolutePath
            }
        }
    }

    
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
