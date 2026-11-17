package de.freal.toleo.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun drmToString(v: DrmStatus) = v.name
    @TypeConverter fun stringToDrm(v: String) = runCatching { DrmStatus.valueOf(v) }.getOrDefault(DrmStatus.UNKNOWN)
    @TypeConverter fun typeToString(v: AnnotationType) = v.name
    @TypeConverter fun stringToType(v: String) = AnnotationType.valueOf(v)
}

@Database(
    entities = [BookEntity::class, PositionEntity::class, AnnotationEntity::class, TagEntity::class, SyncStateEntity::class],
    version = 1,
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
            Room.databaseBuilder(context, ToleoDatabase::class.java, "toleo.db").build()
    }
}
