package io.vdl.core.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DownloadTaskEntity::class], version = 1, exportSchema = true)
internal abstract class VdlDatabase : RoomDatabase() {

    internal abstract fun taskDao(): DownloadTaskDao

    internal companion object {
        private const val NAME = "vdl_tasks.db"

        internal fun build(context: Context): VdlDatabase =
            Room.databaseBuilder(context.applicationContext, VdlDatabase::class.java, NAME)
                .build()
    }
}
