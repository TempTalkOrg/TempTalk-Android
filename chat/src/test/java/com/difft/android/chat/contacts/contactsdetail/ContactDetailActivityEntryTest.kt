package com.difft.android.chat.contacts.contactsdetail

import androidx.fragment.app.FragmentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactDetailActivityEntryTest {
    @Test fun `startActivity opens the independent card host instead of a sheet on the caller`() {
        val caller = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        ContactDetailActivity.startActivity(caller, "uid", sourceType = "RANDOM_CODE")
        val started = shadowOf(caller).nextStartedActivity
        assertEquals(ContactDetailActivity::class.java.name, started.component?.className)
        assertTrue(caller.supportFragmentManager.fragments.none { it is ContactDetailBottomSheetDialogFragment })
    }

    @Test fun `startActivity without an id opens nothing`() {
        val caller = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        ContactDetailActivity.startActivity(caller, null)
        assertNull(shadowOf(caller).nextStartedActivity)
    }
}
