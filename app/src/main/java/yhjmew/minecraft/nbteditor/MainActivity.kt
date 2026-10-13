package yhjmew.minecraft.nbteditor

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.google.gson.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.*
import java.util.*
import yhjmew.minecraft.nbteditor.editor.dialogs.*
import yhjmew.minecraft.nbteditor.editor.EditorViewModel
import yhjmew.minecraft.nbteditor.world.WorldViewModel
import yhjmew.minecraft.nbteditor.world.WorldViewModel.BackupItem
import yhjmew.minecraft.nbteditor.world.WorldViewModel.CacheItem
import yhjmew.minecraft.nbteditor.world.WorldViewModel.WorldItem
import yhjmew.minecraft.nbteditor.mapart.MapArtViewModel
import yhjmew.minecraft.nbteditor.mapart.MapArtViewModel.InventorySlotInfo
import yhjmew.minecraft.nbteditor.nbt.BedrockParser
import yhjmew.minecraft.nbteditor.nbt.NbtTranslator
import yhjmew.minecraft.nbteditor.nbt.adapter.NbtAdapter
import yhjmew.minecraft.nbteditor.nbt.adapter.NbtTreeAdapter
import yhjmew.minecraft.nbteditor.util.CrashHandler
import yhjmew.minecraft.nbteditor.util.DraftAutoSaver
import yhjmew.minecraft.nbteditor.util.DraftStore
import yhjmew.minecraft.nbteditor.util.NbtClipboard
import yhjmew.minecraft.nbteditor.util.NbtExporter

class MainActivity : ComponentActivity() {

    // ============================================
    // ViewModels
    // ============================================
    internal val editorVM: EditorViewModel by viewModels()
    internal val worldVM: WorldViewModel by viewModels()
    internal val mapVM: MapArtViewModel by viewModels()

    // ============================================
    // UI 控件
    // ============================================
    internal var etWorldName: EditText? = null
    internal var nbtListView: ListView? = null
    internal var tvCurrentPath: TextView? = null
    internal var tvFullscreenPath: TextView? = null
    internal var layoutNormalUi: View? = null
    internal var layoutEditorHeader: View? = null
    internal var layoutFullscreenBar: View? = null
    internal var viewMainContent: View? = null
    internal var viewSidebar: View? = null

    // ============================================
    // 适配器
    // ============================================
    internal var nbtAdapter: NbtAdapter? = null
    internal var nbtTreeAdapter: NbtTreeAdapter? = null

    // ============================================
    // 缓存
    // ============================================
    internal var cacheMapList: MutableList<String>? = null
    internal var cacheVillageList: MutableList<String>? = null
    internal var cachePlayerList: MutableList<String>? = null

    // ============================================
    // UI 状态
    // ============================================
    internal var isNightMode = false
    internal var isFullScreen = false
    internal var currentLang: String? = null
    internal var prefs: SharedPreferences? = null

    // ============================================
    // 拼图状态
    // ============================================
    var puzzleRows = 1
    var puzzleCols = 1
    var currentTargetMapArray: JsonArray? = null
    private var puzzleImageUri: Uri? = null




    private val safPathLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            worldVM.setSafPath(uri)
            prefs?.edit()?.putString("saf_tree_uri", uri.toString())?.apply()
            prefs?.edit()?.putString("last_path", "$uri/")?.apply()
            toast(getString(R.string.msg_saf_selected))
            showWorldSelector()
        }
    }

    /**
     * issue #6：统一切换路径 + 持久化，避免重启后回退到 Android/data（需 Shizuku）
     */
    private fun applyPath(newPath: String) {
        worldVM.switchPath(newPath)
        prefs?.edit()?.putString("last_path", newPath)?.apply()
    }

    // ============================================
    // 导出到用户自选目录（SAF，无需存储权限）
    // ============================================
    /** 待导出的内容（用户选目录期间暂存） */
    internal var pendingExportContent: String? = null
    internal var pendingExportName: String? = null

    private val exportDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val content = pendingExportContent
        val name = pendingExportName
        pendingExportContent = null
        pendingExportName = null
        if (uri == null || content == null || name == null) return@registerForActivityResult
        if (NbtExporter.saveViaSaf(this, uri, name, content)) {
            toast(getString(R.string.toast_exported_ok))
        } else {
            toast(getString(R.string.toast_export_failed))
        }
    }

    /** 让用户选一个目录来保存导出文件 */
    internal fun startExportToChosenDir(element: JsonElement, fmt: Int, nameHint: String?) {
        pendingExportContent = NbtClipboard.formatAs(element, fmt)
        pendingExportName = NbtExporter.buildFileName(nameHint, fmt)
        exportDirLauncher.launch(null)
    }

    internal val puzzleImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) {uri ->
        puzzleImageUri = uri  // 先暂存
        mapVM.scanInventory(this)  // 扫描背包→弹出格子选择
    }

    internal val mapImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { mapVM.generateMapFromImage(this, it, currentTargetMapArray) }
    }


    // ============================================
    // 进度对话框
    // ============================================
    private var progressDialog: AlertDialog? = null
    private var tvProgressMessage: TextView? = null

    private var pendingWorldSelector = false
    private var pendingBackupListFolder: String? = null

    /** 当前备份列表所属的存档目录（重命名/删除后据此刷新列表） */
    private var backupListFolder: String? = null

    // ============================================
    // 语言
    // ============================================
    override fun attachBaseContext(newBase: Context) {
        val p = newBase.getSharedPreferences("nbt_config", MODE_PRIVATE)
        val lang = p.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")!!
        val config = Configuration()
        config.setLocale(if ("en" == lang) Locale.ENGLISH else Locale.CHINESE)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ============================================
    // onCreate
    // ============================================
    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashHandler.instance!!.init(this)
        super.onCreate(savedInstanceState)

        NbtTranslator.init(this)
        createNoMedia()
        val pr = File("/storage/emulated/0/Download/NbtEditor_Data/")
        ensureNoMedia(worksDir); ensureNoMedia(backupsDir)
        ensureNoMedia(File(pr, "Works")); ensureNoMedia(File(pr, "Backups"))
        ensureNoMedia(File(pr, "Bridge"))

        prefs = getSharedPreferences("nbt_config", MODE_PRIVATE)
        isNightMode = prefs!!.getBoolean("night_mode", false)
        currentLang = prefs!!.getString("app_language",
            if (Locale.getDefault().language.contains("zh")) "zh" else "en")

        val isChinese = Locale.getDefault().language.contains("zh")
        editorVM.setViewMode(prefs!!.getInt("view_mode", if (isChinese) 2 else 0))
        val skipShizuku = if (prefs!!.contains("skip_shizuku")) {
            prefs!!.getBoolean("skip_shizuku", false)
        } else {
            // 首次安装，根据系统版本决定默认值
            val defaultSkip = Build.VERSION.SDK_INT < 30
            prefs!!.edit()?.putBoolean("skip_shizuku", defaultSkip)?.apply()
            defaultSkip
        }
        worldVM.setUseShizuku(!skipShizuku)

        // 多线程设置：启动时从 prefs 恢复（修复「设置不生效、重启失效」）
        worldVM.setUseMultiThread(prefs!!.getBoolean("use_multithread", true))
        // 恢复上次的 NBT 剪贴板内容（进程被杀/重启后仍可用）
        NbtClipboard.init(this)

        // 草稿箱自动保存：恢复设置并初始化
        DraftAutoSaver.init(this)
        DraftAutoSaver.keepOriginal = prefs!!.getBoolean("draft_keep_original", false)
        DraftAutoSaver.maxDrafts = prefs!!.getInt("draft_max", DraftStore.DEFAULT_MAX_DRAFTS)
        run {
            val iv = prefs!!.getInt("draft_interval", 30)
            val unit = prefs!!.getInt("draft_interval_unit", 0)
            val factor = when (unit) { 1 -> 60_000L; 2 -> 3_600_000L; else -> 1_000L }
            DraftAutoSaver.setInterval(if (iv <= 0) 0L else iv.toLong() * factor)
        }
        worldVM.setIoThreadCount(prefs!!.getInt("io_thread_count", 0))
        mapVM.setPuzzleMultiThread(prefs!!.getBoolean("puzzle_multithread", true))
        mapVM.setPuzzleThreadCount(prefs!!.getInt("puzzle_thread_count", 0))

        // issue #6：恢复上次使用的路径（避免重启后回退到需 Shizuku 的 Android/data）
        prefs!!.getString("last_path", null)?.let { saved ->
            worldVM.restorePath(saved)
            // 若是 SAF 路径，同步恢复 tree uri 状态
            if (saved.startsWith("content://")) {
                try {
                    worldVM.safTreeUri = saved.trimEnd('/').toUri()
                } catch (_: Exception) {}
            }
        }

//        prefs!!.getString("saf_tree_uri", null)?.let { uriStr ->
//            try {
//                val uri = uriStr.toUri()
//                contentResolver.takePersistableUriPermission(uri,
//                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
//                worldVM.safTreeUri = uri; worldVM.switchPath("$uri/")
//            } catch (_: Exception) { prefs!!.edit { remove("saf_tree_uri") } }
//        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.main)
        initViews()
        applyTheme()

        worldVM.editorVM = editorVM
        mapVM.worldVM = worldVM
        setupObservers(); setupButtons()

        try { Class.forName("rikka.shizuku.Shizuku") }
        catch (e: Exception) { toast(getString(R.string.toast_shizuku_init, e.message)) }
        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }

        when {
            Build.VERSION.SDK_INT >= 30 -> {
                // Android 11+ 需要所有文件访问权限
                if (!Environment.isExternalStorageManager()) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_need_all_files_permission))
                        .setMessage(getString(R.string.msg_all_files_permission_required))
                        .setPositiveButton(getString(R.string.btn_go_grant)) { _, _ ->
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = "package:$packageName".toUri()
                                })
                            } catch (_: Exception) {
                                toast(getString(R.string.toast_open_settings_failed))
                            }
                        }
                        // issue #6：不再阻断，用户可稍后处理（自定义路径可能根本不需要此权限）
                        .setNegativeButton(getString(R.string.btn_later), null)
                        .setCancelable(true)
                        .show()
                }
            }
            Build.VERSION.SDK_INT >= 23 -> {
                // Android 6-10 请求存储权限
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        REQUEST_STORAGE_PERMISSION
                    )
                }
            }
        }

        worldVM.checkStoragePermission(this)

        Shizuku.addRequestPermissionResultListener { _, grant ->
            toast(if (grant == PackageManager.PERMISSION_GRANTED)
                getString(R.string.toast_shizuku_granted)
            else getString(R.string.toast_shizuku_denied))
        }

        if (savedInstanceState == null) worldVM.cleanUpOldSessions(this)
        restoreSavedState(savedInstanceState)
    }

    override fun onStop() {
        super.onStop()
        // 进程随时可能被系统回收，离开前台前把未保存的编辑落盘到「草稿箱」
        // （崩溃场景由 CrashHandler 负责，它会在进程终止前再抢救一次）
        worldVM.persistSessionNow(this)
    }

    private fun initViews() {
        viewMainContent = findViewById(R.id.main_content)
        viewSidebar = findViewById(R.id.sidebar_content)
        etWorldName = findViewById(R.id.et_world_name)
        nbtListView = findViewById(R.id.lv_nbt_list)
        tvCurrentPath = findViewById(R.id.tv_current_path)
        layoutNormalUi = findViewById(R.id.layout_normal_ui)
        layoutEditorHeader = findViewById(R.id.layout_editor_header)
        layoutFullscreenBar = findViewById(R.id.layout_fullscreen_bar)
        tvFullscreenPath = findViewById(R.id.tv_fullscreen_path)
    }

    // ============================================
    // 观察者
    // ============================================
    private fun setupObservers() {
        lifecycleScope.launch {
            editorVM.nbtData.collect { if (it != null) refreshAdapter(it) }
        }
        lifecycleScope.launch {
            editorVM.pathTitle.collect { tvCurrentPath?.text = it; tvFullscreenPath?.text = it }
        }
        lifecycleScope.launch {
            combine(worldVM.isLoading, mapVM.isGenerating) { w, m -> w || m }
                .collect { if (it) showProgressDialog(getString(R.string.log_please_wait),
                    worldVM.loadingMessage.value.ifEmpty { mapVM.progressMessage.value }.ifEmpty { "..." })
                else dismissProgressDialog() }
        }
        lifecycleScope.launch {
            worldVM.progressMessage.collect { if (it.isNotEmpty()) updateProgress(it) }
        }
        lifecycleScope.launch {
            mapVM.progressMessage.collect { if (it.isNotEmpty()) updateProgress(it) }
        }
        lifecycleScope.launch {
            worldVM.toastMessage.collect { it?.let { toast(it); worldVM.clearToast() } }
        }
        lifecycleScope.launch {
            mapVM.resultMessage.collect { msg -> msg?.let { handleResult(it); mapVM.clearResult() } }
        }
        lifecycleScope.launch {
            worldVM.worldNameForTitle.collect { name ->
                findViewById<TextView?>(R.id.tv_app_title)?.text = name ?: getString(R.string.app_name)
            }
        }
        lifecycleScope.launch {
            worldVM.worldSeedForDisplay.collect { seed ->
                findViewById<TextView?>(R.id.tv_info_seed)?.let {
                    if (seed != null) { it.text = getString(R.string.key_seed_display, seed); it.isVisible = true }
                    else it.isVisible = false
                }
            }
        }
        lifecycleScope.launch {
            worldVM.scanResultChannel.receiveAsFlow().collect { result ->
                if (pendingWorldSelector) {
                    pendingWorldSelector = false
                    if (result.error != null) {
                        toast(getString(R.string.toast_scanning_failed, result.error))
                    } else {
                        showWorldSelectorDialog(result.worlds)
                    }
                }
            }
        }

        lifecycleScope.launch {
            worldVM.cacheList.drop(1).collect { showCacheDialog(it) }
        }
        lifecycleScope.launch {
            worldVM.backupList.drop(1).collect { list ->
                pendingBackupListFolder?.let { showBackupDialog(list); pendingBackupListFolder = null }
            }
        }
        lifecycleScope.launch {
            worldVM.errorMessage.collect { err -> err?.let { showErrorDialog(it); worldVM.clearError() } }
        }
        lifecycleScope.launch {
            worldVM.isLoading.drop(1).collect { loading ->
                if (!loading) {
                    worldVM.pendingSidebarTask?.let { task ->
                        worldVM.pendingSidebarTask = null
                        task.run()
                    }
                }
            }
        }

        lifecycleScope.launch {
            worldVM.offerCreateKey.collect { keyName ->
                keyName?.let {
                    worldVM.clearOfferCreateKey()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_data_not_exist))
                        .setMessage(getString(R.string.msg_this_archive_has_not_generated_data_yet, it))
                        .setPositiveButton(getString(R.string.btn_create_and_open)) { _, _ ->
                            worldVM.createNewGlobalData(it)
                        }
                        .setNegativeButton(getString(R.string.btn_cancel), null)
                        .show()
                }
            }
        }

        lifecycleScope.launch {
            mapVM.inventorySlotInfo.collect { info ->
                info?.let { showSlotPickerDialog(it) }
            }
        }
    }

    // ============================================
    // 按钮
    // ============================================
    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)

        tvCurrentPath?.setOnClickListener { toast(getString(R.string.long_press_here_to_open_the_root_menu)) }
        tvCurrentPath?.setOnLongClickListener { showRootMenu(); true }

        findViewById<View?>(R.id.btn_go_fullscreen)?.setOnClickListener { toggleFullScreen(true) }
        findViewById<View?>(R.id.btn_exit_fullscreen)?.setOnClickListener {
            if (editorVM.navigationStack.isNotEmpty()) onBackPressed() else toggleFullScreen(false)
        }
        findViewById<View?>(R.id.btn_fullscreen_menu)?.setOnClickListener { showRootMenu() }

        findViewById<Button?>(R.id.btn_copy)?.setOnClickListener {
            val f = etWorldName!!.text.toString().trim()
            if (f.isEmpty() || f == getString(R.string.hint_select_world)) showWorldSelector()
            else { toast(getString(R.string.toast_loading, f)); worldVM.loadLevelDat(this, f) }
        }
        etWorldName?.setOnClickListener { showWorldSelector() }

        findViewById<Button?>(R.id.btn_edit_player)?.setOnClickListener {
            val f = etWorldName!!.text.toString()
            if (checkFolder(f)) worldVM.loadPlayerData(this, f)
        }
        findViewById<Button?>(R.id.btn_parse)?.setOnClickListener { reparseLevelDat() }
        findViewById<Button?>(R.id.btn_check_permission)?.setOnClickListener {
            if (worldVM.checkShizukuReady()) toast(getString(R.string.toast_shizuku_normal))
            worldVM.checkStoragePermission(this)
        }
        findViewById<Button?>(R.id.btn_switch_path)?.setOnClickListener { showPathSelector() }
        findViewById<Button?>(R.id.btn_clear_cache)?.setOnClickListener { worldVM.scanCache(this) }

        findViewById<Button?>(R.id.btn_backup_history)?.setOnClickListener {
            // 备份与草稿箱拼一个入口，各自独立菜单
            val opts = arrayOf(
                getString(R.string.btn_restore),
                getString(R.string.btn_draft_box)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_backup_or_draft))
                .setItems(opts) { _, w ->
                    if (w == 0) {
                        // 备份：按存档划分，需要先确定是哪个存档
                        val f = etWorldName!!.text.toString()
                        if (checkFolder(f)) {
                            backupListFolder = f
                            pendingBackupListFolder = f
                            worldVM.scanBackups(this, f)
                        }
                    } else {
                        showDraftBoxDialog()
                    }
                }.show()
        }
        findViewById<Button?>(R.id.btn_save)?.setOnClickListener {
            // 树形视图同样支持保存：树上的编辑直接作用于根数据对象图，
            // getRootData() 返回真正的根，所见即所存。
            val d = editorVM.getRootData() ?: run { toast(getString(R.string.toast_no_data)); return@setOnClickListener }
            val f = etWorldName!!.text.toString().trim()
            if (f.isEmpty() || f == getString(R.string.hint_select_world))
            { toast(getString(R.string.toast_select_world)); return@setOnClickListener }
            worldVM.saveAndPushBack(this, d, f)
        }

        val btnTree = findViewById<View?>(R.id.btn_tree_mode)
        btnTree?.setOnClickListener { toggleTreeMode() }
        findViewById<View?>(R.id.btn_fullscreen_tree)?.setOnClickListener { btnTree?.performClick() }

        findViewById<View?>(R.id.btn_view_options)?.setOnClickListener { showViewOptionsDialog() }
        findViewById<View?>(R.id.btn_fullscreen_view)?.setOnClickListener { showViewOptionsDialog() }

        findViewById<View?>(R.id.btn_open_drawer)?.setOnClickListener {
            sc?.let { it.isVisible = true; it.bringToFront() }
        }
        findViewById<View?>(R.id.view_mask)?.setOnClickListener { sc?.isVisible = false }

        // 侧边栏按钮
        findViewById<View?>(R.id.btn_online_players)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showMultiPlayerDialog() }
        }
        findViewById<View?>(R.id.btn_map_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showMapListDialog() }
        }
        findViewById<View?>(R.id.btn_village_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showVillageListDialog() }
        }
        findViewById<View?>(R.id.btn_global_nbt)?.setOnClickListener {
            sc?.isVisible = false; ensureDbLoaded { showGlobalDataDialog() }
        }
        findViewById<View?>(R.id.btn_search_nbt)?.setOnClickListener {
            // 树形与列表模式都支持搜索
            showEditorSearchDialog()
        }
        findViewById<View?>(R.id.btn_fullscreen_search)?.setOnClickListener {
            findViewById<View?>(R.id.btn_search_nbt)?.performClick()
        }
        findViewById<View?>(R.id.btn_open_any_key)?.setOnClickListener {
            if (worldVM.currentWorkingDbPath == null) toast(getString(R.string.toast_please_initialize_the_database_first))
            else { sc?.isVisible = false; showOpenByKeyDialog() }
        }

        nbtListView?.onItemClickListener = AdapterView.OnItemClickListener { _, _, p, _ -> onNbtClick(p) }
        nbtListView?.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, p, _ ->
            onNbtLongClick(p); true
        }

        findViewById<View?>(R.id.sidebar_content)?.let {
            it.layoutParams.width = resources.displayMetrics.widthPixels * 2 / 3
            it.layoutParams = it.layoutParams
        }

        findViewById<View?>(R.id.sidebar_header)?.setOnClickListener { showAppInfoDialog() }
        findViewById<View?>(R.id.iv_app_icon)?.setOnClickListener { showAppInfoDialog() }
    }

    // ============================================
    // NBT 列表交互
    // ============================================
    private fun onNbtClick(pos: Int) {
        if (editorVM.isTreeMode.value) {
            editorVM.lastTreeClickPosition = pos
            if (nbtTreeAdapter!!.isContainer(pos)) nbtTreeAdapter!!.toggleExpand(pos)
            else nbtTreeItemClick(pos)
        } else listItemClick(pos)
    }

    private fun nbtTreeItemClick(pos: Int) {
        val node = nbtTreeAdapter!!.getNode(pos) ?: return
        val valueObj = node.value as? JsonObject ?: return
        val v = valueObj.get("v") ?: return
        val type = node.type
        if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500)
            showArrayPaginationDialog(node.key, v.asJsonArray)
        else showEditValueDialog(node.key, valueObj, nbtTreeAdapter!!.getListElementInfo(pos))
    }

    private fun listItemClick(pos: Int) {
        val key = nbtAdapter!!.getKey(pos)
        val raw = nbtAdapter!!.data!!.get(key)
        val item = editorVM.wrapRawItem(raw)
        val type = item.get("t").asInt
        if (type == 10) {
            editorVM.enterFolder(key, item.getAsJsonObject("v"), false, nbtListView!!.firstVisiblePosition)
        } else if (type == 9) {
            editorVM.enterFolder(key, editorVM.convertListToMap(item), true, nbtListView!!.firstVisiblePosition)
        } else {
            val v = item.get("v")
            if ((type == 7 || type == 11 || type == 12) && v.isJsonArray && v.asJsonArray.size() > 500)
                showArrayPaginationDialog(key, v.asJsonArray)
            else showEditValueDialog(key, item)
        }
    }

    private fun onNbtLongClick(pos: Int) {
        editorVM.lastTreeClickPosition = pos
        if (editorVM.isTreeMode.value) {
            val node = nbtTreeAdapter!!.getNode(pos) ?: return
            val value = node.value as? JsonObject ?: return
            showLongPressMenu(node.key, value)
        } else {
            val key = nbtAdapter!!.getKey(pos)
            val raw = nbtAdapter!!.data!!.get(key)
            showLongPressMenu(key, editorVM.wrapRawItem(raw))
        }
    }

    // ============================================
    // 适配器
    // ============================================
    internal fun refreshAdapter(data: JsonObject?) {
        if (editorVM.isTreeMode.value) {
            nbtTreeAdapter = NbtTreeAdapter(this, data).also { it.setViewMode(editorVM.viewMode.value) }
            nbtListView?.adapter = nbtTreeAdapter
        } else {
            editorVM.currentListData = data
            nbtAdapter = NbtAdapter(this, data).also { it.setViewMode(editorVM.viewMode.value) }
            nbtAdapter!!.setPathContext(
                if (editorVM.pathStack.size >= 2) editorVM.pathStack[editorVM.pathStack.size - 2] else ""
            )
            nbtListView?.adapter = nbtAdapter
        }
    }

    private fun toggleTreeMode() {
        editorVM.toggleTreeMode()
        toast(if (editorVM.isTreeMode.value) getString(R.string.toast_tree_switch_mode_blocktopograph)
        else getString(R.string.toast_tree_switch_mode_default))

        if (editorVM.isTreeMode.value) {
            // 进入树形：始终以「真正的根数据」为根。
            // 否则从列表模式深层切过来时，树只显示子树，
            // 而保存写的却是整份数据，容易让用户困惑。
            val root = editorVM.getRootData() ?: return
            editorVM.setRawNbtData(root)
            editorVM.navigationStack.clear()
            editorVM.pathStack.clear()
            editorVM.scrollPositionStack.clear()
            editorVM.lastTreeClickPosition = -1
            editorVM.currentListData = root
            nbtTreeAdapter = NbtTreeAdapter(this, root).also { it.setViewMode(editorVM.viewMode.value) }
            nbtListView?.adapter = nbtTreeAdapter
        } else {
            val data = editorVM.nbtData.value ?: return
            if (nbtTreeAdapter != null && editorVM.lastTreeClickPosition >= 0)
                editorVM.syncListModeFromPath(nbtTreeAdapter!!.getNodePath(editorVM.lastTreeClickPosition))
            refreshAdapter(editorVM.currentListData ?: data)
        }
        editorVM.updatePathTitle()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val sc = findViewById<View?>(R.id.custom_sidebar_container)
        if (sc != null && sc.isVisible) { sc.isVisible = false; return }
        if (editorVM.goBack()) {
            refreshAdapter(editorVM.nbtData.value)
            editorVM.goBackScrollPosition()?.let { nbtListView?.post { nbtListView?.setSelection(it) } }
            return
        }
        if (isFullScreen) { toggleFullScreen(false); return }
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isEditingPlayer", editorVM.isEditingPlayer.value)
        outState.putString("currentTargetKey", editorVM.currentTargetKey.value)
        outState.putString("currentWorkingDbPath", worldVM.currentWorkingDbPath)
        outState.putString("currentWorkingFileOrDir", worldVM.currentWorkingFileOrDir)
        outState.putBoolean("isTreeMode", editorVM.isTreeMode.value)
        etWorldName?.text?.toString()?.let { outState.putString("worldName", it) }
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        editorVM.setEditingPlayer(savedInstanceState.getBoolean("isEditingPlayer"))
        editorVM.setTargetKey(savedInstanceState.getString("currentTargetKey"))
        worldVM.currentWorkingDbPath = savedInstanceState.getString("currentWorkingDbPath")
        worldVM.currentWorkingFileOrDir = savedInstanceState.getString("currentWorkingFileOrDir")
        val tm = savedInstanceState.getBoolean("isTreeMode")
        if (tm != editorVM.isTreeMode.value) toggleTreeMode()
        savedInstanceState.getString("worldName")?.let {
            etWorldName?.setText(it); worldVM.lastLoadedWorldFolder = it
        }
    }

    // ============================================
    // 世界选择 / 路径（轻量包装）
    // ============================================
    private fun showWorldSelector() {
        // issue #6：只有受限路径（Android/data 等）才需要 Shizuku；
        // 公共目录（Download、games/...）用 File API 即可，不打扰用户。
        val path = worldVM.currentPath.value
        val needsShizuku = worldVM.isRestrictedPath(path)
        if (!prefs!!.getBoolean("skip_shizuku", false) && needsShizuku) {
            when (val status = worldVM.checkShizukuStatus(applicationContext)) {
                is WorldViewModel.ShizukuStatus.NotInstalled -> {
                    showShizukuNotInstalledDialog()
                    return
                }
                is WorldViewModel.ShizukuStatus.NotRunning -> {
                    // 非阻断：提示后可继续（可能靠 MANAGE_EXTERNAL_STORAGE 直接访问）
                    toast(getString(R.string.toast_shizuku_not_running))
                }
                is WorldViewModel.ShizukuStatus.PermissionDenied -> {
                    worldVM.requestShizukuPermission()
                    toast(getString(R.string.toast_shizuku_permission_failed))
                }
                is WorldViewModel.ShizukuStatus.Available -> {
                    // 继续
                }
            }
        }

        pendingWorldSelector = true
        if (worldVM.safTreeUri != null) {
            worldVM.scanWorldsViaSaf(this, worldVM.safTreeUri!!)
        } else {
            worldVM.scanWorlds(this)
        }
    }

    private fun showShizukuNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_shizuku_not_found))
            .setMessage(getString(R.string.msg_shizuku_recommend_install))
            .setPositiveButton(getString(R.string.btn_download)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                    })
                } catch (_: Exception) {
                    toast(getString(R.string.toast_open_browser_failed))
                }
            }
            .setNegativeButton(getString(R.string.btn_continue_without)) { _, _ ->
                worldVM.setUseShizuku(false)
                prefs?.edit()?.putBoolean("use_shizuku", false)?.apply()
                // 允许继续扫描（走 File API）
                pendingWorldSelector = true
                worldVM.scanWorlds(this)
            }
            // issue #6：非阻断，用户可稍后再处理
            .setCancelable(true)
            .show()
    }

    private fun showPathSelector() {
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_path_title))
            .setItems(arrayOf(getString(R.string.path_standard), getString(R.string.path_legacy),
                getString(R.string.path_custom), getString(R.string.path_saf))) { _, w ->
                when (w) {
                    0 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(PATH_STANDARD)
                    showWorldSelector()
                }
                    1 -> {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(PATH_LEGACY)
                    showWorldSelector()
                }
                    2 -> showCustomPathDialog()
                    3 -> safPathLauncher.launch(null)
                }
            }.show()
    }

    private fun showCustomPathDialog() {
        val input = EditText(this).apply { setText(worldVM.currentPath.value) }
        AlertDialog.Builder(this).setTitle(getString(R.string.path_custom)).setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                var p = input.text.toString().trim()
                if (p.isNotEmpty()) {
                    worldVM.safTreeUri = null
                    prefs?.edit()?.remove("saf_tree_uri")?.apply()
                    applyPath(if (p.endsWith("/")) p else "$p/")
                    showWorldSelector()
                }
            }.show()
    }

    private fun showWorldSelectorDialog(list: List<WorldItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_no_saves_found)); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.select_world_count, list.size))
            .setItems(list.map { w ->
                val line1 = listTitleLine(w.displayName, w.folderName)
                val line2 = if (w.lastModified > 0)
                    fullTimeOf(w.lastModified) else getString(R.string.time_unknown)
                "$line1\n$line2"
            }.toTypedArray()) { _, i ->
                val f = list[i].folderName; etWorldName?.setText(f); worldVM.loadLevelDat(this, f)
            }.show()
    }

    private fun reparseLevelDat() {
        worldVM.currentWorkingFileOrDir?.let { path ->
            val json = BedrockParser.parse(path)
            editorVM.setRawNbtData(json); editorVM.nbtDataCache["level.dat"] = json
            editorVM.navigationStack.clear(); editorVM.pathStack.clear(); editorVM.scrollPositionStack.clear()
            editorVM.currentListData = json; editorVM.setEditingPlayer(false)
            editorVM.updatePathTitle(); worldVM.updateWorldInfoFromNbt(json)
        } ?: toast(getString(R.string.toast_no_data_to_parse))
    }

    // ============================================
    // 缓存 & 备份 & 错误（轻量包装）
    // ============================================
    private fun showCacheDialog(list: List<CacheItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_cache_empty)); return }
        val lv = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                list.map { "${if (it.isDirectory) "📁" else "📄"} ${it.name}" })
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.title_manage_cache)).setView(lv)
            .setNeutralButton(getString(R.string.btn_close), null)
            .setPositiveButton(getString(R.string.btn_clear_all)) { _, _ -> worldVM.clearAllCache(this); toast(getString(R.string.toast_cleared)) }.show()
        lv.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, p, _ ->
            AlertDialog.Builder(this).setTitle(getString(R.string.btn_delete)).setMessage(getString(R.string.msg_delete_confirm, list[p].name))
                .setPositiveButton(getString(R.string.btn_delete)) { _, _ -> worldVM.deleteCacheItem(list[p]); worldVM.scanCache(this) }.show()
            true
        }
    }

    private fun showBackupDialog(list: List<BackupItem>) {
        if (list.isEmpty()) { toast(getString(R.string.toast_no_backups)); return }
        val worldName = worldVM.backupWorldName.value
        val folder = backupListFolder
        val folderId = folder ?: ""

        // ⋮ 菜单：清空本存档的备份（备份只有「按存档」这一个维度，没有全部）
        val clearHere: (() -> Unit)? =
            if (folder.isNullOrBlank()) null else { { confirmClearBackupsHere(folder, list.size) } }

        showTabbedListDialog(
            title = getString(R.string.dialog_backup_title, list.size),
            items = list,
            tabOf = { backupTabOf(it) },
            linesOf = { b ->
                val line1 = listTitleLine(worldName, folderId)
                val type = if (b.isPlayerData) getString(R.string.msg_local_player)
                           else getString(R.string.msg_world_data)
                "$line1\n" + listSubLine(type, b.time, agoTextOfMillis(b.timestamp))
            },
            onShortClick = { worldVM.restoreBackup(this, it) },
            onLongClick = { showBackupManageMenu(it) },
            menuItems = listOf(
                getString(R.string.btn_clear_backups_here, list.size) to clearHere
            )
        )
    }

    /** 清空本存档的全部备份（二次确认） */
    private fun confirmClearBackupsHere(folder: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_backups_here_confirm, count))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                val dir = File(backupsDir, folder)
                dir.listFiles()?.forEach { worldVM.deleteBackup(BackupItem(it, it.name, "", it.isDirectory)) }
                toast(getString(R.string.toast_backups_cleared))
                currentDialog?.dismiss()
                refreshBackupList()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ============================================
    // 备份管理（长按）
    // ============================================

    private fun showBackupManageMenu(item: BackupItem) {
        val opts = arrayOf(
            getString(R.string.btn_restore_action),
            getString(R.string.btn_detail),
            getString(R.string.btn_rename),
            getString(R.string.btn_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, item.name))
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> worldVM.restoreBackup(this, item)
                    1 -> showBackupDetailDialog(item)
                    2 -> showBackupRenameDialog(item)
                    3 -> confirmDeleteBackup(item)
                }
            }
            .show()
    }

    /** 重命名备份（复用 WorldViewModel.renameBackup） */
    private fun showBackupRenameDialog(item: BackupItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_rename))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName.isEmpty() ->
                        toast(getString(R.string.msg_name_cannot_be_empty))
                    newName.contains('/') || newName.contains('\\') ->
                        toast(getString(R.string.toast_Invalid_name))
                    newName == item.name -> { /* 没改动，忽略 */ }
                    File(item.file.parentFile, newName).exists() ->
                        toast(getString(R.string.msg_backup_name_exists))
                    worldVM.renameBackup(item, newName) -> {
                        toast(getString(R.string.toast_backup_renamed))
                        currentDialog?.dismiss(); refreshBackupList()
                    }
                    else ->
                        toast(getString(R.string.toast_rename_failed, item.name))
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 删除备份（复用 WorldViewModel.deleteBackup） */
    private fun confirmDeleteBackup(item: BackupItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_delete_backup_confirm, item.name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                worldVM.deleteBackup(item)
                toast(getString(R.string.toast_backup_deleted))
                currentDialog?.dismiss(); refreshBackupList()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 备份详情：类型 / 时间 / 大小 / 路径（目录大小在后台计算） */
    private fun showBackupDetailDialog(item: BackupItem) {
        val f = item.file
        val sb = StringBuilder()
        sb.append(getString(R.string.label_type))
            .append(if (item.isDirectory) getString(R.string.type_folder) else getString(R.string.type_file))
            .append('\n')
        sb.append(getString(R.string.label_time) + item.time).append('\n')
        sb.append(getString(R.string.label_size)).append(getString(R.string.label_calculating)).append('\n')
        sb.append(getString(R.string.label_path)).append('\n').append(f.absolutePath)

        val tv = TextView(this).apply {
            text = sb.toString()
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        val detailDlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_backup_detail))
            .setView(tv)
            .setPositiveButton(getString(R.string.btn_close), null)
            .create()
        detailDlg.show()

        // 大小可能很大，放后台算，算完再刷新这一行
        Thread {
            val bytes = sizeOf(f)
            runOnUiThread {
                tv.text = sb.toString().replace(
                    getString(R.string.label_size) + getString(R.string.label_calculating),
                    getString(R.string.label_size) + formatSize(bytes)
                )
            }
        }.start()
    }

    /** 递归求大小（文件直接取长度；目录累加） */
    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        var total = 0L
        try {
            f.listFiles()?.forEach { total += sizeOf(it) }
        } catch (_: Exception) {
        }
        return total
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 重命名/删除后刷新列表（关掉旧弹窗，重新扫描会再次弹出） */
    private fun refreshBackupList() {
        val f = backupListFolder ?: return
        pendingBackupListFolder = f
        worldVM.scanBackups(this, f)
    }

    // ============================================
    // 草稿箱
    // ============================================

    // ============================================
    // 带底部标签的列表对话框（草稿箱 / 备份共用）
    // ============================================

    /** 列表项的分类格 */
    private enum class ListTab(val labelRes: Int) {
        ALL(R.string.tab_all),
        WORLD(R.string.tab_world),
        PLAYER(R.string.tab_player),
        OTHER(R.string.tab_other)
    }

    /**
     * 通用「带底部标签栏的列表」对话框。
     *
     * 标签**动态生成**：只显示实际有内容的分类，最后一格固定是「关闭」。
     *   所有 / 世界 / 玩家 / 其他 / 关闭
     *
     * @param tabOf   分类函数；返回 null 表示「未知」，统一归入「其他」格
     * @param linesOf 每项显示的两行文本
     */
    private fun <T> showTabbedListDialog(
        title: String,
        items: List<T>,
        tabOf: (T) -> ListTab?,
        linesOf: (T) -> String,
        onShortClick: (T) -> Unit,
        onLongClick: (T) -> Unit,
        /** 右上角 ⋮ 菜单项（空 = 不显示 ⋮）；lambda 为 null 表示不可用 */
        menuItems: List<Pair<String, (() -> Unit)?>> = emptyList()
    ) {
        fun tabOfItem(item: T): ListTab = tabOf(item) ?: ListTab.OTHER

        val view = layoutInflater.inflate(R.layout.dialog_list_with_tabs, null, false)
        val lv = view.findViewById<ListView>(R.id.lv_tabbed_items)
        val tabBar = view.findViewById<LinearLayout>(R.id.layout_tab_bar)

        // 有内容的分类（顺序固定：所有 / 世界 / 玩家 / 其他）
        val present = items.map { tabOfItem(it) }.toSet()
        val tabs = listOf(ListTab.ALL, ListTab.WORLD, ListTab.PLAYER, ListTab.OTHER)
            .filter { it == ListTab.ALL || it in present }

        var shown: List<T> = items
        var selected: ListTab = ListTab.ALL

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .create()

        fun render(onRendered: (Int) -> Unit = {}) {
            shown = if (selected == ListTab.ALL) items
                    else items.filter { tabOfItem(it) == selected }
            lv.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, shown.map(linesOf)
            )
            onRendered(shown.size)
            buildTabBar(tabBar, tabs, selected, menuItems) { sel ->
                selected = sel
                render(onRendered)
            }
        }

        lv.setOnItemClickListener { _, _, p, _ ->
            shown.getOrNull(p)?.let(onShortClick)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            val it = shown.getOrNull(p)
            if (it != null) { onLongClick(it); true } else false
        }

        // 窗口尺寸：宽度固定 90%，高度按条数自适应（标签栏始终贴在底部，
        // 条数少时不至于留一大块空白；条数多时封顶，列表内部滚动）
        val density = resources.displayMetrics.density
        val winW = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.7).toInt()
        val itemH = (56 * density).toInt()      // 每项两行的大致高度
        val tabH = (46 * density).toInt()       // 底部标签栏
        val minH = (160 * density).toInt()

        fun applyHeight(count: Int) {
            val want = tabH + count * itemH
            val h = want.coerceIn(minH, maxH)
            dlg.window?.setLayout(winW, h)
        }

        dlg.setOnDismissListener { currentDialog = null }
        currentDialog = dlg
        dlg.show()
        applyHeight(items.size)

        render { applyHeight(it) }
    }

    /** 构建标签栏：若干分类格 + 末尾固定的「关闭」 */
    private fun buildTabBar(
        bar: LinearLayout,
        tabs: List<ListTab>,
        selected: ListTab,
        menuItems: List<Pair<String, (() -> Unit)?>>,
        onSelect: (ListTab) -> Unit
    ) {
        bar.removeAllViews()
        val density = resources.displayMetrics.density
        val vpad = (12 * density).toInt()
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        for (tab in tabs) {
            val isSel = tab == selected
            bar.addView(TextView(this).apply {
                text = getString(tab.labelRes)
                gravity = android.view.Gravity.CENTER
                setPadding(0, vpad, 0, vpad)
                textSize = 13f
                layoutParams = lp
                isClickable = true
                if (isSel) {
                    setTextColor("#009688".toColorInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setBackgroundColor("#E0F2F1".toColorInt())
                } else {
                    setTextColor("#666666".toColorInt())
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                setOnClickListener { onSelect(tab) }
            })
        }
        // 倒数第二格：⋮ 菜单（有菜单项时才显示）
        if (menuItems.isNotEmpty()) {
            bar.addView(TextView(this).apply {
                text = "\u22ee"
                gravity = android.view.Gravity.CENTER
                setPadding(0, vpad, 0, vpad)
                textSize = 16f
                setTextColor("#009688".toColorInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f
                )
                isClickable = true
                setOnClickListener {
                    val labels = menuItems.map { it.first }.toTypedArray()
                    val enabled = menuItems.map { it.second != null }.toBooleanArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.menu_more_actions_title)
                        .setItems(labels) { _, w ->
                            if (menuItems[w].second != null) menuItems[w].second!!.invoke()
                            else toast(getString(R.string.msg_action_unavailable))
                        }
                        .show()
                }
            })
        }

        // 末尾「关闭」
        bar.addView(TextView(this).apply {
            text = getString(R.string.btn_close)
            gravity = android.view.Gravity.CENTER
            setPadding(0, vpad, 0, vpad)
            textSize = 13f
            setTextColor("#009688".toColorInt())
            layoutParams = lp
            isClickable = true
            setOnClickListener { currentDialog?.dismiss() }
        })
    }

    /** 当前显示的带标签列表对话框（供标签栏的「关闭」使用） */
    private var currentDialog: AlertDialog? = null

    /** 草稿的分类格（null = 未知，归入「其他」） */
    private fun draftTabOf(d: DraftStore.Draft): ListTab? {
        // 存档信息缺失 -> 未知
        if (d.folder.isNullOrBlank()) return null
        val k = d.sessionKey
        return when {
            k == "level.dat" -> ListTab.WORLD
            k == "~local_player" || k.startsWith("player") -> ListTab.PLAYER
            k.startsWith("map_") || k.startsWith("VILLAGE_") -> ListTab.OTHER
            else -> null
        }
    }

    /** 备份的分类格（null = 未知，归入「其他」） */
    private fun backupTabOf(b: BackupItem): ListTab? {
        if (backupListFolder.isNullOrBlank()) return null
        return if (b.isPlayerData) ListTab.PLAYER else ListTab.WORLD
    }

    /** 完整时间：yyyy-MM-dd HH:mm:ss（年到秒）；0 显示「未知时间」 */
    private fun fullTimeOf(millis: Long): String =
        if (millis <= 0) getString(R.string.time_unknown)
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(millis))

    /** 列表第 1 行：世界名 + 空格 + 文件夹名(base64 ID) */
    private fun listTitleLine(worldName: String?, folderId: String): String {
        val name = worldName?.takeIf { it.isNotBlank() } ?: folderId
        return if (name == folderId) name else "$name  $folderId"
    }

    /** 列表第 2 行（草稿/备份）：[类型标签] 完整时间 相对时间 */
    private fun listSubLine(typeLabel: String, timeText: String, agoText: String): String =
        "[$typeLabel]  $timeText  $agoText"

    /** 从 timestamp 算出「多久之前」的文案 */
    private fun agoTextOfMillis(millis: Long): String {
        if (millis <= 0) return ""
        val minutes = (System.currentTimeMillis() - millis).coerceAtLeast(0) / 60000
        return agoTextOf(minutes)
    }

    /** 距今多久的本地化文案（避免在 util 层硬编码中文） */
    private fun agoTextOf(minutes: Long): String = when {
        minutes < 1 -> getString(R.string.draft_ago_just_now)
        minutes < 60 -> getString(R.string.draft_ago_minutes, minutes)
        minutes < 60 * 24 -> getString(R.string.draft_ago_hours, minutes / 60)
        else -> getString(R.string.draft_ago_days, minutes / (60 * 24))
    }

    /**
     * 草稿类型标签。
     * 注意 level.dat 是「世界数据」，不是「地图」—— msg_map 指的是 map_xxx 地图数据，
     * 之前这里一律用 msg_map，导致世界数据的草稿被标成 [地图]。
     */
    private fun draftTypeLabel(d: DraftStore.Draft): String {
        val key = d.sessionKey
        return when {
            key == "level.dat" -> getString(R.string.msg_world_data)
            key == "~local_player" -> getString(R.string.msg_local_player)
            key.startsWith("map_") -> getString(R.string.msg_map)
            key.startsWith("VILLAGE_") -> getString(R.string.msg_village)
            key.startsWith("player") -> getString(R.string.msg_player)
            d.isPlayerMode -> getString(R.string.msg_player)
            else -> getString(R.string.msg_world_data)
        }
    }

    /**
     * 草稿箱：列出未保存的编辑，由用户主动找回。
     * 与「备份」分离 —— 备份是已保存状态的快照，草稿是未保存的改动。
     */
    /**
     * 草稿箱。
     *
     * @param viewAll true = 忽略存档筛选，显示全部存档的草稿
     */
    private fun showDraftBoxDialog(viewAll: Boolean = false) {
        val folder = etWorldName?.text?.toString()?.trim().orEmpty()
        val hasFolder = folder.isNotEmpty() &&
            folder != getString(R.string.hint_select_world)

        // 未选存档：提示 + 提供「查看全部」兜底（草稿是救命用的，不能全堵死）
        if (!hasFolder && !viewAll) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_draft_box_no_folder))
                .setMessage(getString(R.string.msg_draft_select_world_first))
                .setPositiveButton(getString(R.string.btn_view_all_drafts)) { _, _ ->
                    showDraftBoxDialog(viewAll = true)
                }
                .setNegativeButton(getString(R.string.btn_close), null)
                .show()
            return
        }

        val all = DraftStore.list(this)
        // 选了存档 -> 只看该存档的草稿，但「未知存档」的草稿始终显示（避免找不回）
        val drafts = if (viewAll) all
                     else all.filter { it.folder.isNullOrBlank() || it.folder == folder }

        if (drafts.isEmpty()) { toast(getString(R.string.msg_no_drafts)); return }

        // 本存档的草稿数（用于「清空本存档」文案与是否可点）
        val hereCount = all.count { !it.folder.isNullOrBlank() && it.folder == folder }
        val clearHere: (() -> Unit)? =
            if (hereCount <= 0) null else { { confirmClearDraftsHere(folder, hereCount) } }

        showTabbedListDialog(
            title = getString(R.string.title_draft_box, drafts.size),
            items = drafts,
            tabOf = { draftTabOf(it) },
            linesOf = { d ->
                val line1 = listTitleLine(d.worldName, d.folderId())
                val type = draftTypeLabel(d)
                val orig = if (d.hasOriginal()) " · ${getString(R.string.draft_has_original)}" else ""
                val line2 = listSubLine(type, fullTimeOf(d.savedAt), agoTextOf(d.minutesAgo())) + orig
                "$line1\n$line2"
            },
            onShortClick = { worldVM.restoreDraft(this, it) },
            onLongClick = { showDraftManageMenu(it) },
            menuItems = listOf(
                getString(R.string.btn_clear_drafts_here, hereCount) to clearHere,
                getString(R.string.btn_clear_drafts) to { confirmClearAllDrafts() }
            )
        )
    }

    // ============================================
    // 草稿管理（长按）
    // ============================================

    /** 草稿长按菜单：恢复 / 详情 / 导出 / 删除 */
    private fun showDraftManageMenu(d: DraftStore.Draft) {
        val opts = arrayOf(
            getString(R.string.btn_restore_action),
            getString(R.string.btn_detail),
            getString(R.string.btn_export),
            getString(R.string.btn_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_manage, d.label()))
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> worldVM.restoreDraft(this, d)
                    1 -> showDraftDetailDialog(d)
                    2 -> exportDraft(d)
                    3 -> confirmDeleteDraft(d)
                }
            }
            .show()
    }

    /** 草稿详情：类型 / key / 所属存档 / 时间 / 顶层字段数 / 体积 / 是否含原始数据 */
    private fun showDraftDetailDialog(d: DraftStore.Draft) {
        val sb = StringBuilder()
        fun row(label: String, value: String) { sb.append(label).append(value).append('\n') }

        row(getString(R.string.label_type), draftTypeLabel(d))
        row(getString(R.string.label_key), d.sessionKey)
        row(getString(R.string.label_world),
            d.worldName?.takeIf { it.isNotBlank() } ?: getString(R.string.msg_unknown))
        row(getString(R.string.label_folder), d.folder ?: getString(R.string.msg_unknown))
        row(getString(R.string.label_time),
            fullTimeOf(d.savedAt) + "  (" + agoTextOf(d.minutesAgo()) + ")")
        row(getString(R.string.label_fields), d.data.size().toString())
        row(getString(R.string.label_size), formatSize(DraftStore.sizeOf(this, d.sessionKey)))
        row(getString(R.string.label_has_original),
            if (d.hasOriginal()) getString(R.string.yes) else getString(R.string.no))

        val tv = TextView(this).apply {
            text = sb.toString().trimEnd()
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_draft_detail))
            .setView(tv)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    /** 导出草稿（复用 NbtExporter 的格式选择 + 目标选择流程） */
    private fun exportDraft(d: DraftStore.Draft) {
        showCopyExportDialog(d.data, d.label())
    }


    /** 删除单份草稿（二次确认） */
    private fun confirmDeleteDraft(d: DraftStore.Draft) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete_draft))
            .setMessage(getString(R.string.msg_delete_draft_confirm, d.label()))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                DraftStore.remove(this, d.sessionKey)
                toast(getString(R.string.toast_draft_deleted))
                currentDialog?.dismiss()
                showDraftBoxDialog(false)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 清空当前存档的草稿 */
    private fun confirmClearDraftsHere(folder: String, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_drafts_here_confirm, count))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                DraftStore.list(this)
                    .filter { !it.folder.isNullOrBlank() && it.folder == folder }
                    .forEach { DraftStore.remove(this, it.sessionKey) }
                toast(getString(R.string.toast_drafts_cleared))
                currentDialog?.dismiss()
                showDraftBoxDialog(false)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 清空全部草稿（所有存档） */
    private fun confirmClearAllDrafts() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_danger_title))
            .setMessage(getString(R.string.msg_clear_drafts_confirm))
            .setPositiveButton(getString(R.string.btn_clear)) { _, _ ->
                DraftStore.clearAll(this)
                toast(getString(R.string.toast_drafts_cleared))
                currentDialog?.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showErrorDialog(msg: String) {
        if (msg.startsWith("DB_CORRUPT:")) {
            val parts = msg.removePrefix("DB_CORRUPT:").split(":")
            AlertDialog.Builder(this).setTitle(getString(R.string.title_database_corruption))
                .setMessage(getString(R.string.msg_have_you_tried_a_brute_force_repair))
                .setPositiveButton(getString(R.string.btn_try_to_fix)) { _, _ ->
                    worldVM.tryRepairDb(parts[0]) { ok ->
                        if (ok) { toast(getString(R.string.msg_under_repair)); parts.getOrNull(1)?.let { worldVM.loadPlayerData(this, it) } }
                    }
                }.setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> toast(getString(R.string.toast_operation_canceled_please_check_archive_integrity)) }.setCancelable(false).show()
        } else AlertDialog.Builder(this).setTitle(getString(R.string.title_error)).setMessage(msg).setPositiveButton(getString(R.string.btn_close), null).show()
    }

    // ============================================
    // 结果处理
    // ============================================
    private fun handleResult(msg: String) {
        when {
            msg.startsWith("Puzzle:SUCCESS:") -> {
                val parts = msg.removePrefix("Puzzle:SUCCESS:").split(":")
                val totalMaps = parts.getOrElse(0) { "0" }
                val totalLayers = parts.getOrElse(1) { "0" }
                val startMapId = parts.getOrElse(2) { "0" }
                val freeSlot = parts.getOrElse(3) { "0" }
                toast(getString(R.string.msg_puzzle_success_format,
                    totalMaps.toIntOrNull() ?: 0,
                    totalLayers.toIntOrNull() ?: 0,
                    startMapId.toIntOrNull() ?: 0,
                    freeSlot.toIntOrNull() ?: 0))
                worldVM.currentWorldFolder.value?.let { folder ->
                    worldVM.reloadPlayerFromCurrentDb()
                }
            }
            msg.startsWith("Puzzle:ERROR:") -> {
                val error = msg.removePrefix("Puzzle:ERROR:")
                toast(getString(R.string.msg_puzzle_error, error))
            }
            else -> toast(msg)
        }
    }

    // ============================================
    // 全屏 & 主题
    // ============================================
    internal fun toggleFullScreen(enable: Boolean) {
        isFullScreen = enable
        if (enable) {
            layoutNormalUi?.isVisible = false; layoutEditorHeader?.isVisible = false
            layoutFullscreenBar?.isVisible = true; tvFullscreenPath?.text = editorVM.pathTitle.value
        } else {
            layoutNormalUi?.isVisible = true; layoutEditorHeader?.isVisible = true
            layoutFullscreenBar?.isVisible = false
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("DiscouragedApi")
    internal fun applyTheme() {
        val main = viewMainContent ?: return
        val tvT = findViewById<TextView?>(R.id.tv_app_title)
        if (isNightMode) {
            main.setBackgroundColor(-0xededee); viewSidebar?.setBackgroundColor(-0xe1e1e2)
            tvT?.setTextColor(-0x1); nbtListView?.setBackgroundColor(-0xe1e1e2)
        } else {
            main.setBackgroundColor(-0xa0a0b); viewSidebar?.setBackgroundColor(-0x1)
            tvT?.setTextColor(-0xcccccd); nbtListView?.setBackgroundColor(-0x1)
        }
    }

    // ============================================
    // 工具
    // ============================================
    internal fun toast(s: String?) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun checkFolder(f: String): Boolean {
        if (f.isEmpty() || f == getString(R.string.hint_select_world))
        { toast(getString(R.string.toast_select_world)); return false }
        return true
    }
    private fun ensureDbLoaded(task: Runnable) {
        if (worldVM.currentWorkingDbPath != null && File(worldVM.currentWorkingDbPath!!).exists()) {
            task.run()
            return
        }
        val f = etWorldName?.text.toString()
        if (f.isEmpty() || f == getString(R.string.hint_select_world)) {
            toast(getString(R.string.toast_please_click_the_blue_button_to_select_an_archive_first))
            return
        }
        worldVM.pendingSidebarTask = task
        worldVM.loadPlayerData(this, f)
    }
    private val worksDir: File
        get() { val d = File("${externalCacheDir?.parent}/Works"); if (!d.exists()) d.mkdirs(); return d }
    private val backupsDir: File
        get() { val d = File("${externalCacheDir?.parent}/Backups"); if (!d.exists()) d.mkdirs(); return d }

    internal fun showProgressDialog(title: String, message: String) {
        if (progressDialog?.isShowing == true) progressDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        tvProgressMessage = view.findViewById(R.id.tv_progress_message)
        tvProgressMessage!!.text = message
        progressDialog = AlertDialog.Builder(this).setTitle(title).setView(view).setCancelable(false).create()
        progressDialog?.show()
    }
    private fun updateProgress(msg: String) { runOnUiThread { tvProgressMessage?.text = msg } }
    internal fun dismissProgressDialog() { progressDialog?.dismiss(); progressDialog = null; tvProgressMessage = null }

    private fun createNoMedia() {
        try {
            val b = File(BRIDGE_ROOT); if (!b.exists()) b.mkdirs()
            File(b, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        } catch (_: Exception) {}
    }
    private fun ensureNoMedia(dir: File) {
        try { if (!dir.exists()) dir.mkdirs(); File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() } }
        catch (_: Exception) {}
    }

    private fun showSlotPickerDialog(info: MapArtViewModel.InventorySlotInfo) {
        val uri = puzzleImageUri ?: run {
            mapVM.clearInventorySlotInfo()
            return
        }

        val emptyCount = info.mainSlots.count { !it.isOccupied }
        val lines = mutableListOf<String>()

        for (i in 0..35) {
            val s = info.mainSlots[i]
            if (s.isOccupied) {
                lines.add("⚠ [$i] ${s.name} x${s.count}")
            } else {
                lines.add(getString(R.string.item_slot_empty, i))
            }
        }

        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, lines)
        val lv = ListView(this).apply { this.adapter = adapter }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_puzzle_slot_select))
            .setMessage(getString(R.string.msg_puzzle_empty_slots_available, emptyCount))
            .setView(lv)
            .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                mapVM.clearInventorySlotInfo()
                puzzleImageUri = null
            }
            .create()

        lv.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            mapVM.clearInventorySlotInfo()

            val targetSlot = if (pos in 0..35) pos else {
                puzzleImageUri = null
                return@OnItemClickListener
            }

            // 如果选了有东西的格子，二次确认
            val slotInfo = info.mainSlots[targetSlot]

            if (slotInfo.isOccupied) {
                val label = getString(R.string.label_puzzle_slot, targetSlot)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_puzzle_slot_occupied))
                    .setMessage(getString(R.string.msg_puzzle_slot_has_items, label, slotInfo.name, slotInfo.count))
                    .setPositiveButton(getString(R.string.btn_puzzle_confirm_overwrite)) { _, _ ->
                        mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot)
                        puzzleImageUri = null
                    }
                    .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                        puzzleImageUri = null
                    }
                    .show()
            } else {
                mapVM.generatePuzzleMap(this, uri, puzzleRows, puzzleCols, targetSlot)
                puzzleImageUri = null
            }
        }

        dialog.show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                toast(getString(R.string.toast_storage_permission_granted))
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_permission_denied))
                    .setMessage(getString(R.string.msg_storage_permission_required))
                    .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$packageName".toUri()
                            })
                        } catch (_: Exception) {
                            toast(getString(R.string.toast_open_settings_failed))
                        }
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 如果是 Android 11+ 且之前没权限，用户授予后自动刷新
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            // 权限已授予，可以提示用户
        }
    }

    // ============================================
    // Companion
    // ============================================
    companion object {
        const val TYPE_PLAYER = 0; const val TYPE_MAP = 1; const val TYPE_VILLAGE = 2
        const val PATH_STANDARD =
            "/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/"
        const val PATH_LEGACY = "/storage/emulated/0/games/com.mojang/minecraftWorlds/"
        const val BRIDGE_ROOT = "/storage/emulated/0/Download/NbtEditor_Data/Bridge/"
        const val LEVEL_DAT_NAME = "level.dat"
        const val REQUEST_STORAGE_PERMISSION = 100
    }
}