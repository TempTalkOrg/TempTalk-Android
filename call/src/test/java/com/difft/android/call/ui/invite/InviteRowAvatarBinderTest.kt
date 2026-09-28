package com.difft.android.call.ui.invite

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.AvatarViewBinder
import com.difft.android.call.data.AvatarData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recycling contract of the meeting-invite row avatar slot: exactly one child View for the
 * lifetime of the holder, an immediate value painted synchronously, and a pending fetch that is
 * cancelled — so its result never lands — once the row is rebound or recycled.
 *
 * Collaborators are injected through the constructor, so the contract is exercised without Hilt
 * and without a RecyclerView.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InviteRowAvatarBinderTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val container = FrameLayout(ApplicationProvider.getApplicationContext())
    private val fake = RecordingAvatarViewBinder(View(ApplicationProvider.getApplicationContext()))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `construction adds exactly one child`() {
        binderWith { neverCompletes() }

        assertEquals(1, container.childCount)
        assertSame(fake.view, container.getChildAt(0))
    }

    @Test
    fun `repeated binds never add another child`() {
        val binder = binderWith { neverCompletes() }

        repeat(3) { index -> binder.bind("u$index", nameData("u$index")) }

        assertEquals(1, container.childCount)
        assertSame(fake.view, container.getChildAt(0))
    }

    @Test
    fun `the immediate value is bound before the fetch resolves`() {
        val pending = CompletableDeferred<AvatarData?>()
        val binder = binderWith { pending.await() }
        val immediate = nameData("u1")

        binder.bind("u1", immediate)

        assertEquals(listOf(immediate), fake.bound)
    }

    /**
     * Rebinding cancels the previous fetch, so its result never reaches the row. The
     * `boundUid == uid` compare inside the binder is defence-in-depth behind that cancel.
     */
    @Test
    fun `rebinding cancels the previous fetch so its late result never lands`() {
        val pending = CompletableDeferred<AvatarData?>()
        // Set only if the u1 fetch resumes normally; cancellation must prevent that, so the
        // guard inside the binder is never what drops the result.
        var resumedNormally = false
        val binder = binderWith { uid ->
            if (uid == "u1") pending.await().also { resumedNormally = true } else null
        }
        val first = nameData("u1")
        val second = nameData("u2")

        binder.bind("u1", first)
        binder.bind("u2", second)
        pending.complete(nameData("u1-fetched"))

        assertFalse("rebind must cancel the previous fetch", resumedNormally)
        assertEquals(listOf(first, second), fake.bound)
    }

    @Test
    fun `cancel stops the pending fetch and drops its result`() {
        val pending = CompletableDeferred<AvatarData?>()
        var resumedNormally = false
        val binder = binderWith { pending.await().also { resumedNormally = true } }
        val immediate = nameData("u1")

        binder.bind("u1", immediate)
        binder.cancel()
        pending.complete(nameData("u1-fetched"))

        assertFalse("cancel must stop the fetch, not merely drop its result", resumedNormally)
        assertEquals(listOf(immediate), fake.bound)
        assertEquals(1, container.childCount)
    }

    @Test
    fun `a failing fetch keeps the immediate value`() {
        val binder = binderWith { throw IllegalStateException("boom") }
        val immediate = nameData("u1")

        binder.bind("u1", immediate)

        assertEquals(listOf(immediate), fake.bound)
    }

    @Test
    fun `a resolved fetch replaces the immediate value`() {
        val fetched = nameData("u1-fetched")
        val binder = binderWith { fetched }
        val immediate = nameData("u1")

        binder.bind("u1", immediate)

        assertEquals(listOf(immediate, fetched), fake.bound)
        assertEquals(1, container.childCount)
    }

    @Test
    fun `a fetch equal to the immediate value is not re-bound`() {
        // Re-binding the same record would only restart the load the immediate bind started.
        val binder = binderWith { nameData("u1") }
        val immediate = nameData("u1")

        binder.bind("u1", immediate)

        assertEquals(listOf(immediate), fake.bound)
    }

    private fun binderWith(fetch: suspend (String) -> AvatarData?) = InviteRowAvatarBinder(
        container = container,
        scope = scope,
        logTag = "InviteRowAvatarBinderTest",
        avatarBinder = fake,
        fetchAvatarData = fetch,
    )

    private suspend fun neverCompletes(): AvatarData? = CompletableDeferred<AvatarData?>().await()

    private fun nameData(uid: String) = AvatarData.FromNameOrUid(uid, uid)

    /** Records every [bind] so the test can assert the exact sequence the row received. */
    private class RecordingAvatarViewBinder(override val view: View) : AvatarViewBinder {
        val bound = mutableListOf<AvatarData>()

        override fun bind(data: AvatarData) {
            bound += data
        }
    }
}
