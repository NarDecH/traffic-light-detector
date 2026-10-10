package com.example.trafficlightdetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.Tensor.QuantizationParams
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Thin, self-contained wrapper around a TFLite **object-detection** model that
 * outputs the standard 4 tensors of `TFLite_Detection_PostProcess`:
 *
 * ```
 * locations  [1, N, 4]   xmin/ymin/xmax/ymax, normalised 0..1
 * classes    [1, N]      class index -> labels.txt
 * scores     [1, N]      confidence 0..1 (logits are detected and sigmoid-ed)
 * num        [1]         how many of the N slots are valid (optional)
 * ```
 *
 * Output tensors are resolved by shape, then by name, then by the well-known
 * order above — TFLite strips layer names from exported graphs, so relying on
 * names alone is fragile. Float and quantized (INT8/UINT8) graphs are both
 * supported, and the input scaling is auto-detected from the input tensor's
 * quantization parameters.
 *
 * Not thread-safe: use one instance per [androidx.camera.core.ImageAnalysis]
 * analyzer (the analyzer already runs on a single-threaded executor).
 */
class TrafficLightDetector private constructor(
    private val interpreter: Interpreter,
    private val labels: TrafficLightLabels,
    private val config: Config,
    private val acceleration: String,
    private val activeDelegate: Any?,
) : Closeable {

    data class Config(
        /** Detections below this score are dropped before NMS. */
        val scoreThreshold: Float = 0.5f,
        /** IoU above which two same-class boxes are considered duplicates. */
        val iouThreshold: Float = 0.5f,
        /** Hard cap on boxes kept per frame. */
        val maxDetections: Int = 10,
        /** CPU threads used by the interpreter when no delegate is active. */
        val numThreads: Int = 4,
        /** Try the GPU delegate first (falls back to CPU automatically). */
        val useGpuDelegate: Boolean = true,
        /** Try the NNAPI delegate after GPU (off by default: slow on some devices). */
        val useNnApiDelegate: Boolean = false,
        /** Domain of the values fed into the model's input tensor. */
        val inputScaling: InputScaling = InputScaling.AUTO,
        /** Whether raw score outputs need a sigmoid first. */
        val scoreActivation: ScoreActivation = ScoreActivation.AUTO,
    )

    enum class InputScaling {
        /** Derive the domain from the input tensor's dtype / quantization params. */
        AUTO,
        /** Feed `channel / 255f` (models with `mean=0, std=1` normalization). */
        ZERO_TO_ONE,
        /** Feed `channel.toFloat()` in `0f..255f` (models that normalize in-graph). */
        RAW_ZERO_TO_255,
        /** Feed `channel / 127.5f - 1f`, the MobileNet-style `[-1, 1]` domain. */
        MINUS_ONE_TO_ONE,
    }

    private data class InputTransform(val scale: Float, val bias: Float)

    enum class ScoreActivation { AUTO, SIGMOID, NONE }

    /** Model input size, e.g. 300 x 300 (useful for logging / debug overlays). */
    val inputWidth: Int
    val inputHeight: Int

    /** Which backend the interpreter actually uses, e.g. `GPU`, `NNAPI`, `CPU x4`. */
    val accelerationDescription: String get() = acceleration

    private val inputTensor: Tensor = interpreter.getInputTensor(0)
    private val quantizedInput = inputTensor.dataType() != DataType.FLOAT32
    private val inputScale: Float = inputTensor.quantizationParams().firstScale
    private val inputZeroPoint: Int = inputTensor.quantizationParams().firstZeroPoint
    private val inputBuffer: ByteBuffer
    private val inputFloatView: java.nio.FloatBuffer?
    /** Affine pixel transform: `input = channel * scale + bias` (see [InputScaling]). */
    private val inputValueScale: Float
    private val inputValueBias: Float
    private val inputQuantRange: IntRange =
        if (inputTensor.dataType() == DataType.INT8) -128..127 else 0..255

    private val outputIndices: List<Int>
    private val outputBuffers: List<ByteBuffer>
    /** Output index -> buffer, the map `runForMultipleInputsOutputs` expects. */
    private val outputBufferMap: MutableMap<Int, Any> = HashMap()
    private val readers: List<TensorReader>
    private var boxesIndex = -1
    private var classesIndex = -1
    private var scoresIndex = -1
    private var countIndex = -1
    private val numBoxes: Int
    private var classScoreAssignmentChecked = false

    private val pixelBuffer: IntArray
    private val boxBuffer = FloatArray(4)

    init {
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "Expected an NHWC input tensor [1, H, W, 3] but found ${inputShape.toList()}"
        }
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        require(inputHeight > 0 && inputWidth > 0) {
            "The model input has dynamic dimensions $inputShape; export it with a fixed size"
        }
        val bytesPerChannel = if (quantizedInput) 1 else 4
        inputBuffer = ByteBuffer
            .allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        inputFloatView = if (quantizedInput) null else inputBuffer.asFloatBuffer()
        val transform = resolveInputTransform()
        inputValueScale = transform.scale
        inputValueBias = transform.bias
        pixelBuffer = IntArray(inputWidth * inputHeight)

        // One output buffer per tensor, sized from the tensor itself.
        outputIndices = (0 until interpreter.outputTensorCount).toList()
        readers = outputIndices.map { index ->
            TensorReader(interpreter.getOutputTensor(index), bytesFor(interpreter.getOutputTensor(index)))
        }
        outputBuffers = readers.map { reader ->
            ByteBuffer.allocateDirect(reader.byteCapacity).order(ByteOrder.nativeOrder())
        }
        // Bind each reader to its buffer before any inference runs.
        readers.forEachIndexed { index, reader ->
            reader.bind(outputBuffers[index])
            outputBufferMap[outputIndices[index]] = outputBuffers[index]
        }
        numBoxes = resolveOutputTensors()
        Log.i(
            TAG,                "Model ready: input ${inputWidth}x$inputHeight " +
                "(quantized=$quantizedInput, scale=$inputValueScale, bias=$inputValueBias), " +
                "$numBoxes boxes, backend=$acceleration",
        )
    }

    /**
     * Runs inference on an **upright** ARGB_8888 bitmap (see
     * `ImageProxy.toUprightBitmap()`) and returns the filtered detections.
     */
    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> {
        fillInputBuffer(bitmap)
        // Note: `Interpreter.run(input, output)` only fills output index 0, which
        // would silently ignore 3 of the 4 detection tensors.
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputBufferMap)

        if (!classScoreAssignmentChecked) verifyClassAndScoreAssignment()

        val scoreReader = readers[scoresIndex]
        val classReader = readers[classesIndex]
        val boxReader = readers[boxesIndex]
        val count = if (countIndex >= 0) {
            readers[countIndex].int(0).coerceIn(0, numBoxes)
        } else {
            numBoxes
        }
        if (count == 0) return emptyList()

        val scoresAreLogits = when (config.scoreActivation) {
            ScoreActivation.SIGMOID -> true
            ScoreActivation.NONE -> false
            ScoreActivation.AUTO -> (0 until count).any { i ->
                val value = scoreReader.float(i)
                value < 0f || value > 1f
            }
        }

        val candidates = ArrayList<Detection>(minOf(count, config.maxDetections * 2))
        for (i in 0 until count) {
            val score = scoreReader.float(i).let { if (scoresAreLogits) TensorMath.sigmoid(it) else it }
            if (score < config.scoreThreshold) continue

            val classIndex = classReader.int(i)
            val trafficLightClass = labels.classAt(classIndex)
            if (trafficLightClass == null) {
                // Background class or a label that is not a traffic light.
                continue
            }
            boxReader.box(i, boxBuffer)

            // TFLite detection models emit [ymin, xmin, ymax, xmax].
            val top = boxBuffer[0].coerceIn(0f, 1f)
            val left = boxBuffer[1].coerceIn(0f, 1f)
            val bottom = boxBuffer[2].coerceIn(0f, 1f)
            val right = boxBuffer[3].coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue

            candidates += Detection(
                trafficLightClass = trafficLightClass,
                label = labels.labelAt(classIndex) ?: trafficLightClass.modelLabel,
                classIndex = classIndex,
                confidence = score.coerceIn(0f, 1f),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }

        return NonMaxSuppression.suppress(
            detections = candidates,
            iouThreshold = config.iouThreshold,
            maxDetections = config.maxDetections,
        )
    }

    @Synchronized
    override fun close() {
        interpreter.close()
        when (val delegate = activeDelegate) {
            is GpuDelegate -> delegate.close()
            is NnApiDelegate -> delegate.close()
        }
        Log.i(TAG, "Detector released")
    }

    // ---------------------------------------------------------------- input ---

    /**
     * Works out which value domain the input tensor expects.
     *
     * Detection exports come in three flavours:
     * * float input -> `0..1` (the overwhelming majority),
     * * quantized over `0..1`  -> scale ~= 1/255 (uint8, zero point 0) or
     *   scale ~= 1/255 with zero point -128 (int8),
     * * quantized symmetrically over `[-1, 1]` -> scale ~= 1/127.5.
     *
     * Getting this wrong makes the model return nothing, which is hard to spot,
     * so the guess can be overridden with [Config.inputScaling].
     */
    private fun resolveInputTransform(): InputTransform = when (config.inputScaling) {
        InputScaling.ZERO_TO_ONE -> InputTransform(1f / 255f, 0f)
        InputScaling.RAW_ZERO_TO_255 -> InputTransform(1f, 0f)
        InputScaling.MINUS_ONE_TO_ONE -> InputTransform(1f / 127.5f, -1f)
        InputScaling.AUTO -> when {
            !quantizedInput -> InputTransform(1f / 255f, 0f)
            inputZeroPoint <= -64 -> InputTransform(1f / 255f, 0f)
            inputScale in 0.0025f..0.006f -> InputTransform(1f / 255f, 0f)
            inputScale in 0.006f..0.015f -> InputTransform(1f / 127.5f, -1f)
            else -> InputTransform(1f, 0f)
        }
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        scaled.getPixels(pixelBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== bitmap) scaled.recycle()

        if (quantizedInput) {
            inputBuffer.rewind()
            for (index in pixelBuffer.indices) {
                val color = pixelBuffer[index]
                val base = index * 3
                inputBuffer.put(base + 0, quantizeChannel((color shr 16) and 0xFF))
                inputBuffer.put(base + 1, quantizeChannel((color shr 8) and 0xFF))
                inputBuffer.put(base + 2, quantizeChannel(color and 0xFF))

            }
        } else {
            val view = inputFloatView!!
            for (index in pixelBuffer.indices) {
                val color = pixelBuffer[index]
                val base = index * 3
                view.put(base + 0, applyInputTransform((color shr 16) and 0xFF))
                view.put(base + 1, applyInputTransform((color shr 8) and 0xFF))
                view.put(base + 2, applyInputTransform(color and 0xFF))
            }
        }
    }

    private fun applyInputTransform(channel: Int): Float =
        channel * inputValueScale + inputValueBias

    private fun quantizeChannel(channel: Int): Byte =
        TensorMath.quantize(
            real = applyInputTransform(channel),
            scale = inputScale,
            zeroPoint = inputZeroPoint,
            min = inputQuantRange.first,
            max = inputQuantRange.last,
        ).toByte()

    // --------------------------------------------------------------- output ---

    /** Total byte size of a tensor's backing buffer. */
    private fun bytesFor(tensor: Tensor): Int {
        val elements = tensor.shape().fold(1L) { acc, dim -> acc * dim }
        return (elements * bytesPerElement(tensor.dataType())).toInt().coerceAtLeast(1)
    }

    private fun bytesPerElement(dataType: DataType): Int = when (dataType) {
        DataType.FLOAT32, DataType.INT32 -> 4
        DataType.INT16 -> 2
        else -> 1 // UINT8 / INT8 / BOOL
    }

    /**
     * Binds the four outputs. Returns the box count `N`.
     *
     * Boxes are unambiguous (only one tensor ends in `4`). `num_detections` is a
     * scalar. The remaining two `[1, N]` tensors are classes and scores: names
     * are used when the exporter kept them, and [verifyClassAndScoreAssignment]
     * checks the guess against real values on the first frame.
     */
    private fun resolveOutputTensors(): Int {
        val tensors = outputIndices.map { interpreter.getOutputTensor(it) }

        boxesIndex = tensors.indexOfFirst { tensor ->
            tensor.shape().let { it.size == 3 && it[2] == 4 }
        }
        if (boxesIndex < 0) {
            boxesIndex = tensors.indexOfFirst { tensor ->
                val name = tensor.name().lowercase()
                name.contains("locat") || name.contains("box")
            }
        }
        require(boxesIndex >= 0) {
            "No bounding-box output tensor found. Expected a [1, N, 4] tensor, " +
                "found ${tensors.map { it.name() + it.shape().toList() }}"
        }
        val boxCount = tensors[boxesIndex].shape()[1]

        countIndex = tensors.indices.firstOrNull { index ->
            index != boxesIndex && elementCount(tensors[index]) == 1L &&
                tensors[index].name().lowercase().contains("num")
        } ?: tensors.indices.firstOrNull { index ->
            index != boxesIndex && elementCount(tensors[index]) == 1L
        } ?: -1

        val remaining = tensors.indices.filter { it != boxesIndex && it != countIndex }
        require(remaining.size >= 2) {
            "Expected separate class and score tensors, found ${tensors.size} outputs"
        }
        val namedScores = remaining.firstOrNull { tensors[it].name().lowercase().contains("score") }
        val namedClasses = remaining.firstOrNull { tensors[it].name().lowercase().contains("class") }
        when {
            namedScores != null && namedClasses != null -> {
                scoresIndex = namedScores
                classesIndex = namedClasses
            }
            // Well-known order of TFLite_Detection_PostProcess: locations, classes, scores, num
            else -> {
                classesIndex = remaining.first()
                scoresIndex = remaining.last()
            }
        }
        require(classesIndex >= 0 && scoresIndex >= 0 && classesIndex != scoresIndex)
        classScoreAssignmentChecked = namedScores != null && namedClasses != null
        return boxCount
    }

    private fun elementCount(tensor: Tensor): Long =
        tensor.shape().fold(1L) { acc, dim -> acc * dim }

    /**
     * Safety net for exporters that drop tensor names: class indices are whole
     * numbers, scores are not. If the guess was inverted, swap it once.
     */
    private fun verifyClassAndScoreAssignment() {
        classScoreAssignmentChecked = true
        val sample = minOf(numBoxes, 32)
        if (sample == 0) return
        val classes = FloatArray(sample) { readers[classesIndex].float(it) }
        val scores = FloatArray(sample) { readers[scoresIndex].float(it) }
        val classesLookLikeIndices = TensorMath.looksLikeIntegerSequence(classes)
        val scoresLookLikeIndices = TensorMath.looksLikeIntegerSequence(scores)
        if (scoresLookLikeIndices && !classesLookLikeIndices) {
            Log.w(TAG, "Class/score outputs looked swapped, correcting the assignment")
            val previousClasses = classesIndex
            classesIndex = scoresIndex
            scoresIndex = previousClasses
        }
    }

    /**
     * Uniform reader over float / int32 / int8 / uint8 output tensors, so the
     * post-processing loop does not care about the model's quantization.
     */
    private class TensorReader(private val tensor: Tensor, val byteCapacity: Int) {
        private val dataType = tensor.dataType()
        private val quantized = dataType == DataType.UINT8 || dataType == DataType.INT8
        /** INT8 tensors hold signed values, UINT8 tensors unsigned ones. */
        private val signedBytes = dataType == DataType.INT8
        private val scale: Float = tensor.quantizationParams().firstScale
        private val zeroPoint: Int = tensor.quantizationParams().firstZeroPoint
        private var buffer: ByteBuffer? = null
        private var floatView: java.nio.FloatBuffer? = null
        private var intView: java.nio.IntBuffer? = null

        fun bind(buffer: ByteBuffer) {
            this.buffer = buffer.order(ByteOrder.nativeOrder())
            floatView = if (dataType == DataType.FLOAT32) buffer.asFloatBuffer() else null
            intView = if (dataType == DataType.INT32) buffer.asIntBuffer() else null
        }

        fun float(index: Int): Float = when {
            quantized -> TensorMath.dequantize(
                rawByte(index),
                scale,
                zeroPoint,
            )
            dataType == DataType.INT32 -> intView!!.get(index).toFloat()
            else -> floatView!!.get(index)
        }

        private fun rawByte(index: Int): Int {
            val byte = buffer!!.get(index).toInt()
            return if (signedBytes) byte else byte and 0xFF
        }

        fun int(index: Int): Int = when {
            quantized -> float(index).roundToInt()
            dataType == DataType.INT32 -> intView!!.get(index)
            else -> floatView!!.get(index).roundToInt()
        }

        /** Reads the 4 box coordinates for detection [index] into [out]. */
        fun box(index: Int, out: FloatArray) {
            out[0] = float(index * 4 + 0)
            out[1] = float(index * 4 + 1)
            out[2] = float(index * 4 + 2)
            out[3] = float(index * 4 + 3)
        }
    }

    companion object {
        private const val TAG = "TrafficLightDetector"

        /** Model file placed in `src/main/assets/`. */
        const val MODEL_ASSET = "traffic_light.tflite"

        /**
         * Loads [MODEL_ASSET] and builds the interpreter, preferring the GPU
         * delegate, then NNAPI, then the multi-threaded CPU kernel (XNNPACK).
         *
         * @throws IllegalStateException when the asset is missing or unusable.
         */
        fun create(context: Context, config: Config = Config()): TrafficLightDetector {
            val labels = TrafficLightLabels.fromAssets(context)
            val model = mapModelFile(context)

            val attempts = buildList {
                if (config.useGpuDelegate) add(Attempt("GPU") { GpuDelegate() })
                if (config.useNnApiDelegate) add(Attempt("NNAPI") { NnApiDelegate() })
                add(Attempt("CPU x${config.numThreads}") { null })
            }

            var lastError: Throwable? = null
            for (attempt in attempts) {
                var delegate: Any? = null
                var interpreter: Interpreter? = null
                try {
                    delegate = attempt.create?.invoke()
                    val options = Interpreter.Options().setNumThreads(config.numThreads)
                    when (delegate) {
                        is GpuDelegate -> options.addDelegate(delegate)
                        is NnApiDelegate -> options.addDelegate(delegate)
                    }
                    // A fresh buffer per attempt: Interpreter keeps reading from it.
                    interpreter = Interpreter(model.duplicate(), options)
                    return TrafficLightDetector(
                        interpreter = interpreter,
                        labels = labels,
                        config = config,
                        acceleration = attempt.name,
                        activeDelegate = delegate,
                    )
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "Falling back from the ${attempt.name} backend: ${t.message}")
                    interpreter?.close()
                    closeQuietly(delegate)
                    model.rewind()
                }
            }
            throw IllegalStateException(
                "Could not create a TFLite interpreter for $MODEL_ASSET. " +
                    "Did you copy the model into app/src/main/assets/ ?",
                lastError,
            )
        }

        private class Attempt(val name: String, val create: (() -> Any?)?)

        private fun closeQuietly(delegate: Any?) {
            when (delegate) {
                is GpuDelegate -> delegate.close()
                is NnApiDelegate -> delegate.close()
            }
        }

        /**
         * Memory-maps the model straight out of the APK; falls back to a heap
         * copy when the asset was compressed by aapt.
         */
        private fun mapModelFile(context: Context): ByteBuffer = try {
            context.assets.openFd(MODEL_ASSET).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Memory mapping failed, copying $MODEL_ASSET instead", t)
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
}

/**
 * Quantisation params are **empty** for an un-quantised tensor, so the values
 * are read through these two helpers instead of indexing the arrays blindly.
 * The fallbacks are the neutral ones: a scale of 1 leaves values untouched and
 * a zero point of 0 is the identity offset.
 */
private val QuantizationParams.firstScale: Float
    get() = if (scale.size == 0) 1f else scale[0]

private val QuantizationParams.firstZeroPoint: Int
    get() = if (zeroPoint.size == 0) 0 else zeroPoint[0]
