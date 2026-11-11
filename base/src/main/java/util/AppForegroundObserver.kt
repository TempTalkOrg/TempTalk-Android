package util

import android.app.Activity
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile





object AppForegroundObserver {
  private val listeners: MutableSet<Listener> = CopyOnWriteArraySet()

  @Volatile
  private var isInitialized: Boolean = false

  @Volatile
  private var isForegrounded: Boolean = false

  @Volatile
  private var startedActivityCount: Int = 0

  
  private val resumedSinceStart = mutableListOf<WeakReference<Activity>>()

  @MainThread
  @JvmStatic
  fun begin() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Must be called on the main thread" }

    ProcessLifecycleOwner.Companion.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onStart(owner: LifecycleOwner) {
        onForeground()
      }

      override fun onStop(owner: LifecycleOwner) {
        onBackground()
      }
    })

    isInitialized = true
  }

  





  @AnyThread
  @JvmStatic
  fun addListener(listener: Listener) {
    listeners.add(listener)

    if (isInitialized) {
      if (isForegrounded) {
        listener.onForeground()
      } else {
        listener.onBackground()
      }
    }
  }

  @AnyThread
  @JvmStatic
  fun removeListener(listener: Listener) {
    listeners.remove(listener)
  }

  @JvmStatic
  fun isForegrounded(): Boolean {
    return isInitialized && isForegrounded
  }

  @JvmStatic
  fun notifyActivityStarted() {
    startedActivityCount++
  }

  @MainThread
  @JvmStatic
  fun notifyActivityResumed(activity: Activity) {
    if (resumedSinceStart.none { it.get() === activity }) {
      resumedSinceStart.add(WeakReference(activity))
    }
  }

  @MainThread
  @JvmStatic
  fun notifyActivityStopped(activity: Activity) {
    startedActivityCount--
    resumedSinceStart.removeAll { it.get().let { ref -> ref == null || ref === activity } }
  }

  





  @MainThread
  @JvmStatic
  fun hasUserFacingActivity(): Boolean =
    resumedSinceStart.any { ref -> ref.get()?.let { !it.isInPictureInPictureMode } == true }

  @JvmStatic
  fun getStartedActivityCount(): Int = startedActivityCount

  @MainThread
  private fun onForeground() {
    isForegrounded = true

    for (listener in listeners) {
      listener.onForeground()
    }
  }

  @MainThread
  private fun onBackground() {
    isForegrounded = false

    for (listener in listeners) {
      listener.onBackground()
    }
  }

  interface Listener {
    fun onForeground() {}
    fun onBackground() {}
  }
}