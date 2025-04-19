package com.luoxiaohei.lowlatencyinput

import android.app.Application
import com.topjohnwu.superuser.Shell
import android.util.Log

class MainApplication : Application() {

    private val TAG = "MainApplication"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate - Configuring libsu...")

        // libsu 配置
        try {
             Shell.enableVerboseLogging = true // 启用详细日志
             Shell.setDefaultBuilder(Shell.Builder.create()
                 .setTimeout(10) // 设置超时（秒）
             )
             Log.d(TAG, "libsu configuration applied.")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring libsu", e)
        }
    }
} 