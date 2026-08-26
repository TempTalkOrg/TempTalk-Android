package com.difft.android.chat.ui

import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.databinding.ChatFragmentMessageListBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-27 — banner removal from `chat_fragment_message_list.xml`. The `tv_privacy_banner` id no
 * longer exists in resources at all (the whole
 * node was deleted, not just hidden) — any lingering reference to it anywhere in production code
 * would already fail to compile ([ChatMessageListFragment.kt]'s `isFriend`/`initPrivacyBanner`/
 * `updatePrivacyBanner` were deleted outright in the same change), so the remaining regression
 * surface this test guards is the RecyclerView's constraint: it must anchor directly to the
 * parent's top, not to the removed banner view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatFragmentMessageListBannerRemovalTest {

    @Test
    fun `T2-27 recyclerView top constraint anchors to parent after banner removal`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val binding = ChatFragmentMessageListBinding.inflate(LayoutInflater.from(activity))

        val params = binding.recyclerViewMessage.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.topToTop)
        assertEquals(-1, params.topToBottom) // no longer constrained below the (deleted) banner
    }
}
