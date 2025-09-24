package com.difft.android.network.responses


import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

data class HotMsgPojo(
    @SerializedName("maxServerTimestamp")
    @Expose
    val maxServerTimestamp: Long,
    @SerializedName("messages")
    @Expose
    val messages: List<String?>?,
    @SerializedName("minServerTimestamp")
    @Expose
    val minServerTimestamp: Long,
    @SerializedName("surplus")
    @Expose
    val surplus: Int
)