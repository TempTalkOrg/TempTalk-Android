package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber










































@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UserAuthData(
    @ProtoNumber(1) val account: String = "",
    @ProtoNumber(2) val baseAuth: String = "",
    @ProtoNumber(3) val microToken: String = "",
    @ProtoNumber(4) val email: String = "",
    @ProtoNumber(5) val phoneNumber: String = "",
    @ProtoNumber(6) val customUid: String = "",
    @ProtoNumber(7) val contactRequestStatus: String = "",
    @ProtoNumber(8) val passcode: String = "",
    @ProtoNumber(9) val pattern: String = "",
    @ProtoNumber(10) val signalingKey: String = "",
    @ProtoNumber(11) val aciIdentityPublicKey: String = "",
    @ProtoNumber(12) val aciIdentityPrivateKey: String = "",
    @ProtoNumber(13) val aciIdentityOldPublicKey: String = "",
    @ProtoNumber(14) val aciIdentityOldPrivateKey: String = "",
    @ProtoNumber(15) val aciIdentityKeyGenTime: Long = 0L,
    





    @ProtoNumber(16) val migrationV1Completed: Boolean = false,
    





    @ProtoNumber(17) val proxyShareLink: String = "",
    




    @ProtoNumber(18) val proxyEnabled: Boolean = false,
    




    @ProtoNumber(19) val proxyProtectCallIp: Boolean = false,
    






    @ProtoNumber(20) val favKey: String = "",
    
    @ProtoNumber(21) val favKeyId: String = "",
    
    @ProtoNumber(22) val favKeyVersion: Int = 0,
) {
    companion object {
        val EMPTY = UserAuthData()
    }
}
