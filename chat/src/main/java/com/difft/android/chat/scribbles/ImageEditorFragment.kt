package com.difft.android.chat.scribbles

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import com.difft.android.chat.animation.ResizeAnimation
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.fonts.FontTypefaceProvider
import com.difft.android.chat.mediasend.MediaSendPageFragment
import com.difft.android.chat.mediasend.v2.MediaAnimations
import com.difft.android.chat.mms.MediaConstraints
import com.difft.android.chat.mms.PushMediaConstraints
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.providers.MyBlobProvider
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.util.ParcelUtil
import com.difft.android.chat.util.SaveAttachmentUtil
import com.difft.android.chat.util.ThrottledDebouncer
import com.difft.android.chat.util.Util
import com.difft.android.chat.util.ViewUtil
import com.difft.android.imageeditor.core.Bounds
import com.difft.android.imageeditor.core.ColorableRenderer
import com.difft.android.imageeditor.core.ImageEditorView
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.SelectableRenderer
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.model.EditorModel
import com.difft.android.imageeditor.core.renderers.BezierDrawingRenderer
import com.difft.android.imageeditor.core.renderers.FaceBlurRenderer
import com.difft.android.imageeditor.core.renderers.MultiLineTextRenderer
import util.FontUtil
import util.concurrent.SimpleTask
import java.io.ByteArrayOutputStream
import java.util.Objects

// Faithful 1:1 Java->Kotlin port; splitting into smaller pieces is tracked as a follow-up.
@Suppress("LargeClass")
class ImageEditorFragment : Fragment(), ImageEditorHudV2.EventListener, MediaSendPageFragment, TextEntryDialogFragment.Controller {

    private var restoredModel: EditorModel? = null

    private var cachedFaceDetection: Pair<Uri, FaceDetectionResult>? = null

    private var currentSelection: EditorElement? = null
    private var imageMaxHeight = 0
    private var imageMaxWidth = 0

    private val deleteFadeDebouncer = ThrottledDebouncer(500)
    private var initialDialImageDegrees = 0f
    private var initialDialScale = 0f
    private var minDialScaleDown = 0f

    class Data @JvmOverloads constructor(private val bundle: Bundle = Bundle()) {

        fun writeModel(model: EditorModel) {
            val bytes = ParcelUtil.serialize(model)
            bundle.putByteArray("MODEL", bytes)
        }

        fun readModel(): EditorModel? {
            val bytes = bundle.getByteArray("MODEL") ?: return null
            return ParcelUtil.deserialize(bytes, EditorModel.CREATOR)
        }

        fun getBundle(): Bundle = bundle
    }

    private var imageUri: Uri? = null
    private lateinit var controller: Controller
    private var imageEditorHud: ImageEditorHudV2? = null
    private var imageEditorView: ImageEditorView? = null
    private var hasMadeAnEditThisSession = false
    private var wasInTrashHitZone = false

    fun setMode(mode: ImageEditorHudV2.Mode) {
        val currentMode = imageEditorHud!!.getMode()
        if (currentMode == mode) {
            return
        }
        imageEditorHud!!.setMode(mode)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateViewPortScaling(imageEditorHud!!.getMode(), imageEditorHud!!.getMode(), newConfig.orientation, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parent = parentFragment
        controller = when {
            parent is Controller -> parent
            activity is Controller -> activity as Controller
            else -> throw IllegalStateException("Parent must implement Controller interface.")
        }

        val arguments = arguments
        if (arguments != null) {
            imageUri = arguments.getParcelable(KEY_IMAGE_URI)
        }

        if (imageUri == null) {
            throw AssertionError("No KEY_IMAGE_URI supplied")
        }

        val mediaConstraints: MediaConstraints = PushMediaConstraints(SentMediaQuality.HIGH)

        // Dynamic size limit based on device memory to prevent OOM
        // Low memory devices: 1600 (10MB), Normal devices: 2560 (26MB) vs original 4096 (67MB)
        val baseDimension = if (Util.isLowMemory(requireContext())) 1600 else 2560
        imageMaxWidth = Math.min(baseDimension, mediaConstraints.getImageMaxWidth(requireContext()))
        imageMaxHeight = Math.min(baseDimension, mediaConstraints.getImageMaxHeight(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.image_editor_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.restoreState()

        val mode = Mode.getByCode(requireArguments().getString(KEY_MODE))

        if (mode == Mode.AVATAR_CAPTURE || mode == Mode.AVATAR_EDIT) {
            view.setPadding(
                0,
                ViewUtil.getStatusBarHeight(view),
                0,
                ViewUtil.getNavigationBarHeight(view)
            )
        }

        imageEditorHud = view.findViewById(R.id.scribble_hud)
        imageEditorView = view.findViewById(R.id.image_editor_view)

        imageEditorView!!.setTypefaceProvider(FontTypefaceProvider())
        if (!CAN_RENDER_EMOJI) {
            imageEditorView!!.addTextInputFilter(RemoveEmojiTextFilter())
        }

        val width = resources.displayMetrics.widthPixels
        val height = ((16 / 9f) * width).toInt()
        imageEditorView!!.minimumHeight = height
        imageEditorView!!.requestLayout()
        imageEditorHud!!.setBottomOfImageEditorView(resources.displayMetrics.heightPixels - height)

        imageEditorHud!!.setEventListener(this)

        imageEditorView!!.setDragListener(dragListener)
        imageEditorView!!.setTapListener(selectionListener)
        imageEditorView!!.setDrawingChangedListener { stillTouching -> onDrawingChanged(stillTouching, true) }
        imageEditorView!!.setUndoRedoStackListener { undoAvailable, redoAvailable -> onUndoRedoAvailabilityChanged(undoAvailable, redoAvailable) }

        var editorModel: EditorModel? = null

        if (restoredModel != null) {
            editorModel = restoredModel
            restoredModel = null
        }

        @ColorInt val blackoutColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1_night)
        if (editorModel == null) {
            editorModel = when (mode) {
                Mode.AVATAR_EDIT -> EditorModel.createForAvatarEdit(blackoutColor)
                Mode.AVATAR_CAPTURE -> EditorModel.createForAvatarCapture(blackoutColor)
                else -> EditorModel.create(blackoutColor)
            }

            val image = EditorElement(UriGlideRenderer(imageUri!!, false, imageMaxWidth, imageMaxHeight, UriGlideRenderer.STRONG_BLUR, mainImageRequestListener))
            image.flags.setSelectable(false).persist()
            editorModel.addElement(image)
        } else {
            controller.onMainImageLoaded()
        }

        if (mode == Mode.AVATAR_CAPTURE || mode == Mode.AVATAR_EDIT) {
            imageEditorHud!!.setUpForAvatarEditing()
        }

        if (mode == Mode.AVATAR_CAPTURE) {
            imageEditorHud!!.enterMode(ImageEditorHudV2.Mode.CROP)
        }

        if (mode == Mode.AVATAR_EDIT) {
            imageEditorHud!!.enterMode(ImageEditorHudV2.Mode.DRAW)
        }

        imageEditorView!!.setModel(editorModel)

        onDrawingChanged(false, false)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)
    }

    override fun setUri(uri: Uri) {
        this.imageUri = uri
    }

    override fun getUri(): Uri {
        return imageUri!!
    }

    override fun saveState(): Any {
        val data = Data()
        data.writeModel(imageEditorView!!.getModel())
        return data
    }

    override fun restoreState(state: Any) {
        if (state is Data) {
            val model = state.readModel()

            if (model != null) {
                if (imageEditorView != null) {
                    imageEditorView!!.setModel(model)
                    onDrawingChanged(false, false)
                } else {
                    this.restoredModel = model
                }
            }
        } else {
            L.w { "$TAG Received a bad saved state. Received class: ${state.javaClass.name}" }
        }
    }

    override fun notifyHidden() {
    }

    override fun onDestroyView() {
        // Clean up all blurred bitmaps when fragment view is destroyed
        // This prevents memory leaks when user leaves the editor
        clearBlurredBitmaps()

        imageEditorView = null
        imageEditorHud = null

        super.onDestroyView()
    }

    private fun changeEntityColor(selectedColor: Int) {
        if (currentSelection != null) {
            val renderer = currentSelection!!.renderer
            if (renderer is ColorableRenderer) {
                renderer.setColor(selectedColor)
                onDrawingChanged(false, true)
            }
        }
    }

    private fun startTextEntityEditing(textElement: EditorElement, selectAll: Boolean) {
        imageEditorView!!.startTextEditing(textElement)

        TextEntryDialogFragment.show(
            childFragmentManager,
            textElement,
            false,
            selectAll,
            imageEditorHud!!.getColorIndex()
        )
    }

    override fun zoomToFitText(editorElement: EditorElement, textRenderer: MultiLineTextRenderer) {
        imageEditorView!!.zoomToFitText(editorElement, textRenderer)
    }

    override fun onTextStyleToggle() {
        if (currentSelection != null && currentSelection!!.renderer is MultiLineTextRenderer) {
            (currentSelection!!.renderer as MultiLineTextRenderer).nextMode()
        }
    }

    override fun onTextEntryDialogDismissed(hasText: Boolean) {
        imageEditorView!!.doneTextEditing()

        if (hasText) {
            imageEditorHud!!.setMode(ImageEditorHudV2.Mode.MOVE_TEXT)
        } else {
            onUndo()
            imageEditorHud!!.setMode(ImageEditorHudV2.Mode.DRAW)
        }
    }

    protected fun addText() {
        val initialText = ""
        val color = imageEditorHud!!.getActiveColor()
        val renderer = MultiLineTextRenderer(initialText, color, MultiLineTextRenderer.Mode.REGULAR)
        val element = EditorElement(renderer, EditorModel.Z_TEXT)

        imageEditorView!!.getModel().addElementCentered(element, 1f)
        imageEditorView!!.invalidate()

        setCurrentSelection(element)

        startTextEntityEditing(element, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == SELECT_STICKER_REQUEST_CODE && data != null) {
            var renderer: Renderer? = null
            val uri = data.data
            if (uri != null) {
                renderer = UriGlideRenderer(uri, true, imageMaxWidth, imageMaxHeight)
            }
            if (renderer != null) {
                val element = EditorElement(renderer, EditorModel.Z_STICKERS)
                imageEditorView!!.getModel().addElementCentered(element, 0.4f)
                setCurrentSelection(element)
                hasMadeAnEditThisSession = true
                imageEditorHud!!.setMode(ImageEditorHudV2.Mode.MOVE_STICKER)
            }
        } else {
            imageEditorHud!!.setMode(ImageEditorHudV2.Mode.DRAW)
        }
    }

    override fun onModeStarted(mode: ImageEditorHudV2.Mode, previousMode: ImageEditorHudV2.Mode) {
        onBackPressedCallback.isEnabled = shouldHandleOnBackPressed(mode)

        imageEditorView!!.setMode(ImageEditorView.Mode.MoveAndResize)
        imageEditorView!!.doneTextEditing()

        controller.onTouchEventsNeeded(mode != ImageEditorHudV2.Mode.NONE)

        updateViewPortScaling(mode, previousMode, resources.configuration.orientation, false)

        if (mode != ImageEditorHudV2.Mode.CROP) {
            imageEditorView!!.getModel().doneCrop()
        }

        imageEditorView!!.getModel()
            .getTrash()
            .flags
            .setVisible(mode == ImageEditorHudV2.Mode.DELETE)
            .persist()

        updateHudDialRotation()

        when (mode) {
            ImageEditorHudV2.Mode.CROP -> {
                imageEditorView!!.getModel().startCrop()
            }

            ImageEditorHudV2.Mode.DRAW, ImageEditorHudV2.Mode.HIGHLIGHT -> {
                onBrushWidthChange()
            }

            ImageEditorHudV2.Mode.BLUR -> {
                onBrushWidthChange()
                imageEditorHud!!.setBlurFacesToggleEnabled(imageEditorView!!.getModel().hasFaceRenderer())
            }

            ImageEditorHudV2.Mode.TEXT -> {
                addText()
            }

            ImageEditorHudV2.Mode.MOVE_TEXT -> {
            }

            ImageEditorHudV2.Mode.NONE -> {
                setCurrentSelection(null)
                hasMadeAnEditThisSession = false
            }

            else -> {
            }
        }
    }

    private fun updateViewPortScaling(
        mode: ImageEditorHudV2.Mode,
        previousMode: ImageEditorHudV2.Mode,
        orientation: Int,
        force: Boolean
    ) {
        val shouldScaleViewPortForCurrentMode = shouldScaleViewPort(mode)
        val shouldScaleViewPortForPreviousMode = shouldScaleViewPort(previousMode)
        val hudProtection = getHudProtection(mode)

        if (shouldScaleViewPortForCurrentMode != shouldScaleViewPortForPreviousMode || force) {
            if (shouldScaleViewPortForCurrentMode) {
                scaleViewPortForDrawing(orientation, hudProtection)
            } else {
                restoreViewPortScaling(orientation)
            }
        }
    }

    private fun getHudProtection(mode: ImageEditorHudV2.Mode): Int {
        return if (mode == ImageEditorHudV2.Mode.CROP) {
            CROP_HUD_PROTECTION
        } else {
            DRAW_HUD_PROTECTION
        }
    }

    override fun onColorChange(color: Int) {
        imageEditorView!!.setDrawingBrushColor(color)
        changeEntityColor(color)
    }

    override fun onTextColorChange(colorIndex: Int) {
        imageEditorHud!!.setColorIndex(colorIndex)
        onColorChange(imageEditorHud!!.getActiveColor())
    }

    override fun onBrushWidthChange() {
        val mode = imageEditorHud!!.getMode()
        imageEditorView!!.startDrawing(
            imageEditorHud!!.getActiveBrushWidth(),
            if (mode == ImageEditorHudV2.Mode.HIGHLIGHT) Paint.Cap.SQUARE else Paint.Cap.ROUND,
            mode == ImageEditorHudV2.Mode.BLUR
        )
    }

    override fun onBlurFacesToggled(enabled: Boolean) {
        val model = imageEditorView!!.getModel()
        val mainImage = model.getMainImage()
        if (mainImage == null) {
            imageEditorHud!!.hideBlurToast()
            return
        }

        if (!enabled) {
            model.clearFaceRenderers()
            imageEditorHud!!.hideBlurToast()
            return
        }

        val inverseCropPosition = model.getInverseCropPosition()

        if (cachedFaceDetection != null) {
            if (cachedFaceDetection!!.first == getUri() && cachedFaceDetection!!.second.position == inverseCropPosition) {
                renderFaceBlurs(cachedFaceDetection!!.second)
                imageEditorHud!!.showBlurToast()
                return
            } else {
                cachedFaceDetection = null
            }
        }

        ComposeDialogManager.showWait(requireActivity())
        mainImage.flags.setChildrenVisible(false)

        SimpleTask.run(lifecycle, {
            if (mainImage.renderer != null) {
                val bitmap = (mainImage.renderer as UriGlideRenderer).getBitmap()
                if (bitmap != null) {
                    val detector: FaceDetector = AndroidFaceDetector()

                    val size = model.getOutputSizeMaxWidth(1000)
                    val render = model.render(ApplicationDependencies.getApplication(), size, FontTypefaceProvider())
                    try {
                        return@run FaceDetectionResult(detector.detect(render), Point(render.width, render.height), inverseCropPosition)
                    } finally {
                        render.recycle()
                        mainImage.flags.reset()
                    }
                }
            }

            FaceDetectionResult(emptyList(), Point(0, 0), Matrix())
        }, { result ->
            mainImage.flags.reset()
            renderFaceBlurs(result)
            ComposeDialogManager.dismissWait()
            imageEditorHud!!.showBlurToast()
        })
    }

    override fun onClearAll() {
        if (imageEditorView != null) {
            imageEditorView!!.getModel().clearUndoStack()
            updateHudDialRotation()
        }
    }

    override fun onCancel() {
        if (hasMadeAnEditThisSession) {
            ComposeDialogManager.showMessageDialogForJava(
                requireActivity(),
                getString(R.string.MediaReviewImagePageFragment__discard_changes),
                getString(R.string.MediaReviewImagePageFragment__youll_lose_any_changes),
                getString(R.string.MediaReviewImagePageFragment__discard),
                getString(android.R.string.cancel),
                true, // showCancel
                true, // cancelable
                {
                    imageEditorHud!!.setMode(ImageEditorHudV2.Mode.NONE)
                    controller.onCancelEditing()
                    Unit
                },
                null, // onCancel
                null  // onDismiss
            )
        } else {
            imageEditorHud!!.setMode(ImageEditorHudV2.Mode.NONE)
            controller.onCancelEditing()
        }
    }

    override fun onUndo() {
        imageEditorView!!.getModel().undo()
        imageEditorHud!!.setBlurFacesToggleEnabled(imageEditorView!!.getModel().hasFaceRenderer())
        updateHudDialRotation()
    }

    override fun onDelete() {
        imageEditorView!!.deleteElement(currentSelection)
    }

    override fun onSave() {
        SaveAttachmentUtil.showWarningDialog(requireContext(), {
            if (FileUtil.canWriteToMediaStore()) {
                performSaveToDisk()
            }
        })
    }

    override fun onFlipHorizontal() {
        imageEditorView!!.getModel().flipHorizontal()
    }

    override fun onRotate90AntiClockwise() {
        imageEditorView!!.getModel().rotate90anticlockwise()
    }

    override fun onCropAspectLock() {
        imageEditorView!!.getModel().setCropAspectLock(!imageEditorView!!.getModel().isCropAspectLocked())
    }

    override val isCropAspectLocked: Boolean
        get() = imageEditorView!!.getModel().isCropAspectLocked()

    override fun onRequestFullScreen(fullScreen: Boolean, hideKeyboard: Boolean) {
        controller.onRequestFullScreen(fullScreen, hideKeyboard)
    }

    override fun onDone() {
        controller.onDoneEditing()
    }

    override fun onDialRotationGestureStarted() {
        val localScaleX = imageEditorView!!.getModel().getMainImage()!!.getLocalScaleX()
        minDialScaleDown = initialDialScale / localScaleX
        imageEditorView!!.getModel().pushUndoPoint()
        imageEditorView!!.getModel().updateUndoRedoAvailabilityState()
        initialDialImageDegrees = Math.toDegrees(imageEditorView!!.getModel().getMainImage()!!.getLocalRotationAngle().toDouble()).toFloat()
    }

    override fun onDialRotationGestureFinished() {
        imageEditorView!!.getModel().getMainImage()!!.commitEditorMatrix()
        imageEditorView!!.getModel().postEdit(true)
        imageEditorView!!.invalidate()
    }

    override fun onDialRotationChanged(degrees: Float) {
        imageEditorView!!.setMainImageEditorMatrixRotation(degrees - initialDialImageDegrees, minDialScaleDown)
    }

    private fun updateHudDialRotation() {
        imageEditorHud!!.setDialRotation(getRotationDegreesRounded(imageEditorView!!.getModel().getMainImage()))
        initialDialScale = imageEditorView!!.getModel().getMainImage()!!.getLocalScaleX()
    }

    private var resizeAnimation: ResizeAnimation? = null

    private fun scaleViewPortForDrawing(orientation: Int, protection: Int) {
        resizeAnimation?.cancel()

        val aspectRatio = getAspectRatioForOrientation(orientation)
        var targetWidth = getWidthForOrientation(orientation) - ViewUtil.dpToPx(32)
        var targetHeight = ((1 / aspectRatio) * targetWidth).toInt()
        val maxHeight = getHeightForOrientation(orientation) - protection

        if (targetHeight > maxHeight) {
            targetHeight = maxHeight
            targetWidth = Math.round(targetHeight * aspectRatio)
        }

        resizeAnimation = ResizeAnimation(imageEditorView!!, targetWidth, targetHeight)
        resizeAnimation!!.duration = 250L
        resizeAnimation!!.interpolator = MediaAnimations.interpolator
        imageEditorView!!.startAnimation(resizeAnimation)
    }

    private fun restoreViewPortScaling(orientation: Int) {
        resizeAnimation?.cancel()

        val maxHeight = getHeightForOrientation(orientation)
        val aspectRatio = getAspectRatioForOrientation(orientation)
        var targetWidth = getWidthForOrientation(orientation)
        var targetHeight = ((1 / aspectRatio) * targetWidth).toInt()

        if (targetHeight > maxHeight) {
            targetHeight = maxHeight
            targetWidth = Math.round(targetHeight * aspectRatio)
        }

        resizeAnimation = ResizeAnimation(imageEditorView!!, targetWidth, targetHeight)
        resizeAnimation!!.duration = 250L
        resizeAnimation!!.interpolator = MediaAnimations.interpolator
        imageEditorView!!.startAnimation(resizeAnimation)
    }

    private fun getHeightForOrientation(orientation: Int): Int {
        return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            Math.max(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels)
        } else {
            Math.min(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels)
        }
    }

    private fun getWidthForOrientation(orientation: Int): Int {
        return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            Math.min(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels)
        } else {
            Math.max(resources.displayMetrics.heightPixels, resources.displayMetrics.widthPixels)
        }
    }

    private fun getAspectRatioForOrientation(orientation: Int): Float {
        return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            PORTRAIT_ASPECT_RATIO
        } else {
            1f / PORTRAIT_ASPECT_RATIO
        }
    }

    private fun performSaveToDisk() {
        // Check view lifecycle before accessing
        if (imageEditorView == null) {
            L.w { "[ImageEditor] Cannot save: view is null" }
            return
        }

        // Get model on UI thread before passing to worker thread
        val model = imageEditorView!!.getModel()
        if (model == null) {
            L.w { "[ImageEditor] Cannot save: model is null" }
            return
        }

        val context = context
        if (context == null) {
            L.w { "[ImageEditor] Cannot save: context is null" }
            return
        }

        SimpleTask.run({ renderToSingleUseBlob(context, model) }, { uri ->
            // Check if fragment is still attached when callback executes
            if (!isAdded || getContext() == null) {
                L.w { "[ImageEditor] Save completed but fragment is detached" }
                return@run
            }

            // renderToSingleUseBlob returns null when the underlying render fails
            // (source image deleted mid-edit, OOM, createDraftAttachment IO error, etc.).
            if (uri == null) {
                L.w { "[ImageEditor] Save aborted: render returned null" }
                Toast.makeText(requireContext(), R.string.operation_failed, Toast.LENGTH_SHORT).show()
                return@run
            }

            SaveAttachmentUtil.saveWithUIFromJava(
                requireContext(),
                viewLifecycleOwner,
                SaveAttachmentUtil.Attachment(uri, MediaUtil.IMAGE_JPEG, System.currentTimeMillis(), null, true, true)
            )
        })
    }

    private fun onDrawingChanged(stillTouching: Boolean, isUserEdit: Boolean) {
        if (isUserEdit) {
            hasMadeAnEditThisSession = true
        }
    }

    private fun onUndoRedoAvailabilityChanged(undoAvailable: Boolean, redoAvailable: Boolean) {
        imageEditorHud!!.setUndoAvailability(undoAvailable)
    }

    private fun renderFaceBlurs(result: FaceDetectionResult) {
        val faces = result.faces

        if (faces.isEmpty()) {
            cachedFaceDetection = null
            return
        }

        imageEditorView!!.getModel().pushUndoPoint()

        val faceMatrix = Matrix()

        for (face in faces) {
            val faceBlurRenderer: Renderer = FaceBlurRenderer()
            val element = EditorElement(faceBlurRenderer, EditorModel.Z_MASK)
            val localMatrix = element.localMatrix

            faceMatrix.setRectToRect(Bounds.FULL_BOUNDS, face.bounds, Matrix.ScaleToFit.FILL)

            localMatrix.set(result.position)
            localMatrix.preConcat(faceMatrix)

            element.flags.setEditable(false)
                .setSelectable(false)
                .persist()

            imageEditorView!!.getModel().addElementWithoutPushUndo(element)
        }

        imageEditorView!!.invalidate()

        cachedFaceDetection = Pair(getUri(), result)
    }

    private fun shouldHandleOnBackPressed(mode: ImageEditorHudV2.Mode): Boolean {
        return mode == ImageEditorHudV2.Mode.CROP ||
                mode == ImageEditorHudV2.Mode.DRAW ||
                mode == ImageEditorHudV2.Mode.HIGHLIGHT ||
                mode == ImageEditorHudV2.Mode.BLUR ||
                mode == ImageEditorHudV2.Mode.TEXT ||
                mode == ImageEditorHudV2.Mode.MOVE_STICKER ||
                mode == ImageEditorHudV2.Mode.MOVE_TEXT ||
                mode == ImageEditorHudV2.Mode.INSERT_STICKER
    }

    private fun onPopEditorMode() {
        setCurrentSelection(null)

        when (imageEditorHud!!.getMode()) {
            ImageEditorHudV2.Mode.NONE -> return
            ImageEditorHudV2.Mode.CROP -> onCancel()
            ImageEditorHudV2.Mode.DRAW, ImageEditorHudV2.Mode.HIGHLIGHT, ImageEditorHudV2.Mode.BLUR -> {
                if (Mode.getByCode(requireArguments().getString(KEY_MODE)) == Mode.NORMAL) {
                    onCancel()
                } else {
                    controller.onTouchEventsNeeded(true)
                    imageEditorHud!!.setMode(ImageEditorHudV2.Mode.CROP)
                }
            }

            ImageEditorHudV2.Mode.INSERT_STICKER,
            ImageEditorHudV2.Mode.MOVE_STICKER,
            ImageEditorHudV2.Mode.MOVE_TEXT,
            ImageEditorHudV2.Mode.DELETE,
            ImageEditorHudV2.Mode.TEXT -> {
                controller.onTouchEventsNeeded(true)
                imageEditorHud!!.setMode(ImageEditorHudV2.Mode.DRAW)
            }

            else -> {
            }
        }
    }

    private val mainImageRequestListener: RequestListener<Bitmap> = object : RequestListener<Bitmap> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
            controller.onMainImageFailedToLoad()
            return false
        }

        override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
            controller.onMainImageLoaded()
            return false
        }
    }

    fun getRotationDegreesRounded(editorElement: EditorElement?): Float {
        if (editorElement == null) {
            return 0f
        }
        return Math.round(Math.toDegrees(editorElement.getLocalRotationAngle().toDouble())).toFloat()
    }

    private val dragListener: ImageEditorView.DragListener = object : ImageEditorView.DragListener {
        override fun onDragStarted(editorElement: EditorElement?) {
            if (imageEditorHud!!.getMode() == ImageEditorHudV2.Mode.CROP) {
                updateHudDialRotation()
                return
            }

            if (editorElement == null || editorElement.renderer is BezierDrawingRenderer) {
                setCurrentSelection(null)
            } else {
                setCurrentSelection(editorElement)
            }

            if (imageEditorView!!.getMode() == ImageEditorView.Mode.MoveAndResize) {
                imageEditorHud!!.setMode(ImageEditorHudV2.Mode.DELETE)
            } else {
                imageEditorHud!!.animate().alpha(0f)
            }
        }

        override fun onDragMoved(editorElement: EditorElement?, isInTrashHitZone: Boolean) {
            if (imageEditorHud!!.getMode() == ImageEditorHudV2.Mode.CROP || editorElement == null) {
                updateHudDialRotation()
                return
            }

            if (isInTrashHitZone) {
                deleteFadeDebouncer.publish {
                    if (!wasInTrashHitZone) {
                        wasInTrashHitZone = true
                        if (imageEditorHud!!.isHapticFeedbackEnabled) {
                            imageEditorHud!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }

                    editorElement.animatePartialFadeOut { imageEditorView?.invalidate() }
                }
            } else {
                deleteFadeDebouncer.publish {
                    wasInTrashHitZone = false
                    editorElement.animatePartialFadeIn { imageEditorView?.invalidate() }
                }
            }
        }

        override fun onDragEnded(editorElement: EditorElement?, isInTrashHitZone: Boolean) {
            wasInTrashHitZone = false
            imageEditorHud!!.animate().alpha(1f)
            if (imageEditorHud!!.getMode() == ImageEditorHudV2.Mode.CROP) {
                updateHudDialRotation()
                return
            }

            if (isInTrashHitZone) {
                deleteFadeDebouncer.clear()
                onDelete()
                setCurrentSelection(null)
                onPopEditorMode()
            } else if (editorElement != null && editorElement.renderer is MultiLineTextRenderer) {
                editorElement.animatePartialFadeIn { imageEditorView?.invalidate() }

                if (imageEditorHud!!.getMode() != ImageEditorHudV2.Mode.TEXT) {
                    imageEditorHud!!.setMode(ImageEditorHudV2.Mode.MOVE_TEXT)
                }
            } else if (editorElement != null && editorElement.renderer is UriGlideRenderer) {
                editorElement.animatePartialFadeIn { imageEditorView?.invalidate() }
                imageEditorHud!!.setMode(ImageEditorHudV2.Mode.MOVE_STICKER)
            }
        }
    }

    private val selectionListener: ImageEditorView.TapListener = object : ImageEditorView.TapListener {

        override fun onEntityDown(editorElement: EditorElement?) {
            if (editorElement != null) {
                controller.onTouchEventsNeeded(true)
            }
        }

        override fun onEntitySingleTap(editorElement: EditorElement?) {
            setCurrentSelection(editorElement)
            if (currentSelection != null) {
                if (editorElement!!.renderer is MultiLineTextRenderer) {
                    setTextElement(editorElement, editorElement.renderer as ColorableRenderer, imageEditorView!!.isTextEditing())
                }
            } else {
                onPopEditorMode()
            }
        }

        override fun onEntityDoubleTap(editorElement: EditorElement) {
            setCurrentSelection(editorElement)
            if (editorElement.renderer is MultiLineTextRenderer) {
                setTextElement(editorElement, editorElement.renderer as ColorableRenderer, true)
            }
        }

        private fun setTextElement(
            editorElement: EditorElement,
            colorableRenderer: ColorableRenderer,
            startEditing: Boolean
        ) {
            val color = colorableRenderer.getColor()
            imageEditorHud!!.enterMode(ImageEditorHudV2.Mode.TEXT)
            imageEditorHud!!.setActiveColor(color)
            if (startEditing) {
                startTextEntityEditing(editorElement, false)
            }
        }
    }

    private fun setCurrentSelection(currentSelection: EditorElement?) {
        setSelectionState(this.currentSelection, false)

        this.currentSelection = currentSelection

        setSelectionState(this.currentSelection, true)

        imageEditorView!!.invalidate()
    }

    private fun setSelectionState(editorElement: EditorElement?, selected: Boolean) {
        if (editorElement != null && editorElement.renderer is SelectableRenderer) {
            (editorElement.renderer as SelectableRenderer).onSelected(selected)
        }
        imageEditorView!!.getModel().setSelected(if (selected) editorElement else null)
    }

    /**
     * Clears blurred bitmaps from the main image renderer to free memory.
     * Called after editing is complete to reduce memory footprint.
     * Blurred bitmaps will be recreated if needed during rendering (e.g., when sending).
     */
    fun clearBlurredBitmaps() {
        if (imageEditorView != null && imageEditorView!!.getModel() != null) {
            val model = imageEditorView!!.getModel()
            val mainImage = model.getMainImage()
            if (mainImage != null) {
                val renderer = mainImage.renderer
                if (renderer is UriGlideRenderer) {
                    renderer.clearBlurredBitmap()
                }
            }
        }
    }

    private val onBackPressedCallback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onPopEditorMode()
        }
    }

    interface Controller {
        fun onTouchEventsNeeded(needed: Boolean)

        fun onRequestFullScreen(fullScreen: Boolean, hideKeyboard: Boolean)

        fun onDoneEditing()

        fun onCancelEditing()

        fun onMainImageLoaded()

        fun onMainImageFailedToLoad()

        fun restoreState()
    }

    private class FaceDetectionResult(
        val faces: List<FaceDetector.Face>,
        imageSize: Point,
        positionMatrix: Matrix
    ) {
        val position: Matrix = Matrix(positionMatrix)

        init {
            val imageProjectionMatrix = Matrix()
            imageProjectionMatrix.setRectToRect(RectF(0f, 0f, imageSize.x.toFloat(), imageSize.y.toFloat()), Bounds.FULL_BOUNDS, Matrix.ScaleToFit.FILL)
            position.preConcat(imageProjectionMatrix)
        }
    }

    private enum class Mode(val code: String) {

        NORMAL("normal"),
        AVATAR_CAPTURE("avatar_capture"),
        AVATAR_EDIT("avatar_edit");

        companion object {
            fun getByCode(code: String?): Mode {
                if (code == null) {
                    return NORMAL
                }

                for (mode in values()) {
                    if (Objects.equals(code, mode.code)) {
                        return mode
                    }
                }

                return NORMAL
            }
        }
    }

    companion object {
        private const val TAG = "ImageEditorFragment"

        @JvmField
        val CAN_RENDER_EMOJI = FontUtil.canRenderEmojiAtFontSize(1024f)

        private const val PORTRAIT_ASPECT_RATIO = 9 / 16f

        private const val KEY_IMAGE_URI = "image_uri"
        private const val KEY_MODE = "mode"

        private const val SELECT_STICKER_REQUEST_CODE = 124

        private val DRAW_HUD_PROTECTION = ViewUtil.dpToPx(72)
        private val CROP_HUD_PROTECTION = ViewUtil.dpToPx(144)

        @JvmStatic
        fun newInstanceForAvatarCapture(imageUri: Uri): ImageEditorFragment {
            val fragment = newInstance(imageUri)
            fragment.requireArguments().putString(KEY_MODE, Mode.AVATAR_CAPTURE.code)
            return fragment
        }

        @JvmStatic
        fun newInstanceForAvatarEdit(imageUri: Uri): ImageEditorFragment {
            val fragment = newInstance(imageUri)
            fragment.requireArguments().putString(KEY_MODE, Mode.AVATAR_EDIT.code)
            return fragment
        }

        @JvmStatic
        fun newInstance(imageUri: Uri): ImageEditorFragment {
            val args = Bundle()
            args.putParcelable(KEY_IMAGE_URI, imageUri)
            args.putString(KEY_MODE, Mode.NORMAL.code)

            val fragment = ImageEditorFragment()
            fragment.arguments = args
            fragment.setUri(imageUri)
            return fragment
        }

        @JvmStatic
        @WorkerThread
        fun renderToSingleUseBlob(context: Context, editorModel: EditorModel): Uri? {
            var image: Bitmap? = null
            try {
                val outputStream = ByteArrayOutputStream()
                image = editorModel.render(context, FontTypefaceProvider())

                image.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                // Free the uncompressed pixel data (~10-48MB) before the blocking I/O wait below;
                // finally's null-guard then no-ops on success and still recycles if compress threw
                // after a successful render.
                image.recycle()
                image = null

                return MyBlobProvider.getInstance()
                    .forData(outputStream.toByteArray())
                    .withMimeType(MediaUtil.IMAGE_JPEG)
                    .createForDraftAttachmentAsync(context).get()
            } catch (e: Exception) {
                // Covers RuntimeException(GlideException) from UriGlideRenderer when the source
                // file vanished mid-edit, createDraftAttachment IO failures, and OOM.
                L.w(e) { "[ImageEditorFragment] renderToSingleUseBlob failed" }
                // Restore the worker thread's interrupt flag if it was cleared by either
                // a direct InterruptedException (from createDraftAttachmentAsync().get()) or
                // an InterruptedException wrapped into RuntimeException by UriGlideRenderer.
                if (e is InterruptedException || e.cause is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } finally {
                if (image != null) {
                    image.recycle()
                }
            }
            return null
        }

        private fun shouldScaleViewPort(mode: ImageEditorHudV2.Mode): Boolean {
            return mode != ImageEditorHudV2.Mode.NONE
        }
    }
}
