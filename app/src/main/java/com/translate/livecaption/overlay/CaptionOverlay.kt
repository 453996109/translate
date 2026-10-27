package com.translate.livecaption.overlay

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.translate.livecaption.Prefs
import com.translate.livecaption.R
import com.translate.livecaption.capture.CaptionBus
import kotlin.math.roundToInt

/**
 * 半透明字幕悬浮窗。
 *
 * 视觉参考 Windows Live Captions：圆角半透明底、正文白、未定稿淡。
 *
 * 交互分三态：
 *  1. 锁定（默认）：`FLAG_NOT_TOUCHABLE`，不挡下层点击；
 *  2. 调整：单指拖动、双指缩放，蓝色边框提示；
 *  3. 自动锁定：调整后 30 秒无操作锁回去，同时**右上角出现一个小握把**，
 *     点它就能重新进入调整模式 —— 否则用户只能切回 App 里开开关，很别扭。
 */
class CaptionOverlay(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var textView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var attached = false
    private var editing = false

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartW = 0
    private var dragStartH = 0
    private var downRawX = 0f
    private var downRawY = 0f

    /** 手势模式：移动 / 只改宽 / 只改高 / 同时改 */
    private var gesture = GESTURE_MOVE

    private val hideRunnable = Runnable { fadeTo(0f) }
    private val unlockRunnable = Runnable { setEditMode(false) }

    /** 旋转 / 分屏 / 折叠屏展开都会走到这里，必须重新钳位，否则窗口会跑到屏幕外 */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post { applyStyle() }
        }

        override fun onLowMemory() {}
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val lp = params ?: return false
                resetUnlockTimer()
                // 只改宽度：字幕是横向文本，等比缩放会把高度一起拉走，
                // 用户真正想调的是"这一行能显示多长"
                lp.width = (lp.width * detector.scaleFactor).toInt()
                    .coerceIn(dp(140), screenSize().x)
                clampInto(lp)
                safeUpdate(lp)
                positionHandle()
                return true
            }
        }
    )

    // ------------------------------------------------------------ 显示 / 隐藏

    fun show() = main.post {
        if (attached) return@post
        val container = FrameLayout(context)
        val vPad = dp(12)
        val hPad = dp(20)

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
            // 左对齐：居中时每来一个新词整行都会重新居中，已出现的字会左右跳，
            // 读起来非常难受。左对齐后新词只往右长，已出现的字位置固定。
            gravity = Gravity.BOTTOM or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            includeFontPadding = false
            setShadowLayer(6f, 0f, 1f, Color.argb(200, 0, 0, 0))
        }
        container.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )
        container.setPadding(hPad, vPad, hPad, vPad)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        root = container
        textView = tv
        params = lp
        container.setOnTouchListener { _, event -> onTouch(event) }

        applyStyle()

        try {
            tv.text = context.getString(R.string.overlay_hint)
            tv.setTextColor(Color.argb(140, 255, 255, 255))
            wm.addView(container, lp)
            attached = true
            container.alpha = 1f
            addHandle()
            context.applicationContext.registerComponentCallbacks(configCallbacks)
        } catch (t: Throwable) {
            android.util.Log.e("LiveCaption", "overlay addView failed", t)
            root = null
            textView = null
            attached = false
        }
    }

    fun hide() = main.post {
        if (!attached) return@post
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(unlockRunnable)
        editing = false
        CaptionBus.editMode.value = false
        try {
            context.applicationContext.unregisterComponentCallbacks(configCallbacks)
        } catch (_: Throwable) {
        }
        removeHandle()
        try {
            wm.removeView(root)
        } catch (_: Throwable) {
        }
        root = null
        textView = null
        attached = false
    }

    // ------------------------------------------------------------ 样式与几何

    /** 样式或位置变化后调用；运行中调用会立即生效。 */
    fun applyStyle() = main.post {
        val container = root ?: return@post
        val tv = textView ?: return@post
        val lp = params ?: return@post
        val size = screenSize()

        val sizePx = prefs.fontSize * context.resources.displayMetrics.scaledDensity
        tv.textSize = prefs.fontSize.toFloat()
        tv.setLineSpacing(dp(3).toFloat(), 1f)

        val lineHeight = (sizePx * 1.38f).roundToInt()
        val vPad = dp(12)
        val autoHeight = lineHeight * prefs.maxLines + vPad * 2

        var width = if (prefs.boxWidthDp > 0) dp(prefs.boxWidthDp) else size.x
        var height = if (prefs.boxHeightDp > 0) dp(prefs.boxHeightDp) else autoHeight

        // 旋转后旧尺寸可能超出新屏幕，钳住（并写回，避免下次还越界）
        width = width.coerceIn(dp(120), size.x)
        height = height.coerceIn(dp(40), size.y)
        if (prefs.boxWidthDp > 0 && width != dp(prefs.boxWidthDp)) prefs.boxWidthDp = px2dp(width)
        if (prefs.boxHeightDp > 0 && height != dp(prefs.boxHeightDp)) prefs.boxHeightDp = px2dp(height)

        lp.width = width
        lp.height = height

        // 位置按「占可移动范围的比例」还原，旋转后仍然落在同一相对位置
        val maxX = (size.x - width).coerceAtLeast(0)
        val maxY = (size.y - height).coerceAtLeast(0)
        val fx = prefs.posXFrac
        val fy = prefs.posYFrac
        val x = if (fx >= 0f) (fx * maxX).roundToInt().coerceIn(0, maxX) else 0
        val y = if (fy >= 0f) {
            (fy * maxY).roundToInt().coerceIn(0, maxY)
        } else {
            (maxY - dp(prefs.bottomMargin)).coerceAtLeast(0)
        }
        lp.x = x
        lp.y = y

        val alpha = (prefs.bgOpacity / 100f * 255).toInt().coerceIn(0, 255)
        container.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(alpha, 0, 0, 0))
            if (editing) setStroke(dp(2), Color.argb(200, 76, 141, 255))
        }
        safeUpdate(lp)
        positionHandle()
    }

    fun setEditMode(on: Boolean) = main.post {
        if (editing == on) return@post
        editing = on
        CaptionBus.editMode.value = on
        val container = root ?: return@post
        val lp = params ?: return@post

        if (on) {
            // 把当前几何固化进 Prefs，否则 applyStyle() 会按「铺满宽度」重算，画面会跳
            if (lp.width <= 0 && container.width > 0) lp.width = container.width
            if (lp.height <= 0 && container.height > 0) lp.height = container.height
        }
        persistGeometry(lp)

        lp.flags = if (on) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        applyStyle()
        if (on) hideHandle() else showHandle()
        resetUnlockTimer()

        // 调整模式下把整个窗口排除在系统手势区之外，
        // 否则从右边缘往左拖会被识别成「返回」手势（实测踩到）
        main.post {
            if (on) {
                val w = container.width
                val h = container.height
                if (w > 0 && h > 0) {
                    container.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
                }
            } else {
                container.systemGestureExclusionRects = emptyList()
            }
        }
    }

    fun isEditing(): Boolean = editing

    // ------------------------------------------------------------ 触摸

    private fun onTouch(event: MotionEvent): Boolean {
        if (!editing) return false
        val lp = params ?: return false
        scaleDetector.onTouchEvent(event)
        resetUnlockTimer()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragStartX = lp.x
                dragStartY = lp.y
                dragStartW = lp.width
                dragStartH = lp.height

                val edge = dp(EDGE_DP)
                val onRight = event.rawX >= lp.x + lp.width - edge
                val onBottom = event.rawY >= lp.y + lp.height - edge
                gesture = when {
                    onRight && onBottom -> GESTURE_RESIZE_BOTH
                    onRight -> GESTURE_RESIZE_W
                    onBottom -> GESTURE_RESIZE_H
                    else -> GESTURE_MOVE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    when (gesture) {
                        GESTURE_MOVE -> {
                            lp.x = dragStartX + dx
                            lp.y = dragStartY + dy
                        }
                        GESTURE_RESIZE_W -> {
                            lp.width = (dragStartW + dx)
                                .coerceIn(dp(140), (screenSize().x - lp.x).coerceAtLeast(dp(140)))
                        }
                        GESTURE_RESIZE_H -> {
                            lp.height = (dragStartH + dy)
                                .coerceIn(dp(48), (screenSize().y - lp.y).coerceAtLeast(dp(48)))
                        }
                        else -> {
                            lp.width = (dragStartW + dx)
                                .coerceIn(dp(140), (screenSize().x - lp.x).coerceAtLeast(dp(140)))
                            lp.height = (dragStartH + dy)
                                .coerceIn(dp(48), (screenSize().y - lp.y).coerceAtLeast(dp(48)))
                        }
                    }
                    clampInto(lp)
                    safeUpdate(lp)
                    positionHandle()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistGeometry(lp)
                positionHandle()
                gesture = GESTURE_MOVE
            }
        }
        return true
    }

    /**
     * 拖动钳位：至少留 [MIN_VISIBLE] 在屏幕内即可，而不是原来的「最多居中」。
     * 原来横屏时如果宽度接近屏宽，x 的可动范围会塌缩成一个点，所以拖不动。
     */
    private fun clampInto(lp: WindowManager.LayoutParams) {
        val size = screenSize()
        val keep = minOf(dp(MIN_VISIBLE_DP), lp.width)
        lp.x = lp.x.coerceIn(keep - lp.width, size.x - keep)
        lp.y = lp.y.coerceIn(0, (size.y - keep).coerceAtLeast(0))
    }

    private fun persistGeometry(lp: WindowManager.LayoutParams) {
        prefs.boxWidthDp = px2dp(lp.width)
        prefs.boxHeightDp = px2dp(lp.height)
        val size = screenSize()
        val maxX = (size.x - lp.width).coerceAtLeast(1)
        val maxY = (size.y - lp.height).coerceAtLeast(1)
        prefs.posXFrac = (lp.x.toFloat() / maxX).coerceIn(0f, 1f)
        prefs.posYFrac = (lp.y.toFloat() / maxY).coerceIn(0f, 1f)
    }

    private fun resetUnlockTimer() {
        main.removeCallbacks(unlockRunnable)
        if (editing) main.postDelayed(unlockRunnable, EDIT_IDLE_MS)
    }

    private fun safeUpdate(lp: WindowManager.LayoutParams) {
        val v = root ?: return
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 解锁握把

    private fun addHandle() {
        if (handle != null) return
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_grip)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(178, 10, 13, 18))
                setStroke(dp(1), Color.argb(150, 76, 141, 255))
            }
            val p = dp(7)
            setPadding(p, p, p, p)
            alpha = 0.62f
            setOnClickListener { setEditMode(true) }
        }
        val lp = WindowManager.LayoutParams(
            dp(HANDLE_DP), dp(HANDLE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        handle = iv
        handleParams = lp
        try {
            wm.addView(iv, lp)
        } catch (t: Throwable) {
            android.util.Log.e("LiveCaption", "handle addView failed", t)
            handle = null
            handleParams = null
            return
        }
        positionHandle()
        if (editing) hideHandle()
    }

    private fun removeHandle() {
        val h = handle ?: return
        try {
            wm.removeView(h)
        } catch (_: Throwable) {
        }
        handle = null
        handleParams = null
    }

    private fun showHandle() {
        val h = handle ?: return
        val lp = handleParams ?: return
        h.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            wm.updateViewLayout(h, lp)
        } catch (_: Throwable) {
        }
        positionHandle()
    }

    private fun hideHandle() {
        val h = handle ?: return
        val lp = handleParams ?: return
        h.visibility = View.GONE
        // 光设 GONE 不够保险，一并关掉触摸，免得残留一个看不见的点击区
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.updateViewLayout(h, lp)
        } catch (_: Throwable) {
        }
    }

    /** 握把贴在字幕条右上角外侧；屏幕放不下时改放到框内右下角 */
    private fun positionHandle() {
        val h = handle ?: return
        val lp = handleParams ?: return
        val box = params ?: return
        val size = screenSize()

        val hs = dp(HANDLE_DP)
        var x = box.x + box.width - hs / 2
        var y = box.y - hs - dp(6)
        if (y < dp(4)) y = box.y + dp(4)
        x = x.coerceIn(0, (size.x - hs).coerceAtLeast(0))
        y = y.coerceIn(0, (size.y - hs).coerceAtLeast(0))

        lp.x = x
        lp.y = y
        try {
            wm.updateViewLayout(h, lp)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 文本

    fun update(stable: String, partial: String) = main.post {
        val tv = textView ?: return@post
        if (stable.isEmpty() && partial.isEmpty()) return@post

        tv.setTextColor(Color.WHITE)
        val sb = SpannableStringBuilder()
        if (stable.isNotEmpty()) {
            val start = sb.length
            sb.append(stable)
            sb.setSpan(
                ForegroundColorSpan(Color.WHITE),
                start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (partial.isNotEmpty()) sb.append(' ')
        }
        if (partial.isNotEmpty()) {
            val start = sb.length
            sb.append(partial)
            sb.setSpan(
                ForegroundColorSpan(Color.argb(176, 255, 255, 255)),
                start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tv.text = sb
        fadeTo(1f)
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun fadeTo(target: Float) {
        val v: View = root ?: return
        if (v.alpha == target) return
        ValueAnimator.ofFloat(v.alpha, target).apply {
            duration = 220
            addUpdateListener { v.alpha = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------ 工具

    /** 必须用 currentWindowMetrics，`resources.displayMetrics` 在旋转后可能是旧值，
     *  这正是「横屏拖不动 / 拉不长」的根因。 */
    private fun screenSize(): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        Point(b.width(), b.height())
    } else {
        val dm = context.resources.displayMetrics
        Point(dm.widthPixels, dm.heightPixels)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
    private fun px2dp(v: Int): Int = (v / context.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val HIDE_DELAY_MS = 4000L
        private const val EDIT_IDLE_MS = 30_000L
        private const val HANDLE_DP = 40
        private const val MIN_VISIBLE_DP = 56
        /** 边缘命中区宽度：在这个范围内按下就是拉伸，不是移动 */
        private const val EDGE_DP = 26

        private const val GESTURE_MOVE = 0
        private const val GESTURE_RESIZE_W = 1
        private const val GESTURE_RESIZE_H = 2
        private const val GESTURE_RESIZE_BOTH = 3
    }
}
