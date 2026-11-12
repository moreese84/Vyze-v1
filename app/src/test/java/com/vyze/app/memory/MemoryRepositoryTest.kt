package com.vyze.app.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for recall-term LIKE escaping (whole-app audit P2 #19,
 * 2026-11-09).
 *
 * recallByText escaped %, _ and backslash, but the DAO query declared
 * no ESCAPE clause — SQLite read the backslashes as literal
 * characters, so any recall query containing % or _ ("99% murni")
 * silently matched nothing. The escape half is pinned here; the
 * ESCAPE '\\' clause lives in the DAO annotation (Room validates its
 * syntax at compile time; semantics are standard SQLite).
 */
class MemoryRepositoryTest {

    @Test
    fun `percent and underscore are escaped`() {
        assertEquals(
            "99\\% murni",
            MemoryRepository.companionEscapeLikeTerms("99% murni")
        )
        assertEquals(
            "model\\_x",
            MemoryRepository.companionEscapeLikeTerms("model_x")
        )
    }

    @Test
    fun `backslash escapes first so later passes keep it single`() {
        assertEquals(
            "a\\\\b",
            MemoryRepository.companionEscapeLikeTerms("a\\b")
        )
        assertEquals(
            "100\\\\\\%",
            MemoryRepository.companionEscapeLikeTerms("100\\%")
        )
    }

    @Test
    fun `plain queries pass through trimmed and capped`() {
        assertEquals(
            "where are my keys",
            MemoryRepository.companionEscapeLikeTerms("  where are my keys  ")
        )
        assertEquals(100, MemoryRepository.companionEscapeLikeTerms("k".repeat(150)).length)
        assertEquals("", MemoryRepository.companionEscapeLikeTerms("   "))
    }

    @Test
    fun `empty ids yield no chunks`() {
        // Whole-app audit P3 #92 (2026-11-09): Room expands IN(:ids)
        // inline — an empty list emits `IN ()`, a SQLite syntax error.
        assertEquals(
            emptyList<List<Long>>(),
            MemoryRepository.companionDeleteIdChunks(emptyList())
        )
    }

    @Test
    fun `ids chunk at 500 under the legacy variable limit`() {
        // Legacy SQLite (API < 30, minSdk 26) caps variables at 999 per
        // statement; the table holds up to 1000 rows, so an unchunked
        // full-table purge dies. 500 leaves headroom either way.
        val chunks = MemoryRepository.companionDeleteIdChunks((1L..1200L).toList())
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals((1L..1200L).toList(), chunks.flatten())
    }
}
