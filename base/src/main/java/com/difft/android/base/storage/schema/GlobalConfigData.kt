package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber











@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GlobalConfigData(
    
    @ProtoNumber(1) val config: String = "",
    
    @ProtoNumber(2) val callServiceUrlStateV3: String = "",
    
    @ProtoNumber(3) val migrationV1Completed: Boolean = false,
    




    @ProtoNumber(4) val pendingLogin: String = "",
) {
    companion object {
        val EMPTY = GlobalConfigData()
    }
}
