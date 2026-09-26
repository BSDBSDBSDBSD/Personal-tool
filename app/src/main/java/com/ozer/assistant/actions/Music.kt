package com.ozer.assistant.actions

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.ozer.assistant.nlu.Features
import com.ozer.assistant.nlu.Fuzzy

data class Song(val id: Long, val title: String, val artist: String, val album: String, val fileName: String)

/** Finds songs stored on the phone and plays them in Musicolet (or any music player). */
object Music {
    const val MUSICOLET = "in.krosbits.musicolet"

    fun songs(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Song(
                    c.getLong(0), c.getString(1) ?: "", c.getString(2)?.takeIf { it != "<unknown>" } ?: "",
                    c.getString(3) ?: "", (c.getString(4) ?: "").substringBeforeLast('.'),
                )
            }
        }
        return out
    }

    /** Best matching songs: a single song for a title, or all songs of an artist. */
    fun search(all: List<Song>, title: String, artist: String?): List<Song> {
        if (all.isEmpty()) return emptyList()
        val full = listOfNotNull(title.ifBlank { null }, artist).joinToString(" של ")
        fun titleScore(s: Song, q: String) = maxOf(Fuzzy.score(q, s.title), Fuzzy.score(q, s.fileName) * 0.95)
        fun artistScore(s: Song, q: String) = Fuzzy.score(q, s.artist)

        if (title.isBlank() && artist != null) {
            val byArtist = all.map { it to artistScore(it, artist) }.filter { it.second >= 0.7 }
            if (byArtist.isNotEmpty()) {
                val top = byArtist.maxOf { it.second }
                return byArtist.filter { it.second >= top - 0.05 }.map { it.first }.shuffled()
            }
            // maybe it was a title after all ("משהו של ...")
            return all.map { it to titleScore(it, artist) }.filter { it.second >= 0.75 }
                .sortedByDescending { it.second }.take(1).map { it.first }
        }
        val scored = all.map { s ->
            var sc = maxOf(titleScore(s, title), titleScore(s, full))
            if (artist != null) {
                val a = artistScore(s, artist)
                sc = if (a >= 0.7) sc + 0.1 * a else maxOf(sc - 0.05, titleScore(s, full))
            }
            // "title artist" said without "של"
            val combo = Fuzzy.score(Features.normalize(title), "${s.title} ${s.artist}")
            s to maxOf(sc, combo * 0.95)
        }
        val best = scored.maxByOrNull { it.second } ?: return emptyList()
        return if (best.second >= 0.72) listOf(best.first) else emptyList()
    }

    fun uri(song: Song) = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

    /** Returns the player name used, or null if nothing could play it. */
    fun play(ctx: Context, song: Song): String? {
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri(song), "audio/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Apps.isInstalled(ctx, MUSICOLET) && Apps.tryStart(ctx, Intent(base).setPackage(MUSICOLET))) {
            return "מוסיקולט"
        }
        return if (Apps.tryStart(ctx, base)) "נגן המוזיקה" else null
    }

    /** Asks the music app to search and play (used when the song isn't found in the phone's library). */
    fun playFromSearch(ctx: Context, query: String): Boolean {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Apps.isInstalled(ctx, MUSICOLET) && Apps.tryStart(ctx, Intent(i).setPackage(MUSICOLET))) return true
        return false
    }

    fun mediaKey(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun isPlaying(ctx: Context) = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
}
