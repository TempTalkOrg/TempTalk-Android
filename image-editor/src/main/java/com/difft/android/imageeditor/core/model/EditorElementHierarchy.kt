package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.difft.android.imageeditor.R
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.SelectableRenderer
import com.difft.android.imageeditor.core.renderers.CropAreaRenderer
import com.difft.android.imageeditor.core.renderers.FillRenderer
import com.difft.android.imageeditor.core.renderers.InverseFillRenderer
import com.difft.android.imageeditor.core.renderers.OvalGuideRenderer
import com.difft.android.imageeditor.core.renderers.SelectedElementGuideRenderer
import com.difft.android.imageeditor.core.renderers.TrashRenderer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Creates and handles a strict EditorElement Hierarchy.
 *
 * ```
 * root - always square, contains only temporary zooms for editing. e.g. when the whole editor zooms out for cropping
 * |
 * |- view - contains persisted adjustments for crops
 * |  |
 * |  |- flipRotate - contains persisted adjustments for flip and rotate operations, ensures operations are centered within the current view
 * |     |
 * |     |- imageRoot
 * |     |  |- mainImage
 * |     |     |- stickers/drawings/text
 * |     |
 * |     |- overlay - always square
 * |     |  |- imageCrop - a crop to match the aspect of the main image
 * |     |  |  |- cropEditorElement - user crop, not always square, but upright, the area of the view
 * |     |  |  |  |  All children do not move/scale or rotate.
 * |     |  |  |  |- blackout
 * |     |  |  |  |- fade
 * |     |  |  |  |- thumbs
 * |     |  |  |  |  |- Center left thumb
 * |     |  |  |  |  |- Center right thumb
 * |     |  |  |  |  |- Top center thumb
 * |     |  |  |  |  |- Bottom center thumb
 * |     |  |  |  |  |- Top left thumb
 * |     |  |  |  |  |- Top right thumb
 * |     |  |  |  |  |- Bottom left thumb
 * |     |  |  |  |  |- Bottom right thumb
 * |     |  |- selection - matches the aspect and overall matrix of the selected item's selectedBounds
 * |     |  |  |- Selection thumbs
 * ```
 */
internal class EditorElementHierarchy private constructor(val root: EditorElement) {

    private val view: EditorElement = root.getChild(0)
    private val flipRotate: EditorElement = view.getChild(0)
    private val imageRoot: EditorElement = flipRotate.getChild(0)
    private val overlay: EditorElement = flipRotate.getChild(1)
    private val imageCrop: EditorElement = overlay.getChild(0)
    private val selection: EditorElement = overlay.getChild(1)
    private val cropEditorElement: EditorElement = imageCrop.getChild(0)
    private val blackout: EditorElement = cropEditorElement.getChild(0)
    private val thumbs: EditorElement = cropEditorElement.getChild(1)
    private val fade: EditorElement = cropEditorElement.getChild(2)
    private val trash: EditorElement = cropEditorElement.getChild(3)

    var selectedElement: EditorElement? = null
        private set

    private enum class CropStyle {
        /**
         * A rectangular overlay with 8 thumbs, corners and edges.
         */
        RECTANGLE,

        /**
         * Cropping with a circular template overlay with Corner thumbs only.
         */
        CIRCLE,

        /**
         * No overlay and no thumbs. Cropping achieved through pinching and panning.
         */
        PINCH_AND_PAN
    }

    fun removeAllSelectionArtifacts() {
        selection.deleteAllChildren()
        selectedElement = null
    }

    fun updateSelectionThumbsForElement(element: EditorElement, overlayMappingMatrix: Matrix?) {
        if (element === selectedElement) {
            setOrUpdateSelectionThumbsForElement(element, overlayMappingMatrix)
        }
    }

    fun setOrUpdateSelectionThumbsForElement(element: EditorElement, overlayMappingMatrix: Matrix?) {
        if (selectedElement !== element) {
            removeAllSelectionArtifacts()

            selectedElement = if (element.renderer is SelectableRenderer) {
                element
            } else {
                null
            }

            if (selectedElement == null) return

            selection.addElement(createSelectionBox())
            selection.addElement(createScaleControlThumb(element))
            selection.addElement(createRotateControlThumb(element))
        }

        if (overlayMappingMatrix != null) {
            val selectionMatrix = selection.localMatrix

            val currentRenderer = selectedElement!!.renderer
            if (currentRenderer is SelectableRenderer) {
                val bounds = RectF()
                currentRenderer.getSelectionBounds(bounds)
                selectionMatrix.setRectToRect(Bounds.FULL_BOUNDS, bounds, Matrix.ScaleToFit.FILL)
            }

            selectionMatrix.postConcat(overlayMappingMatrix)
        }
    }

    fun getImageRoot(): EditorElement = imageRoot

    fun getSelection(): EditorElement = selection

    fun getTrash(): EditorElement = trash

    /**
     * The main image, null if not yet set.
     */
    fun getMainImage(): EditorElement? = if (imageRoot.getChildCount() > 0) imageRoot.getChild(0) else null

    fun getCropEditorElement(): EditorElement = cropEditorElement

    fun getImageCrop(): EditorElement = imageCrop

    fun getOverlay(): EditorElement = overlay

    fun getFlipRotate(): EditorElement = flipRotate

    fun addFade(invalidate: Runnable) {
        fade.flags
            .setVisible(true)
            .persist()

        invalidate.run()
    }

    fun removeFade(invalidate: Runnable) {
        fade.flags
            .setVisible(false)
            .persist()

        invalidate.run()
    }

    /**
     * @param scaleIn Use 1 for no scale in, use less than 1 and it will zoom the image out
     *                so user can see more of the surrounding image while cropping.
     */
    fun startCrop(invalidate: Runnable, scaleIn: Float) {
        val editor = Matrix()

        editor.postScale(scaleIn, scaleIn)
        root.animateEditorTo(editor, invalidate)

        cropEditorElement.flags
            .setVisible(true)

        blackout.flags
            .setVisible(false)

        thumbs.flags
            .setChildrenVisible(true)

        thumbs.forAllInTree { element -> element.flags.setSelectable(true) }

        imageRoot.forAllInTree { element -> element.flags.setSelectable(false) }

        val mainImage = getMainImage()
        if (mainImage != null) {
            mainImage.flags.setSelectable(true)
        }

        invalidate.run()
    }

    fun doneCrop(visibleViewPort: RectF, invalidate: Runnable?) {
        updateViewToCrop(visibleViewPort, invalidate)

        root.rollbackEditorMatrix(invalidate)

        root.forAllInTree { element -> element.flags.reset() }
    }

    fun updateViewToCrop(visibleViewPort: RectF, invalidate: Runnable?) {
        val dst = RectF()

        getCropFinalMatrix().mapRect(dst, Bounds.FULL_BOUNDS)

        val temp = Matrix()
        temp.setRectToRect(dst, visibleViewPort, Matrix.ScaleToFit.CENTER)
        view.animateLocalTo(temp, invalidate)
    }

    private fun getCropFinalMatrix(): Matrix {
        val matrix = Matrix(flipRotate.localMatrix)
        matrix.preConcat(imageCrop.localMatrix)
        matrix.preConcat(cropEditorElement.localMatrix)
        return matrix
    }

    /**
     * Returns a matrix that maps points from the crop on to the visible image.
     *
     * i.e. if a mapped point is in bounds, then the point is on the visible image.
     */
    fun imageMatrixRelativeToCrop(): Matrix? {
        val mainImage = getMainImage() ?: return null

        val matrix1 = Matrix(imageCrop.localMatrix)
        matrix1.preConcat(cropEditorElement.localMatrix)
        matrix1.preConcat(cropEditorElement.editorMatrix)

        val matrix2 = Matrix(mainImage.localMatrix)
        matrix2.preConcat(mainImage.editorMatrix)
        matrix2.preConcat(imageCrop.localMatrix)

        val inverse = Matrix()
        matrix2.invert(inverse)
        inverse.preConcat(matrix1)

        return inverse
    }

    fun dragDropRelease(visibleViewPort: RectF, invalidate: Runnable) {
        if (cropEditorElement.flags.isVisible()) {
            updateViewToCrop(visibleViewPort, invalidate)
        }
    }

    fun getCropRect(): RectF {
        val dst = RectF()
        getCropFinalMatrix().mapRect(dst, Bounds.FULL_BOUNDS)
        return dst
    }

    fun flipRotate(degrees: Float, scaleX: Int, scaleY: Int, visibleViewPort: RectF, invalidate: Runnable?) {
        val newLocal = Matrix(flipRotate.localMatrix)
        if (degrees != 0f) {
            newLocal.postRotate(degrees)
        }
        newLocal.postScale(scaleX.toFloat(), scaleY.toFloat())
        flipRotate.animateLocalTo(newLocal, invalidate)
        updateViewToCrop(visibleViewPort, invalidate)
    }

    /**
     * The full matrix for the [getMainImage] from [root] down.
     */
    fun getMainImageFullMatrix(): Matrix {
        val matrix = Matrix()

        matrix.preConcat(view.localMatrix)
        matrix.preConcat(getMainImageFullMatrixFromFlipRotate())

        return matrix
    }

    /**
     * The full matrix for the [getMainImage] from [flipRotate] down.
     */
    fun getMainImageFullMatrixFromFlipRotate(): Matrix {
        val matrix = Matrix()

        matrix.preConcat(flipRotate.localMatrix)
        matrix.preConcat(imageRoot.localMatrix)

        val mainImage = getMainImage()
        if (mainImage != null) {
            matrix.preConcat(mainImage.localMatrix)
        }

        return matrix
    }

    /**
     * Calculates the exact output size based upon the crops/rotates and zooms in the hierarchy.
     *
     * @param inputSize Main image size
     * @return Size after applying all zooms/rotates and crops
     */
    fun getOutputSize(inputSize: Point): PointF {
        val matrix = Matrix()

        matrix.preConcat(flipRotate.localMatrix)
        matrix.preConcat(cropEditorElement.localMatrix)
        matrix.preConcat(cropEditorElement.editorMatrix)
        val mainImage = getMainImage()
        if (mainImage != null) {
            val xScale = 1f / (xScale(mainImage.localMatrix) * xScale(mainImage.editorMatrix))
            matrix.preScale(xScale, xScale)
        }

        val dst = FloatArray(4)
        matrix.mapPoints(dst, floatArrayOf(0f, 0f, inputSize.x.toFloat(), inputSize.y.toFloat()))

        val widthF = abs(dst[0] - dst[2])
        val heightF = abs(dst[1] - dst[3])

        return PointF(widthF, heightF)
    }

    companion object {
        @JvmStatic
        fun create(@ColorInt blackoutColor: Int): EditorElementHierarchy =
            EditorElementHierarchy(createRoot(CropStyle.RECTANGLE, blackoutColor))

        @JvmStatic
        fun createForCircleEditing(@ColorInt blackoutColor: Int): EditorElementHierarchy =
            EditorElementHierarchy(createRoot(CropStyle.CIRCLE, blackoutColor))

        @JvmStatic
        fun create(root: EditorElement): EditorElementHierarchy =
            EditorElementHierarchy(root)

        private fun createRoot(cropStyle: CropStyle, @ColorInt blackoutColor: Int): EditorElement {
            val root = EditorElement(null)

            val imageRoot = EditorElement(null)
            root.addElement(imageRoot)

            val flipRotate = EditorElement(null)
            imageRoot.addElement(flipRotate)

            val image = EditorElement(null)
            flipRotate.addElement(image)

            val overlay = EditorElement(null)
            flipRotate.addElement(overlay)

            val imageCrop = EditorElement(null)
            overlay.addElement(imageCrop)

            val selection = EditorElement(null)
            overlay.addElement(selection)

            val renderCenterThumbs = cropStyle == CropStyle.RECTANGLE
            val cropEditorElement = EditorElement(CropAreaRenderer(ColorUtils.setAlphaComponent(blackoutColor, 0x7F), renderCenterThumbs))

            cropEditorElement.flags
                .setRotateLocked(true)
                .setAspectLocked(true)
                .setSelectable(false)
                .setVisible(false)
                .persist()

            imageCrop.addElement(cropEditorElement)

            val fade = EditorElement(FillRenderer(ColorUtils.setAlphaComponent(blackoutColor, 0x66)), EditorModel.Z_FADE)
            fade.flags
                .setSelectable(false)
                .setEditable(false)
                .setVisible(false)
                .persist()
            cropEditorElement.addElement(fade)

            val trash = EditorElement(TrashRenderer(), EditorModel.Z_TRASH)
            trash.flags
                .setSelectable(false)
                .setEditable(false)
                .setVisible(false)
                .persist()
            cropEditorElement.addElement(trash)

            val blackout = EditorElement(InverseFillRenderer(ColorUtils.setAlphaComponent(blackoutColor, 0xFF)))

            blackout.flags
                .setSelectable(false)
                .setEditable(false)
                .persist()

            cropEditorElement.addElement(blackout)

            if (cropStyle == CropStyle.PINCH_AND_PAN) {
                cropEditorElement.addElement(EditorElement(null))
            } else {
                cropEditorElement.addElement(createThumbs(cropEditorElement, renderCenterThumbs))

                if (cropStyle == CropStyle.CIRCLE) {
                    val circle = EditorElement(OvalGuideRenderer(R.color.crop_circle_guide_color), EditorModel.Z_CIRCLE)
                    circle.flags.setSelectable(false)
                        .persist()

                    cropEditorElement.addElement(circle)
                }
            }

            return root
        }

        private fun createThumbs(cropEditorElement: EditorElement, centerThumbs: Boolean): EditorElement {
            val thumbs = EditorElement(null)

            thumbs.flags
                .setChildrenVisible(false)
                .setSelectable(false)
                .setVisible(false)
                .persist()

            if (centerThumbs) {
                thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.CENTER_LEFT))
                thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.CENTER_RIGHT))

                thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.TOP_CENTER))
                thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.BOTTOM_CENTER))
            }

            thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.TOP_LEFT))
            thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.TOP_RIGHT))
            thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.BOTTOM_LEFT))
            thumbs.addElement(newThumb(cropEditorElement, ThumbRenderer.ControlPoint.BOTTOM_RIGHT))

            return thumbs
        }

        private fun createSelectionBox(): EditorElement =
            EditorElement(SelectedElementGuideRenderer())

        private fun createScaleControlThumb(element: EditorElement): EditorElement {
            val controlPoint = ThumbRenderer.ControlPoint.SCALE_ROT_RIGHT
            val thumbElement = EditorElement(CropThumbRenderer(controlPoint, element.id))
            thumbElement.localMatrix.preTranslate(controlPoint.x, controlPoint.y)
            return thumbElement
        }

        private fun createRotateControlThumb(element: EditorElement): EditorElement {
            val controlPoint = ThumbRenderer.ControlPoint.SCALE_ROT_LEFT
            val rotateThumbElement = EditorElement(CropThumbRenderer(controlPoint, element.id))
            rotateThumbElement.localMatrix.preTranslate(controlPoint.x, controlPoint.y)
            return rotateThumbElement
        }

        private fun newThumb(toControl: EditorElement, controlPoint: ThumbRenderer.ControlPoint): EditorElement {
            val element = EditorElement(CropThumbRenderer(controlPoint, toControl.id))

            element.flags
                .setSelectable(false)
                .persist()

            element.localMatrix.preTranslate(controlPoint.x, controlPoint.y)

            return element
        }

        /**
         * Extract the x scale from a matrix, which is the length of the first column.
         */
        @JvmStatic
        fun xScale(matrix: Matrix): Float {
            val values = FloatArray(9)
            matrix.getValues(values)
            return sqrt((values[0] * values[0] + values[3] * values[3]).toDouble()).toFloat()
        }
    }
}
