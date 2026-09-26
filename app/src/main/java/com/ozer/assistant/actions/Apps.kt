package com.ozer.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import com.ozer.assistant.nlu.Fuzzy

data class InstalledApp(val label: String, val packageName: String, val names: List<String>)

/** Lists every launchable app on the phone and finds the one the user named. */
object Apps {
    /** Hebrew names people use for popular apps, keyed by package. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "com.whatsapp" to listOf("וואטסאפ", "ווטסאפ", "וטסאפ", "ווצאפ", "וואצאפ", "ווטספ"),
        "com.whatsapp.w4b" to listOf("וואטסאפ עסקי", "ווטסאפ ביזנס"),
        "com.google.android.youtube" to listOf("יוטיוב", "יו טיוב"),
        "com.android.chrome" to listOf("כרום", "גוגל כרום", "דפדפן", "אינטרנט"),
        "com.google.android.googlequicksearchbox" to listOf("גוגל", "חיפוש"),
        "com.waze" to listOf("וויז", "וייז", "ווייז", "ויז"),
        "com.google.android.apps.maps" to listOf("מפות", "גוגל מפות", "מפה"),
        "org.telegram.messenger" to listOf("טלגרם"),
        "com.instagram.android" to listOf("אינסטגרם", "אינסטה"),
        "com.facebook.katana" to listOf("פייסבוק"),
        "com.spotify.music" to listOf("ספוטיפיי"),
        "com.google.android.gm" to listOf("ג'ימייל", "גימייל", "מייל", "דואר"),
        "in.krosbits.musicolet" to listOf("מוסיקולט", "מוזיקולט", "מוסיקה", "מוזיקה", "נגן", "נגן מוזיקה"),
        "com.tranzmate" to listOf("מוביט"),
        "com.gettaxi.android" to listOf("גט", "גט טקסי"),
        "com.pango.app" to listOf("פנגו"),
        "com.cellopark.app" to listOf("סלופארק"),
        "com.netflix.mediaclient" to listOf("נטפליקס"),
        "com.zhiliaoapp.musically" to listOf("טיקטוק"),
        "us.zoom.videomeetings" to listOf("זום"),
        "com.google.android.apps.photos" to listOf("תמונות", "גוגל תמונות", "פוטוס", "גלריה"),
        "com.android.vending" to listOf("חנות", "פליי סטור", "גוגל פליי", "חנות אפליקציות", "חנות פליי"),
        "com.google.android.apps.docs" to listOf("דרייב", "גוגל דרייב"),
        "com.google.android.apps.translate" to listOf("תרגום", "גוגל תרגום", "מתרגם"),
        "com.google.android.keep" to listOf("קיפ", "גוגל קיפ"),
        "com.twitter.android" to listOf("טוויטר", "איקס"),
        "com.snapchat.android" to listOf("סנאפצ'ט", "סנאפ"),
        "com.viber.voip" to listOf("ויבר", "וייבר"),
        "com.bnhp.payments.paymentsapp" to listOf("ביט"),
        "com.payboxapp" to listOf("פייבוקס"),
        "com.sefaria" to listOf("ספריא", "ספריה"),
        "org.sefaria.sefaria" to listOf("ספריא", "ספריה"),
        "com.shazam.android" to listOf("שאזאם"),
        "com.microsoft.office.outlook" to listOf("אאוטלוק"),
        "com.microsoft.office.word" to listOf("וורד"),
        "com.microsoft.office.excel" to listOf("אקסל"),
        "com.wolt.android" to listOf("וולט"),
        "com.ravkav" to listOf("רב קו"),
        "il.co.ynet.android" to listOf("ynet", "וויינט"),
    )

    /** Generic names matched by what the app does rather than its package. */
    private val ROLE_WORDS: Map<String, List<String>> = mapOf(
        "calculator" to listOf("מחשבון", "calculator", "calc"),
        "clock" to listOf("שעון", "שעון מעורר", "clock", "alarm", "deskclock"),
        "calendar" to listOf("יומן", "לוח שנה", "calendar"),
        "contacts" to listOf("אנשי קשר", "contacts", "people"),
        "dialer" to listOf("טלפון", "חייגן", "dialer", "phone"),
        "messaging" to listOf("הודעות", "סמס", "sms", "messages", "messaging", "mms"),
        "files" to listOf("קבצים", "מנהל קבצים", "files", "filemanager", "documentsui", "myfiles"),
        "gallery" to listOf("גלריה", "תמונות", "gallery", "photos", "album"),
        "settings" to listOf("הגדרות", "settings"),
        "recorder" to listOf("הקלטות", "רשמקול", "מקליט", "recorder", "soundrecorder"),
        "radio" to listOf("רדיו", "fmradio", "radio"),
        "weather" to listOf("מזג אוויר", "weather"),
        "notes" to listOf("פנקס", "notes", "memo"),
    )

    @Volatile private var cache: List<InstalledApp>? = null
    private var cacheTime = 0L

    fun list(ctx: Context): List<InstalledApp> {
        val c = cache
        if (c != null && System.currentTimeMillis() - cacheTime < 60_000) return c
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .map { (pkg, label) ->
                val names = mutableListOf(label)
                ALIASES[pkg]?.let { names += it }
                val lowerPkg = pkg.lowercase()
                for ((role, words) in ROLE_WORDS) {
                    val englishKeys = words.filter { w -> w.all { it in 'a'..'z' } }
                    if (englishKeys.any { lowerPkg.contains(it) } || englishKeys.any { label.lowercase().contains(it) }) {
                        names += words.filterNot { w -> w.all { it in 'a'..'z' } }
                    }
                    if (role == "clock" && label.contains("שעון")) names += words
                }
                InstalledApp(label, pkg, names.distinct())
            }
        cache = apps
        cacheTime = System.currentTimeMillis()
        return apps
    }

    fun find(ctx: Context, query: String): InstalledApp? {
        if (query.isBlank()) return null
        return Fuzzy.best(query, list(ctx), 0.72) { it.names }?.first
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun launch(ctx: Context, app: InstalledApp): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(ctx, i)
    }

    /** Fallbacks for things that may have no launcher entry of their own. */
    fun launchSpecial(ctx: Context, query: String): String? {
        val n = com.ozer.assistant.nlu.Features.normalize(query)
        val (intent, name) = when {
            n.contains("מצלמ") || n.contains("camera") ->
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to "המצלמה"
            n.contains("הגדרות") || n.contains("settings") -> Intent(android.provider.Settings.ACTION_SETTINGS) to "ההגדרות"
            n.contains("חייגנ") || n == "טלפונ" || n == "הטלפונ" -> Intent(Intent.ACTION_DIAL) to "החייגן"
            else -> return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tryStart(ctx, intent)) name else null
    }

    fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent); true
    } catch (e: Exception) { false }
}
