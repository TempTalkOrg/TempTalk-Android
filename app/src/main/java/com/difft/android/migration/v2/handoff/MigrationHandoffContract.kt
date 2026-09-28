package com.difft.android.migration.v2.handoff

import com.difft.android.base.migration.PeerApps

/**
 * The cross-package contract for the read-only migration-status query — the single owner of every
 * wire constant, shipped identically in both package families so the two builds cannot drift.
 *
 * Replies carry only the tri-state and a protocol number: never an account id, phone number, email,
 * row count or byte size. The transport is an exported `ContentProvider.call()` so it is answerable
 * off-main before any UI exists and from a cold-start reconciler with no Activity. A `signature`-level
 * `<permission>` is impossible — the two families are signed with different certificates — so the
 * caller check is `Binder.getCallingUid()` → package → the pinned-certificate allow-list in
 * [PeerApps.trustedCaller].
 */
object MigrationHandoffContract {

    /** This build's handoff protocol version, reported in every reply. */
    const val PROTOCOL_VERSION = 1

    /** A peer reporting below this (or shipping no provider at all) is "too old to migrate with". */
    const val MIN_SUPPORTED_PEER_PROTOCOL = 1

    /** Authority = `<applicationId>` + this. Owned by [PeerApps] so `:base` can build peer URIs. */
    const val AUTHORITY_SUFFIX = PeerApps.STATUS_AUTHORITY_SUFFIX

    /** The only accepted `call()` method name; anything else is answered with null. */
    const val METHOD = "migration.handoff.v1"

    // ── request keys ──────────────────────────────────────────────────────────────────────────
    /** Which question is being asked: [QUERY_STATUS] or [QUERY_HANDOFF]. */
    const val KEY_QUERY = "query"
    const val QUERY_STATUS = "status"
    const val QUERY_HANDOFF = "handoff"

    /**
     * The old app left its confirmation page without starting: stop the receiver the new app is
     * holding open, so no pairing timeout follows. A transfer in flight is never touched.
     */
    const val QUERY_CANCEL_RECEIVE = "cancelReceive"

    /** [QUERY_HANDOFF] input: the handoff id the asker minted before it started sending. */
    const val KEY_HANDOFF_ID = "handoffId"

    // ── reply keys ────────────────────────────────────────────────────────────────────────────
    /** Int — [PROTOCOL_VERSION] of the answering build. */
    const val KEY_PROTOCOL = "protocol"

    /** String — a [HandoffState] name. */
    const val KEY_STATE = "state"

    /** String — a [HandoffResult] name. */
    const val KEY_HANDOFF_RESULT = "handoffResult"
}

/**
 * What account material an install holds, as far as another app is allowed to know.
 *
 * Three values and no more: nothing finer than this may be revealed to another process.
 */
enum class HandoffState {
    /** Account + a complete credential + installed ACI identity keys — a session that can travel. */
    READY,

    /** An account is established but the credential or the identity keys are gone (kicked / signed out). */
    DATA_ONLY,

    /** No account has ever been established here — a fresh install that can receive. */
    NONE,
}

/** Whether a specific handoff id was completed by the answering install. */
enum class HandoffResult {
    /** This install adopted that handoff's credential — the asker may now wipe its own. */
    COMPLETED,

    /** Not this handoff (or nothing was adopted) — the asker must stay signed in. */
    UNKNOWN,
}
