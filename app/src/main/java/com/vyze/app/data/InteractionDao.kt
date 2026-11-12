package com.vyze.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [InteractionRecord].
 *
 * All operations are suspend functions designed to run on Dispatchers.IO.
 * The similarity search is NOT done in SQL — it's performed in Kotlin by
 * loading embeddings into memory and computing cosine similarity.
 */
@Dao
interface InteractionDao {

    /** Insert a new interaction record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: InteractionRecord): Long

    /** Get all interaction records (for in-memory similarity search). */
    @Query("SELECT * FROM interaction_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<InteractionRecord>

    /** Get the most recent N interaction records. */
    @Query("SELECT * FROM interaction_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<InteractionRecord>

    /** Get interaction records matching a tag. */
    @Query("SELECT * FROM interaction_records WHERE tags LIKE '%' || :tag || '%' ORDER BY timestamp DESC")
    suspend fun getByTag(tag: String): List<InteractionRecord>

    /**
     * Text-based recall: most recent records whose PROMPT OR OUTPUT mentions
     * the given terms (LIKE — no FTS available without a schema migration).
     * Backs spoken recall queries ("where are my keys?") with no camera frame.
     * Callers pass backslash-escaped terms (see
     * MemoryRepository.companionEscapeLikeTerms) — the ESCAPE clause below
     * is what makes that escaping MEAN anything (audit P2 #19: without it
     * SQLite read the backslashes literally and any query containing % or _
     * matched nothing).
     */
    @Query("SELECT * FROM interaction_records WHERE prompt LIKE '%' || :terms || '%' ESCAPE '\\' OR output LIKE '%' || :terms || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchRecentByPromptAndOutput(terms: String, limit: Int = 5): List<InteractionRecord>

    /** Get total number of stored interactions. */
    @Query("SELECT COUNT(*) FROM interaction_records")
    suspend fun getCount(): Int

    /** Delete the oldest records when exceeding capacity. */
    @Query("DELETE FROM interaction_records WHERE id NOT IN (SELECT id FROM interaction_records ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneExcess(keepCount: Int = 1000)

    /** Delete all interaction records. */
    @Query("DELETE FROM interaction_records")
    suspend fun deleteAll()

    /**
     * Delete the given records by id — used by the one-time anchor-era
     * parrot purge ([com.vyze.app.memory.MemoryRepository.purgeAnchorEraParrots]).
     */
    @Query("DELETE FROM interaction_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
