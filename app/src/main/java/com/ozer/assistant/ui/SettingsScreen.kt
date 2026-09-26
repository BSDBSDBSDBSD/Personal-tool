package com.ozer.assistant.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ozer.assistant.actions.Apps
import com.ozer.assistant.actions.Music
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import com.ozer.assistant.AssistantViewModel
import com.ozer.assistant.speech.WhisperLib
import com.ozer.assistant.actions.NotesApps
import com.ozer.assistant.actions.Notifications
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ozer.assistant.data.Routine
import com.ozer.assistant.data.Store

private class Perm(val title: String, val why: String, val granted: (Context) -> Boolean, val request: () -> Unit)

@Composable
fun SettingsScreen(vm: AssistantViewModel, hebrewVoice: Boolean, resumeTick: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = vm.store
    val speak by store.speak.collectAsState()
    val debug by store.showDebug.collectAsState()
    val refresh = remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }

    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun open(intent: Intent) = Apps.tryStart(ctx, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val perms = buildList {
        add(Perm("מיקרופון", "לפקודות קוליות", { has(Manifest.permission.RECORD_AUDIO) }) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        })
        add(Perm("שירים בטלפון", "כדי למצוא שירים ולנגן במוסיקולט", { has(audioPerm) }) { launcher.launch(audioPerm) })
        add(Perm("אנשי קשר", "כדי לדעת למי להתקשר", { has(Manifest.permission.READ_CONTACTS) }) {
            launcher.launch(Manifest.permission.READ_CONTACTS)
        })
        add(Perm("שיחות", "כדי לחייג ישר בלי ללחוץ", { has(Manifest.permission.CALL_PHONE) }) {
            launcher.launch(Manifest.permission.CALL_PHONE)
        })
        if (Build.VERSION.SDK_INT >= 33) add(Perm("התראות", "כדי להציג תזכורות", {
            has(Manifest.permission.POST_NOTIFICATIONS)
        }) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) })
        if (Build.VERSION.SDK_INT >= 31) add(Perm("מכשירים בקרבת מקום", "לשליטה בבלוטוס", {
            has(Manifest.permission.BLUETOOTH_CONNECT)
        }) { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        add(Perm("שינוי הגדרות מערכת", "לשינוי בהירות", { Settings.System.canWrite(it) }) {
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        })
        add(Perm("גישה להתראות", "כדי להקריא הודעות והתראות (נשארות רק בטלפון)", { Notifications.hasAccess(it) }) {
            Notifications.openAccessSettings(ctx)
        })
        add(Perm("נא לא להפריע", "למצב שקט ורטט", {
            (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        }) { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
    }

    // re-checked when returning from system settings or after a permission dialog
    val granted = remember(resumeTick, refresh.intValue) { perms.map { it.granted(ctx) } }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("כללי", style = MaterialTheme.typography.titleMedium)
        ToggleRow("להקריא תשובות בקול", if (hebrewVoice) "" else "לא נמצא קול עברי — אפשר להתקין ב'הגדרות ← המרת טקסט לדיבור'",
            speak) { store.setSpeak(it) }
        ToggleRow("להציג פרטי זיהוי", "מראה מה המודל הבין מכל פקודה", debug) { store.setShowDebug(it) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SpeechModelSection(vm)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        NotesAppSection(store, resumeTick)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RoutinesSection(store)

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("הרשאות", style = MaterialTheme.typography.titleMedium)
        Text("כל הרשאה נדרשת רק לפקודות מסוימות. שום מידע לא יוצא מהטלפון.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        perms.forEachIndexed { i, p ->
            val ok = granted[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                    Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (ok) Text("✓ מאושר", color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = p.request) { Text("לאשר") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("מידע", style = MaterialTheme.typography.titleMedium)
        Text(
            "העוזר עובד בלי אינטרנט: מודל הבנה קטן (כ-2.4 מגה) רץ על הטלפון.\n" +
                "מוסיקולט: " + (if (Apps.isInstalled(ctx, Music.MUSICOLET)) "מותקן ✓" else "לא מותקן — שירים ינוגנו בנגן אחר") + "\n" +
                "מנוע דיבור: " + (if (WhisperLib.loaded) "Whisper מוכן" else "לא נטען (המכשיר לא נתמך)"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SpeechModelSection(vm: AssistantViewModel) {
    val progress by vm.modelImport
    val message by vm.modelMessage
    val version by vm.modelVersion
    val installed = remember(version) { vm.whisper.hasModel() }
    val sizeMb = remember(version) { vm.whisper.modelSizeMb() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Text("זיהוי דיבור בלי אינטרנט", style = MaterialTheme.typography.titleMedium)
    Text(
        if (installed) "✓ מודל Whisper מותקן ($sizeMb מגה). המיקרופון עובד בלי אינטרנט."
        else "אין מודל. בלי מודל המיקרופון משתמש בזיהוי של הטלפון, שלא עובד בעברית בלי אינטרנט.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "איך מתקינים: מורידים את הקובץ ggml-small-q5_1.bin (מומלץ, כ-190 מגה) או ggml-base-q5_1.bin " +
            "(קטן ומהיר יותר, פחות מדויק), או ggml-small.bin (כ-470 מגה, בלי דחיסה, כבד יותר) במחשב, מעתיקים לטלפון (כבל או כרטיס זיכרון), ובוחרים אותו כאן.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    val p = progress
    if (p != null) {
        Text("מעתיק… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(if (installed) "להחליף מודל" else "בחירת קובץ מודל")
            }
            if (installed) OutlinedButton(onClick = { vm.deleteModel() }) { Text("מחיקה") }
        }
    }
    if (message.isNotEmpty()) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NotesAppSection(store: Store, resumeTick: Int) {
    val ctx = LocalContext.current
    val selected by store.notesApp.collectAsState()
    val apps = remember(resumeTick) { NotesApps.installed(ctx) }
    Text("איפה לשמור פתקים", style = MaterialTheme.typography.titleMedium)
    Text("העוזר תמיד שומר עותק אצלו. אפשר לבחור שגם יפתח פתק באפליקציית הפתקים של הטלפון.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    val options = listOf<Pair<String?, String>>(null to "רק בעוזר") + apps.map { it.packageName to it.label }
    options.forEach { (pkg, label) ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected == pkg, onClick = { store.setNotesApp(pkg) })
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == pkg, onClick = { store.setNotesApp(pkg) })
            Text(label)
        }
    }
    if (apps.isEmpty()) {
        Text("לא נמצאה אפליקציית פתקים בטלפון.", style = MaterialTheme.typography.bodySmall)
    }
    Text("אפשר גם לציין בפקודה: \"תרשום פתק בקיפ לקנות ביצים\".",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun RoutinesSection(store: Store) {
    val routines by store.routines.collectAsState()
    var editing by remember { mutableStateOf<Routine?>(null) }
    var creating by remember { mutableStateOf(false) }

    Text("שגרות (פקודות משלך)", style = MaterialTheme.typography.titleMedium)
    Text("שם אחד שמפעיל כמה פקודות. למשל \"מצב לימוד\": מצב שקט, בהירות 40, פתח ספריא.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    routines.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodyLarge)
                    Text(r.commands.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { editing = r }) { Icon(Icons.Filled.Edit, contentDescription = "עריכה") }
                IconButton(onClick = { store.deleteRoutine(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = "מחיקה") }
            }
        }
    }
    OutlinedButton(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp)) { Text("שגרה חדשה") }

    val current = editing
    if (creating || current != null) {
        RoutineDialog(current, onDismiss = { creating = false; editing = null }) { name, cmds ->
            store.saveRoutine(name, cmds, current?.id)
            creating = false; editing = null
        }
    }
}

@Composable
private fun RoutineDialog(initial: Routine?, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var commands by remember { mutableStateOf(initial?.commands?.joinToString("\n") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "שגרה חדשה" else "עריכת שגרה") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("שם (מה תגיד)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(commands, { commands = it }, label = { Text("פקודות, אחת בכל שורה") },
                    placeholder = { Text("מצב שקט\nבהירות 40\nפתח ספריא") }, minLines = 4,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && commands.isNotBlank(),
                onClick = { onSave(name, commands.lines()) },
            ) { Text("שמירה") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
