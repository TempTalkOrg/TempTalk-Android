package com.difft.android.migration.v2.transfer.link

import com.difft.android.base.migration.LinkMigrationState
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.QrPayload






sealed interface LinkLoginState {
    
    data class Compound(
        val alias: String,
        val estimate: ExportEstimate? = null,
        val syncEnabled: Boolean = true,
        val includeAttachments: Boolean = true,
        
        val reachable: Boolean? = null,
        
        val peerState: LinkPeerState? = null,
        
        val gateReject: String? = null,
        val busy: Boolean = false,
    ) : LinkLoginState {
        
        val willSync: Boolean
            get() = syncEnabled && reachable == true && gateReject == null && peerState != LinkPeerState.SAME_ACCOUNT
    }

    
    data class Plain(val kind: PlainKind, val busy: Boolean = false) : LinkLoginState
}

enum class PlainKind {
    
    LEGACY_DESKTOP,

    
    UNAVAILABLE,
}

sealed interface LinkLoginIntent {
    data class SetSync(val on: Boolean) : LinkLoginIntent
    data class SetIncludeAttachments(val on: Boolean) : LinkLoginIntent
    data object SignIn : LinkLoginIntent

    
    data object SignInWithoutSync : LinkLoginIntent
    data object Cancel : LinkLoginIntent
}

sealed interface LinkLoginEffect {
    data class Toast(val msg: String) : LinkLoginEffect
    data object Finish : LinkLoginEffect

    




    data class OpenSend(val rawPayload: String, val includeAttachments: Boolean, val awaitPeerConfirm: Boolean) : LinkLoginEffect
}


fun deriveLinkLoginState(payload: QrPayload?, state: LinkMigrationState?): LinkLoginState = when {
    payload?.transferKey != null -> LinkLoginState.Compound(alias = payload.alias)
    state == LinkMigrationState.UNAVAILABLE -> LinkLoginState.Plain(PlainKind.UNAVAILABLE)
    else -> LinkLoginState.Plain(PlainKind.LEGACY_DESKTOP)
}
