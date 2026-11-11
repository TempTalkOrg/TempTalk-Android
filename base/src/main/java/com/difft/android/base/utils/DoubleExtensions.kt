/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.base.utils

import java.util.Locale







fun Double.roundedString(places: Int): String {
  return String.format(Locale.US, "%.${places}f", this)
}
