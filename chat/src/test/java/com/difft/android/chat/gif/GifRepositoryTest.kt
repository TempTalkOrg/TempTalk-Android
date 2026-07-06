package com.difft.android.chat.gif

import com.difft.android.base.user.UserManager
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.UrlManager
import com.difft.android.network.responses.GifData
import com.difft.android.network.responses.GifDetailData
import com.difft.android.network.responses.GifListResponse
import com.difft.android.network.responses.Pagenation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [GifRepository] — the dual-cursor pagination + filter algorithm
 * (ported from difft-android GifViewModel). Covers Test Inventory T1, T2, T3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GifRepositoryTest {

    private val httpClient: ChativeHttpClient = mockk(relaxed = true)
    private val httpService: HttpService = mockk()
    private val urlManager: UrlManager = mockk()
    private val userManager: UserManager = mockk(relaxed = true)
    private lateinit var repository: GifRepository

    @Before
    fun setUp() {
        every { httpClient.httpService } returns httpService
        every { urlManager.gifs } returns "https://host/gifs/"
        repository = GifRepository(httpClient, urlManager, userManager)
    }

    private fun detail(gif: String?, webp: String? = "w") =
        GifDetailData(gif = gif, height = 100, webp = webp, width = 100)

    private fun success(data: GifListResponse): BaseResponse<GifListResponse> =
        BaseResponse(ver = 1, status = 0, reason = "", data = data)

    @Test
    fun `T1 trending with pagination count plus offset less than total has more`() = runTest {
        val list = listOf(
            GifData(id = "1", original = detail("o1"), preview = detail("p1"), title = "t"),
            // filtered out: missing original gif
            GifData(id = "2", original = detail(null), preview = detail("p2"), title = "t"),
            // filtered out: missing preview gif
            GifData(id = "3", original = detail("o3"), preview = detail(null), title = "t")
        )
        coEvery { httpService.getGifsTrending(any(), any(), any(), any(), any(), any()) } returns
            success(GifListResponse(data = list, next = null, pagination = Pagenation(count = 20, offset = 0, total_count = 100)))

        val page = repository.fetch(query = null, limit = 20, offset = 0, next = null)

        assertTrue(page.hasMore)
        assertEquals(1, page.items.size)
        assertEquals("1", page.items.first().id)
        assertEquals(20, page.pageCount)
    }

    @Test
    fun `T2 next cursor present without pagination has more`() = runTest {
        coEvery { httpService.getGifsTrending(any(), any(), any(), any(), any(), any()) } returns
            success(GifListResponse(data = emptyList(), next = "cursor123", pagination = null))

        val page = repository.fetch(query = null, limit = 20, offset = 0, next = null)

        assertTrue(page.hasMore)
        assertEquals("cursor123", page.next)
    }

    @Test
    fun `T2b pagination exhausted has no more`() = runTest {
        coEvery { httpService.getGifsSearch(any(), any(), any(), any(), any(), any(), any()) } returns
            success(GifListResponse(data = emptyList(), next = null, pagination = Pagenation(count = 20, offset = 80, total_count = 100)))

        val page = repository.fetch(query = "cat", limit = 20, offset = 80, next = null)

        assertFalse(page.hasMore)
    }

    @Test
    fun `T3 non-success envelope throws GifException`() = runTest {
        coEvery { httpService.getGifsTrending(any(), any(), any(), any(), any(), any()) } returns
            BaseResponse(ver = 1, status = 1, reason = "boom", data = null)

        val ex = assertFailsWith<GifException> {
            repository.fetch(query = null, limit = 20, offset = 0, next = null)
        }
        assertEquals("boom", ex.message)
    }
}
