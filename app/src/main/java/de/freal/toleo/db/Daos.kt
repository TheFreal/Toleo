package de.freal.toleo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """
        SELECT b.publicationId, b.title, b.subtitle, b.authors, COALESCE(NULLIF(b.localCover, ''), b.coverUrl) AS coverUrl, b.format, b.localPath, b.drm,
               b.isUpload, b.inCloud, b.lastOpened, b.sortIndex, b.dismissedAt, b.pinnedAt, b.hidden, p.progress,
               EXISTS(SELECT 1 FROM tag t WHERE t.publicationId = b.publicationId AND t.name = '${TagEntity.FINISHED_NAME}' AND t.deleted = 0) AS finished,
               (SELECT GROUP_CONCAT(t.name, '\u001F') FROM tag t WHERE t.publicationId = b.publicationId AND t.category = '${TagEntity.CATEGORY_COLLECTION}' AND t.deleted = 0) AS collections
        FROM book b LEFT JOIN position p ON p.publicationId = b.publicationId
        """,
    )
    fun observeLibrary(): Flow<List<BookWithState>>

    @Query("SELECT * FROM book WHERE publicationId = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM book")
    suspend fun all(): List<BookEntity>

    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("UPDATE book SET localPath = :path, drm = :drm WHERE publicationId = :id")
    suspend fun setLocal(id: String, path: String?, drm: DrmStatus)

    @Query("UPDATE book SET lastOpened = :time WHERE publicationId = :id")
    suspend fun setLastOpened(id: String, time: Long)

    /** Sets both shelf fields at once so dismissing (which unpins) and undoing it stay consistent. */
    @Query("UPDATE book SET dismissedAt = :dismissedAt, pinnedAt = :pinnedAt WHERE publicationId = :id")
    suspend fun setShelfState(id: String, dismissedAt: Long?, pinnedAt: Long?)

    @Query("UPDATE book SET pinnedAt = :time WHERE publicationId = :id")
    suspend fun setPinnedAt(id: String, time: Long?)

    @Query("UPDATE book SET localCover = :cover WHERE publicationId = :id")
    suspend fun setLocalCover(id: String, cover: String?)

    @Query("UPDATE book SET hidden = :hidden WHERE publicationId = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE book SET inCloud = 0 WHERE publicationId NOT IN (:ids)")
    suspend fun markMissingFromCloud(ids: List<String>)

    @Query("DELETE FROM book WHERE publicationId = :id")
    suspend fun delete(id: String)
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM position WHERE publicationId = :id")
    suspend fun get(id: String): PositionEntity?

    @Upsert
    suspend fun upsert(position: PositionEntity)

    @Query("SELECT * FROM position WHERE dirty = 1")
    suspend fun dirty(): List<PositionEntity>

    /** Re-homes a position when a local book becomes a cloud book; keeps the dirty flag as it was. */
    @Query("UPDATE OR REPLACE position SET publicationId = :to WHERE publicationId = :from")
    suspend fun move(from: String, to: String)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotation WHERE publicationId = :pubId AND deleted = 0 ORDER BY progress, modified")
    fun observe(pubId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotation WHERE id = :id")
    suspend fun get(id: String): AnnotationEntity?

    @Query("SELECT * FROM annotation WHERE publicationId = :pubId AND serverId = :serverId LIMIT 1")
    suspend fun byServerId(pubId: String, serverId: String): AnnotationEntity?

    @Query("SELECT * FROM annotation WHERE transientId = :transientId LIMIT 1")
    suspend fun byTransientId(transientId: String): AnnotationEntity?

    @Query("SELECT * FROM annotation WHERE dirty = 1")
    suspend fun dirty(): List<AnnotationEntity>

    @Upsert
    suspend fun upsert(annotation: AnnotationEntity)

    @Query("DELETE FROM annotation WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** Re-homes highlights and bookmarks when a local book becomes a cloud book. */
    @Query("UPDATE annotation SET publicationId = :to WHERE publicationId = :from")
    suspend fun move(from: String, to: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag WHERE publicationId = :pubId AND serverId = :serverId LIMIT 1")
    suspend fun byServerId(pubId: String, serverId: String): TagEntity?

    @Query("SELECT * FROM tag WHERE transientId = :transientId LIMIT 1")
    suspend fun byTransientId(transientId: String): TagEntity?

    @Query("SELECT * FROM tag WHERE publicationId = :pubId AND name = :name LIMIT 1")
    suspend fun byName(pubId: String, name: String): TagEntity?

    @Query("SELECT * FROM tag WHERE dirty = 1")
    suspend fun dirty(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity): Long

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun hardDelete(id: Long)

    /** Re-homes the finished flag when a local book becomes a cloud book. */
    @Query("UPDATE tag SET publicationId = :to WHERE publicationId = :from")
    suspend fun move(from: String, to: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE account = :account")
    suspend fun get(account: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE account = :account")
    fun observe(account: String): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
