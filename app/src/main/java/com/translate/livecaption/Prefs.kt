package com.translate.livecaption

import android.content.Context
import android.content.SharedPreferences

/**
 * 字幕样式与运行参数。对应 Windows 那边的
 * HKCU\SOFTWARE\Microsoft\LiveCaptions\UI（CaptionLanguage / 窗口尺寸 / 字号等）。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("livecaption", Context.MODE_PRIVATE)

    /** 字号，单位 sp */
    var fontSize: Int
        get() = sp.getInt(KEY_SIZE, 17)
        set(v) = sp.edit().putInt(KEY_SIZE, v).apply()

    /** 背景不透明度 0..100 */
    var bgOpacity: Int
        get() = sp.getInt(KEY_OPACITY, 72)
        set(v) = sp.edit().putInt(KEY_OPACITY, v).apply()

    /** 最多显示几行 */
    var maxLines: Int
        get() = sp.getInt(KEY_LINES, 2)
        set(v) = sp.edit().putInt(KEY_LINES, v).apply()

    /** 未手动调整位置时，距屏幕底部的距离（dp） */
    var bottomMargin: Int
        get() = sp.getInt(KEY_BOTTOM, 96)
        set(v) = sp.edit().putInt(KEY_BOTTOM, v).apply()

    /**
     * 大小写处理。模型词表本身是全大写（LibriSpeech 风格），
     * 所以默认给一个「句首大写」把观感拉回正常。
     * 0=原样 1=句首大写 2=全小写
     */
    var capsMode: Int
        get() = sp.getInt(KEY_CAPS, CaptionCase.MODE_SENTENCE)
        set(v) = sp.edit().putInt(KEY_CAPS, v).apply()

    /** 手动调整后的字幕条宽度（dp）；0 表示铺满屏幕宽度 */
    var boxWidthDp: Int
        get() = sp.getInt(KEY_BOX_W, 0)
        set(v) = sp.edit().putInt(KEY_BOX_W, v).apply()

    /** 手动调整后的字幕条高度（dp）；0 表示按行数自动算 */
    var boxHeightDp: Int
        get() = sp.getInt(KEY_BOX_H, 0)
        set(v) = sp.edit().putInt(KEY_BOX_H, v).apply()

    /** 当前选中的识别模型 id */
    var selectedModelId: String
        get() = sp.getString(KEY_MODEL, "bilingual") ?: "bilingual"
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    /** 是否给句末结果补标点（模型自带标点时不起作用） */
    var punctuate: Boolean
        get() = sp.getBoolean(KEY_PUNCT, true)
        set(v) = sp.edit().putBoolean(KEY_PUNCT, v).apply()

    /**
     * 手动调整后的位置，用「占可移动范围的比例」存（0..1），-1 表示未设置。
     *
     * 为什么用比例而不是 dp：竖屏拖到 80% 处，转到横屏后屏幕高度只剩 1080px，
     * 绝对值会让字幕条跑到屏幕外（用户实测踩到过）。比例存法在旋转后仍然落在 80% 处。
     */
    var posXFrac: Float
        get() = sp.getFloat(KEY_POS_X, -1f)
        set(v) = sp.edit().putFloat(KEY_POS_X, v).apply()

    var posYFrac: Float
        get() = sp.getFloat(KEY_POS_Y, -1f)
        set(v) = sp.edit().putFloat(KEY_POS_Y, v).apply()

    fun resetBox() {
        sp.edit()
            .putInt(KEY_BOX_W, 0)
            .putInt(KEY_BOX_H, 0)
            .putFloat(KEY_POS_X, -1f)
            .putFloat(KEY_POS_Y, -1f)
            .apply()
    }

    companion object {
        private const val KEY_SIZE = "font_size"
        private const val KEY_OPACITY = "bg_opacity"
        private const val KEY_LINES = "max_lines"
        private const val KEY_BOTTOM = "bottom_margin"
        private const val KEY_CAPS = "caps_mode"
        private const val KEY_MODEL = "asr_model_id"
        private const val KEY_PUNCT = "punctuate"
        private const val KEY_BOX_W = "box_w_dp"
        private const val KEY_BOX_H = "box_h_dp"
        // 注意：key 名必须和旧版（存 dp 的 int）区分开，否则 getFloat 读到旧 int 会抛 ClassCastException
        private const val KEY_POS_X = "pos_x_frac"
        private const val KEY_POS_Y = "pos_y_frac"
    }
}
