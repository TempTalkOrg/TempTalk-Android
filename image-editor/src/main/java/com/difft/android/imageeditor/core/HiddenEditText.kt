package com.difft.android.imageeditor.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatEditText
import com.difft.android.imageeditor.core.model.EditorElement
import com.difft.android.imageeditor.core.renderers.MultiLineTextRenderer
import java.util.LinkedList

/**
 * Invisible [android.widget.EditText] that is used during in-image text editing.
 */
class HiddenEditText(context: Context) : AppCompatEditText(context) {

    private var currentTextEditorElement: EditorElement? = null

    private var currentTextEntity: MultiLineTextRenderer? = null

    private var onEndEdit: Runnable? = null

    private var onEditOrSelectionChange: OnEditOrSelectionChange? = null

    private val textFilters: MutableList<TextFilter> = LinkedList()

    init {
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        isClickable = false
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 1f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        clearFocus()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        val entity = currentTextEntity
        if (entity != null) {
            var filtered: String = text.toString()
            for (filter in textFilters) {
                filtered = filter.filter(filtered) ?: filtered
            }
            entity.setText(filtered)
            postEditOrSelectionChange()
        }
    }

    override fun onEditorAction(actionCode: Int) {
        super.onEditorAction(actionCode)
        if (actionCode == EditorInfo.IME_ACTION_DONE && currentTextEntity != null) {
            currentTextEntity!!.setFocused(false)
            endEdit()
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (currentTextEntity != null) {
            currentTextEntity!!.setFocused(focused)
            if (!focused) {
                endEdit()
            }
        }
    }

    fun addTextFilter(filter: TextFilter) {
        textFilters.add(filter)
    }

    fun addTextFilters(filters: Collection<TextFilter>) {
        textFilters.addAll(filters)
    }

    fun removeTextFilter(filter: TextFilter) {
        textFilters.remove(filter)
    }

    private fun endEdit() {
        onEndEdit?.run()
    }

    private fun postEditOrSelectionChange() {
        val element = currentTextEditorElement
        val entity = currentTextEntity
        val listener = onEditOrSelectionChange
        if (element != null && entity != null && listener != null) {
            listener.onChange(element, entity)
        }
    }

    internal fun getCurrentTextEntity(): MultiLineTextRenderer? = currentTextEntity

    internal fun getCurrentTextEditorElement(): EditorElement? = currentTextEditorElement

    fun setCurrentTextEditorElement(currentTextEditorElement: EditorElement?) {
        val renderer = currentTextEditorElement?.renderer
        if (currentTextEditorElement != null && renderer is MultiLineTextRenderer) {
            this.currentTextEditorElement = currentTextEditorElement
            setCurrentTextEntity(renderer)
        } else {
            this.currentTextEditorElement = null
            setCurrentTextEntity(null)
        }

        postEditOrSelectionChange()
    }

    private fun setCurrentTextEntity(currentTextEntity: MultiLineTextRenderer?) {
        if (this.currentTextEntity !== currentTextEntity) {
            if (this.currentTextEntity != null) {
                this.currentTextEntity!!.setFocused(false)
            }
            this.currentTextEntity = currentTextEntity
            if (currentTextEntity != null) {
                val text = currentTextEntity.getText()
                setText(text)
                setSelection(text.length)
            } else {
                setText("")
            }
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (currentTextEntity != null) {
            currentTextEntity!!.setSelection(selStart, selEnd)
            postEditOrSelectionChange()
        }
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val focus = super.requestFocus(direction, previouslyFocusedRect)

        if (currentTextEntity != null && focus) {
            currentTextEntity!!.setFocused(true)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            if (!imm.isAcceptingText) {
                imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, InputMethodManager.HIDE_IMPLICIT_ONLY)
            }
        }

        return focus
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_IMPLICIT_ONLY)
    }

    fun setIncognitoKeyboardEnabled(incognitoKeyboardEnabled: Boolean) {
        imeOptions = if (incognitoKeyboardEnabled) {
            imeOptions or INCOGNITO_KEYBOARD_IME
        } else {
            imeOptions and INCOGNITO_KEYBOARD_IME.inv()
        }
    }

    fun setOnEndEdit(onEndEdit: Runnable?) {
        this.onEndEdit = onEndEdit
    }

    fun setOnEditOrSelectionChange(onEditOrSelectionChange: OnEditOrSelectionChange?) {
        this.onEditOrSelectionChange = onEditOrSelectionChange
    }

    fun interface OnEditOrSelectionChange {
        fun onChange(editorElement: EditorElement, textRenderer: MultiLineTextRenderer)
    }

    interface TextFilter {
        /**
         * Given an input string, return a filtered version.
         */
        fun filter(text: String): String?
    }

    companion object {
        @SuppressLint("InlinedApi")
        private val INCOGNITO_KEYBOARD_IME = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
}
