package com.ozer.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val text: String, val created: Long)

data class Reminder(val id: Long, val text: String, val at: Long, val fired: Boolean = false)

/** Tiny JSON-file storage for notes, reminders and settings. Everything stays on the phone. */
class Store private constructor(private val dir: File) {
    private val notesFile = File(dir, "notes.json")
    private val remindersFile = File(dir, "reminders.json")
    private val prefsFile = File(dir, "prefs.json")

    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<List<Note>> = _notes

    private val _reminders = MutableStateFlow(loadReminders())
    val reminders: StateFlow<List<Reminder>> = _reminders

    private val _speak = MutableStateFlow(loadPrefs().optBoolean("speak", true))
    val speak: StateFlow<Boolean> = _speak

    private val _showDebug = MutableStateFlow(loadPrefs().optBoolean("debug", false))
    val showDebug: StateFlow<Boolean> = _showDebug

    private fun newId() = System.currentTimeMillis() * 1000 + (0..999).random()

    // ---------- notes ----------
    @Synchronized
    fun addNote(text: String): Note {
        val n = Note(newId(), text, System.currentTimeMillis())
        _notes.value = listOf(n) + _notes.value
        saveNotes()
        return n
    }

    @Synchronized
    fun deleteNote(id: Long) {
        _notes.value = _notes.value.filterNot { it.id == id }
        saveNotes()
    }

    private fun loadNotes(): List<Note> = readArray(notesFile).map {
        Note(it.getLong("id"), it.getString("text"), it.getLong("created"))
    }

    private fun saveNotes() = writeArray(notesFile, _notes.value.map {
        JSONObject().put("id", it.id).put("text", it.text).put("created", it.created)
    })

    // ---------- reminders ----------
    @Synchronized
    fun addReminder(text: String, at: Long): Reminder {
        val r = Reminder(newId(), text, at)
        _reminders.value = (_reminders.value + r).sortedBy { it.at }
        saveReminders()
        return r
    }

    @Synchronized
    fun markFired(id: Long) {
        _reminders.value = _reminders.value.map { if (it.id == id) it.copy(fired = true) else it }
        saveReminders()
    }

    @Synchronized
    fun deleteReminder(id: Long) {
        _reminders.value = _reminders.value.filterNot { it.id == id }
        saveReminders()
    }

    fun reminder(id: Long) = _reminders.value.firstOrNull { it.id == id }

    private fun loadReminders(): List<Reminder> = readArray(remindersFile).map {
        Reminder(it.getLong("id"), it.getString("text"), it.getLong("at"), it.optBoolean("fired"))
    }

    private fun saveReminders() = writeArray(remindersFile, _reminders.value.map {
        JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("fired", it.fired)
    })

    // ---------- prefs ----------
    fun setSpeak(v: Boolean) { _speak.value = v; savePrefs() }
    fun setShowDebug(v: Boolean) { _showDebug.value = v; savePrefs() }

    private fun loadPrefs(): JSONObject = try {
        if (prefsFile.exists()) JSONObject(prefsFile.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    @Synchronized
    private fun savePrefs() = atomicWrite(prefsFile,
        JSONObject().put("speak", _speak.value).put("debug", _showDebug.value).toString())

    // ---------- io ----------
    private fun readArray(f: File): List<JSONObject> = try {
        if (!f.exists()) emptyList()
        else JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    } catch (e: Exception) { emptyList() }

    private fun writeArray(f: File, items: List<JSONObject>) =
        atomicWrite(f, JSONArray().also { a -> items.forEach { a.put(it) } }.toString())

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext.filesDir).also { instance = it }
        }
    }
}
