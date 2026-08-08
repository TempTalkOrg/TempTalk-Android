package com.difft.android.chat.mediasend

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.entity.LocalMedia
import java.io.File

/**
 * Single source of truth for "a URI that is actually readable" across all three
 * MediaSelectionActivity entry points.
 *
 * Gallery / camera-cell items carry a MediaStore content URI in [LocalMedia.path] on API 29+;
 * SAF and file-attachment entries only fill [LocalMedia.realPath] with a sandbox absolute path
 * and leave [LocalMedia.path] empty. Reading through the content URI makes "is this process's
 * legacy storage view in effect" irrelevant, because MediaProvider opens the fd in its own
 * process and hands it back over Binder.
 *
 * Pure function: no IO, no permission calls (no takePersistableUriPermission / grantUriPermission)
 * — it only inspects the scheme of an already-granted path.
 *
 * PRECONDITION: valid for the *source* media only. After a [MediaTransform] rewrites
 * [LocalMedia.realPath] to a sandbox output, [LocalMedia.path] still points at the original
 * source, so this function would return the pre-edit media. The post-transform URI is resolved
 * once, at the transform boundary, by MediaSelectionRepository (see SendableMedia).
 */
fun LocalMedia.readableUri(): Uri {
    val contentPath = path
    if (contentPath.isNotEmpty() && PictureMimeType.isContent(contentPath)) {
        return Uri.parse(contentPath)
    }
    if (realPath.isEmpty()) {
        // Unreachable for all three entry points (every one fills realPath). Named here so a
        // future writer that skips it surfaces as one log line instead of a misleading
        // "file:/// not found" several layers downstream.
        L.w { "[MediaAccess] blank source id=$id mime=$mimeType" }
        return Uri.EMPTY
    }
    // Uri.fromFile, never Uri.parse: a bare absolute path parses into a scheme-less relative URI,
    // which regresses both sandbox entry points that depend on a file:// scheme downstream.
    return Uri.fromFile(File(realPath))
}

/**
 * Identity of a media item inside the selection/review session — the key domain of
 * MediaSelectionState.editorStateMap.
 *
 * Deliberately a distinct type from [Uri]: every read and write of the editor-state map must
 * derive its key from [mediaKey], so a stale `realPath.toUri()` cannot type-check its way back
 * in. A mismatch between the writing key and the reading key silently discards the user's
 * crop / drawing / trim with no error at all, which is why this is enforced by the compiler
 * rather than by review.
 *
 * Not Parcelable on purpose: fragment arguments keep carrying a plain [Uri], so the key domain
 * cannot leak across process boundaries and widen this contract.
 */
@JvmInline
value class MediaKey(val uri: Uri)

fun LocalMedia.mediaKey(): MediaKey = MediaKey(readableUri())
