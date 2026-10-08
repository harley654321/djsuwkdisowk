package io.vdl.core.internal.db

import kotlinx.coroutines.flow.Flow

/**
 * Persistence seam. Production uses Room; unit tests use an in-memory fake,
 * which is why the queue never touches Room types directly.
 */
internal interface TaskRepository {
    /** @return false when a task with the same (url, fileName) already exists. */
    suspend fun add(task: DownloadTaskEntity): Boolean

    suspend fun update(task: DownloadTaskEntity)

    suspend fun get(id: String): DownloadTaskEntity?

    suspend fun delete(id: String)

    suspend fun clearCompleted(): Int

    fun observe(id: String): Flow<DownloadTaskEntity?>

    fun observeAll(): Flow<List<DownloadTaskEntity>>

    suspend fun pendingOrdered(): List<DownloadTaskEntity>

    suspend fun runningOrphans(): List<DownloadTaskEntity>

    suspend fun activeTasks(): List<DownloadTaskEntity>
}

internal class RoomTaskRepository internal constructor(
    private val dao: DownloadTaskDao
) : TaskRepository {

    override suspend fun add(task: DownloadTaskEntity): Boolean =
        dao.insert(task) != -1L

    override suspend fun update(task: DownloadTaskEntity) = dao.update(task)

    override suspend fun get(id: String): DownloadTaskEntity? = dao.byId(id)

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun clearCompleted(): Int = dao.deleteCompleted()

    override fun observe(id: String): Flow<DownloadTaskEntity?> = dao.observeById(id)

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = dao.observeAll()

    override suspend fun pendingOrdered(): List<DownloadTaskEntity> = dao.pendingOrdered()

    override suspend fun runningOrphans(): List<DownloadTaskEntity> = dao.runningOrphans()

    override suspend fun activeTasks(): List<DownloadTaskEntity> = dao.activeTasks()
}
