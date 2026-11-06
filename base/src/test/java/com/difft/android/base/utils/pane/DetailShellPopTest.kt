package com.difft.android.base.utils.pane

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Ordinary Activity boundaries; participating-shell result wiring is covered by app integration tests. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DetailShellPopTest {
    private fun activity() = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test fun `ordinary activity stops multi-pop even if it carries protocol extras`() {
        val child = activity()
        child.intent = Intent().putExtra(DetailShellPop.EXTRA_REQUEST_ID, "request")
            .putExtra(DetailShellPop.EXTRA_SUBJECT_ID, "group")
        assertTrue(DetailShellPop.finish(child, 3))
        assertTrue(child.isFinishing)
        assertNotEquals(DetailShellPop.RESULT_POP_MORE, shadowOf(child).resultCode)
        val parent = activity()
        assertFalse(DetailShellPop.consume(parent, shadowOf(child).resultCode, shadowOf(child).resultIntent))
        assertFalse(parent.isFinishing)
    }

    @Test fun `unrelated and invalid results do not close an ordinary activity`() {
        val parent = activity()
        val remaining = Intent().putExtra(DetailShellPop.EXTRA_REMAINING, 1)
        assertFalse(DetailShellPop.consume(parent, Activity.RESULT_OK, remaining))
        assertFalse(DetailShellPop.consume(parent, DetailShellPop.RESULT_POP_MORE, null))
        assertFalse(DetailShellPop.finish(parent, 0))
        assertFalse(parent.isFinishing)
    }

    @Test fun `single pop preserves an existing activity result`() {
        val child = activity()
        val result = Intent().putExtra("existing", "value")
        child.setResult(Activity.RESULT_OK, result)
        assertTrue(DetailShellPop.finish(child, 1))
        assertEquals(Activity.RESULT_OK, shadowOf(child).resultCode)
        assertEquals("value", shadowOf(child).resultIntent.getStringExtra("existing"))
    }
}
