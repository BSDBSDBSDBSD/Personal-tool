package com.ozer.assistant.nlu

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

data class Prediction(val intent: String, val confidence: Float, val runnerUp: String, val runnerUpConfidence: Float)

/** Softmax regression over hashed n-gram features; weights trained by model/train.py. */
class IntentClassifier(modelBytes: ByteArray, val labels: List<String>) {
    private val dim: Int
    private val classes: Int
    private val weights: FloatArray
    private val bias: FloatArray

    init {
        val buf = ByteBuffer.wrap(modelBytes).order(ByteOrder.LITTLE_ENDIAN)
        dim = buf.int
        classes = buf.int
        require(dim == Features.DIM) { "model DIM $dim != ${Features.DIM}" }
        require(classes == labels.size) { "model has $classes classes but ${labels.size} labels" }
        weights = FloatArray(dim * classes)
        buf.asFloatBuffer().get(weights)
        buf.position(8 + dim * classes * 4)
        bias = FloatArray(classes)
        buf.asFloatBuffer().get(bias)
    }

    fun predict(text: String): Prediction {
        val z = bias.copyOf()
        for ((idx, v) in Features.featurize(text)) {
            val row = idx * classes
            for (c in 0 until classes) z[c] += v * weights[row + c]
        }
        val max = z.max()
        var sum = 0.0
        val p = DoubleArray(classes) { exp((z[it] - max).toDouble()).also { e -> sum += e } }
        val order = p.indices.sortedByDescending { p[it] }
        return Prediction(
            labels[order[0]], (p[order[0]] / sum).toFloat(),
            labels[order[1]], (p[order[1]] / sum).toFloat(),
        )
    }

    companion object {
        fun load(model: InputStream, labels: InputStream): IntentClassifier =
            IntentClassifier(
                model.use { it.readBytes() },
                labels.bufferedReader(Charsets.UTF_8).use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() } },
            )
    }
}
