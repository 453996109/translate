# 从 Windows「实时辅助字幕」到 Android —— 移植技术方案

> 前置阅读：`01-rea-report.md`（Windows 侧逆向结论）
> 目标：在 Android 上做到 **系统级实时字幕 + 实时翻译悬浮窗**，全离线、低延迟、可移植

---

## 1. 可行性总评

| 维度 | 结论 |
|---|---|
| **架构** | ✅ 100% 可照搬。Windows 是「回环采集 → 流式 Transducer → 标点/ITN → 翻译 → 悬浮窗」，Android 每一环都有对应能力 |
| **模型权重** | ❌ 不可搬。模型文件头内嵌许可明确禁止再分发与反向工程（见报告 6.5） |
| **算法参数** | ✅ 可搬。80 维 mel / 10 ms 帧移 / 40 ms 出帧 / beam=3 / VAD 阈值 0.6 / 标点阈值 0.35，这些是经验值，直接借用即可少走弯路 |
| **采集范围** | ⚠️ 有折损。Windows 能按进程树抓声音；Android 只能抓「允许被采集」的 App（DRM/音乐类常主动拒绝） |
| **贴边停靠** | ⚠️ 无等价物。Windows 有 AppBar 让其他窗口让位；Android 悬浮窗只能浮在上面，或用无障碍/AccessibilityOverlay |
| **翻译质量** | ⚠️ 换模型后必然不如微软自家 NMT，需要接受质量下降或加大模型（代价是体积/延迟） |

**一句话**：这不是"能不能移植"的问题，是"用什么开源模型替代那 195 MB 私有权重、以及接受多少能力折损"的问题。

---

## 1.5 本地文件逐项判定：哪些真能用？

先说结论：**模型是数据，解码器是逻辑，而逻辑恰恰在唯一不能用的那个文件里。**

| 文件 | 技术可加载 | 授权可用 | 说明 |
|---|---|---|---|
| `encoder/decoder/joint.onnx` 等 7 个 | ✅ **能**（见下） | ❌ | 全是标准 ONNX，opset 11 |
| `lid_quantized.onnx` | ✅ 能 | ❌ | 用了 `ConvInteger`，也是标准量化算子 |
| `tokens.list` / `ciphone.lst` / `sr.ini` / `*.config` | ✅ 能读 | ❌ | 纯文本，但和权重绑定，换模型即作废 |
| `*_capitalizationRules.enc` / `ProfanityList.enc` | ⚠️ 自定义编码 | ❌ | 可逆，但没有必要——规则自己重建更省事 |
| `itn/itn.bin`、`prongen/*.lxa .phn .prel .bin` | ❌ **不能** | ❌ | 私有二进制格式，无语义信息，需逆向格式本身 |
| `embedded.sr.runtime.dll` | ❌ **绝对不能** | ❌ | x64 Windows PE，Android/arm64 加载不了 |
| `Speech.core.dll` + 其余 6 个 SDK DLL | ❌ **绝对不能** | ❌ | 同上，全是 x64 PE |

### 关键澄清：`.onnx` 能跑，但跑起来什么也不是

我用 `onnx_io.py` 逐模型核对了算子与 opset：

```
encoder.onnx          opset=11  op_types=32  非标准算子: 无（DequantizeLinear 是标准量化算子）
joint.onnx            opset=11  op_types=7   非标准算子: 无
wt.quantized.onnx     opset=11  op_types=32   非标准算子: 无
lid_quantized.onnx    opset=11  op_types=17   非标准算子: 无（ConvInteger）
```

也就是说 **ONNX Runtime for Android 完全可以把这些文件加载起来、跑通前向**。
但问题在于：ONNX 图里**只有前向网络**，没有这些东西——

- 束搜索（`beam=3` / `nbest=100` / lattice 重打分）
- **流式缓存状态机**（`cache_k` / `cache_v` / `cache_cnn` 什么时候清空、什么时候回写）
- VAD 分段调度、句末判定、`vad-rewind-frames-count=75` 的回退
- 标点 / ITN / 词对齐的调用时序
- 热词加权（`biasing-weight=1.1`）怎么注入

这些全部在 `Microsoft.CognitiveServices.Speech.extension.embedded.sr.runtime.dll` 里，
5.2 MB 的 x64 PE。**它才是"逻辑"的载体。**

所以现实是：即使你无视许可把 `.onnx` 拷到 Android，你还是得从零写一个流式 RNN-T 解码器
（含缓存状态机，约 1500–2500 行 C++）。那已经不叫"搬"，叫"重写"。

### 唯一合法的 Android 路：Azure 嵌入式语音官方支持 Android

这是本次最值得知道的一条信息。微软官方文档明确：

> 嵌入式语音随 **Speech SDK 1.24.1+（C# / C++ / Java）** 提供；
> **目标环境 Android**：需要 **Android 8.0（API 26）** 或更高，`arm64-v8a` / `armeabi-v7a` 硬件。
> Java 项目直接依赖 `client-sdk-embedded`（AAR）。

| 项 | 内容 |
|---|---|
| 依赖 | `com.microsoft.cognitiveservices.speech:client-sdk-embedded`（**注意必须是 embedded 版，`client-sdk` 只能走云**） |
| 硬件 | API 26+ / arm64-v8a 或 armeabi-v7a |
| 内存 | 模型文件总大小 + 200 MB |
| 音频 | **只接受 mono / 16-bit / 8 kHz 或 16 kHz PCM**（与我们实测的 `sr.ini` 完全对上） |
| 门槛 | **嵌入式语音是 Limited Access**，需要通过 Foundry 的"嵌入式语音受限访问评审"申请；模型从 Azure 下载，按承诺用量计费 |

**这意味着**：
- "能不能在 Android 上跑微软这套模型" → **能，官方就支持**；
- "能不能白嫖 Windows 里那份" → **不能**，Windows 预置包只授权随 Windows 使用，
  且要拿 Android 模型得走 Azure 申请；
- 商务门槛远高于技术门槛。

### 两条路的取舍

| | A. 开源重建（sherpa-onnx） | B. Azure 嵌入式（官方 Android） |
|---|---|---|
| 技术难度 | 中（有现成 SDK，写业务逻辑即可） | 低（API 与 Windows 那份几乎一样） |
| 商务门槛 | 无 | **高**：受限访问审核 + 用量承诺 |
| 模型体积 | 40–100 MB（可自选/裁剪） | 195 MB + 200 MB 运行时 |
| 识别质量 | 中上（Zipformer 已很能打） | 高（微软自家 V10 模型） |
| 可商用 | 需逐个核对上游训练数据许可 | ✅ 授权明确 |
| 适合 | 个人项目 / 开源 / 快速验证 | 设备厂商 / 商业化产品 |

---

## 2. 能力映射表

| Windows 实现（实测） | Android 对应 | 备注 |
|---|---|---|
| `ActivateAudioInterfaceAsync` + `VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK`（进程回环） | `MediaProjection` + `AudioPlaybackCaptureConfiguration` + `AudioRecord`（API 29+） | 底层都是 remote-submix；Android 多一道用户授权 |
| `IAudioCaptureClient::GetBuffer` 拉流 | `AudioRecord.read()` 或 `AAudio`/`Oboe` 回调 | 建议 16 kHz / mono / PCM16 直接采集（`AudioRecord` 支持指定采样率，系统会重采样） |
| 麦克风叠加（`SettingsIncludeMicrophoneAudio`） | 第二条 `AudioRecord(MIC)` + 混音 | 两条流做加权求和，注意回声 |
| Speech SDK `push_audio_input_stream` | RingBuffer → JNI → `OnlineStream::AcceptWaveform` | 每 100~200 ms 推一次 |
| `encoder/decoder/joint.onnx`（RNN-T） | sherpa-onnx 流式 Zipformer Transducer | 同族架构，参数可对照 |
| `svad_init.quantized.onnx` | Silero VAD（ONNX，~2 MB）或 sherpa-onnx VAD | |
| `cappunc.onnx` | sherpa-onnx 标点模型（CT-Transformer） | |
| `itn.bin` + `itntag` | 规则 + `kaldifst`/ICU（Android 上建议纯 Java 规则） | 中文场景量级：数字/单位/日期/金额 |
| `wt.quantized.onnx`（词级时间戳） | sherpa-onnx `timestamps` | 字幕逐词高亮用 |
| `TranslationRecognizer` + 翻译模型包 | OPUS-MT / NLLB-200 int8 on ONNX Runtime | 见第 7 节 |
| WinUI3 + `DwmSetWindowAttribute` 悬浮窗 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` | 建议 Compose 或原生 View |
| `SHAppBarMessage` 贴边停靠 | ❌ 无 | 替代：贴在状态栏下方/导航栏上方，监听 `WindowInsets` |
| `HKCU\...\LiveCaptions\UI` | `DataStore` | |
| `user32` 全局热键 Win+Ctrl+L | `AccessibilityService` 或快捷方式/磁贴 | 无全局热键 API |
| 遥测指标（首字延迟/平均延迟） | 自建埋点，口径照抄 | 见报告第 9 节 |

---

## 3. 目标架构

```
┌────────────────────────────────────────────────────────────────┐
│  :ui 进程                                                       │
│  MainActivity（授权/语言包管理/样式设置）                        │
│  CaptionOverlayService ── 悬浮窗（Compose/View）                 │
│        ▲ Flow<CaptionLine>                                      │
└────────┼───────────────────────────────────────────────────────┘
         │ Binder / 同进程 AIDL
┌────────┼───────────────────────────────────────────────────────┐
│  :capture 前台服务 (foregroundServiceType=mediaProjection)      │
│                                                                │
│  AudioSource ──┬─ PlaybackCapture(AudioRecord)                 │
│                └─ MicCapture(AudioRecord)  → Mixer → 16k PCM16 │
│                        │                                       │
│                        ▼                                       │
│                 RingBuffer(300ms 容量)                          │
│                        │ 每 100ms 取块                          │
│                        ▼                                       │
│  JNI ── sherpa-onnx OnlineRecognizer                            │
│         ├─ VAD（切句）                                          │
│         ├─ Encoder/Decoder/Joiner（流式 Transducer）             │
│         ├─ 部分结果 ──► 字幕稳定化器 ──► Overlay                │
│         └─ 句结束 ──► 标点 + ITN ──► 翻译队列 ──► 译文行         │
│                        │                                       │
│  NMT（可选，独立线程池/独立进程）                                │
└────────────────────────────────────────────────────────────────┘
```

**为什么分两个进程**：模型常驻内存 150~400 MB，UI 崩溃会连带杀掉识别；分进程还能让 UI 进程被杀后字幕不中断（Android 对前台服务更宽容）。代价是 IPC 拷贝，字幕文本量极小可忽略。

**为什么不把模型放 :ui**：悬浮窗重绘频繁，避免 GC 抖动影响推理线程。

---

## 4. 音频采集（移植的第一个真坑）

### 4.1 系统音频

```kotlin
// 1) 申请授权（必须在启动前台服务之前，Android 14+ 强制）
val intent = mediaProjectionManager.createScreenCaptureIntent()
startActivityForResult(intent, REQ_PROJECTION)

// 2) 授权回来后先起前台服务，再取 token
startForegroundService(Intent(ctx, CaptureService::class.java).putExtra("data", data))

// 3) 服务内
val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
projection.registerCallback(object : MediaProjection.Callback() {
    override fun onStop() { /* 用户从通知栏撤权，必须停采集 */ }
}, Handler(Looper.getMainLooper()))

val config = AudioPlaybackCaptureConfiguration.Builder(projection)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
    .addMatchingUsage(AudioAttributes.USAGE_GAME)
    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
    .build()

val record = AudioRecord.Builder()
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
    )
    .setBufferSizeInBytes(16000 * 2 * 2)     // 2 秒
    .setAudioPlaybackCaptureConfig(config)
    .build()
```

**必然踩到的限制**（都是系统强制的，不是 bug）：

| 限制 | 后果 | 对策 |
|---|---|---|
| 只能抓 `USAGE_MEDIA / GAME / UNKNOWN` | 通话、导航播报抓不到 | 想抓就得用无障碍 + 麦克风，或放弃 |
| 目标 App 可设 `ALLOW_CAPTURE_BY_NONE` | 抓到的是**纯静音**（不是报错） | 必须做"静音检测"提示用户："当前应用不允许被录制" |
| DRM 内容抓不到 | 同上 | 同上 |
| 必须同用户空间 | 工作资料的声音抓不到 | 文档说明 |
| 每次会话都要用户点确认 | 体验比 Windows 差一截 | Android 14+ 可复用同一次 token 的重连窗口很窄，老实提示 |
| 部分厂商 ROM 对 remote-submix 有裁剪 | 抓到空流 | 加 feature-detect + 降级到麦克风 |

### 4.2 麦克风叠加

与系统音频用不同 `AudioRecord`，各自线程读，`Mixer` 做 `a*0.7 + b*0.7` 饱和加法。
**必须**在系统音频侧做"回声泄漏检测"：如果麦克风里能听到系统声音（外放），会出现字幕重复，
处理办法是仅当麦克风 RMS 高于系统音频 RMS 的 1.5 倍时才启用麦克风通路。

### 4.3 重采样

`AudioRecord` 指定 16 kHz 时，系统会在 AudioFlinger 层重采样，音质可接受但会引入少量延迟。
若对延迟敏感，用 48 kHz 采集 + `soxr`/`speex_resampler` 自己降到 16 kHz（延迟可控制在 5 ms 内）。

---

## 5. 识别：sherpa-onnx 流式 Transducer

### 5.1 与 Windows 参数对照

| 参数 | Windows（实测 `sr.ini`） | Android（建议起点） |
|---|---|---|
| 特征 | 80 维 log-mel，25 ms 窗 / 10 ms 移 | `--feature-dim=80`（sherpa 默认一致） |
| 出帧 | 每 40 ms 一个 token 帧（CNN 子采样 ×4） | Zipformer 默认 10 ms 帧 → 出帧约 40~80 ms（取决于模型） |
| 解码 | RNN-T，`beam-width=3`，`nbest=100`，`use-lattice=1` | 先 `greedy_search`（延迟最低），再试 `modified_beam_search`（beam=4） |
| VAD | 独立 svad，880 ms 窗，阈值 0.6 / 段阈值 0.35 | Silero VAD，`threshold=0.5`，`min_silence_duration=0.3s` |
| 词表 | 4002 BPE | 视模型（通常 500~2000 BPE） |
| 热词 | `biasing-weight=1.1` | sherpa `--hotwords-file` + `--hotwords-score 1.5` |
| 脏话过滤 | `en-us_ProfanityList.enc` | 自建词表（可选） |
| 语义分段 | `is-semantic-seg-supported=true` | 用标点模型 + 最小长度阈值近似 |

> 关键提醒：**别照抄 `beam-width=3` 就直接上**。Windows 有 lattice + N-best 重打分扛质量，
> Android 上贪心解码更省电更跟手；先用贪心跑通，再用 `modified_beam_search` 做 A/B。

### 5.2 模型选型

| 用途 | 候选 | 量级 | 许可 | 建议 |
|---|---|---|---|---|
| 中英混合 ASR | `sherpa-onnx-streaming-zipformer-bilingual-zh-en`（int8） | 40~100 MB | Apache-2.0（部分子集训练数据许可需自查） | **首选**，移动端 RTF 低 |
| 纯中文高精度 | `sherpa-onnx-streaming-zipformer-zh-int8-2025-*` | 90~170 MB | 上游未声明 | 质量好但许可不清，商用前必须确认 |
| 纯英文低功耗 | `sherpa-onnx-streaming-zipformer-en-*` | 20~40 MB | Apache-2.0 | 手表/低端机 |
| 非流式兜底 | Whisper-small / SenseVoice | 150~500 MB | MIT / Apache-2.0 | 只用于"重新识别这句"，不能做首屏 |

**落地策略**：模型不打进 APK。走"首次启动下载 + SHA256 校验 + 可换源"的模型管理器
（和 Windows 的 Appx 语言包一个思路，见报告 2.4）。

> **易混淆点：「bilingual zh-en」= 双语识别，不是翻译。**
> `sherpa-onnx-streaming-zipformer-bilingual-zh-en` 只是"同一句话里中文英文混着说也能转写"，
> 共享一套 BPE 词表；它**不做语言判定、不做翻译、不做变声**。
> 翻译必须再接一个 NMT 模型（见第 7 节）。这两步在 Windows 上也是分开的，只是被 SDK 包在了一起。
>
> 许可：该权重为 **Apache-2.0**（HF 仓库有显式 license 提交），上游 torchscript 来自
> `pfluo/k2fsa-zipformer-chinese-english-mixed`，训练代码为 icefall
> `pruned_transducer_stateless7_streaming`。sherpa-onnx 框架本身也是 Apache-2.0。
> HF 仓库 620 MB 是**全量**（fp32 + int8 + torchscript + 测试音频），
> Android 打包只需 3 个 int8 onnx + `tokens.txt`，量级约 100 MB（以实际 tar 包为准）。

### 5.3 JNI 推流（关键代码骨架）

```kotlin
object Asr {
    init { System.loadLibrary("sherpa-onnx-jni") }

    external fun createRecognizer(
        encoder: String, decoder: String, joiner: String,
        tokens: String, numThreads: Int, provider: String,   // "cpu" / "nnapi" / "qnn"
        decodingMethod: String,                              // "greedy_search"
        hotwordsFile: String, hotwordsScore: Float,
        blankPenalty: Float, rule1: Float, rule2: Float, rule3: Float
    ): Long
    external fun createStream(): Long
    external fun acceptWaveform(stream: Long, samples: FloatArray, sampleRate: Int)
    external fun isReady(h: Long, s: Long): Boolean
    external fun decode(h: Long, s: Long)
    external fun getText(h: Long, s: Long): String        // 部分结果
    external fun getResult(h: Long, s: Long): String      // 句末结果
    external fun reset(h: Long, s: Long)
    external fun release(...)
}
```

`sherpa-onnx` 官方已经提供这套 JNI（`OnlineRecognizer` / `OnlineStream`），
直接用预编译 `jniLibs`（arm64-v8a + armeabi-v7a），不要自己造轮子。

**线程模型**（照 Windows 的 `AudioCaptureClientReader` 抄）：

```
AudioRecord.read 线程   → RingBuffer.put        （SCHED_FIFO 优先级，禁止任何分配）
推理线程（单线程）       → 每 100 ms RingBuffer.take → acceptWaveform → decode → emit
UI 线程                  → 只消费 StateFlow<String>
```

---

## 6. 后处理

### 6.1 标点与断句

- 句末判定：VAD 端点 + 标点模型的句号类输出，二者取"或"，但要求句长 ≥ 4 字（防抖）。
- 标点模型建议 sherpa-onnx 的中英标点模型（CT-Transformer），**但别每帧都跑**：
  只在"该句 token 数变化且距上次标点 ≥ 300 ms"时跑一次，成本降一个数量级。

### 6.2 ITN（数字/单位正规化）

Windows 用 `itn.bin`（WFST）+ `itntag.onnx`（实体打标）。Android 上建议：

- **英文**：正则为主（`twenty five` → `25`，`three p.m.` → `3 p.m.`），覆盖 90% 场景；
- **中文**：`二〇二六年` → `2026年`、`百分之三十` → `30%`、`三点五` → `3.5`，用规则表；
- 复杂场景（金额、电话号码、地址）再考虑接入 `kaldifst`（有 Android 构建）。

**不要**一上来就上模型：ITN 是规则密集但规则有限的问题，规则版本比模型小 3 个数量级。

### 6.3 词级时间戳

Windows 用独立小模型 `wt.quantized.onnx` 算 ciphone 后验做强制对齐。
sherpa-onnx 的 Transducer 解码本身就带 token 时间，直接取 `result.timestamps` 即可，
不需要额外模型 —— **这是相对 Windows 的简化点**。

### 6.4 字幕稳定化（Windows 观感的关键，必须做）

Windows 字幕"不闪"不是模型好，是**改写策略**好。规则：

```kotlin
class CaptionStabilizer(private val maxLines: Int = 2) {
    private var committed: String = ""     // 已确定不再改的前缀
    private var volatile: String = ""      // 当前句的部分结果

    // 部分结果到达
    fun onPartial(text: String): Display {
        // 与上一次的部分结果求"公共前缀"，只追加不整体替换 → 避免整行闪烁
        volatile = text
        return Display(committed, volatile)
    }

    // 句结束
    fun onFinal(text: String) {
        committed = if (committed.isEmpty()) text else "$committed $text"
        volatile = ""
        // 超过 maxLines 行就滚掉最老的
    }

    // 用户停止说话 N 秒后，整段淡出
}
```

三条硬规则：
1. **只追加，不回退**：部分结果变短时保留旧文本，等 final 再整体校正；
2. **等宽/固定行高**：文字宽度变化会导致重排抖动，用 `fontFeatureSettings = "tnum"` + 固定行高；
3. **淡出而不是清空**：句末停留 1.5 s 再淡出，符合阅读节奏（Windows 也是这个时长量级）。

---

## 7. 实时翻译

Windows 用 SDK 内嵌的 `speech_translation_model`（硬件门控，只有 NPU 机型开）。
Android 上没有这条路，三选一：

| 方案 | 体积 | 质量 | 延迟（骁龙 8 系，句级） | 许可 | 结论 |
|---|---|---|---|---|---|
| **OPUS-MT (Marian) en↔zh，int8 量化** | 80~110 MB / 语向 | 中上（句子级不错，术语弱） | 60~150 ms | CC-BY-4.0 | ✅ **推荐起步** |
| NLLB-200-distilled-600M int8 | 550~700 MB | 好（200 语种） | 300~800 ms | CC-BY-NC-4.0 | ⚠️ 非商用许可，商用需换 |
| ML Kit 端侧翻译 | ~30 MB/语言 | 中（偏口语） | 30~80 ms | 闭源 | ⚠️ 快但不可控、不支持术语表 |
| 云端 API | 0 | 最好 | 网络往返 | — | ❌ 与"本地处理"的隐私卖点冲突 |

**工程建议**：
1. **流式翻译要有节制**。逐 token 翻译会抖得无法阅读。做法：**只在标点/句末触发翻译**，
   部分结果用"上一句译文 + 当前句草稿"两行显示，草稿用更淡的颜色；
2. **译文滞后一行**比"边译边改"体验好得多（Windows 也是这么做的）；
3. NMT 单独线程 + 输入队列长度 1（丢弃过期请求），否则会积压；
4. `ONNX Runtime` 用 `nnapi`/`qnn` EP 前必须先测：小模型在 NNAPI 上经常比 CPU 还慢。

---

## 8. 悬浮窗与排版

```kotlin
val params = WindowManager.LayoutParams(
    MATCH_PARENT, WRAP_CONTENT,
    if (Build.VERSION.SDK_INT >= 26) TYPE_APPLICATION_OVERLAY else TYPE_PHONE,
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_WATCH_OUTSIDE_TOUCH,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.BOTTOM
    // 避开手势条 / 刘海
    val insets = view.rootWindowInsets.getInsets(WindowInsets.Type.systemGestures() or WindowInsets.Type.displayCutout())
    y = insets.bottom + dp(8)
}
```

必须处理的坑：
- **`SYSTEM_ALERT_WINDOW` 需要用户手动授予**，且部分 ROM（MIUI/EMUI）有二次开关，要做引导页；
- **不能拦截触摸**（`FLAG_NOT_FOCUSABLE`），否则用户没法点下面的 App；
- **横屏/分屏**：监听 `onConfigurationChanged` 重新计算位置；
- **游戏全屏**：部分游戏会 `SYSTEM_UI_FLAG_HIDE_NAVIGATION`，悬浮窗仍可见但会盖住操作区，需要"可拖拽 + 半透明"。

样式直接照 Windows 的双轨设计：
- 预设主题（对应 `CurrentSelectedTheme` GUID）→ 一键切换；
- 高级：字体/字号/字色/背景不透明度/描边/最大行数。

---

## 9. 延迟预算（端到端，从声音到屏幕）

| 阶段 | Windows（推断/实测混合） | Android 目标 | 说明 |
|---|---|---|---|
| 采集缓冲 | 10~30 ms | 20~40 ms | `AudioRecord` 缓冲越小越抖 |
| 重采样 | 5 ms | 5 ms | 48k→16k |
| 推流块 | 100 ms（`SPEECH-MaxBufferSizeMs` 约束） | 100 ms | 可降到 60 ms，代价是 CPU 抖动 |
| 编码器 chunk | 160~320 ms（`chunk_size` × 40 ms） | 160~320 ms | Zipformer chunk 16~32 帧 |
| 解码 | 5~15 ms | 5~15 ms | 贪心 |
| 标点 | 20~40 ms | 20~40 ms（节流后） | |
| **ASR 小计** | **~300~500 ms** | **~300~500 ms** | 与 Windows 同量级 |
| 翻译（句级） | 未观测 | +60~150 ms | OPUS-MT int8 |
| 渲染 | 16 ms | 16 ms | |

**结论**：纯字幕可以做到 **首字 300~500 ms**，带翻译 **500~800 ms**。这就是用户能接受的甜点区。

---

## 10. 工程骨架

```
app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/
    │   ├── CMakeLists.txt
    │   └── jni_bridge.cpp           # sherpa-onnx OnlineRecognizer 封装
    ├── jniLibs/arm64-v8a/*.so       # sherpa-onnx 预编译
    └── java/…/
        ├── App.kt
        ├── ui/
        │   ├── MainActivity.kt      # 授权 / 模型管理 / 样式
        │   ├── CaptionOverlay.kt    # 悬浮窗 Composable
        │   └── StylePreset.kt
        ├── capture/
        │   ├── CaptureService.kt    # 前台服务
        │   ├── PlaybackSource.kt    # AudioPlaybackCapture
        │   ├── MicSource.kt
        │   └── Mixer.kt
        ├── asr/
        │   ├── AsrEngine.kt         # JNI 封装
        │   ├── RingBuffer.kt        # 无锁环形缓冲
        │   └── CaptionStabilizer.kt
        ├── nlp/
        │   ├── Punctuator.kt
        │   ├── Itn.kt
        │   └── Translator.kt        # ONNX Runtime + OPUS-MT
        └── model/
            └── ModelManager.kt      # 下载 / 校验 / 版本
```

`AndroidManifest.xml` 关键项：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<service
    android:name=".capture.CaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
```

---

## 11. 分阶段路线

| 阶段 | 交付 | 验收 |
|---|---|---|
| **M0 骨架**（2~3 天） | 前台服务 + AudioRecord + 录音落盘 | 能把系统声音录成 16k wav，播放正常 |
| **M1 纯字幕**（1 周） | sherpa-onnx 流式识别 + 悬浮窗 | 播放英文播客，字幕延迟 < 600 ms，连续 30 分钟不崩 |
| **M2 观感**（1 周） | 稳定化 + 标点 + 样式 + 双行滚动 | 主观"不闪、能读"，对标 Windows 截图效果 |
| **M3 翻译**（1~2 周） | OPUS-MT en→zh + 双行对照 | 句级译文延迟 < 800 ms，BLEU 主观可读 |
| **M4 打磨** | 模型管理器、热词、省电、机型适配矩阵 | 中低端机 RTF < 0.5，8 小时耗电 < 15% |

**先别做的**：多语种自动识别（LID）、说话人分离、词级高亮、麦克风混音。
Windows 有这些，但它们的边际收益远低于"稳、快、不闪"。

---

## 12. 风险与合规

| 风险 | 等级 | 说明与对策 |
|---|---|---|
| **模型许可** | 🔴 高 | 微软权重不可用。选 sherpa-onnx 时必须逐个核对**上游训练数据许可**（部分中文 zipformer 的上游未声明许可，商用前必须确认） |
| **采集限制导致"看起来不工作"** | 🟠 中 | 大量 App 拒绝被采集。必须做静音检测 + 明确文案，否则会被当 bug |
| **功耗/发热** | 🟠 中 | 模型常驻 + 双线程推理。对策：息屏即停、无音频 30 s 自动卸载模型 |
| **悬浮窗权限被 ROM 拦截** | 🟠 中 | MIUI/EMUI/ColorOS 各有二次开关，需分机型引导 |
| **回声/重复字幕** | 🟡 低 | 麦克风通路加门控 |
| **Play 商店政策** | 🟠 中 | 采集+悬浮窗属于敏感权限组合，上架需说明用途；无障碍 API 滥用会被拒 |

---

## 13. 下一步（我可以直接动手的部分）

1. 生成可直接 `./gradlew assembleDebug` 的 Android 工程骨架（M0+M1 打通）；
2. 把 `CaptionStabilizer` / `RingBuffer` / `ITN 规则表` 写成可单测的 Kotlin 模块；
3. 写模型下载与校验（SHA256 + 断点续传 + 多源）；
4. 若你机器上补装 Ghidra，我可以补跑 `CaptionBackend` 的伪代码，把 `chunk_size` 默认值、
   `SPEECH-MaxBufferSizeMs` 实值、`TransformerEncoderMaxChunks` 语义这三处"未观测"补成"已观测"。
