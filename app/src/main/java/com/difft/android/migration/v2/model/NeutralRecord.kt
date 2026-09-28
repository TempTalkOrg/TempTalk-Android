package com.difft.android.migration.v2.model

/**
 * Top-level neutral record: one tagged, heterogeneous unit of the record stream. The export pipeline
 * projects each source entity into a variant and the import pipeline routes by variant; the sealed
 * hierarchy keeps every `when` over it exhaustive without an `else`.
 *
 * Attachment bytes do not live here — [Attachment] carries metadata and a content ref while the bytes
 * travel as separate container frames, which keeps the record stream constant-memory.
 *
 * [Identity] and [Credential] are the two variants that are not storage entities: they never reach
 * the staging DB and are routed to dedicated sinks instead.
 */
sealed interface NeutralRecord {

    data class Message(val value: NeutralMessage) : NeutralRecord

    data class Attachment(val value: NeutralAttachment) : NeutralRecord

    data class ConversationLocal(val value: NeutralConversationLocal) : NeutralRecord

    data class GroupKey(val value: NeutralGroupKey) : NeutralRecord

    /**
     * Account identity material — at most one per container, emitted immediately after the manifest.
     * Installed into secure storage by the restore orchestrator, never written to the staging DB, so
     * the original identity is in place before login would publish a new one.
     */
    data class Identity(val value: NeutralIdentity) : NeutralRecord

    /**
     * The account's session credential (same-device no-login handoff) — at most one per container,
     * emitted immediately after [Identity] and only by a loopback credential-handoff send. Routed to
     * a memory sink and installed into secure storage right after promote. Never staged, never logged.
     */
    data class Credential(val value: NeutralCredential) : NeutralRecord
}
