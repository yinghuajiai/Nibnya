package yhjmew.nibnya.util

import java.io.File

/**
 * 把**旧版数据目录**（`Download/NbtEditor_Data/`）迁移到新目录（`Download/Nibnya/`）。
 *
 * 从 Nibnya 1.0.4a 起改名 + 换包名，数据根目录也跟着改名，所以老用户的数据需要搬一次。
 * 设计原则（都是为了避免「迁移反而把数据弄丢」）：
 *
 * 1. **先完整到位，才允许删源**。任何一步失败都立即停止，源目录原样保留。
 * 2. **不覆盖**。新位置已有同名项时**跳过**（保留新数据），只搬「新位置没有的」。
 * 3. 优先 `renameTo`（同分区近乎瞬时），失败才退化为「复制 + 删源」。
 * 4. 只有**整个旧根都空了**才删掉旧根目录，否则保留并如实告知。
 */
object DataMigrator {

    /** 单个子目录的搬运结果 */
    data class DirResult(
        val name: String,
        val moved: Int,     // 成功搬运的条目数
        val skipped: Int,   // 新位置已存在而跳过的条目数
        val failed: Int     // 失败数
    )

    data class Report(
        val dirs: List<DirResult>,
        val rootRemoved: Boolean,
        val error: String? = null
    ) {
        val totalMoved: Int get() = dirs.sumOf { it.moved }
        val totalSkipped: Int get() = dirs.sumOf { it.skipped }
        val totalFailed: Int get() = dirs.sumOf { it.failed }
        val ok: Boolean get() = error == null && totalFailed == 0
    }

    /** 待迁移内容的统计（给弹窗显示用） */
    data class Stats(
        val totalItems: Int,
        val totalBytes: Long,
        val byDir: List<Pair<String, Int>>
    ) {
        val isEmpty: Boolean get() = totalItems == 0
    }

    // ============================================
    // 检测
    // ============================================

    fun legacyExists(): Boolean = AppDirs.legacyRoot().isDirectory

    /** 统计旧目录里有什么、多大（只扫一层，足够给用户看） */
    fun stats(): Stats {
        val byDir = mutableListOf<Pair<String, Int>>()
        var items = 0
        var bytes = 0L
        for (name in AppDirs.ALL_SUBDIRS) {
            val d = File(AppDirs.legacyRoot(), name)
            if (!d.isDirectory) {
                byDir.add(name to 0)
                continue
            }
            val children = d.listFiles()?.toList() ?: emptyList()
            byDir.add(name to children.size)
            items += children.size
            bytes += sizeOf(d)
        }
        return Stats(items, bytes, byDir)
    }

    // ============================================
    // 执行
    // ============================================

    /**
     * 执行迁移。**可能耗时**（大备份会真的复制文件），调用方应放在后台线程。
     */
    fun migrate(): Report {
        val legacy = AppDirs.legacyRoot()
        if (!legacy.isDirectory) return Report(emptyList(), false, error = null)

        // 确保新目录骨架存在（新建失败就直接放弃，不动旧数据）
        AppDirs.ensureLayout()
        val results = mutableListOf<DirResult>()
        var failure: String? = null

        for (name in AppDirs.ALL_SUBDIRS) {
            val src = File(legacy, name)
            if (!src.isDirectory) continue
            val dst = File(AppDirs.PUBLIC_ROOT, name)

            var moved = 0
            var skipped = 0
            var failed = 0

            val children = src.listFiles()?.toList() ?: emptyList()
            for (child in children) {
                val target = File(dst, child.name)
                if (target.exists()) {
                    // 新位置已有同名项 —— 不覆盖、不删除，跳过并如实计数
                    skipped++
                    continue
                }
                if (!dst.exists() && !dst.mkdirs()) {
                    failed++
                    continue
                }
                if (child.renameTo(target)) {
                    moved++
                    continue
                }
                // rename 失败（跨挂载点等）→ 复制成功后才删源
                if (copyRecursive(child, target)) {
                    if (deleteRecursive(child)) moved++ else failed++
                } else {
                    // 复制没成功：把可能写了一半的目标清掉，源保持不动
                    deleteRecursive(target)
                    failed++
                }
            }

            results.add(DirResult(name, moved, skipped, failed))
            if (failed > 0 && failure == null) failure = "$name 有 $failed 项迁移失败"
        }

        // 旧根空了才删；还有东西（含跳过的）就保留
        var rootRemoved = false
        val leftover = legacy.listFiles()?.toList() ?: emptyList()
        val allEmpty = leftover.all { it.isDirectory && (it.listFiles()?.isEmpty() != false) }
        if (allEmpty) {
            rootRemoved = leftover.all { it.delete() } && legacy.delete()
        }

        return Report(results, rootRemoved, failure)
    }

    // ============================================
    // 文件工具
    // ============================================

    private fun copyRecursive(src: File, dst: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dst.exists() && !dst.mkdirs()) return false
                src.listFiles()?.forEach { child ->
                    if (!copyRecursive(child, File(dst, child.name))) return false
                }
                true
            } else {
                dst.parentFile?.let { if (!it.exists() && !it.mkdirs()) return false }
                src.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteRecursive(f: File?): Boolean {
        if (f == null || !f.exists()) return true
        if (f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        }
        return f.delete()
    }

    private fun sizeOf(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        try {
            f.listFiles()?.forEach { total += sizeOf(it) }
        } catch (_: Exception) {
        }
        return total
    }

    // ============================================
    // 展示辅助
    // ============================================

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
