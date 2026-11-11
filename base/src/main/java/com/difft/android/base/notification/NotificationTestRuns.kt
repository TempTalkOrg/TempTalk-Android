package com.difft.android.base.notification

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


data class NotificationTestOwner(val ownerId: String, val host: Host) {
    enum class Host { HOSTED, SHELL }
}

data class NotificationTestRun(val runId: String, val owner: NotificationTestOwner, val kind: NotificationTestKind)


@Singleton
class NotificationTestRuns @Inject constructor() {
    private var current: NotificationTestRun? = null

    @Synchronized
    fun claim(owner: NotificationTestOwner, kind: NotificationTestKind): NotificationTestRun? {
        if (current != null) return null
        return NotificationTestRun(UUID.randomUUID().toString(), owner, kind).also { current = it }
    }

    @Synchronized
    fun owns(run: NotificationTestRun): Boolean = current == run

    @Synchronized
    fun matches(ownerId: String, runId: String, kind: NotificationTestKind): Boolean =
        current?.let { it.owner.ownerId == ownerId && it.runId == runId && it.kind == kind } == true

    @Synchronized
    fun release(run: NotificationTestRun) {
        if (current == run) current = null
    }
}
