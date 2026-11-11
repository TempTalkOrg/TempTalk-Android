package com.difft.android.chat.recent










class TimeTextFormatTracker(private val readSignature: () -> String) {

    @Volatile
    private var formattedWith: String? = null

    fun markFormatted(signature: String) {
        formattedWith = signature
    }

    
    fun needsRefresh(): Boolean {
        val formatted = formattedWith ?: return false
        return readSignature() != formatted
    }
}
