package com.difft.android.chat.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import com.difft.android.selector.entity.LocalMedia

interface MediaTransform {

    /**
     * Produces the bytes to send for [media], returning the item to send.
     *
     * Contract: an implementation may rewrite [LocalMedia.realPath] to point at its output, and
     * must leave [LocalMedia.path] untouched — `path` stays the original source identity, which
     * both the review pager's stable item IDs and the outgoing filename fallback depend on.
     *
     * An implementation must not attempt to resolve the URI to send from: whether new bytes were
     * produced is decided once, at the transform boundary in `MediaSelectionRepository`, which is
     * the only place that can compare the path before and after this call.
     */
    @WorkerThread
    fun transform(context: Context, media: LocalMedia): LocalMedia
}
