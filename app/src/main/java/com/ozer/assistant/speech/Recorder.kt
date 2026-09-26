package com.ozer.assistant.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records 16 kHz mono audio (what Whisper expects) until the speaker stops talking.
 * A simple energy detector: learns the room's noise level in the first moments,
 * then ends after ~1.2 s of quiet following speech.
 */
class Recorder {
    @Volatile private var stopRequested = false

    fun requestStop() { stopRequested = true }

    /** Blocking; call from a background thread. Returns null if nobody spoke. */
    @SuppressLint("MissingPermission")
    fun record(onLevel: (Float) -> Unit = {}): FloatArray? {
        stopRequested = false
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("לא הצלחתי לפתוח את המיקרופון.")
        }
        val chunk = ShortArray(rate / 10) // 100 ms
        val out = ShortArrayBuilder(rate * 12)
        var noise = -1.0
        var spoke = false
        var quietMs = 0
        var totalMs = 0
        try {
            rec.startRecording()
            while (!stopRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                out.add(chunk, n)
                totalMs += n * 1000 / rate
                var sum = 0.0
                for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
                val rms = sqrt(sum / n)
                onLevel((rms / 3000.0).coerceIn(0.0, 1.0).toFloat())

                if (totalMs <= 300) {
                    noise = if (noise < 0) rms else (noise + rms) / 2
                    continue
                }
                val threshold = max(noise * 2.5, 350.0)
                if (rms > threshold) {
                    spoke = true
                    quietMs = 0
                } else {
                    quietMs += n * 1000 / rate
                    if (!spoke) noise = noise * 0.95 + rms * 0.05
                }
                if (spoke && quietMs >= 1200) break
                if (!spoke && totalMs >= 6000) break
                if (totalMs >= 12_000) break
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (!spoke && !stopRequested) return null
        val pcm = out.toArray()
        if (pcm.size < rate / 2) return null
        // Whisper wants at least ~1 s; pad short commands with silence.
        val size = max(pcm.size, rate + rate / 2)
        return FloatArray(size) { i -> if (i < pcm.size) pcm[i] / 32768f else 0f }
    }

    private class ShortArrayBuilder(initial: Int) {
        private var data = ShortArray(initial)
        private var size = 0
        fun add(src: ShortArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(max(data.size * 2, size + n))
            System.arraycopy(src, 0, data, size, n)
            size += n
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }
}
