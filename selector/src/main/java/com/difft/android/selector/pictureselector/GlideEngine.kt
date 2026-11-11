package com.difft.android.selector.pictureselector

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawableTransformation
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.difft.android.selector.engine.ImageEngine
import com.difft.android.selector.utils.ActivityCompatHelper

class GlideEngine private constructor() : ImageEngine {

    override fun loadImage(context: Context, url: String?, imageView: ImageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return
        }
        Glide.with(context)
            .load(url)
            .into(imageView)
    }

    override fun loadImage(context: Context, imageView: ImageView, url: String?, maxWidth: Int, maxHeight: Int) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return
        }
        Glide.with(context)
            .load(url)
            .override(maxWidth, maxHeight)
            .into(imageView)
    }

    override fun loadAlbumCover(context: Context, url: String?, imageView: ImageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return
        }
        
        val transform = MultiTransformation<Bitmap>(CenterCrop(), RoundedCorners(8))
        Glide.with(context)
            .load(url)
            .override(180, 180)
            .transform(transform)
            .optionalTransform(WebpDrawable::class.java, WebpDrawableTransformation(transform))
            .placeholder(com.difft.android.selector.R.drawable.ps_image_placeholder)
            .into(imageView)
    }

    override fun loadGridImage(context: Context, url: String?, imageView: ImageView) {
        if (!ActivityCompatHelper.assertValidRequest(context)) {
            return
        }
        
        val centerCrop = CenterCrop()
        Glide.with(context)
            .load(url)
            .override(200, 200)
            .transform(centerCrop)
            .optionalTransform(WebpDrawable::class.java, WebpDrawableTransformation(centerCrop))
            .placeholder(com.difft.android.selector.R.drawable.ps_image_placeholder)
            .into(imageView)
    }

    override fun pauseRequests(context: Context) {
        Glide.with(context).pauseRequests()
    }

    override fun resumeRequests(context: Context) {
        Glide.with(context).resumeRequests()
    }

    companion object {
        private val instance = GlideEngine()

        @JvmStatic
        fun createGlideEngine(): GlideEngine = instance
    }
}
