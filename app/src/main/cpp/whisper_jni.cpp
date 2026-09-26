// JNI bridge between the app and whisper.cpp.
#include <jni.h>
#include <string>
#include <algorithm>
#include "whisper.h"

#define JNI_FN(name) Java_com_ozer_assistant_speech_WhisperLib_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(initContext)(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL JNI_FN(freeContext)(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Returns the transcription as UTF-8 bytes (decoded on the Kotlin side, since tokens
// can split multi-byte characters and JNI's NewStringUTF rejects invalid sequences).
JNIEXPORT jbyteArray JNICALL JNI_FN(transcribe)(JNIEnv *env, jobject, jlong ptr, jfloatArray audio,
                                                jstring prompt, jint threads, jboolean shortContext) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const char *promptC = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(1, (int) threads);
    p.language = "he";
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.max_tokens = 64;
    p.initial_prompt = promptC;
    if (shortContext) {
        // Commands are a few seconds long: encode only the audio we have instead of a
        // full 30 s window (50 encoder frames per second, plus a margin). Much faster.
        const int seconds = (int) (n / 16000) + 1;
        p.audio_ctx = std::min(1500, seconds * 50 + 64);
    }

    const int rc = whisper_full(ctx, p, samples, n);

    if (promptC != nullptr) env->ReleaseStringUTFChars(prompt, promptC);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) out += whisper_full_get_segment_text(ctx, i);

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT jstring JNICALL JNI_FN(systemInfo)(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
