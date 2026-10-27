package com.translate.livecaption.asr

/**
 * 规则标点。
 *
 * 为什么先做规则而不是模型：sherpa-onnx 现成的中英标点模型（CT-Transformer）
 * 是 294 MB，叠在 198 MB 的识别模型上就是近 500 MB 包体；而识别器本身已经用 VAD
 * 按停顿切了句，我们缺的主要是「句末符号」和「长停顿处的逗号」这两件事，
 * 用 token 时间戳就能拿到，成本为零。
 *
 * 真正的模型标点留作后续替换点（见 docs/02-android-port-plan.md 6.1）。
 */
object Punctuator {

    private val EN_QUESTION_WORDS = setOf(
        "what", "why", "how", "who", "whom", "whose", "when", "where", "which",
        "is", "are", "was", "were", "am", "do", "does", "did",
        "can", "could", "would", "should", "will", "shall", "have", "has", "may"
    )

    private val ZH_QUESTION_TAIL = listOf("吗", "呢", "嘛", "么")
    private val ZH_QUESTION_ANY = listOf("为什么", "怎么", "什么", "哪", "谁", "多少", "几点", "是不是", "有没有")

    /** 长停顿阈值（秒），超过就补一个逗号 */
    private const val COMMA_GAP = 0.45f

    /**
     * @param text       识别器给的整段文本
     * @param tokens     逐 token 文本（sherpa 的 OnlineRecognizerResult.tokens）
     * @param timestamps 逐 token 起始时间（秒）
     */
    fun apply(text: String, tokens: Array<String>?, timestamps: FloatArray?): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        // 已经带标点（例如未来换成自带标点的模型）就不要重复加
        if (t.last() in "。！？.!?，,") return t

        val cjk = t.any { it.code in 0x4E00..0x9FFF }
        val question = isQuestion(t, cjk)

        var body = t
        if (tokens != null && timestamps != null && tokens.size == timestamps.size && tokens.isNotEmpty()) {
            body = insertPauseCommas(t, tokens, timestamps, cjk) ?: t
        }
        if (body === t || body.none { it == '，' || it == ',' }) {
            body = insertConjunctionComma(body, cjk)
        }

        val end = when {
            question && cjk -> "？"
            question -> "?"
            cjk -> "。"
            else -> "."
        }
        return body + end
    }

    // ------------------------------------------------------------ 疑问句

    private fun isQuestion(t: String, cjk: Boolean): Boolean {
        if (t.contains('?')) return true
        if (cjk) {
            val tail = t.takeLast(3)
            if (ZH_QUESTION_TAIL.any { tail.endsWith(it) }) return true
            return ZH_QUESTION_ANY.any { t.contains(it) } && t.length <= 24
        }
        // 英文看开头：疑问句几乎总是以疑问词/助动词打头
        // （"WHAT ARE YOU DOING" 的最后一个词是 doing，只看词尾会漏判）
        val firstWord = t.lowercase().trimStart().substringBefore(' ').trim('.', ',', '\'')
        return firstWord in EN_QUESTION_WORDS
    }

    // ------------------------------------------------------------ 停顿插逗号

    /**
     * 用 token 时间戳找停顿。先把 token 拼回文本以拿到每个 token 的字符区间；
     * 拼接长度和原文对不上就放弃（说明 sherpa 的 detokenize 规则和我们的假设不同），
     * 宁可少标点也不要错位。
     */
    private fun insertPauseCommas(
        text: String,
        tokens: Array<String>,
        ts: FloatArray,
        cjk: Boolean
    ): String? {
        val lens = IntArray(tokens.size) { tokens[it].replace('\u2581', ' ').length }
        if (lens.sum() != text.length && lens.isNotEmpty() && lens[0] > 0) {
            // 退一步：整句开头那个 ▁ 在 text 里没有对应空格
            lens[0] -= 1
        }
        if (lens.sum() != text.length) return null

        val cuts = ArrayList<Int>(4)
        var pos = 0
        for (i in tokens.indices) {
            pos += lens[i]
            if (i == tokens.size - 1) break
            if (ts[i + 1] - ts[i] < COMMA_GAP) continue
            // token 前导空格落在上一个片段末尾，逗号要插在空格之前，
            // 否则会变成 "THIS , IS"
            var at = pos
            if (at > 0 && text[at - 1] == ' ') at -= 1
            if (at > 0 && text[at - 1].isLetterOrDigit()) cuts.add(at)
        }
        if (cuts.isEmpty()) return null

        val comma = if (cjk) '，' else ','
        val sb = StringBuilder(text.length + cuts.size)
        var last = 0
        for (c in cuts) {
            if (c <= last) continue
            sb.append(text, last, c)
            sb.append(comma)
            last = c
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    // ------------------------------------------------------------ 连词前补逗号

    /**
     * 长句里在转折/因果连词前补一个逗号。只在「没有从停顿里拿到任何逗号」时兜底，
     * 且要求句子够长，避免短句被切得稀碎。
     */
    private fun insertConjunctionComma(text: String, cjk: Boolean): String {
        return if (cjk) {
            if (text.length < 16) return text
            val marks = listOf("但是", "不过", "而且", "因为", "所以", "如果", "然后", "另外")
            var out = text
            for (m in marks) {
                val i = out.indexOf(m)
                if (i > 2) {
                    out = out.substring(0, i) + "，" + out.substring(i)
                    break
                }
            }
            out
        } else {
            val words = text.split(' ')
            if (words.size < 12) return text
            val marks = listOf("but", "because", "although", "though", "so", "which", "while")
            for (k in 1 until words.size) {
                if (words[k].lowercase().trim(',') in marks) {
                    val head = words.subList(0, k).joinToString(" ")
                    val tail = words.subList(k, words.size).joinToString(" ")
                    return "$head, $tail"
                }
            }
            text
        }
    }
}
