package com.translate.livecaption.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.translate.livecaption.model.ModelManager

/**
 * 流式 Transducer 识别器封装（sherpa-onnx）。
 *
 * 与 Windows 侧的对应关系（见 docs/01-rea-report.md 第 6 节）：
 *   encoder.onnx + decoder.onnx + joint.onnx  ≡ 这里的 transducer 三件套
 *   sr.ini 的 ms-per-frame=40 / beam-width=3   ≡  这里用 greedy 先跑通
 *   内置 svad / 端点检测                        ≡  EndpointConfig + isEndpoint()
 */
class AsrEngine(context: Context, model: ModelManager.AsrModel) {

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream

    init {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ModelManager.path(context, model, model.encoder),
                    decoder = ModelManager.path(context, model, model.decoder),
                    joiner = ModelManager.path(context, model, model.joiner)
                ),
                tokens = ModelManager.path(context, model, model.tokens),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search",
            enableEndpoint = true,
            endpointConfig = EndpointConfig()
        )
        // 注意：第一个参数是 AssetManager。我们用的是绝对文件路径（模型在 filesDir），
        // 必须传 null，否则 sherpa-onnx 会把路径当成 assets 里的资源去读，直接报
        // "absolute path ... but assetManager is NOT set to null" 并 abort 进程。
        recognizer = OnlineRecognizer(null, config)
        stream = recognizer.createStream()
    }

    fun accept(samples: FloatArray, count: Int) {
        val data = if (count == samples.size) samples else samples.copyOf(count)
        stream.acceptWaveform(data, SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
    }

    fun text(): String = recognizer.getResult(stream).text

    /** 句末取完整结果（含逐 token 与时间戳），标点要用 */
    fun result(): com.k2fsa.sherpa.onnx.OnlineRecognizerResult =
        recognizer.getResult(stream)

    fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)

    fun reset() = recognizer.reset(stream)

    fun release() {
        try {
            stream.release()
        } catch (_: Throwable) {
        }
        try {
            recognizer.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
