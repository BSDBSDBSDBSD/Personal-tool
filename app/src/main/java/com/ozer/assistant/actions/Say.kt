package com.ozer.assistant.actions

import android.icu.text.DateFormat
import android.icu.util.ULocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date

/** Formats times, dates and durations as natural Hebrew. */
object Say {
    private val DAYS = mapOf(
        DayOfWeek.SUNDAY to "ראשון", DayOfWeek.MONDAY to "שני", DayOfWeek.TUESDAY to "שלישי",
        DayOfWeek.WEDNESDAY to "רביעי", DayOfWeek.THURSDAY to "חמישי", DayOfWeek.FRIDAY to "שישי",
        DayOfWeek.SATURDAY to "שבת",
    )
    private val MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר",
        "אוקטובר", "נובמבר", "דצמבר")

    fun clock(t: LocalTime) = "%d:%02d".format(t.hour, t.minute)

    fun dayName(d: DayOfWeek) = if (d == DayOfWeek.SATURDAY) "שבת" else "יום ${DAYS[d]}"

    fun gregorian(d: LocalDate) = "${d.dayOfMonth} ב${MONTHS[d.monthValue - 1]} ${d.year}"

    fun hebrewDate(date: Date = Date()): String = try {
        val loc = ULocale("he_IL@calendar=hebrew")
        DateFormat.getDateInstance(DateFormat.LONG, loc).format(date)
    } catch (e: Exception) { "" }

    fun whenText(at: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> "היום"
            1L -> "מחר"
            2L -> "מחרתיים"
            in 3L..6L -> "ב" + dayName(at.dayOfWeek)
            else -> "ב-" + gregorian(at.toLocalDate())
        }
        return "$day ב-${clock(at.toLocalTime())}"
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += if (h == 1L) "שעה" else if (h == 2L) "שעתיים" else "$h שעות"
        if (m > 0) parts += if (m == 1L) "דקה" else "$m דקות"
        if (s > 0) parts += if (s == 1L) "שנייה" else "$s שניות"
        return if (parts.isEmpty()) "0 שניות" else parts.joinToString(" ו")
    }
}
