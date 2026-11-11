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
import kotlin.test.assertFalse
import kotlin.test.assertTrue








@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncedContactsV5RouterTest {

    
    @Test
    fun `diff emits BooleanChange for syncedContactsV5`() {
        val changes = UserDataFieldRouter.diff(
            old = UserData(syncedContactsV5 = false),
            new = UserData(syncedContactsV5 = true),
        ).appStateChanges

        val change = changes.filterIsInstance<UserDataFieldRouter.AppStateChange.BooleanChange>()
            .single { it.key == AppStateKeys.SYNCED_CONTACTS_V5 }
        assertTrue(change.value)
    }

    
    @Test
    fun `apply copies syncedContactsV5 into snapshot`() {
        val result = UserDataFieldRouter.applyAppStateChangeToSnapshot(
            base = UserData(syncedContactsV5 = false),
            key = AppStateKeys.SYNCED_CONTACTS_V5,
            value = true,
        )
        assertTrue(result.syncedContactsV5)
    }

    



    @Test
    fun `compose falls back to default false when v5 key absent`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val composed = UserDataFieldRouter.compose(
            auth = auth,
            appState = mutablePreferencesOf(), 
            fallback = UserData(),
        )
        assertFalse(composed.syncedContactsV5)
    }

    
    @Test
    fun `compose reads syncedContactsV5 when key present`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val prefs = mutablePreferencesOf(AppStateKeys.SYNCED_CONTACTS_V5 to true)
        val composed = UserDataFieldRouter.compose(auth = auth, appState = prefs, fallback = UserData())
        assertTrue(composed.syncedContactsV5)
    }

    
    @Test
    fun `diff emits nothing for syncedContactsV5 when unchanged`() {
        val changes = UserDataFieldRouter.diff(
            old = UserData(syncedContactsV5 = true),
            new = UserData(syncedContactsV5 = true),
        ).appStateChanges
        assertEquals(
            0,
            changes.filterIsInstance<UserDataFieldRouter.AppStateChange.BooleanChange>()
                .count { it.key == AppStateKeys.SYNCED_CONTACTS_V5 },
        )
    }
}
