package com.difft.android.network.responses

import com.google.gson.annotations.SerializedName















data class DeletedRecordDto(
    @SerializedName("uid") val uid: String?,
    @SerializedName("reason") val reason: Int,            
    @SerializedName("name") val name: String?,
    @SerializedName("avatar") val avatar: String?,        
    @SerializedName("deleteTime") val deleteTime: Long,   
    @SerializedName("expireTime") val expireTime: Long    
)
