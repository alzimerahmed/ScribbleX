package com.philkes.notallyx.utils.backup

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.NotallyDatabase.Companion.DATABASE_NAME
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.FileAttachment
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Label
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.presentation.viewmodel.preference.PeriodicBackup
import com.philkes.notallyx.test.databaseFiles
import com.philkes.notallyx.utils.ZipVerificationException
import com.philkes.notallyx.utils.getDocumentFolder
import com.philkes.notallyx.utils.listZipFiles
import com.philkes.notallyx.utils.verify
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowLog

/**
 * Regression tests for the backup/restore paths behind the upstream data-loss reports
 * (#1066-class): the exported ZIP must contain a restorable database, verification must reject
 * corrupt or wrongly-encrypted archives before they replace user data, and raw database-file copies
 * must be taken only after a WAL checkpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [35],
    shadows = [com.philkes.notallyx.data.ShadowContextImplMedia::class],
)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
// Production code hops to Dispatchers.Main.immediate from background dispatchers
// (copyDatabase/import); under Robolectric's paused main looper those posts never
// drain while the test thread is parked in runBlocking -> deadlock. An unconfined
// test dispatcher executes the Main hops eagerly on the calling thread instead.
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreRegressionTest {

    private lateinit var application: Application
    private var database: NotallyDatabase? = null

    private val databaseFile: File
        get() = NotallyDatabase.getCurrentDatabaseFile(application)

    private val backupDir: File
        get() = File(application.cacheDir, "backup-restore-test")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
        ShadowLog.stream = System.out
        databaseFile.parentFile?.mkdirs()
        databaseFile.databaseFiles().forEach { it.delete() }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        NotallyXPreferences.clearInstance()
        backupDir.deleteRecursively()
        backupDir.mkdirs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database?.let { if (it.isOpen) it.close() }
        database = null
        NotallyDatabase.clearInstance()
        NotallyXPreferences.clearInstance()
        databaseFile.databaseFiles().forEach { it.delete() }
        backupDir.deleteRecursively()
    }

    @Test
    fun exportAsZip_thenReadBaseNotes_roundTripsNotesAndLabels() {
        seedDatabase()
        installSingletonDatabase()
        val zipFile = File(backupDir, "export.zip")

        runBlocking { application.exportAsZip(Uri.fromFile(zipFile), compress = true) }

        assertThat(zipFile).exists().isFile
        val extractedDb = File(backupDir, NotallyDatabase.DATABASE_NAME)
        ZipFile(zipFile).extractFile(NotallyDatabase.DATABASE_NAME, backupDir.path)

        val restored = application.readBaseNotes(extractedDb)
        assertThat(restored.corruptedNotes).isEqualTo(0)
        assertThat(restored.baseNotes.map { it.title })
            .containsExactlyInAnyOrder("Note A", "Note B")
        assertThat(restored.baseNotes.first { it.title == "Note A" }.body).isEqualTo("Body A")
        assertThat(restored.baseNotes.first { it.title == "Note A" }.labels).containsExactly("work")
        assertThat(restored.labels.map { it.value }).containsExactlyInAnyOrder("work", "personal")
    }

    @Test
    fun verify_validZipContainingDatabase_passes() {
        seedDatabase()
        val zipFile = File(backupDir, "valid.zip")
        val zip = ZipFile(zipFile)
        zip.addFile(databaseFile, ZipParameters().apply { fileNameInZip = DATABASE_NAME })

        zip.verify(databaseFile)
    }

    @Test
    fun verify_zipMissingDatabaseEntry_throws() {
        val textFile = File(backupDir, "not-the-database.txt").apply { writeText("hello") }
        val zipFile = File(backupDir, "missing-db.zip")
        ZipFile(zipFile).addFile(textFile, ZipParameters().apply { fileNameInZip = "other.txt" })

        assertThatThrownBy { ZipFile(zipFile).verify(databaseFile) }
            .isInstanceOf(ZipVerificationException::class.java)
            .hasMessageContaining("Database file missing")
    }

    @Test
    fun verify_encryptedZipWithWrongPassword_throws() {
        val secret = File(backupDir, DATABASE_NAME).apply { writeText("secret database content") }
        val zipFile = File(backupDir, "encrypted.zip")
        val zipFileEncrypted =
            ZipFile(zipFile, "correct-password".toCharArray()).apply {
                val parameters =
                    ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        fileNameInZip = DATABASE_NAME
                    }
                addFile(secret, parameters)
            }

        // verify() with the correct password must succeed...
        zipFileEncrypted.verify(secret)

        // ...while the same archive opened with a wrong password must fail verification
        assertThatThrownBy { ZipFile(zipFile, "wrong-password".toCharArray()).verify(secret) }
            .isInstanceOf(ZipVerificationException::class.java)
            .hasMessageContaining("corrupt encrypted files")
    }

    @Test
    fun backupDatabaseFiles_copiesRestorableDatabaseAfterCheckpoint() {
        seedDatabase()

        val backup = application.backupDatabaseFiles()

        assertThat(backup).isNotNull
        val copiedDb = File(backup, "internal/${NotallyDatabase.DATABASE_NAME}")
        assertThat(copiedDb).exists()
        val restored = application.readBaseNotes(copiedDb)
        assertThat(restored.baseNotes.map { it.title })
            .containsExactlyInAnyOrder("Note A", "Note B")
    }

    @Test
    fun importZip_roundTrip_restoresNotesIntoDatabase() {
        seedDatabase()
        installSingletonDatabase()
        val zipFile = File(backupDir, "roundtrip.zip")
        runBlocking { application.exportAsZip(Uri.fromFile(zipFile), compress = true) }

        // Import the same backup again with duplicate checking: the restore path must
        // succeed and must not duplicate existing notes (idempotent restore).
        runBlocking {
            application.importZip(
                Uri.fromFile(zipFile),
                backupDir,
                zipPassword = "",
                checkDuplicates = true,
            )
        }

        val database = openDatabase()
        val notes = runBlocking { database.getBaseNoteDao().getAll() }
        assertThat(notes.map { it.title }).containsExactlyInAnyOrder("Note A", "Note B")
    }

    @Test
    fun importZip_corruptZip_throwsAndLeavesDatabaseUntouched() {
        seedDatabase()
        installSingletonDatabase()
        val notesBefore = currentNoteTitles()

        val corruptZip = File(backupDir, "corrupt.zip").apply { writeText("not a zip file") }

        assertThatThrownBy {
            runBlocking {
                application.importZip(
                    Uri.fromFile(corruptZip),
                    backupDir,
                    zipPassword = "",
                    checkDuplicates = false,
                )
            }
        }
        assertThat(currentNoteTitles()).containsExactlyInAnyOrderElementsOf(notesBefore)
        // The staging folder must not keep a half-extracted database behind
        assertThat(backupDir.listFiles().orEmpty().filter { it.name == DATABASE_NAME }).isEmpty()
    }

    @Test
    fun importZip_zipMissingDatabaseEntry_throwsAndLeavesDatabaseUntouched() {
        seedDatabase()
        installSingletonDatabase()
        val notesBefore = currentNoteTitles()

        val textFile = File(backupDir, "other.txt").apply { writeText("hello") }
        val zipFile = File(backupDir, "missing-db.zip")
        ZipFile(zipFile).addFile(textFile, ZipParameters().apply { fileNameInZip = "other.txt" })

        assertThatThrownBy {
            runBlocking {
                application.importZip(
                    Uri.fromFile(zipFile),
                    backupDir,
                    zipPassword = "",
                    checkDuplicates = false,
                )
            }
        }
        assertThat(currentNoteTitles()).containsExactlyInAnyOrderElementsOf(notesBefore)
    }

    @Test
    fun importZip_wrongPassword_doesNotThrowAndLeavesDatabaseUntouched() {
        seedDatabase()
        installSingletonDatabase()
        val notesBefore = currentNoteTitles()

        val zipFile = File(backupDir, "encrypted-export.zip")
        runBlocking {
            application.exportAsZip(
                Uri.fromFile(zipFile),
                compress = true,
                password = "correct-password",
            )
        }

        // Wrong password must fail gracefully (no crash, no partial import)
        runBlocking {
            application.importZip(
                Uri.fromFile(zipFile),
                backupDir,
                zipPassword = "wrong-password",
                checkDuplicates = false,
            )
        }
        assertThat(currentNoteTitles()).containsExactlyInAnyOrderElementsOf(notesBefore)
    }

    @Test
    fun importRawDatabase_invalidDatabaseFile_throwsAndCleansUpTempFile() {
        val garbage = File(backupDir, "garbage.sqlite").apply { writeText("definitely not sqlite") }
        val tempDbFile = File(application.cacheDir, "${DATABASE_NAME}_IMPORT")

        assertThatThrownBy {
            runBlocking { application.importRawDatabase(Uri.fromFile(garbage), false) }
        }
        assertThat(tempDbFile).doesNotExist()
    }

    @Test
    fun createBackup_writesRestorableZip_andRespectsMaxBackupsRetention() {
        seedDatabase()
        installSingletonDatabase()
        val preferences = NotallyXPreferences.getInstance(application)
        preferences.backupsFolder.save(Uri.fromFile(backupDir).toString())
        preferences.periodicBackups.save(PeriodicBackup(periodInDays = 1, maxBackups = 2))
        // Two pre-existing (older) backups: with maxBackups = 2 the oldest must be evicted
        // once the new backup is created, and the newest valid backups must survive.
        val older1 = File(backupDir, "NotallyX_Backup_2020-01-01_00_00_00_000.zip")
        val older2 = File(backupDir, "NotallyX_Backup_2020-01-02_00_00_00_000.zip")
        older1.apply { writeText("older1") }.setLastModified(1_000L)
        older2.apply { writeText("older2") }.setLastModified(2_000L)

        val result = runBlocking { application.createBackup() }

        assertThat(result).isNotNull
        val remaining = backupDir.listFiles()!!.filter { it.name.endsWith(".zip") }
        val newBackups = remaining.filter { it.name != older1.name && it.name != older2.name }
        assertThat(older1).doesNotExist()
        assertThat(remaining).hasSize(2)
        assertThat(newBackups).hasSize(1)
        // The newest backup must contain a restorable database
        val newest = newBackups.single()
        val extractedDb = File(backupDir, "retention-${NotallyDatabase.DATABASE_NAME}")
        ZipFile(newest).extractFile(NotallyDatabase.DATABASE_NAME, backupDir.path, extractedDb.name)
        val restored = application.readBaseNotes(extractedDb)
        assertThat(restored.corruptedNotes).isEqualTo(0)
        assertThat(restored.baseNotes.map { it.title })
            .containsExactlyInAnyOrder("Note A", "Note B")
    }

    @Test
    fun listZipFiles_filtersByPrefixAndSortsNewestFirst() {
        val folder = application.getDocumentFolder(Uri.fromFile(backupDir))!!
        // listZipFiles sorts by lastModified DESC, so create files oldest-first to match
        // the asserted order
        val names =
            listOf(
                "NotallyX_Backup_2020-01-01.zip",
                "NotallyX_Backup_2020-01-02.zip",
                "NotallyX_Backup_2020-01-03.zip",
                "Unrelated.zip",
                "NotallyX_Backup_2020-01-04.txt",
            )
        names.forEachIndexed { index, name ->
            File(backupDir, name).apply { writeText("x") }.setLastModified(1_000L + index)
        }

        val zipFiles = folder.listZipFiles("NotallyX_Backup_")

        assertThat(zipFiles.map { it.name })
            .containsExactly(
                "NotallyX_Backup_2020-01-03.zip",
                "NotallyX_Backup_2020-01-02.zip",
                "NotallyX_Backup_2020-01-01.zip",
            )
    }

    @Test
    fun createBackup_invalidBackupFolderPath_reportsExceptionWithoutCrashing() {
        val preferences = NotallyXPreferences.getInstance(application)
        // A path that exists but is a FILE, not a folder: requireBackupFolder passes
        // (exists() == true) but every file operation on it must fail gracefully.
        val notAFolder = File(backupDir, "plain-file.txt").apply { writeText("not a folder") }
        preferences.backupsFolder.save(Uri.fromFile(notAFolder).toString())

        val result = runBlocking { application.createBackup() }

        assertThat(result).isNotNull
        assertThat(result.outputData.getString(OUTPUT_DATA_EXCEPTION)).isNotNull()
    }

    @Test
    fun createBackup_failedExport_deletesPartialBackupAndKeepsExistingBackups() {
        seedDatabase()
        installSingletonDatabase()
        val preferences = NotallyXPreferences.getInstance(application)
        preferences.backupsFolder.save(Uri.fromFile(backupDir).toString())
        preferences.periodicBackups.save(PeriodicBackup(periodInDays = 1, maxBackups = 3))
        // A pre-existing valid backup must survive a failed backup attempt untouched
        val existing = File(backupDir, "NotallyX_Backup_2020-01-01_00_00_00_000.zip")
        existing.writeText("valid older backup")
        existing.setLastModified(1_000L)

        // Force the export to fail AFTER the backup file is created: stub exportAsZip to
        // throw, so createBackup's catch must delete the partial file it just created.
        mockkStatic("com.philkes.notallyx.utils.backup.ExportExtensionsKt")
        coEvery { any<ContextWrapper>().exportAsZip(any(), any(), any(), any()) } throws
            RuntimeException("injected export failure")

        try {
            val result = runBlocking { application.createBackup() }

            assertThat(result).isNotNull
            // The partial backup created by the failed attempt must be gone (any size)
            val leftovers =
                backupDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.startsWith("NotallyX_Backup_") && it.name.endsWith(".zip") }
                    .filter { it.name != existing.name }
            assertThat(leftovers).isEmpty()
            // Pre-existing valid backup must not have been touched by retention/cleanup
            assertThat(existing).exists()
        } finally {
            unmockkStatic("com.philkes.notallyx.utils.backup.ExportExtensionsKt")
        }
    }

    @Test
    fun importZip_corruptDatabaseEntry_throwsAndLeavesDatabaseUntouched() {
        seedDatabase()
        installSingletonDatabase()
        val notesBefore = currentNoteTitles()

        val garbageDb = File(backupDir, "garbage.db").apply { writeText("not a database") }
        val zipFile = File(backupDir, "corrupt-db.zip")
        ZipFile(zipFile).addFile(garbageDb, ZipParameters().apply { fileNameInZip = DATABASE_NAME })

        assertThatThrownBy {
            runBlocking {
                application.importZip(
                    Uri.fromFile(zipFile),
                    backupDir,
                    zipPassword = "",
                    checkDuplicates = false,
                )
            }
        }
        assertThat(currentNoteTitles()).containsExactlyInAnyOrderElementsOf(notesBefore)
    }

    @Test
    fun importZip_missingAttachmentEntries_stillImportsNotes() {
        seedDatabase()
        installSingletonDatabase()
        // A note referencing an image that is NOT in the backup ZIP
        val database = openDatabase()
        runBlocking {
            database
                .getBaseNoteDao()
                .insert(
                    note("Note With Missing Image")
                        .copy(
                            images =
                                listOf(FileAttachment("missing.png", "missing.png", "image/png"))
                        )
                )
        }
        database.checkpoint()
        database.close()
        this.database = null
        installSingletonDatabase()

        val fullZip = File(backupDir, "full.zip")
        runBlocking { application.exportAsZip(Uri.fromFile(fullZip), compress = true) }

        // Build a backup ZIP that contains only the database — the attachment entry is gone
        val dbOnlyZip = File(backupDir, "db-only.zip")
        ZipFile(dbOnlyZip).run {
            val extractedDb = File(backupDir, "extract-${NotallyDatabase.DATABASE_NAME}")
            ZipFile(fullZip)
                .extractFile(NotallyDatabase.DATABASE_NAME, backupDir.path, extractedDb.name)
            addFile(extractedDb, ZipParameters().apply { fileNameInZip = DATABASE_NAME })
        }

        runBlocking {
            application.importZip(
                Uri.fromFile(dbOnlyZip),
                backupDir,
                zipPassword = "",
                checkDuplicates = false,
            )
        }

        // The restore path must tolerate missing attachment entries and still import the notes
        val notes = currentNoteTitles()
        assertThat(notes).contains("Note A", "Note B", "Note With Missing Image")
    }

    private fun currentNoteTitles(): List<String> {
        val database = openDatabase()
        return runBlocking { database.getBaseNoteDao().getAll().map { it.title } }
    }

    private fun seedDatabase() {
        val database = openDatabase()
        runBlocking {
            database
                .getBaseNoteDao()
                .insert(note("Note A", body = "Body A", labels = listOf("work")))
            database.getBaseNoteDao().insert(note("Note B"))
            database.getLabelDao().insert(Label("work", 0))
            database.getLabelDao().insert(Label("personal", 1))
        }
        database.checkpoint()
        database.close()
        this.database = null
        assertThat(databaseFile).exists()
    }

    /**
     * `exportAsZip` -> `copyDatabase` resolves the database through the `NotallyDatabase`
     * singleton. Publish an open, main-thread-queries-allowed instance so the export path runs
     * under Robolectric without touching the production open-helper configuration.
     */
    private fun installSingletonDatabase() {
        val database = openDatabase()
        // Force the Room connection open, otherwise getDatabase() sees a closed instance
        // and rebuilds a production-configured instance without main-thread queries
        database.openHelper.writableDatabase
        NotallyDatabase.postInstance(database)
    }

    private fun openDatabase(): NotallyDatabase {
        return NotallyDatabase.builder(application, false).allowMainThreadQueries().build().also {
            database = it
        }
    }

    private fun note(title: String, body: String = "", labels: List<String> = emptyList()) =
        BaseNote(
            id = 0,
            type = Type.NOTE,
            folder = Folder.NOTES,
            color = "DEFAULT",
            title = title,
            pinned = false,
            timestamp = 1L,
            modifiedTimestamp = 1L,
            labels = labels,
            body = body,
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
