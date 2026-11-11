package com.difft.android.base.session















interface AuthFailureSink {
    
    fun onAuthRejected(source: String)
}





object AuthFailureSource {
    
    const val WS_HANDSHAKE = "wsHandshake"

    
    const val WS_RESPONSE = "wsResponse"

    
    const val DEVICE_API = "deviceApi"

    
    const val MESSAGE_SEND = "messageSend"
}
