package org.thoughtcrime.securesms.mediasend.v2.review

import android.animation.Animator
import android.animation.AnimatorSet
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.kongzue.dialogx.dialogs.BottomDialog
import com.kongzue.dialogx.interfaces.OnBindView
import com.luck.picture.lib.entity.LocalMedia
import util.FileUtils
import util.logging.Log
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.HudCommand
import org.thoughtcrime.securesms.mediasend.v2.MediaAnimations
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionState
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionViewModel
import org.thoughtcrime.securesms.mediasend.v2.MediaValidator
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MemoryUnitFormat
import org.thoughtcrime.securesms.util.SystemWindowInsetsSetter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.views.TouchInterceptingFrameLayout
import org.thoughtcrime.securesms.util.visible
import org.thoughtcrime.securesms.video.TranscodingQuality
import org.thoughtcrime.securesms.video.videoconverter.VideoThumbnailsRangeSelectorView
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.annotation.meta.Exhaustive

/**
 * Allows the user to view and edit selected media.
 */
class MediaReviewFragment : Fragment(R.layout.v2_media_review_fragment), VideoThumbnailsRangeSelectorView.RangeDragListener {

    private val sharedViewModel: MediaSelectionViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    private lateinit var callback: Callback

    private lateinit var drawToolButton: View
    private lateinit var cropAndRotateButton: View
    private lateinit var qualityButton: ImageView
    private lateinit var saveButton: View
    private lateinit var sendButton: ImageView
    private lateinit var addMessageButton: AppCompatTextView
    private lateinit var pager: ViewPager2
    private lateinit var controls: ConstraintLayout
    private lateinit var selectionRecycler: RecyclerView
    private lateinit var controlsShade: View
    private lateinit var videoTimeLine: VideoThumbnailsRangeSelectorView
    private lateinit var videoSizeHint: TextView
    private lateinit var videoTimelinePlaceholder: View
    private lateinit var progress: ProgressBar
    private lateinit var progressWrapper: TouchInterceptingFrameLayout

    private val exclusionZone = listOf(Rect())

    private var animatorSet: AnimatorSet? = null
    private var sentMediaQuality: SentMediaQuality = SentMediaQuality.HIGH

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()

        SystemWindowInsetsSetter.attach(view, viewLifecycleOwner)

        callback = requireListener()

        drawToolButton = view.findViewById(R.id.draw_tool)
        cropAndRotateButton = view.findViewById(R.id.crop_and_rotate_tool)
        qualityButton = view.findViewById(R.id.quality_selector)
        saveButton = view.findViewById(R.id.save_to_media)
        sendButton = view.findViewById(R.id.send)
        addMessageButton = view.findViewById(R.id.add_a_message)
        pager = view.findViewById(R.id.media_pager)
        controls = view.findViewById(R.id.controls)
        selectionRecycler = view.findViewById(R.id.selection_recycler)
        controlsShade = view.findViewById(R.id.controls_shade)
        progress = view.findViewById(R.id.progress)
        progressWrapper = view.findViewById(R.id.progress_wrapper)
        videoTimeLine = view.findViewById(R.id.video_timeline)
        videoSizeHint = view.findViewById(R.id.video_size_hint)
        videoTimelinePlaceholder = view.findViewById(R.id.timeline_placeholder)

        DrawableCompat.setTint(progress.indeterminateDrawable, Color.WHITE)
        progressWrapper.setOnInterceptTouchEventListener { true }

        val pagerAdapter = MediaReviewFragmentPagerAdapter(this)

        sharedViewModel.hudCommands
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe {
                when (it) {
                    HudCommand.ResumeEntryTransition -> startPostponedEnterTransition()
                    else -> Unit
                }
            }

        pager.adapter = pagerAdapter

        controls.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val outRect: Rect = exclusionZone[0]
            videoTimeLine.getHitRect(outRect)
            outRect.left = left
            outRect.right = right
            ViewCompat.setSystemGestureExclusionRects(v, exclusionZone)
        }

        drawToolButton.setOnClickListener {
            sharedViewModel.sendCommand(HudCommand.StartDraw)
        }

        cropAndRotateButton.setOnClickListener {
            sharedViewModel.sendCommand(HudCommand.StartCropAndRotate)
        }

        qualityButton.setOnClickListener {
            showQualitySelectorDialog()
        }

        saveButton.setOnClickListener {
            sharedViewModel.sendCommand(HudCommand.SaveMedia)
        }

//        addMessageEditText.doAfterTextChanged {
//            sharedViewModel.setMessage(it)
//        }

        addMessageButton.setOnClickListener {
            AddMessageDialogFragment.show(parentFragmentManager, sharedViewModel.state.value?.message, false)
        }

        sendButton.setOnClickListener {
            performSend()
        }


        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                qualityButton.alpha = 0f
                saveButton.alpha = 0f
                sharedViewModel.onPageChanged(position)
            }
        })

        if (MediaConstraints.isVideoTranscodeAvailable()) {
            videoTimeLine.registerEditorOnRangeChangeListener(this)
        }

        val selectionAdapter = MappingAdapter(false)
        MediaReviewSelectedItem.register(selectionAdapter) { media, isSelected ->
            if (isSelected) {
                sharedViewModel.removeMedia(media)
            } else {
                sharedViewModel.onPageChanged(media)
            }
        }
        selectionRecycler.adapter = selectionAdapter
        ItemTouchHelper(MediaSelectionItemTouchHelper(sharedViewModel)).attachToRecyclerView(selectionRecycler)

        sharedViewModel.state.observe(viewLifecycleOwner) { state ->
            pagerAdapter.submitMedia(state.selectedMedia)

            selectionAdapter.submitList(
                state.selectedMedia.map { MediaReviewSelectedItem.Model(it, state.focusedMedia == it) }
            )

            presentPager(state)
            presentAddMessageEntry(state.message)
            presentImageQualityToggle(state)
            if (state.quality != sentMediaQuality) {
                presentQualityToggleToast(state)
            }
            sentMediaQuality = state.quality

            presentVideoTimeline(state)
            presentVideoSizeHint(state)

            computeViewStateAndAnimate(state)
        }

        sharedViewModel.mediaErrors
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe(this::handleMediaValidatorFilterError)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    callback.onPopFromReview()
                }
            }
        )
    }

    private fun presentQualityToggleToast(state: MediaSelectionState) {
        val mediaList = state.selectedMedia
        if (mediaList.isEmpty()) {
            return
        }

        val description = if (mediaList.size == 1) {
            val media: LocalMedia = mediaList[0]
            if (MediaUtil.isNonGifVideo(media)) {
                if (state.quality == SentMediaQuality.HIGH) {
                    getString(R.string.MediaReviewFragment__video_set_to_high_quality)
                } else {
                    getString(R.string.MediaReviewFragment__video_set_to_standard_quality)
                }
            } else if (MediaUtil.isImageType(media.mimeType)) {
                if (state.quality == SentMediaQuality.HIGH) {
                    getString(R.string.MediaReviewFragment__photo_set_to_high_quality)
                } else {
                    getString(R.string.MediaReviewFragment__photo_set_to_standard_quality)
                }
            } else {
                Log.i(TAG, "Could not display quality toggle toast for attachment of type: ${media.mimeType}")
                return
            }
        } else {
            if (state.quality == SentMediaQuality.HIGH) {
                resources.getQuantityString(R.plurals.MediaReviewFragment__items_set_to_high_quality, mediaList.size, mediaList.size)
            } else {
                resources.getQuantityString(R.plurals.MediaReviewFragment__items_set_to_standard_quality, mediaList.size, mediaList.size)
            }
        }

        val icon = when (state.quality) {
            SentMediaQuality.HIGH -> R.drawable.symbol_quality_high_24
            else -> R.drawable.symbol_quality_high_slash_24
        }

        MediaReviewToastPopupWindow.show(controls, icon, description)
    }

    override fun onResume() {
        super.onResume()
        sharedViewModel.kick()
    }

    private fun handleMediaValidatorFilterError(error: MediaValidator.FilterError) {
        @Exhaustive
        when (error) {
            MediaValidator.FilterError.None -> return
            MediaValidator.FilterError.ItemTooLarge -> Toast.makeText(requireContext(), R.string.MediaReviewFragment__one_or_more_items_were_too_large, Toast.LENGTH_SHORT).show()
            MediaValidator.FilterError.ItemInvalidType -> Toast.makeText(requireContext(), R.string.MediaReviewFragment__one_or_more_items_were_invalid, Toast.LENGTH_SHORT).show()
            MediaValidator.FilterError.TooManyItems -> Toast.makeText(requireContext(), R.string.MediaReviewFragment__too_many_items_selected, Toast.LENGTH_SHORT).show()
            is MediaValidator.FilterError.NoItems -> {
                if (error.cause != null) {
                    handleMediaValidatorFilterError(error.cause)
                } else {
                    Toast.makeText(requireContext(), R.string.MediaReviewFragment__one_or_more_items_were_invalid, Toast.LENGTH_SHORT).show()
                }
                callback.onNoMediaSelected()
            }
        }

        sharedViewModel.clearMediaErrors()
    }

    private fun performSend() {
        progressWrapper.visible = true
        progressWrapper.animate()
            .setStartDelay(300)
            .setInterpolator(MediaAnimations.interpolator)
            .alpha(1f)

        sharedViewModel
            .send()
            .to(RxUtil.autoDispose(this))
            .subscribe(
                { result -> callback.onSentWithResult(result) },
                { error -> callback.onSendError(error) },
                { callback.onSentWithoutResult() }
            )
    }

    private fun presentAddMessageEntry(message: CharSequence?) {
        addMessageButton.setText(
            message.takeIf { !it.isNullOrEmpty() } ?: getString(R.string.MediaReviewFragment__add_a_message),
            TextView.BufferType.SPANNABLE
        )
    }


    private fun presentImageQualityToggle(state: MediaSelectionState) {
        qualityButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
                startToStart = ConstraintLayout.LayoutParams.UNSET
                startToEnd = cropAndRotateButton.id
            } else {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                startToEnd = ConstraintLayout.LayoutParams.UNSET
            }
        }
        qualityButton.setImageResource(
            when (state.quality) {
                SentMediaQuality.STANDARD -> R.drawable.symbol_quality_high_slash_24
                SentMediaQuality.HIGH -> R.drawable.symbol_quality_high_24
            }
        )
    }

    private fun presentPager(state: MediaSelectionState) {
        pager.isUserInputEnabled = state.isTouchEnabled

        val indexOfSelectedItem = state.selectedMedia.indexOf(state.focusedMedia)

        if (pager.currentItem == indexOfSelectedItem) {
            return
        }

        if (indexOfSelectedItem != -1) {
            pager.setCurrentItem(indexOfSelectedItem, false)
        } else {
            pager.setCurrentItem(0, false)
        }
    }

    private fun presentVideoTimeline(state: MediaSelectionState) {
        val mediaItem = state.focusedMedia ?: return
        if (!MediaUtil.isVideoType(mediaItem.mimeType) || !MediaConstraints.isVideoTranscodeAvailable()) {
            return
        }
        val uri = Uri.parse(mediaItem.realPath)
        val updatedInputInTimeline = videoTimeLine.setInput(uri)
        if (updatedInputInTimeline) {
            videoTimeLine.unregisterDragListener()
        }
        val size: Long = FileUtils.getFileLength(mediaItem.realPath)
        val maxSend = sharedViewModel.getMediaConstraints().getVideoMaxSize(requireContext())
        if (size > maxSend) {
            videoTimeLine.setTimeLimit(state.transcodingPreset.calculateMaxVideoUploadDurationInSeconds(maxSend), TimeUnit.SECONDS)
        }

        if (state.isTouchEnabled) {
            val data = state.getOrCreateVideoTrimData(uri)

            if (data.totalInputDurationUs > 0) {
                videoTimeLine.setRange(data.startTimeUs, data.endTimeUs)
            }
        }
    }

    private fun presentVideoSizeHint(state: MediaSelectionState) {
        val focusedMedia = state.focusedMedia ?: return
        val trimData = state.getOrCreateVideoTrimData(Uri.parse(focusedMedia.realPath))

        videoSizeHint.text = if (state.isVideoTrimmingVisible) {
            val seconds = trimData.getDuration().inWholeSeconds
            val bytes = TranscodingQuality.createFromPreset(state.transcodingPreset, trimData.getDuration().inWholeMilliseconds).byteCountEstimate
            String.format(Locale.getDefault(), "%d:%02d • %s", seconds / 60, seconds % 60, MemoryUnitFormat.formatBytes(bytes, MemoryUnitFormat.MEGA_BYTES, true))
        } else {
            null
        }
    }

    private fun computeViewStateAndAnimate(state: MediaSelectionState) {
        this.animatorSet?.cancel()

        val animators = mutableListOf<Animator>()

        animators.addAll(computeAddMessageAnimators(state))
        animators.addAll(computeAddMediaButtonsAnimators(state))
        animators.addAll(computeSendButtonAnimators(state))
        animators.addAll(computeSaveButtonAnimators(state))
        animators.addAll(computeQualityButtonAnimators(state))
        animators.addAll(computeCropAndRotateButtonAnimators(state))
        animators.addAll(computeDrawToolButtonAnimators(state))
        animators.addAll(computeControlsShadeAnimators(state))
        animators.addAll(computeVideoTimelineAnimator(state))

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(animators)
        animatorSet.start()

        this.animatorSet = animatorSet
    }

    private fun computeControlsShadeAnimators(state: MediaSelectionState): List<Animator> {
        val animators = mutableListOf<Animator>()
        animators += if (state.isTouchEnabled) {
            MediaReviewAnimatorController.getFadeInAnimator(controlsShade)
        } else {
            MediaReviewAnimatorController.getFadeOutAnimator(controlsShade)
        }

        animators += if (state.isVideoTrimmingVisible) {
            MediaReviewAnimatorController.getHeightAnimator(videoTimelinePlaceholder, videoTimelinePlaceholder.height, 44.dp)
        } else {
            MediaReviewAnimatorController.getHeightAnimator(videoTimelinePlaceholder, videoTimelinePlaceholder.height, 1.dp)
        }

        return animators
    }

    private fun computeVideoTimelineAnimator(state: MediaSelectionState): List<Animator> {
        val animators = mutableListOf<Animator>()

        if (state.isVideoTrimmingVisible) {
            animators += MediaReviewAnimatorController.getFadeInAnimator(videoTimeLine).apply {
                startDelay = 100
                duration = 500
            }
        } else {
            animators += MediaReviewAnimatorController.getFadeOutAnimator(videoTimeLine).apply {
                duration = 400
            }
        }

        animators += if (state.isVideoTrimmingVisible && state.isTouchEnabled) {
            MediaReviewAnimatorController.getFadeInAnimator(videoSizeHint).apply {
                startDelay = 100
                duration = 500
            }
        } else {
            MediaReviewAnimatorController.getFadeOutAnimator(videoSizeHint).apply {
                duration = 400
            }
        }

        return animators
    }

    private fun computeAddMessageAnimators(state: MediaSelectionState): List<Animator> {
        return if (!state.isTouchEnabled) {
            listOf(
                MediaReviewAnimatorController.getFadeOutAnimator(addMessageButton)
            )
        } else {
            listOf(
                MediaReviewAnimatorController.getFadeInAnimator(addMessageButton)
            )
        }
    }

    private fun computeAddMediaButtonsAnimators(state: MediaSelectionState): List<Animator> {
        return when {
            !state.isTouchEnabled -> {
                listOf(
                    MediaReviewAnimatorController.getFadeOutAnimator(selectionRecycler)
                )
            }

            state.selectedMedia.size > 1 -> {
                listOf(
                    MediaReviewAnimatorController.getFadeInAnimator(selectionRecycler)
                )
            }

            else -> {
                listOf(
                    MediaReviewAnimatorController.getFadeOutAnimator(selectionRecycler)
                )
            }
        }
    }

    private fun computeSendButtonAnimators(state: MediaSelectionState): List<Animator> {
        return if (state.isTouchEnabled) {
            listOf(
                MediaReviewAnimatorController.getFadeInAnimator(sendButton, isEnabled = state.canSend)
            )
        } else {
            listOf(
                MediaReviewAnimatorController.getFadeOutAnimator(sendButton, isEnabled = state.canSend)
            )
        }
    }

    private fun computeSaveButtonAnimators(state: MediaSelectionState): List<Animator> {
        return if (state.isTouchEnabled && MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
            listOf(
                MediaReviewAnimatorController.getFadeInAnimator(saveButton)
            )
        } else {
            listOf(
                MediaReviewAnimatorController.getFadeOutAnimator(saveButton)
            )
        }
    }

    private fun computeQualityButtonAnimators(state: MediaSelectionState): List<Animator> {
        return if (state.isTouchEnabled) {
            listOf(MediaReviewAnimatorController.getFadeInAnimator(qualityButton))
        } else {
            listOf(MediaReviewAnimatorController.getFadeOutAnimator(qualityButton))
        }
    }

    private fun computeCropAndRotateButtonAnimators(state: MediaSelectionState): List<Animator> {
        return if (state.isTouchEnabled && MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
            listOf(MediaReviewAnimatorController.getFadeInAnimator(cropAndRotateButton))
        } else {
            listOf(MediaReviewAnimatorController.getFadeOutAnimator(cropAndRotateButton))
        }
    }

    private fun computeDrawToolButtonAnimators(state: MediaSelectionState): List<Animator> {
        return if (state.isTouchEnabled && MediaUtil.isImageAndNotGif(state.focusedMedia?.mimeType ?: "")) {
            listOf(MediaReviewAnimatorController.getFadeInAnimator(drawToolButton))
        } else {
            listOf(MediaReviewAnimatorController.getFadeOutAnimator(drawToolButton))
        }
    }


    companion object {
        private val TAG = Log.tag(MediaReviewFragment::class.java)
    }

    interface Callback {
        fun onSentWithResult(mediaSendActivityResult: MediaSendActivityResult)
        fun onSentWithoutResult()
        fun onSendError(error: Throwable)
        fun onNoMediaSelected()
        fun onPopFromReview()
    }

    override fun onRangeDrag(minValue: Long, maxValue: Long, duration: Long, end: Boolean) {
        sharedViewModel.onEditVideoDuration(context = requireContext(), totalDurationUs = duration, startTimeUs = minValue, endTimeUs = maxValue, touchEnabled = end)
    }

    private var qualitySelectorDialog: BottomDialog? = null

    private fun showQualitySelectorDialog() {

        val standardQuality = sharedViewModel.state.value?.quality == SentMediaQuality.STANDARD

        qualitySelectorDialog = BottomDialog.build()
            .setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg3))
            .setCustomView(object : OnBindView<BottomDialog?>(R.layout.layout_media_quality_select_dialog) {
                override fun onBind(dialog: BottomDialog?, v: View) {
                    val btnStandard = v.findViewById<TextView>(R.id.btn_standard)
                    val btnHigh = v.findViewById<TextView>(R.id.btn_high)

                    if (standardQuality) {
                        btnStandard.setBackgroundResource(R.drawable.media_quality_select_background)
                        btnHigh.setBackgroundResource(0)
                    } else {
                        btnStandard.setBackgroundResource(0)
                        btnHigh.setBackgroundResource(R.drawable.media_quality_select_background)
                    }

                    btnStandard.setOnClickListener {
                        qualitySelectorDialog?.dismiss()
                        sharedViewModel.setSentMediaQuality(SentMediaQuality.STANDARD)
                    }

                    btnHigh.setOnClickListener {
                        qualitySelectorDialog?.dismiss()
                        sharedViewModel.setSentMediaQuality(SentMediaQuality.HIGH)
                    }
                }
            })
            .show()
    }
}
