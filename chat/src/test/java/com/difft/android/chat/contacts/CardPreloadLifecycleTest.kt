package com.difft.android.chat.contacts

import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardPreloadLifecycleTest {
    private val preloads = CardPreloads()

    @Test fun `a host stopped during the read shows the card once it is back`() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val read = CompletableDeferred<String>()
        val shown = mutableListOf<String?>()
        request(host.get(), read, shown)
        host.pause().stop().saveInstanceState(Bundle())
        read.complete("local")
        idle()
        assertEquals(emptyList(), shown)
        host.start().resume()
        idle()
        assertEquals(1, shown.size)
        assertNotNull(preloads.take<String>(shown.single()))
    }

    @Test fun `a destroyed host drops the card`() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val read = CompletableDeferred<String>()
        val shown = mutableListOf<String?>()
        request(host.get(), read, shown)
        host.pause().stop().destroy()
        read.complete("local")
        idle()
        assertEquals(emptyList(), shown)
    }

    @Test fun `a second tap while the first is reading opens one card`() {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val read = CompletableDeferred<String>()
        val shown = mutableListOf<String?>()
        request(host, read, shown)
        request(host, read, shown)
        read.complete("local")
        idle()
        assertEquals(1, shown.size)
    }

    private fun request(host: FragmentActivity, read: CompletableDeferred<String>, shown: MutableList<String?>) =
        showCardAfterLocalRead(host, host.supportFragmentManager, "card", preloads, read = { read.await() },
            dispatcher = Dispatchers.Main.immediate) { shown += it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
