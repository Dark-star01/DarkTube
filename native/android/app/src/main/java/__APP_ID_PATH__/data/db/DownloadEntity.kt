package __APP_ID__.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One download job. Metadata only: no media bytes ever go into the database; the files live in
 * app-private work storage while downloading and in MediaStore / the chosen folder when done.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    /** DownloadKind name. */
    val kind: String,
    /** Requested exact height, or null for Best. */
    val requestedHeight: Int?,
    /** Display label: "Best", "1080p", "Audio only", "Subtitles". */
    val qualityLabel: String,
    /** Requested audio language tag, null = original. */
    val audioLanguage: String?,
    /** AudioChoice.AudioKind name. */
    val audioKind: String,
    val audioLabel: String,
    /** SubtitleSpec.encode(...) */
    val subtitleSpecs: String,
    val subtitleFormat: String,
    /** DownloadState name. */
    val state: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = -1,
    val totalBytes: Long = -1,
    val speedBps: Long = 0,
    val etaSeconds: Long = -1,
    val attempts: Int = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** DownloadErrorKind name + friendly text. Technical detail is never stored. */
    val errorKind: String? = null,
    val errorMessage: String? = null,
    /** Non-fatal remark, e.g. "Subtitles (fr) were not available." */
    val note: String? = null,
    /** content:// (MediaStore/SAF) URI of the finished media file. */
    val destinationUri: String? = null,
    val destinationName: String? = null,
    val mimeType: String? = null,
    /** Finished subtitle files, one "lang|uri|name" per line. */
    val subtitleFiles: String? = null,
    val resolvedHeight: Int? = null,
    val containerExt: String? = null,
)
