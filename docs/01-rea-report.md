# Windows 11「实时辅助字幕 / 实时翻译」逆向分析报告

> 目标：Windows Live Captions（设置 → 辅助功能 → 字幕 → 实时辅助字幕）
> 目的：为 Android 端复刻/移植提供可落地的技术依据
> 分析机：Windows 11 企业版 25H2，Build 10.0.26200.9711（x64）
> 分析日期：2026-10-26

---

## 0. 结论速览（TL;DR）

1. **它不是"Windows 自带语音识别"**。这套东西的识别内核是 **微软 Azure 认知服务 Speech SDK 的「嵌入式（offline / embedded）语音」分支**，随系统预置在 `Client.Core` 包内，用 **ONNX Runtime** 在本地跑模型。整套组件里没有任何网络请求用于识别；音频不出本机。
2. **识别模型是流式 RNN-T（Transducer）**：`encoder.onnx`（24 层 Transformer + conv 子采样 + KV/CNN cache）→ `decoder.onnx`（2 层 LSTM predictor）→ `joint.onnx`（4002 词表 BPE）。**每 40 ms 出一个 token 帧**，mel 特征 80 维 / 25 ms 窗 / 10 ms 移。
3. **音频来源是 WASAPI「进程回环」**：`MMDevAPI.dll!ActivateAudioInterfaceAsync`（序号 17，已用导出表核实）+ `VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK`，所以它能给任意 App 的声音配字幕；麦克风是可选叠加项。
4. **后处理链**：VAD（独立 svad 模型，880 ms 窗）→ 标点（cappunc.onnx，34 MB）→ ITN（数字/单位/日期正规化，int8 + 词表）→ 大小写 → 词级时间戳（独立小 encoder，输出 80 个 ciphone 后验）。
5. **模型以 Appx「语言包」形式分发**：`MicrosoftWindows.Speech.<locale>.N`，通过 AppExtension `com.microsoft.speech.model.1` 被枚举；用户第一次开字幕时才下载。**翻译模型另有一套包，且被 NPU 硬件能力门控**（`CopilotPlusPCDetection.h` / `npu_detection.h` / `NoTranslationModelsForHardware`）。
6. **对安卓移植最关键的一句话**：架构可以 1:1 照搬（流式 Transducer + VAD + 标点 + NMT + 悬浮窗），**但权重不能搬** —— 模型文件头部就写着禁止再分发/反向工程。落地必须换成开源权重（sherpa-onnx / Silero / OPUS-MT 等），详见 `02-android-port-plan.md`。

---

## 1. 分析范围与方法

| 手段 | 用途 | 结果 |
|---|---|---|
| 文件系统枚举 | 定位组件与模型包 | 见第 2 节清单 |
| PE 导入表 / 延迟导入表 | 还原模块间依赖与调用的 API 面 | 得到 85 个 Speech SDK C API |
| PE 导出表（REA `inspect_native_exports`） | 确认 SDK 身份、确认 `MMDevAPI` 序号 17 | 见第 4.1 节 |
| 字符串 + RTTI 修饰名 | 还原类名、源文件名、配置键、错误分支 | 得到 8 个 .cpp、多组注册表键 |
| ONNX 图头解析 | 还原张量契约（名称/类型/形状） | 见第 6.2 节 |
| 模型包配置文本 | 还原解码器全部超参 | `sr.ini` / `model_onnx.config` / `v10_model_info.json` |
| 注册表实测 | 还原用户态设置项 | 见第 8 节 |

**没做/做不到的**：REA 的原生反编译 provider（Ghidra）在本机未配置，`binary_overview` 返回 `provider_unavailable`。本报告结论来自**静态字符串/RTTI/导入导出表 + 模型包与配置本体 + 注册表**，均属"观测"级证据；凡属推断处均显式标注。若需要伪代码级确认，装好 Ghidra 后可补跑（`F:\AI coding4\REA\ghidra_12.1.4_PUBLIC` + `ghidra-env.sh`）。

---

## 2. 组件清单（实测）

### 2.1 入口存根

```
C:\Windows\System32\LiveCaptions.exe                     180,224 B
  导入：AddDependencyToProcessPackageGraph (api-ms-win-appmodel-runtime-internal-l1-1-7)
  清单名：Microsoft.Windows.EndUser.LiveCaptions
  字符串：WindowsUdk.UI.Shell.LiveCaptionsExtension / ExtensionFactory
```
**观察**：这是一个"包依赖图存根"，只负责把 `MicrosoftWindows.Client.Core` 包挂进当前进程再转交真正的实现，本身不含识别逻辑。

### 2.2 应用层（`MicrosoftWindows.Client.Core_cw5n1h2txyewy`）

| 文件 | 大小 | 职责（由字符串/导入推断） |
|---|---|---|
| `LiveCaptionsXamlApplication.dll` | 69,120 | WinUI3 应用宿主 |
| `LiveCaptions.dll` | 621,568 | 全部页面与 UI 逻辑：`CaptionsPage` / `ConsentPage` / `DownloadPage` / `InstallProgressPage` / `SelectALanguageToCaptionPage` / `LiveCaptionsWindowContent` / `RegistryUtil` |
| `LiveCaptionsDesktop.dll` | 167,936 | 桌面宿主窗口：`SHAppBarMessage`（贴边停靠）、`DwmSetWindowAttribute`（圆角/材质）、`MonitorFromWindow`、GDI 背景 |
| `LiveCaptionsBackendDll.dll` | 536,064 | **识别后端**（非 UI）。sha256 `7c4a1d49…c8bcfb` |

后端内部源文件名（来自 `__FILE__` 断言字符串，可直接当模块划分看）：

```
Src\Components\LiveCaptions\lib\CaptionBackend.cpp          ← 状态机/识别器生命周期
Src\Components\LiveCaptions\lib\RecognizerManager.cpp       ← 识别器创建与换模型
Src\Components\LiveCaptions\lib\SpeechModelManager.cpp      ← 模型包发现/安装/许可
Src\Components\LiveCaptions\lib\AudioSession.cpp            ← 采集会话
Src\Components\LiveCaptions\lib\AudioSessionScreenReader.cpp ← 读屏器音频分支
Src\Components\LiveCaptions\lib\AudioCaptureClientReader.cpp ← 回环音频读取线程
Src\Components\LiveCaptions\lib\RegistryUtil.cpp            ← 设置持久化
Src\Components\LiveCaptions\inc\LiveCaptionsHelpers.h
Src\SrcPkg\NPUDetection\inc\npu_detection.h                 ← 翻译模型的硬件门控
Src\SrcPkg\NPUDetection\inc\CopilotPlusPCDetection.h
```

### 2.3 语音引擎层（`…\LiveCaptions\`）

| 文件 | 大小 | 说明 |
|---|---|---|
| `Microsoft.CognitiveServices.Speech.core.dll` | 2,398,752 | Azure Speech SDK C API，**585 个导出**（REA 导出表） |
| `Microsoft.CognitiveServices.Speech.extension.embedded.sr.dll` | 289,352 | 嵌入式识别扩展入口（仅导出 `CreateModuleObject`） |
| `Microsoft.CognitiveServices.Speech.extension.embedded.sr.runtime.dll` | 5,213,256 | **真正的解码器**：RNN-T 搜索、VAD 调度、ITN、标点、词对齐 |
| `Microsoft.CognitiveServices.Speech.extension.onnxruntime.dll` | 8,321,096 | ONNX Runtime 构建 |
| `Microsoft.CognitiveServices.Speech.extension.audio.sys.dll` | 160,328 | Windows 音频后端（`ActivateAudioInterfaceAsync`） |
| `Microsoft.CognitiveServices.Speech.extension.telemetry.dll` | 1,582,152 | 遥测（仅指标，不含音频） |
| `Microsoft.CognitiveServices.Speech.extension.lu.dll` | 387,616 | 语言理解（本场景基本未用） |

### 2.4 模型包

```
C:\Program Files\WindowsApps\MicrosoftWindows.Speech.en-US.1_1.0.28.0_x64__cw5n1h2txyewy\
  AppxManifest.xml   → AppExtension Name="com.microsoft.speech.model.1"  Properties/LocaleId=en-US
```

包内文件（按体积）：

| 文件 | 大小 | 作用 |
|---|---|---|
| `encoder.onnx` | 108,404,764 | 流式 Transformer 编码器（24 层 / 8 头 / 512 维） |
| `punct\cappunc.onnx` | 34,216,888 | 标点+分段模型（445 节点） |
| `lid_quantized.onnx` | 21,280,833 | 语种识别（输出 `[N,2]`） |
| `decoder.onnx` | 15,796,296 | LSTM predictor |
| `itn\itntag.quant.onnx` | 3,600,295 | ITN 实体打标（17 个标签） |
| `itn\itn.bin` | 3,156,286 | ITN WFST |
| `joint.onnx` | 2,067,587 | 联合网络（4002 词表） |
| `wt.quantized.onnx` | 1,960,948 | 独立词级时间戳模型 |
| `svad_init.quantized.onnx` | 1,357,221 | 流式 VAD |
| `6000_lexicon.prelt` | 538,442 | 发音词典前缀树 |
| `tokens.list` | 27,853 | 4002 个 BPE token |
| `prongen\*` | ~220 KB | 发音生成（LTS/G2P：`L1033.phn` / `che1033.lxa` / `lsr1033.lxa` / `tn1033.bin`） |
| `en-US_*capitalizationRules.enc` | ~295 KB | 大小写规则（uni/bi/tri-gram） |
| `en-us_ProfanityList.enc` | 22,903 | 脏话过滤词表 |
| `model_onnx.config` / `sr.ini` / `v10_model_info.json` / `lp.config` | < 4 KB | **模型契约与解码超参（见第 6 节）** |

总占用 ≈ **195 MB / 语种**。

---

## 3. 进程与调用链

```
LiveCaptions.exe (System32, 存根)
  └─ AddDependencyToProcessPackageGraph("MicrosoftWindows.Client.Core")
      └─ LiveCaptionsXamlApplication.dll        [WinUI3 App]
          ├─ LiveCaptionsDesktop.dll            [顶层窗口 + AppBar 停靠 + DWM]
          │     └─ LiveCaptions.dll             [页面/设置/字幕控件]
          └─ (WinRT 激活) LiveCaptionsBackend.CaptionBackend
                └─ LiveCaptionsBackendDll.dll
                      ├─ SpeechModelManager  → AppExtension 枚举 com.microsoft.speech.model.1
                      ├─ AudioSession        → ActivateAudioInterfaceAsync(进程回环) → PCM
                      ├─ RecognizerManager   → embedded_speech_config_* → recognizer_create_*
                      └─ CaptionRecognizer   → push_audio_input_stream_write(PCM)
                            └─ Microsoft.CognitiveServices.Speech.core.dll
                                  └─ extension.embedded.sr(.runtime).dll
                                        └─ extension.onnxruntime.dll
                                              └─ encoder/decoder/joint/svad/punct/wt/itn
```

WinRT 类名（观测）：`LiveCaptionsBackend.CaptionBackend`、`LiveCaptionsBackend.AudioSession`、
`LiveCaptionsBackend.CaptionRecognitionEventArgs`、`LiveCaptionsBackend.ModelUpdateStatus`、
`BackendError` 枚举、`BackendException`。

---

## 4. 音频通路

### 4.1 采集：WASAPI 进程回环

证据链（三条独立证据互相印证）：

1. `LiveCaptionsBackendDll.dll` 导入 `MMDevAPI.DLL` **唯一一个符号，且是序号 `#17`**。
2. REA `inspect_native_exports` 打开 `C:\Windows\System32\MMDevAPI.dll`：**ordinal 17 = `ActivateAudioInterfaceAsync`**（`evidence ev_66e29d75…`）。
3. 后端字符串字面量：`Process-loopback activation timed out`。

`ActivateAudioInterfaceAsync` + `VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK` 是 Windows 10 2004 之后
"抓取指定进程组音频"的唯一官方入口。**推论**：Live Captions 按 PID/进程树抓声，而不是抓整机混音，
所以它能做到"给某个 App 配字幕"，也解释了它不需要管理员权限。

`Microsoft.CognitiveServices.Speech.extension.audio.sys.dll` 同样只导入 `MMDevAPI #17`，
说明 SDK 自带的 Windows 音频后端与 App 走同一条路。

### 4.2 数据格式与投递

- 后端调用 `audio_stream_format_create_from_default_input` + `audio_stream_create_push_audio_input_stream`，
  即 **App 侧采集、SDK 侧消费**（不是 `from_default_microphone`，这条 API 虽被导入但不在本路径上）。
- `push_audio_input_stream_write` 逐块写入。
- 属性键 `SPEECH-MaxBufferSizeMs` 存在 → 用来限制 SDK 内部缓冲时长以压低端到端延迟（**推断**，值未观测到）。
- 模型声明支持采样率 `[8000, 16000]`（`v10_model_info.json`），mel 80 维、帧移 10 ms、token 帧 40 ms。
- 所以内部一定是 **16 kHz / 单声道 / PCM16** 重采样后送入。

### 4.3 麦克风与读屏器

设置项字符串：`SettingsIncludeMicrophoneAudio`、`SettingsExcludeScreenReaderAudio`，
配合 `AudioSessionScreenReader.cpp`：
- 默认只抓系统声音；
- 可勾选"包含麦克风"（把麦克风流混进同一条 push stream）；
- 有专门分支处理"读屏器（Narrator）自己发声"导致的自我回声，可排除。

**Android 对照**：`AudioPlaybackCapture` 只能抓"允许被采集"的 App，读屏器（TalkBack）声音同样会回灌，
需要同样的排除策略。

---

## 5. 识别流水线（从 SDK API 面还原）

后端对 Speech SDK 的**完整调用面（85 个延迟导入，实测）**，按生命周期排序即为流水线：

```
① 配置与模型选择
   embedded_speech_config_create
   embedded_speech_config_add_path(modelDir)
   embedded_speech_config_get_num_speech_reco_models / get_speech_reco_model
   embedded_speech_config_set_speech_recognition_model
   （翻译路径）embedded_speech_config_get_num_speech_translation_models
             / get_speech_translation_model / set_speech_translation_model
   speech_config_get_property_bag → property_bag_set_string("SPEECH-MaxBufferSizeMs", …)

② 音频注入
   audio_stream_format_create_from_default_input
   audio_stream_create_push_audio_input_stream
   audio_config_create_audio_input_from_stream

③ 语种
   create_auto_detect_source_lang_config_from_languages（多语种候选）
   auto_detect_source_lang_config_get_property_bag

④ 识别器
   recognizer_create_speech_recognizer_from_auto_detect_source_lang_config   ← 只转写
   recognizer_create_translation_recognizer_from_config                      ← 转写+翻译

⑤ 回调
   recognizer_session_started_set_callback / session_stopped_set_callback
   recognizer_speech_start_detected_set_callback / speech_end_detected_set_callback
   recognizer_recognizing_set_callback      ← 中间结果（字幕"打字机"效果）
   recognizer_recognized_set_callback       ← 稳定结果
   recognizer_canceled_set_callback

⑥ 结果
   result_get_text / result_get_offset / result_get_duration / result_get_channel
   result_get_reason / result_get_reason_canceled / result_get_canceled_error_code
   translation_text_result_get_translation / translation_text_result_get_translation_count

⑦ 收尾
   recognizer_stop_continuous_recognition_async(_wait_for)
   recognizer_handle_release / property_bag_release / audio_config_release
```

**要点**：
- 用的是 **continuous recognition + push stream**，不是 `recognize_once`；
- **`recognizing`（部分结果）驱动 UI**：这就是 Windows 字幕那种"边说边冒字、回退修正"的来源；
- `result_get_offset` / `result_get_duration` 是音频时间轴（100 ns 单位），用于字幕行的时间对齐与过期清理；
- 翻译走 `TranslationRecognizer`，**同一遍前向**同时拿到原文与译文（不是先转写完再喂 NMT，
  至少 SDK 层面是这样暴露的；内部实现是否复用 encoder 未观测）。

---

## 6. 模型契约（核心资产，已完整解出）

### 6.1 `model_onnx.config`

```ini
# quantized model is used in this config.
ModelType=ONNX_TRANSFORMER_ENCODER
ModelEncoder=encoder.onnx
ModelPredictor=decoder.onnx
ModelJoint=joint.onnx
TransformerEncoderMaxChunks=8
ModelLID=lid_quantized.onnx
```

### 6.2 张量接口（实测，非推断）

**encoder.onnx**（5,791 节点，1,171 个 initializer，动态 int8 量化）

| 方向 | 名称 | 类型 | 形状 | 含义 |
|---|---|---|---|---|
| in | `cache_frames` | float32 | `[chunk_size, 80]` | 本块 80 维 log-mel |
| in | `inp_cache_k` | float32 | `[24, 1, 8, hist_size, 64]` | 24 层 × 8 头 × 64 维 KV cache |
| in | `inp_cache_v` | float32 | `[24, 1, 8, hist_size, 64]` | 同上 |
| in | `inp_cache_cnn` | float32 | `[24, 1, 512, 2]` | 每层卷积子采样 2 帧历史 |
| out | `hidden_state` | float32 | `[T, 512]` | 编码器输出（40 ms/帧） |
| out | `oup_cache_k/v` | float32 | `[24,1,8,·,64]` | 回写 cache |
| out | `oup_cache_cnn` | float32 | `[24,1,512,2]` | 回写 cache |
| out | `oup_lid` | float32 | `[T, 512]` | 语种识别特征 |

**joint.onnx**：`enc_hidden[1,512]` ⊗ `dec_hidden[1,512]` → `joint_output[4002]`

**decoder.onnx**：`prev_label int64[1,1]` + `h/c[2,1,1024]` → `hidden[1,1,512]` + 新 `h/c`
→ **2 层 LSTM，隐层 1024，投影到 512**

**lid_quantized.onnx**：`enc_hidden[T,512]` → `language_prob[N,2]`

**wt.quantized.onnx**（词级时间戳）：3 层 / 4 头 / 32 维的小编码器，
`hidden[·,128]` → `oup_ci[·,80]`（80 个上下文音素后验，做强制对齐）

**svad_init.quantized.onnx**：`features[1,880]`（88 帧 × 10 ms = 880 ms 窗）→ 语音概率 `[1,3]`

**cappunc.onnx**：`seq int64[B,T]` → `output[3,B,T]`（3 类：无/逗号/句号类）

**itntag.quant.onnx**：`tokens[T]` + KV cache → 17 类实体标签

### 6.3 `sr.ini`（解码全部超参，实测原文）

```ini
spec-type=rnnt1                 # RNN-T 解码器
model-config-path=model_onnx.config
token-path=tokens.list
beam-width=3                    # 束搜索宽度 3
beam-sort-kind=1
score-norm-kind=0
feature-dim=80                  # 80 维 log-mel
feature-stride=80
ms-per-frame=40                 # 每 40 ms 输出一帧 token
biasing-weight=1.1              # 短语/自定义词加权
biasing-lexical=1

# VAD
vad-model-path=svad_init.quantized.onnx
vad-mode=segmentation
vad-threshold=0.6
vad-segmentation-threshold=0.35
vad-ms-per-frame=10
vad-rewind-frames-count=75

# 语种识别（默认关闭，模型在位）
lid-enabled=0
lid-threshold=0.90
lid-mode=1

# ITN：2 = 中间结果和最终结果都做
itn-mode=2
itn-models=itn/itn.bin
itn-tag-model=itn/itntag.quant.onnx

# 格与 N-best
use-lattice=1
context-size=5
nbest-size=100

# 标点 / 大小写
punctuation-model-path=punct/cappunc.onnx
punctuation-sp-vocab-path=punct/spm_8k.vocab   # SentencePiece 8k
do-punct-processing=true
is-semantic-seg-supported=true                 # 语义分段（换行/断句）

# 词级时间戳
independent-word-timing-model-path=wt.quantized.onnx
use-ciphonescore-size=1

license-version=1                # 与模型内嵌许可文本联动
```

### 6.4 `v10_model_info.json`

```json
{
  "ModelName": "embedded_en-unified_e2e_streaming_compliance_V10",
  "EngineType": "EndToEnd",
  "Usage": "Streaming",
  "ModelType": "CT",
  "SupportedSamplingRate": [8000, 16000],
  "ModelOutput": "Lexical",
  "ModelTask": ["SpeechRecognition"],
  "IsSemanticSegSupported": true,
  "ModelVersion": 5
}
```

### 6.5 模型文件受许可保护（重要法务约束）

每个 `.onnx` 的**文件头就是一段许可文本**，逐字如下（`encoder/decoder/joint/svad/punct/wt/lid` 全部一致）：

> This model and the software may not be used or distributed in any manner except as authorized
> under a valid written agreement, using the reference number 2774316. … You may not work around any
> technical limitations in the model or the software, reverse engineer, decompile, or disassemble the
> model or the software; … or, share, publish, rent, or lease the model or software, or provide the
> model or software as a standalone solution for others to use.

**结论**：架构可以照搬，**权重一个字节都不能搬**。Android 端必须用开源权重重建（见移植方案）。

---

## 7. 翻译（实时翻译）路径

**观测到的证据**：

| 证据 | 位置 |
|---|---|
| `recognizer_create_translation_recognizer_from_config` | 后端延迟导入 |
| `translation_text_result_get_translation(_count)` | 同上 |
| `embedded_speech_config_get/set_speech_translation_model`、`speech_translation_model_get_source_languages / target_languages` | 同上与 core.dll 导出表 |
| `LiveTranslation_props`、`m_translationLocales`、`NoTranslationModelsForHardware`、`m_hardwareTranslationCapabilities` | 后端字符串 |
| `SrcPkg\NPUDetection\inc\npu_detection.h`、`CopilotPlusPCDetection.h` | 后端字符串 |
| UI 字符串 `SelectALanguageToCaptionPage`、`ReadyToCaptionTranslateText`、`Translation` | `LiveCaptions.dll` |

**推论**：
1. 翻译也是 **SDK 内的嵌入式翻译模型**（`speech_translation_model_*`），随"翻译语言包"下载；
2. **是否允许翻译由硬件能力表决定**：本机没有安装任何翻译模型包（`Get-AppxPackage` 只有 `MicrosoftWindows.Speech.en-US.1`），后端字符串里明确有 `NoTranslationModelsForHardware` 分支 → 与"仅 Copilot+ PC / 带 NPU 的机型开放翻译"的公开策略一致；
3. `speech_translation_model_get_source_languages / get_target_languages` 说明翻译模型的语向是**模型自描述**的（一个包只覆盖有限语向），不是任意语对。

**未观测**：翻译模型包的具体文件名、张量形状、是否与 ASR 共享 encoder。要补齐需要一台带 NPU 且已启用翻译的机器。

---

## 8. 用户态设置（注册表实测）

```
HKCU\SOFTWARE\Microsoft\LiveCaptions\UI
    HasUserConsented   = 1
    CaptionLanguage    = en-US
    WindowPosition     = 15          # 停靠位置枚举
    LiveCaptionWidth   = 1824
    LiveCaptionHeight  = 80
    xPosition = 25, yPosition = 1103

HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\ClosedCaptioning   # 复用旧的字幕样式
    CaptionColor=2  CaptionOpacity=1  CaptionSize=2  CaptionFontStyle=4
    CaptionEdgeEffect=4  BackgroundColor=3  BackgroundOpacity=4
    RegionColor=1  RegionOpacity=4  CurrentSelectedTheme={C2A8F759-…}
```

UI 层样式键（`LiveCaptions.dll` 字符串）：`CaptionFontColor`、`CaptionFontOpacity`、
`CaptionUiGridBackgroundColor`、`CaptionInfoGridBackgroundOpacity`、`HostWindowBorderColor`、
`BackgroundColor`、`FontFamily`、`FontSize`、`FontWeight`、`SettingsWindowDock`。

**推论**：Windows 11 的字幕样式是"**旧 ClosedCaptioning 索引 + 新 UI 主题**"双轨制；
停靠（`SHAppBarMessage`）= 告诉系统"这块屏幕区域被我占了"，其他窗口最大化时会让位 ——
这点在 Android 上**没有等价物**（见移植方案的风险项）。

---

## 9. 隐私与遥测

- 官方文案与实测一致：模型与推理全在本地，音频不上云（组件内无识别相关网络依赖，
  `extension.telemetry.dll` 只做指标上报）。
- 遥测事件名（后端字符串）：`LiveCaptionsModelInstallTest`、`LiveCaptionAudioToTextLatency(V2)`、
  `LiveCaptionsCapturedAndRendered`、`reason::noAudioStreamDetected` / `likelyNoSpeechInAudio` /
  `audioDectedAndTextRecognizedSuccess` 等；字段含 `audioDetectedTime`、`textRecognizedTime`、
  `audioToTextLatency`、`avgLatencyInMs`、`installProgress`、`installErrorCode`。
- **可复用的一点**：它的埋点定义了一套很实用的质量指标（首字延迟 / 平均延迟 / 是否检测到音频 /
  是否产出文本），移植时建议直接照抄这套指标口径。

---

## 10. 证据索引

| ID | 内容 |
|---|---|
| `ev_802453c1…` | `Speech.core.dll` 导出表（585 项），证明 Azure Speech SDK C API 身份与 embedded 系列 API |
| `ev_d301f93d…` | `extension.embedded.sr.dll` 导出表（仅 `CreateModuleObject`） |
| `ev_66e29d75…` | `MMDevAPI.dll` 导出表，**ordinal 17 = ActivateAudioInterfaceAsync** |
| 本地文件 | `build/backend_imports.txt`（导入+延迟导入全文） |
| 本地文件 | `build/backend_strings.txt`（2,188 条字符串） |
| 本地文件 | `build/emsr_runtime_strings.txt`（解码器内部字符串） |
| 本地文件 | `build/ui_strings.txt`（UI 字符串） |
| 工具 | `tools/pe_probe.py`（导入/导出/字符串）、`tools/onnx_io.py`（ONNX 图头解析） |

---

## 11. 未解问题（诚实清单）

1. `chunk_size` 的**默认值**未观测（模型是动态维度），影响端到端延迟；需动态调试或读死代码确认。
2. `SPEECH-MaxBufferSizeMs` 的实际取值未观测。
3. 翻译模型包的内部结构（文件名/张量/是否共享 encoder）未观测 —— 本机无翻译包。
4. `TransformerEncoderMaxChunks=8` 的语义（是"注意力窗口 = 8 块"还是"最多缓存 8 块"）需反编译确认。
5. 后端 `CaptionBackend` 状态机的精确状态迁移未反编译（本机 Ghidra provider 未就绪）。
