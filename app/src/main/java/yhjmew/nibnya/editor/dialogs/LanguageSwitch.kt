package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.CheckBox
import android.widget.LinearLayout
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.nbt.NbtTranslator

/**
 * 切换语言。
 *
 * ⚠️ 这里有两处曾经踩过的坑，不要再犯：
 *
 * 1. **写 prefs 必须用 `commit()`（同步），不能用 `apply()`（异步）。**
 *    早期版本用 `apply()` 之后紧接着 `recreate()`，虽然多数情况能赶上，但一旦没赶上，
 *    重建后的界面仍是旧语言 —— 表现就是「点了切换、界面刷新了，但语言没变」。
 *
 * 2. **不要用 `Process.killProcess()` 来做「彻底重启」。**
 *    那在用户眼里就是**闪退**（早期版本照此实现，用户实测报「点确定后闪退」）。
 *    正确做法是 `Activity.recreate()`：ViewModel 虽然存活，但
 *    `NbtTranslator` 会在新 Activity 的 `onCreate` → `init(this)` 里重新按新语言加载，
 *    `refreshAdapter()` 也会用新 Activity 的 Context 重建列表/树适配器，
 *    所以翻译与列表都能正确切换到新语言。
 */

/** prefs 键：切换语言前的说明弹窗不再提示 */
internal const val PREF_SKIP_LANG_PROMPT = "skip_language_switch_prompt"

/** 按当前语言是否要展示说明弹窗，决定直接切还是先确认 */
internal fun MainActivity.showLanguageSwitchDialog(targetLang: String, settingsDialog: DialogInterface?) {
    val cb = CheckBox(this).apply {
        text = getString(R.string.cb_dont_ask_again)
        setPadding(48, 24, 48, 8)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(cb)
    }

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_switch_language))
        .setMessage(getString(R.string.msg_switch_language_restart))
        .setView(container)
        .setPositiveButton(getString(R.string.btn_switch)) { _, _ ->
            if (cb.isChecked) {
                prefs?.edit()?.putBoolean(PREF_SKIP_LANG_PROMPT, true)?.apply()
            }
            applyLanguageAndRestart(targetLang, settingsDialog)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

/**
 * 落盘语言并重建界面。
 *
 * 顺序很关键：
 * 1. 先把当前编辑存入草稿箱（重建后能找回）
 * 2. **同步**写入 `app_language`（`commit()`，确认落盘）
 * 3. 重置翻译缓存（清掉旧语言的 translations）
 * 4. `recreate()` —— 新 Activity 会用新语言重新初始化翻译与适配器
 */
internal fun MainActivity.applyLanguageAndRestart(targetLang: String, settingsDialog: DialogInterface?) {
    // 1) 抢救未保存的编辑
    runCatching { worldVM.persistSessionNow(this) }

    // 2) 同步落盘，确保重建时读到的就是新语言
    val ok = prefs?.edit()?.putString("app_language", targetLang)?.commit() ?: false
    if (!ok) {
        toast(getString(R.string.toast_language_switch_failed))
        return
    }

    // 3) 清翻译缓存（它自己会在新 onCreate 里重新加载）
    NbtTranslator.reset()

    // 4) 重建界面（不是杀进程！）
    settingsDialog?.dismiss()
    recreate()
}
