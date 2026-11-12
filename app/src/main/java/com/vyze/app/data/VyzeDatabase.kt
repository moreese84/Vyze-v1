package com.vyze.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vyze.app.data.InteractionRecord

/**
 * Room database for Vyze.
 *
 * Version 7 — Unique (category, key) index on `vyze_memory` (+ dedupe):
 * upsert(REPLACE) finally honors its contract (audit P2 #18).
 *
 * Version 5 — Added `interaction_records` table with image embeddings for adaptive
 * intelligence and similarity-based retrieval.
 *
 * Version 4 — Added indexes on `vyze_memory` for efficient preference
 * lookups and environment memory pruning. Removed destructive migration
 * to preserve user memory and environment context across version updates.
 *
 * ## Migration Strategy
 * Each version upgrade uses explicit SQL migrations to add columns, tables,
 * or indexes without losing existing data. The `vyze_memory` table is
 * particularly important — it stores user preferences and environment
 * history that the VLM needs for personalized responses.
 */
@Database(
    entities = [ScanEntity::class, ErrorLogEntity::class, VyzeMemoryEntity::class, InteractionRecord::class, MedicineEntity::class],
    version = 7,
    exportSchema = false
)
abstract class VyzeDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun interactionDao(): InteractionDao
    abstract fun medicineDao(): MedicineDao

    companion object {
        private const val DATABASE_NAME = "vyze_scans.db"

        @Volatile
        private var INSTANCE: VyzeDatabase? = null

        // ── Migrations ─────────────────────────────────────────────

        /**
         * Migration 2 → 3: Added `vyze_memory` table for adaptive personal intelligence.
         * This migration is safe — it only creates a new table, no existing data is touched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `vyze_memory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `metadata` TEXT NOT NULL DEFAULT ''
                    )"""
                )
            }
        }

        /**
         * Migration 3 → 4: Added performance indexes on `vyze_memory`.
         *
         * Indexes improve query speed for the most common operations:
         * - `idx_memory_category_key` — fast preference lookup by category+key
         * - `idx_memory_category_timestamp` — fast environment memory retrieval (recent first)
         * - `idx_memory_timestamp` — fast pruning of old memories
         *
         * This migration is additive only — no data is modified or deleted.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Index for fast preference lookups: memoryDao.get(category, key)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_category_key` " +
                    "ON `vyze_memory` (`category`, `key`)"
                )

                // Index for fast environment memory retrieval: memoryDao.getRecentEnvironment()
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_category_timestamp` " +
                    "ON `vyze_memory` (`category`, `timestamp`)"
                )

                // Index for fast pruning: memoryDao.pruneOlderThan()
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_timestamp` " +
                    "ON `vyze_memory` (`timestamp`)"
                )
            }
        }

        /**
         * Migration 4 → 5: Added `interaction_records` table for adaptive intelligence.
         * Stores image embeddings (BLOB), prompts, outputs, and feedback for
         * similarity-based retrieval. Includes index on timestamp for efficient
         * pruning and recency queries.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `interaction_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `image_embedding` BLOB NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `output` TEXT NOT NULL,
                        `feedback` TEXT NOT NULL DEFAULT '',
                        `timestamp` INTEGER NOT NULL,
                        `tags` TEXT NOT NULL DEFAULT ''
                    )"""
                )
                // Index for efficient pruning and recency queries
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_interaction_timestamp` " +
                    "ON `interaction_records` (`timestamp`)"
                )
                // Index for tag-based filtering
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_interaction_tags` " +
                    "ON `interaction_records` (`tags`)"
                )
            }
        }

        /**
         * Migration 5 → 6: Added `medicines` table for local drug knowledge base.
         * Pre-populated via MedicineDatabaseCallback.onCreate().
         *
         * G2 NOTE: this CREATE TABLE once ended `searchName` TEXT NOT —
         * missing NULL, a SQLite syntax error that crashed any DB ≤5
         * upgrade at first open (fresh installs took onCreate and never
         * saw it, hence no dev repro). Introduced in 11d13b5, fixed in
         * 7b99995 (2026-10-10). NOW PINNED: Migration5To6Test executes
         * every statement in migrate() on a real SQLite engine — the bug
         * cannot return silently.
         */
        /**
         * 6 → 7 (audit P2 #18): enforce the (category, key) unique index
         * the upsert KDoc always promised. Dedupe first — the missing
         * constraint let fixed-key writes pile rows, and CREATE UNIQUE
         * INDEX fails on duplicates. Keeps the latest row per key.
         * Index name matches Room's generated `index_<table>_<cols>`
         * convention so open-time schema validation passes.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM vyze_memory WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM vyze_memory GROUP BY category, `key`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_vyze_memory_category_key` " +
                        "ON vyze_memory (`category`, `key`)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `medicines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `genericName` TEXT NOT NULL,
                        `dosage` TEXT NOT NULL,
                        `frequency` TEXT NOT NULL,
                        `warnings` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `searchName` TEXT NOT NULL
                    )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_medicines_searchName` ON `medicines` (`searchName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medicines_category` ON `medicines` (`category`)"
                )
            }
        }

        /** All registered migrations, in order. Internal for the chain test. */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7
        )

        // ── Instance Creation ──────────────────────────────────────

        fun getInstance(context: Context): VyzeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VyzeDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // V1 HAS NO MIGRATION ON PURPOSE (audit P1 #8,
                    // 2026-11-09): version 1 never existed — the database
                    // was born at version 2 (dbd68e3), so there is no v1
                    // schema to migrate from. A v1-stamped file can only
                    // be foreign; wipe it instead of bricking at open.
                    // Migrations FROM 2+ stay strict (chain pinned by
                    // MigrationChainTest).
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .addCallback(MedicineDatabaseCallback.createCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
