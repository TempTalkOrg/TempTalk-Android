package com.difft.android.setting

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.difft.android.R
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.theme.DifftTheme

@Composable
fun AboutPageView(
    onBackClick: () -> Unit,
    appVersion: String,
    channelLabel: String,
    buildVersion: String,
    buildTime: String,
    onCheckForUpdateClick: () -> Unit,
    joinDesktopClick: () -> Unit,
    callServerUrlNodeClick: () -> Unit,
    showCallServerEntry: Boolean,
    showBackButton: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DifftTheme.colors.bg)
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
                channelLabel = channelLabel,
                buildVersion = buildVersion,
                buildTime = buildTime,
                onCheckForUpdateClick = onCheckForUpdateClick,
                joinDesktopClick = joinDesktopClick,
                callServerUrlNodeClick = callServerUrlNodeClick,
                showCallServerEntry = showCallServerEntry
            )
        }
    }
}

@Composable
fun SettingsScreen(
    appVersion: String,
    channelLabel: String,
    buildVersion: String,
    buildTime: String,
    onCheckForUpdateClick: () -> Unit,
    joinDesktopClick: () -> Unit,
    callServerUrlNodeClick: () -> Unit,
    showCallServerEntry: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DifftSettingsDefaults.PageInset)
    ) {
        Spacer(modifier = Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))

        DifftSettingsGroup {
            DifftSettingsRow(
                label = stringResource(id = R.string.settings_version),
                value = appVersion + (if (channelLabel.isNotEmpty()) " ($channelLabel)" else "")
            )
            DifftSettingsRow(
                label = stringResource(id = R.string.settings_build_version),
                value = buildVersion
            )
            DifftSettingsRow(
                label = stringResource(id = R.string.settings_build_time),
                value = buildTime
            )
        }

        Spacer(modifier = Modifier.height(DifftSettingsDefaults.GroupSpacing))

        DifftSettingsGroup {
            DifftSettingsRow(
                label = stringResource(R.string.settings_check_new_version),
                onClick = onCheckForUpdateClick
            )
            DifftSettingsRow(
                label = stringResource(R.string.settings_obtain_desktop_version),
                onClick = joinDesktopClick
            )
        }

        if (showCallServerEntry) {
            Spacer(modifier = Modifier.height(DifftSettingsDefaults.GroupSpacing))

            DifftSettingsGroup {
                DifftSettingsRow(
                    label = stringResource(com.difft.android.call.R.string.call_service_node_dashboard_title),
                    onClick = callServerUrlNodeClick
                )
            }
        }

        Spacer(modifier = Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DefaultAboutPageView() {
    DifftTheme(applyWindowBackground = false) {
        AboutPageView(
            onBackClick = {},
            appVersion = "1.0.0",
            channelLabel = "Google",
            buildVersion = "1.0.0",
            buildTime = "12:00 PM",
            onCheckForUpdateClick = {},
            joinDesktopClick = {},
            callServerUrlNodeClick = {},
            showCallServerEntry = false
        )
    }
}
