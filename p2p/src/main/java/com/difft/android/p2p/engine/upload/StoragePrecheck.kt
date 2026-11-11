package com.difft.android.p2p.engine.upload






fun interface StoragePrecheck {
    




    fun hasRoomFor(declaredBytes: Long): Boolean
}
