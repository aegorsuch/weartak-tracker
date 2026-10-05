package com.tak.weartak_tracker.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults

// UI building blocks mirroring WearTAK-CIV's settings components (WearTAKSettingsActivity.kt).

@Composable
fun WearTakScreenDimensions(
    text: String,
    rowContent: @Composable () -> Unit,
    columnContent: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        Box(
            modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(bottom = 60.dp).align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        text = text,
                        fontSize = if (text.length < 20) 17.sp else 15.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp),
                    )
                }
                columnContent()
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { rowContent() }
        }
    }
}

@Composable
fun WearTAKPageWithBackArrow(title: String, onBack: () -> Unit, columnContent: ScalingLazyListScope.() -> Unit) =
    WearTakScreenDimensions(title, { WearTAKBackButton(onBack) }, columnContent)

@Composable
fun WearTAKPageWithConfirmCancel(
    title: String,
    onClickConfirm: () -> Unit,
    onClickCancel: () -> Unit,
    overlay: @Composable () -> Unit = {},
    columnContent: ScalingLazyListScope.() -> Unit,
) = WearTakScreenDimensions(
    title,
    {
        WearTAKConfirmCancelButtons(onClickCancel = onClickCancel, onClickConfirm = onClickConfirm)
        overlay()
    },
    columnContent,
)

private val titleChipColors
    @Composable get() = ChipDefaults.chipColors(
        backgroundColor = Color.DarkGray,
        contentColor = Color.White,
        secondaryContentColor = Color.LightGray,
    )

@Composable
fun WearTAKTitleChip(title: String, icon: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(text = title) },
        colors = titleChipColors,
        icon = { icon() },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun WearTAKTitleChipWithState(title: String, state: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(text = title) },
        secondaryLabel = { Text(text = state, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = titleChipColors,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun WearTAKToggleChip(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, description: String) {
    ToggleChip(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        checked = checked,
        colors = ToggleChipDefaults.toggleChipColors(uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor),
        toggleControl = { Switch(checked = checked, onCheckedChange = null, enabled = true) },
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun <T> WearTAKSelectableChip(selected: Boolean, onClick: () -> Unit, title: String, item: T, list: List<T>) {
    SelectableChip(
        modifier = Modifier.fillMaxWidth(),
        selected = selected,
        onClick = { onClick() },
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        shape = when {
            list.size == 1 -> MaterialTheme.shapes.large
            item == list.first() -> RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
            item == list.last() -> RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
            else -> RoundedCornerShape(0.dp)
        },
    )
}

@Composable
fun WearTAKSplitToggleChip(
    appIcon: @Composable () -> Unit,
    title: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    SplitToggleChip(
        label = {
            appIcon()
            Text(
                title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
        secondaryLabel = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        checked = checked,
        toggleControl = { Switch(checked = checked, onCheckedChange = null, enabled = true, modifier = Modifier.size(25.dp)) },
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun WearTAKOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        label = { Text(text = label, color = colors.primary) },
        placeholder = { Text(text = label, color = colors.primary) },
        textStyle = TextStyle(color = colors.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun WearTAKConfirmCancelButtons(onClickCancel: () -> Unit, onClickConfirm: () -> Unit) {
    Row {
        IconButton(
            onClick = onClickCancel,
            modifier = Modifier.size(50.dp).border(1.dp, color = Color.Black, shape = CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Red, containerColor = Color.DarkGray),
        ) { Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel", tint = Color.Red) }
        Spacer(modifier = Modifier.width(20.dp))
        IconButton(
            onClick = onClickConfirm,
            modifier = Modifier.size(50.dp).border(1.dp, color = Color.Black, shape = CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Black, containerColor = colors.primary),
        ) { Icon(imageVector = Icons.Filled.Check, contentDescription = "Confirm", tint = Color.Black) }
    }
}

@Composable
fun WearTAKBackButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(height = 48.dp, width = 80.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray, contentColor = Color.Red),
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = Color.Red)
    }
}

/** String entry page with an outlined text field and X / check buttons. */
@Composable
fun WearTAKStringEntryPage(
    item: String,
    label: String,
    onBack: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    validate: (String) -> String? = { null },
    confirmFunction: (String) -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(item)) }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background).padding(bottom = 16.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            label = { Text(text = label, color = colors.primary) },
            placeholder = { Text(text = label, color = colors.primary) },
            textStyle = TextStyle(color = colors.primary),
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            WearTAKConfirmCancelButtons(
                onClickCancel = onBack,
                onClickConfirm = {
                    val error = validate(text.text)
                    if (error != null) {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    } else {
                        confirmFunction(text.text)
                        onBack()
                    }
                },
            )
        }
    }
}

/** Int entry page with the same min/max validation messages as CIV. */
@Composable
fun WearTAKIntEntryPage(item: Int, label: String, minimum: Int, maximum: Int, onBack: () -> Unit, confirmFunction: (Int) -> Unit) =
    WearTAKStringEntryPage(
        item = item.toString(),
        label = label,
        onBack = onBack,
        keyboardType = KeyboardType.Number,
        validate = { s ->
            val v = s.trim().toIntOrNull()
            when {
                s.isBlank() -> "Please enter a value"
                v == null -> " Value is not a number"
                v > maximum -> "Value exceeds maximum, please re-enter"
                v < minimum -> " Value is less than minimum, please re-enter"
                else -> null
            }
        },
        confirmFunction = { confirmFunction(it.trim().toInt()) },
    )

@Composable
fun <T> WearTAKSelectionPage(
    title: String,
    list: List<T>,
    selected: T?,
    label: (T) -> String = { it.toString() },
    onBack: () -> Unit,
    confirmFunction: (T) -> Unit,
) {
    WearTAKPageWithBackArrow(title, onBack) {
        itemsIndexed(list) { _, item ->
            WearTAKSelectableChip(
                selected = item == selected,
                onClick = { confirmFunction(item) },
                title = label(item),
                item = item,
                list = list,
            )
        }
        item { Spacer(modifier = Modifier.height(15.dp)) }
    }
}

@Composable
fun WearTAKLoadingDialog(show: Boolean, secondaryText: String) {
    if (!show) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.DarkGray)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = secondaryText, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun WearTAKAlertDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit = {},
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    if (!show) return
    AlertDialog(
        containerColor = colors.background,
        modifier = Modifier,
        onDismissRequest = onDismissRequest,
        title = { title() },
        text = { text() },
        confirmButton = { confirmButton() },
        dismissButton = { dismissButton() },
    )
}
