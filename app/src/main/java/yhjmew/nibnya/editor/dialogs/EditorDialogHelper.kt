package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
import yhjmew.nibnya.util.BackupSettings
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
            // 用「🔍 从列表选择」这类明确说法，而不是光秃秃的「选择」——
            // 玩家才知道这个按钮是干什么的
            text = getString(R.string.title_choose)
            isAllCaps = false
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

    // ---- 保存前自动备份（放在草稿箱上面）----
    setupBackupSettings(dialogView)

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
            val target = if ("zh" == currentLang) "en" else "zh"
            val skip = prefs?.getBoolean(PREF_SKIP_LANG_PROMPT, false) == true
            if (skip) {
                applyLanguageAndRestart(target, dialog)
            } else {
                showLanguageSwitchDialog(target, dialog)
            }
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
                    // 与全局错误弹窗同一形态；「导出」是内置按钮，不用再传 actionText
                    showLogDialog(
                        title = getString(R.string.title_logs),
                        detail = AppLogger.getLogs().ifEmpty { getString(R.string.msg_no_logs) },
                        cancelText = getString(R.string.btn_close)
                    )
                }
                // 索引 2 =「💾 导出日志到文件」——与日志弹窗里的导出走同一条路径
                2 -> exportLogsToFile()
                // 索引 3 =「📄 复制日志到剪贴板」
                3 -> copyToClipboard(getString(R.string.title_logs), AppLogger.getLogs())
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
    val intervalSp = root.findViewById<Spinner?>(R.id.sp_draft_interval)
    val unitSp = root.findViewById<Spinner?>(R.id.sp_draft_interval_unit)
    val maxSp = root.findViewById<Spinner?>(R.id.sp_draft_max)
    val cbAuto = root.findViewById<CheckBox?>(R.id.cb_draft_autosave) ?: return
    val cbKeep = root.findViewById<CheckBox?>(R.id.cb_draft_keep_original)

    // 当前值（从 prefs 读，缺省：30 秒 / 上限 10）
    var interval = prefs?.getInt("draft_interval", 30) ?: 30
    var unit = prefs?.getInt("draft_interval_unit", 0) ?: 0      // 0=秒
    var maxDrafts = prefs?.getInt("draft_max", DraftStore.DEFAULT_MAX_DRAFTS)
        ?: DraftStore.DEFAULT_MAX_DRAFTS
    // 程序回显复选框时抑制回调（否则 setChecked 会反过来改 interval）
    var suppressCheck = false

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
        // 顶部的复选框跟着「间隔是否 > 0」走
        suppressCheck = true
        cbAuto.isChecked = interval > 0
        suppressCheck = false
    }

    // ① 自动保存开关：关 = interval 0
    cbAuto.isChecked = interval > 0
    cbAuto.setOnCheckedChangeListener { _, checked ->
        if (suppressCheck) return@setOnCheckedChangeListener
        if (!checked) {
            interval = 0
            intervalSp?.setSelection(0)   // 「关闭」
        } else {
            val lastCustom = prefs?.getInt("draft_interval_last", 30) ?: 30
            interval = if (lastCustom > 0) lastCustom else 30
            val idx = DRAFT_INTERVAL_VALUES.indexOf(interval)
            intervalSp?.setSelection(if (idx >= 0) idx else DRAFT_INTERVAL_VALUES.size - 1)
        }
        applyAll()
    }

    // ② 间隔：关闭 / 2 / 4 / 8 / 自定义
    // 自定义格改成短图标 + 数值（`✎ 30`），避免被截断成看不到数字
    bindPresetSpinner(
        sp = intervalSp,
        presetLabels = listOf(getString(R.string.draft_interval_off), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_interval,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { interval },
        applyValue = { n ->
            interval = n
            if (n > 0) prefs?.edit()?.putInt("draft_interval_last", n)?.apply()
            applyAll()
        }
    )

    // ③ 单位
    unitSp?.adapter = ArrayAdapter(
        this, android.R.layout.simple_spinner_item,
        mutableListOf(
            getString(R.string.unit_seconds),
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours)
        )
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    unitSp?.setSelection(unit.coerceIn(0, 2))
    var userPickedUnit = false
    unitSp?.setOnTouchListener { _, _ -> userPickedUnit = true; false }
    unitSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedUnit) return
            unit = position
            applyAll()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ④ 数量上限：5 / 10 / 20 / 自定义（同样用短图标 + 数值）
    bindPresetSpinner(
        sp = maxSp,
        presetLabels = listOf("5", "10", "20"),
        presetValues = listOf(5, 10, 20),
        titleRes = R.string.title_custom_draft_max,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { maxDrafts },
        applyValue = { n -> maxDrafts = n; applyAll() }
    )

    cbKeep?.isChecked = prefs?.getBoolean("draft_keep_original", false) ?: false
    cbKeep?.setOnCheckedChangeListener { _, checked ->
        DraftAutoSaver.keepOriginal = checked
        prefs?.edit()?.putBoolean("draft_keep_original", checked)?.apply()
    }

    applyAll()
}

/** 「关闭 / 2 / 4 / 8 / 自定义」对应的秒数；-1 表示自定义 */
private val DRAFT_INTERVAL_VALUES = listOf(0, 2, 4, 8, -1)

// ============================================
// 「保存前自动备份」设置（结构完全对齐草稿箱那组）
// ============================================

/**
 * 开关 + 间隔（关闭/2/4/8/自定义 + 秒·分·时） + 上限（5/10/20/自定义）。
 *
 * ⚠️ 与草稿箱那组一样有两个时序/适配器陷阱，这里照同样的方式规避：
 * 1. `Spinner.setSelection()` 的回调由**布局阶段**派发，时机不可控 → 只响应**用户真的触摸过**的操作
 * 2. `ArrayAdapter` 必须拿 **MutableList**（`remove`/`insert` 会改动底层集合）
 */
/**
 * 绑定「预设值 + 自定义…」下拉框。**统一解决两个老毛病**：
 *
 * 1. **自定义值显示不全**：以前自定义格用「自定义…」当前缀，加上数字后太长，
 *    在窄 Spinner 里被截断成「自定义…」，用户看不到自己设了几 → 改用**短图标** `✎ N`
 *    （草稿箱的间隔早就这么干了，其它几处没跟上）。
 * 2. **再次点击「自定义」没反应**：若 Spinner 的选中索引没变，`onItemSelected` **不会回调**，
 *    于是用户第二次点同一格时什么都不发生。→ 触摸后补一次检查兜底，并用时间戳防重入。
 */
internal fun MainActivity.bindPresetSpinner(
    sp: Spinner?,
    presetLabels: List<String>,
    presetValues: List<Int>,
    titleRes: Int,
    invalidMsgRes: Int,
    currentValue: () -> Int,
    applyValue: (Int) -> Unit
) {
    if (sp == null) return
    val customIdx = presetValues.size
    val pen = getString(R.string.draft_interval_custom)
    val tapHint = getString(R.string.custom_tap_to_edit)

    /** 是否已经设过自定义值（值不等于任何预设） */
    fun hasCustomValue(): Boolean {
        val v = currentValue()
        return v > 0 && presetValues.indexOf(v) < 0
    }

    /**
     * **收起态**那一栏的文案（乙方案）：
     * - 设过自定义 → `✎ 5`（带笔，一眼看出是自己设的）
     * - 没设过 → 「自定义」（提示入口在这儿）
     */
    fun collapsedLabel(): String =
        if (hasCustomValue()) "$pen ${currentValue()}"
        else getString(R.string.thread_count_custom).removeSuffix("…")

    /**
     * **展开态**那一行的文案：始终带「（点此修改）」提示 ——
     * 只有在展开时才提示「可点」，收起时就只显示数值，避免被挤成多行。
     */
    fun expandedLabel(): String =
        if (hasCustomValue()) "$pen ${currentValue()}$tapHint"
        else getString(R.string.thread_count_custom).removeSuffix("…") + tapHint

    // 收起态与展开态的两套文案（同一个位置可能不一样）
    val collapsed = (presetLabels + collapsedLabel()).toMutableList()
    val expanded = (presetLabels + expandedLabel()).toMutableList()

    lateinit var openCustom: () -> Unit

    // 直接用 ArrayAdapter 套系统布局：
    // - 收起态 getView 由 ArrayAdapter + simple_spinner_item 提供（黑字/默认字号/默认内边距）
    // - 展开态 getDropDownView 由 simple_spinner_dropdown_item 提供
    // 只覆盖 getDropDownView 来加「（点此修改）」与点击监听。
    val adapter = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, collapsed
    ) {
        /**
         * 展开态：自定义那一行显示 `✎ 5（点此修改）` 并挂点击。
         *
         * ⚠️ 为什么必须挂点击：AdapterView 在「选中位置没变」时**不会回调 onItemSelected**
         *    （setSelection 里 `mOldSelectedPosition == position` 就直接 return），
         *    所以「再展开下拉点同一格」天生收不到事件。挂在行 View 上才能保证一定能再次编辑
         *    （ListPopupWindow 的 onItemClick 在 ListView 层处理，不会被行点击抢掉）。
         */
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val tv = super.getDropDownView(position, convertView, parent) as TextView
            tv.text = expanded[position]
            // 复用 View 时必须清掉上一次挂的监听，否则滚动后会串号
            tv.setOnClickListener(null)
            if (position == customIdx) {
                tv.setOnClickListener { openCustom() }
            }
            return tv
        }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = adapter

    fun refreshLabels() {
        collapsed[customIdx] = collapsedLabel()
        expanded[customIdx] = expandedLabel()
        adapter.notifyDataSetChanged()
    }

    // 回显：命中预设就选它，否则停在自定义格
    val idx = presetValues.indexOf(currentValue())
    sp.setSelection(if (idx >= 0) idx else customIdx)

    var userPicked = false
    openCustom = {
        showCustomNumberDialog(
            getString(titleRes), 1, CUSTOM_VALUE_MAX, getString(invalidMsgRes),
            currentValue().takeIf { it > 0 } ?: 1
        ) { n ->
            applyValue(n)
            refreshLabels()
            sp.setSelection(customIdx)
        }
    }

    // ⚠️ 这里**只负责标记「用户真的操作过」**，不要再加 postDelayed 之类的兜底：
    //    早期版本在触摸后延迟检查「当前是否停在自定义格」就弹框，结果用户每次
    //    「单纯展开下拉」都会被弹出输入框（选中项本来就是自定义格）。
    sp.setOnTouchListener { _, _ -> userPicked = true; false }
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
            if (!userPicked) return                  // 程序回显不处理
            if (position == customIdx) openCustom() else applyValue(presetValues[position])
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}

private fun MainActivity.setupBackupSettings(root: android.view.View) {
    val cbAuto = root.findViewById<CheckBox?>(R.id.cb_backup_auto) ?: return
    val intervalSp = root.findViewById<Spinner?>(R.id.sp_backup_interval)
    val unitSp = root.findViewById<Spinner?>(R.id.sp_backup_interval_unit)
    val maxSp = root.findViewById<Spinner?>(R.id.sp_backup_max)
    val layoutInterval = root.findViewById<android.view.View?>(R.id.layout_backup_interval)
    val layoutMax = root.findViewById<android.view.View?>(R.id.layout_backup_max)

    var auto = prefs?.getBoolean(BackupSettings.PREF_AUTO, true) ?: true
    var interval = prefs?.getInt(BackupSettings.PREF_INTERVAL, BackupSettings.DEFAULT_INTERVAL)
        ?: BackupSettings.DEFAULT_INTERVAL
    var unit = prefs?.getInt(BackupSettings.PREF_INTERVAL_UNIT, BackupSettings.DEFAULT_UNIT)
        ?: BackupSettings.DEFAULT_UNIT
    var maxKeep = prefs?.getInt(BackupSettings.PREF_MAX, BackupSettings.DEFAULT_MAX)
        ?: BackupSettings.DEFAULT_MAX

    fun writePrefs() {
        prefs?.edit()
            ?.putBoolean(BackupSettings.PREF_AUTO, auto)
            ?.putInt(BackupSettings.PREF_INTERVAL, interval)
            ?.putInt(BackupSettings.PREF_INTERVAL_UNIT, unit)
            ?.putInt(BackupSettings.PREF_MAX, maxKeep)
            ?.apply()
        // 关掉总开关时整组置灰（和草稿箱「关闭时单位置灰」一个思路）
        layoutInterval?.alpha = if (auto) 1f else 0.4f
        layoutMax?.alpha = if (auto) 1f else 0.4f
        intervalSp?.isEnabled = auto
        unitSp?.isEnabled = auto && interval > 0
        maxSp?.isEnabled = auto
    }

    cbAuto.isChecked = auto
    cbAuto.setOnCheckedChangeListener { _, checked ->
        auto = checked
        writePrefs()
    }

    // ---- 间隔：关闭 / 2 / 4 / 8 / 自定义（+ 秒·分·时）----
    bindPresetSpinner(
        sp = intervalSp,
        presetLabels = listOf(getString(R.string.backup_interval_off), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_interval,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { interval },
        applyValue = { n ->
            interval = n
            if (n > 0) prefs?.edit()?.putInt(BackupSettings.PREF_INTERVAL_LAST, n)?.apply()
            writePrefs()
        }
    )

    unitSp?.adapter = ArrayAdapter(
        this, android.R.layout.simple_spinner_item,
        mutableListOf(
            getString(R.string.unit_seconds),
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours)
        )
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    unitSp?.setSelection(unit.coerceIn(0, 2))
    var userPickedUnit = false
    unitSp?.setOnTouchListener { _, _ -> userPickedUnit = true; false }
    unitSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
            if (!userPickedUnit) return
            unit = position
            writePrefs()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ---- 上限：5 / 10 / 20 / 自定义 ----
    bindPresetSpinner(
        sp = maxSp,
        presetLabels = listOf("5", "10", "20"),
        presetValues = listOf(5, 10, 20),
        titleRes = R.string.title_custom_backup_max,
        invalidMsgRes = R.string.msg_draft_interval_invalid,
        currentValue = { maxKeep },
        applyValue = { n ->
            maxKeep = n
            writePrefs()
        }
    )

    writePrefs()
}

private fun MainActivity.showCustomNumberDialog(
    title: String, min: Int, max: Int, invalidMsg: String,
    current: Int, onOk: (Int) -> Unit
) {
    val input = EditText(this).apply {
        // TYPE_CLASS_NUMBER 让键盘只有数字；再加 InputFilter 挡住粘贴进来的
        // 小数点 / 科学记数法 / 负号（用户明确要求只能填整数）
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(digitsOnlyFilter())
        hint = getString(R.string.hint_number_range, min, max)
        setText(if (current in min..max) current.toString() else "")
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
            // toIntOrNull 顺带挡住 "1.5" / "1e3" 这类非法输入
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < min || n > max) toast(invalidMsg) else onOk(n)
        }
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .show()
}

// ============================================
// 回收站设置（自动清理天数）
// ============================================

/** 自定义数字框允许的最大值（用户要求：最大不超过 1 万） */
internal const val CUSTOM_VALUE_MAX = 10_000

/** 只允许纯数字（挡掉小数点、科学记数法、负号；粘贴同样被过滤） */
private fun digitsOnlyFilter(): android.text.InputFilter =
    android.text.InputFilter { source, _, _, _, _, _ ->
        if (source.isEmpty()) return@InputFilter source
        if (source.all { it in '0'..'9' }) source else ""
    }

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
                    getString(R.string.title_custom_trash_keep), 1, CUSTOM_VALUE_MAX,
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

    var saved = prefs?.getInt(prefKey, 0) ?: 0

    // 选项：自动 / 2 / 4 / 8 / 自定义（与草稿·备份走同一套 bindPresetSpinner，
    // 共用「✎ N（点此修改）」文案与「自定义行可点击」的行为）
    bindPresetSpinner(
        sp = spinner,
        presetLabels = listOf(getString(R.string.thread_count_auto), "2", "4", "8"),
        presetValues = listOf(0, 2, 4, 8),
        titleRes = R.string.title_custom_thread_count,
        invalidMsgRes = R.string.msg_thread_count_invalid,
        currentValue = { saved },
        applyValue = { n ->
            saved = n
            prefs?.edit()?.putInt(prefKey, n)?.apply()
            onChanged(n)
        }
    )

    spinner.isEnabled = enabled
    container?.alpha = if (enabled) 1f else 0.4f
}

