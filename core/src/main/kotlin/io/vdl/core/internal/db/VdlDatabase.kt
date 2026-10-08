package io.vdl.core.internal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadTaskEntity::class], version = 2, exportSchema = true)
internal abstract class VdlDatabase : RoomDatabase() {

    internal abstract fun taskDao(): DownloadTaskDao

    internal companion object {
        private const val NAME = "vdl_tasks.db"

        /** v1 -> v2: task kind (DIRECT/HLS) + HLS maxHeight cap. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN kind TEXT NOT NULL DEFAULT 'DIRECT'"
                )
                db.execSQL("ALTER TABLE tasks ADD COLUMN maxHeight INTEGER")
            }
        }

        internal fun build(context: Context): VdlDatabase =
            Room.databaseBuilder(context.applicationContext, VdlDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
