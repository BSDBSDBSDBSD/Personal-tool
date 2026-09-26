package com.ozer.assistant.nlu

/** A word as the user wrote it ([display]) plus its normalized form ([norm]) used for matching. */
data class Token(val display: String, val norm: String)

object HebrewText {
    /** One-letter prefixes that attach to Hebrew words: ב ל ו ה מ ש כ */
    const val PREFIX_LETTERS = "בלוהמשכ"

    private val APOSTROPHES = setOf('\'', '"', '`', '’', '‘', '“', '”', '׳', '״')

    fun normWord(word: String): String {
        val sb = StringBuilder()
        for (raw in word.lowercase()) {
            if (raw in APOSTROPHES) continue
            val c = raw.code
            if (c in 0x0591..0x05C7) continue
            val ch = Features.finalToRegular(raw)
            if (ch in 'א'..'ת' || ch in 'a'..'z' || ch in '0'..'9' || ch == ':') sb.append(ch)
        }
        return sb.toString().trim(':')
    }

    /** Splits on whitespace, dashes and punctuation; "ב-8" becomes a single token "ב8". */
    fun tokenize(text: String): List<Token> {
        val prepared = text.replace(Regex("(\\d)\\.(\\d\\d)"), "$1:$2")
        val raw = prepared.split(Regex("[\\s\\-־,;!?()\\[\\]/]+")).filter { it.isNotBlank() }
        val out = ArrayList<Token>()
        var i = 0
        while (i < raw.size) {
            var disp = raw[i].trim('.', '…')
            var norm = normWord(disp)
            // A bare prefix letter ("ב" in "ב-8") glues onto the next word.
            if (norm.length == 1 && norm[0] in "בל" && i + 1 < raw.size) {
                disp = disp + "-" + raw[i + 1]
                norm += normWord(raw[i + 1])
                i++
            }
            if (norm.isNotEmpty()) out += Token(disp, norm)
            i++
        }
        return out
    }

    /** The word plus variants with up to two leading prefix letters removed: "ולמחר" -> [ולמחר, למחר, מחר]. */
    fun cores(norm: String): List<Pair<Int, String>> {
        val res = mutableListOf(0 to norm)
        var s = norm
        for (k in 1..2) {
            if (s.length > 2 && s[0] in PREFIX_LETTERS) {
                s = s.substring(1)
                res += k to s
            } else break
        }
        return res
    }

    fun norm(text: String): String = Features.normalize(text)

    // ---------- numbers ----------
    private val UNITS: Map<String, Int> = mapOf(
        "אפס" to 0,
        "אחת" to 1, "אחד" to 1,
        "שתיים" to 2, "שתים" to 2, "שניים" to 2, "שני" to 2, "שתי" to 2,
        "שלוש" to 3, "שלושה" to 3, "שלושת" to 3,
        "ארבע" to 4, "ארבעה" to 4, "ארבעת" to 4,
        "חמש" to 5, "חמישה" to 5, "חמשת" to 5,
        "שש" to 6, "שישה" to 6, "ששת" to 6,
        "שבע" to 7, "שבעה" to 7, "שבעת" to 7,
        "שמונה" to 8, "שמונת" to 8,
        "תשע" to 9, "תשעה" to 9, "תשעת" to 9,
        "עשר" to 10, "עשרה" to 10, "עשרת" to 10,
    ).mapKeys { normWord(it.key) }

    private val TENS: Map<String, Int> = mapOf(
        "עשרים" to 20, "שלושים" to 30, "ארבעים" to 40, "חמישים" to 50,
        "שישים" to 60, "שבעים" to 70, "שמונים" to 80, "תשעים" to 90, "מאה" to 100,
    ).mapKeys { normWord(it.key) }

    private val TEEN = setOf(normWord("עשרה"), normWord("עשר"))
    private val AND_ONE = setOf(normWord("ואחת"), normWord("ואחד"))

    /** Value of a single number word or digit string (normalized, prefix already removed). */
    fun simpleNumber(norm: String): Int? {
        if (norm.isNotEmpty() && norm.all { it in '0'..'9' }) return norm.toIntOrNull()
        return UNITS[norm] ?: TENS[norm]
    }

    /**
     * Reads a number starting at tokens[start] (after stripping a prefix on the first word only
     * if [allowPrefix]). Handles "אחת עשרה", "עשרים וחמש", "25". Returns value and token count.
     */
    fun readNumber(tokens: List<Token>, start: Int, allowPrefix: Boolean = true): Pair<Int, Int>? {
        if (start >= tokens.size) return null
        val first = tokens[start].norm
        val candidates = if (allowPrefix) cores(first).map { it.second } else listOf(first)
        for (c in candidates) {
            val v = simpleNumber(c) ?: continue
            var value = v
            var used = 1
            val next = tokens.getOrNull(start + 1)?.norm
            if (v in 1..9 && next != null && next in TEEN && UNITS.containsKey(c)) {
                value = v + 10; used = 2
            } else if (v in 20..90 && v % 10 == 0 && next != null && next.startsWith("ו")) {
                val u = simpleNumber(next.substring(1))
                if (u != null && u in 1..9) {
                    value = v + u; used = 2
                } else if (next in AND_ONE) {
                    value = v + 1; used = 2
                }
            }
            return value to used
        }
        return null
    }

    /** First number anywhere in the text; "חצי" = 50, "מקסימום" = 100, "מינימום" = 0. */
    fun findNumber(tokens: List<Token>): Int? {
        for (i in tokens.indices) {
            for ((_, c) in cores(tokens[i].norm)) {
                when (c) {
                    normWord("חצי") -> return 50
                    normWord("מקסימום"), normWord("המקסימום"), "max" -> return 100
                    normWord("מינימום"), normWord("המינימום"), "min" -> return 0
                }
            }
            val digits = tokens[i].norm.filter { it in '0'..'9' }
            if (digits.isNotEmpty()) return digits.toIntOrNull()
            readNumber(tokens, i)?.let { return it.first }
        }
        return null
    }
}
