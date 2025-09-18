package com.difft.android.base.widget

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.kongzue.dialogx.DialogX
import com.kongzue.dialogx.style.MaterialStyle
import com.kongzue.dialogx.util.TextInfo

object DialogXUtil {
    fun initDialog(context: Context, theme: Int? = null) {
        L.i { "[DialogXUtil] initDialog theme: $theme" }
        DialogX.init(context)
        DialogX.globalStyle = MaterialStyle.style()
        when (theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> {
                DialogX.globalTheme = DialogX.THEME.LIGHT
            }

            AppCompatDelegate.MODE_NIGHT_YES -> {
                DialogX.globalTheme = DialogX.THEME.DARK
            }

            else -> {
                DialogX.globalTheme = DialogX.THEME.AUTO
            }
        }
        DialogX.autoShowInputKeyboard = true

        // 创建一个具有正确主题配置的Context来获取颜色资源
        val themedContext = createThemedContext(context, theme)

        DialogX.backgroundColor = ContextCompat.getColor(themedContext, R.color.bg3)
        DialogX.titleTextInfo = TextInfo().apply {
            this.fontColor = ContextCompat.getColor(themedContext, R.color.t_primary)
            this.fontSize = 16
        }
        DialogX.messageTextInfo = TextInfo().apply {
            this.fontColor = ContextCompat.getColor(themedContext, R.color.t_primary)
            this.fontSize = 14
        }
        DialogX.buttonTextInfo = TextInfo().apply {
            this.fontColor = ContextCompat.getColor(themedContext, R.color.t_primary)
            this.fontSize = 14
        }
        DialogX.okButtonTextInfo = TextInfo().apply {
            this.fontColor = ContextCompat.getColor(themedContext, R.color.t_info)
            this.fontSize = 14
        }

        DialogX.cancelableTipDialog = true
        DialogX.tipProgressColor = ContextCompat.getColor(themedContext, R.color.t_info)
        DialogX.tipBackgroundColor = ContextCompat.getColor(themedContext, R.color.bg3)
        DialogX.tipTextInfo = TextInfo().apply {
            this.fontColor = ContextCompat.getColor(themedContext, R.color.t_primary)
            this.fontSize = 14
        }
    }

    private fun createThemedContext(context: Context, theme: Int?): Context {
        val configuration = Configuration(context.resources.configuration)
        
        // 根据主题设置正确的UI模式
        when (theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> {
                configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_NO
            }
            AppCompatDelegate.MODE_NIGHT_YES -> {
                configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_YES
            }
            else -> {
                // 保持系统默认或跟随系统
                configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_UNDEFINED
            }
        }
        
        return context.createConfigurationContext(configuration)
    }
}