package com.vyze.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Vyze's Adaptive Personal Intelligence system.
 *
 * Tracks two categories of memory:
 * 1. **User Preferences** — brevity level, priority focus areas, TTS preferences
 * 2. **Visual Environment History** — recent scene descriptions, frequently seen objects
 *
 * Each memory entry has a [category] key, a [key] for specific lookup, a [value]
 * payload, and a [timestamp] for staleness/pruning.
 *
 * ## Usage
 * - Preferences are read at VLM prompt construction time to personalize responses.
 * - Environment history accumulates scene context for richer, personalized descriptions.
 */
@Entity(
    tableName = "vyze_memory",
    // Audit P2 #18: upsert(REPLACE) conflicts only on the id PK — fixed-key
    // writes (preferences) piled duplicates because no unique key existed.
    // The (category, key) unique index makes REPLACE honor the KDoc's
    // "replaces on category+key conflict" contract. Added in DB v7.
    indices = [Index(value = ["category", "key"], unique = true)]
)
data class VyzeMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Memory category: "preference", "environment", "interaction". */
    val category: String,

    /** Specific key within the category (e.g., "brevity", "priority_objects", "last_scene"). */
    val key: String,

    /** Serialized value payload (JSON string or plain text). */
    val value: String,

    /** Timestamp when this memory was created/updated (System.currentTimeMillis). */
    val timestamp: Long = System.currentTimeMillis(),

    /** Optional metadata (e.g., confidence, source). */
    val metadata: String = ""
)
