package com.difft.android.migration.v2.handoff







sealed interface MigrationHandoffEntryState {

    
    data object Loading : MigrationHandoffEntryState

    
    data object Offer : MigrationHandoffEntryState

    




    data object PeerTooOld : MigrationHandoffEntryState

    
    data class Blocked(val reason: HandoffEntryBlockReason) : MigrationHandoffEntryState
}


enum class HandoffEntryBlockReason {
    
    TARGET_NOT_EMPTY,

    
    PEER_NO_ACCOUNT,
}

sealed interface MigrationHandoffEntryIntent {
    



    data class Start(val autoStart: Boolean = false) : MigrationHandoffEntryIntent

    data object ChooseHandoff : MigrationHandoffEntryIntent

    
    data object ChooseNormalLogin : MigrationHandoffEntryIntent

    data object Back : MigrationHandoffEntryIntent
}

sealed interface MigrationHandoffEntryEffect {
    data object StartReceive : MigrationHandoffEntryEffect
    data object OpenLogin : MigrationHandoffEntryEffect
    data object Finish : MigrationHandoffEntryEffect
}





enum class HandoffOffer { NONE, OFFER, PEER_TOO_OLD }
