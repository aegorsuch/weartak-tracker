package com.tak.weartak_tracker

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import com.tak.weartak_tracker.ui.WearTAKTextToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolbarTest {
    @Test
    fun exposesOnlyAvailableActionsAndInvokesTheirCallbacks() {
        val toolbar = WearTAKTextToolbar()
        var copied = false
        var pasted = false
        toolbar.showMenu(Rect.Zero, { copied = true }, { pasted = true }, null, null)

        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        val actions = requireNotNull(toolbar.actions)
        assertEquals(listOf(R.string.text_copy, R.string.text_paste), actions.map { it.first })
        actions[0].second()
        actions[1].second()
        assertTrue(copied)
        assertTrue(pasted)
    }

    @Test
    fun hidingDiscardsCallbacks() {
        val toolbar = WearTAKTextToolbar()
        toolbar.showMenu(Rect.Zero, {}, null, {}, {})
        toolbar.hide()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertNull(toolbar.actions)
    }

    @Test
    fun emptyMenuRemainsHidden() {
        val toolbar = WearTAKTextToolbar()
        toolbar.showMenu(Rect.Zero, null, null, null, null)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }
}
