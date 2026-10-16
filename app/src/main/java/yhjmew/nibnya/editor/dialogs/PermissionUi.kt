package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.core.net.toUri
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R

/**
 * 「所有文件访问权限」的**唯一**申请入口。
 *
 * 设计：**永远先让用户读完说明再跳系统设置**。
 *
 * 背景（2026-10-15 修）：旧代码在 `MainActivity.onCreate` 里除了这个说明弹窗，
 * 还**额外**调了一次 `WorldViewModel.checkStoragePermission()` —— 那个函数
 * 没有任何弹窗就 `startActivity` 跳系统设置，于是「说明弹窗刚出现就被设置页顶掉」，
 * 用户根本没机会读完。
 *
 * 现在：
 * - 启动时**不再**自动申请（推迟到真正要读写的时候：打开存档 / 保存 / 切换路径 / 手动点按钮）
 * - 任何一处申请都走这里，先弹说明，用户点「前往授予」才跳转
 */

/** prefs 键：用户勾了「不再提示」，之后直接跳系统设置 */
internal const val PREF_SKIP_ALL_FILES_PROMPT = "skip_all_files_prompt"

/**
 * 需要「所有文件访问」时调用。
 * 已授权 → 直接返回 true；未授权 → 弹说明框并返回 false。
 *
 * @param reason 触发原因（会拼在正文前，告诉用户「为什么现在要它」）；可为 null
 */
internal fun MainActivity.askAllFilesPermission(reason: String? = null): Boolean {
    if (Build.VERSION.SDK_INT < 30) return true
    if (Environment.isExternalStorageManager()) return true

    // 用户此前选了「不再提示」→ 直接跳，不再啰嗦
    if (prefs?.getBoolean(PREF_SKIP_ALL_FILES_PROMPT, false) == true) {
        openAllFilesSettings()
        return false
    }

    val msg = buildString {
        if (!reason.isNullOrBlank()) {
            append(reason).append("\n\n")
        }
        append(getString(R.string.msg_all_files_permission_required))
    }

    val cb = CheckBox(this).apply {
        text = getString(R.string.cb_dont_ask_again)
        setPadding(48, 24, 48, 8)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(cb)
    }

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_need_all_files_permission))
        .setMessage(msg)
        .setView(container)
        .setPositiveButton(getString(R.string.btn_go_grant)) { _, _ ->
            if (cb.isChecked) prefs?.edit()?.putBoolean(PREF_SKIP_ALL_FILES_PROMPT, true)?.apply()
            openAllFilesSettings()
        }
        .setNegativeButton(getString(R.string.btn_later), null)
        .show()
    return false
}

/** 真正跳系统设置（只有用户确认后才会走到这里） */
private fun MainActivity.openAllFilesSettings() {
    try {
        startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:$packageName".toUri()
            }
        )
    } catch (_: Exception) {
        toast(getString(R.string.toast_open_settings_failed))
    }
}
