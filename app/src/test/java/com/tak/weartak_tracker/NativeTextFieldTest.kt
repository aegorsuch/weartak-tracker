package com.tak.weartak_tracker

import android.app.Activity
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.tak.weartak_tracker.ui.TrackerEditText
import com.tak.weartak_tracker.ui.nativeInputType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeTextFieldTest {
    private fun withEditor(test: (TrackerEditText) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val editor = TrackerEditText(activity)
            activity.setContentView(editor)
            editor.requestFocus()
            test(editor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun prefillAndCursorAppearInTheFullscreenImeWithoutEmittingAnEdit() = withEditor { editor ->
        var changes = 0
        editor.onValueChange = { changes++ }
        editor.updateValue(TextFieldValue("ODIN-ATAK", TextRange(9)))
        val info = EditorInfo()
        val connection = requireNotNull(editor.onCreateInputConnection(info))
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        assertEquals("ODIN-ATAK", extracted.text.toString())
        assertEquals(9, extracted.selectionStart)
        assertEquals(9, extracted.selectionEnd)
        assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertEquals(0, changes)
    }

    @Test
    fun everyCommittedCharacterUpdatesTheDraftAndFullscreenPreview() = withEditor { editor ->
        var draft = TextFieldValue("")
        editor.onValueChange = { draft = it }
        editor.updateValue(draft)
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        "ALPHA".forEachIndexed { index, character ->
            assertTrue(connection.commitText(character.toString(), 1))
            editor.updateValue(draft)
            val expected = "ALPHA".take(index + 1)
            assertEquals(expected, draft.text)
            assertEquals(expected, connection.getExtractedText(ExtractedTextRequest(), 0).text.toString())
        }
        assertEquals(TextRange(5), draft.selection)
    }

    @Test
    fun recompositionDoesNotReplaceEditableOrDiscardComposingText() = withEditor { editor ->
        var draft = TextFieldValue("")
        editor.onValueChange = { draft = it }
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(connection.setComposingText("A", 1))
        val editable = editor.text
        editor.updateValue(draft)
        assertSame(editable, editor.text)
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
        assertTrue(connection.setComposingText("ALPHA", 1))
        editor.updateValue(draft)
        assertTrue(connection.finishComposingText())
        assertEquals("ALPHA", draft.text)
        assertEquals("ALPHA", editor.text.toString())
    }

    @Test
    fun selectionReplacementDeletionAndExternalRestoreStaySynchronized() = withEditor { editor ->
        var draft = TextFieldValue("Old Name", TextRange(8))
        editor.updateValue(draft)
        editor.onValueChange = { draft = it }
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(connection.setSelection(0, 3))
        assertTrue(connection.commitText("New", 1))
        editor.updateValue(draft)
        assertEquals("New Name", draft.text)
        assertTrue(connection.deleteSurroundingText(1, 0))
        editor.updateValue(draft)
        assertEquals("Ne Name", draft.text)
        editor.updateValue(TextFieldValue("Restored", TextRange(2)))
        assertEquals("Restored", editor.text.toString())
        assertEquals(2, editor.selectionStart)
        assertEquals("Ne Name", draft.text)
    }

    @Test
    fun keyboardTypesPreserveNumericUriAsciiAndMaskedPinModes() {
        assertEquals(InputType.TYPE_CLASS_TEXT, nativeInputType(KeyboardType.Text))
        assertEquals(InputType.TYPE_CLASS_NUMBER, nativeInputType(KeyboardType.Number))
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            nativeInputType(KeyboardType.Uri))
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            nativeInputType(KeyboardType.Ascii))
        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            nativeInputType(KeyboardType.NumberPassword))
    }

    @Test
    fun numericSettingsAndMaskedPinAcceptSuccessiveDigits() = withEditor { editor ->
        editor.inputType = nativeInputType(KeyboardType.Number)
        var draft = TextFieldValue("")
        editor.onValueChange = { draft = it }
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        "12345".forEach { digit ->
            assertTrue(connection.commitText(digit.toString(), 1))
            editor.updateValue(draft)
        }
        assertEquals("12345", draft.text)

        editor.inputType = nativeInputType(KeyboardType.NumberPassword)
        assertTrue(editor.transformationMethod is PasswordTransformationMethod)
        assertEquals("12345", editor.text.toString())
        assertFalse(editor.transformationMethod.getTransformation(editor.text, editor).toString().contains("12345"))
    }

    @Test
    fun nativeClipboardActionsStillUpdateTheDraft() = withEditor { editor ->
        var draft = TextFieldValue("ALPHA", TextRange(5))
        editor.updateValue(draft)
        editor.onValueChange = { draft = it }
        assertTrue(editor.onTextContextMenuItem(android.R.id.selectAll))
        assertTrue(editor.onTextContextMenuItem(android.R.id.cut))
        editor.updateValue(draft)
        assertEquals("", draft.text)
        assertTrue(editor.onTextContextMenuItem(android.R.id.paste))
        editor.updateValue(draft)
        assertEquals("ALPHA", draft.text)
    }
}
