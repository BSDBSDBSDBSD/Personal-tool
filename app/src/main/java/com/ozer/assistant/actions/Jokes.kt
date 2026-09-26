package com.ozer.assistant.actions

import android.content.Context

/** A bag of jokes and riddles (assets/jokes.txt); goes through all of them before repeating. */
object Jokes {
    private var all: List<Pair<String, String>>? = null
    private val bag = ArrayDeque<Int>()
    private val riddleBag = ArrayDeque<Int>()

    private fun load(ctx: Context): List<Pair<String, String>> = all ?: ctx.assets.open("jokes.txt")
        .bufferedReader(Charsets.UTF_8).readLines()
        .filter { it.contains('|') }
        .map { it.substringBefore('|').trim() to it.substringAfter('|').trim() }
        .also { all = it }

    @Synchronized
    fun next(ctx: Context, riddle: Boolean): String {
        val jokes = load(ctx)
        val q = if (riddle) riddleBag else bag
        if (q.isEmpty()) {
            q.addAll(jokes.indices.filter { !riddle || jokes[it].first.startsWith("חידה") }.shuffled())
        }
        val (setup, punch) = jokes[q.removeFirst()]
        return "$setup\n\n$punch"
    }
}
