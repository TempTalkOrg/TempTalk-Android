package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener

@Deprecated("Custom data loader engine")
interface ExtendLoaderEngine {
    
    fun loadAllAlbumData(context: Context, query: OnQueryAllAlbumListener<LocalMediaFolder>)

    
    fun loadOnlyInAppDirAllMediaData(context: Context, query: OnQueryAlbumListener<LocalMediaFolder>)

    
    fun loadFirstPageMediaData(
        context: Context,
        bucketId: Long,
        page: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>
    )

    
    fun loadMoreMediaData(
        context: Context,
        bucketId: Long,
        page: Int,
        limit: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>
    )
}
