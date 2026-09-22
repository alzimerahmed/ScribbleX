package com.philkes.notallyx.utils.sync

import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.model.toBaseNote
import com.philkes.notallyx.data.model.toJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Unit tests for the last-write-wins merge logic of the sync MVP (Phase 7 F1). */
class SyncMergeLogicTest {

    private fun note(id: Long, modified: Long, title: String = "Note $id") =
        BaseNote(
            id,
            com.philkes.notallyx.data.model.Type.NOTE,
            Folder.NOTES,
            BaseNote.COLOR_DEFAULT,
            title,
            false,
            modified - 1000,
            modified,
            emptyList(),
            "body of $title",
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            NoteViewMode.EDIT,
            false,
        )

    @Test
    fun `local newer wins`() {
        val decision = mergeLastWriteWins(localModified = 2000L, remoteModified = 1000L)
        assertThat(decision).isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `remote newer wins`() {
        val decision = mergeLastWriteWins(localModified = 1000L, remoteModified = 2000L)
        assertThat(decision).isEqualTo(MergeDecision.TakeRemote)
    }

    @Test
    fun `tie keeps local`() {
        val decision = mergeLastWriteWins(localModified = 1000L, remoteModified = 1000L)
        assertThat(decision).isEqualTo(MergeDecision.KeepLocal)
    }

    @Test
    fun `missing local note takes remote`() {
        // localModified of 0 models "note does not exist locally"
        val decision = mergeLastWriteWins(localModified = 0L, remoteModified = 5L)
        assertThat(decision).isEqualTo(MergeDecision.TakeRemote)
    }

    @Test
    fun `note json round trip preserves content and timestamps`() {
        val original = note(42L, 123456789L, "Round trip")
        val restored = original.toJson().toBaseNote()
        // id is DB-assigned, not carried in the JSON payload
        assertThat(restored.title).isEqualTo(original.title)
        assertThat(restored.body).isEqualTo(original.body)
        assertThat(restored.modifiedTimestamp).isEqualTo(original.modifiedTimestamp)
        assertThat(restored.timestamp).isEqualTo(original.timestamp)
        assertThat(restored.type).isEqualTo(original.type)
    }
}
