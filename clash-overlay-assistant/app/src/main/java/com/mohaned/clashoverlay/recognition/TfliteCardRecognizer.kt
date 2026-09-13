package com.mohaned.clashoverlay.recognition

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Real on-device classifier.
 *
 * No model means no guesses:
 * the app never fabricates card identities.
 */
class TfliteCardRecognizer(context: Context) : AutoCloseable {

    private val interpreter: Interpreter?
    private val labels: List<String>

    val availability: Availability

    enum class Availability {
        READY,
        MODEL_UNAVAILABLE,
        LABELS_UNAVAILABLE,
        INVALID_MODEL
    }

    init {
        val model = runCatching {
            context.assets.openFd("models/card_classifier.tflite")
        }.getOrNull()

        interpreter = runCatching {
            model?.let {
                FileInputStream(it.fileDescriptor).channel
                    .map(
                        FileChannel.MapMode.READ_ONLY,
                        it.startOffset,
                        it.declaredLength
                    )
                    .let { mapped ->
                        Interpreter(
                            mapped,
                            Interpreter.Options().apply {
                                setNumThreads(2)
                                setUseXNNPACK(true)
                            }
                        )
                    }
            }
        }.getOrNull()

        labels = runCatching {
            context.assets
                .open("models/labels.txt")
                .bufferedReader()
                .readLines()
                .map(String::trim)
                .filter(String::isNotEmpty)
        }.getOrDefault(emptyList())

        availability = when {
            interpreter == null ->
                if (model == null) {
                    Availability.MODEL_UNAVAILABLE
                } else {
                    Availability.INVALID_MODEL
                }

            labels.isEmpty() ->
                Availability.LABELS_UNAVAILABLE

            else ->
                Availability.READY
        }
    }

    fun isAvailable(): Boolean {
        return availability == Availability.READY
    }

    fun classify(bitmap: Bitmap): Pair<String, Float>? {

        val tflite = interpreter ?: return null

        if (!isAvailable()) return null

        val input = tflite.getInputTensor(0)
        val shape = input.shape()

        if (
            shape.size != 4 ||
            shape[0] != 1 ||
            shape[3] != 3
        ) {
            return null
        }

        val h = shape[1]
        val w = shape[2]

        if (h <= 0 || w <= 0) {
            return null
        }

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            w,
            h,
            true
        )

        return try {

            val inputBuffer = makeInput(
                resized,
                input
            )

            val outputTensor =
                tflite.getOutputTensor(0)

            val classes =
                outputTensor.shape().lastOrNull()
                    ?: return null

            if (classes != labels.size) {
                return null
            }

            val probs = readOutput(
                tflite,
                outputTensor,
                classes,
                inputBuffer
            )

            val best =
                probs.indices.maxByOrNull {
                    probs[it]
                } ?: return null

            val confidence =
                probs[best].coerceIn(0f, 1f)

            labels.getOrNull(best)?.let {
                it to confidence
            }

        } finally {
            resized.recycle()
        }
    }

    private fun readOutput(
        tflite: Interpreter,
        tensor: org.tensorflow.lite.Tensor,
        classes: Int,
        input: ByteBuffer
    ): FloatArray {

        return when (tensor.dataType()) {

            DataType.FLOAT32 -> {

                val out =
                    Array(1) {
                        FloatArray(classes)
                    }

                tflite.runForMultipleInputsOutputs(
                    arrayOf(input),
                    mapOf(0 to out)
                )

                normalizeScores(out[0])
            }

            DataType.UINT8,
            DataType.INT8 -> {

                val out =
                    Array(1) {
                        ByteArray(classes)
                    }

                tflite.runForMultipleInputsOutputs(
                    arrayOf(input),
                    mapOf(0 to out)
                )

                val q =
                    tensor.quantizationParams()

                normalizeScores(
                    out[0]
                        .map {

                            val raw =
                                if (
                                    tensor.dataType() ==
                                    DataType.UINT8
                                ) {
                                    it.toInt() and 0xFF
                                } else {
                                    it.toInt()
                                }

                            (raw - q.zeroPoint) * q.scale
                        }
                        .toFloatArray()
                )
            }

            else -> {
                FloatArray(classes)
            }
        }
    }

    /**
     * Converts model outputs into probabilities.
     *
     * IMPORTANT:
     * Use Float throughout this function.
     * Mixing Double and Float was causing the
     * GitHub Actions compilation error.
     */
    private fun normalizeScores(
        values: FloatArray
    ): FloatArray {

        if (values.all { it in 0f..1f }) {
            return values
        }

        val maxValue =
            values.maxOrNull() ?: return values

        val exps =
            FloatArray(values.size)

        // FIX:
        // Float, not Double.
        var sum = 0f

        values.forEachIndexed { i, v ->

            val e =
                kotlin.math.exp(
                    (v - maxValue).toDouble()
                ).toFloat()

            exps[i] = e

            // Float + Float
            sum += e
        }

        // FIX:
        // Compare Float with Float.
        if (sum <= 0f) {
            return values
        }

        for (i in exps.indices) {

            exps[i] =
                (exps[i] / sum)
                    .coerceIn(0f, 1f)
        }

        return exps
    }

    private fun makeInput(
        bitmap: Bitmap,
        tensor: org.tensorflow.lite.Tensor
    ): ByteBuffer {

        val type =
            tensor.dataType()

        val bytes =
            if (type == DataType.FLOAT32) {
                4
            } else {
                1
            }

        val buffer =
            ByteBuffer.allocateDirect(
                bitmap.width *
                    bitmap.height *
                    3 *
                    bytes
            ).order(
                ByteOrder.nativeOrder()
            )

        val pixels =
            IntArray(
                bitmap.width *
                    bitmap.height
            )

        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        val q =
            tensor.quantizationParams()

        if (
            type != DataType.FLOAT32 &&
            q.scale <= 0f
        ) {
            error(
                "Invalid model quantization scale"
            )
        }

        pixels.forEach { pixel ->

            val channels =
                intArrayOf(
                    pixel shr 16 and 255,
                    pixel shr 8 and 255,
                    pixel and 255
                )

            channels.forEach { channel ->

                if (type == DataType.FLOAT32) {

                    buffer.putFloat(
                        channel / 255f
                    )

                } else {

                    val quantized =
                        (
                            channel / 255f /
                                q.scale +
                                q.zeroPoint
                            ).roundToInt()

                    if (
                        type ==
                        DataType.UINT8
                    ) {

                        buffer.put(
                            quantized
                                .coerceIn(0, 255)
                                .toByte()
                        )

                    } else {

                        buffer.put(
                            quantized
                                .coerceIn(-128, 127)
                                .toByte()
                        )
                    }
                }
            }
        }

        buffer.rewind()

        return buffer
    }

    override fun close() {
        interpreter?.close()
    }
}
