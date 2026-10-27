package com.translate.livecaption.model

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型管理：下载 / 校验 / 定位，支持多个模型。
 *
 * 设计上参考了 Windows Live Captions 的 Appx 语言包机制（见
 * docs/01-rea-report.md 2.4）：模型不进安装包，首次使用时下载。
 *
 * 目前提供两个模型：
 *  A. zipformer-bilingual-zh-en-2023-02-20 —— 已验证的默认模型，词表全大写、无标点（Apache-2.0）
 *  B. x-asr-960ms-...-zh-en-punct-int8     —— 自带标点，词表小写+标点，体积更小（Apache-2.0）
 */
object ModelManager {

    data class FileSpec(val name: String, val size: Long, val sha256: String)

    /** 以 tar.bz2 整包分发时的下载信息 */
    data class ArchiveSpec(
        val urls: List<String>,
        val innerPrefix: String,
        val size: Long,
        val sha256: String,
    )

    data class AsrModel(
        val id: String,
        val title: String,
        val subtitle: String,
        val dirName: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val files: List<FileSpec>,
        val nativePunct: Boolean,
        val directUrls: List<String> = emptyList(),
        val archive: ArchiveSpec? = null,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
        val encoderName get() = encoder
        val decoderName get() = decoder
        val joinerName get() = joiner
        val tokensName get() = tokens
    }

    // ------------------------------------------------------------------ 模型 A

    private const val BILINGUAL_DIR = "zipformer-bilingual-zh-en"

    val BILINGUAL = AsrModel(
        id = "bilingual",
        title = "Zipformer 中英双语",
        subtitle = "198 MB · 不带标点 · 全大写词表",
        dirName = BILINGUAL_DIR,
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.int8.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        tokens = "tokens.txt",
        files = listOf(
            FileSpec("tokens.txt", 56_317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
            FileSpec("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
            FileSpec("decoder-epoch-99-avg-1.int8.onnx", 13_091_040L, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa"),
            FileSpec("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
        ),
        nativePunct = false,
        directUrls = listOf(
            "https://modelscope.cn/models/pkufool/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/master",
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main",
        ),
    )

    // ------------------------------------------------------------------ 模型 B

    private const val XASR_DIR = "x-asr-zh-en-punct"
    private const val XASR_REPO =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"

    val XASR_PUNCT = AsrModel(
        id = "xasr",
        title = "X-ASR 中英双语 · 自带标点",
        subtitle = "168 MB · 识别时直接输出 ，。？！ · 词表小写",
        dirName = XASR_DIR,
        encoder = "encoder.int8.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        files = listOf(
            FileSpec("tokens.txt", 58_806L, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"),
            FileSpec("joiner.int8.onnx", 2_581_422L, "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3"),
            FileSpec("decoder.onnx", 11_309_084L, "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257"),
            FileSpec("encoder.int8.onnx", 155_276_576L, "017e3cf23097302dbc57ebd72cf4a209cf55c367920669e2d9ce9c0381a96ddd"),
        ),
        nativePunct = true,
        archive = ArchiveSpec(
            urls = listOf(
                "https://gh-proxy.com/$XASR_REPO",
                XASR_REPO,
            ),
            innerPrefix = "sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/",
            size = 133_895_831L,
            sha256 = "0a92b798bd6801c333c7ce8aebf5ba769bfe7f3f3511699a67837b2288428603",
        ),
    )

    val MODELS = listOf(BILINGUAL, XASR_PUNCT)

    fun byId(id: String?): AsrModel = MODELS.firstOrNull { it.id == id } ?: BILINGUAL

    // ------------------------------------------------------------------ 路径

    fun dir(context: Context, model: AsrModel): File = File(context.filesDir, "models/${model.dirName}")

    fun path(context: Context, model: AsrModel, fileName: String): String =
        File(dir(context, model), fileName).absolutePath

    fun isReady(context: Context, model: AsrModel): Boolean = model.files.all { spec ->
        val f = File(dir(context, model), spec.name)
        f.isFile && f.length() == spec.size
    }

    // ------------------------------------------------------------------ 下载

    /**
     * @param onProgress (已完成字节, 总字节, 当前说明)
     */
    fun download(
        context: Context,
        model: AsrModel,
        onProgress: (done: Long, total: Long, note: String) -> Unit
    ) {
        val target = dir(context, model).apply { mkdirs() }
        val total = model.totalBytes

        if (model.archive != null) {
            downloadAndExtract(context, model, target, onProgress)
            return
        }

        var done = model.files.filter { File(target, it.name).let { f -> f.isFile && f.length() == it.size } }
            .sumOf { it.size }

        for (spec in model.files) {
            val out = File(target, spec.name)
            if (out.isFile && out.length() == spec.size) continue

            var lastError: Exception? = null
            var ok = false
            for (base in model.directUrls) {
                try {
                    fetchToFile("$base/${spec.name}", out) { read ->
                        onProgress(done + read, total, spec.name)
                    }
                    verify(out, spec)
                    done += spec.size
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    out.delete()
                }
            }
            if (!ok) throw lastError ?: IllegalStateException("download failed: ${spec.name}")
        }
        onProgress(total, total, "")
    }

    private fun downloadAndExtract(
        context: Context,
        model: AsrModel,
        target: File,
        onProgress: (Long, Long, String) -> Unit
    ) {
        if (isReady(context, model)) {
            onProgress(model.totalBytes, model.totalBytes, "")
            return
        }
        val archive = model.archive ?: return
        val tmp = File(context.cacheDir, "${model.dirName}.tar.bz2")
        var lastError: Exception? = null

        for (url in archive.urls) {
            try {
                fetchToFile(url, tmp) { read -> onProgress(read, archive.size, "下载模型包") }
                if (tmp.length() != archive.size) throw IllegalStateException("包大小不符")
                val md = sha256(tmp)
                if (!md.equals(archive.sha256, true)) throw IllegalStateException("包校验失败")
                extract(tmp, archive, target, model, onProgress)
                tmp.delete()
                onProgress(model.totalBytes, model.totalBytes, "")
                return
            } catch (e: Exception) {
                lastError = e
                tmp.delete()
            }
        }
        throw lastError ?: IllegalStateException("模型包下载失败")
    }

    private fun extract(
        archiveFile: File,
        archive: ArchiveSpec,
        target: File,
        model: AsrModel,
        onProgress: (Long, Long, String) -> Unit
    ) {
        val wanted = model.files.associateBy { it.name }
        var doneBytes = 0L
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { bin ->
            TarArchiveInputStream(bin).use { tin ->
                var entry = tin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.removePrefix(archive.innerPrefix)
                        val spec = wanted[name]
                        if (spec != null && !name.contains('/')) {
                            val out = File(target, name)
                            out.outputStream().use { os -> tin.copyTo(os, 1 shl 16) }
                            verify(out, spec)
                            doneBytes += spec.size
                            onProgress(doneBytes, model.totalBytes, "解压 $name")
                        }
                    }
                    entry = tin.nextEntry
                }
            }
        }
    }

    private fun verify(file: File, spec: FileSpec) {
        if (file.length() != spec.size) {
            file.delete()
            throw IllegalStateException("${spec.name} 大小不符（${file.length()} != ${spec.size}）")
        }
        if (spec.sha256.isNotEmpty() && !spec.sha256.equals(sha256(file), true)) {
            file.delete()
            throw IllegalStateException("${spec.name} 校验失败")
        }
    }

    private fun fetchToFile(url: String, out: File, onRead: (Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiveCaption/0.1")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val tmp = File(out.parentFile, out.name + ".part")
            var written = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        onRead(written)
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(512 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
