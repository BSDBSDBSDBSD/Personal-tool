package com.ozer.assistant.actions

import android.content.Context
import android.content.Intent
import com.ozer.assistant.nlu.Features
import com.ozer.assistant.nlu.Fuzzy

/** Writing notes into the phone's own notes app (Keep, Samsung Notes, ...). */
object NotesApps {
    private const val KEEP = "com.google.android.keep"
    private const val CREATE_NOTE = "com.google.android.gms.actions.CREATE_NOTE"

    /** Known notes apps and the names people call them. */
    private val KNOWN: Map<String, List<String>> = mapOf(
        KEEP to listOf("קיפ", "גוגל קיפ", "keep"),
        "com.samsung.android.app.notes" to listOf("סמסונג נוטס", "סמסונג פתקים", "פתקים של סמסונג", "samsung notes"),
        "com.socialnmobile.dragonnote" to listOf("קולור נוט", "colornote"),
        "com.miui.notes" to listOf("פתקים של שיאומי"),
        "com.coloros.note" to listOf("פתקים של אופו"),
        "com.oneplus.note" to listOf("פתקים של וואן פלוס"),
        "com.simplemobiletools.notes.pro" to listOf("סימפל נוטס"),
        "org.fossify.notes" to listOf("פוסיפיי נוטס"),
        "com.microsoft.office.onenote" to listOf("וואן נוט", "onenote"),
        "com.evernote" to listOf("אוורנוט", "evernote"),
        "com.example.android.notepad" to listOf("פנקס של וואווי"),
        "com.android.notes" to listOf("פתקים"),
    )

    /** "באפליקציית הפתקים" etc: the user's default notes app, whichever it is. */
    private val GENERIC = listOf(
        "באפליקציית הפתקים", "באפליקציית פתקים", "בפתקים של הטלפון", "בפתקים של המכשיר", "באפליקציית הפנקס",
        "בפנקס", "בפתקים", "בפתקים שלי", "באפליקציה",
    ).map { Features.normalize(it) }.sortedByDescending { it.length }

    /** Installed notes apps: the known ones plus anything whose name says notes/memo. */
    fun installed(ctx: Context): List<InstalledApp> = Apps.list(ctx).filter { app ->
        app.packageName in KNOWN || app.label.lowercase().let { l ->
            listOf("note", "memo", "פתק", "פנקס", "notepad").any { l.contains(it) }
        }
    }.map { it.copy(names = it.names + (KNOWN[it.packageName] ?: emptyList())) }

    data class Target(val app: InstalledApp?, val generic: Boolean, val text: String)

    /**
     * Splits "בקיפ לקנות ביצים" into (Keep, "לקנות ביצים"). Looks at the start and the end of the text.
     * [generic] = the user said "באפליקציית הפתקים" without naming one.
     */
    fun resolve(ctx: Context, text: String): Target {
        val words = text.trim().split(Regex("\\s+"))
        val norm = words.map { Features.normalize(it) }
        // generic phrases, at the start or the end
        for (g in GENERIC) {
            val gl = g.split(' ').size
            if (words.size > gl && norm.take(gl).joinToString(" ") == g) return Target(null, true, words.drop(gl).joinToString(" "))
            if (words.size > gl && norm.takeLast(gl).joinToString(" ") == g) return Target(null, true, words.dropLast(gl).joinToString(" "))
        }
        val apps = installed(ctx)
        if (apps.isEmpty()) return Target(null, false, text)
        // "בקיפ ..." / "... בסמסונג נוטס": one or two words starting with ב
        for (k in 2 downTo 1) {
            if (words.size <= k) continue
            for (atStart in listOf(true, false)) {
                val part = if (atStart) words.take(k) else words.takeLast(k)
                if (!part.first().startsWith("ב")) continue
                val phrase = part.joinToString(" ").substring(1)
                val m = Fuzzy.best(phrase, apps, 0.85) { it.names } ?: continue
                val rest = if (atStart) words.drop(k) else words.dropLast(k)
                return Target(m.first, false, rest.joinToString(" "))
            }
        }
        return Target(null, false, text)
    }

    /** Opens a new note with [text] in the app. Returns false if the app refused it. */
    fun write(ctx: Context, pkg: String, text: String): Boolean {
        val create = Intent(CREATE_NOTE).setPackage(pkg).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent(Intent.ACTION_SEND).setPackage(pkg).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, text.take(40))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val order = if (pkg == KEEP) listOf(create, share) else listOf(share, create)
        return order.any { Apps.tryStart(ctx, it) }
    }
}
