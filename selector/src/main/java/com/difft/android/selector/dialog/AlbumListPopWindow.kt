package com.difft.android.selector.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.adapter.PictureAlbumAdapter
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.decoration.WrapContentLinearLayoutManager
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnAlbumItemClickListener
import com.difft.android.selector.utils.DensityUtil

class AlbumListPopWindow(context: Context, config: SelectorConfig) : PopupWindow() {
    private val mContext: Context = context
    private lateinit var windMask: View
    private lateinit var mRecyclerView: RecyclerView
    private var isDismiss = false
    private var windowMaxHeight = 0
    private lateinit var mAdapter: PictureAlbumAdapter
    private val selectorConfig: SelectorConfig = config

    private var windowStatusListener: OnPopupWindowStatusListener? = null

    init {
        contentView = LayoutInflater.from(context).inflate(R.layout.ps_window_folder, null)
        width = RelativeLayout.LayoutParams.MATCH_PARENT
        height = RelativeLayout.LayoutParams.WRAP_CONTENT
        animationStyle = R.style.PictureThemeWindowStyle
        isFocusable = true
        isOutsideTouchable = true
        update()
        initViews()
    }

    private fun initViews() {
        windowMaxHeight = (DensityUtil.getScreenHeight(mContext) * 0.6).toInt()
        mRecyclerView = contentView.findViewById(R.id.folder_list)
        windMask = contentView.findViewById(R.id.rootViewBg)
        mRecyclerView.layoutManager = WrapContentLinearLayoutManager(mContext)
        mAdapter = PictureAlbumAdapter(selectorConfig)
        mRecyclerView.adapter = mAdapter
        windMask.setOnClickListener { dismiss() }
        // rootView click-to-dismiss was a pre-M vestige (guarded by SDK_INT < M,
        // unreachable at minSdk 26). Dismiss is handled by setOutsideTouchable(true)
        // + the windMask click listener above.
    }

    @SuppressLint("NotifyDataSetChanged")
    fun bindAlbumData(list: List<LocalMediaFolder>) {
        mAdapter.bindAlbumData(list)
        mAdapter.notifyDataSetChanged()
        val lp = mRecyclerView.layoutParams
        lp.height = if (list.size > ALBUM_MAX_COUNT) windowMaxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
    }

    fun getAlbumList(): List<LocalMediaFolder> = mAdapter.getAlbumList()

    fun getFolder(position: Int): LocalMediaFolder? =
        if (mAdapter.getAlbumList().size > 0 && position < mAdapter.getAlbumList().size) {
            mAdapter.getAlbumList()[position]
        } else {
            null
        }

    fun getFirstAlbumImageCount(): Int =
        if (getFolderCount() > 0) getFolder(0)!!.folderTotalNum else 0

    fun getFolderCount(): Int = mAdapter.getAlbumList().size

    fun setOnIBridgeAlbumWidget(listener: OnAlbumItemClickListener) {
        mAdapter.setOnIBridgeAlbumWidget(listener)
    }

    override fun showAsDropDown(anchor: View) {
        if (getAlbumList().isEmpty()) {
            return
        }
        // isN() (SDK_INT == N/24) is always false at minSdk 26 — the showAtLocation
        // workaround branch was dead; always use the standard showAsDropDown.
        super.showAsDropDown(anchor)
        isDismiss = false
        windowStatusListener?.onShowPopupWindow()
        windMask.animate().alpha(1f).setDuration(250).setStartDelay(250).start()
        changeSelectedAlbumStyle()
    }

    fun changeSelectedAlbumStyle() {
        val folders = mAdapter.getAlbumList()
        for (i in folders.indices) {
            val folder = folders[i]
            folder.isSelectTag = false
            mAdapter.notifyItemChanged(i)
            for (j in 0 until selectorConfig.selectCount) {
                val media = selectorConfig.selectedResult[j]
                if (TextUtils.equals(folder.folderName, media.parentFolderName)
                    || folder.bucketId == PictureConfig.ALL.toLong()
                ) {
                    folder.isSelectTag = true
                    mAdapter.notifyItemChanged(i)
                    break
                }
            }
        }
    }

    override fun dismiss() {
        if (isDismiss) {
            return
        }
        windMask.alpha = 0f
        windowStatusListener?.onDismissPopupWindow()
        isDismiss = true
        windMask.post {
            super@AlbumListPopWindow.dismiss()
            isDismiss = false
        }
    }

    fun setOnPopupWindowStatusListener(listener: OnPopupWindowStatusListener) {
        this.windowStatusListener = listener
    }

    interface OnPopupWindowStatusListener {
        fun onShowPopupWindow()
        fun onDismissPopupWindow()
    }

    companion object {
        private const val ALBUM_MAX_COUNT = 8

        @JvmStatic
        fun buildPopWindow(context: Context, config: SelectorConfig): AlbumListPopWindow =
            AlbumListPopWindow(context, config)
    }
}
