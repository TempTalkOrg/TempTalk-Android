package com.difft.android.base.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.difft.android.base.R


val SfProFont = FontFamily(
    Font(R.font.sf_pro, FontWeight.Normal),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    backgroundColorResId: Int = R.color.bg1,  // Allow custom background color
    content: @Composable() () -> Unit
) {
    val context = LocalContext.current
    val backgroundColor = colorResource(id = backgroundColorResId)

    // Set window background color to affect status bar on Android 15+
    SideEffect {
        val activity = context as? Activity
        activity?.window?.setBackgroundDrawableResource(backgroundColorResId)
    }

    val colors = if (darkTheme) {
        darkColorScheme(
//            primary = Color.Transparent,
//            primaryContainer = Color.Transparent,
//            secondary = Color.Transparent,
//            background = backgroundColor,
//            surface = Color.Transparent,
//            onPrimary = Color.Transparent,
//            onSecondary = Color.Transparent,
//            onBackground = Color.White,
//            onSurface = Color.White
        )
    } else {
        lightColorScheme(
//            primary = Color.Transparent,
//            primaryContainer = Color.Transparent,
//            secondary = Color.Transparent,
//            background = backgroundColor,
//            surface = Color.Transparent,
//            onPrimary = Color.Transparent,
//            onSecondary = Color.Transparent,
//            onBackground = Color.White,
//            onSurface = Color.White,
        )
    }

    // Don't modify window settings here - let theme handle it
    // Android 15+ will automatically use windowBackground color for status bar

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = Shapes
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) { content() }
    }
}
