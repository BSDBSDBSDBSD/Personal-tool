package com.ozer.assistant.actions

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ozer.assistant.MainActivity
import com.ozer.assistant.R
import com.ozer.assistant.data.Reminder
import com.ozer.assistant.data.Store

object Reminders {
    const val CHANNEL = "reminders"
    private const val EXTRA_ID = "reminder_id"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "תזכורות", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "תזכורות שביקשת מהעוזר"
                    enableVibration(true)
                },
            )
        }
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, r.id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, r)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }

    fun cancel(ctx: Context, r: Reminder) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, r))
    }

    fun rescheduleAll(ctx: Context) {
        val store = Store.get(ctx)
        val now = System.currentTimeMillis()
        for (r in store.reminders.value) {
            if (r.fired) continue
            if (r.at > now) schedule(ctx, r) else fire(ctx, r.id)
        }
    }

    fun fire(ctx: Context, id: Long) {
        val store = Store.get(ctx)
        val r = store.reminder(id) ?: return
        if (r.fired) return
        store.markFired(id)
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle("תזכורת")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(r.id.hashCode(), n)
        } catch (e: SecurityException) {
            // notifications permission not granted; the reminder still shows as done in the app
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            fire(context, intent.getLongExtra(EXTRA_ID, -1))
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            rescheduleAll(context)
        }
    }
}
