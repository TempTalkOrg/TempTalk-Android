package com.difft.android.network.proxy

import android.util.Base64
import java.security.MessageDigest
import java.security.cert.X509Certificate







object ProxySpki {

    
    fun pinOf(cert: X509Certificate): String {
        
        
        
        
        
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    



    fun matches(cert: X509Certificate, expectedPin: String): Boolean {
        val expected = expectedPin.trim()
        if (expected.isEmpty()) return false
        return constantTimeEquals(pinOf(cert), expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }
}
