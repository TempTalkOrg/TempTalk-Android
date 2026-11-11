/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.video.videoconverter.utils

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

object Extensions {
  




  @JvmStatic
  fun isWithin(expected: Long, actual: Long, tolerance: Float): Boolean {
    val floor = floor(expected * (1 - tolerance)).roundToLong()
    val ceiling = ceil(expected * (1 + tolerance)).roundToLong()
    return actual in floor..ceiling
  }
}
