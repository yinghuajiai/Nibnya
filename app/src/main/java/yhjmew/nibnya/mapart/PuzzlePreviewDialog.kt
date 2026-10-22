package yhjmew.nibnya.mapart

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 拼图生成前的预览对话框。
 *
 * 两个标签页：
 *   A. 按行列切 —— 缩略图叠红色网格线，+/− 调行列，出 N 张地图
 *   B. 自由裁剪 —— 拖角点/边/内部选择区域，出 1 张地图
 *
 * 两个页面共用一套撤销栈（一步一撤、不限步数）。
 * 只在「操作结束」时记快照（手指抬起 / 点 +− / 切标签页），拖动过程绝不记。
 */
/**
 * +/− 步进的上限。
 *
 * 注意：这**不是**行列的硬限制。用户用「自定义尺寸」填了 300 行，
 * 预览里仍要显示 300 行 —— 之前写成 coerceIn(1, 20) 会把用户的选择悄悄压回 20，
 * 属于把用户输入吃掉，已修。这里只用于 +/− 防误触。
 */
private const val STEP_MAX = 999

/** 标签页编号 */
private const val TAB_GRID = 0
private const val TAB_CROP = 1

internal fun MainActivity.showPuzzlePreview(
    uri: Uri,
    onConfirm: (rows: Int, cols: Int) -> Unit,
    onCropConfirm: (cropRect: RectF) -> Unit,
    onCancel: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

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

    val originalSize = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        o.outWidth to o.outHeight
    }.getOrNull() ?: (bitmap.width to bitmap.height)

    // ---------------- 状态 ----------------
    var mode = TAB_GRID
    var rows = puzzleRows.coerceAtLeast(1)
    var cols = puzzleCols.coerceAtLeast(1)
    var lockRatio = true
    // 裁剪框：**原图像素坐标**（不是屏幕坐标），缩放/转屏都不会错位
    val crop = RectF(0f, 0f, originalSize.first.toFloat(), originalSize.second.toFloat())

    data class Snap(
        val mode: Int, val rows: Int, val cols: Int,
        val cropL: Float, val cropT: Float, val cropR: Float, val cropB: Float
    )

    fun snapshot() = Snap(mode, rows, cols, crop.left, crop.top, crop.right, crop.bottom)

    val undoStack = ArrayDeque<Snap>()
    val redoStack = ArrayDeque<Snap>()

    // ---------------- 视图 ----------------
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(8), px(14), 0)
    }

    val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabGrid = makeTab(getString(R.string.tab_puzzle_grid), true, density)
    val tabCrop = makeTab(getString(R.string.tab_puzzle_crop), false, density)
    tabs.addView(tabGrid)
    tabs.addView(tabCrop)
    root.addView(tabs)

    val previewView = GridPreviewView<Snap>(this, bitmap)

    // 撤销 / 重做 / 重置（底部左侧）—— 先建出来，回调里要用
    lateinit var btnUndo: Button
    lateinit var btnRedo: Button

    fun updateUndoButtons() {
        btnUndo.isEnabled = undoStack.isNotEmpty()
        btnRedo.isEnabled = redoStack.isNotEmpty()
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        btnRedo.alpha = if (btnRedo.isEnabled) 1f else 0.35f
    }

    fun applySnap(s: Snap) {
        mode = s.mode; rows = s.rows; cols = s.cols
        crop.set(s.cropL, s.cropT, s.cropR, s.cropB)
    }

    /** 记一步（调用方保证是"操作结束"时刻） */
    fun pushHistory(prev: Snap) {
        undoStack.addLast(prev)
        redoStack.clear()            // 新操作让重做分支失效
        updateUndoButtons()
    }

    // 行列步进 / 标签切换都要记历史，所以先声明 refresh 再赋值
    var refresh: () -> Unit = {}

    btnUndo = makeTextButton(getString(R.string.btn_undo), density) {
        // 拖动中不可点（按钮已置灰）；万一触发，先把手上的拖动落地
        if (previewView.isDragging) return@makeTextButton
        if (undoStack.isEmpty()) return@makeTextButton
        redoStack.addLast(snapshot())
        applySnap(undoStack.removeLast())
        refresh()
    }
    btnRedo = makeTextButton(getString(R.string.btn_redo), density) {
        if (previewView.isDragging) return@makeTextButton
        if (redoStack.isEmpty()) return@makeTextButton
        undoStack.addLast(snapshot())
        applySnap(redoStack.removeLast())
        refresh()
    }
    val btnReset = makeTextButton(getString(R.string.btn_reset), density) {
        if (previewView.isDragging) return@makeTextButton
        pushHistory(snapshot())
        mode = TAB_GRID
        rows = puzzleRows.coerceAtLeast(1)
        cols = puzzleCols.coerceAtLeast(1)
        crop.set(0f, 0f, originalSize.first.toFloat(), originalSize.second.toFloat())
        refresh()
    }

    val previewSize = (resources.displayMetrics.widthPixels * 0.62f).toInt()
    root.addView(previewView, LinearLayout.LayoutParams(previewSize, previewSize).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = px(10)
    })

    val tvInfo = TextView(this).apply {
        textSize = 13f; setTextColor("#333333".toColorInt())
        setLineSpacing(px(2).toFloat(), 1f)
        setPadding(px(4), px(10), px(4), 0)
    }
    root.addView(tvInfo)

    val tvWarn = TextView(this).apply {
        textSize = 12f; setTextColor("#E65100".toColorInt())
        setPadding(px(4), px(6), px(4), 0)
    }
    root.addView(tvWarn)

    // 行列步进行（仅标签页 A）
    val stepperRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(10), px(4), 0)
    }
    val tvRows = TextView(this).apply { textSize = 13f }
    val tvCols = TextView(this).apply { textSize = 13f }
    stepperRow.addView(tvRows)
    stepperRow.addView(makeSmallButton("−", density) {
        if (rows > 1) { val p = snapshot(); rows--; pushHistory(p); refresh() }
    })
    stepperRow.addView(makeSmallButton("+", density) {
        if (rows < STEP_MAX) { val p = snapshot(); rows++; pushHistory(p); refresh() }
    })
    stepperRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    stepperRow.addView(tvCols)
    stepperRow.addView(makeSmallButton("−", density) {
        if (cols > 1) { val p = snapshot(); cols--; pushHistory(p); refresh() }
    })
    stepperRow.addView(makeSmallButton("+", density) {
        if (cols < STEP_MAX) { val p = snapshot(); cols++; pushHistory(p); refresh() }
    })
    root.addView(stepperRow)

    // 比例锁（仅标签页 B）
    val lockRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(4), px(8), px(4), 0)
    }
    val switchLock = android.widget.Switch(this).apply {
        isChecked = true
        text = getString(R.string.msg_puzzle_lock_ratio)
        textSize = 13f
    }
    switchLock.setOnCheckedChangeListener { _, checked ->
        lockRatio = checked
        previewView.lockRatio = checked
        previewView.invalidate()
    }
    lockRow.addView(switchLock)
    root.addView(lockRow)

    // 撤销/重做/重置行
    val undoRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(2), px(6), px(2), 0)
    }
    undoRow.addView(btnUndo)
    undoRow.addView(btnRedo)
    undoRow.addView(btnReset)
    root.addView(undoRow)

    // ---------------- 刷新 ----------------
    refresh = {
        previewView.mode = mode
        previewView.rows = rows
        previewView.cols = cols
        previewView.cropImage = RectF(crop)
        previewView.lockRatio = lockRatio
        previewView.invalidate()

        val isGrid = mode == TAB_GRID
        stepperRow.visibility = if (isGrid) View.VISIBLE else View.GONE
        lockRow.visibility = if (isGrid) View.GONE else View.VISIBLE

        setTabStyle(tabGrid, isGrid, density)
        setTabStyle(tabCrop, !isGrid, density)
        tabGrid.setOnClickListener {
            if (mode == TAB_GRID) return@setOnClickListener
            pushHistory(snapshot()); mode = TAB_GRID; refresh()
        }
        tabCrop.setOnClickListener {
            if (mode == TAB_CROP) return@setOnClickListener
            pushHistory(snapshot()); mode = TAB_CROP; refresh()
        }

        val total = if (isGrid) rows * cols else 1
        val layers = (total + 25) / 26
        val cellW = if (isGrid) originalSize.first / cols else cropW(crop).roundToInt()
        val cellH = if (isGrid) originalSize.second / rows else cropH(crop).roundToInt()

        tvRows.text = getString(R.string.msg_puzzle_rows, rows)
        tvCols.text = getString(R.string.msg_puzzle_cols, cols)

        tvInfo.text = if (isGrid) {
            getString(
                R.string.msg_puzzle_preview_info,
                rows, cols, total,
                if (layers <= 1) getString(R.string.msg_puzzle_boxes_one)
                else getString(R.string.msg_puzzle_boxes_many, layers),
                originalSize.first, originalSize.second, cellW, cellH
            )
        } else {
            getString(
                R.string.msg_puzzle_crop_info,
                cellW, cellH,
                cropW(crop).roundToInt(), cropH(crop).roundToInt()
            )
        }

        tvWarn.text = if (isGrid) {
            if (cellW < 128 || cellH < 128) getString(R.string.msg_puzzle_low_res) else ""
        } else {
            if (lockRatio && kotlin.math.abs(cropW(crop) - cropH(crop)) > 1f)
                getString(R.string.msg_puzzle_crop_not_square) else ""
        }
        updateUndoButtons()
    }

    // 拖动结束才记历史
    previewView.onDragEnd = {
        pushHistory(previewView.dragStartSnapshot ?: snapshot())
        refresh()
    }
    previewView.onDragStart = {
        previewView.dragStartSnapshot = snapshot()
    }
    // 拖动中让撤销按钮不可用（用户要求）
    previewView.onDraggingChanged = { dragging ->
        btnUndo.isEnabled = !dragging && undoStack.isNotEmpty()
        btnRedo.isEnabled = !dragging && redoStack.isNotEmpty()
        btnUndo.alpha = if (btnUndo.isEnabled) 1f else 0.35f
        btnRedo.alpha = if (btnRedo.isEnabled) 1f else 0.35f
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_puzzle_preview))
        .setView(root)
        .setPositiveButton(R.string.btn_next_pick_slot) { _, _ ->
            if (mode == TAB_GRID) {
                puzzleRows = rows
                puzzleCols = cols
                onConfirm(rows, cols)
            } else {
                onCropConfirm(RectF(crop))
            }
        }
        .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .create()

    dialog.setOnDismissListener { if (!bitmap.isRecycled) bitmap.recycle() }

    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    refresh()
}

private fun cropW(r: RectF) = r.right - r.left
private fun cropH(r: RectF) = r.bottom - r.top

private fun MainActivity.makeTab(text: String, selected: Boolean, density: Float): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
        setTextColor(if (selected) "#2196F3".toColorInt() else "#666666".toColorInt())
        setBackgroundColor(if (selected) "#E3F2FD".toColorInt() else Color.TRANSPARENT)
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

private fun MainActivity.setTabStyle(tv: TextView, selected: Boolean, density: Float) {
    tv.setTextColor(if (selected) "#2196F3".toColorInt() else "#666666".toColorInt())
    tv.setBackgroundColor(if (selected) "#E3F2FD".toColorInt() else Color.TRANSPARENT)
    tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    tv.isClickable = true
}

private fun MainActivity.makeSmallButton(text: String, density: Float, onClick: () -> Unit): Button =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        setOnClickListener { onClick() }
    }

private fun MainActivity.makeTextButton(text: String, density: Float, onClick: () -> Unit): Button =
    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        setTextColor("#2196F3".toColorInt())
        setOnClickListener { onClick() }
    }

/**
 * 预览视图：网格线（标签页 A）/ 可拖动的裁剪框（标签页 B）。
 *
 * 裁剪框存的坐标是**原图像素**，绘制时转换到视图坐标，
 * 这样窗口尺寸变化不会影响裁剪结果。
 */
private class GridPreviewView<T>(ctx: Context, val bitmap: Bitmap) : View(ctx) {
    var mode = TAB_GRID
    var rows = 1
    var cols = 1
    var cropImage: RectF = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    var lockRatio = true

    var isDragging = false
        private set

    // 回调（由对话框设置）
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onDraggingChanged: ((Boolean) -> Unit)? = null
    var dragStartSnapshot: T? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#FF3B30"); style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000"); style = Paint.Style.FILL
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#FFD60A"); style = Paint.Style.STROKE; isAntiAlias = true
    }

    private val dst = RectF()          // 图片在视图里的位置
    private val cropView = RectF()     // 裁剪框在视图里的位置

    /** 原图坐标 → 视图坐标 */
    private fun toView(r: RectF): RectF {
        val sx = dst.width() / bitmap.width
        val sy = dst.height() / bitmap.height
        return RectF(
            dst.left + r.left * sx, dst.top + r.top * sy,
            dst.left + r.right * sx, dst.top + r.bottom * sy
        )
    }

    /** 视图坐标 → 原图坐标 */
    private fun toImage(x: Float, y: Float): Pair<Float, Float> {
        val sx = dst.width() / bitmap.width
        val sy = dst.height() / bitmap.height
        return Pair(
            ((x - dst.left) / sx).coerceIn(0f, bitmap.width.toFloat()),
            ((y - dst.top) / sy).coerceIn(0f, bitmap.height.toFloat())
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val scale = min(vw / bitmap.width, vh / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)
        canvas.drawBitmap(bitmap, null, dst, bmpPaint)

        if (mode == TAB_GRID) {
            gridPaint.strokeWidth = max(1.5f, resources.displayMetrics.density * 1.2f)
            for (c in 1 until cols) {
                val x = dst.left + dst.width() * c / cols
                canvas.drawLine(x, dst.top, x, dst.bottom, gridPaint)
            }
            for (r in 1 until rows) {
                val y = dst.top + dst.height() * r / rows
                canvas.drawLine(dst.left, y, dst.right, y, gridPaint)
            }
            gridPaint.strokeWidth = resources.displayMetrics.density * 1.6f
            canvas.drawRect(dst, gridPaint)
        } else {
            cropView.set(toView(cropImage))
            // 框外压暗
            canvas.drawRect(dst.left, dst.top, dst.right, cropView.top, dimPaint)
            canvas.drawRect(dst.left, cropView.bottom, dst.right, dst.bottom, dimPaint)
            canvas.drawRect(dst.left, cropView.top, cropView.left, cropView.bottom, dimPaint)
            canvas.drawRect(cropView.right, cropView.top, dst.right, cropView.bottom, dimPaint)

            borderPaint.strokeWidth = resources.displayMetrics.density * 2f
            canvas.drawRect(cropView, borderPaint)

            // 三分线（辅助构图）
            gridPaint.strokeWidth = resources.displayMetrics.density
            gridPaint.alpha = 110
            for (i in 1..2) {
                val x = cropView.left + cropView.width() * i / 3f
                val y = cropView.top + cropView.height() * i / 3f
                canvas.drawLine(x, cropView.top, x, cropView.bottom, gridPaint)
                canvas.drawLine(cropView.left, y, cropView.right, y, gridPaint)
            }
            gridPaint.alpha = 255

            // 四角把手
            val hs = resources.displayMetrics.density * 7f
            for ((hx, hy) in listOf(
                cropView.left to cropView.top, cropView.right to cropView.top,
                cropView.left to cropView.bottom, cropView.right to cropView.bottom
            )) {
                canvas.drawCircle(hx, hy, hs, handlePaint)
                canvas.drawCircle(hx, hy, hs, borderPaint.apply {
                    strokeWidth = resources.displayMetrics.density * 1.2f
                })
            }
        }
    }

    // ---------------- 触摸 ----------------
    private var dragCorner = 0   // 0=none 1=TL 2=TR 3=BL 4=BR 5=move
    private var lastX = 0f
    private var lastY = 0f
    private var dragStartView = RectF()

    private fun hitTest(x: Float, y: Float): Int {
        val hs = resources.displayMetrics.density * 26f
        val l = kotlin.math.abs(x - cropView.left) < hs
        val r = kotlin.math.abs(x - cropView.right) < hs
        val t = kotlin.math.abs(y - cropView.top) < hs
        val b = kotlin.math.abs(y - cropView.bottom) < hs
        return when {
            l && t -> 1
            r && t -> 2
            l && b -> 3
            r && b -> 4
            cropView.contains(x, y) -> 5
            else -> 0
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode != TAB_CROP) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                dragStartView.set(cropView)
                dragStartImage.set(cropImage)
                dragCorner = hitTest(e.x, e.y)
                // 点框外：把最近的一个角拉过来（更符合直觉）
                if (dragCorner == 0 && dst.contains(e.x, e.y)) {
                    dragCorner = 5
                }
                if (dragCorner != 0) {
                    isDragging = true
                    onDraggingChanged?.invoke(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragCorner == 0) return true
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDraggingChanged?.invoke(false)
                    // 位移太小就当没动（防手抖塞满历史）
                    val moved = kotlin.math.abs(cropImage.left - dragStartImage.left) > 1f ||
                            kotlin.math.abs(cropImage.top - dragStartImage.top) > 1f ||
                            kotlin.math.abs(cropImage.right - dragStartImage.right) > 1f ||
                            kotlin.math.abs(cropImage.bottom - dragStartImage.bottom) > 1f
                    if (moved) onDragEnd?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private val dragStartImage = RectF()

    private fun applyDrag(dxView: Float, dyView: Float) {
        if (dragStartView.isEmpty) return
        val sx = bitmap.width / dst.width()
        val sy = bitmap.height / dst.height()
        val dx = dxView * sx
        val dy = dyView * sy

        val minSize = 16f
        var l = cropImage.left; var t = cropImage.top
        var r = cropImage.right; var b = cropImage.bottom

        when (dragCorner) {
            1 -> { l += dx; t += dy }
            2 -> { r += dx; t += dy }
            3 -> { l += dx; b += dy }
            4 -> { r += dx; b += dy }
            5 -> {
                // 整体平移，不越界
                val w = r - l; val h = b - t
                l = (l + dx).coerceIn(0f, bitmap.width - w)
                t = (t + dy).coerceIn(0f, bitmap.height - h)
                r = l + w; b = t + h
            }
        }

        l = l.coerceIn(0f, bitmap.width.toFloat())
        t = t.coerceIn(0f, bitmap.height.toFloat())
        r = r.coerceIn(0f, bitmap.width.toFloat())
        b = b.coerceIn(0f, bitmap.height.toFloat())

        if (r - l < minSize) { if (dragCorner == 1 || dragCorner == 3) l = r - minSize else r = l + minSize }
        if (b - t < minSize) { if (dragCorner == 1 || dragCorner == 2) t = b - minSize else b = t + minSize }

        if (lockRatio) {
            // 锁定正方形：以较大边为准
            val side = max(r - l, b - t)
            when (dragCorner) {
                1 -> { l = r - side; t = b - side }
                2 -> { r = l + side; t = b - side }
                3 -> { l = r - side; b = t + side }
                4 -> { r = l + side; b = t + side }
            }
            l = l.coerceAtLeast(0f); t = t.coerceAtLeast(0f)
            r = r.coerceAtMost(bitmap.width.toFloat()); b = b.coerceAtMost(bitmap.height.toFloat())
        }

        cropImage.set(l, t, r, b)
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
