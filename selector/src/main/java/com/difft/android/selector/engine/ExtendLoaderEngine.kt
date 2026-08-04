package com.difft.android.selector.engine

import android.content.Context

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.entity.LocalMediaFolder
import com.difft.android.selector.interfaces.OnQueryAlbumListener
import com.difft.android.selector.interfaces.OnQueryAllAlbumListener
import com.difft.android.selector.interfaces.OnQueryDataResultListener

@Deprecated("Custom data loader engine")
interface ExtendLoaderEngine {
    /** Load all album list data. */
    fun loadAllAlbumData(context: Context, query: OnQueryAllAlbumListener<LocalMediaFolder>)

    /** Load resources in the specified directory. */
    fun loadOnlyInAppDirAllMediaData(context: Context, query: OnQueryAlbumListener<LocalMediaFolder>)

    /** Load the first page of data. Valid only in isPageStrategy mode. */
    fun loadFirstPageMediaData(
        context: Context,
        bucketId: Long,
        page: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>
    )

    /** Load more data. Valid only in isPageStrategy mode. */
    fun loadMoreMediaData(
        context: Context,
        bucketId: Long,
        page: Int,
        limit: Int,
        pageSize: Int,
        query: OnQueryDataResultListener<LocalMedia>
    )
}
