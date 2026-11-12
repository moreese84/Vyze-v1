package com.vyze.app.memory
import com.vyze.app.vision.EmbeddingEngine

import android.graphics.Bitmap
import android.util.Log
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.InteractionRecord
import com.vyze.app.data.SuspectMarker
import com.vyze.app.util.StorageRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Vyze's adaptive intelligence memory system.
 *
 * Orchestrates:
 * 1. **Storing** interactions — generates image embedding, serializes, persists to Room
 * 2. **Retrieving** similar past interactions — cosine similarity search over stored embeddings
 * 3. **Pruning** — keeps storage bounded by discarding oldest records
 *
 * ## Thread Safety
 * All operations run on [Dispatchers.IO]. The repository holds no mutable state
 * beyond the DAO reference, so it is safe to use from any coroutine scope.
 *
 * ## Performance
 * - Embedding generation: ~1ms (16×16 grayscale, pure CPU)
 * - Similarity search: <10ms for 500 records (linear scan, in-memory)
 * - Room writes: <5ms (single INSERT on indexed table)
 * - All off main thread — zero UI impact
 *
 * ## Usage
 * ```kotlin
 * val repo = MemoryRepository(interactionDao)
 *
 * // After inference:
 * repo.storeInteraction(bitmap, prompt, output)
 *
 * // Before next inference:
 * val similar = repo.findSimilar(bitmap, topK = 3)
 * ```
 */
class MemoryRepository(private val interactionDao: InteractionDao) {

    private val tag = "MemoryRepository"

    /**
     * Store a completed interaction with its image embedding.
     *
     * Generates a 256-float perceptual embedding from the camera frame,
     * serializes it, and persists the full interaction record to Room.
     *
     * Automatically prunes excess records (keeps most recent 1000).
     *
     * @param bitmap   The camera frame used for this interaction
     * @param prompt   The prompt sent to the VLM
     * @param output   The VLM-generated response
     * @param feedback Optional user feedback (edited text, preference tags)
     * @param tags     Optional categorical tags (e.g., "navigation", "text_reading")
     */
    suspend fun storeInteraction(
        bitmap: Bitmap,
        prompt: String,
        output: String,
        feedback: String = "",
        tags: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // 1. Generate lightweight embedding from the camera frame
            val embedding = EmbeddingEngine.generateEmbedding(bitmap)
            val serializedEmbedding = InteractionRecord.serializeEmbedding(embedding)

            // 2. Persist to Room
            val record = InteractionRecord(
                imageEmbedding = serializedEmbedding,
                prompt = StorageRedaction.sanitizeForStorage(prompt, StorageRedaction.STORED_PROMPT_MAX_CHARS),
                output = StorageRedaction.sanitizeForStorage(output, StorageRedaction.STORED_TEXT_MAX_CHARS),
                feedback = feedback,
                tags = tags
            )
            interactionDao.insert(record)

            // 3. Prune excess records (keep most recent 1000)
            val count = interactionDao.getCount()
            if (count > MAX_INTERACTIONS) {
                interactionDao.pruneExcess(MAX_INTERACTIONS)
                Log.d(tag, "Pruned interaction records: $count → $MAX_INTERACTIONS")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(tag, "Stored interaction #${record.id} in ${elapsed}ms " +
                "(embedding: ${embedding.size} floats)")

        } catch (e: Throwable) {
            Log.e(tag, "Failed to store interaction: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Find the top-K most visually similar past interactions.
     *
     * Loads all stored embeddings into memory, computes cosine similarity
     * against the query bitmap's embedding, and returns matches above the
     * similarity threshold.
     *
     * This runs entirely on Dispatchers.IO — no UI thread impact.
     *
     * @param bitmap   The current camera frame to compare against
     * @param topK     Maximum number of similar interactions to return (default: 5)
     * @param minSim   Minimum similarity score to include (default: 0.3)
     * @return List of similar interactions with scores, most similar first
     */
    suspend fun findSimilar(
        bitmap: Bitmap,
        topK: Int = 5,
        minSim: Float = 0.3f
    ): List<SimilarInteraction> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // 1. Generate query embedding
            val queryEmbedding = EmbeddingEngine.generateEmbedding(bitmap)

            // 2. Load all stored interactions (bounded by MAX_INTERACTIONS),
            //    excluding suspect-marked rows (pre-labeled FAILURE evidence)
            //    and anchor-era parrot rows (provable template output such as
            //    the bracket echo records 536/537, which carry EMPTY
            //    feedback — marks alone cannot catch them until the startup
            //    purge lands, so the output is checked directly here),
            //    and canned refusals ("Text is unclear." — zero scene fact;
            //    injecting one re-anchored the next answer on the refusal,
            //    device 10:19:45 → 10:19:55).
            //    None may anchor a future answer via "Prior scene memory"
            //    injection (memory-poisoning guard, 2026-10-20/21).
            val allRecent = interactionDao.getRecent(MAX_INTERACTIONS)
            val candidates = allRecent.filterNot {
                SuspectMarker.hasSuspectMark(it.feedback) ||
                    AnchorEraParrot.isAnchorEraParrot(it.output) ||
                    NonAnswerRefusal.isNonAnswer(it.output)
            }
            if (candidates.size < allRecent.size) {
                Log.d(tag, "Excluded ${allRecent.size - candidates.size} suspect-marked " +
                    "records from similarity candidates")
            }
            if (candidates.isEmpty()) {
                Log.d(tag, "No stored interactions for similarity search")
                return@withContext emptyList()
            }

            // 3. Find top-K matches via cosine similarity
            val matches = EmbeddingEngine.findTopK(
                queryEmbedding = queryEmbedding,
                candidates = candidates,
                topK = topK,
                minSimilarity = minSim
            )

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(tag, "Similarity search: ${candidates.size} candidates → " +
                "${matches.size} matches in ${elapsed}ms")

            matches.map { (record, score) ->
                SimilarInteraction(
                    record = record,
                    similarityScore = score
                )
            }

        } catch (e: Throwable) {
            Log.e(tag, "Similarity search failed: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Text-based recall — most recent interactions mentioning [query] terms.
     * Serves spoken recall queries ("where are my keys?") that arrive with NO
     * camera frame, where visual similarity search cannot run. Escapes SQL
     * LIKE wildcards in the user's words before delegating to the DAO.
     *
     * @param query  Raw user query text (kept verbatim; LIKE wildcards escaped)
     * @param limit  Maximum records to return (default: 5)
     * @return Most recent matching records, newest first — empty on any failure
     */
    suspend fun recallByText(
        query: String,
        limit: Int = 5
    ): List<InteractionRecord> = withContext(Dispatchers.IO) {
        try {
            val terms = companionEscapeLikeTerms(query)
            if (terms.isBlank()) {
                emptyList()
            } else {
                // Same memory-poisoning guard as findSimilar: suspect rows are
                // failure evidence, parrot rows are provable template output,
                // and refusals answer nothing — a recalled non-answer spoken
                // back as "you asked where your keys are — Text is unclear."
                // is the same loop one channel over.
                interactionDao.searchRecentByPromptAndOutput(terms, limit)
                    .filterNot {
                        SuspectMarker.hasSuspectMark(it.feedback) ||
                            AnchorEraParrot.isAnchorEraParrot(it.output) ||
                            NonAnswerRefusal.isNonAnswer(it.output)
                    }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Text recall failed: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * One-time purge of ANCHOR-ERA PARROT records (2026-10-20).
     *
     * When the prompt still carried concrete example objects, the model
     * stored parroted completions ("That is a dark blue shirt.") as ordinary
     * interaction records with EMPTY feedback — so neither the suspect-mark
     * filter nor age alone ever removes them, yet visual similarity keeps
     * re-injecting them as "Prior scene memory" (one such row outlived the
     * prompt fix by a hop through this database and bled "dark-colored" into
     * a fresh answer on 2026-10-20).
     *
     * [AnchorEraParrot] matches the exact phrases the old prompt shipped as
     * inline examples — provably template output, never scene description —
     * so deletion loses nothing usable. Idempotent; runs once per process
     * start from the controller's init block.
     *
     * @return number of poisoned records deleted.
     */
    suspend fun purgeAnchorEraParrots(): Int = withContext(Dispatchers.IO) {
        try {
            val poisoned = interactionDao.getAll()
                .filter { AnchorEraParrot.isAnchorEraParrot(it.output) }
            if (poisoned.isNotEmpty()) {
                // Chunked: one IN() per 500 ids (P3 #92 — legacy SQLite
                // caps variables at 999; the empty case yields no chunks).
                for (chunk in companionDeleteIdChunks(poisoned.map { it.id })) {
                    interactionDao.deleteByIds(chunk)
                }
                Log.d(tag, "Purged ${poisoned.size} anchor-era parrot records " +
                    "(ids ${poisoned.joinToString(",") { it.id.toString() }.take(120)})")
            }
            poisoned.size
        } catch (e: Throwable) {
            Log.e(tag, "Anchor-era parrot purge failed: ${e.javaClass.simpleName}: ${e.message}", e)
            0
        }
    }

    /**
     * Get recent interactions for context (non-similarity-based).
     * Useful for providing spatial continuity even without visual matching.
     */
    suspend fun getRecentInteractions(limit: Int = 5): List<InteractionRecord> =
        withContext(Dispatchers.IO) {
            try {
                interactionDao.getRecent(limit)
            } catch (e: Throwable) {
                Log.e(tag, "Failed to get recent interactions: ${e.message}")
                emptyList()
            }
        }

    /**
     * Get the total number of stored interactions.
     */
    suspend fun getInteractionCount(): Int = withContext(Dispatchers.IO) {
        try {
            interactionDao.getCount()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to get interaction count: ${e.message}")
            0
        }
    }

    companion object {
        /** Maximum interaction records to keep in the database. */
        private const val MAX_INTERACTIONS = 1000

        /**
         * Prepare a raw recall query for the DAO's LIKE match (audit P2
         * #19): trim, cap at 100 chars, backslash-escape LIKE wildcards
         * (backslash FIRST so later passes keep it single). The DAO
         * declares ESCAPE '\\' — the two halves only work together.
         * Pure — JVM-tested in MemoryRepositoryTest.
         */
        internal fun companionEscapeLikeTerms(query: String): String =
            query
                .trim()
                .take(100)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

        /**
         * IN-list chunk bound (audit P3 #92): legacy SQLite (API < 30,
         * minSdk 26) allows 999 variables per statement; half that leaves
         * headroom no matter what else shares the query.
         */
        private const val DELETE_IDS_CHUNK_SIZE = 500

        /**
         * Split [ids] into IN-safe chunks (audit P3 #92): empty in → no
         * chunks (Room would emit `IN ()`, a SQLite syntax error); big
         * purges in → 500-id chunks (the table holds 1000 rows, so one
         * unchunked full-table purge dies on the legacy 999-variable
         * limit). Pure — JVM-tested in MemoryRepositoryTest.
         */
        internal fun companionDeleteIdChunks(ids: List<Long>): List<List<Long>> =
            if (ids.isEmpty()) emptyList() else ids.chunked(DELETE_IDS_CHUNK_SIZE)
    }
}

/**
 * A past interaction with its similarity score relative to the current frame.
 *
 * @param record           The stored interaction record
 * @param similarityScore  Cosine similarity score (0.0–1.0, higher = more similar)
 */
data class SimilarInteraction(
    val record: InteractionRecord,
    val similarityScore: Float
)
