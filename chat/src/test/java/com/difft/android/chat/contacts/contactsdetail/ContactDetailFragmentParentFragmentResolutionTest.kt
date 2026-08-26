package com.difft.android.chat.contacts.contactsdetail

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9b/M10b (issue #1127, family E): pins the real AndroidX mechanism
 * [ContactDetailFragment.isPopupMode] depends on (`parentFragment is BottomSheetDialogFragment`)
 * using bare double fragments — the real `@AndroidEntryPoint` `ContactDetailFragment` can't be
 * launched without a Hilt harness (mirrors `LinkedDevicesBackButtonTest`'s
 * `isInDualPaneMode` framework-fact test exactly).
 *
 * M9b reproduces `ContactDetailBottomSheetDialogFragment`'s attachment shape
 * (`childFragmentManager`) -> `parentFragment is BottomSheetDialogFragment == true`.
 * M10b reproduces the shape shared by `ContactDetailActivity` (path 1) and
 * `IndexActivity.replaceDetailFragmentForCurrentTab` (path 3) -> both attach directly via
 * `supportFragmentManager`, never a `childFragmentManager` -> `false`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactDetailFragmentParentFragmentResolutionTest {

    @Test
    fun `M9b BottomSheet child fragment manager attachment resolves parentFragment as BottomSheetDialogFragment`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val bottomSheet = BottomSheetDialogFragment()
            controller.get().supportFragmentManager.beginTransaction()
                .add(bottomSheet, "bottomsheet").commitNow()

            val child = Fragment()
            bottomSheet.childFragmentManager.beginTransaction().add(child, "child").commitNow()

            assertTrue(
                child.parentFragment is BottomSheetDialogFragment,
                "a fragment attached via a BottomSheetDialogFragment's childFragmentManager must " +
                    "resolve parentFragment as a BottomSheetDialogFragment",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }

    @Test
    fun `M10b direct supportFragmentManager attachment never resolves parentFragment as BottomSheetDialogFragment`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val fragment = Fragment()
            controller.get().supportFragmentManager.beginTransaction()
                .add(fragment, "probe").commitNow()

            assertFalse(
                fragment.parentFragment is BottomSheetDialogFragment,
                "a fragment attached directly via supportFragmentManager (ContactDetailActivity / " +
                    "IndexActivity dual-pane) must never resolve parentFragment as a " +
                    "BottomSheetDialogFragment",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
