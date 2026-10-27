package com.translate.livecaption.capture

import kotlinx.coroutines.flow.MutableStateFlow

enum class CaptionState {
    IDLE,        // 未运行
    PREPARING,   // 加载模型 / 建立采集
    RUNNING,     // 正常识别中
    NO_AUDIO,    // 采到了静音（多半是目标应用禁止被录制）
    ERROR
}

/**
 * 进程内状态总线：Service 写，Activity 读。
 * 单进程架构下没必要上 Binder/AIDL。
 */
object CaptionBus {
    val state = MutableStateFlow(CaptionState.IDLE)
    val message = MutableStateFlow("")
    /** 最近识别到的一行，仅用于 Activity 里做实时预览 */
    val line = MutableStateFlow("")
    /** 输入电平 0..1，用于判断是否真的有声音 */
    val level = MutableStateFlow(0f)
    /** 字幕条是否处于「可拖动/缩放」的调整模式 */
    val editMode = MutableStateFlow(false)
}
