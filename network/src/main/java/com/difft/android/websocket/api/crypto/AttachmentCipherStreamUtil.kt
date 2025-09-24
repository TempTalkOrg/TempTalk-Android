/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.websocket.api.crypto

class AttachmentCipherStreamUtil {
  companion object {
    @JvmStatic
    fun getCiphertextLength(plaintextLength: Long): Long {
      return 16 + (plaintextLength / 16 + 1) * 16 + 32
    }

    @JvmStatic
    fun getPlaintextLength(ciphertextLength: Long): Long {
      return ((ciphertextLength - 16 - 32) / 16 - 1) * 16
    }
  }
}
