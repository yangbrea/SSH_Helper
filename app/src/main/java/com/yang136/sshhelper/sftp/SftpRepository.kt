package com.yang136.sshhelper.sftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.LocalRootEntity
import com.yang136.sshhelper.data.SftpBookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class LocalFile(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String?,
)

data class LocalTreeEntry(val file: LocalFile, val relativePath: String)

class SftpRepository(private val context: Context, private val database: AppDatabase) {
    val localRoots: Flow<List<LocalRootEntity>> = database.localRootDao().observeAll()

    fun bookmarks(hostId: Long): Flow<List<SftpBookmarkEntity>> = database.sftpBookmarkDao().observeForHost(hostId)

    suspend fun addLocalRoot(uri: Uri, displayName: String) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        database.localRootDao().insert(LocalRootEntity(uri = uri.toString(), displayName = displayName))
    }

    suspend fun removeLocalRoot(root: LocalRootEntity) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        database.localRootDao().delete(root)
    }

    suspend fun listLocal(uri: Uri): List<LocalFile> = withContext(Dispatchers.IO) {
        listChildren(uri)
    }

    suspend fun createLocalDirectory(rootUri: Uri, name: String): LocalFile = withContext(Dispatchers.IO) {
        val parent = asDocumentUri(rootUri)
        val created = DocumentsContract.createDocument(
            context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: error("无法创建本地目录")
        localFile(created)
    }

    suspend fun walkLocal(uri: Uri): List<LocalTreeEntry> = withContext(Dispatchers.IO) {
        val start = localFile(asDocumentUri(uri))
        buildList {
            fun visit(file: LocalFile, relative: String) {
                add(LocalTreeEntry(file, relative))
                if (file.isDirectory) listChildren(file.uri).forEach { child ->
                    visit(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                }
            }
            visit(start, "")
        }
    }

    suspend fun ensureLocalDirectory(parentUri: Uri, relativePath: String): Uri = withContext(Dispatchers.IO) {
        var current = asDocumentUri(parentUri)
        relativePath.split('/').filter(String::isNotBlank).forEach { segment ->
            require(segment != "." && segment != ".." && !segment.contains('/')) { "无效的本地目录名" }
            current = listChildren(current).firstOrNull { it.name == segment && it.isDirectory }?.uri
                ?: DocumentsContract.createDocument(
                    context.contentResolver, current, DocumentsContract.Document.MIME_TYPE_DIR, segment,
                )
                ?: error("无法创建本地目录：$segment")
        }
        current
    }

    suspend fun renameLocal(uri: Uri, name: String) = withContext(Dispatchers.IO) {
        check(DocumentsContract.renameDocument(context.contentResolver, asDocumentUri(uri), name) != null) {
            "当前文件提供程序不支持重命名"
        }
    }

    suspend fun deleteLocal(uri: Uri) = withContext(Dispatchers.IO) {
        check(DocumentsContract.deleteDocument(context.contentResolver, asDocumentUri(uri))) { "无法删除本地文件" }
    }

    suspend fun addBookmark(hostId: Long, path: String, label: String) {
        database.sftpBookmarkDao().insert(SftpBookmarkEntity(hostId = hostId, path = normalizeRemotePath(path), label = label.trim().ifEmpty { path }))
    }

    suspend fun removeBookmark(bookmark: SftpBookmarkEntity) = database.sftpBookmarkDao().delete(bookmark)

    private fun asDocumentUri(uri: Uri): Uri = if (DocumentsContract.isDocumentUri(context, uri)) uri else {
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }

    private fun listChildren(parentUri: Uri): List<LocalFile> {
        val parent = asDocumentUri(parentUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val child = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                    val mime = cursor.getString(2)
                    add(LocalFile(
                        uri = child,
                        name = cursor.getString(1) ?: "未命名",
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (cursor.isNull(3)) -1 else cursor.getLong(3),
                        modifiedAt = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        mimeType = mime,
                    ))
                }
            }
        } ?: error("无法读取本地目录")
    }

    private fun localFile(uri: Uri): LocalFile {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return context.contentResolver.query(asDocumentUri(uri), projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "本地文件不存在" }
            val mime = cursor.getString(1)
            LocalFile(
                asDocumentUri(uri), cursor.getString(0) ?: "未命名",
                mime == DocumentsContract.Document.MIME_TYPE_DIR,
                if (cursor.isNull(2)) -1 else cursor.getLong(2),
                if (cursor.isNull(3)) 0 else cursor.getLong(3), mime,
            )
        } ?: error("本地文件不存在")
    }
}
