package com.difft.android.base.ui.sidechrome

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.getSafeActivity
import java.util.Collections
import java.util.WeakHashMap


interface SideChromeHost {
    val sideChrome: SideChromeController?
}





val LocalSideChromeEnabled = staticCompositionLocalOf { true }






interface SideChromeOptOut {
    val keepsOwnChrome: Boolean
}


internal fun Fragment.keepsOwnChrome(): Boolean =
    generateSequence(this) { it.parentFragment }.any { it is DialogFragment || (it as? SideChromeOptOut)?.keepsOwnChrome == true }

object SideChrome {

    
    private val hosts: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    fun of(context: Context): SideChromeController? = (context.getSafeActivity() as? SideChromeHost)?.sideChrome

    fun register(activity: Activity) {
        hosts.add(activity)
    }

    fun unregister(activity: Activity) {
        hosts.remove(activity)
    }

    



    fun applyPlacement(context: Context, placement: NavigationPlacement): List<Activity> {
        SideChromeSettings.setPlacement(context, placement)
        val live = hosts.filter { !it.isFinishing && !it.isDestroyed }
        L.i { "[SideChrome] placement=${placement.name.lowercase()} recreate=${live.size}" }
        live.forEach { ActivityCompat.recreate(it) }
        return live
    }
}


@Composable
fun currentSideChrome(): SideChromeController? {
    if (!LocalSideChromeEnabled.current) return null
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) {
        val page = runCatching { FragmentManager.findFragment<Fragment>(view) }.getOrNull()
        if (page?.keepsOwnChrome() == true) null else SideChrome.of(context)
    }
}






@Composable
fun PublishSideChrome(model: SideChromeModel): Boolean {
    val controller = currentSideChrome() ?: return false
    val owner = LocalLifecycleOwner.current
    val entry = remember(controller, owner) { controller.registry.publish(owner, model) }
    SideEffect { entry?.update(model) }
    DisposableEffect(entry) { onDispose { entry?.remove() } }
    return controller.active
}





fun Fragment.publishSideChrome(model: SideChromeModel): SideChromeRegistry.Entry? {
    if (keepsOwnChrome()) return null
    val controller = activity?.let(SideChrome::of) ?: return null
    return controller.registry.publish(viewLifecycleOwner, model)
}
