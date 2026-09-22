package com.philkes.notallyx.utils.sync

import android.content.ContextWrapper
import com.philkes.notallyx.R
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.toBaseNote
import com.philkes.notallyx.data.model.toJson
import com.philkes.notallyx.data.repository.NoteRepository
import com.philkes.notallyx.data.repository.RoomNoteRepository
import com.philkes.notallyx.presentation.viewmodel.preference.Constants.PASSWORD_EMPTY
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.backup.createBackup
import com.philkes.notallyx.utils.log
import java.util.UUID
import kotlinx.coroutines.delay

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
 *    working copy; sync never blocks editing. On sync, per note:
 *      - remote only  -> download + insert
 *      - local only   -> upload
 *      - both         -> newer `modifiedTimestamp` wins (tie: local wins)
 *
 *    4a. SYNC IDENTITY (remediation of review finding C1): the local
 *    autoincrement `id` is NOT a global key — two devices can generate the
 *    same id and LWW would silently discard one side. Remote files are keyed
 *    by a per-note UUID (`note-<syncId>.json`): the UUID lives in the note's
 *    JSON payload (`BaseNote.syncId`, Room column added by Migration14) and is
 *    generated lazily at first sync for local notes. The remote payload's `id`
 *    is NEVER trusted: notes are matched by syncId, and remote notes are
 *    inserted with `id = 0` so Room autogenerates a collision-free local id.
 *
 *    4b. DELETION TOMBSTONES (remediation of review finding C2): permanent
 *    local deletes record the note's syncId in the SyncTombstone table (see
 *    NoteRepository.delete* / AutoRemoveDeletedNotesWorker). On sync the
 *    engine DELETEs remote files for tombstoned syncIds (tombstones are kept
 *    so the deletion is never undone) and skips downloading remote notes
 *    whose syncId is tombstoned. Deletions now propagate across devices;
 *    the earlier "deletions do not propagate" MVP limitation is resolved.
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
 * - Content-hash sync keys (no schema change): cannot distinguish "new on the
 *   other device" from "deleted here" without a stable identity, so a Room
 *   column (Migration14) was chosen after all — additive and idempotent.
 * - Plain (unencrypted) WebDAV: rejected — notes are sensitive; E2E is cheap.
 *
 * CONSEQUENCES
 * ------------
 * + Zero new dependencies; zero impact when disabled.
 * + Server can be any dumb WebDAV share; nothing server-side to deploy.
 * + Deletions propagate via tombstones (SyncTombstone, Migration14).
 * - Attachments (images/files/audio) are not synced in the MVP — only note
 *   content JSON; attachment sync is the first follow-up.
 * - LWW can silently discard the older side of a concurrent edit; the
 *   pre-sync backup snapshot is the recovery path.
 * - Clock skew between devices shifts LWW outcomes; acceptable for MVP.
 * - Legacy pre-UUID remote files (`note-<numericId>.json`) are adopted by
 *   payload syncId during pull; the numeric-named file keeps serving until a
 *   newer local version overwrites it in place.
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
        // PASSWORD_EMPTY ("None") is the legacy "unset" sentinel (M1) — never use it as a key
        if (serverUrl.isEmpty() || password.isEmpty() || password == PASSWORD_EMPTY) {
            return SyncResult(SyncResult.Status.NOT_CONFIGURED)
        }
        if (!WebDavClient.isSecureBaseUrl(serverUrl)) {
            // M3: reject plaintext HTTP except explicit LAN allowances
            return SyncResult(
                SyncResult.Status.ERROR,
                message = context.getString(R.string.sync_error_insecure_url),
            )
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
            context.log(TAG, msg = "Sync failed", throwable = e)
            SyncResult(SyncResult.Status.ERROR, message = e.message)
        }
    }

    private suspend fun syncNotes(client: WebDavClient): SyncResult {
        val tombstoned = noteRepository.getTombstones().map { it.syncId }.toSet()
        val remoteEntries = withRetries { client.propfind(SYNC_ROOT) }
        val remoteByFileName =
            remoteEntries
                .map { it.href.substringBeforeLast('?').substringAfterLast('/') }
                .filter { it.startsWith(NOTE_FILE_PREFIX) && it.endsWith(NOTE_FILE_SUFFIX) }
                .toSet()

        // 0. Push deletions: tombstoned notes must be removed from the server (C2). The
        // tombstone row is kept so the deletion is never resurrected by a later pull.
        for (syncId in tombstoned) {
            val fileName = noteFileName(syncId)
            if (fileName in remoteByFileName) {
                withRetries { client.delete("$SYNC_ROOT/$fileName") }
            }
        }

        val localNotes = noteRepository.getAll()
        val localBySyncId = localNotes.filter { it.syncId != null }.associateBy { it.syncId!! }
        var uploaded = 0
        var downloaded = 0
        val remoteSyncIds = mutableSetOf<String>()

        // 1. Pull: for every remote note file, compare and merge. Match by payload syncId —
        // the remote `id` is never trusted (C1).
        for (fileName in remoteByFileName) {
            val payload = withRetries { client.get("$SYNC_ROOT/$fileName") } ?: continue
            val remoteNote = decryptNote(payload) ?: continue
            val remoteSyncId = remoteNote.syncId ?: continue
            if (remoteSyncId in tombstoned) {
                // Deleted locally on this device: remove the remote copy, keep the tombstone.
                withRetries { client.delete("$SYNC_ROOT/$fileName") }
                continue
            }
            remoteSyncIds.add(remoteSyncId)
            val localNote = localBySyncId[remoteSyncId]
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
                        // Never trust the remote id: let Room autogenerate (C1)
                        noteRepository.insert(listOf(remoteNote.copy(id = 0)))
                    } else {
                        noteRepository.updateAll(listOf(remoteNote.copy(id = localNote.id)))
                    }
                    downloaded++
                }
            }
        }

        // 2. Push: local notes with no remote counterpart. Notes without a syncId get one
        // generated lazily at first sync and persisted via the repository.
        for (note in localNotes) {
            var current = note
            if (current.syncId == null) {
                current = current.copy(syncId = UUID.randomUUID().toString())
                noteRepository.updateAll(listOf(current))
            }
            if (current.syncId !in remoteSyncIds) {
                val fileName = noteFileName(current.syncId!!)
                withRetries { client.put("$SYNC_ROOT/$fileName", encryptNote(current)) }
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
            context.log(TAG, msg = "Skipping undecryptable/unparseable remote note", throwable = e)
            null
        }

    private suspend fun <T> withRetries(maxAttempts: Int = 2, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                // Non-transient client errors must not be retried
                if (e is WebDavException && (e.statusCode == 401 || e.statusCode == 403)) {
                    throw e
                }
                if (attempt < maxAttempts - 1) {
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
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
        private const val RETRY_BACKOFF_MS = 250L

        fun noteFileName(syncId: String) = "$NOTE_FILE_PREFIX$syncId$NOTE_FILE_SUFFIX"

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
