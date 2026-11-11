package com.difft.android.base.migration









object MigrationQrWire {
    
    const val MAGIC_V2: Byte = 0x02

    
    const val MAX_PAYLOAD_BYTES = 512

    



    const val MIN_BARE_TEXT_LENGTH = 65

    
    const val FLAG_DOWNLOAD = 0x01

    
    fun isDownloadPayload(payload: ByteArray): Boolean =
        payload.size >= 2 && payload[0] == MAGIC_V2 && payload[1].toInt() and FLAG_DOWNLOAD != 0

    
    fun stripLineBreaks(raw: String): String = raw.trim('\r', '\n')

    
    fun looksLikeBarePayload(raw: String): Boolean {
        val text = stripLineBreaks(raw)
        return text.length >= MIN_BARE_TEXT_LENGTH && Base45.isAlphabet(text)
    }
}
