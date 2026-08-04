package com.difft.android.imageeditor.core.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.annotation.WorkerThread
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import com.difft.android.imageeditor.core.UndoRedoStackListener
import com.difft.android.imageeditor.core.renderers.FaceBlurRenderer
import com.difft.android.imageeditor.core.renderers.MultiLineTextRenderer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Contains a reference to the root [EditorElement], maintains undo and redo stacks and has a
 * reference to the [EditorElementHierarchy].
 *
 * As such it is the entry point for all operations that change the image.
 *
 * 1:1 Kotlin port; size preserved verbatim. #1093
 */
@Suppress("LargeClass")
class EditorModel private constructor(
    private val editingPurpose: EditingPurpose,
    private val fixedRatio: Float,
    private val size: Point,
    private var editorElementHierarchy: EditorElementHierarchy,
    private val undoRedoStacks: UndoRedoStacks,
    private val cropUndoRedoStacks: UndoRedoStacks
) : Parcelable, RendererContext.Ready {

    private var invalidate: Runnable = NULL_RUNNABLE

    private var undoRedoStackListener: UndoRedoStackListener? = null

    private val inBoundsMemory = InBoundsMemory()

    private val visibleViewPort = RectF()

    private enum class EditingPurpose {
        IMAGE,
        AVATAR_CAPTURE,
        AVATAR_EDIT
    }

    private constructor(
        editingPurpose: EditingPurpose,
        fixedRatio: Float,
        editorElementHierarchy: EditorElementHierarchy
    ) : this(
        editingPurpose,
        fixedRatio,
        Point(1024, 1024),
        editorElementHierarchy,
        UndoRedoStacks(50),
        UndoRedoStacks(50)
    )

    @Suppress("DEPRECATION")
    private constructor(`in`: Parcel) : this(
        EditingPurpose.entries[`in`.readInt()],
        `in`.readFloat(),
        Point(`in`.readInt(), `in`.readInt()),
        EditorElementHierarchy.create(`in`.readParcelable(EditorModel::class.java.classLoader)!!),
        `in`.readParcelable(EditorModel::class.java.classLoader)!!,
        `in`.readParcelable(EditorModel::class.java.classLoader)!!
    )

    fun setSelected(editorElement: EditorElement?) {
        if (editorElement == null) {
            editorElementHierarchy.removeAllSelectionArtifacts()
        } else {
            val overlayMappingMatrix = findRelativeMatrix(editorElement, editorElementHierarchy.getOverlay())
            editorElementHierarchy.setOrUpdateSelectionThumbsForElement(editorElement, overlayMappingMatrix)
        }
    }

    fun updateSelectionThumbsIfSelected(editorElement: EditorElement) {
        val overlayMappingMatrix = findRelativeMatrix(editorElement, editorElementHierarchy.getOverlay())
        editorElementHierarchy.updateSelectionThumbsForElement(editorElement, overlayMappingMatrix)
    }

    fun setSelectionVisible(visible: Boolean) {
        editorElementHierarchy.getSelection()
            .flags
            .setVisible(visible)
            .setChildrenVisible(visible)
            .persist()
    }

    /** Keeps the image within the crop bounds as it rotates */
    fun setMainImageEditorMatrixRotation(angle: Float, minScaleDown: Float) {
        setEditorMatrixToRotationMatrixAboutParentsOrigin(editorElementHierarchy.getMainImage()!!, angle)
        scaleMainImageEditorMatrixToFitInsideCropBounds(minScaleDown, 2f)
    }

    private fun scaleMainImageEditorMatrixToFitInsideCropBounds(minScaleDown: Float, maxScaleUp: Float) {
        val mainImage = editorElementHierarchy.getMainImage()!!
        val mainImageLocalBackup = Matrix(mainImage.localMatrix)
        val mainImageEditorBackup = Matrix(mainImage.editorMatrix)

        mainImage.commitEditorMatrix()
        val combinedLocal = Matrix(mainImage.localMatrix)
        val newLocal = Bisect.bisectToTest(
            mainImage,
            minScaleDown,
            maxScaleUp,
            { cropIsWithinMainImageBounds() },
            { matrix, scale -> matrix.preScale(scale, scale) }
        )

        val invertLocal = Matrix()
        if (newLocal != null && combinedLocal.invert(invertLocal)) {
            invertLocal.preConcat(newLocal) // L^-1 (L * Scale) -> Scale
            mainImageEditorBackup.preConcat(invertLocal) // add the scale to editor matrix to keep this image within crop
        }
        mainImage.localMatrix.set(mainImageLocalBackup)
        mainImage.editorMatrix.set(mainImageEditorBackup)
    }

    /**
     * Sets the editor matrix for the element to a rotation of the degrees but does so that we are rotating around the
     * parents elements origin.
     */
    private fun setEditorMatrixToRotationMatrixAboutParentsOrigin(element: EditorElement, degrees: Float) {
        val localMatrix = element.localMatrix
        val editorMatrix = element.editorMatrix
        localMatrix.invert(editorMatrix)
        editorMatrix.preRotate(degrees)
        editorMatrix.preConcat(localMatrix)
        // Editor Matrix is then: Local^-1 * Rotate(degrees) * Local
        // So you end up with this overall for the element: Local * Local^-1 * Rotate(degrees) * Local
        // Meaning the rotate applies after existing effects of the local matrix
        // Where as simply setting the editor matrix rotate gives this: Local * Rotate(degrees)
        // which rotates around local origin first
    }

    /**
     * Renders tree with the following matrix:
     *
     * viewModelMatrix * matrix * editorMatrix
     *
     * Child nodes are supplied with a viewModelMatrix' = viewModelMatrix * matrix * editorMatrix
     *
     * @param rendererContext Canvas to draw on to.
     * @param renderOnTop     This element will appear on top of the overlay.
     */
    fun draw(rendererContext: RendererContext, renderOnTop: EditorElement?) {
        val root = editorElementHierarchy.root
        if (renderOnTop != null) {
            root.forAllInTree { element -> element.flags.mark() }

            renderOnTop.flags.setVisible(false)
        }

        // pass 1
        root.draw(rendererContext)

        if (renderOnTop != null) {
            // hide all
            try {
                root.forAllInTree { element -> element.flags.setVisible(renderOnTop === element) }

                // pass 2
                root.draw(rendererContext)
            } finally {
                root.forAllInTree { element -> element.flags.restore() }
            }
        }
    }

    fun findElementInverseMatrix(element: EditorElement, viewMatrix: Matrix): Matrix? {
        val inverse = Matrix()
        return if (findElement(element, viewMatrix, inverse)) inverse else null
    }

    private fun findElementMatrix(element: EditorElement, viewMatrix: Matrix): Matrix? {
        val inverse = findElementInverseMatrix(element, viewMatrix)
        if (inverse != null) {
            val regular = Matrix()
            inverse.invert(regular)
            return regular
        }
        return null
    }

    fun findElementAtPoint(point: PointF, viewMatrix: Matrix, outInverseModelMatrix: Matrix): EditorElement? =
        editorElementHierarchy.root.findElementAt(point.x, point.y, viewMatrix, outInverseModelMatrix)

    fun checkTrashIntersectsPoint(point: PointF): Boolean {
        val trash = editorElementHierarchy.getTrash()
        return if (trash.flags.isVisible()) {
            trash.flags
                .setSelectable(true)
                .persist()

            val isIntersecting = trash.findElementAt(point.x, point.y, Matrix(), Matrix()) != null

            trash.flags
                .setSelectable(false)
                .persist()

            isIntersecting
        } else {
            false
        }
    }

    private fun findElement(element: EditorElement, viewMatrix: Matrix, outInverseModelMatrix: Matrix): Boolean =
        editorElementHierarchy.root.findElement(element, viewMatrix, outInverseModelMatrix) === element

    fun pushUndoPoint() {
        val cropping = isCropping()
        if (cropping && !currentCropIsAcceptable()) {
            return
        }

        getActiveUndoRedoStacks(cropping).pushState(editorElementHierarchy.root)
    }

    fun updateUndoRedoAvailabilityState() {
        updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()))
    }

    fun clearUndoStack() {
        var root = editorElementHierarchy.root
        val original = root
        val cropping = isCropping()
        val stacks = getActiveUndoRedoStacks(cropping)
        var didPop = false

        while (stacks.canUndo(root)) {
            val oldRootElement = root
            val popped = stacks.undoStack.pop(oldRootElement)

            if (popped != null) {
                didPop = true
                editorElementHierarchy = EditorElementHierarchy.create(popped)
                stacks.redoStack.tryPush(oldRootElement)
            } else {
                break
            }

            root = editorElementHierarchy.root
        }

        if (didPop) {
            restoreStateWithAnimations(original, editorElementHierarchy.root, invalidate, cropping)
            invalidate.run()
            editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate)
            inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement())
        }

        updateUndoRedoAvailableState(stacks)
    }

    fun undo() {
        val cropping = isCropping()
        val stacks = getActiveUndoRedoStacks(cropping)

        undoRedo(stacks.undoStack, stacks.redoStack, cropping)

        updateUndoRedoAvailableState(stacks)
    }

    private fun undoRedo(fromStack: ElementStack, toStack: ElementStack, keepEditorState: Boolean) {
        val oldRootElement = editorElementHierarchy.root
        val popped = fromStack.pop(oldRootElement)

        if (popped != null) {
            setEditorElementHierarchy(EditorElementHierarchy.create(popped))

            toStack.tryPush(oldRootElement)

            restoreStateWithAnimations(oldRootElement, editorElementHierarchy.root, invalidate, keepEditorState)
            invalidate.run()

            // re-zoom image root as the view port might be different now
            editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate)

            inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement())
        }
    }

    /** Replaces the hierarchy, maintaining any selection if possible */
    private fun setEditorElementHierarchy(hierarchy: EditorElementHierarchy) {
        val selectedElement = editorElementHierarchy.selectedElement
        editorElementHierarchy = hierarchy
        setSelected(if (selectedElement != null) findById(selectedElement.id) else null)
    }

    private fun updateUndoRedoAvailableState(currentStack: UndoRedoStacks) {
        val listener = undoRedoStackListener ?: return

        val root = editorElementHierarchy.root

        listener.onAvailabilityChanged(currentStack.canUndo(root), currentStack.canRedo(root))
    }

    fun addFade() {
        editorElementHierarchy.addFade(invalidate)
    }

    fun removeFade() {
        editorElementHierarchy.removeFade(invalidate)
    }

    fun startCrop() {
        val scaleIn = 0.8f

        pushUndoPoint()
        cropUndoRedoStacks.clear(editorElementHierarchy.root)
        editorElementHierarchy.startCrop(invalidate, scaleIn)
        inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement())
        updateUndoRedoAvailableState(cropUndoRedoStacks)
    }

    fun doneCrop() {
        editorElementHierarchy.doneCrop(visibleViewPort, invalidate)
        updateUndoRedoAvailableState(undoRedoStacks)
    }

    fun setCropAspectLock(locked: Boolean) {
        val flags = editorElementHierarchy.getCropEditorElement().flags
        val currentState = flags.setAspectLocked(locked).getCurrentState()

        flags.reset()
        flags.setAspectLocked(locked)
            .persist()
        flags.restoreState(currentState)
    }

    fun isCropAspectLocked(): Boolean =
        editorElementHierarchy.getCropEditorElement().flags.isAspectLocked()

    fun postEdit(allowScaleToRepairCrop: Boolean) {
        val cropping = isCropping()
        if (cropping) {
            ensureFitsBounds(allowScaleToRepairCrop)
        }

        updateUndoRedoAvailableState(getActiveUndoRedoStacks(cropping))
    }

    /**
     * @param cropping Set to true if cropping is underway.
     * @return The correct stack for the mode of operation.
     */
    private fun getActiveUndoRedoStacks(cropping: Boolean): UndoRedoStacks =
        if (cropping) cropUndoRedoStacks else undoRedoStacks

    private fun ensureFitsBounds(allowScaleToRepairCrop: Boolean) {
        val mainImage = editorElementHierarchy.getMainImage() ?: return

        val cropEditorElement = editorElementHierarchy.getCropEditorElement()

        if (!currentCropIsAcceptable()) {
            if (allowScaleToRepairCrop) {
                if (!tryToScaleToFit(cropEditorElement, 0.9f)) {
                    tryToScaleToFit(mainImage, 2f)
                }
            } else {
                tryToFixTranslationOutOfBounds(mainImage, inBoundsMemory.getLastKnownGoodMainImageMatrix())
            }

            if (!currentCropIsAcceptable()) {
                inBoundsMemory.restore(mainImage, cropEditorElement, invalidate)
            } else {
                inBoundsMemory.push(mainImage, cropEditorElement)
            }
        }

        editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate)
    }

    /**
     * Attempts to scale the supplied element such that [cropIsWithinMainImageBounds] is true.
     *
     * Does not respect minimum scale, so does need a further check to [currentCropIsAcceptable] afterwards.
     *
     * @param element     The element to be scaled. If successful, it will be animated to the correct position.
     * @param scaleAtMost The amount of scale to apply at most. Use < 1 for the crop, and > 1 for the image.
     * @return true if successfully scaled the element. false if the element was left unchanged.
     */
    private fun tryToScaleToFit(element: EditorElement, scaleAtMost: Float): Boolean =
        Bisect.bisectToTest(
            element,
            1f,
            scaleAtMost,
            { cropIsWithinMainImageBounds() },
            { matrix, scale -> matrix.preScale(scale, scale) },
            invalidate
        )

    /**
     * Attempts to translate the supplied element such that [cropIsWithinMainImageBounds] is true.
     * If you supply both x and y, it will attempt to find a fit on the diagonal with vector x, y.
     *
     * @param element          The element to be translated. If successful, it will be animated to the correct position.
     * @param translateXAtMost The maximum translation to apply in the x axis.
     * @param translateYAtMost The maximum translation to apply in the y axis.
     * @return a matrix if successfully translated the element. null if the element unable to be translated to fit.
     */
    private fun tryToTranslateToFit(element: EditorElement, translateXAtMost: Float, translateYAtMost: Float): Matrix? =
        Bisect.bisectToTest(
            element,
            0f,
            1f,
            { cropIsWithinMainImageBounds() },
            { matrix, factor -> matrix.postTranslate(factor * translateXAtMost, factor * translateYAtMost) }
        )

    /**
     * Tries to fix an element that is out of bounds by adjusting it's translation.
     *
     * @param element               Element to move.
     * @param lastKnownGoodPosition Last known good position of element.
     * @return true iff fixed the element.
     */
    private fun tryToFixTranslationOutOfBounds(element: EditorElement, lastKnownGoodPosition: Matrix): Boolean {
        val elementMatrix = element.localMatrix
        val original = Matrix(elementMatrix)
        val current = FloatArray(9)
        val lastGood = FloatArray(9)
        var matrix: Matrix?

        elementMatrix.getValues(current)
        lastKnownGoodPosition.getValues(lastGood)

        val xTranslate = current[2] - lastGood[2]
        val yTranslate = current[5] - lastGood[5]

        if (abs(xTranslate) < Bisect.ACCURACY && abs(yTranslate) < Bisect.ACCURACY) {
            return false
        }

        val pass1X: Float
        val pass1Y: Float

        val pass2X: Float
        val pass2Y: Float

        // try the fix by the smallest user translation first
        if (abs(xTranslate) < abs(yTranslate)) {
            // try to bisect along x
            pass1X = -xTranslate
            pass1Y = 0f

            // then y
            pass2X = 0f
            pass2Y = -yTranslate
        } else {
            // try to bisect along y
            pass1X = 0f
            pass1Y = -yTranslate

            // then x
            pass2X = -xTranslate
            pass2Y = 0f
        }

        matrix = tryToTranslateToFit(element, pass1X, pass1Y)
        if (matrix != null) {
            element.animateLocalTo(matrix, invalidate)
            return true
        }

        matrix = tryToTranslateToFit(element, pass2X, pass2Y)
        if (matrix != null) {
            element.animateLocalTo(matrix, invalidate)
            return true
        }

        // apply pass 1 fully
        elementMatrix.postTranslate(pass1X, pass1Y)

        matrix = tryToTranslateToFit(element, pass2X, pass2Y)
        elementMatrix.set(original)

        if (matrix != null) {
            element.animateLocalTo(matrix, invalidate)
            return true
        }

        return false
    }

    fun dragDropRelease() {
        editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate)
    }

    /**
     * Pixel count must be no smaller than [MINIMUM_CROP_PIXEL_COUNT] (unless its original size was less than that)
     * and all points must be within the bounds.
     */
    private fun currentCropIsAcceptable(): Boolean {
        val outputSize = getOutputSize()
        val outputPixelCount = outputSize.x * outputSize.y
        val minimumPixelCount = min(size.x * size.y, MINIMUM_CROP_PIXEL_COUNT)

        var thinnestRatio = MINIMUM_RATIO

        if (compareRatios(size, thinnestRatio) < 0) {
            // original is narrower than the thinnestRatio
            thinnestRatio = size
        }

        return compareRatios(outputSize, thinnestRatio) >= 0 &&
            outputPixelCount >= minimumPixelCount &&
            cropIsWithinMainImageBounds()
    }

    /**
     * @return true if and only if the current crop rect is fully in the bounds.
     */
    private fun cropIsWithinMainImageBounds(): Boolean =
        Bounds.boundsRemainInBounds(editorElementHierarchy.imageMatrixRelativeToCrop())

    /**
     * Called as edits are underway.
     */
    fun moving(editorElement: EditorElement) {
        if (!isCropping()) {
            updateSelectionThumbsIfSelected(editorElement)
            return
        }

        val mainImage = editorElementHierarchy.getMainImage()
        val cropEditorElement = editorElementHierarchy.getCropEditorElement()

        if (editorElement === mainImage || editorElement === cropEditorElement) {
            if (currentCropIsAcceptable()) {
                inBoundsMemory.push(mainImage, cropEditorElement)
            }
        }
    }

    fun setVisibleViewPort(visibleViewPort: RectF) {
        this.visibleViewPort.set(visibleViewPort)
        editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(editingPurpose.ordinal)
        dest.writeFloat(fixedRatio)
        dest.writeInt(size.x)
        dest.writeInt(size.y)
        dest.writeParcelable(editorElementHierarchy.root, flags)
        dest.writeParcelable(undoRedoStacks, flags)
        dest.writeParcelable(cropUndoRedoStacks, flags)
    }

    /**
     * Blocking render of the model.
     */
    @WorkerThread
    fun render(context: Context, typefaceProvider: RendererContext.TypefaceProvider): Bitmap =
        render(context, null, typefaceProvider)

    /**
     * Blocking render of the model.
     */
    @WorkerThread
    fun render(context: Context, size: Point?, typefaceProvider: RendererContext.TypefaceProvider): Bitmap {
        val image = editorElementHierarchy.getFlipRotate()
        val cropRect = editorElementHierarchy.getCropRect()
        val outputSize = size ?: getOutputSize()

        val bitmap = Bitmap.createBitmap(outputSize.x, outputSize.y, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val rendererContext = RendererContext(context, canvas, RendererContext.Ready.NULL, RendererContext.Invalidate.NULL, typefaceProvider)

            val bitmapArea = RectF()
            bitmapArea.right = bitmap.width.toFloat()
            bitmapArea.bottom = bitmap.height.toFloat()

            val viewMatrix = Matrix()
            viewMatrix.setRectToRect(cropRect, bitmapArea, Matrix.ScaleToFit.FILL)

            rendererContext.setIsEditing(false)
            rendererContext.setBlockingLoad(true)

            val overlay = editorElementHierarchy.getOverlay()
            overlay.flags.setVisible(false).setChildrenVisible(false)

            try {
                rendererContext.canvasMatrix.initial(viewMatrix)
                image.draw(rendererContext)
            } finally {
                overlay.flags.reset()
            }
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    private fun getOutputSize(): Point {
        val outputSize = editorElementHierarchy.getOutputSize(size)

        val width = max(MINIMUM_OUTPUT_WIDTH.toFloat(), outputSize.x).toInt()
        val height = (width * outputSize.y / outputSize.x).toInt()

        return Point(width, height)
    }

    fun getOutputSizeMaxWidth(maxDimension: Int): Point {
        val outputSize = editorElementHierarchy.getOutputSize(size)

        var width = min(maxDimension, max(MINIMUM_OUTPUT_WIDTH.toFloat(), outputSize.x).toInt())
        var height = (width * outputSize.y / outputSize.x).toInt()

        if (height > maxDimension) {
            height = maxDimension
            width = (height * outputSize.x / outputSize.y).toInt()
        }

        return Point(width, height)
    }

    override fun onReady(renderer: Renderer, cropMatrix: Matrix?, size: Point?) {
        if (cropMatrix != null && size != null && isRendererOfMainImage(renderer)) {
            val changedBefore = isChanged()
            val imageCropMatrix = editorElementHierarchy.getImageCrop().localMatrix
            this.size.set(size.x, size.y)
            if (imageCropMatrix.isIdentity) {
                imageCropMatrix.set(cropMatrix)

                if (editingPurpose == EditingPurpose.AVATAR_CAPTURE || editingPurpose == EditingPurpose.AVATAR_EDIT) {
                    val userCropMatrix = editorElementHierarchy.getCropEditorElement().localMatrix
                    if (size.x > size.y) {
                        userCropMatrix.setScale(fixedRatio * size.y / size.x.toFloat(), 1f)
                    } else {
                        userCropMatrix.setScale(1f, size.x / size.y.toFloat())
                    }
                }

                editorElementHierarchy.doneCrop(visibleViewPort, null)

                if (!changedBefore) {
                    undoRedoStacks.clear(editorElementHierarchy.root)
                }

                when (editingPurpose) {
                    EditingPurpose.AVATAR_CAPTURE -> startCrop()
                    EditingPurpose.IMAGE, EditingPurpose.AVATAR_EDIT -> {}
                }
            }
        }
    }

    private fun isRendererOfMainImage(renderer: Renderer): Boolean {
        val mainImage = editorElementHierarchy.getMainImage()
        val mainImageRenderer = mainImage?.renderer
        return mainImageRenderer === renderer
    }

    /**
     * Add a new [EditorElement] centered in the current visible crop area.
     *
     * @param element New element to add.
     * @param scale   Initial scale for new element.
     */
    fun addElementCentered(element: EditorElement, scale: Float) {
        val localMatrix = element.localMatrix

        editorElementHierarchy.getMainImageFullMatrix().invert(localMatrix)

        localMatrix.preScale(scale, scale)
        addElement(element)
    }

    /**
     * Add an element to the main image, or if there is no main image, make the new element the main image.
     *
     * @param element New element to add.
     */
    fun addElement(element: EditorElement) {
        pushUndoPoint()
        addElementWithoutPushUndo(element)
    }

    fun addElementWithoutPushUndo(element: EditorElement) {
        val mainImage = editorElementHierarchy.getMainImage()
        val parent = mainImage ?: editorElementHierarchy.getImageRoot()

        parent.addElement(element)

        if (parent !== mainImage) {
            undoRedoStacks.clear(editorElementHierarchy.root)
        }

        updateUndoRedoAvailableState(undoRedoStacks)
    }

    fun clearFaceRenderers() {
        val mainImage = editorElementHierarchy.getMainImage()
        if (mainImage != null) {
            var hasPushedUndo = false
            for (i in mainImage.getChildCount() - 1 downTo 0) {
                if (mainImage.getChild(i).renderer is FaceBlurRenderer) {
                    if (!hasPushedUndo) {
                        pushUndoPoint()
                        hasPushedUndo = true
                    }

                    mainImage.deleteChild(mainImage.getChild(i), invalidate)
                }
            }
        }
    }

    fun hasFaceRenderer(): Boolean {
        val mainImage = editorElementHierarchy.getMainImage()
        if (mainImage != null) {
            for (i in mainImage.getChildCount() - 1 downTo 0) {
                if (mainImage.getChild(i).renderer is FaceBlurRenderer) {
                    return true
                }
            }
        }

        return false
    }

    fun isChanged(): Boolean = undoRedoStacks.isChanged(editorElementHierarchy.root)

    fun findCropRelativeToRoot(): RectF = findCropRelativeTo(editorElementHierarchy.root)

    internal fun findCropRelativeTo(element: EditorElement): RectF =
        findRelativeBounds(editorElementHierarchy.getCropEditorElement(), element)

    internal fun findRelativeBounds(from: EditorElement, to: EditorElement): RectF {
        val relative = findRelativeMatrix(from, to)

        val dst = RectF(Bounds.FULL_BOUNDS)
        if (relative != null) {
            relative.mapRect(dst, Bounds.FULL_BOUNDS)
        }
        return dst
    }

    /**
     * Returns a matrix that maps points in the [from] element in to points in the [to] element.
     */
    fun findRelativeMatrix(from: EditorElement, to: EditorElement): Matrix? {
        val matrix = findElementInverseMatrix(to, Matrix())
        val outOf = findElementMatrix(from, Matrix())

        if (outOf != null && matrix != null) {
            matrix.preConcat(outOf)
            return matrix
        }
        return null
    }

    fun rotate90anticlockwise() {
        flipRotate(-90f, 1, 1)
    }

    fun flipHorizontal() {
        flipRotate(0f, -1, 1)
    }

    private fun flipRotate(degrees: Float, scaleX: Int, scaleY: Int) {
        pushUndoPoint()
        editorElementHierarchy.flipRotate(degrees, scaleX, scaleY, visibleViewPort, invalidate)
        updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()))
    }

    fun getRoot(): EditorElement = editorElementHierarchy.root

    fun getTrash(): EditorElement = editorElementHierarchy.getTrash()

    fun getMainImage(): EditorElement? = editorElementHierarchy.getMainImage()

    fun delete(editorElement: EditorElement) {
        editorElementHierarchy.getImageRoot().forAllInTree { element -> element.deleteChild(editorElement, invalidate) }
        setSelected(null)
    }

    fun findById(uuid: UUID): EditorElement? = getRoot().findElementWithId(uuid)

    /**
     * Changes the temporary view so that the text element is centered in it.
     *
     * @param entity       Entity to center on.
     * @param textRenderer The text renderer, which can make additional adjustments to the zoom matrix
     *                     to leave space for the keyboard for example.
     */
    fun zoomToTextElement(entity: EditorElement, textRenderer: MultiLineTextRenderer) {
        val elementInverseMatrix = findElementInverseMatrix(entity, Matrix())
        if (elementInverseMatrix != null) {
            val root = editorElementHierarchy.root

            elementInverseMatrix.preConcat(root.editorMatrix)

            textRenderer.applyRecommendedEditorMatrix(elementInverseMatrix)

            root.animateEditorTo(elementInverseMatrix, invalidate)
        }
    }

    fun zoomOut() {
        editorElementHierarchy.root.rollbackEditorMatrix(invalidate)
    }

    fun indicateSelected(selected: EditorElement) {
        selected.singleScalePulse(invalidate)
    }

    fun isCropping(): Boolean =
        editorElementHierarchy.getCropEditorElement().flags.isVisible()

    /**
     * Returns a matrix that maps bounds to the crop area.
     */
    fun getInverseCropPosition(): Matrix {
        val matrix = Matrix()
        matrix.set(findRelativeMatrix(editorElementHierarchy.getMainImage()!!, editorElementHierarchy.getCropEditorElement()))
        matrix.postConcat(editorElementHierarchy.getFlipRotate().localMatrix)

        val positionRelativeToCrop = Matrix()
        matrix.invert(positionRelativeToCrop)
        return positionRelativeToCrop
    }

    fun setInvalidate(invalidate: Runnable?) {
        this.invalidate = invalidate ?: NULL_RUNNABLE
    }

    fun setUndoRedoStackListener(undoRedoStackListener: UndoRedoStackListener?) {
        this.undoRedoStackListener = undoRedoStackListener

        updateUndoRedoAvailableState(getActiveUndoRedoStacks(isCropping()))
    }

    companion object {
        const val Z_MASK = -1
        const val Z_DRAWING = 0
        const val Z_STICKERS = 0
        const val Z_FADE = 1
        const val Z_TEXT = 2
        const val Z_TRASH = 3
        const val Z_CIRCLE = 4

        private val NULL_RUNNABLE = Runnable { }

        private const val MINIMUM_OUTPUT_WIDTH = 1024

        private const val MINIMUM_CROP_PIXEL_COUNT = 100
        private val MINIMUM_RATIO = Point(15, 1)

        @JvmStatic
        fun create(@ColorInt blackoutColor: Int): EditorModel {
            val model = EditorModel(EditingPurpose.IMAGE, 0f, EditorElementHierarchy.create(blackoutColor))
            model.setCropAspectLock(false)
            return model
        }

        @JvmStatic
        fun createForAvatarCapture(@ColorInt blackoutColor: Int): EditorModel {
            val editorModel = EditorModel(EditingPurpose.AVATAR_CAPTURE, 1f, EditorElementHierarchy.createForCircleEditing(blackoutColor))
            editorModel.setCropAspectLock(true)
            return editorModel
        }

        @JvmStatic
        fun createForAvatarEdit(@ColorInt blackoutColor: Int): EditorModel {
            val editorModel = EditorModel(EditingPurpose.AVATAR_EDIT, 1f, EditorElementHierarchy.createForCircleEditing(blackoutColor))
            editorModel.setCropAspectLock(true)
            return editorModel
        }

        private fun restoreStateWithAnimations(fromRootElement: EditorElement, toRootElement: EditorElement, onInvalidate: Runnable, keepEditorState: Boolean) {
            val fromMap = getElementMap(fromRootElement)
            val toMap = getElementMap(toRootElement)

            for (fromElement in fromMap.values) {
                fromElement.stopAnimation()
                val toElement = toMap[fromElement.id]
                if (toElement != null) {
                    toElement.animateFrom(fromElement.getLocalMatrixAnimating(), onInvalidate)

                    if (keepEditorState) {
                        toElement.editorMatrix.set(fromElement.editorMatrix)
                        toElement.flags.set(fromElement.flags)
                    }
                } else {
                    // element is removed
                    val parentFrom = fromRootElement.parentOf(fromElement)
                    if (parentFrom != null) {
                        val toParent = toMap[parentFrom.id]
                        if (toParent != null) {
                            toParent.addDeletedChildFadingOut(fromElement, onInvalidate)
                        }
                    }
                }
            }

            for (toElement in toMap.values) {
                if (!fromMap.containsKey(toElement.id)) {
                    // new item
                    toElement.animateFadeIn(onInvalidate)
                }
            }
        }

        private fun getElementMap(element: EditorElement): Map<UUID, EditorElement> {
            val result = HashMap<UUID, EditorElement>()
            element.buildMap(result)
            return result
        }

        /**
         * -1 iff a is a narrower ratio than b.
         * +1 iff a is a squarer ratio than b.
         * 0 if the ratios are the same.
         */
        private fun compareRatios(a: Point, b: Point): Int {
            val smallA = min(a.x, a.y)
            val largeA = max(a.x, a.y)

            val smallB = min(b.x, b.y)
            val largeB = max(b.x, b.y)

            return (smallA * largeB).compareTo(smallB * largeA)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<EditorModel> = object : Parcelable.Creator<EditorModel> {
            override fun createFromParcel(`in`: Parcel): EditorModel = EditorModel(`in`)
            override fun newArray(size: Int): Array<EditorModel?> = arrayOfNulls(size)
        }
    }
}
