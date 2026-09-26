package com.ozer.assistant.nlu

import kotlin.math.sqrt

/**
 * Text normalization + feature hashing. MUST stay identical to model/features.py,
 * which is what the weights in assets/nlu_model.bin were trained with.
 */
object Features {
    const val DIM = 1 shl 14

    private val DROP = setOf('\'', '"', '`', '’', '‘', '“', '”', '׳', '״')

    fun finalToRegular(ch: Char): Char = when (ch) {
        'ך' -> 'כ'; 'ם' -> 'מ'; 'ן' -> 'נ'; 'ף' -> 'פ'; 'ץ' -> 'צ'
        else -> ch
    }

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (raw in text.lowercase()) {
            if (raw in DROP) continue
            val c = raw.code
            if (c in 0x0591..0x05C7 && c != 0x05BE) continue
            val ch = finalToRegular(raw)
            if (ch in 'א'..'ת' || ch in 'a'..'z' || ch in '0'..'9') sb.append(ch) else sb.append(' ')
        }
        return sb.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun digitsToHash(tok: String): String {
        val sb = StringBuilder()
        var prevDigit = false
        for (ch in tok) {
            if (ch in '0'..'9') {
                if (!prevDigit) sb.append('#')
                prevDigit = true
            } else {
                sb.append(ch)
                prevDigit = false
            }
        }
        return sb.toString()
    }

    fun tokens(text: String): List<String> {
        val n = normalize(text)
        return if (n.isEmpty()) emptyList() else n.split(' ').map(::digitsToHash)
    }

    fun fnv1a(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    fun featureStrings(text: String): List<String> {
        val feats = ArrayList<String>()
        var prev = "^"
        for (t in tokens(text)) {
            feats += "w|$t"
            feats += "b|$prev|$t"
            prev = t
            val padded = "<$t>"
            for (n in 2..4) {
                for (i in 0..padded.length - n) feats += "c|" + padded.substring(i, i + n)
            }
        }
        return feats
    }

    /** Sparse L2-normalized feature vector as index -> value. */
    fun featurize(text: String): Map<Int, Float> {
        val counts = HashMap<Int, Double>()
        for (f in featureStrings(text)) {
            val idx = ((fnv1a(f).toLong() and 0xFFFFFFFFL) % DIM).toInt()
            counts[idx] = (counts[idx] ?: 0.0) + 1.0
        }
        val norm = sqrt(counts.values.sumOf { it * it })
        if (norm == 0.0) return emptyMap()
        return counts.mapValues { (it.value / norm).toFloat() }
    }
}
