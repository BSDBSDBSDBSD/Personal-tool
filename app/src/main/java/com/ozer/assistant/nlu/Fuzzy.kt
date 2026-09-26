package com.ozer.assistant.nlu

import kotlin.math.max
import kotlin.math.min

/** Fuzzy matching of spoken/typed names against app labels, song titles and contact names. */
object Fuzzy {
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun ratio(a: String, b: String): Double {
        val m = max(a.length, b.length)
        return if (m == 0) 0.0 else 1.0 - levenshtein(a, b).toDouble() / m
    }

    /**
     * Consonant skeleton shared by Hebrew and Latin spellings, so "טלגרם" and "Telegram"
     * both become "tlgrm", and "וואטסאפ" / "WhatsApp" both become "tsp".
     */
    fun skeleton(text: String): String {
        val s = Features.normalize(text).replace(" ", "")
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val next = s.getOrNull(i + 1)
            val piece = when (ch) {
                'א', 'ה', 'ו', 'י', 'ע' -> ""
                'ב' -> "b"; 'ג' -> "g"; 'ד' -> "d"; 'ז' -> "z"; 'ח' -> "k"; 'ט' -> "t"
                'כ' -> "k"; 'ל' -> "l"; 'מ' -> "m"; 'נ' -> "n"; 'ס' -> "s"; 'פ' -> "p"
                'צ' -> "z"; 'ק' -> "k"; 'ר' -> "r"; 'ש' -> "s"; 'ת' -> "t"
                'a', 'e', 'i', 'o', 'u', 'y', 'h', 'w' -> ""
                'c' -> if (next == 'e' || next == 'i' || next == 'y') "s" else "k"
                'q' -> "k"; 'f' -> "p"; 'v' -> "b"; 'j' -> "g"; 'x' -> "ks"
                else -> ch.toString()
            }
            for (p in piece) if (out.isEmpty() || out[out.length - 1] != p) out.append(p)
            i++
        }
        return out.toString()
    }

    /** Query variants: as written, and with a leading Hebrew prefix letter removed ("לווטסאפ" -> "ווטסאפ"). */
    fun variants(query: String): List<String> {
        val n = Features.normalize(query)
        if (n.isEmpty()) return emptyList()
        val res = linkedSetOf(n)
        val first = n.substringBefore(' ')
        val rest = n.substring(first.length)
        for ((k, c) in HebrewText.cores(first)) if (k > 0) res += c + rest
        return res.toList()
    }

    /** Similarity 0..1 between a user query and a candidate name. */
    fun score(query: String, candidate: String): Double {
        val c = Features.normalize(candidate)
        if (c.isEmpty()) return 0.0
        var best = 0.0
        val ck = skeleton(c)
        for (q in variants(query)) {
            if (q == c) return 1.0
            var s = ratio(q, c)
            if (q.length >= 2 && (" $c ").contains(" $q ")) s = max(s, 0.8 + 0.15 * q.length / c.length)
            else if (q.length >= 3 && c.contains(q)) s = max(s, 0.7 + 0.2 * q.length / c.length)
            if (c.length >= 3 && (" $q ").contains(" $c ")) s = max(s, 0.75 + 0.15 * c.length / q.length)
            val qk = skeleton(q)
            if (qk.length == 1 && qk == ck && q.length >= 3) s = max(s, 0.8)
            if (qk.length >= 2 && ck.length >= 2) {
                var k = ratio(qk, ck) * 0.92
                if (qk == ck) k = 0.9
                else if (qk.length >= 3 && ck.startsWith(qk)) k = max(k, 0.75)
                s = max(s, k)
            }
            best = max(best, s)
        }
        return best
    }

    fun <T> best(query: String, items: List<T>, threshold: Double, names: (T) -> List<String>): Pair<T, Double>? {
        var bestItem: T? = null
        var bestScore = 0.0
        for (item in items) {
            for (name in names(item)) {
                val s = score(query, name)
                if (s > bestScore) {
                    bestScore = s; bestItem = item
                }
            }
        }
        return if (bestItem != null && bestScore >= threshold) bestItem to bestScore else null
    }
}
