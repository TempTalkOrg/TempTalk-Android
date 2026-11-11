package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.MigrationHandshake











fun interface MigrationHandshakeValidator {

    





    fun validate(handshake: MigrationHandshake): MigrationGateDecision
}


sealed interface MigrationGateDecision {
    
    data object Accept : MigrationGateDecision

    
    data class Reject(val reason: MigrationRejectReason) : MigrationGateDecision
}





enum class MigrationRejectReason(val wire: String) {
    
    ENV_MISMATCH("environment-mismatch"),

    
    ACCOUNT_MISMATCH("account-mismatch"),

    
    VERSION_INCOMPATIBLE("version-incompatible"),

    





    IDENTITY_MISMATCH("identity-mismatch"),

    




    HANDOFF_UNSUPPORTED("handoff-unsupported"),

    
    HANDOFF_DISABLED("handoff-disabled"),

    
    TARGET_NOT_EMPTY("target-not-empty"),

    



    PAIR_TOKEN("pair-token"),

    



    INSUFFICIENT_STORAGE("insufficient-storage"),
}
