package com.difft.android

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Framework-assumption test (per docs/agents/testing-patterns.md § Framework Assumption Tests)
 * for the detail-pane commit policy.
 *
 * Pins the two framework instruments behind it:
 *   - a show()/hide() FragmentTransaction committed AFTER onSaveInstanceState() throws
 *     IllegalStateException with plain commit(), which is why `PaneNavigator` queues every stack
 *     change while state is saved and replays it on start/resume (`PaneNavigatorTest` covers that
 *     queue against the real navigator);
 *   - the same transaction does NOT throw with commitAllowingStateLoss(), the escape hatch the
 *     navigator deliberately does not use.
 *
 * The mechanic under test, FragmentManager.checkStateLoss() on commit, is qualifier- and
 * IndexActivity-independent, so a bare FragmentActivity host reproduces it exactly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DetailPaneCommitAfterStateSaveTest {

    /**
     * Drives a bare FragmentActivity past onSaveInstanceState with one added fragment,
     * asserting the state-saved precondition before returning the fragment to hide.
     */
    private fun hostPastStateSave(): Pair<FragmentActivity, Fragment> {
        val controller: ActivityController<FragmentActivity> =
            Robolectric.buildActivity(FragmentActivity::class.java)
                .create().start().resume()
        val activity = controller.get()
        // Attach a fragment while state is NOT saved (commitNow executes synchronously).
        activity.supportFragmentManager.beginTransaction()
            .add(Fragment(), "detail_tab_0")
            .commitNow()
        // Drive past onSaveInstanceState -> FragmentManager.mStateSaved = true.
        controller.saveInstanceState(Bundle())
        assertTrue(
            "precondition: FragmentManager must report state saved after saveInstanceState()",
            activity.supportFragmentManager.isStateSaved
        )
        val fragment = activity.supportFragmentManager.findFragmentByTag("detail_tab_0")!!
        return activity to fragment
    }

    @Test
    fun `plain commit after onSaveInstanceState throws IllegalStateException`() {
        val (activity, fragment) = hostPastStateSave()
        try {
            activity.supportFragmentManager.beginTransaction()
                .hide(fragment)
                .commit()
            fail("plain commit() after onSaveInstanceState should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "expected checkStateLoss violation, got: ${e.message}",
                e.message?.contains("Can not perform this action after onSaveInstanceState") == true
            )
        }
    }

    @Test
    fun `commitAllowingStateLoss after onSaveInstanceState does not throw`() {
        val (activity, fragment) = hostPastStateSave()
        // Must NOT throw — this is the fix instrument. No try/catch: any throw fails the test.
        activity.supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }
}
