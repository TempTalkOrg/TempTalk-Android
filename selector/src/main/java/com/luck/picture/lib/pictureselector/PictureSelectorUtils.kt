package com.luck.picture.lib.pictureselector

import android.content.Context
import androidx.core.content.ContextCompat
import com.difft.android.base.utils.LanguageUtils
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.style.PictureSelectorStyle
import com.luck.picture.lib.style.SelectMainStyle
import com.luck.picture.lib.style.TitleBarStyle

object PictureSelectorUtils {
    fun getSelectorStyle(context: Context): PictureSelectorStyle {
        val selectorStyle = PictureSelectorStyle()
//        val titleBarStyle = TitleBarStyle()
//        titleBarStyle.titleBackgroundColor = ContextCompat.getColor(context, com.difft.android.base.R.color.bg1)
//        val back = ResUtils.getDrawable(R.drawable.chat_contact_detail_ic_back) as VectorDrawable
//        back.colorFilter = PorterDuffColorFilter(mainColor, PorterDuff.Mode.SRC_IN)
//        titleBarStyle.titleLeftBackResource = R.drawable.chat_contact_detail_ic_back
//        titleBarStyle.titleTextColor = ContextCompat.getColor(this, com.difft.android.base.R.color.t_primary)
//        titleBarStyle.titleCancelTextColor = ContextCompat.getColor(this, com.difft.android.base.R.color.t_primary)

//        val selectMainStyle = SelectMainStyle()
//        selectMainStyle.mainListBackgroundColor = ContextCompat.getColor(context, com.difft.android.base.R.color.bg1)
//
//        selectorStyle.titleBarStyle = titleBarStyle
//        selectorStyle.selectMainStyle = selectMainStyle
        return selectorStyle
    }

    fun getLanguage(context: Context): Int {
        val locale = LanguageUtils.getLanguage(context)
        return when (locale.language) {
            "zh" -> LanguageConfig.CHINESE
            "en" -> LanguageConfig.ENGLISH
            else -> LanguageConfig.ENGLISH
        }
    }
}