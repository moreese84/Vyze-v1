package com.vyze.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Migration-chain gate (whole-app audit P1 #8, 2026-11-09).
 *
 * The 2→6 chain must be contiguous: any gap bricks upgrading installs
 * at first open ("A migration from X to 6 was required but not found").
 * History note: version 1 never existed (the database was born at
 * version 2 in dbd68e3 with a destructive fallback), so there is no
 * 1→2 migration to write — a v1 file can only be foreign, and the
 * builder wipes it via fallbackToDestructiveMigrationFrom(1) instead
 * of crashing. This test pins the 2→current contiguity plus G2-style
 * execution of every migration's SQL on a real engine.
 */
class MigrationChainTest {

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
    fun `migration chain starts at 2 and reaches the current version without gaps`() {
        // Room's @Database annotation is not runtime-visible, so the
        // current version is pinned literally — keep in sync with the
        // @Database version in VyzeDatabase (bump + extend the chain).
        val dbVersion = 7
        val chain = VyzeDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }

        assertTrue("chain must not be empty", chain.isNotEmpty())
        assertEquals("chain must start at 2 (v1 never existed)", 2, chain.first().startVersion)
        chain.zipWithNext { prev, next ->
            assertEquals(
                "gap between ${prev.startVersion}→${prev.endVersion} and " +
                    "${next.startVersion}→${next.endVersion}",
                prev.endVersion, next.startVersion,
            )
        }
        assertEquals("chain must reach the current DB version", dbVersion, chain.last().endVersion)
    }

    @Test
    fun `migration 6 to 7 dedupes preferences and enforces the key`() {
        // Audit P2 #18: upsert(REPLACE) conflicts only on the id PK, so
        // fixed-key writes (preferences) piled duplicates — the KDoc's
        // "replaces on category+key conflict" was false without a unique
        // index. 6→7 keeps the latest row per key, then enforces it.
        VyzeDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }
            .filter { it.startVersion < 6 }
            .forEach { it.migrate(db) }
        conn.createStatement().use { st ->
            st.execute(
                "INSERT INTO vyze_memory (category, `key`, value, timestamp, metadata) VALUES " +
                    "('preference', 'brevity', 'short', 100, '')," +
                    "('preference', 'brevity', 'detailed', 200, '')," +
                    "('preference', 'brevity', 'short', 300, '')"
            )
        }
        VyzeDatabase.MIGRATION_6_7.migrate(db)
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT value FROM vyze_memory WHERE category = 'preference' AND `key` = 'brevity'",
            ).use { rs ->
                assertTrue("surviving row missing", rs.next())
                assertEquals("short", rs.getString(1))
                assertFalse("duplicate survived", rs.next())
            }
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_vyze_memory_category_key'",
            ).use { rs ->
                assertTrue("unique index missing", rs.next())
            }
            // REPLACE now honors category+key instead of piling rows.
            st.execute(
                "INSERT OR REPLACE INTO vyze_memory (category, `key`, value, timestamp, metadata) " +
                    "VALUES ('preference', 'brevity', 'replaced', 400, '')"
            )
            st.executeQuery("SELECT COUNT(*) FROM vyze_memory").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `every migration executes in order on a real engine`() {
        // G2 lesson applied to the whole chain: fresh installs build
        // tables from entities and never run these statements, so SQL
        // errors here only strike upgraders — execute them all in order.
        VyzeDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }.forEach { it.migrate(db) }
        // vyze_memory + interaction_records + medicines all exist now.
        val tables = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
            ).use { rs ->
                while (rs.next()) tables.add(rs.getString(1))
            }
        }
        assertTrue("vyze_memory missing: $tables", tables.contains("vyze_memory"))
        assertTrue("interaction_records missing: $tables", tables.contains("interaction_records"))
        assertTrue("medicines missing: $tables", tables.contains("medicines"))
    }
}
