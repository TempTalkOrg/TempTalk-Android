package com.difft.android.selector.interfaces

import com.difft.android.selector.entity.LocalMediaFolder

interface OnAlbumItemClickListener {
    fun onItemClick(position: Int, curFolder: LocalMediaFolder)
}
