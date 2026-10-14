package com.andyyhope.featureflag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.andyyhope.featureflag.ui.FlagStrings
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueText
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A menu of an enum's cases, each shown as its value text, raw (iOS menu Picker). */
@Composable
internal fun FlagPicker(
    selected: FlagValueBox,
    options: List<FlagValueBox>,
    onSelect: (FlagValueBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { isExpanded = true }) {
            Text(text = FlagValueText.format(selected))
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = FlagValueText.format(option)) },
                    onClick = {
                        isExpanded = false
                        if (option != selected) onSelect(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private enum class DateTimeStep { Closed, Date, Time }

private val DateTimeText: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * The local date and time of [instant] (iOS compact DatePicker, date and time). Tapping it picks a date,
 * then a time; the seconds of [instant] are kept, as the iOS picker has no seconds either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlagDateTimeButton(instant: Instant, onPick: (Instant) -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val local = instant.atZone(zone)
    var step by remember { mutableStateOf(DateTimeStep.Closed) }
    var pickedDate by remember { mutableStateOf(local.toLocalDate()) }
    TextButton(
        onClick = {
            pickedDate = local.toLocalDate()
            step = DateTimeStep.Date
        },
        modifier = modifier,
    ) {
        Text(text = DateTimeText.format(local), style = MaterialTheme.typography.bodyMedium)
    }
    when (step) {
        DateTimeStep.Closed -> Unit
        DateTimeStep.Date -> {
            // The Material date picker speaks UTC midnights, whatever the device's zone.
            val state = rememberDatePickerState(
                initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { step = DateTimeStep.Closed },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.selectedDateMillis?.let { millis ->
                                pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                            step = DateTimeStep.Time
                        },
                    ) { Text(text = FlagStrings.OK) }
                },
                dismissButton = {
                    TextButton(onClick = { step = DateTimeStep.Closed }) { Text(text = FlagStrings.CANCEL) }
                },
            ) {
                DatePicker(state = state)
            }
        }
        DateTimeStep.Time -> {
            val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
            AlertDialog(
                onDismissRequest = { step = DateTimeStep.Closed },
                confirmButton = {
                    TextButton(
                        onClick = {
                            step = DateTimeStep.Closed
                            val time = LocalTime.of(state.hour, state.minute, local.second)
                            onPick(LocalDateTime.of(pickedDate, time).atZone(zone).toInstant())
                        },
                    ) { Text(text = FlagStrings.OK) }
                },
                dismissButton = {
                    TextButton(onClick = { step = DateTimeStep.Closed }) { Text(text = FlagStrings.CANCEL) }
                },
                text = { TimePicker(state = state) },
            )
        }
    }
}
