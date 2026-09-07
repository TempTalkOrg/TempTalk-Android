package com.difft.android.chat.message

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode

/**
 * `buildForwardData` is where a forward is born, so it is where a new local identity has to be
 * minted — on BOTH branches. Re-forwarding used to hand the new message the source's own
 * ForwardContext object, which is the sharing this pins closed.
 *
 * Robolectric because the real `buildForwardData` resolves string resources for the forward preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class BuildForwardDataIdentityTest {

    @Before
    fun setUp() {
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers { "/root/attachment/${firstArg<String>()}/" }
        mockkObject(EncryptedAttachmentAccess)
        every { EncryptedAttachmentAccess.isReadable(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun attachment(localId: String, isForwardCopy: Boolean = false) = Attachment(
        id = "server-id",
        authorityId = 777L,
        contentType = "image/jpeg",
        key = byteArrayOf(1),
        size = 10,
        thumbnail = null,
        digest = byteArrayOf(2),
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.SUCCESS.code,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    private fun message(block: TextChatMessage.() -> Unit) = TextChatMessage().apply {
        id = "msg-1"
        authorId = "author"
        block()
    }

    @Test
    fun `plain attachment message becomes a forward copy with a new identity`() {
        val original = attachment("local-src")
        val source = message { attachment = original }

        val built = source.buildForwardData()!!.second
        val copy = built.forwards!!.single().attachments!!.single()

        assertNotEquals("local-src", copy.localId)
        assertTrue(copy.isForwardCopy)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
        // Source is the ORIGINAL attachment's own directory — a message's attachment is addressed
        // exactly like a forwarded one.
        assertEquals("/root/attachment/local-src/photo.jpg", copy.forwardSourceFilePath)
        // Server identity untouched, and the original object is not mutated.
        assertEquals("server-id", copy.id)
        assertEquals(AttachmentStatus.SUCCESS.code, original.status)
    }

    @Test
    fun `re-forward deep-copies instead of reusing the source ForwardContext`() {
        val nestedLeaf = attachment("local-nested", isForwardCopy = true)
        val topLeaf = attachment("local-top", isForwardCopy = true)
        val sourceContext = ForwardContext(
            listOf(
                Forward(
                    1L, 0, false, "author", "text", listOf(topLeaf),
                    listOf(Forward(2L, 0, false, "author", null, listOf(nestedLeaf), null, null)),
                    null
                )
            ),
            false
        )
        val source = message { forwardContext = sourceContext }

        val built = source.buildForwardData()!!.second

        assertNotEquals(sourceContext, built)
        val copiedTop = built.forwards!!.single().attachments!!.single()
        val copiedNested = built.forwards!!.single().forwards!!.single().attachments!!.single()
        assertNotEquals("local-top", copiedTop.localId)
        assertNotEquals("local-nested", copiedNested.localId)
        assertNotEquals(copiedTop.localId, copiedNested.localId)
        // Source tree untouched.
        assertEquals("local-top", topLeaf.localId)
        assertEquals("local-nested", nestedLeaf.localId)
    }

    @Test
    fun `re-forward resolves the copy source from the source copy's own directory`() {
        val source = message {
            forwardContext = ForwardContext(
                listOf(Forward(1L, 0, false, "author", null, listOf(attachment("local-src", isForwardCopy = true)), null, null)),
                false
            )
        }

        val copy = source.buildForwardData()!!.second.forwards!!.single().attachments!!.single()

        assertEquals("/root/attachment/local-src/photo.jpg", copy.forwardSourceFilePath)
    }

    @Test
    fun `confidential source gets no local copy source on either branch`() {
        val plain = message {
            attachment = attachment("local-src")
            mode = Mode.CONFIDENTIAL_VALUE
        }
        val reForward = message {
            forwardContext = ForwardContext(
                listOf(Forward(1L, 0, false, "author", null, listOf(attachment("local-src", isForwardCopy = true)), null, null)),
                false
            )
            mode = Mode.CONFIDENTIAL_VALUE
        }

        assertNull(plain.buildForwardData()!!.second.forwards!!.single().attachments!!.single().forwardSourceFilePath)
        assertNull(reForward.buildForwardData()!!.second.forwards!!.single().attachments!!.single().forwardSourceFilePath)
    }
}
