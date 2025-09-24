package com.difft.android.login

import android.content.Context
import com.difft.android.base.utils.LanguageUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object RandomNameUtil {

    private fun parseNamesJson(context: Context): List<NameData> {
        val inputStream = context.resources.openRawResource(R.raw.names)
        val reader = InputStreamReader(inputStream)

        val gson = Gson()
        val type = object : TypeToken<List<NameData>>() {}.type
        val nameDataList: List<NameData> = gson.fromJson(reader, type)

        reader.close()

        return nameDataList
    }

    private var lastGenderMale = false
    private fun toggleGender(): Boolean {
        lastGenderMale = !lastGenderMale
        return lastGenderMale
    }

    fun getRandomName(context: Context): String {
        val nameDataList = parseNamesJson(context)
        val currentLanguage = LanguageUtils.getLanguage(context).language
        val selectedRegion = if (currentLanguage == "zh") "China" else "United States"
        val nameData = nameDataList.find { it.region == selectedRegion }
            ?: throw IllegalStateException("Region data not found for $selectedRegion")

        val isMale = toggleGender()

        val surname = nameData.surnames.random()
        val givenName = if (isMale) nameData.male.random() else nameData.female.random()

        return if (currentLanguage == "zh") {
            "$surname$givenName"
        } else {
            "$givenName $surname"
        }
    }
}

data class NameData(
    val region: String,
    val male: List<String>,
    val female: List<String>,
    val surnames: List<String>
)