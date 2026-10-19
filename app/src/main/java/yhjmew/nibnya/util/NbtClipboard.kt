package yhjmew.nibnya.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import yhjmew.nibnya.nbt.SnbtConverter
import java.io.File
import yhjmew.nibnya.R

/**
 * 应用内 NBT 剪贴板。
 *
 * 为什么不用系统剪贴板就够：
 * - 系统剪贴板存的是**文本**，丢失类型信息（Byte/Short/Long 都变成数字），
 *   粘贴回来需要猜；应用内保留原始 JSON 结构才能精确还原。
 * - 系统剪贴板会被其他 App 覆盖。
 *
 * 因此这里把内容**落盘**（filesDir），进程被杀、App 重启后依然可用。
 */
object NbtClipboard {

    private const val FILE_NAME = "nbt_clipboard.json"

    /** 格式：写入系统剪贴板时用哪种文本形式 */
    const val FORMAT_JSON = 0
    const val FORMAT_SNBT = 1
    const val FORMAT_TEXT = 2

    private var cached: JsonElement? = null
    private var loaded = false

    // ============================================
    // 读写
    // ============================================

    /** 启动时调用一次；多次调用安全（只加载一次） */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists() && f.length() > 0) {
                cached = JsonParser.parseString(f.readText())
                AppLogger.info("Clipboard", R.string.log_clipboard_restored)
            }
        } catch (e: Exception) {
            AppLogger.warn("Clipboard", R.string.log_clipboard_read_failed, e.message)
        }
    }

    fun get(context: Context): JsonElement? {
        init(context)
        return cached
    }

    fun isEmpty(context: Context): Boolean = get(context) == null

    /** 设置内容：同时写内存与磁盘 */
    fun set(context: Context, element: JsonElement) {
        cached = element.deepCopy()
        save(context)
    }

    fun clear(context: Context) {
        cached = null
        try {
            File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }
    }

    private fun save(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).writeText(cached?.toString() ?: "")
        } catch (e: Exception) {
            AppLogger.warn("Clipboard", R.string.log_clipboard_save_failed, e.message)
        }
    }

    // ============================================
    // 与系统剪贴板互通
    // ============================================

    /** 把内容按指定格式写入系统剪贴板（供其他 App 粘贴） */
    fun writeToSystemClipboard(context: Context, element: JsonElement, format: Int) {
        try {
            val text = formatAs(element, format)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NBT", text))
        } catch (e: Exception) {
            AppLogger.warn("Clipboard", R.string.log_clipboard_write_sys_failed, e.message)
        }
    }

    fun formatAs(element: JsonElement, format: Int): String = when (format) {
        FORMAT_SNBT -> SnbtConverter.toSnbt(element)
        FORMAT_TEXT -> toPlainText(element)
        else -> element.toString()
    }

    /**
     * 从系统剪贴板读取并解析（自动识别内部 JSON / SNBT）。
     * @return 成功返回解析结果，失败返回 null
     */
    fun readFromSystemClipboard(context: Context): JsonElement? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!cm.hasPrimaryClip()) return null
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
            SnbtConverter.parseAny(text)
        } catch (e: Exception) {
            AppLogger.warn("Clipboard", R.string.log_clipboard_read_sys_failed, e.message)
            null
        }
    }

    // ============================================
    // 纯文本（可读）导出
    // ============================================

    private fun toPlainText(element: JsonElement): String {
        val sb = StringBuilder()
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.has("t")) {
                appendWrapper(sb, "", obj)
            } else {
                for (k in obj.keySet().sorted()) {
                    sb.append(k).append(": ")
                    val v = obj.get(k)
                    if (v.isJsonObject) appendWrapper(sb, "  ", v.asJsonObject) else sb.append(v)
                    sb.append('\n')
                }
            }
        } else sb.append(element.toString())
        return sb.toString()
    }

    private fun appendWrapper(sb: StringBuilder, indent: String, w: JsonObject) {
        val t = w.get("t")?.asInt ?: 10
        val v = w.get("v")
        when (t) {
            9 -> {
                val arr = if (v != null && v.isJsonArray) v.asJsonArray else JsonArray()
                sb.append("[List ").append(arr.size()).append("]\n")
                val it = w.get("itemType")?.asInt ?: 10
                for (i in 0 until arr.size()) {
                    sb.append(indent).append("  [").append(i).append("] ")
                    val e = arr.get(i)
                    if (e.isJsonObject) appendWrapper(sb, "$indent  ", wrapBare(e.asJsonObject, it))
                    else sb.append(e.toString())
                    sb.append('\n')
                }
            }
            10 -> {
                val obj = if (v != null && v.isJsonObject) v.asJsonObject else JsonObject()
                sb.append("{Compound ").append(obj.size()).append("}\n")
                for (k in obj.keySet().sorted()) {
                    sb.append(indent).append("  ").append(k).append(": ")
                    val e = obj.get(k)
                    if (e.isJsonObject) appendWrapper(sb, "$indent  ", e.asJsonObject)
                    else sb.append(e.toString())
                    sb.append('\n')
                }
            }
            else -> sb.append(SnbtConverter.toSnbt(w))
        }
    }

    /** 列表元素是裸值，套一层包装方便复用 appendWrapper */
    private fun wrapBare(bare: JsonObject, itemType: Int): JsonObject {
        if (bare.has("t")) return bare
        val w = JsonObject()
        w.addProperty("t", itemType)
        w.add("v", bare)
        return w
    }
}
