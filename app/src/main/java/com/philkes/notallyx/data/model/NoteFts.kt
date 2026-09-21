package com.philkes.notallyx.data.model

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 external-content index over [BaseNote] title, body and list items. The rowid of this virtual
 * table mirrors `BaseNote.id`, so `NoteFts.docid` joins directly to `BaseNote.id`.
 *
 * Room keeps this table in sync with the content table via generated triggers on fresh installs;
 * [com.philkes.notallyx.data.NotallyDatabase.Companion.Migration12] recreates the same triggers
 * (and backfills content) for upgrading databases.
 */
@Fts4(contentEntity = BaseNote::class)
@Entity(tableName = "NoteFts")
data class NoteFts(val title: String, val body: String, val items: String) {

    companion object {
        /** Column indices inside FTS `offsets()` output, in declaration order. */
        const val COLUMN_TITLE = 0
        const val COLUMN_BODY = 1
        const val COLUMN_ITEMS = 2
    }
}
