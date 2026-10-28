package com.dd3boh.outertune.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.dd3boh.outertune.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 应用内部日志与崩溃记录管理器。
 *
 * 功能：
 * 1. 注册全局未捕获异常捕获器，闪退时自动抓取完整调用栈（Stack Trace）、机型、系统版本、应用版本并持久化；
 * 2. 内存环形队列 + 文件滚动记录运行时重要事件与非致命报错；
 * 3. 提供一键复制到剪贴板与系统分享能力，便于用户将诊断信息反馈给开发者。
 */
object AppLogManager {
    private const val TAG = "AppLogManager"
    private const val CRASH_FILE_NAME = "crash_log.txt"
    private const val RUNTIME_LOG_FILE = "app_runtime.log"
    private const val MAX_RUNTIME_LOG_LINES = 1000
    private const val MAX_RUNTIME_FILE_SIZE = 1024 * 512 // 512 KB

    private var appContext: Context? = null
    private val memoryLogs = ConcurrentLinkedQueue<String>()

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(app, thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record uncaught exception", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        i(TAG, "AppLogManager installed. Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    private fun getCrashFile(context: Context): File {
        return File(context.filesDir, CRASH_FILE_NAME)
    }

    private fun getRuntimeLogFile(context: Context): File {
        return File(context.filesDir, RUNTIME_LOG_FILE)
    }

    /** 记录未捕获异常崩溃 */
    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val crashFile = getCrashFile(context)
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val crashReport = buildString {
            appendLine("=================== 崩溃报告 (Crash Report) ===================")
            appendLine("崩溃时间: $timeStr")
            appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.BUILD_TYPE}")
            appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND} ${Build.DEVICE})")
            appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.ID})")
            appendLine("支持架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("崩溃线程: ${thread.name} (id: ${thread.id}, priority: ${thread.priority})")
            appendLine("异常类型: ${throwable.javaClass.name}")
            appendLine("异常描述: ${throwable.message}")
            appendLine("------------------- 详细调用栈 (Stack Trace) -------------------")
            appendLine(throwable.stackTraceToString())
            appendLine("================================================================")
        }

        try {
            crashFile.writeText(crashReport, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report to file", e)
        }

        // 同时推入内存日志
        log("CRASH", TAG, "FATAL: ${throwable.message}", throwable)
    }

    /** 记录已知捕获但异常的事件（如 reportException） */
    fun recordNonFatal(throwable: Throwable, message: String? = null) {
        val msg = if (message != null) "$message: ${throwable.message}" else (throwable.message ?: throwable.javaClass.simpleName)
        e("NON_FATAL", msg, throwable)
    }

    /** 统一日志记录 */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = if (throwable != null) {
            "[$timeStr][$level][$tag] $message\n${throwable.stackTraceToString().trimEnd()}"
        } else {
            "[$timeStr][$level][$tag] $message"
        }

        // 写入环形内存队列
        memoryLogs.add(logLine)
        while (memoryLogs.size > MAX_RUNTIME_LOG_LINES) {
            memoryLogs.poll()
        }

        // 写入磁盘文件
        appContext?.let { ctx ->
            try {
                val file = getRuntimeLogFile(ctx)
                if (file.exists() && file.length() > MAX_RUNTIME_FILE_SIZE) {
                    val text = file.readText(Charsets.UTF_8)
                    val half = text.length / 2
                    file.writeText(text.substring(half), Charsets.UTF_8)
                }
                file.appendText(logLine + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                // 忽略写文件错误，避免次生异常
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        log("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        log("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        log("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        log("E", tag, message, throwable)
    }

    fun hasCrashLog(): Boolean {
        val ctx = appContext ?: return false
        val file = getCrashFile(ctx)
        return file.exists() && file.length() > 0
    }

    fun getCrashLog(): String? {
        val ctx = appContext ?: return null
        val file = getCrashFile(ctx)
        return if (file.exists() && file.length() > 0) {
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun clearCrashLog() {
        val ctx = appContext ?: return
        try {
            val file = getCrashFile(ctx)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete crash file", e)
        }
    }

    fun getRuntimeLog(): String {
        val ctx = appContext
        if (ctx != null) {
            val file = getRuntimeLogFile(ctx)
            if (file.exists() && file.length() > 0) {
                try {
                    return file.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    // fall back to memory
                }
            }
        }
        return memoryLogs.joinToString("\n")
    }

    fun clearRuntimeLog() {
        memoryLogs.clear()
        val ctx = appContext ?: return
        try {
            val file = getRuntimeLogFile(ctx)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear runtime log file", e)
        }
    }

    /** 复制文本到系统剪贴板 */
    fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String = "已复制到剪贴板，可直接粘贴发给开发者") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 调用系统分享 Intent 分享日志 */
    fun shareLog(context: Context, title: String, content: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, content)
            }
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 获取格式化的系统/环境诊断摘要 */
    fun getDiagnosticSummary(): String {
        return buildString {
            appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.BUILD_TYPE}")
            appendLine("应用包名: ${BuildConfig.APPLICATION_ID}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND} ${Build.PRODUCT})")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.ID})")
            appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            val ctx = appContext
            if (ctx != null) {
                val isIgnoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)
                appendLine("电池优化无限制状态: ${if (isIgnoring) "已开启 (推荐)" else "未开启 (受系统省电策略限制)"}")
            }
        }
    }
}
