package com.difft.android.selector.basic

interface IPictureSelectorEvent {
    fun loadAllAlbumData()

    fun loadFirstPageMediaData(firstBucketId: Long)

    fun loadOnlyInAppDirectoryAllMediaData()

    fun loadMoreMediaData()
}
