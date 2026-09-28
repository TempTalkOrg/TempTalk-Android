package com.difft.android.translate

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges a GMS [Task] to a suspending call without pulling in `kotlinx-coroutines-play-services`
 * (the repository does not depend on it, and a new dependency has to be mirrored into
 * `gradle/verification-metadata.xml`).
 *
 * The third listener is the load-bearing one: ML Kit finishes some model-download failures by
 * cancelling the Task instead of completing it with success or failure. Without the isCanceled
 * branch the continuation is never resumed, and the caller hangs forever — in the download case
 * holding the store's single in-flight slot for the rest of the process's life.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCompleteListener { if (it.isCanceled) cont.cancel() }
}
