package com.philkes.notallyx.presentation.viewmodel.usecase

import android.app.Application
import com.philkes.notallyx.R
import com.philkes.notallyx.data.model.Attachment
import com.philkes.notallyx.data.model.Audio
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.ConverterErrorReporter
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.deepCopy
import com.philkes.notallyx.data.repository.AttachmentRepository
import com.philkes.notallyx.data.repository.NoteRepository
import com.philkes.notallyx.utils.cancelPinAndReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Use-case extracted from BaseNoteModel (F2): note mutations (pin, color, move, labels, delete,
 * duplicate). Logic moved verbatim from BaseNoteModel — behavior-preserving. The ViewModel keeps
 * ownership of ActionMode state, toasts and preference side-effects.
 */
class NoteOperationsUseCase(
    private val app: Application,
    private val scope: CoroutineScope,
    private val noteRepository: NoteRepository,
    private val attachmentRepository: AttachmentRepository,
    private val labelRepository: LabelRepository,
) {

    fun pinBaseNotes(ids: LongArray, pinned: Boolean) {
        scope.launch(Dispatchers.IO) { noteRepository.updatePinned(ids, pinned) }
    }

    suspend fun updatePinnedToStatus(ids: LongArray, pinnedToStatusBar: Boolean): List<BaseNote> {
        return withContext(Dispatchers.IO) {
            noteRepository.updatePinnedToStatus(ids, pinnedToStatusBar)
            noteRepository.getByIds(ids)
        }
    }

    fun colorBaseNote(ids: LongArray, color: String) {
        scope.launch(Dispatchers.IO) { noteRepository.updateColor(ids, color) }
    }

    fun changeColor(oldColor: String, newColor: String) {
        scope.launch(Dispatchers.IO) { noteRepository.updateColor(oldColor, newColor) }
    }

    suspend fun moveBaseNotes(ids: LongArray, folder: Folder) {
        withContext(Dispatchers.IO) { noteRepository.moveBaseNotes(ids, folder) }
    }

    fun updateBaseNoteLabels(labels: List<String>, id: Long) {
        scope.launch(Dispatchers.IO) { noteRepository.updateLabels(id, labels) }
    }

    /** Deletes the given notes, cancelling their pins/reminders. Returns the deleted notes. */
    suspend fun deleteBaseNotes(ids: LongArray): Collection<BaseNote> {
        val notes = withContext(Dispatchers.IO) { noteRepository.getByIds(ids) }
        app.cancelPinAndReminders(notes)
        return withContext(Dispatchers.IO) {
            noteRepository.delete(ids)
            return@withContext notes
        }
    }

    /** Deletes every note, cancels reminders, removes attachments and all labels. */
    suspend fun deleteAllNotes(): Collection<BaseNote> {
        val (ids, noteReminders) =
            withContext(Dispatchers.IO) {
                Pair(noteRepository.getAllIds().toLongArray(), noteRepository.getAllReminders())
            }
        noteReminders.forEach { app.cancelPinAndReminders(it.id, it.reminders) }
        val deletedNotes = deleteBaseNotes(ids)
        attachmentRepository.deleteAttachments(deletedNotes)
        withContext(Dispatchers.IO) { labelRepository.deleteAll() }
        return deletedNotes
    }

    suspend fun getDeletedNoteIds(): LongArray =
        withContext(Dispatchers.IO) { noteRepository.getDeletedNoteIds() }

    /** Permanently deletes all trashed notes and their attachment files from disk. */
    suspend fun deleteAllTrashedBaseNotes() {
        val ids: LongArray
        val images = ArrayList<FileAttachment>()
        val files = ArrayList<FileAttachment>()
        val audios = ArrayList<Audio>()
        withContext(Dispatchers.IO) {
            ids = noteRepository.getDeletedNoteIds()
            val imageStrings = noteRepository.getDeletedNoteImages()
            val fileStrings = noteRepository.getDeletedNoteFiles()
            val audioStrings = noteRepository.getDeletedNoteAudios()
            imageStrings.flatMapTo(images) { json -> Converters.jsonToFiles(json) }
            fileStrings.flatMapTo(files) { json -> Converters.jsonToFiles(json) }
            audioStrings.flatMapTo(audios) { json -> Converters.jsonToAudios(json) }
            noteRepository.deleteFrom(Folder.DELETED)
        }
        val attachments = ArrayList<Attachment>(images.size + files.size + audios.size)
        attachments.addAll(images)
        attachments.addAll(files)
        attachments.addAll(audios)
        withContext(Dispatchers.IO) { attachmentRepository.deleteAttachments(attachments, ids) }
    }

    suspend fun duplicateNotes(notes: Collection<BaseNote>): List<Long> {
        val now = System.currentTimeMillis()
        val copies: List<BaseNote> =
            notes.map { original ->
                original
                    .deepCopy()
                    .copy(
                        id = 0L,
                        title =
                            if (original.title.isNotEmpty())
                                "${original.title} (${app.getString(R.string.copy)})"
                            else app.getString(R.string.copy),
                        timestamp = now,
                        modifiedTimestamp = now,
                    )
            }
        return withContext(Dispatchers.IO) { noteRepository.insert(copies) }
    }

    fun saveNotes(notes: List<BaseNote>) {
        scope.launch(Dispatchers.IO) { noteRepository.insert(notes) }
    }

    /** Re-serializes all notes through the Converters to repair corrupted spans/attachments. */
    suspend fun cleanupDatabase() {
        ConverterErrorReporter.enabled.set(false)
        val allNotes = noteRepository.getAll()
        noteRepository.updateAll(allNotes)
    }
}
