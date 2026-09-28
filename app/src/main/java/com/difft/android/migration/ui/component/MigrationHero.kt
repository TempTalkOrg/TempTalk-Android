package com.difft.android.migration.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Centred opening of a decision page: a bare outline mark in the icon colour, the page title and
 * one or two lines of explanation.
 */
@Composable
fun MigrationHero(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.migration_ic_transfer,
    secondaryDescription: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(HeroMarkSize),
        )
        Text(
            text = title,
            style = DifftTheme.typography.h3,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (description != null) HeroLine(description)
        if (secondaryDescription != null) HeroLine(secondaryDescription)
    }
}

@Composable
private fun HeroLine(text: String) {
    Text(
        text = text,
        style = DifftTheme.typography.p3,
        color = DifftTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = HeroLineMaxWidth),
    )
}

private val HeroMarkSize = 48.dp
private val HeroLineMaxWidth = 304.dp
