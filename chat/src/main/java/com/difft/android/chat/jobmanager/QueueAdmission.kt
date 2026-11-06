package com.difft.android.chat.jobmanager

/** Durable admission says nothing about execution or delivery. */
sealed interface QueueAdmission {
    data class Persisted(val jobId: String) : QueueAdmission
    data object RejectedLimit : QueueAdmission
    data class FailedBeforePersist(val reason: String) : QueueAdmission
    data class Unconfirmed(val jobId: String, val reason: String) : QueueAdmission
}
