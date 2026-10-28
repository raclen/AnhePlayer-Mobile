package com.dd3boh.outertune.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    /**
     * 检测当前应用是否已忽略电池优化（即已设为无限制 / 允许后台完全活动）
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check battery optimization state", e)
            true
        }
    }

    /**
     * 引导用户将本应用加入电池优化白名单（设为无限制），避免息屏后被系统切断网络或冻结进程
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        // 优先使用直接请求弹窗（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogManager.i(TAG, "Launched ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, fallback to settings", e)
        }

        // 次选：打开系统电池优化应用列表页面
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogManager.i(TAG, "Launched ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, fallback to app details", e)
        }

        // 兜底：打开本应用的应用详情页（用户可在其内部配置耗电管理/后台权限）
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogManager.i(TAG, "Launched ACTION_APPLICATION_DETAILS_SETTINGS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open any battery settings page", e)
        }
    }

    fun getBatteryOptimizationStatusText(context: Context): String {
        return if (isIgnoringBatteryOptimizations(context)) {
            "已加白 (后台无限制)"
        } else {
            "未加白 (息屏可能受限断网)"
        }
    }
}
