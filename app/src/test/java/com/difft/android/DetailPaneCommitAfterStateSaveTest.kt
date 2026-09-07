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
 * Framework-assumption test for the restoreDetailFragmentsState state-loss fix
 * (per docs/claude/testing-patterns.md § Framework Assumption Tests).
 *
 * ## Coverage contract — read before relying on this test
 *
 * This test pins the two framework instruments the one-line fix in
 * IndexActivity.restoreDetailFragmentsState() (transaction.commit() ->
 * commitAllowingStateLoss()) depends on:
 *   - CRASH instrument: a show()/hide() FragmentTransaction committed AFTER
 *     onSaveInstanceState() throws IllegalStateException with plain commit().
 *   - FIX instrument: the same transaction does NOT throw with
 *     commitAllowingStateLoss().
 *
 * What it guarantees: the fix is not dead code — the framework behaves exactly as
 * the fix assumes. Per testing-patterns.md § Framework Assumption Tests, a wrong
 * assumption here would make the fix ineffective while other tests still passed;
 * this test rules that out.
 *
 * ## What this test does NOT cover — no revert-detection
 *
 * It deliberately does NOT exercise IndexActivity.restoreDetailFragmentsState.
 * IndexActivity is a Hilt @AndroidEntryPoint whose onCreate needs test modules the
 * :app module lacks. Because this test drives a bare FragmentActivity host and never
 * references the production call site, it will NOT fail if the IndexActivity fix line
 * is reverted from commitAllowingStateLoss() back to commit() — both @Test methods
 * would still pass. Revert-detection needs real-IndexActivity integration coverage,
 * blocked on that Hilt test-infra buildout.
 *
 * The mechanic under test — FragmentManager.checkStateLoss() on commit — is
 * qualifier- and IndexActivity-independent, so a bare FragmentActivity host
 * reproduces it exactly.
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
