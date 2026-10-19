package yhjmew.nibnya.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import yhjmew.nibnya.R

/**
 * 备份策略（开关 / 节流间隔 / 保留上限）。
 *
 * 设计上**刻意对齐草稿箱**（`DraftAutoSaver` + `DraftStore`），因为它那套已经验证好用：
 * - 开关 + 间隔（关闭 / 2 / 4 / 8 / 自定义）+ 单位（秒 / 分 / 时）+ 数量上限
 * - 超上限删最旧（对应 `DraftStore.pruneTo`）
 *
 * 与草稿箱的**唯一语义差别**：这里不是「定时落盘」，而是「保存存档前先留一份快照」，
 * 所以节流规则是「同一存档 + 同一类型，距上次备份不足间隔就跳过」。
 */
object BackupSettings {

    const val PREF_AUTO = "backup_auto"
    const val PREF_INTERVAL = "backup_interval"
    const val PREF_INTERVAL_UNIT = "backup_interval_unit"
    const val PREF_INTERVAL_LAST = "backup_interval_last"
    const val PREF_MAX = "backup_max"

    /** 默认 5 分钟 */
    const val DEFAULT_INTERVAL = 5
    /** 0 = 秒 */
    const val DEFAULT_UNIT = 1
    /** 默认保留 10 份（每个存档的每种类型各自计数） */
    const val DEFAULT_MAX = 10

    /** 间隔下拉可选值；-1 代表「自定义」 */
    val INTERVAL_VALUES = listOf(0, 2, 4, 8, -1)

    /** 上限下拉可选值；-1 代表「自定义」 */
    val MAX_VALUES = listOf(5, 10, 20, -1)

    /** 单位换算成秒 */
    fun unitFactor(unit: Int): Long = when (unit) {
        2 -> 3600L      // 小时
        1 -> 60L        // 分钟
        else -> 1L      // 秒
    }

    /** 自动备份是否开启（默认开） */
    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_AUTO, true)

    /** 节流间隔（毫秒）；<= 0 表示每次都备份 */
    fun intervalMs(context: Context): Long {
        val p = prefs(context)
        val interval = p.getInt(PREF_INTERVAL, DEFAULT_INTERVAL)
        if (interval <= 0) return 0L
        val unit = p.getInt(PREF_INTERVAL_UNIT, DEFAULT_UNIT)
        return interval.toLong() * unitFactor(unit) * 1000L
    }

    /** 保留上限；<= 0 表示不限制 */
    fun maxBackups(context: Context): Int = prefs(context).getInt(PREF_MAX, DEFAULT_MAX)

    private fun prefs(context: Context) =
        context.getSharedPreferences("nbt_config", Context.MODE_PRIVATE)

    // ============================================
    // 节流判定 + 上限清理
    // ============================================

    /**
     * 该存档 + 该类型距上次备份是否已经超过节流间隔。
     *
     * 「上次备份时间」**从备份文件名解析**（复用 [parseTimestamp]），
     * 不碰 `lastModified`（历史上用目录 mtime 显示备份时间出过错）。
     *
     * 好处：若用户把这个存档的备份清空了，时间自然重置，下次保存会立刻重新备份。
     *
     * @param isPlayerData true = 玩家数据（`db_*`），false = 世界数据（`level_*.dat`）
     */
    fun isDueForBackup(context: Context, folderName: String, isPlayerData: Boolean): Boolean {
        val wait = intervalMs(context)
        if (wait <= 0) return true          // 间隔设为「每次都备份」
        val dir = File(AppDirs.backups(), folderName)
        if (!dir.isDirectory) return true   // 从没备份过
        val prefix = if (isPlayerData) "db_" else "level_"
        val newest = dir.listFiles()
            ?.filter { it.name.startsWith(prefix) }
            ?.maxOfOrNull { parseTimestamp(it.name) }
            ?: 0L
        if (newest <= 0L) return true
        return System.currentTimeMillis() - newest >= wait
    }

    /**
     * 超出上限时删除该存档该类型最旧的几份。
     * 与 `DraftStore.pruneTo` 同构：`max <= 0` 表示不限制。
     *
     * ⚠️ 必须按类型分开计数：`db_*`（整个 db 目录）与 `level_*.dat`（单个文件）
     * 是两种东西，混在一起算会「改玩家数据把世界数据的备份挤掉」。
     */
    fun prune(context: Context, folderName: String, isPlayerData: Boolean): Int {
        val max = maxBackups(context)
        if (max <= 0) return 0
        return try {
            val dir = File(AppDirs.backups(), folderName)
            if (!dir.isDirectory) return 0
            val prefix = if (isPlayerData) "db_" else "level_"
            val items = dir.listFiles()?.filter { it.name.startsWith(prefix) } ?: return 0
            if (items.size <= max) return 0
            val doomed = items.sortedByDescending { parseTimestamp(it.name) }.drop(max)
            var n = 0
            doomed.forEach { f ->
                deleteRecursive(f)
                n++
                AppLogger.info("Backup", R.string.log_backup_prune, max, f.name)
            }
            n
        } catch (e: Exception) {
            AppLogger.warn("Backup", R.string.log_backup_prune_failed, e.message)
            0
        }
    }

    /** 解析备份文件名里的时间戳（毫秒）；0 = 解析失败 */
    fun parseTimestamp(fileName: String): Long = try {
        val body = fileName
            .removePrefix("level_").removePrefix("db_")
            .removeSuffix(".dat")
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        fmt.isLenient = false
        fmt.parse(body)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    /** 生成备份文件名用的时间戳（与 [parseTimestamp] 对应） */
    fun newTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }
}
