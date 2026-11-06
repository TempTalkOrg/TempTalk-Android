package com.difft.android.chat.ui

import com.difft.android.chat.message.TextChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode

/**
 * Decision table for the burns a confidential viewer owes when recreation closes it (#1440). It
 * must match the close handlers: voice/text burn only once revealed, an attachment once opened,
 * never an own or non-confidential message.
 */
class ConfidentialPendingBurnTest {

    private fun message(id: String, isMine: Boolean, confidential: Boolean) = TextChatMessage().apply {
        this.id = id
        this.isMine = isMine
        this.mode = if (confidential) Mode.CONFIDENTIAL_VALUE else Mode.NORMAL_VALUE
    }

    private enum class Kind { AUDIO, TEXT, ATTACHMENT }

    private fun idsFor(kind: Kind, isMine: Boolean, confidential: Boolean, consumed: Boolean): List<String> {
        val m = message("m", isMine, confidential)
        return when (kind) {
            Kind.AUDIO -> pendingConfidentialBurnIds(m, null, null, consumed)
            Kind.TEXT -> pendingConfidentialBurnIds(null, m, null, consumed)
            Kind.ATTACHMENT -> pendingConfidentialBurnIds(null, null, m, consumed)
        }
    }

    @Test
    fun `only messages the close handlers would burn are returned`() {
        for (kind in Kind.values()) {
            for (isMine in listOf(false, true)) {
                for (confidential in listOf(false, true)) {
                    for (consumed in listOf(false, true)) {
                        val revealed = kind == Kind.ATTACHMENT || consumed
                        val expected = if (!isMine && confidential && revealed) listOf("m") else emptyList()
                        assertEquals(
                            "kind=$kind isMine=$isMine confidential=$confidential consumed=$consumed",
                            expected,
                            idsFor(kind, isMine, confidential, consumed),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `nothing open saves nothing`() {
        assertEquals(emptyList<String>(), pendingConfidentialBurnIds(null, null, null, viewerConsumed = true))
    }
}
