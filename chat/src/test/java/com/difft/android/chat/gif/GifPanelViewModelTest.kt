package com.difft.android.chat.gif

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [GifPanelViewModel]. T8 (search debounce -> only last query fetches),
 * T9 (PickGif -> Effect.SendGif). Uses a StandardTestDispatcher so the 300ms debounce
 * window is controllable via virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GifPanelViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val gifRepository: GifRepository = mockk()
    private val gifSendUseCase: GifSendUseCase = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Default: any fetch returns an empty page so the init trending load is harmless.
        coEvery { gifRepository.fetch(any(), any(), any(), any()) } returns
            GifPage(items = emptyList(), hasMore = false, next = null, pageCount = 0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item() = GifUiItem(
        id = "1",
        webpUrl = "https://host/w.webp",
        width = 200,
        height = 100
    )

    @Test
    fun `T8 search debounce only last query triggers fetch`() = runTest(dispatcher) {
        val vm = GifPanelViewModel(gifRepository, gifSendUseCase)
        advanceUntilIdle() // drain the init trending load

        vm.dispatch(GifPanelContract.Intent.Search("c"))
        advanceTimeBy(100)
        vm.dispatch(GifPanelContract.Intent.Search("ca"))
        advanceTimeBy(100)
        vm.dispatch(GifPanelContract.Intent.Search("cat"))
        advanceUntilIdle() // let the 300ms debounce settle on the last value

        // Only "cat" survives the debounce -> exactly one search fetch with that query.
        coVerify(exactly = 1) { gifRepository.fetch("cat", any(), any(), any()) }
        coVerify(exactly = 0) { gifRepository.fetch("c", any(), any(), any()) }
        coVerify(exactly = 0) { gifRepository.fetch("ca", any(), any(), any()) }
    }

    @Test
    fun `T9 pick gif emits SendGif effect`() = runTest(dispatcher) {
        val fakeUri = mockk<Uri>(relaxed = true)
        coEvery { gifSendUseCase.resolveSendable(any()) } returns fakeUri

        val vm = GifPanelViewModel(gifRepository, gifSendUseCase)
        advanceUntilIdle()

        vm.effect.test {
            vm.dispatch(GifPanelContract.Intent.PickGif(item()))
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is GifPanelContract.Effect.SendGif)
            assertEquals(fakeUri, effect.uri)
            assertEquals(200, effect.width)
            assertEquals(100, effect.height)
            cancelAndIgnoreRemainingEvents()
        }
        // Send now resolves the preview rendition (webpUrl) everywhere — the grid already caches it,
        // so no extra download (dropped the separate original rendition).
        coVerify { gifSendUseCase.resolveSendable(GifSendInput.FromUrl("https://host/w.webp", 200, 100)) }
    }
}
