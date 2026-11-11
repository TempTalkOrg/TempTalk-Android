package com.difft.android.p2p.engine.model





enum class TransferDirection(val wire: String) {
    
    UPLOAD("upload"),

    




    DOWNLOAD("download");

    companion object {
        
        fun of(dir: String?): TransferDirection =
            entries.firstOrNull { it.wire == dir?.trim()?.lowercase() } ?: UPLOAD
    }
}


val QrPayload.direction: TransferDirection
    get() = TransferDirection.of(dir)
