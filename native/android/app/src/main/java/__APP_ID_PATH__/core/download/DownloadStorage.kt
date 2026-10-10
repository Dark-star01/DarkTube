package __APP_ID__.core.download

import __APP_ID__.core.log.AppLog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** A file that has been moved to its final, user-visible location. */
data class PublishedFile(val uri: String, val displayName: String, val mimeType: String, val sizeBytes: Long)

/**
 * Scoped storage only (no MANAGE_EXTERNAL_STORAGE, no legacy storage permission).
 *  - While downloading: app-private work directory per job.
 *  - When finished: copied to the user's chosen folder (Storage Access Framework tree) or, by default,
 *    Downloads/DarkTube through MediaStore. Names come from [NamePolicy]; an existing file is never
 *    overwritten.
 *  - Android 9 and older (no MediaStore.Downloads): app-specific external folder.
 */
class DownloadStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("darktube", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    // ───────────── work area ─────────────

    private val workRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").also { it.mkdirs() }

    fun workDir(id: Long): File = File(workRoot, id.toString()).also { it.mkdirs() }

    fun cleanWork(id: Long) {
        File(workRoot, id.toString()).deleteRecursively()
    }

    fun workFiles(id: Long): Map<String, Long> =
        File(workRoot, id.toString()).listFiles()?.filter { it.isFile }?.associate { it.name to it.length() } ?: emptyMap()

    fun workBytes(id: Long): Long = workFiles(id).values.sum()

    /** Space left for both the work copy and the final copy. */
    fun freeBytes(): Long = try {
        StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    // ───────────── destination ─────────────

    var customTreeUri: String?
        get() = prefs.getString(KEY_TREE, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY_TREE) else putString(KEY_TREE, value) }.apply() }

    fun destinationDescription(): String {
        val tree = customTreeUri
        if (tree != null) {
            val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            if (doc != null && doc.canWrite()) return doc.name ?: "Chosen folder"
        }
        return if (Build.VERSION.SDK_INT >= 29) "Downloads/DarkTube" else "App storage / Download / DarkTube"
    }

    /** True if the chosen folder is still writable (permission can be revoked by the user or system). */
    private fun customFolder(): DocumentFile? {
        val tree = customTreeUri ?: return null
        val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
        return if (doc != null && doc.exists() && doc.canWrite()) doc else {
            AppLog.w("Download", "chosen folder is no longer writable; using the default location")
            null
        }
    }

    /**
     * Moves [source] to its final place under a collision-free name derived from [baseName].
     * [source] is deleted only after the copy succeeded.
     */
    @Throws(DownloadException::class)
    fun publish(baseName: String, extension: String, source: File, mimeType: String): PublishedFile {
        val size = source.length()
        if (size <= 0) throw DownloadException(DownloadErrorKind.STORAGE_FAILURE, "empty source ${source.name}")
        try {
            val folder = customFolder()
            val result = when {
                folder != null -> publishToTree(folder, baseName, extension, source, mimeType)
                Build.VERSION.SDK_INT >= 29 -> publishToMediaStore(baseName, extension, source, mimeType)
                else -> publishToAppFolder(baseName, extension, source, mimeType)
            }
            source.delete()
            return result
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            val full = e.message?.contains("ENOSPC", true) == true || e.message?.contains("No space", true) == true
            throw DownloadException(if (full) DownloadErrorKind.STORAGE_FULL else DownloadErrorKind.STORAGE_FAILURE, "publish: ${e.message}", e)
        } catch (e: SecurityException) {
            throw DownloadException(DownloadErrorKind.STORAGE_FAILURE, "publish denied: ${e.message}", e)
        }
    }

    private fun publishToMediaStore(base: String, ext: String, source: File, mime: String): PublishedFile {
        val relative = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = NamePolicy.uniqueFileName(base, ext) { existsInMediaStore(collection, relative, it) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert returned null")
        try {
            copy(source, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // never leave a half-written entry behind
            throw e
        }
        return PublishedFile(uri.toString(), displayNameOf(uri) ?: name, mime, source.length())
    }

    private fun existsInMediaStore(collection: Uri, relative: String, name: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { it.moveToFirst() } ?: false

    private fun publishToTree(folder: DocumentFile, base: String, ext: String, source: File, mime: String): PublishedFile {
        val name = NamePolicy.uniqueFileName(base, ext) { folder.findFile(it) != null }
        val doc = folder.createFile(mime, name) ?: throw IOException("could not create $name in chosen folder")
        try {
            copy(source, doc.uri)
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        return PublishedFile(doc.uri.toString(), doc.name ?: name, mime, source.length())
    }

    private fun publishToAppFolder(base: String, ext: String, source: File, mime: String): PublishedFile {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER).also { it.mkdirs() }
        val name = NamePolicy.uniqueFileName(base, ext) { File(dir, it).exists() }
        val target = File(dir, name)
        source.copyTo(target, overwrite = false)
        return PublishedFile(Uri.fromFile(target).toString(), name, mime, target.length())
    }

    private fun copy(source: File, target: Uri) {
        val out = resolver.openOutputStream(target, "w") ?: throw IOException("cannot open $target")
        out.use { o -> source.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ───────────── existing files ─────────────

    fun exists(uriString: String?): Boolean {
        if (uriString == null) return false
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") File(uri.path ?: return false).exists()
            else resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun delete(uriString: String?): Boolean {
        if (uriString == null) return true
        val uri = Uri.parse(uriString)
        return try {
            when {
                uri.scheme == "file" -> File(uri.path ?: return true).delete()
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(resolver, uri)
                else -> resolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            AppLog.w("Download", "delete failed: ${e.message}")
            !exists(uriString)
        }
    }

    companion object {
        const val FOLDER = "DarkTube"
        private const val KEY_TREE = "download_tree_uri"
    }
}
