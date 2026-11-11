package com.difft.android.p2p.engine.upload








object UploadReplyHeaders {
    
    const val HANDOFF_INSTALLED = "X-Migration-Handoff-Installed"

    
    fun handoffInstalled(headers: Map<String, String>): Boolean =
        headers.entries.any { it.key.equals(HANDOFF_INSTALLED, ignoreCase = true) && it.value.equals("true", ignoreCase = true) }
}
