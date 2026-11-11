package com.difft.android.base.log.lumberjack

import com.difft.android.base.log.lumberjack.data.TimerData





object T {
    private val mTimers = HashMap<Any, TimerData>()
    private val mEmptyData = TimerData()

    
    
    

    






    fun start(key: Any): Boolean {
        val timerData = clear(key)
        mTimers[key] = TimerData().start()
        return timerData != null
    }

    





    fun lap(key: Any): Long? {
        val data = getTimer(key)
        return data.lap()
    }

    





    fun stop(key: Any): Long? {
        val data = getTimer(key)
        return data.stop()
    }

    





    fun clear(key: Any): TimerData? {
        return mTimers.remove(key)
    }

    




    fun exists(key: Any): Boolean {
        return mTimers.containsKey(key)
    }

    
    
    

    






    fun printAndStart(key: Any): String {
        val replaced = start(key)
        val data = getTimer(key)
        return "New timer started at ${data.getStartTime()}${if (replaced) " [old timer has been replaced!]" else ""}"
    }

    





    fun printAndLap(key: Any): String {
        val lap = lap(key) ?: return "NULL"
        return "Lap = " + lap + "ms"
    }

    





    fun printAndStop(key: Any): String {
        val stop = stop(key) ?: return "NULL"
        return "Total = " + stop + "ms"
    }

    





    fun printAndLapTotal(key: Any): String {
        val lap = lap(key) ?: return "NULL"
        val data = getTimer(key)
        val total = data.getTotal() ?: "NULL"
        return "Total = " + total + "ms | Lap = " + lap + "ms"
    }

    fun print(key: Any): String {
        if (!exists(key)) {
            return "Timer[$key] does not exist!"
        }

        val data = getTimer(key)
        var info = "Started: ${data.wasStarted()}"
        data.getLaps()?.size?.let {
            info += " | Laps: ${it}"
        }
        info += " | Total = ${data.getTotal()}ms | Running: ${data.isRunning()}"
        return info
    }





    fun getStart(key: Any): Long? {
        val data = mTimers[key]
        return data?.getStart()
    }

    fun getLaps(key: Any): List<Long>? {
        val data = mTimers[key]
        return data?.getLaps()
    }

    fun getEnd(key: Any): Long? {
        val data = mTimers[key]
        return data?.getEnd()
    }





    private fun getTimer(key: Any): TimerData = mTimers[key] ?: mEmptyData
}