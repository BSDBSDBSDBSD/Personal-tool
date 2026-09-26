package com.ozer.assistant.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import com.ozer.assistant.nlu.Fuzzy
import java.time.LocalTime

data class Contact(val name: String, val number: String)

object Phone {
    fun contacts(ctx: Context): List<Contact> {
        val out = ArrayList<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                out += Contact(name, number)
            }
        }
        return out.distinctBy { it.name }
    }

    fun call(ctx: Context, query: String): ActionResult {
        if (query.isBlank()) return ActionResult("למי להתקשר?")
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult("כדי למצוא את איש הקשר צריך הרשאה לאנשי הקשר.", listOf(Manifest.permission.READ_CONTACTS))
        }
        // A number said directly ("תתקשר ל-0501234567")
        val digits = query.filter { it.isDigit() || it == '+' }
        val target: Contact = if (digits.length >= 3) Contact(digits, digits) else {
            Fuzzy.best(query, contacts(ctx), 0.72) { listOf(it.name) }?.first
                ?: return ActionResult("לא מצאתי באנשי הקשר את \"$query\".")
        }
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target.number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Apps.tryStart(ctx, intent)) return ActionResult("לא הצלחתי לפתוח את החייגן.")
        return if (canCall) ActionResult("מתקשר ל${target.name}.")
        else ActionResult("פתחתי את החייגן עם ${target.name} — לחץ חיוג. (אפשר לאשר הרשאת שיחות בהגדרות העוזר כדי שיחייג ישר.)")
    }

    fun timer(ctx: Context, seconds: Long?): ActionResult {
        if (seconds == null || seconds <= 0) return ActionResult("לכמה זמן? למשל: 'טיימר לעשר דקות'.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtMost(86399).toInt())
            .putExtra(AlarmClock.EXTRA_MESSAGE, "טיימר")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (Apps.tryStart(ctx, i)) ActionResult("הפעלתי טיימר ל${Say.duration(seconds)}.")
        else ActionResult("לא מצאתי אפליקציית שעון שמקבלת טיימרים.")
    }

    fun alarm(ctx: Context, time: LocalTime?): ActionResult {
        if (time == null) return ActionResult("לאיזו שעה? למשל: 'תעיר אותי בשש וחצי'.")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "עוזר")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (Apps.tryStart(ctx, i)) ActionResult("כיוונתי שעון מעורר ל-${Say.clock(time)}.")
        else ActionResult("לא מצאתי אפליקציית שעון שמקבלת שעונים מעוררים.")
    }
}
