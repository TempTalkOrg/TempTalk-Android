package com.difft.android.p2p.engine.io

import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import okio.Sink






interface FileSink {

    
    fun openWrite(meta: FileMetadata): Sink

    





    fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String>

    



    fun discard(meta: FileMetadata) {}

    




    fun successHeaders(meta: FileMetadata): Map<String, String> = emptyMap()
}
