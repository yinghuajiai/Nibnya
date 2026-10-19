package yhjmew.nibnya.world

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import yhjmew.nibnya.util.AppLogger
import java.io.File
import yhjmew.nibnya.R

/**
 * SAF（存储访问框架）读写工具层。
 *
 * 为什么需要它：SAF 模式下存档路径是 `content://…`，`java.io.File` 对它**完全无效**
 * （连 `exists()` 都是 false），所以这些路径的读写必须走 `DocumentFile`。
 *
 * ⚠️ 本文件只负责「怎么做」，不负责「该不该做」；所有失败都返回原因字符串，
 * **由调用方决定是抛错还是降级** —— 绝不允许在这里吞掉失败后返回成功。
 */
object SafIo {

    // ============================================
    // 定位
    // ============================================

    /** 在 tree uri 根下找到名为 [folder] 的世界目录 */
    fun worldDirOf(context: Context, treeUri: Uri, folder: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        // 情形 1：tree 本身就是一个世界目录（用户直接选了世界文件夹）
        if (root.name == folder && isWorldDir(root)) return root
        // 情形 2：tree 是「存档根」，其下第一个同名目录才是世界
        val child = root.findFile(folder) ?: return null
        return if (child.isDirectory) child else null
    }

    private fun isWorldDir(doc: DocumentFile): Boolean =
        doc.isDirectory && doc.findFile("level.dat") != null && doc.findFile("db") != null

    // ============================================
    // 读：SAF → File
    // ============================================

    /**
     * 递归把 [srcDoc] 的全部内容复制到本地目录 [dest]。
     * @param skip 需要跳过的文件名（如 LevelDB 的 LOCK/LOG）
     * @return null 表示成功，否则为失败原因
     */
    fun copyDocToFile(
        context: Context,
        srcDoc: DocumentFile,
        dest: File,
        skip: (String) -> Boolean = { false }
    ): String? {
        if (!dest.exists() && !dest.mkdirs()) return "无法创建目录 ${dest.absolutePath}"
        for (child in srcDoc.listFiles()) {
            val name = child.name ?: continue
            if (skip(name)) continue
            val target = File(dest, name)
            if (child.isDirectory) {
                copyDocToFile(context, child, target, skip)?.let { return it }
            } else {
                try {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: return "无法读取 $name"
                } catch (e: Exception) {
                    return "读取 $name 失败：${e.message}"
                }
            }
        }
        return null
    }

    // ============================================
    // 写：File → SAF（目录：临时目录 + 改名 + 可回滚）
    // ============================================

    /**
     * 用 [srcDir] 的内容替换 [parent] 下名为 [name] 的目录。
     *
     * 顺序（**故意做成可回滚**，绝不先删旧数据）：
     * ① 完整写入临时目录 `name__new_<ts>`
     * ② 旧目录 `name` 改名为 `name__old_<ts>`
     * ③ 临时目录改名为 `name`
     * ④ 若 ③ 失败 → 把旧目录改回 `name`（回滚）
     * ⑤ 全部成功后才删除 `name__old_<ts>`
     *
     * provider 不支持改名时降级为「就地逐个覆盖」（不删目录本身）。
     *
     * @return Pair(是否成功, 失败原因)；成功时 second 为 null
     */
    fun replaceDirInSaf(
        context: Context,
        parent: DocumentFile,
        name: String,
        srcDir: File
    ): Pair<Boolean, String?> {
        val ts = System.currentTimeMillis()
        val newName = "${name}__new_$ts"
        val oldName = "${name}__old_$ts"

        // 0) 清理可能的上次残留
        parent.findFile(newName)?.delete()
        parent.findFile(oldName)?.delete()

        // 1) 完整写入临时目录
        val newDir = parent.createDirectory(newName)
            ?: return false to "无法创建临时目录（权限不足或空间不足）"
        copyFileToDoc(context, srcDir, newDir)?.let { err ->
            newDir.delete()
            return false to "写入临时目录失败：$err"
        }

        // 2) 旧目录让位
        val old = parent.findFile(name)
        var movedOld = false
        if (old != null) {
            if (old.renameTo(oldName)) {
                movedOld = true
            } else {
                // provider 不支持改名 → 就地覆盖（仍不删目录本身）
                val err = mergeFileToDoc(context, srcDir, old)
                newDir.delete()
                return if (err == null) true to null
                else false to "该目录不支持改名，就地覆盖失败：$err"
            }
        }

        // 3) 临时目录改名到位
        if (!newDir.renameTo(name)) {
            // 回滚：旧目录改回来，原数据不受影响
            if (movedOld) parent.findFile(oldName)?.renameTo(name)
            newDir.delete()
            return false to "改名失败（该目录位置不支持改名），已回滚，原数据未受影响"
        }

        // 4) 成功后才清理旧目录
        if (movedOld) parent.findFile(oldName)?.delete()
        return true to null
    }

    /**
     * 用 [srcFile] 覆盖 [parent] 下名为 [name] 的文件。
     *
     * 优先**原地覆盖**（截断写入）：不删旧文件，避免「删了却写不进去」。
     * 文件不存在时才新建。
     *
     * @return Pair(是否成功, 失败原因)
     */
    fun replaceFileInSaf(
        context: Context,
        parent: DocumentFile,
        name: String,
        srcFile: File
    ): Pair<Boolean, String?> {
        val existing = parent.findFile(name)
        if (existing != null && existing.isFile) {
            writeFileToDoc(context, existing, srcFile)?.let { return false to it }
            return true to null
        }
        val created = parent.createFile("application/octet-stream", name)
            ?: return false to "无法创建文件 $name"
        writeFileToDoc(context, created, srcFile)?.let { err ->
            created.delete()
            return false to err
        }
        return true to null
    }

    /** 覆盖写入已有文档（截断）；返回 null 表示成功 */
    private fun writeFileToDoc(context: Context, doc: DocumentFile, srcFile: File): String? {
        return try {
            val bytes = srcFile.readBytes()
            val out = try {
                context.contentResolver.openOutputStream(doc.uri, "wt")
            } catch (_: Exception) {
                null
            } ?: try {
                context.contentResolver.openOutputStream(doc.uri, "w")
            } catch (_: Exception) {
                null
            } ?: return "无法打开输出流（provider 不支持写入）"
            out.use {
                it.write(bytes)
                it.flush()
            }
            null
        } catch (e: Exception) {
            "写入失败：${e.message}"
        }
    }

    // ============================================
    // 写：File → SAF（内部递归）
    // ============================================

    /**
     * 把本地目录 [srcDir] 的内容写入文档目录 [dstDoc]。
     * @return null 表示成功，否则为失败原因
     */
    private fun copyFileToDoc(
        context: Context,
        srcDir: File,
        dstDoc: DocumentFile,
        skip: (String) -> Boolean = { false }
    ): String? {
        val children = srcDir.listFiles() ?: return null
        for (child in children) {
            if (skip(child.name)) continue
            if (child.isDirectory) {
                val sub = dstDoc.createDirectory(child.name)
                    ?: return "无法创建子目录 ${child.name}"
                copyFileToDoc(context, child, sub, skip)?.let { return it }
            } else {
                val doc = dstDoc.createFile("application/octet-stream", child.name)
                    ?: return "无法创建文件 ${child.name}"
                writeFileToDoc(context, doc, child)?.let { return it }
            }
        }
        return null
    }

    /**
     * 降级路径：把 [srcDir] 的内容**逐个覆盖**写入已有目录 [dstDoc]（不删除多余文件、不删目录本身）。
     * 仅在该位置不支持 `renameDocument` 时使用。
     * @return null 表示成功
     */
    private fun mergeFileToDoc(
        context: Context,
        srcDir: File,
        dstDoc: DocumentFile
    ): String? {
        val children = srcDir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val sub = dstDoc.findFile(child.name)?.takeIf { it.isDirectory }
                    ?: dstDoc.createDirectory(child.name)
                    ?: return "无法创建子目录 ${child.name}"
                mergeFileToDoc(context, child, sub)?.let { return it }
            } else {
                val existing = dstDoc.findFile(child.name)
                if (existing != null && existing.isFile) {
                    writeFileToDoc(context, existing, child)?.let { return it }
                } else {
                    val doc = dstDoc.createFile("application/octet-stream", child.name)
                        ?: return "无法创建文件 ${child.name}"
                    writeFileToDoc(context, doc, child)?.let { return it }
                }
            }
        }
        return null
    }

    // ============================================
    // 删
    // ============================================

    /**
     * 递归删除一个文档（目录或文件）。
     * @return 是否删除成功
     */
    fun deleteDoc(doc: DocumentFile): Boolean {
        return try {
            if (doc.isDirectory) {
                doc.listFiles().forEach { child ->
                    // 子项删除失败不中断：父目录删除时系统会一并清理
                    if (!deleteDoc(child)) AppLogger.warn("SafIo", R.string.log_saf_delete_failed, child.name)
                }
            }
            val ok = doc.delete()
            if (!ok) AppLogger.warn("SafIo", R.string.log_saf_delete_returned_false, doc.name)
            ok
        } catch (e: Exception) {
            AppLogger.warn("SafIo", R.string.log_saf_delete_exception, doc.name, e.message)
            false
        }
    }
}
