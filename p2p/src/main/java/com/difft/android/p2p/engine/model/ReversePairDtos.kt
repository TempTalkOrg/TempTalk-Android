package com.difft.android.p2p.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /api/migration/v1/reverse-pair?pin=<PIN>` — the phone asking a computer that displayed an
 * export code to send its history here.
 *
 * The direction of the bytes is unchanged from phone→phone: the computer becomes the uploading
 * client and this device stays the receiving server. This request only tells it where to connect and
 * proves the phone read the code; the peer holds the response open while its user confirms.
 */
@Serializable
data class ReversePairRequest(
    /** Wire-format generation. A peer that does not know the value answers 400 `unsupported-protocol`. */
    @SerialName("protocol") val protocol: Int = REVERSE_PAIR_PROTOCOL,
    @SerialName("device") val device: ReversePairDevice,
    @SerialName("listen") val listen: ReversePairListen,
    /** 32 random bytes, base64url. Single-use, matched before the PIN at `/prepare-upload`. Never logged. */
    @SerialName("pairToken") val pairToken: String,
    @SerialName("precheck") val precheck: ReversePairPrecheck,
)

/** This device's identity, including the server certificate fingerprint the peer must pin. */
@Serializable
data class ReversePairDevice(
    @SerialName("alias") val alias: String,
    @SerialName("platform") val platform: String = PLATFORM_ANDROID,
    @SerialName("appVersion") val appVersion: String,
    /** This device's receiver certificate SHA-256, hex. Closes the trust chain in the other direction. */
    @SerialName("fingerprint") val fingerprint: String,
)

/** Where the peer connects back: LAN addresses only, first preferred, at most four. */
@Serializable
data class ReversePairListen(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("port") val port: Int,
)

/**
 * The same source-compat identity the QR advertises, so the peer can refuse an impossible transfer
 * before its user is asked to confirm anything.
 */
@Serializable
data class ReversePairPrecheck(
    @SerialName("env") val env: String,
    @SerialName("formatVersion") val formatVersion: String,
    @SerialName("account") val account: String,
)

/**
 * The 200 the peer returns once its user confirmed. [connectWithinMs] starts counting here, not at
 * the request: everything before the 200 is the confirmation hang.
 */
@Serializable
data class ReversePairResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("device") val device: ReversePairPeer? = null,
    @SerialName("connectWithinMs") val connectWithinMs: Long? = null,
)

/** The peer's self-description in the 200; advisory, shown on the waiting page. */
@Serializable
data class ReversePairPeer(
    @SerialName("alias") val alias: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
)

/** The only protocol generation this app speaks; a peer that answers otherwise must be updated. */
const val REVERSE_PAIR_PROTOCOL = 1

/** Platform name this app reports in [ReversePairDevice.platform]. */
const val PLATFORM_ANDROID = "android"
