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









@Composable
fun DifftSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.search_default_hint),
    onSearch: (() -> Unit)? = null,
    enabled: Boolean = true,
    size: DifftTextFieldSize = DifftTextFieldSize.Large,
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
    style = DifftTextFieldStyle.Search,
    size = size,
    clearMode = ClearMode.WhenNotEmpty,
    leadingIcon = {
        
        
        Icon(
            painter = painterResource(R.drawable.base_ic_search),
            contentDescription = null, 
            modifier = Modifier.size(DifftInputDefaults.LeadingIconSize),
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
