package com.difft.android.chat.contacts

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.fragment.app.FragmentFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OldContactsPagerStateInvariantTest {

    @Test fun `the old contacts pager pages still instantiate by name and show nothing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(
            "com.difft.android.chat.contacts.contactsgroup.GroupsFragment",
            "com.difft.android.chat.contacts.contactsall.ContactsAllFragment",
        ).forEach { name ->
            val fragment = FragmentFactory().instantiate(javaClass.classLoader!!, name)
            assertNull(fragment.onCreateView(LayoutInflater.from(context), FrameLayout(context), null), name)
        }
    }
}
