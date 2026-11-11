package com.difft.android.chat.gif

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.GifData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton


data class GifPage(
    val items: List<GifData>,
    val hasMore: Boolean,
    val next: String?,
    val pageCount: Int
)


class GifException(message: String?) : RuntimeException(message)





@Singleton
class GifRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager
) {
    companion object {
        const val PAGE_LIMIT = 20
    }

    
    suspend fun fetch(query: String?, limit: Int, offset: Int, next: String?): GifPage =
        withContext(Dispatchers.IO) {
            val base = urlManager.gifs
            val resp = if (query.isNullOrEmpty()) {
                httpClient.httpService.getGifsTrending(base + "v1/gifs/trending", limit, offset, next)
            } else {
                httpClient.httpService.getGifsSearch(base + "v1/gifs/search", query, limit, offset, next)
            }
            if (!resp.isSuccess()) throw GifException(resp.reason)
            val data = resp.data
            val hasMore = data?.pagination?.let { it.count + it.offset < it.total_count }
                ?: (data?.next != null)
            val list = data?.data?.filter {
                !it.preview?.gif.isNullOrEmpty() && !it.original?.gif.isNullOrEmpty()
            } ?: emptyList()
            GifPage(list, hasMore, data?.next, data?.pagination?.count ?: 0)
        }
}
