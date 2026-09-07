package com.difft.android.base.widget

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.compose.input.DifftInputSurface
import com.difft.android.base.ui.compose.input.DifftInputDefaults
import com.difft.android.base.ui.theme.DifftTheme
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size

/** Leading icon options for [DifftClearableInputView]. */
enum class LeadingIcon { None, Search }

/**
 * XML-hostable shell around [DifftClearableTextField]. Transitional: when a page migrates to
 * Compose, replace this tag with a direct [DifftClearableTextField]/DifftSearchBar call — the
 * composable itself needs no change.
 *
 * ## State ownership
 * [query] is this View's live text (same standing as `EditText.getText()`). The host reads it
 * on demand; a ViewModel-side copy fed by [onQueryChanged] is derived state and must not be
 * written back.
 *
 * ## Setter semantics
 * - user typing            -> [onQueryChanged] only
 * - `query = x` (external) -> NO callback (equal value: complete no-op)
 * - [clear]                -> [onClear] only, unconditionally (even when already empty —
 *                             mirrors TextView.setText's unconditional TextWatcher notification)
 * - user taps ✕            -> [onClear] + refocus + keyboard
 * - instance-state restore -> [onQueryChanged] (posted), never [onClear]
 */
open class DifftClearableInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val defaultLeadingIcon: LeadingIcon = LeadingIcon.None,
    defaultClearMode: ClearMode = ClearMode.WhileEditing,
    defaultImeAction: ImeAction = ImeAction.Default,
    private val defaultHeight: Dp? = null,
    private val defaultContentPadding: PaddingValues = PaddingValues(0.dp),
    private val defaultClearIconEndInset: Dp = 0.dp,
) : DifftComposeView(context, attrs, defStyleAttr) {

    private var queryState by mutableStateOf("")
    private var hintState by mutableStateOf<CharSequence?>(null)
    private var clearModeState by mutableStateOf(defaultClearMode)
    private var keyboardOptionsState by mutableStateOf(KeyboardOptions(imeAction = defaultImeAction))
    private var maxLengthState by mutableStateOf<Int?>(null)
    private var enabledState by mutableStateOf(true)
    private var autoFocusState = false
    private var containerColorState by mutableStateOf<Int?>(null)
    private var surfaceState by mutableStateOf(DifftInputSurface.Page)
    private var heightState by mutableStateOf(defaultHeight)
    private var minHeightState by mutableStateOf(0.dp)
    private var textSizeSpState by mutableStateOf<Float?>(null)
    private var contentPaddingState by mutableStateOf<PaddingValues?>(null)

    /** Current text. Setter fires no callback; setting an equal value is a complete no-op. */
    var query: String
        get() = queryState
        set(value) {
            if (value != queryState) queryState = value
        }

    var hint: CharSequence?
        get() = hintState
        set(value) {
            hintState = value
        }

    /** Surface the field sits on (page vs popup / bottom sheet); see [DifftInputSurface]. */
    var surface: DifftInputSurface
        get() = surfaceState
        set(value) {
            surfaceState = value
        }

    var clearMode: ClearMode
        get() = clearModeState
        set(value) {
            clearModeState = value
        }

    var keyboardOptions: KeyboardOptions
        get() = keyboardOptionsState
        set(value) {
            keyboardOptionsState = value
            applyAutofillHints()
        }

    var maxLength: Int?
        get() = maxLengthState
        set(value) {
            maxLengthState = value
        }

    /** Fires only on user typing. External setters, [clear], and the ✕ tap never fire it. */
    var onQueryChanged: (String) -> Unit = {}

    /** Fires when the user taps ✕ or the host calls [clear] — the complete "cleared" signal. */
    var onClear: () -> Unit = {}

    /** Fires on the IME action key (Search / Done / ...). */
    var onImeAction: (() -> Unit)? = null

    init {
        // The shell itself is the autofill target: the system anchors its suggestion popup to a
        // REAL view's bounds. Compose's virtual autofill nodes mis-anchored the popup at the
        // screen origin, so descendants are excluded and this view exposes the value instead.
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftClearableInputView) {
                hintState = getString(R.styleable.DifftClearableInputView_dsi_hint)
                autoFocusState = getBoolean(R.styleable.DifftClearableInputView_dsi_autoFocus, false)
                if (hasValue(R.styleable.DifftClearableInputView_dsi_clearMode)) {
                    clearModeState = when (getInt(R.styleable.DifftClearableInputView_dsi_clearMode, 0)) {
                        1 -> ClearMode.WhileEditing
                        else -> ClearMode.WhenNotEmpty
                    }
                }
                val imeAction = when (getInt(R.styleable.DifftClearableInputView_dsi_imeAction, 0)) {
                    1 -> ImeAction.Search
                    2 -> ImeAction.Done
                    3 -> ImeAction.Next
                    4 -> ImeAction.Send
                    else -> defaultImeAction
                }
                val keyboardType = when (getInt(R.styleable.DifftClearableInputView_dsi_keyboardType, 0)) {
                    1 -> KeyboardType.Email
                    2 -> KeyboardType.Phone
                    3 -> KeyboardType.Number
                    4 -> KeyboardType.Password
                    else -> KeyboardType.Text
                }
                keyboardOptionsState = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction)
                applyAutofillHints()
                if (hasValue(R.styleable.DifftClearableInputView_dsi_maxLength)) {
                    maxLengthState = getInt(R.styleable.DifftClearableInputView_dsi_maxLength, 0)
                }
                dpOrNull(R.styleable.DifftClearableInputView_dsi_contentPaddingStart)?.let {
                    contentPaddingState = PaddingValues(start = it)
                }
                if (hasValue(R.styleable.DifftClearableInputView_dsi_containerColor)) {
                    containerColorState = getColor(R.styleable.DifftClearableInputView_dsi_containerColor, 0)
                }
                if (hasValue(R.styleable.DifftClearableInputView_dsi_surface)) {
                    surfaceState = when (getInt(R.styleable.DifftClearableInputView_dsi_surface, 0)) {
                        1 -> DifftInputSurface.Popup
                        else -> DifftInputSurface.Page
                    }
                }
                dpOrNull(R.styleable.DifftClearableInputView_dsi_height)?.let { heightState = it }
                dpOrNull(R.styleable.DifftClearableInputView_dsi_minHeight)?.let {
                    minHeightState = it
                    heightState = null
                }
                if (hasValue(R.styleable.DifftClearableInputView_android_textSize)) {
                    textSizeSpState = getDimension(R.styleable.DifftClearableInputView_android_textSize, 0f) /
                        resources.displayMetrics.scaledDensity
                }
                if (hasValue(R.styleable.DifftClearableInputView_android_enabled)) {
                    // Route through the setter: the framework does not apply android:enabled on a
                    // plain View, so this is the only place the XML value lands — it must reach
                    // BOTH the Compose state and the View level (isEnabled feeds getAutofillType).
                    isEnabled = getBoolean(R.styleable.DifftClearableInputView_android_enabled, true)
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    // ---------- Autofill (View-level) ----------

    override fun getAutofillType(): Int = if (isEnabled) AUTOFILL_TYPE_TEXT else AUTOFILL_TYPE_NONE

    override fun getAutofillValue(): AutofillValue? = AutofillValue.forText(queryState)

    override fun autofill(value: AutofillValue) {
        if (!value.isText) return
        val text = value.textValue.toString()
        val capped = maxLengthState?.let(text::take) ?: text
        queryState = capped
        // Autofill behaves like user input — hosts must react (enable buttons, etc.).
        onQueryChanged(capped)
    }

    private fun applyAutofillHints() {
        val hint = when (keyboardOptionsState.keyboardType) {
            KeyboardType.Email -> AUTOFILL_HINT_EMAIL_ADDRESS
            KeyboardType.Phone -> AUTOFILL_HINT_PHONE
            KeyboardType.Password -> AUTOFILL_HINT_PASSWORD
            else -> null
        }
        setAutofillHints(*(hint?.let { arrayOf(it) } ?: emptyArray()))
    }

    private fun notifyAutofillFocus(focused: Boolean) {
        val afm = ContextCompat.getSystemService(context, AutofillManager::class.java) ?: return
        runCatching {
            if (focused) afm.notifyViewEntered(this) else afm.notifyViewExited(this)
        }.onFailure { L.w { "[DifftInput] autofill notify failed: ${it.stackTraceToString()}" } }
    }

    // ---------- Commands ----------

    /**
     * Programmatic clear: empties the text and fires [onClear] UNCONDITIONALLY — even when the
     * text is already empty, mirroring TextView.setText's unconditional TextWatcher notification
     * (callers like group-update refresh chains rely on firing from an already-empty state).
     * Does not move focus and does not show the keyboard (matches the legacy
     * `buttonClear.performClick()` semantics).
     */
    fun clear() {
        queryState = ""
        onClear()
    }

    /** Requests focus on the inner text field and shows the keyboard. */
    fun focusAndShowKeyboard() = post(ImeCommand.FOCUS_AND_SHOW)

    /** Requests focus on the inner text field (Compose focus implies an IME session). */
    fun requestInputFocus() = post(ImeCommand.FOCUS)

    /**
     * Clears the inner Compose focus and hides the keyboard. Replaces the legacy
     * `clearFocus()` + `KeyboardUtils/ViewUtil.hideKeyboard(view)` pair — a plain View
     * `clearFocus()` cannot clear Compose's internal FocusState.
     */
    fun hideKeyboard() {
        post(ImeCommand.HIDE)
        // The legacy path used windowToken directly, which works even when this subtree is
        // already GONE; keep that path as an idempotent fallback.
        ContextCompat.getSystemService(context, InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    private enum class ImeCommand { FOCUS, FOCUS_AND_SHOW, HIDE }

    private var pendingCommand by mutableStateOf<Pair<ImeCommand, Long>?>(null)
    private var commandSeq = 0L

    private fun post(cmd: ImeCommand) {
        pendingCommand = cmd to commandSeq++
    }

    // ---------- Instance state ----------
    // AbstractComposeView does not inherit EditText's automatic text freezing; without this,
    // process death / foldable reconfiguration would drop the query silently.

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        putString(KEY_QUERY, queryState)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
            val restored = state.getString(KEY_QUERY).orEmpty()
            queryState = restored
            // EditText's restore fires the TextWatcher, so hosts re-run their search; posting
            // keeps that equivalence once listeners are attached. Never route through clear().
            post { onQueryChanged(restored) }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    @Composable
    override fun ThemedContent() {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        LaunchedEffect(pendingCommand) {
            val (cmd, _) = pendingCommand ?: return@LaunchedEffect
            runCatching {
                when (cmd) {
                    ImeCommand.FOCUS -> focusRequester.requestFocus()
                    ImeCommand.FOCUS_AND_SHOW -> {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                    ImeCommand.HIDE -> {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                }
            }.onFailure { L.w { "[DifftInput] ime command $cmd failed: ${it.stackTraceToString()}" } }
            pendingCommand = null
        }

        val defaultTextStyle = DifftTheme.typography.bodyMedium
        val textStyle = textSizeSpState?.let {
            defaultTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp))
        } ?: defaultTextStyle

        DifftClearableTextField(
            value = queryState,
            onValueChange = { newValue ->
                queryState = newValue
                onQueryChanged(newValue)
            },
            onClear = {
                queryState = ""
                onClear()
            },
            hint = hintState?.toString(),
            clearMode = clearModeState,
            leadingIcon = when (defaultLeadingIcon) {
                LeadingIcon.None -> null
                LeadingIcon.Search -> ({
                    Icon(
                        painter = painterResource(R.drawable.base_ic_search),
                        contentDescription = null,
                        tint = DifftTheme.colors.icon,
                        modifier = Modifier.size(
                            width = DifftInputDefaults.LeadingIconWidth,
                            height = DifftInputDefaults.LeadingIconHeight,
                        ),
                    )
                })
            },
            enabled = enabledState,
            keyboardOptions = keyboardOptionsState,
            onImeAction = onImeAction,
            onFocusChanged = { focused -> notifyAutofillFocus(focused) },
            surface = surfaceState,
            containerColor = containerColorState?.let { Color(it) }
                ?: if (forceDark) DifftTheme.colors.bg5 else null,
            textStyle = textStyle,
            height = heightState,
            minHeight = minHeightState,
            contentPadding = contentPaddingState ?: defaultContentPadding,
            clearIconEndInset = defaultClearIconEndInset,
            maxLength = maxLengthState,
            autoFocus = autoFocusState,
            focusRequester = focusRequester,
        )
    }

    companion object {
        private const val KEY_SUPER = "difft_input_super"
        private const val KEY_QUERY = "difft_input_query"
    }
}
