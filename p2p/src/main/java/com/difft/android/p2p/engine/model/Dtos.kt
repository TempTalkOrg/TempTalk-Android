package com.difft.android.p2p.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Wire DTOs for the LocalSend v2.1 protocol plus the QR pairing payload. Pure Kotlin, no Android
 * imports — the transport-agnostic engine core that iOS mirrors.
 *
 * Wire compatibility is load-bearing: the real LocalSend app is the conformance oracle, so every
 * [SerialName] must match the protocol byte-for-byte — one renamed key breaks interop. Golden-JSON
 * conformance tests guard this in CI.
 */

@Serializable
data class DeviceInfo(                                    // register + prepare-upload "info"
    @SerialName("alias") val alias: String,
    @SerialName("version") val version: String = "2.1",
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("deviceType") val deviceType: String = "mobile", // mobile|desktop|web|headless|server
    @SerialName("fingerprint") val fingerprint: String,          // cert SHA-256 (https)
    @SerialName("port") val port: Int = 53317,
    @SerialName("protocol") val protocol: String = "https",      // http|https
    @SerialName("download") val download: Boolean = false,
    @SerialName("announce") val announce: Boolean = true,
    /**
     * Transport-observed source IP of the multicast/HTTP packet that delivered this DeviceInfo. Not
     * a wire field — `@Transient`, so it is never (de)serialized. The announcer's IP is known only
     * from the UDP datagram / TCP peer address, not the JSON body, so the discovery layer stamps it
     * here to drive the register reply and send-to-peer.
     */
    @Transient val lastSeenIp: String? = null,
)

@Serializable
data class FileMetadata(                                  // prepare-upload files map value
    @SerialName("id") val id: String,
    @SerialName("fileName") val fileName: String,
    @SerialName("size") val size: Long,
    @SerialName("fileType") val fileType: String,                // MIME
    @SerialName("sha256") val sha256: String? = null,            // hex — integrity check
    @SerialName("preview") val preview: String? = null,
    @SerialName("metadata") val metadata: FileTimes? = null,
)

@Serializable
data class FileTimes(
    @SerialName("modified") val modified: String? = null,        // ISO8601
    @SerialName("accessed") val accessed: String? = null,
)

@Serializable
data class PrepareUploadRequest(                          // prepare-upload body
    @SerialName("info") val info: DeviceInfo,
    @SerialName("files") val files: Map<String, FileMetadata>,   // fileId -> metadata
    /**
     * Migration-only handshake extension: the sending device declares its source identity so the
     * receiver can fail fast before any container bytes flow — environment isolation (test↔test /
     * prod↔prod), account match, format-version compatibility. Omitted when null, so a plain
     * file-transfer `prepare-upload` stays wire-compatible with LocalSend. The receiver's migration
     * server passes it to a validator; a generic file receiver ignores it.
     */
    @SerialName("migration") val migration: MigrationHandshake? = null,
)

/**
 * The sending device's source identity, carried in [PrepareUploadRequest.migration] so the receiving
 * device can pre-check compatibility before accepting the upload session — the authoritative
 * fail-fast gate. Every field is compared by the receiver-supplied validator against the receiving
 * device's own identity (the values it advertised in its [QrPayload]):
 *
 *  - [sourceEnvironment] — the environment under its platform-neutral wire name (`online` / `test`,
 *    see `QrPayloadV2Codec.Environment`). A cross-environment migration is hard-rejected; this is
 *    the security-critical environment-isolation gate.
 *  - [sourceAccount] — the migrating account id; must equal the receiver's logging-in account.
 *  - [sourceAppVersion] / [sourceFormatVersion] — version/format compatibility (cross-major reject),
 *    so a transfer the container reader would later reject is never started.
 *
 * Platform-neutral so the iOS port mirrors it. The compared values are never logged raw.
 */
@Serializable
data class MigrationHandshake(
    @SerialName("env") val sourceEnvironment: String,
    @SerialName("account") val sourceAccount: String,
    @SerialName("appVersion") val sourceAppVersion: String,
    @SerialName("formatVersion") val sourceFormatVersion: String,
    /**
     * The sender is handing its login credential over inside the container (same-device, loopback,
     * no-login migration). Both ends must agree: a receiver not running the handoff refuses a
     * handshake that claims it, or the sender would be told "200, you may wipe" by a receiver that
     * never installs the credential, stranding the user signed out on both apps. Omitted when null.
     */
    @SerialName("credentialHandoff") val credentialHandoff: Boolean? = null,
)

@Serializable
data class PrepareUploadResponse(                         // prepare-upload 200
    @SerialName("sessionId") val sessionId: String,
    @SerialName("files") val files: Map<String, String>,         // fileId -> fileToken
    /**
     * Session-level credential for `/status` and `/cancel`. The per-file tokens above are consumed by
     * the upload itself; this one has to outlive it, because the questions it authorizes ("how is my
     * upload doing", "how did it end") are asked after the upload connection is gone. base64url,
     * 128 bits, never logged. Null when the receiver predates it.
     */
    @SerialName("sessionToken") val sessionToken: String? = null,
    /** What this receiver can do beyond the base protocol — see [ReceiverCaps]. Null from an older receiver. */
    @SerialName("caps") val caps: List<String>? = null,
)

/**
 * Capability tokens a receiver lists in [PrepareUploadResponse.caps]. A sender enables a behaviour
 * only when the matching token is present — never from a version number — and ignores tokens it
 * does not know, so a receiver may add new ones freely.
 */
object ReceiverCaps {
    /** Serves `GET /status` and keeps a terminal record of each session (see StatusResponse). */
    const val STATUS_V1 = "status-v1"

    /** Sends `102 Processing` on the `/upload` connection while the verdict is pending. Liveness only. */
    const val BEAT_V1 = "beat-v1"
}

/** How a session ended — [StatusResponse.terminal] and the retained record. */
object TransferTerminal {
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * What a `/cancel` did to the data — [CancelResponse.state]. `too_late` covers everything past the
 * receiver's commit point, a finished import included: the caller reads the real outcome from
 * `/status`, never from this. There is no "unknown" on the wire: a session the receiver does not
 * know is a 404, and "unknown" is the sender's own word for an answer it could not obtain.
 */
object CancelState {
    const val STOPPED = "stopped"
    const val ROLLED_BACK = "rolled_back"
    const val TOO_LATE = "too_late"
}

/**
 * This receiver's own names for what it is doing. Shown to the user on the other end and nothing
 * else: a sender decides on `progressRevision` and `terminal`, never on the stage, because every
 * platform divides the work differently.
 */
object TransferStages {
    const val PREPARED = "prepared"
    const val RECEIVING = "receiving"
    const val VERIFYING = "verifying"
    const val IMPORTING = "importing"
    const val PROMOTING = "promoting"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * `GET /api/localsend/v2/status?sessionId=&token=` → 200; 400 missing parameter; 404 unknown session
 * or wrong token (deliberately the same answer). Two figures are for deciding —
 * [progressRevision] (did anything happen since I last asked) and [terminal] (is it over, and how) —
 * the rest is for showing. [progressRevision] moves with every attachment byte written, not only
 * with records or whole attachments, so a long attachment write reads as work, not as a stall.
 */
@Serializable
data class StatusResponse(
    @SerialName("stage") val stage: String,
    /** Display only, and optional: a receiver whose import engine does not count records omits it. */
    @SerialName("records") val records: Long? = null,
    @SerialName("attachmentBytes") val attachmentBytes: Long,
    @SerialName("progressRevision") val progressRevision: Long,
    /** One of [TransferTerminal], or null while the session is live. */
    @SerialName("terminal") val terminal: String? = null,
    @SerialName("updatedAt") val updatedAt: Long,
)

/** `POST /api/localsend/v2/cancel?sessionId=&token=` → 200. [state] is one of [CancelState]. */
@Serializable
data class CancelResponse(
    @SerialName("state") val state: String,
)

/**
 * QR payload — the pairing layer, not LocalSend wire. Carries everything the sender needs to
 * bootstrap a direct connection without multicast discovery: address, the cert fingerprint to pin
 * (MITM defense), a one-time PIN, and the receiver alias.
 *
 * [transferKey] is an optional extension for the migration no-disk byte-pipe: the receiver generates
 * an ephemeral 256-bit key, base64-encodes it here, and the sender keys the migration container KDF
 * with it, riding the existing passphrase position. Omitted when null. Never log this value — it is
 * a live decryption key for the in-flight stream.
 */
@Serializable
data class QrPayload(
    @SerialName("v") val v: Int = 1,
    /** The first candidate address — what a single-address flow connects to, and what the wire's `addr[0]` is. */
    @SerialName("ip") val ip: String,
    @SerialName("port") val port: Int = 53317,
    @SerialName("fp") val fp: String,                            // cert SHA-256 == DeviceInfo.fingerprint
    @SerialName("pin") val pin: String,                          // one-time 6-digit
    @SerialName("alias") val alias: String,
    @SerialName("transferKey") val transferKey: String? = null,  // base64 ephemeral key (migration P2P only)
    /**
     * The receiving device's advertised source-compat identity, migration-only. The sender reads
     * these on scan for an early local pre-check before connecting, and echoes its own identity in
     * the [MigrationHandshake] so the receiver can run the authoritative check at `prepare-upload`.
     * All four are omitted when null. Not secret, but still not logged raw.
     */
    @SerialName("env") val environment: String? = null,          // receiver build environment (test/prod)
    @SerialName("account") val account: String? = null,          // receiver's logging-in account
    @SerialName("appVersion") val appVersion: String? = null,    // receiver app version
    @SerialName("formatVersion") val formatVersion: String? = null, // receiver supported container MAJOR.MINOR
    /**
     * Which way the bytes flow, from the scanner's point of view: `"upload"` — the scanner sends via
     * `prepare-upload`/`upload`; `"download"` — the scanner pulls the displayer's export via
     * `prepare-download`/`download`. Absent or unknown means upload. See [TransferDirection].
     */
    @SerialName("dir") val dir: String? = null,
    /**
     * Every address the displayer can be reached at, first one preferred, at most four; empty means
     * "just [ip]". The connecting side races them (TCP + TLS + fingerprint) and keeps the winner.
     * Carried by the v2 binary code only, so it is not part of the JSON shape.
     */
    @Transient val ips: List<String> = emptyList(),
) {
    /** The addresses to try, never empty. */
    val candidates: List<String> get() = ips.ifEmpty { listOf(ip) }
}

/** `prepare-download` body: the puller's identity and handshake; the displayer owns the file list. */
@Serializable
data class PrepareDownloadRequest(
    @SerialName("info") val info: DeviceInfo,
    @SerialName("migration") val migration: MigrationHandshake? = null,
)

/**
 * `prepare-download` 200 body. [files] is the upstream shape (fileId → metadata, with the real byte
 * size), [tokens] the per-file download tokens and [migration] the displayer's own handshake so the
 * puller can run the same gate the receiver runs at `prepare-upload`.
 */
@Serializable
data class PrepareDownloadResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("files") val files: Map<String, FileMetadata> = emptyMap(),
    @SerialName("tokens") val tokens: Map<String, String> = emptyMap(),
    @SerialName("migration") val migration: MigrationHandshake? = null,
)

/**
 * `link-intent` body: sent by the phone before it links a Desktop that showed a compound login QR,
 * carrying its decision — [sync] — so the Desktop knows at provisioning time
 * whether to wait for an upload or go straight to the inbox.
 */
@Serializable
data class LinkIntentRequest(
    @SerialName("info") val info: DeviceInfo,
    @SerialName("migration") val migration: MigrationHandshake,
    @SerialName("sync") val sync: Boolean,
)

/**
 * `link-precheck` body: the phone's identity and handshake, so the Desktop can answer how its
 * retained history relates to this account before the confirmation page renders.
 */
@Serializable
data class LinkPrecheckRequest(
    @SerialName("info") val info: DeviceInfo,
    @SerialName("migration") val migration: MigrationHandshake,
)

/** `200` body of both `link-precheck` and `link-intent`: one of [LinkPeerState]'s wire values. */
@Serializable
data class LinkPeerStateResponse(@SerialName("state") val state: String)

/** `POST /api/migration/v1/claim?pin=` body: the scanner announcing itself the moment it read the code. */
@Serializable
data class ClaimRequest(@SerialName("info") val info: DeviceInfo)

/**
 * How the Desktop's retained history relates to the account that scanned its code. Only the relation
 * travels — never the retained account itself.
 */
enum class LinkPeerState(val wire: String) {
    /** No history on the Desktop: a sync may follow the login. */
    CLEAN("clean"),

    /** The Desktop already holds this account's history: the login keeps it, nothing syncs. */
    SAME_ACCOUNT("same-account"),

    /** The Desktop holds another account's history: its user must confirm deletion before a sync. */
    DIFFERENT_ACCOUNT("different-account");

    companion object {
        fun of(wire: String?): LinkPeerState? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

/** `download-result` body: the puller's import verdict; [error] is a wire reason token. */
@Serializable
data class DownloadResultRequest(
    @SerialName("ok") val ok: Boolean,
    @SerialName("error") val error: String? = null,
)

/**
 * Single JSON codec for the whole module.
 * - `encodeDefaults = true`: emit default-valued fields (LocalSend expects `version`, `port` etc.).
 * - `explicitNulls = false`: omit null optionals instead of emitting `"key":null` (LocalSend omits them).
 * - `ignoreUnknownKeys = true`: forward-compat — a future real-LocalSend field we don't model
 *   must not break parsing (oracle robustness).
 */
val P2pJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
