package com.ozer.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import com.ozer.assistant.actions.ActionResult
import com.ozer.assistant.actions.Apps
import com.ozer.assistant.actions.DeviceControl
import com.ozer.assistant.actions.Music
import com.ozer.assistant.actions.Phone
import com.ozer.assistant.actions.Reminders
import com.ozer.assistant.actions.Say
import com.ozer.assistant.data.Store
import com.ozer.assistant.nlu.Command
import com.ozer.assistant.nlu.Features
import com.ozer.assistant.nlu.IntentClassifier
import com.ozer.assistant.nlu.Understanding
import java.time.LocalDateTime
import java.time.ZoneId

data class Reply(
    val text: String,
    val permissions: List<String> = emptyList(),
    val debug: String = "",
    val retry: Boolean = true,
)

/** Understands a sentence and carries it out. Runs fully on the phone. */
class Assistant(private val ctx: Context) {
    private val store = Store.get(ctx)
    private val understanding: Understanding = ctx.assets.let { a ->
        Understanding(IntentClassifier.load(a.open("nlu_model.bin"), a.open("nlu_labels.txt")))
    }

    fun handle(text: String): Reply {
        val cmd = understanding.understand(text)
        var result = run(cmd, text)
        // If the classifier wasn't sure, but the whole sentence is simply an app's name, open it.
        if (cmd.intent == "other") {
            Apps.find(ctx, text)?.let { app ->
                if (Apps.launch(ctx, app)) result = ActionResult("פותח את ${app.label}.")
            }
        }
        val debug = "${cmd.intent} ${"%.0f".format(cmd.confidence * 100)}% (אחריו: ${cmd.runnerUp})" +
            (if (cmd.query.isNotEmpty()) " · \"${cmd.query}\"" else "") +
            (cmd.artist?.let { " · אמן: \"$it\"" } ?: "")
        return Reply(result.text, result.permissions, debug, result.retry)
    }

    private fun need(perm: String) = ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED

    private fun run(c: Command, raw: String): ActionResult = when (c.intent) {
        "open_app" -> openApp(c.query.ifBlank { raw })
        "play_song" -> playSong(c)
        "music_pause" -> { Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); ActionResult("עצרתי.") }
        "music_resume" -> { Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY); ActionResult("ממשיך לנגן.") }
        "music_next" -> { Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); ActionResult("השיר הבא.") }
        "music_prev" -> { Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); ActionResult("השיר הקודם.") }
        "note_add" -> {
            if (c.query.isBlank()) ActionResult("מה לרשום? למשל: 'תרשום לקנות חלב'.")
            else { store.addNote(c.query); ActionResult("רשמתי: ${c.query}") }
        }
        "note_list" -> listNotes(c.query)
        "reminder_add" -> addReminder(c)
        "timer_set" -> Phone.timer(ctx, c.time?.durationSeconds)
        "alarm_set" -> Phone.alarm(ctx, c.time?.clock ?: c.time?.at?.toLocalTime())
        "call_contact" -> Phone.call(ctx, c.query)
        "flashlight_on" -> DeviceControl.flashlight(ctx, true)
        "flashlight_off" -> DeviceControl.flashlight(ctx, false)
        "wifi_on" -> DeviceControl.wifi(ctx, true)
        "wifi_off" -> DeviceControl.wifi(ctx, false)
        "bluetooth_on" -> DeviceControl.bluetooth(ctx, true)
        "bluetooth_off" -> DeviceControl.bluetooth(ctx, false)
        "volume_up" -> DeviceControl.volumeStep(ctx, true)
        "volume_down" -> DeviceControl.volumeStep(ctx, false)
        "volume_set" -> DeviceControl.volumeSet(ctx, c.number, c.percent)
        "volume_mute" -> DeviceControl.mute(ctx)
        "brightness_up" -> DeviceControl.brightnessStep(ctx, true)
        "brightness_down" -> DeviceControl.brightnessStep(ctx, false)
        "brightness_set" -> DeviceControl.brightnessSet(ctx, c.number)
        "brightness_auto" -> DeviceControl.brightnessAuto(ctx)
        "dnd_on" -> DeviceControl.doNotDisturb(ctx, true)
        "dnd_off" -> DeviceControl.doNotDisturb(ctx, false)
        "vibrate_mode" -> DeviceControl.vibrate(ctx)
        "open_settings" -> DeviceControl.openSettings(ctx, c.settingsPage ?: com.ozer.assistant.nlu.SettingsPage.MAIN)
        "time_query" -> ActionResult("השעה ${Say.clock(java.time.LocalTime.now())}.")
        "date_query" -> {
            val today = java.time.LocalDate.now()
            val heb = Say.hebrewDate()
            ActionResult("היום ${Say.dayName(today.dayOfWeek)}, ${Say.gregorian(today)}" +
                if (heb.isNotEmpty()) ".\nבתאריך עברי: $heb (התאריך העברי מתחלף בשקיעה)." else ".")
        }
        "battery_query" -> DeviceControl.battery(ctx)
        "greeting" -> ActionResult(greeting())
        "thanks" -> ActionResult(listOf("בשמחה!", "בכיף.", "תמיד לשירותך.", "אין על מה.").random())
        "help" -> ActionResult(HELP)
        else -> ActionResult("לא הבנתי. אפשר לנסח אחרת? תגיד 'עזרה' כדי לראות מה אני יודע לעשות.")
    }

    private fun greeting(): String {
        val h = java.time.LocalTime.now().hour
        val hello = when (h) {
            in 5..11 -> "בוקר טוב!"
            in 12..16 -> "צהריים טובים!"
            in 17..21 -> "ערב טוב!"
            else -> "לילה טוב!"
        }
        return "$hello במה אפשר לעזור?"
    }

    private fun openApp(query: String): ActionResult {
        Apps.find(ctx, query)?.let { app ->
            return if (Apps.launch(ctx, app)) ActionResult("פותח את ${app.label}.")
            else ActionResult("לא הצלחתי לפתוח את ${app.label}.")
        }
        Apps.launchSpecial(ctx, query)?.let { return ActionResult("פותח את $it.") }
        return ActionResult("לא מצאתי אפליקציה בשם \"$query\" בטלפון.")
    }

    private fun playSong(c: Command): ActionResult {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (c.query.isBlank() && c.artist == null) {
            Music.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            return ActionResult("מנגן.")
        }
        if (need(perm)) return ActionResult("כדי למצוא שירים בטלפון צריך הרשאה לקבצי מוזיקה.", listOf(perm))
        val found = Music.search(Music.songs(ctx), c.query, c.artist)
        val said = listOfNotNull(c.query.ifBlank { null }, c.artist?.let { "של $it" }).joinToString(" ")
        if (found.isEmpty()) {
            if (Music.playFromSearch(ctx, listOfNotNull(c.query.ifBlank { null }, c.artist).joinToString(" "))) {
                return ActionResult("לא מצאתי בדיוק את \"$said\" — ביקשתי ממוסיקולט לחפש.")
            }
            return ActionResult("לא מצאתי בטלפון שיר \"$said\".")
        }
        val song = found.first()
        val player = Music.play(ctx, song) ?: return ActionResult("לא מצאתי נגן שיכול לנגן את השיר.")
        val who = if (song.artist.isNotBlank()) " של ${song.artist}" else ""
        return ActionResult("מנגן את ${song.title.ifBlank { song.fileName }}$who ב$player.")
    }

    private fun listNotes(about: String): ActionResult {
        val all = store.notes.value
        if (all.isEmpty()) return ActionResult("אין לך עדיין פתקים. תגיד למשל 'תרשום לקנות חלב'.")
        val q = Features.normalize(about)
        val list = if (q.isBlank()) all else all.filter { n ->
            val t = Features.normalize(n.text)
            q.split(' ').any { w -> t.contains(w) || t.contains(w.drop(1)) && w.length > 3 }
        }
        if (list.isEmpty()) return ActionResult("לא מצאתי פתק על \"$about\".")
        val shown = list.take(10).mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
        val more = if (list.size > 10) "\n(ועוד ${list.size - 10} בלשונית הפתקים)" else ""
        return ActionResult((if (q.isBlank()) "הפתקים שלך:\n" else "מצאתי:\n") + shown + more)
    }

    private fun addReminder(c: Command): ActionResult {
        val at = c.time?.at ?: return ActionResult("מתי להזכיר? למשל: 'תזכיר לי מחר בשמונה ${c.query.ifBlank { "לקנות חלב" }}'.")
        val text = c.query.ifBlank { "תזכורת" }
        if (!at.isAfter(LocalDateTime.now())) return ActionResult("הזמן הזה כבר עבר. מתי להזכיר?")
        val r = store.addReminder(text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        Reminders.schedule(ctx, r)
        val perms = if (Build.VERSION.SDK_INT >= 33 && need(Manifest.permission.POST_NOTIFICATIONS))
            listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        return ActionResult("אזכיר לך ${Say.whenText(at)}: $text", perms, retry = false)
    }

    companion object {
        val HELP = """
            אני עובד בלי אינטרנט. אפשר לבקש למשל:
            • פתח וואטסאפ / תכנס לוויז
            • תנגן את אדון עולם במוסיקולט / שים שיר של ישי ריבו
            • עצור, תמשיך, השיר הבא
            • תרשום לקנות חלב / מה רשמתי?
            • תזכיר לי מחר בשמונה להתקשר לבנק
            • טיימר לעשר דקות / תעיר אותי בשש וחצי
            • תתקשר לאמא
            • תדליק פנס / תגביר ווליום / בהירות 50
            • מצב שקט / רטט / תדליק בלוטוס / מצב טיסה
            • מה השעה? מה התאריך העברי? כמה סוללה?
            לדבר בלי אינטרנט: צריך להתקין מודל Whisper בלשונית הגדרות.
        """.trimIndent()
    }
}
