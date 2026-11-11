/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.video.interfaces

import android.media.MediaExtractor
import java.io.Closeable
import java.io.IOException




interface MediaInput : Closeable {
  @Throws(IOException::class)
  fun createExtractor(): MediaExtractor

  fun hasSameInput(other: MediaInput): Boolean
}
