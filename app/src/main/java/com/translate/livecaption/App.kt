package com.translate.livecaption

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_CAPTURE,
            "字幕采集",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "实时字幕运行时的常驻通知"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_CAPTURE = "capture"
    }
}
