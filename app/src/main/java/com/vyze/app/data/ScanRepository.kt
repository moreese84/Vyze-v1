package com.vyze.app.data

import android.content.Context
import com.vyze.app.util.StorageRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository that abstracts access to the scan history database.
 *
 * All write operations run on [Dispatchers.IO] to avoid blocking the main thread.
 * Read operations that return [Flow] are already safe; suspend functions also
 * run on IO.
 */
class ScanRepository(context: Context) {

    private val scanDao: ScanDao = VyzeDatabase.getInstance(context).scanDao()

    // ── Write ─────────────────────────────────────────────────────────

    /** Saves a scan result. Returns the generated row ID. */
    suspend fun saveScan(type: String, content: String, metadata: String = ""): Long {
        return withContext(Dispatchers.IO) {
            scanDao.insertScan(
                ScanEntity(
                    type = type,
                    content = StorageRedaction.sanitizeForStorage(content, StorageRedaction.STORED_SCAN_MAX_CHARS),
                    metadata = StorageRedaction.sanitizeForStorage(metadata, StorageRedaction.STORED_META_MAX_CHARS)
                )
            )
        }
    }

    /** Saves an OCR scan result. */
    suspend fun saveOcrScan(text: String): Long {
        return saveScan(type = TYPE_OCR, content = text)
    }

    /** Saves a barcode/QR scan result. */
    suspend fun saveBarcodeScan(content: String, format: String = ""): Long {
        return saveScan(type = TYPE_BARCODE, content = content, metadata = format)
    }

    /** Saves a currency detection result. */
    suspend fun saveCurrencyScan(denomination: String): Long {
        return saveScan(type = TYPE_CURRENCY, content = denomination)
    }

    /** Saves a bank card identification result. */
    suspend fun saveBankCardScan(cardInfo: String): Long {
        return saveScan(type = TYPE_BANK_CARD, content = cardInfo)
    }

    /** Saves a color analysis result. */
    suspend fun saveColorScan(colorName: String): Long {
        return saveScan(type = TYPE_COLOR, content = colorName)
    }

    /** Saves a scene summary. */
    suspend fun saveSceneScan(summary: String): Long {
        return saveScan(type = TYPE_SCENE, content = summary)
    }

    // ── Read ──────────────────────────────────────────────────────────

    /** Get the most recent N scans. */
    suspend fun getRecentScans(limit: Int = 3): List<ScanEntity> {
        return withContext(Dispatchers.IO) {
            scanDao.getRecentScans(limit)
        }
    }

    /** Observe recent scans as a reactive Flow. */
    fun observeRecentScans(limit: Int = 3): Flow<List<ScanEntity>> {
        return scanDao.observeRecentScans(limit)
    }

    /** Get scans filtered by type. */
    suspend fun getScansByType(type: String): List<ScanEntity> {
        return withContext(Dispatchers.IO) {
            scanDao.getScansByType(type)
        }
    }

    /** Get all scans. */
    suspend fun getAllScans(): List<ScanEntity> {
        return withContext(Dispatchers.IO) {
            scanDao.getAllScans()
        }
    }

    // ── Delete ────────────────────────────────────────────────────────

    /** Clear all scan history. */
    suspend fun clearAllScans() {
        withContext(Dispatchers.IO) {
            scanDao.deleteAllScans()
        }
    }

    /** Delete scans older than the specified number of days. */
    suspend fun pruneOldScans(daysOld: Int = 30) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - (daysOld * 24L * 60 * 60 * 1000)
            scanDao.deleteScansOlderThan(cutoff)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Formats the last N scans into a TTS-friendly string.
     * Example: "Last scan: 50 Ringgit note detected. Before that: Banana."
     */
    suspend fun formatRecentScansForTts(limit: Int = 3): String {
        val scans = getRecentScans(limit)
        if (scans.isEmpty()) return "No scan history available."

        return buildString {
            scans.forEachIndexed { index, scan ->
                when (index) {
                    0 -> append("Last scan: ${scan.content}.")
                    1 -> append(" Before that: ${scan.content}.")
                    else -> append(" And before that: ${scan.content}.")
                }
            }
        }
    }

    companion object {
        const val TYPE_OCR = "OCR"
        const val TYPE_BARCODE = "BARCODE"
        const val TYPE_CURRENCY = "CURRENCY"
        const val TYPE_BANK_CARD = "BANK_CARD"
        const val TYPE_COLOR = "COLOR"
        const val TYPE_FACE = "FACE"
        const val TYPE_SCENE = "SCENE"
    }
}
