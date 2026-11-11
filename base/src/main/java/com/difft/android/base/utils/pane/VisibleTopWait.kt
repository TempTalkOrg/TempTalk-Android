package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch








fun Fragment.waitWhileVisibleTop(running: Flow<Boolean>, wait: PageWait) {
    val visibility = detailBackOwner()
    val activity = requireActivity()
    viewLifecycleOwner.lifecycleScope.launch {
        visibility.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                running.distinctUntilChanged().collect { wait.update(it) }
            } finally {
                
                
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) wait.dismiss()
            }
        }
    }
}
