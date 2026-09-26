package com.ozer.assistant.nlu

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class TimeResult(
    /** Absolute moment, when the text contained a day, a clock time or "בעוד ...". */
    val at: LocalDateTime?,
    /** Length in seconds, when the text contained a duration ("10 דקות"). */
    val durationSeconds: Long?,
    /** Clock time when one was said explicitly (for alarms). */
    val clock: LocalTime?,
    /** Indices of tokens that were part of the time expression. */
    val consumed: Set<Int>,
)

/** Parses Hebrew time expressions. Pure logic, so it can be unit-tested off-device. */
object TimeParser {
    private fun n(s: String) = HebrewText.normWord(s)

    private val MINUTE = setOf(n("דקה"), n("דקות"), n("דק"), "min", "minutes")
    private val HOUR = setOf(n("שעה"), n("שעות"), "hour", "hours")
    private val SECOND = setOf(n("שניה"), n("שנייה"), n("שניות"), n("שנ"), "sec", "seconds")
    private val DAY = setOf(n("יום"), n("ימים"))
    private val WEEK = setOf(n("שבוע"), n("שבועות"))
    private val DUALS = mapOf(
        n("שעתיים") to 7200L, n("יומיים") to 172800L, n("שבועיים") to 1209600L, n("דקותיים") to 120L,
    )

    private val WEEKDAYS = mapOf(
        n("ראשון") to DayOfWeek.SUNDAY, n("שני") to DayOfWeek.MONDAY, n("שלישי") to DayOfWeek.TUESDAY,
        n("רביעי") to DayOfWeek.WEDNESDAY, n("חמישי") to DayOfWeek.THURSDAY, n("שישי") to DayOfWeek.FRIDAY,
    )

    private enum class Period { MORNING, NOON, AFTERNOON, EVENING, NIGHT }

    private fun unitSeconds(word: String): Long? = when (word) {
        in MINUTE -> 60L
        in HOUR -> 3600L
        in SECOND -> 1L
        in DAY -> 86400L
        in WEEK -> 604800L
        else -> null
    }

    /** "10 דקות", "חצי שעה", "שעה וחצי", "שעתיים", "דקה", "כמה דקות". Returns seconds and tokens used. */
    fun readDuration(t: List<Token>, start: Int): Pair<Long, Int>? {
        if (start >= t.size) return null
        var i = start
        var total: Long
        val first = HebrewText.cores(t[i].norm).map { it.second }

        val dual = first.firstNotNullOfOrNull { DUALS[it] }
        val unitAlone = first.firstNotNullOfOrNull { unitSeconds(it) }
        when {
            dual != null -> { total = dual; i++ }
            unitAlone != null -> { total = unitAlone; i++ }
            first.any { it == n("חצי") || it == n("רבע") } && i + 1 < t.size && unitSeconds(t[i + 1].norm) != null -> {
                val u = unitSeconds(t[i + 1].norm)!!
                total = if (first.contains(n("חצי"))) u / 2 else u / 4
                i += 2
            }
            first.contains(n("כמה")) && i + 1 < t.size && unitSeconds(t[i + 1].norm) != null -> {
                val u = unitSeconds(t[i + 1].norm)!!
                total = if (u == 60L) 300L else u * 2
                i += 2
            }
            else -> {
                val (num, used) = HebrewText.readNumber(t, i) ?: return null
                val u = t.getOrNull(i + used)?.let { unitSeconds(it.norm) } ?: return null
                total = num * u
                i += used + 1
            }
        }
        // "וחצי" / "ורבע" / "ו-20 דקות" after the first part
        val lastUnit = unitOfPrevious(t, i - 1)
        while (i < t.size) {
            val w = t[i].norm
            if (w == n("וחצי") && lastUnit != null) { total += lastUnit / 2; i++; continue }
            if (w == n("ורבע") && lastUnit != null) { total += lastUnit / 4; i++; continue }
            if (w.startsWith("ו") && w.length > 1) {
                val sub = listOf(Token(w.substring(1), w.substring(1))) + t.subList(i + 1, t.size)
                val more = readDuration(sub, 0)
                if (more != null) { total += more.first; i += more.second; continue }
            }
            break
        }
        return total to (i - start)
    }

    private fun unitOfPrevious(t: List<Token>, idx: Int): Long? {
        val w = t.getOrNull(idx)?.norm ?: return null
        return unitSeconds(w) ?: DUALS[w]?.let { if (it == 7200L) 3600L else null }
    }

    private fun periodOf(w: String): Period? = when (w) {
        n("בוקר"), n("בבוקר"), "am" -> Period.MORNING
        n("צהריים"), n("בצהריים"), n("צהרים"), n("בצהרים") -> Period.NOON
        n("אחהצ"), n("אחרצ") -> Period.AFTERNOON
        n("ערב"), n("בערב"), "pm" -> Period.EVENING
        n("לילה"), n("בלילה") -> Period.NIGHT
        else -> null
    }

    private fun applyPeriod(h: Int, p: Period?): Int = when (p) {
        null -> h
        Period.MORNING -> if (h == 12) 0 else h
        Period.NOON -> if (h in 1..5) h + 12 else h
        Period.AFTERNOON, Period.EVENING -> if (h in 1..11) h + 12 else h
        Period.NIGHT -> if (h in 6..11) h + 12 else if (h == 12) 0 else h
    }

    /**
     * @param preferMorning for alarms: an hour without a period ("בשש") means morning.
     */
    fun parse(text: String, now: LocalDateTime, preferMorning: Boolean = false): TimeResult =
        parseTokens(HebrewText.tokenize(text), now, preferMorning)

    fun parseTokens(t: List<Token>, now: LocalDateTime, preferMorning: Boolean = false): TimeResult {
        val used = HashSet<Int>()
        var relative: Long? = null
        var duration: Long? = null
        var dayOffset: Int? = null
        var weekday: DayOfWeek? = null
        var hour: Int? = null
        var minute = 0
        var period: Period? = null
        var defaultTime: LocalTime? = null

        var i = 0
        while (i < t.size) {
            if (i in used) { i++; continue }
            val cores = HebrewText.cores(t[i].norm).map { it.second }
            fun has(vararg words: String) = words.any { n(it) in cores }

            // "בעוד 10 דקות"
            if (has("עוד") && relative == null) {
                val d = readDuration(t, i + 1)
                if (d != null) {
                    relative = d.first
                    for (k in i..i + d.second) used += k
                    i += d.second + 1
                    continue
                }
            }
            // day words
            when {
                has("היום") -> { dayOffset = 0; used += i; i++; continue }
                has("מחר") -> { dayOffset = 1; used += i; i++; continue }
                has("מחרתיים") -> { dayOffset = 2; used += i; i++; continue }
                has("מוצאי", "מוצש", "מוצאש") -> {
                    weekday = DayOfWeek.SATURDAY; defaultTime = LocalTime.of(20, 0); used += i
                    if (t.getOrNull(i + 1)?.norm == n("שבת")) used += i + 1
                    i++; continue
                }
                has("שבת") && (i == 0 || t[i - 1].norm != n("מצב")) -> {
                    weekday = DayOfWeek.SATURDAY; used += i; i++; continue
                }
                has("יום") && i + 1 < t.size && WEEKDAYS.containsKey(t[i + 1].norm) -> {
                    weekday = WEEKDAYS[t[i + 1].norm]; used += i; used += i + 1; i += 2; continue
                }
                has("שבוע") && t.getOrNull(i + 1)?.norm == n("הבא") -> {
                    dayOffset = (dayOffset ?: 0) + 7; used += i; used += i + 1; i += 2; continue
                }
                has("חצות") -> { hour = 0; used += i; if (dayOffset == null) dayOffset = 1; i++; continue }
            }
            // "אחרי הצהריים"
            if (has("אחרי") && t.getOrNull(i + 1)?.let { periodOf(it.norm) } == Period.NOON) {
                period = Period.AFTERNOON; used += i; used += i + 1; i += 2; continue
            }
            val p = periodOf(t[i].norm)
            if (p != null) {
                period = p; used += i; i++; continue
            }

            // "ברבע לשמונה", "בעשרה לתשע"
            val before = when {
                has("רבע") -> 15
                has("עשרה", "עשר") && t.getOrNull(i + 1)?.norm?.startsWith("ל") == true -> 10
                has("חמישה", "חמש") && t.getOrNull(i + 1)?.norm?.startsWith("ל") == true -> 5
                has("עשרים") && t.getOrNull(i + 1)?.norm?.startsWith("ל") == true -> 20
                else -> null
            }
            if (before != null && hour == null && t[i].norm.startsWith("ב") && i + 1 < t.size &&
                t[i + 1].norm.startsWith("ל")
            ) {
                val h = HebrewText.readNumber(listOf(Token("", t[i + 1].norm.substring(1))) +
                    t.subList(i + 2, t.size), 0, allowPrefix = false)
                if (h != null && h.first in 1..24) {
                    hour = (h.first - 1 + 24) % 24; minute = 60 - before
                    for (k in i..i + h.second) used += k
                    i += h.second + 1
                    continue
                }
            }

            // clock time: needs a ב/ל prefix ("בשמונה", "ב-8:30", "ל7") or "בשעה" before it
            if (hour == null) {
                val prevIsHourWord = i > 0 && t[i - 1].norm in setOf(n("בשעה"), n("לשעה"), n("שעה")) &&
                    (i - 1) !in used
                val w = t[i].norm
                val body = when {
                    prevIsHourWord -> w
                    w.length >= 2 && (w[0] == 'ב' || w[0] == 'ל') -> w.substring(1)
                    else -> null
                }
                if (body != null) {
                    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(body)
                    if (m != null) {
                        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                        if (h in 0..24 && mi in 0..59) {
                            hour = h % 24; minute = mi; used += i
                            if (prevIsHourWord) used += i - 1
                            i++; continue
                        }
                    }
                    val num = HebrewText.readNumber(listOf(Token("", body)) + t.subList(i + 1, t.size), 0,
                        allowPrefix = false)
                    // "ב-10 דקות" is a duration, not a clock time
                    val followedByUnit = num != null && t.getOrNull(i + num.second)?.let {
                        unitSeconds(it.norm) != null
                    } == true
                    if (num != null && num.first in 0..24 && !followedByUnit) {
                        hour = num.first % 24
                        for (k in i until i + num.second) used += k
                        if (prevIsHourWord) used += i - 1
                        i += num.second
                        // minutes
                        val mw = t.getOrNull(i)?.norm
                        when {
                            mw == n("וחצי") -> { minute = 30; used += i; i++ }
                            mw == n("ורבע") -> { minute = 15; used += i; i++ }
                            mw != null && mw.startsWith("ו") && mw.length > 1 -> {
                                val mm = HebrewText.readNumber(listOf(Token("", mw.substring(1))) +
                                    t.subList(i + 1, t.size), 0, allowPrefix = false)
                                if (mm != null && mm.first in 1..59) {
                                    minute = mm.first
                                    for (k in i until i + mm.second) used += k
                                    i += mm.second
                                    if (t.getOrNull(i)?.norm in MINUTE) { used += i; i++ }
                                }
                            }
                        }
                        continue
                    }
                }
            }

            // "בשעה 8": leave "בשעה" for the clock-time check on the next word
            val nextIsNumber = HebrewText.readNumber(t, i + 1) != null ||
                t.getOrNull(i + 1)?.norm?.let { Regex("^\\d{1,2}:\\d{2}$").matches(it) } == true
            if (t[i].norm in setOf(n("בשעה"), n("לשעה"), n("שעה")) && nextIsNumber) { i++; continue }

            // bare duration ("טיימר 10 דקות", "ל-5 דקות")
            if (duration == null) {
                val d = readDuration(t, i)
                if (d != null) {
                    duration = d.first
                    for (k in i until i + d.second) used += k
                    i += d.second
                    continue
                }
            }
            i++
        }

        val today = now.toLocalDate()
        var clock: LocalTime? = null
        val at: LocalDateTime? = when {
            relative != null -> now.plusSeconds(relative).truncatedTo(ChronoUnit.MINUTES)
            hour != null || dayOffset != null || weekday != null || period != null -> {
                var date: LocalDate = today.plusDays((dayOffset ?: 0).toLong())
                val explicitDay = dayOffset != null || weekday != null
                if (weekday != null) {
                    var ahead = (weekday.value - today.dayOfWeek.value + 7) % 7
                    if (ahead == 0) ahead = 7
                    date = today.plusDays(ahead.toLong() + if (dayOffset != null && dayOffset >= 7) 7 else 0)
                }
                val time: LocalTime = if (hour != null) {
                    var h = applyPeriod(hour, period)
                    if (period == null && hour in 1..12) {
                        h = when {
                            preferMorning -> hour % 12 + if (hour == 12) 12 else 0
                            explicitDay -> if (hour in 1..6) hour + 12 else hour
                            else -> {
                                // next occurrence of either hh or hh+12
                                val am = LocalTime.of(hour % 12, minute)
                                val pm = LocalTime.of(hour % 12 + 12, minute)
                                val nowT = now.toLocalTime()
                                when {
                                    am.isAfter(nowT) -> hour % 12
                                    pm.isAfter(nowT) -> hour % 12 + 12
                                    else -> hour % 12
                                }
                            }
                        }
                    }
                    LocalTime.of(h % 24, minute).also { clock = it }
                } else defaultTime ?: when (period) {
                    Period.MORNING -> LocalTime.of(8, 0)
                    Period.NOON -> LocalTime.of(12, 0)
                    Period.AFTERNOON -> LocalTime.of(16, 0)
                    Period.EVENING -> LocalTime.of(19, 0)
                    Period.NIGHT -> LocalTime.of(22, 0)
                    null -> LocalTime.of(9, 0)
                }
                var dt = LocalDateTime.of(date, time)
                if (!explicitDay && !dt.isAfter(now)) dt = dt.plusDays(1)
                if (explicitDay && dayOffset == 0 && !dt.isAfter(now) && hour == null) {
                    dt = now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
                }
                dt
            }
            else -> null
        }
        return TimeResult(at, duration ?: relative, clock, used)
    }
}
