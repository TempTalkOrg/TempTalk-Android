package com.difft.android.chat.video.videoconverter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import com.difft.android.base.concurrent.AppExecutors
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.DecryptableUriMediaInput
import com.difft.android.chat.util.ViewUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.video.interfaces.MediaInput
import com.difft.android.video.videoconverter.VideoThumbnailsExtractor
import java.io.IOException
import java.lang.ref.WeakReference







abstract class VideoThumbnailsView : View {

    @JvmField protected var currentUri: Uri? = null

    private var input: MediaInput? = null

    @Volatile
    private var thumbnails: ArrayList<Bitmap>? = null
    private var thumbnailsJob: Job? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempRect = RectF()
    private val drawRect = Rect()
    private val tempDrawRect = Rect()
    private var duration: Long = 0

    @JvmField protected val clippingPath = Path()

    






    private val extractionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    


    @Throws(IOException::class)
    fun setInput(uri: Uri): Boolean {
        if (uri == currentUri) return false

        currentUri = uri
        input = DecryptableUriMediaInput.createForUri(context, uri)
        thumbnails = null
        thumbnailsJob?.cancel()
        thumbnailsJob = null
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        thumbnails = null
        thumbnailsJob?.cancel()
        thumbnailsJob = null
        
        
        extractionScope.coroutineContext.cancelChildren()

        input?.let {
            try {
                it.close()
            } catch (e: IOException) {
                L.w(e) { "$TAG close input failed" }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentInput = input ?: return

        val left = paddingLeft
        val top = paddingTop
        val right = width - paddingRight
        val bottom = height - paddingBottom

        clippingPath.reset()
        clippingPath.addRoundRect(
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
            CORNER_RADIUS.toFloat(), CORNER_RADIUS.toFloat(), Path.Direction.CW,
        )

        tempDrawRect.set(left, top, right, bottom)

        if (drawRect != tempDrawRect) {
            drawRect.set(tempDrawRect)
            thumbnails = null
            thumbnailsJob?.cancel()
            thumbnailsJob = null
        }

        val current = thumbnails
        if (current == null) {
            if (thumbnailsJob == null) {
                val thumbnailCount = drawRect.width() / drawRect.height()
                val thumbnailWidth = drawRect.width().toFloat() / thumbnailCount
                val thumbnailHeight = drawRect.height().toFloat()

                val list = ArrayList<Bitmap>(thumbnailCount)
                thumbnails = list
                thumbnailsJob = launchExtraction(
                    input = currentInput,
                    thumbnailWidth = thumbnailWidth,
                    thumbnailHeight = thumbnailHeight,
                    thumbnailCount = thumbnailCount,
                    collector = list,
                )
            }
        } else {
            val thumbnailCount = drawRect.width() / drawRect.height()
            val thumbnailWidth = drawRect.width().toFloat() / thumbnailCount
            val thumbnailHeight = drawRect.height().toFloat()

            tempRect.top = drawRect.top.toFloat()
            tempRect.bottom = drawRect.bottom.toFloat()
            canvas.withClip(clippingPath) {
                for (i in current.indices) {
                    tempRect.left = drawRect.left + i * thumbnailWidth
                    tempRect.right = tempRect.left + thumbnailWidth

                    val thumbnailBitmap = current[i] ?: continue
                    withRotation(180f, tempRect.centerX(), tempRect.centerY()) {
                        tempDrawRect.set(0, 0, thumbnailBitmap.width, thumbnailBitmap.height)
                        if (tempDrawRect.width() * thumbnailHeight > tempDrawRect.height() * thumbnailWidth) {
                            val w = tempDrawRect.height() * thumbnailWidth / thumbnailHeight
                            tempDrawRect.left = tempDrawRect.centerX() - (w / 2).toInt()
                            tempDrawRect.right = tempDrawRect.left + w.toInt()
                        } else {
                            val h = tempDrawRect.width() * thumbnailHeight / thumbnailWidth
                            tempDrawRect.top = tempDrawRect.centerY() - (h / 2).toInt()
                            tempDrawRect.bottom = tempDrawRect.top + h.toInt()
                        }
                        drawBitmap(thumbnailBitmap, tempDrawRect, tempRect, paint)
                    }
                }
            }
        }
    }

    private fun setDuration(duration: Long) {
        if (this.duration != duration) {
            this.duration = duration
            afterDurationChange(duration)
        }
    }

    protected abstract fun afterDurationChange(duration: Long)

    fun getDuration(): Long = duration

    private fun launchExtraction(
        input: MediaInput,
        thumbnailWidth: Float,
        thumbnailHeight: Float,
        thumbnailCount: Int,
        collector: ArrayList<Bitmap>,
    ): Job {
        val viewRef = WeakReference(this)
        
        
        
        val sourceScheme = currentUri?.scheme
        return extractionScope.launch {
            L.i { "$TAG generate $thumbnailCount thumbnails ${thumbnailWidth}x$thumbnailHeight" }
            var discoveredDuration = 0L

            VideoThumbnailsExtractor.extractThumbnails(
                input,
                thumbnailCount,
                thumbnailHeight.toInt(),
                object : VideoThumbnailsExtractor.Callback {
                    override fun durationKnown(duration: Long) {
                        discoveredDuration = duration
                    }

                    override fun publishProgress(index: Int, thumbnail: Bitmap): Boolean {
                        val notCanceled = this@launch.isActive
                        if (notCanceled) {
                            
                            AppExecutors.mainHandler().post {
                                val view = viewRef.get() ?: return@post
                                if (view.thumbnails === collector) {
                                    collector.add(thumbnail)
                                    view.invalidate()
                                }
                            }
                        }
                        return notCanceled
                    }

                    override fun failed() {
                        L.w { "[MediaAccess] $TAG thumbnail extraction failed scheme=$sourceScheme" }
                    }
                },
            )

            
            
            
            
            withContext(Dispatchers.Main) {
                val view = viewRef.get() ?: return@withContext
                view.setDuration(discoveredDuration)
                view.invalidate()
                L.i { "$TAG onPostExecute, we have ${view.thumbnails?.size ?: "null"} thumbs" }
            }
        }
    }

    private companion object {
        private const val TAG = "VideoThumbnailsView"
        
        private val CORNER_RADIUS get() = ViewUtil.dpToPx(8)
    }
}
