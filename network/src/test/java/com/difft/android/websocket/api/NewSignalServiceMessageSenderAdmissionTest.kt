package com.difft.android.websocket.api

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.assertFailsWith





class NewSignalServiceMessageSenderAdmissionTest {

    private val messagingService: NewMessagingService = mockk(relaxed = true)
    private val messageSendRepository: MessageSendRepository = mockk(relaxed = true)
    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private lateinit var sender: NewSignalServiceMessageSender

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockk<GlobalHiltEntryPoint>(relaxed = true)
        sender = NewSignalServiceMessageSender(
            messagingService = messagingService,
            maxEnvelopeSize = 65536L,
            messageEncryptor = mockk<INewMessageContentEncryptor>(relaxed = true),
            conversationManager = conversationManager,
            messageSendRepository = messageSendRepository,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    @Test
    fun `a closed send ticket is refused before any request`() = runTest {
        val ticket = openTicket(AdmissionKind.SEND).also { it.close() }

        assertFailsWith<IllegalStateException> { sendData(ticket) }

        assertNothingSent()
    }

    @Test
    fun `a receive ticket cannot admit a send`() = runTest {
        val ticket = openTicket(AdmissionKind.RECEIVE)

        assertFailsWith<IllegalStateException> { sendData(ticket) }

        assertNothingSent()
        ticket.close()
    }

    private fun openTicket(kind: AdmissionKind): MessagingTicket =
        requireNotNull(TestMessagingPause().open().gate.tryAdmit(kind, "test"))

    private suspend fun sendData(ticket: MessagingTicket) {
        val message = SignalServiceProtos.DataMessage.newBuilder().setTimestamp(1L).setBody("x").build()
        sender.sendDataMessage(For.Account("peer"), For.Account("peer"), message, null, ticket)
    }

    private fun assertNothingSent() {
        coVerify(exactly = 0) { messagingService.send(any(), any()) }
        coVerify(exactly = 0) { messagingService.sendToGroup(any(), any()) }
        coVerify(exactly = 0) { messageSendRepository.sendMessage(any(), any()) }
        coVerify(exactly = 0) { conversationManager.updatePublicKeyInfoData(any<For>()) }
    }
}
