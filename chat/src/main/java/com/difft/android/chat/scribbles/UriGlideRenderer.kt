package com.difft.android.chat.scribbles

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.BitmapUtil
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import com.difft.android.imageeditor.core.SelectableRenderer
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.model.EditorModel
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * Uses Glide to load an image and implements a [Renderer].
 *
 * The image can be encrypted.
 */
class UriGlideRenderer @JvmOverloads constructor(
    private val imageUri: Uri,
    private val decryptable: Boolean,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val blurRadius: Float = STRONG_BLUR,
    private val bitmapRequestListener: RequestListener<Bitmap>? = null
) : SelectableRenderer {

    private val paint = Paint()
    private val imageProjectionMatrix = Matrix()
    private val temp = Matrix()
    private val blurScaleMatrix = Matrix()
    private val blurLock = Any()

    private var selected = false

    private var currentBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var blurPaint: Paint? = null

    init {
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true
    }

    override fun render(rendererContext: RendererContext) {
        if (getBitmap() == null) {
            if (rendererContext.isBlockingLoad()) {
                try {
                    val loaded = getGlideRequestBuilder(rendererContext.context, false).submit().get()
                    setBitmap(rendererContext, loaded)
                } catch (e: ExecutionException) {
                    throw RuntimeException(e)
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            } else {
                getGlideRequestBuilder(rendererContext.context, true).into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        setBitmap(rendererContext, resource)

                        rendererContext.invalidate.onInvalidate(this@UriGlideRenderer)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        currentBitmap = null
                    }

                    // Without this the non-blocking branch fails silently: the preview stays black
                    // and nothing anywhere records that the image was never decoded.
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        L.w { "[MediaAccess] $TAG preview load failed scheme=${imageUri.scheme}" }
                    }
                })
            }
        }

        val bitmap = getBitmap()
        if (bitmap != null) {
            rendererContext.save()

            rendererContext.canvasMatrix.concat(imageProjectionMatrix)

            // Units are image level pixels at this point.

            val alpha = paint.alpha
            paint.alpha = rendererContext.getAlpha(alpha)

            rendererContext.canvas.drawBitmap(bitmap, 0f, 0f, if (rendererContext.maskPaint != null) rendererContext.maskPaint else paint)

            paint.alpha = alpha

            rendererContext.restore()

            renderBlurOverlay(rendererContext)
        } else if (rendererContext.isBlockingLoad()) {
            // If failed to load, we draw a black out, in case image was sticker positioned to cover private info.
            rendererContext.canvas.drawRect(Bounds.FULL_BOUNDS, paint)
        }
    }

    private fun renderBlurOverlay(rendererContext: RendererContext) {
        var renderMask = false

        for (child in rendererContext.children) {
            if (child.zOrder == EditorModel.Z_MASK) {
                renderMask = true
                if (blurPaint == null) {
                    blurPaint = Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                        isDither = true
                    }
                }
                blurPaint!!.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                rendererContext.maskPaint = blurPaint
                child.draw(rendererContext)
            }
        }

        if (renderMask) {
            rendererContext.save()
            rendererContext.canvasMatrix.concat(imageProjectionMatrix)

            blurPaint!!.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_ATOP)
            blurPaint!!.maskFilter = null

            // Synchronize access to blurredBitmap to prevent race conditions
            synchronized(blurLock) {
                if (blurredBitmap == null) {
                    blurredBitmap = blur(currentBitmap!!, rendererContext.context, blurRadius)

                    blurScaleMatrix.setRectToRect(
                        RectF(0f, 0f, blurredBitmap!!.width.toFloat(), blurredBitmap!!.height.toFloat()),
                        RectF(0f, 0f, currentBitmap!!.width.toFloat(), currentBitmap!!.height.toFloat()),
                        Matrix.ScaleToFit.FILL
                    )
                }

                rendererContext.canvas.concat(blurScaleMatrix)
                rendererContext.canvas.drawBitmap(blurredBitmap!!, 0f, 0f, blurPaint)
            }
            blurPaint!!.xfermode = null

            rendererContext.restore()
        }
    }

    private fun getGlideRequestBuilder(context: Context, preview: Boolean): RequestBuilder<Bitmap> {
        var width = this.maxWidth
        var height = this.maxHeight

        if (preview) {
            width = Math.min(width, PREVIEW_DIMENSION_LIMIT)
            height = Math.min(height, PREVIEW_DIMENSION_LIMIT)
        }

        val requestBuilder = Glide.with(context)
            .asBitmap()
            .override(width, height)
            .centerInside()
            .addListener(bitmapRequestListener)

        // A content URI must go through ContentResolver: File(uri.path) would be
        // "/external/images/media/1", a path that never exists, so every gallery image failed to
        // decode here. file:// keeps the File model so sandbox / SAF loads stay byte-identical —
        // limiting the change to the branch that is already 100% broken.
        if (ContentResolver.SCHEME_CONTENT == imageUri.scheme) {
            return requestBuilder.load(imageUri)
        }
        val uriPath = imageUri.path
        return if (!uriPath.isNullOrEmpty()) {
            requestBuilder.load(File(uriPath))
        } else {
            requestBuilder.load(imageUri)
        }
    }

    override fun hitTest(x: Float, y: Float): Boolean {
        return if (selected) Bounds.contains(x, y) else pixelAlphaNotZero(x, y)
    }

    private fun pixelAlphaNotZero(x: Float, y: Float): Boolean {
        val bitmap = getBitmap() ?: return false

        imageProjectionMatrix.invert(temp)

        val onBmp = FloatArray(2)
        temp.mapPoints(onBmp, floatArrayOf(x, y))

        val xInt = onBmp[0].toInt()
        val yInt = onBmp[1].toInt()

        return if (xInt >= 0 && xInt < bitmap.width && yInt >= 0 && yInt < bitmap.height) {
            (bitmap.getPixel(xInt, yInt) and 0xff000000.toInt()) != 0
        } else {
            false
        }
    }

    /**
     * Always use this getter, as Bitmap is kept in Glide's LRUCache, so it could have been recycled
     * by Glide. If it has, or was never set, this method returns null.
     */
    fun getBitmap(): Bitmap? {
        val b = currentBitmap
        if (b != null && b.isRecycled) {
            currentBitmap = null
        }
        return currentBitmap
    }

    private fun setBitmap(rendererContext: RendererContext, bitmap: Bitmap?) {
        this.currentBitmap = bitmap
        if (bitmap != null) {
            val from = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imageProjectionMatrix.setRectToRect(from, Bounds.FULL_BOUNDS, Matrix.ScaleToFit.CENTER)
            rendererContext.rendererReady.onReady(this@UriGlideRenderer, cropMatrix(bitmap), Point(bitmap.width, bitmap.height))
        }
    }

    /**
     * Clears the blurred bitmap to free memory after editing is complete.
     * The blurred bitmap will be recreated if needed during rendering (e.g., when sending).
     * Does not clear the main bitmap as it's managed by Glide and may be needed for sending.
     * Thread-safe: synchronized to prevent concurrent access during rendering.
     */
    fun clearBlurredBitmap() {
        synchronized(blurLock) {
            blurredBitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            blurredBitmap = null
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(imageUri.toString())
        dest.writeInt(if (decryptable) 1 else 0)
        dest.writeInt(maxWidth)
        dest.writeInt(maxHeight)
        dest.writeFloat(blurRadius)
    }

    override fun onSelected(selected: Boolean) {
        if (this.selected != selected) {
            this.selected = selected
        }
    }

    override fun getSelectionBounds(bounds: RectF) {
        bounds.set(Bounds.FULL_BOUNDS)
    }

    companion object {
        private const val TAG = "UriGlideRenderer"

        private const val PREVIEW_DIMENSION_LIMIT = 2048
        private const val MAX_BLUR_DIMENSION = 300

        const val WEAK_BLUR = 3f
        const val STRONG_BLUR = 25f

        private fun cropMatrix(bitmap: Bitmap): Matrix {
            val matrix = Matrix()
            if (bitmap.width > bitmap.height) {
                matrix.preScale(1f, bitmap.height.toFloat() / bitmap.width)
            } else {
                matrix.preScale(bitmap.width.toFloat() / bitmap.height, 1f)
            }
            return matrix
        }

        private fun blur(bitmap: Bitmap, context: Context, blurRadius: Float): Bitmap {
            val previewSize = scaleKeepingAspectRatio(Point(bitmap.width, bitmap.height), PREVIEW_DIMENSION_LIMIT)
            val blurSize = scaleKeepingAspectRatio(Point(previewSize.x / 2, previewSize.y / 2), MAX_BLUR_DIMENSION)
            val small = BitmapUtil.createScaledBitmap(bitmap, blurSize.x, blurSize.y)

            L.d { "$TAG Bitmap: ${bitmap.width}x${bitmap.height}, Blur: ${blurSize.x}x${blurSize.y}" }

            val rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, small)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(blurRadius)
            script.setInput(input)
            script.forEach(output)

            val blurred = Bitmap.createBitmap(small.width, small.height, small.config!!)
            output.copyTo(blurred)
            return blurred
        }

        private fun scaleKeepingAspectRatio(dimens: Point, maxDimen: Int): Point {
            var outX = dimens.x
            var outY = dimens.y

            if (dimens.x > maxDimen || dimens.y > maxDimen) {
                outX = maxDimen
                outY = maxDimen

                val widthRatio = dimens.x / maxDimen.toFloat()
                val heightRatio = dimens.y / maxDimen.toFloat()

                if (widthRatio > heightRatio) {
                    outY = (dimens.y / widthRatio).toInt()
                } else {
                    outX = (dimens.x / heightRatio).toInt()
                }
            }

            return Point(outX, outY)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<UriGlideRenderer> = object : Parcelable.Creator<UriGlideRenderer> {
            override fun createFromParcel(parcel: Parcel): UriGlideRenderer {
                return UriGlideRenderer(
                    Uri.parse(parcel.readString()),
                    parcel.readInt() == 1,
                    parcel.readInt(),
                    parcel.readInt(),
                    parcel.readFloat()
                )
            }

            override fun newArray(size: Int): Array<UriGlideRenderer?> {
                return arrayOfNulls(size)
            }
        }
    }
}
