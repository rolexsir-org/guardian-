package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 6 → 7 adds `incidents.reportedBy` so a safety event can say who filed it
 * instead of silently attributing every report to the current user.
 *
 * The test opens a real SQLite database at the previous version, runs the shipped
 * `Migration` object against it, and checks that existing rows survive with an
 * honest (null) reporter. This is the same migration Room runs on an upgrade, so a
 * regression here fails before it reaches a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

  private lateinit var context: Context

  /** `incidents` exactly as version 6 created it: no `reportedBy` column. */
  private val incidentsAtVersion6 = """
    CREATE TABLE IF NOT EXISTS `incidents` (
      `id` TEXT NOT NULL PRIMARY KEY,
      `title` TEXT NOT NULL,
      `category` TEXT NOT NULL,
      `severity` TEXT NOT NULL,
      `description` TEXT NOT NULL,
      `latitude` REAL,
      `longitude` REAL,
      `timestamp` INTEGER NOT NULL,
      `upvotes` INTEGER NOT NULL,
      `localOnly` INTEGER NOT NULL
    )
  """.trimIndent()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.deleteDatabase(DB_NAME)
  }

  @After
  fun tearDown() {
    context.deleteDatabase(DB_NAME)
  }

  @Test
  fun migrationSixToSevenDeclaresTheVersionsItMovesBetween() {
    assertEquals(6, GuardianDatabase.MIGRATION_6_7.startVersion)
    assertEquals(7, GuardianDatabase.MIGRATION_6_7.endVersion)
  }

  @Test
  fun upgradingFromVersionSixKeepsRowsAndAddsTheReporterColumn() {
    openAt(version = 6).use { legacy ->
      legacy.execSQL(
        "INSERT INTO incidents (id, title, category, severity, description, latitude, longitude, " +
          "timestamp, upvotes, localOnly) VALUES ('legacy-1', 'Broken street light', 'Hazard', 'Low', " +
          "'Dark crossing', NULL, NULL, 1700000000000, 2, 1)",
      )
    }

    openAt(version = 7).use { upgraded ->
      upgraded.query("SELECT id, title, reportedBy FROM incidents ORDER BY id").use { cursor ->
        assertTrue("The row written at version 6 must still be there.", cursor.moveToFirst())
        assertEquals("legacy-1", cursor.getString(0))
        assertEquals("Broken street light", cursor.getString(1))
        assertTrue(
          "An existing report has no known author; it must not be attributed to the current user.",
          cursor.isNull(2),
        )
        assertEquals(1, cursor.count)
      }
    }
  }

  @Test
  fun aNewlyCreatedVersionSevenDatabaseAlreadyHasTheColumn() {
    openAt(version = 7, fresh = true).use { created ->
      created.query("SELECT reportedBy FROM incidents").use { cursor ->
        assertEquals(1, cursor.columnCount)
        assertEquals(0, cursor.count)
      }
    }
  }

  private fun openAt(version: Int, fresh: Boolean = false): SupportSQLiteDatabase {
    val factory = FrameworkSQLiteOpenHelperFactory()
    val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
      .name(DB_NAME)
      .callback(
        object : SupportSQLiteOpenHelper.Callback(version) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(incidentsAtVersion6)
            if (version >= 7 && fresh) db.execSQL("ALTER TABLE `incidents` ADD COLUMN `reportedBy` TEXT")
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 7 && newVersion >= 7) GuardianDatabase.MIGRATION_6_7.migrate(db)
          }
        },
      )
      .build()
    return factory.create(configuration).writableDatabase
  }

  private companion object {
    const val DB_NAME = "migration_test.db"
  }
}
