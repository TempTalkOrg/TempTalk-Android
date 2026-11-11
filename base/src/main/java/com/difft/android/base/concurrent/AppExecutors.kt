package com.difft.android.base.concurrent

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
















object AppExecutors {

    
    @JvmField
    val Default: Executor = Dispatchers.Default.asExecutor()

    
    @JvmField
    val IO: Executor = Dispatchers.IO.asExecutor()

    
    @JvmField
    val Main: Executor = Dispatchers.Main.asExecutor()

    
    @JvmStatic
    fun mainHandler(): Handler = MainHandlerHolder.handler

    private object MainHandlerHolder {
        val handler: Handler = Handler(Looper.getMainLooper())
    }
}
