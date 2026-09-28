package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins all three [UserDataFieldRouter] legs for `dualPaneRailCollapsed`. The `compose` leg is
 * the one that gets forgotten (it was missed once for `dualPaneListCollapsed` and once in the
 * first cut of this flag): without it the toggle persists for the session only and silently
 * reverts to expanded on the next process start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DualPaneRailCollapsedRouterTest {

    @Test
    fun `diff emits BooleanChange for dualPaneRailCollapsed`() {
        val changes = UserDataFieldRouter.diff(
            old = UserData(dualPaneRailCollapsed = false),
            new = UserData(dualPaneRailCollapsed = true),
        ).appStateChanges
        val change = changes.filterIsInstance<UserDataFieldRouter.AppStateChange.BooleanChange>()
            .single { it.key == AppStateKeys.DUAL_PANE_RAIL_COLLAPSED }
        assertTrue(change.value)
    }

    @Test
    fun `apply copies dualPaneRailCollapsed into snapshot`() {
        val result = UserDataFieldRouter.applyAppStateChangeToSnapshot(
            base = UserData(dualPaneRailCollapsed = false),
            key = AppStateKeys.DUAL_PANE_RAIL_COLLAPSED,
            value = true,
        )
        assertTrue(result.dualPaneRailCollapsed)
    }

    @Test
    fun `compose reads dualPaneRailCollapsed back on cold start`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val prefs = mutablePreferencesOf(AppStateKeys.DUAL_PANE_RAIL_COLLAPSED to true)
        val composed = UserDataFieldRouter.compose(auth = auth, appState = prefs, fallback = UserData())
        assertTrue(
            composed.dualPaneRailCollapsed,
            "a collapsed rail persisted to the app-state store must survive a process restart",
        )
    }

    @Test
    fun `compose falls back to expanded when the key is absent`() {
        val auth = UserAuthDataMapper.fromUserData(UserData())
        val composed = UserDataFieldRouter.compose(auth = auth, appState = mutablePreferencesOf(), fallback = UserData())
        assertFalse(composed.dualPaneRailCollapsed)
    }
}
