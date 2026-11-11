/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package util

import java.io.IOException
import java.io.InputStream







fun InputStream.readVarInt32(): Int {
  var result = 0

  
  for (shift in 0 until 32 step 7) {
    
    
    val byte: Int = read()
    if (byte < 0) {
      return -1
    }

    val lowestSevenBits = byte and 0x7F
    val shiftedBits = lowestSevenBits shl shift

    result = result or shiftedBits

    
    if (byte and 0x80 == 0) {
      return result
    }
  }

  throw IOException("Malformed varint!")
}




@Throws(IOException::class)
fun InputStream.readFully(autoClose: Boolean = true): ByteArray {
  return StreamUtil.readFully(this, Integer.MAX_VALUE, autoClose)
}





@Throws(IOException::class)
fun InputStream.readFully(buffer: ByteArray) {
  return StreamUtil.readFully(this, buffer)
}





@Throws(IOException::class)
fun InputStream.readNBytesOrThrow(length: Int): ByteArray {
  val buffer = ByteArray(length)
  this.readFully(buffer)
  return buffer
}

@Throws(IOException::class)
fun InputStream.readLength(): Long {
  val buffer = ByteArray(4096)
  var count = 0L

  while (this.read(buffer).also { if (it > 0) count += it } != -1) {
    
  }

  return count
}




@Throws(IOException::class)
fun InputStream.drain() {
  this.readLength()
}
