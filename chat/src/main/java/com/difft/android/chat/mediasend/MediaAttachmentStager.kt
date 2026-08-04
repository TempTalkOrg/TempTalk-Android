package com.difft.android.chat.mediasend

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Copies an outgoing attachment's bytes from its source URI into the encrypted attachment dir, and
 * says whether that succeeded.
 *
 * The previous call site handed a bare path to a copy helper and dropped its boolean result, so a
 * source that could not be read produced no destination file yet the message was enqueued anyway
 * and the upload then retried against a file that had never existed.
 */
object MediaAttachmentStager {

    sealed interface StageResult {
        object Staged : StageResult
        data class Failed(val failure: MediaFailure) : StageResult
    }

    /**
     * [displayName] is the only item context available here: this runs per send, after the review
     * screen is gone, so there is no user-visible index to quote.
     */
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
        // Not IOException — see openSource: a revoked URI throws SecurityException. Deliberately
        // NOT Throwable, so Error semantics stay unchanged.
        // Never leave a truncated staged file behind for the upload to pick up.
        runCatching { File(destPath).delete() }
        StageResult.Failed(failureFor(context, source, mimeType, displayName, cause = e))
    }

    /**
     * The single "source URI -> stream" entry point.
     *
     * Three shapes actually reach here, and one of them has no scheme at all: the voice-message and
     * file-pre-send call sites build their URI from a bare absolute path, which leaves the scheme
     * null. `ContentResolver.openInputStream` cannot open that — there is no authority to resolve —
     * so normalizing here is what keeps those paths byte-identical to what they do today.
     */
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
