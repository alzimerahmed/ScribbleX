package com.philkes.notallyx.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Deletion marker for sync (Phase 7 remediation, C2): when a note is permanently deleted locally,
 * its [BaseNote.syncId] is recorded here so the sync engine can (a) delete the remote file and (b)
 * never re-download a tombstoned note from another device. Tombstones are kept forever — they are
 * tiny and purging them would resurrect deleted notes.
 */
@Entity(tableName = "SyncTombstone")
data class SyncTombstone(@PrimaryKey val syncId: String, val deletedTimestamp: Long)
