package com.philkes.notallyx.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Format: `#RRGGBB` or `#AARRGGBB` or [BaseNote.COLOR_DEFAULT] */
typealias ColorString = String

/**
 * Index tuning (Phase 8): the legacy composite index leads with `id` (the rowid alias), so SQLite
 * can never use it to satisfy `WHERE folder = ...` lookups — every list query degenerates into a
 * full table scan + sort. The two indices below cover the dominant query shapes:
 * - (folder, pinned, timestamp): main/archived/deleted list queries (`WHERE folder = ? ORDER BY
 *   pinned DESC, timestamp DESC`) and keyword-search variants.
 * - (folder, modifiedTimestamp): deleted-note auto-removal (`WHERE folder = 'DELETED' AND
 *   modifiedTimestamp < :before`). The legacy index is kept (additive-only migration; it still
 *   serves `WHERE id IN (...)` lookups).
 */
@Entity(
    indices =
        [
            Index(value = ["id", "folder", "pinned", "timestamp", "labels"]),
            Index(value = ["folder", "pinned", "timestamp"]),
            Index(value = ["folder", "modifiedTimestamp"]),
        ]
)
data class BaseNote(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: Type,
    val folder: Folder,
    val color: ColorString,
    val title: String,
    val pinned: Boolean,
    val timestamp: Long,
    val modifiedTimestamp: Long,
    val labels: List<String>,
    val body: String,
    val spans: List<SpanRepresentation>,
    val items: List<ListItem>,
    val images: List<FileAttachment>,
    val files: List<FileAttachment>,
    val audios: List<Audio>,
    val reminders: List<Reminder>,
    val viewMode: NoteViewMode,
    val isPinnedToStatus: Boolean,

    /**
     * Global sync identity (Phase 7 remediation, C1): a UUID generated lazily at first sync and
     * stable across devices. Local autoincrement `id` values collide between devices, so sync keys
     * remote files by this UUID (`note-<syncId>.json`) instead of `id`. Nullable because existing
     * rows are backfilled lazily by the sync engine. Not part of [equals]/[hashCode] (content
     * equality is unchanged).
     */
    val syncId: String? = null,
) : Item {

    companion object {
        const val COLOR_DEFAULT = "DEFAULT"
        const val COLOR_NEW = "NEW"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseNote

        if (id != other.id) return false
        if (type != other.type) return false
        if (folder != other.folder) return false
        if (color != other.color) return false
        if (title != other.title) return false
        if (pinned != other.pinned) return false
        if (timestamp != other.timestamp) return false
        if (labels != other.labels) return false
        if (body != other.body) return false
        if (spans != other.spans) return false
        if (items != other.items) return false
        if (images != other.images) return false
        if (files != other.files) return false
        if (audios != other.audios) return false
        if (reminders != other.reminders) return false
        if (viewMode != other.viewMode) return false
        if (isPinnedToStatus != other.isPinnedToStatus) return false

        return true
    }

    fun equalContents(other: BaseNote?): Boolean {
        if (other == null) return false
        return type == other.type &&
            title == other.title &&
            body == other.body &&
            spans == other.spans &&
            items == other.items
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + folder.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + pinned.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + labels.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + spans.hashCode()
        result = 31 * result + items.hashCode()
        result = 31 * result + images.hashCode()
        result = 31 * result + files.hashCode()
        result = 31 * result + audios.hashCode()
        result = 31 * result + reminders.hashCode()
        result = 31 * result + viewMode.hashCode()
        result = 31 * result + isPinnedToStatus.hashCode()
        return result
    }
}

fun BaseNote.deepCopy(): BaseNote {
    return copy(
        labels = labels.toMutableList(),
        spans = spans.map { it.copy() }.toMutableList(),
        items = items.map { it.copy() }.toMutableList(),
        images = images.map { it.copy() }.toMutableList(),
        files = files.map { it.copy() }.toMutableList(),
        audios = audios.map { it.copy() }.toMutableList(),
        reminders = reminders.map { it.copy() }.toMutableList(),
    )
}
