package io.vdl.core.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface DownloadTaskDao {

    /** Returns -1 when a task with the same (url, fileName) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tasks WHERE state = 'COMPLETED'")
    suspend fun deleteCompleted(): Int

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    /** Pending tasks, highest priority first, oldest first. */
    @Query(
        "SELECT * FROM tasks WHERE state = 'PENDING' " +
            "ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, createdAt ASC"
    )
    suspend fun pendingOrdered(): List<DownloadTaskEntity>

    /** Tasks that were RUNNING when the process died; restored to PENDING at startup. */
    @Query("SELECT * FROM tasks WHERE state = 'RUNNING'")
    suspend fun runningOrphans(): List<DownloadTaskEntity>

    /** Everything that is not terminal, for cancelAll(). */
    @Query("SELECT * FROM tasks WHERE state NOT IN ('COMPLETED', 'CANCELLED')")
    suspend fun activeTasks(): List<DownloadTaskEntity>
}
