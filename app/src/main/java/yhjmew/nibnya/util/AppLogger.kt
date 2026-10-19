package yhjmew.nibnya.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val logEntries = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_ENTRIES = 500

    /**
     * 应用 Context，由 `MainActivity.onCreate` 注入。
     * 用于生成日志头部（设备信息 + 标题文案）。
     * 为空时（极早期日志）退回纯英文硬编码文案，保证任何情况下都不会崩。
     */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun info(tag: String, message: String) {
        append("INFO", tag, message)
    }

    fun warn(tag: String, message: String) {
        append("WARN", tag, message)
    }

    fun error(tag: String, message: String) {
        append("ERROR", tag, message)
    }

    private fun append(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        logEntries.add("[$timestamp][$level][$tag] $message")
        if (logEntries.size > MAX_ENTRIES) {
            logEntries.removeAt(0)
        }
    }

    /**
     * 组装完整日志：标题 + 时间 + **设备信息** + 全部日志条目。
     *
     * ⚠️ 标题与设备信息标签**跟随应用内设定的语言**（DeviceInfo.localizedContext），
     * 所以中文用户导出的是中文版、英文用户导出的是英文版。
     * 日志正文本身由各处调用点写入（多为中文），不在此翻译。
     */
    fun getLogs(): String {
        val ctx = appContext?.let { DeviceInfo.localizedContext(it) }
        val sb = StringBuilder()

        // 标题（原来是硬编码的「NBT Editor Logs」—— 旧应用名，已废弃）
        sb.appendLine("====== ${ctx?.getString(yhjmew.nibnya.R.string.log_header) ?: "Nibnya Logs"} ======")
        sb.appendLine("${ctx?.getString(yhjmew.nibnya.R.string.log_time) ?: "Time"}: " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("==============================")

        if (ctx != null) {
            sb.appendLine("${ctx.getString(yhjmew.nibnya.R.string.log_device_info)}:")
            sb.append(DeviceInfo.collect(ctx))
            sb.appendLine("==============================")
        }

        if (logEntries.isNotEmpty()) {
            sb.appendLine("${ctx?.getString(yhjmew.nibnya.R.string.log_entries) ?: "Logs"}:")
            logEntries.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    fun clear() {
        logEntries.clear()
    }

    fun exportToFile(): File? {
        val logs = getLogs()
        if (logs.isEmpty()) return null
        return try {
            val dir = AppDirs.crashLogs()
            if (!dir.exists()) dir.mkdirs()
            val fileName = "debug-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.log"
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(logs.toByteArray()) }
            file
        } catch (_: Exception) {
            null
        }
    }
    fun error(tag: String, message: String, throwable: Throwable) {
        val detail = if (throwable.message != null) "$message: ${throwable.message}" else message
        append("ERROR", tag, detail)
    }
}
