package com.difft.android.chat.ui.messageaction

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals






@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34])
class MessageActionPopupWindowBackgroundTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val globalConfigsManager: IGlobalConfigsManager = mockk {
        every { getNewGlobalConfigs() } returns null
    }

    private val noopCallbacks = object : MessageActionPopup.Callbacks {
        override fun onReactionSelected(emoji: String, isRemove: Boolean) {}
        override fun onMoreEmojiClick() {}
        override fun onActionSelected(action: MessageAction.Type) {}
        override fun onDismiss() {}
    }

    @Test
    fun `M25 show and dismiss never write the host window background`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContentView(FrameLayout(activity))
            val sentinel = ColorDrawable(Color.RED)
            activity.window.setBackgroundDrawable(sentinel)

            val anchorView = FrameLayout(activity)
            activity.addContentView(anchorView, FrameLayout.LayoutParams(100, 100))

            val message = TextChatMessage().apply {
                id = "msg-1"
                authorId = "author-1"
                isMine = true
                message = "hello"
            }
            val builder = MessageActionConfigBuilder(globalConfigsManager, translateSupported = true)
            val config = builder.build(
                message = message,
                mostUseEmojis = listOf("👍"),
                isForForward = false,
                isSaved = false,
                anchorBounds = Rect(0, 0, 100, 100)
            )

            val popup = MessageActionPopup(activity)
            popup.show(anchorView = anchorView, config = config, callbacks = noopCallbacks)
            shadowOf(Looper.getMainLooper()).idle()

            var background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "MessageActionPopup.show() must never touch the host window background " +
                    "(restoreWindowBackground parameter removed — applyWindowBackground = false is a pure skip)",
            )

            popup.dismiss()
            shadowOf(Looper.getMainLooper()).idle()

            background = activity.window.decorView.background as ColorDrawable
            assertEquals(
                Color.RED,
                background.color,
                "MessageActionPopup.dismiss() must still leave the host window background untouched",
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
