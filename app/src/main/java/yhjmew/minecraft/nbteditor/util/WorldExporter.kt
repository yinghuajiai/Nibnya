package yhjmew.minecraft.nbteditor.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把整个世界目录打包成 `.mcworld`（本质是 zip），可被 Minecraft 直接导入。
 *
 * 只有**完整的世界目录**（含 level.dat 与 db/）才能打包成 .mcworld ——
 * 备份里那种只有一半的（level_xxx.dat 或 db_xxx）不行，
 * 所以导出 .mcworld 是「选择世界」的功能，而不是备份的功能。
 */
object WorldExporter {

    /** .mcworld 默认保存位置（与 Exports 分开，便于用户找到可导入的文件） */
    fun defaultWorldDir(): File = File("/storage/emulated/0/Download/NbtEditor_Data/Worlds")

    /**
     * 生成文件名：`<世界名>.mcworld`（不带时间戳，便于识别）。
     * 重名时由 [uniqueFile] 自动追加 (1)(2)。
     */
    fun buildFileName(worldName: String?): String {
        val safe = (worldName ?: "world")
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").trim('_').ifEmpty { "world" }.take(40)
        return "$safe.mcworld"
    }

    /** 目标已存在时自动加 (1)(2)…，避免静默覆盖 */
    fun uniqueFile(dir: File, fileName: String): File {
        val f = File(dir, fileName)
        if (!f.exists()) return f
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var i = 1
        while (i < 1000) {
            val cand = File(dir, "$base($i).$ext")
            if (!cand.exists()) return cand
            i++
        }
        return f
    }

    /**
     * 打包世界目录。
     * 会跳过无意义的文件（LOCK/LOG 等 LevelDB 临时文件、.nomedia）。
     *
     * @param onProgress 已写入字节数 -> 总字节数；用于显示进度
     * @return 成功时返回生成的文件
     */
    fun packWorld(
        worldDir: File,
        destDir: File,
        fileName: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File? {
        if (!worldDir.exists() || !worldDir.isDirectory) return null
        return try {
            if (!destDir.exists() && !destDir.mkdirs()) return null
            val out = uniqueFile(destDir, fileName)
            val total = TrashStore.sizeOf(worldDir).coerceAtLeast(1L)
            var done = 0L

            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
                // ⚠️ .mcworld 要求 zip 根目录**直接**是 level.dat / db/ 等，
                // 不能再套一层世界文件夹，否则 Minecraft 无法识别导入。
                worldDir.walkTopDown().forEach { f ->
                    if (f == worldDir) return@forEach
                    val name = f.name
                    if (isSkippable(name)) return@forEach

                    val entryName = f.relativeTo(worldDir).path.replace('\\', '/')
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry("$entryName/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(f).use { input ->
                            val buf = ByteArray(64 * 1024)
                            var len = input.read(buf)
                            while (len > 0) {
                                zos.write(buf, 0, len)
                                done += len
                                onProgress(done, total)
                                len = input.read(buf)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            AppLogger.info("Export", "已导出 .mcworld: ${out.absolutePath} (${out.length()} 字节)")
            out
        } catch (e: Exception) {
            AppLogger.warn("Export", "打包 .mcworld 失败: ${e.message}")
            null
        }
    }

    /** LevelDB 临时/锁文件与元数据噪音，打包时跳过 */
    private fun isSkippable(name: String): Boolean = when {
        name == ".nomedia" -> true
        name == "LOCK" || name == "LOG" || name == "LOG.old" -> true
        else -> false
    }
}
