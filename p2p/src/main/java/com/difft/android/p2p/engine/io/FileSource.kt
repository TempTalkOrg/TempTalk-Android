package com.difft.android.p2p.engine.io

import okio.Source






interface FileSource {
    val fileName: String
    val size: Long
    val mimeType: String

    
    val sha256: String?

    
    fun openRead(): Source
}
