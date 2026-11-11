package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.media.JpegGpsStripper
import com.difft.android.base.utils.FileUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object ImageSendFileOps {
    data class GpsOutput(val file: File?, val reason: JpegGpsStripper.FailureReason?)

    fun isJpeg(context: Context, input: Uri): Boolean = open(context, input).use {
        it.read() == 0xff && it.read() == 0xd8 && it.read() == 0xff
    }

    fun deleteDraftInput(input: Uri, output: File): Boolean = deleteDraftInput(input, listOf(output))

    fun deleteDraftInput(input: Uri, outputs: Collection<File>): Boolean {
        val file = retirableDraftInput(input, outputs) ?: return false
        val deleted = delete(file)
        input.path?.let(FileUtil::invalidateFileValidity)
        return deleted
    }

    fun retirableDraftInput(input: Uri, outputs: Collection<File>): File? {
        val file = draftFile(input) ?: return null
        
        if (!runCatching { outputs.none { file == it.canonicalFile } }.getOrDefault(false)) return null
        
        return File(input.path ?: return null)
    }

    fun stripJpeg(context: Context, source: Uri, extension: String): GpsOutput {
        
        return open(context, source).use { input ->
            val original = draftFile(source)
            var target: File? = original
            var backup: File? = null
            var retained: File? = original
            var stripStarted = false

            fun failed(reason: JpegGpsStripper.FailureReason): GpsOutput {
                if (original != null && stripStarted) {
                    val saved = checkNotNull(backup)
                    
                    retained = runCatching {
                        Files.move(saved.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        original
                    }.getOrElse { saved }
                }
                return GpsOutput(retained, reason)
            }

            try {
                val directory = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY))
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("Cannot create draft directory")
                }
                fun newFile() = File(directory, "${UUID.randomUUID()}.${extension.ifBlank { "jpg" }}")
                val working = original ?: newFile().also { target = it }
                val copy = if (original != null) newFile().also { backup = it } else working
                copy.outputStream().use { input.copyTo(it) }
                stripStarted = true
                when (val result = JpegGpsStripper.strip(working)) {
                    JpegGpsStripper.StripResult.Stripped -> {
                        retained = working
                        GpsOutput(working, null)
                    }
                    JpegGpsStripper.StripResult.NotJpeg -> failed(JpegGpsStripper.FailureReason.NOT_JPEG)
                    is JpegGpsStripper.StripResult.Failed -> failed(result.reason)
                }
            } catch (_: OutOfMemoryError) {
                failed(JpegGpsStripper.FailureReason.OOM)
            } catch (_: Exception) {
                failed(JpegGpsStripper.FailureReason.IO)
            } finally {
                target?.takeIf { it != retained }?.let(::delete)
                backup?.takeIf { it != retained }?.let(::delete)
            }
        }
    }

    private fun open(context: Context, input: Uri): InputStream =
        context.contentResolver.openInputStream(input) ?: throw FileNotFoundException("Image source unavailable")

    private fun draftFile(input: Uri): File? {
        if (input.scheme != "file") return null
        return try {
            val file = File(input.path ?: return null).canonicalFile
            val root = File(FileUtil.getFilePath(FileUtil.DRAFT_ATTACHMENTS_DIRECTORY)).canonicalPath
            file.takeIf { it.path.startsWith(root + File.separator) && it.isFile }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun delete(file: File): Boolean =
        try {
            val existed = file.exists()
            if (existed && !file.delete()) {
                L.w { "[MediaSend] cleanup result=failed reason=io" }
                false
            } else {
                FileUtil.invalidateFileValidity(file.path)
                existed
            }
        } catch (_: Exception) {
            L.w { "[MediaSend] cleanup result=failed reason=io" }
            false
        }
}
