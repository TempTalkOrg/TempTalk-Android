package com.difft.android.base

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.LanguageUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
abstract class BaseActivity : AppCompatActivity() {
    private val activityStartTimestamp: Long = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppStartup.onCriticalRenderEventStart()
        super.onCreate(savedInstanceState)
        AppStartup.onCriticalRenderEventEnd()
        L.i { "[BaseActivity]${javaClass.name} Activity onCreate cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
    }

    override fun onResume() {
        super.onResume()
        L.i { "[BaseActivity]${javaClass.name} Activity onResume cost: ${System.currentTimeMillis() - activityStartTimestamp}" }
    }


    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LanguageUtils.updateBaseContextLocale(context))
    }

    override fun onDestroy() {
        super.onDestroy()
        L.i { "[BaseActivity]${javaClass.name} Activity onDestroy" } }
}