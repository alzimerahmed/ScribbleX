package com.philkes.notallyx.data.repository

import android.content.ContextWrapper
import com.philkes.notallyx.data.model.Attachment
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.utils.deleteAttachments
import com.philkes.notallyx.utils.migrateAllAttachments

/**
 * Repository seam for attachment file operations (delegating to the existing ContextWrapper
 * extension functions in utils/IOExtensions.kt) so use-cases can be tested with fakes.
 */
interface AttachmentRepository {

    /** Deletes the attachment files of the given notes from disk. */
    suspend fun deleteAttachments(notes: Collection<BaseNote>)

    /** Deletes the given attachment files, restricted to the given note ids when provided. */
    suspend fun deleteAttachments(attachments: Collection<Attachment>, ids: LongArray? = null)

    /** Moves all attachment files between internal and external storage. */
    suspend fun migrateAllAttachments(toPrivate: Boolean)
}

class FileAttachmentRepository(private val contextProvider: () -> ContextWrapper) :
    AttachmentRepository {

    override suspend fun deleteAttachments(notes: Collection<BaseNote>) {
        contextProvider().deleteAttachments(notes)
    }

    override suspend fun deleteAttachments(attachments: Collection<Attachment>, ids: LongArray?) {
        contextProvider().deleteAttachments(attachments, ids)
    }

    override suspend fun migrateAllAttachments(toPrivate: Boolean) {
        contextProvider().migrateAllAttachments(toPrivate)
    }
}
