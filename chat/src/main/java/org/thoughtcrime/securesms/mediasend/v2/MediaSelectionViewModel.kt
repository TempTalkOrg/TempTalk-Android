package org.thoughtcrime.securesms.mediasend.v2

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.luck.picture.lib.entity.LocalMedia
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import util.logging.Log
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.videos.VideoTrimData
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.scribbles.ImageEditorFragment
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.livedata.Store
import java.util.Collections
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel which maintains the list of selected media and other shared values.
 */
class MediaSelectionViewModel(
    initialMedia: List<LocalMedia>,
    private val repository: MediaSelectionRepository
) : ViewModel() {

    private val TAG = Log.tag(MediaSelectionViewModel::class.java)

    private val selectedMediaSubject: Subject<List<LocalMedia>> = BehaviorSubject.create()

    private val store: Store<MediaSelectionState> = Store(MediaSelectionState())

    val state: LiveData<MediaSelectionState> = store.stateLiveData

    private val internalHudCommands = PublishSubject.create<HudCommand>()
    val hudCommands: Observable<HudCommand> = internalHudCommands

    val mediaErrors: BehaviorSubject<MediaValidator.FilterError> = BehaviorSubject.createDefault(MediaValidator.FilterError.None)

    fun sendCommand(hudCommand: HudCommand) {
        internalHudCommands.onNext(hudCommand)
    }

    fun setTouchEnabled(isEnabled: Boolean) {
        store.update { it.copy(isTouchEnabled = isEnabled) }
    }

    fun onPageChanged(media: LocalMedia) {
        store.update { it.copy(focusedMedia = media) }
    }

    fun onPageChanged(position: Int) {
        store.update {
            if (position >= it.selectedMedia.size) {
                it.copy(focusedMedia = null)
            } else {
                val focusedMedia: LocalMedia = it.selectedMedia[position]
                it.copy(focusedMedia = focusedMedia)
            }
        }
    }

    private fun addMedia(media: List<LocalMedia>) {
        val newSelectionList: List<LocalMedia> = linkedSetOf<LocalMedia>().apply {
            addAll(store.state.selectedMedia)
            addAll(media)
        }.toList()

        store.update {
            val initializedVideoEditorStates = newSelectionList.filterNot { media -> it.editorStateMap.containsKey(Uri.parse(media.realPath)) }
                .filter { media -> MediaUtil.isVideoType(media.mimeType) }
                .associate { media: LocalMedia ->
                    val duration = media.duration.milliseconds.inWholeMicroseconds
                    Uri.parse(media.realPath) to VideoTrimData(false, duration, 0, duration)
                }
            val initializedImageEditorStates = newSelectionList.filterNot { media -> it.editorStateMap.containsKey(Uri.parse(media.realPath)) }
                .filter { media -> MediaUtil.isImageAndNotGif(media.mimeType) }
                .associate { media: LocalMedia ->
                    Uri.parse(media.realPath) to ImageEditorFragment.Data()
                }
            it.copy(
                selectedMedia = newSelectionList,
                focusedMedia = it.focusedMedia ?: newSelectionList.first(),
                editorStateMap = it.editorStateMap + initializedVideoEditorStates + initializedImageEditorStates
            )
        }

        selectedMediaSubject.onNext(newSelectionList)
    }


    fun swapMedia(originalStart: Int, end: Int): Boolean {
        var start: Int = originalStart

        if (lastMediaDrag.first == start && lastMediaDrag.second == end) {
            return true
        } else if (lastMediaDrag.first == start) {
            start = lastMediaDrag.second
        }

        val snapshot = store.state

        if (end >= snapshot.selectedMedia.size || end < 0 || start >= snapshot.selectedMedia.size || start < 0) {
            return false
        }

        lastMediaDrag = Pair(originalStart, end)

        val newMediaList = snapshot.selectedMedia.toMutableList()

        if (start < end) {
            for (i in start until end) {
                Collections.swap(newMediaList, i, i + 1)
            }
        } else {
            for (i in start downTo end + 1) {
                Collections.swap(newMediaList, i, i - 1)
            }
        }

        store.update {
            it.copy(
                selectedMedia = newMediaList
            )
        }

        return true
    }

    fun isValidMediaDragPosition(position: Int): Boolean {
        return position >= 0 && position < store.state.selectedMedia.size
    }

    fun onMediaDragFinished() {
        lastMediaDrag = Pair(0, 0)
    }

    fun removeMedia(media: LocalMedia) {
        val snapshot = store.state
        val newMediaList = snapshot.selectedMedia - media
        val oldFocusIndex = snapshot.selectedMedia.indexOf(media)
        val newFocus = when {
            newMediaList.isEmpty() -> null
            media == snapshot.focusedMedia -> newMediaList[Util.clamp(oldFocusIndex, 0, newMediaList.size - 1)]
            else -> snapshot.focusedMedia
        }

        store.update {
            it.copy(
                selectedMedia = newMediaList,
                focusedMedia = newFocus,
                editorStateMap = it.editorStateMap - Uri.parse(media.realPath)
            )
        }

        if (newMediaList.isEmpty() && !store.state.suppressEmptyError) {
            mediaErrors.onNext(MediaValidator.FilterError.NoItems())
        }

        selectedMediaSubject.onNext(newMediaList)
        repository.deleteBlobs(listOf(media))
    }

    fun clearMediaErrors() {
        mediaErrors.onNext(MediaValidator.FilterError.None)
    }

    fun kick() {
        store.update { it }
    }

    fun getMediaConstraints(): MediaConstraints {
        return MediaConstraints.getPushMediaConstraints()
    }


    fun setSentMediaQuality(sentMediaQuality: SentMediaQuality) {
        if (sentMediaQuality == store.state.quality) {
            return
        }

        store.update { it.copy(quality = sentMediaQuality, isPreUploadEnabled = false) }
    }

    fun setMessage(text: CharSequence?) {
        store.update { it.copy(message = text) }
    }

    fun onEditVideoDuration(context: Context, totalDurationUs: Long, startTimeUs: Long, endTimeUs: Long, touchEnabled: Boolean) {
        store.update {
            val uri = it.focusedMedia?.realPath?.let { path -> Uri.parse(path) } ?: return@update it
            val data = it.getOrCreateVideoTrimData(uri)
            val clampedStartTime = max(startTimeUs, 0)

            val unedited = !data.isDurationEdited
            val durationEdited = clampedStartTime > 0 || endTimeUs < totalDurationUs
            val isEntireDuration = startTimeUs == 0L && endTimeUs == totalDurationUs
            val endMoved = !isEntireDuration && data.endTimeUs != endTimeUs
            val maxVideoDurationUs: Long = it.transcodingPreset.calculateMaxVideoUploadDurationInSeconds(getMediaConstraints().getVideoMaxSize(context)).seconds.inWholeMicroseconds
            val preserveStartTime = unedited || !endMoved
            val videoTrimData = VideoTrimData(durationEdited, totalDurationUs, clampedStartTime, endTimeUs)
            val updatedData = clampToMaxClipDuration(videoTrimData, maxVideoDurationUs, preserveStartTime)

            if (updatedData != videoTrimData) {
                Log.d(TAG, "Video trim clamped from ${videoTrimData.startTimeUs}, ${videoTrimData.endTimeUs} to ${updatedData.startTimeUs}, ${updatedData.endTimeUs}")
            }

            if (unedited && durationEdited) {
                Log.d(TAG, "Canceling upload because the duration has been edited for the first time..")
            }

            it.copy(
                isTouchEnabled = touchEnabled,
                editorStateMap = it.editorStateMap + (uri to updatedData)
            )
        }
    }

    fun getEditorState(uri: Uri): Any? {
        return store.state.editorStateMap[uri]
    }

    fun setEditorState(uri: Uri, state: Any) {
        store.update {
            it.copy(editorStateMap = it.editorStateMap + (uri to state))
        }
    }

    fun send(
        scheduledDate: Long? = null
    ): Maybe<MediaSendActivityResult> = send(scheduledDate ?: -1)

    fun send(): Maybe<MediaSendActivityResult> {
        return repository.send(
            selectedMedia = store.state.selectedMedia,
            stateMap = store.state.editorStateMap,
            quality = store.state.quality,
            message = store.state.message,
        )
    }

    private val disposables = CompositeDisposable()

    private var lastMediaDrag: Pair<Int, Int> = Pair(0, 0)

    init {
        if (initialMedia.isNotEmpty()) {
            addMedia(initialMedia)
        }
    }

    override fun onCleared() {
        disposables.clear()
    }


    companion object {
        private const val STATE_PREFIX = "selection.view.model"

        @JvmStatic
        fun clampToMaxClipDuration(data: VideoTrimData, maxVideoDurationUs: Long, preserveStartTime: Boolean): VideoTrimData {
            if (!MediaConstraints.isVideoTranscodeAvailable()) {
                return data
            }

            if ((data.endTimeUs - data.startTimeUs) <= maxVideoDurationUs) {
                return data
            }

            return data.copy(
                isDurationEdited = true,
                startTimeUs = if (!preserveStartTime) data.endTimeUs - maxVideoDurationUs else data.startTimeUs,
                endTimeUs = if (preserveStartTime) data.startTimeUs + maxVideoDurationUs else data.endTimeUs
            )
        }
    }

    class Factory(
        private val initialMedia: List<LocalMedia>,
        private val repository: MediaSelectionRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return requireNotNull(modelClass.cast(MediaSelectionViewModel(initialMedia, repository)))
        }
    }
}
