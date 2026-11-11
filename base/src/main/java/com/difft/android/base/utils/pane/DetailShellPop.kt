package com.difft.android.base.utils.pane

import android.app.Activity
import android.content.Intent


object DetailShellPop {
    const val RESULT_POP_MORE = 0x504f50
    const val EXTRA_REMAINING = "detail_shell_remaining"
    const val EXTRA_REQUEST_ID = "detail_shell_request_id"
    const val EXTRA_SUBJECT_ID = "detail_shell_subject_id"

    internal data class Request(val id: String, val subjectId: String?) {
        fun writeTo(intent: Intent): Intent = intent.putExtra(EXTRA_REQUEST_ID, id)
            .putExtra(EXTRA_SUBJECT_ID, subjectId)
    }

    internal fun request(intent: Intent?): Request? {
        val id = intent?.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return null
        if (!intent.hasExtra(EXTRA_SUBJECT_ID)) return null
        return Request(id, intent.getStringExtra(EXTRA_SUBJECT_ID))
    }

    fun finish(activity: Activity, count: Int): Boolean {
        if (count <= 0) return false
        
        val origin = (activity as? DetailShellActivity)?.inheritedShellRequest
        if (count > 1 && origin != null) {
            activity.setResult(RESULT_POP_MORE, origin.writeTo(Intent().putExtra(EXTRA_REMAINING, count - 1)))
        }
        activity.finish()
        return true
    }

    fun consume(activity: Activity, resultCode: Int, data: Intent?): Boolean =
        (activity as? DetailShellActivity)?.consumeShellResult(resultCode, data) ?: false
}
