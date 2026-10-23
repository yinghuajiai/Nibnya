package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.util.DataMigrator

/**
 * 旧数据目录迁移（`Download/NbtEditor_Data/` → `Download/Nibnya/`）。
 *
 * 1.0.4a 起改名 + 换包名，数据根目录也跟着改名，所以老用户的数据需要搬一次。
 *
 * 两条入口：
 * - **首次启动自动检测**：`MainActivity.onCreate` 里调 [maybePromptDataMigration]，
 *   只提示一次（选择记进 prefs）
 * - **设置页手动**：`startDataMigration(manual = true)`
 *
 * 无论哪条路，**都不会自动删旧目录** —— 只有全部搬空才会删。
 */

/**
 * 启动时检查旧数据目录。
 *
 * 策略（2026-10-15 调整）：**只要旧目录里还有东西就提醒**，不再「只提醒一次」。
 * 原因：原先「只提醒一次」会让「第一次被别的弹窗挡住 / 用户随手点了暂不」变成**永久不再提醒**，
 * 用户就只能自己找到设置里的入口。
 * 旧数据一旦迁完（或用户自己清空），旧目录就空了，自然不再打扰。
 */
internal fun MainActivity.maybePromptDataMigration() {
    Thread {
        val stats = runCatching {
            if (!DataMigrator.legacyExists()) return@runCatching null
            DataMigrator.stats().takeIf { !it.isEmpty }
        }.getOrNull()
        if (stats == null) return@Thread
        runOnUiThread { showMigrateDialog(stats) }
    }.start()
}

private fun MainActivity.showMigrateDialog(stats: DataMigrator.Stats) {
    val detail = buildString {
        stats.byDir.forEach { (name, count) ->
            if (count > 0) append("· ").append(name).append(": ").append(count).append('\n')
        }
        append('\n').append(DataMigrator.formatSize(stats.totalBytes))
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_migrate_data))
        .setMessage(getString(R.string.msg_migrate_found, detail))
        .setPositiveButton(getString(R.string.btn_migrate_now)) { _, _ -> startDataMigration(manual = false) }
        .setNegativeButton(getString(R.string.btn_migrate_later), null)
        .show()
}

/** 后台执行迁移，完成后 Toast 报告结果 */
internal fun MainActivity.startDataMigration(manual: Boolean) {
    Thread {
        val hasLegacy = DataMigrator.legacyExists()
        if (!hasLegacy) {
            runOnUiThread { toast(getString(R.string.msg_migrate_none_left)) }
            return@Thread
        }
        val stats = DataMigrator.stats()
        if (stats.isEmpty) {
            runOnUiThread { toast(getString(R.string.msg_migrate_nothing)) }
            return@Thread
        }
        runOnUiThread { showProgressDialog(getString(R.string.title_migrate_data), getString(R.string.status_migrating)) }

        val report = runCatching { DataMigrator.migrate() }.getOrElse {
            DataMigrator.Report(emptyList(), false, error = it.message ?: "unknown")
        }

        Handler(Looper.getMainLooper()).post {
            dismissProgressDialog()
            val msg = if (report.totalFailed > 0 || report.error != null) {
                getString(R.string.msg_migrate_partial, report.totalMoved, report.totalSkipped, report.totalFailed)
            } else {
                getString(R.string.msg_migrate_done, report.totalMoved, report.totalSkipped)
            }
            toast(msg)
        }
    }.start()
}
