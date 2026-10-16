package yhjmew.nibnya.util

import java.io.File

/**
 * 应用所有数据目录的**唯一**定义处。
 *
 * 以前这些路径散落在 8 个文件里（MainActivity / WorldViewModel / TrashStore /
 * NbtExporter / WorldExporter / AppLogger / CrashHandler），改一次名字要改一圈，
 * 而且极易改漏 —— 统一收到这里。
 *
 * 公共目录布局（用户可用文件管理器直接查看 / 自行清理）：
 * ```
 * Download/Nibnya/
 * ├── Works/        工作副本（level.dat 与 working_db_*）
 * ├── Backups/      备份（按存档文件夹名分目录）
 * ├── Exports/      文本导出（JSON / SNBT / TXT）
 * ├── Worlds/       .mcworld 导出
 * ├── Trash/        回收站
 * ├── Crash_Logs/   崩溃日志
 * └── Bridge/       Shizuku 中转（含 .nomedia）
 * ```
 *
 * ⚠️ 这里只负责「路径是什么」，不负责「目录该不该存在」以外的事。
 */
object AppDirs {

    /** 公共数据根目录 */
    const val PUBLIC_ROOT = "/storage/emulated/0/Download/Nibnya/"

    /**
     * 旧版数据根目录。
     * **只用于「迁移」与读取旧数据**，新代码不得写入这里。
     */
    const val LEGACY_PUBLIC_ROOT = "/storage/emulated/0/Download/NbtEditor_Data/"

    const val WORKS = "Works"
    const val BACKUPS = "Backups"
    const val EXPORTS = "Exports"
    const val WORLDS = "Worlds"
    const val TRASH = "Trash"
    const val CRASH_LOGS = "Crash_Logs"
    const val BRIDGE = "Bridge"

    /** 迁移时需要照顾的全部子目录名（顺序即展示顺序） */
    val ALL_SUBDIRS = listOf(WORKS, BACKUPS, EXPORTS, WORLDS, TRASH, CRASH_LOGS, BRIDGE)

    // ============================================
    // 路径（不创建）
    // ============================================

    fun publicRoot(): File = File(PUBLIC_ROOT)
    fun works(): File = File(PUBLIC_ROOT, WORKS)
    fun backups(): File = File(PUBLIC_ROOT, BACKUPS)
    fun exports(): File = File(PUBLIC_ROOT, EXPORTS)
    fun worlds(): File = File(PUBLIC_ROOT, WORLDS)
    fun trash(): File = File(PUBLIC_ROOT, TRASH)
    fun crashLogs(): File = File(PUBLIC_ROOT, CRASH_LOGS)
    fun bridge(): File = File(PUBLIC_ROOT, BRIDGE)

    /** 旧版根目录（迁移用） */
    fun legacyRoot(): File = File(LEGACY_PUBLIC_ROOT)

    // ============================================
    // 创建
    // ============================================

    /** 确保公共目录骨架存在；失败不抛异常（可能没有「所有文件访问」权限） */
    fun ensureLayout() {
        listOf(
            publicRoot(), works(), backups(), exports(), worlds(), trash(), crashLogs(), bridge()
        ).forEach { runCatching { if (!it.exists()) it.mkdirs() } }
    }
}
