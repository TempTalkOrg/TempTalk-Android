package com.difft.android.base.log.lumberjack

import android.util.Log

/**
 * Per-day byte budget for INFO lines in the file log. WARN and ERROR are never dropped, so a
 * runaway INFO loop (the 2026-10 audit saw 7 MB days) cannot push the diagnostics out of the
 * 7-day upload window. Callers serialize access (single logger thread or synchronized sink).
 */
class DailyLogBudget(private val limitBytes: Long) {

    enum class Admission { WRITE, DROP, WRITE_EXHAUSTED_MARKER }

    private var day = ""
    private var bytesToday = 0L
    private var markerWritten = false

    /**
     * @param day calendar day of the line, e.g. "2026-10-10"; a change resets the budget
     * @return whether to write the line, drop it, or write the one-time "budget exhausted" marker
     *         instead of it (the caller writes the marker at WARN and drops the line).
     */
    fun admit(day: String, priority: Int, bytes: Int): Admission {
        if (day != this.day) {
            this.day = day
            bytesToday = 0L
            markerWritten = false
        }
        if (priority > Log.INFO) return Admission.WRITE
        bytesToday += bytes
        if (bytesToday <= limitBytes) return Admission.WRITE
        if (markerWritten) return Admission.DROP
        markerWritten = true
        return Admission.WRITE_EXHAUSTED_MARKER
    }
}
