package com.difft.android.migration.v2.transfer.login

import android.graphics.Bitmap






sealed interface MigrationP2pReceiveState {
    
    data object Starting : MigrationP2pReceiveState

    





    data class WaitingForPeer(
        val qrBitmap: Bitmap?,
        val sameDevice: Boolean = false,
        
        val refreshed: Boolean = false,
    ) : MigrationP2pReceiveState

    



    data class Receiving(val progress: Float, val peerAlias: String? = null, val totalBytes: Long = 0L) : MigrationP2pReceiveState

    



    data class AwaitingDesktopConfirm(val peerAlias: String? = null) : MigrationP2pReceiveState

    
    data class AwaitingDesktopConnect(val peerAlias: String? = null) : MigrationP2pReceiveState

    



    data object Verifying : MigrationP2pReceiveState

    
    data object Promoted : MigrationP2pReceiveState

    





    data object Activated : MigrationP2pReceiveState

    



    data object CommittedUnconfirmed : MigrationP2pReceiveState

    
    data class Error(val reason: String) : MigrationP2pReceiveState

    
    data object Skipped : MigrationP2pReceiveState
}

sealed interface MigrationP2pReceiveIntent {
    data object Start : MigrationP2pReceiveIntent
    data object Retry : MigrationP2pReceiveIntent

    
    data object RelaunchLegacySender : MigrationP2pReceiveIntent

    
    data object ScanPeerQr : MigrationP2pReceiveIntent

    
    data class PeerQrScanned(val raw: String) : MigrationP2pReceiveIntent

    
    data object ScanCancelled : MigrationP2pReceiveIntent

    
    data object Cancel : MigrationP2pReceiveIntent

    
    data object Leave : MigrationP2pReceiveIntent

    



    data object OpenUpdate : MigrationP2pReceiveIntent
}


sealed interface MigrationP2pReceiveEffect {
    
    data class LaunchLegacySender(val rawPayload: String) : MigrationP2pReceiveEffect

    
    data object LaunchScanner : MigrationP2pReceiveEffect

    
    data object OpenUpdatePage : MigrationP2pReceiveEffect

    data class Toast(val msg: String) : MigrationP2pReceiveEffect
}
