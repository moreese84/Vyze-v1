package com.vyze.app.speech
import com.vyze.app.util.CrashLogFile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voice-driven bug report manager for Vyze.
 *
 * ## Flow
 * 1. User says "I want to make a report" (multilingual trigger)
 * 2. App enters REPORTING mode — "Please describe your issue"
 * 3. User speaks their report
 * 4. Report saved to local file with device metadata
 * 5. Email intent launched with pre-filled report → user taps Send
 *
 * ## Privacy
 * - Reports are saved locally first (never auto-uploaded)
 * - Email is sent via Android's email client (user confirms send)
 * - Only device metadata is included (no images, no model data)
 */
class ReportManager(private val context: Context) {

    companion object {
        private const val TAG = "ReportManager"

        /** Official email address for bug reports. */
        const val REPORTS_EMAIL = "mauricedavidalam@gmail.com"

        /**
         * App-private reports directory (audit P1 #5, 2026-11-09): reports
         * used to land in public Downloads/Vyze_Reports, world-readable.
         * Pure java.io — JVM-tested in ReportManagerTest.
         */
        fun reportsDirectory(filesDir: File): File {
            val dir = File(filesDir, "reports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * Latin trigger phrases — WORD-BOUNDARY matched (audit P3 #86).
         * Bare contains() stole reporter-class embeddings ("reporter",
         * "unreported") into report mode. Inflections and Malay affixed
         * forms are explicit: boundary matching no longer catches
         * "reporting" as a substring of "report", nor "laporan"/"melapor"
         * as substrings of "lapor".
         */
        private val TRIGGER_KEYWORDS_LATIN = listOf(
            // English
            "report", "reports", "reported", "reporting",
            "bug report", "make a report", "file a report",
            "report a bug", "report an issue", "report a problem",
            "i want to report", "i want to make a report",
            "i need to report", "i need to make a report",
            // Malay
            "lapor", "laporkan", "laporan", "melapor", "melaporkan",
            "buat laporan", "nak lapor",
            "saya nak buat laporan", "saya ingin melapor",
            "lapor bug", "lapor masalah", "aduan",
        )

        /** Chinese triggers — substring matched (no word boundaries). */
        private val TRIGGER_KEYWORDS_CJK = listOf(
            "报告", "提交报告", "我要报告", "我想报告",
            "反馈", "问题反馈", "提交反馈"
        )

        private val TRIGGER_LATIN_REGEX =
            Regex("\\b(${TRIGGER_KEYWORDS_LATIN.joinToString("|")})\\b")

        /**
         * Pure trigger check (audit P3 #86): latin phrases are
         * word-boundary matched, Chinese substring matched. JVM-tested in
         * ReportManagerTest (the instance method delegates here so the
         * rule pins without a Context).
         */
        internal fun companionIsReportTrigger(spokenText: String): Boolean {
            val lower = spokenText.lowercase().trim()
            return TRIGGER_LATIN_REGEX.containsMatchIn(lower) ||
                TRIGGER_KEYWORDS_CJK.any { lower.contains(it) }
        }
    }

    /**
     * Check if the spoken text contains a report trigger phrase.
     * Case-insensitive, matches any language.
     *
     * @param spokenText The transcribed speech from SpeechRecognizer
     * @return true if the text contains a report trigger keyword
     */
    fun isReportTrigger(spokenText: String): Boolean =
        companionIsReportTrigger(spokenText)

    /**
     * Save a bug report to a local text file.
     *
     * File location: app-private files/reports/ (moved out of public
     * Downloads/Vyze_Reports in 2026-11-09, audit P1 #5 — dictated
     * report text is user content and must not be world-readable).
     * File name: vyze_report_YYYY-MM-DD_HH-mm-ss.txt
     *
     * The file contains:
     * - Timestamp
     * - Device info (model, manufacturer, Android version)
     * - App version (from BuildConfig if available, or package version)
     * - User's spoken report text
     *
     * @param reportText The user's spoken bug report
     * @return The saved file, or null on failure
     */
    fun saveReport(reportText: String): File? {
        return try {
            val reportsDir = reportsDirectory(context.filesDir)

            // Generate timestamped filename
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val reportFile = File(reportsDir, "vyze_report_$timestamp.txt")

            // Build report content with device metadata
            val reportContent = buildString {
                appendLine("═══════════════════════════════════════════")
                appendLine("  VYZE BUG REPORT")
                appendLine("═══════════════════════════════════════════")
                appendLine()
                appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine()
                appendLine("── Device Info ──")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.DEVICE}")
                appendLine("Product: ${Build.PRODUCT}")
                appendLine()
                appendLine("── App Info ──")
                appendLine("App: Vyze")
                appendLine("Version: ${getAppVersion()}")
                appendLine()
                appendLine("── User Report ──")
                appendLine(reportText.trim())
                appendLine()
                appendLine("═══════════════════════════════════════════")
            }

            reportFile.writeText(reportContent)
            Log.i(TAG, "Report saved: ${reportFile.absolutePath} (${reportContent.length} chars)")
            CrashLogFile.log(TAG, "Report saved: ${reportFile.name}")
            reportFile

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save report: ${e.message}", e)
            CrashLogFile.logError(TAG, "Failed to save report: ${e.message}", e)
            null
        }
    }

    /**
     * Launch email intent with the report pre-filled.
     * Uses Android's native email client — user confirms send.
     *
     * This is a one-tap flow: the email is pre-addressed, pre-subjected,
     * and pre-body'd. User just taps Send.
     *
     * @param reportFile The saved report file
     * @return true if the email intent was launched
     */
    fun sendReportEmail(reportFile: File): Boolean {
        return try {
            val subject = "Vyze Bug Report — ${Build.MANUFACTURER} ${Build.MODEL}"
            val body = buildString {
                appendLine("Hi Vyze Team,")
                appendLine()
                appendLine("I'm reporting an issue with the Vyze app.")
                appendLine()
                appendLine("Please see the attached report file for device details and description.")
                appendLine()
                appendLine("── Report Summary ──")
                appendLine(reportFile.readText())
            }

            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORTS_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Verify there's an email client available
            if (emailIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(emailIntent)
                Log.i(TAG, "Email intent launched → $REPORTS_EMAIL")
                CrashLogFile.log(TAG, "Email intent launched for report: ${reportFile.name}")
                true
            } else {
                Log.w(TAG, "No email client found — report saved but not sent")
                false
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch email intent: ${e.message}", e)
            CrashLogFile.logError(TAG, "Failed to launch email: ${e.message}", e)
            false
        }
    }

    /**
     * Get the app version string from the package manager.
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Throwable) {
            "unknown"
        }
    }
}
