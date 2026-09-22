# ScribbleX — Phase 8 Static Performance Audit

Static audit only (no profiling, no builds — verification deferred to CI). Scope per docs/plan.md
Phase 8: startup, main-thread I/O, Room efficiency, WorkManager, attachment/audio handling.

## Summary of applied fixes

| # | Fix | Files |
|---|-----|-------|
| 1 | Room indices for list-query and deleted-cleanup query shapes + **Room migration 12→13** | `BaseNote.kt`, `NotallyDatabase.kt`, `13.json`, `NotallyDatabaseMigrationTest.kt` |

## ⚠️ PROMINENT FLAG: hand-written schema 13.json

`app/schemas/com.philkes.notallyx.data.NotallyDatabase/13.json` was **hand-written** from 12.json
(no Gradle/KSP available in this environment). The `identityHash` value is **copied from 12.json and
is stale**. This is safe for the migration tests (MigrationTestHelper validates table/index schema,
not the identity hash) and for production (the runtime identity hash comes from Room's generated
code, not the JSON), **but CI MUST run `./gradlew kspDebugKotlin` (any KSP task) once to
regenerate 13.json with the correct identity hash before release**. If the entity annotation and
the JSON ever disagree, KSP regeneration is the source of truth.

## Findings

### (a) Startup paths (Application / Activity onCreate)

| Finding | Severity | Action |
|---|---|---|
| `NotallyXApplication.onCreate` reads many SharedPreferences (`NotallyXPreferences.getInstance`) synchronously on main. Standard Android pattern; first access loads prefs once. | Minor | Deferred (accepted) |
| `restorePinnedNotifications()` calls `NotallyDatabase.getDatabase()` on main — but `Room.build()` does not open the DB; the actual query runs on `Dispatchers.IO` via `runOnIODispatcher`. | OK | None |
| Backup observers (`backupsFolder`, `backupPassword`) fire `autoBackupOnSaveFileExists` (file stat) on main in observer callbacks. Single `File.exists()` — negligible. | Nit | Deferred |
| `NotallyDatabase.getDatabase`/`createInstance` is `@MainThread` and does SQLCipher setup (`System.loadLibrary`, cipher init) on main when biometric lock is enabled. One-time cost, only with biometric lock enabled. | Minor | Deferred (architecture-sensitive; touching it risks the encryption path) |

### (b) Main-thread I/O

| Finding | Severity | Action |
|---|---|---|
| ViewModels/use-cases consistently wrap DB/file work in `Dispatchers.IO` (57 call sites verified across `NotallyModel`, `BaseNoteModel`, `NoteOperationsUseCase`, `BackupUseCase`, `DatabaseLocationUseCase`, widget/receiver code). | OK | None |
| `EditActivity` line ~198: `runBlocking(Dispatchers.IO) { saveNote() }` on back-press — blocks main until save completes. Pre-existing upstream behavior (intentional "save before exit"); changing risks data-loss semantics. | Major | Deferred (behavior-preserving constraint) |
| `NotallyModel.addFiles` loops `app.importFile(...)` — each call is `withContext(Dispatchers.IO)` internally; the loop itself is on main but only posts progress between IO hops. | OK | None |
| `IOExtensions.migrateAllAttachments` / `deleteAttachments` do file I/O — all callers verified to run on IO dispatcher. | OK | None |

### (c) Room query efficiency

| Finding | Severity | Action |
|---|---|---|
| **BaseNote's only index leads with `id`** (the rowid alias) — `WHERE folder = ?` list queries (the app's hottest path: main list, archived, deleted, labels, keyword search) all full-scan + sort. | **High** | **FIXED** — added `Index(folder, pinned, timestamp)` and `Index(folder, modifiedTimestamp)` via Migration 13. Legacy index kept (additive-only). |
| `getDeletedNoteIdsOlderThan` (daily auto-remove worker) scans `folder='DELETED' AND modifiedTimestamp < ?` — covered by the new `(folder, modifiedTimestamp)` index. | Medium | FIXED (same migration) |
| List queries (`getFrom`, `getAllAsync`, keyword/label queries) have no LIMIT/paging — full note list materialized incl. JSON columns (spans/items/attachments). | Medium | **Deferred** — converting to Paging is an architecture change (violates behavior-preserving constraint). RecyclerView + DiffUtil currently absorbs this; recommend Paging 3 or keyset pagination as a future phase. |
| `getBaseNotesByLabel*` / `getBaseNotesByLabelKeyword` use `labels LIKE '%…%'` over a JSON column — full scan by design (documented in DAO). | Low | Deferred (storage-format change required) |
| FTS path (`searchNotes`) uses the NoteFts virtual table with `offsets()` ranking; FTS4 external-content table has its own index — efficient. Fallback LIKE path unchanged. | OK | None |
| No N+1 patterns found: widget, reminders, sync all use batched `IN (:ids)` queries. | OK | None |

### (d) WorkManager

| Finding | Severity | Action |
|---|---|---|
| `SyncWorker` (new, Phase 7): one-off + daily periodic, `NetworkType.CONNECTED` constraint, `Result.retry()` on failure → WorkManager default backoff (EXPONENTIAL, 30s) applies. Correct; explicit backoff criteria would be cosmetic. | OK | None |
| `schedulePeriodicSync` uses `ExistingPeriodicWorkPolicy.UPDATE` — correct (preserves enqueue time, updates spec). | OK | None |
| `AutoBackupWorker` / `AutoRemoveDeletedNotesWorker`: unique periodic work with KEEP — correct; no network constraint needed (local work). | OK | None |
| `checkUpdatePeriodicBackup` observes work-info LiveData on every app start — cheap (WorkManager local query). | OK | None |

### (e) Attachment / audio handling

| Finding | Severity | Action |
|---|---|---|
| Image display uses **Glide** everywhere (`BaseNoteVH`, `PreviewImageVH`, `ImageVH`, `ViewImageActivity`) — Glide performs bounds-based downsampling internally; no manual `inSampleSize` needed. | OK | None |
| `File.decodeToBitmap()` (IOExtensions) decodes with `inJustDecodeBounds = true` — returns null bitmap, used only as a dimension carrier in HTML export (`ModelExtensions.toHtml`) and image import validation. Runs on IO (export/import paths). Misleading name, not a perf issue. | Nit | Deferred (rename = API churn, no perf gain) |
| Attachment import (`Context.importFile`) copies Uri → temp file on `Dispatchers.IO`; audio import likewise. | OK | None |
| `RecordAudioActivity`: recording runs in `AudioRecordService` (bound service), unbind + `stopService` on destroy, save/discard dialog on back. Lifecycle correct; temp file deleted on discard. | OK | None |
| `NotallyModel.addFiles` imports sequentially per-Uri (progress-friendly). Parallelizing risks SD-card contention; sequential is intentional. | OK | None |

## Risks of the applied fix

- Migration 13 is `CREATE INDEX IF NOT EXISTS` only — no data touched; idempotent (crash-recovery safe).
- Index build cost on first upgrade is proportional to note count (single pass, milliseconds for
  realistic note counts; seconds for pathological 100k+ note databases).
- The drifted-schema pattern seen in v4–v6 does not apply: index creation is validated against
  `13.json` in `migrate12To13_addsQueryShapeIndicesAndPreservesData`.
- If the hand-written `13.json` differs from what KSP generates (e.g. index ordering), the
  migration test will fail in CI — that is the intended safety net; regenerate the JSON via KSP.
