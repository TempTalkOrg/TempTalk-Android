package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * State-driven wait: shows [wait] while [running] is true and this page is the visible top of its
 * stack ([detailBackOwner]); a covered, hidden or popped page lets go. When only the Activity
 * stopped (background) the claim is kept, so coming back does not mount the dialog again (a
 * visible blink); if the work ended meanwhile, the first value on return releases it. The claim
 * also goes with the view (see [PageWait]). Call once per view, from `onViewCreated`.
 */
fun Fragment.waitWhileVisibleTop(running: Flow<Boolean>, wait: PageWait) {
    val visibility = detailBackOwner()
    val activity = requireActivity()
    viewLifecycleOwner.lifecycleScope.launch {
        visibility.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                running.distinctUntilChanged().collect { wait.update(it) }
            } finally {
                // The Activity's lifecycle reaches CREATED before its fragments stop, so a stop
                // caused by the Activity reads as not started here.
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) wait.dismiss()
            }
        }
    }
}
