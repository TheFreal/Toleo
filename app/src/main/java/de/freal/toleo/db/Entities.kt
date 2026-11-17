package de.freal.toleo.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DrmStatus { UNKNOWN, NONE, ADOBE, LCP }

@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey val publicationId: String,
    val deliverableId: String?,
    val identifier: String?,
    val resellerId: Int?,
    val title: String,
    val subtitle: String?,
    val authors: String,
    val publisher: String?,
    val language: String?,
    val issued: String?,
    /** "EPUB" | "PDF" | "OTHER" */
    val format: String,
    val purchased: Boolean,
    val isUpload: Boolean,
    val coverUrl: String?,
    val inCloud: Boolean = true,
    val localPath: String? = null,
    val drm: DrmStatus = DrmStatus.UNKNOWN,
    val lastOpened: Long? = null,
    /** Order of appearance in the cloud inventory (newest first is index 0). */
    val sortIndex: Int = 0,
    /** When the book was dismissed from "Recently opened"; it returns once [lastOpened] passes this. */
    val dismissedAt: Long? = null,
    /** When the user added the book to "Also reading" by hand; pinned books stay there until dismissed. */
    val pinnedAt: Long? = null,
    /** Hidden from the Library, the shelf and the current-book panel; listed under "Hidden" instead. Local only. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

/** Reading position: the Tolino `bookmark` item, one per publication. */
@Entity(tableName = "position")
data class PositionEntity(
    @PrimaryKey val publicationId: String,
    val serverId: String? = null,
    val itemRevision: String? = null,
    /** Tolino position string, e.g. `OEBPS/ch1.html#point(/1/4/2:10)`. */
    val tolinoPosition: String? = null,
    val progress: Double = 0.0,
    /** Readium Locator JSON when known precisely. */
    val locatorJson: String? = null,
    val modified: Long = 0,
    val dirty: Boolean = false,
)

enum class AnnotationType(val tolinoType: String) {
    /** Page bookmark. */
    DOGEAR("dogears"),
    /** Highlight, optionally with a note. Tolino stores both as `comments`. */
    HIGHLIGHT("comments"),
}

@Entity(
    tableName = "annotation",
    indices = [Index("publicationId"), Index(value = ["publicationId", "serverId"]), Index("transientId")],
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val publicationId: String,
    val type: AnnotationType,
    val serverId: String? = null,
    val itemRevision: String? = null,
    /** Sent on add so the echoed server item can be matched back to this row. */
    val transientId: String,
    val startPosition: String? = null,
    val endPosition: String? = null,
    /** Highlighted text, or the page excerpt for a dogear. */
    val text: String? = null,
    val note: String? = null,
    val color: Int = DEFAULT_HIGHLIGHT,
    val locatorJson: String? = null,
    val progress: Double? = null,
    val modified: Long,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
) {
    companion object {
        const val DEFAULT_HIGHLIGHT = 0xFFFFD54F.toInt()
    }
}

/** Collection membership and the "finished" flag, both Tolino `tags`. */
@Entity(tableName = "tag", indices = [Index("publicationId")])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val publicationId: String,
    val serverId: String? = null,
    val itemRevision: String? = null,
    val transientId: String? = null,
    val name: String,
    val category: String,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
) {
    companion object {
        const val FINISHED_NAME = "collection_finished_readings_name"
        const val CATEGORY_SYSTEM = "system"
        const val CATEGORY_COLLECTION = "collection"
    }
}

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val account: String,
    /** Top-level revision as raw JSON (string or number, the API isn't consistent). */
    val revisionJson: String?,
    val lastSync: Long,
)

/** Library row: book plus its progress and finished flag. */
data class BookWithState(
    val publicationId: String,
    val title: String,
    val subtitle: String?,
    val authors: String,
    val coverUrl: String?,
    val format: String,
    val localPath: String?,
    val drm: DrmStatus,
    val isUpload: Boolean,
    val inCloud: Boolean,
    val lastOpened: Long?,
    val sortIndex: Int,
    val dismissedAt: Long?,
    val pinnedAt: Long?,
    val hidden: Boolean,
    val progress: Double?,
    val finished: Boolean,
    val collections: String?,
)
