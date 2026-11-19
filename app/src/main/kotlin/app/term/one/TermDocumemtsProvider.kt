package app.term.one

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class TermDocumentsProvider : DocumentsProvider() {

    private lateinit var base: File
    private lateinit var authority: String

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val paths = TermPaths.get(ctx)
        paths.ensureDirectories()
        base = paths.home
        authority = ctx.packageName + ".documents"
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.drawable.ic_documents_root)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_DOCUMENT_ID, base.path)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, base.freeSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        include(cursor, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: throw FileNotFoundException("Provider is not ready")
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("$parentDocumentId is not a directory")
        }
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.setNotificationUri(
            ctx.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        )
        parent.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { include(cursor, it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("$parentDocumentId is not a directory")
        }
        val target = uniqueFile(parent, cleanName(displayName))
        try {
            val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
            if (!created) {
                throw FileNotFoundException("Unable to create ${target.name}")
            }
        } catch (e: IOException) {
            throw FileNotFoundException("Unable to create ${target.name}: ${e.message}")
        }
        notifyChildren(parent)
        return target.path
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (file == base) {
            throw FileNotFoundException("The home directory cannot be deleted")
        }
        deleteTree(file)
        if (existsNoFollow(file)) {
            throw FileNotFoundException("Unable to delete ${file.name}")
        }
        file.parentFile?.let { notifyChildren(it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        if (file == base) {
            throw FileNotFoundException("The home directory cannot be renamed")
        }
        val parent = file.parentFile ?: throw FileNotFoundException("No parent directory")
        val target = File(parent, cleanName(displayName))
        if (existsNoFollow(target)) {
            throw FileNotFoundException("${target.name} already exists")
        }
        if (!file.renameTo(target)) {
            throw FileNotFoundException("Unable to rename ${file.name}")
        }
        notifyChildren(parent)
        return target.path
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = File(parentDocumentId).toPath().normalize()
        val child = File(documentId).toPath().normalize()
        return child.startsWith(parent)
    }

    override fun getDocumentType(documentId: String): String {
        val file = fileFor(documentId)
        return if (file.isDirectory) Document.MIME_TYPE_DIR else mimeOf(file.name)
    }

    // ------------------------------------------------------------------ helpers

    private fun include(cursor: MatrixCursor, file: File) {
        val directory = file.isDirectory
        var flags = 0
        if (directory) {
            if (file.canWrite()) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file != base) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.path)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, if (directory) Document.MIME_TYPE_DIR else mimeOf(file.name))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    // The path is only normalised lexically, so symbolic links inside the home
    // directory (for example ~/storage/shared) stay reachable.
    private fun fileFor(documentId: String): File {
        val file = File(documentId).toPath().normalize().toFile()
        if (!file.toPath().startsWith(base.toPath())) {
            throw FileNotFoundException("$documentId is outside the home directory")
        }
        if (!existsNoFollow(file)) {
            throw FileNotFoundException("$documentId does not exist")
        }
        return file
    }

    private fun cleanName(displayName: String): String {
        val name = displayName.replace('/', '_').trim()
        if (name.isEmpty() || name == "." || name == "..") {
            throw FileNotFoundException("Invalid file name")
        }
        return name
    }

    private fun uniqueFile(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!existsNoFollow(candidate)) {
            return candidate
        }
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (existsNoFollow(candidate)) {
            candidate = File(parent, "$stem ($index)$extension")
            index++
        }
        return candidate
    }

    private fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) {
            return "application/octet-stream"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun notifyChildren(parent: File) {
        val ctx = context ?: return
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, parent.path),
            null
        )
    }

    private fun existsNoFollow(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (e: ErrnoException) {
        false
    }

    // Deletes a tree without following symbolic links.
    private fun deleteTree(file: File) {
        val mode = try {
            Os.lstat(file.path).st_mode
        } catch (e: ErrnoException) {
            return
        }
        if (OsConstants.S_ISDIR(mode)) {
            file.list()?.forEach { deleteTree(File(file, it)) }
        }
        file.delete()
    }

    private companion object {
        const val ROOT_ID = "root"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }
}