package com.difft.android.base.ui.compose.input

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Search preset of [DifftClearableTextField]: magnifier leading icon, `bg2` pill container,
 * [ClearMode.WhenNotEmpty] semantics and [ImeAction.Search].
 *
 * [onSearch] defaults to null: all legacy search boxes declared `imeOptions="actionSearch"`
 * without ever attaching an editor-action listener, so the search key performing no business
 * action IS the equivalent behavior. Do not add per-page handlers during migration.
 */
@Composable
fun DifftSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.search_default_hint),
    onSearch: (() -> Unit)? = null,
    enabled: Boolean = true,
    surface: DifftInputSurface = DifftInputSurface.Page,
    containerColor: Color? = null,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
) = DifftClearableTextField(
    value = query,
    onValueChange = onQueryChange,
    onClear = onClear,
    modifier = modifier,
    hint = hint,
    clearMode = ClearMode.WhenNotEmpty,
    leadingIcon = {
        Icon(
            painter = painterResource(R.drawable.base_ic_search),
            contentDescription = null, // decorative — skipped by TalkBack
            tint = DifftTheme.colors.icon,
            modifier = Modifier.size(
                width = DifftInputDefaults.LeadingIconWidth,
                height = DifftInputDefaults.LeadingIconHeight,
            ),
        )
    },
    enabled = enabled,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    onImeAction = onSearch,
    surface = surface,
    containerColor = containerColor,
    autoFocus = autoFocus,
    focusRequester = focusRequester,
)
