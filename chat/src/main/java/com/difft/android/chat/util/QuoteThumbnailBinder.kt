package com.difft.android.chat.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawableTransformation
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.difft.android.base.utils.dp
import com.difft.android.chat.R

/**
 * Shared render slice for quote media previews, consumed by both the message-list ViewHolder (⑤)
 * and the input compose-bar Fragment (⑥). Holds only the genuinely shared logic: a rounded
 * center-crop Glide load and a static type-icon setter. Byte generation (input-only) and the
 * DB reverse-lookup (list-only) intentionally stay in their respective owners.
 */
object QuoteThumbnailBinder {

    /**
     * Loads [source] into [imageView] as a 4dp rounded center-crop thumbnail. No-ops if the host
     * Activity is finishing/destroyed (Glide.with would throw on a dead host).
     *
     * @param source a Glide-loadable model — typically a [ByteArray] (inline thumbnail) or
     *   [java.io.File] (on-disk original/thumbnail path).
     */
    fun loadRoundedThumbnail(imageView: ImageView, source: Any) {
        if (!imageView.isHostActivityAlive()) return
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        // Shared crop+corner spec applied to BOTH static bitmaps (.transform) AND animated WebP
        // (.optionalTransform). Without the WebpDrawable branch, a BitmapTransformation on an
        // animated WebP throws "Unable to convert WebpDrawable to a Bitmap" → the load fails and
        // falls back to .error(ic_file) rendered at CENTER_CROP (an oversized file icon). Mirrors
        // ImageAndVideoMessageView.
        val transform = MultiTransformation(CenterCrop(), RoundedCorners(4.dp))
        Glide.with(imageView)
            .load(source)
            .transform(transform)
            .optionalTransform(WebpDrawable::class.java, WebpDrawableTransformation(transform))
            .error(R.drawable.ic_file)
            .into(imageView)
    }

    /**
     * Sets a static type icon (e.g. mic for voice, [R.drawable.ic_file] for file/other) on
     * [imageView] at CENTER scale. Clears any pending Glide load first so a recycled view does not
     * flash a stale thumbnail before the icon resolves.
     *
     * No runtime tint: every quote type-icon drawable bakes in `@color/icon` (day/night adaptive)
     * directly — `ic_file` and `chat_ic_quote_mic` — so they stay size- and color-consistent
     * without per-view tinting (which would also discolor the real photo thumbnails this same
     * view shows).
     */
    fun setTypeIcon(imageView: ImageView, @DrawableRes resId: Int) {
        if (imageView.isHostActivityAlive()) Glide.with(imageView).clear(imageView)
        imageView.scaleType = ImageView.ScaleType.CENTER
        imageView.setImageResource(resId)
    }
}

/**
 * Walks the [ContextWrapper] chain (including Hilt's FragmentContextWrapper) to find the host
 * [Activity] and reports whether it is still alive. Returns `true` for a non-Activity context
 * (e.g. the application context) — there is nothing Activity-scoped to be torn down.
 */
fun View.isHostActivityAlive(): Boolean {
    var ctx: Context? = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return !ctx.isFinishing && !ctx.isDestroyed
        ctx = ctx.baseContext
    }
    return true
}
