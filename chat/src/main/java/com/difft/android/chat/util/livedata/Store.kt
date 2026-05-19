package com.difft.android.chat.util.livedata

import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.difft.android.base.concurrent.AppExecutors
import com.difft.android.chat.util.concurrent.SerialExecutor
import java.util.concurrent.Executor

/**
 * Manages a state to be updated from a view model and provide direct and live access. Updates
 * occur serially on the same executor to allow updating in a thread safe way. While not every
 * state update is guaranteed to be emitted, no update action will be dropped and state that is
 * emitted will be accurate.
 */
open class Store<State>(initialState: State) {

    private val liveStore: LiveDataStore = LiveDataStore(initialState)

    val stateLiveData: LiveData<State> get() = liveStore

    val state: State get() = liveStore.currentState()

    @AnyThread
    fun update(updater: (State) -> State) {
        liveStore.update(updater)
    }

    @MainThread
    fun <Input> update(source: LiveData<Input>, action: Action<Input, State>) {
        liveStore.update(source, action)
    }

    @MainThread
    fun clear() {
        liveStore.clear()
    }

    fun interface Action<Input, S> {
        fun apply(input: Input, current: S): S
    }

    private inner class LiveDataStore(initialState: State) : MediatorLiveData<State>() {

        @Volatile private var state: State = initialState
        private val stateUpdater: Executor = SerialExecutor(AppExecutors.Default)
        private val sources: MutableSet<LiveData<*>> = HashSet()

        init {
            setState(initialState)
        }

        @Synchronized
        fun currentState(): State = state

        @Synchronized
        private fun setState(newState: State) {
            this.state = newState
            postValue(newState)
        }

        fun <Input> update(source: LiveData<Input>, action: Action<Input, State>) {
            sources.add(source)
            addSource(source) { input ->
                stateUpdater.execute { setState(action.apply(input, currentState())) }
            }
        }

        fun update(updater: (State) -> State) {
            stateUpdater.execute { setState(updater(currentState())) }
        }

        fun clear() {
            for (source in sources) removeSource(source)
            sources.clear()
        }
    }
}
