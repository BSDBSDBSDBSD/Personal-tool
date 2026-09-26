package com.ozer.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.ozer.assistant.nlu.Fuzzy
import com.ozer.assistant.nlu.HebrewText
import com.ozer.assistant.nlu.TimeResult
import com.ozer.assistant.nlu.Understanding
import java.time.ZoneId

/** Messages (SMS / WhatsApp) and calendar events. Both open the ready-made item; the user taps send/save. */
object Messaging {
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /**
     * [query] is "<contact> <message>", e.g. "לאמא שאני מאחר". The contact may be 1–3 words; the split
     * that best matches the address book wins.
     */
    fun send(ctx: Context, query: String, channel: String?): ActionResult {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult("כדי לשלוח הודעה צריך הרשאה לאנשי הקשר.", listOf(Manifest.permission.READ_CONTACTS))
        }
        val tokens = HebrewText.tokenize(query)
        if (tokens.isEmpty()) return ActionResult("למי לשלוח? למשל: 'תשלח לאמא שאני מאחר'.")

        var name: String? = null
        var number: String? = null
        var used = 0
        val digits = tokens[0].norm.filter { it.isDigit() }
        if (digits.length >= 7) {
            name = digits; number = digits; used = 1
        } else {
            val contacts = Phone.contacts(ctx)
            var best = 0.0
            for (k in minOf(3, tokens.size) downTo 1) {
                val candidate = tokens.take(k).joinToString(" ") { it.display }
                val m = Fuzzy.best(candidate, contacts, 0.8) { listOf(it.name) } ?: continue
                // prefer the longer split on (near) ties: "יוסי כהן" over "יוסי"
                if (m.second > best + 0.02) {
                    best = m.second; name = m.first.name; number = m.first.number; used = k
                }
            }
        }
        if (number == null) {
            return ActionResult("לא מצאתי באנשי הקשר את \"${tokens.first().display}\".")
        }
        val rest = tokens.drop(used).dropWhile { it.norm.isEmpty() }
        val text = Understanding.join(Understanding.stripShe(rest)).trimStart(':', '-', ' ')

        if (channel == "whatsapp") {
            val pkg = listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { Apps.isInstalled(ctx, it) }
            if (pkg != null) {
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=${international(number)}&text=${Uri.encode(text)}")
                val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Apps.tryStart(ctx, i)) {
                    return ActionResult(
                        if (text.isBlank()) "פתחתי צ'אט וואטסאפ עם $name."
                        else "פתחתי בוואטסאפ הודעה ל$name: \"$text\". לחץ שליחה.",
                    )
                }
            }
        }
        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Apps.tryStart(ctx, sms)) return ActionResult("לא מצאתי אפליקציית הודעות.")
        val note = if (channel == "whatsapp") " (וואטסאפ לא מותקן, אז פתחתי SMS)" else ""
        return ActionResult(
            if (text.isBlank()) "פתחתי הודעה חדשה ל$name$note."
            else "פתחתי הודעה ל$name$note: \"$text\". לחץ שליחה.",
        )
    }

    /** Israeli local numbers (05x...) to the international form WhatsApp wants (9725x...). */
    fun international(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        else if (d.startsWith("0")) d = "972" + d.drop(1)
        return d
    }

    fun calendar(ctx: Context, title: String, time: TimeResult?): ActionResult {
        val at = time?.at ?: return ActionResult("מתי? למשל: 'תוסיף ליומן פגישה מחר בעשר'.")
        val allDay = time.clock == null && time.durationSeconds == null
        val begin = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val name = title.ifBlank { "אירוע" }
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, name)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Apps.tryStart(ctx, i)) return ActionResult("לא מצאתי אפליקציית יומן בטלפון.")
        val whenText = if (allDay) Say.whenText(at).substringBefore(" ב-") else Say.whenText(at)
        return ActionResult("פתחתי ביומן: \"$name\" $whenText. לחץ שמירה.")
    }
}
