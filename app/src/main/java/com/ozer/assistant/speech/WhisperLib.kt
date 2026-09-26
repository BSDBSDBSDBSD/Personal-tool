package com.ozer.assistant.speech

/** Native bridge to whisper.cpp (app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    val loaded: Boolean = try {
        System.loadLibrary("ozer_whisper"); true
    } catch (e: Throwable) { false }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun transcribe(ptr: Long, audio: FloatArray, prompt: String?, threads: Int, shortContext: Boolean): ByteArray?
    external fun systemInfo(): String
}
