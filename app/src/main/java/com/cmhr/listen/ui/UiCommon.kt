package com.cmhr.listen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun <T> HorizontalChoiceSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    optionTestTag: ((T) -> String)? = null
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options) { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                modifier = optionTestTag?.let { Modifier.testTag(it(option)) } ?: Modifier
            )
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    value: String,
    update: (String) -> Unit,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    allowBlank: Boolean = false
) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text(title) },
    text = {
        OutlinedTextField(
            value = value,
            onValueChange = update,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (allowBlank) "名称（留空自动生成）" else "名称") },
            singleLine = true
        )
    },
    confirmButton = { Button(onClick = confirm, enabled = allowBlank || value.isNotBlank()) { Text("确定") } },
    dismissButton = { OutlinedButton(onClick = dismiss) { Text("取消") } }
)

@Composable
fun TimedDeleteDialog(
    title: String,
    message: String,
    confirm: () -> Unit,
    dismiss: () -> Unit
) {
    var secondsRemaining by remember(title, message) { mutableIntStateOf(2) }
    LaunchedEffect(title, message) {
        while (secondsRemaining > 0) {
            delay(1_000)
            secondsRemaining--
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = confirm,
                enabled = secondsRemaining == 0
            ) { Text(if (secondsRemaining > 0) "删除（${secondsRemaining}s）" else "确认删除") }
        },
        dismissButton = { OutlinedButton(onClick = dismiss) { Text("取消") } }
    )
}

@Composable
fun ExpandHeader(title: String, expanded: Boolean, click: () -> Unit) =
    Card(Modifier.fillMaxWidth().clickable(onClick = click)) {
        Text("$title ${if (expanded) "▲" else "▼"}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }

fun formatDateTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
fun formatDuration(value: Long): String = String.format(Locale.US, "%.1fs", value / 1000.0)
fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)
