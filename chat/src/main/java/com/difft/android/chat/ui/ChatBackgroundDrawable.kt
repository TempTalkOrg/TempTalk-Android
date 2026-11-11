package com.difft.android.chat.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.chat.ui.theme.ChatTheme
import com.difft.android.chat.ui.theme.ChatThemeSetting
import com.difft.android.chat.util.ViewUtil
import java.util.Collections
import java.util.WeakHashMap

class ChatBackgroundDrawable(
    context: Context,
    private val view: View? = null, 
    private val drawSystemBars: Boolean = false,
    
    private val drawStatusBarStrip: Boolean = true,
) : Drawable() {

    private val appContext = context.applicationContext
    private val configuration = Configuration(context.resources.configuration)
    private var wallpaper = newPainter(ChatThemeSetting.current)
    private val backdropOwnerLocation = IntArray(2)
    private val backdropViewportLocation = IntArray(2)

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, com.difft.android.base.R.color.bg1)
        isAntiAlias = true
    }

    init {
        live.add(this)
    }

    
    private fun currentWallpaper(): ChatWallpaperPainter {
        val theme = ChatThemeSetting.current
        if (wallpaper.theme != theme) {
            val alpha = wallpaper.alpha
            val filter = wallpaper.colorFilter
            wallpaper = newPainter(theme).also {
                it.alpha = alpha
                it.colorFilter = filter
            }
        }
        return wallpaper
    }

    private fun newPainter(theme: ChatTheme) =
        ChatWallpaperPainter(appContext.createConfigurationContext(configuration), theme).also { it.onPatternReady = ::invalidateSelf }

    override fun draw(canvas: Canvas) {
        val bounds = bounds

        
        
        val barInsets = if (drawSystemBars && view != null) systemBarInsets(view) else null
        val statusBarHeight = if (drawStatusBarStrip) barInsets?.top ?: 0 else 0
        val navigationBarHeight = barInsets?.bottom ?: 0

        if (drawSystemBars && view != null) {
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), statusBarHeight.toFloat(), paint)

            canvas.drawRect(
                0f,
                (bounds.height() - navigationBarHeight).toFloat(),
                bounds.width().toFloat(),
                bounds.height().toFloat(),
                paint
            )
        }

        canvas.save()
        canvas.clipRect(bounds)
        if (drawSystemBars) {
            canvas.clipRect(
                0,
                statusBarHeight,
                bounds.width(),
                bounds.height() - navigationBarHeight
            )
        }
        currentWallpaper().draw(canvas, bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        canvas.restore()
    }

    
    internal fun drawWallpaperFor(canvas: Canvas, viewport: View) {
        
        view?.getLocationInWindow(backdropOwnerLocation)
        viewport.getLocationInWindow(backdropViewportLocation)
        val left = (backdropOwnerLocation[0] - backdropViewportLocation[0]).toFloat()
        val top = (backdropOwnerLocation[1] - backdropViewportLocation[1]).toFloat()
        currentWallpaper().draw(canvas, left + bounds.left, top + bounds.top, left + bounds.right, top + bounds.bottom)
    }

    private fun systemBarInsets(view: View): androidx.core.graphics.Insets {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?: return androidx.core.graphics.Insets.of(0, ViewUtil.getStatusBarHeight(view), 0, ViewUtil.getNavigationBarHeight(view))
        return insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    }

    override fun setAlpha(alpha: Int) {
        wallpaper.alpha = alpha
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        wallpaper.colorFilter = colorFilter
        paint.colorFilter = colorFilter
    }

    override fun getAlpha(): Int {
        return wallpaper.alpha
    }

    override fun isStateful(): Boolean {
        return wallpaper.isStateful
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int {
        return wallpaper.opacity
    }

    internal companion object {
        private val live: MutableSet<ChatBackgroundDrawable> = Collections.newSetFromMap(WeakHashMap())

        
        fun themeChanged() {
            live.toList().forEach { it.invalidateSelf() }
        }
    }
}
