package com.difft.android.migration.v2.transfer

import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.MigrationException
import com.difft.android.p2p.android.client.PrepareUploadReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException




class MigrationTransferFailureReasonTest {

    @Test
    fun `every shape of an unreachable peer is recognised`() {
        assertTrue(ConnectException("Connection refused").isPeerUnreachable())
        assertTrue(
            SocketTimeoutException("failed to connect to /192.168.1.68 (port 53317) from /10.10.0.85 (port 39640) after 10000ms")
                .isPeerUnreachable(),
        )
        assertTrue(NoRouteToHostException("No route to host").isPeerUnreachable())
        assertTrue(UnknownHostException("desktop.local").isPeerUnreachable())
        
        assertTrue(SocketException("Connection reset").isPeerUnreachable())
        assertTrue(ProtocolException("unexpected end of stream").isPeerUnreachable())
        assertTrue(EOFException().isPeerUnreachable())
        
        assertTrue(SSLException("Write error: ssl=0x7: I/O error during system call, Broken pipe").isPeerUnreachable())
    }

    @Test
    fun `other failures keep their own reason`() {
        assertFalse(IllegalStateException("prepare-download failed 500").isPeerUnreachable())
        assertFalse(java.io.IOException("closed").isPeerUnreachable())
        
        assertFalse(SSLHandshakeException("fingerprint mismatch").isPeerUnreachable())
        assertFalse(SSLPeerUnverifiedException("pin").isPeerUnreachable())
    }

    
    @Test
    fun `failure reasons speak the classifier's vocabulary`() {
        assertEquals(PrepareUploadReasons.PEER_UNREACHABLE, failureReason(SocketException("Connection reset")))
        
        assertEquals(
            "failed: ${MigrationError.ConnectionLost.code}",
            failureReason(MigrationException.SinkInterrupted("sink closed", java.io.IOException("source is closed"))),
        )
        assertEquals("failed: ${MigrationError.EncryptionFailed.code}", failureReason(MigrationException.EncryptionFailed("cipher")))
        assertEquals("prepare-download failed 500", failureReason(IllegalStateException("prepare-download failed 500")))
    }

    
    @Test
    fun `a connection the sender dropped under the receiver reads as the link going`() {
        assertEquals(
            "failed: ${MigrationError.ConnectionLost.code}",
            receiverFailureReason("\n not found: limit=0 content=…", EOFException("\n not found: limit=0 content=…")),
        )
        assertEquals(
            "failed: ${MigrationError.ConnectionLost.code}",
            receiverFailureReason("Read timed out", SocketTimeoutException("Read timed out")),
        )
        
        assertEquals("403 forbidden: account-mismatch", receiverFailureReason("403 forbidden: account-mismatch", null))
    }

    
    @Test
    fun `only a live sender accepts a late callback`() {
        assertTrue(TransferState.Transferring(TransferState.Role.SENDER, 0.4f).acceptsSenderCallback())
        assertTrue(TransferState.AwaitingPeerConfirm.acceptsSenderCallback())
        assertFalse(TransferState.Cancelled.acceptsSenderCallback())
        assertFalse(TransferState.AwaitingVerdict.acceptsSenderCallback())
        assertFalse(TransferState.Failed("x").acceptsSenderCallback())
        assertFalse(TransferState.Transferring(TransferState.Role.RECEIVER, 0.4f).acceptsSenderCallback())
    }

    



    @Test
    fun `promoting ignores a link failure but never the import's own verdict`() {
        assertFalse(TransferState.Promoting.acceptsReceiverFailure(fromOutcome = false))
        assertTrue(TransferState.Promoting.acceptsReceiverFailure(fromOutcome = true))

        
        val receiving = TransferState.Transferring(TransferState.Role.RECEIVER, 0.4f)
        assertTrue(receiving.acceptsReceiverFailure(fromOutcome = false))
        assertTrue(receiving.acceptsReceiverFailure(fromOutcome = true))

        
        assertFalse(TransferState.Promoted.acceptsReceiverFailure(fromOutcome = true))
        assertFalse(TransferState.Cancelled.acceptsReceiverFailure(fromOutcome = true))
        assertFalse(TransferState.Failed("x").acceptsReceiverFailure(fromOutcome = true))
    }
}
