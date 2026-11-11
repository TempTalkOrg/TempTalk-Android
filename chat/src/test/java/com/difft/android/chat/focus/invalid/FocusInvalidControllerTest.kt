package com.difft.android.chat.focus.invalid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupFocusState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull





@OptIn(ExperimentalCoroutinesApi::class)
class FocusInvalidControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val effective = MutableStateFlow(invalid(0, version = 5))
    private var account: String? = "me"
    private val cachedState = MutableStateFlow(GroupFocusState(featureEnabled = true, ownerUid = "me"))
    private val repository: GroupFocusRepository = mockk {
        every { this@mockk.effective } returns this@FocusInvalidControllerTest.effective
        every { state } returns cachedState
        every { effectiveNow() } answers { this@FocusInvalidControllerTest.effective.value }
        coEvery { ensureLoaded() } returns Unit
    }

    private fun invalid(reason: Int, version: Int, gid: String = "A") =
        EffectiveFocus(featureEnabled = true, active = true, invalid = true, invalidReason = reason, groupIds = setOf(gid), version = version)

    private fun valid(gid: String, version: Int) = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf(gid), version = version)

    private fun TestScope.dataStore(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, name) }

    private fun TestScope.controller(store: DataStore<Preferences>) = FocusInvalidController(store, repository, backgroundScope, { account })

    @Test
    fun `the dialog is pending until its episode is handled, and the answer survives a restart`() = runTest(UnconfinedTestDispatcher()) {
        val store = dataStore("marks.preferences_pb") 
        val first = controller(store)
        val pending = first.pendingDialog.first { it != null }
        assertEquals(FocusInvalidKind.DISBANDED, pending?.kind)

        first.markDialogHandled(pending!!.episode).join()
        assertNull(first.pendingDialog.first { it == null })

        val second = controller(store)
        assertEquals(true, second.stripVisible.first { it }) 
        assertNull(second.pendingDialog.first { it == null })

        
        effective.value = invalid(1, version = 6)
        assertEquals(FocusInvalidKind.REMOVED, second.pendingDialog.first { it != null }?.kind)
    }

    @Test
    fun `editing options in the invalid state does not bring back an answered dialog or a closed strip`() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(dataStore("marks4.preferences_pb"))
        controller.markDialogHandled(controller.pendingDialog.first { it != null }!!.episode).join()
        controller.dismissStrip(controller.currentEpisode()).join()

        effective.value = invalid(0, version = 9) 
        assertNull(controller.pendingDialog.first { it == null })
        assertEquals(false, controller.stripVisible.first { !it })
    }

    @Test
    fun `a valid to invalid transition after a reselect is a new episode even for the same reason and group`() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(dataStore("marks5.preferences_pb"))
        controller.markDialogHandled(controller.pendingDialog.first { it != null }!!.episode).join()

        effective.value = valid("B", version = 6) 
        assertNull(controller.pendingDialog.first { it == null })
        effective.value = invalid(0, version = 7, gid = "A") 
        assertEquals("0:A", controller.pendingDialog.first { it != null }?.episode)
        assertEquals(true, controller.stripVisible.first { it })
    }

    @Test
    fun `nothing shows until the marks are loaded`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val slow = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                gate.await()
                emit(emptyPreferences())
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = emptyPreferences()
        }
        val controller = FocusInvalidController(slow, repository, backgroundScope, { account })
        assertNull(controller.pendingDialog.value)
        assertEquals(false, controller.stripVisible.value)

        gate.complete(Unit)
        assertEquals(5, controller.pendingDialog.first { it != null }?.version)
        assertEquals(true, controller.stripVisible.first { it })
    }

    @Test
    fun `the dialog clears when another device resolves the state with a newer version`() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(dataStore("marks2.preferences_pb"))
        assertEquals(5, controller.pendingDialog.first { it != null }?.version)

        effective.value = valid("B", version = 6)
        assertNull(controller.pendingDialog.first { it == null })
        assertEquals(false, controller.stripVisible.first { !it })
    }

    @Test
    fun `a mark of another account is ignored, the dialog and the strip show again`() = runTest(UnconfinedTestDispatcher()) {
        val store = dataStore("other-account.preferences_pb")
        store.edit {
            it[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE] = "someone-else|0:A"
            it[AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE] = "someone-else|0:A"
        }
        val controller = controller(store)
        assertEquals("0:A", controller.pendingDialog.first { it != null }?.episode)
        assertEquals(true, controller.stripVisible.first { it })
    }

    @Test
    fun `a mark of this account is honored on load and written stamped with the account`() = runTest(UnconfinedTestDispatcher()) {
        val store = dataStore("own-account.preferences_pb")
        store.edit {
            it[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE] = "me|0:A"
            it[AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE] = "me|0:A"
        }
        val controller = controller(store)
        effective.value = invalid(1, version = 6) 
        controller.pendingDialog.first { it != null }

        effective.value = invalid(0, version = 7) 
        assertNull(controller.pendingDialog.first { it == null })
        assertEquals(false, controller.stripVisible.first { !it })

        effective.value = invalid(1, version = 8)
        controller.markDialogHandled("1:A").join()
        assertEquals("me|1:A", store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])
    }

    @Test
    fun `markDialogHandled writes only while the same invalid episode is still current for this account`() = runTest(UnconfinedTestDispatcher()) {
        val store = dataStore("guard.preferences_pb")
        val controller = controller(store)
        val episode = controller.pendingDialog.first { it != null }!!.episode

        
        effective.value = EffectiveFocus.INACTIVE
        controller.markDialogHandled(episode).join()
        assertNull(store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])

        
        effective.value = valid("B", version = 7)
        controller.markDialogHandled(episode).join()
        assertNull(store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])

        
        effective.value = invalid(1, version = 8)
        controller.markDialogHandled(episode).join()
        assertNull(store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])
        assertEquals(FocusInvalidKind.REMOVED, controller.pendingDialog.first { it != null }?.kind) 

        
        controller.markDialogHandled("1:A").join()
        assertEquals("me|1:A", store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])
        assertNull(controller.pendingDialog.first { it == null })
    }

    @Test
    fun `markDialogHandled is a no-op for another account or without one`() = runTest(UnconfinedTestDispatcher()) {
        val store = dataStore("guard-account.preferences_pb")
        val controller = controller(store)
        val episode = controller.pendingDialog.first { it != null }!!.episode

        cachedState.value = GroupFocusState(featureEnabled = true, ownerUid = "someone-else")
        controller.markDialogHandled(episode).join()
        assertNull(store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])

        cachedState.value = GroupFocusState(featureEnabled = true, ownerUid = "me")
        account = null
        controller.markDialogHandled(episode).join()
        assertNull(store.data.first()[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE])
        assertEquals(episode, controller.pendingDialog.value?.episode)
    }

    @Test
    fun `the strip is closed per episode and returns for a different invalidation`() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(dataStore("marks3.preferences_pb"))
        controller.stripVisible.first { it }

        controller.dismissStrip(controller.currentEpisode()).join()
        assertEquals(false, controller.stripVisible.first { !it })

        effective.value = invalid(2, version = 6)
        assertEquals(true, controller.stripVisible.first { it })
    }
}
