package com.difft.android.call

import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.data.AvatarData
import org.difft.app.database.test.builders.buildContactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The binder behind the XML meeting-invite rows: one avatar View per ViewHolder, rebound on every
 * bind — including an equals-equal rebind, because a load that AvatarView dropped while detached
 * needs the retry. It must keep the letter and letter size the per-bind View factory produced for
 * each avatar diameter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallAvatarViewBinderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `name-or-uid avatar at 48dp keeps the 22dp letter`() {
        val binder = buildCallAvatarViewBinder(context, avatarSizeDp = 48)

        binder.bind(AvatarData.FromNameOrUid("Alice", "+123.1"))

        assertEquals("A", letterOf(binder).text.toString())
        assertEquals(spToPx(22f), letterOf(binder).textSize, 0.5f)
    }

    @Test
    fun `name-or-uid avatar at 32dp scales the letter to 15dp`() {
        val binder = buildCallAvatarViewBinder(context, avatarSizeDp = 32)

        binder.bind(AvatarData.FromNameOrUid("Alice", "+123.1"))

        assertEquals(spToPx(15f), letterOf(binder).textSize, 0.5f)
    }

    @Test
    fun `contactor avatar renders the contact letter`() {
        val binder = buildCallAvatarViewBinder(context, avatarSizeDp = 48)

        binder.bind(AvatarData.FromContactor(buildContactor(id = "+12312345678", name = "Bob")))

        assertEquals("B", letterOf(binder).text.toString())
    }

    @Test
    fun `rebinding another participant reuses the same View`() {
        val binder = buildCallAvatarViewBinder(context, avatarSizeDp = 48)
        binder.bind(AvatarData.FromContactor(buildContactor(id = "+12312345678", name = "Bob")))
        val first = binder.view

        binder.bind(AvatarData.FromNameOrUid("Zoe", "+999"))

        assertSame("a rebind must not rebuild the View", first, binder.view)
        assertEquals("Z", letterOf(binder).text.toString())
    }

    @Test
    fun `an equals-equal rebind re-applies the avatar`() {
        // AvatarView drops an image download that finishes while detached and never retries on
        // its own; re-applying on every bind (as the XML rows do) is that retry.
        val binder = buildCallAvatarViewBinder(context, avatarSizeDp = 48)
        binder.bind(AvatarData.FromNameOrUid("Alice", "+123.1"))
        // Written straight onto the hosted View; setAvatar must overwrite it.
        letterOf(binder).text = SENTINEL

        binder.bind(AvatarData.FromNameOrUid("Alice", "+123.1"))

        assertEquals("A", letterOf(binder).text.toString())
    }

    private fun letterOf(binder: AvatarViewBinder): TextView =
        binder.view.findViewById(com.difft.android.chat.R.id.tv_avatar)

    private fun spToPx(sp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics,
    )

    private companion object {
        const val SENTINEL = "ZZ"
    }
}
