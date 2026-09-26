package com.ozer.assistant.speech

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline Hebrew speech recognition with a Whisper model the user copies to the phone.
 * The model is loaded when the mic is used and freed after a minute of idle time,
 * so it doesn't hold ~0.5 GB of RAM while the assistant just sits there.
 */
class WhisperEngine private constructor(private val appCtx: Context) {
    val modelFile = File(appCtx.filesDir, "whisper-model.bin")
    private val mutex = Mutex()
    private var ctxPtr = 0L
    private var unloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun hasModel() = WhisperLib.loaded && modelFile.exists() && modelFile.length() > 1_000_000

    fun modelSizeMb() = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0

    suspend fun transcribe(audio: FloatArray): String = mutex.withLock {
        unloadJob?.cancel()
        withContext(Dispatchers.Default) {
            if (ctxPtr == 0L) {
                ctxPtr = WhisperLib.initContext(modelFile.absolutePath)
                if (ctxPtr == 0L) throw IllegalStateException("לא הצלחתי לטעון את קובץ המודל. אולי הקובץ פגום?")
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val bytes = WhisperLib.transcribe(ctxPtr, audio, PROMPT, threads, true)
                ?: throw IllegalStateException("הזיהוי נכשל.")
            scheduleUnload()
            clean(String(bytes, Charsets.UTF_8))
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(60_000)
            mutex.withLock { unloadNow() }
        }
    }

    private fun unloadNow() {
        if (ctxPtr != 0L) {
            WhisperLib.freeContext(ctxPtr)
            ctxPtr = 0L
        }
    }

    suspend fun deleteModel() = mutex.withLock {
        unloadNow()
        modelFile.delete()
    }

    /**
     * Copies a model the user picked (from Downloads, SD card, ...) into app storage.
     * Checks it really is a whisper.cpp "ggml" file first.
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = appCtx.contentResolver
            val size = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
            if (size in 0..1_000_000) error("הקובץ קטן מדי — זה לא מודל Whisper.")
            if (size > 0 && appCtx.filesDir.usableSpace < size + 50_000_000) {
                error("אין מספיק מקום פנוי בטלפון (צריך בערך ${size / 1_000_000 + 50} מגה).")
            }
            val input = cr.openInputStream(uri) ?: error("לא הצלחתי לפתוח את הקובץ.")
            val tmp = File(appCtx.filesDir, "whisper-model.tmp")
            input.use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4 || !(magic contentEquals GGML_MAGIC)) {
                    error("זה לא קובץ מודל של whisper.cpp. צריך קובץ ggml-....bin.")
                }
                tmp.outputStream().use { out ->
                    out.write(magic)
                    val buf = ByteArray(1 shl 20)
                    var copied = 4L
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        copied += r
                        if (size > 0) onProgress(copied.toFloat() / size)
                    }
                }
            }
            mutex.withLock {
                unloadNow()
                modelFile.delete()
                if (!tmp.renameTo(modelFile)) error("לא הצלחתי לשמור את המודל.")
            }
            modelFile.length()
        }
    }

    companion object {
        /** whisper.cpp files start with the uint32 0x67676d6c ("ggml") in little-endian. */
        private val GGML_MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)

        /** Hints for the decoder: the kind of Hebrew commands it will hear. */
        private const val PROMPT =
            "פתח וואטסאפ. תנגן את השיר במוסיקולט. תזכיר לי מחר בשמונה. תרשום פתק. תתקשר לאמא. " +
                "תדליק פנס. טיימר לעשר דקות. תגביר ווליום. מה השעה?"

        /** Drops Whisper's non-speech tags like "[מוזיקה]" or "(צחוק)" and the prompt echo. */
        fun clean(s: String): String = s
            .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        @Volatile private var instance: WhisperEngine? = null
        fun get(ctx: Context): WhisperEngine = instance ?: synchronized(this) {
            instance ?: WhisperEngine(ctx.applicationContext).also { instance = it }
        }
    }
}
