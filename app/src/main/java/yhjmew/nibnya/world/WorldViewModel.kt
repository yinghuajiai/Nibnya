package yhjmew.nibnya.world

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import yhjmew.nibnya.MainActivity
import yhjmew.nibnya.R
import yhjmew.nibnya.db.PlayerDbManager
import yhjmew.nibnya.editor.EditorViewModel
import yhjmew.nibnya.editor.dialogs.*
import yhjmew.nibnya.nbt.BedrockParser
import yhjmew.nibnya.nbt.NbtTranslator
import yhjmew.nibnya.nbt.NbtTranslator.getString
import yhjmew.nibnya.util.AppDirs
import yhjmew.nibnya.util.AppLogger
import yhjmew.nibnya.util.DraftAutoSaver
import yhjmew.nibnya.util.DraftStore
import yhjmew.nibnya.util.TrashStore

/**
 * 世界/存档/路径/文件 I/O 的 ViewModel
 * 加载完成后通过 editorVM 写入数据
 */
class WorldViewModel : ViewModel(), DraftAutoSaver.Provider {

    /** Activity 创建后注入 */
    var editorVM: EditorViewModel? = null

    // ============================================
    // 数据类
    // ============================================
    data class WorldItem(
        val folderName: String,
        val displayName: String,
        /** level.dat 的最后修改时间（毫秒）；0 = 未知 */
        val lastModified: Long = 0L
    )

    data class CacheItem(
        val file: File,
        val name: String,
        val isDirectory: Boolean
    )

    data class BackupItem(
        val file: File,
        val name: String,
        /** 展示用时间（从文件名解析，格式 yyyy-MM-dd HH:mm:ss） */
        val time: String,
        val isDirectory: Boolean,
        /** 备份类型：level_ = 世界数据，db_ = 玩家数据 */
        val isPlayerData: Boolean = false,
        /** 从文件名解析出的时间戳（毫秒）；0 = 解析失败 */
        val timestamp: Long = 0L
    )

    // ============================================
    // 状态
    // ============================================
    data class ScanResult(val worlds: List<WorldItem>, val error: String? = null)

    private val _scanResultChannel = Channel<ScanResult>(Channel.BUFFERED)
    val scanResultChannel = _scanResultChannel

    private val _offerCreateKey = MutableStateFlow<String?>(null)
    val offerCreateKey: StateFlow<String?> = _offerCreateKey.asStateFlow()

    fun clearOfferCreateKey() { _offerCreateKey.value = null }

    private val _currentPath = MutableStateFlow(MainActivity.PATH_STANDARD)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    var pendingSidebarTask: Runnable? = null

    private var useMultiThread = true

    fun setUseMultiThread(use: Boolean) {
        useMultiThread = use
    }

    /**
     * 并发线程数设置：0 = 自动（按文件规模自适应），>0 = 用户指定
     */
    private var ioThreadCount = 0

    fun setIoThreadCount(n: Int) {
        ioThreadCount = if (n < 0) 0 else n
    }

    /**
     * 自动模式下的自适应线程数：
     * 文件多且大 -> 多线程；文件少或小 -> 少线程（避免线程切换开销）。
     */
    private fun autoThreadCount(totalBytes: Long, fileCount: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            // 太小：不值得并行
            totalBytes < 256 * 1024 || fileCount < 4 -> 1
            // 小规模：轻度并行
            totalBytes < 4L * 1024 * 1024 -> min(2, cores)
            // 中等：核心数
            totalBytes < 64L * 1024 * 1024 -> min(cores, 4)
            // 大存档：I/O 密集，线程数可略高于核心数
            else -> min(cores + 1, 8)
        }
    }

    /**
     * 解析最终使用的线程数。
     * @return 1 表示走单线程
     */
    private fun resolveThreadCount(totalBytes: Long, fileCount: Int): Int {
        if (!useMultiThread) return 1
        if (ioThreadCount > 0) return ioThreadCount
        return autoThreadCount(totalBytes, fileCount)
    }

    /** 递归统计目录总字节数与文件数（用于智能判断是否值得并行） */
    private fun measureTree(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        try {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count++
                }
            }
        } catch (_: Exception) {}
        return Pair(bytes, count)
    }

    private val _worldList = MutableStateFlow<List<WorldItem>>(emptyList())
    val worldList: StateFlow<List<WorldItem>> = _worldList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _currentWorldFolder = MutableStateFlow<String?>(null)
    val currentWorldFolder: StateFlow<String?> = _currentWorldFolder.asStateFlow()

    private val _worldNameForTitle = MutableStateFlow<String?>(null)
    val worldNameForTitle: StateFlow<String?> = _worldNameForTitle.asStateFlow()

    private val _worldSeedForDisplay = MutableStateFlow<String?>(null)
    val worldSeedForDisplay: StateFlow<String?> = _worldSeedForDisplay.asStateFlow()

    private val _cacheList = MutableStateFlow<List<CacheItem>>(emptyList())
    val cacheList: StateFlow<List<CacheItem>> = _cacheList.asStateFlow()

    /**
     * 备份列表。
     * ⚠️ 除了本该有的 [backupList]（供 UI 取值），这里额外用一个 **Channel** 做「扫描完成」的事件通知。
     *
     * 为什么不能只用 StateFlow：`_backupList.value = parsed` 在**内容与上次相同**时不会发射
     * （`StateFlow` 用 `equals` 去重），于是 `backupList.drop(1).collect { ... }` 永远等不到事件，
     * 表现就是「点恢复备份没任何反应」。而扫同一存档两次得到的结果本来就是相等的。
     */
    private val _backupList = MutableStateFlow<List<BackupItem>>(emptyList())
    val backupList: StateFlow<List<BackupItem>> = _backupList.asStateFlow()

    private val _backupScanDone = Channel<List<BackupItem>>(Channel.BUFFERED)
    val backupScanDone = _backupScanDone

    /** 当前备份列表所属存档的世界名（读不到时为 null，UI 回退显示文件夹名） */
    private val _backupWorldName = MutableStateFlow<String?>(null)
    val backupWorldName: StateFlow<String?> = _backupWorldName.asStateFlow()

    /** 草稿箱当前筛选的存档名（null = 全部） */
    private val _draftFilterFolder = MutableStateFlow<String?>(null)
    val draftFilterFolder: StateFlow<String?> = _draftFilterFolder.asStateFlow()
    fun setDraftFilterFolder(folder: String?) { _draftFilterFolder.value = folder }

    fun clearToast() { _toastMessage.value = null }
    fun clearError() { _errorMessage.value = null }

    // ============================================
    // 工作目录
    // ============================================
    var currentWorkingDbPath: String? = null
    var currentWorkingFileOrDir: String? = null
    var lastLoadedWorldFolder: String? = null
    var safTreeUri: android.net.Uri? = null

    private fun baseDir(context: Context): File {
        return if (_currentPath.value.contains("Android/data")) {
            context.getExternalFilesDir(null)!!
        } else {
            val publicDir = AppDirs.publicRoot()
            if (!publicDir.exists()) publicDir.mkdirs()
            publicDir
        }
    }

    fun worksDir(context: Context): File {
        val w = File(baseDir(context), "Works")
        if (!w.exists()) w.mkdirs()
        return w
    }

    fun backupsDir(context: Context): File {
        val b = File(baseDir(context), "Backups")
        if (!b.exists()) b.mkdirs()
        return b
    }

    // ============================================
    // 路径管理
    // ============================================
    fun switchPath(newPath: String) {
        _currentPath.value = newPath
    }

    /**
     * issue #6：启动时恢复上次使用的路径（由 MainActivity 从 prefs 读出后注入）
     */
    fun restorePath(savedPath: String?) {
        if (!savedPath.isNullOrBlank()) {
            _currentPath.value = savedPath
            AppLogger.info("Path", "恢复上次路径: $savedPath")
        }
    }

    // ============================================
    // 路径可写性判断（issue #6）
    // ============================================

    /**
     * 该路径是否走 Android/data 受限目录（Android 11+ 需要 Shizuku 或 MANAGE_EXTERNAL_STORAGE）
     */
    fun isRestrictedPath(path: String = _currentPath.value): Boolean {
        return path.contains("Android/data") || path.contains("Android/obb")
    }

    /**
     * 实际探测目标目录是否可直接写入（不靠猜测）。
     * 通过创建并删除一个临时文件来判断，比 canWrite() 可靠。
     */
    fun canWriteDirectly(targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) {
                // 不存在则尝试创建
                if (!targetDir.mkdirs()) return false
            }
            if (!targetDir.isDirectory) return false
            val probe = File(targetDir, ".nbt_write_test_${System.currentTimeMillis()}")
            val ok = probe.createNewFile()
            if (ok) probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断保存目标是否需要 Shizuku（受限路径 且 无法直接写）
     */
    fun needsShizukuFor(targetPath: String): Boolean {
        val dir = File(targetPath).let { if (it.isDirectory) it else it.parentFile }
        if (dir == null) return false
        return !canWriteDirectly(dir)
    }

    fun setSafPath(uri: android.net.Uri) {
        safTreeUri = uri
        _currentPath.value = "$uri/"
    }

    // ============================================
    // 扫描存档
    // ============================================
    fun scanWorlds(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_scanning)
            try {
                val dir = File(_currentPath.value)
                val rawFolders = mutableListOf<String>()

                // === 新增：检查当前路径本身是否就是一个世界文件夹 ===
                val checkLevel = File(dir, "level.dat")
                val checkDb = File(dir, "db")
                if (checkLevel.exists() && checkDb.exists()) {
                    // 当前路径本身就是世界文件夹，直接加载
                    val realName = getWorldRealNameByDir(dir) ?: "Unknown"
                    val item = WorldItem(dir.name, realName, levelDatTime(dir))
                    _worldList.value = listOf(item)
                    _scanResultChannel.trySend(ScanResult(listOf(item)))
                    _isLoading.value = false
                    return@launch
                }

                if (dir.exists() && dir.canRead()) {
                    dir.listFiles()?.forEach { if (it.isDirectory) rawFolders.add(it.name) }
                }

                if (rawFolders.isEmpty() && checkShizukuAvailable()) {
                    try {
                        val p = runShizukuCmd(arrayOf("sh", "-c", "ls \"${_currentPath.value}\""))
                        val r = BufferedReader(InputStreamReader(p.inputStream))
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            line?.trim()?.let { if (it.isNotEmpty()) rawFolders.add(it) }
                        }
                        p.waitFor()
                    } catch (_: Exception) {}
                }

                val result = mutableListOf<WorldItem>()
                for (name in rawFolders) {
                    val fullPath = _currentPath.value + name
                    val checkLevel = File(fullPath, "level.dat")
                    val checkDb = File(fullPath, "db")
                    var isValid = false

                    if (checkLevel.exists() && checkDb.exists()) isValid = true
                    else if (checkShizukuAvailable()) {
                        try {
                            val c1 = runShizukuCmd(arrayOf("sh", "-c", "ls \"${checkLevel.absolutePath}\"")).waitFor()
                            val c2 = runShizukuCmd(arrayOf("sh", "-c", "ls -d \"${checkDb.absolutePath}\"")).waitFor()
                            if (c1 == 0 && c2 == 0) isValid = true
                        } catch (_: Exception) {}
                    }

                    if (isValid) {
                        val realName = getWorldRealName(context, name) ?: "Unknown"
                        result.add(WorldItem(name, realName, levelDatTime(File(fullPath))))
                    }
                }

                // 按最后游玩时间倒序：最近玩过的排前面
                result.sortByDescending { it.lastModified }
                _worldList.value = result
                _scanResultChannel.trySend(ScanResult(result))
            } catch (e: Exception) {
                AppLogger.error("ScanWorlds", "Scan failed", e)
                _errorMessage.value = e.toString()
                _scanResultChannel.trySend(ScanResult(emptyList(), e.toString()))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun scanWorldsViaSaf(context: Context, treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_scanning)

            try {
                val resolvedPath = SafPathResolver.resolveTreeUriToPath(context, treeUri)

                if (resolvedPath != null && SafPathResolver.isProbablyUsablePath(resolvedPath)) {
                    _currentPath.value = if (resolvedPath.endsWith("/")) resolvedPath else "$resolvedPath/"
                    scanWorlds(context)
                } else {
                    scanWorldsWithDocumentFile(context, treeUri)
                }
            } catch (e: Exception) {
                _errorMessage.value = NbtTranslator.getString(R.string.err_saf_scan_failed, e.message ?: "")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun scanWorldsWithDocumentFile(context: Context, treeUri: Uri) {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return

        val worldFolders = treeDoc.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val hasLevelDat = folder.findFile("level.dat") != null
                val hasDb = folder.findFile("db") != null
                if (hasLevelDat && hasDb) {
                    val displayName = readWorldNameFromSaf(context, folder) ?: folder.name ?: "Unknown"
                    WorldItem(folderName = folder.name ?: "", displayName = displayName)
                } else null
            }

        _worldList.value = worldFolders
        _scanResultChannel.trySend(ScanResult(worldFolders))
    }

    private fun readWorldNameFromSaf(context: Context, worldFolder: DocumentFile): String? {
        try {
            val levelnameFile = worldFolder.findFile("levelname.txt") ?: return null
            context.contentResolver.openInputStream(levelnameFile.uri)?.use { input ->
                return java.io.BufferedReader(java.io.InputStreamReader(input)).readLine()
            }
        } catch (e: Exception) {
            android.util.Log.w("WorldVM", "Failed to read levelname.txt via SAF", e)
        }
        return null
    }

    fun loadLevelDatViaSaf(context: Context, folderName: String) {
        val treeUri = safTreeUri ?: return
        lastLoadedWorldFolder = folderName

        // 与 loadLevelDat 一致的会话处理，避免这条路径丢失未保存编辑
        editorVM?.let { evm ->
            evm.saveSession(currentWorkingDbPath)
            persistSession(context)
            if (evm.tryRestoreSession("level.dat")) return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_loading_level_dat)

            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw Exception("Invalid tree URI")
                val worldFolder = treeDoc.findFile(folderName)
                    ?: throw Exception("World folder not found")
                val levelDatDoc = worldFolder.findFile("level.dat")
                    ?: throw Exception("level.dat not found")

                val destFile = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (destFile.exists()) destFile.delete()

                context.contentResolver.openInputStream(levelDatDoc.uri)?.use { input ->
                    java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }

                currentWorkingFileOrDir = destFile.absolutePath
                val json = BedrockParser.parse(destFile.absolutePath)

                val evm = editorVM ?: return@launch
                evm.setEditingPlayer(false)
                evm.setTargetKey(null)
                evm.setRawNbtData(json)
                evm.nbtDataCache["level.dat"] = json
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.currentListData = json
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "level.dat", json)

                updateWorldInfoFromNbt(json)
                _currentWorldFolder.value = folderName
                _isLoading.value = false
                _toastMessage.value = NbtTranslator.getString(R.string.msg_loaded_level_dat)

            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message ?: NbtTranslator.getString(R.string.msg_load_failed)
            }
        }
    }

    // ============================================
    // 读取真实世界名
    // ============================================
    private fun getWorldRealName(context: Context, folderName: String): String? {
        val fullPath = "${_currentPath.value}$folderName/levelname.txt"
        val file = File(fullPath)
        if (file.exists() && file.canRead()) {
            try {
                val name = BufferedReader(InputStreamReader(FileInputStream(file))).readLine()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "cat \"$fullPath\""))
                val name = BufferedReader(InputStreamReader(p.inputStream)).readLine()
                p.waitFor()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getWorldRealNameByDir(worldDir: File): String? {
        val file = File(worldDir, "levelname.txt")
        if (file.exists() && file.canRead()) {
            try {
                return java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(file))).readLine()
            } catch (_: Exception) {}
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "cat \"${file.absolutePath}\""))
                val name = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream)).readLine()
                p.waitFor()
                if (!name.isNullOrBlank()) return name
            } catch (_: Exception) {}
        }
        return null
    }

    // ============================================
    // 草稿箱：自动落盘（防止进程被杀 / 崩溃丢失未保存编辑）
    // ============================================
    // 只负责「把未保存的编辑写进草稿箱」。找回方式是用户主动打开
    // 「草稿箱」（主页「恢复备份」→「草稿箱」），**不再自动弹窗打扰**。

    /** 把当前编辑内容落盘（供 Activity 在 onStop 时调用，防进程被杀） */
    fun persistSessionNow(context: Context) = persistSession(context)

    /**
     * 当前编辑内容的快照。供 DraftAutoSaver 定时落盘 / 崩溃时紧急落盘使用。
     * @return null 表示当前没有可保存的内容
     */
    override fun snapshotForDraft(): DraftAutoSaver.DraftSnapshot? {
        val evm = editorVM ?: return null
        val data = evm.nbtData.value ?: return null
        val isPlayer = evm.isEditingPlayer.value
        // level.dat 固定用 "level.dat"；只有玩家模式才记 targetKey，
        // 否则会把 _currentTargetKey 的默认值 "~local_player" 写进世界数据草稿，
        // 导致草稿箱里标签张冠李戴。
        val key = if (isPlayer) evm.currentTargetKey.value else "level.dat"
        if (key.isNullOrBlank()) return null
        return DraftAutoSaver.DraftSnapshot(
            sessionKey = key,
            data = data,
            isPlayerMode = isPlayer,
            targetKey = if (isPlayer) evm.currentTargetKey.value else null,
            dbPath = currentWorkingDbPath,
            folder = lastLoadedWorldFolder,
            worldName = _worldNameForTitle.value
        )
    }

    /** 立即落盘一份草稿 */
    private fun persistSession(context: Context) {
        val snap = snapshotForDraft() ?: return
        DraftStore.save(
            context = context,
            sessionKey = snap.sessionKey,
            data = snap.data,
            isPlayerMode = snap.isPlayerMode,
            targetKey = snap.targetKey,
            dbPath = snap.dbPath,
            folder = snap.folder,
            // 原始数据由 DraftAutoSaver 按开关携带，这里只写当前数据
            originalData = null,
            worldName = _worldNameForTitle.value,
            maxDrafts = DraftAutoSaver.maxDrafts
        )
    }

    /**
     * 从草稿箱恢复一份草稿到编辑器。
     *
     * 注意：**不自动保存** —— 只把数据套用到编辑器，用户确认无误后自己点「保存修改」。
     * 恢复前先把当前正在编辑的内容落盘，避免丢失当前进度。
     */
    fun restoreDraft(context: Context, draft: DraftStore.Draft) {
        val evm = editorVM ?: return

        // 1) 先落盘当前内容，避免被覆盖丢失
        persistSession(context)

        // 2) 套用草稿数据
        evm.setEditingPlayer(draft.isPlayerMode)
        // 世界数据不要设 targetKey（否则标题/标签会显示成 ~local_player）
        evm.setTargetKey(if (draft.isPlayerMode) draft.targetKey else null)
        evm.setRawNbtData(draft.data)
        evm.nbtDataCache[draft.sessionKey] = draft.data
        evm.navigationStack.clear()
        evm.pathStack.clear()
        evm.scrollPositionStack.clear()
        evm.updatePathTitle()

        // 3) 恢复保存目标：玩家数据需要 dbPath，level.dat 需要存档目录
        draft.dbPath?.takeIf { it.isNotBlank() }?.let { currentWorkingDbPath = it }
        draft.folder?.takeIf { it.isNotBlank() }?.let {
            lastLoadedWorldFolder = it
            _currentWorldFolder.value = it
        }

        // 4) 进入编辑状态，启动草稿自动保存
        DraftAutoSaver.start()

        // 5) 明确提示：还需要手动保存才会写入磁盘
        _toastMessage.value = getString(R.string.toast_draft_restored)
    }

    /**
     * 保存成功后调用：停掉自动保存，并清除对应的草稿。
     * @param sessionKey 与 snapshotForDraft 里使用的 key 一致
     */
    private fun onSavedSuccessfully(context: Context, sessionKey: String?) {
        DraftAutoSaver.stop()
        if (!sessionKey.isNullOrBlank()) DraftStore.remove(context, sessionKey)
    }

    /**
     * 加载存档成功后调用：进入编辑态并记录「改动前的原始数据」。
     * @param key      草稿 key（与 snapshotForDraft 一致）
     * @param original 刚加载进来的原始数据，供「对比改动」使用
     */
    private fun onEditStarted(context: Context, key: String, original: JsonObject?) {
        DraftAutoSaver.rememberOriginal(key, original)
        DraftAutoSaver.start()
    }

    // ============================================
    // 统一入口：自动区分 File / SAF
    // ============================================
    /**
     * 当前是否处于 SAF 模式（拿到了 tree uri **且** 路径是 `content://`）。
     *
     * ⚠️ 必须同时判断 `content://`：`scanWorldsViaSaf` 在「URI 能解析成可读真实路径」时
     * 会把 `_currentPath` 换成真实路径（那种情况 File API 可用），必须继续走 File 分支。
     */
    private fun isSafMode(): Boolean =
        safTreeUri != null && _currentPath.value.startsWith("content://")

    /** 打开世界数据（level.dat）：SAF 模式走 DocumentFile，否则走 File */
    fun openLevelDat(context: Context, folder: String) {
        if (isSafMode()) loadLevelDatViaSaf(context, folder)
        else loadLevelDat(context, folder)
    }

    /** 打开玩家数据（db/）：SAF 模式走 DocumentFile，否则走 File */
    fun openPlayerData(context: Context, folder: String, onSuccess: Runnable? = null) {
        when {
            isSafMode() -> loadPlayerDataViaSaf(context, folder, onSuccess)
            onSuccess == null -> loadPlayerData(context, folder)
            else -> {
                // 与 loadPlayerDataViaSaf 同样用 pendingSidebarTask 承载后续任务
                pendingSidebarTask = onSuccess
                loadPlayerData(context, folder)
            }
        }
    }

    // ============================================
    // 加载 level.dat
    // ============================================
    fun loadLevelDat(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)

        val isNewWorld = (lastLoadedWorldFolder == null || lastLoadedWorldFolder != folder)
        if (isNewWorld) {
            evm.reset()
            lastLoadedWorldFolder = folder
        } else if (evm.tryRestoreSession("level.dat")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_level_dat)
            try {
                val worldPath = resolveWorldPath(folder)
                val src = "${worldPath}level.dat"
                val destFile = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (destFile.exists()) destFile.delete()

                var success = copyFileNative(File(src), destFile)
                if (!success && checkShizukuAvailable()) {
                    val bridgeDir = File(MainActivity.BRIDGE_ROOT)
                    if (!bridgeDir.exists()) bridgeDir.mkdirs()
                    val bridgeFile = MainActivity.BRIDGE_ROOT + "level.dat"
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "cp \"$src\" \"$bridgeFile\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "chmod 777 \"$bridgeFile\"")).waitFor()
                    val bF = File(bridgeFile)
                    if (bF.exists()) { copyFile(bF, destFile); success = true }
                }

                if (!success) throw Exception("Failed to copy level.dat")

                currentWorkingFileOrDir = destFile.absolutePath

                val json = BedrockParser.parse(destFile.absolutePath)

                evm.setEditingPlayer(false)
                evm.setTargetKey(null)

                evm.setRawNbtData(json)  // 内部赋值
                evm.nbtDataCache["level.dat"] = json
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.currentListData = json
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "level.dat", json)

                // 更新世界信息
                updateWorldInfoFromNbt(json)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.msg_loaded_level_dat)
            } catch (e: Exception) {
                AppLogger.error("LoadLevelDat", "Load level.dat failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message ?: getString(R.string.msg_load_failed)
            }
        }
    }

    // ============================================
    // 加载玩家数据
    // ============================================

    fun loadPlayerDataViaSaf(context: Context, folderName: String, onSuccess: Runnable? = null) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (onSuccess == null && evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = NbtTranslator.getString(R.string.msg_loading_player)
            try {
                val treeUri = safTreeUri ?: throw Exception("SAF URI is null")
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw Exception("Invalid tree URI")
                val worldFolder = treeDoc.findFile(folderName)
                    ?: throw Exception("World folder not found: $folderName")
                val dbFolder = worldFolder.findFile("db")
                    ?: throw Exception("db folder not found")

                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(worksDir(context), "working_db_$uniqueId")
                if (!workDir.exists()) workDir.mkdirs()

                // 递归从 SAF 复制 db 文件夹
                safCopyDirectory(context, dbFolder, workDir)

                cleanLevelDbMeta(workDir)
                currentWorkingDbPath = workDir.absolutePath

                val dbManager = PlayerDbManager(workDir.absolutePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = BedrockParser.parseBytes(data)
                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "~local_player", playerDataObj)

                _currentWorldFolder.value = folderName
                _isLoading.value = false
                _toastMessage.value = NbtTranslator.getString(R.string.toast_player_loaded_success)
                onSuccess?.let { pendingSidebarTask = it }

            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
                if (e.message?.contains("DB_CORRUPT") == true) {
                    currentWorkingDbPath?.let { dbPath ->
                        _errorMessage.value = "DB_CORRUPT:$dbPath:$folderName"
                    }
                }
            }
        }
    }

    fun loadPlayerData(context: Context, folder: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (evm.tryRestoreSession("~local_player")) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_loading_player)
            try {
                val worldDir = File(resolveWorldPath(folder))
                val srcPath = File(worldDir, "db").absolutePath
                val srcDirFile = File(srcPath)

                val canUseShizuku = checkShizukuAvailable()
                var useNative = false
                if (srcDirFile.exists() && srcDirFile.canRead() && srcDirFile.listFiles() != null) {
                    useNative = true
                } else if (!canUseShizuku) {
                    // issue #6：给出可操作的提示，而不是笼统的「需要 Shizuku」
                    throw Exception(getString(R.string.msg_cannot_read_need_permission_or_path, srcPath))
                }

                // 清理锁
                try {
                    if (useNative) File(srcDirFile, "LOCK").delete()
                    else {
                        val p = runShizukuCmd(arrayOf("sh", "-c", "ls \"$srcPath/LOCK\""))
                        if (p.waitFor() == 0) runShizukuCmd(arrayOf("sh", "-c", "rm -f \"$srcPath/LOCK\"")).waitFor()
                    }
                } catch (_: Exception) {}

                val uniqueId = System.currentTimeMillis().toString()
                val workDir = File(worksDir(context), "working_db_$uniqueId")
                val appPrivatePath = workDir.absolutePath

                if (useNative) {
                    if (!workDir.exists()) workDir.mkdirs()
                    smartCopy(srcDirFile, workDir)
                } else {
                    val bridgePath = MainActivity.BRIDGE_ROOT + "load_db_$uniqueId"
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                    createNoMedia()
                    runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")).waitFor()
                    runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"$bridgePath\"")).waitFor()

                    val cmd = "cp -rf \"$srcPath/.\" \"$bridgePath/\""
                    val pCopy = runShizukuCmd(arrayOf("sh", "-c", cmd))
                    val exitCode = pCopy.waitFor()
                    if (exitCode != 0) {
                        val err = BufferedReader(InputStreamReader(pCopy.errorStream)).readText()
                        throw Exception(getString(R.string.text_shizuku_copy_error, exitCode, err))
                    }
                    runShizukuCmd(arrayOf("sh", "-c", "chmod -R 777 \"$bridgePath\"")).waitFor()
                    val bridgeDir = File(bridgePath)
                    if (!bridgeDir.exists() || bridgeDir.list().isNullOrEmpty())
                        throw Exception(getString(R.string.text_source_dir_empty, srcPath))
                    if (!workDir.exists()) workDir.mkdirs()
                    smartCopy(bridgeDir, workDir)
                    runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgePath\"")).waitFor()
                }

                cleanLevelDbMeta(workDir)

                currentWorkingDbPath = appPrivatePath

                val dbManager = PlayerDbManager(appPrivatePath)
                val data = dbManager.readLocalPlayer()
                dbManager.close()

                val playerDataObj = BedrockParser.parseBytes(data)

                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, "~local_player", playerDataObj)

                _currentWorldFolder.value = folder
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_player_loaded_success)
            } catch (e: Exception) {
                AppLogger.error("LoadPlayer", "Load player failed", e)
                _isLoading.value = false
                _errorMessage.value = e.message
                // 数据库损坏
                if (e.message?.contains("DB_CORRUPT") == true) {
                    currentWorkingDbPath?.let { dbPath ->
                        _errorMessage.value = "DB_CORRUPT:$dbPath:$folder"
                    }
                }
            }
        }
    }

    // ============================================
    // 加载指定 Key
    // ============================================
    fun loadSpecificKey(context: Context, keyName: String) {
        val evm = editorVM ?: return
        evm.saveSession(currentWorkingDbPath)
        persistSession(context)
        if (evm.tryRestoreSession(keyName)) return
        if (evm.nbtDataCache.containsKey(keyName)) {
            evm.setTargetKey(keyName)
            evm.setEditingPlayer(true)
            evm.setRawNbtData(evm.nbtDataCache[keyName])
            evm.navigationStack.clear()
            evm.pathStack.clear()
            evm.scrollPositionStack.clear()
            evm.updatePathTitle()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                currentWorkingDbPath?.let { File(it, "LOCK").delete() }
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val data: ByteArray
                try {
                    data = db.readSpecificKey(keyName)
                } catch (e: Exception) {
                    db.close()
                    _isLoading.value = false
                    _offerCreateKey.value = keyName
                    return@launch
                }
                db.close()
                val jsonData = BedrockParser.parseBytes(data)

                evm.setTargetKey(keyName)
                evm.setEditingPlayer(true)
                evm.setRawNbtData(jsonData)
                evm.nbtDataCache[keyName] = jsonData
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                // 进入编辑态：启动草稿自动保存并记录原始数据
                onEditStarted(context, keyName, jsonData)
                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loaded, keyName)
            } catch (e: Exception) {
                AppLogger.error("LoadKey", "Load key $keyName failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    fun createNewGlobalData(keyName: String?) {
        val evm = editorVM ?: return
        val emptyNbt = JsonObject()
        evm.setTargetKey(keyName)
        evm.setEditingPlayer(true)
        evm.setRawNbtData(emptyNbt)
        evm.navigationStack.clear()
        evm.pathStack.clear()
        evm.scrollPositionStack.clear()
        evm.updatePathTitle()
    }

    // ============================================
    // 保存
    // ============================================
    fun saveAndPushBack(context: Context, dataToSave: JsonObject?, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = getString(R.string.msg_saving)
            try {
                createBackup(context, folder)
                val evm = editorVM ?: throw Exception(getString(R.string.msg_editorvm_missing))

                if (evm.isEditingPlayer.value) {
                    val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                    val oldWorkDir = File(dbPath)
                    if (!oldWorkDir.exists()) throw Exception(getString(R.string.msg_workdir_lost))

                    val uniqueId = System.currentTimeMillis().toString()
                    val newWorkDir = File(worksDir(context), "working_db_$uniqueId")
                    smartCopy(oldWorkDir, newWorkDir)
                    cleanLevelDbMeta(newWorkDir)

                    // 先把当前正在编辑的数据写入缓存，确保不遗漏
                    val currentKey = evm.currentTargetKey.value
                    AppLogger.info("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey, dataToSave keys=${dataToSave?.keySet()?.joinToString(",") { it } ?: "null"}")
                    if (currentKey != null && dataToSave != null) {
                        evm.nbtDataCache[currentKey] = dataToSave
                        AppLogger.info("ListDebug", "saveAndPushBack(玩家): nbtDataCache[$currentKey] 已更新为 dataToSave")
                    } else {
                        AppLogger.warn("ListDebug", "saveAndPushBack(玩家): currentKey=$currentKey 或 dataToSave=null，未更新缓存!")
                    }

                    // 遍历缓存，写入全部修改过的数据
                    val db = PlayerDbManager(newWorkDir.absolutePath)

                    // 确保把 DB 中所有已修改的数据都写回（包括拼图生成的地图）
                    val allKeys = mutableSetOf<String>()
                    allKeys.addAll(evm.nbtDataCache.keys.filterNotNull())
// 从 DB 中扫描所有已知 key
                    try {
                        allKeys.addAll(db.listMapKeys())
                        allKeys.addAll(db.listVillageKeys())
                        allKeys.addAll(db.listPlayerKeys())
                    } catch (_: Exception) {}

// 遍历写入
                    for (key in allKeys) {
                        val data = evm.nbtDataCache[key]
                        if (data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                            AppLogger.info("ListDebug", "写DB(遍历allKeys): key=$key, bytes=${bytes.size}, keys=${data.keySet().joinToString(",") { it }}")
                        }
                    }

                    for ((key, data) in evm.nbtDataCache) {
                        if (key != null && data != null) {
                            val bytes = BedrockParser.writeToBytes(data)
                            db.writeSpecificKey(key, bytes)
                            AppLogger.info("ListDebug", "写DB(遍历缓存): key=$key, bytes=${bytes.size}, keys=${data.keySet().joinToString(",") { it }}")
                        }
                    }

                    db.close()

                    val mcDbPath = "${resolveWorldPath(folder)}db/"
                    val mcDbDir = File(mcDbPath)

                    // issue #6：按「可写性」决定写入方式，而不是单纯看有没有 Shizuku
                    // 1) 先尝试直接文件写入（Download/games 等公共目录可写）
                    var savedDirectly = false
                    if (canWriteDirectly(mcDbDir)) {
                        try {
                            smartCopy(newWorkDir, mcDbDir)
                            savedDirectly = true
                            AppLogger.info("Save", "玩家数据直接写入成功: $mcDbPath")
                        } catch (e: Exception) {
                            AppLogger.warn("Save", "直接写入失败，尝试 Shizuku: ${e.message}")
                        }
                    }

                    // 2) SAF 模式：目标路径是 content://，File 与 Shizuku 都无效 -> 走 DocumentFile
                    //    ⚠️ 失败必须抛错。绝不能像原来那样「发个命令就当成功」，那是数据丢失。
                    if (!savedDirectly && isSafMode()) {
                        val worldDoc = SafIo.worldDirOf(context, safTreeUri!!, folder)
                            ?: throw Exception(getString(R.string.msg_save_need_permission_or_path, mcDbPath))
                        val (safOk, safErr) = SafIo.replaceDirInSaf(context, worldDoc, "db", newWorkDir)
                        if (!safOk) throw Exception(safErr ?: getString(R.string.msg_save_need_permission_or_path, mcDbPath))
                        savedDirectly = true
                        AppLogger.info("Save", "玩家数据经 SAF 写入: $mcDbPath")
                    }

                    // 3) 直接写与 SAF 都不行，且 Shizuku 可用 -> 走桥接
                    if (!savedDirectly) {
                        if (checkShizukuAvailable()) {
                            val bridgeSave = MainActivity.BRIDGE_ROOT + "save_db_$uniqueId"
                            runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${MainActivity.BRIDGE_ROOT}\"")).waitFor()
                            createNoMedia()
                            runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()
                            smartCopy(newWorkDir, File(bridgeSave))
                            val cp = runShizukuCmd(arrayOf("sh", "-c", "cp -rf \"$bridgeSave/.\" \"$mcDbPath\""))
                            val exit = cp.waitFor()
                            runShizukuCmd(arrayOf("sh", "-c", "rm -rf \"$bridgeSave\"")).waitFor()
                            // 必须检查退出码：非 0 时数据其实没写进去，绝不能提示成功
                            if (exit != 0) throw Exception(getString(R.string.msg_save_need_permission_or_path, mcDbPath))
                            AppLogger.info("Save", "玩家数据经 Shizuku 写入: $mcDbPath")
                        } else {
                            // 4) 都不行 -> 明确报错，提示用户处理权限/换路径
                            throw Exception(getString(R.string.msg_save_need_permission_or_path, mcDbPath))
                        }
                    }

                    deleteRecursive(oldWorkDir)
                    currentWorkingDbPath = newWorkDir.absolutePath
                    // 已成功写回磁盘，会话不再需要
                    onSavedSuccessfully(context, evm.currentTargetKey.value)
                    _toastMessage.value = getString(R.string.toast_player_saved)
                } else {
                    if (currentWorkingFileOrDir == null) {
                        val f = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                        currentWorkingFileOrDir = f.absolutePath
                    }
                    BedrockParser.write(dataToSave, currentWorkingFileOrDir)

                    val workingFile = File(currentWorkingFileOrDir!!)
                    val targetPath = "${resolveWorldPath(folder)}level.dat"
                    val targetFile = File(targetPath)

                    // issue #6：先试直接写，失败再用 Shizuku，都不行给明确提示
                    var success = copyFileNative(workingFile, targetFile)
                    if (success) {
                        AppLogger.info("Save", "level.dat 直接写入成功: $targetPath")
                    }

                    // SAF 模式：content:// 路径 File 写不了，改用 DocumentFile 覆盖
                    if (!success && isSafMode()) {
                        val worldDoc = SafIo.worldDirOf(context, safTreeUri!!, folder)
                            ?: throw Exception(getString(R.string.msg_save_need_permission_or_path, targetPath))
                        val (safOk, safErr) = SafIo.replaceFileInSaf(
                            context, worldDoc, MainActivity.LEVEL_DAT_NAME, workingFile
                        )
                        if (!safOk) throw Exception(safErr ?: getString(R.string.msg_save_need_permission_or_path, targetPath))
                        success = true
                        AppLogger.info("Save", "level.dat 经 SAF 写入: $targetPath")
                    }

                    if (!success && checkShizukuAvailable()) {
                        // Shizuku 降级
                        val bridgeFile = MainActivity.BRIDGE_ROOT + "level.dat"
                        File(MainActivity.BRIDGE_ROOT).mkdirs()
                        copyFile(workingFile, File(bridgeFile))
                        val cp = runShizukuCmd(arrayOf("sh", "-c", "cp \"$bridgeFile\" \"$targetPath\""))
                        // 必须检查退出码：原来无条件 success = true，cp 失败也会提示「已保存」
                        if (cp.waitFor() == 0) {
                            success = true
                            AppLogger.info("Save", "level.dat 经 Shizuku 写入: $targetPath")
                        } else {
                            AppLogger.warn("Save", "level.dat 经 Shizuku 写入失败（cp 退出码非 0）: $targetPath")
                        }
                    }

                    if (!success) {
                        throw Exception(getString(R.string.msg_save_need_permission_or_path, targetPath))
                    }
                    // 已成功写回磁盘，会话不再需要
                    onSavedSuccessfully(context, "level.dat")
                    _toastMessage.value = getString(R.string.toast_level_dat_saved)
                }
                _isLoading.value = false
            } catch (e: Exception) {
                AppLogger.error("Save", "Save failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.msg_save_failed_with_details, "${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            }
        }
    }

    private fun createBackup(context: Context, folderName: String) {
        try {
            val backupRoot = File(backupsDir(context), folderName)
            if (!backupRoot.exists()) backupRoot.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val evm = editorVM ?: return
            if (evm.isEditingPlayer.value) {
                currentWorkingDbPath?.let { dbPath ->
                    val srcDb = File(dbPath)
                    if (srcDb.exists() && srcDb.isDirectory) {
                        copyDirectory(srcDb, File(backupRoot, "db_$ts"))
                    }
                }
            } else {
                val src = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                if (src.exists() && src.isFile) {
                    copyFileNative(src, File(backupRoot, "level_$ts.dat"))
                }
            }
        } catch (e: Exception) {
            Log.e("WorldVM", "Backup failed", e)
        }
    }

    // ============================================
    // 缓存管理
    // ============================================
    fun scanCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = worksDir(context)
            if (!dir.exists()) dir.mkdirs()
            val files = dir.listFiles()
            val list = mutableListOf<CacheItem>()
            if (files != null) {
                files.sortByDescending { it.lastModified() }
                for (f in files) list.add(CacheItem(f, f.name, f.isDirectory))
            }
            _cacheList.value = list
        }
    }

    fun deleteCacheItem(item: CacheItem) {
        deleteRecursive(item.file)
    }

    fun clearAllCache(context: Context) {
        val dir = worksDir(context)
        dir.listFiles()?.forEach { deleteRecursive(it) }
        currentWorkingDbPath = null
        currentWorkingFileOrDir = null
        lastLoadedWorldFolder = null
        _cacheList.value = emptyList()
    }

    // ============================================
    // 备份管理
    // ============================================
    fun scanBackups(context: Context, folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 世界名：读原存档的 levelname.txt（存档已删则保持 null，UI 显示文件夹名）
            _backupWorldName.value = try {
                getWorldRealName(context, folderName)
            } catch (_: Exception) {
                null
            }

            val dir = File(backupsDir(context), folderName)
            if (!dir.exists()) {
                _backupList.value = emptyList()
                _backupScanDone.trySend(emptyList())
                return@launch
            }
            val files = dir.listFiles() ?: emptyArray()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            // 备份文件名由 createBackup 生成：level_<ts>.dat / db_<ts>
            // 时间从**文件名**解析 —— 目录的 lastModified 对备份目录毫无意义
            // （之前就因此显示成「文件夹被改动的时间」）
            val parsed = files.map { f ->
                val isPlayer = f.name.startsWith("db_")
                val ts = parseBackupTimestamp(f.name)
                BackupItem(
                    file = f,
                    name = f.name,
                    time = if (ts > 0) sdf.format(Date(ts)) else sdf.format(Date(f.lastModified())),
                    isDirectory = f.isDirectory,
                    isPlayerData = isPlayer,
                    timestamp = if (ts > 0) ts else f.lastModified()
                )
            }.sortedByDescending { it.timestamp }
            _backupList.value = parsed
            // 用事件通道通知 UI：内容与上次相同时 StateFlow 不会发射，走通道才能保证「点一次弹一次」
            _backupScanDone.trySend(parsed)
        }
    }

    /**
     * 从备份文件名解析时间戳（毫秒）。
     * 形如 level_2026-10-10_23-33-39.dat 或 db_2026-10-10_23-21-55
     * @return 0 表示解析失败
     */
    private fun parseBackupTimestamp(fileName: String): Long {
        return try {
            val body = fileName
                .removePrefix("level_").removePrefix("db_")
                .removeSuffix(".dat")
            val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            fmt.isLenient = false
            fmt.parse(body)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun restoreBackup(context: Context, backupItem: BackupItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val evm = editorVM ?: return@launch
                if (backupItem.isDirectory) {
                    val uniqueId = System.currentTimeMillis().toString()
                    val restoreDir = File(worksDir(context), "working_db_restore_$uniqueId")
                    deleteRecursive(restoreDir)
                    smartCopy(backupItem.file, restoreDir)
                    File(restoreDir, "LOCK").delete()
                    File(restoreDir, "LOG").delete()
                    File(restoreDir, "LOG.old").delete()
                    currentWorkingDbPath = restoreDir.absolutePath

                    val db = PlayerDbManager(restoreDir.absolutePath)
                    val data = db.readLocalPlayer()
                    db.close()

                    val json = BedrockParser.parseBytes(data)
                    evm.setEditingPlayer(true)
                    evm.setRawNbtData(json)
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.updatePathTitle()

                    // 进入编辑态：启动草稿自动保存并记录原始数据
                    onEditStarted(context, "~local_player", json)
                    _toastMessage.value = getString(R.string.toast_player_restored)
                } else {
                    val target = File(worksDir(context), MainActivity.LEVEL_DAT_NAME)
                    copyFileNative(backupItem.file, target)
                    currentWorkingFileOrDir = target.absolutePath

                    val json = BedrockParser.parse(target.absolutePath)
                    evm.setEditingPlayer(false)
                    evm.setTargetKey(null)
                    evm.setRawNbtData(json)
                    evm.nbtDataCache["level.dat"] = json
                    evm.navigationStack.clear()
                    evm.pathStack.clear()
                    evm.scrollPositionStack.clear()
                    evm.currentListData = json
                    evm.updatePathTitle()

                    // 进入编辑态：启动草稿自动保存并记录原始数据
                    onEditStarted(context, "level.dat", json)
                    updateWorldInfoFromNbt(json)
                    _toastMessage.value = getString(R.string.toast_level_restored)
                }
            } catch (e: Exception) {
                _errorMessage.value = getString(R.string.msg_restore_failed, e.message)
            }
        }
    }

    fun deleteBackup(item: BackupItem) {
        deleteRecursive(item.file)
    }

    fun renameBackup(item: BackupItem, newName: String): Boolean {
        val newFile = File(item.file.parent, newName)
        return item.file.renameTo(newFile)
    }
    // ============================================
    // 删除存档（移入回收站）
    // ============================================

    /**
     * 把整个世界目录移入回收站。
     *
     * 优先尝试原生命名（同分区时是原子操作、瞬间完成）；
     * 失败则退回复制 + 删除；再失败且 Shizuku 可用则用 mv。
     *
     * @return Pair(成功, 失败原因)；成功时 second 为回收站目录名
     */
    fun moveWorldToTrash(
        context: Context,
        folder: String,
        worldName: String?,
        alsoBackups: Boolean,
        alsoDrafts: Boolean
    ): Pair<Boolean, String?> {
        // SAF 模式：源是 content:// 路径，File.exists() 恒为 false，必须走 DocumentFile。
        // 顺序很重要：**先完整复制成功，才允许删源**；复制失败绝不动源目录。
        if (isSafMode()) {
            val treeUri = safTreeUri!!
            val worldDoc = SafIo.worldDirOf(context, treeUri, folder)
                ?: return Pair(false, getString(R.string.msg_world_dir_not_found))
            val slotName = TrashStore.newSlotName(worldName)
            val slot = TrashStore.slotDir(context, slotName)
            val dest = File(slot, "world")
            slot.mkdirs()
            val copyErr = SafIo.copyDocToFile(context, worldDoc, dest) { it in levelDbMetaFiles }
            if (copyErr != null) {
                deleteRecursive(dest)   // 清掉半成品，源目录原样保留
                return Pair(false, getString(R.string.msg_delete_failed) + "：$copyErr")
            }
            if (!SafIo.deleteDoc(worldDoc)) {
                // 已复制成功但删不掉源：回收站里有副本、源也还在，明确报告失败
                return Pair(false, getString(R.string.msg_delete_failed))
            }
            TrashStore.writeMeta(slot, folder, worldName, alsoBackups, alsoDrafts, safFolder = true)
            if (alsoBackups) moveBackupsIntoSlot(context, folder, slot)
            if (alsoDrafts) deleteDraftsOf(context, folder)
            if (lastLoadedWorldFolder == folder) {
                lastLoadedWorldFolder = null
                currentWorkingDbPath = null
                currentWorkingFileOrDir = null
                _currentWorldFolder.value = null
                editorVM?.reset()
            }
            AppLogger.info("World", "已移入回收站(SAF): $folder -> $slotName")
            return Pair(true, slotName)
        }

        val src = File(resolveWorldPath(folder))
        if (!src.exists()) return Pair(false, getString(R.string.msg_world_dir_not_found))

        // 能不能写？不能写就别白忙（Android/data 需要 Shizuku 或所有文件权限）
        val parent = src.parentFile
        val canNative = parent != null && canWriteDirectly(parent)
        val canShizuku = checkShizukuAvailable()
        if (!canNative && !canShizuku) {
            return Pair(false, getString(R.string.msg_delete_need_permission))
        }

        val slotName = TrashStore.newSlotName(worldName)
        val slot = TrashStore.slotDir(context, slotName)
        val dest = File(slot, "world")

        // 1) 原生命名（同分区）
        var moved = false
        if (canNative) {
            try {
                slot.mkdirs()
                moved = src.renameTo(dest)
            } catch (_: Exception) {
            }
            // 2) 复制 + 删除
            if (!moved) {
                try {
                    dest.mkdirs()
                    smartCopy(src, dest)
                    deleteRecursive(src)
                    moved = true
                } catch (_: Exception) {
                }
            }
        }
        // 3) Shizuku mv
        if (!moved && canShizuku) {
            try {
                runShizukuCmd(arrayOf("sh", "-c", "mkdir -p \"${slot.absolutePath}\"")).waitFor()
                val p = runShizukuCmd(arrayOf("sh", "-c",
                    "mv \"${src.absolutePath}\" \"${dest.absolutePath}\""))
                moved = p.waitFor() == 0
            } catch (_: Exception) {
            }
        }

        if (!moved) return Pair(false, getString(R.string.msg_delete_failed))

        TrashStore.writeMeta(slot, folder, worldName, alsoBackups, alsoDrafts)

        // 连带处理：备份（移入回收站槽内，恢复时可一并找回）
        if (alsoBackups) moveBackupsIntoSlot(context, folder, slot)

        // 连带处理：草稿
        if (alsoDrafts) deleteDraftsOf(context, folder)

        // 若当前正在编辑该存档，清掉加载状态避免指向已消失的目录
        if (lastLoadedWorldFolder == folder) {
            lastLoadedWorldFolder = null
            currentWorkingDbPath = null
            currentWorkingFileOrDir = null
            _currentWorldFolder.value = null
            editorVM?.reset()
        }

        AppLogger.info("World", "已移入回收站: $folder -> $slotName")
        return Pair(true, slotName)
    }

    /** 从回收站恢复一个存档 */
    fun restoreFromTrash(
        context: Context,
        item: TrashStore.TrashItem,
        overwrite: Boolean
    ): Pair<Boolean, String?> {
        val src = item.worldDir()
        if (!src.exists()) return Pair(false, getString(R.string.msg_trash_content_missing))

        val targetParent = File(_currentPath.value)
        val target = File(targetParent, item.originalFolder)

        // SAF 模式恢复：目标位置是 content://，只能走 DocumentFile
        if (isSafMode()) {
            val treeUri = safTreeUri!!
            val parentDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Pair(false, getString(R.string.msg_restore_failed))
            val existing = parentDoc.findFile(item.originalFolder)
            if (existing != null && !overwrite) {
                return Pair(false, getString(R.string.msg_restore_target_exists))
            }
            if (existing != null && overwrite) {
                // 旧存档也先挪进回收站，避免直接覆盖丢数据
                val oldSlot = TrashStore.slotDir(context, TrashStore.newSlotName("${item.originalFolder}_overwritten"))
                oldSlot.mkdirs()
                val oldDest = File(oldSlot, "world")
                val moveErr = SafIo.copyDocToFile(context, existing, oldDest) { it in levelDbMetaFiles }
                if (moveErr == null) SafIo.deleteDoc(existing)
            }
            val (okSaf, errSaf) = SafIo.replaceDirInSaf(context, parentDoc, item.originalFolder, src)
            if (!okSaf) return Pair(false, errSaf ?: getString(R.string.msg_restore_failed))
            deleteRecursive(src)
            restoreBackupsIfAny(context, item)
            try { deleteRecursive(item.dir) } catch (_: Exception) {}
            AppLogger.info("World", "已从回收站恢复(SAF): ${item.originalFolder}")
            return Pair(true, null)
        }

        if (target.exists() && !overwrite) {
            return Pair(false, getString(R.string.msg_restore_target_exists))
        }
        if (target.exists() && overwrite) {
            // 目标已存在：把旧的也挪进回收站，避免直接覆盖丢失
            val oldSlot = TrashStore.slotDir(context, TrashStore.newSlotName("${item.originalFolder}_overwritten"))
            try {
                oldSlot.mkdirs()
                if (!target.renameTo(File(oldSlot, "world"))) {
                    val d = File(oldSlot, "world"); d.mkdirs()
                    smartCopy(target, d); deleteRecursive(target)
                }
            } catch (_: Exception) {
            }
        }

        var ok = false
        try {
            if (!targetParent.exists()) targetParent.mkdirs()
            ok = src.renameTo(target)
            if (!ok) {
                target.mkdirs(); smartCopy(src, target); deleteRecursive(src); ok = true
            }
        } catch (_: Exception) {
        }
        if (!ok && checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c",
                    "mv \"${src.absolutePath}\" \"${target.absolutePath}\""))
                ok = p.waitFor() == 0
            } catch (_: Exception) {
            }
        }
        if (!ok) return Pair(false, getString(R.string.msg_restore_failed))

        // 备份一并还原
        restoreBackupsIfAny(context, item)

        // 清掉回收站槽（内容已移走）
        try {
            deleteRecursive(item.dir)
        } catch (_: Exception) {
        }

        AppLogger.info("World", "已从回收站恢复: ${item.originalFolder}")
        return Pair(true, null)
    }

    /** 删除某存档的全部草稿 */
    private fun deleteDraftsOf(context: Context, folder: String) {
        try {
            DraftStore.list(context)
                .filter { it.folder == folder }
                .forEach { DraftStore.remove(context, it.sessionKey) }
        } catch (_: Exception) {
        }
    }

    /** 把某存档的备份移入回收站槽（恢复时可一并找回） */
    private fun moveBackupsIntoSlot(context: Context, folder: String, slot: File) {
        try {
            val bk = File(backupsDir(context), folder)
            if (bk.exists()) {
                val bkDest = File(slot, "backups")
                if (!bk.renameTo(bkDest)) {
                    bkDest.mkdirs(); smartCopy(bk, bkDest); deleteRecursive(bk)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 恢复存档时，把连带移入回收站槽的备份放回备份目录 */
    private fun restoreBackupsIfAny(context: Context, item: TrashStore.TrashItem) {
        if (!item.hasBackups) return
        try {
            val bkSrc = File(item.dir, "backups")
            if (bkSrc.exists()) {
                val bkDest = File(backupsDir(context), item.originalFolder)
                if (bkDest.exists()) deleteRecursive(bkDest)
                bkDest.parentFile?.mkdirs()
                if (!bkSrc.renameTo(bkDest)) { bkDest.mkdirs(); smartCopy(bkSrc, bkDest) }
            }
        } catch (_: Exception) {
        }
    }

    /** 自动清理回收站（设置里可配天数） */
    fun autoCleanTrash(context: Context, days: Int): Int = TrashStore.autoClean(context, days)



    // ============================================
    // 清理旧会话
    // ============================================
    fun cleanUpOldSessions(context: Context) {
        val privateWorks = File(context.getExternalFilesDir(null), "Works")
        cleanDirContent(privateWorks)
        val publicWorks = AppDirs.works()
        cleanDirContent(publicWorks)
        val bridgeDir = File(MainActivity.BRIDGE_ROOT)
        if (bridgeDir.exists()) {
            createNoMedia()
            bridgeDir.listFiles()?.forEach {
                if (it.name != ".nomedia") deleteRecursive(it)
            }
        } else {
            bridgeDir.mkdirs()
            createNoMedia()
        }
    }

    private fun cleanDirContent(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach {
            if ((it.isDirectory && (it.name.startsWith("working_db_") || it.name.startsWith("db_restore_")))
                || (it.isFile && it.name == MainActivity.LEVEL_DAT_NAME)
            ) deleteRecursive(it)
        }
    }

    // ============================================
    // 更新世界信息卡片
    // ============================================
    fun updateWorldInfoFromNbt(rootJson: JsonObject?) {
        if (rootJson == null) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
            return
        }
        try {
            var nameStr: String? = null
            if (rootJson.has("LevelName")) {
                val obj = rootJson.getAsJsonObject("LevelName")
                if (obj.has("v")) nameStr = obj.get("v").asString
            }
            var seedStr: String? = null
            if (rootJson.has("RandomSeed")) {
                val obj = rootJson.getAsJsonObject("RandomSeed")
                if (obj.has("v")) seedStr = obj.get("v").asString
            }
            _worldNameForTitle.value = nameStr
            _worldSeedForDisplay.value = seedStr
        } catch (_: Exception) {
            _worldNameForTitle.value = null
            _worldSeedForDisplay.value = null
        }
    }

    // ============================================
    // 加载自定义 Key（Hex/文本）
    // ============================================
    fun loadCustomKey(inputStr: String, isHex: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val dbPath = currentWorkingDbPath ?: throw Exception(getString(R.string.text_db_path_is_null))
                val db = PlayerDbManager(dbPath)
                val keyBytes = if (isHex) hexStringToByteArray(inputStr)
                else inputStr.toByteArray(Charsets.UTF_8)
                val data = db.readRawKey(keyBytes)
                db.close()
                val json = BedrockParser.parseBytes(data)

                val evm = editorVM ?: return@launch
                evm.setEditingPlayer(true)
                evm.setTargetKey(inputStr)
                evm.setRawNbtData(json)
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()

                _isLoading.value = false
                _toastMessage.value = getString(R.string.toast_loading_successfully)
            } catch (e: Exception) {
                AppLogger.error("LoadCustomKey", "Custom key load failed", e)
                _isLoading.value = false
                _errorMessage.value = getString(R.string.err_load_failed_with_msg, e.message)
            }
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val hex = s.replace(" ", "")
        require(hex.length % 2 == 0) { getString(R.string.msg_the_length_must_be_an_even_number) }
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val high = hex[i].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i]))
            val low = hex[i + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException(getString(R.string.msg_invalid_hex, hex[i + 1]))
            data[i / 2] = ((high shl 4) + low).toByte()
        }
        return data
    }

    // ============================================
    // Shizuku（照搬原代码）
    // ============================================
    private var useShizuku = true

    fun setUseShizuku(use: Boolean) { useShizuku = use }

    fun requestShizukuPermission() {
        try {
            Class.forName("rikka.shizuku.Shizuku")
                .getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, 0)
        } catch (_: Exception) {
            // 忽略，调用方自行处理
        }
    }

    sealed class ShizukuStatus {
        object Available : ShizukuStatus()
        object NotInstalled : ShizukuStatus()
        object NotRunning : ShizukuStatus()
        object PermissionDenied : ShizukuStatus()
    }

    fun checkShizukuStatus(context: Context? = null): ShizukuStatus {
        if (!useShizuku) return ShizukuStatus.NotInstalled
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            val ping = c.getMethod("pingBinder").invoke(null) as? Boolean ?: return ShizukuStatus.NotRunning
            if (!ping) {
                // ping 失败，检查 Shizuku App 是否安装
                val isInstalled = context?.let { ctx ->
                    try {
                        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                } ?: true // 没传 context 时默认 NotRunning
                return if (isInstalled) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
            }
            val perm = c.getMethod("checkSelfPermission").invoke(null) as? Int
            if (perm != PackageManager.PERMISSION_GRANTED) return ShizukuStatus.PermissionDenied
            return ShizukuStatus.Available
        } catch (_: ClassNotFoundException) {
            return ShizukuStatus.NotInstalled
        } catch (_: Exception) {
            return ShizukuStatus.NotRunning
        }
    }

    // 保留旧的 checkShizukuAvailable() 但改为调用新方法保持兼容
    fun checkShizukuAvailable(): Boolean {
        return checkShizukuStatus() is ShizukuStatus.Available
    }

    fun checkShizukuReady(): Boolean {
        if (!useShizuku) return true
        if (checkShizukuAvailable()) return true
        try {
            val c = Class.forName("rikka.shizuku.Shizuku")
            c.getMethod("requestPermission", Int::class.javaPrimitiveType).invoke(null, 0)
        } catch (_: Exception) {}
        return false
    }

    fun checkStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${context.packageName}".toUri()
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun runShizukuCmd(cmd: Array<String?>): Process {
        if (!checkShizukuAvailable()) throw Exception(getString(R.string.err_shizuku_not_running))
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        var m: Method? = null
        for (mm in shizukuClass.declaredMethods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) for (mm in shizukuClass.methods) {
            if (mm.name == "newProcess" && mm.parameterTypes.size == 3) { m = mm; break }
        }
        if (m == null) throw Exception(getString(R.string.msg_shizuku_api_error))
        m.isAccessible = true
        try { return m.invoke(null, cmd, null, null) as Process }
        catch (e: Exception) {
            if (e.message?.contains(getString(R.string.msg_binder_haven_t_been_received)) == true)
                throw Exception(getString(R.string.err_shizuku_binder_not_ready))
            throw e
        }
    }

    // ============================================
    // 文件操作
    // ============================================
    /** 拷贝缓冲区：64KB（原为 1KB，大文件时系统调用次数减少 64 倍） */
    private val copyBufferSize = 64 * 1024

    /** 零拷贝的阈值：大文件用 FileChannel.transferTo，小文件走普通流更划算 */
    private val zeroCopyThreshold = 512L * 1024

    private fun copyFile(src: File, dst: File) {
        // 大文件走零拷贝（免用户态↔内核态数据搬运），小文件用流即可
        if (src.length() >= zeroCopyThreshold) {
            try {
                copyFileZeroCopy(src, dst)
                return
            } catch (_: Exception) {
                // 零拷贝失败（某些 SAF/虚拟文件系统不支持），回退到流式
            }
        }
        copyFileStream(src, dst)
    }

    /**
     * FileChannel.transferTo 零拷贝。
     * 在 Linux/Android 上可直接由内核在 fd 间搬运数据，避免 64KB 缓冲区的往返。
     */
    private fun copyFileZeroCopy(src: File, dst: File) {
        FileInputStream(src).channel.use { inCh ->
            FileOutputStream(dst).channel.use { outCh ->
                var pos = 0L
                val size = inCh.size()
                while (pos < size) {
                    val moved = inCh.transferTo(pos, size - pos, outCh)
                    if (moved <= 0) break
                    pos += moved
                }
            }
        }
    }

    private fun copyFileStream(src: File, dst: File) {
        val `in` = FileInputStream(src)
        val out = FileOutputStream(dst)
        try {
            val buf = ByteArray(copyBufferSize)
            var len: Int
            while ((`in`.read(buf).also { len = it }) > 0) out.write(buf, 0, len)
            out.flush()
        } finally {
            try { `in`.close() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
        }
    }

    private fun copyFileNative(src: File, dst: File): Boolean {
        return try { copyFile(src, dst); true } catch (_: Exception) { false }
    }

    /** LevelDB 临时/锁文件，拷贝时必须跳过；CURRENT/MANIFEST 是索引必须保留 */
    private val levelDbMetaFiles = setOf("LOCK", "LOG", "LOG.old")

    private fun shouldSkipFile(name: String): Boolean {
        return name in levelDbMetaFiles
    }

    private fun cleanLevelDbMeta(dir: File) {
        File(dir, "LOCK").takeIf { it.exists() }?.delete()
        File(dir, "LOG").takeIf { it.exists() }?.delete()
        File(dir, "LOG.old").takeIf { it.exists() }?.delete()
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            source.list()?.forEach {
                if (shouldSkipFile(it)) return@forEach
                copyDirectory(File(source, it), File(target, it))
            }
        } else copyFile(source, target)
    }

    private fun safCopyDirectory(context: Context, srcDoc: DocumentFile, dstDir: File) {
        if (!dstDir.exists()) dstDir.mkdirs()
        for (child in srcDoc.listFiles()) {
            val name = child.name ?: continue
            if (shouldSkipFile(name)) continue
            val dstFile = File(dstDir, name)
            if (child.isDirectory) {
                safCopyDirectory(context, child, dstFile)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    java.io.FileOutputStream(dstFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * 取世界 level.dat 的最后修改时间（毫秒）。
     * 原生读不到时（Android/data 无权限）退回 Shizuku 的 stat 命令。
     * @return 0 表示未知
     */
    private fun levelDatTime(worldDir: File): Long {
        val lf = File(worldDir, "level.dat")
        try {
            if (lf.exists() && lf.canRead()) return lf.lastModified()
        } catch (_: Exception) {
        }
        if (checkShizukuAvailable()) {
            try {
                val p = runShizukuCmd(arrayOf("sh", "-c", "stat -c %Y \"${lf.absolutePath}\" 2>/dev/null"))
                val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
                p.waitFor()
                // stat 返回秒，转毫秒
                out?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1000L }
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * 统一拷贝入口：
     * - 目录：按「总大小 + 文件数」智能决定是否并行、用几个线程
     * - 文件：直接拷
     */
    private fun smartCopy(src: File, dst: File) {
        if (!src.isDirectory) {
            copyFile(src, dst)
            return
        }
        if (!useMultiThread) {
            copyDirectory(src, dst)
            return
        }

        // 统计规模，决定线程数（自动降级：小规模走单线程）
        val (totalBytes, fileCount) = measureTree(src)
        val threads = resolveThreadCount(totalBytes, fileCount)
        if (threads <= 1) {
            copyDirectory(src, dst)
        } else {
            copyDirectoryParallel(src, dst, threads)
        }
    }

    /** 递归收集待拷贝的 (源文件, 目标文件) 与需要创建的目录 */
    private fun collectCopyPlan(
        source: File, target: File,
        files: MutableList<Pair<File, File>>,
        dirs: MutableList<File>
    ) {
        if (source.isDirectory) {
            dirs.add(target)
            source.listFiles()?.forEach { child ->
                if (shouldSkipFile(child.name)) return@forEach
                collectCopyPlan(child, File(target, child.name), files, dirs)
            }
        } else if (source.isFile) {
            files.add(Pair(source, target))
        }
    }

    /**
     * 多线程拷贝：先收集完整清单（单线程、极快），再并行拷贝文件。
     * 这样只创建一个线程池，不会像递归实现那样为每个子目录新建线程池。
     */
    private fun copyDirectoryParallel(source: File, target: File, threads: Int) {
        AppLogger.info("Copy", "多线程拷贝: $threads 线程, $source -> $target")
        val files = mutableListOf<Pair<File, File>>()
        val dirs = mutableListOf<File>()
        collectCopyPlan(source, target, files, dirs)

        // 建目录（必须先于文件）
        dirs.forEach { if (!it.exists()) it.mkdirs() }
        if (files.isEmpty()) return

        val totalFiles = files.size
        val totalSize = files.sumOf { it.first.length() }
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(totalFiles)
        val errorRef = AtomicReference<Throwable?>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val copiedSize = java.util.concurrent.atomic.AtomicLong(0L)

        files.forEach { (srcFile, dstFile) ->
            executor.submit {
                try {
                    if (errorRef.get() != null) return@submit
                    dstFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    copyFile(srcFile, dstFile)

                    val done = completed.incrementAndGet()
                    val bytes = copiedSize.addAndGet(srcFile.length())
                    val progress = (bytes * 100 / maxOf(totalSize, 1)).toInt()
                    _progressMessage.value =
                        getString(R.string.copying_progress, done, totalFiles, progress)
                } catch (e: Throwable) {
                    errorRef.set(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
        errorRef.get()?.let { throw it }
    }

    fun createNoMedia() {
        try {
            val bridgeDir = File(MainActivity.BRIDGE_ROOT)
            if (!bridgeDir.exists()) bridgeDir.mkdirs()
            val noMedia = File(bridgeDir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (_: Exception) {}
    }

    fun ensureNoMedia(dir: File) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
        } catch (_: Exception) {}
    }

    // ============================================
    // DB 修复
    // ============================================
    fun tryRepairDb(dbPath: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PlayerDbManager.tryRepair(dbPath)
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                AppLogger.error("RepairDB", "DB repair failed", e)
                _errorMessage.value = getString(R.string.toast_repair_failed, e.message)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    /** 供 UI 展示用：暴露世界目录路径 */
    fun resolveWorldPathPublic(folder: String): String = resolveWorldPath(folder)

    private fun resolveWorldPath(folder: String): String {
        val base = _currentPath.value
        val baseFile = File(base)
        // 如果当前路径的最后一段文件夹名 == folder，说明路径已经指向世界本身
        if (baseFile.name == folder) {
            return if (base.endsWith("/")) base else "$base/"
        }
        return if (base.endsWith("/")) "$base$folder/" else "$base/$folder/"
    }

    fun reloadPlayerFromCurrentDb() {
        val dbPath = currentWorkingDbPath ?: return
        val evm = editorVM ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _loadingMessage.value = "Reloading player..."
            try {
                val db = PlayerDbManager(dbPath)
                val data = db.readLocalPlayer()
                db.close()

                val playerDataObj = BedrockParser.parseBytes(data)
                evm.setEditingPlayer(true)
                evm.setTargetKey("~local_player")
                evm.setRawNbtData(playerDataObj)
                evm.nbtDataCache["~local_player"] = playerDataObj
                evm.navigationStack.clear()
                evm.pathStack.clear()
                evm.scrollPositionStack.clear()
                evm.updatePathTitle()
                _isLoading.value = false
                _toastMessage.value = "Player reloaded"
            } catch (e: Exception) {
                _isLoading.value = false
                _errorMessage.value = e.message
            }
        }
    }
}