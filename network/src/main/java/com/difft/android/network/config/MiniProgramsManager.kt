package com.difft.android.network.config


data class Config(
    val crossFirewall: Boolean,
    val didNavigatePassCode: List<Int>,
    val whiteListApp: Boolean
)

data class BotMenuInfo(
    val bot_menus: List<BotMenu>?
)

data class BotMenu(
    val attribute: Attribute?,
    val desc: String?,
    val id: Int,
    val name: String?,
    val type: Int
)

data class Attribute(
    val app_id: String?,
    val content: String?,
    val uri_type: String?
)