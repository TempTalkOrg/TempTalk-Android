package com.difft.android.migration.v2.handoff

/**
 * MVI contract for the new package's first-launch "migrate from the old app?" page.
 *
 * The page is reached either from this app's startup probe or from the old app's home screen; in the
 * latter case the choice was already made there, so the receiver starts without a second tap.
 */
sealed interface MigrationHandoffEntryState {

    /** Probing the gate and the peer; a bounded wait, never indefinite. */
    data object Loading : MigrationHandoffEntryState

    /** The old app holds a session that can travel — offer it. */
    data object Offer : MigrationHandoffEntryState

    /**
     * The old app is installed and trusted but predates the handoff protocol. The only fix is an
     * update inside the old app, which this app cannot drive, so the page explains it and offers the
     * other way to sign in.
     */
    data object PeerTooOld : MigrationHandoffEntryState

    /** The migration cannot happen; [reason] says which of the two ways it is closed. */
    data class Blocked(val reason: HandoffEntryBlockReason) : MigrationHandoffEntryState
}

/** Why the no-login migration is not on offer. Each maps to its own explanation. */
enum class HandoffEntryBlockReason {
    /** This install already has an account or chat data, so it cannot adopt another session. */
    TARGET_NOT_EMPTY,

    /** The old app has nothing to hand over (signed out, or never signed in on this device). */
    PEER_NO_ACCOUNT,
}

sealed interface MigrationHandoffEntryIntent {
    /**
     * Probe and render. [autoStart] means the user already chose this in the old app, so a passing
     * gate goes straight to the receiver instead of asking again.
     */
    data class Start(val autoStart: Boolean = false) : MigrationHandoffEntryIntent

    data object ChooseHandoff : MigrationHandoffEntryIntent

    /** "Use another sign-in method" — leaves the handoff path for good. */
    data object ChooseNormalLogin : MigrationHandoffEntryIntent

    data object Back : MigrationHandoffEntryIntent
}

sealed interface MigrationHandoffEntryEffect {
    data object StartReceive : MigrationHandoffEntryEffect
    data object OpenLogin : MigrationHandoffEntryEffect
    data object Finish : MigrationHandoffEntryEffect
}

/**
 * What the startup probe concluded: whether to show the entry page at all, and whether to show it
 * with the update explanation rather than dropping the user on the login page with no reason.
 */
enum class HandoffOffer { NONE, OFFER, PEER_TOO_OLD }
