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

/**
 * Pins the [UserDataFieldRouter] wiring for the renamed `syncedContactsV5` upgrade-resync flag
 * (P1-04): diff → BooleanChange, applyAppStateChangeToSnapshot → copy, compose read → fallback to
 * the UserData default (false) when the new `synced_contacts_v5` key is absent. The absent-key
 * fallback is the whole mechanism: every install upgrading from the v4 key reads false once, which
 * drives one full re-pull that seeds publicAccountType.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncedContactsV5RouterTest {

    /** diff: flipping the flag emits exactly one BooleanChange for the v5 key. */
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

    /** apply: the v5 key writes through to the UserData snapshot. */
    @Test
    fun `apply copies syncedContactsV5 into snapshot`() {
        val result = UserDataFieldRouter.applyAppStateChangeToSnapshot(
            base = UserData(syncedContactsV5 = false),
            key = AppStateKeys.SYNCED_CONTACTS_V5,
            value = true,
        )
        assertTrue(result.syncedContactsV5)
    }

    /**
     * read: an absent v5 key falls back to the UserData default false — the upgrade-resync trigger
     * (every install upgrading from the v4 key, T13 semantics).
     */
    @Test
    fun `compose falls back to default false when v5 key absent`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val composed = UserDataFieldRouter.compose(
            auth = auth,
            appState = mutablePreferencesOf(), // v5 key never written (every upgrading install)
            fallback = UserData(),
        )
        assertFalse(composed.syncedContactsV5)
    }

    /** read: a present v5 key wins over the default (post-successful-sync steady state). */
    @Test
    fun `compose reads syncedContactsV5 when key present`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val prefs = mutablePreferencesOf(AppStateKeys.SYNCED_CONTACTS_V5 to true)
        val composed = UserDataFieldRouter.compose(auth = auth, appState = prefs, fallback = UserData())
        assertTrue(composed.syncedContactsV5)
    }

    /** diff: no change when the flag is unchanged (no spurious write). */
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
