package com.vyze.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM tests for synchronous idempotent medicine seeding (whole-app
 * audit P2 #16/#17, 2026-11-09).
 *
 * The seed ran once in onCreate, fire-and-forget: upgraded DBs kept an
 * empty medicines table forever (#16), and fresh installs raced
 * readers against the background insert (#17). Seeding is now a set of
 * idempotent INSERT..WHERE NOT EXISTS statements executed synchronously
 * in onOpen (inside the open lock, before the first query returns).
 * These tests execute the REAL generated statements against the REAL
 * migration-created table on sqlite-jdbc — no SQL copies.
 */
class MedicineSeedTest {

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
                    "seed test only needs execSQL, got '${method.name}'",
                )
            }
        } as SupportSQLiteDatabase
        // Real table shape, courtesy of the real migration.
        VyzeDatabase.MIGRATION_5_6.migrate(db)
    }

    @After
    fun tearDown() {
        conn.close()
    }

    private fun rowCount(): Int {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM medicines").use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    @Test
    fun `seed fills the empty table exactly once`() {
        val statements = MedicineDatabaseCallback.companionMedicineSeedStatements(
            MedicineDatabaseCallback.commonMedicinesForTests()
        )
        assertTrue("seed list must not be empty", statements.isNotEmpty())
        statements.forEach { db.execSQL(it) }
        val firstCount = rowCount()
        assertEquals(statements.size, firstCount)
        // Idempotent: every later open re-runs these with no dupes.
        statements.forEach { db.execSQL(it) }
        assertEquals(firstCount, rowCount())
    }

    @Test
    fun `seeded rows carry the list content`() {
        val statements = MedicineDatabaseCallback.companionMedicineSeedStatements(
            MedicineDatabaseCallback.commonMedicinesForTests()
        )
        statements.forEach { db.execSQL(it) }
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT dosage, category FROM medicines WHERE searchName = 'panadol'",
            ).use { rs ->
                assertTrue("panadol missing", rs.next())
                assertEquals("500mg tablet", rs.getString(1))
                assertEquals("Pain relief", rs.getString(2))
            }
        }
    }

    @Test
    fun `apostrophes escape without breaking the statement`() {
        val tricky = MedicineEntity(
            name = "Farmer's Ointment",
            genericName = "Petroleum jelly",
            dosage = "50g tin",
            frequency = "Apply as needed",
            warnings = "Don't ingest",
            category = "Skin",
            searchName = "farmers ointment"
        )
        val statements = MedicineDatabaseCallback.companionMedicineSeedStatements(listOf(tricky))
        assertEquals(1, statements.size)
        assertTrue(statements[0].contains("Farmer''s Ointment"))
        statements.forEach { db.execSQL(it) }
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name, warnings FROM medicines WHERE searchName = 'farmers ointment'",
            ).use { rs ->
                assertTrue("escaped row missing", rs.next())
                assertEquals("Farmer's Ointment", rs.getString(1))
                assertEquals("Don't ingest", rs.getString(2))
            }
        }
    }
}
