package yhjmew.minecraft.nbteditor

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * 尝试把 SAF Tree Uri 推测为普通路径
 * 这里只是推测，不保证 100% 可用
 */
object SafPathResolver {

    fun resolveTreeUriToPath(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) return null
        if (Build.VERSION.SDK_INT < 21) return null

        return try {
            val authority = treeUri.authority
            if (authority != "com.android.externalstorage.documents") return null

            var docId: String? = null

            try {
                docId = DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Throwable) {
            }

            if (docId.isNullOrEmpty()) {
                try {
                    if (DocumentsContract.isDocumentUri(context, treeUri)) {
                        docId = DocumentsContract.getDocumentId(treeUri)
                    }
                } catch (_: Throwable) {
                }
            }

            if (docId.isNullOrEmpty()) return null

            resolveExternalStorageDocId(docId!!)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 处理 externalstorage 文档ID
     * 例如：
     * primary:Download
     * primary:Documents/Test
     * 1234-5678:Folder/Test
     * raw:/storage/emulated/0/Download/Test
     */
    private fun resolveExternalStorageDocId(docId: String): String? {
        if (docId.isEmpty()) return null

        if (docId.startsWith("raw:")) {
            return docId.substring(4)
        }

        val index = docId.indexOf(':')
        if (index < 0) return null

        val volume = docId.substring(0, index)
        var relativePath = docId.substring(index + 1)

        val basePath = when {
            "primary".equals(volume, ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            "home".equals(volume, ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath + "/Documents"
            else -> "/storage/$volume"
        }

        return joinPath(basePath, relativePath)
    }

    private fun joinPath(basePath: String, relativePath: String): String? {
        if (basePath.isEmpty()) return null
        if (relativePath.isEmpty()) return basePath

        var rel = relativePath.replace('\\', '/')
        while (rel.startsWith("/")) {
            rel = rel.substring(1)
        }

        return if (basePath.endsWith("/")) basePath + rel else "$basePath/$rel"
    }

    fun isProbablyUsablePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }
}
