package com.ozer.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ozer.assistant.actions.Reminders
import com.ozer.assistant.actions.Say
import com.ozer.assistant.data.Store
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private fun toLocal(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

@Composable
private fun Empty(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun NotesScreen(store: Store, modifier: Modifier = Modifier) {
    val notes by store.notes.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("פתק חדש…") })
            IconButton(onClick = { if (input.isNotBlank()) { store.addNote(input.trim()); input = "" } }) {
                Icon(Icons.Filled.Add, contentDescription = "הוסף")
            }
        }
        if (notes.isEmpty()) Empty("אין פתקים. אפשר גם להגיד: 'תרשום לקנות חלב'.", Modifier.fillMaxSize())
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.text, style = MaterialTheme.typography.bodyLarge)
                            val d = toLocal(n.created)
                            Text("${Say.gregorian(d.toLocalDate())} ${Say.clock(d.toLocalTime())}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { store.deleteNote(n.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "מחק")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemindersScreen(store: Store, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val all by store.reminders.collectAsState()
    val upcoming = all.filter { !it.fired }.sortedBy { it.at }
    val past = all.filter { it.fired }.sortedByDescending { it.at }.take(20)
    if (all.isEmpty()) {
        Empty("אין תזכורות. תגיד למשל: 'תזכיר לי מחר בשמונה להתקשר לבנק'.", modifier)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (upcoming.isNotEmpty()) item { Text("קרובות", style = MaterialTheme.typography.titleMedium) }
        items(upcoming, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = MaterialTheme.typography.bodyLarge)
                        Text(Say.whenText(toLocal(r.at)), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { Reminders.cancel(ctx, r); store.deleteReminder(r.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "בטל")
                    }
                }
            }
        }
        if (past.isNotEmpty()) item { Text("שהיו", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(past, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.text, color = MaterialTheme.colorScheme.outline)
                    val d = toLocal(r.at)
                    Text("${Say.gregorian(d.toLocalDate())} ${Say.clock(d.toLocalTime())}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { store.deleteReminder(r.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "מחק")
                }
            }
        }
    }
}
