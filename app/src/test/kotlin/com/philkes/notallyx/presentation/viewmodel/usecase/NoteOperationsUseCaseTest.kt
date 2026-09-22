package com.philkes.notallyx.presentation.viewmodel.usecase

import android.app.Application
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.data.repository.FakeAttachmentRepository
import com.philkes.notallyx.data.repository.FakeLabelRepository
import com.philkes.notallyx.data.repository.FakeNoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [NoteOperationsUseCase] (F2) using fake repositories — no Room/Android DB
 * required. Verifies the behavior moved verbatim out of BaseNoteModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteOperationsUseCaseTest {

    private data class Fixture(
        val useCase: NoteOperationsUseCase,
        val noteRepository: FakeNoteRepository,
        val attachmentRepository: FakeAttachmentRepository,
    )

    private fun createFixture(scope: CoroutineScope): Fixture {
        val noteRepository = FakeNoteRepository()
        val attachmentRepository = FakeAttachmentRepository()
        val useCase =
            NoteOperationsUseCase(
                RuntimeEnvironment.getApplication() as Application,
                scope,
                noteRepository,
                attachmentRepository,
                FakeLabelRepository(),
            )
        return Fixture(useCase, noteRepository, attachmentRepository)
    }

    @Test
    fun `duplicateNotes inserts copies with copy title and reset id`() = runTest {
        val fixture = createFixture(this)
        val original = createNote(title = "Shopping")
        val ids = fixture.useCase.duplicateNotes(listOf(original))
        val copy = fixture.noteRepository.notes[ids.single()]!!
        assertTrue(copy.id != 0L && copy.id != original.id)
        assertTrue(copy.title.startsWith("Shopping ("))
        assertTrue(copy.title.endsWith("Copy)"))
        assertEquals(original.body, copy.body)
    }

    @Test
    fun `deleteBaseNotes removes notes and returns them`() = runTest {
        val fixture = createFixture(this)
        val ids = fixture.noteRepository.insert(listOf(createNote(), createNote()))
        val deleted = fixture.useCase.deleteBaseNotes(ids.toLongArray())
        assertEquals(2, deleted.size)
        assertTrue(fixture.noteRepository.notes.isEmpty())
        // attachment deletion runs in a separate launched coroutine on Dispatchers.IO — poll
        awaitUntil { fixture.attachmentRepository.deletedNotes.size == 1 }
        assertEquals(1, fixture.attachmentRepository.deletedNotes.size)
    }

    @Test
    fun `deleteAllTrashedBaseNotes deletes only trashed notes`() = runTest {
        val fixture = createFixture(this)
        fixture.noteRepository.insert(listOf(createNote(folder = Folder.DELETED), createNote()))
        fixture.useCase.deleteAllTrashedBaseNotes()
        assertEquals(1, fixture.noteRepository.notes.size)
        assertEquals(Folder.NOTES, fixture.noteRepository.notes.values.single().folder)
        assertTrue(fixture.attachmentRepository.deletedAttachments.isNotEmpty())
    }

    @Test
    fun `pinBaseNotes pins selected notes`() = runTest {
        val fixture = createFixture(backgroundScope)
        val ids = fixture.noteRepository.insert(listOf(createNote(), createNote()))
        fixture.useCase.pinBaseNotes(ids.toLongArray(), true)
        // launched on Dispatchers.IO — advanceUntilIdle does not cover real IO threads, poll
        awaitUntil { fixture.noteRepository.notes.values.all { it.pinned } }
        assertTrue(fixture.noteRepository.notes.values.all { it.pinned })
    }

    private companion object {

        /**
         * Polls [condition] until true or timeout. Runs on Dispatchers.IO so the test dispatcher's
         * scheduler can process queued coroutines while we wait.
         */
        suspend fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) =
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!condition()) {
                    check(System.currentTimeMillis() < deadline) {
                        "Condition not met within timeout"
                    }
                    Thread.sleep(25)
                }
            }

        fun createNote(title: String = "Title", folder: Folder = Folder.NOTES): BaseNote =
            BaseNote(
                id = 0L,
                type = Type.NOTE,
                folder = folder,
                color = BaseNote.COLOR_DEFAULT,
                title = title,
                pinned = false,
                timestamp = 0L,
                modifiedTimestamp = 0L,
                labels = emptyList(),
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
    }
}
