package yhjmew.minecraft.nbteditor.util

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 编辑会话的**磁盘**存储，用于「意外退出后恢复未保存的编辑」。
 *
 * 之前的 sessionCacheMap 是纯内存的：
 * - 从最近任务划掉、系统内存不足杀进程、崩溃 —— 都拿不到
 * - 只有 Activity 重建（配置变更等）才有效
 *
 * 这里把会话落盘到 filesDir/sessions/，进程被杀后依然可用。
 */
object SessionStore {

    private const val DIR_NAME = "sessions"

    /** 会话 key 里可能有 '~' 等字符，做一次安全化 */
    private fun fileNameOf(sessionKey: String): String {
        val safe = sessionKey.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return "$safe.json"
    }

    private fun dirOf(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(context: Context, sessionKey: String): File =
        File(dirOf(context), fileNameOf(sessionKey))

    /**
     * 保存一个会话（覆盖同 key 的旧会话）。
     */
    fun save(
        context: Context,
        sessionKey: String,
        data: JsonObject?,
        isPlayerMode: Boolean,
        targetKey: String?,
        dbPath: String?,
        folder: String?
    ) {
        if (data == null) return
        try {
            val obj = JsonObject()
            obj.addProperty("key", sessionKey)
            obj.addProperty("time", System.currentTimeMillis())
            obj.addProperty("isPlayerMode", isPlayerMode)
            obj.addProperty("targetKey", targetKey)
            obj.addProperty("dbPath", dbPath)
            obj.addProperty("folder", folder)
            obj.add("data", data)
            fileOf(context, sessionKey).writeText(obj.toString())
            AppLogger.info("Session", "会话已落盘: $sessionKey")
        } catch (e: Exception) {
            AppLogger.warn("Session", "会话落盘失败: ${e.message}")
        }
    }

    fun load(context: Context, sessionKey: String): Persisted? {
        return try {
            val f = fileOf(context, sessionKey)
            if (!f.exists() || f.length() == 0L) return null
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            val data = obj.getAsJsonObject("data") ?: return null
            Persisted(
                sessionKey = obj.get("key")?.asString ?: sessionKey,
                savedAt = obj.get("time")?.asLong ?: 0L,
                isPlayerMode = obj.get("isPlayerMode")?.asBoolean ?: false,
                targetKey = obj.get("targetKey")?.takeIf { !it.isJsonNull }?.asString,
                dbPath = obj.get("dbPath")?.takeIf { !it.isJsonNull }?.asString,
                folder = obj.get("folder")?.takeIf { !it.isJsonNull }?.asString,
                data = data
            )
        } catch (e: Exception) {
            AppLogger.warn("Session", "读取会话失败: ${e.message}")
            null
        }
    }

    fun remove(context: Context, sessionKey: String) {
        try {
            fileOf(context, sessionKey).takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }
    }

    fun clearAll(context: Context) {
        try {
            dirOf(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    data class Persisted(
        val sessionKey: String,
        val savedAt: Long,
        val isPlayerMode: Boolean,
        val targetKey: String?,
        val dbPath: String?,
        val folder: String?,
        val data: JsonObject
    )
}
