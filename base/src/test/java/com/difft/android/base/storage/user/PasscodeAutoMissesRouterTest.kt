package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals





@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PasscodeAutoMissesRouterTest {

    @Test
    fun `diff emits IntChange for passcodeAutoMisses`() {
        val changes = UserDataFieldRouter.diff(
            old = UserData(passcodeAutoMisses = 3),
            new = UserData(passcodeAutoMisses = 4),
        ).appStateChanges
        val change = changes.filterIsInstance<UserDataFieldRouter.AppStateChange.IntChange>()
            .single { it.key == AppStateKeys.PASSCODE_AUTO_MISSES }
        assertEquals(4, change.value)
    }

    @Test
    fun `apply copies passcodeAutoMisses into snapshot`() {
        val result = UserDataFieldRouter.applyAppStateChangeToSnapshot(
            base = UserData(passcodeAutoMisses = 0),
            key = AppStateKeys.PASSCODE_AUTO_MISSES,
            value = 7,
        )
        assertEquals(7, result.passcodeAutoMisses)
    }

    @Test
    fun `compose reads passcodeAutoMisses back on cold start`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val prefs = mutablePreferencesOf(AppStateKeys.PASSCODE_AUTO_MISSES to 20)
        val composed = UserDataFieldRouter.compose(auth = auth, appState = prefs, fallback = UserData())
        assertEquals(20, composed.passcodeAutoMisses, "a spent budget must survive a process restart")
    }

    @Test
    fun `compose falls back to zero when the key is absent`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val composed = UserDataFieldRouter.compose(auth = auth, appState = mutablePreferencesOf(), fallback = UserData())
        assertEquals(0, composed.passcodeAutoMisses)
    }
}
