package com.difft.android.migration.v2.model

import com.difft.android.migration.v2.container.AbsentStaysNull

/**
 * Top-level neutral entities: pure canonical data shapes, with no WCDB and no serialization. Each
 * platform projects its own storage into these on export and maps back on import. Local
 * auto-increment primary keys are deliberately not modeled — every cross-record reference uses a
 * portable stableId or composite identity, so FK reconnection works across platforms.
 */

/**
 * Canonical message.
 *
 * @param stableId portable message id, the target of quote / forward / reaction / attachment FK
 *   reconnection on import.
 * @param roomType per-message conversation kind (0 = single, 1 = group) — intrinsic, not derived.
 *   The Android message query filters on it alongside the conversation ref, so an imported group
 *   message left at 0 never matches the group query and the conversation opens empty.
 * @param sender canonical sender uid. The sending device id is not a field: it is the tail of
 *   [stableId] under the frozen id grammar `"${sentAt}${sender-digits}${deviceId}"`.
 * @param serverAt server-assigned timestamp in ms — the cross-platform sort and watermark
 *   authority. Required: exporters fall back to [sentAt] so importers never see 0.
 * @param expireStartedAt tri-state expiry-countdown exception anchor in ms. **null** = derive the
 *   anchor from the conversation's read ladder ([NeutralConversationLocal.readPositions]) and
 *   direction; **0** = explicitly not started, which the target must persist distinguishably from
 *   "absent" and never let the ladder override; **> 0** = the anchor itself. It travels only when
 *   the source's effective expiry start differs from the derived one, so a faithful ladder keeps it
 *   null on most messages. `expiresAt` is derivable and not modeled.
 * @param systemType the raw per-platform system-message type code, carried verbatim for
 *   same-platform re-render fidelity; there is deliberately no cross-platform semantic enum map.
 * @param displayText the pre-rendered display text of a system message. An importer that cannot
 *   re-render from the raw payload shows this verbatim. Null for non-system messages.
 * @param systemPayload structured system-message payload as a JSON string, keyed by [systemType].
 *   For notify-class types it is the server notify JSON verbatim, so importers can re-run their own
 *   receive-path parser. Optional: an exporter that discarded the raw notify sends null and relies
 *   on [displayText], and importers must support both.
 * @param attachmentRefs stableIds of the [NeutralAttachment] records belonging to this message;
 *   the bytes travel as separate frames.
 * @param sendState outgoing-only; null means [SendState.SENT]. Importers must treat outgoing as
 *   sent unless FAILED, or ends that derive status render a permanent "sending".
 * @param recipients the recipient roster snapshot taken when the message was sent, sender excluded
 *   — the denominator of the group read-receipt screen, whose numerator is
 *   [NeutralConversationLocal.participantReadPositions]. Deliberately a snapshot, not current
 *   membership, which the destination could not reconstruct. Populated only for a group message
 *   this account sent.
 * @param platformExtras catch-all for platform-private extras. Keys carry the frozen platform
 *   namespace prefix (`android.` / `ios.` / `desktop.`); importers read only their own prefix.
 */
data class NeutralMessage(
    val stableId: String,
    val conversationRef: String,
    val roomType: Int = 0,
    val sender: String,
    val direction: MessageDirection,
    val body: String?,
    val sentAt: Long,
    val serverAt: Long,
    val receivedAt: Long?,
    val expireTimer: Int,
    val expireStartedAt: Long? = null,
    val contentKind: ContentKind,
    val systemType: String?,
    val systemPayload: String? = null,
    val displayText: String? = null,
    val confidential: Boolean,
    val quote: NeutralQuote? = null,
    val forwardContext: NeutralForwardContext? = null,
    val mentions: List<NeutralMention> = emptyList(),
    val reactions: List<NeutralReaction> = emptyList(),
    val sharedContacts: List<NeutralSharedContact> = emptyList(),
    val attachmentRefs: List<String> = emptyList(),
    val sendState: SendState? = null,
    val sequenceId: Long? = null,
    val notifySequenceId: Long? = null,
    val criticalAlertType: Int? = null,
    val screenShot: NeutralScreenShot? = null,
    val recipients: List<String> = emptyList(),
    val platformExtras: Map<String, String> = emptyMap(),
)

/**
 * Marks a message as a screenshot notice (`DataMessage.ScreenShot` on the wire).
 *
 * Carried as `{author, sentAt}`, the same portable message reference [NeutralQuote] uses, rather
 * than the wire's `RealSource`: `sourceDevice` is meaningless to another platform and
 * `serverTimestamp` duplicates [NeutralMessage.serverAt]. On Android the pair is self-referential
 * and the renderer only tests presence, but the wire format permits pointing elsewhere, so the
 * struct is kept.
 */
data class NeutralScreenShot(
    val author: String,
    val sentAt: Long,
)

/**
 * Outgoing-message send outcome; null means [SENT] on the wire. Only FAILED changes importer
 * behaviour, and a platform with no failure marker exports null.
 */
enum class SendState {
    SENT,
    FAILED,
}

/** Message direction. */
enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

/**
 * Which owner a [NeutralAttachment] belongs to. Android's `AttachmentModel` has mutually exclusive
 * owner FKs; the neutral schema carries the owner kind plus a portable composite identity, since
 * forward and quote local PKs are not portable.
 *
 * [MESSAGE] owners are named by [NeutralAttachment.messageRef] and their bytes live under
 * `attachment/<messageId>/`. [FORWARD] owners are the forward node identified by
 * `{ownerAuthor, ownerSentAt}`, and their bytes live under `attachment/<authorityId>/`.
 *
 * There is no QUOTE owner: quote previews travel meta-only via [NeutralQuote.attachmentsMeta] and
 * never appear as attachment records. Any other owner value is skipped by the importer, bytes included.
 */
enum class AttachmentOwner {
    MESSAGE,
    FORWARD,
}

/**
 * Attachment metadata. The bytes travel separately as binary frames in the container, so this record
 * models metadata only; platform-local `path` and thumbnails are deliberately excluded as derivable
 * or platform-local.
 *
 * @param messageRef stableId of the message this attachment is rendered under, always filled for
 *   every [owner] kind: a forward-owned attachment names its host message here, and [ownerAuthor] /
 *   [ownerSentAt] say which node of that message's forward tree it belongs to. An empty value fails
 *   the import on iOS, which locates the message by this field.
 * @param flags wire-proto flags bitmask verbatim (bit0 = VOICE, bit2 = GIF), rather than a derived
 *   `isVoice`, so the GIF bit survives migration.
 * @param amplitudes decoded voice-waveform data; null when absent or the source platform has none.
 * @param serverAttachmentId the server-side attachment id, letting the destination re-download bytes
 *   that were not present at export time. Always travels, independent of byte presence.
 * @param encryptionKey base64 of the attachment's at-rest / transit key (32 AES ‖ 32 HMAC), paired
 *   with [serverAttachmentId] so a re-downloaded blob can be decrypted. Always travels.
 * @param digestBase64 base64 of the ciphertext digest, length-self-describing: 16 bytes is MD5, 32
 *   bytes is SHA-256. It digests the carried blob when bytes travel and the server blob when
 *   [pointerOnly]. The destination's download gate requires it alongside [serverAttachmentId] and
 *   [encryptionKey], so it always travels.
 * @param pointerOnly true when the bytes are not carried — the source file was absent, or the
 *   exporter cannot produce the contract blob form. The destination re-downloads when the message is
 *   next viewed; a past-retention failure renders as "attachment unavailable", not an error.
 * @param ownerAuthor portable author uid of the owning forward node, part of the `{author, sentAt}`
 *   re-link key; null for [AttachmentOwner.MESSAGE].
 * @param ownerSentAt portable sentAt of the owning forward node; null for [AttachmentOwner.MESSAGE].
 */
data class NeutralAttachment(
    val stableId: String,
    val messageRef: String,
    val contentType: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val flags: Int = 0,
    val amplitudes: String? = null,
    val fileName: String?,
    val serverAttachmentId: Long? = null,
    val encryptionKey: String? = null,
    val digestBase64: String? = null,
    val pointerOnly: Boolean = false,
    val owner: AttachmentOwner = AttachmentOwner.MESSAGE,
    val ownerAuthor: String? = null,
    val ownerSentAt: Long? = null,
)

/**
 * Conversation-local state. Carries local-only state: room name, avatar, last content and unread
 * counts are excluded because they are server-resynced or derived.
 *
 * @param roomType conversation kind (0 = single, 1 = group) — intrinsic, not recomputed. It is not
 *   reliably re-derivable on the target in time, because the post-login group full-refresh that
 *   would set it arrives too late and the conversation would render as a 1-on-1 until then.
 * @param pinnedTime pin timestamp in ms; presence implies pinned.
 * @param readPosition the migrating account's own authoritative read watermark, distinct from
 *   [participantReadPositions]. Its `maxServerTime` decides read / unread and the unread count; its
 *   `readAt` may be 0, in which case `maxServerTime` stands in as the effective read moment. Null
 *   means the conversation was never read.
 * @param readPositions the migrating account's own read ladder, merged across its devices, in
 *   canonical form: `maxServerTime` strictly increasing, every `readAt > 0`, and `readAt`
 *   non-decreasing along the rows. A message's ladder read time is the smallest `readAt` among the
 *   rows whose `maxServerTime` covers its `serverAt`. Empty means no ladder rows travelled, which is
 *   not the same as unread. Invariant: `readPositions.last().maxServerTime <= readPosition
 *   .maxServerTime` — messages between the last row and the watermark are read, with the watermark's
 *   effective `readAt` as their time.
 * @param participantReadPositions others' per-participant read watermarks, one entry per other
 *   participant. It must NOT contain the migrating account itself, whose state travels as
 *   [readPosition] and [readPositions]. They drive the "read by" indicator on outgoing messages: a sent message is read by
 *   X once X's watermark reaches the message's position. Android persists only `maxServerTime` per
 *   participant; the rest of [NeutralReadPosition] is 0 there.
 */
data class NeutralConversationLocal(
    val conversationRef: String,
    val roomType: Int = 0,
    val pinnedTime: Long? = null,
    val readPosition: NeutralReadPosition? = null,
    val readPositions: List<NeutralReadPosition> = emptyList(),
    val participantReadPositions: List<NeutralParticipantReadPosition> = emptyList(),
)

/**
 * One other participant's read watermark in a conversation, carried in
 * [NeutralConversationLocal.participantReadPositions] so the destination can render the "read by"
 * indicator on outgoing messages after restore.
 *
 * The rich [NeutralReadPosition] struct is reused rather than a bare Long so iOS and Desktop can
 * carry their extra fields without a schema change; Android uses only `maxServerTime`.
 */
data class NeutralParticipantReadPosition(
    val uid: String,
    val readPosition: NeutralReadPosition,
)

/**
 * Canonical read position — one watermark, or one rung of a read ladder. [maxServerTime] is the
 * cross-platform sort authority; [readAt] is the wall-clock moment that range was read, and is not
 * required to be `>= maxServerTime` (a platform that merges reads into time buckets keeps the
 * bucket's first read moment). The Signal-sourced [maxNotifySequenceId] and [maxSequenceId] are 0 on
 * platforms that carry a subset.
 */
data class NeutralReadPosition(
    val readAt: Long,
    val maxServerTime: Long,
    val maxNotifySequenceId: Long,
    val maxSequenceId: Long,
)

/**
 * Group crypto key: a portable gid to root-key mapping.
 *
 * @param keyVersion server-driven key-rotation generation (#968): 0 is the un-rotated baseline and
 *   it increases monotonically on each rotate. It migrates alongside [rGroup] because a migrated key
 *   with a stale version reads as baseline and mismatches the server.
 */
data class NeutralGroupKey(
    val groupId: String,
    val rGroup: String,
    val keyVersion: Int = 0,
)

/**
 * Account identity material carried with the migration container.
 *
 * `masterKey` is the ACI identity private key in this product and the server holds only the public
 * half, so the private key can never be re-fetched after login. DTProto has no forward secrecy, so a
 * device that generates a fresh identity silently loses every still-queued message sealed to the old
 * one and resets the identity-derived business key chain. The container therefore carries the
 * identity and the new device installs it before login publishes one.
 *
 * It lives in the encrypted container body only, emitted as the first record after the manifest so
 * the importer sees it before any bulk data.
 *
 * Every property name below is a frozen wire field name — gson uses the exact Kotlin property names
 * — and part of the cross-platform contract, so renaming one breaks iOS and Desktop interop. Values
 * travel verbatim as stored; this type performs no re-encoding.
 *
 * @param aciPrivateKey the ACI identity private key (the masterKey) — the identity-continuity
 *   payload proper.
 * @param oldAciPrivateKey the rotation-window old private key. The decrypt path falls back to it for
 *   messages still sealed to the pre-rotation identity, so the old pair travels as a group with
 *   [identityKeyGenTime].
 * @param identityKeyGenTime generation timestamp in ms of the current identity. The old-key decrypt
 *   fallback is gated by a 24h window measured from it, and 0 reads as expired and disables the
 *   fallback, so the original value must be carried and never re-stamped to now.
 * @param favKey favourites secret, base64 NO_WRAP. Re-derivable from the server with the identity,
 *   but carried to avoid a round-trip and a state regression.
 * @param proxyShareLink self-hosted proxy share link. It embeds a coturn `static-auth-secret`, so it
 *   must stay inside the encrypted body.
 * @param recoverySlotKeks recovery-vault per-slot KEKs. Missing one makes a future identity rotation
 *   permanently discard that slot server-side, silently killing the user's recovery code. Cold
 *   recovery has not landed on Android, so it exports null and the frozen wire name is reserved.
 * @param recoveryStaleSlots non-secret companion metadata to [recoverySlotKeks]; same status.
 */
data class NeutralIdentity(
    val aciPublicKey: String,
    val aciPrivateKey: String,
    val oldAciPublicKey: String? = null,
    val oldAciPrivateKey: String? = null,
    val identityKeyGenTime: Long = 0,
    val favKey: String? = null,
    val favKeyId: String? = null,
    val favKeyVersion: Int = 0,
    val proxyShareLink: String? = null,
    val proxyEnabled: Boolean = false,
    val proxyProtectCallIp: Boolean = false,
    @field:AbsentStaysNull val recoverySlotKeks: Map<String, String>? = null,
    @field:AbsentStaysNull val recoveryStaleSlots: List<String>? = null,
)

/**
 * The account's session credential — the [com.difft.android.base.user.UserData] fields a successful
 * sign-in writes — carried only by a same-device, loopback, credential-handoff container, so the new
 * package comes up already signed in without a verification code.
 *
 * Not carried: `passcode` / `pattern`, since App Lock is never migrated; `registrationId`, which is
 * generated per sign-in; the identity keys, which travel in [NeutralIdentity]; and `customUid` /
 * `contactRequestStatus`, which re-sync from the server on first connect.
 *
 * This is a live account credential. It exists only inside the encrypted container body under the
 * ephemeral transfer key — never in a file, an Intent extra, or a log line.
 *
 * @param handoffId correlates the old app's local sign-out with the new app's activation, so a crash
 *   on either side can be reconciled by asking the peer whether that handoff completed.
 */
data class NeutralCredential(
    val account: String,
    val baseAuth: String,
    val microToken: String,
    val signalingKey: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val handoffId: String,
)
