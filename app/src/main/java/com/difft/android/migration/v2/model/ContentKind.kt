package com.difft.android.migration.v2.model

/**
 * Canonical content-kind discriminator for a [NeutralMessage]. The platforms encode content kind
 * incompatibly — iOS by record type, Android and Desktop by field presence — so each projects into
 * this one enum on export and expands back on import.
 *
 * [REACTION] exists only for platforms that persist standalone reaction interactions; the canonical
 * carrier is the target message's [NeutralMessage.reactions] list.
 */
enum class ContentKind {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    FILE,
    CONTACT_SHARE,
    CARD,
    TASK,
    VOTE,
    REACTION,
    FORWARD,
    CALL,
    SYSTEM,

    /**
     * Decode-side sentinel, never exported: an unrecognized enum string from a newer MINOR
     * deserializes to this and the importer skips the whole record along with its attachment records
     * and byte frames, so an unknown value is a designed path rather than a Gson-null NPE.
     */
    UNKNOWN,
}
