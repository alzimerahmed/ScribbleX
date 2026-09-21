package com.philkes.notallyx.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.philkes.notallyx.data.dao.BaseNoteDao
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Reminder
import com.philkes.notallyx.data.model.SpanRepresentation
import com.philkes.notallyx.data.model.Type
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end tests for the FTS-backed keyword search ([BaseNoteDao.searchNotes]) against a real
 * in-memory Room database, including ranking, folder filtering and the fallback path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FtsSearchDaoTest {

    private lateinit var database: NotallyDatabase
    private lateinit var baseNoteDao: BaseNoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, NotallyDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        baseNoteDao = database.getBaseNoteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun note(
        id: Long,
        title: String = "",
        body: String = "",
        folder: Folder = Folder.NOTES,
        pinned: Boolean = false,
        modifiedTimestamp: Long = 0,
        labels: List<String> = emptyList(),
    ) =
        BaseNote(
            id = id,
            type = Type.NOTE,
            folder = folder,
            color = "DEFAULT",
            title = title,
            pinned = pinned,
            timestamp = 0,
            modifiedTimestamp = modifiedTimestamp,
            labels = labels,
            body = body,
            spans = emptyList<SpanRepresentation>(),
            items = emptyList(),
            images = emptyList<FileAttachment>(),
            files = emptyList<FileAttachment>(),
            audios = emptyList<Audio>(),
            reminders = emptyList<Reminder>(),
            viewMode = NoteViewMode.EDIT,
            isPinnedToStatus = false,
        )

    private suspend fun seed() {
        baseNoteDao.insert(
            note(id = 1, title = "Milk", body = "buy milk and honey", modifiedTimestamp = 10)
        )
        baseNoteDao.insert(
            note(id = 2, body = "the milkman cometh at midnight", modifiedTimestamp = 20)
        )
        baseNoteDao.insert(note(id = 3, body = "unrelated", folder = Folder.DELETED))
        baseNoteDao.insert(note(id = 4, body = "nothing relevant", labels = listOf("milk")))
    }

    @Test
    fun search_matchesWholeWordsAcrossTitleBodyAndLabels() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("milk", Folder.NOTES, "").first()
        // FTS matches the word in bodies; label-only note found via label fallback
        assertThat(results.map { it.id }).containsExactlyInAnyOrder(1L, 2L, 4L)
    }

    @Test
    fun search_prefixMatches() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("midn", Folder.NOTES, "").first()
        assertThat(results.map { it.id }).containsExactly(2L)
    }

    @Test
    fun search_titleMatchRanksAboveBodyMatch() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("milk", Folder.NOTES, "").first()
        // Note 1 has "milk" in the title (higher weight) and is ranked first despite older
        // modifiedTimestamp than note 2
        assertThat(results.first().id).isEqualTo(1L)
    }

    @Test
    fun search_filtersByFolder() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("unrelated", Folder.DELETED, "").first()
        assertThat(results.map { it.id }).containsExactly(3L)
    }

    @Test
    fun search_unlabeledFilterExcludesLabeledNotes() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("milk", Folder.NOTES, null).first()
        assertThat(results.map { it.id }).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun search_blankKeywordFallsBackToListingAllNotes() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("", Folder.NOTES, "").first()
        assertThat(results.map { it.id }).containsExactly(1L, 2L, 4L)
    }

    @Test
    fun search_ftsSyntaxInjectionDoesNotCrash() = runTest {
        seed()
        val results = baseNoteDao.searchNotes("\" OR NOT NULL --", Folder.NOTES, "").first()
        assertThat(results).isEmpty()
    }

    @Test
    fun search_indexStaysInSyncAfterInsert() = runTest {
        seed()
        baseNoteDao.insert(note(id = 5, body = "freshly inserted pineapple"))
        val results = baseNoteDao.searchNotes("pineapple", Folder.NOTES, "").first()
        assertThat(results.map { it.id }).containsExactly(5L)
    }
}
