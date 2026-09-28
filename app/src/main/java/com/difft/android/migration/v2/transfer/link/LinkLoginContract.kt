package com.difft.android.migration.v2.transfer.link

import com.difft.android.base.migration.LinkMigrationState
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.QrPayload

/**
 * MVI contract for the "sign in to Quicall for Desktop" confirmation page: the one place where the
 * phone decides whether its chat history syncs along with the link. What the page shows depends only
 * on what the scanned QR carried.
 */
sealed interface LinkLoginState {
    /** The code carried pairing info: the Desktop can receive, and `link-precheck` says how its history relates. */
    data class Compound(
        val alias: String,
        val estimate: ExportEstimate? = null,
        val syncEnabled: Boolean = true,
        val includeAttachments: Boolean = true,
        /** null while the precheck runs; false forces sync off for this login. */
        val reachable: Boolean? = null,
        /** The precheck's answer; null until it arrives. Same account → no sync switch at all. */
        val peerState: LinkPeerState? = null,
        /** The Desktop's gate refused the sync (env / version); the page then offers a plain sign-in. */
        val gateReject: String? = null,
        val busy: Boolean = false,
    ) : LinkLoginState {
        /** Whether this login will carry history: the user wants it and nothing stands in the way. */
        val willSync: Boolean
            get() = syncEnabled && reachable == true && gateReject == null && peerState != LinkPeerState.SAME_ACCOUNT
    }

    /** A plain link code: the page only explains why nothing syncs, then signs in. */
    data class Plain(val kind: PlainKind, val busy: Boolean = false) : LinkLoginState
}

enum class PlainKind {
    /** A Desktop too old to carry migration info at all. */
    LEGACY_DESKTOP,

    /** A current Desktop whose receiver could not start (`dataMigrationState=unavailable`). */
    UNAVAILABLE,
}

sealed interface LinkLoginIntent {
    data class SetSync(val on: Boolean) : LinkLoginIntent
    data class SetIncludeAttachments(val on: Boolean) : LinkLoginIntent
    data object SignIn : LinkLoginIntent

    /** After a gate reject: link anyway, history stays on the phone. */
    data object SignInWithoutSync : LinkLoginIntent
    data object Cancel : LinkLoginIntent
}

sealed interface LinkLoginEffect {
    data class Toast(val msg: String) : LinkLoginEffect
    data object Finish : LinkLoginEffect

    /**
     * The link went through with sync on: hand the pairing payload to the send screen, which starts at
     * once. [awaitPeerConfirm] is set on an account switch: the Desktop will hold `prepare-upload`
     * until its user confirms, so the sender must retry through that wait.
     */
    data class OpenSend(val rawPayload: String, val includeAttachments: Boolean, val awaitPeerConfirm: Boolean) : LinkLoginEffect
}

/** What the page starts from, decided purely by the code's contents. */
fun deriveLinkLoginState(payload: QrPayload?, state: LinkMigrationState?): LinkLoginState = when {
    payload?.transferKey != null -> LinkLoginState.Compound(alias = payload.alias)
    state == LinkMigrationState.UNAVAILABLE -> LinkLoginState.Plain(PlainKind.UNAVAILABLE)
    else -> LinkLoginState.Plain(PlainKind.LEGACY_DESKTOP)
}
