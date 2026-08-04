package com.difft.android.selector.config

import android.content.pm.ActivityInfo
import com.difft.android.selector.basic.IBridgeLoaderFactory
import com.difft.android.selector.basic.IBridgeViewLifecycle
import com.difft.android.selector.basic.InterpolatorFactory
import com.difft.android.selector.engine.CompressEngine
import com.difft.android.selector.engine.CompressFileEngine
import com.difft.android.selector.engine.CropEngine
import com.difft.android.selector.engine.CropFileEngine
import com.difft.android.selector.engine.ExtendLoaderEngine
import com.difft.android.selector.engine.ImageEngine
import com.difft.android.selector.engine.SandboxFileEngine
import com.difft.android.selector.engine.UriToFileTransformEngine
import com.difft.android.selector.engine.VideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnCustomLoadingListener
import com.difft.android.selector.interfaces.OnExternalPreviewEventListener
import com.difft.android.selector.interfaces.OnGridItemSelectAnimListener
import com.difft.android.selector.interfaces.OnInjectActivityPreviewListener
import com.difft.android.selector.interfaces.OnInjectLayoutResourceListener
import com.difft.android.selector.interfaces.OnPermissionDeniedListener
import com.difft.android.selector.interfaces.OnPermissionDescriptionListener
import com.difft.android.selector.interfaces.OnPermissionsInterceptListener
import com.difft.android.selector.interfaces.OnPreviewInterceptListener
import com.difft.android.selector.interfaces.OnResultCallbackListener
import com.difft.android.selector.interfaces.OnSelectAnimListener
import com.difft.android.selector.interfaces.OnSelectLimitTipsListener
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.magical.BuildRecycleItemViewParams
import com.difft.android.selector.style.PictureSelectorStyle
import com.difft.android.selector.thread.PictureThreadUtils
import com.difft.android.selector.utils.FileDirMap
import com.difft.android.selector.utils.SdkVersionUtils

/**
 * PictureSelector config hub. The `@JvmField` annotations on the public fields are retained from
 * the staged Java→Kotlin port (harmless for Kotlin callers now that the module is 100% Kotlin).
 * Field-level dead-code pruning of the remaining unused config fields is deliberately deferred to
 * a follow-up (issue #1077).
 */
class SelectorConfig {
    @JvmField var chooseMode: Int = 0
    @JvmField var isOnlyCamera: Boolean = false
    @JvmField var isDirectReturnSingle: Boolean = false
    @JvmField var cameraImageFormat: String = ""
    @JvmField var cameraVideoFormat: String = ""
    @JvmField var cameraImageFormatForQ: String = ""
    @JvmField var cameraVideoFormatForQ: String = ""
    @JvmField var requestedOrientation: Int = 0
    @JvmField var isCameraAroundState: Boolean = false
    @JvmField var selectionMode: Int = 0
    @JvmField var maxSelectNum: Int = 0
    @JvmField var minSelectNum: Int = 0
    @JvmField var maxVideoSelectNum: Int = 0
    @JvmField var minVideoSelectNum: Int = 0
    @JvmField var minAudioSelectNum: Int = 0
    @JvmField var videoQuality: Int = 0
    @JvmField var filterVideoMaxSecond: Int = 0
    @JvmField var filterVideoMinSecond: Int = 0
    @JvmField var selectMaxDurationSecond: Int = 0
    @JvmField var selectMinDurationSecond: Int = 0
    @JvmField var recordVideoMaxSecond: Int = 0
    @JvmField var recordVideoMinSecond: Int = 0
    @JvmField var imageSpanCount: Int = 0
    @JvmField var filterMaxFileSize: Long = 0
    @JvmField var filterMinFileSize: Long = 0
    @JvmField var selectMaxFileSize: Long = 0
    @JvmField var selectMinFileSize: Long = 0
    @JvmField var language: Int = 0
    @JvmField var defaultLanguage: Int = 0
    @JvmField var isDisplayCamera: Boolean = false
    @JvmField var isGif: Boolean = false
    @JvmField var isWebp: Boolean = false
    @JvmField var isBmp: Boolean = false
    @JvmField var isHeic: Boolean = false
    @JvmField var isEnablePreviewImage: Boolean = false
    @JvmField var isEnablePreviewVideo: Boolean = false
    @JvmField var isEnablePreviewAudio: Boolean = false
    @JvmField var isPreviewFullScreenMode: Boolean = false
    @JvmField var isPreviewZoomEffect: Boolean = false
    @JvmField var isOpenClickSound: Boolean = false
    @JvmField var isEmptyResultReturn: Boolean = false
    @JvmField var isHidePreviewDownload: Boolean = false
    @JvmField var isHidePreviewShare: Boolean = false
    @JvmField var isShowConfidentialTip: Boolean = false
    @JvmField var isWithVideoImage: Boolean = false
    @JvmField var queryOnlyImageList: MutableList<String> = ArrayList()
    @JvmField var queryOnlyVideoList: MutableList<String> = ArrayList()
    @JvmField var queryOnlyAudioList: MutableList<String> = ArrayList()
    @JvmField var skipCropList: MutableList<String> = ArrayList()
    @JvmField var isCheckOriginalImage: Boolean = false
    @JvmField var outPutCameraImageFileName: String = ""
    @JvmField var outPutCameraVideoFileName: String = ""
    @JvmField var outPutAudioFileName: String = ""
    @JvmField var outPutCameraDir: String = ""
    @JvmField var outPutAudioDir: String = ""
    @JvmField var sandboxDir: String = ""
    @JvmField var originalPath: String = ""
    @JvmField var cameraPath: String = ""
    @JvmField var sortOrder: String = ""
    @JvmField var defaultAlbumName: String = ""
    @JvmField var pageSize: Int = 0
    @JvmField var isPageStrategy: Boolean = false
    @JvmField var isFilterInvalidFile: Boolean = false
    @JvmField var isMaxSelectEnabledMask: Boolean = false
    @JvmField var animationMode: Int = 0
    @JvmField var isAutomaticTitleRecyclerTop: Boolean = false
    @JvmField var isQuickCapture: Boolean = false
    @JvmField var isCameraRotateImage: Boolean = false
    @JvmField var isAutoRotating: Boolean = false
    @JvmField var isSyncCover: Boolean = false
    @JvmField var ofAllCameraType: Int = 0
    @JvmField var isOnlySandboxDir: Boolean = false
    @JvmField var isResultListenerBack: Boolean = false
    @JvmField var isInjectLayoutResource: Boolean = false
    @JvmField var isActivityResultBack: Boolean = false
    @JvmField var isCompressEngine: Boolean = false
    @JvmField var isLoaderDataEngine: Boolean = false
    @JvmField var isLoaderFactoryEngine: Boolean = false
    @JvmField var isSandboxFileEngine: Boolean = false
    @JvmField var isOriginalControl: Boolean = false
    @JvmField var isDisplayTimeAxis: Boolean = false
    @JvmField var isFastSlidingSelect: Boolean = false
    @JvmField var isSelectZoomAnim: Boolean = false
    @JvmField var isAutoVideoPlay: Boolean = false
    @JvmField var isLoopAutoPlay: Boolean = false
    @JvmField var isFilterSizeDuration: Boolean = false
    @JvmField var isPageSyncAsCount: Boolean = false
    @JvmField var isPauseResumePlay: Boolean = false
    @JvmField var isSyncWidthAndHeight: Boolean = false
    @JvmField var isOriginalSkipCompress: Boolean = false
    @JvmField var isPreloadFirst: Boolean = false
    @JvmField var isUseSystemVideoPlayer: Boolean = false
    @JvmField var isNewKeyBackMode: Boolean = false
    @JvmField var selectorStyle: PictureSelectorStyle = PictureSelectorStyle()

    init {
        initDefaultValue()
    }

    private fun initDefaultValue() {
        chooseMode = SelectMimeType.ofImage()
        isOnlyCamera = false
        selectionMode = SelectModeConfig.MULTIPLE
        selectorStyle = PictureSelectorStyle()
        maxSelectNum = 9
        minSelectNum = 0
        maxVideoSelectNum = 1
        minVideoSelectNum = 0
        minAudioSelectNum = 0
        videoQuality = VideoQuality.VIDEO_QUALITY_HIGH
        language = LanguageConfig.UNKNOWN_LANGUAGE
        defaultLanguage = LanguageConfig.SYSTEM_LANGUAGE
        filterVideoMaxSecond = 0
        filterVideoMinSecond = 0
        selectMaxDurationSecond = 0
        selectMinDurationSecond = 0
        filterMaxFileSize = 0
        filterMinFileSize = 0
        selectMaxFileSize = 0
        selectMinFileSize = 0
        recordVideoMaxSecond = 60
        recordVideoMinSecond = 0
        imageSpanCount = PictureConfig.DEFAULT_SPAN_COUNT
        isCameraAroundState = false
        isWithVideoImage = false
        isDisplayCamera = true
        isGif = false
        isWebp = true
        isBmp = true
        isHeic = true
        isCheckOriginalImage = false
        isDirectReturnSingle = false
        isEnablePreviewImage = true
        isEnablePreviewVideo = true
        isEnablePreviewAudio = true
        isHidePreviewDownload = false
        isShowConfidentialTip = false
        isOpenClickSound = false
        isEmptyResultReturn = false
        cameraImageFormat = PictureMimeType.JPEG
        cameraVideoFormat = PictureMimeType.MP4
        cameraImageFormatForQ = PictureMimeType.MIME_TYPE_IMAGE
        cameraVideoFormatForQ = PictureMimeType.MIME_TYPE_VIDEO
        outPutCameraImageFileName = ""
        outPutCameraVideoFileName = ""
        outPutAudioFileName = ""
        queryOnlyImageList = ArrayList()
        queryOnlyVideoList = ArrayList()
        queryOnlyAudioList = ArrayList()
        outPutCameraDir = ""
        outPutAudioDir = ""
        sandboxDir = ""
        originalPath = ""
        cameraPath = ""
        pageSize = PictureConfig.MAX_PAGE_SIZE
        isPageStrategy = true
        isFilterInvalidFile = false
        isMaxSelectEnabledMask = false
        animationMode = -1
        isAutomaticTitleRecyclerTop = true
        isQuickCapture = true
        isCameraRotateImage = true
        isAutoRotating = true
        isSyncCover = !SdkVersionUtils.isQ()
        ofAllCameraType = SelectMimeType.ofAll()
        isOnlySandboxDir = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        isResultListenerBack = true
        isActivityResultBack = false
        isCompressEngine = false
        isLoaderDataEngine = false
        isLoaderFactoryEngine = false
        isSandboxFileEngine = false
        isPreviewFullScreenMode = true
        isPreviewZoomEffect = chooseMode != SelectMimeType.ofAudio()
        isOriginalControl = false
        isInjectLayoutResource = false
        isDisplayTimeAxis = true
        isFastSlidingSelect = false
        skipCropList = ArrayList()
        sortOrder = ""
        isSelectZoomAnim = true
        defaultAlbumName = ""
        isAutoVideoPlay = false
        isLoopAutoPlay = false
        isFilterSizeDuration = true
        isPageSyncAsCount = false
        isPauseResumePlay = false
        isSyncWidthAndHeight = true
        isOriginalSkipCompress = false
        isPreloadFirst = true
        isNewKeyBackMode = true
        isUseSystemVideoPlayer = false
    }

    // Callback listening
    @JvmField var imageEngine: ImageEngine? = null
    @JvmField var compressEngine: CompressEngine? = null
    @JvmField var compressFileEngine: CompressFileEngine? = null
    @JvmField var cropEngine: CropEngine? = null
    @JvmField var cropFileEngine: CropFileEngine? = null
    @JvmField var sandboxFileEngine: SandboxFileEngine? = null
    @JvmField var uriToFileTransformEngine: UriToFileTransformEngine? = null
    @JvmField var loaderDataEngine: ExtendLoaderEngine? = null
    @JvmField var videoPlayerEngine: VideoPlayerEngine<*>? = null
    @JvmField var viewLifecycle: IBridgeViewLifecycle? = null
    @JvmField var loaderFactory: IBridgeLoaderFactory? = null
    @JvmField var interpolatorFactory: InterpolatorFactory? = null
    @JvmField var onSelectLimitTipsListener: OnSelectLimitTipsListener? = null
    @JvmField var onResultCallListener: OnResultCallbackListener<LocalMedia>? = null
    @JvmField var onExternalPreviewEventListener: OnExternalPreviewEventListener? = null
    @JvmField var onInjectActivityPreviewListener: OnInjectActivityPreviewListener? = null
    @JvmField var onPermissionsEventListener: OnPermissionsInterceptListener? = null
    @JvmField var onLayoutResourceListener: OnInjectLayoutResourceListener? = null
    @JvmField var onPreviewInterceptListener: OnPreviewInterceptListener? = null
    @JvmField var onPermissionDescriptionListener: OnPermissionDescriptionListener? = null
    @JvmField var onPermissionDeniedListener: OnPermissionDeniedListener? = null
    @JvmField var onItemSelectAnimListener: OnGridItemSelectAnimListener? = null
    @JvmField var onSelectAnimListener: OnSelectAnimListener? = null
    @JvmField var onCustomLoadingListener: OnCustomLoadingListener? = null

    // selected current album folder
    @JvmField var currentLocalMediaFolder: LocalMediaFolder? = null

    // selected result
    @JvmField val selectedResult = ArrayList<LocalMedia>()

    @Synchronized
    fun getSelectedResult(): ArrayList<LocalMedia> = selectedResult

    val selectCount: Int get() = selectedResult.size

    fun addSelectResult(media: LocalMedia) {
        selectedResult.add(media)
    }

    fun addAllSelectResult(result: ArrayList<LocalMedia>) {
        selectedResult.addAll(result)
    }

    val resultFirstMimeType: String
        get() = if (selectedResult.size > 0) selectedResult[0].mimeType else ""

    // selected preview result
    @JvmField val selectedPreviewResult = ArrayList<LocalMedia>()

    fun addSelectedPreviewResult(list: ArrayList<LocalMedia>?) {
        if (list != null) {
            selectedPreviewResult.clear()
            selectedPreviewResult.addAll(list)
        }
    }

    // all album data source
    @JvmField val albumDataSource = ArrayList<LocalMediaFolder>()

    fun addAlbumDataSource(list: List<LocalMediaFolder>?) {
        if (list != null) {
            albumDataSource.clear()
            albumDataSource.addAll(list)
        }
    }

    // all data source
    @JvmField val dataSource = ArrayList<LocalMedia>()

    fun addDataSource(list: ArrayList<LocalMedia>?) {
        if (list != null) {
            dataSource.clear()
            dataSource.addAll(list)
        }
    }

    fun destroy() {
        imageEngine = null
        compressEngine = null
        compressFileEngine = null
        cropEngine = null
        cropFileEngine = null
        sandboxFileEngine = null
        uriToFileTransformEngine = null
        loaderDataEngine = null
        onResultCallListener = null
        onExternalPreviewEventListener = null
        onInjectActivityPreviewListener = null
        onPermissionsEventListener = null
        onLayoutResourceListener = null
        onPreviewInterceptListener = null
        onSelectLimitTipsListener = null
        onPermissionDescriptionListener = null
        onPermissionDeniedListener = null
        viewLifecycle = null
        loaderFactory = null
        interpolatorFactory = null
        onItemSelectAnimListener = null
        onSelectAnimListener = null
        videoPlayerEngine = null
        onCustomLoadingListener = null
        currentLocalMediaFolder = null
        dataSource.clear()
        selectedResult.clear()
        albumDataSource.clear()
        selectedPreviewResult.clear()
        PictureThreadUtils.cancel(PictureThreadUtils.getIoPool())
        BuildRecycleItemViewParams.clear()
        FileDirMap.clear()
        LocalMedia.destroyPool()
    }
}
