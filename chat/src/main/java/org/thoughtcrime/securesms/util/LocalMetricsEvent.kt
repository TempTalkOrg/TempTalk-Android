package org.thoughtcrime.securesms.util

data class LocalMetricsEvent(
  val createdAt: Long,
  val eventId: String,
  val eventName: String,
  val splits: MutableList<LocalMetricsSplit>
) {
  override fun toString(): String {
    return "[$eventName] total: ${splits.sumOf { it.duration }} | ${splits.map { it.toString() }.joinToString(", ")}"
  }
}
