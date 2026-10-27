# 实时字幕 LiveCaption（Android）

Windows 11「实时辅助字幕」的安卓复刻：**抓系统播放的声音 → 本地流式识别 → 半透明悬浮窗显示字幕**。
全离线、不联网、不上传音频。

> 逆向分析过程见 `docs/01-rea-report.md`，移植设计见 `docs/02-android-port-plan.md`，
> 模型选型见 `docs/03-model-selection.md`。

---

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 前台服务 + 回环采集 | ✅ 完成 |
| M1 | 流式识别 + 悬浮窗字幕 | ✅ 完成（本仓库） |
| M2 | 标点 / 行内对齐 / 观感打磨 | ✅ 完成（规则标点，见下） |
| M3 | 机器翻译（原文 + 译文双行） | ⬜ 待做 |

## 已验证（Pixel 6 模拟器 · Android 16 / API 36）

| 项 | 结果 |
|---|---|
| 构建 | `assembleDebug` 通过，APK 74 MB（arm64-v8a + x86_64） |
| 界面 | 正常渲染，无崩溃（`docs/screens/01-home.png`） |
| 模型下载 | 4 个文件共 189 MB，下载完成后 SHA-256 全部校验通过 |
| 前台服务 | `isForeground=true`，`types=0x00000020`（MEDIA_PROJECTION） |
| 录屏授权 | 系统弹窗 → 选择应用 → 授权成功 |
| 悬浮窗 | 半透明圆角字幕条正常浮在目标应用之上（`docs/screens/04-overlay.png`） |
| 静音检测 | 采到静音 3 秒后提示「未检测到音频，该应用可能禁止被录制」 |
| 大小写切换 | 原样显示 `THIS IS HOW...`，句首大写显示 `This is how...`（`05` / `09`） |
| 拖动调整 | 调整模式下拖动字幕条，位置持久化（`06` / `07`） |
| 行数 | 1–8 行可调，实测 6 行（`08`） |
| 实时生效 | 运行中改样式，字幕条立即变化，无需重启字幕 |
| 横竖屏 | 竖屏→横屏字幕条不丢失，仍在屏幕内；横屏可左右拖动、可拉到整屏宽（`10` / `11`） |
| 解锁握把 | 自动锁定后右上角出现点阵握把，点击回到调整模式（`12`） |
| 左对齐 | 字幕从左侧开始向右生长，已出现的字不再左右跳动（`14` / `16`） |
| 自动标点 | 7 组用例全部通过：`THIS IS A TEST.` / `WHAT ARE YOU DOING?` / `你好吗？` / 停顿与连词补逗号 |
| 双模型 | 模型列表可下载 / 切换；X-ASR（自带标点）实测下载解压 + 加载成功（`17` / `18`） |
| 单轴缩放 | 拖右边只改宽度、拖下边只改高度，互不影响（`19` / `20`） |

> 说明：模拟器以 `-no-audio` 启动，没有真实音频输入，所以**识别出字**这一段没能在模拟器上跑。
> 需要在真机上播放一段中英混说的音频来验证端到端延迟与准确率。

## 字幕样式

| 项 | 说明 |
|---|---|
| 字号 | 13–29 sp |
| 背景不透明度 | 0–100% |
| 保留行数 | 1–8 行；文字从底部对齐，超出部分从**顶部**裁掉，永远显示最新的内容 |
| 距底部 | 未手动拖过位置时，控制字幕条离屏幕底部的距离 |
| **大小写** | 模型的词表是**全大写**的（LibriSpeech 风格），所以默认做「句首大写」还原；可切「原样 / 全小写」 |
| 调整位置和大小 | 打开后字幕条出现蓝色边框：**中间拖动移动 · 右边拉宽 · 下边拉高**；双指只调宽度。**30 秒无操作自动锁定** |
| 解锁握把 | 锁定状态下字幕条右上角常驻一个点阵小圆钮，点它直接进入调整模式，不用切回 App（`12` / `13`） |
| 横竖屏 | 位置按**占可移动范围的比例**存储，竖屏拖到 80% 处切横屏仍在 80% 处，且一律钳进屏幕内（`10` / `11`） |
| 行内对齐 | **左对齐**。居中会让每来一个新词整行重新居中，已出现的字左右乱跳，读字幕很难受 |
| 自动标点 | 句末补标点 + 长停顿补逗号 + 疑问句识别，可在设置里关（`15`） |


---

## 快速开始

### 1. 构建

```bash
# 需要 JDK 17 + Android SDK（platform 35 / build-tools 35）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

如果机器上没有 Gradle wrapper，用本机 Gradle 8.13+ 直接构建：

```bash
gradle assembleDebug
```

### 2. 安装并授权

App 里有三个开关，必须全绿才能开始：

1. **录音权限** —— 系统音频采集的硬性要求（`RECORD_AUDIO`）
2. **通知权限** —— 前台服务必须有常驻通知
3. **悬浮窗权限** —— 显示字幕条；国产 ROM 可能还有一层"后台弹出界面"开关

### 3. 下载模型

首次进来「识别模型」卡片里有两个可选模型，点右侧按钮下载，下完点「使用」切换：

| 模型 | 体积 | 特点 |
|---|---|---|
| **Zipformer 中英双语** | 198 MB | 已验证的默认模型；词表**全大写**、**不带标点**（标点靠规则补） |
| **X-ASR 中英双语 · 自带标点** | 168 MB | 识别时**直接输出 ，。？！**；词表小写，体积更小 |

两个都是 Apache-2.0。模型不会打进 APK，运行时下载到
`/data/data/com.translate.livecaption/files/models/`，逐文件 SHA-256 校验
（X-ASR 是 tar.bz2 整包分发，App 内解压后同样逐文件校验）。

- Zipformer 首选源：ModelScope（国内快），回落 Hugging Face
- X-ASR 首选源：`gh-proxy.com` 镜像，回落 GitHub 直连

### 4. 开始字幕

点「开始字幕」→ 系统弹录屏授权 → 允许 → 播放任何音视频 → 字幕条出现在屏幕底部。

---

## 目录结构

```
app/src/main/java/com/translate/livecaption/
├── App.kt                        通知渠道
├── MainActivity.kt               授权 / 模型 / 样式 / 起停
├── Prefs.kt                      样式持久化
├── capture/
│   ├── CaptureService.kt         前台服务：采集 + 识别 + 悬浮窗编排
│   └── CaptionBus.kt             进程内状态总线
├── asr/
│   ├── AsrEngine.kt              sherpa-onnx 流式 Transducer 封装
│   ├── RingBuffer.kt             采集线程 ↔ 识别线程 无锁环形缓冲
│   └── CaptionStabilizer.kt      字幕稳定化（只追加不回退）
├── overlay/
│   └── CaptionOverlay.kt         半透明悬浮窗
└── model/
    └── ModelManager.kt           模型下载 / 校验 / 定位
```

---

## 标点是怎么做的

**没有引入额外模型**，用的是规则 + 识别器已有的逐 token 时间戳：| 规则 | 依据 | 例子 |
|---|---|---|
| 句末补 `。` / `.` | VAD 端点切出来的一段就是一句 | `今天天气不错。` |
| 疑问句补 `？` / `?` | 中文看句尾（吗/呢/嘛）或疑问词；英文看**句首**（what/why/do/is…） | `WHAT ARE YOU DOING?` → `What are you doing?` |
| 长停顿补 `，` | 相邻 token 起始时间差 ≥ 0.45 s | `THIS IS, A TEST.`（IS 后停了 0.7 s） |
| 连词前补 `，` | 句长 ≥ 12 词且没有停顿逗号时兜底 | `… SOME MILK, BUT THEY …` |

为什么不上模型：sherpa-onnx 现成的中英标点模型（CT-Transformer）是 **294 MB**，
叠在 198 MB 的识别模型上就是近 500 MB 包体。而我们缺的主要是「句末符号」和「停顿处逗号」，
这两件事用时间戳就能做，成本为零。

**但规则标点的上限不高**（长句不给中间逗号、问句靠关键词猜）。所以更彻底的办法是做在识别端：
选择「X-ASR 中英双语 · 自带标点」后，模型在解码时直接吐出 `，。？！`，此时规则标点自动关闭
（`Punctuator` 只在 `nativePunct = false` 的模型上生效）。**这是目前标点效果最好的路径。**




| Windows（实测） | 本项目 |
|---|---|
| `ActivateAudioInterfaceAsync` + 进程回环 | `AudioPlaybackCaptureConfiguration` + `AudioRecord` |
| `push_audio_input_stream` 推流 | `RingBuffer` → `OnlineStream.acceptWaveform` |
| `encoder/decoder/joint.onnx`（RNN-T） | `OnlineRecognizer` 的 transducer 三件套 |
| `sr.ini`：80 维 mel / 10 ms 帧移 / 40 ms 出帧 | `FeatureConfig(16000, 80)`，出帧由模型决定 |
| `svad_init` + 端点检测 | `EndpointConfig` + `isEndpoint()` |
| `cappunc.onnx` 标点 | ⬜ M2 接入 `OnlinePunctuation` |
| `itn.bin` 逆向文本规整 | ⬜ M2 用规则实现 |
| WinUI3 + DWM 悬浮条 | `TYPE_APPLICATION_OVERLAY` + 圆角渐变底 |
| `HKCU\...\LiveCaptions\UI` | `SharedPreferences` |
| Appx 语言包按需下载 | `ModelManager` 按需下载 + 校验 |

---

## 已知限制

1. **不是所有声音都能抓**。Android 只允许采集 `USAGE_MEDIA / GAME / UNKNOWN` 且未主动
   设置 `ALLOW_CAPTURE_BY_NONE` 的应用。DRM 内容、通话、部分音乐 App 会返回**静音**，
   App 检测到持续静音会提示"该应用可能禁止被录制"。
2. **悬浮窗不能"让位"**。Windows 有 AppBar 能让其他窗口缩排，Android 只能覆盖。
   字幕条默认不拦截触摸（`FLAG_NOT_TOUCHABLE`）；要移动/缩放它，先在 App 里打开「调整位置和大小」，
   调完会自动锁定（30 秒无操作也会自动锁）。
3. **首字延迟 300–500 ms**，与 Windows 同量级；带翻译会到 500–800 ms。
4. 模型常驻内存约 400 MB，低端机可能被杀，建议在 8 GB 以上机型使用。
5. **Android 14+ 的录屏授权会先问你「共享整个屏幕」还是「共享单个应用」**。
   选单个应用时，音频采集会被限定在该应用；想给所有应用配字幕，要选整个屏幕。


---

## 技术栈

- Kotlin 2.2.21 / AGP 8.13.0 / Gradle 8.13+
- minSdk 29（AudioPlaybackCapture 的下限）/ targetSdk 35
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8（Apache-2.0，本地 AAR）
- 模型 `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`（Apache-2.0）

## 许可

应用代码 MIT。模型权重遵循其各自许可（Apache-2.0），分发时请保留 NOTICE。
