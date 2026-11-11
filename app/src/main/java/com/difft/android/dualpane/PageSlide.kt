package com.difft.android.dualpane

import android.animation.Animator
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.transition.TransitionValues
import androidx.transition.Visibility











internal class PageSlide private constructor(private val role: Role) : Visibility() {

    enum class Role { ENTER_OVER, EXIT_UNDER, LEAVE_OVER, RETURN_UNDER }

    override fun onAppear(sceneRoot: ViewGroup, view: View, startValues: TransitionValues?, endValues: TransitionValues?): Animator? =
        when (role) {
            Role.ENTER_OVER -> PaneMotion.slideIn(view)
            Role.RETURN_UNDER -> PaneMotion.parallaxBack(view)
            else -> null
        }.also { if (it != null) onRun?.invoke(role) }

    override fun onDisappear(sceneRoot: ViewGroup, view: View, startValues: TransitionValues?, endValues: TransitionValues?): Animator? =
        when (role) {
            Role.EXIT_UNDER -> PaneMotion.parallaxOut(view)
            Role.LEAVE_OVER -> PaneMotion.slideOut(view)
            else -> null
        }.also { if (it != null) onRun?.invoke(role) }

    companion object {
        
        internal var onRun: ((Role) -> Unit)? = null

        
        fun push(entering: Fragment, covered: Fragment?) {
            entering.enterTransition = PageSlide(Role.ENTER_OVER)
            entering.exitTransition = null
            covered?.enterTransition = null
            covered?.exitTransition = PageSlide(Role.EXIT_UNDER)
        }

        
        fun pop(leaving: Fragment, revealed: Fragment) {
            leaving.exitTransition = PageSlide(Role.LEAVE_OVER)
            leaving.enterTransition = null
            revealed.enterTransition = PageSlide(Role.RETURN_UNDER)
            revealed.exitTransition = null
        }

        
        fun clear(pages: Iterable<Fragment>) {
            pages.forEach {
                it.enterTransition = null
                it.exitTransition = null
            }
        }
    }
}
