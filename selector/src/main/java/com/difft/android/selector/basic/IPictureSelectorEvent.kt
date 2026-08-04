package com.difft.android.selector.basic

interface IPictureSelectorEvent {
    /** 获取相册目录 */
    fun loadAllAlbumData()

    /** 获取首页资源 */
    fun loadFirstPageMediaData(firstBucketId: Long)

    /** 加载应用沙盒内的资源 */
    fun loadOnlyInAppDirectoryAllMediaData()

    /** 加载更多 */
    fun loadMoreMediaData()
}
