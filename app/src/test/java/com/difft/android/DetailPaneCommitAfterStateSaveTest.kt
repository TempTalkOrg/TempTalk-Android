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
















@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DetailPaneCommitAfterStateSaveTest {

    



    private fun hostPastStateSave(): Pair<FragmentActivity, Fragment> {
        val controller: ActivityController<FragmentActivity> =
            Robolectric.buildActivity(FragmentActivity::class.java)
                .create().start().resume()
        val activity = controller.get()
        
        activity.supportFragmentManager.beginTransaction()
            .add(Fragment(), "detail_tab_0")
            .commitNow()
        
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
        
        activity.supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commitAllowingStateLoss()
    }
}
