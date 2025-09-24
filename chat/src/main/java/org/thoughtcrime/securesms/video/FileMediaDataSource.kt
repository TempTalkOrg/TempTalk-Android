package org.thoughtcrime.securesms.video

import android.media.MediaDataSource
import androidx.annotation.RequiresApi
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.FileInputStream
import java.io.IOException

@RequiresApi(23)
class FileMediaDataSource(private val file: File) : MediaDataSource() {

    private val fileInputStream: FileInputStream = FileInputStream(file)
    private val fileSize: Long = file.length()

    @Throws(IOException::class)
    override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int {
        L.d { "readAt position: $position, length requested: $length" }
        if (position >= fileSize) {
            return -1
        }

        FileInputStream(file).use { fileInputStream ->
            fileInputStream.channel.position(position)

            var totalRead = 0
            while (totalRead < length) {
                val bytesRead = fileInputStream.read(bytes, offset + totalRead, length - totalRead)
                if (bytesRead == -1) {
                    return if (totalRead == 0) -1 else totalRead
                }
                totalRead += bytesRead
            }

            L.d { "readAt totalRead: $totalRead" }
            return totalRead
        }
    }

    @Throws(IOException::class)
    override fun getSize(): Long {
        return fileSize
    }

    @Throws(IOException::class)
    override fun close() {
        fileInputStream.close()
    }
}


