package com.difft.android.chat.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.utils.dp







object QuoteThumbnailBinder {

    





    private val roundedCornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 4.dp.toFloat())
        }
    }

    







    fun loadRoundedThumbnail(imageView: ImageView, source: Any, rounded: Boolean = true) {
        if (!imageView.isHostActivityAlive()) return
        
        imageView.imageTintList = null
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        
        
        
        imageView.outlineProvider = roundedCornerOutline
        imageView.clipToOutline = rounded
        Glide.with(imageView)
            .load(source)
            
            
            .apply { if (source is Uri) diskCacheStrategy(DiskCacheStrategy.NONE) }
            
            
            
            
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    imageView.visibility = View.GONE
                    return true 
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean = false
            })
            .into(imageView)
    }

    






    fun setTypeIcon(imageView: ImageView, @DrawableRes resId: Int, @ColorInt tint: Int? = null) {
        if (imageView.isHostActivityAlive()) Glide.with(imageView).clear(imageView)
        
        
        imageView.clipToOutline = false
        imageView.scaleType = ImageView.ScaleType.CENTER
        imageView.imageTintList = tint?.let(ColorStateList::valueOf)
        imageView.setImageResource(resId)
    }
}






fun View.isHostActivityAlive(): Boolean {
    var ctx: Context? = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return !ctx.isFinishing && !ctx.isDestroyed
        ctx = ctx.baseContext
    }
    return true
}
