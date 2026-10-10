package __APP_ID__.core.download

import java.io.File

/** What [DownloadManager] needs from file storage. [DownloadStorage] is the Android implementation. */
interface DownloadFiles {
    fun workDir(id: Long): File
    fun cleanWork(id: Long)
    fun workFiles(id: Long): Map<String, Long>
    fun workBytes(id: Long): Long
    fun freeBytes(): Long

    @Throws(DownloadException::class)
    fun publish(baseName: String, extension: String, source: File, mimeType: String): PublishedFile

    fun delete(uriString: String?): Boolean
    fun exists(uriString: String?): Boolean
}

data class PublishedFile(val uri: String, val displayName: String, val mimeType: String, val sizeBytes: Long)
