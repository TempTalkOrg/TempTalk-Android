package com.difft.android.selector

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.selector.adapter.PicturePreviewAdapter
import com.difft.android.selector.adapter.holder.BasePreviewHolder
import com.difft.android.selector.adapter.holder.PreviewGalleryAdapter
import com.difft.android.selector.adapter.holder.PreviewVideoHolder
import com.difft.android.selector.basic.PictureCommonFragment
import com.difft.android.selector.basic.PictureMediaScannerConnection
import com.difft.android.selector.config.Crop
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.decoration.HorizontalItemDecoration
import com.difft.android.selector.decoration.WrapContentLinearLayoutManager
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.MediaExtraInfo
import com.difft.android.selector.interfaces.OnCallbackListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener
import com.difft.android.selector.loader.IBridgeMediaLoader
import com.difft.android.selector.loader.LocalMediaLoader
import com.difft.android.selector.loader.LocalMediaPageLoader
import com.difft.android.selector.magical.BuildRecycleItemViewParams
import com.difft.android.selector.magical.MagicalView
import com.difft.android.selector.magical.OnMagicalViewCallback
import com.difft.android.selector.magical.ViewParams
import com.difft.android.selector.manager.SelectedManager
import com.difft.android.selector.style.SelectMainStyle
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.DensityUtil
import com.difft.android.selector.utils.DownloadFileUtils
import com.difft.android.selector.utils.MediaUtils
import com.difft.android.selector.utils.StyleUtils
import com.difft.android.selector.utils.ValueOf
import com.difft.android.selector.widget.BottomNavBar
import com.difft.android.selector.widget.CompleteSelectView
import com.difft.android.selector.widget.PreviewBottomNavBar
import com.difft.android.selector.widget.PreviewTitleBar
import com.difft.android.selector.widget.TitleBar
import java.io.File
import java.util.Collections

/**
 * 1:1 Java→Kotlin port (issue #1077); structural split deferred — see #1077 future work.
 */
@Suppress("LargeClass")
open class PictureSelectorPreviewFragment : PictureCommonFragment() {

    protected var mData: ArrayList<LocalMedia> = ArrayList()

    protected lateinit var magicalView: MagicalView

    protected lateinit var viewPager: ViewPager2

    protected lateinit var viewPageAdapter: PicturePreviewAdapter

    protected lateinit var bottomNarBar: PreviewBottomNavBar

    protected lateinit var titleBar: PreviewTitleBar

    /**
     * if there more
     */
    protected var isHasMore = true

    protected var curPosition = 0

    protected var isInternalBottomPreview = false

    protected var isSaveInstanceState = false

    /**
     * 当前相册
     */
    protected var currentAlbum: String? = null

    /**
     * 是否显示了拍照入口
     */
    protected var isShowCamera = false

    /**
     * 是否外部预览进来
     */
    protected var isExternalPreview = false

    /**
     * 外部预览是否支持删除
     */
    protected var isDisplayDelete = false

    protected var isAnimationStart = false

    protected var totalNum = 0

    protected var screenWidth = 0
    protected var screenHeight = 0

    protected var mBucketId = -1L

    protected lateinit var tvSelected: TextView

    protected lateinit var tvSelectedWord: TextView

    protected lateinit var selectClickArea: View

    protected lateinit var ivShare: AppCompatImageView

    protected lateinit var completeSelectView: CompleteSelectView

    protected var needScaleBig = true

    protected var needScaleSmall = false

    protected var mGalleryRecycle: RecyclerView? = null

    protected var mGalleryAdapter: PreviewGalleryAdapter? = null

    protected val mAnimViews: MutableList<View> = ArrayList()

    private var isPause = false

    override fun getFragmentTag(): String = TAG

    /**
     * 内部预览
     *
     * @param isBottomPreview  是否顶部预览进来的
     * @param currentAlbumName 当前预览的目录
     * @param isShowCamera     是否有显示拍照图标
     * @param position         预览下标
     * @param totalNum         当前预览总数
     * @param page             当前页码
     * @param currentBucketId  当前相册目录id
     * @param data             预览数据源
     */
    fun setInternalPreviewData(
        isBottomPreview: Boolean, currentAlbumName: String?, isShowCamera: Boolean,
        position: Int, totalNum: Int, page: Int, currentBucketId: Long,
        data: ArrayList<LocalMedia>
    ) {
        this.mPage = page
        this.mBucketId = currentBucketId
        this.mData = data
        this.totalNum = totalNum
        this.curPosition = position
        this.currentAlbum = currentAlbumName
        this.isShowCamera = isShowCamera
        this.isInternalBottomPreview = isBottomPreview
    }

    /**
     * 外部预览
     *
     * @param position        预览下标
     * @param totalNum        当前预览总数
     * @param data            预览数据源
     * @param isDisplayDelete 是否显示删除按钮
     */
    fun setExternalPreviewData(position: Int, totalNum: Int, data: ArrayList<LocalMedia>, isDisplayDelete: Boolean) {
        this.mData = data
        this.totalNum = totalNum
        this.curPosition = position
        this.isDisplayDelete = isDisplayDelete
        this.isExternalPreview = true
    }

    override fun getResourceId(): Int {
        val layoutResourceId = InjectResourceSource.getLayoutResource(context, InjectResourceSource.PREVIEW_LAYOUT_RESOURCE, selectorConfig)
        if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) {
            return layoutResourceId
        }
        return R.layout.ps_fragment_preview
    }

    override fun onSelectedChange(isAddRemove: Boolean, currentMedia: LocalMedia) {
        // 更新TitleBar和BottomNarBar选择态
        tvSelected.isSelected = selectorConfig.selectedResult.contains(currentMedia)
        bottomNarBar.setSelectedChange()
        completeSelectView.setSelectedChange(true)
        notifySelectNumberStyle(currentMedia)
        notifyPreviewGalleryData(isAddRemove, currentMedia)
    }

    override fun onCheckOriginalChange() {
        bottomNarBar.setOriginalCheck()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reStartSavedInstance(savedInstanceState)
        isSaveInstanceState = savedInstanceState != null
        screenWidth = DensityUtil.getRealScreenWidth(requireContext())
        screenHeight = DensityUtil.getScreenHeight(requireContext())
        titleBar = view.findViewById(R.id.title_bar)
        tvSelected = view.findViewById(R.id.ps_tv_selected)
        tvSelectedWord = view.findViewById(R.id.ps_tv_selected_word)
        selectClickArea = view.findViewById(R.id.select_click_area)
        ivShare = view.findViewById(R.id.ps_iv_share)
        completeSelectView = view.findViewById(R.id.ps_complete_select)
        magicalView = view.findViewById(R.id.magical)
        viewPager = ViewPager2(requireContext())
        bottomNarBar = view.findViewById(R.id.bottom_nar_bar)
        magicalView.setMagicalContent(viewPager)
        setMagicalViewBackgroundColor()
        setMagicalViewAction()
        addAminViews(titleBar, tvSelected, tvSelectedWord, selectClickArea, completeSelectView, bottomNarBar, ivShare)
        onCreateLoader()
        initTitleBar()
        initViewPagerData(mData)
        if (isExternalPreview) {
            externalPreviewStyle()
        } else {
            initBottomNavBar()
            initPreviewSelectGallery(view as ViewGroup)
            initComplete()
        }
        iniMagicalView()
    }

    /**
     * addAminViews
     */
    fun addAminViews(vararg views: View) {
        Collections.addAll(mAnimViews, *views)
    }

    private fun setMagicalViewBackgroundColor() {
        val mainStyle = selectorConfig.selectorStyle.selectMainStyle!!
        if (StyleUtils.checkStyleValidity(mainStyle.previewBackgroundColor)) {
            magicalView.setBackgroundColor(mainStyle.previewBackgroundColor)
        } else {
            if (selectorConfig.chooseMode == SelectMimeType.ofAudio() ||
                (mData.size > 0 && PictureMimeType.isHasAudio(mData[0].mimeType))
            ) {
                magicalView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ps_color_white))
            } else {
                magicalView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ps_color_black))
            }
        }
    }

    override fun reStartSavedInstance(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            mPage = savedInstanceState.getInt(PictureConfig.EXTRA_CURRENT_PAGE, 1)
            mBucketId = savedInstanceState.getLong(PictureConfig.EXTRA_CURRENT_BUCKET_ID, -1)
            curPosition = savedInstanceState.getInt(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, curPosition)
            isShowCamera = savedInstanceState.getBoolean(PictureConfig.EXTRA_DISPLAY_CAMERA, isShowCamera)
            totalNum = savedInstanceState.getInt(PictureConfig.EXTRA_PREVIEW_CURRENT_ALBUM_TOTAL, totalNum)
            isExternalPreview = savedInstanceState.getBoolean(PictureConfig.EXTRA_EXTERNAL_PREVIEW, isExternalPreview)
            isDisplayDelete = savedInstanceState.getBoolean(PictureConfig.EXTRA_EXTERNAL_PREVIEW_DISPLAY_DELETE, isDisplayDelete)
            isInternalBottomPreview = savedInstanceState.getBoolean(PictureConfig.EXTRA_BOTTOM_PREVIEW, isInternalBottomPreview)
            currentAlbum = savedInstanceState.getString(PictureConfig.EXTRA_CURRENT_ALBUM_NAME, "")
            if (mData.size == 0) {
                mData.addAll(ArrayList(selectorConfig.selectedPreviewResult))
            }
        }
    }

    override fun onKeyBackFragmentFinish() {
        onKeyDownBackToMin()
    }

    /**
     * 设置MagicalView
     */
    private fun iniMagicalView() {
        if (isHasMagicalEffect()) {
            val alpha = if (isSaveInstanceState) 1.0f else 0.0f
            magicalView.setBackgroundAlpha(alpha)
            for (i in mAnimViews.indices) {
                if (mAnimViews[i] is TitleBar) {
                    continue
                }
                mAnimViews[i].alpha = alpha
            }
        } else {
            magicalView.setBackgroundAlpha(1.0f)
        }
    }

    private fun isHasMagicalEffect(): Boolean {
        return !isInternalBottomPreview && selectorConfig.isPreviewZoomEffect
    }

    /**
     * 设置MagicalView监听器
     */
    protected fun setMagicalViewAction() {
        if (isHasMagicalEffect()) {
            magicalView.setOnMojitoViewCallback(object : OnMagicalViewCallback {
                override fun onBeginBackMinAnim() {
                    onMojitoBeginBackMinAnim()
                }

                override fun onBeginMagicalAnimComplete(mojitoView: MagicalView, showImmediately: Boolean) {
                    onMojitoBeginAnimComplete(mojitoView, showImmediately)
                }

                override fun onBackgroundAlpha(alpha: Float) {
                    onMojitoBackgroundAlpha(alpha)
                }

                override fun onMagicalViewFinish() {
                    onMojitoMagicalViewFinish()
                }

                override fun onBeginBackMinMagicalFinish(isResetSize: Boolean) {
                    onMojitoBeginBackMinFinish(isResetSize)
                }
            })
        }
    }

    /**
     * 开始准备执行缩放动画
     */
    protected fun onMojitoBeginBackMinAnim() {
        val currentHolder = viewPageAdapter.getCurrentHolder(viewPager.currentItem) ?: return
        if (currentHolder.coverImageView!!.visibility == View.GONE) {
            currentHolder.coverImageView!!.visibility = View.VISIBLE
        }
        if (currentHolder is PreviewVideoHolder) {
            if (currentHolder.ivPlayButton!!.visibility == View.VISIBLE) {
                currentHolder.ivPlayButton!!.visibility = View.GONE
            }
        }
    }

    /**
     * 关闭缩放动画执行完成后关闭页面
     */
    protected fun onMojitoMagicalViewFinish() {
        if (isExternalPreview && isNormalDefaultEnter() && isHasMagicalEffect()) {
            onExitPictureSelector()
        } else {
            onBackCurrentFragment()
        }
    }

    /**
     * 缩放动画执行时透明度跟随变化
     */
    protected fun onMojitoBackgroundAlpha(alpha: Float) {
        for (i in mAnimViews.indices) {
            if (mAnimViews[i] is TitleBar) {
                continue
            }
            mAnimViews[i].alpha = alpha
        }
    }

    /**
     * 关闭缩放动画执行完成
     */
    protected fun onMojitoBeginBackMinFinish(isResetSize: Boolean) {
        val itemViewParams = BuildRecycleItemViewParams.getItemViewParams(if (isShowCamera) curPosition + 1 else curPosition) ?: return
        val currentHolder = viewPageAdapter.getCurrentHolder(viewPager.currentItem) ?: return
        currentHolder.coverImageView!!.layoutParams.width = itemViewParams.width
        currentHolder.coverImageView!!.layoutParams.height = itemViewParams.height
        currentHolder.coverImageView!!.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /**
     * 缩放动画执行完成
     */
    protected fun onMojitoBeginAnimComplete(mojitoView: MagicalView, showImmediately: Boolean) {
        val currentHolder = viewPageAdapter.getCurrentHolder(viewPager.currentItem) ?: return
        val media = mData[viewPager.currentItem]
        val realWidth: Int
        val realHeight: Int
        if (media.isCut && media.cropImageWidth > 0 && media.cropImageHeight > 0) {
            realWidth = media.cropImageWidth
            realHeight = media.cropImageHeight
        } else {
            realWidth = media.width
            realHeight = media.height
        }
        if (MediaUtils.isLongImage(realWidth, realHeight)) {
            currentHolder.coverImageView!!.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            currentHolder.coverImageView!!.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (currentHolder is PreviewVideoHolder) {
            if (selectorConfig.isAutoVideoPlay) {
                startAutoVideoPlay(viewPager.currentItem)
            } else {
                if (currentHolder.ivPlayButton!!.visibility == View.GONE) {
                    if (!isPlaying()) {
                        currentHolder.ivPlayButton!!.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(PictureConfig.EXTRA_CURRENT_PAGE, mPage)
        outState.putLong(PictureConfig.EXTRA_CURRENT_BUCKET_ID, mBucketId)
        outState.putInt(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, curPosition)
        outState.putInt(PictureConfig.EXTRA_PREVIEW_CURRENT_ALBUM_TOTAL, totalNum)
        outState.putBoolean(PictureConfig.EXTRA_EXTERNAL_PREVIEW, isExternalPreview)
        outState.putBoolean(PictureConfig.EXTRA_EXTERNAL_PREVIEW_DISPLAY_DELETE, isDisplayDelete)
        outState.putBoolean(PictureConfig.EXTRA_DISPLAY_CAMERA, isShowCamera)
        outState.putBoolean(PictureConfig.EXTRA_BOTTOM_PREVIEW, isInternalBottomPreview)
        outState.putString(PictureConfig.EXTRA_CURRENT_ALBUM_NAME, currentAlbum)
        selectorConfig.addSelectedPreviewResult(mData)
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (isHasMagicalEffect()) {
            // config.isPreviewZoomEffect模式下使用缩放动画
            return null
        }
        val windowAnimationStyle = selectorConfig.selectorStyle.windowAnimationStyle!!
        if (windowAnimationStyle.activityPreviewEnterAnimation != 0 && windowAnimationStyle.activityPreviewExitAnimation != 0) {
            val loadAnimation = AnimationUtils.loadAnimation(
                activity,
                if (enter) windowAnimationStyle.activityPreviewEnterAnimation else windowAnimationStyle.activityPreviewExitAnimation
            )
            if (enter) {
                onEnterFragment()
            } else {
                onExitFragment()
            }
            return loadAnimation
        } else {
            return super.onCreateAnimation(transit, enter, nextAnim)
        }
    }

    override fun sendChangeSubSelectPositionEvent(adapterChange: Boolean) {
        if (selectorConfig.selectorStyle.selectMainStyle!!.isPreviewSelectNumberStyle) {
            if (selectorConfig.selectorStyle.selectMainStyle!!.isSelectNumberStyle) {
                for (index in 0 until selectorConfig.selectCount) {
                    val media = selectorConfig.selectedResult[index]
                    media.num = index + 1
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isHasMagicalEffect() && mData.size > curPosition) {
            val media = mData[curPosition]
            if (PictureMimeType.isHasVideo(media.mimeType)) {
                getVideoRealSizeFromMedia(media, false, object : OnCallbackListener<IntArray> {
                    override fun onCall(data: IntArray?) {
                        changeViewParams(data!!)
                    }
                })
            } else {
                getImageRealSizeFromMedia(media, false, object : OnCallbackListener<IntArray> {
                    override fun onCall(data: IntArray?) {
                        changeViewParams(data!!)
                    }
                })
            }
        }
    }

    private fun changeViewParams(size: IntArray) {
        val viewParams = BuildRecycleItemViewParams.getItemViewParams(if (isShowCamera) curPosition + 1 else curPosition)
        if (viewParams == null || size[0] == 0 || size[1] == 0) {
            magicalView.setViewParams(0, 0, 0, 0, size[0], size[1])
            magicalView.resetStartNormal(size[0], size[1], false)
        } else {
            magicalView.setViewParams(viewParams.left, viewParams.top, viewParams.width, viewParams.height, size[0], size[1])
            magicalView.resetStart()
        }
    }

    override fun onCreateLoader() {
        if (isExternalPreview) {
            return
        }
        val factory = selectorConfig.loaderFactory
        if (factory != null) {
            mLoader = factory.onCreateLoader()
            if (mLoader == null) {
                throw NullPointerException("No available " + IBridgeMediaLoader::class.java + " loader found")
            }
        } else {
            mLoader = if (selectorConfig.isPageStrategy)
                LocalMediaPageLoader(getAppContext(), selectorConfig)
            else
                LocalMediaLoader(getAppContext(), selectorConfig)
        }
    }

    /**
     * 加载更多
     */
    private fun loadMoreData() {
        mPage++
        val engine = selectorConfig.loaderDataEngine
        if (engine != null) {
            engine.loadMoreMediaData(
                requireContext(), mBucketId, mPage,
                selectorConfig.pageSize, selectorConfig.pageSize,
                object : OnQueryDataResultListener<LocalMedia>() {
                    override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                        handleMoreData(result, isHasMore)
                    }
                })
        } else {
            mLoader.loadPageMediaData(mBucketId, mPage, selectorConfig.pageSize, object : OnQueryDataResultListener<LocalMedia>() {
                override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                    handleMoreData(result, isHasMore)
                }
            })
        }
    }

    private fun handleMoreData(result: List<LocalMedia>, isHasMore: Boolean) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        this.isHasMore = isHasMore
        if (isHasMore) {
            if (result.isNotEmpty()) {
                val oldStartPosition = mData.size
                mData.addAll(result)
                val itemCount = mData.size
                viewPageAdapter.notifyItemRangeChanged(oldStartPosition, itemCount)
            } else {
                loadMoreData()
            }
        }
    }

    @Suppress("LongMethod")
    private fun initComplete() {
        val selectMainStyle = selectorConfig.selectorStyle.selectMainStyle!!

        if (StyleUtils.checkStyleValidity(selectMainStyle.previewSelectBackground)) {
            tvSelected.setBackgroundResource(selectMainStyle.previewSelectBackground)
        } else if (StyleUtils.checkStyleValidity(selectMainStyle.selectBackground)) {
            tvSelected.setBackgroundResource(selectMainStyle.selectBackground)
        }
        if (StyleUtils.checkStyleValidity(selectMainStyle.previewSelectTextResId)) {
            tvSelectedWord.text = getString(selectMainStyle.previewSelectTextResId)
        } else if (StyleUtils.checkTextValidity(selectMainStyle.previewSelectText)) {
            tvSelectedWord.text = selectMainStyle.previewSelectText
        } else {
            tvSelectedWord.text = ""
        }
        if (StyleUtils.checkSizeValidity(selectMainStyle.previewSelectTextSize)) {
            tvSelectedWord.textSize = selectMainStyle.previewSelectTextSize.toFloat()
        }

        if (StyleUtils.checkStyleValidity(selectMainStyle.previewSelectTextColor)) {
            tvSelectedWord.setTextColor(selectMainStyle.previewSelectTextColor)
        }

        if (StyleUtils.checkSizeValidity(selectMainStyle.previewSelectMarginRight)) {
            val lp = tvSelected.layoutParams
            if (lp is ConstraintLayout.LayoutParams) {
                lp.rightMargin = selectMainStyle.previewSelectMarginRight
            } else if (lp is RelativeLayout.LayoutParams) {
                lp.rightMargin = selectMainStyle.previewSelectMarginRight
            }
        }
        completeSelectView.setCompleteSelectViewStyle()
        completeSelectView.setSelectedChange(true)
        if (selectMainStyle.isCompleteSelectRelativeTop) {
            val lp = completeSelectView.layoutParams
            if (lp is ConstraintLayout.LayoutParams) {
                lp.topToTop = R.id.title_bar
                lp.bottomToBottom = R.id.title_bar
            }
        }

        if (selectMainStyle.isPreviewSelectRelativeBottom) {
            val tvSelectedLp = tvSelected.layoutParams
            if (tvSelectedLp is ConstraintLayout.LayoutParams) {
                tvSelectedLp.topToTop = R.id.bottom_nar_bar
                tvSelectedLp.bottomToBottom = R.id.bottom_nar_bar

                (tvSelectedWord.layoutParams as ConstraintLayout.LayoutParams).topToTop = R.id.bottom_nar_bar
                (tvSelectedWord.layoutParams as ConstraintLayout.LayoutParams).bottomToBottom = R.id.bottom_nar_bar

                (selectClickArea.layoutParams as ConstraintLayout.LayoutParams).topToTop = R.id.bottom_nar_bar
                (selectClickArea.layoutParams as ConstraintLayout.LayoutParams).bottomToBottom = R.id.bottom_nar_bar
            }
        }
        completeSelectView.setOnClickListener {
            val isComplete: Boolean
            if (selectMainStyle.isCompleteSelectRelativeTop && selectorConfig.selectCount == 0) {
                isComplete = confirmSelect(mData[viewPager.currentItem], false) == SelectedManager.ADD_SUCCESS
            } else {
                isComplete = selectorConfig.selectCount > 0
            }
            if (selectorConfig.isEmptyResultReturn && selectorConfig.selectCount == 0) {
                onExitPictureSelector()
            } else {
                if (isComplete) {
                    dispatchTransformResult()
                }
            }
        }
    }

    private fun initTitleBar() {
        if (selectorConfig.selectorStyle.titleBarStyle!!.isHideTitleBar) {
            titleBar.visibility = View.GONE
        }
        titleBar.setTitleBarStyle()
        titleBar.setOnTitleBarListener(object : TitleBar.OnTitleBarListener() {
            override fun onBackPressed() {
                if (isExternalPreview) {
                    if (selectorConfig.isPreviewZoomEffect) {
                        magicalView.backToMin()
                    } else {
                        handleExternalPreviewBack()
                    }
                } else {
                    if (!isInternalBottomPreview && selectorConfig.isPreviewZoomEffect) {
                        magicalView.backToMin()
                    } else {
                        onBackCurrentFragment()
                    }
                }
            }
        })
        titleBar.setTitle((curPosition + 1).toString() + "/" + totalNum)
        titleBar.getImageDelete().setOnClickListener {
            deletePreview()
        }

        selectClickArea.setOnClickListener {
            if (isExternalPreview) {
                deletePreview()
            } else {
                val currentMedia = mData[viewPager.currentItem]
                val selectResultCode = confirmSelect(currentMedia, tvSelected.isSelected)
                if (selectResultCode == SelectedManager.ADD_SUCCESS) {
                    val animListener = selectorConfig.onSelectAnimListener
                    if (animListener != null) {
                        animListener.onSelectAnim(tvSelected)
                    } else {
                        tvSelected.startAnimation(AnimationUtils.loadAnimation(context, R.anim.ps_anim_modal_in))
                    }
                }
            }
        }
        tvSelected.setOnClickListener {
            selectClickArea.performClick()
        }

        ivShare.setOnClickListener {
            try {
                val currentMedia = mData[viewPager.currentItem]
                val availablePath = currentMedia.availablePath
                val uri: Uri
                if (PictureMimeType.isContent(availablePath)) {
                    // Encrypted-at-rest attachment: the media is backed by a decrypting content uri
                    // and the plaintext realPath does not exist on disk — share the content uri
                    // directly so the receiver reads decrypted bytes (no FileProvider/ENOENT).
                    uri = Uri.parse(availablePath)
                } else {
                    val file = File(currentMedia.realPath)
                    uri = FileProvider.getUriForFile(requireContext(), requireActivity().applicationContext.packageName + ".provider", file)
                }
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = currentMedia.mimeType
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                // clipData carries the grant reliably across the chooser to the resolved target.
                intent.clipData = ClipData.newRawUri(currentMedia.fileName, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                requireActivity().startActivity(Intent.createChooser(intent, currentMedia.fileName))
            } catch (e: Exception) {
                L.w(e) { "Share: share File error" }
            }
        }
    }

    @Suppress("LongMethod")
    protected fun initPreviewSelectGallery(group: ViewGroup) {
        val selectMainStyle = selectorConfig.selectorStyle.selectMainStyle!!
        if (selectMainStyle.isPreviewDisplaySelectGallery) {
            mGalleryRecycle = RecyclerView(requireContext())
            val galleryRecycle = mGalleryRecycle!!
            if (StyleUtils.checkStyleValidity(selectMainStyle.adapterPreviewGalleryBackgroundResource)) {
                galleryRecycle.setBackgroundResource(selectMainStyle.adapterPreviewGalleryBackgroundResource)
            } else {
                galleryRecycle.setBackgroundResource(R.drawable.ps_preview_gallery_bg)
            }
            group.addView(galleryRecycle)

            val layoutParams = galleryRecycle.layoutParams
            if (layoutParams is ConstraintLayout.LayoutParams) {
                layoutParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
                layoutParams.bottomToTop = R.id.bottom_nar_bar
                layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                layoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            val layoutManager = object : WrapContentLinearLayoutManager(requireContext()) {
                override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
                    super.smoothScrollToPosition(recyclerView, state, position)
                    val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {
                        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                            return 300f / displayMetrics.densityDpi
                        }
                    }
                    smoothScroller.targetPosition = position
                    startSmoothScroll(smoothScroller)
                }
            }
            val itemAnimator = galleryRecycle.itemAnimator
            if (itemAnimator != null) {
                (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            }
            if (galleryRecycle.itemDecorationCount == 0) {
                galleryRecycle.addItemDecoration(HorizontalItemDecoration(Integer.MAX_VALUE, DensityUtil.dip2px(requireContext(), 6f)))
            }
            layoutManager.orientation = LinearLayoutManager.HORIZONTAL
            galleryRecycle.layoutManager = layoutManager
            if (selectorConfig.selectCount > 0) {
                galleryRecycle.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.ps_anim_layout_fall_enter)
            }
            mGalleryAdapter = PreviewGalleryAdapter(selectorConfig, isInternalBottomPreview)
            notifyGallerySelectMedia(mData[curPosition])
            galleryRecycle.adapter = mGalleryAdapter
            mGalleryAdapter!!.setItemClickListener(object : PreviewGalleryAdapter.OnItemClickListener {
                override fun onItemClick(position: Int, media: LocalMedia, v: View) {
                    if (position == RecyclerView.NO_POSITION) {
                        return
                    }
                    val albumName = if (TextUtils.isEmpty(selectorConfig.defaultAlbumName)) getString(R.string.ps_camera_roll) else selectorConfig.defaultAlbumName
                    if (isInternalBottomPreview || TextUtils.equals(currentAlbum, albumName) ||
                        TextUtils.equals(media.parentFolderName, currentAlbum)
                    ) {
                        val newPosition = if (isInternalBottomPreview) position else if (isShowCamera) media.position - 1 else media.position
                        if (newPosition == viewPager.currentItem && media.isChecked) {
                            return
                        }
                        val item = viewPageAdapter.getItem(newPosition)
                        if (item != null && (!TextUtils.equals(media.path, item.path) || media.id != item.id)) {
                            return
                        }
                        if (viewPager.adapter != null) {
                            // 这里清空一下重新设置，发现频繁调用setCurrentItem会出现页面闪现之前图片
                            viewPager.adapter = null
                            viewPager.adapter = viewPageAdapter
                        }
                        viewPager.setCurrentItem(newPosition, false)
                        notifyGallerySelectMedia(media)
                        viewPager.post {
                            if (selectorConfig.isPreviewZoomEffect) {
                                viewPageAdapter.setVideoPlayButtonUI(newPosition)
                            }
                        }
                    }
                }
            })
            if (selectorConfig.selectCount > 0) {
                galleryRecycle.visibility = View.VISIBLE
            } else {
                galleryRecycle.visibility = View.INVISIBLE
            }
            addAminViews(galleryRecycle)
            val mItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun isLongPressDragEnabled(): Boolean {
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    viewHolder.itemView.alpha = 0.7f
                    return makeMovementFlags(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)
                }

                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    try {
                        //得到item原来的position
                        val fromPosition = viewHolder.absoluteAdapterPosition
                        //得到目标position
                        val toPosition = target.absoluteAdapterPosition
                        val galleryData = mGalleryAdapter!!.getData() as MutableList<LocalMedia>
                        if (fromPosition < toPosition) {
                            for (i in fromPosition until toPosition) {
                                Collections.swap(galleryData, i, i + 1)
                                Collections.swap(selectorConfig.selectedResult, i, i + 1)
                                if (isInternalBottomPreview) {
                                    Collections.swap(mData, i, i + 1)
                                }
                            }
                        } else {
                            for (i in fromPosition downTo toPosition + 1) {
                                Collections.swap(galleryData, i, i - 1)
                                Collections.swap(selectorConfig.selectedResult, i, i - 1)
                                if (isInternalBottomPreview) {
                                    Collections.swap(mData, i, i - 1)
                                }
                            }
                        }
                        mGalleryAdapter!!.notifyItemMoved(fromPosition, toPosition)
                    } catch (e: Exception) {
                        L.w(e) { "[PictureSelectorPreviewFragment] onMove gallery item error:" }
                    }
                    return true
                }

                override fun onChildDraw(
                    c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                    dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
                ) {
                    if (needScaleBig) {
                        needScaleBig = false
                        val animatorSet = AnimatorSet()
                        animatorSet.playTogether(
                            ObjectAnimator.ofFloat(viewHolder.itemView, "scaleX", 1.0f, 1.1f),
                            ObjectAnimator.ofFloat(viewHolder.itemView, "scaleY", 1.0f, 1.1f)
                        )
                        animatorSet.duration = 50
                        animatorSet.interpolator = LinearInterpolator()
                        animatorSet.start()
                        animatorSet.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                needScaleSmall = true
                            }
                        })
                    }
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                }

                override fun getAnimationDuration(recyclerView: RecyclerView, animationType: Int, animateDx: Float, animateDy: Float): Long {
                    return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    viewHolder.itemView.alpha = 1.0f
                    if (needScaleSmall) {
                        needScaleSmall = false
                        val animatorSet = AnimatorSet()
                        animatorSet.playTogether(
                            ObjectAnimator.ofFloat(viewHolder.itemView, "scaleX", 1.1f, 1.0f),
                            ObjectAnimator.ofFloat(viewHolder.itemView, "scaleY", 1.1f, 1.0f)
                        )
                        animatorSet.interpolator = LinearInterpolator()
                        animatorSet.duration = 50
                        animatorSet.start()
                        animatorSet.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                needScaleBig = true
                            }
                        })
                    }
                    super.clearView(recyclerView, viewHolder)
                    mGalleryAdapter!!.notifyItemChanged(viewHolder.absoluteAdapterPosition)
                    if (isInternalBottomPreview) {
                        val position = mGalleryAdapter!!.getLastCheckPosition()
                        if (viewPager.currentItem != position && position != RecyclerView.NO_POSITION) {
                            if (viewPager.adapter != null) {
                                viewPager.adapter = null
                                viewPager.adapter = viewPageAdapter
                            }
                            viewPager.setCurrentItem(position, false)
                        }
                    }
                    if (selectorConfig.selectorStyle.selectMainStyle!!.isSelectNumberStyle) {
                        if (!ActivityCompatHelper.isDestroy(activity)) {
                            val fragments = requireActivity().supportFragmentManager.fragments
                            for (i in fragments.indices) {
                                val fragment = fragments[i]
                                if (fragment is PictureCommonFragment) {
                                    fragment.sendChangeSubSelectPositionEvent(true)
                                }
                            }
                        }
                    }
                }
            })
            mItemTouchHelper.attachToRecyclerView(galleryRecycle)
            mGalleryAdapter!!.setItemLongClickListener(object : PreviewGalleryAdapter.OnItemLongClickListener {
                override fun onItemLongClick(holder: RecyclerView.ViewHolder, position: Int, v: View) {
                    val vibrator = requireActivity().getSystemService(Service.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(50)
                    if (mGalleryAdapter!!.itemCount != selectorConfig.maxSelectNum) {
                        mItemTouchHelper.startDrag(holder)
                        return
                    }
                    if (holder.layoutPosition != mGalleryAdapter!!.itemCount - 1) {
                        mItemTouchHelper.startDrag(holder)
                    }
                }
            })
        }
    }

    /**
     * 刷新画廊数据选中状态
     */
    private fun notifyGallerySelectMedia(currentMedia: LocalMedia) {
        val galleryAdapter = mGalleryAdapter
        if (galleryAdapter != null && selectorConfig.selectorStyle.selectMainStyle!!.isPreviewDisplaySelectGallery) {
            galleryAdapter.isSelectMedia(currentMedia)
        }
    }

    /**
     * 刷新画廊数据
     */
    private fun notifyPreviewGalleryData(isAddRemove: Boolean, currentMedia: LocalMedia) {
        val galleryAdapter = mGalleryAdapter
        if (galleryAdapter != null && selectorConfig.selectorStyle.selectMainStyle!!.isPreviewDisplaySelectGallery) {
            if (mGalleryRecycle!!.visibility == View.INVISIBLE) {
                mGalleryRecycle!!.visibility = View.VISIBLE
            }
            if (isAddRemove) {
                if (selectorConfig.selectionMode == SelectModeConfig.SINGLE) {
                    galleryAdapter.clear()
                }
                galleryAdapter.addGalleryData(currentMedia)
                mGalleryRecycle!!.smoothScrollToPosition(galleryAdapter.itemCount - 1)
            } else {
                galleryAdapter.removeGalleryData(currentMedia)
                if (selectorConfig.selectCount == 0) {
                    mGalleryRecycle!!.visibility = View.INVISIBLE
                }
            }
        }
    }

    /**
     * 调用了startPreview预览逻辑
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun deletePreview() {
        if (isDisplayDelete) {
            val listener = selectorConfig.onExternalPreviewEventListener
            if (listener != null) {
                listener.onPreviewDelete(viewPager.currentItem)
                val currentItem = viewPager.currentItem
                mData.removeAt(currentItem)
                if (mData.size == 0) {
                    handleExternalPreviewBack()
                    return
                }
                titleBar.setTitle(getString(R.string.ps_preview_image_num, curPosition + 1, mData.size))
                totalNum = mData.size
                curPosition = currentItem
                if (viewPager.adapter != null) {
                    viewPager.adapter = null
                    viewPager.adapter = viewPageAdapter
                }
                viewPager.setCurrentItem(curPosition, false)
            }
        }
    }

    /**
     * 处理外部预览返回处理
     */
    private fun handleExternalPreviewBack() {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            if (selectorConfig.isPreviewFullScreenMode) {
                hideFullScreenStatusBar()
            }
            onExitPictureSelector()
        }
    }

    override fun onExitFragment() {
        if (selectorConfig.isPreviewFullScreenMode) {
            hideFullScreenStatusBar()
        }
    }

    private fun initBottomNavBar() {
        bottomNarBar.setBottomNavBarStyle()
        bottomNarBar.setSelectedChange()
        bottomNarBar.setOnBottomNavBarListener(object : BottomNavBar.OnBottomNavBarListener() {
            override fun onEditImage() {
            }

            override fun onCheckOriginalChange() {
                sendSelectedOriginalChangeEvent()
            }

            override fun onFirstCheckOriginalSelectedChange() {
                val currentItem = viewPager.currentItem
                if (mData.size > currentItem) {
                    val media = mData[currentItem]
                    confirmSelect(media, false)
                }
            }
        })
    }

    /**
     * 外部预览的样式
     */
    private fun externalPreviewStyle() {
        titleBar.getImageDelete().visibility = if (isDisplayDelete) View.VISIBLE else View.GONE
        tvSelected.visibility = View.GONE
        bottomNarBar.visibility = View.GONE
        completeSelectView.visibility = View.GONE
        ivShare.visibility = if (selectorConfig.isHidePreviewShare) View.GONE else View.VISIBLE

        if (selectorConfig.isShowConfidentialTip) {
            addConfidentialTipView()
        }

        // Default to fullscreen mode with titlebar hidden
        if (selectorConfig.isPreviewFullScreenMode) {
            initHiddenTitleBar()
        }
    }

    private fun addConfidentialTipView() {
        val root = view
        if (root !is ConstraintLayout) return
        val parent = root

        val tipView = com.difft.android.base.widget.ConfidentialTipView(requireContext())
        tipView.id = View.generateViewId()

        val bottomMargin = DensityUtil.dip2px(requireContext(), 80f)
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        )
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomMargin = bottomMargin
        parent.addView(tipView, params)
    }

    /**
     * Initialize titlebar as hidden for fullscreen preview mode
     */
    private fun initHiddenTitleBar() {
        titleBar.post {
            // Set initial state: titlebar hidden (translated up by its height)
            titleBar.translationY = -titleBar.height.toFloat()
            for (view in mAnimViews) {
                view.alpha = 0.0f
            }
            showFullScreenStatusBar()
        }
    }

    protected fun createAdapter(): PicturePreviewAdapter {
        return PicturePreviewAdapter(selectorConfig)
    }

    private fun initViewPagerData(data: ArrayList<LocalMedia>) {
        viewPageAdapter = createAdapter()
        viewPageAdapter.setData(data)
        viewPageAdapter.setOnPreviewEventListener(MyOnPreviewEventListener())
        viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        viewPager.adapter = viewPageAdapter
        selectorConfig.selectedPreviewResult.clear()
        if (data.size == 0 || curPosition >= data.size || curPosition < 0) {
            onKeyBackFragmentFinish()
            return
        }
        val media = data[curPosition]
        bottomNarBar.isDisplayEditor(
            PictureMimeType.isHasVideo(media.mimeType) || PictureMimeType.isHasAudio(media.mimeType)
        )
        tvSelected.isSelected = selectorConfig.selectedResult.contains(data[viewPager.currentItem])
        viewPager.registerOnPageChangeCallback(pageChangeCallback)
        viewPager.setPageTransformer(MarginPageTransformer(DensityUtil.dip2px(getAppContext(), 3f)))
        viewPager.setCurrentItem(curPosition, false)
        sendChangeSubSelectPositionEvent(false)
        notifySelectNumberStyle(data[curPosition])
        startZoomEffect(media)
    }

    /**
     * 启动预览缩放特效
     */
    protected fun startZoomEffect(media: LocalMedia) {
        if (isSaveInstanceState || isInternalBottomPreview) {
            return
        }
        if (selectorConfig.isPreviewZoomEffect) {
            viewPager.post {
                viewPageAdapter.setCoverScaleType(curPosition)
            }
            if (PictureMimeType.isHasVideo(media.mimeType)) {
                getVideoRealSizeFromMedia(media, !PictureMimeType.isHasHttp(media.availablePath), object : OnCallbackListener<IntArray> {
                    override fun onCall(data: IntArray?) {
                        start(data!!)
                    }
                })
            } else {
                getImageRealSizeFromMedia(media, !PictureMimeType.isHasHttp(media.availablePath), object : OnCallbackListener<IntArray> {
                    override fun onCall(data: IntArray?) {
                        start(data!!)
                    }
                })
            }
        }
    }

    /**
     * start magical
     */
    private fun start(size: IntArray) {
        magicalView.changeRealScreenHeight(size[0], size[1], false)
        val viewParams = BuildRecycleItemViewParams.getItemViewParams(if (isShowCamera) curPosition + 1 else curPosition)
        if (viewParams == null || (size[0] == 0 && size[1] == 0)) {
            viewPager.post {
                magicalView.startNormal(size[0], size[1], false)
            }
            magicalView.setBackgroundAlpha(1.0f)
            for (i in mAnimViews.indices) {
                mAnimViews[i].alpha = 1.0f
            }
        } else {
            magicalView.setViewParams(viewParams.left, viewParams.top, viewParams.width, viewParams.height, size[0], size[1])
            magicalView.start(false)
        }
        ObjectAnimator.ofFloat(viewPager, "alpha", 0.0f, 1.0f).setDuration(50).start()
    }

    /**
     * ViewPageAdapter回调事件处理
     */
    private inner class MyOnPreviewEventListener : BasePreviewHolder.OnPreviewEventListener {

        override fun onBackPressed() {
            if (selectorConfig.isPreviewFullScreenMode) {
                previewFullScreenMode()
            } else {
                if (isExternalPreview) {
                    if (selectorConfig.isPreviewZoomEffect) {
                        magicalView.backToMin()
                    } else {
                        handleExternalPreviewBack()
                    }
                } else {
                    if (!isInternalBottomPreview && selectorConfig.isPreviewZoomEffect) {
                        magicalView.backToMin()
                    } else {
                        onBackCurrentFragment()
                    }
                }
            }
        }

        override fun onPreviewVideoTitle(videoName: String?) {
            if (TextUtils.isEmpty(videoName)) {
                titleBar.setTitle((curPosition + 1).toString() + "/" + totalNum)
            } else {
                titleBar.setTitle(videoName)
            }
        }

        override fun onLongPressDownload(media: LocalMedia) {
            if (selectorConfig.isHidePreviewDownload) {
                return
            }
            if (isExternalPreview) {
                onExternalLongPressDownload(media)
            }
        }
    }

    /**
     * 回到初始位置
     */
    private fun onKeyDownBackToMin() {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            if (isExternalPreview) {
                if (selectorConfig.isPreviewZoomEffect) {
                    magicalView.backToMin()
                } else {
                    onExitPictureSelector()
                }
            } else if (isInternalBottomPreview) {
                onBackCurrentFragment()
            } else if (selectorConfig.isPreviewZoomEffect) {
                magicalView.backToMin()
            } else {
                onBackCurrentFragment()
            }
        }
    }

    /**
     * 预览全屏模式
     */
    private fun previewFullScreenMode() {
        if (isAnimationStart) {
            return
        }
        val isAnimInit = titleBar.translationY == 0.0f
        val set = AnimatorSet()
        val titleBarForm = if (isAnimInit) 0f else -titleBar.height.toFloat()
        val titleBarTo = if (isAnimInit) -titleBar.height.toFloat() else 0f
        val alphaForm = if (isAnimInit) 1.0f else 0.0f
        val alphaTo = if (isAnimInit) 0.0f else 1.0f
        for (i in mAnimViews.indices) {
            val view = mAnimViews[i]
            set.playTogether(ObjectAnimator.ofFloat(view, "alpha", alphaForm, alphaTo))
            if (view is TitleBar) {
                set.playTogether(ObjectAnimator.ofFloat(view, "translationY", titleBarForm, titleBarTo))
            }
        }
        set.duration = 350
        set.start()
        isAnimationStart = true
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimationStart = false
            }
        })

        if (isAnimInit) {
            showFullScreenStatusBar()
        } else {
            hideFullScreenStatusBar()
        }
    }

    /**
     * 全屏模式
     */
    private fun showFullScreenStatusBar() {
        for (i in mAnimViews.indices) {
            mAnimViews[i].isEnabled = false
        }
        bottomNarBar.getEditor().isEnabled = false
    }

    /**
     * 隐藏全屏模式
     */
    private fun hideFullScreenStatusBar() {
        for (i in mAnimViews.indices) {
            mAnimViews[i].isEnabled = true
        }
        bottomNarBar.getEditor().isEnabled = true
    }

    /**
     * 外部预览长按下载
     */
    private fun onExternalLongPressDownload(media: LocalMedia) {
        val listener = selectorConfig.onExternalPreviewEventListener ?: return
        if (!listener.onLongPressDownload(context, media)) {
            val content: String = if (PictureMimeType.isHasAudio(media.mimeType) || PictureMimeType.isUrlHasAudio(media.availablePath)) {
                getString(R.string.ps_prompt_audio_content)
            } else if (PictureMimeType.isHasVideo(media.mimeType) || PictureMimeType.isUrlHasVideo(media.availablePath)) {
                getString(R.string.ps_prompt_video_content)
            } else {
                getString(R.string.ps_prompt_image_content)
            }

            ComposeDialogManager.showMessageDialogForJava(
                requireActivity(),
                getString(R.string.ps_prompt),
                content,
                getString(R.string.ps_confirm),
                getString(R.string.ps_cancel),
                true,
                true,
                {
                    val path = media.availablePath
                    if (PictureMimeType.isHasHttp(path)) {
                        showLoading()
                    }
                    DownloadFileUtils.saveLocalFile(requireContext(), path, media.mimeType, object : OnCallbackListener<String> {
                        override fun onCall(data: String?) {
                            dismissLoading()
                            if (TextUtils.isEmpty(data)) {
                                val errorMsg: String = if (PictureMimeType.isHasAudio(media.mimeType)) {
                                    getString(R.string.ps_save_audio_error)
                                } else if (PictureMimeType.isHasVideo(media.mimeType)) {
                                    getString(R.string.ps_save_video_error)
                                } else {
                                    getString(R.string.ps_save_image_error)
                                }
                                ToastUtil.show(errorMsg)
                            } else {
                                PictureMediaScannerConnection(requireActivity(), data)
                                ToastUtil.show(getString(R.string.ps_save_success))
                            }
                        }
                    })
                    Unit
                },
                null,
                null
            )
        }
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            if (mData.size > position) {
                val currentMedia = if (positionOffsetPixels < screenWidth / 2) mData[position] else mData[position + 1]
                tvSelected.isSelected = isSelected(currentMedia)
                notifyGallerySelectMedia(currentMedia)
                notifySelectNumberStyle(currentMedia)
            }
        }

        override fun onPageSelected(position: Int) {
            curPosition = position
            titleBar.setTitle((curPosition + 1).toString() + "/" + totalNum)
            if (mData.size > position) {
                val currentMedia = mData[position]
                notifySelectNumberStyle(currentMedia)
                if (isHasMagicalEffect()) {
                    changeMagicalViewParams(position)
                }
                if (selectorConfig.isPreviewZoomEffect) {
                    if (isInternalBottomPreview && selectorConfig.isAutoVideoPlay) {
                        startAutoVideoPlay(position)
                    } else {
                        viewPageAdapter.setVideoPlayButtonUI(position)
                    }
                } else {
                    if (selectorConfig.isAutoVideoPlay) {
                        startAutoVideoPlay(position)
                    }
                }
                notifyGallerySelectMedia(currentMedia)
                bottomNarBar.isDisplayEditor(
                    PictureMimeType.isHasVideo(currentMedia.mimeType) || PictureMimeType.isHasAudio(currentMedia.mimeType)
                )
                if (!isExternalPreview && !isInternalBottomPreview && !selectorConfig.isOnlySandboxDir) {
                    if (selectorConfig.isPageStrategy) {
                        if (isHasMore) {
                            if (position == (viewPageAdapter.itemCount - 1) - PictureConfig.MIN_PAGE_SIZE ||
                                position == viewPageAdapter.itemCount - 1
                            ) {
                                loadMoreData()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 自动播放视频
     */
    private fun startAutoVideoPlay(position: Int) {
        viewPager.post {
            viewPageAdapter.startAutoVideoPlay(position)
        }
    }

    /**
     * 更新MagicalView ViewParams 参数
     */
    private fun changeMagicalViewParams(position: Int) {
        val media = mData[position]
        if (PictureMimeType.isHasVideo(media.mimeType)) {
            getVideoRealSizeFromMedia(media, false, object : OnCallbackListener<IntArray> {
                override fun onCall(data: IntArray?) {
                    setMagicalViewParams(data!![0], data[1], position)
                }
            })
        } else {
            getImageRealSizeFromMedia(media, false, object : OnCallbackListener<IntArray> {
                override fun onCall(data: IntArray?) {
                    setMagicalViewParams(data!![0], data[1], position)
                }
            })
        }
    }

    /**
     * setMagicalViewParams
     */
    private fun setMagicalViewParams(imageWidth: Int, imageHeight: Int, position: Int) {
        magicalView.changeRealScreenHeight(imageWidth, imageHeight, true)
        val viewParams = BuildRecycleItemViewParams.getItemViewParams(if (isShowCamera) position + 1 else position)
        if (viewParams == null || imageWidth == 0 || imageHeight == 0) {
            magicalView.setViewParams(0, 0, 0, 0, imageWidth, imageHeight)
        } else {
            magicalView.setViewParams(viewParams.left, viewParams.top, viewParams.width, viewParams.height, imageWidth, imageHeight)
        }
    }

    /**
     * 获取图片Media的真实大小
     */
    private fun getImageRealSizeFromMedia(media: LocalMedia, resize: Boolean, call: OnCallbackListener<IntArray>) {
        var realWidth: Int
        var realHeight: Int
        var isReturnNow = true
        if (MediaUtils.isLongImage(media.width, media.height)) {
            realWidth = screenWidth
            realHeight = screenHeight
        } else {
            realWidth = media.width
            realHeight = media.height
            if (resize) {
                if ((realWidth <= 0 || realHeight <= 0) || (realWidth > realHeight)) {
                    if (selectorConfig.isSyncWidthAndHeight) {
                        isReturnNow = false
                        // 先不展现内容，异步获取可能耗时会导致界面先出现图片而后在放大出现
                        viewPager.alpha = 0f
                        MediaUtils.getImageSize(requireContext(), media.availablePath, object : OnCallbackListener<MediaExtraInfo> {
                            override fun onCall(data: MediaExtraInfo?) {
                                if (data!!.width > 0) {
                                    media.width = data.width
                                }
                                if (data.height > 0) {
                                    media.height = data.height
                                }
                                call.onCall(intArrayOf(media.width, media.height))
                            }
                        })
                    }
                }
            }
        }
        if (media.isCut && media.cropImageWidth > 0 && media.cropImageHeight > 0) {
            realWidth = media.cropImageWidth
            realHeight = media.cropImageHeight
        }
        if (isReturnNow) {
            call.onCall(intArrayOf(realWidth, realHeight))
        }
    }

    /**
     * 获取视频Media的真实大小
     */
    private fun getVideoRealSizeFromMedia(media: LocalMedia, resize: Boolean, call: OnCallbackListener<IntArray>) {
        var isReturnNow = true
        if (resize) {
            if ((media.width <= 0 || media.height <= 0) || (media.width > media.height)) {
                if (selectorConfig.isSyncWidthAndHeight) {
                    isReturnNow = false
                    // 先不展现内容，异步获取可能耗时会导致界面先出现图片而后在放大出现
                    viewPager.alpha = 0f
                    MediaUtils.getVideoSize(requireContext(), media.availablePath, object : OnCallbackListener<MediaExtraInfo> {
                        override fun onCall(data: MediaExtraInfo?) {
                            if (data!!.width > 0) {
                                media.width = data.width
                            }
                            if (data.height > 0) {
                                media.height = data.height
                            }
                            call.onCall(intArrayOf(media.width, media.height))
                        }
                    })
                }
            }
        }
        if (isReturnNow) {
            call.onCall(intArrayOf(media.width, media.height))
        }
    }

    /**
     * 对选择数量进行编号排序
     */
    fun notifySelectNumberStyle(currentMedia: LocalMedia) {
        if (selectorConfig.selectorStyle.selectMainStyle!!.isPreviewSelectNumberStyle) {
            if (selectorConfig.selectorStyle.selectMainStyle!!.isSelectNumberStyle) {
                tvSelected.text = ""
                for (i in 0 until selectorConfig.selectCount) {
                    val media = selectorConfig.selectedResult[i]
                    if (TextUtils.equals(media.path, currentMedia.path) || media.id == currentMedia.id) {
                        currentMedia.num = media.num
                        media.position = currentMedia.position
                        tvSelected.text = ValueOf.toString(currentMedia.num)
                    }
                }
            }
        }
    }

    /**
     * 当前图片是否选中
     */
    protected fun isSelected(media: LocalMedia): Boolean {
        return selectorConfig.selectedResult.contains(media)
    }

    override fun onEditMedia(data: Intent) {
        if (mData.size > viewPager.currentItem) {
            val currentMedia = mData[viewPager.currentItem]
            val output = Crop.getOutput(data)
            currentMedia.cutPath = if (output != null) output.path else ""
            currentMedia.cropImageWidth = Crop.getOutputImageWidth(data)
            currentMedia.cropImageHeight = Crop.getOutputImageHeight(data)
            currentMedia.cropOffsetX = Crop.getOutputImageOffsetX(data)
            currentMedia.cropOffsetY = Crop.getOutputImageOffsetY(data)
            currentMedia.cropResultAspectRatio = Crop.getOutputCropAspectRatio(data)
            currentMedia.isCut = !TextUtils.isEmpty(currentMedia.cutPath)
            currentMedia.customData = Crop.getOutputCustomExtraData(data)
            currentMedia.isEditorImage = currentMedia.isCut
            currentMedia.sandboxPath = currentMedia.cutPath
            if (selectorConfig.selectedResult.contains(currentMedia)) {
                val exitsMedia = currentMedia.compareLocalMedia
                if (exitsMedia != null) {
                    exitsMedia.cutPath = currentMedia.cutPath
                    exitsMedia.isCut = currentMedia.isCut
                    exitsMedia.isEditorImage = currentMedia.isEditorImage
                    exitsMedia.customData = currentMedia.customData
                    exitsMedia.sandboxPath = currentMedia.cutPath
                    exitsMedia.cropImageWidth = Crop.getOutputImageWidth(data)
                    exitsMedia.cropImageHeight = Crop.getOutputImageHeight(data)
                    exitsMedia.cropOffsetX = Crop.getOutputImageOffsetX(data)
                    exitsMedia.cropOffsetY = Crop.getOutputImageOffsetY(data)
                    exitsMedia.cropResultAspectRatio = Crop.getOutputCropAspectRatio(data)
                }
                sendFixedSelectedChangeEvent(currentMedia)
            } else {
                confirmSelect(currentMedia, false)
            }
            viewPageAdapter.notifyItemChanged(viewPager.currentItem)
            notifyGallerySelectMedia(currentMedia)
        }
    }

    override fun onExitPictureSelector() {
        if (::viewPageAdapter.isInitialized) {
            viewPageAdapter.destroy()
        }
        super.onExitPictureSelector()
    }

    override fun onResume() {
        super.onResume()
        if (isPause) {
            resumePausePlay()
            isPause = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying()) {
            resumePausePlay()
            isPause = true
        }
    }

    private fun resumePausePlay() {
        if (::viewPageAdapter.isInitialized) {
            val holder = viewPageAdapter.getCurrentHolder(viewPager.currentItem)
            holder?.resumePausePlay()
        }
    }

    private fun isPlaying(): Boolean {
        return ::viewPageAdapter.isInitialized && viewPageAdapter.isPlaying(viewPager.currentItem)
    }

    override fun onDestroy() {
        if (::viewPageAdapter.isInitialized) {
            viewPageAdapter.destroy()
        }
        if (::viewPager.isInitialized) {
            viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        }
        super.onDestroy()
    }

    companion object {
        @JvmField
        val TAG: String = PictureSelectorPreviewFragment::class.java.simpleName

        @JvmStatic
        fun newInstance(): PictureSelectorPreviewFragment {
            val fragment = PictureSelectorPreviewFragment()
            fragment.arguments = Bundle()
            return fragment
        }
    }
}
