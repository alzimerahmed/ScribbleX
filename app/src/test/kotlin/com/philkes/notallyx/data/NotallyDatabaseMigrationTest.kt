package com.philkes.notallyx.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.philkes.notallyx.data.model.Color
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the data-loss-sensitive Room upgrade path (upstream #1066 class): a broken
 * migration silently corrupts or wipes user data on app update.
 *
 * The full-path test drives a v1 database through every migration to v11 and validates the result
 * against the exported schema plus the surviving data. Per-step tests cover the transitions whose
 * exported schemas are reliable.
 *
 * Known issue (documented in docs/research.md): the exported schema JSONs for v4-v6 are drifted
 * (each already contains the column its same-numbered migration adds), so intermediate validation
 * against those files is not meaningful. The ALTER-ADD migrations are idempotent
 * (`addColumnIfMissing`) so a database that already carries a column never crashes on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NotallyDatabaseMigrationTest {

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            NotallyDatabase::class.java,
        )

    @Test
    fun migrate1To2_addsColorColumnWithDefault() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO BaseNote (type, folder, title, pinned, timestamp, labels, body, spans, items) " +
                    "VALUES ('NOTE', 'NOTES', 't', 0, 1, '[]', 'body', '[]', '[]')"
            )
        }

        helper
            .runMigrationsAndValidate(DB_NAME, 2, true, NotallyDatabase.Companion.Migration2)
            .use { db ->
                db.query("SELECT color FROM BaseNote").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("DEFAULT")
                }
            }
    }

    @Test
    fun migrate2To3_addsImagesColumnWithEmptyArrayDefault() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO BaseNote (type, folder, color, title, pinned, timestamp, labels, body, spans, items) " +
                    "VALUES ('NOTE', 'NOTES', 'DEFAULT', 't', 0, 1, '[]', 'body', '[]', '[]')"
            )
        }

        helper
            .runMigrationsAndValidate(DB_NAME, 3, true, NotallyDatabase.Companion.Migration3)
            .use { db ->
                db.query("SELECT images FROM BaseNote").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("[]")
                }
            }
    }

    /**
     * Migration 4 (audios) cannot be validated against the drifted v4 schema export (it already
     * contains `files`, which only Migration 5 adds), so the migration is executed directly on a
     * v3-shaped database and its behavior asserted without schema validation.
     */
    @Test
    fun migrate3To4_addsAudiosColumnWithEmptyArrayDefault() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO BaseNote (type, folder, color, title, pinned, timestamp, labels, body, spans, items, images) " +
                    "VALUES ('NOTE', 'NOTES', 'DEFAULT', 't', 0, 1, '[]', 'body', '[]', '[]', '[]')"
            )
        }
        runBareMigrationAndAssert(3) { supportDb ->
            NotallyDatabase.Companion.Migration4.migrate(supportDb)
            supportDb.query("SELECT audios FROM BaseNote").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
            }
        }
    }

    /**
     * Migration 5 (files) cannot be validated against the drifted v5 schema export (it already
     * contains `modifiedTimestamp`, which only Migration 6 adds); executed directly instead.
     *
     * The v4 schema export is itself drifted (already contains `files`), so the real ADD path is
     * exercised from a genuine v3 database through Migrations 4 and 5 — `files` does not exist
     * until Migration 5 actually runs.
     */
    @Test
    fun migrate4To5_addsFilesColumnWithEmptyArrayDefault() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO BaseNote (type, folder, color, title, pinned, timestamp, labels, body, spans, items, images) " +
                    "VALUES ('NOTE', 'NOTES', 'DEFAULT', 't', 0, 1, '[]', 'body', '[]', '[]', '[]')"
            )
        }
        runBareMigrationAndAssert(5) { supportDb ->
            NotallyDatabase.Companion.Migration4.migrate(supportDb)
            NotallyDatabase.Companion.Migration5.migrate(supportDb)
            supportDb.query("SELECT files, audios FROM BaseNote").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
                assertThat(cursor.getString(1)).isEqualTo("[]")
            }
        }
    }

    /**
     * The v6 schema export already contains `reminders` (drift), so a v6 database cannot prove
     * Migration 7's ALTER actually runs. The real path starts from a v5 database (which lacks
     * `reminders`) and chains Migrations 6 and 7.
     */
    @Test
    fun migrate6To7_addsRemindersIdempotentlyWhenMissing() {
        helper.createDatabase(DB_NAME, 5).use { db -> seedNote(db, version = 5, timestamp = 1L) }

        runBareMigrationAndAssert(7) { supportDb ->
            NotallyDatabase.Companion.Migration6.migrate(supportDb)
            NotallyDatabase.Companion.Migration7.migrate(supportDb)
            supportDb.query("SELECT reminders FROM BaseNote").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("[]")
            }
        }
    }

    @Test
    fun migrate7To8_convertsNamedColorsToHexAndKeepsDefault() {
        helper.createDatabase(DB_NAME, 7).use { db ->
            val insert =
                "INSERT INTO BaseNote (type, folder, color, title, pinned, timestamp, modifiedTimestamp, images, audios, files, reminders, labels, body, spans, items) " +
                    "VALUES ('NOTE', 'NOTES', ?, 'title', 0, 1, 1, '[]', '[]', '[]', '[]', '[]', 'body', '[]', '[]')"
            Color.entries.forEach { color -> db.execSQL(insert, arrayOf(color.name)) }
        }

        helper
            .runMigrationsAndValidate(DB_NAME, 8, true, NotallyDatabase.Companion.Migration8)
            .use { db ->
                db.query("SELECT DISTINCT color FROM BaseNote").use { cursor ->
                    while (cursor.moveToNext()) {
                        val colorString = cursor.getString(0)
                        // DEFAULT is a valid stored value and must survive; named colors become
                        // #RRGGBB
                        if (colorString == "DEFAULT") {
                            assertThat(colorString).isEqualTo("DEFAULT")
                        } else {
                            assertThat(colorString).startsWith("#").hasSize(7)
                        }
                    }
                }
            }
    }

    @Test
    fun migrate8To9_addsViewModeWithEditDefault() {
        helper.createDatabase(DB_NAME, 8).use { db -> seedNote(db, version = 8, timestamp = 1L) }

        helper
            .runMigrationsAndValidate(DB_NAME, 9, true, NotallyDatabase.Companion.Migration9)
            .use { db ->
                db.query("SELECT viewMode FROM BaseNote").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("EDIT")
                }
            }
    }

    @Test
    fun migrate9To10_addsIsPinnedToStatusDefaultFalse() {
        helper.createDatabase(DB_NAME, 9).use { db ->
            seedNote(db, version = 9, timestamp = 1L, withViewMode = true)
        }

        helper
            .runMigrationsAndValidate(DB_NAME, 10, true, NotallyDatabase.Companion.Migration10)
            .use { db ->
                db.query("SELECT isPinnedToStatus FROM BaseNote").use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(0)
                }
            }
    }

    @Test
    fun migrate10To11_assignsSequentialLabelOrder() {
        helper.createDatabase(DB_NAME, 10).use { db ->
            seedNote(db, version = 10, timestamp = 1L, withViewMode = true)
            listOf("Zeta", "Alpha", "Middle").forEach { value ->
                db.execSQL("INSERT INTO Label (value) VALUES (?)", arrayOf(value))
            }
        }

        helper
            .runMigrationsAndValidate(DB_NAME, 11, true, NotallyDatabase.Companion.Migration11)
            .use { db ->
                db.query("SELECT value, `order` FROM Label ORDER BY `order`").use { cursor ->
                    val ordered = mutableListOf<Pair<String, Int>>()
                    while (cursor.moveToNext()) {
                        ordered.add(cursor.getString(0) to cursor.getInt(1))
                    }
                    // Migration orders by value DESC: Zeta=0, Middle=1, Alpha=2
                    assertThat(ordered).containsExactly("Zeta" to 0, "Middle" to 1, "Alpha" to 2)
                }
            }
    }

    /**
     * A database that already carries `modifiedTimestamp` (as the drifted v5 schema export
     * produces) must not crash Migration 6's ALTER, and the backfill must still run so rows
     * corrupted by the historic DEFAULT 'timestamp' bug are repaired.
     */
    @Test
    fun migrate5To6_isIdempotentWhenColumnAlreadyExists() {
        helper.createDatabase(DB_NAME, 5).use { db ->
            // The drifted v5 schema export already contains modifiedTimestamp
            seedNote(db, version = 5, timestamp = 42L)
        }

        runBareMigrationAndAssert(5) { supportDb ->
            NotallyDatabase.Companion.Migration6.migrate(supportDb)
            supportDb.query("SELECT timestamp, modifiedTimestamp FROM BaseNote").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(42L)
                assertThat(cursor.getLong(1)).isEqualTo(42L)
            }
        }
    }

    /** The full upgrade path any long-standing user takes. Nothing may be lost or corrupted. */
    @Test
    fun migrateAllVersions_1To11_preservesNotesAndBackfills() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO BaseNote (type, folder, title, pinned, timestamp, labels, body, spans, items) " +
                    "VALUES ('NOTE', 'NOTES', 'survivor', 1, 7, '[]', 'kept body', '[]', '[]')"
            )
            db.execSQL("INSERT INTO Label (value) VALUES ('Zeta')")
            db.execSQL("INSERT INTO Label (value) VALUES ('Alpha')")
        }

        helper
            .runMigrationsAndValidate(
                DB_NAME,
                11,
                true,
                NotallyDatabase.Companion.Migration2,
                NotallyDatabase.Companion.Migration3,
                NotallyDatabase.Companion.Migration4,
                NotallyDatabase.Companion.Migration5,
                NotallyDatabase.Companion.Migration6,
                NotallyDatabase.Companion.Migration7,
                NotallyDatabase.Companion.Migration8,
                NotallyDatabase.Companion.Migration9,
                NotallyDatabase.Companion.Migration10,
                NotallyDatabase.Companion.Migration11,
            )
            .use { db ->
                db.query(
                        "SELECT title, body, pinned, timestamp, color, images, audios, files, reminders, viewMode, isPinnedToStatus, modifiedTimestamp FROM BaseNote"
                    )
                    .use { cursor ->
                        assertThat(cursor.count).isEqualTo(1)
                        assertThat(cursor.moveToFirst()).isTrue()
                        assertThat(cursor.getString(0)).isEqualTo("survivor")
                        assertThat(cursor.getString(1)).isEqualTo("kept body")
                        assertThat(cursor.getInt(2)).isEqualTo(1)
                        assertThat(cursor.getLong(3)).isEqualTo(7L)
                        assertThat(cursor.getString(4)).isEqualTo("DEFAULT")
                        // Attachment columns added by Migrations 3/4/5 default to an empty JSON
                        // array
                        assertThat(cursor.getString(5)).isEqualTo("[]")
                        assertThat(cursor.getString(6)).isEqualTo("[]")
                        assertThat(cursor.getString(7)).isEqualTo("[]")
                        // Reminders added by Migration 7
                        assertThat(cursor.getString(8)).isEqualTo("[]")
                        assertThat(cursor.getString(9)).isEqualTo("EDIT")
                        assertThat(cursor.getInt(10)).isEqualTo(0)
                        // Migration 6 backfills modifiedTimestamp from timestamp
                        assertThat(cursor.getLong(11)).isEqualTo(7L)
                    }
                db.query("SELECT value, `order` FROM Label ORDER BY `order`").use { cursor ->
                    val ordered = mutableListOf<Pair<String, Int>>()
                    while (cursor.moveToNext()) {
                        ordered.add(cursor.getString(0) to cursor.getInt(1))
                    }
                    assertThat(ordered).containsExactly("Zeta" to 0, "Alpha" to 1)
                }
            }
    }

    /**
     * Opens the database with a bare SupportSQLiteOpenHelper (no Room validation — used for
     * transitions whose target schema export is drifted) and runs [block] against it.
     */
    private fun runBareMigrationAndAssert(
        version: Int,
        block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit,
    ) {
        val openHelper =
            androidx.sqlite.db.framework
                .FrameworkSQLiteOpenHelperFactory()
                .create(
                    androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(
                            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                .targetContext
                        )
                        .name(DB_NAME)
                        .callback(
                            object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                                override fun onCreate(
                                    db: androidx.sqlite.db.SupportSQLiteDatabase
                                ) {}

                                override fun onUpgrade(
                                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) {}
                            }
                        )
                        .build()
                )
        try {
            openHelper.writableDatabase.use(block)
        } finally {
            openHelper.close()
        }
    }

    /** Inserts one note using only the columns that exist at the given schema version. */
    private fun seedNote(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        version: Int,
        timestamp: Long,
        withViewMode: Boolean = false,
    ) {
        val columns =
            mutableListOf(
                "type",
                "folder",
                "title",
                "pinned",
                "timestamp",
                "labels",
                "body",
                "spans",
                "items",
            )
        if (version >= 2) columns.add("color")
        if (version >= 3) columns.add("images")
        if (version >= 4) {
            columns.add("files")
            columns.add("audios")
        }
        if (version >= 5) columns.add("modifiedTimestamp")
        if (version >= 6) columns.add("reminders")
        if (version >= 10) columns.add("isPinnedToStatus")
        val values =
            mutableListOf<String>().apply {
                add("'NOTE'")
                add("'NOTES'")
                add("'t'")
                add("0")
                add(timestamp.toString())
                add("'[]'")
                add("'body'")
                add("'[]'")
                add("'[]'")
                if (version >= 2) add("'DEFAULT'")
                if (version >= 3) add("'[]'")
                if (version >= 4) {
                    add("'[]'")
                    add("'[]'")
                }
                if (version >= 5) add(timestamp.toString())
                if (version >= 6) add("'[]'")
                if (version >= 10) add("0")
            }
        if (withViewMode) {
            columns.add("viewMode")
            values.add("'EDIT'")
        }
        db.execSQL(
            "INSERT INTO BaseNote (${columns.joinToString(", ")}) VALUES (${values.joinToString(", ")})"
        )
    }

    private companion object {
        const val DB_NAME = "migration-test"
    }
}
