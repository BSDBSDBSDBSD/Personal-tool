package com.ozer.assistant.actions

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.ozer.assistant.nlu.Features
import com.ozer.assistant.nlu.Fuzzy

data class NotifItem(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long)

/**
 * Keeps the latest notifications in memory (never saved, never sent anywhere) so the assistant can
 * answer "מה ההודעה האחרונה בוואטסאפ?". Needs the user to allow notification access.
 */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        try { activeNotifications?.sortedBy { it.postTime }?.forEach { add(this, it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(this, sbn)

    companion object {
        private const val MAX = 60
        private val items = ArrayDeque<NotifItem>()

        fun recent(): List<NotifItem> = synchronized(items) { items.toList().asReversed() }

        private fun add(ctx: Context, sbn: StatusBarNotification) {
            if (sbn.packageName == ctx.packageName || sbn.isOngoing) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val app = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) { sbn.packageName }
            val item = NotifItem(sbn.key, sbn.packageName, app, title, text, sbn.postTime)
            synchronized(items) {
                items.removeAll { it.key == item.key && it.text == item.text }
                items.addLast(item)
                while (items.size > MAX) items.removeFirst()
            }
        }
    }
}

object Notifications {
    fun hasAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openAccessSettings(ctx: Context) {
        val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                ComponentName(ctx, NotifListener::class.java).flattenToString())
        if (!Apps.tryStart(ctx, detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Apps.tryStart(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** [query]: an app ("בוואטסאפ"), a person ("משה"), or empty for everything. */
    fun read(ctx: Context, query: String, rawText: String): ActionResult {
        if (!hasAccess(ctx)) {
            openAccessSettings(ctx)
            return ActionResult("כדי לקרוא התראות צריך לאשר לעוזר 'גישה להתראות'. פתחתי את המסך: תפעיל את 'עוזר' ותנסה שוב. " +
                "ההתראות נשארות רק בטלפון.")
        }
        val all = NotifListener.recent()
        if (all.isEmpty()) return ActionResult("אין התראות חדשות מאז שהעוזר קיבל גישה.")
        val onlyLast = Features.normalize(rawText).let { it.contains("האחרונה") || it.contains("האחרונ ") }
        var list = all
        var about = ""
        if (query.isNotBlank()) {
            // by app?
            val apps = all.distinctBy { it.pkg }
            val installed = Apps.list(ctx).associateBy { it.packageName }
            val byApp = Fuzzy.best(query, apps, 0.8) { n -> listOf(n.app) + (installed[n.pkg]?.names ?: emptyList()) }
            if (byApp != null) {
                list = all.filter { it.pkg == byApp.first.pkg }
                about = " מ${byApp.first.app}"
            } else {
                // by sender / chat title
                list = all.filter { Fuzzy.score(query, it.title) >= 0.75 ||
                    Features.normalize(it.title).contains(Features.normalize(query).removePrefix("מ")) }
                about = " מ$query"
                if (list.isEmpty()) return ActionResult("לא מצאתי התראות$about.")
            }
        }
        val shown = list.take(if (onlyLast) 1 else 5)
        val lines = shown.joinToString("\n") { n ->
            val who = if (n.title.isNotEmpty()) n.title else n.app
            val where = if (about.isEmpty() && n.app != who) " (${n.app})" else ""
            "• $who$where: ${n.text.take(200)}"
        }
        val head = if (onlyLast) "ההתראה האחרונה$about:" else "ההתראות האחרונות$about:"
        return ActionResult("$head\n$lines")
    }
}
