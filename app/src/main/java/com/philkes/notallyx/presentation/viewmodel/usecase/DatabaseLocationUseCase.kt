package com.philkes.notallyx.presentation.viewmodel.usecase

import android.app.Application
import com.philkes.notallyx.data.NotallyDatabase
import com.philkes.notallyx.data.repository.AttachmentRepository
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.copyToLarge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Use-case extracted from BaseNoteModel (F2): moving the database (and attachments) between
 * internal and public external storage. Logic moved verbatim from BaseNoteModel —
 * behavior-preserving. This is a data-integrity-sensitive area: checkpoint before copy, restore
 * previous database instance on failure.
 */
class DatabaseLocationUseCase(
    private val app: Application,
    private val scope: CoroutineScope,
    private val preferences: NotallyXPreferences,
    private val attachmentRepository: AttachmentRepository,
) {

    fun enableDataInPublic(callback: (() -> Unit)? = null) {
        scope.launch {
            val database =
                withContext(Dispatchers.Main.immediate) { NotallyDatabase.getDatabase(app) }
            withContext(Dispatchers.IO) {
                NotallyDatabase.startReplacement()
                try {
                    database.value.checkpoint()
                    NotallyDatabase.clearInstance()
                    val targetDirectory = NotallyDatabase.getExternalDatabaseFile(app).parentFile
                    val internalDatabaseFiles = NotallyDatabase.getInternalDatabaseFiles(app)
                    internalDatabaseFiles.forEach {
                        it.copyToLarge(File(targetDirectory, it.name), overwrite = true)
                    }
                    val notallyDatabase =
                        withContext(Dispatchers.Main.immediate) {
                            NotallyDatabase.getFreshDatabase(
                                app,
                                true,
                                preferences.biometricLock.value,
                            )
                        }
                    val ping =
                        try {
                            notallyDatabase.ping()
                        } catch (e: Exception) {
                            throw RuntimeException(
                                "Moving internal '${internalDatabaseFiles.map { it.name }}' to public '$targetDirectory' folder failed",
                                e,
                            )
                        }
                    if (!ping) {
                        throw RuntimeException(
                            "Moving internal '${internalDatabaseFiles.map { it.name }}' to public '$targetDirectory' folder failed"
                        )
                    }
                    attachmentRepository.migrateAllAttachments(toPrivate = false)
                    preferences.dataInPublicFolder.save(true)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        NotallyDatabase.postNewInstance(app, dataInPublic = false)
                    }
                    throw e
                }
            }
            withContext(Dispatchers.Main.immediate) {
                NotallyDatabase.postNewInstance(app, dataInPublic = true)
            }
            callback?.invoke()
        }
    }

    fun disableDataInPublic(callback: (() -> Unit)? = null) {
        scope.launch {
            val database =
                withContext(Dispatchers.Main.immediate) { NotallyDatabase.getDatabase(app) }
            withContext(Dispatchers.IO) {
                NotallyDatabase.startReplacement()
                try {
                    database.value.checkpoint()
                    NotallyDatabase.clearInstance()
                    val targetDirectory = NotallyDatabase.getInternalDatabaseFile(app).parentFile
                    val externalDatabaseFiles = NotallyDatabase.getExternalDatabaseFiles(app)
                    externalDatabaseFiles.forEach {
                        it.copyToLarge(File(targetDirectory, it.name), overwrite = true)
                    }
                    val notallyDatabase =
                        withContext(Dispatchers.Main.immediate) {
                            NotallyDatabase.getFreshDatabase(
                                app,
                                false,
                                preferences.biometricLock.value,
                            )
                        }
                    val ping =
                        try {
                            notallyDatabase.ping()
                        } catch (e: Exception) {
                            throw RuntimeException(
                                "Moving public '${externalDatabaseFiles.map { it.name }}' to internal '$targetDirectory' folder failed",
                                e,
                            )
                        }
                    if (!ping) {
                        throw RuntimeException(
                            "Moving public '${externalDatabaseFiles.map { it.name }}' to internal '$targetDirectory' folder failed"
                        )
                    }
                    attachmentRepository.migrateAllAttachments(toPrivate = true)
                    preferences.dataInPublicFolder.save(false)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        NotallyDatabase.postNewInstance(app, dataInPublic = true)
                    }
                    throw e
                }
            }
            withContext(Dispatchers.Main.immediate) {
                NotallyDatabase.postNewInstance(app, dataInPublic = false)
            }
            callback?.invoke()
        }
    }
}
