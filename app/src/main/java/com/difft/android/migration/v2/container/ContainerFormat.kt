package com.difft.android.migration.v2.container

/**
 * Byte-layout constants and framing tags for the migration container.
 *
 * ## On-disk layout
 *
 * ```
 * [ plaintext header (42 B) ]                         ← BackupCrypto.buildHeader
 * [ encrypted body ]  ── AES-256-CTR, authenticated ──┐
 *     manifest frame                                  │
 *     record frames (0..N, JSONL)                     │ all under one CTR keystream;
 *     attachment frames (0..M, length-prefixed bytes) │ every byte fed to the HMAC
 *     end-of-stream frame                             │
 * [ trailing HMAC-SHA256 (32 B) ]  over header ‖ ciphertext  ←┘
 * ```
 *
 * The header and envelope come from [com.difft.android.migration.crypto.BackupCrypto]; this format
 * does not use its 58-byte body prefix, which packaged a WCDB cipher key and db length the neutral
 * container does not carry.
 *
 * ## Frame structure (inside the encrypted body)
 *
 * Each frame: `[ TAG (1 B) ][ LENGTH (4 B, BE, unsigned-as-int) ][ PAYLOAD (LENGTH B) ]`.
 *  - [TAG_MANIFEST] — payload = UTF-8 JSON manifest object (exactly one, first frame).
 *  - [TAG_RECORD]   — payload = one UTF-8 JSONL line (a single [com.difft.android.migration.v2.model.NeutralRecord]).
 *  - [TAG_ATTACHMENT] — payload = raw attachment bytes; the owning record's `contentRef`
 *    names this frame and a preceding [TAG_RECORD] (an Attachment record) carries the
 *    metadata + byte length, so the reader knows how many bytes to expect.
 *  - [TAG_END] — payload length 0; marks clean end of stream (lets the reader distinguish a
 *    complete archive from a truncated one independently of the HMAC check).
 *
 * Framing keeps the stream constant-memory: the writer never holds more than one frame's
 * payload in memory, and attachment bytes are copied through a fixed buffer.
 */
internal object ContainerFormat {

    /** Frame tag: the single JSON manifest object (first body frame). */
    const val TAG_MANIFEST: Byte = 0x01

    /** Frame tag: one neutral record, JSONL-encoded. */
    const val TAG_RECORD: Byte = 0x02

    /** Frame tag: raw attachment bytes for the most recent Attachment record. */
    const val TAG_ATTACHMENT: Byte = 0x03

    /** Frame tag: end-of-stream sentinel (zero-length payload). */
    const val TAG_END: Byte = 0x7F

    /** Frame fixed prefix size: 1-byte tag + 4-byte big-endian length. */
    const val FRAME_PREFIX_SIZE = 1 + 4

    /** Container body format version, recorded in the manifest. */
    const val BODY_FORMAT_VERSION = 1

    /** Serialization codec id recorded in the manifest. */
    const val ENCODING_JSONL = "jsonl"

    /**
     * Semantic container format version `MAJOR.MINOR` recorded in the manifest — the authoritative
     * compatibility field, distinct from the plaintext-header `version` (a coarse pre-decrypt gate)
     * and [BODY_FORMAT_VERSION] (body encoding revision). MINOR is additive-only; MAJOR is breaking
     * and importers reject across it.
     *
     * Frozen at 1.0 until the feature ships. The series starts at 1 because going 0.x to 1.0 later
     * would itself be a MAJOR change and would reject every container produced before it. Additive
     * schema growth is expected and is not a reason to bump either component.
     */
    const val FORMAT_VERSION = "1.0"

    /** Source platform constant written into the manifest for this app. */
    const val SOURCE_PLATFORM_ANDROID = "android"

    /**
     * Android's capability declaration for the manifest `sourceCapabilities`. Frozen vocabulary:
     * `coldRecovery` / `keyReset` / `topicReply` / `voiceWaveform`. Android has key reset and voice
     * waveforms; cold recovery has not landed and topic replies are not persisted.
     */
    val SOURCE_CAPABILITIES_ANDROID = listOf("keyReset", "voiceWaveform")

    /**
     * Frozen capability name for a container carrying the session-credential record (same-device
     * no-login handoff). Declarative only: installation is decided by the handshake's
     * `credentialHandoff` flag and the receiver's mode, never by this string.
     */
    const val CAPABILITY_CREDENTIAL_HANDOFF = "credentialHandoff"

    /** Streaming buffer for copying attachment bytes through the cipher. */
    const val ATTACHMENT_COPY_BUFFER = 64 * 1024

    /**
     * Hard upper bound on a 4-byte-length frame's payload (manifest + JSONL record frames).
     *
     * These frames are fully allocated into a `ByteArray` to be JSON-decoded, so an attacker-crafted
     * length (e.g. `0x7FFFFFFF` ≈ 2 GiB) would trigger an immediate OOM before any content is read.
     * Manifests and single JSONL records are small — 64 MiB is orders of magnitude above any
     * legitimate record while still impossible to allocate maliciously. A declared length above this
     * cap is rejected as corrupt/malicious ([com.difft.android.migration.model.MigrationException.DecryptionFailed]).
     *
     * Attachment bytes use the 8-byte length path and are streamed through
     * [ATTACHMENT_COPY_BUFFER], so they are not bounded by this cap.
     */
    const val MAX_FRAME_PAYLOAD_BYTES = 64 * 1024 * 1024
}

/**
 * Container manifest (first body frame). Self-describes the archive so a reader can validate format
 * and version before consuming records. Serialized to JSON via [RecordJsonCodec].
 *
 * Additive-only: new keys serialize automatically through Gson defaults and older importers ignore
 * what they do not recognize.
 *
 * @param createdAt wall-clock ms the archive was written; diagnostic only.
 * @param recordCount total neutral records that follow. 0 means "not counted"; the reader returns
 *   the tallied truth regardless.
 * @param attachmentCount total attachment byte-frames that follow; same rule.
 * @param attachmentBytes total attachment payload bytes that follow; same rule. With [recordCount]
 *   it drives both ends' progress bars, since the container's own byte length is never known up
 *   front.
 * @param sourceAccount exporting account id, compared against the current account on import.
 * @param sourceCapabilities source-end capability declaration. Distinguishes "the source has no
 *   such capability" from "has it but did not carry the data": a missing capability is benign,
 *   except that a cold-recovery-capable receiver must prompt the user to reset recovery codes when
 *   the source lacks `coldRecovery`.
 */
data class ContainerManifest(
    val bodyFormatVersion: Int,
    val encoding: String,
    val createdAt: Long,
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long = 0,
    val sourceAccount: String,
    val sourcePlatform: String,
    val sourceAppVersion: String,
    val formatVersion: String,
    val sourceCapabilities: List<String> = emptyList(),
)
