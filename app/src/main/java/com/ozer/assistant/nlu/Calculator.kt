package com.ozer.assistant.nlu

import kotlin.math.pow
import kotlin.math.sqrt

/** Spoken/typed Hebrew arithmetic: "כמה זה 15 כפול 7", "שלוש ועוד ארבע", "20 אחוז מ-350", "שורש של 81". */
object Calculator {
    data class Result(val expression: String, val value: Double)

    private fun n(s: String) = HebrewText.normWord(s)

    private val OPS = mapOf(
        n("ועוד") to "+", n("פלוס") to "+", n("עוד") to "+",
        n("פחות") to "-", n("מינוס") to "-",
        n("כפול") to "*", n("כפל") to "*", n("פי") to "*",
        n("חלקי") to "/", n("לחלק") to "/", n("חלק") to "/", n("מחולק") to "/",
        n("בחזקת") to "^",
    )
    private val SQUARE = setOf(n("בריבוע"))
    private val ROOT = setOf(n("שורש"), n("השורש"))
    private val PERCENT = setOf(n("אחוז"), n("אחוזים"))
    private val OF = setOf(n("מ"), n("מתוך"), n("של"))

    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Op(val op: String) : Tok()
    }

    /** Returns null when the text has no arithmetic in it. */
    fun evaluate(text: String): Result? {
        val raw = Regex("\\d+(?:\\.\\d+)?|[+\\-*/x×÷^()%]|[\\p{L}'\"׳״]+").findAll(text.lowercase()).map { it.value }.toList()
        val out = ArrayList<Tok>()
        var i = 0
        var lastWordNumber = false
        fun pushNum(v: Double) {
            // "5 6" (two numbers in a row) is not an expression; keep the latest
            if (out.lastOrNull() is Tok.Num) out.removeAt(out.size - 1)
            out += Tok.Num(v)
        }
        while (i < raw.size) {
            val r = raw[i]
            when {
                r[0].isDigit() -> { pushNum(r.toDouble()); lastWordNumber = false }
                r == "+" -> out += Tok.Op("+")
                r == "-" -> out += Tok.Op("-")
                r == "*" || r == "x" || r == "×" -> out += Tok.Op("*")
                r == "/" || r == "÷" -> out += Tok.Op("/")
                r == "^" -> out += Tok.Op("^")
                r == "(" || r == ")" -> out += Tok.Op(r)
                r == "%" -> applyPercent(out, raw, i).also { i = it }
                else -> {
                    val w = n(r)
                    val cores = HebrewText.cores(w).map { it.second }
                    when {
                        w in OPS -> { out += Tok.Op(OPS.getValue(w)); lastWordNumber = false }
                        w in SQUARE -> { out += Tok.Op("^"); out += Tok.Num(2.0) }
                        cores.any { it in ROOT } -> out += Tok.Op("√")
                        w in PERCENT -> i = applyPercent(out, raw, i)
                        // "עשרים וחמש", "אחת עשרה"
                        lastWordNumber && w.startsWith("ו") && HebrewText.simpleNumber(w.substring(1)) in 1..9 &&
                            (out.last() as? Tok.Num)?.v?.let { it >= 20 && it % 10 == 0.0 } == true -> {
                            val prev = (out.removeAt(out.size - 1) as Tok.Num).v
                            out += Tok.Num(prev + HebrewText.simpleNumber(w.substring(1))!!)
                        }
                        lastWordNumber && (w == n("עשרה") || w == n("עשר")) &&
                            (out.last() as? Tok.Num)?.v?.let { it in 1.0..9.0 } == true -> {
                            val prev = (out.removeAt(out.size - 1) as Tok.Num).v
                            out += Tok.Num(prev + 10)
                        }
                        else -> {
                            val v = cores.firstNotNullOfOrNull { HebrewText.simpleNumber(it) }
                            if (v != null) { pushNum(v.toDouble()); lastWordNumber = true }
                        }
                    }
                }
            }
            i++
        }
        // trim dangling operators ("כמה זה 5 ועוד" -> "5")
        while (out.isNotEmpty() && out.last() is Tok.Op && (out.last() as Tok.Op).op !in setOf(")")) out.removeAt(out.size - 1)
        while (out.isNotEmpty() && out.first() is Tok.Op && (out.first() as Tok.Op).op !in setOf("(", "√", "-")) out.removeAt(0)
        if (out.count { it is Tok.Num } == 0 || out.none { it is Tok.Op }) return null
        val value = eval(out) ?: return null
        return Result(pretty(out), value)
    }

    /** "20 אחוז מ-350" -> 0.2 * 350. Returns the index of the last raw token consumed. */
    private fun applyPercent(out: ArrayList<Tok>, raw: List<String>, i: Int): Int {
        val last = out.lastOrNull() as? Tok.Num ?: return i
        out[out.size - 1] = Tok.Num(last.v / 100)
        var j = i + 1
        if (j < raw.size && n(raw[j]) in OF) {
            j++
            if (j < raw.size && raw[j] == "-") j++
            out += Tok.Op("*")
            return j - 1
        }
        // "מ-350" glued: next raw is a word starting with מ followed by a number
        return i
    }

    private fun prec(op: String) = when (op) { "+", "-" -> 1; "*", "/" -> 2; "^" -> 3; "√", "neg" -> 4; else -> 0 }

    private fun eval(tokens: List<Tok>): Double? = try {
        val values = ArrayDeque<Double>()
        val ops = ArrayDeque<String>()
        fun apply() {
            val op = ops.removeLast()
            when (op) {
                "√" -> values.addLast(sqrt(values.removeLast()))
                "neg" -> values.addLast(-values.removeLast())
                else -> {
                    val b = values.removeLast(); val a = values.removeLast()
                    values.addLast(when (op) {
                        "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "^" -> a.pow(b)
                        else -> error(op)
                    })
                }
            }
        }
        var expectOperand = true
        for (t in tokens) {
            when (t) {
                is Tok.Num -> { values.addLast(t.v); expectOperand = false }
                is Tok.Op -> when {
                    t.op == "(" -> ops.addLast("(")
                    t.op == ")" -> { while (ops.last() != "(") apply(); ops.removeLast() }
                    t.op == "√" -> ops.addLast("√")
                    t.op == "-" && expectOperand -> ops.addLast("neg")
                    else -> {
                        while (ops.isNotEmpty() && ops.last() != "(" &&
                            (prec(ops.last()) > prec(t.op) || (prec(ops.last()) == prec(t.op) && t.op != "^"))
                        ) apply()
                        ops.addLast(t.op)
                        expectOperand = true
                    }
                }
            }
        }
        while (ops.isNotEmpty()) apply()
        if (values.size == 1) values.first() else null
    } catch (e: Exception) { null }

    private fun pretty(tokens: List<Tok>) = tokens.joinToString(" ") {
        when (it) {
            is Tok.Num -> format(it.v)
            is Tok.Op -> when (it.op) { "*" -> "×"; "/" -> "÷"; else -> it.op }
        }
    }.replace("( ", "(").replace(" )", ")").replace("√ ", "√")

    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "לא מוגדר"
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        return "%.6f".format(v).trimEnd('0').trimEnd('.')
    }
}
