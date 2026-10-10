package __APP_ID__.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt ASC, id ASC")
    suspend fun queued(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED','DOWNLOADING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED','DOWNLOADING')")
    suspend fun activeCount(): Int

    /**
     * Process death / reboot recovery: a row still marked DOWNLOADING cannot have a live process any
     * more. It goes back to the queue and resumes from its partial files.
     */
    @Query("UPDATE downloads SET state = 'QUEUED', speedBps = 0, etaSeconds = -1 WHERE state = 'DOWNLOADING'")
    suspend fun requeueInterrupted(): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}
