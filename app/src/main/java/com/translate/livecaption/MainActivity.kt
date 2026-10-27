package com.translate.livecaption

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.translate.livecaption.capture.CaptionBus
import com.translate.livecaption.capture.CaptureService
import com.translate.livecaption.capture.CaptionState
import com.translate.livecaption.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusExtra: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var btnToggle: Button
    private lateinit var switchAdjust: SwitchCompat
    private lateinit var capsRaw: TextView
    private lateinit var capsSentence: TextView
    private lateinit var capsLower: TextView

    private lateinit var previewStable: TextView
    private lateinit var previewVolatile: TextView
    private lateinit var previewBox: View

    private lateinit var rowMic: View
    private lateinit var rowNotify: View
    private lateinit var rowOverlay: View

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
        } else {
            toast(getString(R.string.capture_denied))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusExtra = findViewById(R.id.statusExtra)
        modelProgress = findViewById(R.id.modelProgress)
        btnToggle = findViewById(R.id.btnToggle)
        switchAdjust = findViewById(R.id.switchAdjust)
        capsRaw = findViewById(R.id.capsRaw)
        capsSentence = findViewById(R.id.capsSentence)
        capsLower = findViewById(R.id.capsLower)

        previewStable = findViewById(R.id.previewStable)
        previewVolatile = findViewById(R.id.previewVolatile)
        previewBox = findViewById(R.id.previewBox)

        rowMic = findViewById(R.id.rowMic)
        rowNotify = findViewById(R.id.rowNotify)
        rowOverlay = findViewById(R.id.rowOverlay)

        setupPermissionRows()
        setupCapsAndAdjust()
        setupStyleControls()
        refreshPermissions()
        refreshModel()
        observeBus()

        btnToggle.setOnClickListener { onToggleClick() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshModel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 权限

    private fun setupPermissionRows() {
        bindRow(rowMic, getString(R.string.perm_record)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        bindRow(rowNotify, getString(R.string.perm_notify)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        bindRow(rowOverlay, getString(R.string.perm_overlay)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun bindRow(root: View, title: String, onClick: () -> Unit) {
        root.findViewById<TextView>(R.id.rowTitle).text = title
        root.findViewById<Button>(R.id.rowAction).setOnClickListener { onClick() }
    }

    private fun refreshPermissions() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val overlay = Settings.canDrawOverlays(this)

        setRowState(rowMic, mic)
        setRowState(rowNotify, notify)
        setRowState(rowOverlay, overlay)
    }

    private fun setRowState(root: View, granted: Boolean) {
        val state = root.findViewById<TextView>(R.id.rowState)
        val action = root.findViewById<Button>(R.id.rowAction)
        state.text = getString(if (granted) R.string.granted else R.string.not_granted)
        state.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.ok else R.color.text3)
        )
        action.visibility = if (granted) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ 模型

    private val rowViews = HashMap<String, Array<View>>()
    private var downloading = false

    private fun refreshModel() {
        val list = findViewById<LinearLayout>(R.id.modelList)
        list.removeAllViews()
        rowViews.clear()
        val selected = prefs.selectedModelId

        ModelManager.MODELS.forEachIndexed { i, m ->
            if (i > 0) {
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.line))
                })
            }
            list.addView(buildModelRow(m, selected))
        }
    }

    private fun buildModelRow(m: ModelManager.AsrModel, selectedId: String): View {
        val ready = ModelManager.isReady(this, m)
        val isSelected = m.id == selectedId

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = m.title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 15f
        }
        val sub = TextView(this).apply {
            text = m.subtitle
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text3))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        }
        val state = TextView(this).apply {
            text = when {
                !ready -> "未下载"
                isSelected -> "当前使用"
                else -> "已下载"
            }
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when {
                        !ready -> R.color.text3
                        isSelected -> R.color.ok
                        else -> R.color.text2
                    }
                )
            )
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(title)
        col.addView(sub)
        col.addView(state)

        val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = when {
                !ready -> "下载"
                isSelected -> "使用中"
                else -> "使用"
            }
            textSize = 12f
            isAllCaps = false
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isSelected) R.color.text3 else R.color.accent
                )
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isEnabled = !isSelected
            setOnClickListener {
                when {
                    !ModelManager.isReady(this@MainActivity, m) -> startModelDownload(m)
                    prefs.selectedModelId != m.id -> selectModel(m)
                }
            }
        }

        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) })

        rowViews[m.id] = arrayOf(title, sub, state, btn)
        return row
    }

    private fun selectModel(m: ModelManager.AsrModel) {
        prefs.selectedModelId = m.id
        if (CaptureService.isRunning()) {
            CaptureService.stop(this)
            toast("已切换到「${m.title}」，请重新开始字幕")
        } else {
            toast("已选择「${m.title}」")
        }
        refreshModel()
    }

    private fun startModelDownload(m: ModelManager.AsrModel) {
        if (downloading) return
        downloading = true
        modelProgress.visibility = View.VISIBLE
        modelProgress.progress = 0

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(this@MainActivity, m) { done, total, note ->
                        val p = (done * 1000 / total.coerceAtLeast(1)).toInt()
                        scope.launch {
                            modelProgress.progress = p
                            rowViews[m.id]?.let { v ->
                                (v[2] as TextView).text = if (note.isBlank()) {
                                    "下载完成"
                                } else {
                                    "下载中 ${done / 1048576}/${total / 1048576} MB"
                                }
                                (v[3] as android.widget.Button).text = "${p / 10}%"
                            }
                        }
                    }
                }
                toast("模型就绪")
            } catch (t: Throwable) {
                toast("下载失败：${t.message}")
            } finally {
                downloading = false
                modelProgress.visibility = View.GONE
                refreshModel()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 样式

    private fun setupStyleControls() {
        val seekSize = findViewById<SeekBar>(R.id.seekSize)
        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)
        val seekLines = findViewById<SeekBar>(R.id.seekLines)
        val seekBottom = findViewById<SeekBar>(R.id.seekBottom)

        val valueSize = findViewById<TextView>(R.id.valueSize)
        val valueOpacity = findViewById<TextView>(R.id.valueOpacity)
        val valueLines = findViewById<TextView>(R.id.valueLines)
        val valueBottom = findViewById<TextView>(R.id.valueBottom)

        seekSize.progress = (prefs.fontSize - 13).coerceIn(0, 16)
        seekOpacity.progress = prefs.bgOpacity
        seekLines.progress = (prefs.maxLines - 1).coerceIn(0, 7)
        seekBottom.progress = prefs.bottomMargin.coerceIn(0, 400)

        fun sync() {
            valueSize.text = "${prefs.fontSize}sp"
            valueOpacity.text = "${prefs.bgOpacity}%"
            valueLines.text = "${prefs.maxLines}"
            valueBottom.text = "${prefs.bottomMargin}dp"
            applyPreview()
        }

        fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
                sync()
                // 运行中也立刻生效，不用重开字幕
                CaptureService.requestRestyle()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        seekSize.setOnSeekBarChangeListener(listener { prefs.fontSize = 13 + it })
        seekOpacity.setOnSeekBarChangeListener(listener { prefs.bgOpacity = it })
        seekLines.setOnSeekBarChangeListener(listener {
            prefs.maxLines = 1 + it
            // 行数变化代表「要自动高度」，清掉手动调过的高度
            prefs.boxHeightDp = 0
        })
        seekBottom.setOnSeekBarChangeListener(listener {
            prefs.bottomMargin = it
            // 手动拖过位置后，动画距底部滑块要重新接管位置
            prefs.posYFrac = -1f
        })

        sync()
    }

    private fun setupCapsAndAdjust() {
        applyCapsSelection()

        capsRaw.setOnClickListener { prefs.capsMode = CaptionCase.MODE_RAW; afterCapsChange() }
        capsSentence.setOnClickListener { prefs.capsMode = CaptionCase.MODE_SENTENCE; afterCapsChange() }
        capsLower.setOnClickListener { prefs.capsMode = CaptionCase.MODE_LOWER; afterCapsChange() }

        switchAdjust.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!CaptureService.isRunning()) {
                    switchAdjust.isChecked = false
                    toast("请先开始字幕，再调整位置和大小")
                    return@setOnCheckedChangeListener
                }
                CaptureService.requestEditMode(true)
            } else {
                CaptureService.requestEditMode(false)
            }
        }

        findViewById<Button>(R.id.btnResetBox).setOnClickListener {
            prefs.resetBox()
            CaptureService.requestRestyle()
            toast("已恢复默认位置和大小")
        }

        findViewById<SwitchCompat>(R.id.switchPunct).apply {
            isChecked = prefs.punctuate
            setOnCheckedChangeListener { _, checked -> prefs.punctuate = checked }
        }
    }

    private fun afterCapsChange() {
        applyCapsSelection()
        applyPreview()
        // 大小写是渲染层的事，下一帧字幕就会按新规则来
    }

    private fun applyCapsSelection() {
        val views = listOf(
            capsRaw to CaptionCase.MODE_RAW,
            capsSentence to CaptionCase.MODE_SENTENCE,
            capsLower to CaptionCase.MODE_LOWER,
        )
        for ((v, mode) in views) {
            val on = prefs.capsMode == mode
            v.background = if (on) {
                ContextCompat.getDrawable(this, R.drawable.bg_pill)
            } else null
            v.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.accent else R.color.text2)
            )
        }
    }

    private fun applyPreview() {
        previewStable.textSize = prefs.fontSize.toFloat()
        previewVolatile.textSize = prefs.fontSize.toFloat()
        previewStable.text = CaptionCase.format(PREVIEW_STABLE, prefs.capsMode)
        previewVolatile.text = CaptionCase.format(PREVIEW_VOLATILE, prefs.capsMode)

        val alpha = (prefs.bgOpacity / 100f * 255).toInt().coerceIn(0, 255)
        previewBox.background = GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(Color.argb(alpha, 14, 17, 22))
            setStroke(1, Color.parseColor("#2A323E"))
        }
    }

    // ------------------------------------------------------------ 状态

    private fun observeBus() {
        scope.launch {
            CaptionBus.state.collect { st ->
                statusDot.setBackgroundResource(
                    when (st) {
                        CaptionState.RUNNING -> R.drawable.dot_active
                        CaptionState.NO_AUDIO -> R.drawable.dot_warn
                        CaptionState.ERROR -> R.drawable.dot_warn
                        else -> R.drawable.dot_idle
                    }
                )
                statusText.text = when (st) {
                    CaptionState.IDLE -> "空闲"
                    CaptionState.PREPARING -> "准备中…"
                    CaptionState.RUNNING -> "识别中"
                    CaptionState.NO_AUDIO -> "无音频输入"
                    CaptionState.ERROR -> "出错"
                }
                val running = st == CaptionState.RUNNING || st == CaptionState.PREPARING ||
                    st == CaptionState.NO_AUDIO
                btnToggle.text = getString(if (running) R.string.stop else R.string.start)
            }
        }
        scope.launch {
            CaptionBus.message.collect { statusExtra.text = it }
        }
        scope.launch {
            CaptionBus.line.collect { line ->
                if (line.isNotEmpty()) {
                    previewStable.text = line
                    previewVolatile.text = ""
                }
            }
        }
        scope.launch {
            // 字幕条 30 秒无操作会自动锁定，这里同步开关状态
            CaptionBus.editMode.collect { on ->
                if (switchAdjust.isChecked != on) switchAdjust.isChecked = on
            }
        }
    }

    // ------------------------------------------------------------ 主按钮

    private fun onToggleClick() {
        val st = CaptionBus.state.value
        if (st == CaptionState.RUNNING || st == CaptionState.PREPARING || st == CaptionState.NO_AUDIO) {
            CaptureService.stop(this)
            return
        }
        if (!ModelManager.isReady(this, ModelManager.byId(prefs.selectedModelId))) {
            toast("请先下载并选择识别模型")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast("请先授予录音权限")
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 故意用模型原样的全大写，方便直观对比大小写切换效果 */
        private const val PREVIEW_STABLE = "THIS IS HOW THE CAPTION LOOKS."
        private const val PREVIEW_VOLATILE = "AND THIS PART IS STILL BEING RECOGNIZED."
    }
}
