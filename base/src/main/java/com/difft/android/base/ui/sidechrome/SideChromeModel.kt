package com.difft.android.base.ui.sidechrome

import androidx.annotation.DrawableRes






data class RailAction(
    
    val id: String,
    @DrawableRes val icon: Int,
    
    val contentDescription: String,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    
    val badge: Int = 0,
    
    val tinted: Boolean = true,
    val onClick: () -> Unit,
)






data class SideChromeModel(
    
    val back: (() -> Unit)? = null,
    
    @DrawableRes val backIcon: Int = 0,
    val actions: List<RailAction> = emptyList(),
)
