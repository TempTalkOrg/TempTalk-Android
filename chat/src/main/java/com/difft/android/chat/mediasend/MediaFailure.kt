package com.difft.android.chat.mediasend

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.difft.android.base.android.permission.MediaReadDenialKind
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppPrivateStorage
import com.difft.android.video.exceptions.VideoPostProcessingException
import com.difft.android.video.exceptions.VideoSizeException
import com.difft.android.video.exceptions.VideoSourceException
import com.difft.android.video.videoconverter.exceptions.EncodingException
import com.difft.android.video.videoconverter.muxer.MuxingException
import java.io.FileNotFoundException

/**
 * The user-facing category of a single media item's send failure. Capped at five members on purpose:
 * each one must map to a *different next action* for the user, otherwise it is noise. There is no
 * "upload failed" member because this surface closes before anything is enqueued — the chain it
 * covers makes no network call, and upload failures already have their own surface on the bubble.
 *
 * [retryable] answers "would pressing retry, right now, do anything different?" A failure whose fix
 * needs an out-of-band action (grant, restart, free space, pick another file) must NOT be offered a
 * retry entry, because that entry is a promise that cannot be kept.
 *
 * [code] is the stable support code carried by the one structured log line, so a single entry is
 * enough to classify a report with no repro.
 */
enum class MediaFailureReason(val retryable: Boolean, val code: String) {
    SOURCE_UNREADABLE(retryable = false, code = "MSND-01"),
    MEDIA_UNSUPPORTED(retryable = false, code = "MSND-02"),
    OUT_OF_SPACE(retryable = false, code = "MSND-03"),
    TRANSFORM_FAILED(retryable = true, code = "MSND-04"),
    UNKNOWN(retryable = true, code = "MSND-05"),
}

/**
 * One failed item. [position] is the 1-based index the user sees on the review screen; it is
 * [MediaFailureClassifier.NO_POSITION] when the failure happens after that screen is gone and no
 * meaningful index exists.
 */
data class MediaFailure(
    val position: Int,
    val displayName: String?,
    val reason: MediaFailureReason,
    val denialKind: MediaReadDenialKind?,
    val cause: Throwable?,
)

/**
 * The single place a media send failure becomes a category, a code and one log line.
 *
 * Classification never guesses from the exception type alone: the transcoder reports both "cannot
 * read" and "unsupported or corrupt" as [VideoSourceException] with no [ErrnoException] in the cause
 * chain, so a type table would blame the format for what is actually a denied read. That ambiguous
 * branch opens the item for real — one byte — which is also the only permitted way to establish
 * readability, since exists()/length()/isFile all return success values on the affected devices.
 */
object MediaFailureClassifier {

    /** No user-visible index available (staging, or a throwable with no item context). */
    const val NO_POSITION: Int = 0

    private const val MAX_CAUSE_DEPTH = 8
    private const val UNKNOWN_ERRNO = -1
    private const val NOT_AVAILABLE = "n/a"
    private const val NONE = "none"

    /** Leading public directory of a shared-storage path, e.g. "Movies" in /storage/emulated/0/Movies/x. */
    private val PUBLIC_ROOT = Regex("""^/storage/[^/]+/(?:[0-9]+/)?([^/]+)/""")

    /** Classifies an ALREADY-OBSERVED failure and emits exactly one [L.e] line for it. */
    @WorkerThread
    fun classifyAndLog(
        context: Context,
        uri: Uri,
        mimeType: String?,
        position: Int,
        displayName: String?,
        cause: Throwable?,
    ): MediaFailure {
        val reason = classifyReason(context, uri, cause)
        val denialKind = if (reason == MediaFailureReason.SOURCE_UNREADABLE) {
            PermissionUtil.classifyReadDenial(uri, cause, context).kind
        } else null
        val failure = MediaFailure(position, displayName, reason, denialKind, cause)
        L.e { denialLogLine(uri, mimeType, failure) }
        return failure
    }

    /**
     * Classification for a throwable with no item context. Safe on the main thread: with no URI there
     * is nothing to open, so the ambiguous types stay [MediaFailureReason.UNKNOWN] instead of being
     * reported as an unsupported format — naming a cause that was never established is exactly the
     * mis-attribution this classifier exists to prevent.
     */
    fun classifyThrown(cause: Throwable): MediaFailure {
        val reason = errnoReason(cause)
            ?: readDenialTypeReason(cause)
            ?: transformTypeReason(cause)
            ?: MediaFailureReason.UNKNOWN
        val failure = MediaFailure(NO_POSITION, null, reason, null, cause)
        L.e { thrownLogLine(failure) }
        return failure
    }

    /** True when [uri] can actually be opened right now. The ONLY sanctioned readability check. */
    @WorkerThread
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        MediaAttachmentStager.openSource(context, uri)?.use { input ->
            // Read one byte: a provider may defer the real open until the first read. EOF (-1) on a
            // zero-byte item still means the open succeeded, so the value is discarded.
            input.read()
            true
        } ?: false
    } catch (e: Exception) {
        // Not IOException: a revoked URI throws SecurityException. Deliberately NOT Throwable, so
        // Error semantics (OutOfMemoryError above all) stay exactly as they are today. Silent because
        // the one classifier log line already reports the failure.
        false
    }

    @VisibleForTesting
    @WorkerThread
    internal fun classifyReason(context: Context, uri: Uri, cause: Throwable?): MediaFailureReason {
        errnoReason(cause)?.let { return it }
        // SecurityException is the one type kept out of the probe: a content URI is opened through
        // the very ContentResolver.openInputStream that threw it, so the probe can only reproduce
        // the same revoked-grant failure. Answering here is the same verdict without a second
        // Binder round trip. FileNotFoundException gets no such exemption — see below.
        if (causeChain(cause).any { it is SecurityException }) return MediaFailureReason.SOURCE_UNREADABLE
        // Only an actual open can tell "denied" from "unsupported" or "the destination failed", so
        // the probe gates every remaining category: an exception over a source that cannot be read
        // is a read failure, not a processing failure.
        if (!isReadable(context, uri)) return MediaFailureReason.SOURCE_UNREADABLE
        val chain = causeChain(cause).toList()
        return when {
            chain.any { isUnsupportedMediaType(it) } -> MediaFailureReason.MEDIA_UNSUPPORTED
            chain.any { isTransformFailureType(it) } -> MediaFailureReason.TRANSFORM_FAILED
            // The source opened fine, so a "no such file" in the chain is about the write side of
            // the staging copy — its destination — not the item the user picked. That is a
            // processing failure whose retry can succeed, and must not spend the source's
            // non-retryable verdict.
            chain.any { it is FileNotFoundException } -> MediaFailureReason.TRANSFORM_FAILED
            else -> MediaFailureReason.UNKNOWN
        }
    }

    /**
     * The one structured line per failed item. Deliberately does NOT include stackTraceToString():
     * FileNotFoundException's message IS the absolute path, so the normally-whitelisted stack dump
     * would put a file name in the log file. The cause-type chain plus errno carries the same
     * diagnostic value without it.
     */
    @VisibleForTesting
    internal fun denialLogLine(uri: Uri, mimeType: String?, failure: MediaFailure): String =
        "[MediaAccess] read denied code=${failure.reason.code} reason=${failure.reason.name}" +
            " kind=${failure.denialKind?.name ?: NOT_AVAILABLE} scheme=${uri.scheme ?: NONE}" +
            " authority=${uri.authority ?: NONE} dir=${logDir(uri)} mime=${mimeType ?: NONE}" +
            " sdk=${Build.VERSION.SDK_INT} errno=${errnoName(failure.cause)}" +
            " cause=${causeTypeChain(failure.cause)}"

    /** Same discipline as [denialLogLine], minus the URI fields there is no URI for. */
    @VisibleForTesting
    internal fun thrownLogLine(failure: MediaFailure): String =
        "[MediaSend] send failed code=${failure.reason.code} reason=${failure.reason.name}" +
            " errno=${errnoName(failure.cause)} cause=${causeTypeChain(failure.cause)}"

    /**
     * A location coarse enough to be safe and specific enough to be useful: the top-level public
     * directory ("Movies", "DCIM"), "private" for our own storage, or a content URI's leading path
     * segments ("external/video"). Never a file name.
     */
    @VisibleForTesting
    internal fun logDir(uri: Uri): String = when {
        uri.scheme == ContentResolver.SCHEME_CONTENT ->
            uri.pathSegments.take(2).joinToString("/").ifEmpty { NONE }
        else -> uri.path?.let { path ->
            if (AppPrivateStorage.isAppPrivate(path)) "private"
            else PUBLIC_ROOT.find(path)?.groupValues?.get(1) ?: "other"
        } ?: NONE
    }

    /** e.g. "EACCES", or "n/a" when no [ErrnoException] carried the failure. */
    @VisibleForTesting
    internal fun errnoName(cause: Throwable?): String = errnoOf(cause)
        .takeIf { it != UNKNOWN_ERRNO }
        ?.let { OsConstants.errnoName(it) ?: it.toString() }
        ?: NOT_AVAILABLE

    /** e.g. "VideoSourceException<-RuntimeException" — types only, never messages. */
    @VisibleForTesting
    internal fun causeTypeChain(cause: Throwable?): String =
        causeChain(cause).joinToString("<-") { it.javaClass.simpleName }.ifEmpty { "none" }

    /** Depth-capped so a cause cycle cannot spin; an ExecutionException unwraps through here too. */
    @VisibleForTesting
    internal fun causeChain(t: Throwable?): Sequence<Throwable> =
        generateSequence(t) { cur -> cur.cause?.takeIf { it !== cur } }.take(MAX_CAUSE_DEPTH)

    /**
     * ENOENT lands on SOURCE_UNREADABLE rather than a dedicated "file is gone" category: a missing
     * MediaStore file and a cleaned-up sandbox file share the same next step ("pick it again"), which
     * the denial kind already words separately.
     */
    private fun errnoReason(cause: Throwable?): MediaFailureReason? = when (errnoOf(cause)) {
        OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT -> MediaFailureReason.SOURCE_UNREADABLE
        OsConstants.ENOSPC, OsConstants.EDQUOT -> MediaFailureReason.OUT_OF_SPACE
        else -> null
    }

    /**
     * The type table for the no-URI path only. With nothing to open, a [FileNotFoundException]
     * cannot be told apart from a denied read, so it is reported as one.
     *
     * The URI-carrying path must NOT use this: there, a write-side [FileNotFoundException] — the
     * staging copy's destination — would be blamed on a source that opens perfectly well and would
     * lose its retry entry. [classifyReason] probes instead.
     */
    private fun readDenialTypeReason(cause: Throwable?): MediaFailureReason? =
        MediaFailureReason.SOURCE_UNREADABLE
            .takeIf { causeChain(cause).any { t -> t is SecurityException || t is FileNotFoundException } }

    private fun transformTypeReason(cause: Throwable?): MediaFailureReason? =
        MediaFailureReason.TRANSFORM_FAILED.takeIf { causeChain(cause).any { t -> isTransformFailureType(t) } }

    private fun isTransformFailureType(t: Throwable): Boolean =
        t is EncodingException || t is MuxingException ||
            t is VideoSizeException || t is VideoPostProcessingException

    private fun isUnsupportedMediaType(t: Throwable): Boolean =
        t is VideoSourceException || t is IllegalArgumentException || t is UnsupportedOperationException

    private fun errnoOf(t: Throwable?): Int =
        causeChain(t).filterIsInstance<ErrnoException>().firstOrNull()?.errno ?: UNKNOWN_ERRNO
}
