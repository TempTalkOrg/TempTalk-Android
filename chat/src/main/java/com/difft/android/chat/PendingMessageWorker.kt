package com.difft.android.chat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class PendingMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            L.i { "[Message][PendingMessageHelper][PendingMessageWorker] Starting pending message processing" }

            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
            val success = entryPoint.pendingMessageHelper.obtainPendingMessageAndSave()

            if (success) {
                L.i { "[Message][PendingMessageHelper][PendingMessageWorker] Pending message processing completed successfully" }
                Result.success()
            } else {
                L.w { "[Message][PendingMessageHelper][PendingMessageWorker] Pending message processing failed, no retry needed" }
                Result.failure()
            }

        } catch (e: Exception) {
            L.e { "[Message][PendingMessageHelper][PendingMessageWorker] Error during pending message processing: ${e.stackTraceToString()}" }
            Result.failure()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        val pendingMessageHelper: PendingMessageHelper
    }
}
