package com.difft.android.chat.gif

import com.difft.android.base.user.UserManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.GifData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One page of GIF results plus the cursors needed to fetch the next page. */
data class GifPage(
    val items: List<GifData>,
    val hasMore: Boolean,
    val next: String?,
    val pageCount: Int
)

/** Thrown when the GIF trending/search endpoint returns a non-success envelope. */
class GifException(message: String?) : RuntimeException(message)

/**
 * GIF network + pagination layer. Ports difft-android's GifViewModel network logic
 * (RxJava Single -> suspend) and keeps the dual-cursor (offset + next) hasMore algorithm.
 * The `/gifs/` route requires token auth, so the JWT (microToken) is passed explicitly;
 * the HeaderInterceptor's Basic baseAuth fallback is rejected by this route (401).
 * Endpoints take a full URL built from [UrlManager.gifs] so requests ride the `/gifs/` proxy.
 */
@Singleton
class GifRepository @Inject constructor(
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val userManager: UserManager
) {
    private fun token(): String = userManager.getUserData()?.microToken ?: ""

    companion object {
        const val PAGE_LIMIT = 20
    }

    /**
     * Fetch one page. Empty/null [query] -> trending, otherwise search.
     * Filters out entries missing a usable preview or original gif (matches difft).
     */
    suspend fun fetch(query: String?, limit: Int, offset: Int, next: String?): GifPage =
        withContext(Dispatchers.IO) {
            val base = urlManager.gifs
            val resp = if (query.isNullOrEmpty()) {
                httpClient.httpService.getGifsTrending(token(), base + "v1/gifs/trending", limit, offset, next)
            } else {
                httpClient.httpService.getGifsSearch(token(), base + "v1/gifs/search", query, limit, offset, next)
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
