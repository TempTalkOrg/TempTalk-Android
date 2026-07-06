package com.difft.android.chat.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.utils.dp

/**
 * Shared render slice for quote media previews, consumed by both the message-list ViewHolder (⑤)
 * and the input compose-bar Fragment (⑥). Holds only the genuinely shared logic: a rounded
 * center-crop Glide load and a static type-icon setter. Byte generation (input-only) and the
 * DB reverse-lookup (list-only) intentionally stay in their respective owners.
 */
object QuoteThumbnailBinder {

    /**
     * View-level outline clip that rounds the thumbnail corners (4dp) independently of the Glide
     * decode/cache path — so it rounds ANY drawable (static bitmap / animated WebP / GifDrawable)
     * without a BitmapTransformation, which cannot be applied to a WebpDrawable. Mirrors
     * ImageAndVideoMessageView so quote and bubble share one corner mechanism (no duplicate logic).
     */
    private val roundedCornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 4.dp.toFloat())
        }
    }

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
        // Corners come from a view-level outline clip (see [roundedCornerOutline]), NOT a Glide
        // transform — so no BitmapTransformation is applied to any drawable, and animated WebP plays
        // without the "Unable to convert WebpDrawable to a Bitmap" freeze. Crop stays on CENTER_CROP.
        imageView.outlineProvider = roundedCornerOutline
        imageView.clipToOutline = true
        Glide.with(imageView)
            .load(source)
            // A content Uri here is the decrypting attachment provider — never let Glide persist the
            // decrypted source/result to its disk cache (would defeat encrypted-at-rest storage).
            .apply { if (source is Uri) diskCacheStrategy(DiskCacheStrategy.NONE) }
            // This view is used ONLY for image/video quotes. On failure (e.g. a self-sent attachment
            // whose plaintext was deleted post-upload before the .encrypt was resolvable, or a decode
            // error) do NOT fall back to a file icon: it is semantically wrong AND, at CENTER_CROP,
            // rendered oversized. Hide the thumbnail so the quote degrades cleanly to text-only.
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    imageView.visibility = View.GONE
                    return true // handled — suppress the default error drawable
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean = false
            })
            .into(imageView)
    }

    /**
     * Sets a static type icon (e.g. mic for voice, ic_file for file/other) on
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
        // Reset the outline clip a prior thumbnail bind may have left on this recycled view: type
        // icons render at CENTER with no rounding (matching the pre-view-clip behavior).
        imageView.clipToOutline = false
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
