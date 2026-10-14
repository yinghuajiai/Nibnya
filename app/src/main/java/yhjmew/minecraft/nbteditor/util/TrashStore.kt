package yhjmew.minecraft.nbteditor.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站：删除存档时先把整个世界目录**移进来**，而不是直接抹掉。
 *
 * 目录结构：
 * ```
 * NbtEditor_Data/Trash/
 * └── 20261014_233045_我的生存世界/     ← 一次删除 = 一个文件夹
 *     ├── _trash_meta.json              ← 元数据（原始文件夹名/世界名/时间）
 *     └── world/                        ← 被移进来的整个世界目录
 * ```
 *
 * 这样 restore 时能放回原位；deleteForever 才真正删除。
 */
object TrashStore {

    const val DIR_NAME = "Trash"
    private const val META_NAME = "_trash_meta.json"
    private const val WORLD_SUB = "world"

    /** 默认自动清理天数（用户可在设置里改；<= 0 表示不自动清理） */
    const val DEFAULT_KEEP_DAYS = 30

    /** 回收站根目录（公共下载目录下，用户可用文件管理器看到） */
    fun trashRoot(): File = File("/storage/emulated/0/Download/NbtEditor_Data/$DIR_NAME")

    /** 一次删除的记录 */
    data class TrashItem(
        val dir: File,                  // Trash 下的那层文件夹
        val originalFolder: String,     // 原存档文件夹名（base64 ID）
        val worldName: String?,         // 世界名（可能读不到）
        val deletedAt: Long,            // 删除时间
        val hasBackups: Boolean,        // 当时是否连带移除了备份
        val hasDrafts: Boolean,         // 当时是否连带移除了草稿
        val sizeBytes: Long             // 占用体积
    ) {
        val label: String get() = worldName?.takeIf { it.isNotBlank() } ?: originalFolder

        fun timeText(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(deletedAt))

        /** 实际存放世界内容的位置 */
        fun worldDir(): File = File(dir, WORLD_SUB)
    }

    private fun ensureRoot(): File {
        val r = trashRoot()
        if (!r.exists()) r.mkdirs()
        return r
    }

    /** 生成一个不冲突的回收站目录名 `<时间戳>_<世界名>` */
    fun newSlotName(worldName: String?): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = (worldName ?: "world")
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").trim('_').ifEmpty { "world" }.take(30)
        return "${ts}_$safe"
    }

    fun slotDir(slotName: String): File = File(ensureRoot(), slotName)

    /** 写入元数据 */
    fun writeMeta(
        slot: File,
        originalFolder: String,
        worldName: String?,
        hasBackups: Boolean,
        hasDrafts: Boolean
    ) {
        try {
            if (!slot.exists()) slot.mkdirs()
            val o = JsonObject()
            o.addProperty("originalFolder", originalFolder)
            o.addProperty("worldName", worldName)
            o.addProperty("deletedAt", System.currentTimeMillis())
            o.addProperty("hasBackups", hasBackups)
            o.addProperty("hasDrafts", hasDrafts)
            File(slot, META_NAME).writeText(o.toString())
        } catch (e: Exception) {
            AppLogger.warn("Trash", "写入回收站元数据失败: ${e.message}")
        }
    }

    /** 列出回收站内容（按删除时间倒序） */
    fun list(): List<TrashItem> {
        return try {
            val root = trashRoot()
            if (!root.isDirectory) return emptyList()
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { parse(it) }
                ?.sortedByDescending { it.deletedAt }
                ?: emptyList()
        } catch (e: Exception) {
            AppLogger.warn("Trash", "列出回收站失败: ${e.message}")
            emptyList()
        }
    }

    fun count(): Int = try {
        trashRoot().listFiles()?.count { it.isDirectory } ?: 0
    } catch (_: Exception) {
        0
    }

    private fun parse(dir: File): TrashItem? {
        return try {
            val metaFile = File(dir, META_NAME)
            val o = if (metaFile.exists() && metaFile.length() > 0) {
                JsonParser.parseString(metaFile.readText()).asJsonObject
            } else JsonObject()

            val original = o.get("originalFolder")?.takeIf { !it.isJsonNull }?.asString
                ?: dir.name.substringAfter('_').ifEmpty { dir.name }

            TrashItem(
                dir = dir,
                originalFolder = original,
                worldName = o.get("worldName")?.takeIf { !it.isJsonNull }?.asString,
                deletedAt = o.get("deletedAt")?.asLong
                    ?: dir.lastModified(),
                hasBackups = o.get("hasBackups")?.asBoolean ?: false,
                hasDrafts = o.get("hasDrafts")?.asBoolean ?: false,
                sizeBytes = sizeOf(dir)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 递归求体积 */
    fun sizeOf(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        try {
            f.listFiles()?.forEach { total += sizeOf(it) }
        } catch (_: Exception) {
        }
        return total
    }

    fun deleteForever(item: TrashItem) {
        try {
            deleteRecursive(item.dir)
            AppLogger.info("Trash", "已彻底删除: ${item.dir.name}")
        } catch (e: Exception) {
            AppLogger.warn("Trash", "彻底删除失败: ${e.message}")
        }
    }

    fun clearAll() {
        try {
            trashRoot().listFiles()?.forEach { deleteRecursive(it) }
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * 自动清理超过 [days] 天的条目。
     * @param days <= 0 表示不自动清理
     * @return 清理掉的数量
     */
    fun autoClean(days: Int): Int {
        if (days <= 0) return 0
        var n = 0
        try {
            val deadline = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            list().forEach {
                if (it.deletedAt > 0 && it.deletedAt < deadline) {
                    deleteForever(it); n++
                }
            }
            if (n > 0) AppLogger.info("Trash", "自动清理了 $n 个超过 $days 天的回收站条目")
        } catch (_: Exception) {
        }
        return n
    }
}
