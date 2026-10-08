package com.tak.weartak_tracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tak.weartak_tracker.R

internal class WearTAKTextToolbar : TextToolbar {
    var actions by mutableStateOf<List<Pair<Int, () -> Unit>>?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (actions == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        actions = listOfNotNull(
            onCopyRequested?.let { R.string.text_copy to it },
            onPasteRequested?.let { R.string.text_paste to it },
            onCutRequested?.let { R.string.text_cut to it },
            onSelectAllRequested?.let { R.string.text_select_all to it },
        ).takeIf { it.isNotEmpty() }
    }

    override fun hide() {
        actions = null
    }
}

@Composable
internal fun WearTAKTextSelection(content: @Composable () -> Unit) {
    val toolbar = remember { WearTAKTextToolbar() }
    val keyboard = LocalSoftwareKeyboardController.current
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
    }
    val actions = toolbar.actions
    if (actions != null) {
        LaunchedEffect(toolbar) { keyboard?.hide() }
        // The phone-style floating toolbar is difficult to reach on a round watch.
        Dialog(
            onDismissRequest = toolbar::hide,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WearTAKPageWithBackArrow(stringResource(R.string.text_actions), toolbar::hide) {
                actions.forEach { (label, action) ->
                    item {
                        WearTAKTitleChip(stringResource(label)) {
                            toolbar.hide()
                            action()
                        }
                    }
                }
            }
        }
    }
}
