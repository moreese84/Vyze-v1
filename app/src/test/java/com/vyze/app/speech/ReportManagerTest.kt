package com.vyze.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for private report storage (whole-app audit P1 #5,
 * 2026-11-09).
 *
 * Bug reports (user-dictated text + device metadata) were written to
 * public Downloads/Vyze_Reports, world-readable. Reports now live in
 * app-private storage; the email flow is unaffected (it inlines the
 * file into the message body, never attaches a public URI).
 */
class ReportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `reports directory is a private child named reports`() {
        val filesDir = tempFolder.newFolder("files")
        val dir = ReportManager.reportsDirectory(filesDir)
        assertEquals(File(filesDir, "reports"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `reports directory resolves idempotently`() {
        val filesDir = tempFolder.newFolder("files")
        val first = ReportManager.reportsDirectory(filesDir)
        val second = ReportManager.reportsDirectory(filesDir)
        assertEquals(first, second)
        assertTrue(second.isDirectory)
    }

    @Test
    fun `genuine triggers still fire in all three languages`() {
        // Whole-app audit P3 #86 (2026-11-09): latin triggers are
        // word-boundary matched now; inflections and Malay affixed
        // forms are explicit keywords so they survive the switch.
        for (q in listOf(
            "i want to make a report",
            "file a report please",
            "i'm reporting a bug",
            "the app reported an error",
            "saya nak buat laporan",
            "hantar laporan cepat",
            "saya ingin melapor",
            "buat laporan",
            "aduan kerosakan",
            "我要报告",
            "问题反馈",
        )) {
            assertTrue(q, ReportManager.companionIsReportTrigger(q))
        }
    }

    @Test
    fun `report embedded in other words does not trigger`() {
        // Bare contains("report") stole reporter-class embeddings into
        // report mode. ("airport" never actually matched — it has no
        // "report" substring — but it pins the boundary anyway.)
        for (q in listOf(
            "the airport is closed",
            "he works as a reporter",
            "the reporters are here",
            "this was unreported",
            "what is this",
            "read the label",
        )) {
            assertFalse(q, ReportManager.companionIsReportTrigger(q))
        }
    }
}
