package com.difft.android.migration.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.systemBarsWithCutoutPadding
import com.difft.android.base.ui.theme.DifftTheme








@Composable
fun MigrationPage(
    title: String? = null,
    navIcon: MigrationNavIcon = MigrationNavIcon.NONE,
    onNav: () -> Unit = {},
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    scrollable: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DifftTheme.colors.bg)
            .systemBarsWithCutoutPadding(),
    ) {
        MigrationTitleBar(title = title, navIcon = navIcon, onNav = onNav)
        val body = Modifier
            .weight(1f)
            .fillMaxWidth()
        Column(
            modifier = (if (scrollable) body.verticalScroll(rememberScrollState()) else body)
                .padding(horizontal = PageInset)
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageInset)
                    .padding(bottom = 20.dp),
                content = footer,
            )
        }
    }
}


@Composable
fun MigrationGap(height: Int = 16) = Spacer(Modifier.height(height.dp))


val PageInset = 16.dp
