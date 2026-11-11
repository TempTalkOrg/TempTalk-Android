package com.difft.android.call.ui.barrage

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.LCallViewModel
import com.difft.android.call.ui.tabletop.LocalCallTabletop







@Composable
fun BubbleOverlayWindowHost(viewModel: LCallViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity() ?: return
    
    
    val tabletopStageBottomPx = LocalCallTabletop.current?.bandTopPx
    val stageBottomPx = remember { mutableStateOf<Int?>(null) }
    SideEffect { stageBottomPx.value = tabletopStageBottomPx }

    DisposableEffect(activity, lifecycleOwner) {
        if (activity.isFinishing || activity.isDestroyed) {
            L.w { "[BubbleOverlay] Activity finishing/destroyed, skip overlay window" }
            return@DisposableEffect onDispose { }
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        
        
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            (activity as? ViewModelStoreOwner)?.let { setViewTreeViewModelStoreOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner)
            )
            setContent {
                
                
                
                
                DifftTheme(darkTheme = true, applyWindowBackground = false) {
                    CompositionLocalProvider(LocalBubbleStageBottomPx provides stageBottomPx.value) {
                        BubbleOverlayLayer(viewModel = viewModel)
                    }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            
            
            
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            
            token = activity.window.decorView.windowToken
            gravity = Gravity.TOP or Gravity.START
            
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            windowManager.addView(composeView, params)
        } catch (t: Throwable) {
            
            L.w(t) { "[BubbleOverlay] addView failed, skip overlay window" }
            return@DisposableEffect onDispose { }
        }

        
        
        var removed = false
        val removeOverlay: () -> Unit = {
            if (!removed) {
                removed = true
                runCatching { windowManager.removeViewImmediate(composeView) }
                    .onFailure { L.w(it) { "[BubbleOverlay] removeView failed" } }
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                removeOverlay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            removeOverlay()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
