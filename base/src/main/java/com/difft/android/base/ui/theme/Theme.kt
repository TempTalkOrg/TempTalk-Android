package com.difft.android.base.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.difft.android.base.R


val SfProFont = FontFamily(
    Font(R.font.sf_pro, FontWeight.Normal),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable() () -> Unit
) {

    val backgroundColor = colorResource(id = if (darkTheme) R.color.bg1_night else R.color.bg1)

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

    val context = LocalContext.current
    val window = (context as? android.app.Activity)?.window

    // 在应用启动时设置系统栏颜色
    LaunchedEffect(context) {
        window?.let {
            // 启用边缘到边缘模式，隐藏系统的状态栏和导航栏
            WindowCompat.setDecorFitsSystemWindows(it, false)

            // 设置状态栏和导航栏透明
            it.statusBarColor = android.graphics.Color.TRANSPARENT
            it.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

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
