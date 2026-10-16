package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.nbt.adapter.NbtTreeAdapter
import yhjmew.nibnya.nbt.NbtTranslator
import yhjmew.nibnya.nbt.NbtTranslator.getString
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.util.DraftAutoSaver
import yhjmew.nibnya.util.DraftStore
import yhjmew.nibnya.util.TrashStore
import yhjmew.nibnya.util.NbtClipboard
import yhjmew.nibnya.util.NbtExporter

// ============================================
// 剪贴板（复制 / 粘贴）
// ============================================

/**
 * 复制 / 导出 NBT。两步选择：
 *
 * **第 1 步 选格式**：JSON（可再导入） / SNBT（游戏内格式） / 纯文本（方便阅读）
 *
 * **第 2 步 选目标**：
 * - 复制到剪贴板 —— 应用内保留结构化副本（存盘，进程被杀不丢）并写系统剪贴板
 * - 保存到下载目录 —— `Download/Nibnya/Exports/`，需所有文件访问权限
 * - 选择目录… —— 走 SAF，任何权限都不需要
 *
 * 之前只写应用内存却提示「已复制到剪贴板」，用户切到别处粘贴是空的。
 */
internal fun MainActivity.showCopyExportDialog(element: JsonElement, nameHint: String? = null) {
    val labels = arrayOf(
        getString(R.string.clip_format_json),
        getString(R.string.clip_format_snbt),
        getString(R.string.clip_format_text)
    )
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_copy_as))
        .setItems(labels) { _, w ->
            val fmt = when (w) {
                NbtClipboard.FORMAT_SNBT -> NbtClipboard.FORMAT_SNBT
                NbtClipboard.FORMAT_TEXT -> NbtClipboard.FORMAT_TEXT
                else -> NbtClipboard.FORMAT_JSON
            }
            showExportDestDialog(element, fmt, labels[w], nameHint)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

private fun MainActivity.showExportDestDialog(
    element: JsonElement,
    fmt: Int,
    fmtLabel: String,
    nameHint: String?
) {
    val dests = arrayOf(
        getString(R.string.export_dest_clipboard),
        getString(R.string.export_dest_download),
        getString(R.string.export_dest_choose)
    )
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_export_to))
        .setItems(dests) { _, d ->
            when (d) {
                0 -> {
                    // 应用内结构化副本（落盘）
                    NbtClipboard.set(this, element)
                    // 系统剪贴板（按格式转文本）
                    NbtClipboard.writeToSystemClipboard(this, element, fmt)
                    toast(getString(R.string.toast_copied_format, fmtLabel))
                }
                1 -> exportToDefaultDir(element, fmt, nameHint)
                2 -> startExportToChosenDir(element, fmt, nameHint)
            }
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

/** 保存到公共下载目录（需所有文件访问权限；不可写时引导改用 SAF） */
private fun MainActivity.exportToDefaultDir(element: JsonElement, fmt: Int, nameHint: String?) {
    val content = NbtClipboard.formatAs(element, fmt)
    val dir = NbtExporter.defaultExportDir()
    val fileName = NbtExporter.buildFileName(nameHint, fmt)
    val saved = NbtExporter.saveToDir(dir, fileName, content)
    if (saved != null) {
        toast(getString(R.string.toast_exported_to, saved.absolutePath))
    } else {
        // 没权限：提示并引导选目录（SAF 不需要权限）
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_dest_download))
            .setMessage(getString(R.string.msg_export_need_permission))
            .setPositiveButton(getString(R.string.export_dest_choose)) { _, _ ->
                startExportToChosenDir(element, fmt, nameHint)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}

// ============================================
// 剪贴板（粘贴）
// ============================================

/**
 * 取粘贴来源：
 * 1. 优先用应用内剪贴板（保留类型信息，最准确）
 * 2. 应用内为空时，尝试从系统剪贴板读取（自动识别 JSON / SNBT），
 *    这样从别的来源复制的内容也能直接粘进来
 */
internal fun MainActivity.getPasteSource(): JsonElement? {
    NbtClipboard.get(this)?.let { return it }
    val fromSystem = NbtClipboard.readFromSystemClipboard(this)
    if (fromSystem != null) {
        NbtClipboard.set(this, fromSystem)
        return fromSystem
    }
    return null
}

// ============================================
// 根菜单
// ============================================
fun MainActivity.showRootMenu() {
    val current = editorVM.nbtData.value ?: run { toast(getString(R.string.toast_no_data)); return }
    val ops = arrayOf(getString(R.string.action_add_tag), getString(R.string.action_paste_tag), getString(R.string.action_copy_root), getString(R.string.action_paste_root), getString(R.string.action_clear_all))
    AlertDialog.Builder(this).setTitle(getString(R.string.menu_root_title)).setItems(ops) { _, w ->
        when (w) {
            0 -> showAddChildDialog(current)
            1 -> {
                val clip = getPasteSource()
                if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return@setItems }
                val input = EditText(this).apply { hint = getString(R.string.hint_new_tag_name) }
                AlertDialog.Builder(this).setTitle(getString(R.string.title_paste_as)).setView(input)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        val n = input.text.toString()
                        if (n.isNotEmpty() && !current.has(n)) {
                            current.add(n, clip.deepCopy())
                            refreshAfterEdit(); toast(getString(R.string.toast_pasted))
                        } else toast(getString(R.string.toast_name_empty_or_exists))
                    }.show()
            }
            2 -> {
                showCopyExportDialog(current, etWorldName?.text?.toString()?.trim())
            }
            3 -> {
                val clip = NbtClipboard.readFromSystemClipboard(this)
                if (clip == null || !clip.isJsonObject) {
                    toast(getString(R.string.toast_clipboard_empty)); return@setItems
                }
                val newObj = clip.asJsonObject
                // 若粘进来的是标签包装 {t,v}，取其内层 compound
                val incoming = if (newObj.has("t") && newObj.has("v") && newObj.get("v").isJsonObject)
                    newObj.getAsJsonObject("v") else newObj
                AlertDialog.Builder(this).setTitle(getString(R.string.msg_confirm_replace)).setMessage(getString(R.string.msg_replace_all_data))
                    .setPositiveButton(getString(R.string.btn_replace)) { _, _ ->
                        ArrayList(current.keySet()).forEach { current.remove(it) }
                        incoming.entrySet().forEach { current.add(it.key, it.value.deepCopy()) }
                        NbtClipboard.set(this, incoming)
                        refreshAfterEdit(); toast(getString(R.string.toast_replaced))
                    }.setNegativeButton(getString(R.string.btn_cancel), null).show()
            }
            4 -> AlertDialog.Builder(this).setTitle(getString(R.string.dialog_danger_title)).setMessage(getString(R.string.btn_confirm_clear))
                .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                    ArrayList(current.keySet()).forEach { current.remove(it) }
                    refreshAfterEdit(); toast(getString(R.string.toast_cleared))
                }.setNegativeButton(getString(R.string.btn_cancel), null).show()
        }
    }.show()
}

// ============================================
// 长按菜单
// ============================================
fun MainActivity.showLongPressMenu(key: String?, itemData: JsonObject) {
    val ops = arrayOf(getString(R.string.menu_copy), getString(R.string.menu_paste), getString(R.string.menu_delete), getString(R.string.menu_rename), getString(R.string.menu_add_child))
    AlertDialog.Builder(this).setTitle(getString(R.string.title_manage, key)).setItems(ops) { _, w ->
        if (editorVM.isTreeMode.value) {
            handleTreeLongPress(w, key ?: "", itemData)
        } else {
            handleListLongPress(w, key, itemData)
        }
    }.show()
}

/**
 * 列表模式的长按处理（保持原有行为）
 */
private fun MainActivity.handleListLongPress(w: Int, key: String?, itemData: JsonObject) {
    val parent = nbtAdapter?.data ?: return
    val nodeKey = key ?: return
    when (w) {
        0 -> showCopyExportDialog(itemData, nodeKey)
        1 -> {
            val clip = getPasteSource()
            if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return }
            val pasteKey = nodeKey + "_copy"
            if (parent.has(pasteKey)) { toast(getString(R.string.toast_name_exists)); return }
            parent.add(pasteKey, clip.deepCopy())
            refreshAfterEdit(); toast(getString(R.string.toast_pasted))
        }
        2 -> { parent.remove(nodeKey); refreshAfterEdit(); toast(getString(R.string.toast_deleted)) }
        3 -> showRenameDialog(nodeKey, itemData, parent)
        4 -> addChildToNode(itemData)
    }
}

/**
 * 树形模式的长按处理。
 * 关键修复：用 getParentRef 区分「父是 compound」和「父是 List」，
 * 之前统一按 compound 处理，导致 List 元素的删除/粘贴落到错误对象上。
 */
private fun MainActivity.handleTreeLongPress(w: Int, fallbackKey: String, fallbackData: JsonObject) {
    val adapter = nbtTreeAdapter ?: return
    val pos = editorVM.lastTreeClickPosition
    // 防止 rebuild 后位置越界（getNode 有边界检查，越界返回 null）
    val node = if (pos in 0 until adapter.count) adapter.getNode(pos) else null
    val nodeData = node?.value as? JsonObject ?: fallbackData
    val nodeKey = node?.key ?: fallbackKey
    // 注意：根级字段的 parent 是虚拟 ROOT（不是 null），它们是普通字段，
    // 可以正常删除/重命名；真正的虚拟 ROOT 节点不会出现在可见列表里。
    val isRoot = node == null

    when (w) {
        0 -> showCopyExportDialog(nodeData, nodeKey)

        1 -> {  // 粘贴
            val clip = getPasteSource()
            if (clip == null) { toast(getString(R.string.toast_clipboard_empty)); return }
            if (isRoot) {
                // 根：作为新字段加入根数据，让用户输入字段名
                val rootObj = editorVM.nbtData.value ?: return
                val input = EditText(this).apply { hint = getString(R.string.hint_new_tag_name) }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_paste_as))
                    .setView(input)
                    .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                        val n = input.text.toString().trim()
                        if (n.isEmpty() || rootObj.has(n)) {
                            toast(getString(R.string.toast_name_empty_or_exists))
                        } else {
                            rootObj.add(n, clip.deepCopy())
                            adapter.rebuild()
                            toast(getString(R.string.toast_pasted))
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                return
            } else {
                val ref = adapter.getParentRef(pos) ?: run {
                    toast(getString(R.string.toast_operation_failed)); return
                }
                when (ref) {
                    is NbtTreeAdapter.ParentRef.Compound -> {
                        val pasteKey = ref.key + "_copy"
                        if (ref.obj.has(pasteKey)) { toast(getString(R.string.toast_name_exists)); return }
                        ref.obj.add(pasteKey, clip.deepCopy())
                    }
                    is NbtTreeAdapter.ParentRef.ListArr -> {
                        // List 元素：粘贴即覆盖该元素（clip 可能是 {t,v} 包装，取内层值）
                        val newVal = if (clip.isJsonObject && clip.asJsonObject.has("v"))
                            clip.asJsonObject.get("v").deepCopy()
                        else clip.deepCopy()
                        if (ref.index in 0 until ref.arr.size()) ref.arr.set(ref.index, newVal)
                    }
                }
            }
            adapter.rebuild()
            toast(getString(R.string.toast_pasted))
        }

        2 -> {  // 删除
            if (isRoot) { toast(getString(R.string.toast_root_cannot_delete)); return }
            val ref = adapter.getParentRef(pos) ?: run {
                toast(getString(R.string.toast_operation_failed)); return
            }
            when (ref) {
                is NbtTreeAdapter.ParentRef.Compound -> ref.obj.remove(ref.key)
                is NbtTreeAdapter.ParentRef.ListArr -> {
                    if (ref.index in 0 until ref.arr.size()) ref.arr.remove(ref.index)
                }
            }
            adapter.rebuild()
            toast(getString(R.string.toast_deleted))
        }

        3 -> {  // 重命名
            if (isRoot) { toast(getString(R.string.toast_root_cannot_rename)); return }
            val ref = adapter.getParentRef(pos)
            if (ref is NbtTreeAdapter.ParentRef.ListArr) {
                // List 元素由下标决定，没有独立名字
                toast(getString(R.string.toast_list_element_no_name))
                return
            }
            val compound = (ref as? NbtTreeAdapter.ParentRef.Compound)?.obj ?: run {
                toast(getString(R.string.toast_operation_failed)); return
            }
            showTreeRenameDialog(nodeKey, nodeData, compound, adapter)
        }

        4 -> addChildToNode(nodeData)
    }
}

/** 树形模式的重命名（完成后重建树） */
private fun MainActivity.showTreeRenameDialog(
    oldKey: String,
    itemData: JsonObject?,
    parent: JsonObject,
    adapter: NbtTreeAdapter
) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_rename)).setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val nk = input.text.toString().trim()
            if (nk.isEmpty() || parent.has(nk)) {
                toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton
            }
            parent.remove(oldKey)
            parent.add(nk, itemData)
            adapter.rebuild()
            toast(getString(R.string.toast_renamed))
        }.show()
}

// ============================================
// 重命名
// ============================================
private fun MainActivity.showRenameDialog(oldKey: String?, itemData: JsonObject?, parent: JsonObject) {
    val input = EditText(this).apply { setText(oldKey) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_rename)).setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val nk = input.text.toString().trim()
            if (nk.isEmpty() || parent.has(nk)) { toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton }
            parent.remove(oldKey); parent.add(nk, itemData)
            refreshAfterEdit(); toast(getString(R.string.toast_renamed))
        }.show()
}

// ============================================
// 添加子项到节点
// ============================================
private fun MainActivity.addChildToNode(nodeData: JsonObject?) {
    val data = nodeData ?: return
    val type = data.get("t").asInt
    if (type == 10) {
        showAddChildDialog(data.getAsJsonObject("v"))
        if (editorVM.isTreeMode.value) {
            nbtTreeAdapter?.getNode(editorVM.lastTreeClickPosition)?.let {
                if (!it.isExpanded) nbtTreeAdapter?.toggleExpand(editorVM.lastTreeClickPosition)
            }
        }
    } else if (type == 9) {
        val itemType = data.get("itemType").asInt
        AppLogger.info("ListDebug", "addChildToNode: List itemType=$itemType, isTreeMode=${editorVM.isTreeMode.value}, 当前List v size=${data.get("v")?.asJsonArray?.size() ?: -1}")
        val newVal: JsonElement = when (itemType) {
            1 -> JsonPrimitive(0.toByte()); 2 -> JsonPrimitive(0.toShort())
            3 -> JsonPrimitive(0); 4 -> JsonPrimitive(0L)
            5 -> JsonPrimitive(0.0f); 6 -> JsonPrimitive(0.0)
            8 -> JsonPrimitive(""); 10 -> JsonObject(); else -> JsonPrimitive(0)
        }
        if (editorVM.isTreeMode.value) {
            data.getAsJsonArray("v").add(newVal)
            AppLogger.info("ListDebug", "addChildToNode(树形): 直接往 data.v 添加")
            // 树形模式：新增元素后必须重建树，否则新元素不显示
            nbtTreeAdapter?.rebuild()
        } else {
            val target = editorVM.findOriginalListData()
            if (target != null) {
                target.add(newVal)
                AppLogger.info("ListDebug", "addChildToNode(列表): findOriginalListData 找到数组，添加后 size=${target.size()}")
            } else {
                data.getAsJsonArray("v").add(newVal)
                AppLogger.warn("ListDebug", "addChildToNode(列表): findOriginalListData 返回 null，fallback 到 data.v 添加")
            }
            // 从源数组重建假 Map，再刷新列表
            editorVM.rebuildFakeMapFromSource()
            nbtAdapter?.refreshKeys()
        }
        toast(getString(R.string.toast_added_to_list))
        return  // 不走到末尾的 refreshAfterEdit
    }
}

// ============================================
// 添加子项对话框
// ============================================
fun MainActivity.showAddChildDialog(parent: JsonObject) {
    val types = arrayOf("Byte(1)","Short(2)","Int(3)","Long(4)","Float(5)","Double(6)",
        "ByteArray(7)","String(8)","List(9)","Compound(10)","IntArray(11)","LongArray(12)")
    val ids = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
    AlertDialog.Builder(this).setTitle(getString(R.string.menu_add_child)).setItems(types) { _, w ->
        showNameInputDialog(parent, ids[w])
    }.show()
}

private fun MainActivity.showNameInputDialog(parent: JsonObject, type: Int) {
    val input = EditText(this).apply { hint = getString(R.string.text_name) }
    AlertDialog.Builder(this).setTitle(getString(R.string.hint_new_tag_name)).setView(input)
        .setPositiveButton(getString(R.string.btn_create)) { _, _ ->
            val name = input.text.toString()
            if (name.isEmpty() || parent.has(name)) { toast(getString(R.string.toast_Invalid_name)); return@setPositiveButton }
            AppLogger.info("ListDebug", "showNameInputDialog: 新增标签 name=$name, type=$type, parent.keys=${parent.keySet().joinToString(",") { it }}")
            val t = JsonObject().apply { addProperty("t", type) }
            when (type) {
                1 -> t.addProperty("v", 0.toByte()); 2 -> t.addProperty("v", 0.toShort())
                3 -> t.addProperty("v", 0); 4 -> t.addProperty("v", 0L)
                5 -> t.addProperty("v", 0.0f); 6 -> t.addProperty("v", 0.0)
                7 -> t.add("v", JsonArray()); 8 -> t.addProperty("v", "")
                9 -> { t.add("v", JsonArray()); t.addProperty("itemType", 10); AppLogger.info("ListDebug", "新增的是 List(9) 标签, itemType=10, v=[]") }
                10 -> t.add("v", JsonObject()); 11 -> t.add("v", JsonArray())
                12 -> t.add("v", JsonArray())
            }
            parent.add(name, t)
            AppLogger.info("ListDebug", "新增后 parent.keys=${parent.keySet().joinToString(",") { it }}, 是否已添加=$name=${parent.has(name)}")
            if (editorVM.isTreeMode.value) {
                // 树形模式：重建树，新增的字段才会出现
                nbtTreeAdapter?.rebuild()
                AppLogger.info("ListDebug", "树形模式：已 rebuild")
            } else if (nbtAdapter?.data === parent) {
                nbtAdapter?.refreshKeys()
            } else {
                AppLogger.warn("ListDebug", "nbtAdapter.data !== parent，不刷新（parent 可能不是当前显示层）")
            }
            toast(getString(R.string.toast_created_item, name))
        }.show()
}

// ============================================
// 编辑值对话框（带自动填充）
// ============================================
fun MainActivity.showEditValueDialog(key: String, item: JsonObject, listElementInfo: Pair<JsonArray, Int>? = null) {
    val type = item.get("t").asInt
    if (type == 9 || type == 10) { toast(getString(R.string.toast_click_detail)); return }

    val input = EditText(this)
    if (item.has("v")) {
        val v = item.get("v")
        input.setText(if (v.isJsonArray) v.toString() else v.asString)
    }

    var showAutocomplete = false
    var detectedType = "all"
    val parent = if (editorVM.pathStack.isNotEmpty()) editorVM.pathStack.peek() else ""
    val grandParent = if (editorVM.pathStack.size >= 2) editorVM.pathStack[editorVM.pathStack.size - 2] else ""

    if (key in listOf("Name", "id", "name", "Id", "AttributeName")) {
        detectedType = detectAutocompleteType(parent, grandParent)
        showAutocomplete = true
    }
    if ((type == 1 || type == 2) && key == "Id") {
        val dt = detectAutocompleteType(parent, grandParent)
        if (dt != "all") { detectedType = dt; showAutocomplete = true }
    }

    val builder = AlertDialog.Builder(this)
    builder.setTitle(getString(R.string.title_edit_type_key, getTypeName(type), key))

    if (showAutocomplete) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = getString(R.string.title_choose)
            setTextColor("#2196F3".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showAutocompleteDialog(input, detectedType) }
        })
        builder.setView(layout)
    } else {
        builder.setView(input)
    }

    builder.setPositiveButton(getString(R.string.btn_save)) { _, _ ->
        try {
            val value = input.text.toString().trim()
            when (type) {
                1 -> {
                    val b = value.toInt()
                    if (b < Byte.MIN_VALUE || b > Byte.MAX_VALUE)
                        throw IllegalArgumentException(getString(R.string.msg_value_out_of_range, "Byte", "-128", "127"))
                    item.addProperty("v", b.toByte())
                }
                2 -> {
                    val s = value.toInt()
                    if (s < Short.MIN_VALUE || s > Short.MAX_VALUE)
                        throw IllegalArgumentException(getString(R.string.msg_value_out_of_range, "Short", "-32768", "32767"))
                    item.addProperty("v", s.toShort())
                }
                3 -> item.addProperty("v", value.toInt())
                4 -> item.addProperty("v", value.toLong())
                5, 6 -> item.addProperty("v", value.toDouble())
                8 -> item.addProperty("v", value)
                7, 11, 12 -> item.add("v", JsonParser.parseString(value).asJsonArray)
            }
            // List 元素编辑：把新值写回原数组，避免树形模式下临时 wrapper 丢失数据
            listElementInfo?.let { (arr, idx) ->
                val newV = item.get("v")
                if (newV != null && idx >= 0 && idx < arr.size()) {
                    arr.set(idx, newV)
                }
            }
            refreshAfterEdit()
        } catch (e: Exception) { toast(getString(R.string.err_save_failed, e.message)) }
    }.show()
}

fun getTypeName(t: Int): String = when (t) {
    1 -> "Byte"; 2 -> "Short"; 3 -> "Int"; 4 -> "Long"; 5 -> "Float"; 6 -> "Double"
    7 -> "ByteArray"; 8 -> "String"; 11 -> "IntArray"; 12 -> "LongArray"; else -> "?"
}

// ============================================
// 数组分页
// ============================================
fun MainActivity.showArrayPaginationDialog(key: String, arr: JsonArray) {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 30, 50, 0)
    }
    layout.addView(TextView(this).apply {
        text = getString(R.string.text_huge_array_detected, arr.size()); textSize = 16f
    })
    val etStart = EditText(this).apply {
        hint = getString(R.string.hint_start_0); inputType = InputType.TYPE_CLASS_NUMBER; setText("0")
    }
    val etCount = EditText(this).apply {
        hint = getString(R.string.hint_count_200); inputType = InputType.TYPE_CLASS_NUMBER; setText("200")
    }
    layout.addView(etStart); layout.addView(etCount)

    if (key == "colors" && arr.size() >= 16384) {
        layout.addView(Button(this).apply {
            text = getString(R.string.text_import_images_to_generate_map_images)
            setOnClickListener {
                currentTargetMapArray = arr

                mapImageLauncher.launch(arrayOf("image/*"))
            }
        })
    }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_manage, key)).setView(layout)
        .setPositiveButton(getString(R.string.btn_view_and_edit_clips)) { _, _ ->
            try {
                var s = etStart.text.toString().toInt()
                var c = etCount.text.toString().toInt()
                if (s < 0) s = 0; if (c > 2000) c = 2000
                if (s + c > arr.size()) c = arr.size() - s
                showSubArrayEditDialog(arr, s, c)
            } catch (_: NumberFormatException) { toast(getString(R.string.toast_please_enter_valid_numbers)) }
        }.setNegativeButton(getString(R.string.btn_close), null).show()
}

private fun MainActivity.showSubArrayEditDialog(original: JsonArray, start: Int, count: Int) {
    val sb = StringBuilder("[")
    for (i in 0..<count) { sb.append(original[start + i]); if (i < count - 1) sb.append(",") }
    sb.append("]")
    val input = EditText(this).apply { setText(sb.toString()) }
    AlertDialog.Builder(this).setTitle(getString(R.string.title_edit_range, start, start + count - 1)).setView(input)
        .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
            try {
                val parsed = JsonParser.parseString(input.text.toString())
                if (!parsed.isJsonArray || parsed.asJsonArray.size() != count) {
                    toast(getString(R.string.toast_count_mismatch)); return@setPositiveButton
                }
                for (i in 0..<count) original[start + i] = parsed.asJsonArray[i]
                refreshAfterEdit(); toast(getString(R.string.toast_saved))
            } catch (e: Exception) { toast(getString(R.string.err_format, e.message)) }
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

// ============================================
// NBT 搜索
// ============================================
fun MainActivity.showEditorSearchDialog() {
    val et = EditText(this).apply { hint = getString(R.string.text_search_key_or_translate); isSingleLine = true }

    // 树形 / 列表两种模式都支持搜索
    fun applyFilter(q: String?) {
        if (editorVM.isTreeMode.value) nbtTreeAdapter?.filter(q)
        else nbtAdapter?.filter(q)
    }

    val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.title_search_nbt_data)).setView(et)
        .setPositiveButton(getString(R.string.btn_close), null)
        .setNeutralButton(getString(R.string.btn_clear)) { _, _ -> applyFilter(null); et.setText("") }
        .create()
    et.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {}
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            applyFilter(s.toString())
        }
    })
    dialog.setOnShowListener {
        et.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)
            ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }
    dialog.show()
}

// ============================================
// 视图选项
// ============================================
fun MainActivity.showViewOptionsDialog() {
    val items = arrayOf(getString(R.string.mode_raw), getString(R.string.mode_raw_trans), getString(R.string.mode_smart), getString(R.string.mode_simple))
    AlertDialog.Builder(this).setTitle(getString(R.string.title_view_mode))
        .setSingleChoiceItems(items, editorVM.viewMode.value) { d, which ->
            editorVM.setViewMode(which)
            prefs?.edit { putInt("view_mode", which) }
            nbtAdapter?.setViewMode(which); nbtTreeAdapter?.setViewMode(which)
            d.dismiss(); toast(getString(R.string.toast_mode_changed))
        }.show()
}

// ============================================
// 按名称打开 Key
// ============================================
fun MainActivity.showOpenByKeyDialog() {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 20)
    }
    layout.addView(TextView(this).apply { text = getString(R.string.text_open_nbt_by_name_hint) })
    val etKey = EditText(this); layout.addView(etKey)
    val cbHex = CheckBox(this).apply { text = getString(R.string.title_hex_16_hexadecimal_mode) }; layout.addView(cbHex)
    AlertDialog.Builder(this).setTitle(getString(R.string.title_open_nbt_by_name)).setView(layout)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val input = etKey.text.toString()
            if (input.isEmpty()) { toast(getString(R.string.toast_input_cannot_be_empty)); return@setPositiveButton }
            worldVM.loadCustomKey(input, cbHex.isChecked)
        }.setNegativeButton(getString(R.string.btn_cancel), null).show()
}

// ============================================
// 应用信息
// ============================================
@Suppress("DEPRECATION")
fun MainActivity.showAppInfoDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_app_info, null, false)
    val dialog = AlertDialog.Builder(this).setView(dialogView).create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()

    // 宽度固定 85%，高度按内容自适应但**不超过屏幕 85%** —— 否则内容一多
    // 就会超出屏幕，底部的选项既看不到也点不到（布局根节点是 ScrollView，
    // 有了高度上限才能真正滚动起来）。
    val metrics = resources.displayMetrics
    val winWidth = (metrics.widthPixels * 0.85).toInt()
    val maxHeight = (metrics.heightPixels * 0.85).toInt()
    dialog.window?.let { w ->
        // 先按不限高测量一次内容高度，再取「内容高」与「上限」的较小值
        dialogView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(winWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = dialogView.measuredHeight
        val targetHeight = if (contentHeight in 1 until maxHeight) contentHeight else maxHeight
        w.setLayout(winWidth, targetHeight)
    }

    // 展开/收起时重新计算高度，保证内容变化后依然可滚动
    fun remeasure() {
        dialogView.post {
            dialog.window?.let { w ->
                dialogView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(winWidth, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                val h = dialogView.measuredHeight
                w.setLayout(winWidth, if (h in 1 until maxHeight) h else maxHeight)
            }
        }
    }

    val aboutHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_about_header)
    val aboutContent = dialogView.findViewById<TextView?>(R.id.tv_about_content)
    val aboutExpand = dialogView.findViewById<TextView?>(R.id.tv_about_expand)
    aboutContent?.let {
        it.text = Html.fromHtml(getString(R.string.msg_about_content_full).replace("\n", "<br>"))
        it.movementMethod = LinkMovementMethod.getInstance()
    }
    aboutHeader?.setOnClickListener {
        aboutContent?.let {
            it.isVisible = !it.isVisible
            aboutExpand?.text = if (it.isVisible) "▲" else "▼"
            remeasure()
        }
    }

    val settingsHeader = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_header)
    val settingsContent = dialogView.findViewById<LinearLayout?>(R.id.layout_settings_content)
    val settingsExpand = dialogView.findViewById<TextView?>(R.id.tv_settings_expand)
    settingsHeader?.setOnClickListener {
        settingsContent?.let {
            it.isVisible = !it.isVisible
            settingsExpand?.text = if (it.isVisible) "▲" else "▼"
            remeasure()
        }
    }

    // ① 文件操作多线程
    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_multithread)?.let { cb ->
        cb.isChecked = prefs?.getBoolean("use_multithread", true) ?: true
        cb.setOnCheckedChangeListener { _, c ->
            worldVM.setUseMultiThread(c)
            prefs?.edit()?.putBoolean("use_multithread", c)?.apply()
            // 关闭多线程时，并发数选择失效（置灰）
            dialogView.findViewById<LinearLayout?>(R.id.layout_io_thread_count)?.alpha =
                if (c) 1f else 0.4f
        }
    }
    setupThreadCountSpinner(
        dialogView, R.id.sp_io_thread_count, R.id.layout_io_thread_count,
        prefKey = "io_thread_count",
        enabled = prefs?.getBoolean("use_multithread", true) ?: true,
        onChanged = { worldVM.setIoThreadCount(it) }
    )

    // ② 拼图生成多线程
    dialogView.findViewById<CheckBox?>(R.id.cb_dialog_puzzle_multithread)?.let { cb ->
        cb.isChecked = prefs?.getBoolean("puzzle_multithread", true) ?: true
        cb.setOnCheckedChangeListener { _, c ->
            mapVM.setPuzzleMultiThread(c)
            prefs?.edit()?.putBoolean("puzzle_multithread", c)?.apply()
            dialogView.findViewById<LinearLayout?>(R.id.layout_puzzle_thread_count)?.alpha =
                if (c) 1f else 0.4f
        }
    }
    setupThreadCountSpinner(
        dialogView, R.id.sp_puzzle_thread_count, R.id.layout_puzzle_thread_count,
        prefKey = "puzzle_thread_count",
        enabled = prefs?.getBoolean("puzzle_multithread", true) ?: true,
        onChanged = { mapVM.setPuzzleThreadCount(it) }
    )

    // ---- 草稿箱设置 ----
    setupDraftSettings(dialogView)

    // ---- 回收站自动清理 ----
    setupTrashSettings(dialogView)

    dialogView.findViewById<CheckBox?>(R.id.cb_skip_shizuku)?.let {
        it.isChecked = prefs?.getBoolean("skip_shizuku", false) ?: false
        it.setOnCheckedChangeListener { _, c ->
            worldVM.setUseShizuku(!c)
            prefs?.edit()?.putBoolean("skip_shizuku", c)?.apply()
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_migrate_data)?.setOnClickListener {
        dialog.dismiss()
        startDataMigration(manual = true)
    }

    dialogView.findViewById<Button?>(R.id.btn_dialog_theme)?.let {
        it.text = if (isNightMode) getString(R.string.action_switch_day) else getString(R.string.action_switch_night)
        it.setOnClickListener {
            isNightMode = !isNightMode; prefs?.edit { putBoolean("night_mode", isNightMode) }
            applyTheme(); dialog.dismiss(); recreate()
        }
    }
    dialogView.findViewById<Button?>(R.id.btn_dialog_lang)?.let {
        it.text = if ("zh" == currentLang) getString(R.string.switch_to_english) else getString(R.string.switch_to_chinese)
        it.setOnClickListener {
            // ⚠️ 必须**彻底重建进程**，不能用 recreate()：
            // ViewModel 在 recreate 时存活，NbtTranslator 的 isLoaded / mContext 仍是旧语言，
            // 且 NBT 适配器里已渲染的文本不会重算 —— 于是出现「切成英文后设置页仍是一堆中文」。
            // 切换前先说明会重启（未保存的编辑会进草稿箱），用户确认后再切。
            val target = if ("zh" == currentLang) "en" else "zh"
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_switch_language))
                .setMessage(getString(R.string.msg_switch_language_restart))
                .setPositiveButton(getString(R.string.btn_switch)) { _, _ ->
                    // 落盘当前编辑，避免重启丢内容（存进草稿箱，回来能找回）
                    worldVM.persistSessionNow(this)
                    prefs?.edit { putString("app_language", target) }
                    dialog.dismiss()
                    restartApp()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    var clicks = 0; var lastClick = 0L
    dialogView.findViewById<View?>(R.id.in_app_icon)?.setOnClickListener {
        val now = System.currentTimeMillis()
        if (now - lastClick > 5000) clicks = 0
        lastClick = now; clicks++
        if (clicks >= 10) { clicks = 0; showDebugMenu() }
    }
}

// ============================================
// 调试菜单
// ============================================
fun MainActivity.showDebugMenu() {
    AlertDialog.Builder(this).setTitle(getString(R.string.title_debug_menu))
        .setItems(arrayOf(getString(R.string.debug_crash_test), getString(R.string.debug_show_logs), getString(R.string.debug_export_logs), getString(R.string.debug_copy_logs))) { _, w ->
            when (w) {
                0 -> Handler(Looper.getMainLooper()).postDelayed(
                    { throw RuntimeException("Manual crash test") }, 1000
                )
                1 -> {
                    val logs = AppLogger.getLogs()
                    val scrollView = ScrollView(this)
                    val logTv = TextView(this).apply {
                        text = logs.ifEmpty { getString(R.string.msg_no_logs) }
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setPadding(20, 20, 20, 20)
                        setTextIsSelectable(true)
                    }
                    scrollView.addView(logTv)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_logs))
                        .setView(scrollView)
                        .setPositiveButton(getString(R.string.btn_close), null)
                        .setNeutralButton(getString(R.string.menu_copy)) { _, _ ->
                            if (logs.isEmpty()) {
                                toast(getString(R.string.msg_no_logs_to_copy))
                            } else {
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.title_logs), logs))
                                toast(getString(R.string.toast_logs_copied))
                            }
                        }
                        .setNegativeButton(getString(R.string.btn_export)) { _, _ ->
                            val file = AppLogger.exportToFile()
                            if (file != null) {
                                toast(getString(R.string.toast_logs_exported, file.absolutePath))
                            } else {
                                toast(getString(R.string.msg_no_logs_to_export))
                            }
                        }
                        .show()
                }
                2 -> {
                    val logs = AppLogger.getLogs()
                    if (logs.isEmpty()) {
                        toast(getString(R.string.msg_no_logs_to_copy))
                    } else {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Logs", logs))
                        toast(getString(R.string.toast_logs_copied))
                    }
                }
                3 -> {
                    val file = AppLogger.exportToFile()
                    if (file != null) {
                        toast(getString(R.string.toast_logs_exported, file.absolutePath))
                    } else {
                        toast(getString(R.string.msg_no_logs_to_export))
                    }
                }
            }
        }.show()
}

// ============================================
// 辅助：编辑后刷新
// ============================================
private fun MainActivity.refreshAfterEdit() {
    AppLogger.info("ListDebug", "refreshAfterEdit 调用: isTreeMode=${editorVM.isTreeMode.value}")
    editorVM.syncListFakeMapToSource()
    // 标记草稿待保存：由 DraftAutoSaver 按用户设定的间隔写盘
    DraftAutoSaver.markDirty()
    if (editorVM.isTreeMode.value) {
        // 树形模式：结构可能变化（新增/删除/重命名），必须重建节点树，
        // 仅 notifyDataSetChanged() 不会产生新节点，导致改动看不见。
        nbtTreeAdapter?.rebuild()
    } else {
        nbtAdapter?.refreshKeys()
    }
}

// ============================================
// 草稿箱设置（自动保存间隔 / 单位 / 数量上限 / 保留原始数据）
// ============================================

/** 单位换算：0=秒 1=分 2=时 */
private fun unitFactor(unit: Int): Long = when (unit) {
    1 -> 60_000L
    2 -> 3_600_000L
    else -> 1_000L
}

private fun MainActivity.setupDraftSettings(root: android.view.View) {
    // 注意：Spinner.setSelection() 触发的 onItemSelected 是**异步**回调，
    // 若只用普通布尔标志，函数返回时回调还没到，标志已置回 false，
    // 就会在「打开设置」的瞬间弹出「自定义间隔」输入框。
    // 因此用 post 延迟到初始回调派发完毕后再解除抑制。
    var suppress = true

    val intervalSp = root.findViewById<Spinner?>(R.id.sp_draft_interval)
    val unitSp = root.findViewById<Spinner?>(R.id.sp_draft_interval_unit)
    val maxSp = root.findViewById<Spinner?>(R.id.sp_draft_max)
    val cbAuto = root.findViewById<CheckBox?>(R.id.cb_draft_autosave)
    val cbKeep = root.findViewById<CheckBox?>(R.id.cb_draft_keep_original)

    // 当前值（从 prefs 读，缺省：30 秒 / 上限 10）
    var interval = prefs?.getInt("draft_interval", 30) ?: 30
    var unit = prefs?.getInt("draft_interval_unit", 0) ?: 0      // 0=秒
    var maxDrafts = prefs?.getInt("draft_max", DraftStore.DEFAULT_MAX_DRAFTS)
        ?: DraftStore.DEFAULT_MAX_DRAFTS

    /** 把当前设置应用到自动保存器 */
    fun applyAll() {
        val ms = if (interval <= 0) 0L else interval.toLong() * unitFactor(unit)
        DraftAutoSaver.setInterval(ms)
        DraftAutoSaver.maxDrafts = maxDrafts
        DraftStore.pruneTo(this, maxDrafts)
        prefs?.edit()?.putInt("draft_interval", interval)?.apply()
        prefs?.edit()?.putInt("draft_interval_unit", unit)?.apply()
        prefs?.edit()?.putInt("draft_max", maxDrafts)?.apply()
        // 单位选择在「关闭」时无意义，置灰
        unitSp?.isEnabled = interval > 0
        unitSp?.alpha = if (interval > 0) 1f else 0.4f
        cbAuto?.isChecked = interval > 0
    }

    // ① 自动保存开关：关 = interval 0
    cbAuto?.isChecked = interval > 0
    cbAuto?.setOnCheckedChangeListener { _, checked ->
        if (suppress) return@setOnCheckedChangeListener
        if (!checked) {
            interval = 0
            intervalSp?.setSelection(0)   // 「关闭」
        } else {
            var restored = 30
            // 优先用上次的值（自定义间隔存在 prefs）
            val lastCustom = prefs?.getInt("draft_interval_last", 30) ?: 30
            restored = if (lastCustom > 0) lastCustom else 30
            interval = restored
            val idx = DRAFT_INTERVAL_VALUES.indexOf(restored)
            intervalSp?.setSelection(if (idx >= 0) idx else DRAFT_INTERVAL_VALUES.size - 1)
        }
        applyAll()
    }

    // ② 间隔
    // 「自定义」项用短标签（避免在半宽 Spinner 里被截断成 "Cu.."），
    // 且当自定义值生效时直接显示该数值，让用户一眼看到当前设定
    val customIcon = getString(R.string.draft_interval_custom)
    fun customLabel(): String {
        val isCustomValue = interval > 0 && DRAFT_INTERVAL_VALUES.indexOf(interval) < 0
        return if (isCustomValue) "$customIcon $interval" else customIcon
    }
    // 注意：必须用 MutableList。ArrayAdapter.remove()/insert() 会直接操作
    // 底层集合，若传入 listOf(...) 生成的不可变 List，会抛
    // UnsupportedOperationException: remove（已在真机崩溃日志中出现过）。
    val intervalLabels = mutableListOf(
        getString(R.string.draft_interval_off), "2", "4", "8", customLabel()
    )
    val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
    intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    intervalSp?.adapter = intervalAdapter

    // ③ 单位
    val unitLabels = mutableListOf(
        getString(R.string.unit_seconds), getString(R.string.unit_minutes), getString(R.string.unit_hours)
    )
    unitSp?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, unitLabels)
        .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    // ④ 数量上限
    val maxLabels = mutableListOf("5", "10", "20", getString(R.string.thread_count_custom))
    val maxValues = listOf(5, 10, 20, -1)
    maxSp?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, maxLabels)
        .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    // 初始化回显
    run {
        val idx = DRAFT_INTERVAL_VALUES.indexOf(interval)
        intervalSp?.setSelection(if (interval <= 0) 0 else if (idx >= 0) idx else DRAFT_INTERVAL_VALUES.size - 1)
        unitSp?.setSelection(unit.coerceIn(0, 2))
        val mIdx = maxValues.indexOf(maxDrafts)
        maxSp?.setSelection(if (mIdx >= 0) mIdx else maxValues.size - 1)
    }
    applyAll()

    /** 刷新「自定义」项文字，反映当前自定义值 */
    fun refreshIntervalCustomLabel() {
        val sp = intervalSp ?: return
        val idx = intervalLabels.size - 1
        if (idx < 0) return
        val want = customLabel()
        if (intervalLabels.getOrNull(idx) == want) return
        // 原地修改 + 通知刷新（不要用 adapter.remove/insert，
        // 且无论如何都不该改动 List 的长度，只改最后一项的文字）
        // 这里的 setSelection 不会误触发监听器 —— 监听器已改用「用户触摸」判断
        intervalLabels[idx] = want
        intervalAdapter.notifyDataSetChanged()
        if (interval > 0 && DRAFT_INTERVAL_VALUES.indexOf(interval) < 0) {
            sp.setSelection(idx)
        }
    }

    // ⚠️ 时序陷阱：setSelection 的回调由**布局阶段**派发，时机不可控
    // （post{} / 延迟标志都不可靠）。改为只响应**用户真的触摸过**的操作，
    // 程序回显不产生触摸事件，因此天然被忽略。
    var userPickedInterval = false
    intervalSp?.setOnTouchListener { _, _ -> userPickedInterval = true; false }
    intervalSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedInterval) return
            val valAt = DRAFT_INTERVAL_VALUES.getOrNull(position)
            if (valAt == null || valAt == -1) {
                // 「自定义…」
                showCustomNumberDialog(
                    getString(R.string.title_custom_interval), 1, 999,
                    getString(R.string.msg_draft_interval_invalid),
                    if (interval > 0) interval else 30
                ) { n ->
                    interval = n
                    prefs?.edit()?.putInt("draft_interval_last", n)?.apply()
                    refreshIntervalCustomLabel()
                    applyAll()
                }
            } else {
                interval = valAt
                if (valAt > 0) prefs?.edit()?.putInt("draft_interval_last", valAt)?.apply()
                applyAll()
            }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    var userPickedUnit = false
    unitSp?.setOnTouchListener { _, _ -> userPickedUnit = true; false }
    unitSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedUnit) return
            unit = position.coerceIn(0, 2)
            applyAll()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    var userPickedMax = false
    maxSp?.setOnTouchListener { _, _ -> userPickedMax = true; false }
    maxSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedMax) return
            val valAt = maxValues.getOrNull(position) ?: return
            if (valAt == -1) {
                showCustomNumberDialog(
                    getString(R.string.title_custom_draft_max), 1, 99,
                    getString(R.string.msg_draft_max_invalid), maxDrafts
                ) { n ->
                    maxDrafts = n
                    applyAll()
                }
            } else {
                maxDrafts = valAt
                applyAll()
            }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ⑤ 保留原始数据
    cbKeep?.isChecked = prefs?.getBoolean("draft_keep_original", false) ?: false
    DraftAutoSaver.keepOriginal = cbKeep?.isChecked ?: false
    cbKeep?.setOnCheckedChangeListener { _, c ->
        DraftAutoSaver.keepOriginal = c
        prefs?.edit()?.putBoolean("draft_keep_original", c)?.apply()
    }

    // 初始选中回调派发完毕后再允许用户交互，避免误触发「自定义」输入框
    root.post { suppress = false }
}

/** 「关闭 / 2 / 4 / 8 / 自定义」对应的秒数；-1 表示自定义 */
private val DRAFT_INTERVAL_VALUES = listOf(0, 2, 4, 8, -1)

/** 通用「输入一个数字」对话框（带范围校验） */
private fun MainActivity.showCustomNumberDialog(
    title: String, min: Int, max: Int, invalidMsg: String,
    current: Int, onOk: (Int) -> Unit
) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = "$min - $max"
        setText(if (current in min..max) current.toString() else "")
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < min || n > max) toast(invalidMsg) else onOk(n)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

// ============================================
// 回收站设置（自动清理天数）
// ============================================

/** 「不自动清 / 7 / 30 / 90 / 自定义」对应的天数；-1 表示自定义 */
private val TRASH_KEEP_VALUES = listOf(0, 7, 30, 90, -1)

private fun MainActivity.setupTrashSettings(root: android.view.View) {
    val sp = root.findViewById<Spinner?>(R.id.sp_trash_keep) ?: return
    var days = prefs?.getInt("trash_keep_days", TrashStore.DEFAULT_KEEP_DAYS)
        ?: TrashStore.DEFAULT_KEEP_DAYS

    fun labelFor(d: Int): String = when (d) {
        0 -> getString(R.string.trash_keep_never)
        else -> getString(R.string.trash_keep_days, d)
    }

    // 「自定义」格：一旦有自定义值，直接显示成「N 天」
    // （之前显示「自定义… 3650」，用户看不出到底保留多少天）
    fun customLabel(): String =
        if (days > 0 && TRASH_KEEP_VALUES.indexOf(days) < 0) labelFor(days)
        else getString(R.string.thread_count_custom)

    val labels = mutableListOf(
        labelFor(0), labelFor(7), labelFor(30), labelFor(90), customLabel()
    )
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter

    fun apply() {
        prefs?.edit()?.putInt("trash_keep_days", days)?.apply()
    }

    // 回显
    run {
        val idx = TRASH_KEEP_VALUES.indexOf(days)
        sp.setSelection(if (idx >= 0) idx else TRASH_KEEP_VALUES.size - 1)
    }
    apply()

    // ⚠️ 时序陷阱：setSelection 的回调由**布局阶段**派发，时机不可控
    // （post{} / 延迟标志都不可靠）。改为只响应**用户真的触摸过**的操作，
    // 程序回显不产生触摸事件，因此天然被忽略。
    var userPicked = false
    sp.setOnTouchListener { _, _ -> userPicked = true; false }
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPicked) return
            val valAt = TRASH_KEEP_VALUES.getOrNull(position) ?: return
            if (valAt == -1) {
                showCustomNumberDialog(
                    getString(R.string.title_custom_trash_keep), 1, 3650,
                    getString(R.string.msg_trash_keep_invalid),
                    if (days > 0) days else 30
                ) { n ->
                    days = n
                    apply()
                    // 刷新「自定义」项文字（改内容不改长度，用 notifyDataSetChanged）
                    labels[labels.size - 1] = customLabel()
                    adapter.notifyDataSetChanged()
                }
            } else {
                days = valAt
                apply()
            }
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}

// ============================================
// 并发线程数选择器（自动 / 2 / 4 / 8 / 自定义…）
// ============================================

/**
 * 绑定一个线程数 Spinner。
 * @param onChanged 最终值回调：0 = 自动，>0 = 指定线程数
 * @param enabled   父开关是否开启（关闭时置灰禁用）
 */
private fun MainActivity.setupThreadCountSpinner(
    root: View,
    spinnerId: Int,
    containerId: Int,
    prefKey: String,
    enabled: Boolean,
    onChanged: (Int) -> Unit
) {
    val spinner = root.findViewById<Spinner?>(spinnerId) ?: return
    val container = root.findViewById<LinearLayout?>(containerId)

    // 选项：自动 / 2 / 4 / 8 / 自定义…
    // 必须用 MutableList —— 不可变 List 上调用 ArrayAdapter.remove 会抛
    // UnsupportedOperationException（与草稿间隔那处是同一个坑）。
    val labels = mutableListOf(
        getString(R.string.thread_count_auto), "2", "4", "8",
        getString(R.string.thread_count_custom)
    )
    val values = listOf(0, 2, 4, 8, -1)  // -1 代表「自定义…」

    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter

    /** 把「自定义…」那一项的文字刷新为「自定义 (N)」（原地改，不动长度） */
    fun updateCustomLabel(n: Int) {
        val idx = labels.size - 1
        if (idx < 0) return
        labels[idx] = "${getString(R.string.thread_count_custom).removeSuffix("…")} ($n)"
        adapter.notifyDataSetChanged()
    }

    // 读取已保存的值并选中对应项
    val saved = prefs?.getInt(prefKey, 0) ?: 0
    val presetIdx = values.indexOf(saved)
    if (presetIdx >= 0) {
        spinner.setSelection(presetIdx)
    } else {
        // 自定义值：显示为「自定义 (N)」
        updateCustomLabel(saved)
        spinner.setSelection(labels.size - 1)
    }

    spinner.isEnabled = enabled
    container?.alpha = if (enabled) 1f else 0.4f

    // 同草稿设置：只有用户真的触摸过才响应，避免回显时误弹「自定义」输入框
    var userPickedThread = false
    spinner.setOnTouchListener { _, _ -> userPickedThread = true; false }
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!userPickedThread) return
            val v = values[position]
            if (v == -1) {
                // 「自定义…」：弹出输入框
                showCustomThreadDialog(prefKey, saved) { n ->
                    prefs?.edit()?.putInt(prefKey, n)?.apply()
                    onChanged(n)
                    updateCustomLabel(n)
                    spinner.setSelection(labels.size - 1)
                }
            } else {
                prefs?.edit()?.putInt(prefKey, v)?.apply()
                onChanged(v)
            }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

private fun MainActivity.showCustomThreadDialog(prefKey: String, current: Int, onOk: (Int) -> Unit) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        hint = getString(R.string.hint_thread_count_range)
        setText(if (current > 0) current.toString() else "")
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_custom_thread_count))
        .setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > 32) {
                toast(getString(R.string.msg_thread_count_invalid))
            } else {
                onOk(n)
            }
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}