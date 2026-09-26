package com.ozer.assistant.nlu

import java.time.LocalDateTime

enum class SettingsPage {
    MAIN, WIFI, BLUETOOTH, DISPLAY, SOUND, BATTERY, LOCATION, AIRPLANE, APPS, NETWORK, HOTSPOT, LANGUAGE,
    KEYBOARD, DATE, STORAGE, SECURITY, PRIVACY, ACCESSIBILITY, NOTIFICATIONS, ACCOUNTS, ABOUT,
}

data class Command(
    val intent: String,
    val confidence: Float,
    /** Main free-text slot: app name, song title, contact, note content, reminder text, search term. */
    val query: String = "",
    val artist: String? = null,
    val time: TimeResult? = null,
    val number: Int? = null,
    val percent: Boolean = false,
    val settingsPage: SettingsPage? = null,
    val runnerUp: String = "",
    /** For message_send: "whatsapp" or "sms". */
    val channel: String? = null,
)

/** Turns a sentence into a [Command]: classifier picks the intent, rules pull out the details. */
class Understanding(private val classifier: IntentClassifier) {

    fun understand(text: String, now: LocalDateTime = LocalDateTime.now()): Command {
        val pred = classifier.predict(text)
        val intent = if (pred.confidence < MIN_CONFIDENCE) "other" else pred.intent
        return extract(intent, text, now).copy(confidence = pred.confidence, runnerUp = pred.runnerUp)
    }

    fun extract(intent: String, text: String, now: LocalDateTime): Command {
        val tokens = HebrewText.tokenize(text)
        val base = Command(intent, 1f)
        return when (intent) {
            "open_app" -> base.copy(query = strip(tokens, OPEN_LEAD, TRAIL))
            "play_song" -> {
                val rest = stripTokens(tokens, PLAY_LEAD, TRAIL + PLAY_TRAIL)
                val ofIdx = rest.indexOfFirst { it.norm in OF_WORDS }
                when {
                    ofIdx == 0 -> base.copy(artist = join(rest.drop(1)))
                    ofIdx > 0 -> base.copy(query = join(rest.take(ofIdx)), artist = join(rest.drop(ofIdx + 1)))
                    else -> base.copy(query = join(rest))
                }
            }
            "call_contact" -> base.copy(query = strip(tokens, CALL_LEAD, TRAIL + CALL_TRAIL))
            "note_add" -> {
                base.copy(query = join(stripShe(stripTokens(tokens, NOTE_LEAD, TRAIL))))
            }
            "note_list" -> {
                val about = tokens.indexOfFirst { it.norm == n("על") }
                base.copy(query = if (about >= 0) join(tokens.drop(about + 1).let { stripTail(it, TRAIL) }) else "")
            }
            "reminder_add" -> {
                val tr = TimeParser.parseTokens(tokens, now)
                val remaining = tokens.filterIndexed { i, _ -> i !in tr.consumed }
                base.copy(time = tr, query = strip(remaining, REMIND_LEAD, TRAIL))
            }
            "timer_set" -> base.copy(time = TimeParser.parseTokens(tokens, now))
            "alarm_set" -> base.copy(time = TimeParser.parseTokens(tokens, now, preferMorning = true))
            "volume_set", "brightness_set" -> {
                val num = HebrewText.findNumber(tokens)
                val pct = text.contains('%') || tokens.any { it.norm == n("אחוז") || it.norm == n("אחוזים") }
                base.copy(number = num, percent = pct)
            }
            "open_settings" -> base.copy(settingsPage = settingsPage(text))
            "message_send" -> {
                val whatsapp = tokens.any { t -> HebrewText.cores(t.norm).any { it.second in WHATSAPP } }
                val kept = tokens.filterNot { t ->
                    HebrewText.cores(t.norm).any { it.second in WHATSAPP } || t.norm in SMS_WORDS
                }
                base.copy(query = strip(kept, MSG_LEAD, TRAIL), channel = if (whatsapp) "whatsapp" else "sms")
            }
            "calendar_add" -> {
                val tr = TimeParser.parseTokens(tokens, now)
                val remaining = tokens.filterIndexed { i, _ -> i !in tr.consumed }
                base.copy(time = tr, query = strip(remaining, CAL_LEAD, TRAIL + CAL_LEAD))
            }
            "notif_read" -> base.copy(query = strip(tokens, NOTIF_LEAD, TRAIL + NOTIF_LEAD))
            "calc" -> base.copy(query = text)
            "joke" -> base.copy(query = if (tokens.any { it.norm.contains(n("חיד")) }) "riddle" else "")
            else -> base
        }
    }

    companion object {
        const val MIN_CONFIDENCE = 0.35f

        private fun n(s: String) = HebrewText.normWord(s)
        private fun set(vararg w: String) = w.map { n(it) }.toSet()

        private val FILLER = set(
            "בבקשה", "עוזר", "היי", "הי", "שמע", "תקשיב", "נו", "יאללה", "תגיד", "אפשר", "לי", "את",
            "עכשיו", "אני", "רוצה", "תעשה", "טובה", "אותי", "אנא", "please", "תוכל", "תוכלי", "צריך", "מהר",
        )
        private val TRAIL = set("בבקשה", "עכשיו", "תודה", "מהר", "לי", "כבר", "יאללה", "please", "רבה", "עוזר")
        private val OPEN_LEAD = FILLER + set(
            "תפתח", "פתח", "תפתחי", "פתחי", "לפתוח", "תפתחו", "תכנס", "תיכנס", "כנס", "היכנס", "להיכנס", "תכנסי",
            "תעלה", "תעלי", "הפעל", "תפעיל", "תפעילי", "תריץ", "תביא", "תקפיץ", "פותח", "פתיחת", "תכניס", "תעביר",
            "קח", "open", "האפליקציה", "אפליקציית", "אפליקציה", "לאפליקציה", "לאפליקציית", "ל",
        )
        private val PLAY_LEAD = FILLER + set(
            "תנגן", "נגן", "תנגני", "תשמיע", "השמע", "תשמיעי", "שים", "תשים", "תשימי", "תפעיל", "הפעל", "תפעילי",
            "לנגן", "להשמיע", "תפתח", "פתח", "play", "תן", "בא", "לשמוע", "תדליק", "השיר", "שיר", "הניגון",
            "ניגון", "הזמר", "הזמרת", "שירים", "משהו", "מוזיקה", "מוסיקה", "במוסיקולט", "במוזיקולט",
        )
        private val PLAY_TRAIL = set(
            "במוסיקולט", "במוזיקולט", "מוסיקולט", "במוסיקה", "בנגן", "המוזיקה", "מהטלפון", "musicolet", "במוזיקה",
        )
        private val OF_WORDS = set("של", "מאת", "by")
        private val CALL_LEAD = FILLER + set(
            "תתקשר", "תתקשרי", "התקשר", "חייג", "תחייג", "תחייגי", "להתקשר", "תצלצל", "צלצל", "תוציא", "שיחה",
            "טלפון", "תרים", "תשיג", "תחבר", "עם", "אל", "call", "ל",
        )
        private val CALL_TRAIL = set("בטלפון", "בנייד", "לבית", "לנייד")
        private val NOTE_LEAD = FILLER + set(
            "תרשום", "רשום", "תרשמי", "תכתוב", "כתוב", "תכתבי", "תוסיף", "הוסף", "תיצור", "צור", "פתק", "חדש",
            "תשמור", "שמור", "תזכור", "לרשום", "לכתוב", "לפתקים", "בפתקים", "בפתק", "לפתק", "הערה", "note",
            "תכתתוב", "תירשום",
        )
        private val SHE_WORDS = set(
            "אני", "צריך", "צריכה", "יש", "אין", "מחר", "היום", "אתמול", "הוא", "היא", "אנחנו", "הם", "לא", "כל",
            "זה", "אתה", "אנחנו", "כבר", "הכל", "הכול", "את", "אני", "אחזור", "אגיע", "אאחר", "נפגש", "נדבר",
            "יהיה", "אפשר", "תבוא", "תבואו", "תחזור", "תתקשר", "תקנה", "תביא", "הגעתי", "יצאתי", "אתם",
        )
        private val REMIND_LEAD = FILLER + set(
            "תזכיר", "תזכירי", "הזכר", "תזכורת", "תקבע", "קבע", "תגדיר", "להזכיר", "תוסיף", "חדשה", "remind",
            "me", "תזכרי", "ש", "על",
        )

        private val WHATSAPP = set("וואטסאפ", "ווטסאפ", "וטסאפ", "וואצאפ", "ווצאפ", "whatsapp")
        private val SMS_WORDS = set("בסמס", "בsms", "במסרון", "בהודעה", "בהודעת", "סמס", "sms", "מסרון")
        private val MSG_LEAD = FILLER + set(
            "תשלח", "שלח", "תשלחי", "לשלוח", "תכתוב", "תכתבי", "כתוב", "תגיד", "תודיע", "תעדכן", "הודעה", "הודעת",
            "סמס", "sms", "מסרון", "חדשה", "אל",
        )
        private val CAL_LEAD = FILLER + set(
            "תוסיף", "הוסף", "תוסיפי", "תכניס", "תרשום", "רשום", "תקבע", "תשים", "ליומן", "ביומן", "יומן", "אירוע",
            "חדש", "צור", "תיצור", "ללוח", "השנה", "בלוח",
        )
        private val NOTIF_LEAD = FILLER + set(
            "מה", "מי", "ההתראות", "התראות", "התראה", "תקריא", "תקריאי", "הקרא", "תראה", "תראי", "ההודעה",
            "ההודעות", "הודעות", "הודעה", "האחרונה", "האחרונות", "אחרונות", "אחרונה", "החדשות", "חדשות", "יש",
            "כתב", "כתבה", "כתבו", "שלח", "שלחה", "שלחו", "אמר", "אמרה", "הגיע", "הגיעו", "שלי", "של", "notifications",
        )

        /** "שהחנייה בקומה 3" -> "החנייה בקומה 3": drops the Hebrew "that" prefix from the first word. */
        fun stripShe(tokens: List<Token>): List<Token> {
            val rest = tokens.toMutableList()
            if (rest.isEmpty()) return rest
            val f = rest[0]
            if (f.norm == "ש") rest.removeAt(0)
            else if (f.norm.length > 2 && f.norm[0] == 'ש') {
                val tail = f.norm.substring(1)
                if (tail[0] == 'ה' || tail in SHE_WORDS) rest[0] = Token(f.display.substring(1), tail)
            }
            return rest
        }

        fun join(t: List<Token>) = t.joinToString(" ") { it.display }.trim()

        fun stripTail(tokens: List<Token>, trail: Set<String>): List<Token> {
            var e = tokens.size
            while (e > 0 && tokens[e - 1].norm in trail) e--
            return tokens.subList(0, e)
        }

        fun stripTokens(tokens: List<Token>, lead: Set<String>, trail: Set<String>): List<Token> {
            var s = 0
            while (s < tokens.size && tokens[s].norm in lead) s++
            return stripTail(tokens.subList(s, tokens.size), trail)
        }

        fun strip(tokens: List<Token>, lead: Set<String>, trail: Set<String>) = join(stripTokens(tokens, lead, trail))

        private val PAGES: List<Pair<SettingsPage, List<String>>> = listOf(
            SettingsPage.AIRPLANE to listOf("טיסה", "airplane"),
            SettingsPage.HOTSPOT to listOf("נקודה חמה", "נקודת חמה", "hotspot", "נקודה"),
            SettingsPage.WIFI to listOf("וויפי", "ויפי", "וייפיי", "ווייפיי", "wifi", "wi fi", "אלחוטי", "וויי פיי"),
            SettingsPage.BLUETOOTH to listOf("בלוטוס", "בלוטות", "בלוטוט", "bluetooth"),
            SettingsPage.NETWORK to listOf("נתונים", "גלישה", "רשת", "סים", "סלולר", "data"),
            SettingsPage.BATTERY to listOf("סוללה", "סוללת", "חיסכון", "battery"),
            SettingsPage.LOCATION to listOf("מיקום", "gps", "גיפיאס", "location"),
            SettingsPage.DISPLAY to listOf("מסך", "תצוגה", "בהירות", "כהה", "לילה", "רקע", "גופן", "כתב", "display"),
            SettingsPage.SOUND to listOf("צליל", "צלצול", "רינגטון", "סאונד", "קול", "sound"),
            SettingsPage.KEYBOARD to listOf("מקלדת", "keyboard"),
            SettingsPage.LANGUAGE to listOf("שפה", "language"),
            SettingsPage.DATE to listOf("תאריך", "שעה", "date"),
            SettingsPage.STORAGE to listOf("אחסון", "זיכרון", "storage"),
            SettingsPage.SECURITY to listOf("אבטחה", "נעילה", "סיסמה", "security"),
            SettingsPage.PRIVACY to listOf("פרטיות", "privacy"),
            SettingsPage.ACCESSIBILITY to listOf("נגישות", "accessibility"),
            SettingsPage.NOTIFICATIONS to listOf("התראות", "הודעות", "notifications"),
            SettingsPage.ACCOUNTS to listOf("חשבונות", "סינכרון", "סנכרון", "accounts"),
            SettingsPage.APPS to listOf("אפליקציות", "יישומים", "apps"),
            SettingsPage.ABOUT to listOf("מידע על", "אודות", "about"),
        ).map { (p, ws) -> p to ws.map { Features.normalize(it) } }

        fun settingsPage(text: String): SettingsPage {
            val norm = " " + Features.normalize(text) + " "
            for ((page, words) in PAGES) {
                if (words.any { w -> norm.contains(w) }) return page
            }
            return SettingsPage.MAIN
        }
    }
}
