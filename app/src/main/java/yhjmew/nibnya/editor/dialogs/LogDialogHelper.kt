package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R

/**
 * 全局统一的「错误 / 日志」弹窗。
 *
 * 之前应用里各处的错误提示写法五花八门（有的 `setMessage` 一行字、
 * 有的只有「关闭」、有的连复制都没有），用户看不全也复制不出来。
 * 这里统一成一种形态：
 *
 * ```
 * ┌──────────────────────────────┐
 * │  错误                         │
 * │  ┌────────────────────────┐  │
 * │  │ 详细日志（等宽、可滚动、  │  │  ← 长按可选中复制
 * │  │ 长按可选中复制）         │  │
 * │  └────────────────────────┘  │
 * │   复制      尝试修复     取消  │  ← 复制恒有；动作/取消可选
 * └──────────────────────────────┘
 * ```
 *
 * @param title      标题（如「错误」「数据库损坏」）
 * @param detail     中间展示的详细日志；为空时显示「未知」
 * @param actionText 可选的动作按钮文案（如「尝试修复」「前往设置」）
 * @param onAction   动作按钮回调；[actionText] 与 [onAction] 都非空才显示该按钮
 * @param cancelText 取消按钮文案，默认「取消」
 * @param onCancel   取消按钮回调
 * @param cancelable 是否允许点外部/返回键关闭，默认 true
 *                   （数据库损坏这类必须让用户明确选择的场景传 false）
 */
fun MainActivity.showLogDialog(
    title: String,
    detail: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    cancelText: String? = null,
    onCancel: (() -> Unit)? = null,
    cancelable: Boolean = true
) {
    val body = detail.ifBlank { getString(R.string.msg_unknown) }

    // 等宽字体 + 可选中：日志/堆栈对齐好看，且长按能选中复制
    val tv = TextView(this).apply {
        text = body
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setPadding(40, 32, 40, 32)
        setTextIsSelectable(true)
    }
    val scroll = ScrollView(this).apply { addView(tv) }

    val builder = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton(getString(R.string.menu_copy)) { _, _ ->
            copyToClipboard(title, body)
        }
        .setNegativeButton(cancelText ?: getString(R.string.btn_cancel)) { _, _ ->
            onCancel?.invoke()
        }

    // 最多 3 个按钮：动作（可选）+ 复制 + 取消
    if (actionText != null && onAction != null) {
        builder.setNeutralButton(actionText) { _, _ -> onAction() }
    }
    builder.setCancelable(cancelable)
    builder.show()
}

/** 复制文本到系统剪贴板（统一入口，顺带给出 toast 反馈） */
internal fun MainActivity.copyToClipboard(label: String, text: String) {
    if (text.isBlank()) {
        toast(getString(R.string.msg_no_logs_to_copy))
        return
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText(label, text))
    toast(getString(R.string.toast_copied))
}
