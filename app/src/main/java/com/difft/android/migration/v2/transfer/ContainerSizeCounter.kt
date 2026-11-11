package com.difft.android.migration.v2.transfer

import com.difft.android.migration.v2.container.ArchiveSink
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong










class ContainerSizeCounter : ArchiveSink {

    private val counted = AtomicLong(0L)

    
    val byteCount: Long get() = counted.get()

    override fun openStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            counted.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            counted.addAndGet(len.toLong())
        }
    }

    override fun abort() {
        
    }
}
