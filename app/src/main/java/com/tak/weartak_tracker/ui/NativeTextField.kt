package com.tak.weartak_tracker.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text

/** Keeps the platform input connection alive while Compose observes the draft. */
internal class TrackerEditText(context: Context) : EditText(context) {
    var onValueChange: (TextFieldValue) -> Unit = {}
    private var updating = false
    private var ready = false

    fun openKeyboard() {
        doOnAttach {
            post {
                requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    init {
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = notifyValue()
        })
        ready = true
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // TextView can call this before the subclass has been initialized.
        if (ready && selStart >= 0 && selEnd >= 0 && !updating) notifyValue()
    }

    private fun notifyValue() {
        if (updating) return
        val value = text?.toString().orEmpty()
        onValueChange(TextFieldValue(value, TextRange(
            selectionStart.coerceIn(0, value.length),
            selectionEnd.coerceIn(0, value.length),
        )))
    }

    fun updateValue(value: TextFieldValue) {
        updating = true
        try {
            // Replacing an unchanged Editable discards the IME's composing spans/fullscreen state.
            if (text.toString() != value.text) setText(value.text)
            val start = value.selection.start.coerceIn(0, length())
            val end = value.selection.end.coerceIn(0, length())
            if (selectionStart != start || selectionEnd != end) setSelection(start, end)
        } finally {
            updating = false
        }
    }
}

internal fun nativeInputType(type: KeyboardType): Int = when (type) {
    KeyboardType.Number -> InputType.TYPE_CLASS_NUMBER
    KeyboardType.NumberPassword -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    KeyboardType.Uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    KeyboardType.Ascii -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    else -> InputType.TYPE_CLASS_TEXT
}

@Composable
internal fun WearTAKNativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    if (draft.text != value) draft = TextFieldValue(value, TextRange(value.length))
    WearTAKNativeTextField(draft, {
        draft = it
        onValueChange(it.text)
    }, label, keyboardType)
}

@Composable
internal fun WearTAKNativeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    onDone: (() -> Unit)? = null,
    openKeyboard: Boolean = false,
) {
    val foreground = colors.primary
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = foreground, fontSize = 12.sp)
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                TrackerEditText(context).apply {
                    inputType = nativeInputType(keyboardType)
                    setSingleLine(true)
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(foreground.toArgb())
                    setHintTextColor(foreground.toArgb())
                    textSize = 14f
                    val density = resources.displayMetrics.density
                    minHeight = (48 * density).toInt()
                    val inset = (8 * density).toInt()
                    setPadding(inset, inset, inset, inset)
                    background = GradientDrawable().apply {
                        cornerRadius = 4 * density
                        setStroke(density.toInt().coerceAtLeast(1), foreground.toArgb())
                    }
                    if (openKeyboard) openKeyboard()
                }
            },
            update = { editor ->
                editor.onValueChange = onValueChange
                editor.hint = label
                editor.updateValue(value)
                editor.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) {
                        if (onDone != null) onDone()
                        else (editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(editor.windowToken, 0)
                        true
                    } else false
                }
            },
        )
    }
}
