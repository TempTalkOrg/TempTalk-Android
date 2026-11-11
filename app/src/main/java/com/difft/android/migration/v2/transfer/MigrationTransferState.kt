package com.difft.android.migration.v2.transfer









sealed interface TransferState {

    
    data object Idle : TransferState

    
    data object Starting : TransferState

    
    data class WaitingForPeer(val ip: String, val port: Int, val pin: String) : TransferState

    



    data class Transferring(
        val role: Role,
        val progress: Float,
        val peerAlias: String? = null,
        val totalBytes: Long = 0L,
    ) : TransferState

    
    data object Promoting : TransferState

    



    data object AwaitingVerdict : TransferState

    




    data object VerdictStalled : TransferState

    



    data object AwaitingPeerConfirm : TransferState

    



    data class AwaitingDesktopConfirm(val peerAlias: String? = null) : TransferState

    



    data class AwaitingDesktopConnect(val peerAlias: String? = null, val deadlineMs: Long) : TransferState

    
    data object Promoted : TransferState

    




    data object CommittedUnconfirmed : TransferState

    
    data object Sent : TransferState

    
    data class Failed(val reason: String) : TransferState

    
    data object Cancelled : TransferState

    




    data object Unknown : TransferState

    
    enum class Role { RECEIVER, SENDER }
}


fun TransferState.isTerminal(): Boolean = when (this) {
    TransferState.Promoted, TransferState.Sent, is TransferState.Failed, TransferState.Cancelled,
    TransferState.Unknown, TransferState.CommittedUnconfirmed -> true
    TransferState.Idle, TransferState.Starting, is TransferState.WaitingForPeer,
    is TransferState.Transferring, TransferState.Promoting, TransferState.AwaitingVerdict,
    TransferState.VerdictStalled, TransferState.AwaitingPeerConfirm,
    is TransferState.AwaitingDesktopConfirm, is TransferState.AwaitingDesktopConnect -> false
}









fun TransferState.acceptsReceiverFailure(fromOutcome: Boolean): Boolean =
    !isTerminal() && (this != TransferState.Promoting || fromOutcome)


fun TransferState.isAwaitingVerdict(): Boolean =
    this == TransferState.AwaitingVerdict || this == TransferState.VerdictStalled
