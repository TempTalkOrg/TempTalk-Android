package com.difft.android.imageeditor.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.view.GestureDetectorCompat
import com.difft.android.imageeditor.R
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.imageeditor.core.model.ThumbRenderer
import com.difft.android.imageeditor.core.renderers.BezierDrawingRenderer
import com.difft.android.imageeditor.core.renderers.MultiLineTextRenderer
import com.difft.android.imageeditor.core.renderers.TrashRenderer
import java.util.LinkedList
import kotlin.math.min

/**
 * ImageEditorView
 *
 * Android [android.view.View] that allows manipulation of a base image, rotate/flip/crop and
 * addition and manipulation of text/drawing/and other image layers that move with the base image.
 *
 * Drawing
 *
 * Drawing is achieved by setting the [color] and putting the view in [Mode.Draw].
 * Touch events are then passed to a new [BezierDrawingRenderer] on a new [EditorElement].
 *
 * New images
 *
 * To add new images to the base image add via the [EditorModel.addElementCentered]
 * which centers the new item in the current crop area.
 */
class ImageEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var editText: HiddenEditText

    private var mode: Mode = Mode.MoveAndResize

    @ColorInt
    private var color: Int = 0xff000000.toInt()

    private var thickness = 0.02f

    private var cap: Paint.Cap = Paint.Cap.ROUND

    private lateinit var model: EditorModel

    private lateinit var doubleTap: GestureDetectorCompat

    private var drawingChangedListener: DrawingChangedListener? = null

    private var sizeChangedListener: SizeChangedListener? = null

    private var undoRedoStackListener: UndoRedoStackListener? = null

    private var dragListener: DragListener? = null

    private val textFilters: MutableList<HiddenEditText.TextFilter> = LinkedList()

    private val viewMatrix = Matrix()
    private val viewPort = Bounds.newFullBounds()
    private val visibleViewPort = Bounds.newFullBounds()
    private val screen = RectF()

    private var tapListener: TapListener? = null
    private var rendererContext: RendererContext? = null
    private var typefaceProvider: RendererContext.TypefaceProvider? = null

    private var editSession: EditSession? = null
    private var moreThanOnePointerUsedInSession = false
    private lateinit var touchDownStart: PointF

    private var inDrag = false

    private val rendererReady = RendererContext.Ready { renderer, cropMatrix, size ->
        model.onReady(renderer, cropMatrix, size)
        invalidate()
    }

    private val rendererInvalidate = RendererContext.Invalidate { _ -> invalidate() }

    init {
        initView(attrs)
    }

    // setOnTouchListener forwards to a double-tap gesture detector — no whole-view click semantics.
    @SuppressLint("ClickableViewAccessibility")
    private fun initView(attributeSet: AttributeSet?) {
        setWillNotDraw(false)

        val blackoutColor: Int = if (attributeSet != null) {
            val typedArray = context.obtainStyledAttributes(attributeSet, R.styleable.ImageEditorView)
            val color = typedArray.getColor(R.styleable.ImageEditorView_imageEditorView_blackoutColor, DEFAULT_BLACKOUT_COLOR)
            typedArray.recycle()
            color
        } else {
            DEFAULT_BLACKOUT_COLOR
        }

        setModel(EditorModel.create(blackoutColor))

        editText = createAHiddenTextEntryField()

        doubleTap = GestureDetectorCompat(context, DoubleTapGestureListener())

        setOnTouchListener { _, event -> doubleTap.onTouchEvent(event) }
    }

    private fun createAHiddenTextEntryField(): HiddenEditText {
        val editText = HiddenEditText(context)
        addView(editText)
        editText.clearFocus()
        editText.setOnEndEdit(this::doneTextEditing)
        editText.setOnEditOrSelectionChange(this::zoomToFitText)
        editText.addTextFilters(textFilters)

        return editText
    }

    fun startTextEditing(editorElement: EditorElement) {
        getModel().addFade()
        if (editorElement.renderer is MultiLineTextRenderer) {
            getModel().setSelectionVisible(false)
            editText.setCurrentTextEditorElement(editorElement)
        }
    }

    fun zoomToFitText(editorElement: EditorElement, textRenderer: MultiLineTextRenderer) {
        getModel().zoomToTextElement(editorElement, textRenderer)
    }

    fun isTextEditing(): Boolean = editText.getCurrentTextEntity() != null

    fun doneTextEditing() {
        getModel().zoomOut()
        getModel().removeFade()
        getModel().setSelectionVisible(true)
        if (editText.getCurrentTextEntity() != null) {
            getModel().setSelected(null)
            editText.setCurrentTextEditorElement(null)
            editText.hideKeyboard()
        }
    }

    fun setTypefaceProvider(typefaceProvider: RendererContext.TypefaceProvider) {
        this.typefaceProvider = typefaceProvider
    }

    fun addTextInputFilter(inputFilter: HiddenEditText.TextFilter) {
        textFilters.add(inputFilter)
        editText = createAHiddenTextEntryField()
    }

    fun removeTextInputFilter(inputFilter: HiddenEditText.TextFilter) {
        textFilters.remove(inputFilter)
        editText = createAHiddenTextEntryField()
    }

    override fun onDraw(canvas: Canvas) {
        var rc = rendererContext
        if (rc == null || rc.canvas !== canvas || rc.typefaceProvider !== typefaceProvider) {
            rc = RendererContext(context, canvas, rendererReady, rendererInvalidate, typefaceProvider!!)
            rendererContext = rc
        }
        rc.save()
        try {
            rc.canvasMatrix.initial(viewMatrix)

            model.draw(rc, editText.getCurrentTextEditorElement())
        } finally {
            rc.restore()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewMatrix()
        sizeChangedListener?.onSizeChanged(w, h)
    }

    private fun updateViewMatrix() {
        screen.right = width.toFloat()
        screen.bottom = height.toFloat()

        viewMatrix.setRectToRect(viewPort, screen, Matrix.ScaleToFit.FILL)

        val values = FloatArray(9)
        viewMatrix.getValues(values)

        val scale = values[0] / values[4]

        val tempViewPort = Bounds.newFullBounds()
        if (scale < 1) {
            tempViewPort.top /= scale
            tempViewPort.bottom /= scale
        } else {
            tempViewPort.left *= scale
            tempViewPort.right *= scale
        }

        visibleViewPort.set(tempViewPort)

        viewMatrix.setRectToRect(visibleViewPort, screen, Matrix.ScaleToFit.CENTER)

        model.setVisibleViewPort(visibleViewPort)

        invalidate()
    }

    fun setModel(model: EditorModel) {
        if (!this::model.isInitialized || this.model !== model) {
            if (this::model.isInitialized) {
                this.model.setInvalidate(null)
                this.model.setUndoRedoStackListener(null)
            }
            this.model = model
            this.model.setInvalidate { invalidate() }
            this.model.setUndoRedoStackListener(this::onUndoRedoAvailabilityChanged)
            this.model.setVisibleViewPort(visibleViewPort)
            invalidate()
        }
    }

    // Image-editor drawing/manipulation gestures — no whole-view click semantics.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inverse = Matrix()
                val point = getPoint(event)
                val selected = model.findElementAtPoint(point, viewMatrix, inverse)

                inDrag = false
                moreThanOnePointerUsedInSession = false
                touchDownStart = point
                model.pushUndoPoint()
                editSession = startEdit(inverse, point, selected)

                if (editSession != null) {
                    checkTrashIntersect(point)
                }

                if (tapListener != null && allowTaps()) {
                    if (editSession != null) {
                        tapListener!!.onEntityDown(editSession!!.selected)
                    } else {
                        tapListener!!.onEntityDown(null)
                    }
                }

                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editSession != null) {
                    val historySize = event.historySize
                    val pointerCount = min(2, event.pointerCount)

                    for (h in 0 until historySize) {
                        for (p in 0 until pointerCount) {
                            editSession!!.movePoint(p, getHistoricalPoint(event, p, h))
                        }
                    }

                    for (p in 0 until pointerCount) {
                        editSession!!.movePoint(p, getPoint(event, p))
                    }
                    model.moving(editSession!!.selected)
                    invalidate()
                    if (inDrag) {
                        notifyDragMove(editSession!!.selected, checkTrashIntersect(getPoint(event)))
                    } else if (pointerCount == 1) {
                        checkDragStart(event)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (editSession != null && event.pointerCount == 2) {
                    moreThanOnePointerUsedInSession = true
                    editSession!!.commit()
                    model.pushUndoPoint()

                    val newInverse = model.findElementInverseMatrix(editSession!!.selected, viewMatrix)
                    editSession = if (newInverse != null) {
                        editSession!!.newPoint(newInverse, getPoint(event, event.actionIndex), event.actionIndex)
                    } else {
                        null
                    }
                    if (editSession == null) {
                        dragDropRelease(false)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (editSession != null && event.actionIndex < 2) {
                    editSession!!.commit()
                    model.pushUndoPoint()
                    dragDropRelease(true)

                    val newInverse = model.findElementInverseMatrix(editSession!!.selected, viewMatrix)
                    editSession = if (newInverse != null) {
                        editSession!!.removePoint(newInverse, event.actionIndex)
                    } else {
                        null
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (editSession != null) {
                    editSession!!.commit()
                    dragDropRelease(false)

                    val point = getPoint(event)
                    val hittingTrash = event.pointerCount == 1 &&
                        checkTrashIntersect(point) &&
                        model.findElementAtPoint(point, viewMatrix, Matrix()) === editSession!!.selected

                    if (inDrag) {
                        notifyDragEnd(editSession!!.selected, hittingTrash)
                        inDrag = false
                    }

                    editSession = null
                    model.postEdit(moreThanOnePointerUsedInSession)
                    invalidate()
                    return true
                } else {
                    model.postEdit(moreThanOnePointerUsedInSession)
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun checkTrashIntersect(point: PointF): Boolean {
        if (mode == Mode.Draw || mode == Mode.Blur) {
            return false
        }

        return if (model.checkTrashIntersectsPoint(point)) {
            val renderer = model.getTrash().renderer
            if (renderer is TrashRenderer) {
                renderer.expand()
            }
            true
        } else {
            val renderer = model.getTrash().renderer
            if (renderer is TrashRenderer) {
                renderer.shrink()
            }
            false
        }
    }

    private fun checkDragStart(moveEvent: MotionEvent) {
        if (inDrag || editSession == null) {
            return
        }
        val dX = touchDownStart.x - moveEvent.x
        val dY = touchDownStart.y - moveEvent.y

        val distSquared = dX * dX + dY * dY
        if (distSquared > MAX_MOVE_SQUARED_BEFORE_DRAG) {
            inDrag = true
            notifyDragStart(editSession!!.selected)
        }
    }

    private fun notifyDragStart(editorElement: EditorElement?) {
        dragListener?.onDragStarted(editorElement)
    }

    private fun notifyDragMove(editorElement: EditorElement?, isInTrashHitZone: Boolean) {
        dragListener?.onDragMoved(editorElement, isInTrashHitZone)
    }

    private fun notifyDragEnd(editorElement: EditorElement?, isInTrashHitZone: Boolean) {
        dragListener?.onDragEnded(editorElement, isInTrashHitZone)
    }

    private fun startEdit(inverse: Matrix, point: PointF, selected: EditorElement?): EditSession? {
        val editSession = startAMoveAndResizeSession(inverse, point, selected)
        return if (editSession == null && (mode == Mode.Draw || mode == Mode.Blur)) {
            startADrawingSession(point)
        } else {
            setMode(Mode.MoveAndResize)
            editSession
        }
    }

    private fun startADrawingSession(point: PointF): EditSession {
        val renderer = BezierDrawingRenderer(color, thickness * Bounds.FULL_BOUNDS.width(), cap, model.findCropRelativeToRoot())
        val element = EditorElement(renderer, if (mode == Mode.Blur) EditorModel.Z_MASK else EditorModel.Z_DRAWING)
        model.addElementCentered(element, 1f)

        val elementInverseMatrix = model.findElementInverseMatrix(element, viewMatrix)

        return DrawingSession.start(element, renderer, elementInverseMatrix!!, point)
    }

    private fun startAMoveAndResizeSession(inverse: Matrix, point: PointF, selectedElement: EditorElement?): EditSession? {
        if (selectedElement == null) return null

        var selected = selectedElement

        val thumb = selected.renderer
        if (thumb is ThumbRenderer) {
            val thumbControlledElement = getModel().findById(thumb.elementToControl) ?: return null

            val thumbsParent = getModel().getRoot().findParent(selected) ?: return null

            val thumbContainerRelativeMatrix = model.findRelativeMatrix(thumbsParent, thumbControlledElement) ?: return null

            selected = thumbControlledElement

            val elementInverseMatrix = model.findElementInverseMatrix(selected, viewMatrix)
            return if (elementInverseMatrix != null) {
                ThumbDragEditSession.startDrag(selected, elementInverseMatrix, thumbContainerRelativeMatrix, thumb.controlPoint, point)
            } else {
                null
            }
        }

        return ElementDragEditSession.startDrag(selected, inverse, point)
    }

    fun getMode(): Mode = mode

    fun setMode(mode: Mode) {
        this.mode = mode
    }

    fun setMainImageEditorMatrixRotation(angle: Float, minScaleDown: Float) {
        model.setMainImageEditorMatrixRotation(angle, minScaleDown)
        invalidate()
    }

    fun startDrawing(thickness: Float, cap: Paint.Cap, blur: Boolean) {
        this.thickness = thickness
        this.cap = cap
        setMode(if (blur) Mode.Blur else Mode.Draw)
    }

    fun setDrawingBrushColor(color: Int) {
        this.color = color
    }

    private fun dragDropRelease(stillTouching: Boolean) {
        model.dragDropRelease()
        drawingChangedListener?.onDrawingChanged(stillTouching)
    }

    fun getModel(): EditorModel = model

    fun setDrawingChangedListener(drawingChangedListener: DrawingChangedListener?) {
        this.drawingChangedListener = drawingChangedListener
    }

    fun setSizeChangedListener(sizeChangedListener: SizeChangedListener?) {
        this.sizeChangedListener = sizeChangedListener
    }

    fun setUndoRedoStackListener(undoRedoStackListener: UndoRedoStackListener?) {
        this.undoRedoStackListener = undoRedoStackListener
    }

    fun setDragListener(dragListener: DragListener?) {
        this.dragListener = dragListener
    }

    fun setTapListener(tapListener: TapListener?) {
        this.tapListener = tapListener
    }

    fun deleteElement(editorElement: EditorElement?) {
        if (editorElement != null) {
            model.delete(editorElement)
            invalidate()
        }
    }

    private fun onUndoRedoAvailabilityChanged(undoAvailable: Boolean, redoAvailable: Boolean) {
        undoRedoStackListener?.onAvailabilityChanged(undoAvailable, redoAvailable)
    }

    private inner class DoubleTapGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tapListener != null && editSession != null && allowTaps()) {
                tapListener!!.onEntityDoubleTap(editSession!!.selected)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {}

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (tapListener != null && allowTaps()) {
                if (editSession != null) {
                    val selected = editSession!!.selected
                    model.indicateSelected(selected)
                    model.setSelected(selected)
                    tapListener!!.onEntitySingleTap(selected)
                } else {
                    tapListener!!.onEntitySingleTap(null)
                    model.setSelected(null)
                }
                return true
            }
            return false
        }

        override fun onDown(e: MotionEvent): Boolean = false
    }

    private fun allowTaps(): Boolean = !model.isCropping() && mode != Mode.Draw && mode != Mode.Blur

    enum class Mode {
        MoveAndResize,
        Draw,
        Blur
    }

    fun interface DrawingChangedListener {
        fun onDrawingChanged(stillTouching: Boolean)
    }

    fun interface SizeChangedListener {
        fun onSizeChanged(newWidth: Int, newHeight: Int)
    }

    interface DragListener {
        fun onDragStarted(editorElement: EditorElement?)
        fun onDragMoved(editorElement: EditorElement?, isInTrashHitZone: Boolean)
        fun onDragEnded(editorElement: EditorElement?, isInTrashHitZone: Boolean)
    }

    interface TapListener {

        fun onEntityDown(editorElement: EditorElement?)

        fun onEntitySingleTap(editorElement: EditorElement?)

        fun onEntityDoubleTap(editorElement: EditorElement)
    }

    companion object {
        private val DEFAULT_BLACKOUT_COLOR = 0xFF000000.toInt()

        /** Maximum distance squared a user can move the pointer before we consider a drag starting */
        private const val MAX_MOVE_SQUARED_BEFORE_DRAG = 10

        private fun getPoint(event: MotionEvent): PointF = getPoint(event, 0)

        private fun getPoint(event: MotionEvent, p: Int): PointF = PointF(event.getX(p), event.getY(p))

        private fun getHistoricalPoint(event: MotionEvent, p: Int, historicalIndex: Int): PointF =
            PointF(event.getHistoricalX(p, historicalIndex), event.getHistoricalY(p, historicalIndex))
    }
}
