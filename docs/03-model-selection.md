# 模型选型：ASR 与 NMT

> 结论先说：
> **ASR** 用 `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`（Apache-2.0，流式，中英混合）。
> **NMT** 在 M3 阶段用 OPUS-MT int8 起步，**不要用 NLLB**（CC-BY-NC，禁止商用）。
> 有更准的 ASR，但它们要么不流式、要么体积劝退、要么许可不明 —— 逐项说明如下。

---

## 一、ASR：为什么不换更"新"的模型

实时字幕有两个硬约束：**必须流式**（部分结果要持续吐）、**必须能常驻手机**（< 300 MB）。
用这两条一筛，候选立刻变少：

| 模型 | 流式 | 体积（int8） | 许可 | 实时字幕适用性 |
|---|---|---|---|---|
| **zipformer-bilingual-zh-en (2023-02-20)** | ✅ Transducer | 198 MB | **Apache-2.0** | ✅ **选它** |
| zipformer-zh-14M / small-zh | ✅ | 14–40 MB | 上游未声明 | ⚠️ 许可不清，可做低端机备选 |
| zipformer-zh-int8-2025-06-30 | ✅ | ~170 MB | **未声明** | ⚠️ 精度更好但许可不明，商用有风险 |
| Paraformer streaming（FunASR） | ✅ | ~220 MB | MIT（附模型协议） | ⭕ 纯中文更好，中英混合弱 |
| SenseVoice-Small | ❌ 非流式 | 230 MB | Apache-2.0 | ⭕ 要配 VAD 切段"模拟流式"，首字延迟差 |
| FireRedASR (1.1B / 8.3B) | ❌ | 1.1B 起 | Apache-2.0 | ❌ 中文 CER 最强，但不是端侧流式 |
| Qwen3-ASR (0.6B / 1.7B) | ❌ 偏离线 | 1.8–4.4 GB | Apache-2.0 | ❌ 中文 SOTA，但模型太大、不是为低延迟设计 |
| Whisper tiny/base/small | ❌ | 75–500 MB | MIT | ❌ 中文偏弱、非流式，translate 只能译成英文 |
| Vosk small | ✅ | 40 MB | Apache-2.0 | ⭕ 最容易过包体关，精度明显落后 |

**判断依据**：

1. **流式是刚需**。非流式模型（SenseVoice / FireRedASR / Qwen3-ASR）必须靠 VAD 切句后才能出字，
   首字延迟会被"等一句话说完"拖到 2–4 秒，做字幕体验不合格。Whisper 也有同样问题。
2. **中英混合是刚需**。中文视频里夹英文术语（"这个 API 的 latency"）太常见。
   Paraformer 纯中文强，但混说场景会掉字；zipformer-bilingual 共享一套 BPE，混说天然支持。
3. **许可要干净**。`2023-02-20` 这个权重是 HF 上有显式 Apache-2.0 提交的，
   上游 `pfluo/k2fsa-zipformer-chinese-english-mixed`，训练用 icefall 公开配方。
   几个"更新更强"的中文 zipformer 模型卡上**没有声明许可**，商用前必须逐个确认。

**如果后面想提精度**，按顺序试这三条，成本由低到高：

- 换 `modified_beam_search`（`maxActivePaths=4`）—— 免费，精度 +1~2%，CPU +30%
- 加 **热词表**（`hotwordsFile` + `hotwordsScore≈1.5`），专有名词命中率提升最明显
- 换更大的 zipformer（如 `zh-int8-2025-06-30`），但要先解决许可

---

## 二、NMT：M3 阶段用哪个

先说结论：**OPUS-MT（Marian）单语向模型 + int8 量化**，理由是端侧唯一能在 100 MB 量级
做到"句子级可用"的方案。

| 模型 | 参数量 | 体积（int8 量级） | 许可 | 端侧可行性 |
|---|---|---|---|---|
| **OPUS-MT en↔zh** | ~77M | 80–110 MB / 语向 | CC-BY-4.0（个别 Apache-2.0） | ✅ **起步方案** |
| Bergamot（Mozilla） | ~40M | 20–40 MB / 语向 | Apache-2.0 | ✅ 更快更小，**需核实是否有 en↔zh 权重** |
| NLLB-200-distilled-600M | 600M | 550–700 MB | **CC-BY-NC-4.0** | ⚠️ 质量好但**禁止商用** |
| MADLAD-400-3B | 3B | 2 GB+ | Apache-2.0 | ❌ 中文不是强项，体积劝退 |
| HY-MT1.5（腾讯混元） | 1.8B / 7B | 1–4 GB | 需查 | ❌ 中英 BLEU 最高，但端侧不现实 |
| ML Kit 端侧翻译 | — | ~30 MB/语言 | 闭源 | ⭕ 快，但不可控、不能加术语表、要 Google 服务 |

**关键提醒**：

1. **别被 NLLB 的高分骗了**。它质量确实好，但 `CC-BY-NC-4.0` 明确禁止商业用途，
   而且 600M 蒸馏版 int8 也要 600 MB —— 手机装不下，许可也不允许。
2. **翻译必须按句触发**，不能逐 token。做法：标点/端点触发 → 整句送 NMT → 译文滞后一行显示。
   逐词翻译会让字幕持续抖动，可读性归零。
3. **双行对照**比单行替换好：上行原文、下行译文，用户能对照着看（Windows 也是这个交互）。
4. **NMT 线程要丢过期请求**：队列长度 1，新句来了直接替换旧请求，否则会积压出"延迟雪崩"。

**M3 的落地顺序**：

```
OPUS-MT int8 (en→zh) 跑通
   ↓ 质量不够
加术语表 / 用 COMET 挑更好的 checkpoint
   ↓ 还不够
考虑蒸馏或换 Bergamot（如果 en↔zh 权重可用）
```

---

## 三、为什么不直接端到端"语音翻译"

理论上可以用 Whisper 的 `task=translate` 或 SeamlessM4T 一步到位（语音 → 外文文字）。但：

- Whisper 的 translate **只能译成英文**，而且非流式；
- SeamlessM4T v2 是 7B 级，手机跑不动；
- 端到端模型不能替换其中一环（想换 NMT 或想加术语表都不行）。

分两步（ASR + NMT）虽然多一次延迟，但每一环都可控、可替换、可调优 ——
这也是 Windows 那边实际的做法（见 `docs/01-rea-report.md` 第 7 节）。

---

## 四、补充：X-ASR 自带标点模型（2026-06）

用户反馈规则标点不够用之后，引入了一个**识别时就带标点**的模型：

| 项 | 内容 |
|---|---|
| 包名 | `sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05` |
| 上游 | [Gilgamesh-J/X-ASR](https://github.com/Gilgamesh-J/X-ASR)（**Apache-2.0**，基于 icefall，204★） |
| 体积 | tar.bz2 133.9 MB；解压后 encoder 155 MB(int8) + decoder 11 MB + joiner 2.6 MB + tokens 59 KB |
| 结构 | 标准 icefall streaming zipformer transducer（`cached_key_N` / `cached_nonlin_attn_N` / `cached_conv*_N`） |
| 词表 | 5000 tokens，**小写英文 + 标点**（`▁，▁。▁！▁？` 以及 `, ? !`） |
| 兼容性 | 已在 sherpa-onnx **1.13.8** 上实测加载并跑通（未升级运行时） |

**和默认模型的取舍**：体积小 30 MB、标点原生、英文小写（更接近正常书写）。
代价是模型族较新（2026-06），中文长尾表现尚未大规模验证。

**分发方式**：官方只发 tar.bz2，App 端用 `commons-compress` 的
`BZip2CompressorInputStream + TarArchiveInputStream` 解包，只挑 4 个必需文件落地，
其余（`bpe.model`、`test_wavs/`）丢弃。
