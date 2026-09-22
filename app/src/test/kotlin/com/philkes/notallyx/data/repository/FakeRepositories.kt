package com.philkes.notallyx.data.repository

import com.philkes.notallyx.data.dao.NoteIdReminder
import com.philkes.notallyx.data.imports.ImportResult
import com.philkes.notallyx.data.model.Attachment
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Label
import com.philkes.notallyx.data.model.Reminder

/** In-memory fake of [NoteRepository] for unit tests (F2 seam). */
class FakeNoteRepository : NoteRepository {

    val notes = LinkedHashMap<Long, BaseNote>()
    var nextId = 1L

    override fun getFrom(folder: Folder) = throw UnsupportedOperationException()

    override fun getAllAsync() = throw UnsupportedOperationException()

    override fun getAllRemindersAsync() = throw UnsupportedOperationException()

    override fun getAllBaseNotesWithReminders() = throw UnsupportedOperationException()

    override fun getBaseNotesByLabel(label: String) = throw UnsupportedOperationException()

    override fun getBaseNotesWithoutLabel(folder: Folder) = throw UnsupportedOperationException()

    override fun searchNotes(keyword: String, folder: Folder, label: String?) =
        throw UnsupportedOperationException()

    override fun get(id: Long): BaseNote? = notes[id]

    override fun getByIds(ids: LongArray): List<BaseNote> = ids.mapNotNull { notes[it] }

    override suspend fun getAllIds(): List<Long> = notes.keys.toList()

    override suspend fun getAll(): List<BaseNote> = notes.values.toList()

    override suspend fun getAllReminders(): List<NoteIdReminder> =
        notes.values
            .filter { it.reminders.isNotEmpty() }
            .map { NoteIdReminder(it.id, it.reminders) }

    override suspend fun getDeletedNoteIds(): LongArray =
        notes.values.filter { it.folder == Folder.DELETED }.map { it.id }.toLongArray()

    override suspend fun getDeletedNoteImages(): List<String> = emptyList()

    override suspend fun getDeletedNoteFiles(): List<String> = emptyList()

    override suspend fun getDeletedNoteAudios(): List<String> = emptyList()

    override suspend fun insert(baseNotes: List<BaseNote>): List<Long> =
        baseNotes.map { note ->
            val id = if (note.id == 0L) nextId++ else note.id
            notes[id] = note.copy(id = id)
            id
        }

    override suspend fun insertSafe(context: android.content.ContextWrapper, baseNote: BaseNote) =
        insert(listOf(baseNote)).first()

    override suspend fun delete(id: Long) {
        notes.remove(id)
    }

    override suspend fun delete(ids: LongArray) {
        ids.forEach { notes.remove(it) }
    }

    override suspend fun deleteFrom(folder: Folder) {
        notes.entries.removeIf { it.value.folder == folder }
    }

    override suspend fun updatePinned(ids: LongArray, pinned: Boolean) {
        updateByIds(ids) { it.copy(pinned = pinned) }
    }

    override fun updatePinnedToStatus(ids: LongArray, isPinnedToStatus: Boolean) {
        updateByIds(ids) { it.copy(isPinnedToStatus = isPinnedToStatus) }
    }

    override suspend fun updateColor(ids: LongArray, color: String) {
        updateByIds(ids) { it.copy(color = color) }
    }

    override suspend fun updateColor(oldColor: String, newColor: String) {
        notes.keys.toList().forEach { id ->
            val note = notes[id]!!
            if (note.color == oldColor) notes[id] = note.copy(color = newColor)
        }
    }

    override suspend fun updateLabels(id: Long, labels: List<String>) {
        notes[id]?.let { notes[id] = it.copy(labels = labels) }
    }

    override suspend fun updateImages(id: Long, images: List<FileAttachment>) {
        notes[id]?.let { notes[id] = it.copy(images = images) }
    }

    override suspend fun updateFiles(id: Long, files: List<FileAttachment>) {
        notes[id]?.let { notes[id] = it.copy(files = files) }
    }

    override suspend fun updateAudios(id: Long, audios: List<Audio>) {
        notes[id]?.let { notes[id] = it.copy(audios = audios) }
    }

    override suspend fun updateReminders(id: Long, reminders: List<Reminder>) {
        notes[id]?.let { notes[id] = it.copy(reminders = reminders) }
    }

    override suspend fun updateAll(baseNotes: List<BaseNote>) {
        baseNotes.forEach { notes[it.id] = it }
    }

    override suspend fun getTombstones(): List<com.philkes.notallyx.data.model.SyncTombstone> =
        emptyList()

    override suspend fun moveBaseNotes(ids: LongArray, folder: Folder) {
        updateByIds(ids) { it.copy(folder = folder) }
    }

    private fun updateByIds(ids: LongArray, transform: (BaseNote) -> BaseNote) {
        ids.forEach { id -> notes[id]?.let { notes[id] = transform(it) } }
    }
}

/** In-memory fake of [LabelRepository] for unit tests (F2 seam). */
class FakeLabelRepository : LabelRepository {

    val labels = LinkedHashMap<String, Int>()

    override fun getAll() = throw UnsupportedOperationException()

    override suspend fun getArrayOfAll(): Array<String> = labels.keys.toTypedArray()

    override suspend fun exists(value: String): Boolean = labels.containsKey(value)

    override suspend fun getMaxOrder(): Int? = labels.values.maxOrNull()

    override suspend fun insert(label: Label) {
        labels.putIfAbsent(label.value, label.order)
    }

    override suspend fun update(labels: List<Label>) {
        labels.forEach { this.labels[it.value] = it.order }
    }

    override suspend fun delete(value: String) {
        labels.remove(value)
    }

    override suspend fun deleteAll() = labels.clear()

    override suspend fun deleteLabel(value: String) = delete(value)

    override suspend fun updateLabel(oldValue: String, newValue: String) {
        val order = labels.remove(oldValue)
        if (order != null) {
            labels[newValue] = order
        }
    }

    override suspend fun importBackup(
        baseNotes: List<BaseNote>,
        labels: List<Label>,
        readCorrupted: Int,
        checkDuplicates: Boolean,
    ): ImportResult = ImportResult(baseNotes.size, 0, readCorrupted)
}

/** In-memory fake of [AttachmentRepository] recording calls for unit tests (F2 seam). */
class FakeAttachmentRepository : AttachmentRepository {

    val deletedNotes = mutableListOf<Collection<BaseNote>>()
    val deletedAttachments = mutableListOf<Pair<List<Attachment>, LongArray?>>()
    var migratedToPrivate: Boolean? = null

    override suspend fun deleteAttachments(notes: Collection<BaseNote>) {
        deletedNotes.add(notes)
    }

    override suspend fun deleteAttachments(attachments: Collection<Attachment>, ids: LongArray?) {
        deletedAttachments.add(Pair(attachments.toList(), ids))
    }

    override suspend fun migrateAllAttachments(toPrivate: Boolean) {
        migratedToPrivate = toPrivate
    }
}
