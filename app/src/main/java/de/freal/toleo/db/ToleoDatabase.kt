package de.freal.toleo.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun drmToString(v: DrmStatus) = v.name
    @TypeConverter fun stringToDrm(v: String) = runCatching { DrmStatus.valueOf(v) }.getOrDefault(DrmStatus.UNKNOWN)
    @TypeConverter fun typeToString(v: AnnotationType) = v.name
    @TypeConverter fun stringToType(v: String) = AnnotationType.valueOf(v)
}

@Database(
    entities = [BookEntity::class, PositionEntity::class, AnnotationEntity::class, TagEntity::class, SyncStateEntity::class],
    version = 4,
)
@TypeConverters(Converters::class)
abstract class ToleoDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun positions(): PositionDao
    abstract fun annotations(): AnnotationDao
    abstract fun tags(): TagDao
    abstract fun syncState(): SyncStateDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, ToleoDatabase::class.java, "toleo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** Adds `book.dismissedAt` for the "Also reading" shelf on the home screen. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN dismissedAt INTEGER")
            }
        }

        /** Adds `book.pinnedAt` for books added to "Also reading" by hand. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN pinnedAt INTEGER")
            }
        }

        /** Adds `book.hidden` for books the user moved out of the Library. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
