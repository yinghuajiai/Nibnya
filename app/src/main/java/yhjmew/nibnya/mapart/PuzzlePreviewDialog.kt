package yhjmew.nibnya.mapart

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import kotlin.math.max

/**
 * 拼图生成前的预览对话框。
 *
 * 只做第一步：**按行列切**（自由裁剪是下一个标签页）。
 *
 * ## 为什么要有这一步
 * 原流程是「先选尺寸 → 再选图」，选完尺寸根本看不到切开后长什么样，
 * 也不知道原图分辨率够不够（每块低于 128×128 会被拉伸变糊）。
 *
 * 现在改成「先选图 → 预览（可调行列）→ 再扫背包选槽位」。
 */
internal fun MainActivity.showPuzzlePreview(
    uri: Uri,
    onConfirm: (rows: Int, cols: Int) -> Unit,
    onCancel: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()   // 局部仍可用，供 setPadding 等

    // 解码缩略图：只为预览，限制在 720px 以内，避免大图 OOM
    val decoded = decodePreviewBitmap(uri, 720)
    if (decoded == null) {
        toast(getString(R.string.toast_processing_error_maybe_the_image_is_too_large))
        onCancel()
        return
    }
    val bitmap = if (decoded.width > 720 || decoded.height > 720) {
        val s = 720f / max(decoded.width, decoded.height)
        Bitmap.createScaledBitmap(
            decoded, (decoded.width * s).toInt(), (decoded.height * s).toInt(), true
        ).also { decoded.recycle() }
    } else decoded

    // 原始尺寸（用于清晰度提示）
    val originalSize = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        o.outWidth to o.outHeight
    }.getOrNull() ?: (bitmap.width to bitmap.height)

    var rows = puzzleRows.coerceIn(1, 20)
    var cols = puzzleCols.coerceIn(1, 20)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), 0)
    }

    // ---- 标签页（现在只有「按行列切」，自由裁剪留占位）----
    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabGrid = makePreviewTab(getString(R.string.tab_puzzle_grid), true, density)
    val tabCrop = makePreviewTab(getString(R.string.tab_puzzle_crop), false, density)
    tabCrop.alpha = 0.4f
    tabs.addView(tabGrid)
    tabs.addView(tabCrop)
    root.addView(tabs)

    // ---- 预览图（固定正方形，避免布局跳动）----
    val previewView = GridPreviewView(this, bitmap)
    val previewSize = (resources.displayMetrics.widthPixels * 0.62f).toInt()
    root.addView(
        previewView,
        LinearLayout.LayoutParams(previewSize, previewSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (10 * density).toInt()
        }
    )

    // ---- 信息区 ----
    val tvInfo = TextView(this).apply {
        textSize = 13f
        setTextColor("#333333".toColorInt())
        setLineSpacing((2 * density).toInt().toFloat(), 1f)
        setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), 0)
    }
    root.addView(tvInfo)

    val tvWarn = TextView(this).apply {
        textSize = 12f
        setTextColor("#E65100".toColorInt())
        setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), 0)
    }
    root.addView(tvWarn)

    // ---- 行列调节 ----
    val stepperRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), 0)
    }
    val tvRows = TextView(this).apply { textSize = 13f }
    val tvCols = TextView(this).apply { textSize = 13f }

    fun refresh() {
        previewView.rows = rows
        previewView.cols = cols
        previewView.invalidate()

        val total = rows * cols
        val layers = (total + 25) / 26          // 每层最多 26 张
        val cellW = originalSize.first / cols
        val cellH = originalSize.second / rows

        tvRows.text = getString(R.string.msg_puzzle_rows, rows)
        tvCols.text = getString(R.string.msg_puzzle_cols, cols)

        tvInfo.text = getString(
            R.string.msg_puzzle_preview_info,
            rows, cols, total,
            if (layers <= 1) getString(R.string.msg_puzzle_boxes_one)
            else getString(R.string.msg_puzzle_boxes_many, layers),
            originalSize.first, originalSize.second, cellW, cellH
        )

        // 每块不足 128×128 时会被拉伸 → 提醒但不阻止
        tvWarn.text = if (cellW < 128 || cellH < 128) {
            getString(R.string.msg_puzzle_low_res)
        } else ""
    }

    stepperRow.addView(tvRows)
    stepperRow.addView(makeStepButton("−", density) {
        if (rows > 1) { rows--; refresh() }
    })
    stepperRow.addView(makeStepButton("+", density) {
        if (rows < 20) { rows++; refresh() }
    })
    val spacer = View(this)
    stepperRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
    stepperRow.addView(tvCols)
    stepperRow.addView(makeStepButton("−", density) {
        if (cols > 1) { cols--; refresh() }
    })
    stepperRow.addView(makeStepButton("+", density) {
        if (cols < 20) { cols++; refresh() }
    })
    root.addView(stepperRow)

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_preview))
        .setView(root)
        .setPositiveButton(R.string.btn_next_pick_slot) { _, _ ->
            puzzleRows = rows
            puzzleCols = cols
            onConfirm(rows, cols)
        }
        .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .create()

    dialog.setOnDismissListener {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    refresh()
}

/** 标签页样式 */
private fun MainActivity.makePreviewTab(text: String, selected: Boolean, density: Float): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
        setTextColor(if (selected) "#2196F3".toColorInt() else "#666666".toColorInt())
        setBackgroundColor(if (selected) "#E3F2FD".toColorInt() else Color.TRANSPARENT)
        typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD
        else android.graphics.Typeface.DEFAULT
    }

/** 小号 +/− 按钮 */
private fun MainActivity.makeStepButton(
    text: String,
    density: Float,
    onClick: () -> Unit
): Button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
    this.text = text
    isAllCaps = false
    textSize = 16f
    minWidth = 0
    minimumWidth = 0
    setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
    setOnClickListener { onClick() }
}

/**
 * 缩略图上叠红色网格线，直观看到每块切到哪。
 *
 * 以 `fit center` 方式绘制（保持原图比例），网格线只画在图片区域内。
 */
private class GridPreviewView(ctx: android.content.Context, val bitmap: Bitmap) : View(ctx) {
    var rows = 1
    var cols = 1

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // fit center：等比缩放并在视图内居中
        val scale = minOf(vw / bitmap.width, vh / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
        canvas.drawBitmap(bitmap, null, dst, bmpPaint)

        linePaint.strokeWidth = max(1.5f, resources.displayMetrics.density * 1.2f)
        for (c in 1 until cols) {
            val x = dst.left + dst.width() * c / cols
            canvas.drawLine(x, dst.top, x, dst.bottom, linePaint)
        }
        for (r in 1 until rows) {
            val y = dst.top + dst.height() * r / rows
            canvas.drawLine(dst.left, y, dst.right, y, linePaint)
        }
        // 外框
        linePaint.strokeWidth = resources.displayMetrics.density * 1.6f
        canvas.drawRect(dst, linePaint)
    }
}

/** 解码预览用缩略图（限制最大边，避免大图 OOM） */
private fun MainActivity.decodePreviewBitmap(uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}
