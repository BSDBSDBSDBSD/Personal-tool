package com.ozer.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ozer.assistant.AssistantViewModel

private val EXAMPLES = listOf(
    "מה אתה יודע לעשות?", "פתח וואטסאפ", "תנגן את אדון עולם", "תרשום לקנות חלב",
    "תזכיר לי בעוד חצי שעה לשתות", "תדליק פנס", "מה התאריך העברי?", "טיימר לחמש דקות",
)

@Composable
fun ChatScreen(
    vm: AssistantViewModel,
    listening: Boolean,
    partial: String,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showDebug by vm.store.showDebug.collectAsState()
    val busy by vm.busy
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }
    fun submit() {
        if (input.isNotBlank()) { vm.send(input); input = "" }
    }

    Column(modifier.imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterStart else Alignment.CenterEnd) {
                    Column(
                        Modifier.widthIn(max = 320.dp)
                            .background(
                                if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        if (showDebug && m.debug.isNotEmpty()) {
                            Text(m.debug, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.messages.size <= 2) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(EXAMPLES) { ex -> SuggestionChip(onClick = { vm.send(ex) }, label = { Text(ex) }) }
            }
        }
        if (listening || partial.isNotEmpty()) {
            Text(
                if (partial.isNotEmpty()) partial else "מקשיב…",
                Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("כתוב פקודה…") },
                singleLine = false, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            if (input.isNotBlank()) {
                IconButton(onClick = { submit() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "שלח")
                }
            } else {
                FilledIconButton(
                    onClick = onMic, modifier = Modifier.padding(start = 6.dp).size(52.dp),
                    colors = if (listening) IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ) else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = "דבר")
                }
            }
        }
    }
}
