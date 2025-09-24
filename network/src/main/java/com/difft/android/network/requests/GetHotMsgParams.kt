package com.difft.android.network.requests


import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

/**
 * maxSequenceId - minSequenceId must <= 100
 */
data class GetHotMsgParams(
    @SerializedName("gid")
    @Expose
    val gid: String,
    @SerializedName("maxSequenceId")
    @Expose
    val maxSequenceId: Long?,
    @SerializedName("minSequenceId")
    @Expose
    val minSequenceId: Long?,
    @SerializedName("number")
    @Expose
    val number: String,
    @SerializedName("sequenceIds")
    @Expose
    val sequenceIds: List<Long>
)