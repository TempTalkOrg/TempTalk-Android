package org.thoughtcrime.securesms.util

data class LocalMetricsSplit(
  val name: String,
  val duration: Long
) {
  override fun toString(): String {
    return "$name: $duration"
  }
}
