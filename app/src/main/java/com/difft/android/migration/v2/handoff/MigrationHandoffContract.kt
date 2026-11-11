package com.difft.android.migration.v2.handoff

import com.difft.android.base.migration.PeerApps












object MigrationHandoffContract {

    
    const val PROTOCOL_VERSION = 1

    
    const val MIN_SUPPORTED_PEER_PROTOCOL = 1

    
    const val AUTHORITY_SUFFIX = PeerApps.STATUS_AUTHORITY_SUFFIX

    
    const val METHOD = "migration.handoff.v1"

    
    
    const val KEY_QUERY = "query"
    const val QUERY_STATUS = "status"
    const val QUERY_HANDOFF = "handoff"

    



    const val QUERY_CANCEL_RECEIVE = "cancelReceive"

    
    const val KEY_HANDOFF_ID = "handoffId"

    
    
    const val KEY_PROTOCOL = "protocol"

    
    const val KEY_STATE = "state"

    
    const val KEY_HANDOFF_RESULT = "handoffResult"
}






enum class HandoffState {
    
    READY,

    
    DATA_ONLY,

    
    NONE,
}


enum class HandoffResult {
    
    COMPLETED,

    
    UNKNOWN,
}
