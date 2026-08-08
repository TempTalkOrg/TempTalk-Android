package com.difft.android.selector

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.text.TextUtils
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.difft.android.base.android.permission.MediaAccessState
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.adapter.PictureImageGridAdapter
import com.difft.android.selector.animators.AlphaInAnimationAdapter
import com.difft.android.selector.animators.AnimationType
import com.difft.android.selector.animators.SlideInBottomAnimationAdapter
import com.difft.android.selector.basic.FragmentInjectManager
import com.difft.android.selector.basic.IPictureSelectorEvent
import com.difft.android.selector.basic.PictureCommonFragment
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PermissionEvent
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.decoration.GridSpacingItemDecoration
import com.difft.android.selector.dialog.AlbumListPopWindow
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnAlbumItemClickListener
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener
import com.difft.android.selector.interfaces.OnRecyclerViewPreloadMoreListener
import com.difft.android.selector.interfaces.OnRecyclerViewScrollListener
import com.difft.android.selector.interfaces.OnRecyclerViewScrollStateListener
import com.difft.android.selector.interfaces.OnRequestPermissionListener
import com.difft.android.selector.loader.IBridgeMediaLoader
import com.difft.android.selector.loader.LocalMediaLoader
import com.difft.android.selector.loader.LocalMediaPageLoader
import com.difft.android.selector.magical.BuildRecycleItemViewParams
import com.difft.android.selector.manager.SelectedManager
import com.difft.android.selector.permissions.PermissionChecker
import com.difft.android.selector.permissions.PermissionConfig
import com.difft.android.selector.permissions.PermissionResultCallback
import com.difft.android.selector.permissions.PermissionUtil
import com.difft.android.selector.style.SelectMainStyle
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.AnimUtils
import com.difft.android.selector.utils.DateUtils
import com.difft.android.selector.utils.DensityUtil
import com.difft.android.selector.utils.DoubleUtils
import com.difft.android.selector.utils.StyleUtils
import com.difft.android.selector.utils.ToastUtils
import com.difft.android.selector.utils.ValueOf
import com.difft.android.selector.widget.BottomNavBar
import com.difft.android.selector.widget.RecyclerPreloadView
import com.difft.android.selector.widget.SlideSelectTouchListener
import com.difft.android.selector.widget.SlideSelectionHandler
import com.difft.android.selector.widget.TitleBar
import java.io.File

/**
 * 1:1 Java→Kotlin port (issue #1077); structural split deferred — see #1077 future work.
 */
@Suppress("LargeClass")
class PictureSelectorFragment : PictureCommonFragment(),
    OnRecyclerViewPreloadMoreListener, IPictureSelectorEvent {

    private lateinit var mRecycler: RecyclerPreloadView
    private lateinit var tvDataEmpty: TextView
    private lateinit var titleBar: TitleBar
    private lateinit var bottomNarBar: BottomNavBar
    private lateinit var completeSelectView: com.difft.android.selector.widget.CompleteSelectView
    private lateinit var tvCurrentDataTime: TextView

    /**
     * Android 14+ "Select photos" partial-access hint bar (shown only when
     * media access is PARTIAL). Its own settings request code is kept distinct
     * from PermissionChecker.REQUEST_CODE (10086) so returning from Settings
     * routes through onActivityResult, not the graceful-close settings path.
     */
    private var partialAccessBar: View? = null
    private var partialAccessManage: TextView? = null

    private var intervalClickTime = 0L
    private var allFolderSize = 0
    private var currentPosition = -1

    /**
     * Use camera to callback
     */
    private var isCameraCallback = false

    /**
     * memory recycling
     */
    private var isMemoryRecycling = false
    private var isDisplayCamera = false

    private lateinit var mAdapter: PictureImageGridAdapter

    private lateinit var albumListPopWindow: AlbumListPopWindow

    private var mDragSelectTouchListener: SlideSelectTouchListener? = null

    override fun getFragmentTag(): String = TAG

    override fun getResourceId(): Int {
        val layoutResourceId = InjectResourceSource.getLayoutResource(
            context, InjectResourceSource.MAIN_SELECTOR_LAYOUT_RESOURCE, selectorConfig
        )
        if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) {
            return layoutResourceId
        }
        return R.layout.ps_fragment_selector
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onSelectedChange(isAddRemove: Boolean, currentMedia: LocalMedia) {
        bottomNarBar.setSelectedChange()
        completeSelectView.setSelectedChange(false)
        // 刷新列表数据
        if (checkNotifyStrategy(isAddRemove)) {
            mAdapter.notifyItemPositionChanged(currentMedia.position)
            mRecycler.postDelayed({ mAdapter.notifyDataSetChanged() }, SELECT_ANIM_DURATION.toLong())
        } else {
            mAdapter.notifyItemPositionChanged(currentMedia.position)
        }
        if (!isAddRemove) {
            sendChangeSubSelectPositionEvent(true)
        }
    }

    override fun onFixedSelectedChange(oldLocalMedia: LocalMedia) {
        mAdapter.notifyItemPositionChanged(oldLocalMedia.position)
    }

    override fun sendChangeSubSelectPositionEvent(adapterChange: Boolean) {
        if (selectorConfig.selectorStyle.selectMainStyle!!.isSelectNumberStyle) {
            for (index in 0 until selectorConfig.selectCount) {
                val media = selectorConfig.selectedResult[index]
                media.num = index + 1
                if (adapterChange) {
                    mAdapter.notifyItemPositionChanged(media.position)
                }
            }
        }
    }

    override fun onCheckOriginalChange() {
        bottomNarBar.setOriginalCheck()
    }

    /**
     * 刷新列表策略
     */
    private fun checkNotifyStrategy(isAddRemove: Boolean): Boolean {
        var isNotifyAll = false
        if (selectorConfig.isMaxSelectEnabledMask) {
            if (selectorConfig.isWithVideoImage) {
                if (selectorConfig.selectionMode == SelectModeConfig.SINGLE) {
                    // ignore
                } else {
                    isNotifyAll = selectorConfig.selectCount == selectorConfig.maxSelectNum ||
                        (!isAddRemove && selectorConfig.selectCount == selectorConfig.maxSelectNum - 1)
                }
            } else {
                if (selectorConfig.selectCount == 0 || (isAddRemove && selectorConfig.selectCount == 1)) {
                    // 首次添加或单选，选择数量变为0了，都notifyDataSetChanged
                    isNotifyAll = true
                } else {
                    if (PictureMimeType.isHasVideo(selectorConfig.resultFirstMimeType)) {
                        val maxSelectNum = if (selectorConfig.maxVideoSelectNum > 0)
                            selectorConfig.maxVideoSelectNum else selectorConfig.maxSelectNum
                        isNotifyAll = selectorConfig.selectCount == maxSelectNum ||
                            (!isAddRemove && selectorConfig.selectCount == maxSelectNum - 1)
                    } else {
                        isNotifyAll = selectorConfig.selectCount == selectorConfig.maxSelectNum ||
                            (!isAddRemove && selectorConfig.selectCount == selectorConfig.maxSelectNum - 1)
                    }
                }
            }
        }
        return isNotifyAll
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(PictureConfig.EXTRA_ALL_FOLDER_SIZE, allFolderSize)
        outState.putInt(PictureConfig.EXTRA_CURRENT_PAGE, mPage)
        if (::mRecycler.isInitialized) {
            outState.putInt(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, mRecycler.getLastVisiblePosition())
        }
        if (::mAdapter.isInitialized) {
            outState.putBoolean(PictureConfig.EXTRA_DISPLAY_CAMERA, mAdapter.isDisplayCamera())
            selectorConfig.addDataSource(mAdapter.getData())
        }
        if (::albumListPopWindow.isInitialized) {
            selectorConfig.addAlbumDataSource(albumListPopWindow.getAlbumList())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reStartSavedInstance(savedInstanceState)
        isMemoryRecycling = savedInstanceState != null
        tvDataEmpty = view.findViewById(R.id.tv_data_empty)
        completeSelectView = view.findViewById(R.id.ps_complete_select)
        titleBar = view.findViewById(R.id.title_bar)
        bottomNarBar = view.findViewById(R.id.bottom_nar_bar)
        tvCurrentDataTime = view.findViewById(R.id.tv_current_data_time)
        partialAccessBar = view.findViewById(R.id.ps_partial_access_bar)
        partialAccessManage = view.findViewById(R.id.ps_partial_access_manage)
        // Guard against custom onLayoutResourceListener layouts that omit this view (mirrors the
        // partialAccessBar null-guard in updatePartialAccessBar).
        partialAccessManage?.setOnClickListener { v -> showPartialAccessMenu(v) }
        updatePartialAccessBar()
        onCreateLoader()
        initAlbumListPopWindow()
        initTitleBar()
        initComplete()
        initRecycler(view)
        initBottomNavBar()
        if (isMemoryRecycling) {
            recoverSaveInstanceData()
        } else {
            requestLoadData()
        }
    }

    override fun onFragmentResume() {
        setRootViewKeyListener(requireView())
    }

    override fun reStartSavedInstance(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            allFolderSize = savedInstanceState.getInt(PictureConfig.EXTRA_ALL_FOLDER_SIZE)
            mPage = savedInstanceState.getInt(PictureConfig.EXTRA_CURRENT_PAGE, mPage)
            currentPosition = savedInstanceState.getInt(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, currentPosition)
            isDisplayCamera = savedInstanceState.getBoolean(PictureConfig.EXTRA_DISPLAY_CAMERA, selectorConfig.isDisplayCamera)
        } else {
            isDisplayCamera = selectorConfig.isDisplayCamera
        }
    }

    /**
     * 完成按钮
     */
    private fun initComplete() {
        if (selectorConfig.selectionMode == SelectModeConfig.SINGLE && selectorConfig.isDirectReturnSingle) {
            selectorConfig.selectorStyle.titleBarStyle!!.isHideCancelButton = false
            titleBar.getTitleCancelView().visibility = View.VISIBLE
            completeSelectView.visibility = View.GONE
        } else {
            completeSelectView.setCompleteSelectViewStyle()
            completeSelectView.setSelectedChange(false)
            val selectMainStyle = selectorConfig.selectorStyle.selectMainStyle!!
            if (selectMainStyle.isCompleteSelectRelativeTop) {
                val lp = completeSelectView.layoutParams
                if (lp is ConstraintLayout.LayoutParams) {
                    lp.topToTop = R.id.title_bar
                    lp.bottomToBottom = R.id.title_bar
                    if (selectorConfig.isPreviewFullScreenMode) {
                        lp.topMargin = DensityUtil.getStatusBarHeight(requireContext())
                    }
                } else if (lp is RelativeLayout.LayoutParams) {
                    if (selectorConfig.isPreviewFullScreenMode) {
                        lp.topMargin = DensityUtil.getStatusBarHeight(requireContext())
                    }
                }
            }
            completeSelectView.setOnClickListener {
                if (selectorConfig.isEmptyResultReturn && selectorConfig.selectCount == 0) {
                    onExitPictureSelector()
                } else {
                    dispatchTransformResult()
                }
            }
        }
    }

    override fun onCreateLoader() {
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

    private fun initTitleBar() {
        if (selectorConfig.selectorStyle.titleBarStyle!!.isHideTitleBar) {
            titleBar.visibility = View.GONE
        }
        titleBar.setTitleBarStyle()
        titleBar.setOnTitleBarListener(object : TitleBar.OnTitleBarListener() {
            override fun onTitleDoubleClick() {
                if (selectorConfig.isAutomaticTitleRecyclerTop) {
                    val intervalTime = 500
                    if (SystemClock.uptimeMillis() - intervalClickTime < intervalTime && mAdapter.getItemCount() > 0) {
                        mRecycler.scrollToPosition(0)
                    } else {
                        intervalClickTime = SystemClock.uptimeMillis()
                    }
                }
            }

            override fun onBackPressed() {
                if (albumListPopWindow.isShowing) {
                    albumListPopWindow.dismiss()
                } else {
                    onKeyBackFragmentFinish()
                }
            }

            override fun onShowAlbumPopWindow(anchor: View) {
                albumListPopWindow.showAsDropDown(anchor)
            }
        })
    }

    /**
     * initAlbumListPopWindow
     */
    private fun initAlbumListPopWindow() {
        albumListPopWindow = AlbumListPopWindow.buildPopWindow(requireContext(), selectorConfig)
        albumListPopWindow.setOnPopupWindowStatusListener(object : AlbumListPopWindow.OnPopupWindowStatusListener {
            override fun onShowPopupWindow() {
                if (!selectorConfig.isOnlySandboxDir) {
                    AnimUtils.rotateArrow(titleBar.getImageArrow(), true)
                }
            }

            override fun onDismissPopupWindow() {
                if (!selectorConfig.isOnlySandboxDir) {
                    AnimUtils.rotateArrow(titleBar.getImageArrow(), false)
                }
            }
        })
        addAlbumPopWindowAction()
    }

    private fun recoverSaveInstanceData() {
        mAdapter.setDisplayCamera(isDisplayCamera)
        setEnterAnimationDuration(0L)
        if (selectorConfig.isOnlySandboxDir) {
            handleInAppDirAllMedia(selectorConfig.currentLocalMediaFolder)
        } else {
            handleRecoverAlbumData(ArrayList(selectorConfig.albumDataSource))
        }
    }

    private fun handleRecoverAlbumData(albumData: List<LocalMediaFolder>) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        if (albumData.isNotEmpty()) {
            val firstFolder: LocalMediaFolder
            if (selectorConfig.currentLocalMediaFolder != null) {
                firstFolder = selectorConfig.currentLocalMediaFolder!!
            } else {
                firstFolder = albumData[0]
                selectorConfig.currentLocalMediaFolder = firstFolder
            }
            titleBar.setTitle(firstFolder.folderName)
            albumListPopWindow.bindAlbumData(albumData)
            if (selectorConfig.isPageStrategy) {
                handleFirstPageMedia(ArrayList(selectorConfig.dataSource), true)
            } else {
                setAdapterData(firstFolder.data)
            }
        } else {
            showDataNull()
        }
    }

    private fun requestLoadData() {
        mAdapter.setDisplayCamera(isDisplayCamera)
        if (PermissionChecker.isCheckReadStorage(selectorConfig.chooseMode, requireContext())) {
            beginLoadData()
        } else {
            val readPermissionArray = PermissionConfig.getReadPermissionArray(getAppContext(), selectorConfig.chooseMode)
            onPermissionExplainEvent(true, readPermissionArray)
            if (selectorConfig.onPermissionsEventListener != null) {
                onApplyPermissionsEvent(PermissionEvent.EVENT_SOURCE_DATA, readPermissionArray)
            } else {
                PermissionChecker.getInstance().requestPermissions(this, readPermissionArray, object : PermissionResultCallback {
                    override fun onGranted() {
                        beginLoadData()
                    }

                    override fun onDenied() {
                        handlePermissionDenied(readPermissionArray)
                    }
                })
            }
        }
    }

    override fun onApplyPermissionsEvent(event: Int, permissionArray: Array<String>) {
        if (event != PermissionEvent.EVENT_SOURCE_DATA) {
            super.onApplyPermissionsEvent(event, permissionArray)
        } else {
            selectorConfig.onPermissionsEventListener!!.requestPermission(this, permissionArray, object : OnRequestPermissionListener {
                override fun onCall(permissionArray: Array<String>, isResult: Boolean) {
                    if (isResult) {
                        beginLoadData()
                    } else {
                        handlePermissionDenied(permissionArray)
                    }
                }
            })
        }
    }

    /**
     * 开始获取数据
     */
    private fun beginLoadData() {
        onPermissionExplainEvent(false, emptyArray())
        if (selectorConfig.isOnlySandboxDir) {
            loadOnlyInAppDirectoryAllMediaData()
        } else {
            loadAllAlbumData()
        }
    }

    override fun handlePermissionSettingResult(permissions: Array<String>) {
        onPermissionExplainEvent(false, emptyArray())
        val isHasCamera = permissions.isNotEmpty() && TextUtils.equals(permissions[0], PermissionConfig.CAMERA[0])
        val isHasPermissions: Boolean
        val listener = selectorConfig.onPermissionsEventListener
        isHasPermissions = if (listener != null) {
            listener.hasPermissions(this, permissions)
        } else {
            PermissionChecker.isCheckSelfPermission(requireContext(), permissions)
        }
        if (isHasPermissions) {
            if (isHasCamera) {
                openSelectedCamera()
            } else {
                beginLoadData()
            }
        } else {
            if (isHasCamera) {
                ToastUtils.showToast(requireContext(), getString(R.string.ps_camera))
            } else {
                ToastUtils.showToast(requireContext(), getString(R.string.ps_jurisdiction))
                onKeyBackFragmentFinish()
            }
        }
        PermissionConfig.CURRENT_REQUEST_PERMISSION = arrayOf()
    }

    /**
     * Toggle the Android 14+ partial-access hint bar. Single source of truth is
     * :base getMediaAccessState()==PARTIAL (Signal canOnlyReadSelected) — NOT the
     * selector isPartialVisualAccessGranted, which also returns true under FULL and
     * would wrongly show the bar with full access.
     */
    private fun updatePartialAccessBar() {
        val bar = partialAccessBar ?: return
        val partial = currentMediaAccessState(context) == MediaAccessState.PARTIAL
        bar.visibility = if (partial) View.VISIBLE else View.GONE
    }

    /**
     * Shorthand for the :base three-state predicate. Kept fully-qualified here
     * (not imported) because its simple name collides with the selector's own
     * [PermissionUtil], already imported for [PermissionUtil.goIntentSetting].
     * Uses [getAppContext] instead of the passed-in context — callers may
     * invoke this after fragment teardown (e.g. returning from system Settings),
     * where [getContext] can be null and crash the non-null Kotlin param.
     */
    private fun currentMediaAccessState(context: Context?): MediaAccessState {
        return com.difft.android.base.android.permission.PermissionUtil.getMediaAccessState(getAppContext())
    }

    /**
     * "Manage" menu (aligns with Signal ManageContextMenu two entries). Uses the
     * host Activity context so the popup inherits the app DayNight theme.
     */
    private fun showPartialAccessMenu(anchor: View) {
        val popupMenu = PopupMenu(requireActivity(), anchor)
        popupMenu.menu.add(0, 1, 0, getString(R.string.ps_partial_access_select_more))
        popupMenu.menu.add(0, 2, 1, getString(R.string.ps_partial_access_settings))
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    requestSelectMore()
                    true
                }
                2 -> {
                    openPartialAccessSettings()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }

    /**
     * "Select more photos": reuse the selector's own re-selection mechanism with a
     * custom callback. Both branches reload — after re-selecting, the user may still
     * be in "Selected photos" (onDenied) but the selection set changed, so a reload
     * is required either way. Never route onDenied to handlePermissionDenied (which
     * toasts and closes the gallery).
     */
    private fun requestSelectMore() {
        L.i { "[MediaAccess] user tapped select-more (partial)" }
        val readPermissionArray = PermissionConfig.getReadPermissionArray(getAppContext(), selectorConfig.chooseMode)
        PermissionChecker.getInstance().requestPermissions(this, readPermissionArray, object : PermissionResultCallback {
            override fun onGranted() {
                reloadAfterAccessChange()
            }

            override fun onDenied() {
                reloadAfterAccessChange()
            }
        })
    }

    /**
     * "Go to Settings": use a dedicated request code so onActivityResult below only
     * reloads (never closes) — the base REQUEST_GO_SETTING path would close the
     * gallery when access is still partial (a legal usable state).
     */
    private fun openPartialAccessSettings() {
        L.i { "[MediaAccess] user tapped go-to-settings (partial)" }
        PermissionUtil.goIntentSetting(this, REQUEST_PARTIAL_SETTINGS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PARTIAL_SETTINGS) {
            reloadAfterAccessChange()
        }
    }

    /**
     * Unified reload after the user changed access via re-selection or Settings.
     * Three branches: NONE (revoked in Settings) → mirror handlePermissionSettingResult
     * graceful degradation (toast + close) instead of leaving a stale unreadable
     * gallery; FULL/PARTIAL → fresh reload and refresh the bar (hidden when FULL).
     */
    private fun reloadAfterAccessChange() {
        val state = currentMediaAccessState(context)
        if (state == MediaAccessState.NONE) {
            // Fragment may be detached when returning from Settings; use the
            // null-safe app context for both string lookup and the toast.
            val appContext = getAppContext()
            ToastUtils.showToast(appContext, appContext.getString(R.string.ps_jurisdiction))
            onKeyBackFragmentFinish()
            updatePartialAccessBar()
            return
        }
        beginLoadData()
        updatePartialAccessBar()
    }

    /**
     * 给AlbumListPopWindow添加事件
     */
    private fun addAlbumPopWindowAction() {
        albumListPopWindow.setOnIBridgeAlbumWidget(object : OnAlbumItemClickListener {
            override fun onItemClick(position: Int, curFolder: LocalMediaFolder) {
                isDisplayCamera = selectorConfig.isDisplayCamera && curFolder.bucketId == PictureConfig.ALL.toLong()
                mAdapter.setDisplayCamera(isDisplayCamera)
                titleBar.setTitle(curFolder.folderName)
                val lastFolder = selectorConfig.currentLocalMediaFolder!!
                val lastBucketId = lastFolder.bucketId
                if (selectorConfig.isPageStrategy) {
                    if (curFolder.bucketId != lastBucketId) {
                        // 1、记录一下上一次相册数据加载到哪了，到时候切回来的时候要续上
                        lastFolder.data = mAdapter.getData()
                        lastFolder.currentDataPage = mPage
                        lastFolder.isHasMore = mRecycler.isEnabledLoadMore

                        // 2、判断当前相册是否请求过，如果请求过则不从MediaStore去拉取了
                        if (curFolder.data!!.size > 0 && !curFolder.isHasMore) {
                            setAdapterData(curFolder.data)
                            mPage = curFolder.currentDataPage
                            mRecycler.isEnabledLoadMore = curFolder.isHasMore
                            mRecycler.smoothScrollToPosition(0)
                        } else {
                            // 3、从MediaStore拉取数据
                            mPage = 1
                            val engine = selectorConfig.loaderDataEngine
                            if (engine != null) {
                                engine.loadFirstPageMediaData(
                                    requireContext(), curFolder.bucketId, mPage, selectorConfig.pageSize,
                                    object : OnQueryDataResultListener<LocalMedia>() {
                                        override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                                            handleSwitchAlbum(result, isHasMore)
                                        }
                                    })
                            } else {
                                mLoader.loadPageMediaData(curFolder.bucketId, mPage, selectorConfig.pageSize,
                                    object : OnQueryDataResultListener<LocalMedia>() {
                                        override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                                            handleSwitchAlbum(result, isHasMore)
                                        }
                                    })
                            }
                        }
                    }
                } else {
                    // 非分页模式直接导入该相册下的所有资源
                    if (curFolder.bucketId != lastBucketId) {
                        setAdapterData(curFolder.data)
                        mRecycler.smoothScrollToPosition(0)
                    }
                }
                selectorConfig.currentLocalMediaFolder = curFolder
                albumListPopWindow.dismiss()
                val dragListener = mDragSelectTouchListener
                if (dragListener != null && selectorConfig.isFastSlidingSelect) {
                    dragListener.setRecyclerViewHeaderCount(if (mAdapter.isDisplayCamera()) 1 else 0)
                }
            }
        })
    }

    private fun handleSwitchAlbum(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        mRecycler.isEnabledLoadMore = isHasMore
        if (result.size == 0) {
            // 如果从MediaStore拉取都没有数据了，adapter里的可能是缓存所以也清除
            mAdapter.getData().clear()
        }
        setAdapterData(result)
        mRecycler.onScrolled(0, 0)
        mRecycler.smoothScrollToPosition(0)
    }

    private fun initBottomNavBar() {
        bottomNarBar.setBottomNavBarStyle()
        bottomNarBar.setOnBottomNavBarListener(object : BottomNavBar.OnBottomNavBarListener() {
            override fun onPreview() {
                onStartPreview(0, true)
            }

            override fun onCheckOriginalChange() {
                sendSelectedOriginalChangeEvent()
            }
        })
        bottomNarBar.setSelectedChange()
    }

    override fun loadAllAlbumData() {
        val engine = selectorConfig.loaderDataEngine
        if (engine != null) {
            engine.loadAllAlbumData(requireContext(), object : OnQueryAllAlbumListener<LocalMediaFolder> {
                override fun onComplete(result: List<LocalMediaFolder>) {
                    handleAllAlbumData(false, result)
                }
            })
        } else {
            val isPreload = preloadPageFirstData()
            mLoader.loadAllAlbum(object : OnQueryAllAlbumListener<LocalMediaFolder> {
                override fun onComplete(result: List<LocalMediaFolder>) {
                    handleAllAlbumData(isPreload, result)
                }
            })
        }
    }

    private fun preloadPageFirstData(): Boolean {
        var isPreload = false
        if (selectorConfig.isPageStrategy && selectorConfig.isPreloadFirst) {
            val firstFolder = LocalMediaFolder()
            firstFolder.bucketId = PictureConfig.ALL.toLong()
            if (TextUtils.isEmpty(selectorConfig.defaultAlbumName)) {
                titleBar.setTitle(
                    if (selectorConfig.chooseMode == SelectMimeType.ofAudio())
                        requireContext().getString(R.string.ps_all_audio)
                    else
                        requireContext().getString(R.string.ps_camera_roll)
                )
            } else {
                titleBar.setTitle(selectorConfig.defaultAlbumName)
            }
            firstFolder.folderName = titleBar.getTitleText()
            selectorConfig.currentLocalMediaFolder = firstFolder
            loadFirstPageMediaData(firstFolder.bucketId)
            isPreload = true
        }
        return isPreload
    }

    private fun handleAllAlbumData(isPreload: Boolean, result: List<LocalMediaFolder>) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        if (result.isNotEmpty()) {
            val firstFolder: LocalMediaFolder
            if (isPreload) {
                firstFolder = result[0]
                selectorConfig.currentLocalMediaFolder = firstFolder
            } else {
                if (selectorConfig.currentLocalMediaFolder != null) {
                    firstFolder = selectorConfig.currentLocalMediaFolder!!
                } else {
                    firstFolder = result[0]
                    selectorConfig.currentLocalMediaFolder = firstFolder
                }
            }
            titleBar.setTitle(firstFolder.folderName)
            albumListPopWindow.bindAlbumData(result)
            if (selectorConfig.isPageStrategy) {
                if (selectorConfig.isPreloadFirst) {
                    mRecycler.isEnabledLoadMore = true
                } else {
                    loadFirstPageMediaData(firstFolder.bucketId)
                }
            } else {
                setAdapterData(firstFolder.data)
            }
        } else {
            showDataNull()
        }
    }

    override fun loadFirstPageMediaData(firstBucketId: Long) {
        mPage = 1
        mRecycler.isEnabledLoadMore = true
        val engine = selectorConfig.loaderDataEngine
        if (engine != null) {
            engine.loadFirstPageMediaData(
                requireContext(), firstBucketId, mPage, mPage * selectorConfig.pageSize,
                object : OnQueryDataResultListener<LocalMedia>() {
                    override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                        handleFirstPageMedia(result, isHasMore)
                    }
                })
        } else {
            mLoader.loadPageMediaData(firstBucketId, mPage, mPage * selectorConfig.pageSize,
                object : OnQueryDataResultListener<LocalMedia>() {
                    override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                        handleFirstPageMedia(result, isHasMore)
                    }
                })
        }
    }

    private fun handleFirstPageMedia(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        mRecycler.isEnabledLoadMore = isHasMore
        if (mRecycler.isEnabledLoadMore && result.size == 0) {
            // 如果isHasMore为true但result.size = 0;
            // 那么有可能是开启了某些条件过滤，实际上是还有更多资源的再强制请求
            onRecyclerViewPreloadMore()
        } else {
            setAdapterData(result)
        }
    }

    override fun loadOnlyInAppDirectoryAllMediaData() {
        val engine = selectorConfig.loaderDataEngine
        if (engine != null) {
            engine.loadOnlyInAppDirAllMediaData(requireContext(), object : OnQueryAlbumListener<LocalMediaFolder> {
                override fun onComplete(result: LocalMediaFolder) {
                    handleInAppDirAllMedia(result)
                }
            })
        } else {
            mLoader.loadOnlyInAppDirAllMedia(object : OnQueryAlbumListener<LocalMediaFolder?> {
                override fun onComplete(result: LocalMediaFolder?) {
                    handleInAppDirAllMedia(result)
                }
            })
        }
    }

    private fun handleInAppDirAllMedia(folder: LocalMediaFolder?) {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            val sandboxDir = selectorConfig.sandboxDir
            val isNonNull = folder != null
            val folderName = if (isNonNull) folder!!.folderName else File(sandboxDir).name
            titleBar.setTitle(folderName)
            if (isNonNull) {
                selectorConfig.currentLocalMediaFolder = folder
                setAdapterData(folder!!.data)
            } else {
                showDataNull()
            }
        }
    }

    /**
     * 内存不足时，恢复RecyclerView定位位置
     */
    private fun recoveryRecyclerPosition() {
        if (currentPosition > 0) {
            mRecycler.post {
                mRecycler.scrollToPosition(currentPosition)
                mRecycler.setLastVisiblePosition(currentPosition)
            }
        }
    }

    private fun initRecycler(view: View) {
        mRecycler = view.findViewById(R.id.recycler)
        val selectorStyle = selectorConfig.selectorStyle
        val selectMainStyle = selectorStyle.selectMainStyle!!
        val listBackgroundColor = selectMainStyle.mainListBackgroundColor
        if (StyleUtils.checkStyleValidity(listBackgroundColor)) {
            mRecycler.setBackgroundColor(listBackgroundColor)
        } else {
            mRecycler.setBackgroundColor(ContextCompat.getColor(getAppContext(), R.color.ps_color_black))
        }
        val imageSpanCount = if (selectorConfig.imageSpanCount <= 0) PictureConfig.DEFAULT_SPAN_COUNT else selectorConfig.imageSpanCount
        if (mRecycler.itemDecorationCount == 0) {
            if (StyleUtils.checkSizeValidity(selectMainStyle.adapterItemSpacingSize)) {
                mRecycler.addItemDecoration(
                    GridSpacingItemDecoration(
                        imageSpanCount,
                        selectMainStyle.adapterItemSpacingSize, selectMainStyle.isAdapterItemIncludeEdge
                    )
                )
            } else {
                mRecycler.addItemDecoration(
                    GridSpacingItemDecoration(
                        imageSpanCount,
                        DensityUtil.dip2px(view.context, 1f), selectMainStyle.isAdapterItemIncludeEdge
                    )
                )
            }
        }
        mRecycler.layoutManager = GridLayoutManager(requireContext(), imageSpanCount)
        val itemAnimator = mRecycler.itemAnimator
        if (itemAnimator != null) {
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            mRecycler.itemAnimator = null
        }
        if (selectorConfig.isPageStrategy) {
            mRecycler.setReachBottomRow(RecyclerPreloadView.BOTTOM_PRELOAD)
            mRecycler.setOnRecyclerViewPreloadListener(this)
        } else {
            mRecycler.setHasFixedSize(true)
        }
        mAdapter = PictureImageGridAdapter(requireContext(), selectorConfig)
        mAdapter.setDisplayCamera(isDisplayCamera)
        when (selectorConfig.animationMode) {
            AnimationType.ALPHA_IN_ANIMATION -> mRecycler.adapter = AlphaInAnimationAdapter(mAdapter)
            AnimationType.SLIDE_IN_BOTTOM_ANIMATION -> mRecycler.adapter = SlideInBottomAnimationAdapter(mAdapter)
            else -> mRecycler.adapter = mAdapter
        }
        addRecyclerAction()
    }

    @Suppress("LongMethod")
    private fun addRecyclerAction() {
        mAdapter.setOnItemClickListener(object : PictureImageGridAdapter.OnItemClickListener {
            override fun openCameraClick() {
                if (DoubleUtils.isFastDoubleClick()) {
                    return
                }
                openSelectedCamera()
            }

            override fun onSelected(selectedView: View, position: Int, media: LocalMedia): Int {
                val selectResultCode = confirmSelect(media, selectedView.isSelected)
                if (selectResultCode == SelectedManager.ADD_SUCCESS) {
                    val animListener = selectorConfig.onSelectAnimListener
                    if (animListener != null) {
                        val duration = animListener.onSelectAnim(selectedView)
                        if (duration > 0) {
                            SELECT_ANIM_DURATION = duration.toInt()
                        }
                    } else {
                        val animation = AnimationUtils.loadAnimation(context, R.anim.ps_anim_modal_in)
                        SELECT_ANIM_DURATION = animation.duration.toInt()
                        selectedView.startAnimation(animation)
                    }
                }
                return selectResultCode
            }

            override fun onItemClick(selectedView: View, position: Int, media: LocalMedia) {
                if (selectorConfig.selectionMode == SelectModeConfig.SINGLE && selectorConfig.isDirectReturnSingle) {
                    selectorConfig.selectedResult.clear()
                    val selectResultCode = confirmSelect(media, false)
                    if (selectResultCode == SelectedManager.ADD_SUCCESS) {
                        dispatchTransformResult()
                    }
                } else {
                    if (DoubleUtils.isFastDoubleClick()) {
                        return
                    }
                    onStartPreview(position, false)
                }
            }

            override fun onItemLongClick(itemView: View, position: Int) {
                val dragListener = mDragSelectTouchListener
                if (dragListener != null && selectorConfig.isFastSlidingSelect) {
                    val vibrator = requireActivity().getSystemService(Service.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(50)
                    dragListener.startSlideSelection(position)
                }
            }
        })

        mRecycler.setOnRecyclerViewScrollStateListener(object : OnRecyclerViewScrollStateListener {
            override fun onScrollFast() {
                selectorConfig.imageEngine?.pauseRequests(requireContext())
            }

            override fun onScrollSlow() {
                selectorConfig.imageEngine?.resumeRequests(requireContext())
            }
        })
        mRecycler.setOnRecyclerViewScrollListener(object : OnRecyclerViewScrollListener {
            override fun onScrolled(dx: Int, dy: Int) {
                setCurrentMediaCreateTimeText()
            }

            override fun onScrollStateChanged(state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    showCurrentMediaCreateTimeUI()
                } else if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    hideCurrentMediaCreateTimeUI()
                }
            }
        })

        if (selectorConfig.isFastSlidingSelect) {
            val selectedPosition = HashSet<Int>()
            val slideSelectionHandler = SlideSelectionHandler(object : SlideSelectionHandler.ISelectionHandler {
                override fun getSelection(): Set<Int> {
                    for (i in 0 until selectorConfig.selectCount) {
                        val media = selectorConfig.selectedResult[i]
                        selectedPosition.add(media.position)
                    }
                    return selectedPosition
                }

                override fun changeSelection(start: Int, end: Int, isSelected: Boolean, calledFromOnStart: Boolean) {
                    val adapterData = mAdapter.getData()
                    if (adapterData.size == 0 || start > adapterData.size) {
                        return
                    }
                    val media = adapterData[start]
                    val selectResultCode = confirmSelect(media, selectorConfig.selectedResult.contains(media))
                    mDragSelectTouchListener!!.isActive = selectResultCode != SelectedManager.INVALID
                }
            })
            mDragSelectTouchListener = SlideSelectTouchListener()
                .setRecyclerViewHeaderCount(if (mAdapter.isDisplayCamera()) 1 else 0)
                .withSelectListener(slideSelectionHandler)
            mRecycler.addOnItemTouchListener(mDragSelectTouchListener!!)
        }
    }

    /**
     * 显示当前资源时间轴
     */
    private fun setCurrentMediaCreateTimeText() {
        if (selectorConfig.isDisplayTimeAxis) {
            val position = mRecycler.getFirstVisiblePosition()
            if (position != RecyclerView.NO_POSITION) {
                val data = mAdapter.getData()
                if (data.size > position && data[position].dateAddedTime > 0) {
                    tvCurrentDataTime.text = DateUtils.getDataFormat(requireContext(), data[position].dateAddedTime)
                }
            }
        }
    }

    /**
     * 显示当前资源时间轴
     */
    private fun showCurrentMediaCreateTimeUI() {
        if (selectorConfig.isDisplayTimeAxis && mAdapter.getData().size > 0) {
            if (tvCurrentDataTime.alpha == 0f) {
                tvCurrentDataTime.animate().setDuration(150).alphaBy(1.0f).start()
            }
        }
    }

    /**
     * 隐藏当前资源时间轴
     */
    private fun hideCurrentMediaCreateTimeUI() {
        if (selectorConfig.isDisplayTimeAxis && mAdapter.getData().size > 0) {
            tvCurrentDataTime.animate().setDuration(250).alpha(0.0f).start()
        }
    }

    /**
     * 预览图片
     *
     * @param position        预览图片下标
     * @param isBottomPreview true 底部预览模式 false列表预览模式
     */
    private fun onStartPreview(position: Int, isBottomPreview: Boolean) {
        if (ActivityCompatHelper.checkFragmentNonExits(requireActivity(), PictureSelectorPreviewFragment.TAG)) {
            val data: ArrayList<LocalMedia>
            val totalNum: Int
            var currentBucketId = 0L
            if (isBottomPreview) {
                data = ArrayList(selectorConfig.selectedResult)
                totalNum = data.size
            } else {
                data = ArrayList(mAdapter.getData())
                val currentLocalMediaFolder = selectorConfig.currentLocalMediaFolder
                if (currentLocalMediaFolder != null) {
                    totalNum = currentLocalMediaFolder.folderTotalNum
                    currentBucketId = currentLocalMediaFolder.bucketId
                } else {
                    totalNum = data.size
                    currentBucketId = if (data.size > 0) data[0].bucketId else PictureConfig.ALL.toLong()
                }
            }
            if (!isBottomPreview && selectorConfig.isPreviewZoomEffect) {
                BuildRecycleItemViewParams.generateViewParams(
                    mRecycler,
                    if (selectorConfig.isPreviewFullScreenMode) 0 else DensityUtil.getStatusBarHeight(requireContext())
                )
            }
            val interceptListener = selectorConfig.onPreviewInterceptListener
            if (interceptListener != null) {
                interceptListener.onPreview(
                    requireContext(), position, totalNum, mPage, currentBucketId, titleBar.getTitleText(),
                    mAdapter.isDisplayCamera(), data, isBottomPreview
                )
            } else {
                if (ActivityCompatHelper.checkFragmentNonExits(requireActivity(), PictureSelectorPreviewFragment.TAG)) {
                    val previewFragment = PictureSelectorPreviewFragment.newInstance()
                    previewFragment.setInternalPreviewData(
                        isBottomPreview, titleBar.getTitleText(), mAdapter.isDisplayCamera(),
                        position, totalNum, mPage, currentBucketId, data
                    )
                    FragmentInjectManager.injectFragment(requireActivity(), PictureSelectorPreviewFragment.TAG, previewFragment)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setAdapterData(result: ArrayList<LocalMedia>?) {
        // 这个地方有个时间差，主要是解决进场动画和查询数据同时进行导致动画有点卡顿问题，
        // 主要是针对添加PictureSelectorFragment方式下
        val enterAnimationDuration = getEnterAnimationDuration()
        if (enterAnimationDuration > 0) {
            requireView().postDelayed({ setAdapterDataComplete(result) }, enterAnimationDuration)
        } else {
            setAdapterDataComplete(result)
        }
    }

    private fun setAdapterDataComplete(result: ArrayList<LocalMedia>?) {
        setEnterAnimationDuration(0L)
        sendChangeSubSelectPositionEvent(false)
        mAdapter.setDataAndDataSetChanged(result)
        selectorConfig.dataSource.clear()
        selectorConfig.albumDataSource.clear()
        recoveryRecyclerPosition()
        if (mAdapter.isDataEmpty()) {
            showDataNull()
        } else {
            hideDataNull()
        }
    }

    override fun onRecyclerViewPreloadMore() {
        if (isMemoryRecycling) {
            // 这里延迟是拍照导致的页面被回收，Fragment的重创会快于相机的onActivityResult的
            requireView().postDelayed({ loadMoreMediaData() }, 350)
        } else {
            loadMoreMediaData()
        }
    }

    /**
     * 加载更多
     */
    override fun loadMoreMediaData() {
        if (mRecycler.isEnabledLoadMore) {
            mPage++
            val localMediaFolder = selectorConfig.currentLocalMediaFolder
            val bucketId = localMediaFolder?.bucketId ?: 0L
            val engine = selectorConfig.loaderDataEngine
            if (engine != null) {
                engine.loadMoreMediaData(
                    requireContext(), bucketId, mPage,
                    selectorConfig.pageSize, selectorConfig.pageSize,
                    object : OnQueryDataResultListener<LocalMedia>() {
                        override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                            handleMoreMediaData(result, isHasMore)
                        }
                    })
            } else {
                mLoader.loadPageMediaData(bucketId, mPage, selectorConfig.pageSize,
                    object : OnQueryDataResultListener<LocalMedia>() {
                        override fun onComplete(result: ArrayList<LocalMedia>, isHasMore: Boolean) {
                            handleMoreMediaData(result, isHasMore)
                        }
                    })
            }
        }
    }

    private fun handleMoreMediaData(result: MutableList<LocalMedia>, isHasMore: Boolean) {
        if (ActivityCompatHelper.isDestroy(activity)) {
            return
        }
        mRecycler.isEnabledLoadMore = isHasMore
        if (mRecycler.isEnabledLoadMore) {
            removePageCameraRepeatData(result)
            if (result.size > 0) {
                val positionStart = mAdapter.getData().size
                mAdapter.getData().addAll(result)
                mAdapter.notifyItemRangeChanged(positionStart, mAdapter.getItemCount())
                hideDataNull()
            } else {
                // 如果没数据这里在强制调用一下上拉加载更多，防止是因为某些条件过滤导致的假为0的情况
                onRecyclerViewPreloadMore()
            }
            if (result.size < PictureConfig.MIN_PAGE_SIZE) {
                // 当数据量过少时强制触发一下上拉加载更多，防止没有自动触发加载更多
                mRecycler.onScrolled(mRecycler.scrollX, mRecycler.scrollY)
            }
        }
    }

    private fun removePageCameraRepeatData(result: MutableList<LocalMedia>) {
        try {
            if (selectorConfig.isPageStrategy && isCameraCallback) {
                synchronized(LOCK) {
                    val iterator = result.iterator()
                    while (iterator.hasNext()) {
                        if (mAdapter.getData().contains(iterator.next())) {
                            iterator.remove()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e) { "[PictureSelectorFragment] handleCameraResult error:" }
        } finally {
            isCameraCallback = false
        }
    }

    override fun dispatchCameraMediaResult(media: LocalMedia) {
        val exitsTotalNum = albumListPopWindow.getFirstAlbumImageCount()
        if (!isAddSameImp(exitsTotalNum)) {
            mAdapter.getData().add(0, media)
            isCameraCallback = true
        }
        if (selectorConfig.selectionMode == SelectModeConfig.SINGLE && selectorConfig.isDirectReturnSingle) {
            selectorConfig.selectedResult.clear()
            val selectResultCode = confirmSelect(media, false)
            if (selectResultCode == SelectedManager.ADD_SUCCESS) {
                dispatchTransformResult()
            }
        } else {
            confirmSelect(media, false)
        }
        mAdapter.notifyItemInserted(if (selectorConfig.isDisplayCamera) 1 else 0)
        mAdapter.notifyItemRangeChanged(if (selectorConfig.isDisplayCamera) 1 else 0, mAdapter.getData().size)
        if (selectorConfig.isOnlySandboxDir) {
            var currentLocalMediaFolder = selectorConfig.currentLocalMediaFolder
            if (currentLocalMediaFolder == null) {
                currentLocalMediaFolder = LocalMediaFolder()
            }
            currentLocalMediaFolder.bucketId = ValueOf.toLong(media.parentFolderName.hashCode())
            currentLocalMediaFolder.folderName = media.parentFolderName
            currentLocalMediaFolder.firstMimeType = media.mimeType
            currentLocalMediaFolder.firstImagePath = media.path
            currentLocalMediaFolder.folderTotalNum = mAdapter.getData().size
            currentLocalMediaFolder.currentDataPage = mPage
            currentLocalMediaFolder.isHasMore = false
            currentLocalMediaFolder.data = mAdapter.getData()
            mRecycler.isEnabledLoadMore = false
            selectorConfig.currentLocalMediaFolder = currentLocalMediaFolder
        } else {
            mergeFolder(media)
        }
        allFolderSize = 0
        if (mAdapter.getData().size > 0 || selectorConfig.isDirectReturnSingle) {
            hideDataNull()
        } else {
            showDataNull()
        }
    }

    /**
     * 拍照出来的合并到相应的专辑目录中去
     */
    private fun mergeFolder(media: LocalMedia) {
        val allFolder: LocalMediaFolder
        val albumList = albumListPopWindow.getAlbumList() as MutableList<LocalMediaFolder>
        if (albumListPopWindow.getFolderCount() == 0) {
            // 1、没有相册时需要手动创建相机胶卷
            allFolder = LocalMediaFolder()
            val folderName: String = if (TextUtils.isEmpty(selectorConfig.defaultAlbumName)) {
                if (selectorConfig.chooseMode == SelectMimeType.ofAudio()) getString(R.string.ps_all_audio) else getString(R.string.ps_camera_roll)
            } else {
                selectorConfig.defaultAlbumName
            }
            allFolder.folderName = folderName
            allFolder.firstImagePath = ""
            allFolder.bucketId = PictureConfig.ALL.toLong()
            albumList.add(0, allFolder)
        } else {
            // 2、有相册就找到对应的相册把数据加进去
            allFolder = albumListPopWindow.getFolder(0)!!
        }
        allFolder.firstImagePath = media.path
        allFolder.firstMimeType = media.mimeType
        allFolder.data = mAdapter.getData()
        allFolder.bucketId = PictureConfig.ALL.toLong()
        allFolder.folderTotalNum = if (isAddSameImp(allFolder.folderTotalNum)) allFolder.folderTotalNum else allFolder.folderTotalNum + 1
        val currentLocalMediaFolder = selectorConfig.currentLocalMediaFolder
        if (currentLocalMediaFolder == null || currentLocalMediaFolder.folderTotalNum == 0) {
            selectorConfig.currentLocalMediaFolder = allFolder
        }
        // 先查找Camera目录，没有找到则创建一个Camera目录
        var cameraFolder: LocalMediaFolder? = null
        for (i in albumList.indices) {
            val exitsFolder = albumList[i]
            if (TextUtils.equals(exitsFolder.folderName, media.parentFolderName)) {
                cameraFolder = exitsFolder
                break
            }
        }
        if (cameraFolder == null) {
            // 还没有这个目录，创建一个
            cameraFolder = LocalMediaFolder()
            albumList.add(cameraFolder)
        }
        cameraFolder.folderName = media.parentFolderName
        if (cameraFolder.bucketId == -1L || cameraFolder.bucketId == 0L) {
            cameraFolder.bucketId = media.bucketId
        }
        // 分页模式下，切换到Camera目录下时，会直接从MediaStore拉取
        if (selectorConfig.isPageStrategy) {
            cameraFolder.isHasMore = true
        } else {
            // 非分页模式数据都是存在目录的data下，所以直接添加进去就行
            if (!isAddSameImp(allFolder.folderTotalNum) ||
                !TextUtils.isEmpty(selectorConfig.outPutCameraDir) ||
                !TextUtils.isEmpty(selectorConfig.outPutAudioDir)
            ) {
                cameraFolder.data!!.add(0, media)
            }
        }
        cameraFolder.folderTotalNum = if (isAddSameImp(allFolder.folderTotalNum)) cameraFolder.folderTotalNum else cameraFolder.folderTotalNum + 1
        cameraFolder.firstImagePath = selectorConfig.cameraPath
        cameraFolder.firstMimeType = media.mimeType
        albumListPopWindow.bindAlbumData(albumList)
    }

    /**
     * 数量是否一致
     */
    private fun isAddSameImp(totalNum: Int): Boolean {
        if (totalNum == 0) {
            return false
        }
        return allFolderSize in 1 until totalNum
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mDragSelectTouchListener?.stopAutoScroll()
    }

    /**
     * 显示数据为空提示
     */
    private fun showDataNull() {
        val folder = selectorConfig.currentLocalMediaFolder
        if (folder == null || folder.bucketId == PictureConfig.ALL.toLong()) {
            if (tvDataEmpty.visibility == View.GONE) {
                tvDataEmpty.visibility = View.VISIBLE
            }
            tvDataEmpty.setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ps_ic_no_data, 0, 0)
            val tips: String = if (selectorConfig.chooseMode == SelectMimeType.ofAudio()) {
                getString(R.string.ps_audio_empty)
            } else if (currentMediaAccessState(context) == MediaAccessState.PARTIAL) {
                // Partial access with 0 selected: guide the user to select more (Manage bar stays visible).
                getString(R.string.ps_partial_access_empty)
            } else {
                getString(R.string.ps_empty)
            }
            tvDataEmpty.text = tips
        }
    }

    /**
     * 隐藏数据为空提示
     */
    private fun hideDataNull() {
        if (tvDataEmpty.visibility == View.VISIBLE) {
            tvDataEmpty.visibility = View.GONE
        }
    }

    companion object {
        @JvmField
        val TAG: String = PictureSelectorFragment::class.java.simpleName
        private val LOCK = Any()

        /**
         * 这个时间对应的是R.anim.ps_anim_modal_in里面的
         */
        private var SELECT_ANIM_DURATION = 135
        private const val REQUEST_PARTIAL_SETTINGS = 20086

        @JvmStatic
        fun newInstance(): PictureSelectorFragment {
            val fragment = PictureSelectorFragment()
            fragment.arguments = Bundle()
            return fragment
        }
    }
}
