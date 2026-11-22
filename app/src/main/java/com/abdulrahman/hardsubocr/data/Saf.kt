package com.abdulrahman.hardsubocr.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** A folder reachable through a persisted Storage Access Framework tree grant. */
data class FolderRef(val treeUri: Uri, val docId: String, val name: String) {
    val documentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun serialize(): String = listOf(treeUri.toString(), docId, name).joinToString("\u001F")

    companion object {
        fun parse(s: String?): FolderRef? {
            if (s.isNullOrBlank()) return null
            val p = s.split("\u001F")
            if (p.size < 3) return null
            return FolderRef(Uri.parse(p[0]), p[1], p[2])
        }
    }
}

data class DocEntry(val uri: Uri, val docId: String, val name: String, val mime: String, val size: Long, val modified: Long) {
    val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

object Saf {
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp")
    const val SRT_MIME = "application/x-subrip"

    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

    fun persist(context: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Write may not be offered for some providers; keep at least read access.
            if (write) runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun folderFromTree(context: Context, treeUri: Uri): FolderRef {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val name = queryName(context.contentResolver, docUri) ?: docId.substringAfterLast(':').substringAfterLast('/')
        return FolderRef(treeUri, docId, name.ifBlank { "مجلد" })
    }

    fun displayName(context: Context, uri: Uri): String =
        queryName(context.contentResolver, uri) ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")

    fun size(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun queryName(cr: ContentResolver, uri: Uri): String? = try {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** One query for the whole folder (DocumentFile would issue one query per file). */
    fun listChildren(context: Context, folder: FolderRef): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.treeUri, folder.docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<DocEntry>()
        context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                val mod = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(DocEntry(DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, id), id, name, mime, size, mod))
            }
        }
        return out
    }

    fun listImages(context: Context, folder: FolderRef): List<DocEntry> =
        listChildren(context, folder).filter { !it.isDir && (isImageName(it.name)) }.sortedBy { it.name }

    /** Sub folders of [parent] that directly contain images (plus [parent] itself if it does). */
    fun imageFolders(context: Context, parent: FolderRef): List<Pair<FolderRef, Int>> {
        val res = ArrayList<Pair<FolderRef, Int>>()
        val children = listChildren(context, parent)
        val own = children.count { !it.isDir && isImageName(it.name) }
        if (own > 0) res.add(parent to own)
        for (d in children.filter { it.isDir }.sortedBy { it.name.lowercase() }) {
            val f = FolderRef(parent.treeUri, d.docId, d.name)
            val n = try {
                listChildren(context, f).count { !it.isDir && isImageName(it.name) }
            } catch (e: Exception) {
                0
            }
            if (n > 0) res.add(f to n)
        }
        return res
    }

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("تعذر فتح $uri")

    /** Writes a file into [folder]. Existing file with the same name is replaced or a new name is picked. */
    fun writeFile(context: Context, folder: FolderRef, name: String, mime: String, data: ByteArray, overwrite: Boolean): Uri {
        val cr = context.contentResolver
        val existing = listChildren(context, folder).filter { !it.isDir }
        var finalName = name
        val hit = existing.firstOrNull { it.name == name }
        if (hit != null && overwrite) {
            if (writeTo(cr, hit.uri, data)) return hit.uri
            // provider refused truncation: delete and recreate
            runCatching { DocumentsContract.deleteDocument(cr, hit.uri) }
        } else if (hit != null) {
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var i = 2
            val names = existing.map { it.name }.toSet()
            while (true) {
                val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
                if (candidate !in names) {
                    finalName = candidate; break
                }
                i++
            }
        }
        val created = DocumentsContract.createDocument(cr, folder.documentUri, mime, finalName)
            ?: throw FileNotFoundException("تعذر إنشاء $finalName")
        if (!writeTo(cr, created, data)) throw FileNotFoundException("تعذر الكتابة في $finalName")
        return created
    }

    /** Replaces the content of an existing document. */
    fun overwrite(context: Context, uri: Uri, data: ByteArray): Boolean = writeTo(context.contentResolver, uri, data)

    private fun writeTo(cr: ContentResolver, uri: Uri, data: ByteArray): Boolean {
        for (mode in arrayOf("wt", "w")) {
            try {
                cr.openOutputStream(uri, mode)?.use { it.write(data); it.flush() } ?: continue
                return true
            } catch (e: Exception) {
                // try next mode
            }
        }
        return false
    }

    /** Finds or creates a sub folder (used to export the cropped subtitle frames of a video). */
    fun subFolder(context: Context, parent: FolderRef, name: String): FolderRef {
        listChildren(context, parent).firstOrNull { it.isDir && it.name == name }?.let {
            return FolderRef(parent.treeUri, it.docId, it.name)
        }
        val uri = DocumentsContract.createDocument(
            context.contentResolver, parent.documentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw FileNotFoundException("تعذر إنشاء المجلد $name")
        return FolderRef(parent.treeUri, DocumentsContract.getDocumentId(uri), name)
    }
}
