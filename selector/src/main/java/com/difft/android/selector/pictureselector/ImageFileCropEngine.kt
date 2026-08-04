package com.difft.android.selector.pictureselector

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.difft.android.selector.engine.CropFileEngine
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.utils.StyleUtils
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropImageEngine
import java.util.ArrayList

class ImageFileCropEngine(
    private val context: Context,
    private val selectorStyle: PictureSelectorStyle?
) : CropFileEngine {

    override fun onStartCrop(
        fragment: Fragment,
        srcUri: Uri,
        destinationUri: Uri,
        dataSource: ArrayList<String>,
        requestCode: Int
    ) {
        // 防止在 Android 9 等设备上因 dataSource 为空导致 UCropMultipleActivity 崩溃
        if (dataSource.isEmpty()) {
            return
        }
        val options = buildOptions()
        val uCrop = UCrop.of(srcUri, destinationUri, dataSource)
        uCrop.withOptions(options)
        uCrop.setImageEngine(object : UCropImageEngine {
            override fun loadImage(context: Context, url: String, imageView: ImageView) {
                if (!ImageLoaderUtils.assertValidRequest(context)) {
                    return
                }
                Glide.with(context).load(url).override(180, 180).into(imageView)
            }

            override fun loadImage(context: Context, url: Uri, maxWidth: Int, maxHeight: Int, call: UCropImageEngine.OnCallbackListener<Bitmap>?) {
                Glide.with(context).asBitmap().load(url).override(maxWidth, maxHeight).into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        call?.onCall(resource)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        call?.onCall(null)
                    }
                })
            }
        })
        uCrop.start(fragment.requireActivity(), fragment, requestCode)
    }

    /**
     * 配制UCrop，可根据需求自我扩展
     */
    private fun buildOptions(): UCrop.Options {
        val options = UCrop.Options()
        options.setCircleDimmedLayer(true)
        options.isCropDragSmoothToCenter(false)
        options.isForbidSkipMultipleCrop(true)
        options.setHideBottomControls(true)
        options.setMaxScaleMultiplier(100f)

        // uCrop reads colors as hard ARGB values (window.setStatusBarColor / toolbar paint).
        // It does NOT auto-switch on theme change. Resolve dark/light explicitly so the
        // crop screen matches the app's current night mode.
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES

        val barColor = ContextCompat.getColor(context, com.difft.android.base.R.color.bg)
        val textColor = ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary)

        options.setRootViewBackgroundColor(barColor)
        options.setStatusBarColor(barColor)
        options.setToolbarColor(barColor)
        options.setToolbarWidgetColor(textColor)
        // status bar icons: dark mode → light icons (false); light mode → dark icons (true).
        options.isDarkStatusBarBlack(!isDark)

        // Allow selectorStyle to override status/toolbar color when set explicitly.
        if (selectorStyle != null && selectorStyle.selectMainStyle!!.statusBarColor != 0) {
            val mainStyle = selectorStyle.selectMainStyle!!
            val statusBarColor = mainStyle.statusBarColor
            if (StyleUtils.checkStyleValidity(statusBarColor)) {
                options.setStatusBarColor(statusBarColor)
                options.setToolbarColor(statusBarColor)
                options.isDarkStatusBarBlack(mainStyle.isDarkStatusBarBlack)
            }
            val titleBarStyle = selectorStyle.titleBarStyle!!
            if (StyleUtils.checkStyleValidity(titleBarStyle.titleTextColor)) {
                options.setToolbarWidgetColor(titleBarStyle.titleTextColor)
            }
        }

        return options
    }
}
