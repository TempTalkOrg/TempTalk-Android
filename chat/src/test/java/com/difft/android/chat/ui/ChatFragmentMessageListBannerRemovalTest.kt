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




@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatFragmentMessageListBannerRemovalTest {

    @Test
    fun `T2-27 recyclerView top constraint anchors to parent after banner removal`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val binding = ChatFragmentMessageListBinding.inflate(LayoutInflater.from(activity))

        val params = binding.recyclerViewMessage.layoutParams as ConstraintLayout.LayoutParams

        assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.topToTop)
        assertEquals(-1, params.topToBottom) 
    }
}
