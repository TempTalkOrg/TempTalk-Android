/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.difft.android.video.exceptions

/**
 * Exception to denote when video processing has had an issue with its source input.
 */
class VideoSourceException : Exception {
  constructor(message: String?) : super(message)
  constructor(message: String?, inner: Exception?) : super(message, inner)
}
