package com.translate.livecaption.asr

/**
 * 采集线程与识别线程之间的单生产者/单消费者环形缓冲。
 *
 * 对应 Windows 侧 AudioCaptureClientReader.cpp 的职责：
 * WASAPI 回环读线程只负责往缓冲里塞，解码线程按固定块长取。
 */
class RingBuffer(capacitySamples: Int) {

    private val buf = FloatArray(capacitySamples)
    private var head = 0      // 写入位置
    private var tail = 0      // 读取位置
    private var count = 0

    val capacity: Int get() = synchronized(this) { buf.size }

    @Synchronized
    fun write(src: FloatArray, len: Int) {
        var i = 0
        while (i < len) {
            buf[head] = src[i]
            head = (head + 1) % buf.size
            if (count == buf.size) {
                // 溢出：丢最旧的
                tail = (tail + 1) % buf.size
            } else {
                count++
            }
            i++
        }
    }

    /** 读取最多 max 个样本，返回实际读取数（0 表示暂无数据）。 */
    @Synchronized
    fun read(out: FloatArray, max: Int): Int {
        val n = minOf(max, count)
        var i = 0
        while (i < n) {
            out[i] = buf[tail]
            tail = (tail + 1) % buf.size
            i++
        }
        count -= n
        return n
    }

    @Synchronized
    fun available(): Int = count

    @Synchronized
    fun clear() {
        head = 0
        tail = 0
        count = 0
    }
}
