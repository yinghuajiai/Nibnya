package yhjmew.nibnya.mapart

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R

/**
 * 拼图成品的「放入背包」槽位选择器。
 *
 * ## 为什么改成网格
 * 原来是 36 行纯文本列表（`[0] minecraft:netherite_sword x1` / `[1] --空--` …），
 * 选个格子要滚半天，而且完全看不出「哪格空、哪格满」的空间关系。
 *
 * ## 布局取舍
 * 游戏里背包是 9 列 × 4 行。但 9 列在手机上每格只有 ~35dp，
 * **中文物品名根本放不下**（用户明确要求要能看到物品名）。
 * 所以这里用 **4 列 × 9 行**：格子够大能放下名字与数量，
 * 同时在每行下方标注对应的原始槽位号，仍然能对上游戏里的位置。
 *
 * ## 交互（按用户确认）
 * - 空格：直接点选
 * - 已占用：**置灰但仍可点**，点了弹二次确认「这里已有 xxx ×N，确定覆盖？」
 *   （不完全禁掉 —— 否则玩家想覆盖时就没办法了）
 * - 长按任意格子：显示物品全名与槽位号
 */
internal fun MainActivity.showSlotGrid(
    info: MapArtViewModel.InventorySlotInfo,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val emptyCount = info.mainSlots.count { !it.isOccupied }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), 0)
    }

    // 说明：明确「成品只占 1 格」，避免玩家以为要选很多格
    root.addView(TextView(this).apply {
        text = getString(R.string.msg_puzzle_slots_summary, emptyCount, info.mainSlots.size, 1)
        textSize = 12f
        setTextColor("#666666".toColorInt())
        setPadding(dp(4), 0, dp(4), dp(8))
    })

    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    var pickedSlot = -1

    // 4 列 × 9 行 = 36 格
    val cols = 4
    for (rowStart in 0 until 36 step cols) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        for (i in rowStart until minOf(rowStart + cols, 36)) {
            val s = info.mainSlots[i]
            val cell = buildCell(s, ::dp)
            cell.setOnClickListener { pickedSlot = s.slot; onPick(s.slot) }
            cell.setOnLongClickListener {
                showSlotDetail(s)
                true
            }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // 补齐最后一行的空位，保证列宽一致
        for (k in (minOf(rowStart + cols, 36) - rowStart) until cols) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        grid.addView(row)
    }

    root.addView(ScrollView(this).apply { addView(grid) })

    // 图例
    root.addView(TextView(this).apply {
        text = getString(R.string.msg_puzzle_slot_legend)
        textSize = 11f
        setTextColor("#999999".toColorInt())
        setPadding(dp(4), dp(4), dp(4), dp(4))
    })

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_slot_select))
        .setView(root)
        .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .show()
}

/** 一个格子：序号 + 物品名 + 数量；空位显示「空」 */
private fun MainActivity.buildCell(
    s: MapArtViewModel.SlotInfo,
    dp: (Int) -> Int
): View {
    val occupied = s.isOccupied
    val cell = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (occupied) "#ECECEC".toColorInt() else "#E8F5E9".toColorInt())
            setStroke(dp(1), if (occupied) "#BDBDBD".toColorInt() else "#A5D6A7".toColorInt())
        }
        // 可点击反馈：用状态选择器改底色即可（不用 foreground，
        // 否则系统 selector 会盖住格子本身的底色，看着发灰）
        isClickable = true
    }

    // 槽位号（小字，灰色）
    cell.addView(TextView(this).apply {
        text = s.slot.toString()
        textSize = 10f
        setTextColor("#999999".toColorInt())
    })

    if (occupied) {
        // 物品名：短名优先（去掉 minecraft: 前缀），最多两行
        cell.addView(TextView(this).apply {
            text = shortItemName(s.name)
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER
            setTextColor("#333333".toColorInt())
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // 数量角标
        cell.addView(TextView(this).apply {
            text = getString(R.string.msg_puzzle_slot_count, s.count)
            textSize = 10f
            setTextColor("#E65100".toColorInt())
        })
    } else {
        cell.addView(TextView(this).apply {
            text = getString(R.string.text_slot_empty)
            textSize = 11f
            setTextColor("#9E9E9E".toColorInt())
        })
    }
    return cell
}

/** 长按格子：显示完整物品名与槽位号 */
private fun MainActivity.showSlotDetail(s: MapArtViewModel.SlotInfo) {
    val label = getString(R.string.label_puzzle_slot, s.slot)
    if (!s.isOccupied) {
        toast(getString(R.string.msg_puzzle_slot_is_empty, label))
        return
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_slot_occupied))
        .setMessage(getString(R.string.msg_puzzle_slot_detail, label, s.name, s.count))
        .setPositiveButton(R.string.btn_close, null)
        .show()
}

/** `minecraft:netherite_sword` → `netherite_sword`；过长时截断 */
private fun shortItemName(raw: String): String {
    val n = raw.substringAfter(':')
    return if (n.length > 14) n.substring(0, 13) + "…" else n
}
