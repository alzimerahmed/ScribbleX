package com.philkes.notallyx.utils.backup

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.NotallyDatabase.Companion.DATABASE_NAME
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Folder
import com.philkes.notallyx.data.model.Label
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.Type
import com.philkes.notallyx.test.databaseFiles
import com.philkes.notallyx.utils.ZipVerificationException
import com.philkes.notallyx.utils.verify
import java.io.File
import kotlinx.coroutines.runBlocking
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
class BackupRestoreRegressionTest {

    private lateinit var application: Application
    private var database: NotallyDatabase? = null

    private val databaseFile: File
        get() = NotallyDatabase.getCurrentDatabaseFile(application)

    private val backupDir: File
        get() = File(application.cacheDir, "backup-restore-test")

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
        ShadowLog.stream = System.out
        databaseFile.parentFile?.mkdirs()
        databaseFile.databaseFiles().forEach { it.delete() }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        backupDir.deleteRecursively()
        backupDir.mkdirs()
    }

    @After
    fun tearDown() {
        database?.let { if (it.isOpen) it.close() }
        database = null
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
