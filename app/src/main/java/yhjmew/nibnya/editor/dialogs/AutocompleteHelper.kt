package yhjmew.nibnya.editor.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R

/**
 * 一条可选项（物品 / 方块 / 效果 / 附魔）。
 *
 * @param id 仅效果与附魔有数字 ID；物品/方块为 null
 */
data class AutocompleteEntry(
    val name: String,
    val namespace: String,
    val category: String,
    val id: String? = null
) {
    /** 效果/附魔填数字 ID，其余填 `命名空间:标识符` */
    val fillValue: String
        get() = id?.takeIf { it.isNotEmpty() } ?: namespace
}

/** 「使用自定义 ID」那一行的标记 */
private const val CAT_CUSTOM = "custom"

// ============================================
// 数据加载（带缓存）
// ============================================

/**
 * 词表缓存。
 *
 * 原来每次打开选择框都要重新解析 JSON（物品 585 条 + 方块 1326 条，30~75 KB），
 * **且在主线程**，所以打开时明显卡顿。这里按 `语言|分类` 缓存，只解析一次。
 */
private object AutocompleteData {
    private val cache = HashMap<String, List<AutocompleteEntry>>()

    fun load(ctx: Context, lang: String, category: String): List<AutocompleteEntry> {
        val key = "$lang|$category"
        cache[key]?.let { return it }

        val folder = if (lang == "zh") "zh" else "en"
        val list = ArrayList<AutocompleteEntry>()
        when (category) {
            "item" -> read(ctx, list, "$folder/item_translation.json", "item")
            "block" -> read(ctx, list, "$folder/block_translation.json", "block")
            "effect" -> read(ctx, list, "$folder/potion_translation.json", "effect")
            "enchant" -> read(ctx, list, "$folder/ench_translation.json", "enchant")
            "all" -> {
                read(ctx, list, "$folder/item_translation.json", "item")
                read(ctx, list, "$folder/block_translation.json", "block")
            }
        }
        cache[key] = list
        return list
    }

    private fun read(
        ctx: Context, out: MutableList<AutocompleteEntry>, path: String, category: String
    ) {
        try {
            val json = ctx.assets.open(path).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            val arr = JsonParser.parseString(json).asJsonArray
            for (i in 0 until arr.size()) {
                val obj = arr[i].asJsonObject
                var ns = obj.get("namespace")?.asString ?: ""
                val name = obj.get("name")?.asString ?: ""
                val id = obj.get("id")?.asString
                if (ns.isEmpty()) continue
                // 效果/附魔是内置枚举，不加命名空间前缀；物品/方块补 minecraft:
                if (category != "effect" && category != "enchant" && !ns.contains(":")) {
                    ns = "minecraft:$ns"
                }
                out.add(AutocompleteEntry(name, ns, category, id))
            }
        } catch (_: Exception) {
            // 资源缺失不该让整个选择框不可用
        }
    }
}

// ============================================
// 选择对话框
// ============================================

/** 四个分类标签（顺序即展示顺序） */
private val CATEGORIES = listOf("item", "block", "effect", "enchant")

/**
 * 打开「搜索并选择」对话框。
 *
 * @param targetInput 选中后把值填回这个输入框
 * @param dataType    初始分类（item / block / effect / enchant / all）
 */
fun MainActivity.showAutocompleteDialog(targetInput: EditText, dataType: String) {
    // 初始停在与上下文相符的分类；"all"（如编辑物品的 Name）落在「物品」
    var current = if (CATEGORIES.contains(dataType)) dataType else "item"
    var query = ""

    val pad = (14 * resources.displayMetrics.density).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
    }

    // ---- 搜索行：[输入框] [清空] ----
    // 「清空」放在搜索框旁边才顺手（原来它在底部按钮栏里，且不生效）。
    val searchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val etSearch = EditText(this).apply {
        hint = getString(R.string.search_type_hint, getString(categoryLabelRes(current)))
        textSize = 14f
        maxLines = 1
    }
    searchRow.addView(etSearch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val btnClear = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = getString(R.string.btn_clear)
        isAllCaps = false
        textSize = 13f
        setTextColor("#2196F3".toColorInt())
    }
    searchRow.addView(btnClear)
    root.addView(searchRow)

    // ---- 分类标签 ----
    val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabs = HashMap<String, TextView>()
    var refreshList: () -> Unit = {}

    fun styleTab(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) "#2196F3".toColorInt() else "#666666".toColorInt())
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.setBackgroundColor(if (selected) "#E3F2FD".toColorInt() else Color.TRANSPARENT)
    }

    for (cat in CATEGORIES) {
        val tv = TextView(this).apply {
            text = getString(categoryLabelRes(cat))
            textSize = 13f
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                if (current == cat) return@setOnClickListener
                current = cat
                tabs.forEach { (k, v) -> styleTab(v, k == cat) }
                etSearch.hint =
                    getString(R.string.search_type_hint, getString(categoryLabelRes(cat)))
                refreshList()
            }
        }
        styleTab(tv, cat == current)
        tabs[cat] = tv
        tabRow.addView(tv)
    }
    root.addView(
        HorizontalScrollView(this).apply { addView(tabRow) },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    // ---- 结果列表（高度按屏幕比例；原来硬编码 400px，只能显示 3 条）----
    val listHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
    val lv = ListView(this)
    root.addView(
        lv,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight)
    )

    // ---- 底部计数 ----
    val tvCount = TextView(this).apply {
        textSize = 12f
        setTextColor("#888888".toColorInt())
        setPadding(pad, pad / 3, pad, pad / 3)
    }
    root.addView(tvCount)

    var adapter: ArrayAdapter<AutocompleteEntry>? = null

    fun rebuild() {
        val all = AutocompleteData.load(this, currentLang ?: "zh", current)
        val raw = query.trim()
        val q = raw.lowercase(Locale.getDefault())

        val rows: MutableList<AutocompleteEntry> = ArrayList()

        // 搜不到时给一条「使用自定义 ID」的出口（模组物品 / 新版本物品不在词表里）
        val customCandidate = raw.isNotEmpty() && !raw.contains(' ') &&
                all.none { it.namespace.equals(raw, true) || it.id == raw }
        if (customCandidate) {
            rows.add(AutocompleteEntry(raw, raw, CAT_CUSTOM))
        }

        val matched = if (q.isEmpty()) all
        else all.mapNotNull { e -> rank(e, q)?.let { e to it } }
            .sortedBy { it.second }
            .map { it.first }
        rows.addAll(matched)

        adapter = object : ArrayAdapter<AutocompleteEntry>(
            this, android.R.layout.simple_list_item_2, rows
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val item = getItem(position) ?: return view
                val tv1 = view.findViewById<TextView>(android.R.id.text1)
                val tv2 = view.findViewById<TextView>(android.R.id.text2)
                if (item.category == CAT_CUSTOM) {
                    tv1.text = getString(R.string.btn_use_custom_id, item.namespace)
                    tv1.setTextColor("#2196F3".toColorInt())
                    tv2.text = getString(R.string.msg_use_custom_id_hint)
                } else {
                    val prefix = when (item.category) {
                        "block" -> "[Block] "; "item" -> "[Item] "
                        "effect" -> "[Effect] "; "enchant" -> "[Enchant] "; else -> ""
                    }
                    tv1.text = "$prefix${item.name}"
                    tv1.setTextColor("#333333".toColorInt())
                    tv2.text =
                        if (item.id != null) "ID:${item.id} | ${item.namespace}" else item.namespace
                }
                tv1.textSize = 15f
                tv2.setTextColor("#666666".toColorInt())
                tv2.textSize = 12f
                return view
            }
        }
        lv.adapter = adapter

        tvCount.text = when {
            q.isEmpty() -> getString(R.string.msg_autocomplete_count, all.size, all.size)
            matched.isEmpty() -> getString(R.string.msg_autocomplete_no_match)
            else -> getString(R.string.msg_autocomplete_count, all.size, matched.size)
        }
    }
    refreshList = { rebuild() }

    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_choose_type, getString(categoryLabelRes(current))))
        .setView(root)
        .setNegativeButton(R.string.btn_cancel, null)
        .create()

    lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
        adapter?.getItem(position)?.let {
            targetInput.setText(it.fillValue)
            targetInput.setSelection(targetInput.text.length)
        }
        dialog.dismiss()
    }

    btnClear.setOnClickListener { etSearch.setText("") }

    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            query = s.toString()
            refreshList()
        }

        override fun afterTextChanged(s: Editable?) {}
    })

    dialog.show()
    // 切分类时标题跟着变（dialog 建立后才能改）
    tabs.forEach { (cat, tv) ->
        tv.setOnClickListener {
            if (current == cat) return@setOnClickListener
            current = cat
            tabs.forEach { (k, v) -> styleTab(v, k == cat) }
            etSearch.hint = getString(R.string.search_type_hint, getString(categoryLabelRes(cat)))
            dialog.setTitle(
                getString(R.string.title_choose_type, getString(categoryLabelRes(cat)))
            )
            rebuild()
        }
    }
    rebuild()
}

/**
 * 匹配打分：越小越靠前；返回 null 表示不匹配。
 * 顺序：ID 完全相等 → 标识符前缀 → 名称完全相等 → 名称前缀 → 包含。
 */
private fun rank(e: AutocompleteEntry, q: String): Int? {
    val ns = e.namespace.lowercase(Locale.getDefault())
    val raw = ns.substringAfter(':')
    val nm = e.name.lowercase(Locale.getDefault())
    val id = e.id ?: ""
    return when {
        ns == q || raw == q || (id.isNotEmpty() && id == q) -> 0
        raw.startsWith(q) || ns.startsWith(q) -> 1
        nm == q -> 2
        nm.startsWith(q) -> 3
        ns.contains(q) -> 4
        nm.contains(q) -> 5
        id.isNotEmpty() && id.startsWith(q) -> 6
        else -> null
    }
}

/** 分类名对应的字符串资源（原来写死英文 "All"，才出现「选择All」） */
fun categoryLabelRes(type: String): Int = when (type) {
    "item" -> R.string.title_item
    "block" -> R.string.title_block
    "effect" -> R.string.title_effect
    "enchant" -> R.string.title_enchant
    "all" -> R.string.title_all
    else -> R.string.title_data
}

fun detectAutocompleteType(parent: String?, grandParent: String?): String {
    if (parent == "ActiveEffects" || grandParent == "ActiveEffects"
        || parent == "MobEffects" || parent == "Effects"
    ) return "effect"
    if (parent == "ench" || parent == "Enchantments" || parent == "StoredEnchantments"
        || grandParent == "ench" || grandParent == "Enchantments"
    ) return "enchant"
    return "all"
}
