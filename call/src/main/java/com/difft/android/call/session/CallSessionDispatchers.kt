package com.difft.android.call.session

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatchers of a [CallSession]. Production binds `Dispatchers.Default` and
 * `Dispatchers.Main.immediate` (see `CallSessionModule`); tests pass test dispatchers.
 */
class CallSessionDispatchers(val default: CoroutineDispatcher, val main: CoroutineDispatcher)
