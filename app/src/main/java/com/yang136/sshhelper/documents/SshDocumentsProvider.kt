package com.yang136.sshhelper.documents

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.yang136.sshhelper.SshHelperApplication
import com.yang136.sshhelper.sftp.RemoteFile
import com.yang136.sshhelper.sftp.RemoteFileType
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class SshDocumentsProvider : DocumentsProvider() {
    private val providerContext: android.content.Context get() = checkNotNull(context) { "Provider 尚未初始化" }
    private val authority: String get() = "${providerContext.packageName}.documents"
    private val backend: DocumentsBackend
        get() = backendOverride
            ?: (providerContext.applicationContext as SshHelperApplication).container.documentsBackend

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = providerCall {
        val cursor = MatrixCursor(projection.orDefault(ROOT_PROJECTION))
        backend.roots().forEach { root ->
            val host = root.host
            cursor.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, "host:${host.id}")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentIdCodec.root(host.id))
                add(DocumentsContract.Root.COLUMN_TITLE, host.name)
                add(DocumentsContract.Root.COLUMN_SUMMARY, "${host.username}@${host.hostname}:${host.port}")
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                add(DocumentsContract.Root.COLUMN_ICON, com.yang136.sshhelper.R.mipmap.ic_launcher)
            }
        }
        cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = providerCall {
        val id = DocumentIdCodec.decode(documentId)
        val file = backend.stat(id.hostId, id.path)
        MatrixCursor(projection.orDefault(DOCUMENT_PROJECTION)).also { includeDocument(it, id, file) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = providerCall {
        val parent = DocumentIdCodec.decode(parentDocumentId)
        val cursor = MatrixCursor(projection.orDefault(DOCUMENT_PROJECTION))
        backend.children(parent.hostId, parent.path)
            .sortedWith(compareBy<RemoteFile> { it.type != RemoteFileType.DIRECTORY }.thenBy { it.name.lowercase(Locale.ROOT) })
            .forEach { child -> includeDocument(cursor, DocumentId(parent.hostId, child.path), child) }
        cursor.setNotificationUri(providerContext.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = providerCall {
        signal?.throwIfCanceled()
        val id = DocumentIdCodec.decode(documentId)
        val path = id.path ?: throw FileNotFoundException("不能打开主目录")
        val writable = mode.any { it == 'w' || it == 'a' || it == '+' }
        val needsExistingContent = !writable || mode.contains('r') || mode.contains('a')
        signal?.setOnCancelListener { backend.close(id.hostId) }
        val opened = try {
            backend.prepareOpen(id.hostId, path, needsExistingContent)
        } finally {
            signal?.setOnCancelListener(null)
        }
        signal?.throwIfCanceled()
        val thread = HandlerThread("ssh-doc-close-${id.hostId}").apply { start() }
        val randomAccess = RandomAccessFile(opened.cache, if (writable) "rw" else "r")
        try {
            val callback = object : ProxyFileDescriptorCallback() {
                private fun requireUnlocked() {
                    if (!backend.isDeviceUnlocked()) throw ErrnoException("DocumentsProvider", OsConstants.EACCES)
                }

                override fun onGetSize(): Long = synchronized(randomAccess) {
                    requireUnlocked()
                    randomAccess.length()
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = synchronized(randomAccess) {
                    requireUnlocked()
                    randomAccess.seek(offset)
                    randomAccess.read(data, 0, size).coerceAtLeast(0)
                }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = synchronized(randomAccess) {
                    requireUnlocked()
                    randomAccess.seek(offset)
                    randomAccess.write(data, 0, size)
                    size
                }

                override fun onFsync() = synchronized(randomAccess) {
                    requireUnlocked()
                    randomAccess.fd.sync()
                }

                override fun onRelease() {
                    synchronized(randomAccess) { randomAccess.close() }
                    runBlocking(Dispatchers.IO) {
                        var failed = false
                        try {
                            if (writable) backend.commit(opened)
                        } catch (_: Throwable) {
                            failed = true
                        } finally {
                            backend.release(opened, keepRecovery = failed)
                            notifyDocumentChanged(documentId)
                        }
                    }
                    thread.quitSafely()
                }
            }
            providerContext.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.parseMode(mode),
                callback,
                Handler(thread.looper),
            )
        } catch (error: Throwable) {
            randomAccess.close()
            thread.quitSafely()
            runBlocking(Dispatchers.IO) { backend.release(opened, keepRecovery = false) }
            throw error
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = providerCall {
        val parent = DocumentIdCodec.decode(parentDocumentId)
        val created = backend.create(
            parent.hostId,
            parent.path,
            displayName,
            directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
        )
        notifyChildrenChanged(parentDocumentId)
        DocumentIdCodec.encode(DocumentId(parent.hostId, created.path))
    }

    override fun renameDocument(documentId: String, displayName: String): String = providerCall {
        val id = DocumentIdCodec.decode(documentId)
        val path = id.path ?: error("不能重命名主目录")
        val renamed = backend.rename(id.hostId, path, displayName)
        notifyDocumentChanged(documentId)
        DocumentIdCodec.encode(DocumentId(id.hostId, renamed.path))
    }

    override fun deleteDocument(documentId: String) = providerCall {
        val id = DocumentIdCodec.decode(documentId)
        val path = id.path ?: error("不能删除主目录")
        backend.delete(id.hostId, path)
        notifyDocumentChanged(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = providerCall {
        val parent = DocumentIdCodec.decode(parentDocumentId)
        val child = DocumentIdCodec.decode(documentId)
        if (parent.hostId != child.hostId) return@providerCall false
        child.path?.let { backend.isChild(parent.hostId, parent.path, it) } ?: false
    }

    override fun getDocumentType(documentId: String): String = providerCall {
        val id = DocumentIdCodec.decode(documentId)
        mimeType(backend.stat(id.hostId, id.path))
    }

    private fun includeDocument(cursor: MatrixCursor, id: DocumentId, file: RemoteFile) {
        val isRoot = id.path == null
        val directory = file.type == RemoteFileType.DIRECTORY
        val flags = when {
            isRoot -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            directory -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            file.type == RemoteFileType.FILE -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> 0
        }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentIdCodec.encode(id))
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isRoot) "主目录" else file.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, if (directory) null else file.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.modifiedAt.takeIf { it > 0 })
        }
    }

    private fun mimeType(file: RemoteFile): String {
        if (file.type == RemoteFileType.DIRECTORY) return DocumentsContract.Document.MIME_TYPE_DIR
        val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        providerContext.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null,
        )
    }

    private fun notifyDocumentChanged(documentId: String) {
        providerContext.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority, documentId), null)
    }

    private fun <T> providerCall(block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) { block() }
    } catch (error: FileNotFoundException) {
        throw error
    } catch (error: Throwable) {
        throw FileNotFoundException(error.message ?: "远端文件操作失败").apply { initCause(error) }
    }

    private fun Array<out String>?.orDefault(default: Array<String>): Array<String> = this?.map(String::toString)?.toTypedArray() ?: default

    companion object {
        @Volatile internal var backendOverride: DocumentsBackend? = null

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

data class DocumentId(val hostId: Long, val path: String? = null)

object DocumentIdCodec {
    private const val PREFIX = "v1"
    private const val ROOT = "~"

    fun root(hostId: Long): String = encode(DocumentId(hostId))

    fun encode(id: DocumentId): String {
        require(id.hostId > 0) { "无效的主机 ID" }
        val path = id.path?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray())
        } ?: ROOT
        return "$PREFIX|${id.hostId}|$path"
    }

    fun decode(value: String): DocumentId {
        val parts = value.split('|', limit = 3)
        require(parts.size == 3 && parts[0] == PREFIX) { "无效的文档 ID" }
        val hostId = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: error("无效的主机 ID")
        if (parts[2] == ROOT) return DocumentId(hostId)
        val path = runCatching { Base64.getUrlDecoder().decode(parts[2]).decodeToString() }
            .getOrElse { error("无效的文档 ID") }
        require(path.startsWith('/') && !path.split('/').contains("..") && '\u0000' !in path) { "无效的远端路径" }
        return DocumentId(hostId, path)
    }
}
