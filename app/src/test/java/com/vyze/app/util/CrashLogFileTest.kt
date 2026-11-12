package com.vyze.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the crash-log privacy gates (whole-app audit P1 #1,
 * 2026-11-09).
 *
 * The crash log used to carry user content (OCR takes, transcripts,
 * prompts, answers) and was exported to public Downloads after every
 * inference. Content now logs only on debug builds ([CrashLogFile.logContent])
 * and the export is debug-gated too. Release builds keep metadata-only
 * lines (lengths, counts, lane names) and never write content anywhere.
 *
 * No other test touches [CrashLogFile]'s singleton state; every test
 * below resets the override and clears entries in a finally block.
 */
class CrashLogFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `debug override wins over the build flag`() {
        try {
            CrashLogFile.debugBuildOverride = true
            assertTrue(CrashLogFile.isDebugBuild())
            CrashLogFile.debugBuildOverride = false
            assertFalse(CrashLogFile.isDebugBuild())
        } finally {
            CrashLogFile.debugBuildOverride = null
        }
    }

    @Test
    fun `unset override defaults to release-safe`() {
        // init() is never called on JVM, so the app flag keeps its
        // false default: content must be dropped, never written.
        CrashLogFile.debugBuildOverride = null
        assertFalse(CrashLogFile.isDebugBuild())
    }

    @Test
    fun `content logs land on debug builds`() {
        try {
            CrashLogFile.debugBuildOverride = true
            CrashLogFile.debugClearEntriesForTests()
            CrashLogFile.logContent("T", "OCR result: SECRET")
            assertEquals(1, CrashLogFile.debugEntryCountForTests())
        } finally {
            CrashLogFile.debugBuildOverride = null
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `content logs are dropped on release builds`() {
        try {
            CrashLogFile.debugBuildOverride = false
            CrashLogFile.debugClearEntriesForTests()
            CrashLogFile.logContent("T", "OCR result: SECRET")
            CrashLogFile.logContent("T", "Response: SECRET")
            assertEquals(0, CrashLogFile.debugEntryCountForTests())
        } finally {
            CrashLogFile.debugBuildOverride = null
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `metadata logs land on every build`() {
        try {
            CrashLogFile.debugBuildOverride = false
            CrashLogFile.debugClearEntriesForTests()
            CrashLogFile.log("T", "OCR done: 158 chars, confidence=0.74")
            assertEquals(1, CrashLogFile.debugEntryCountForTests())
        } finally {
            CrashLogFile.debugBuildOverride = null
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test(timeout = 60_000)
    fun `concurrent formats never leak another threads value`() {
        // Whole-app audit P2 #20 (2026-11-09), deterministic half: the
        // shared SimpleDateFormat carries one mutable Calendar, so two
        // threads formatting distinct dates can read each other's value
        // (wrong-but-well-formed — a shape check alone misses it). Each
        // thread formats its own fixed dates; the independent oracle is
        // java.time. Every result must equal its own input's rendering.
        val oracle = java.time.format.DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(java.time.ZoneId.systemDefault())
        val threads = 32
        val perThread = 500
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        val date = java.util.Date(1_700_000_000_000L + t * 60_000L + i * 7L)
                        val expected = oracle.format(date.toInstant())
                        val actual = CrashLogFile.debugFormatTimestampForTests(date)
                        if (actual != expected) {
                            failures.add("t$t i$i: $actual != $expected")
                            return@Thread
                        }
                    }
                } catch (e: Throwable) {
                    failures.add("t$t threw $e")
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertTrue(
            "cross-talk: ${failures.take(3)}",
            failures.isEmpty(),
        )
    }

    @Test(timeout = 60_000)
    fun `concurrent logs keep intact timestamps`() {
        // Whole-app audit P2 #20 (2026-11-09): the shared
        // SimpleDateFormat was formatted OUTSIDE the lock from log() and
        // logError(), so concurrent callers (inference lanes, mic, TTS)
        // could corrupt each other's timestamps or throw. Timestamps are
        // now formatted with a thread-safe formatter; every entry from a
        // 32-thread hammer must keep the HH:mm:ss.SSS shape.
        val stamp = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\| ")
        val threads = 32
        val perThread = 500
        try {
            CrashLogFile.debugClearEntriesForTests()
            val start = java.util.concurrent.CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val workers = (0 until threads).map { t ->
                Thread {
                    try {
                        start.await()
                        repeat(perThread) { i ->
                            CrashLogFile.log("T$t", "msg $i")
                        }
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }.also { it.start() }
            }
            start.countDown()
            workers.forEach { it.join(30_000) }
            assertTrue(
                "worker threw: ${failures.peek()}",
                failures.isEmpty(),
            )
            // The in-memory ring caps at 200; every survivor must still
            // carry an intact timestamp.
            val entries = CrashLogFile.debugEntriesSnapshotForTests()
            assertTrue("expected survivors in the ring", entries.isNotEmpty())
            val bad = entries.filterNot { stamp.containsMatchIn(it) }
            assertTrue(
                "corrupted timestamps: ${bad.take(3)}",
                bad.isEmpty(),
            )
        } finally {
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `oversized log file rotates keeping the tail`() {
        // Whole-app audit P3 #61 (2026-11-09): MAX_ENTRIES capped the
        // in-memory ring only — the FILE grew unbounded until the next
        // init truncated it. Past the byte cap the file now rotates to
        // its tail instead of growing forever.
        val f = tempFolder.newFile("rot.log")
        f.writeText(
            (1..9000).joinToString("\n") { n ->
                "line-${n.toString().padStart(5, '0')}-padding-padding-pad"
            } + "\n"
        )
        assertTrue(f.length() > CrashLogFile.MAX_LOG_FILE_BYTES)
        try {
            CrashLogFile.debugSetLogFileForTests(f)
            CrashLogFile.log("T", "rotation-trigger")
            CrashLogFile.flush()
            assertTrue(
                "file still over cap: ${f.length()}",
                f.length() < CrashLogFile.MAX_LOG_FILE_BYTES + 4096,
            )
            val text = f.readText()
            assertTrue(text.contains("line-09000"))
            assertTrue(text.contains("rotation-trigger"))
            assertTrue(text.contains("ROTATED"))
            assertFalse(text.contains("line-00001"))
        } finally {
            CrashLogFile.debugSetLogFileForTests(null)
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `small log file appends without rotating`() {
        val f = tempFolder.newFile("small.log")
        f.writeText("--- start ---\n")
        try {
            CrashLogFile.debugSetLogFileForTests(f)
            CrashLogFile.log("T", "hello")
            CrashLogFile.flush()
            val text = f.readText()
            assertTrue(text.contains("--- start ---"))
            assertTrue(text.contains("hello"))
            assertFalse(text.contains("ROTATED"))
        } finally {
            CrashLogFile.debugSetLogFileForTests(null)
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `flush drains queued lines to the file`() {
        // Whole-app audit P3 #93 (2026-11-09): log() hands the file
        // write to a background writer — flush() is the drain barrier.
        val f = tempFolder.newFile("drain.log")
        try {
            CrashLogFile.debugSetLogFileForTests(f)
            CrashLogFile.log("T", "line one")
            CrashLogFile.log("T", "line two")
            CrashLogFile.flush()
            val text = f.readText()
            assertTrue(text.contains("line one"))
            assertTrue(text.contains("line two"))
        } finally {
            CrashLogFile.debugSetLogFileForTests(null)
            CrashLogFile.debugClearEntriesForTests()
        }
    }

    @Test
    fun `logError writes synchronously without a flush`() {
        // The crash-evidence path stays synchronous: an error line must
        // hit disk on the caller, never wait for the writer thread.
        val f = tempFolder.newFile("sync.log")
        try {
            CrashLogFile.debugSetLogFileForTests(f)
            CrashLogFile.logError("T", "fatal boom")
            assertTrue(f.readText().contains("fatal boom"))
        } finally {
            CrashLogFile.debugSetLogFileForTests(null)
            CrashLogFile.debugClearEntriesForTests()
        }
    }
}
