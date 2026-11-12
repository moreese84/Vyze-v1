package com.vyze.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for embedding BLOB validation (whole-app audit P3 #91,
 * 2026-11-09).
 *
 * [InteractionRecord.deserializeEmbedding] never validated its input:
 * short/long bodies silently produced wrong-length vectors (or died
 * deep in the buffer read). The size contract now fails fast at the
 * boundary with a clear message — symmetric with serialize's require —
 * and the similarity search already skips failed rows per record, so a
 * corrupt BLOB degrades recall instead of crashing it.
 */
class InteractionRecordTest {

    @Test
    fun `round trip preserves all 256 floats`() {
        val original = FloatArray(InteractionRecord.EMBEDDING_SIZE) { it * 0.5f }
        val bytes = InteractionRecord.serializeEmbedding(original)
        assertEquals(InteractionRecord.EMBEDDING_SIZE * 4, bytes.size)
        assertArrayEquals(original, InteractionRecord.deserializeEmbedding(bytes), 0f)
    }

    @Test
    fun `short body fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractionRecord.deserializeEmbedding(ByteArray(10))
        }
    }

    @Test
    fun `empty body fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractionRecord.deserializeEmbedding(ByteArray(0))
        }
    }

    @Test
    fun `long body fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractionRecord.deserializeEmbedding(
                ByteArray(InteractionRecord.EMBEDDING_SIZE * 4 + 4)
            )
        }
    }
}
