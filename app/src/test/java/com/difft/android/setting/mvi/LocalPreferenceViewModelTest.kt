package com.difft.android.setting.mvi

import com.difft.android.base.user.UserData
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.call.data.VoicePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPreferenceViewModelTest {
    @Test fun `display completion is kept in state so a page recreated before its pop still closes`() = runTest {
        val users = FakeUserManager(UserData(account = "original"))
        val model = LocalPreferenceViewModel(users)
        assertFalse(model.state.value.closeRequested)
        model.dispatch(LocalPreferenceContract.Intent.SelectDisplay(NotificationContentDisplayType.entries.first().value))
        // The first page collector consumes it by popping; a collector attached after a fold sees the same truth.
        assertTrue(model.state.first().closeRequested)
        assertTrue(model.state.first().closeRequested)
        model.dispatch(LocalPreferenceContract.Intent.SelectSpeed(1.5f))
        assertTrue(model.state.value.closeRequested)
    }

    @Test fun `valid choices persist and invalid choices leave state and storage unchanged`() {
        val users = FakeUserManager(UserData(account = "original"))
        val model = LocalPreferenceViewModel(users)
        model.dispatch(LocalPreferenceContract.Intent.SelectSpeed(1.5f))
        assertEquals(1.5f, model.state.value.speed)
        assertEquals(1.5f, users.getUserData()?.voicePlaybackSpeed)
        model.dispatch(LocalPreferenceContract.Intent.SelectVoice(VoicePreset.ORIGINAL))
        assertEquals(VoicePreset.ORIGINAL.sdkKey, users.getUserData()?.callVoiceChangerPreset)
        val before = model.state.value
        val stored = users.getUserData()?.copy()
        model.dispatch(LocalPreferenceContract.Intent.SelectSpeed(5f))
        model.dispatch(LocalPreferenceContract.Intent.SelectDisplay(Int.MIN_VALUE))
        assertEquals(before, model.state.value)
        assertEquals(stored, users.getUserData())
    }
}
