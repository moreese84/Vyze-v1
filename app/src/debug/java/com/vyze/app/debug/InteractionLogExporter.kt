package com.vyze.app.debug

import android.content.Context
import android.util.Log
import com.vyze.app.data.InteractionLogRow
import com.vyze.app.data.VyzeDatabase
import java.io.File

/**
 * DEBUG-ONLY interaction export (Phase 1 of the Jev distillation pipeline).
 *
 * This class lives in the `src/debug` source set: the RELEASE VARIANT NEVER
 * COMPILES IT. There is no flag, no dead code, no reflection — the shipped
 * release APK contains neither this exporter, its receiver, nor the
 * INTERNET/storage code paths it uses. The offline guarantee is structural.
 *
 * Purpose: write recorded (query, answer) transcripts to a JSONL file the
 * developer pulls via `adb pull` and feeds into tools/jev_harness for the
 * Jev audit pass (echo rate / language-mirror compliance / relevance).
 *
 * Trigger (from a development machine, debug build installed):
 *
 *     adb shell am broadcast \
 *         -n com.vyze.app/.debug.InteractionLogExportReceiver
 *
 * Output: <external-files-dir>/jev_export/interactions_<epoch>.jsonl
 * (app-scoped external storage — `adb pull` works, no storage permission).
 */
object InteractionLogExporter {

    private const val TAG = "InteractionLogExporter"

    /** Dialogue adjacency window for threading `previous` (2 minutes). */
    private const val ADJACENCY_WINDOW_MS = 2 * 60_000L

    /** Cap on exported rows — the interaction table itself prunes at 1000. */
    private const val MAX_ROWS = 2_000

    /**
     * Export all recorded speech interactions to a JSONL file.
     *
     * @return the written file, or null when there was nothing to export
     *   (or the write failed — reason is logged either way).
     */
    suspend fun export(context: Context): File? {
        val db = VyzeDatabase.getInstance(context)
        val rows = collectRows(db)
        if (rows.isEmpty()) return null

        val dir = File(context.getExternalFilesDir(null), "jev_export")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Export: cannot create ${dir.absolutePath}")
            return null
        }
        val outFile = File(dir, "interactions_${System.currentTimeMillis()}.jsonl")
        return writeRows(rows, outFile)
    }

    /**
     * Collect + thread all exportable rows (SHARED pipeline). Both the
     * app-scoped export (adb pull) and the Downloads export (USB transfer)
     * build rows through here, so the two outputs are always identical
     * apart from their location.
     */
    suspend fun collectRows(db: VyzeDatabase): List<InteractionLogRow> {
        // Source 1: interaction_records (camera/VLM lane). The stored
        // `prompt` is the FULL built prompt; only rows whose built prompt
        // carries the raw spoken query are usable, so marker-less rows
        // (gesture scene-describes) are skipped deliberately.
        val records = db.interactionDao().getAll()

        // Source 2: vyze_memory interaction rows (text-only lane stores
        // the RAW query in metadata, answer in value).
        val memoryRows = db.memoryDao().getAllByCategory("interaction")

        data class Stamp(val ts: Long, val row: InteractionLogRow)

        val stamped = ArrayList<Stamp>(records.size + memoryRows.size)

        for (r in records) {
            val query = InteractionLogRow.extractQuery(r.prompt) ?: continue
            if (r.output.isBlank()) continue
            stamped += Stamp(
                r.timestamp,
                InteractionLogRow(
                    id = "ir_${r.id}",
                    lang = InteractionLogRow.inferLanguage(query),
                    query = query,
                    answer = r.output,
                    previous = null, // threaded below
                    ts = r.timestamp,
                    source = "interaction_records",
                    lane = InteractionLogRow.laneOf(query),
                ),
            )
        }
        for (m in memoryRows) {
            if (m.metadata.isBlank() || m.value.isBlank()) continue
            stamped += Stamp(
                m.timestamp,
                InteractionLogRow(
                    id = "vm_${m.id}",
                    lang = InteractionLogRow.inferLanguage(m.metadata),
                    query = m.metadata,
                    answer = m.value,
                    previous = null, // threaded below
                    ts = m.timestamp,
                    source = "vyze_memory",
                    lane = InteractionLogRow.laneOf(m.metadata),
                ),
            )
        }

        if (stamped.isEmpty()) {
            Log.i(TAG, "Export: no speech interactions recorded yet — nothing to write")
            return emptyList()
        }

        // Newest-first sources → chronological order, then thread dialogue
        // adjacency: a query spoken within ADJACENCY_WINDOW_MS of the
        // previous exported query carries it as `previous` (follow-up
        // context the audit and future distillation need).
        val sorted = stamped.sortedBy { it.ts }.take(MAX_ROWS)
        var lastTs = Long.MIN_VALUE
        var lastQuery: String? = null
        return sorted.map { s ->
            val adjacent = lastQuery != null && (s.ts - lastTs) in 1..ADJACENCY_WINDOW_MS
            val row = if (adjacent) s.row.copy(previous = lastQuery) else s.row
            lastTs = s.ts
            lastQuery = s.row.query
            row
        }
    }

    /** Write rows to a file — shared writer for both export targets. */
    private fun writeRows(rows: List<InteractionLogRow>, outFile: File): File? = try {
        outFile.bufferedWriter(Charsets.UTF_8).use { w ->
            for (row in rows) {
                w.write(row.toJson())
                w.write("\n")
            }
        }
        Log.i(TAG, "Export: wrote ${rows.size} rows → ${outFile.absolutePath}")
        outFile
    } catch (t: Throwable) {
        Log.w(TAG, "Export: write failed: ${t.message}")
        null
    }
}
