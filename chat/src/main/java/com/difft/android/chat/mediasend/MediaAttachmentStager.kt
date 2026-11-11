package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileInputStream
import java.io.InputStream









object MediaAttachmentStager {

    sealed interface StageResult {
        object Staged : StageResult
        data class Failed(val failure: MediaFailure) : StageResult
    }

    



    @WorkerThread
    fun stage(
        context: Context,
        source: Uri,
        destPath: String,
        mimeType: String,
        displayName: String?,
    ): StageResult = try {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val copied = openSource(context, source)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (copied) {
            StageResult.Staged
        } else {
            StageResult.Failed(failureFor(context, source, mimeType, displayName, cause = null))
        }
    } catch (e: Exception) {
        
        
        
        runCatching { File(destPath).delete() }
        StageResult.Failed(failureFor(context, source, mimeType, displayName, cause = e))
    }

    







    @WorkerThread
    internal fun openSource(context: Context, uri: Uri): InputStream? = when (uri.scheme) {
        null, "" -> uri.path?.let { FileInputStream(File(it)) }
        else -> context.contentResolver.openInputStream(uri)
    }

    private fun failureFor(
        context: Context,
        source: Uri,
        mimeType: String,
        displayName: String?,
        cause: Throwable?,
    ): MediaFailure = MediaFailureClassifier.classifyAndLog(
        context = context,
        uri = source,
        mimeType = mimeType,
        position = MediaFailureClassifier.NO_POSITION,
        displayName = displayName,
        cause = cause,
    )
}
