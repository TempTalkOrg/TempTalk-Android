package com.difft.android.network.responses


import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

data class ConversationMsgInfoPojo(
    @SerializedName("conversationMsgInfos")
    @Expose
    val conversationMsgInfos: List<String?>?,
    @SerializedName("hasMore")
    @Expose
    val hasMore: Boolean
)