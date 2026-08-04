package com.difft.android.selector.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.selector.R
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnAlbumItemClickListener

class PictureAlbumAdapter(private val selectorConfig: SelectorConfig) :
    RecyclerView.Adapter<PictureAlbumAdapter.ViewHolder>() {

    private var albumList: MutableList<LocalMediaFolder>? = null

    private var onAlbumItemClickListener: OnAlbumItemClickListener? = null

    fun bindAlbumData(albumList: List<LocalMediaFolder>) {
        this.albumList = ArrayList(albumList)
    }

    fun getAlbumList(): List<LocalMediaFolder> {
        return albumList ?: ArrayList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutResourceId = InjectResourceSource.getLayoutResource(parent.context, InjectResourceSource.ALBUM_ITEM_LAYOUT_RESOURCE, selectorConfig)
        val itemView = LayoutInflater.from(parent.context)
            .inflate(if (layoutResourceId != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) layoutResourceId else R.layout.ps_album_folder_item, parent, false)
        return ViewHolder(itemView)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = albumList!![position]
        val name = folder.folderName
        val imageNum = folder.folderTotalNum
        val imagePath = folder.firstImagePath
        holder.tvSelectTag.visibility = if (folder.isSelectTag) View.VISIBLE else View.INVISIBLE
        val currentLocalMediaFolder = selectorConfig.currentLocalMediaFolder
        holder.itemView.isSelected = currentLocalMediaFolder != null && folder.bucketId == currentLocalMediaFolder.bucketId
        val firstMimeType = folder.firstMimeType
        if (PictureMimeType.isHasAudio(firstMimeType)) {
            holder.ivFirstImage.setImageResource(R.drawable.ps_audio_placeholder)
        } else {
            selectorConfig.imageEngine?.loadAlbumCover(holder.itemView.context, imagePath, holder.ivFirstImage)
        }
        val context = holder.itemView.context
        holder.tvFolderName.text = context.getString(R.string.ps_camera_roll_num, name, imageNum)
        holder.itemView.setOnClickListener {
            if (onAlbumItemClickListener == null) {
                return@setOnClickListener
            }
            onAlbumItemClickListener!!.onItemClick(position, folder)
        }
    }

    override fun getItemCount(): Int {
        return albumList!!.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivFirstImage: ImageView = itemView.findViewById(R.id.first_image)
        val tvFolderName: TextView = itemView.findViewById(R.id.tv_folder_name)
        val tvSelectTag: TextView = itemView.findViewById(R.id.tv_select_tag)

        init {
            val selectorStyle = selectorConfig.selectorStyle
            val albumWindowStyle = selectorStyle.albumWindowStyle!!
            val itemBackground = albumWindowStyle.albumAdapterItemBackground
            if (itemBackground != 0) {
                itemView.setBackgroundResource(itemBackground)
            }
            val itemSelectStyle = albumWindowStyle.albumAdapterItemSelectStyle
            if (itemSelectStyle != 0) {
                tvSelectTag.setBackgroundResource(itemSelectStyle)
            }
            val titleColor = albumWindowStyle.albumAdapterItemTitleColor
            if (titleColor != 0) {
                tvFolderName.setTextColor(titleColor)
            }
            val titleSize = albumWindowStyle.albumAdapterItemTitleSize
            if (titleSize > 0) {
                tvFolderName.setTextSize(titleSize.toFloat())
            }
        }
    }

    fun setOnIBridgeAlbumWidget(listener: OnAlbumItemClickListener) {
        this.onAlbumItemClickListener = listener
    }
}
