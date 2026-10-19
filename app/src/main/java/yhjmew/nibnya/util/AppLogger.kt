package yhjmew.nibnya.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import yhjmew.nibnya.R

/**
 * 应用内日志。
 *
 * ## 为什么日志要存「资源 ID + 参数」而不是成品字符串
 * 日志正文需要跟随**应用内设定的语言**（中文用户看中文、英文用户看英文）。
 * 如果在写入时就 `getString()` 定型，那么用户切到英文后，之前记录的那批日志
 * 仍是旧语言 —— 导出的文件会是中英混杂。所以这里把资源 ID 与参数**存起来**，
 * 等到真正渲染（显示 / 导出 / 复制）时才用**当时的语言**格式化。
 */
object AppLogger {
    private const val MAX_ENTRIES = 500

    /** 应用 Context，由 `MainActivity.onCreate` 注入；用于渲染时取资源 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 一条日志。
     * @param resId 资源 ID（本地化文案）；为 0 表示用 [raw]
     * @param args  资源占位符参数（渲染时才代入）
     * @param raw   已经成型的字符串（调用方本就有成品文本时用，如异常 message）
     */
    private class Entry(
        val time: String,
        val level: String,
        val tag: String,
        val resId: Int,
        val args: Array<out Any?>,
        val raw: String?
    )

    private val entries = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ============================================
    // 记录：资源 ID 版（推荐，随语言本地化）
    // ============================================

    fun info(tag: String, resId: Int, vararg args: Any?) = append("INFO", tag, resId, args, null)
    fun warn(tag: String, resId: Int, vararg args: Any?) = append("WARN", tag, resId, args, null)
    fun error(tag: String, resId: Int, vararg args: Any?) = append("ERROR", tag, resId, args, null)

    // ============================================
    // 记录：成品字符串版（异常信息这类无法资源化的场景）
    // ============================================

    fun info(tag: String, message: String) = append("INFO", tag, 0, emptyArray(), message)
    fun warn(tag: String, message: String) = append("WARN", tag, 0, emptyArray(), message)
    fun error(tag: String, message: String) = append("ERROR", tag, 0, emptyArray(), message)

    fun error(tag: String, message: String, throwable: Throwable) {
        val detail = if (throwable.message != null) "$message: ${throwable.message}" else message
        append("ERROR", tag, 0, emptyArray(), detail)
    }

    /** 资源文案 + 异常详情 */
    fun error(tag: String, resId: Int, throwable: Throwable, vararg args: Any?) {
        val text = format(resId, args) ?: ""
        val detail = if (throwable.message != null) "$text: ${throwable.message}" else text
        append("ERROR", tag, 0, emptyArray(), detail)
    }

    private fun append(
        level: String,
        tag: String,
        resId: Int,
        args: Array<out Any?>,
        raw: String?
    ) {
        entries.addLast(Entry(timeFormat.format(Date()), level, tag, resId, args, raw))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** 用当前语言格式化；拿不到 Context 时退回占位提示（不会崩） */
    private fun format(resId: Int, args: Array<out Any?>): String? {
        if (resId == 0) return null
        val ctx = appContext ?: return "[#$resId]"
        return try {
            ctx.getString(resId, *args)
        } catch (_: Throwable) {
            "[#$resId]"
        }
    }

    // ============================================
    // 渲染
    // ============================================

    /**
     * 组装完整日志：标题 + 时间 + **设备信息** + 全部日志条目。
     * 标题、设备信息标签、**以及每条日志正文**都按应用语言渲染。
     */
    fun getLogs(): String {
        val ctx = appContext?.let { DeviceInfo.localizedContext(it) }
        val sb = StringBuilder()

        sb.appendLine("====== ${ctx?.getString(R.string.log_header) ?: "Nibnya Logs"} ======")
        sb.appendLine("${ctx?.getString(R.string.log_time) ?: "Time"}: " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("==============================")

        if (ctx != null) {
            sb.appendLine("${ctx.getString(R.string.log_device_info)}:")
            sb.append(DeviceInfo.collect(ctx))
            sb.appendLine("==============================")
        }

        if (entries.isNotEmpty()) {
            sb.appendLine("${ctx?.getString(R.string.log_entries) ?: "Logs"}:")
            entries.forEach { e ->
                val body = e.raw ?: format(e.resId, e.args) ?: ""
                sb.appendLine("[${e.time}][${e.level}][${e.tag}] $body")
            }
        }
        return sb.toString()
    }

    fun clear() {
        entries.clear()
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
}
