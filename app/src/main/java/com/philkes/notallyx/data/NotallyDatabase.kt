package com.philkes.notallyx.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.philkes.notallyx.NotallyXApplication.Companion.isTestRunner
import com.philkes.notallyx.data.dao.BaseNoteDao
import com.philkes.notallyx.data.dao.CommonDao
import com.philkes.notallyx.data.dao.LabelDao
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Color
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.data.model.Label
import com.philkes.notallyx.data.model.NoteFts
import com.philkes.notallyx.data.model.NoteViewMode
import com.philkes.notallyx.data.model.toColorString
import com.philkes.notallyx.presentation.view.misc.NotNullLiveData
import com.philkes.notallyx.presentation.viewmodel.preference.BiometricLock
import com.philkes.notallyx.presentation.viewmodel.preference.NotallyXPreferences
import com.philkes.notallyx.utils.getExternalMediaDirectory
import com.philkes.notallyx.utils.log
import com.philkes.notallyx.utils.security.getInitializedCipherForDecryption
import com.philkes.notallyx.utils.security.isEncryptedDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@TypeConverters(Converters::class)
@Database(entities = [BaseNote::class, Label::class, NoteFts::class], version = 13)
abstract class NotallyDatabase : RoomDatabase() {

    abstract fun getLabelDao(): LabelDao

    abstract fun getCommonDao(): CommonDao

    abstract fun getBaseNoteDao(): BaseNoteDao

    fun checkpoint() {
        getBaseNoteDao().query(SimpleSQLiteQuery("pragma wal_checkpoint(FULL)"))
    }

    fun ping() = getBaseNoteDao().query(SimpleSQLiteQuery("SELECT 1")) == 1

    companion object {

        const val DATABASE_NAME = "NotallyDatabase"

        @Volatile private var instance: NotNullLiveData<NotallyDatabase>? = null
        @Volatile private var replacementInProgress = false

        fun getCurrentDatabaseFile(context: ContextWrapper): File {
            return if (NotallyXPreferences.getInstance(context).dataInPublicFolder.value) {
                getExternalDatabaseFile(context)
            } else {
                getInternalDatabaseFile(context)
            }
        }

        fun getExternalDatabaseFile(context: ContextWrapper): File {
            return File(context.getExternalMediaDirectory(), DATABASE_NAME)
        }

        fun getExternalDatabaseFiles(context: ContextWrapper): List<File> {
            return listOf(
                    File(context.getExternalMediaDirectory(), DATABASE_NAME),
                    File(context.getExternalMediaDirectory(), "$DATABASE_NAME-shm"),
                    File(context.getExternalMediaDirectory(), "$DATABASE_NAME-wal"),
                )
                .filter { it.exists() }
        }

        fun getInternalDatabaseFile(context: Context): File {
            return context.getDatabasePath(DATABASE_NAME)
        }

        fun getInternalDatabaseFiles(context: ContextWrapper): List<File> {
            val directory = context.getDatabasePath(DATABASE_NAME).parentFile
            return listOf(
                    File(directory, DATABASE_NAME),
                    File(directory, "$DATABASE_NAME-shm"),
                    File(directory, "$DATABASE_NAME-wal"),
                )
                .filter { it.exists() }
        }

        private fun getCurrentDatabaseName(
            context: ContextWrapper,
            dataInPublicFolder: Boolean,
        ): String {
            return if (dataInPublicFolder) {
                getExternalDatabaseFile(context).absolutePath
            } else {
                DATABASE_NAME
            }
        }

        @MainThread
        fun getDatabase(context: ContextWrapper): NotNullLiveData<NotallyDatabase> {
            return instance?.also {
                if (!replacementInProgress && !it.value.isOpen) {
                    it.value = createInstance(context, NotallyXPreferences.getInstance(context))
                }
            }
                ?: synchronized(this) {
                    val preferences = NotallyXPreferences.getInstance(context)
                    this.instance = NotNullLiveData(createInstance(context, preferences))
                    return this.instance!!
                }
        }

        fun clearInstance() {
            this.instance?.value?.let { database ->
                if (database.isOpen) {
                    database.close()
                }
            }
        }

        fun startReplacement() {
            replacementInProgress = true
        }

        fun endReplacement() {
            replacementInProgress = false
        }

        fun isBeingReplaced() = replacementInProgress

        private var testInstance: NotallyDatabase? = null

        private fun getTestDatabase(context: ContextWrapper): NotallyDatabase {
            return testInstance
                ?: synchronized(this) {
                    testInstance =
                        Room.inMemoryDatabaseBuilder(context, NotallyDatabase::class.java)
                            .allowMainThreadQueries()
                            .build()
                    return testInstance!!
                }
        }

        @MainThread
        fun getFreshDatabase(
            context: ContextWrapper,
            dataInPublic: Boolean,
            biometricLock: BiometricLock,
        ): NotallyDatabase {
            return if (isTestRunner()) {
                getTestDatabase(context)
            } else {
                createInstance(
                    context,
                    NotallyXPreferences.getInstance(context),
                    dataInPublic = dataInPublic,
                    biometricLock = biometricLock,
                )
            }
        }

        @MainThread
        private fun createInstance(
            context: ContextWrapper,
            preferences: NotallyXPreferences,
            dataInPublic: Boolean = preferences.dataInPublicFolder.value,
            biometricLock: BiometricLock = preferences.biometricLock.value,
        ): NotallyDatabase {
            Log.d(
                DATABASE_NAME,
                "Creating database instance with dataInPublic: '$dataInPublic' and biometric lock: '$biometricLock'",
            )
            clearInstance()
            val instanceBuilder =
                builder(context, dataInPublic)
                    .openHelperFactory(BackupOpenHelperFactory(context))
                    .setupEncryption(context, preferences, biometricLock)
            return instanceBuilder.build()
        }

        internal fun builder(
            context: ContextWrapper,
            dataInPublic: Boolean,
        ): Builder<NotallyDatabase> =
            Room.databaseBuilder(
                    context,
                    NotallyDatabase::class.java,
                    getCurrentDatabaseName(context, dataInPublic),
                )
                .addMigrations(
                    Migration2,
                    Migration3,
                    Migration4,
                    Migration5,
                    Migration6,
                    Migration7,
                    Migration8,
                    Migration9,
                    Migration10,
                    Migration11,
                    Migration12,
                    Migration13,
                )

        @VisibleForTesting
        internal fun Builder<NotallyDatabase>.setupEncryption(
            context: ContextWrapper,
            preferences: NotallyXPreferences,
            biometricLock: BiometricLock = preferences.biometricLock.value,
        ): Builder<NotallyDatabase> {
            return this.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (biometricLock == BiometricLock.ENABLED) {
                        if (getCurrentDatabaseFile(context).isEncryptedDatabase(context)) {
                            initializeDecryption(context, preferences, this)
                        } else {
                            context.log(
                                DATABASE_NAME,
                                "Database is not encrypted even though biometric lock is enabled, disabling biometric lock",
                            )
                            preferences.biometricLock.save(BiometricLock.DISABLED)
                        }
                    } else {
                        if (getCurrentDatabaseFile(context).isEncryptedDatabase(context)) {
                            context.log(
                                DATABASE_NAME,
                                "Database is encrypted even though biometric lock is disabled, enabling biometric lock",
                            )
                            preferences.biometricLock.save(BiometricLock.ENABLED)
                            initializeDecryption(context, preferences, this)
                        }
                    }
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private fun initializeDecryption(
            context: ContextWrapper,
            preferences: NotallyXPreferences,
            instanceBuilder: Builder<NotallyDatabase>,
        ) {
            System.loadLibrary("sqlcipher")
            val initializationVector = preferences.iv.value!!
            val cipher = getInitializedCipherForDecryption(iv = initializationVector)
            val encryptedPassphrase = preferences.databaseEncryptionKey.value
            val passphrase = cipher.doFinal(encryptedPassphrase)
            val factory = BackupOpenHelperFactory(context, SupportOpenHelperFactory(passphrase))
            instanceBuilder.openHelperFactory(factory)
        }

        @MainThread
        fun postNewInstance(
            context: ContextWrapper,
            dataInPublic: Boolean? = null,
            biometricLock: BiometricLock? = null,
        ) {
            val preferences = NotallyXPreferences.getInstance(context)
            val notallyDatabase =
                getFreshDatabase(
                    context,
                    dataInPublic ?: preferences.dataInPublicFolder.value,
                    biometricLock ?: preferences.biometricLock.value,
                )
            postInstance(notallyDatabase)
        }

        @MainThread
        fun postInstance(notallyDatabase: NotallyDatabase) {
            synchronized(this) {
                val current = instance
                if (current == null) {
                    instance = NotNullLiveData(notallyDatabase)
                } else {
                    current.value = notallyDatabase
                }
                replacementInProgress = false
            }
        }

        /**
         * Idempotent ALTER TABLE ADD COLUMN: some shipped schema exports drifted from the migration
         * history, so an upgrading database may already carry the column. Skipping the ALTER (and
         * any backfill tied to it) is always safe; re-running it is a crash.
         */
        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String,
        ): Boolean {
            val tableName = table.replace("`", "")
            val columnName = column.replace("`", "")
            val cursor = db.query("PRAGMA table_info(`$tableName`)")
            val existing = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    existing.add(it.getString(it.getColumnIndexOrThrow("name")))
                }
            }
            if (columnName in existing) {
                return false
            }
            db.execSQL("ALTER TABLE `$table` ADD COLUMN $column $definition")
            return true
        }

        object Migration2 : Migration(1, 2) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "color", "TEXT NOT NULL DEFAULT 'DEFAULT'")
            }
        }

        object Migration3 : Migration(2, 3) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "images", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        object Migration4 : Migration(3, 4) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "audios", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        object Migration5 : Migration(4, 5) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "files", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        object Migration6 : Migration(5, 6) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "BaseNote",
                    "modifiedTimestamp",
                    "INTEGER NOT NULL DEFAULT 0",
                )
                // Unconditional: repairs rows corrupted by the historic DEFAULT 'timestamp' bug
                // and backfills fresh columns; idempotent either way.
                db.execSQL("UPDATE BaseNote SET modifiedTimestamp = timestamp")
            }
        }

        object Migration7 : Migration(6, 7) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "reminders", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        object Migration8 : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT id, color FROM BaseNote")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                    val colorString = cursor.getString(cursor.getColumnIndexOrThrow("color"))
                    val color = Color.valueOfOrDefault(colorString)
                    val hexColor = color.toColorString()
                    db.execSQL("UPDATE BaseNote SET color = ? WHERE id = ?", arrayOf(hexColor, id))
                }
                cursor.close()
            }
        }

        object Migration9 : Migration(8, 9) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    db,
                    "BaseNote",
                    "viewMode",
                    "TEXT NOT NULL DEFAULT '${NoteViewMode.EDIT.name}'",
                )
            }
        }

        object Migration10 : Migration(9, 10) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "BaseNote", "isPinnedToStatus", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        object Migration11 : Migration(10, 11) {

            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "Label", "`order`", "INTEGER NOT NULL DEFAULT 0")
                val cursor = db.query("SELECT value FROM Label ORDER BY value DESC")
                var order = 0
                while (cursor.moveToNext()) {
                    val value = cursor.getString(0)
                    db.execSQL(
                        "UPDATE Label SET `order` = ? WHERE value = ?",
                        arrayOf(order, value),
                    )
                    order++
                }
                cursor.close()
            }
        }

        /**
         * Adds the NoteFts FTS4 external-content index over BaseNote(title, body, items).
         *
         * Fully additive and idempotent: the virtual table and sync triggers are created IF NOT
         * EXISTS, and `INSERT INTO NoteFts(NoteFts) VALUES('rebuild')` (re)populates the index from
         * the content table without touching any BaseNote/Label row.
         */
        object Migration12 : Migration(11, 12) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `NoteFts` USING FTS4(`title` TEXT NOT NULL, `body` TEXT NOT NULL, `items` TEXT NOT NULL, content=`BaseNote`)"
                )
                createFtsSyncTriggers(db)
                // Backfills the index from existing rows; safe to re-run at any time.
                db.execSQL("INSERT INTO NoteFts(`NoteFts`) VALUES('rebuild')")
            }

            /**
             * Mirrors the triggers Room generates for @Fts4(contentEntity = BaseNote::class) on
             * fresh installs — without them an upgraded database's index would silently go stale.
             */
            private fun createFtsSyncTriggers(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_NoteFts_BEFORE_UPDATE BEFORE UPDATE ON `BaseNote` BEGIN DELETE FROM `NoteFts` WHERE docid=OLD.`rowid`; END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_NoteFts_BEFORE_DELETE BEFORE DELETE ON `BaseNote` BEGIN DELETE FROM `NoteFts` WHERE docid=OLD.`rowid`; END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_NoteFts_AFTER_UPDATE AFTER UPDATE ON `BaseNote` BEGIN INSERT INTO `NoteFts`(docid, `title`, `body`, `items`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`, NEW.`items`); END"
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_NoteFts_AFTER_INSERT AFTER INSERT ON `BaseNote` BEGIN INSERT INTO `NoteFts`(docid, `title`, `body`, `items`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`, NEW.`items`); END"
                )
            }
        }

        /**
         * Phase 8 performance: adds query-shape indices over BaseNote (see [BaseNote] KDoc).
         *
         * Fully additive and idempotent: `CREATE INDEX IF NOT EXISTS` only, no table/column
         * changes, no user data touched. Index names mirror what Room generates for the
         * updated @Entity, so a migrated database matches a fresh-install schema exactly.
         */
        object Migration13 : Migration(12, 13) {

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_BaseNote_folder_pinned_timestamp` ON `BaseNote` (`folder`, `pinned`, `timestamp`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_BaseNote_folder_modifiedTimestamp` ON `BaseNote` (`folder`, `modifiedTimestamp`)"
                )
            }
        }
    }
}
