package com.translate.livecaption

/**
 * 字幕大小写处理。
 *
 * 为什么需要：sherpa-onnx 这个 zipformer 中英双语模型的词表是**全大写**的
 * （`tokens.txt` 里是 ▁AS / ▁ONE / ▁OF / ▁A 这种），所以原始识别结果一律大写。
 * Windows 那边有独立的大小写模型（en-US_unigram_capitalizationRules.enc 等），
 * Android 这里用规则补一个够用的版本。
 */
object CaptionCase {

    const val MODE_RAW = 0        // 原样（全大写）
    const val MODE_SENTENCE = 1   // 句首大写
    const val MODE_LOWER = 2      // 全小写

    private val loneI = Regex("(?<![A-Za-z])i(?![A-Za-z])")

    fun format(text: String, mode: Int): String {
        if (mode == MODE_RAW || text.isEmpty()) return text

        val lower = text.lowercase()
        if (mode == MODE_LOWER) return lower

        val sb = StringBuilder(lower.length)
        var capNext = true
        for (ch in lower) {
            if (capNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
            }
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n' ||
                ch == '。' || ch == '！' || ch == '？'
            ) {
                capNext = true
            }
        }
        // 单独的小写 i 还原成 I
        return loneI.replace(sb.toString(), "I")
    }
}
