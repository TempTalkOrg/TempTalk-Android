package com.difft.android.p2p.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable






@Serializable
data class ReversePairRequest(
    
    @SerialName("protocol") val protocol: Int = REVERSE_PAIR_PROTOCOL,
    @SerialName("device") val device: ReversePairDevice,
    @SerialName("listen") val listen: ReversePairListen,
    
    @SerialName("pairToken") val pairToken: String,
    @SerialName("precheck") val precheck: ReversePairPrecheck,
)


@Serializable
data class ReversePairDevice(
    @SerialName("alias") val alias: String,
    @SerialName("platform") val platform: String = PLATFORM_ANDROID,
    @SerialName("appVersion") val appVersion: String,
    
    @SerialName("fingerprint") val fingerprint: String,
)


@Serializable
data class ReversePairListen(
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("port") val port: Int,
)





@Serializable
data class ReversePairPrecheck(
    @SerialName("env") val env: String,
    @SerialName("formatVersion") val formatVersion: String,
    @SerialName("account") val account: String,
)





@Serializable
data class ReversePairResponse(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("device") val device: ReversePairPeer? = null,
    @SerialName("connectWithinMs") val connectWithinMs: Long? = null,
)


@Serializable
data class ReversePairPeer(
    @SerialName("alias") val alias: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
)


const val REVERSE_PAIR_PROTOCOL = 1


const val PLATFORM_ANDROID = "android"
