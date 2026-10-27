package com.translate.livecaption.asr

/**
 * 字幕稳定化。
 *
 * Windows 实时字幕「不闪」靠的不是模型，而是改写策略：部分结果只追加、
 * 不整体替换；定稿后才并入正文；正文只保留最近若干字符。详见
 * docs/02-android-port-plan.md 6.4。
 */
class CaptionStabilizer(private val maxCommittedChars: Int = 320) {

    private val committed = ArrayDeque<String>()
    private var partial: String = ""

    fun onPartial(text: String) {
        partial = text
    }

    fun onFinal(text: String) {
        val s = text.trim()
        if (s.isNotEmpty()) {
            committed.addLast(s)
            while (committed.size > 1 && committed.joinToString(" ").length > maxCommittedChars) {
                committed.removeFirst()
            }
        }
        partial = ""
    }

    /** 已定稿的正文（白色） */
    fun stable(): String = committed.joinToString(" ")

    /** 当前句的部分结果（淡色） */
    fun volatile(): String = partial

    fun clear() {
        committed.clear()
        partial = ""
    }

    fun isEmpty(): Boolean = committed.isEmpty() && partial.isEmpty()
}
