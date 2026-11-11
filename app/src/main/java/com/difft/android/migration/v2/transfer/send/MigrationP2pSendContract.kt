package com.difft.android.migration.v2.transfer.send

import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler









sealed interface MigrationP2pSendState {

    
    data object Idle : MigrationP2pSendState

    
    data object Scanning : MigrationP2pSendState

    
    



    data class Ready(
        val alias: String,
        val estimate: ExportEstimate? = null,
        val includeAttachments: Boolean = true,
        




        val spaceShortfallBytes: Long = 0,
    ) : MigrationP2pSendState {

        
        val spaceSufficient: Boolean get() = spaceShortfallBytes <= 0

        
        val canDropAttachments: Boolean get() = estimate?.offersAttachmentChoice == true
    }

    




    data object Authenticating : MigrationP2pSendState

    




    data class Blocked(val reason: String) : MigrationP2pSendState

    
    data object Preparing : MigrationP2pSendState

    



    data object AwaitingPeerConfirm : MigrationP2pSendState

    
    data class Transferring(val progress: Float, val totalBytes: Long = 0L) : MigrationP2pSendState

    




    data object AwaitingVerdict : MigrationP2pSendState

    





    data class Unconfirmed(val phase: Phase) : MigrationP2pSendState {
        enum class Phase { WAITING, IDLE, CHECKING, UNREACHABLE }
    }

    




    data object Done : MigrationP2pSendState

    






    data class Failed(
        val reason: String,
        val retryEnabled: Boolean = true,
        val reconcileOutcome: MigrationHandoffReconciler.Outcome? = null,
    ) : MigrationP2pSendState {

        
        val reconciling: Boolean get() = !retryEnabled && reconcileOutcome == null
    }

    
    data object Cancelled : MigrationP2pSendState
}

sealed interface MigrationP2pSendIntent {
    data object LaunchScan : MigrationP2pSendIntent
    data class QrScanned(val raw: String) : MigrationP2pSendIntent

    
    data object ScanCancelled : MigrationP2pSendIntent
    data object Send : MigrationP2pSendIntent
    data object Cancel : MigrationP2pSendIntent

    
    data object Close : MigrationP2pSendIntent

    
    data class SetIncludeAttachments(val include: Boolean) : MigrationP2pSendIntent

    
    data object ReturnToPeer : MigrationP2pSendIntent

    
    data object AuthSucceeded : MigrationP2pSendIntent

    
    data object AuthFailed : MigrationP2pSendIntent

    
    data object RecheckPeer : MigrationP2pSendIntent

    
    data object RecheckVerdict : MigrationP2pSendIntent

    
    data object DiscardUnconfirmed : MigrationP2pSendIntent

    
    data object RescanLink : MigrationP2pSendIntent

    
    data object OpenStorageSettings : MigrationP2pSendIntent

    
    data object OpenUpdate : MigrationP2pSendIntent

    




    data object RefreshPairing : MigrationP2pSendIntent
}

sealed interface MigrationP2pSendEffect {
    
    data object ReturnToPeerApp : MigrationP2pSendEffect

    
    data object BringPeerToFront : MigrationP2pSendEffect

    data object LaunchScanActivity : MigrationP2pSendEffect

    
    data object RescanLink : MigrationP2pSendEffect

    
    data object RequestAppLockAuth : MigrationP2pSendEffect

    
    data object RequestDeviceCredentialAuth : MigrationP2pSendEffect
    data class Toast(val msg: String) : MigrationP2pSendEffect
    data object Finish : MigrationP2pSendEffect
    data object OpenStorageSettings : MigrationP2pSendEffect
    data object OpenUpdatePage : MigrationP2pSendEffect

    
    data object RequestFreshPairing : MigrationP2pSendEffect
}
