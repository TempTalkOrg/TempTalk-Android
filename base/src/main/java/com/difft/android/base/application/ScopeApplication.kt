package com.difft.android.base.application

import android.app.Application
import kotlinx.coroutines.CoroutineScope

abstract class ScopeApplication : Application(), CoroutineScope {
}