package com.translate.livecaption.capture

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.translate.livecaption.App
import com.translate.livecaption.CaptionCase
import com.translate.livecaption.MainActivity
import com.translate.livecaption.Prefs
import com.translate.livecaption.R
import com.translate.livecaption.asr.AsrEngine
import com.translate.livecaption.asr.CaptionStabilizer
import com.translate.livecaption.asr.Punctuator
import com.translate.livecaption.asr.RingBuffer
import com.translate.livecaption.model.ModelManager
import com.translate.livecaption.overlay.CaptionOverlay
import kotlin.math.sqrt

/**
 * 采集 + 识别 + 悬浮窗的编排者。
 *
 * 对应 Windows 的 LiveCaptionsBackendDll.dll：
 *   AudioSession  → 这里的 AudioRecord(回环)
 *   RecognizerManager → AsrEngine
 *   CaptionBackend → 这里的识别线程与状态机
 */
class CaptureService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val ring = RingBuffer(SAMPLE_RATE * 20)     // 20 秒环形缓冲，够扛一次卡顿
    private val stabilizer = CaptionStabilizer()

    private var overlay: CaptionOverlay? = null
    private var engine: AsrEngine? = null
    private var activeModel: ModelManager.AsrModel? = null
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null

    private var captureThread: Thread? = null
    private var asrThread: Thread? = null

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (requestAudioPermission() && data != null) {
                    startCapture(resultCode, data)
                } else {
                    fail("缺少录音权限或录屏授权")
                }
            }

            ACTION_STOP -> stopSelf()

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 前台

    private fun startAsForeground() {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notif_stop), stopPi)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else 0
        )
    }

    private fun requestAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- 启动

    private fun startCapture(resultCode: Int, data: Intent) {
        CaptionBus.state.value = CaptionState.PREPARING
        CaptionBus.message.value = "加载模型…"

        Thread({
            try {
                // 先把悬浮窗挂上去（几毫秒），给用户即时反馈；模型加载要几秒
                Handler(Looper.getMainLooper()).post {
                    val o = CaptionOverlay(this, prefs)
                    o.show()
                    overlay = o
                }

                val model = ModelManager.byId(prefs.selectedModelId)
                if (!ModelManager.isReady(this, model)) {
                    fail("模型未下载完成")
                    return@Thread
                }

                engine = AsrEngine(this, model)
                activeModel = model

                val mpm = getSystemService(MediaProjectionManager::class.java)
                val proj = mpm.getMediaProjection(resultCode, data)
                    ?: run { fail("获取 MediaProjection 失败"); return@Thread }
                projection = proj

                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 用户从通知栏/系统 UI 撤权
                        Log.i(TAG, "media projection stopped by system")
                        stopSelf()
                    }
                }, Handler(Looper.getMainLooper()))

                val rec = buildPlaybackRecord(proj)
                record = rec

                stabilizer.clear()
                ring.clear()
                rec.startRecording()
                running = true
                startThreads()

                CaptionBus.state.value = CaptionState.RUNNING
                CaptionBus.message.value = "正在识别"
            } catch (t: Throwable) {
                Log.e(TAG, "startCapture failed", t)
                fail(t.message ?: "启动失败")
            }
        }, "lc-start").start()
    }

    /**
     * 关键一步：用 AudioPlaybackCapture 抓「系统播放的声音」。
     * 对应 Windows 的 ActivateAudioInterfaceAsync + 进程回环。
     */
    private fun buildPlaybackRecord(proj: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE / 2))   // ~0.5 s
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord 初始化失败（该系统/机型可能不支持回环采集）")
        }
        return rec
    }

    private fun startThreads() {
        captureThread = Thread({ captureLoop() }, "lc-capture").apply { start() }
        asrThread = Thread({ asrLoop() }, "lc-asr").apply { start() }
    }

    // ---------------------------------------------------------------- 采集

    private fun captureLoop() {
        val buf = ShortArray(BLOCK_SAMPLES)          // 100 ms
        val fbuf = FloatArray(BLOCK_SAMPLES)
        var silentBlocks = 0
        var hadText = false

        try {
            while (running) {
                val n = record?.read(buf, 0, buf.size) ?: 0
                if (n <= 0) {
                    Thread.sleep(5)
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i] / 32768f
                    fbuf[i] = s
                    sum += (s * s).toDouble()
                }
                ring.write(fbuf, n)

                // 静音检测：连续 3 秒几乎无声 → 提示「该应用可能禁止被录制」
                val rms = sqrt(sum / n).toFloat()
                CaptionBus.level.value = (rms * 8f).coerceIn(0f, 1f)
                if (rms < SILENCE_RMS) {
                    silentBlocks++
                    if (silentBlocks > 30 && !hadText && CaptionBus.state.value == CaptionState.RUNNING) {
                        CaptionBus.state.value = CaptionState.NO_AUDIO
                        CaptionBus.message.value = "未检测到音频，该应用可能禁止被录制"
                    }
                } else {
                    silentBlocks = 0
                    if (CaptionBus.state.value == CaptionState.NO_AUDIO) {
                        CaptionBus.state.value = CaptionState.RUNNING
                        CaptionBus.message.value = "正在识别"
                    }
                }
                if (CaptionBus.line.value.isNotEmpty()) hadText = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop died", t)
        }
    }

    // ---------------------------------------------------------------- 识别

    private fun asrLoop() {
        val eng = engine ?: return
        val out = FloatArray(BLOCK_SAMPLES)
        var lastText = ""

        try {
            while (running) {
                val n = ring.read(out, out.size)
                if (n == 0) {
                    Thread.sleep(8)
                    continue
                }
                eng.accept(out, n)

                val text = eng.text()
                if (text != lastText) {
                    lastText = text
                    stabilizer.onPartial(text)
                    publish()
                }

                if (eng.isEndpoint()) {
                    val r = eng.result()
                    // 模型自带标点就别再套规则，否则会重复标点
                    val needRulePunct = prefs.punctuate && activeModel?.nativePunct != true
                    val finalText = if (needRulePunct) {
                        Punctuator.apply(r.text, r.tokens, r.timestamps)
                    } else {
                        r.text
                    }
                    stabilizer.onFinal(finalText)
                    eng.reset()
                    lastText = ""
                    publish()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "asr loop died", t)
        }
    }

    private fun publish() {
        // 模型词表是全大写的，这里按用户选择做大小写还原
        val mode = prefs.capsMode
        val stable = CaptionCase.format(stabilizer.stable(), mode)
        val partial = CaptionCase.format(stabilizer.volatile(), mode)
        CaptionBus.line.value = (stable + " " + partial).trim()
        overlay?.update(stable, partial)
    }

    // ---------------------------------------------------------------- 收尾

    private fun fail(message: String) {
        CaptionBus.state.value = CaptionState.ERROR
        CaptionBus.message.value = message
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        running = false
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            record?.release()
        } catch (_: Throwable) {
        }
        record = null

        try {
            @Suppress("DEPRECATION")
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null

        captureThread?.join(300)
        asrThread?.join(600)
        captureThread = null
        asrThread = null

        engine?.release()
        engine = null

        overlay?.hide()
        overlay = null

        CaptionBus.state.value = CaptionState.IDLE
        CaptionBus.message.value = ""
        CaptionBus.line.value = ""
        CaptionBus.level.value = 0f

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveCaption"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        private const val BLOCK_SAMPLES = 1600           // 100 ms
        private const val SILENCE_RMS = 0.0012f

        const val ACTION_START = "com.translate.livecaption.START"
        const val ACTION_STOP = "com.translate.livecaption.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        @Volatile
        private var instance: CaptureService? = null

        /** 运行中可直接热更新样式（字号 / 透明度 / 行数 / 位置） */
        fun requestRestyle() {
            instance?.overlay?.applyStyle()
        }

        /** 切换字幕条的「可拖动 / 可缩放」状态 */
        fun requestEditMode(on: Boolean) {
            instance?.overlay?.setEditMode(on)
        }

        fun isRunning(): Boolean = instance != null
    }
}
