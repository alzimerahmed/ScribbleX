package com.philkes.notallyx.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.LiveData
import com.philkes.notallyx.data.dao.BaseNoteDao
import com.philkes.notallyx.data.dao.NoteIdReminder
import com.philkes.notallyx.data.dao.NoteReminder
import com.philkes.notallyx.data.dao.moveBaseNotes
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * Repository seam over [BaseNoteDao] so ViewModels and use-cases can depend on an interface that
 * can be faked in unit tests. Method signatures mirror the underlying DAO 1:1 — this is a
 * behavior-preserving refactor, no logic lives here.
 */
interface NoteRepository {

    fun getFrom(folder: Folder): LiveData<List<BaseNote>>

    fun getAllAsync(): LiveData<List<BaseNote>>

    fun getAllRemindersAsync(): LiveData<List<NoteReminder>>

    fun getAllBaseNotesWithReminders(): LiveData<List<BaseNote>>

    fun getBaseNotesByLabel(label: String): Flow<List<BaseNote>>

    fun getBaseNotesWithoutLabel(folder: Folder): Flow<List<BaseNote>>

    fun searchNotes(keyword: String, folder: Folder, label: String?): Flow<List<BaseNote>>

    fun get(id: Long): BaseNote?

    fun getByIds(ids: LongArray): List<BaseNote>

    suspend fun getAllIds(): List<Long>

    suspend fun getAll(): List<BaseNote>

    suspend fun getAllReminders(): List<NoteIdReminder>

    suspend fun getDeletedNoteIds(): LongArray

    suspend fun getDeletedNoteImages(): List<String>

    suspend fun getDeletedNoteFiles(): List<String>

    suspend fun getDeletedNoteAudios(): List<String>

    suspend fun insert(baseNotes: List<BaseNote>): List<Long>

    suspend fun insertSafe(context: ContextWrapper, baseNote: BaseNote): Long

    suspend fun delete(id: Long)

    suspend fun delete(ids: LongArray)

    suspend fun deleteFrom(folder: Folder)

    suspend fun updateAll(baseNotes: List<BaseNote>)

    suspend fun updatePinned(ids: LongArray, pinned: Boolean)

    fun updatePinnedToStatus(ids: LongArray, isPinnedToStatus: Boolean)

    /**
     * Moves notes to another folder, re-scheduling or cancelling their reminders accordingly (see
     * data/dao/DaoExtensions.kt).
     */
    suspend fun moveBaseNotes(ids: LongArray, folder: Folder)

    suspend fun updateColor(ids: LongArray, color: String)

    suspend fun updateColor(oldColor: String, newColor: String)

    suspend fun updateLabels(id: Long, labels: List<String>)

    suspend fun updateImages(id: Long, images: List<FileAttachment>)

    suspend fun updateFiles(id: Long, files: List<FileAttachment>)

    suspend fun updateAudios(id: Long, audios: List<Audio>)

    suspend fun updateReminders(id: Long, reminders: List<Reminder>)
}

/**
 * Room-backed implementation. The DAO is resolved lazily on every call, because the database
 * instance can be replaced at runtime (encryption toggle, internal/public storage move).
 */
class RoomNoteRepository(
    private val contextProvider: () -> Context,
    private val daoProvider: () -> BaseNoteDao,
) : NoteRepository {

    private val dao: BaseNoteDao
        get() = daoProvider()

    override fun getFrom(folder: Folder): LiveData<List<BaseNote>> = dao.getFrom(folder)

    override fun getAllAsync(): LiveData<List<BaseNote>> = dao.getAllAsync()

    override fun getAllRemindersAsync(): LiveData<List<NoteReminder>> = dao.getAllRemindersAsync()

    override fun getAllBaseNotesWithReminders(): LiveData<List<BaseNote>> =
        dao.getAllBaseNotesWithReminders()

    override fun getBaseNotesByLabel(label: String): Flow<List<BaseNote>> =
        dao.getBaseNotesByLabel(label)

    override fun getBaseNotesWithoutLabel(folder: Folder): Flow<List<BaseNote>> =
        dao.getBaseNotesWithoutLabel(folder)

    override fun searchNotes(
        keyword: String,
        folder: Folder,
        label: String?,
    ): Flow<List<BaseNote>> = dao.searchNotes(keyword, folder, label)

    override fun get(id: Long): BaseNote? = dao.get(id)

    override fun getByIds(ids: LongArray): List<BaseNote> = dao.getByIds(ids)

    override suspend fun getAllIds(): List<Long> = dao.getAllIds()

    override suspend fun getAll(): List<BaseNote> = dao.getAll()

    override suspend fun getAllReminders(): List<NoteIdReminder> = dao.getAllReminders()

    override suspend fun getDeletedNoteIds(): LongArray = dao.getDeletedNoteIds()

    override suspend fun getDeletedNoteImages(): List<String> = dao.getDeletedNoteImages()

    override suspend fun getDeletedNoteFiles(): List<String> = dao.getDeletedNoteFiles()

    override suspend fun getDeletedNoteAudios(): List<String> = dao.getDeletedNoteAudios()

    override suspend fun insert(baseNotes: List<BaseNote>): List<Long> = dao.insert(baseNotes)

    override suspend fun insertSafe(context: ContextWrapper, baseNote: BaseNote): Long =
        dao.insertSafe(context, baseNote)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun delete(ids: LongArray) = dao.delete(ids)

    override suspend fun deleteFrom(folder: Folder) = dao.deleteFrom(folder)

    override suspend fun updateAll(baseNotes: List<BaseNote>) = dao.updateAll(baseNotes)

    override suspend fun updatePinned(ids: LongArray, pinned: Boolean) =
        dao.updatePinned(ids, pinned)

    override fun updatePinnedToStatus(ids: LongArray, isPinnedToStatus: Boolean) =
        dao.updatePinnedToStatus(ids, isPinnedToStatus)

    override suspend fun moveBaseNotes(ids: LongArray, folder: Folder) =
        contextProvider().moveBaseNotes(dao, ids, folder)

    override suspend fun updateColor(ids: LongArray, color: String) = dao.updateColor(ids, color)

    override suspend fun updateColor(oldColor: String, newColor: String) =
        dao.updateColor(oldColor, newColor)

    override suspend fun updateLabels(id: Long, labels: List<String>) = dao.updateLabels(id, labels)

    override suspend fun updateImages(id: Long, images: List<FileAttachment>) =
        dao.updateImages(id, images)

    override suspend fun updateFiles(id: Long, files: List<FileAttachment>) =
        dao.updateFiles(id, files)

    override suspend fun updateAudios(id: Long, audios: List<Audio>) = dao.updateAudios(id, audios)

    override suspend fun updateReminders(id: Long, reminders: List<Reminder>) =
        dao.updateReminders(id, reminders)
}
