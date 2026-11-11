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














enum class MediaFailureReason(val retryable: Boolean, val code: String) {
    SOURCE_UNREADABLE(retryable = false, code = "MSND-01"),
    MEDIA_UNSUPPORTED(retryable = false, code = "MSND-02"),
    OUT_OF_SPACE(retryable = false, code = "MSND-03"),
    TRANSFORM_FAILED(retryable = true, code = "MSND-04"),
    UNKNOWN(retryable = true, code = "MSND-05"),
}






data class MediaFailure(
    val position: Int,
    val displayName: String?,
    val reason: MediaFailureReason,
    val denialKind: MediaReadDenialKind?,
    val cause: Throwable?,
)










object MediaFailureClassifier {

    
    const val NO_POSITION: Int = 0

    private const val MAX_CAUSE_DEPTH = 8
    private const val UNKNOWN_ERRNO = -1
    private const val NOT_AVAILABLE = "n/a"
    private const val NONE = "none"

    
    private val PUBLIC_ROOT = Regex("""^/storage/[^/]+/(?:[0-9]+/)?([^/]+)/""")

    
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

    





    fun classifyThrown(cause: Throwable): MediaFailure {
        val reason = errnoReason(cause)
            ?: readDenialTypeReason(cause)
            ?: transformTypeReason(cause)
            ?: MediaFailureReason.UNKNOWN
        val failure = MediaFailure(NO_POSITION, null, reason, null, cause)
        L.e { thrownLogLine(failure) }
        return failure
    }

    
    @WorkerThread
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        MediaAttachmentStager.openSource(context, uri)?.use { input ->
            
            
            input.read()
            true
        } ?: false
    } catch (e: Exception) {
        
        
        
        false
    }

    @VisibleForTesting
    @WorkerThread
    internal fun classifyReason(context: Context, uri: Uri, cause: Throwable?): MediaFailureReason {
        errnoReason(cause)?.let { return it }
        
        
        
        
        if (causeChain(cause).any { it is SecurityException }) return MediaFailureReason.SOURCE_UNREADABLE
        
        
        
        if (!isReadable(context, uri)) return MediaFailureReason.SOURCE_UNREADABLE
        val chain = causeChain(cause).toList()
        return when {
            chain.any { isUnsupportedMediaType(it) } -> MediaFailureReason.MEDIA_UNSUPPORTED
            chain.any { isTransformFailureType(it) } -> MediaFailureReason.TRANSFORM_FAILED
            
            
            
            
            chain.any { it is FileNotFoundException } -> MediaFailureReason.TRANSFORM_FAILED
            else -> MediaFailureReason.UNKNOWN
        }
    }

    





    @VisibleForTesting
    internal fun denialLogLine(uri: Uri, mimeType: String?, failure: MediaFailure): String =
        "[MediaAccess] read denied code=${failure.reason.code} reason=${failure.reason.name}" +
            " kind=${failure.denialKind?.name ?: NOT_AVAILABLE} scheme=${uri.scheme ?: NONE}" +
            " authority=${uri.authority ?: NONE} dir=${logDir(uri)} mime=${mimeType ?: NONE}" +
            " sdk=${Build.VERSION.SDK_INT} errno=${errnoName(failure.cause)}" +
            " cause=${causeTypeChain(failure.cause)}"

    
    @VisibleForTesting
    internal fun thrownLogLine(failure: MediaFailure): String =
        "[MediaSend] send failed code=${failure.reason.code} reason=${failure.reason.name}" +
            " errno=${errnoName(failure.cause)} cause=${causeTypeChain(failure.cause)}"

    




    @VisibleForTesting
    internal fun logDir(uri: Uri): String = when {
        uri.scheme == ContentResolver.SCHEME_CONTENT ->
            uri.pathSegments.take(2).joinToString("/").ifEmpty { NONE }
        else -> uri.path?.let { path ->
            if (AppPrivateStorage.isAppPrivate(path)) "private"
            else PUBLIC_ROOT.find(path)?.groupValues?.get(1) ?: "other"
        } ?: NONE
    }

    
    @VisibleForTesting
    internal fun errnoName(cause: Throwable?): String = errnoOf(cause)
        .takeIf { it != UNKNOWN_ERRNO }
        ?.let { OsConstants.errnoName(it) ?: it.toString() }
        ?: NOT_AVAILABLE

    
    @VisibleForTesting
    internal fun causeTypeChain(cause: Throwable?): String =
        causeChain(cause).joinToString("<-") { it.javaClass.simpleName }.ifEmpty { "none" }

    
    @VisibleForTesting
    internal fun causeChain(t: Throwable?): Sequence<Throwable> =
        generateSequence(t) { cur -> cur.cause?.takeIf { it !== cur } }.take(MAX_CAUSE_DEPTH)

    




    private fun errnoReason(cause: Throwable?): MediaFailureReason? = when (errnoOf(cause)) {
        OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT -> MediaFailureReason.SOURCE_UNREADABLE
        OsConstants.ENOSPC, OsConstants.EDQUOT -> MediaFailureReason.OUT_OF_SPACE
        else -> null
    }

    







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
