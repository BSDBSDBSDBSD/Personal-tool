package com.ozer.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ozer.assistant.actions.Reminders
import com.ozer.assistant.speech.Recorder
import com.ozer.assistant.speech.WhisperEngine
import com.ozer.assistant.ui.ChatScreen
import com.ozer.assistant.ui.NotesScreen
import com.ozer.assistant.ui.RemindersScreen
import com.ozer.assistant.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Reminders.ensureChannel(this)
        Reminders.rescheduleAll(this)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root(vm, resumeTick.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: String, val icon: ImageVector) {
    CHAT("שיחה", Icons.AutoMirrored.Filled.Chat),
    NOTES("פתקים", Icons.Filled.EditNote),
    REMINDERS("תזכורות", Icons.Filled.Alarm),
    SETTINGS("הגדרות", Icons.Filled.Settings),
}

@Composable
private fun Root(vm: AssistantViewModel, resumeTick: Int) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val speakOn by vm.store.speak.collectAsState()

    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val reply by vm.lastReply
    LaunchedEffect(reply) { reply?.let { if (speakOn) speaker.speak(it.second) } }

    // Runtime permissions asked for by a command (e.g. contacts for "תתקשר לאמא").
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onPermissionsResult(res.values.all { it })
    }
    val needed by vm.permissionRequest
    LaunchedEffect(needed) { if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) }

    // Microphone
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val voice = remember {
        VoiceInput(
            ctx,
            onPartial = { partial = it },
            onResult = { partial = ""; vm.send(it) },
            onFail = { partial = ""; vm.messages += Message(false, it) },
            onListening = { listening = it },
        )
    }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    // Offline Whisper recognition, when the user has installed a model file.
    val whisper = remember { WhisperEngine.get(ctx) }
    val recorder = remember { Recorder() }
    val scope = rememberCoroutineScope()
    var whisperJob by remember { mutableStateOf<Job?>(null) }
    fun startWhisper() {
        whisperJob = scope.launch {
            try {
                listening = true
                partial = "מקשיב… דבר עכשיו"
                val audio = withContext(Dispatchers.IO) { recorder.record() }
                listening = false
                if (audio == null) {
                    partial = ""
                    vm.messages += Message(false, "לא שמעתי כלום, נסה שוב.")
                    return@launch
                }
                partial = "מזהה דיבור…"
                val text = whisper.transcribe(audio)
                partial = ""
                if (text.isBlank()) vm.messages += Message(false, "לא הצלחתי להבין, נסה שוב.") else vm.send(text)
            } catch (e: Exception) {
                listening = false
                partial = ""
                vm.messages += Message(false, e.message ?: "שגיאה בזיהוי דיבור.")
            }
        }
    }
    fun startListening() = if (whisper.hasModel()) startWhisper() else voice.start()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else vm.messages += Message(false, "בלי הרשאת מיקרופון אפשר להקליד בלבד.")
    }
    val startMic = {
        speaker.stop()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun onMic() {
        when {
            whisperJob?.isActive == true -> { if (listening) recorder.requestStop() }
            listening -> { voice.stop(); listening = false }
            else -> startMic()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.title) }, label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            Tab.CHAT -> ChatScreen(vm, listening, partial, onMic = { onMic() }, modifier = m)
            Tab.NOTES -> NotesScreen(vm.store, m)
            Tab.REMINDERS -> RemindersScreen(vm.store, m)
            Tab.SETTINGS -> SettingsScreen(vm, speaker.hebrewAvailable, resumeTick, m)
        }
    }
}
