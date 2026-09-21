package com.philkes.notallyx.presentation.viewmodel.usecase

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.imports.ImportException
import com.philkes.notallyx.data.imports.ImportSource
import com.philkes.notallyx.data.imports.NotesImporter
import com.philkes.notallyx.data.repository.LabelRepository
import com.philkes.notallyx.presentation.exportedText
import com.philkes.notallyx.presentation.showToast
import com.philkes.notallyx.presentation.view.misc.Progress
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.backup.exportAsZip
import com.philkes.notallyx.utils.backup.importRawDatabase
import com.philkes.notallyx.utils.backup.importZip
import com.philkes.notallyx.utils.backup.readAsBackup
import com.philkes.notallyx.utils.getBackupDir
import com.philkes.notallyx.utils.log
import com.philkes.notallyx.utils.toMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Use-case extracted from BaseNoteModel (F2): backup export/import triggers. Logic moved verbatim
 * from BaseNoteModel — behavior-preserving (backup/restore is the most sensitive area, see
 * AGENTS.md). The ViewModel keeps ownership of toasts wiring via app context and progress LiveData,
 * which are passed in.
 */
class BackupUseCase(
    private val app: Application,
    private val scope: CoroutineScope,
    private val preferences: NotallyXPreferences,
    private val labelRepository: LabelRepository,
    private val progress: MutableLiveData<Progress>,
    private val importProgress: MutableLiveData<Progress>,
) {

    fun exportBackup(uri: Uri, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val exportedNotesAndAttachments =
                withContext(Dispatchers.IO) {
                    app.log(TAG, msg = "Exporting backup to '$uri'...")
                    return@withContext app.exportAsZip(
                            uri,
                            password = preferences.backupPassword.value,
                            backupProgress = progress,
                        )
                        .also { app.log(TAG, msg = "Finished exporting backup to '$uri'") }
                }

            app.showToast(app.exportedText(exportedNotesAndAttachments))
            onComplete?.invoke()
        }
    }

    fun importRawDatabase(uri: Uri, checkDuplicates: Boolean) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
        }

        scope.launch(exceptionHandler) {
            val importResult =
                withContext(Dispatchers.IO) {
                    app.importRawDatabase(uri, checkDuplicates, importProgress)
                }
            app.showToast(app.toMessage(importResult))
        }
    }

    fun importZipBackup(uri: Uri, password: String, checkDuplicates: Boolean) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
        }

        val backupDir = app.getBackupDir()
        scope.launch(exceptionHandler) {
            app.importZip(uri, backupDir, password, checkDuplicates, importProgress)
        }
    }

    fun importXmlBackup(uri: Uri) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
        }

        scope.launch(exceptionHandler) {
            val result =
                withContext(Dispatchers.IO) {
                    val stream =
                        requireNotNull(
                            app.contentResolver.openInputStream(uri),
                            { "InputStream for '$uri' is null" },
                        )
                    val (baseNotes, labels) = stream.readAsBackup()
                    labelRepository.importBackup(baseNotes, labels, 0, false)
                }
            app.showToast(app.toMessage(result))
        }
    }

    fun importFromOtherApp(uri: Uri, importSource: ImportSource) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            app.log(TAG, throwable = throwable)
            if (throwable is ImportException) {
                app.showToast(throwable.textResId)
            } else {
                app.showToast("${app.getString(R.string.invalid_backup)}: ${throwable.message}")
            }
        }

        scope.launch(exceptionHandler) {
            val database =
                withContext(Dispatchers.Main.immediate) { NotallyDatabase.getDatabase(app).value }
            val result =
                withContext(Dispatchers.IO) {
                    NotesImporter(app, database).import(uri, importSource, importProgress)
                }
            app.showToast(app.toMessage(result))
        }
    }

    companion object {
        private const val TAG = "BackupUseCase"
    }
}
