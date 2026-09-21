package com.philkes.notallyx.utils.sync

import android.content.ContextWrapper
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.toBaseNote
import com.philkes.notallyx.data.model.toJson
import com.philkes.notallyx.data.repository.NoteRepository
import com.philkes.notallyx.data.repository.RoomNoteRepository
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.backup.createBackup
import com.philkes.notallyx.utils.log

/*
 * ============================================================================
 * ADR 0008: Self-hosted sync MVP (Phase 7, Feature F1)
 * ============================================================================
 *
 * STATUS: Accepted (MVP)
 *
 * CONTEXT
 * -------
 * ScribbleX is offline-first and FOSS (F-Droid). Users asked for multi-device
 * note sync without any proprietary backend or GMS dependency. Upstream
 * NotallyX has no sync at all by design.
 *
 * DECISION
 * --------
 * 1. TRANSPORT: WebDAV/Nextcloud, accessed through a minimal hand-rolled
 *    client over HttpURLConnection (WebDavClient). No Sardine, no OkHttp —
 *    HttpURLConnection is in the platform, keeps the APK small, and is
 *    FOSS-safe. Operations: PUT, GET, DELETE, MKCOL, PROPFIND (Depth: 1).
 *
 * 2. E2E ENCRYPTION: every payload is encrypted client-side with AES-256-GCM,
 *    key derived from the user's sync password via PBKDF2-HmacSHA256 with a
 *    per-payload random salt (SyncCrypto). The server stores only opaque
 *    blobs; the server operator (or a Nextcloud admin) cannot read notes.
 *    This reuses the same platform JCE primitives the app already trusts for
 *    SQLCipher/zip AES crypto instead of adding a dependency.
 *
 * 3. OPT-IN, DISABLED BY DEFAULT: all sync code is inert unless the user
 *    enables it and configures server URL + credentials. When disabled, no
 *    worker is scheduled, no network call is made, and the settings UI is the
 *    only code path touched.
 *
 * 4. OFFLINE-FIRST + LAST-WRITE-WINS (LWW): the local database is always the
 *    working copy; sync never blocks editing. On sync, per note id:
 *      - remote only  -> download + insert
 *      - local only   -> upload
 *      - both         -> newer `modifiedTimestamp` wins (tie: local wins)
 *    Simplest-correct MVP choice: full-list comparison WITHOUT tombstones.
 *    Deletion propagation is therefore NOT supported in the MVP (a deleted
 *    note re-appears from the server on the next sync). Tombstones were the
 *    alternative but require either a schema change (banned for this phase)
 *    or a separate remote marker file scheme; deferred to a follow-up.
 *
 * 5. BACKUP BEFORE SYNC: before applying ANY remote change, the engine
 *    snapshots a local backup via the existing backup export utilities
 *    (ContextWrapper.createBackup()). If the snapshot fails, sync aborts —
 *    this is the #1066-class data-loss guardrail applied to sync.
 *
 * 6. WORKERS: on-demand ("Sync now") via a OneTimeWorkRequest and an optional
 *    periodic worker, both WorkManager CoroutineWorkers (SyncWorker), matching
 *    the existing AutoBackupWorker/AutoRemoveDeletedNotesWorker patterns.
 *
 * ALTERNATIVES CONSIDERED
 * -----------------------
 * - Sardine/okhttp WebDAV libs: rejected (new heavy deps, FOSS review cost).
 * - Syncthing/SFTP/rsync-style transports: no stable Android-embedded FOSS
 *   story; WebDAV is what self-hosters (Nextcloud) already run.
 * - Full CRDT/3-way merge: massive complexity, no upstream precedent; LWW per
 *   note matches the backup/import semantics users already understand.
 * - Tombstone table in Room: requires schema change (v12 frozen this phase).
 * - Plain (unencrypted) WebDAV: rejected — notes are sensitive; E2E is cheap.
 *
 * CONSEQUENCES
 * ------------
 * + Zero new dependencies; zero impact when disabled.
 * + Server can be any dumb WebDAV share; nothing server-side to deploy.
 * - Deletions do not propagate (MVP limitation, documented in UI strings).
 * - Attachments (images/files/audio) are not synced in the MVP — only note
 *   content JSON; attachment sync is the first follow-up.
 * - LWW can silently discard the older side of a concurrent edit; the
 *   pre-sync backup snapshot is the recovery path.
 * - Clock skew between devices shifts LWW outcomes; acceptable for MVP.
 * ============================================================================
 */

/** Result of one sync run, surfaced to the settings UI / worker output. */
data class SyncResult(
    val status: Status,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val message: String? = null,
) {
    enum class Status {
        SUCCESS,
        DISABLED,
        NOT_CONFIGURED,
        BACKUP_FAILED,
        ERROR,
    }
}

/**
 * Pure LWW merge decision — unit-testable without Android. [localModified] / [remoteModified] are
 * the notes' `modifiedTimestamp` values. Ties favor the local side (avoids clobbering the device
 * the user is holding).
 */
sealed interface MergeDecision {
    data object KeepLocal : MergeDecision

    data object TakeRemote : MergeDecision
}

fun mergeLastWriteWins(localModified: Long, remoteModified: Long): MergeDecision =
    if (localModified >= remoteModified) MergeDecision.KeepLocal else MergeDecision.TakeRemote

class SyncEngine(
    private val context: ContextWrapper,
    private val noteRepository: NoteRepository,
    private val preferences: NotallyXPreferences,
    private val clientFactory:
        (serverUrl: String, username: String, password: String) -> WebDavClient =
        { url, username, password ->
            defaultClientFactory(url, username, password)
        },
) {

    suspend fun syncNow(): SyncResult {
        if (!preferences.syncEnabled.value) {
            return SyncResult(SyncResult.Status.DISABLED)
        }
        val serverUrl = preferences.syncServerUrl.value.trim()
        val username = preferences.syncUsername.value
        val password = preferences.syncPassword.value
        if (serverUrl.isEmpty() || password.isEmpty()) {
            return SyncResult(SyncResult.Status.NOT_CONFIGURED)
        }

        // Guardrail: snapshot a local backup BEFORE applying any remote change.
        // A failed snapshot aborts the sync — never risk data without a fallback.
        val backupResult = context.createBackup()
        if (backupResult.outputData.getString(OUTPUT_DATA_EXCEPTION) != null) {
            return SyncResult(
                SyncResult.Status.BACKUP_FAILED,
                message = backupResult.outputData.getString(OUTPUT_DATA_EXCEPTION),
            )
        }

        val client = clientFactory(serverUrl, username, password)
        return try {
            withRetries { client.mkcol(SYNC_ROOT) }
            syncNotes(client)
        } catch (e: Exception) {
            log(TAG, msg = "Sync failed", throwable = e)
            SyncResult(SyncResult.Status.ERROR, message = e.message)
        }
    }

    private suspend fun syncNotes(client: WebDavClient): SyncResult {
        val remoteEntries = withRetries { client.propfind(SYNC_ROOT) }
        val remoteByFileName =
            remoteEntries
                .map { it.href.substringBeforeLast('?').substringAfterLast('/') }
                .filter { it.startsWith(NOTE_FILE_PREFIX) && it.endsWith(NOTE_FILE_SUFFIX) }
                .toSet()

        val localNotes = noteRepository.getAll()
        val localById = localNotes.associateBy { it.id }
        var uploaded = 0
        var downloaded = 0

        // 1. Pull: for every remote note file, compare and merge.
        for (fileName in remoteByFileName) {
            val remoteId =
                fileName
                    .removePrefix(NOTE_FILE_PREFIX)
                    .removeSuffix(NOTE_FILE_SUFFIX)
                    .toLongOrNull() ?: continue
            val payload = withRetries { client.get("$SYNC_ROOT/$fileName") } ?: continue
            val remoteNote = decryptNote(payload) ?: continue
            val localNote = localById[remoteId]
            when (
                mergeLastWriteWins(localNote?.modifiedTimestamp ?: 0L, remoteNote.modifiedTimestamp)
            ) {
                MergeDecision.KeepLocal -> {
                    // Local is equal or newer; push it so the other device converges.
                    if (
                        localNote != null &&
                            localNote.modifiedTimestamp > remoteNote.modifiedTimestamp
                    ) {
                        withRetries { client.put("$SYNC_ROOT/$fileName", encryptNote(localNote)) }
                        uploaded++
                    }
                }

                MergeDecision.TakeRemote -> {
                    if (localNote == null) {
                        noteRepository.insert(listOf(remoteNote))
                    } else {
                        noteRepository.updateAll(listOf(remoteNote))
                    }
                    downloaded++
                }
            }
        }

        // 2. Push: local notes with no remote counterpart.
        for (note in localNotes) {
            val fileName = noteFileName(note.id)
            if (fileName !in remoteByFileName) {
                withRetries { client.put("$SYNC_ROOT/$fileName", encryptNote(note)) }
                uploaded++
            }
        }

        preferences.syncLastExecution.save(System.currentTimeMillis())
        return SyncResult(SyncResult.Status.SUCCESS, uploaded, downloaded)
    }

    private fun encryptNote(note: BaseNote): ByteArray =
        SyncCrypto.encrypt(
            note.toJson().toByteArray(Charsets.UTF_8),
            preferences.syncPassword.value,
        )

    private fun decryptNote(payload: ByteArray): BaseNote? =
        try {
            String(SyncCrypto.decrypt(payload, preferences.syncPassword.value), Charsets.UTF_8)
                .toBaseNote()
        } catch (e: Exception) {
            log(TAG, msg = "Skipping undecryptable/unparseable remote note", throwable = e)
            null
        }

    private inline fun <T> withRetries(maxAttempts: Int = 2, block: () -> T): T {
        var lastError: Exception? = null
        repeat(maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError!!
    }

    companion object {
        private const val TAG = "SyncEngine"
        const val SYNC_ROOT = "/NotallyX-Sync"
        const val NOTE_FILE_PREFIX = "note-"
        const val NOTE_FILE_SUFFIX = ".json"
        const val OUTPUT_DATA_EXCEPTION = "exception"

        fun noteFileName(noteId: Long) = "$NOTE_FILE_PREFIX$noteId$NOTE_FILE_SUFFIX"

        /** Factory used in production; injectable for tests. */
        fun defaultClientFactory(serverUrl: String, username: String, password: String) =
            WebDavClient(serverUrl, username, password)
    }
}

/** Builds a production [SyncEngine] from the application context. */
fun createSyncEngine(context: ContextWrapper): SyncEngine {
    val preferences = NotallyXPreferences.getInstance(context)
    val repository =
        RoomNoteRepository(
            contextProvider = { context },
            daoProvider = { NotallyDatabase.getDatabase(context).value.getBaseNoteDao() },
        )
    return SyncEngine(context, repository, preferences)
}
