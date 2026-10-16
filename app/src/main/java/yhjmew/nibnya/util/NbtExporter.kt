package yhjmew.nibnya.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 NBT 内容导出为文件。
 *
 * 两种落点：
 * 1. **下载目录**（默认）：`/storage/emulated/0/Download/Nibnya/Exports/`
 *    需要「所有文件访问权限」；不可写时调用方应回退到 SAF。
 * 2. **用户自选目录**：通过 SAF（OpenDocumentTree）拿到的 tree uri，
 *    用 DocumentFile 写入，无需任何存储权限。
 */
object NbtExporter {

    /** 导出根目录（公共下载目录下，方便用户用文件管理器找到） */
    fun defaultExportDir(): File = AppDirs.exports()

    /** 文件扩展名 */
    fun extensionOf(format: Int): String = when (format) {
        NbtClipboard.FORMAT_SNBT -> "snbt"
        NbtClipboard.FORMAT_TEXT -> "txt"
        else -> "json"
    }

    /**
     * 生成带时间戳的文件名，避免覆盖。
     * 例如：`Inventory_20261010_233045.json`
     */
    fun buildFileName(nameHint: String?, format: Int): String {
        val safe = (nameHint ?: "nbt")
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { "nbt" }
            .take(40)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${safe}_$ts.${extensionOf(format)}"
    }

    /**
     * 直接写入某个目录（需可写）。
     * @return 写出的文件；失败返回 null
     */
    fun saveToDir(dir: File, fileName: String, content: String): File? {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            val f = File(dir, fileName)
            // 同名时自动加序号，避免覆盖已有导出
            val target = if (f.exists()) {
                val base = fileName.substringBeforeLast('.')
                val ext = fileName.substringAfterLast('.', "")
                var i = 1
                var cand: File
                do { cand = File(dir, "${base}_$i.$ext"); i++ } while (cand.exists() && i < 1000)
                cand
            } else f
            target.writeText(content)
            target
        } catch (e: Exception) {
            AppLogger.warn("Export", "写入导出目录失败: ${e.message}")
            null
        }
    }

    /**
     * 通过 SAF 写入用户选定的目录（无需存储权限）。
     * @return 是否成功
     */
    fun saveViaSaf(context: Context, treeUri: Uri, fileName: String, content: String): Boolean {
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            if (!dir.isDirectory || !dir.canWrite()) return false
            // 同名时改名，避免静默覆盖
            var name = fileName
            if (dir.findFile(name) != null) {
                val base = fileName.substringBeforeLast('.')
                val ext = fileName.substringAfterLast('.', "")
                var i = 1
                while (dir.findFile("${base}_$i.$ext") != null && i < 1000) i++
                name = "${base}_$i.$ext"
            }
            val doc = dir.createFile(mimeOf(fileName), name) ?: return false
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            AppLogger.warn("Export", "SAF 导出失败: ${e.message}")
            false
        }
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "snbt" -> "text/plain"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
