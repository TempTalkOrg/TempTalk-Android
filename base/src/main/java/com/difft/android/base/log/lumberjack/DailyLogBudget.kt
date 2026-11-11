package com.difft.android.base.log.lumberjack

import android.util.Log






class DailyLogBudget(private val limitBytes: Long) {

    enum class Admission { WRITE, DROP, WRITE_EXHAUSTED_MARKER }

    private var day = ""
    private var bytesToday = 0L
    private var markerWritten = false

    




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
