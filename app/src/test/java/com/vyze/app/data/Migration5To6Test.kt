package com.vyze.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * G2 regression gate — EXECUTES [VyzeDatabase.MIGRATION_5_6]'s statements
 * against a real SQLite engine (pure JVM, no device needed).
 *
 * The 5→6 CREATE TABLE originally ended `searchName TEXT NOT` (missing
 * NULL) — a SQLite syntax error that crashed EVERY install upgrading from
 * DB version ≤5 at first open. Fresh installs never saw it because onCreate
 * builds tables from the entities instead of running migrations, which is
 * exactly why no dev device reproduced it.
 *
 * The house pattern for migration SQL is "it looks right"; this test makes
 * the DB engine the judge:
 *  1. every statement in migrate() executes (the G2 class of bug),
 *  2. the resulting schema matches [MedicineEntity]'s contract — Room
 *     validates the migrated schema against the generated table info at
 *     open and throws "Migration didn't properly handle" on any mismatch,
 *  3. an entity-shaped row inserts through every NOT NULL column, and the
 *     unique searchName index is actually enforced.
 *
 * [SupportSQLiteDatabase] is bridged via a reflection proxy that forwards
 * only execSQL — the single seam migrate() uses — so the test never
 * hard-codes the interface's method surface (and needs no Robolectric).
 */
class Migration5To6Test {

    private lateinit var conn: Connection
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "execSQL" && args?.getOrNull(0) is String -> {
                    conn.createStatement().use { it.execute(args[0] as String) }
                    null
                }
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.getOrNull(0)
                method.name == "toString" -> "JdbcExecForwarder"
                else -> throw UnsupportedOperationException(
                    "migration test only needs execSQL, got '${method.name}'",
                )
            }
        } as SupportSQLiteDatabase
    }

    @After
    fun tearDown() {
        conn.close()
    }

    @Test
    fun `migration 5 to 6 executes without error`() {
        // G2: pre-fix this threw a SQLite syntax error near "NOT" — the
        // exact crash an upgrading (DB ≤5) install hit at first open.
        VyzeDatabase.MIGRATION_5_6.migrate(db)
        // All statements are IF NOT EXISTS — a re-run must stay clean too.
        VyzeDatabase.MIGRATION_5_6.migrate(db)
    }

    @Test
    fun `migrated schema matches the MedicineEntity contract`() {
        VyzeDatabase.MIGRATION_5_6.migrate(db)

        // Columns: name → (isPrimary, notNull) exactly as Room's open-time
        // table-info validation will compare them.
        val info = LinkedHashMap<String, Pair<Boolean, Boolean>>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`medicines`)").use { rs ->
                while (rs.next()) {
                    info[rs.getString("name")] =
                        (rs.getInt("pk") != 0) to (rs.getInt("notnull") != 0)
                }
            }
        }
        val expected = listOf(
            "id", "name", "genericName", "dosage",
            "frequency", "warnings", "category", "searchName",
        )
        assertEquals(expected.sorted(), info.keys.sorted())
        // Every TEXT column is NOT NULL — including the G2 column. (SQLite
        // reports notnull=0 for the INTEGER PRIMARY KEY itself.)
        expected.filter { it != "id" }.forEach { col ->
            assertTrue("$col must be NOT NULL", info.getValue(col).second)
        }
        assertTrue("id must be the primary key", info.getValue("id").first)

        // Entity indices: unique searchName + category.
        val indices = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='medicines'",
            ).use { rs ->
                while (rs.next()) indices.add(rs.getString(1))
            }
        }
        assertTrue(
            "unique index missing: $indices",
            indices.contains("index_medicines_searchName"),
        )
        assertTrue(
            "category index missing: $indices",
            indices.contains("index_medicines_category"),
        )
    }

    @Test
    fun `entity-shaped row inserts and the unique searchName index enforces`() {
        VyzeDatabase.MIGRATION_5_6.migrate(db)
        val row = (
            "INSERT INTO `medicines` " +
                "(`name`, `genericName`, `dosage`, `frequency`, `warnings`, `category`, `searchName`) VALUES (" +
                "'Diclac Retard', 'Diclofenac sodium', '100mg', 'One tablet daily', " +
                "'Avoid if allergic to NSAIDs', 'Pain relief', 'diclac retard')"
            )
        conn.createStatement().use { st ->
            st.executeUpdate(row)
            // Unique index is enforced, not decorative: same searchName again fails.
            assertThrows(java.sql.SQLException::class.java) {
                conn.createStatement().use { it.executeUpdate(row) }
            }
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `medicines`").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }
    }
}
