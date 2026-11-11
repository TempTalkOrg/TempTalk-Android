package com.difft.android.migration.v2.transfer.link

import com.difft.android.base.migration.LinkMigrationState
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class LinkLoginStateTest {

    private val compound = QrPayload(ip = "192.168.1.23", port = 47821, fp = "AA", pin = "482913", alias = "TempTalk Desktop", transferKey = "a2V5")

    @Test
    fun `pairing info makes a compound page regardless of state`() {
        val state = deriveLinkLoginState(compound, null)
        assertTrue(state is LinkLoginState.Compound)
        assertEquals("TempTalk Desktop", (state as LinkLoginState.Compound).alias)
        assertTrue(deriveLinkLoginState(compound, LinkMigrationState.UNAVAILABLE) is LinkLoginState.Compound)
    }

    @Test
    fun `a payload without a transfer key is not pairing info`() {
        val state = deriveLinkLoginState(compound.copy(transferKey = null), null)
        assertEquals(LinkLoginState.Plain(PlainKind.LEGACY_DESKTOP), state)
    }

    @Test
    fun `plain codes map by state and default to a legacy desktop`() {
        assertEquals(LinkLoginState.Plain(PlainKind.UNAVAILABLE), deriveLinkLoginState(null, LinkMigrationState.UNAVAILABLE))
        assertEquals(LinkLoginState.Plain(PlainKind.LEGACY_DESKTOP), deriveLinkLoginState(null, null))
    }

    @Test
    fun `a login syncs only when wanted, reachable and not refused`() {
        val base = LinkLoginState.Compound(alias = "d", reachable = true)
        assertTrue(base.willSync)
        assertFalse(base.copy(syncEnabled = false).willSync)
        assertFalse(base.copy(reachable = false).willSync)
        assertFalse(base.copy(reachable = null).willSync)
        assertFalse(base.copy(gateReject = "environment-mismatch").willSync)
        
        assertFalse(base.copy(peerState = LinkPeerState.SAME_ACCOUNT).willSync)
        assertTrue(base.copy(peerState = LinkPeerState.DIFFERENT_ACCOUNT).willSync)
    }

    @Test
    fun `attachments become a choice only above one gibibyte`() {
        val threshold = ExportEstimate.ATTACHMENT_TOGGLE_MIN_BYTES
        assertFalse(ExportEstimate(recordCount = 1, recordBytes = 10, attachmentBytes = threshold, voiceNoteBytes = 0).offersAttachmentChoice)
        assertTrue(ExportEstimate(recordCount = 1, recordBytes = 10, attachmentBytes = threshold + 1, voiceNoteBytes = 0).offersAttachmentChoice)
    }
}
