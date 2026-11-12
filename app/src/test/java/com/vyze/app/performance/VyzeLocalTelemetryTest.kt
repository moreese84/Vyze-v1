package com.vyze.app.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for private telemetry storage (whole-app audit P1 #6
 * scope-down, 2026-11-09).
 *
 * Telemetry CSVs were written to public Downloads/Vyze_Telemetry. They
 * now live in app-private storage; the only remaining public-Downloads
 * touch is model-sideload READS (plus the debug-only crash-log export).
 */
class VyzeLocalTelemetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `telemetry directory is a private child named telemetry`() {
        val filesDir = tempFolder.newFolder("files")
        val dir = VyzeLocalTelemetry.telemetryDir(filesDir)
        assertEquals(File(filesDir, "telemetry"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `telemetry directory resolves idempotently`() {
        val filesDir = tempFolder.newFolder("files")
        val first = VyzeLocalTelemetry.telemetryDir(filesDir)
        val second = VyzeLocalTelemetry.telemetryDir(filesDir)
        assertEquals(first, second)
        assertTrue(second.isDirectory)
    }

    @Test
    fun `csv cells keep spaces but never break columns`() {
        // Whole-app audit P3 #63 (2026-11-09): deviceModel/socModel went
        // into CSV rows RAW — a comma/newline in a Build string shifts
        // columns (or rows). Cells keep spaces (readable corridor
        // analysis) but commas become semicolons and quotes/newlines go.
        assertEquals(
            "samsung SM-S918B",
            VyzeLocalTelemetry.companionCsvCell("samsung SM-S918B")
        )
        assertEquals("Foo; Bar", VyzeLocalTelemetry.companionCsvCell("Foo, Bar"))
        assertEquals("ab", VyzeLocalTelemetry.companionCsvCell("a\"b"))
        assertEquals("ab", VyzeLocalTelemetry.companionCsvCell("a\nb"))
        assertEquals("ab", VyzeLocalTelemetry.companionCsvCell("a\rb"))
        assertEquals("", VyzeLocalTelemetry.companionCsvCell(null))
        assertEquals("padded", VyzeLocalTelemetry.companionCsvCell("  padded  "))
        assertEquals(64, VyzeLocalTelemetry.companionCsvCell("x".repeat(100)).length)
    }

    @Test
    fun `header is needed exactly for missing or empty files`() {
        val dir = tempFolder.newFolder("tele")
        val missing = File(dir, "telemetry_2099-01-01.csv")
        val empty = File(dir, "telemetry_2099-01-02.csv").also { it.writeText("") }
        val used = File(dir, "telemetry_2099-01-03.csv").also { it.writeText("h\n1\n") }
        assertTrue(VyzeLocalTelemetry.companionNeedsHeader(missing))
        assertTrue(VyzeLocalTelemetry.companionNeedsHeader(empty))
        assertTrue(!VyzeLocalTelemetry.companionNeedsHeader(used))
    }

    @Test
    fun `retention sweep deletes only old telemetry files`() {
        val dir = tempFolder.newFolder("tele")
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val old = File(dir, "telemetry_2025-01-01.csv").also {
            it.writeText("old\n")
            it.setLastModified(now - 31 * dayMs)
        }
        val fresh = File(dir, "telemetry_2099-01-01.csv").also {
            it.writeText("fresh\n")
            it.setLastModified(now)
        }
        val foreign = File(dir, "notes.txt").also {
            it.writeText("keep me\n")
            it.setLastModified(now - 31 * dayMs)
        }
        assertEquals(1, VyzeLocalTelemetry.companionSweepOldFiles(dir, now))
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
        assertTrue(foreign.exists())
    }

    @Test(timeout = 60_000)
    fun `concurrent appends never tear lines and write one header`() {
        // The header check-then-act + append run under one lock: 8
        // threads racing the first write must still produce exactly one
        // header and 400 intact lines (no interleaved fragments).
        val file = File(tempFolder.newFolder("tele"), "telemetry_2099-06-01.csv")
        val threads = 8
        val perThread = 50
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        VyzeLocalTelemetry.companionAppendCsvLines(
                            file, listOf("t${t}-line-$i")
                        )
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertTrue("worker threw: ${failures.peek()}", failures.isEmpty())
        val lines = file.readLines()
        assertEquals(1 + threads * perThread, lines.size)
        assertEquals(1, lines.count { it.startsWith("tsMs,") })
        for (t in 0 until threads) {
            for (i in 0 until perThread) {
                assertEquals(1, lines.count { it == "t${t}-line-$i" })
            }
        }
    }
}
