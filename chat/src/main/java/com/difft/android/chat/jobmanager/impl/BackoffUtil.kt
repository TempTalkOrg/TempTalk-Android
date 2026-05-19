package com.difft.android.chat.jobmanager.impl

import kotlin.math.pow
import kotlin.random.Random

object BackoffUtil {

  /**
   * Simple exponential backoff with random jitter.
   * @param pastAttemptCount The number of attempts that have already been made.
   *
   * @return The calculated backoff.
   */
  @JvmStatic
  fun exponentialBackoff(pastAttemptCount: Int, maxBackoff: Long): Long {
    require(pastAttemptCount >= 1) { "Bad attempt count! $pastAttemptCount" }

    val boundedAttempt = pastAttemptCount.coerceAtMost(30)
    val exponentialBackoff = 2.0.pow(boundedAttempt).toLong() * 1000
    val actualBackoff = exponentialBackoff.coerceAtMost(maxBackoff)
    val jitter = 0.75 + (Random.nextFloat() * 0.5)

    return (actualBackoff * jitter).toLong()
  }
}
