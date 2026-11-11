package com.difft.android.me

import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.installGlassBackdrop
import com.difft.android.databinding.MeFragmentBinding
import com.difft.android.home.HomePageGlassBackdrop


internal fun installMeGlassChrome(owner: LifecycleOwner, binding: MeFragmentBinding) {
    if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED || !GlassMode.isLive(binding.root.context)) return
    val source = binding.root.installGlassBackdrop(binding.scrollView2) { canvas ->
        
        canvas.drawColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg))
    }
    
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) source.close()
    })
    HomePageGlassBackdrop(binding.root, owner, source)
}
