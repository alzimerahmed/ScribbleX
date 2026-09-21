package com.philkes.notallyx.data.repository

import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the F2 repository seams using in-memory fakes. These verify the fake-able DAO
 * seams behave like the Room-backed implementations for the operations used by the ViewModels.
 */
class NoteRepositoryFakeTest {

    private fun note(
        id: Long = 0L,
        title: String = "Title",
        folder: Folder = Folder.NOTES,
        pinned: Boolean = false,
        color: String = BaseNote.COLOR_DEFAULT,
        labels: List<String> = emptyList(),
    ): BaseNote =
        BaseNote(
            id = id,
            type = com.philkes.notallyx.data.model.Type.NOTE,
            folder = folder,
            color = color,
            title = title,
            pinned = pinned,
            timestamp = 0L,
            modifiedTimestamp = 0L,
            labels = labels,
            body = "Body",
            spans = emptyList(),
            items = emptyList(),
            images = emptyList(),
            files = emptyList(),
            audios = emptyList(),
            reminders = emptyList(),
            viewMode = NoteViewMode.EDIT,
            isPinnedToStatus = false,
        )

    @Test
    fun `insert assigns new ids`() = runTest {
        val repository = FakeNoteRepository()
        val ids = repository.insert(listOf(note(title = "A"), note(title = "B")))
        assertEquals(listOf(1L, 2L), ids)
        assertEquals(2, repository.notes.size)
    }

    @Test
    fun `delete removes notes by ids`() = runTest {
        val repository = FakeNoteRepository()
        val ids = repository.insert(listOf(note(), note()))
        repository.delete(ids)
        assertTrue(repository.notes.isEmpty())
    }

    @Test
    fun `moveBaseNotes changes folder`() = runTest {
        val repository = FakeNoteRepository()
        val ids = repository.insert(listOf(note()))
        repository.moveBaseNotes(ids, Folder.DELETED)
        assertEquals(Folder.DELETED, repository.notes[ids.first()]?.folder)
    }

    @Test
    fun `updatePinned updates only selected notes`() = runTest {
        val repository = FakeNoteRepository()
        val ids = repository.insert(listOf(note(), note()))
        repository.updatePinned(longArrayOf(ids.first()), true)
        assertTrue(repository.notes[ids.first()]!!.pinned)
        assertFalse(repository.notes[ids.last()]!!.pinned)
    }

    @Test
    fun `getByIds returns only existing notes`() = runTest {
        val repository = FakeNoteRepository()
        val ids = repository.insert(listOf(note(), note()))
        val result = repository.getByIds(longArrayOf(ids.first(), 999L))
        assertEquals(1, result.size)
    }

    @Test
    fun `get returns null for missing note`() = runTest {
        val repository = FakeNoteRepository()
        repository.insert(listOf(note()))
        assertNull(repository.get(42L))
    }

    @Test
    fun `label repository insert exists update delete roundtrip`() = runTest {
        val repository = FakeLabelRepository()
        repository.insert(com.philkes.notallyx.data.model.Label("Work", 0))
        assertTrue(repository.exists("Work"))
        repository.updateLabel("Work", "Important")
        assertFalse(repository.exists("Work"))
        assertTrue(repository.exists("Important"))
        assertEquals("Important", repository.getArrayOfAll().single())
        repository.deleteLabel("Important")
        assertFalse(repository.exists("Important"))
    }
}
