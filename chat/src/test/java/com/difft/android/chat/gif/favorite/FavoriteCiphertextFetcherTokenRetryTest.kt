package com.difft.android.chat.gif.favorite

import com.difft.android.chat.fileshare.DownloadReq
import com.difft.android.chat.fileshare.DownloadResp
import com.difft.android.chat.fileshare.FileShareRepo
import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.AuthStatus
import com.difft.android.network.auth.AuthTokenProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Call
import retrofit2.Response
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse









class FavoriteCiphertextFetcherTokenRetryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeProvider(
        private val first: String,
        private val refreshed: String,
    ) : AuthTokenProvider {
        var refreshCalls = 0
            private set

        override suspend fun current(): String = first
        override fun peek(): String = first
        override suspend fun refreshRejected(rejected: String): String {
            refreshCalls++
            return refreshed
        }
    }

    private val fileShareRepo = mockk<FileShareRepo>()

    private fun answering(status: Int): Call<BaseResponse<DownloadResp>> =
        mockk<Call<BaseResponse<DownloadResp>>>().also {
            every { it.execute() } returns
                Response.success(BaseResponse(ver = 1, status = status, reason = null, data = null))
        }

    
    @Test
    fun `a rejected download token is retried with the refreshed token`() = runTest {
        val tokens = mutableListOf<String>()
        every { fileShareRepo.download(any()) } answers {
            tokens += firstArg<DownloadReq>().token
            answering(if (tokens.size == 1) AuthStatus.INVALID_TOKEN else NO_PERMISSION)
        }
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        val fetcher = FavoriteCiphertextFetcher(fileShareRepo, provider)
        val dest = File(temp.newFolder(), "favorite.enc")

        
        assertFalse(fetcher.downloadCiphertextTo(AUTHORIZE_ID, FILE_HASH, dest))

        assertEquals(listOf(STALE, FRESH), tokens)
        assertEquals(1, provider.refreshCalls)
    }

    private companion object {
        const val STALE = "stale-token"
        const val FRESH = "fresh-token"
        const val NO_PERMISSION = 2
        const val AUTHORIZE_ID = 42L
        const val FILE_HASH = "abcdef"
    }
}
