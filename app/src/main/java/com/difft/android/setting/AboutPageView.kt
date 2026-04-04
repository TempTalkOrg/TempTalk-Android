package com.difft.android.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.R
import com.difft.android.base.ui.TitleBar

@Composable
fun AboutPageView(
    onBackClick: () -> Unit,
    appVersion: String,
    buildVersion: String,
    buildTime: String,
    onCheckForUpdateClick: () -> Unit,
    joinDesktopClick: () -> Unit,
    callServerUrlNodeClick: () -> Unit,
    isInsider: Boolean,
    showBackButton: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = com.difft.android.base.R.color.bg_setting))
    ) {

        TitleBar(
            titleText = stringResource(id = R.string.settings_about),
            showBackButton = showBackButton,
            onBackClick = onBackClick
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsScreen(
                appVersion = appVersion,
                buildVersion = buildVersion,
                buildTime = buildTime,
                onCheckForUpdateClick = onCheckForUpdateClick,
                joinDesktopClick = joinDesktopClick,
                callServerUrlNodeClick = callServerUrlNodeClick,
                isInsider = isInsider
            )
        }
    }
}


@Composable
fun SettingItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colorResource(id = com.difft.android.base.R.color.t_primary),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = colorResource(id = com.difft.android.base.R.color.t_third),
            fontSize = 16.sp,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun ClickableSettingsItem(title: String, click: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { click() }
            .background(
                colorResource(id = com.difft.android.base.R.color.bg_setting_item),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colorResource(id = com.difft.android.base.R.color.t_primary),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(id = com.difft.android.chat.R.drawable.chat_ic_arrow_right),
            contentDescription = "Check New Version",
            modifier = Modifier.size(20.dp),
            tint = colorResource(id = com.difft.android.base.R.color.t_primary)
        )
    }
}


@Composable
fun SettingsScreen(
    appVersion: String,
    buildVersion: String,
    buildTime: String,
    onCheckForUpdateClick: () -> Unit,
    joinDesktopClick: () -> Unit,
    callServerUrlNodeClick: () -> Unit,
    isInsider: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier.background(
                colorResource(id = com.difft.android.base.R.color.bg_setting_item),
                shape = RoundedCornerShape(8.dp)
            )
        ) {
            SettingItem(
                title = stringResource(id = R.string.settings_version),
                value = appVersion + (if (isInsider) " (Insider)" else "")
            )
            HorizontalDivider(
                color = colorResource(id = com.difft.android.base.R.color.bg_setting),
                thickness = 1.dp
            )
            SettingItem(
                title = stringResource(id = R.string.settings_build_version),
                value = buildVersion
            )
            HorizontalDivider(
                color = colorResource(id = com.difft.android.base.R.color.bg_setting),
                thickness = 1.dp
            )
            SettingItem(
                title = stringResource(id = R.string.settings_build_time),
                value = buildTime
            )
        }

        Spacer(modifier = Modifier.height(25.dp))

        Column(
            modifier = Modifier.background(
                colorResource(id = com.difft.android.base.R.color.bg_setting_item),
                shape = RoundedCornerShape(8.dp)
            )
        ) {
            ClickableSettingsItem(
                title = stringResource(R.string.settings_check_new_version),
                click = onCheckForUpdateClick
            )
            HorizontalDivider(
                color = colorResource(id = com.difft.android.base.R.color.bg_setting),
                thickness = 1.dp
            )
            ClickableSettingsItem(
                title = stringResource(R.string.settings_obtain_desktop_version),
                click = joinDesktopClick
            )
        }

        if(isInsider) {
            Spacer(modifier = Modifier.height(25.dp))

            Column(
                modifier = Modifier.background(
                    colorResource(id = com.difft.android.base.R.color.bg_setting_item),
                    shape = RoundedCornerShape(8.dp)
                )
            ) {
                ClickableSettingsItem(
                    title = stringResource(com.difft.android.call.R.string.call_service_node_dashboard_title),
                    click = callServerUrlNodeClick
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
@Preview
private fun DefaultAboutPageView() {
    AboutPageView(
        onBackClick = {},
        appVersion = "1.0.0",
        buildVersion = "1.0.0",
        buildTime = "12:00 PM",
        onCheckForUpdateClick = {},
        joinDesktopClick = {},
        callServerUrlNodeClick = {},
        isInsider = false
    )
}